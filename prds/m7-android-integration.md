# PRD: M7 — Android integration (first true end-to-end)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M7 — Android integration (first true end-to-end)**. This is the
first milestone where the whole stack runs together on real hardware: the
already-proven, device-agnostic `UsbIpServer` / `TransferEngine` (M5, done —
validated on the desktop L2 harness) is wired behind the **real Android USB Host
API** so a generic gamepad plugged into the TV reaches the Fedora PC over the
network (`usbip attach` → `evtest`).

Architecture sections in play:

- **§2** — no-root claim: `openDevice` + `claimInterface(intf, forceClaim=true)`
  on *every* interface detaches the kernel driver. Proven standalone by the M6
  spike; M7 does the same claim inside the real backend.
- **§4.1 / §4.2** — the wire structs and the two phases. Unchanged by M7; the
  core already speaks them. Relevant here only because `deviceInfo()` fills the
  `usbip_usb_device` struct (§4.1) and `submit`/`cancel` feed the transfer phase
  (§4.2).
- **§5 & §5.1** — the URB → Host API mapping table and the reader → engine →
  writer concurrency model. M7 supplies the bottom row of that table on Android:
  ep0 via `controlTransfer` (already wired), interrupt/bulk IN/OUT via
  `UsbRequest.queue()` / `requestWait()`, correlated back by `setClientData`.
- **§8** — Android lifecycle: foreground service, USB permission handshake,
  attach broadcast.

The prerequisite gates are MET: **M5** (protocol + engine, done, L2-validated on
the fake) and **M6** (Host-API claim + interrupt-IN read, gate MET on the TV with
an F310, 2026-09-23). M7 is "wire the two together". **The core protocol/engine
is DONE and must not change**, except for the one small, testable `core` helper
specified below.

## Scope

Delivers, running on the Android TV with a generic gamepad plugged in and a
Fedora PC on the same network:

- **`AndroidUsbBackend` implements the full `UsbBackend` contract** against the
  Host API — `deviceInfo()`, `submit()` (ep≠0), `cancel()`, plus the already-wired
  `rawDescriptors()`, `controlTransfer()`, `close()`. It owns the open device,
  the claimed interfaces, an address→`UsbEndpoint` map, and a single dispatcher
  thread that drives `requestWait()`.
- **`ServerService` runs the server.** The foreground service (§8) receives the
  permission-granted `UsbDevice`, builds an `AndroidUsbBackend`, starts
  `new UsbIpServer(backend)` on a worker thread, reflects status in its
  persistent notification, and tears both down on `onDestroy`.
- **`MainActivity` drives the service.** The existing enumerate + USB-permission
  handshake (§8) now routes the granted device to `ServerService` (Start) and
  stops it (Stop), instead of driving the M6 spike. UI labels updated off the
  M6-spike wording.
- **One new `core` helper, `DescriptorParser.parseDeviceInfo(byte[] raw, int
  speed)`** (pure Java, JVM-testable), so `bcdDevice` and the other device-
  descriptor fields are decoded in `core` under an L1 test rather than by hand in
  the Android class. Keeps `AndroidUsbBackend` thin and `core` Android-free
  (Invariant 2).

**Observable outcome:** on Fedora, `usbip list -r <tv-ip>` shows the device;
`sudo usbip attach -r <tv-ip> -b 1-1` succeeds; `lsusb` shows it; `evtest` shows
live buttons/axes from the physical pad on the TV; the TV's foreground
notification stays up for the session; clean detach and Stop.

**Out of scope — do NOT gold-plate:**

- **No change to `core/net`, `core/engine`, `core/protocol`.** The server and
  engine are done and device-agnostic; M7 adds only the optional
  `DescriptorParser.parseDeviceInfo` helper to `core/usb`. If you find yourself
  editing `UsbIpServer` or `TransferEngine`, **stop and flag** — the design is
  that they are already correct.
