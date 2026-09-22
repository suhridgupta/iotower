# PRD: M3 — DEVLIST negotiation (`core/net`)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M3 — DEVLIST negotiation (`core/net`)**. Architecture **§4** and
**§4.1** (negotiation phase, `OP_REQ_DEVLIST`/`OP_REP_DEVLIST` layout, the
`usbip_usb_device` (312B) and `usbip_usb_interface` (4B) structs), plus **§6**
(server loop, one client at a time, `tcpNoDelay`) and **§3** (the backend seam).
Layers **L1 + L2**.

The server skeleton (`UsbIpServer`) already listens on `0.0.0.0:3240`, accepts a
client, sets `tcpNoDelay`, and calls `handle(client)` — which today just closes
the socket with two TODOs. **All eight wire struct codecs exist from M1**
(`OpHeader`, `UsbIpUsbDevice`, `UsbIpUsbInterface`, …) and the descriptor walk
exists from M2 (`DescriptorParser` → `EndpointMap`, with `interfaceCount()` and
per-endpoint `interfaceNumber`). This PRD wires those together to answer
`OP_REQ_DEVLIST` and returns one exported device with its interface list.

`usbip list -r <ip>` is the driving client. `usbip attach` / `OP_REQ_IMPORT`
(0x8003) is **M4 and explicitly out of scope** here.

## Scope

**Delivers:**

- `UsbIpServer.handle()` reads the 8-byte `OpHeader`, and when the code is
  `OP_REQ_DEVLIST` (0x8005) writes a complete, correctly-framed
  `OP_REP_DEVLIST` (0x0005) reply: `OpHeader` (version `0x0111`, code `0x0005`,
  status 0), a `u32` exported-device count of **1**, one `usbip_usb_device`
  (312B), then exactly `bNumInterfaces` `usbip_usb_interface` (4B) blocks — one
  per interface, carrying that interface's class/subclass/protocol.
- A minimal, **device-agnostic** way to obtain the per-interface
  class/subclass/protocol triple from the descriptors: extend `DescriptorParser`
  to also record a per-interface `InterfaceInfo` list on the `EndpointMap`
  (see Approach). No VID/PID logic, no device table.
- Stable, invented identity for the exported device (`busid`, `busnum`,
  `devnum`, `path`, `devid`) held in one place so **M4 IMPORT reuses the exact
  same values**.
- A small, production-invisible **testability affordance** on `UsbIpServer` so an
  L1 test can bind an ephemeral port and cleanly stop the server thread.
- **L1** in-process integration test (`DevlistNegotiationTest`) that speaks the
  DEVLIST bytes over loopback and asserts the decoded reply field-by-field, plus
  the **L2** `usbip list -r 127.0.0.1` check on the user's box.

**Out of scope (do NOT gold-plate):**

- **`OP_REQ_IMPORT` (0x8003) and the transfer phase.** M4. `handle()` must
  recognise a non-DEVLIST op code and close the socket cleanly with a clear
  `TODO(M4)` — it does **not** parse the import busid, does **not** switch to the
  transfer phase, does **not** send an `OP_REP_IMPORT`. State this in a comment.
- **Multiple exported devices / a 0-device path.** v1 is one backend = one
  device (README: "one device and one client at a time"). Advertise exactly one
  device; the count is hardcoded to 1. Do **not** build a device list, a
  0-device branch, or a `devid`-routing table (that is v2, §10). No test for the
  empty-list path — no such code path exists in v1.
- **Any `UsbBackend` interface change.** `rawDescriptors()` + `deviceInfo()` are
  sufficient (see Approach §"Backend data"). Do not add a method. If the
  implementer believes one is unavoidable, **stop and flag** rather than adding
  it.
- **Endpoint routing / claiming / transfers.** M4/M5. M3 parses descriptors only
  to extract the interface triples and count for the reply.
- **Multi-config / alternate-setting handling.** Reuse M2's first-config-only
  walk unchanged; the interface list records the alt-setting-0 interfaces only
  (see Approach).

