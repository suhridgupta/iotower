# PRD: M6 — Android claim & read (Host-API spike, no network)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M6 — Android claim & read (Host-API spike, no network)**.
Architecture §2 (why no-root works: `claimInterface(forceClaim)` detaches the
kernel driver), §5 (URB → Host API mapping; build the endpoint map from
descriptors), and §8 (Android lifecycle: USB permission, attach broadcast).

This is the **first contact with the Android USB Host API** and it is
deliberately *minimal*. M1–M5 proved the entire USB/IP protocol off-device
(L1 + the desktop L2 harness). M6 does not touch the network or the
`UsbIpServer` at all — it exists to de-risk the port by proving, on the real
TV, the one premise the whole project rests on: that a userspace app can wrest
a USB device away from the kernel driver **without root** and read its input
reports. If this fails on the hardware, nothing downstream matters; if it
passes, M7 wires the already-proven server behind the same Host API calls.

## Scope

Delivers, running on the Android TV with a USB device plugged in:

- **Enumerate** the attached USB devices via `UsbManager`, pick one, and
  **request USB permission** for it (§8) with the standard
  `PendingIntent` + `BroadcastReceiver` flow. Re-run on
  `ACTION_USB_DEVICE_ATTACHED`.
- **Open + claim:** `usbManager.openDevice(device)`, then
  `connection.claimInterface(intf, /* forceClaim = */ true)` on **every**
  interface the device exposes (§2). This is the step that detaches the kernel
  input driver.
- **Build the endpoint map** by feeding `connection.getRawDescriptors()` to the
  existing **`core` `DescriptorParser`** (no re-implementation), and log the
  parsed topology. As a hardware cross-check, also enumerate the Host API's own
  `UsbInterface`/`UsbEndpoint` objects and confirm the core parser's addresses
  agree with what the platform reports.
- **Read interrupt-IN:** for each interrupt-IN endpoint, run a background loop
  that queues a `UsbRequest` and blocks on `connection.requestWait()`, logging
  each received report (length + hex) to **Logcat** under a single stable tag.
- **Surface status** in the thin launcher UI (device name, claim result, a
  Start/Stop control) and **stop cleanly** (cancel the read loop, release every
  claimed interface, close the connection) on Stop and on `onDestroy`.

Out of scope (do **not** gold-plate):

- **No network, no `UsbIpServer`, no USB/IP framing.** M6 reads to Logcat only.
- **No `AndroidUsbBackend` implementation.** Filling in `submit` / `cancel` /
  `deviceInfo` against the `UsbBackend` contract is **M7**. M6's read loop lives
  in its own spike class and does not implement or depend on `UsbBackend`. (Its
  stale per-milestone TODO comments are corrected to point at M6/M7 as part of
  the doc audit — no behavioural change.)
- No OUT / control / bulk transfer paths beyond what enumeration itself needs
  (none here — descriptors come from `getRawDescriptors()`, not a control read).
- No isochronous support (unsupported, §5/§7).
- No device selection UI beyond "first eligible device"; no multi-device
  (v2). No foreground-service / screen-off survival (that is M9); the spike may
  run from the activity.

## Approach

A new **`HostApiSpike`** class in `:android` owns the whole open → claim →
read → stop lifecycle for one device; `MainActivity` owns enumeration, the
permission handshake, and the UI, and drives the spike.

**Invariants preserved.** Device-agnostic throughout — the spike reads
descriptors and routes purely by `(endpoint type == interrupt, direction ==
IN)`, never by VID/PID (Invariant 1). `core` stays Android-free: `HostApiSpike`
is in `:android` and only *consumes* `DescriptorParser` / `EndpointMap` /
`EndpointInfo` from `core` (Invariant 2). Every null-returning Host API call —
`openDevice`, `getRawDescriptors`, endpoint lookups — is guarded (Invariant 6):
a failure logs and aborts the spike rather than crashing.

**`HostApiSpike` (new, `:android`)**
- `start(UsbManager, UsbDevice)`:
  - `connection = usbManager.openDevice(device)`; if null, log + fail.
  - For `i` in `0 .. device.getInterfaceCount()-1`:
    `connection.claimInterface(device.getInterface(i), true)`; record success
    per interface. **This is the "UI stops scrolling" moment.**
  - `raw = connection.getRawDescriptors()`; if null/short, log + continue with
    Host-API enumeration only. Else `EndpointMap map = DescriptorParser.parse(raw)`
    and log `map` (interface count, each endpoint's address/type/dir/
    maxPacketSize/interval). Cross-check the parsed interrupt-IN addresses
    against the Host API `UsbEndpoint` list and log any disagreement.
  - For each interrupt-IN endpoint (from the Host API list, so we hold the real
    `UsbEndpoint`), start a daemon reader thread: `UsbRequest req = new
    UsbRequest(); req.initialize(connection, ep);` then loop while running —
    `ByteBuffer buf = ByteBuffer.allocate(ep.getMaxPacketSize()); req.queue(buf);`
    (the non-deprecated `queue(ByteBuffer)`, API 26+, ok at minSdk 28), and if
    `connection.requestWait()` returns this request, log the report bytes.
