# testdata

Ground-truth captures for validating the protocol against reality
(architecture.md §12). Two kinds live here.

## 1. Descriptor dumps  (for M2 — the descriptor parser)

Raw configuration descriptors, exactly the bytes `getRawDescriptors()` returns,
so `DescriptorParser` tests assert a known endpoint topology.

```bash
# Find the device's sysfs node:
lsusb                                   # note the bus/device, e.g. Bus 001 Device 007
# Raw descriptor bytes (device + config descriptors, concatenated):
sudo cat /sys/bus/usb/devices/1-1/descriptors > g29-descriptors.bin
# Human-readable reference to eyeball against:
sudo lsusb -v -s 001:007 > g29-lsusb.txt
```

Capture both a **simple generic gamepad** and the **G29** (composite,
multi-interface) so the parser is tested on the easy and the hard case.

## 2. Golden wire captures  (for M1 — the codec vectors)

Byte-exact USB/IP exchanges from the reference server, so codec round-trips are
checked against real framing rather than our own assumptions.

```bash
# On a Linux box, export a real device with the STOCK reference server:
sudo modprobe usbip-host
sudo usbipd -D                          # reference USB/IP server daemon
sudo usbip bind -b 1-1                  # export the device

# Capture the negotiation + a little transfer traffic on the wire:
sudo tcpdump -i lo -w devlist-import.pcap 'tcp port 3240' &
usbip list -r 127.0.0.1                 # exercises OP_REQ_DEVLIST
sudo usbip attach -r 127.0.0.1 -b 1-1   # exercises OP_REQ_IMPORT + control transfers
sudo kill %1                            # stop tcpdump
```

Extract the TCP payloads from the `.pcap` (Wireshark "Follow TCP Stream", or
`tshark -r devlist-import.pcap -T fields -e tcp.payload`) and save the relevant
byte sequences as the expected values in the codec tests. `usbmon` +
Wireshark's usbmon capture is the alternative when you want USB-level, not
USB/IP-level, ground truth.

Keep captures small and name them by what they exercise (`devlist.bin`,
`import.bin`, `submit-control.bin`).
