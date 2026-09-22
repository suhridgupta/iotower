# I/O Tower

A minimal, **no-root USB/IP server for Android TV**. Plug a USB device into the
TV, and it appears on a remote Linux PC as if it were connected locally — so the
PC's own drivers bind to it and handle everything device-specific.

I/O Tower is a **transparent USB pass-through**: it forwards raw USB transfers and
descriptors and never interprets what it is carrying. There are no device tables,
no VID/PID logic, and no per-device branches anywhere in the code. A Logitech G29
racing wheel is the running validation target, but nothing in the design is
specific to it — a plain gamepad, and in principle any non-isochronous USB device,
works the same way.

The goal is to reproduce the useful core of VirtualHere's no-root Android server,
for a single device, using only public APIs — and to lean on the Linux kernel for
the entire client side, so you write and maintain code on one end only.

## How it works

Three constraints shape the whole design:

- **No root on the TV.** Everything on the Android side goes through the public
  `android.hardware.usb.host` API. Calling `claimInterface(intf, /*forceClaim=*/ true)`
  detaches Android's own kernel driver from each interface and hands the app
  exclusive control of the endpoints — no root, no `usbfs`, no `ioctl`.
- **No protocol code on the PC.** The Linux kernel already ships the USB/IP
  client (`vhci-hcd`) and the `usbip` userspace tool, so the PC side is entirely
  stock. You never implement USB/IP on Fedora.
- **Speak stock USB/IP.** Framed correctly, the unmodified in-kernel client talks
  to the app directly. There is no custom wire format to invent.

All device-specific behavior — a wheel's mode switch and force feedback, a pad's
rumble, LEDs, calibration — is handled by the ordinary drivers on the PC
(`new-lg4ff`, Oversteer, `evdev`, …). The server just moves USB transfers back
and forth.

```
Android TV (this app)                         Fedora PC (stock tools)
  USB device
    -> USB Host API (claimInterface forceClaim)
    -> USB/IP bridge (protocol + transfer engine)
    -> foreground service, TCP :3240   <== TCP/IP ==>   vhci-hcd + usbip attach
                                                          -> real /dev device
                                                          -> class driver / new-lg4ff / Oversteer / evdev
```

## Quick start

On the PC side there is nothing to build — just the stock tools:

```bash
sudo modprobe vhci-hcd
usbip list -r <tv-ip>                      # see what the TV exports (no root needed)
sudo usbip attach -r <tv-ip> -b <busid>    # attach it (needs root)
lsusb                                 # the device is now present locally
evtest                                # or Oversteer, or the device's own tooling
```

Install the tools with `sudo dnf install usbip usbutils` on Fedora if they are
missing.

## Status

Early / in development. Protocol codecs (M1), the descriptor parser + endpoint
map (M2), `DEVLIST` negotiation (M3), `IMPORT` + control transfers (M4), and the
asynchronous interrupt/bulk transfer engine with `CMD_UNLINK` cancellation (M5)
are done — a fake device enumerates in `lsusb` and streams scripted input over
the desktop harness, **so the entire USB/IP protocol is now proven with no
Android and no hardware**. Porting to the TV (M6/M7) is next. The full design,
protocol details, concurrency model, and the milestone plan with pass gates live
in [`architecture.md`](architecture.md) and [`MILESTONES.md`](MILESTONES.md).

The build order is **testability-ordered**: the entire USB/IP protocol is proven
locally first — codecs, then the descriptor parser, then `DEVLIST`, `IMPORT`, and
the interrupt-IN transfer engine, all against a fake device with the stock
`usbip` client and no hardware — and only then is it ported to the TV
(`claimInterface`), validated on the G29 (force feedback), and hardened (reset
recovery, lifecycle, latency).

## Scope and limitations

- **Isochronous transfers are unsupported.** The Android Host API cannot do them,
  which excludes webcams and USB audio. Everything else — control, interrupt, and
  bulk — is supported, including OUT transfers (force feedback, rumble, LEDs).
- **One device and one client at a time** in v1. Multiple simultaneous inputs
  (e.g. a USB hub of controllers) are planned for v2 — see architecture.md §10.
- **No auth or encryption in the core server.** It assumes a trusted LAN by
  default. An optional PC-side companion daemon can add auto-attach, auto-reattach
  on device reset, and a PIN/TLS gate via a localhost proxy — off the app's
  critical path (see §9 of the architecture doc).

## Building

A **Java** multi-module Gradle project. Most of the logic lives in a pure-Java
`core` module with no Android dependency, so it builds and tests on a plain JVM;
only the Android app touches the USB Host API.

```bash
./gradlew :core:test        # fast protocol/engine unit tests — no SDK, no device
./gradlew :desktop:run      # run the server against a fake device, attach with stock usbip
./gradlew :android:assembleDebug   # build the TV app (needs the Android SDK)
```

Layout: `core/` (protocol + engine, pure Java), `android/` (the TV app),
`desktop/` (local test harness), `companion/` (optional Python PC daemon, §9),
`testdata/` (captures). Toolchain and the full build/test guide are in
[`DEVELOPING.md`](DEVELOPING.md).

## Documentation

- [`architecture.md`](architecture.md) — full design: goals and constraints, the
  USB/IP protocol surface, URB-to-Host-API mapping, concurrency model, latency
  notes, device-reset handling, the optional PC companion, scope, build order,
  testing, and a risk register.
