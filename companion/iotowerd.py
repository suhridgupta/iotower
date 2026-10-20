#!/usr/bin/env python3
"""I/O Tower PC-side companion (optional -- architecture.md section 9).

Orchestration only: it shells out to the stock ``usbip`` CLI and never
implements any USB/IP protocol itself. Supervisor duties (section 9.1):

  * preflight     -- check ``usbip`` is present and ``vhci-hcd`` is loadable
  * discover      -- wait for the device the Android TV exports, then attach
  * auto-reattach -- when the device resets / re-enumerates (section 7), go back
                     and re-attach automatically, hands-off at the couch
  * wedge recovery-- reset ``vhci-hcd`` when the stock client wedges after an
                     abrupt disconnect (the documented `open vhci_driver` failure)
  * clean teardown-- detach our port(s) on exit (Ctrl-C or SIGTERM), so no dead
                     vhci port is leaked

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
# state-labelled lines; ANSI color only on a TTY. Standard library only.
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
        # Honor the conventional NO_COLOR env var and a non-TTY stdout.
        self.color = color and sys.stdout.isatty() and not os.environ.get("NO_COLOR")

    def _c(self, s: str, name: str) -> str:
        if not self.color:
            return s
        return f"{self._CODES.get(name, '')}{s}{self._CODES['reset']}"

    def _stamp(self) -> str:
        return self._c(datetime.now().strftime("%H:%M:%S"), "dim")

    def state(self, state: str, msg: str = "") -> None:
        tag = self._c(f"{state:<9}", self._STATE_COLOR.get(state, "bold"))
        line = f"{self._stamp()} {tag} {msg}".rstrip()
        print(line, flush=True)

    def info(self, msg: str) -> None:
        print(f"{self._stamp()} {msg}", flush=True)

    def ok(self, msg: str) -> None:
        print(f"{self._stamp()} {self._c('OK', 'green')}        {msg}", flush=True)

    def warn(self, msg: str) -> None:
        print(f"{self._stamp()} {self._c('WARN', 'yellow')}      {msg}", flush=True)

    def error(self, msg: str) -> None:
        print(f"{self._stamp()} {self._c('ERROR', 'red')}     {msg}",
              file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- #
# Pure parsers of stock `usbip` text output -- the only non-trivial logic, kept
# side-effect free so it is unit-testable with no TV and no root (section Test).
# --------------------------------------------------------------------------- #
# A busid is "<bus>-<port[.port...]>", e.g. 1-1 or 3-1.2
_BUSID_RE = re.compile(r"^\s*(\d+-[\d.]+):\s", re.MULTILINE)
# A `usbip port` remote line: "<busid> -> usbip://<host>:<port>/<busid>"
_REMOTE_RE = re.compile(r"usbip://([^:/\s]+):(\d+)/(\d+-[\d.]+)")
# A `usbip port` header line: "Port 03: <Port in Use> at ..."
_PORT_RE = re.compile(r"^Port\s+(\d+):", re.MULTILINE)


class PortRow(NamedTuple):
    port: int          # the local vhci port number (for `usbip detach -p`)
    host: str          # remote host the port is attached to
    busid: str         # remote busid


def parse_exported_busids(list_stdout: str) -> List[str]:
    """Busids from `usbip list -r <host>` output, in listing order."""
    return _BUSID_RE.findall(list_stdout)


def device_is_exported(list_stdout: str, busid: str) -> bool:
    """True if `busid` appears in a `usbip list -r` dump."""
    return busid in parse_exported_busids(list_stdout)


def parse_attached_ports(port_stdout: str, host: Optional[str] = None) -> List[PortRow]:
    """Parse `usbip port` into (port, host, busid) rows.

    Associates each ``Port NN:`` header with the ``usbip://host:port/busid``
    URL that follows it. When ``host`` is given, only rows attached to that
    host are returned -- so teardown never detaches a port the user attached by
    hand to a different server.
    """
    rows: List[PortRow] = []
    cur_port: Optional[int] = None
    for line in port_stdout.splitlines():
        m = _PORT_RE.match(line.strip()) or _PORT_RE.match(line)
        if m:
            cur_port = int(m.group(1))
            continue
        r = _REMOTE_RE.search(line)
        if r and cur_port is not None:
            row = PortRow(port=cur_port, host=r.group(1), busid=r.group(3))
            if host is None or row.host == host:
                rows.append(row)
            cur_port = None
    return rows


# --------------------------------------------------------------------------- #
# Thin subprocess wrappers. None raise on non-zero; the supervisor loop decides.
# --------------------------------------------------------------------------- #
class Cmd(NamedTuple):
    rc: int
    out: str
    err: str


def _run(args: List[str]) -> Cmd:
    p = subprocess.run(args, capture_output=True, text=True)
    return Cmd(p.returncode, p.stdout or "", p.stderr or "")


def list_remote(host: str) -> Cmd:
    return _run(["usbip", "list", "-r", host])


def attach(host: str, busid: str) -> Cmd:
    return _run(["usbip", "attach", "-r", host, "-b", busid])


def detach(port: int) -> Cmd:
    return _run(["sudo", "usbip", "detach", "-p", str(port)])


def port_table() -> Cmd:
    return _run(["usbip", "port"])


def _looks_wedged(c: Cmd) -> bool:
    """The stock client's `open vhci_driver` failure after an abrupt drop."""
    return c.rc != 0 and "vhci" in (c.err + c.out).lower()


