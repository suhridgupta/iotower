# PRD: M4 — IMPORT + control transfers → enumeration (`core/net`)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M4 — IMPORT + control transfers → enumeration**. Architecture
**§4.1** (`OP_REQ_IMPORT`/`OP_REP_IMPORT`, the 32-byte busid, "on success the
socket switches into the transfer phase — no more op headers, only URB
traffic"), **§4.2** (`usbip_header_basic` (20B), `USBIP_CMD_SUBMIT` (28B) +
optional OUT payload, `USBIP_RET_SUBMIT` (28B) + optional IN payload, the
`setup[8]` bytes for control transfers), and **§5** (the URB→Host-API mapping
table; "Control (ep0), setup[8] populated → `controlTransfer(...)`"; "control
transfers can't use `UsbRequest` … ep0 goes through the synchronous
`controlTransfer()` call"). Layers **L1 + L2**.

M3 left the server answering `OP_REQ_DEVLIST` and closing on `OP_REQ_IMPORT`
with a `TODO(M4)` (see `UsbIpServer.handle()`'s `OP_REQ_IMPORT` case). M4 removes
that stub: it parses the import busid, sends `OP_REP_IMPORT`, **switches the
socket into the transfer phase**, and serves ep0 control transfers so the stock
kernel client enumerates the exported device. `usbip attach -r 127.0.0.1 -b 1-1`
then `lsusb` / `lsusb -v` is the driving client.

The invented identity M3 defined once on `UsbIpServer`
(`EXPORTED_BUSID = "1-1"`, `EXPORTED_BUSNUM = 1`, `EXPORTED_DEVNUM = 1`,
`EXPORTED_PATH`, `EXPORTED_DEVID = 0x00010001`) is **reused verbatim** here —
IMPORT matches against `EXPORTED_BUSID` and the transfer-phase `devid` echoed in
replies is `EXPORTED_DEVID`. Do not relocate, rename, or re-derive them.

## Scope

**Delivers:**

- **`OP_REQ_IMPORT` (0x8003) handling.** `handle()`'s existing `OP_REQ_IMPORT`
  case reads the 32-byte busid body, NUL-trims it, and compares to
  `EXPORTED_BUSID`:
  - **match** → write `OP_REP_IMPORT` (`OpHeader` version `0x0111`, code
    `0x0003`, status 0) immediately followed by **one** `usbip_usb_device`
    (312B) built from the *same* fields M3's DEVLIST used (identity from
    `deviceInfo()`, invented path/busid/busnum/devnum). **No interface blocks** —
    unlike DEVLIST, the IMPORT reply is `OpHeader + usbip_usb_device` only
    (§4.1). Then **enter the transfer phase** (below) on the same socket without
    closing.
  - **no match / null backend data** → write `OP_REP_IMPORT` with a **non-zero
    status** and **no** device struct, then close the connection.
- **The transfer phase — control (ep0) served synchronously.** After a
  successful IMPORT, loop reading 48-byte messages (20-byte
  `usbip_header_basic` + 28-byte command block), routing by
  `(command, ep, direction)`:
  - **`CMD_SUBMIT` on ep 0 (control)** → decode the standard 8-byte setup packet
    from the `CmdSubmit.setup` field into `(bmRequestType, bRequest, wValue,
    wIndex, wLength)` (all wXxx little-endian, per the USB setup-packet layout —
    this is *protocol-level* decoding, not device-level), read the OUT payload
    when `direction == DIR_OUT`, call
    `backend.controlTransfer(bmRequestType, bRequest, wValue, wIndex, buffer,
    transfer_buffer_length, timeout)`, and write a `RET_SUBMIT`
    (`usbip_header_basic` with command `RET_SUBMIT`, the **echoed** seqnum,
    `EXPORTED_DEVID`, the echoed direction/ep, then the 28-byte `RetSubmit`
    block, then the IN payload — `actual_length` bytes — when `direction ==
    DIR_IN`). A negative return from `controlTransfer` becomes a negative
    `RetSubmit.status` with `actual_length == 0` and no payload.
  - **`CMD_SUBMIT` on ep ≠ 0** and **`CMD_UNLINK`** → **minimal, provisional
    stub replies** that keep the wire framed and detach clean (see Approach
    §4). These are placeholders explicitly replaced by the M5 engine; M4 does
    **not** implement real interrupt/bulk transport.
  - loop until EOF (client detached) or a read/parse error; close the socket in
    `finally` (invariant 6 — one bad connection must not kill the accept loop).
- **`FakeUsbBackend.controlTransfer` serves `GET_DESCRIPTOR` from its captured
  descriptor blob** so the desktop harness actually enumerates in `lsusb`
  (§ desktop change is required this milestone — see Approach §5). Device and
  configuration descriptors are synthesized from the F310 capture the fake
  already serves; all other control requests (strings, etc.) stall (negative
  return). The *server* stays a dumb pipe — this device-side interpretation
  lives only in the fake, which stands in for a real device.
- **L1** in-process test (`ImportControlTest`) — send IMPORT, assert
  `OP_REP_IMPORT`, then send an ep0 `GET_DESCRIPTOR` `CMD_SUBMIT` and assert the
  `RET_SUBMIT` payload. **L2** — `usbip attach` + `lsusb`/`lsusb -v` + clean
  detach on the user's Fedora box.

**Out of scope (do NOT gold-plate — this is the M4/M5 line):**

- **The asynchronous concurrency engine (all of §5.1) — M5.** Do **not** build
  the reader → engine → writer thread split, the single writer thread, the
  `seqnum → in-flight UsbRequest` `ConcurrentHashMap`, or any out-of-order
  completion. M4's transfer phase is a **single-threaded, strictly synchronous
  submit → controlTransfer → ret loop** running on the accept thread. This is
  correct *only* because enumeration control traffic is strictly serial (the
  kernel issues one ep0 request and waits for its reply before the next). It is
  deliberately **provisional** and will be **replaced** by `TransferEngine`
  (§5.1) in M5 — do not try to make it the permanent design, and do not start
  filling in `core/engine/TransferEngine.java` (leave it the stub it is).
- **Interrupt-IN streaming — M5.** No `UsbRequest.queue()`/`requestWait()` loop,
  no canned-report replay, no `evtest`. On ep ≠ 0, M4 emits only the provisional
  stub reply (Approach §4); it does not move real interrupt/bulk data.
- **`CMD_UNLINK` / cancel — M5.** M4 acknowledges an UNLINK with a well-formed
  `RET_UNLINK` (status 0) purely so detach is clean; it does not cancel a real
  in-flight transfer (there is no async engine to cancel in).
- **Multiple devices / clients, `devid` routing** — v2 (§10). One exported
  device, `EXPORTED_DEVID` echoed unconditionally.
- **Any `UsbBackend` interface change.** `controlTransfer(...)` already exists
  and is exactly the ep0 call M4 needs; `rawDescriptors()`/`deviceInfo()` cover
  the IMPORT reply. Do **not** add or change a method. If the implementer
  believes one is unavoidable, **stop and flag**.
- **Multi-config / string descriptors / SET_INTERFACE semantics.** The fake
  serves DEVICE and CONFIGURATION; it stalls the rest. `lsusb` enumerates
  without strings (they show as numeric indices) — that satisfies the gate. Do
  not synthesize string descriptors in the fake.

## Approach

### 1. IMPORT request/reply in `handle()`

Replace the `OP_REQ_IMPORT` `TODO(M4)` case body. The 8-byte `OpHeader` is
already read by `handle()`; the import body is a single **32-byte busid string**
(NUL-padded ASCII — the same fixed-width encoding `UsbIpUsbDevice` uses for its
`busid` field). Read it with the existing `readFully(in, byte[32])` helper,
NUL-trim to a `String`, and compare `.equals(EXPORTED_BUSID)`.

- **Match:** guard `backend.rawDescriptors()` / `backend.deviceInfo()` for null
  (close on null, as `handleDevlist` does). Build the reply into a
  `BIG_ENDIAN` `ByteBuffer` sized `OpHeader.BYTES + UsbIpUsbDevice.BYTES`:
  `new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT, UsbIp.STATUS_OK).writeTo(buf)`
  then the **same** `UsbIpUsbDevice` M3 built (factor the device-struct
  construction M3 open-codes in `handleDevlist` into a private
  `buildExportedDevice(DeviceInfo, int numInterfaces)` helper and call it from
  both DEVLIST and IMPORT, so the two replies can never drift). Write, flush,
  then call the transfer-phase loop (§3) — **do not close** here; the loop's
  caller closes in `finally`.
- **No match:** `new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT,
  /* status */ 1).writeTo(buf)`, write those 8 bytes, flush, return (the
  `finally` closes). No device struct follows an error status.

### 2. The phase switch

The negotiation phase is one-request-per-connection (M3). IMPORT is different:
on success the **same socket** carries URB traffic until detach. Structurally,
`handle()` keeps its "read one `OpHeader`, switch on code" shape; the
`OP_REQ_IMPORT` success path simply **does not return** after writing
`OP_REP_IMPORT` — it calls `runTransferPhase(in, out)` which loops until EOF.
DEVLIST still returns after one reply. The accept loop in `run()` is unchanged
(one client at a time, §6).

### 3. `runTransferPhase(InputStream, OutputStream)` — synchronous control loop

New private method on `UsbIpServer` (kept here, **not** in `TransferEngine`, so
the throwaway synchronous loop does not pollute the class that will hold M5's
async engine). Loop:

1. `readFully(in, basic[20])`; on EOF (client detached) return cleanly.
2. Decode `UsbIpHeaderBasic.readFrom(BIG_ENDIAN)` → `(command, seqnum, devid,
   direction, ep)`.
3. `readFully(in, block[28])`.
   - **`command == CMD_SUBMIT`:** decode `CmdSubmit.readFrom(BIG_ENDIAN)`. If
     `direction == DIR_OUT` and `transferBufferLength > 0`, `readFully` that many
     payload bytes. Then dispatch:
     - **`ep == 0` (control):** decode the setup packet from `cmd.setup`
       (bytes: `bmRequestType`, `bRequest`, `wValue` LE u16, `wIndex` LE u16,
       `wLength` LE u16 — a small private `decodeSetup` helper or inline). For
       IN, allocate `byte[transferBufferLength]`; for OUT, use the payload just
       read. Call `backend.controlTransfer(bmRequestType, bRequest, wValue,
       wIndex, buffer, transferBufferLength, CONTROL_TIMEOUT_MS)`. `ret = ` the
       return; `status = ret < 0 ? ret : 0`, `actualLength = ret < 0 ? 0 : ret`.
       Write the reply (§ below).
     - **`ep != 0`:** provisional stub — write a `RET_SUBMIT` with
       `status = -EPIPE` (`-32`), `actualLength = 0`, no payload (see §4).
   - **`command == CMD_UNLINK`:** decode `CmdUnlink.readFrom` (28B), write a
     `RET_UNLINK` (`UsbIpHeaderBasic` command `RET_UNLINK`, echoed seqnum,
     `EXPORTED_DEVID`, direction/ep from the basic header, then
     `new RetUnlink(0).writeTo`). Provisional (see §4).
   - **anything else:** log and return (close) — an unexpected transfer-phase
     command means a desynced stream.

**Writing a `RET_SUBMIT`** (helper `writeRetSubmit(out, seqnum, direction, ep,
status, data, actualLength)`): allocate a `BIG_ENDIAN` buffer sized
`UsbIpHeaderBasic.BYTES + RetSubmit.BYTES + (direction == DIR_IN ? actualLength
: 0)`; write `new UsbIpHeaderBasic(RET_SUBMIT, seqnum, EXPORTED_DEVID,
direction, ep)`, then `new RetSubmit(status, actualLength, 0, 0, 0)`, then, for
IN, `buffer[0..actualLength]`. `out.write(...); out.flush();`. Reuse the M1
codecs' `writeTo` — do not hand-roll bytes.

**Invariants preserved:** *device-agnostic* — the server decodes only the
generic setup packet and routes by `(ep, direction)`; it never branches on
`bRequest`, VID, or PID (GET_DESCRIPTOR vs SET_CONFIGURATION both just go to
`controlTransfer`). *core stays Android-free* — plain `java.*`, sockets,
`ByteBuffer`; the ep0 seam is the existing `UsbBackend.controlTransfer`.
*big-endian wire* — every USB/IP frame is `BIG_ENDIAN`; the **only** little-endian
read is the setup packet's wValue/wIndex/wLength, which are LE by the USB spec
(same LE-vs-BE care the descriptor parser already documents) — call this out in
a code comment.

### 4. Provisional non-control replies (the M4/M5 seam, stated precisely)

M4 serves control fully and answers everything else with a **well-formed but
inert** reply, for one reason only: keep the TCP stream byte-synchronized and
let detach complete cleanly without building the async engine. Concretely:

- **ep ≠ 0 `CMD_SUBMIT`** (e.g. the `xpad`/`usbhid` driver's interrupt-IN URB on
  `0x81` after it binds) → immediate `RET_SUBMIT` with `status = -EPIPE`.
- **`CMD_UNLINK`** → immediate `RET_UNLINK` status 0.

These are **not** interrupt-IN support and **not** cancel support — they are
throwaway acks. M5 replaces `runTransferPhase` with the real
reader/engine/writer model (§5.1), at which point interrupt-IN carries real
report data and UNLINK cancels a real in-flight `UsbRequest`. The implementer
must resist "improving" the stubs (e.g. actually queueing a transfer) — that is
M5 and would preempt its design. **If enumeration turns out to require real
interrupt-IN data (it should not — `lsusb` reads only the descriptors fetched
over ep0), stop and flag** rather than pulling M5 work forward.

### 5. `FakeUsbBackend.controlTransfer` — synthesize descriptors (desktop change)

For L2 to enumerate, the fake must answer the kernel's enumeration control
reads. Implement `controlTransfer` to recognize the **standard GET_DESCRIPTOR**
request (`bmRequestType == 0x80`, `bRequest == 0x06`; `descriptorType =
wValue >> 8`) and serve from the bytes it already returns from
`rawDescriptors()` (the F310 capture = an 18-byte device descriptor immediately
followed by a 48-byte configuration block, 66 bytes total):

- **DEVICE (type 0x01):** copy `min(length, 18)` bytes from offset 0 of the blob
  into `buffer`; return the count.
- **CONFIGURATION (type 0x02):** copy `min(length, 48)` bytes from offset 18
  (the config block, whose `wTotalLength` is 0x0030 = 48) into `buffer`; return
  the count. This naturally serves both the kernel's initial 9-byte probe and
  its full-length re-read.
- **everything else** (STRING, etc.): return a negative value (stall) — the
  device still enumerates; strings show as numeric indices in `lsusb -v`.

Keep this parsing generic (offset/length from the setup packet); do **not** add
VID/PID logic. The device descriptor's `bNumConfigurations`/config offsets are
read from the blob, not hardcoded past the two offsets above (18 is
`bLength` of the device descriptor — read `blob[0]` rather than the literal 18
if the implementer prefers; either is acceptable since the F310 device
descriptor is the standard 18 bytes). The fake's `submit`/`cancel` stay as they
are (M5 gives the fake its canned-report replay; a `TODO(M5)` note is fine).

### 6. Backend data — no interface change

`UsbBackend.controlTransfer(...)`, `rawDescriptors()`, `deviceInfo()` are
sufficient. **No method is added or changed.** `AndroidUsbBackend` is untouched
this milestone (M6/M7 flesh it out against the same contract; its
`controlTransfer` will call the real `UsbDeviceConnection.controlTransfer` and
answer strings for free).

## Files to touch

Edit:

- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — implement the
  `OP_REQ_IMPORT` case (busid parse + match/reject, `OP_REP_IMPORT`), the
  `buildExportedDevice(...)` helper shared with DEVLIST, `runTransferPhase(...)`,
  the `decodeSetup` + `writeRetSubmit` helpers, and the `CONTROL_TIMEOUT_MS`
  constant. Reuse the M3 identity constants and `readFully`.
- `desktop/src/main/java/com/iotower/desktop/FakeUsbBackend.java` — implement
  `controlTransfer` to serve GET_DESCRIPTOR (DEVICE/CONFIGURATION) from the
  captured blob; stall the rest.

Add:

- `core/src/test/java/com/iotower/core/net/ImportControlTest.java` — the L1
  in-process test (below).

Docs to audit & update **in this same commit** (feature-workflow step 3): mark
**M4 done** in `MILESTONES.md` (with what was gated and how); update
`README.md` if it states the transfer phase is unimplemented; note in
`architecture.md` §5.1 (or a one-line pointer) that the synchronous control loop
is the M4 provisional path superseded by the M5 engine, so the doc does not read
as if the async engine already exists. Do **not** touch `core/protocol` (M1
codecs are complete), `UsbBackend`, `DeviceInfo`, `EndpointMap`,
`DescriptorParser`, `TransferEngine`, or `android/`. If `android/` or
`TransferEngine` seems to need a change, **stop and flag**.

## Test plan

- **Layers:** L1 (`:core:test`) + L2 (desktop harness + stock `usbip`).

- **New cases (only new ones):** a single new class `ImportControlTest` reusing
  `DevlistNegotiationTest`'s idiom exactly (construct `new UsbIpServer(backend,
  0)`, run on a daemon thread, `awaitBoundPort(2000)`, loopback `Socket`,
  `readFully` helper, decode replies with the **real M1 codecs**, `server.stop()`
  + `join` in `finally`). Its private `FakeBackend` reuses
  `DevlistNegotiationTest`'s byte-identical simple-gamepad descriptor fixture for
  `rawDescriptors()`/`deviceInfo()`, and additionally implements
  `controlTransfer` to serve GET_DESCRIPTOR(DEVICE) from fixture bytes `[0,18)`
  and GET_DESCRIPTOR(CONFIGURATION) from `[18, 18+wTotalLength)` — mirroring the
  `FakeUsbBackend` logic so the test asserts the exact same payloads L2 will.

  1. **`importThenControlDescriptors`** — the milestone's core case. Purpose:
     prove the full M4 path — IMPORT parse/match → `OP_REP_IMPORT` framing →
     **phase switch** → ep0 setup decode → forward to `controlTransfer` →
     `RET_SUBMIT` framing + IN payload — none of which any existing test covers
     (M1 = struct codecs in isolation; M3 = DEVLIST only, never the transfer
     phase). On one connection:
     - Send `OP_REQ_IMPORT` (`OpHeader(VERSION, OP_REQ_IMPORT, 0)`) + a 32-byte
       NUL-padded `"1-1"` busid. Assert the reply `OpHeader`: `version 0x0111`,
       `code == OP_REP_IMPORT` (0x0003), `status == 0`; then a `UsbIpUsbDevice`
       (312B) with `busid "1-1"`, `busnum 1`, `devnum 1`, `idVendor 0x046d`,
       `idProduct 0xc294`, `bNumInterfaces 1`. Assert **no** interface block
       follows (the next bytes are the transfer phase, not a `usbip_usb_interface`).
     - Send a `CMD_SUBMIT`: `UsbIpHeaderBasic(CMD_SUBMIT, seqnum=1,
       EXPORTED_DEVID, DIR_IN, ep=0)` + `CmdSubmit(transferFlags=0,
       transferBufferLength=18, startFrame=0, numberOfPackets=0xFFFFFFFF,
       interval=0, setup={0x80,0x06,0x00,0x01,0x00,0x00,0x12,0x00})`
       (GET_DESCRIPTOR DEVICE, wLength 18). Assert the `RET_SUBMIT`:
       `UsbIpHeaderBasic` `command == RET_SUBMIT` (3), `seqnum == 1`,
       `devid == EXPORTED_DEVID`; `RetSubmit.status == 0`, `actualLength == 18`;
       and the 18 payload bytes `equals` fixture `[0,18)` (a device descriptor
       with `bDescriptorType == 0x01`).
     - Send a second `CMD_SUBMIT` for GET_DESCRIPTOR CONFIGURATION (setup
       `{0x80,0x06,0x00,0x02,0x00,0x00,0x30,0x00}`, `transferBufferLength = 48`,
       `seqnum = 2`). Assert `status 0`, `actualLength == 48`, payload `equals`
       fixture `[18,66)` (`bDescriptorType == 0x02`, `wTotalLength == 0x0030`).
       Purpose of the second submit: proves the loop **serves multiple
       sequential submits on one connection** (does not close after one) and
       handles a **different offset/length** — a path the single device-descriptor
       submit does not exercise.
  2. **`importUnknownBusidRejected`** — distinct branch nothing else covers.
     Send `OP_REQ_IMPORT` + 32-byte `"9-9"`. Assert `OP_REP_IMPORT` with
     `status != 0`, that **no** `usbip_usb_device` follows, and that the server
     closed the connection (`in.read() == -1`). Justified as a genuinely new
     code path (the reject branch); it is small and does not overlap case 1.

  **Deliberately not tested at L1:** the ep≠0 `-EPIPE` stub and the `RET_UNLINK`
  ack — they are provisional M4 behavior that M5 replaces, so locking them in a
  test is churn (CLAUDE.md: add a test only for a new code path worth keeping).
  "Clean detach" is validated at L2. No new `testdata/` file — the fixture is
  the synthetic blob already inlined in `DevlistNegotiationTest`; the real
  cross-check is L2 against the F310 capture the desktop harness serves.

- **L2 (user's Fedora box):**
  ```
  ./gradlew :desktop:run                 # in one terminal
  sudo modprobe vhci-hcd
  usbip list   -r 127.0.0.1              # still shows 1-1  046d:c21d (M3, regression; no root)
  sudo usbip attach -r 127.0.0.1 -b 1-1  # attach needs root (writes the vhci sysfs node)
  lsusb                                  # shows  ID 046d:c21d  (device enumerated)
  lsusb -v -d 046d:c21d                  # device + config + interface + endpoint
                                         #   descriptors match testdata/simple-gamepad-lsusb.txt
  usbip port                             # shows the attached port
  sudo usbip detach -p <port>            # clean detach (needs root), harness returns to listening
  ```

## Pass gate

Two runs, mirroring M1/M2/M3 (the in-session VM cannot run Gradle — Maven
Central egress is blocked by org policy, so the JUnit runner is unfetchable):

1. **In-session (no Gradle/JUnit).** Compile all `core` main sources **and**
   `desktop/.../FakeUsbBackend.java` with a JDK's `javac` (the release the repo
   targets); this proves `runTransferPhase`, the IMPORT case, the shared
   `buildExportedDevice`, and the fake's `controlTransfer` build cleanly. Then
   run a throwaway plain-`java` harness (a `main`, **not committed**) that: news
   the real `FakeUsbBackend`, starts `UsbIpServer` on port 0, `awaitBoundPort`,
   connects over loopback, sends `OP_REQ_IMPORT "1-1"`, then GET_DESCRIPTOR
   DEVICE and CONFIGURATION submits, and asserts each `RET_SUBMIT` payload
   `equals` the corresponding slice of `testdata/simple-gamepad-descriptors.bin`
   (`[0,18)` and `[18,66)`), printing `PASS`/`FAIL` per assertion. **Gate:**
   sources compile and the harness prints `PASS` for every assertion.
   *Tooling note for the implementer:* this Fedora box currently exposes only a
   JDK 11 `java` on `PATH` and **no `javac`**; locate the JDK 17 the Gradle build
   uses (`./gradlew` resolves it) for the compile step, or run this gate
   wherever `javac` is available. The committed `ImportControlTest` is **not**
   executed in-session (no JUnit runner); its canonical run is gate (2).
2. **Canonical (user's machine, JDK 17).** `./gradlew :core:test` green —
   `ImportControlTest` plus all existing M1/M2/M3 tests — **and** the L2 flow
   above: `usbip attach` succeeds, `lsusb` shows `046d:c21d`, `lsusb -v` matches
   `testdata/simple-gamepad-lsusb.txt`, and `usbip detach` returns the harness
   to a clean listening state. Both are the **user's** to re-run; say so in the
   completion note.

## Risks / open questions

- **Driver binding submits interrupt-IN before/around `lsusb` (the stub's
  reason to exist).** After `attach`, `xpad`/`usbhid` binds the F310 and submits
  an interrupt-IN URB on `0x81`. M4 answers it with `-EPIPE` so the stream stays
  framed and detach is clean; the descriptors `lsusb` needs are fetched over ep0
  regardless. If in practice the `-EPIPE` stall makes the kernel **fail the
  whole enumeration** (so the device does not appear in `lsusb` at all), that is
  the M4/M5 line moving — **stop and flag** rather than starting the interrupt-IN
  engine; the fallback is to leave the interrupt URB pending (no reply) instead
  of stalling it, which the implementer may try, but flag either way.
- **Synchronous loop vs the §5.1 async engine.** M4's single-threaded loop is
  provisional and correct only for serial control traffic. It must live in
  `UsbIpServer` (or a small private helper), **not** in `TransferEngine`, and it
  is expected to be deleted/replaced in M5. If the implementer finds themselves
  needing a second thread, a seqnum map, or `UsbRequest`-style queueing to pass
  the M4 gate, they have crossed into M5 — **stop and flag**.
- **`OP_REP_IMPORT` has no interface array.** Unlike DEVLIST, the IMPORT reply
  is `OpHeader + usbip_usb_device` only. Emitting interface blocks here would
  desync the transfer phase. Verify against the kernel's
  `Documentation/usb/usbip_protocol.rst`; the assertion in case 1 ("no interface
  block follows") guards this.
- **IMPORT error-status shape.** On busid mismatch the kernel client expects a
  non-zero `OP_REP_IMPORT` status and no trailing device struct. The exact errno
  is not load-bearing (any non-zero works for "not found"); if the stock client
  is fussy about a specific value, **flag** — but `status = 1` should suffice.
- **Setup packet endianness.** `wValue/wIndex/wLength` in `setup[8]` are
  **little-endian** (USB spec) while every enclosing USB/IP frame is big-endian.
  This is the one mixed-endianness spot in `core/net`; a wrong read here yields a
  descriptor type/length off by a byte-swap. Comment it, as the descriptor
  parser comments its LE `wMaxPacketSize`.
- **`FakeUsbBackend` is a desktop change this milestone.** M3 forbade touching
  `desktop`; M4 requires it (the fake must answer enumeration control reads).
  This is expected and in-scope — it does not change the `UsbBackend` seam, only
  the fake's implementation of an existing method. If the implementer thinks the
  *core* server needs to interpret GET_DESCRIPTOR to make `lsusb` work, that is
  wrong (it breaks device-agnosticism) — **stop and flag**.
- **`TransferEngine.java` stale comment.** Its javadoc says `TODO(milestone 3)`;
  the async engine is actually M5. Fixing that one-word reference is a fair
  docs-audit touch, but do **not** implement anything in that class for M4.
