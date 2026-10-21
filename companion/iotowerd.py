#!/usr/bin/env python3
"""I/O Tower PC-side companion (optional -- architecture.md section 9).

Orchestration only: it shells out to the stock ``usbip`` CLI and never
implements any USB/IP protocol itself. Supervisor duties (section 9.1):

  * preflight     -- check ``usbip`` is present and ``vhci-hcd`` is loadable
  * discover      -- wait for the device the Android TV exports, then attach
  * auto-reattach -- when the device resets / re-enumerates (section 7), go back
                     and re-attach automatically, hands-off at the couch
  * clean detach  -- the moment our attached device drops (a reset, or a TV-side
                     Eject), proactively ``usbip detach`` our vhci port so the
                     stock client does not wedge on the abrupt remote close,
                     then re-discover
  * wedge recovery-- reset ``vhci-hcd`` when the stock client wedges after an
                     abrupt disconnect (the documented `open vhci_driver` failure)
  * clean teardown-- detach our port(s) on exit (Ctrl-C or SIGTERM)

When run in a terminal it also keeps a single rewriting status line at the
bottom (state + device + uptime); under systemd / when piped the status line is
suppressed so the journal stays clean.

The optional authenticating PIN/TLS proxy (section 9.2), and the matching
Android-server PIN gate, are a separate concern and are **not** implemented here
yet -- see MILESTONES.md M10. Standard library only; no third-party packages.
"""
from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import sys
import time
from datetime import datetime
from typing import List, NamedTuple, Optional

USBIP_PORT = 3240  # the Android server's listener (architecture.md section 1/8)


# --------------------------------------------------------------------------- #
# Pretty terminal output (section 9 "finalize the UI"): structured, timestamped,
# state-labelled log lines, plus an optional one-line live status at the bottom
# when stdout is a TTY. ANSI color only on a TTY. Standard library only.
# --------------------------------------------------------------------------- #
class Ui:
    _CODES = {
        "reset": "\033[0m", "dim": "\033[2m", "bold": "\033[1m",
        "red": "\033[31m", "green": "\033[32m", "yellow": "\033[33m",
        "blue": "\033[34m", "cyan": "\033[36m",
    }
    # Each state gets a color so a glance at the log reads the session's phase.
    _STATE_COLOR = {
        "PREFLIGHT": "cyan", "DISCOVER": "blue", "ATTACH": "yellow",
        "ATTACHED": "green", "WATCH": "dim", "RESET": "yellow",
        "WEDGE": "red", "DETACH": "cyan", "STOP": "cyan",
    }

    def __init__(self, color: bool = True) -> None:
        self.tty = sys.stdout.isatty()
        # Honor the conventional NO_COLOR env var and a non-TTY stdout.
        self.color = color and self.tty and not os.environ.get("NO_COLOR")
        self._status = ""  # current live status-line text (TTY only)

    def _c(self, s: str, name: str) -> str:
        if not self.color:
            return s
        return f"{self._CODES.get(name, '')}{s}{self._CODES['reset']}"

    def _stamp(self) -> str:
        return self._c(datetime.now().strftime("%H:%M:%S"), "dim")

    # -- emit a log line, redrawing the live status line around it on a TTY. --- #
    def _emit(self, text: str) -> None:
        if self.tty and self._status:
            sys.stdout.write("\r\033[K")       # clear the status line
        sys.stdout.write(text + "\n")
        if self.tty and self._status:
            sys.stdout.write(self._status)     # redraw it under the new line
        sys.stdout.flush()

    def state(self, state: str, msg: str = "") -> None:
        tag = self._c(f"{state:<9}", self._STATE_COLOR.get(state, "bold"))
        self._emit(f"{self._stamp()} {tag} {msg}".rstrip())

    def info(self, msg: str) -> None:
        self._emit(f"{self._stamp()} {msg}")

    def ok(self, msg: str) -> None:
        self._emit(f"{self._stamp()} {self._c('OK', 'green')}        {msg}")

    def warn(self, msg: str) -> None:
        self._emit(f"{self._stamp()} {self._c('WARN', 'yellow')}      {msg}")

    def error(self, msg: str) -> None:
        # stderr: do not disturb the stdout status line.
        print(f"{self._stamp()} {self._c('ERROR', 'red')}     {msg}",
              file=sys.stderr, flush=True)

    # -- the live, rewriting status line (TTY only; a no-op under journald). --- #
    def status_line(self, state: str, detail: str) -> None:
        bullet = self._c("*", self._STATE_COLOR.get(state, "bold"))
        self._status = f"{bullet} {state}  {detail}"
        if self.tty:
            sys.stdout.write("\r\033[K" + self._status)
            sys.stdout.flush()

    def clear_status_line(self) -> None:
        if self.tty and self._status:
            sys.stdout.write("\r\033[K")
            sys.stdout.flush()
        self._status = ""