- `stop()`: flip a volatile `running=false`, `req.cancel()` + `requestWait()`
  wakeup / interrupt+join each reader, `releaseInterface` every claimed
  interface, `connection.close()`. Idempotent.

**`MainActivity` (rewrite the body of the existing thin activity)**
- On `onCreate` / on `USB_DEVICE_ATTACHED` intent: `pickDevice()` from
  `usbManager.getDeviceList()` (first entry). If none, show "plug in a device".
- Permission: if `usbManager.hasPermission(device)` → `startSpike(device)`;
  else `usbManager.requestPermission(device, PendingIntent.getBroadcast(... ACTION
  _USB_PERMISSION ...))` with `FLAG_IMMUTABLE`. A registered `BroadcastReceiver`
  handles the grant → `startSpike`, or logs denial.
- UI: replace the static `TextView` with a small vertical layout — a status
  `TextView` (updated with device + claim summary) and a D-pad-focusable
  Start/Stop `Button`. Platform widgets only (Invariant 5 / §13: no AndroidX).
- `onDestroy`: `spike.stop()`, unregister the receiver.

No changes to `ServerService` (M7 wires it), `AndroidManifest.xml` (the
`USB_DEVICE_ATTACHED` filter and permissions are already present), or
`device_filter.xml`.

## Files to touch

- **Add** `android/src/main/java/com/iotower/android/HostApiSpike.java` — the
  open/claim/read/stop lifecycle.
- **Change** `android/src/main/java/com/iotower/android/MainActivity.java` —
  enumeration, permission handshake, status UI, Start/Stop, cleanup.
- **Change** `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` —
  comment-only: correct the stale `TODO(milestone 1/2/3)` references to point at
  M6 (spike) / M7 (backend). No code change.

## Test plan

- **Layer(s):** **L3 only.** The spike is pure Host-API glue against real
  hardware and the platform `UsbManager`/`UsbRequest` classes; there is no
  JVM-testable logic that an existing `core` test does not already cover. The
  descriptor→endpoint-map path it exercises is `core`'s and is already covered
  by M2's `DescriptorParserTest` against the captured F310 dump — **do not add a
  redundant L1 test** (CLAUDE.md: minimize test count). `./gradlew :core:test`
  stays green because `core` is untouched.
- **New cases:** none automated. The M6 gate is a manual hardware procedure
  (below).
- **Fixtures / testdata:** none new. (Optional, not required for the gate: if
  the reader logs the raw descriptor bytes, a future capture could be dropped
  into `testdata/` as a real second device dump — but M6 does not require it.)

## Pass gate

On the TV, `./gradlew :android:installDebug`, plug in a USB input device
(the cheap generic gamepad or the F310), grant the permission prompt, press
Start, and with `adb logcat -s IoTowerSpike`:

1. The log shows the device opened, **every interface claimed with
   `forceClaim`**, and the parsed endpoint map (interrupt-IN endpoint present,
   e.g. `0x81`).
2. **Live interrupt-IN reports scroll in Logcat** as you move the sticks /
   press buttons.
3. **The TV UI stops responding to the pad** (no more menu scrolling) the
   instant the interfaces are claimed — proving the kernel input driver let go
   and the **no-root premise holds on this hardware**.

Meeting 2 **and** 3 together is the milestone. Stop releases the device and the
TV regains control of the pad.

## Risks / open questions

- **Some HID devices are filtered from `UsbManager`** (bare boot-protocol
  keyboards/mice; §2). If the chosen test pad does not appear in
  `getDeviceList()`, that is the known Host-API limitation, not a bug — try the
  F310 / the generic gamepad (both known-grabbable) and note which devices
  enumerate.
- **`requestWait()` blocking semantics.** Cancelling a queued `UsbRequest` to
  unblock a reader on Stop can be firmware-dependent; if `requestWait()` does not
  return promptly after `cancel()`, fall back to closing the connection to force
  it and just join with a timeout. Flag if a clean Stop cannot be achieved.
- **`getRawDescriptors()` may return null** on some devices/levels. The spike
  must still claim + read via Host-API endpoint enumeration in that case; log
  the null and continue rather than aborting. Flag if it is null on the primary
  test device (M7 needs those bytes for the exported `usbip_usb_device`).
- If claiming interfaces does **not** stop the TV UI scrolling on this specific
  TV, **stop and flag** — that would contradict §2 on this hardware and is a
  project-level finding, not something to code around.
