#!/usr/bin/env python3
"""I/O Tower PC-side companion (optional — architecture.md §9).

Orchestration only: it shells out to the stock ``usbip`` CLI and never
implements any USB/IP protocol itself. Duties (§9.1):

  * preflight  — check ``usbip`` is present and ``vhci-hcd`` is loadable
  * discover + auto-attach the device exported by the Android server
  * auto-reattach when the device resets / re-enumerates (§7)
  * clean teardown on exit

The optional authenticating PIN/TLS proxy (§9.2) is a separate concern and is
not implemented here yet.
"""
import argparse
import subprocess
import sys
import time


def preflight() -> None:
    if subprocess.run(["which", "usbip"], capture_output=True).returncode != 0:
        sys.exit("usbip not found — install it:  sudo dnf install usbip usbutils")
    # vhci-hcd provides the in-kernel USB/IP client; loading is idempotent.
    subprocess.run(["sudo", "modprobe", "vhci-hcd"], check=False)


def list_remote(host: str) -> str:
    return subprocess.run(
        ["usbip", "list", "-r", host], capture_output=True, text=True
    ).stdout


def attach(host: str, busid: str) -> int:
    return subprocess.run(["usbip", "attach", "-r", host, "-b", busid]).returncode


def detach_all() -> None:
    # TODO: parse `usbip port` and detach our port(s) cleanly on exit (§9.1).
    pass


def supervise(host: str, busid: str, interval: float) -> None:
    """Attach, then watch and re-attach on reset (§9.1). Skeleton loop."""
    print(f"[iotowerd] supervising {host} busid={busid} (every {interval}s)")
    while True:
        # TODO: detect detach / re-enumeration (§7) and re-run attach().
        time.sleep(interval)


def main() -> None:
    p = argparse.ArgumentParser(description="I/O Tower PC companion")
    p.add_argument("--host", required=True, help="Android TV IP address")
    p.add_argument("--busid", default="1-1", help="exported busid (default: 1-1)")
    p.add_argument("--interval", type=float, default=2.0, help="poll seconds")
    args = p.parse_args()

    preflight()
    print(list_remote(args.host))
    if attach(args.host, args.busid) != 0:
        sys.exit(f"[iotowerd] initial attach of {args.busid} failed")
    try:
        supervise(args.host, args.busid, args.interval)
    except KeyboardInterrupt:
        detach_all()
        print("\n[iotowerd] stopped")


if __name__ == "__main__":
    main()