## Approach

### 1. Where the interface class/subclass/protocol triple comes from

**Decision: extend the M2 descriptor walk to record a per-interface
`InterfaceInfo`, not parse in the net layer.** The `net` layer already parses
`rawDescriptors()` once (to learn the interface count); having it *also*
re-walk the bytes for interface classes would duplicate the walk and put
descriptor-offset knowledge in two places. The descriptor walk is the one place
that already dispatches on `INTERFACE` descriptors — adding three field reads
there is minimal and keeps all USB-descriptor-offset logic in `DescriptorParser`
(single source of truth), exactly as M2 kept endpoint decoding there.

Concretely:

- **New value type** `com.iotower.core.usb.InterfaceInfo` — immutable,
  public-final-field style matching `EndpointInfo`:
  `int interfaceNumber, bInterfaceClass, bInterfaceSubClass, bInterfaceProtocol`.
- **`EndpointMap`** gains an insertion-ordered `List<InterfaceInfo>` with
  `addInterface(InterfaceInfo)` and `List<InterfaceInfo> interfaces()`
  (returned unmodifiable, like `all()`). Leave `add/get/all/size/interfaceCount`
  untouched.
- **`DescriptorParser`**, in its existing `INTERFACE` (`0x04`) case, reads the
  standard 9-byte interface descriptor (USB 2.0 §9.6.5), which it already
  length-checks against `MIN_LEN_INTERFACE = 9`:

  | offset | field | width |
  |---|---|---|
  | +2 | bInterfaceNumber | u8 (already read for `currentInterface`) |
  | +3 | bAlternateSetting | u8 |
  | +5 | bInterfaceClass | u8 |
  | +6 | bInterfaceSubClass | u8 |
  | +7 | bInterfaceProtocol | u8 |

  All single-byte, so **no endianness concern** (the LE/BE trap from M2 applies
  only to the multi-byte `wMaxPacketSize`; leave that code as-is). It appends an
  `InterfaceInfo` **only when `bAlternateSetting (offset +3) == 0`**, so the
  list holds exactly one entry per interface, in interface-number order, and
  alternate settings do not create duplicates. (v1 targets have no alt settings;
  this rule just keeps the list = one-per-interface if one ever appears.)

**Invariants preserved:** device-agnostic — this is a generic descriptor field
read, no VID/PID branch, identical for the F310 and the G29; `core` stays
Android-free (plain `java.*`); the big-endian *wire* invariant is untouched (the
triple bytes are u8; the BE codecs in `core/protocol` are unchanged).

**Ripple check:** `InterfaceInfo` is new and additive; the `EndpointMap`
additions are new methods; `EndpointInfo` and `DescriptorParser.parse`'s
signature are unchanged. Existing call sites (`DescriptorParserTest`,
`RealCaptureTest`, `FakeUsbBackend`) do not break — verified: none construct
`EndpointMap` interface state, and `parse(byte[])` still returns an
`EndpointMap`. `RealCaptureTest` continues to pass unchanged (the F310 interface
descriptor is 9 bytes, so offsets +5/+6/+7 are in-bounds).

### 2. Invented identity (busid/busnum/devnum/path/devid) — one home, reused by M4

The server invents these and must reuse them consistently for M4 IMPORT (§4.1:
"You invent `busid`/`busnum`/`devnum` yourself and reuse them consistently").
Put them in **one place**: a small package-private immutable holder
`com.iotower.core.net.ExportedDevice` (or static constants on `UsbIpServer` —
implementer's choice, but they must be defined once and referenced, not
re-typed). Concrete v1 values:

| field | value | notes |
|---|---|---|
| busid | `"1-1"` | the string `usbip attach -b 1-1` will name in M4 |
| busnum | `1` | |
| devnum | `1` | |
| path | `"/sys/devices/tv/1-1"` | any stable string (§4.1) |
| devid | `(busnum << 16) \| devnum` = `0x00010001` | the §4.2 transfer-phase `devid`; **not** written in the DEVLIST reply, but define it here so M4 reuses it |

