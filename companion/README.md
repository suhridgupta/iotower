# I/O Tower — PC companion (optional)

An optional Fedora-side helper (architecture.md §9). It automates
`usbip attach`, re-attaches when the device resets / re-enumerates (§7), and is
the natural home for the optional PIN/TLS proxy (§9.2).

**The core server works without any of this** — a manual `usbip attach` is the
baseline, and this daemon is orchestration around the stock CLI, not protocol
code.

## Requirements

```bash
sudo dnf install usbip usbutils
```

## Run

```bash
python3 iotowerd.py --host <tv-ip> --busid 1-1
```

## Install as a service

```bash
sudo mkdir -p /opt/iotower && sudo cp iotowerd.py /opt/iotower/
sudo cp iotower.service /etc/systemd/system/
# edit the --host IP in the unit first, then:
sudo systemctl enable --now iotower
```

Status: skeleton — preflight / list / attach wired; auto-reattach (§7) and the
PIN/TLS proxy (§9.2) are TODO.
