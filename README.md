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
Android and no hardware**. The M6 Host-API spike (`HostApiSpike`:
`claimInterface(forceClaim)` on every interface + interrupt-IN read to Logcat,
no network) **passed its L3 gate on real hardware** — a Logitech F310 was
claimed with no root and its live input streamed to Logcat, confirming the
no-root premise (§2). M7 (the proven server in a foreground service, real input
over the network) is next. The full
design, protocol details, concurrency model, and the milestone plan with pass
gates live in [`architecture.md`](architecture.md) and
[`MILESTONES.md`](MILESTONES.md).

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

## Deploying to the TV (ADB over the network)

The TV app is installed and debugged over the network with `adb` — no cable.
One-time setup on the TV: enable **Developer options** (Settings → System →
About → click *Build* 7×), then turn on **USB debugging** under Developer
options. On most Android TVs that also opens network ADB on port 5555; newer
Google TV boxes instead expose a separate **Wireless debugging** toggle that
needs a pairing step (see below). Find the TV's IP under Settings → Network &
Internet → your (Ethernet) connection, or the *Status* screen.

```bash
# Connect (accept the "Allow debugging?" prompt on the TV the first time)
adb connect <tv-ip>:5555
adb devices                     # TV should show as "device" (not "unauthorized"/"offline")

# Install / update the app (reinstalls in place, keeps data; same command each time)
./gradlew :android:installDebug
#   or directly:
adb install -r android/build/outputs/apk/debug/app-debug.apk

# Watch the app's logs. The M6 Host-API spike logs under a single tag:
adb logcat -c                   # clear old logs (optional)
adb logcat -s IoTowerSpike      # follow only spike output
#   everything from the app's process, all tags:
adb logcat --pid=$(adb shell pidof -s com.iotower.android)

# Launch / stop the app from the PC (optional)
adb shell am start -n com.iotower.android/.MainActivity
adb shell am force-stop com.iotower.android

# List the USB devices the TV sees (handy when several are attached)
adb shell dumpsys usb | sed -n '/USB Host State/,/^$/p'
```

**Wireless-debugging fallback.** If `adb connect <ip>:5555` is refused, the TV is
on the newer secure flow: Developer options → **Wireless debugging → Pair device
with pairing code**, then use the pairing host:port and 6-digit code it shows,
and connect to the *debug* host:port (both differ from 5555):

```bash
adb pair <tv-ip>:<pairing-port>     # enter the 6-digit code
adb connect <tv-ip>:<debug-port>
```

**Tips.** A stale connection shows as `offline` — `adb disconnect` then
`adb connect` again clears it. DHCP can change the TV's IP after a reboot;
reserve it on the router if you reconnect often. A signature mismatch on install
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, e.g. after building on a different
machine) needs `adb uninstall com.iotower.android` first. The full M6 test
procedure (plug in a pad, Start, and what the spike log should show) is the L3
gate in [`MILESTONES.md`](MILESTONES.md).

## Documentation

- [`architecture.md`](architecture.md) — full design: goals and constraints, the
  USB/IP protocol surface, URB-to-Host-API mapping, concurrency model, latency
  notes, device-reset handling, the optional PC companion, scope, build order,
  testing, and a risk register.
