# BUG: M7 — interrupt-IN input reports arrive empty (len=0) once a USB/IP client is attached

> Status: **OPEN** — blocks the M7 L3 gate. Discovered during M7 hardware
> bring-up (2026-09-23). Code changes made while investigating are **uncommitted**
> (the committed M7 baseline is `75a691a`; the working tree carries the diagnostic
> reader-pump backend described below).

## One-line summary

A generic gamepad plugged into the Android TV enumerates on the Fedora PC over
USB/IP and its driver (`xpad`) binds, but **no input reaches the PC**: the TV
app's reads of the interrupt-IN endpoint complete continuously with **0 bytes**
whenever a USB/IP client is attached — even though a direct read of the same
endpoint, before any client attaches, returns real 20-byte reports.

## Hardware / environment

- **TV side:** Android TV running the I/O Tower app (`com.iotower.android`),
  `minSdk 28` / `compileSdk 35`. Reached over the network via `adb`.
- **Device under test:** Logitech Gamepad F310, switch on **"X"** (XInput mode) —
  enumerates as `046d:c21d`, vendor-specific class `ff/ff/ff`, one interface,
  interrupt-IN endpoint `0x81` (maxPacketSize 32), interrupt-OUT endpoint `0x02`.
  Plugged directly into the TV's USB port.
- **PC side:** Fedora (kernel 7.2.5-200.fc44), stock `usbip` + `vhci-hcd`, driver
  `xpad`. PC is the USB/IP client; TV IP is `192.168.1.107` (the exporter).
- **Same LAN, wired.** Not a firewall issue (see Findings F1).

## Symptom (what the user sees)

`usbip attach` succeeds, `lsusb` shows the pad, `xpad` binds and `evtest`
`/dev/input/eventNN` lists the full F310 capability set — but **moving the sticks
or pressing buttons produces no `EV_ABS`/`EV_KEY` events**. On the TV, Logcat
(tag `IoTowerBackend`) shows a steady stream of `read ep=0x81 len=0` completions.

## What WORKS vs what is BROKEN

Working end-to-end over the network:
- `OP_REQ_DEVLIST` — `usbip list -r <tv-ip>` shows the device with the right
  busid/VID:PID/class.
- `OP_REQ_IMPORT` + full **enumeration** — the kernel reads the device and
  configuration descriptors and the real string descriptors (`Gamepad F310`,
  `Logitech`, serial `E6C085C5`); `xpad` binds; `usbip port` shows the port in use.
- **OUT** transfers — the `xpad` init/LED write to endpoint `0x02` completes
  (`completion ep=0x2 dir=OUT len=3`).
- A **direct interrupt-IN read at claim time, before any client attaches** —
  returns a real report (see F6).

Broken:
- **Interrupt-IN reports once a client is attached** — every read of `0x81`
  completes with `len=0` and an all-zero buffer, at the network round-trip rate
  (~83/s), regardless of stick/button input.

## Steps to reproduce

Prerequisites: the TV app installed, the F310 (in XInput/"X" mode) plugged into
the TV, `adb connect <tv-ip>:5555` working, Fedora with `usbip`/`usbutils`.

1. Build & install the app to the TV:
   `cd ~/Codes/iotower && ./gradlew :android:installDebug`
2. Start the server on the TV: open **I/O Tower**, press **Start**, accept the
   USB-permission dialog. Status shows `Serving 046D:C21D on :3240`.
3. Stream the TV logs (leave running):
   `adb logcat -c && adb logcat -s IoTowerBackend AndroidRuntime:E`
4. Attach from Fedora:
   `sudo modprobe vhci-hcd`
   `sudo usbip detach -p 00`   (ok if "already detached")
   `sudo usbip attach -r 192.168.1.107 -b 1-1`