- **No `UsbRequest` / buffer pooling or reuse.** A fresh `UsbRequest` per submit
  is acceptable (correctness first). Buffer/request reuse is an explicit
  **non-goal**, deferred to **M9** (latency). Do not build a pool.
- **No isochronous.** Unsupported by the Host API and out of scope (§5, §7).
- **No device reset / re-enumeration / mode-switch recovery.** That is **M8**
  (G29) and **M9** (robustness). M7 handles one plugged device, one session.
- **No screen-off / service-survival hardening, no multi-device.** M9 and v2.
- **No PIN/TLS/companion.** M10.
- **No auto-start on attach beyond the existing permission handshake.** The
  attach broadcast may route into the same Start path, but don't add new
  lifecycle machinery.

## Approach

### 1. `AndroidUsbBackend` — the real `UsbBackend`

**Construction / ownership.** The backend needs the `UsbDevice` (not just the
`UsbDeviceConnection`) to enumerate interfaces and endpoints, so its construction
changes. Specify a **static factory** that performs open + claim and returns a
ready backend, mirroring `HostApiSpike.start`:

```
static AndroidUsbBackend open(UsbManager usbManager, UsbDevice device)  // null on failure
```

- `usbManager.openDevice(device)` — guard null (Invariant 6 / §8: permission
  lost or race). On null, the factory returns null and the service reports the
  failure rather than crashing.
- `claimInterface(intf, /* forceClaim= */ true)` on **every** interface (§2),
  exactly as the spike does. Keep the list of claimed `UsbInterface`s for
  `close()`.
- Build the **address→`UsbEndpoint` map once, here**, by walking every claimed
  interface's endpoints (`intf.getEndpoint(i)`) and keying by
  `ep.getAddress()` (the 8-bit address including the `0x80` IN bit). `submit`
  looks up the real `UsbEndpoint` handle from this map by `endpointAddress` —
  routing by `(endpoint, direction)` from the descriptors, never by device
  identity (Invariant 1). ep0 is not in this map (control goes through
  `controlTransfer`).
- Start the single dispatcher thread (below) before returning.

The private constructor takes the already-open `connection`, the claimed-
interface list, and the endpoint map; the factory is the only caller. (Rationale
for factory-does-open, rather than being handed an open connection: it keeps the
claim policy — forceClaim on every interface — in one place next to the endpoint-
map build, and matches the spike the M6 gate validated. Flag the alternative in
Risks.)

**`deviceInfo()`** (§4.1). Build a `DeviceInfo`. `idVendor`, `idProduct`,
`deviceClass`, `deviceSubClass`, `deviceProtocol`, `numConfigurations`,
`numInterfaces`, and `configurationValue` are available from `UsbDevice` /
`UsbConfiguration` getters — **but `bcdDevice` is not exposed by `UsbDevice`** and
must come from the raw device descriptor (bytes 12–13, little-endian, of
`getRawDescriptors()`). To keep `core` Android-free and put the byte-walking
under an L1 test, delegate the whole struct to the new core helper:

```
DeviceInfo parseDeviceInfo(byte[] raw, int speed)   // in core/usb/DescriptorParser
```

`AndroidUsbBackend.deviceInfo()` then calls `DescriptorParser.parseDeviceInfo(
connection.getRawDescriptors(), SPEED)` and returns the result (guard the
`getRawDescriptors()` null per Invariant 6). This means all descriptor field
extraction — including the fields `UsbDevice` *could* have provided — lives in one
tested core function, and the Android class holds no hand-decoded offsets. The
helper reads, from the 18-byte device descriptor (USB 2.0 §9.6.1, little-endian):
`idVendor` (8–9), `idProduct` (10–11), `bcdDevice` (12–13), `bDeviceClass` (4),
`bDeviceSubClass` (5), `bDeviceProtocol` (6), `bNumConfigurations` (17); and from
the first configuration descriptor (found by advancing `bLength` past the device
descriptor): `bConfigurationValue` and `bNumInterfaces`. `speed` is passed in
(the Host API has no getter — see below and Risks).