# --------------------------------------------------------------------------- #
# Pure parsers of stock `usbip` text output -- the only non-trivial logic, kept
# side-effect free so it is unit-testable with no TV and no root (section Test).
# --------------------------------------------------------------------------- #
# A busid is "<bus>-<port[.port...]>", e.g. 1-1 or 3-1.2 (used for discovery).
_BUSID_RE = re.compile(r"^\s*(\d+-[\d.]+):\s", re.MULTILINE)
# A `usbip port` in-use header: "Port 03: <Port in Use> at ...". This line comes
# from sysfs and is present even without root -- unlike the usbip://host/busid
# line, which needs a per-attach record file that is often unreadable (libusbip
# `read_record` errors -> "unknown host, remote port and remote busid"). So we
# never match on host/busid: we identify *our* attach by which vhci port is newly
# in use after we run `usbip attach`. Device-agnostic; no VID/PID, no record.


def parse_exported_busids(list_stdout: str) -> List[str]:
    """Busids from `usbip list -r <host>` output, in listing order."""
    return _BUSID_RE.findall(list_stdout)


def device_is_exported(list_stdout: str, busid: str) -> bool:
    """True if `busid` appears in a `usbip list -r` dump."""
    return busid in parse_exported_busids(list_stdout)


_PORT_INUSE_RE = re.compile(r"^Port\s+(\d+):\s*<Port in Use>", re.MULTILINE)


def parse_ports_in_use(port_stdout: str) -> set:
    """The set of vhci port numbers currently in use, from `usbip port`.

    Reads only the ``Port NN: <Port in Use>`` headers, so it works whether or
    not the per-attach record is readable (the libusbip `read_record` /
    "unknown host" case). Returns an empty set when nothing is attached.
    """
    return {int(m) for m in _PORT_INUSE_RE.findall(port_stdout)}


# --------------------------------------------------------------------------- #
# Thin subprocess wrappers. None raise on non-zero; the supervisor loop decides.
# --------------------------------------------------------------------------- #
class Cmd(NamedTuple):
    rc: int
    out: str
    err: str


def _run(args: List[str], timeout: Optional[float] = None) -> Cmd:
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
        return Cmd(p.returncode, p.stdout or "", p.stderr or "")
    except subprocess.TimeoutExpired as e:
        # A hung client (e.g. `usbip list -r` against the single-client TV server
        # while a device is already attached) must not block the loop. rc 124 is
        # the conventional timeout code; the caller treats it as "not reachable".
        def _txt(x):
            return x.decode(errors="replace") if isinstance(x, bytes) else (x or "")
        return Cmd(124, _txt(e.stdout), _txt(e.stderr) + f"\ntimed out after {timeout:g}s")


def list_remote(host: str) -> Cmd:
    return _run(["usbip", "list", "-r", host], timeout=10)


def attach(host: str, busid: str) -> Cmd:
    return _run(["sudo", "usbip", "attach", "-r", host, "-b", busid], timeout=20)


def detach(port: int) -> Cmd:
    return _run(["sudo", "usbip", "detach", "-p", str(port)], timeout=10)


def port_table() -> Cmd:
    # We only read the `Port NN: <Port in Use>` headers (from sysfs), so no root
    # is needed; the per-attach record (which needs root and is often unreadable)
    # is not used. Non-sudo also avoids a password re-prompt in the watch loop.
    return _run(["usbip", "port"], timeout=10)


def _looks_wedged(c: Cmd) -> bool:
    """The stock client's `open vhci_driver` failure after an abrupt drop."""
    return c.rc != 0 and "vhci" in (c.err + c.out).lower()