These are constants (one device in v1); they do not come from the backend. The
`usbip_usb_device` **identity** fields, by contrast, come from
`backend.deviceInfo()` and the parsed descriptors:

- `idVendor, idProduct, bcdDevice, bDeviceClass, bDeviceSubClass,
  bDeviceProtocol, bConfigurationValue, bNumConfigurations, speed` ← straight
  from `deviceInfo()`.
- `bNumInterfaces` ← `deviceInfo().numInterfaces`, and **the reply emits exactly
  that many `usbip_usb_interface` blocks** so the struct field and the block
  count are consistent by construction (the single most important wire-framing
  property). For each interface index `i` in `[0, bNumInterfaces)`, take the
  triple from the parsed `interfaces()` list at index `i`; if the list is
  shorter than `bNumInterfaces` (a malformed/backend inconsistency that should
  not happen for the F310), emit `(0,0,0)` for the missing entries rather than
  emitting fewer blocks than the count claims. The normal path
  (`interfaces().size() == deviceInfo().numInterfaces`) emits each real triple.
  See Risks for the deviceInfo-vs-descriptor consistency note.

### 3. The `handle()` read/dispatch flow

Rewrite the body of `handle(Socket)` (keep the one-client-at-a-time accept loop
in `run()` unchanged, §6):

1. Get the socket `InputStream` / `OutputStream`.
2. **Read exactly 8 bytes** for the `OpHeader` using a full-read helper
   `readFully(InputStream, byte[])` that loops `read()` until the buffer is full
   or EOF (a single `read` may return fewer than 8 bytes). On EOF before 8 bytes
   (client closed) → close the socket and return, no error.
3. Decode via `OpHeader.readFrom(ByteBuffer.wrap(headerBytes).order(BIG_ENDIAN))`.
   Note: `usbip list` sends **only** the 8-byte header for DEVLIST — there is
   **no request body** to read.
4. `switch (header.code)`:
   - **`UsbIp.OP_REQ_DEVLIST`**: build the reply (step 5), write it, flush, then
     close the socket. (`usbip list` opens a fresh connection per command and
     closes after reading the reply, so one request per accepted connection is
     correct and simplest — do not loop.)
   - **`UsbIp.OP_REQ_IMPORT`**: `TODO(M4)` — log the received import request and
     close the socket without replying. Do not parse the busid or enter the
     transfer phase.
   - **default**: log the unexpected op code and close.
5. **Build the DEVLIST reply** into a single `ByteBuffer` allocated
   `BIG_ENDIAN`, sized exactly
   `OpHeader.BYTES + 4 + UsbIpUsbDevice.BYTES + n*UsbIpUsbInterface.BYTES`
   where `n = deviceInfo().numInterfaces`:
   - `new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_DEVLIST, UsbIp.STATUS_OK).writeTo(buf)`
   - `buf.putInt(1)` — exported-device count.
   - `usbipUsbDevice.writeTo(buf)` — built per step 2 (identity from
     `deviceInfo()`, invented path/busid/busnum/devnum).
   - for each interface `i`: `new UsbIpUsbInterface(cls, sub, proto, 0).writeTo(buf)`.
   - Write `buf.array()` (0..`buf.position()`) to the `OutputStream`, `flush()`.

**Guards (invariant 6 — a long-running server must never NPE / die mid-session):**
`handle()` wraps its work in `try { ... } catch (IOException | RuntimeException e)
{ log; }` and closes the socket in a `finally`, so a null `rawDescriptors()` /
`deviceInfo()` or a parse failure closes the one connection instead of killing
the `accept` loop. Guard `backend.rawDescriptors()` and `backend.deviceInfo()`
for null before use (close the connection with a logged message if either is
null).

### 4. Backend data — no interface change