5. Open evtest and move the pad:
   `evtest`  (note the F310's eventNN, Ctrl-C; no sudo — the user is in the `input` group)
   `evtest /dev/input/event21`  (then move sticks / press buttons)
6. Observe: `evtest` prints **no** events; Logcat prints `read ep=0x81 len=0 ...`
   continuously.

Isolated repro of the *direct read* that DOES work (no PC needed): the backend
briefly ran a one-shot probe at claim time — a single `UsbRequest.queue()` +
`connection.requestWait(4000)` on the interrupt-IN endpoint, run before the
client attaches. Pressing buttons during its 4-second window yields
`PROBE GOT DATA len=20 first=00 14 ...` (F6).

## Investigation log (chronological findings)

### F1 — Not a network/firewall issue
DEVLIST, enumeration (control transfers both directions), and the OUT LED write
all traverse the network successfully; the empty buffer (`arr16=00 00 ...`) is
logged **on the TV**, before anything is put on the wire. A firewall would block
the TCP connection outright and nothing would enumerate.

### F2 — Not a buffer-capacity / requested-length issue
The engine sizes the IN buffer from the client URB's `transfer_buffer_length`
(xpad requests **64** on the 32-byte endpoint: `reqLen=64 cap=64`). Reducing the
receive buffer to the endpoint max-packet size (`queueCap=32`) changed nothing —
still `len=0`. So capacity is not the cause.

### F3 — Not a position/length misread; no data lands
Diagnostic dump of the completed buffer shows `pos=0 cap=32 actualLen=0
arr16=00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00`. The buffer is genuinely
empty after the read — not a case of data present but length read wrong.

### F4 — Not the `requestWait(timeout)` overload
First cut used `UsbDeviceConnection.requestWait(long, TimeUnit)` — **that overload
does not exist** (compile error; only `requestWait()` and `requestWait(long)`
exist). Switched to the blocking `requestWait()` (the M6-proven form). Behaviour
unchanged (still `len=0`).

### F5 — Not multiple concurrent requests, not cross-thread queue/wait
`xpad` keeps ~6 interrupt-IN URBs in flight. Tried three read models, all still
`len=0`:
- per-URB `UsbRequest` (multiple concurrent on one endpoint);
- a single persistent reused `UsbRequest` per endpoint (read "pump");
- a **single dedicated reader thread** that is the sole caller of `requestWait()`,
  with OUT moved to synchronous `bulkTransfer` so nothing else touches
  `requestWait()`. This is functionally identical to the M6 spike and to the
  probe — and it **still** returns `len=0` once a client is attached.

### F6 — A direct read BEFORE the client attaches returns REAL data
A one-shot probe at claim time (single queue + `requestWait(4000)`, same thread,
nothing else on the connection, run before any USB/IP client): while wiggling the
pad it returned
`PROBE GOT DATA len=20 first=00 14 00 10 00 00 80 00 80 00 80 00 80 00 00 00`
— a valid F310 XInput frame (report type `00`, length `0x14`=20, sticks centred
at `80 00`). **So the device streams to a direct read; the read code is correct.**

### F7 — Reads only start completing when the client attaches, then are empty
With the dedicated reader running from `open()` (21:12:13), the first completion
did not arrive until **21:12:34** — the moment `usbip attach` ran — after which
completions stream at ~83/s (network RTT) with `len=0`. An **idle** pad produces
**no** completions to a passive read (the pad reports on change, not
continuously); the probe only got data because the tester was wiggling. The empty
completions appear specifically once the client's traffic is flowing.

### F8 — Latent bugs found & fixed along the way (not the root cause)
- `AndroidUsbBackend.open()` (claim + probe) ran on the **main thread** inside
  `ServerService.onStartCommand`, blocking the UI thread and tripping a
  foreground-service ANR / crash. **Fixed:** all blocking USB work moved to a
  worker thread in `ServerService`.
- The probe re-queued a still-pending `UsbRequest` after a `requestWait` timeout
  -> `IllegalStateException: this request is currently queued`. **Fixed:** probe
  reduced to a single read.
- The rewritten reader forgot `UsbRequest.setClientData(reader)`, so completions
  routed to `null` and were silently dropped (reads appeared to hang). **Fixed.**

## Current leading hypothesis

The physical interrupt-IN endpoint (or our host-side view of it) is put into a
**non-delivering state by the control transfers `xpad` issues on attach** —
almost certainly the standard enumeration sequence forwarded over USB/IP
(`GET_DESCRIPTOR`, `SET_CONFIGURATION` = bRequest `0x09`, possibly `SET_INTERFACE`
= `0x0b`). Candidate mechanisms:
1. `SET_CONFIGURATION`/`SET_INTERFACE` forwarded to an **already-configured**
   device resets its endpoints / data toggles, desyncing the host controller's
   toggle for `0x81` so subsequent IN transfers return empty.
2. The IN transfers are actually completing with an **error/short status** that
   the backend currently mis-reports as success with 0 bytes — the code always
   reports `status 0, len = buffer.position()` and never inspects the underlying
   URB status (the Android `UsbRequest`/`requestWait` API does not surface it).

The strongest evidence for "xpad's control activity is the trigger" is F6 vs F7:
identical read code returns 20 bytes before the client attaches and 0 bytes after.

## Next experiments to try (rough priority order)

1. **Read the IN endpoint with synchronous `bulkTransfer` instead of
   `UsbRequest`.** `connection.bulkTransfer(inEndpoint, buf, len, timeout)`
   returns the byte count **or -1 on error/timeout**, so it distinguishes
   "errored" from "empty" (which `requestWait` hides) — and may itself deliver the
   report. Quick to try on the reader thread.
2. **Log every forwarded control transfer** (`bmRequestType`, `bRequest`,
   `wValue`, `wIndex`, return) in `AndroidUsbBackend.controlTransfer`, correlated
   in time with the first empty `0x81` read, to see exactly what `xpad` sends at
   attach (looking for `SET_CONFIGURATION` `0x09`, `SET_INTERFACE` `0x0b`,
   `CLEAR_FEATURE`).
3. **Re-arm the endpoint after enumeration:** after a forwarded
   `SET_CONFIGURATION`/`SET_INTERFACE`, re-`claimInterface` or re-`initialize` the
   `UsbRequest`, and/or issue `CLEAR_FEATURE(ENDPOINT_HALT)` on `0x81`.
4. **Consider generic handling of a redundant `SET_CONFIGURATION`** in the
   server/backend (the device is already configured; some USB/IP servers
   special-case this). Protocol-generic, not device-specific, so it does not
   violate Invariant 1 — but confirm before adding.
5. **Compare against the M6 spike directly:** temporarily wire `HostApiSpike`
   (the known-good M6 read, gate MET today) to run on this build to re-confirm the
   device streams, and diff its exact call sequence against the backend.
6. If reachable, capture `usbmon`/Wireshark of `xpad`'s enumeration against a
   direct USB connection to see the canonical sequence.

## Relevant code

- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — the read
  path (dedicated reader thread, `EndpointReader`, `deliverAndRequeue`), OUT via
  `bulkTransfer`, `controlTransfer`. **Carries the diagnostic logging and the
  read-pump rewrite; uncommitted.**
- `android/src/main/java/com/iotower/android/ServerService.java` — now runs
  `open()` + the server loop on a worker thread (ANR fix). **Uncommitted.**
- `core/src/main/java/com/iotower/core/engine/TransferEngine.java` — calls
  `backend.submit(endpointAddress, direction, buffer, length)` for ep!=0 and wires
  the returned `UsbTransfer.completion`. IN buffer is `new byte[transferBufferLength]`.
- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — reader/transfer
  phase; forwards `CMD_SUBMIT`/`CMD_UNLINK`.
- Reference (known-good): the M6 spike
  `android/src/main/java/com/iotower/android/HostApiSpike.java` — one persistent
  `UsbRequest`, single-threaded `queue`->`requestWait` loop; read real reports on
  this exact hardware (M6 gate MET 2026-09-23).

## Key log excerpts

Empty reads once attached (dedicated-reader build):
```
21:12:13.446 I IoTowerBackend: opened + claimed 1/1 interface(s); endpoints: 0x81 0x2
21:12:34.547 I IoTowerBackend: read ep=0x81 len=0 waiters=1 (#1)     <-- first completion == moment of `usbip attach`
21:12:34.559 I IoTowerBackend: read ep=0x81 len=0 waiters=0 (#2)
 ...                            read ep=0x81 len=0  (steady ~83/s, regardless of input)
```

Direct pre-client read returns real data (probe build):
```
PROBE: queued 0x81 - WIGGLE THE STICKS / PRESS BUTTONS NOW (waiting up to 4s)
PROBE GOT DATA len=20 first=00 14 00 10 00 00 80 00 80 00 80 00 80 00 00 00
```

Enumeration + OUT succeed (earlier build):
```
usb 5-1: New USB device found, idVendor=046d, idProduct=c21d, bcdDevice=40.14
input: Logitech Gamepad F310 as .../usb5/5-1/5-1:1.0/input/input23
usbcore: registered new interface driver xpad
IoTowerBackend: completion #1 ep=0x2 dir=OUT len=3
```

---

## Live session log (continued 2026-09-23, ~21:20)

### Note: MODE button
Tester pressed the front **MODE** button on the F310 mid-test to see if output
changed. On the F310 the front MODE button only remaps the left analog stick <->
D-pad (indicated by an LED); it does **not** change the USB device/mode. The
D/X **switch on the back** is what changes DirectInput vs XInput (a different USB
device: different descriptors/PID) — that is a physical switch, not the MODE
button. So MODE is not expected to affect this bug, but recording it in case the
next run shows a correlation.

### Experiment 1 — read IN via synchronous `bulkTransfer` (in progress)
Replaced the `UsbRequest`/`requestWait` read path entirely with a dedicated
per-IN-endpoint reader thread doing blocking
`connection.bulkTransfer(endpoint, buf, buf.length, 200ms)` in a loop, delivering
each non-empty read to the oldest waiting client URB. OUT already uses
`bulkTransfer`; ep0 uses `controlTransfer`. **No `UsbRequest` anywhere now.**
Diagnostic log line per read: `bulkRead ep=0x81 n=<count> waiters=<k> (#N)`, where
`n` is the byte count, `0`/negative = empty/timeout, `-1` = error.

**What to look for in the next run:**
- `bulkRead ep=0x81 n=20 …` while wiggling -> **FIXED** (bulkTransfer delivers the
  report; `evtest` should move).
- `n=-1` -> the reads are **erroring** (endpoint halt/reset by xpad's enumeration)
  — confirms hypothesis (2)/SET_CONFIGURATION reset; next step is CLEAR_FEATURE /
  re-arm after enumeration.
- `n=0` -> genuinely empty reads even via bulkTransfer — points away from the URB
  API and toward the endpoint being fed nothing once the client drives it.

Result: _pending — awaiting hardware run._

### Experiment 1 RESULT — `bulkTransfer` returns **-1 (error)**, not empty
```
21:24:44.502 opened + claimed 1/1 interface(s); endpoints: 0x81 0x2
21:24:44.709 bulkRead ep=0x81 n=-1 waiters=0 (#1)     <-- ~200ms apart (full READ_TIMEOUT) before client
 …
21:24:46.965 bulkRead ep=0x81 n=-1 waiters=0 (#12)
21:25:16.070 bulkRead ep=0x81 n=-1 waiters=0 (#500)   <-- after this, reads are FAST (see timing note)
21:25:22.070 bulkRead ep=0x81 n=-1 waiters=1 (#1000)  <-- 500 reads in 6s == ~12ms each == immediate error, not a 200ms timeout
 …
```
`evtest` then died with: `expected 24 bytes, got -1` / `evtest: error reading: No such device`.

**Key observations:**
- Every IN read returns **`n=-1`** — an **error/timeout**, never `0` and never a
  real byte count. `bulkTransfer` exposes what `requestWait` hid (it reported the
  same failing reads as 0-byte *successes*).
- **Timing tells error vs timeout:** before the client attaches, reads take the
  full ~200ms `READ_TIMEOUT_MS` then return -1 (a genuine *timeout* — an idle pad
  sends nothing; it reports on change). Once xpad attaches, reads return -1 in
  **~12ms** (500 reads in 6s) — i.e. an **immediate error**, not a timeout.
- So xpad's attach flips the endpoint from "idle/no data" to "**errors
  immediately**". The endpoint is being **halted/stalled** by the client's
  enumeration activity.
- The "logs update with nothing to do with keystrokes" the tester noticed is
  exactly this: the reads fire on their own timer/error, decoupled from input.
- Caveat: `bulkTransfer` never returned real data even pre-client, but that window
  was only 200ms and idle — not yet proof `bulkTransfer` *can't* read this
  interrupt IN. The `UsbRequest` probe got 20 bytes because it waited 4s while the
  pad was wiggled. A claim-time `bulkTransfer` with a multi-second timeout while
  wiggling would confirm.

### Updated leading hypothesis (higher confidence)
xpad's enumeration — a forwarded `SET_CONFIGURATION` (`0x09`) and/or
`SET_INTERFACE` (`0x0b`), or an endpoint reset — **halts/stalls the physical
interrupt-IN endpoint**, after which every read errors. `requestWait` masked this
as empty (0-byte) completions; `bulkTransfer` reveals it as `-1`. The endpoint is
fine (probe reads 20 bytes) until the client drives it.

### Refined next steps (for the next session — START HERE)
1. **Log every forwarded control transfer**, timestamped, in
   `AndroidUsbBackend.controlTransfer` — `bmRequestType`, `bRequest`, `wValue`,
   `wIndex`, `wLength`, and the return value. Correlate with the moment reads go
   from timeout(-1) to immediate-error(-1). Watch for `0x09` SET_CONFIGURATION,
   `0x0b` SET_INTERFACE, `0x01` CLEAR_FEATURE, `0x03` SET_FEATURE.
2. **Clear the endpoint halt after enumeration** — issue a control transfer
   `CLEAR_FEATURE(ENDPOINT_HALT)` on `0x81`:
   `bmRequestType=0x02, bRequest=0x01 (CLEAR_FEATURE), wValue=0x0000 (ENDPOINT_HALT),
   wIndex=0x0081, length=0`. Try it after the suspected culprit control transfer,
   or lazily when a read returns -1.
3. **Handle a redundant `SET_CONFIGURATION` generically** — the device is already
   configured by Android; forwarding xpad's `SET_CONFIGURATION(1)` may reset it.
   Options (both protocol-generic, not device-specific, so Invariant 1 holds):
   (a) intercept `SET_CONFIGURATION` in the server/backend and ACK it without
   forwarding to the device; or (b) after forwarding, **re-`claimInterface` and
   re-clear halts**. Do step 1 first to confirm which request is the trigger.
4. **Confirm `bulkTransfer` can read this endpoint at all** — a one-shot claim-time
   `bulkTransfer(0x81, buf, len, 4000ms)` while wiggling, before any client
   (expect `n=20`). If it also fails, prefer the `UsbRequest` path for reads and
   put the halt-clear fix there.
5. **Check interface re-claim after `SET_CONFIGURATION`** — a config change can
   drop the interface claim; re-claim if so.

### Current code state (for resumption)
- **Committed baseline:** `75a691a` on `feat/m7` — the M7 feature as originally
  built (per-URB `UsbRequest`, no diagnostics). `git stash` or revert the working
  tree to return to it.
- **Uncommitted working tree** carries the investigation:
  - `AndroidUsbBackend.java` — now the **bulkTransfer** read path (one reader
    thread per IN endpoint) + `bulkRead` diagnostics. This is Experiment 1, NOT a
    fix.
  - `ServerService.java` — the ANR fix (USB work on a worker thread). **This one
    is a genuine fix worth keeping** regardless of the read-path outcome.
  - `bugs/m7-interrupt-in-empty-reports.md` — this document.
- `:core` is untouched throughout; `./gradlew :core:test` stays green.