# --------------------------------------------------------------------------- #
# Supervisor: discover -> attach -> watch -> (clean detach on drop) -> discover,
# with wedge recovery and clean teardown of the port(s) we created.
# --------------------------------------------------------------------------- #
class Supervisor:
    def __init__(self, host: str, busid: str, interval: float, ui: Ui,
                 wedge_retries: int = 2) -> None:
        self.host = host
        self.busid = busid
        self.interval = interval
        self.ui = ui
        self.wedge_retries = wedge_retries
        self._stop = False
        self._backoff = interval            # grows on repeated attach failure
        self._attached_port: Optional[int] = None  # the vhci port we created
        self._since = 0.0                   # monotonic time of the last attach

    def request_stop(self, *_a) -> None:
        self._stop = True

    # -- vhci-hcd reset for the documented stock-client wedge (README). ------ #
    def _reset_vhci(self) -> bool:
        self.ui.state("WEDGE", "stock vhci-hcd wedged; reloading the module")
        _run(["sudo", "modprobe", "-r", "vhci-hcd"])
        c = _run(["sudo", "modprobe", "vhci-hcd"])
        if c.rc != 0:
            self.ui.warn("could not reload vhci-hcd (it may be 'in use'); "
                         "a PC reboot clears it -- continuing")
            return False
        return True

    # -- vhci ports currently in use (device-agnostic; no record needed). ---- #
    def _ports_in_use(self) -> set:
        return parse_ports_in_use(port_table().out)

    def _attached(self) -> bool:
        # We are attached iff the port we created is still in use.
        return self._attached_port is not None and self._attached_port in self._ports_in_use()

    # -- one discover+attach attempt; returns True once attached. ------------ #
    def _try_attach(self) -> bool:
        lst = list_remote(self.host)
        if _looks_wedged(lst):
            for _ in range(self.wedge_retries):
                if self._reset_vhci():
                    break
            return False
        if lst.rc != 0:
            self.ui.state("DISCOVER", f"{self.host} not reachable yet "
                          f"({lst.err.strip() or 'no route'})")
            return False
        if not device_is_exported(lst.out, self.busid):
            self.ui.state("DISCOVER",
                          f"waiting for busid {self.busid} on {self.host}")
            self.ui.status_line("DISCOVER", f"waiting for {self.busid}@{self.host}")
            return False

        before = self._ports_in_use()
        self.ui.state("ATTACH", f"{self.host} busid {self.busid}")
        at = attach(self.host, self.busid)
        if _looks_wedged(at):
            for _ in range(self.wedge_retries):
                if self._reset_vhci() and attach(self.host, self.busid).rc == 0:
                    break
        elif at.rc != 0:
            self.ui.warn(f"attach failed: {at.err.strip() or at.out.strip()}")
            return False

        # Our attach is whichever vhci port is newly in use. The port can take a
        # moment to register after `usbip attach` returns, so poll briefly.
        after = before
        new: List[int] = []
        deadline = time.monotonic() + 2.5
        while True:
            after = self._ports_in_use()
            new = sorted(after - before)
            if new or time.monotonic() >= deadline:
                break
            time.sleep(0.3)
        if new:
            self._attached_port = new[0]
        elif self._attached_port is not None and self._attached_port in after:
            pass  # re-confirming an attach we already own (e.g. a retry)
        else:
            if at.rc == 0:
                self.ui.warn("`usbip attach` returned ok but no new vhci port "
                             f"appeared (ports in use before={sorted(before)}, "
                             f"after={sorted(after)}) -- retrying. If 'after' is "
                             "non-empty, a stale attach is held; clear it with "
                             "`sudo modprobe -r vhci-hcd && sudo modprobe vhci-hcd`")
            return False

        self._since = time.monotonic()
        self.ui.state("ATTACHED",
                      f"{self.busid} on {self.host} -> vhci port {self._attached_port}")
        self.ui.info(self.ui._c(
            "the device is now local -- `lsusb` / `evtest` / Oversteer see it",
            "dim"))
        self.ui.status_line("SERVING", f"{self.busid}@{self.host}  up 00:00")
        return True

    def run(self) -> None:
        # Discover + attach, backing off while the device is absent/unreachable.
        while not self._stop:
            if self._try_attach():
                self._backoff = self.interval
                self._watch()
            else:
                self._sleep(self._backoff)
                self._backoff = min(self._backoff * 1.5, 30.0)

    # -- steady state: poll until our port drops, detach cleanly, then loop. -- #
    def _watch(self) -> None:
        self.ui.state("WATCH", f"holding {self.busid}; "
                      f"re-attach is automatic on reset (every {self.interval:g}s)")
        while not self._stop:
            self._sleep(self.interval)
            if self._stop:
                return
            if not self._attached():
                self.ui.clear_status_line()
                self.ui.state("RESET", f"{self.busid} dropped "
                              "(reset or TV-side eject) -- detaching cleanly")
                self._clean_detach()
                return
            up = int(time.monotonic() - self._since)
            self.ui.status_line("SERVING",
                                f"{self.busid}@{self.host}  up {up // 60:02d}:{up % 60:02d}")

    # -- proactively detach our vhci slot so the stock client does not wedge. - #
    def _clean_detach(self) -> None:
        if self._attached_port is not None:
            d = detach(self._attached_port)
            if d.rc == 0:
                self.ui.ok(f"detached vhci port {self._attached_port} (clean)")
            # a slot already gone is fine -- nothing to clean there.
        self._attached_port = None

    def _sleep(self, secs: float) -> None:
        # Sleep in small slices so a signal stops us within ~0.2 s.
        end = time.monotonic() + secs
        while not self._stop and time.monotonic() < end:
            time.sleep(min(0.2, max(0.0, end - time.monotonic())))

    def teardown(self) -> None:
        self.ui.clear_status_line()
        self.ui.state("DETACH", "releasing our vhci port on exit")
        if self._attached_port is None:
            self.ui.info(self.ui._c("nothing we attached; clean", "dim"))
            return
        d = detach(self._attached_port)
        if d.rc == 0:
            self.ui.ok(f"detached vhci port {self._attached_port}")
        else:
            self.ui.warn(f"detach of port {self._attached_port} failed: "
                         f"{d.err.strip() or d.out.strip()}")