`UsbBackend` exposes `rawDescriptors()` and `deviceInfo()`. The server parses
`rawDescriptors()` **once** per DEVLIST via `DescriptorParser.parse(...)` to get
the `interfaces()` triples (and could use `interfaceCount()` as a cross-check),
and reads identity from `deviceInfo()`. **No `UsbBackend` method is added or
changed.** Confirmed sufficient; `FakeUsbBackend` already serves the real F310
capture and a consistent `DeviceInfo`, so **no desktop change is needed** for
M3 beyond what this PRD adds to `core`.

### 5. Testability affordance for the ephemeral port

The current constructor takes a fixed port and `run()` creates the
`ServerSocket` locally, so a test cannot learn the actual port when binding to
`0`, nor unblock `accept()` to stop the thread. Add the **minimal** affordance,
invisible to production (Android/desktop keep using the fixed-port constructor
and never call these):

- Keep a `private volatile ServerSocket serverSocket;` assigned right after
  `bind` in `run()`.
- Add `private final java.util.concurrent.CountDownLatch boundLatch = new
  CountDownLatch(1);` and `private volatile int boundPort = -1;`. In `run()`,
  immediately after a successful `bind`: `boundPort = serverSocket.getLocalPort();
  boundLatch.countDown();`.
- Add `public int awaitBoundPort(long timeoutMillis) throws InterruptedException`
  — awaits the latch up to the timeout and returns `boundPort` (or `-1` on
  timeout). This lets a test construct `new UsbIpServer(backend, 0)`, start
  `run()` on a thread, and learn the OS-assigned port race-free.
- Extend `stop()` to also `close()` the `serverSocket` (guarded for null),
  unblocking the blocked `accept()`; the resulting `SocketException` is swallowed
  by the existing `if (running) e.printStackTrace()` guard because `running` is
  already `false`. This is a real lifecycle fix (today `stop()` cannot actually
  stop a server blocked in `accept()`), not test-only scaffolding.

No change to the two constructors or to the production call path.

## Files to touch

Add:

- `core/src/main/java/com/iotower/core/usb/InterfaceInfo.java` — the new triple
  value type.
- `core/src/test/java/com/iotower/core/net/DevlistNegotiationTest.java` — the L1
  test (new package under test; nothing there today).

Edit:

- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — implement
  `handle()`, add the invented-identity constants/holder, add the
  `awaitBoundPort` + `serverSocket`/`stop()` affordance.
- `core/src/main/java/com/iotower/core/usb/EndpointMap.java` — add the
  `List<InterfaceInfo>` + `addInterface` + `interfaces()`.
- `core/src/main/java/com/iotower/core/usb/DescriptorParser.java` — in the
  existing `INTERFACE` case, record an `InterfaceInfo` (alt-setting-0 only).

Do **not** touch `core/protocol` (M1 codecs are complete and correct),
`EndpointInfo`, `UsbBackend`, `DeviceInfo`, `desktop/`, or `android/`. If the
implementer finds `desktop`/`android` need a change, **stop and flag**.

## Test plan

- **Layers:** L1 (`:core:test`) + L2 (desktop harness + stock `usbip`).