# --------------------------------------------------------------------------- #
# Supervisor: discover -> attach -> watch -> (reset) -> discover, with wedge
# recovery and clean teardown of the port(s) we created.
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
        self._backoff = interval  # grows on repeated attach failure

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

    # -- our currently-attached port(s) for this host, if any. --------------- #
    def _our_ports(self) -> List[PortRow]:
        c = port_table()
        if _looks_wedged(c):
            return []
        return parse_attached_ports(c.out, host=self.host)

    def _attached(self) -> bool:
        return any(r.busid == self.busid for r in self._our_ports())

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
            return False

        self.ui.state("ATTACH", f"{self.host} busid {self.busid}")
        at = attach(self.host, self.busid)
        if _looks_wedged(at):
            for _ in range(self.wedge_retries):
                if self._reset_vhci() and attach(self.host, self.busid).rc == 0:
                    break
        elif at.rc != 0:
            self.ui.warn(f"attach failed: {at.err.strip() or at.out.strip()}")
            return False

        if self._attached():
            rows = [r for r in self._our_ports() if r.busid == self.busid]
            port = rows[0].port if rows else "?"
            self.ui.state("ATTACHED",
                          f"{self.busid} on {self.host} -> vhci port {port}")
            self.ui.info(self.ui._c(
                "the device is now local -- `lsusb` / `evtest` / Oversteer see it",
                "dim"))
            return True
        return False

    def run(self) -> None:
        # Discover + attach, backing off while the device is absent/unreachable.
        while not self._stop:
            if self._try_attach():
                self._backoff = self.interval
                self._watch()
            else:
                self._sleep(self._backoff)
                self._backoff = min(self._backoff * 1.5, 30.0)

    # -- steady state: poll until our port drops (reset / unplug), then loop.  #
    def _watch(self) -> None:
        self.ui.state("WATCH", f"holding {self.busid}; "
                      f"re-attach is automatic on reset (every {self.interval:g}s)")
        while not self._stop:
            self._sleep(self.interval)
            if self._stop:
                return
            if not self._attached():
                self.ui.state("RESET", f"{self.busid} dropped "
                              "(unplug or re-enumeration) -- re-discovering")
                return

    def _sleep(self, secs: float) -> None:
        # Sleep in small slices so a signal stops us within ~0.2 s.
        end = time.monotonic() + secs
        while not self._stop and time.monotonic() < end:
            time.sleep(min(0.2, max(0.0, end - time.monotonic())))

    def teardown(self) -> None:
        self.ui.state("DETACH", "releasing our vhci port(s) on exit")
        ports = [r for r in self._our_ports()]
        if not ports:
            self.ui.info(self.ui._c("nothing attached for this host; clean", "dim"))
            return
        for r in ports:
            d = detach(r.port)
            if d.rc == 0:
                self.ui.ok(f"detached vhci port {r.port} ({r.busid})")
            else:
                self.ui.warn(f"detach of port {r.port} failed: "
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
                    "and detach cleanly on exit. Orchestration around stock usbip "
                    "-- no protocol code, standard library only.",
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