def preflight(ui: Ui) -> None:
    ui.state("PREFLIGHT", "checking usbip + vhci-hcd")
    if _run(["which", "usbip"]).rc != 0:
        ui.error("usbip not found -- install it:  sudo dnf install usbip usbutils")
        sys.exit(1)
    # vhci-hcd provides the in-kernel USB/IP client; loading is idempotent.
    if _run(["sudo", "modprobe", "vhci-hcd"]).rc != 0:
        ui.warn("could not modprobe vhci-hcd now; will retry on attach")
    ui.ok("usbip present; vhci-hcd available")


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="iotowerd.py",
        description="I/O Tower PC companion: auto-attach the USB device exported "
                    "by the Android TV, auto-reattach it on reset/re-enumeration, "
                    "detach cleanly when it drops or on exit. Orchestration around "
                    "stock usbip -- no protocol code, standard library only.",
        epilog="Example:  python3 iotowerd.py --host 192.168.1.50 --busid 1-1")
    p.add_argument("--host", required=True, help="Android TV IP address")
    p.add_argument("--busid", default="1-1",
                   help="exported busid to attach (default: 1-1)")
    p.add_argument("--interval", type=float, default=2.0,
                   help="seconds between reset-watch polls (default: 2.0)")
    p.add_argument("--no-color", action="store_true",
                   help="disable ANSI color (also honored: non-TTY, $NO_COLOR)")
    return p


def main(argv: Optional[List[str]] = None) -> None:
    args = build_arg_parser().parse_args(argv)
    ui = Ui(color=not args.no_color)
    ui.info(ui._c(f"I/O Tower companion -- supervising {args.host} "
                  f"busid={args.busid}", "bold"))

    preflight(ui)
    sup = Supervisor(args.host, args.busid, args.interval, ui)
    signal.signal(signal.SIGINT, sup.request_stop)
    signal.signal(signal.SIGTERM, sup.request_stop)  # clean `systemctl stop`
    try:
        sup.run()
    finally:
        sup.teardown()
        ui.state("STOP", "companion stopped")


if __name__ == "__main__":
    main()
