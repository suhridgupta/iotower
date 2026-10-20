# I/O Tower — PC companion (optional)

An optional Fedora-side helper (architecture.md §9). It automates `usbip attach`,
re-attaches automatically when the device resets / re-enumerates (§7), recovers
the stock `vhci-hcd` client when it wedges after an abrupt disconnect, and
detaches cleanly on exit.

**The core server works without any of this** — a manual `usbip attach` is the
baseline, and this daemon is orchestration around the stock CLI, not protocol
code. Standard library only; no third-party Python packages.

## Requirements

```bash
sudo dnf install usbip usbutils
```

## Run

```bash
python3 iotowerd.py --host <tv-ip> --busid 1-1
```

It waits for the TV to export the device, attaches it, then holds it — if the
device drops (unplug, or a G29 PC↔native mode switch re-enumeration) it
re-attaches hands-off. `Ctrl-C` detaches our vhci port and exits clean. Output
is colorized on a TTY; piping, `--no-color`, or `$NO_COLOR` turns color off.

```
--host      Android TV IP address (required)
--busid     exported busid to attach (default: 1-1)
--interval  seconds between reset-watch polls (default: 2.0)
--no-color  disable ANSI color
```

## Test

Pure-logic unit tests (the `usbip` output parsers), no TV/root/network:

```bash
python3 -m unittest discover -s companion
```

## Install as a service

```bash
sudo mkdir -p /opt/iotower && sudo cp iotowerd.py /opt/iotower/
sudo cp iotower.service /etc/systemd/system/
# edit the --host IP in the unit first, then:
sudo systemctl enable --now iotower
```

`systemctl stop` sends SIGTERM, which the daemon handles like `Ctrl-C` — it
detaches its port before exiting, so no dead vhci port is leaked.

## Status

Supervisor (§9.1) **implemented**: preflight, discover + auto-attach,
auto-reattach on reset/re-enumeration, stock-client (`vhci-hcd`) wedge recovery,
and clean teardown on SIGINT/SIGTERM. The optional authenticating **PIN/TLS
proxy (§9.2)** and the matching Android-server PIN gate are **still TODO** — so
M10 is not yet fully complete (see MILESTONES.md M10).