- **`speed`:** the Host API exposes no device-speed getter. Specify a named
  constant `SPEED = UsbIp` full-speed value **2** (matching what `FakeUsbBackend`
  advertises), passed into `parseDeviceInfo`. It is largely cosmetic — the vhci
  client re-reads descriptors after import and the kernel re-derives topology —
  but it is a guess; flag it as an open question.

**`submit(endpointAddress, direction, buffer, length)` for ep≠0** (§5, §5.1).
Called from the engine (which itself is called from the reader thread). Steps:

1. Look up the `UsbEndpoint` in the address→endpoint map by `endpointAddress`.
   If absent → return a `UsbTransfer` completed with `-ECONNRESET (-104)` **or**
   return null (the engine treats a null return as `-ECONNRESET` — see
   `TransferEngine.submit`); pick the null-return path for simplicity and match
   how the engine and `FakeUsbBackend` already handle "can't submit". Guard
   Invariant 6.
2. Allocate a `UsbRequest`, `initialize(connection, endpoint)`. For an IN
   transfer the engine already allocated a `length`-sized `buffer`; wrap the
   caller's `buffer` in a `ByteBuffer` (IN: to receive; OUT: containing the
   payload the reader read).
3. `request.setClientData(usbTransfer)` to correlate the completion back to the
   `UsbTransfer` (§5.1). Create the `UsbTransfer` with `handle = request` so
   `cancel` can reach the `UsbRequest`.
4. `request.queue(byteBuffer)` — the **`ByteBuffer` form** (API 26+; minSdk 28,
   so this is safe and non-deprecated). On a false return, complete the transfer
   `-ECONNRESET` and return it (do not desync).
5. Return the `UsbTransfer`; the engine wires its `completion` future.

**Dispatcher thread (one per connection/backend).** A single thread owns
`connection.requestWait()` in a loop — **only one thread may call
`requestWait()`** on a connection. Each returned `UsbRequest` is matched back to
its `UsbTransfer` via `request.getClientData()`; read the transferred bytes
(IN: the `ByteBuffer` position gives actual length; copy out a right-sized
`byte[]`), determine status (0 on success), and `completion.complete(new
UsbTransfer.Result(status, data, actualLength))`. For OUT, data is null and
actual length is the sent length. A cancelled request surfaces here too (or via
`requestWait` returning it after `cancel`); complete it `-ECONNRESET` if not
already done — the engine's atomic `inflight.remove` is the real arbiter, so a
late/duplicate completion on an already-removed seqnum is harmless.

- **Shutdown.** `requestWait()` blocks. Specify clean shutdown: set a `volatile
  running=false`, then the backstop is `connection.close()` (in `close()`), which
  unblocks a parked `requestWait()` and fails outstanding requests — the same
  backstop the M6 spike relies on. Optionally use the timeout form
  `requestWait(timeout, unit)` (API 26+) to poll the running flag without relying
  solely on close; specify one and note the choice. Either way the dispatcher
  loop must exit and be `join`ed (bounded, e.g. 500 ms) in `close()`.
- **Guards (Invariant 6).** Every null-returning Host API call —
  `initialize` false, `queue` false, `requestWait` null, endpoint lookup miss —
  must complete the affected transfer with `-ECONNRESET (-104)` and never NPE or
  kill the dispatcher; a bad transfer must not stall the session. This mirrors
  `FakeUsbBackend.cancel` (`-104`) and `TransferEngine`'s own null/exception
  handling.