- **New cases (only new ones):**

  1. **`DevlistNegotiationTest.devlistReturnsExportedDevice`** — the one L1
     integration test. Purpose: prove the end-to-end DEVLIST path
     (read header → parse → build reply → frame on the wire) that **no existing
     test covers** (M1 tests single struct codecs in isolation; M2 tests the
     parser in isolation; nothing exercises `UsbIpServer.handle()` or the
     assembled multi-struct reply).

     *Setup:* a tiny in-test `UsbBackend` (a private static class or lambda-style
     impl in the test file) that returns:
     - `rawDescriptors()` → an **inlined synthetic** descriptor blob: device
       descriptor (18B) + configuration descriptor (9B, `bNumInterfaces = 1`) +
       interface 0 descriptor (9B: `bInterfaceNumber 0`, `bAlternateSetting 0`,
       `bInterfaceClass 0x03` HID, `bInterfaceSubClass 0x00`,
       `bInterfaceProtocol 0x00`) + a HID class descriptor (9B, type `0x21`,
       proves skip) + an interrupt-IN endpoint `0x81` (7B). This mirrors M2
       Fixture A; keep the bytes **byte-identical** to the in-session harness
       (see Pass gate). Other `UsbBackend` methods throw
       `UnsupportedOperationException` (M3 never calls them).
     - `deviceInfo()` → `new DeviceInfo(0x046d, 0xc294, 0x0100, 0,0,0, 1, 1, 1, 3)`
       (idVendor 0x046d, idProduct 0xc294, bcdDevice 0x0100, class/sub/proto 0,
       bConfigurationValue 1, bNumConfigurations 1, **bNumInterfaces 1**,
       speed 3).

     *Drive:* construct `new UsbIpServer(backend, 0)`, run it on a daemon thread,
     `int port = server.awaitBoundPort(2000)`. Open a `Socket("127.0.0.1", port)`.
     Write the 8-byte request built with the existing codec:
     `new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_DEVLIST, 0).writeTo(buf)` (BE),
     send, flush. Read the reply with a `readFully` helper into exact-size
     buffers and decode **with the existing codecs' `readFrom`** (do not
     hand-roll byte parsing).

     *Assert (enumerate):*
     - `OpHeader`: `version == 0x0111`, `code == UsbIp.OP_REP_DEVLIST` (0x0005),
       `status == 0`.
     - device count `u32 == 1`.
     - `UsbIpUsbDevice` (312B decoded): `busid.equals("1-1")`, `busnum == 1`,
       `devnum == 1`, `idVendor == 0x046d`, `idProduct == 0xc294`,
       `bNumInterfaces == 1` (and `path.equals("/sys/devices/tv/1-1")`).
     - exactly **one** `UsbIpUsbInterface` block follows, decoded:
       `bInterfaceClass == 0x03`, `bInterfaceSubClass == 0`,
       `bInterfaceProtocol == 0`.
     - the server closed the connection: the next `read()` returns `-1` (proves
       the reply length is exactly what was framed — no trailing/short bytes).

     *Teardown:* `server.stop()`; join the thread with a short timeout.

     The **"no device advertised" (count 0) path is not tested** — v1 has no such
     code path (count is hardcoded 1); adding it would be gold-plating (CLAUDE.md
     "only new code paths"). One test method suffices; do not split into several
     classes.

- **Fixtures / testdata:** none as files. The descriptor blob is **inlined** in
  the test (and duplicated byte-identically in the in-session harness), same
  rationale as M1/M2: it is synthetic, spec-derived, and short, so keeping the
  bytes next to the asserting code makes them auditable. Do **not** add a `.bin`
  under `testdata/` — a synthetic blob there would be mistaken for a real
  capture (the M2 caveat). The **real** cross-check is L2 against the F310
  capture `FakeUsbBackend` already serves.