**`cancel(UsbTransfer)`** — `((UsbRequest) transfer.handle).cancel()`. Guard a
null handle. The dispatcher (or the engine's `inflight.remove`) resolves the
resulting completion; keep it best-effort (catch and log, per the spike).

**`controlTransfer(...)` and `rawDescriptors()`** — already implemented; **keep
as-is**. (`controlTransfer` delegates to `connection.controlTransfer`;
`rawDescriptors` to `connection.getRawDescriptors`.)

**`close()`** — set `running=false`, stop/join the dispatcher, `cancel` any
still-in-flight requests best-effort, `releaseInterface` every claimed interface,
`connection.close()`. Idempotent. (The engine's `shutdown()` cancels in-flight
transfers first, then `UsbIpServer` does **not** close the backend — the service
does, on `onDestroy` — so `close()` is called once per session, after the server
thread has stopped.)

### 2. `ServerService` — run the server (§8)

- Receive the granted `UsbDevice` from the start Intent extra
  `UsbManager.EXTRA_DEVICE` (put there by `MainActivity`).
- `startForeground(...)` with the existing notification (already built); update
  its text to reflect state (e.g. "Serving <VID:PID> on :3240" / "no device").
- Build the backend: `AndroidUsbBackend.open(usbManager, device)`. On null
  (open/claim failed), update the notification and `stopSelf()` — do not leave a
  dead foreground service.
- Start the server on a worker thread:
  `server = new UsbIpServer(backend); serverThread = new Thread(server,
  "usbip-server"); serverThread.start();` (the `UsbIpServer` accept loop blocks,
  hence a thread; it is device- and Android-agnostic and unchanged).
- **`onDestroy`:** `server.stop()` (unblocks accept / closes the listen socket),
  join the worker thread bounded, then `backend.close()` (release + close). Order
  matters: stop the server first so no transfer is mid-flight when the backend
  closes.
- Keep it single-device / single-session; `START_STICKY` acceptable but no new
  restart logic (M9). The manifest already declares the service, the
  `connectedDevice` foreground type, and the FOREGROUND_SERVICE /
  FOREGROUND_SERVICE_CONNECTED_DEVICE / POST_NOTIFICATIONS / INTERNET permissions
  — no manifest change needed.

### 3. `MainActivity` — drive the service, not the spike

- Keep the existing enumerate (`pickDevice`, device-agnostic first device) and
  USB-permission handshake (`PendingIntent` + `BroadcastReceiver`, §8) exactly.
- On permission granted (and on `hasPermission` already true), **Start** now
  launches `ServerService` with the device:
  `startForegroundService(new Intent(this, ServerService.class)
  .putExtra(UsbManager.EXTRA_DEVICE, device))` — instead of `spike.start(...)`.
- **Stop** now `stopService(...)` the `ServerService` instead of `spike.stop()`.
- Track running state from the service the activity started (a simple boolean the
  activity owns is sufficient for v1 — no binding required; note that if the
  service dies independently the label can go stale, acceptable for M7 and
  addressed by M9). Update the toggle label accordingly.
- Update UI strings off the M6 wording: title "I/O Tower — USB/IP server (M7)",
  status lines about attaching from the PC (`usbip attach -r <tv-ip> -b 1-1`)
  rather than `adb logcat`.
- **`HostApiSpike`:** recommended to **retain the class** (architecture §2
  references it and the M6 gate is MET), but it is **no longer wired to the
  button** — the activity no longer holds a `HostApiSpike` field. Leaving a
  now-unused class is a deliberate, documented choice, not silent dead code:
  note it in the code (a short class-level comment: "M6 diagnostic, retained,
  not wired into the M7 server path") and flag the keep-vs-remove decision as an
  open question. Do **not** delete it silently.

### Invariants preserved

- **Device-agnostic (Invariant 1):** no VID/PID logic anywhere; `submit` routes
  purely by `endpointAddress` against the claim-time endpoint map. `pickDevice`
  takes the first device with no filter.
- **`core` stays Android-free (Invariant 2):** the only `core` change is
  `DescriptorParser.parseDeviceInfo`, pure byte-walking, no `android.*` import.
  All `UsbRequest`/`UsbEndpoint`/`UsbManager` code is confined to `:android`.
- **Big-endian wire (Invariant 4):** untouched — all framing is in `core`, which
  M7 does not modify. (The device descriptor `parseDeviceInfo` reads is USB
  little-endian, decoded by hand exactly as the existing `DescriptorParser.parse`
  decodes `wMaxPacketSize` — do not use a BIG_ENDIAN `ByteBuffer` there.)
- **No third-party libs (Invariant 5):** only platform + `:core`.
- **Guard null-returning Host API calls (Invariant 6):** enumerated above.

## Files to touch

- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — implement
  `deviceInfo`, `submit`, `cancel`, `close`; add the `open` factory, the
  address→`UsbEndpoint` map, and the dispatcher thread. (change)
- `android/src/main/java/com/iotower/android/ServerService.java` — receive the
  device, build the backend, run `UsbIpServer` on a worker thread, teardown in
  `onDestroy`; notification text. (change)
- `android/src/main/java/com/iotower/android/MainActivity.java` — Start/Stop now
  drive `ServerService` with the granted device; drop the `HostApiSpike` field;
  UI-label updates. (change)
- `core/src/main/java/com/iotower/core/usb/DescriptorParser.java` — add
  `public static DeviceInfo parseDeviceInfo(byte[] raw, int speed)`. **Only
  addition; do not change `parse`.** (change)
- `core/src/test/java/com/iotower/core/usb/DescriptorParserTest.java` — add the
  one `parseDeviceInfo` L1 case (see Test plan). (change — extend, do not add a
  file)

No manifest, gradle, or `res/` change. **No change to any file under
`core/net`, `core/engine`, or `core/protocol`, nor to `desktop/`.** If the
implementer believes one is needed, **stop and flag**.

## Test plan

- **Layers:** L1 (one new core case) + L3 (the real hardware gate, run by the
  user). L2 (desktop harness) is unchanged by M7 and must still pass as a
  regression.

- **New cases (and only new ones):**
  - **L1 — `DescriptorParserTest.parseDeviceInfoFromRealCapture` (add to the
    existing test class).** *Purpose:* cover the new device-descriptor-field
    extraction path (`parseDeviceInfo`) that no existing test exercises —
    `DescriptorParserTest.parse*` and `RealCaptureTest` only assert the
    *endpoint/interface* topology from `parse()`, never the device-identity
    fields (`idVendor`/`idProduct`/`bcdDevice`/class/`numConfigurations`/
    `configurationValue`). *Assertion:* load
    `testdata/simple-gamepad-descriptors.bin` (the F310 capture, reuse the
    `locateTestData` walk-up already in `RealCaptureTest`/this class), call
    `DescriptorParser.parseDeviceInfo(raw, /* speed */ 2)`, and assert the known
    identity: `idVendor == 0x046d`, `idProduct == 0xc21d`,
    `bcdDevice == 0x4014`, `deviceClass == 0xff`, `numConfigurations == 1`,
    `numInterfaces == 1`, `configurationValue ==` the captured value, and
    `speed == 2` (the passed-in arg round-trips). This is the single genuinely
    new, JVM-testable code path M7 introduces.
  - **No L1 test for `AndroidUsbBackend` / `ServerService` / `MainActivity`.**
    They import `android.*` and are not JVM-testable (same situation as the M6
    spike). Do not add instrumented/Robolectric tests (would pull a
    third-party/test-only dependency — Invariant 5 — and is out of scope). The
    real proof for these is the L3 gate.

- **Fixtures / testdata:** none new — reuses the existing
  `testdata/simple-gamepad-descriptors.bin` (F310, `046d:c21d`, `bcdDevice
  0x4014`, vendor-specific class `0xff`, 1 config, 1 interface).

- **L2 regression (unchanged, not a new test):** the desktop harness still
  serves the fake device — `./gradlew :desktop:run`, then `usbip attach -r
  127.0.0.1 -b 1-1` and `evtest` on Fedora — because `core` is unchanged apart
  from the additive helper. State this held (or is expected to hold) but write no
  new L2 test.

- **L3 — the real gate (run by the user on the TV; no Android SDK/TV in the
  authoring/CI env).** Procedure and pass condition:
  1. `./gradlew :android:installDebug` to the TV; plug in the generic gamepad.
  2. In the app: (grant USB permission when prompted), press **Start**. The
     foreground notification appears and stays up.
  3. On Fedora (same network, `vhci-hcd` loaded):
     - `usbip list -r <tv-ip>` lists the device with the right busid `1-1` and
       VID:PID.
     - `sudo usbip attach -r <tv-ip> -b 1-1` succeeds.
     - `lsusb` shows the device on Fedora.
     - `evtest` on the new input node shows **live buttons/axes** as the physical
       pad on the TV is moved.
  4. Detach (`sudo usbip detach -p 0`) and **Stop** in the app release cleanly;
     the notification clears; the TV can be Started again.
  - **Pass:** a real device's input appears on the PC over the network via the
    TV (the MILESTONES.md M7 gate), and the session stays up under continuous
    input. Mirror the M6 framing: **code complete; L3 hardware gate pending, run
    by the user** — the authoring environment has no Android SDK / no TV.

## Pass gate

- `./gradlew :core:test` **green** (existing suite + the one new
  `parseDeviceInfo` case).
- `./gradlew :android:assembleDebug` **compiles** (the `:android` module builds
  with the real backend/service/activity).
- **L3 (the real gate), met on hardware by the user:** `usbip attach` to the
  TV's IP with the generic gamepad, and `evtest` on Fedora shows real input
  coming through the TV; the foreground notification stays up; clean detach/Stop.

`./gradlew :core:test` is expected to stay green because `core` is unchanged
apart from the additive, side-effect-free `DescriptorParser.parseDeviceInfo`
helper (no change to `parse`, `net`, `engine`, or `protocol`).

## Risks / open questions

The implementer should **stop and flag** rather than guess if any of these turns
out differently:

1. **`speed` default.** No Host API getter exists. The PRD specifies full-speed
   (`2`), matching `FakeUsbBackend`. It is believed cosmetic (vhci re-reads
   descriptors after import), but if a real attach rejects or mis-classifies the
   device over speed, revisit. **Open question:** is `2` correct, or should the
   backend attempt to infer speed some other way?
2. **Retain vs remove `HostApiSpike`.** PRD recommends **retain** (unwired) — the
   M6 gate is MET and §2 references it. **Open question / decision to confirm:**
   keep the class as an unwired M6 diagnostic (with the documenting comment), or
   delete it now that the real backend supersedes it? Do not leave it silently
   unused either way.
3. **`requestWait()` single-thread + shutdown correctness.** Exactly one thread
   may call `requestWait()`; shutdown must reliably unblock it. PRD specifies a
   `running` flag plus `connection.close()` as the backstop (as the M6 spike
   does), optionally the `requestWait(timeout)` form. **Open question:** does the
   TV firmware reliably unblock a parked `requestWait()` on `close()` (the spike
   suggests yes)? If not, the timeout-poll form is the fallback.
4. **`UsbRequest` per-submit vs reuse.** PRD mandates per-submit (correctness
   first) and defers pooling/reuse to **M9** (latency). Flagged so the
   implementer does not pre-optimize.
5. **Factory-does-open vs handed-an-open-connection.** PRD chooses the factory
   performing open + forceClaim itself (matches the M6 spike the gate validated,
   keeps claim policy + endpoint-map build together). **Open question:** if the
   service or activity already needs the open connection for another reason,
   revisit and pass it in instead.
6. **Reset / re-enumeration is explicitly M8, not M7.** If the generic pad (or a
   later G29) re-enumerates or mode-switches mid-session, M7 does **not** recover
   — that is M8/M9. Do not add reset handling here; flag if the test device
   forces the issue.
7. **`configurationValue` source.** PRD reads `bConfigurationValue` from the
   first configuration descriptor in `parseDeviceInfo`. Confirm the captured
   F310 value matches what the L1 assertion expects (read it from the fixture
   when writing the test); if `UsbConfiguration.getId()` and the descriptor
   disagree on a real device, prefer the descriptor (single source of truth,
   already tested).