- **L2 (user's Fedora box):** `./gradlew :desktop:run`, then
  `usbip list -r 127.0.0.1` prints the fake device — busid `1-1`, VID:PID
  `046d:c21d` (the F310 the existing `FakeUsbBackend` serves). No desktop-module
  change is required for this (confirmed: `FakeUsbBackend` already returns the
  F310 capture + a matching `DeviceInfo`; the parser reads its interface triple
  generically). This is the milestone's real-wire validation against the
  in-kernel client and is **run by the user**.

## Pass gate

Two runs, exactly as M1/M2 framed it — the in-session VM **cannot run Gradle**
(Maven Central egress is blocked by org policy: 403 through the proxy, only
github.com reachable, so the JUnit runner cannot be fetched):

1. **In-session (no Gradle, no JUnit runner).** Compile **all** `core` main
   sources with `javac --release 11` — this must succeed, proving `handle()`,
   the `InterfaceInfo`/`EndpointMap`/`DescriptorParser` deltas, and the
   `UsbIpServer` ephemeral-port affordance build cleanly. Then run a throwaway
   **plain-`java` assertion harness** (a `main`, not committed) that reproduces
   the L1 test end-to-end in-process: it uses the in-test fake backend, starts
   `UsbIpServer` on port 0, `awaitBoundPort`, connects over loopback, sends the
   `OP_REQ_DEVLIST` bytes, decodes the reply with the real codecs, and prints
   `PASS`/`FAIL` for every assertion in case 1. **Keep the harness's descriptor
   fixture and `DeviceInfo` byte-identical to the committed test** so it
   validates the same thing. **Gate:** main sources compile and the harness
   prints `PASS` for all assertions. Because the JUnit runner is unfetchable
   here, the committed `DevlistNegotiationTest` is **not executed in-session** —
   its canonical run is gate (2); say so in the completion note.

2. **Canonical (user's machine).** `./gradlew :core:test` on the user's Fedora
   box (JDK 17) — runs `DevlistNegotiationTest` plus all existing M1/M2 tests,
   all green — **and** the L2 check: `./gradlew :desktop:run` then
   `usbip list -r 127.0.0.1` shows the fake device with busid `1-1` and VID:PID
   `046d:c21d`. Both are the **user's** to re-run; state this in the completion
   note.

## Risks / open questions

- **Synthetic L1 fixture caveat (same as M1/M2).** The in-test descriptor blob
  and `DeviceInfo` are synthetic and spec-derived, not captured — no bound USB
  device/root in-session. The parser and reply assembly are device-agnostic, so
  the synthetic single-interface device exercises the same code path a real
  capture would; the **real** cross-check is the L2 `usbip list` against the F310
  the desktop harness serves. Flag, don't paper over.
- **`bNumInterfaces` source: `deviceInfo()` vs parsed descriptors.** This PRD
  uses `deviceInfo().numInterfaces` for both the struct field and the emitted
  block count (consistent by construction) and fills triples from the parsed
  list by index, with a `(0,0,0)` fallback if the list is short. For the F310
  both agree (1). **Open question for the orchestrator:** confirm you're happy
  driving the count from `deviceInfo()` rather than from the parsed
  `interfaces().size()`. The alternative (drive everything from the descriptor
  walk, treat `deviceInfo().numInterfaces` as redundant) is also defensible;
  I chose `deviceInfo()` because that field exists precisely to fill this struct
  (§4.1) and it keeps the count and blocks trivially consistent. Either way the
  wire is well-formed; this only matters if a backend ever reports a
  `numInterfaces` that disagrees with its own descriptors (a backend bug).
- **Testability affordance on `UsbIpServer`.** `awaitBoundPort`, the stored
  `serverSocket`, and `stop()` closing it are new public/behavioral surface. They
  are justified (the L1 test needs an ephemeral port and a clean shutdown, and
  `stop()` genuinely couldn't stop a blocked `accept()` before). If the
  implementer finds a smaller affordance that still lets the test learn the port
  race-free, that's acceptable — but do not make the test hardcode a fixed port
  (flaky under parallel CI).
- **Alternate settings.** Recording only `bAlternateSetting == 0` interfaces
  keeps `interfaces()` one-per-interface. v1 targets have none. If a real device
  ever exports the wheel on a non-zero alt setting, revisit — **stop and flag**
  rather than silently changing the rule.
- **IMPORT boundary (M4).** `handle()` deliberately closes on `OP_REQ_IMPORT`
  with a `TODO(M4)` and does not enter the transfer phase. The invented
  identity constants (`busid "1-1"`, `devid 0x00010001`, …) are defined now so
  M4 reuses them verbatim; do not relocate or rename them without updating M4's
  expectations. If the implementer thinks M3 must partially handle IMPORT to
  make DEVLIST work, that's wrong — **stop and flag**.
- **`UsbBackend` unchanged.** If implementation reveals a genuine need to change
  the `UsbBackend` seam (it should not), **stop and flag** rather than editing
  the interface — that ripples into `AndroidUsbBackend` and `FakeUsbBackend`.
