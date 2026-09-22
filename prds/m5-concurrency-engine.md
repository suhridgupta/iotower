# PRD: M5 — Interrupt IN + the concurrency engine (`core/engine`)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M5 — Interrupt IN + the concurrency engine**. Architecture
§5 (URB → Host API mapping) and §5.1 (the reader → engine → writer concurrency
model), with §6 (TcpNoDelay, allocation discipline) as the latency backdrop.

M4 shipped a deliberately *provisional* single-threaded synchronous transfer
loop (`UsbIpServer.runTransferPhase`) that serves ep0 `GET_DESCRIPTOR` traffic
well enough to enumerate — correct only because enumeration is strictly serial —
and stubs every ep≠0 SUBMIT with `-EPIPE` and every UNLINK with an inert
status-0 ack. `TransferEngine` is still an empty stub. M5 replaces that loop
with the real asynchronous engine so interrupt IN/OUT transfers actually flow
and `CMD_UNLINK` actually cancels. **At the end of M5 the entire USB/IP protocol
is proven with zero Android and zero hardware.**

## Scope

Delivers:

- A real `TransferEngine` (`core/engine`) implementing the §5.1 model: a single
  **writer** thread that is the sole owner of the socket `OutputStream` (replies
  never interleave on the wire), a small **worker pool** for blocking ep0
  `controlTransfer` calls, and asynchronous correlation of non-control
  completions back to their `seqnum`.
- Interrupt/bulk **IN** transport: ep≠0 IN `CMD_SUBMIT` → `UsbBackend.submit`,
  whose asynchronous completion is framed back as `RET_SUBMIT` carrying the
  `actualLength` payload — keyed by `seqnum`, tolerant of **out-of-order**
  completion.
- Interrupt/bulk **OUT** transport: ep≠0 OUT `CMD_SUBMIT` (the FFB/rumble/LED
  path) → `UsbBackend.submit` with the read OUT payload → status-only
  `RET_SUBMIT`. No device-specific handling — it is just another transfer (§5).
- `CMD_UNLINK` → cancel the in-flight transfer (`UsbBackend.cancel`) and reply
  `RET_UNLINK`, **suppressing** any late `RET_SUBMIT` for the cancelled
  `seqnum`.
- `UsbIpServer.runTransferPhase` rewritten to be only the **reader** thread:
  parse frames, read OUT payloads, dispatch to the engine, and tear the engine
  down cleanly on detach (EOF) / desync / error.
- `desktop/FakeUsbBackend` fleshed out to **replay canned interrupt-IN reports**
  on the capture's IN endpoint (`0x81`) so the desktop harness produces a live
  input stream on the PC (the L2 gate), and to accept-and-drop OUT transfers on
  `0x02`.

Out of scope (do **not** gold-plate):

- No isochronous support (explicitly unsupported, §5).
- No Android code (`:android` / `AndroidUsbBackend` is M6/M7). `core` stays
  Android-free.
- No descriptor/endpoint-type inspection in the engine — route by
  `(ep, direction)` only; the engine never reads VID/PID or endpoint type
  (Invariant 1). The `EndpointMap` is **not** a dependency of the engine.
- No multi-device / multiple concurrent clients (v2, architecture §10). One
  device, one client at a time, as today.
- No PIN/TLS/companion (M10).

## Approach

### Threads (all plain Java, `java.util.concurrent`; no Android)

Per accepted-and-imported connection, `runTransferPhase` builds one
`TransferEngine`, `start()`s it, runs the reader loop on the accept thread, and
`shutdown()`s it in a `finally`. Next client → a fresh engine. Nothing is shared
between connections.

- **Reader** = the accept thread already in `runTransferPhase`. Sole reader of
  the socket. Parses the 20-byte `UsbIpHeaderBasic` + 28-byte block, reads the
  OUT payload for OUT SUBMITs, and calls `engine.submit(...)` / `engine.unlink(...)`.
  It **never** blocks on a transfer (control now runs on the worker pool, not
  inline) and it **never** writes to the socket after the engine starts.
- **Writer** = one dedicated thread draining a `BlockingQueue<byte[]>` of
  fully-framed reply buffers, writing + flushing each to the `OutputStream`.
  Being the only writer, it guarantees replies never interleave (§5.1) and it
  flushes every frame (§6, low latency). Stopped by a private poison-pill
  sentinel enqueued from `shutdown()`.
- **Worker pool** = a small fixed `ExecutorService` (2 threads) that runs the
  blocking `backend.controlTransfer` for ep0 SUBMITs, so a slow control transfer
  cannot stall the reader. ep≠0 submits do **not** use the pool — `backend.submit`
  is asynchronous and returns immediately.

### Correlation, out-of-order, and cancel — one source of truth

A `ConcurrentHashMap<Integer seqnum, Inflight>` is the single source of truth.
`Inflight` holds the request header echo fields (`seqnum`, `direction`, `ep`)
and, for ep≠0, the `UsbTransfer` handle (for cancel). The whole correctness of
out-of-order + cancel rests on **`remove(seqnum)` having exactly one winner**:

- On completion of any transfer, the completing code does
  `if (inflight.remove(seqnum) != null) enqueue RET_SUBMIT`. If the entry is
  gone (already unlinked), the completion is silently dropped.
- On `CMD_UNLINK(target)`: `Inflight e = inflight.remove(target)`. If `e != null`
  and it carries a `UsbTransfer`, call `backend.cancel(e.transfer)`. Then enqueue
  `RET_UNLINK` (status 0) regardless (a target that already completed simply
  isn't found — status 0, "nothing to unlink").

Because both the completion path and the unlink path go through
`ConcurrentHashMap.remove`, exactly one of them wins for a given `seqnum`: a
cancelled transfer never also emits a `RET_SUBMIT`, and a transfer that
completes a hair before its unlink is acked with a bare `RET_UNLINK`. No locks,
no double-reply, no lost reply.

### URB → backend routing (§5, mechanical, device-agnostic)

- `ep == 0` (control): decode the 8-byte setup packet **little-endian** (the one
  LE spot; every enclosing USB/IP frame is big-endian). For IN allocate
  `buffer[transferBufferLength]`, for OUT use the read payload. Run
  `backend.controlTransfer(...)` on the worker pool. `ret < 0` → status = ret,
  actualLength = 0; else status = 0, actualLength = ret. IN with data → payload.
  (Behaviour identical to M4's `handleControlSubmit`, now off the reader thread.)
- `ep != 0` (interrupt/bulk): `endpointAddress = ep | (direction == DIR_IN ? 0x80 : 0)`.
  - IN: allocate `buffer[transferBufferLength]`, `backend.submit(addr, DIR_IN,
    buffer, transferBufferLength)`; on completion frame `RET_SUBMIT` with
    `min(result.actualLength, result.data.length)` payload bytes.
  - OUT: `backend.submit(addr, DIR_OUT, outPayload, transferBufferLength)`; on
    completion frame a status-only `RET_SUBMIT` (`actualLength` from the result).
- `result.status` (0 or `-errno`) is copied straight into `RET_SUBMIT.status`.

Framing reuses the existing `UsbIpHeaderBasic` + `RetSubmit` / `RetUnlink`
codecs (big-endian) exactly as `writeRetSubmit` does today; move that framing
into the engine.

### Shutdown (no thread leaks, no NPEs — Invariant 6)

`shutdown()`: for every remaining `Inflight` with a `UsbTransfer`, best-effort
`backend.cancel` (releases Host-API requests on Android); clear the map; stop the
worker pool (`shutdownNow`, short `awaitTermination`); enqueue the writer poison
pill and `join` the writer with a timeout. `shutdown()` must **not** close the
`UsbBackend` — the backend outlives a single client connection (desktop process,
Android service). Guard every `backend` return that can be null/absent so a bad
client disconnect closes the connection but never kills the server.

### FakeUsbBackend (desktop, L2 enabler)

Implement `submit` so the harness produces live input with no hardware:

- IN on `0x81`: complete the transfer after the endpoint's poll interval (~4 ms)
  with the next frame from a small **scripted report cycle** (a canned XInput
  20-byte report sweeping one axis / toggling one button), using a
  `ScheduledExecutorService`. The vhci client re-submits continuously, so a
  steady report stream flows → `evtest` on the PC shows moving axes/buttons.
- OUT on `0x02`: complete immediately, status 0, `actualLength = length`
  (accept-and-drop; the fake has nothing to actuate).
- `cancel`: cancel the pending scheduled completion (complete with
  `-ECONNRESET` / mark done) so `UNLINK` releases it.
- `close`: shut the scheduler down.

Keep `controlTransfer` and `deviceInfo`/`rawDescriptors` as they are (still the
F310 capture). The engine never sees any of this — it stays device-agnostic.

## Files to touch

- `core/src/main/java/com/iotower/core/engine/TransferEngine.java` — implement
  (replace the stub): writer thread, worker pool, inflight map, `start` /
  `submit` / `unlink` / `shutdown`, and the RET framing.
- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — rewrite
  `runTransferPhase` to construct/start/shutdown the engine and act only as the
  reader; delete the provisional `-EPIPE` stub, the inline synchronous
  `handleControlSubmit`, and the inline `writeRetSubmit` (framing moves to the
  engine). Keep IMPORT/DEVLIST untouched. `EXPORTED_DEVID` is passed to the
  engine.
- `desktop/src/main/java/com/iotower/desktop/FakeUsbBackend.java` — implement
  `submit` (scripted IN reports on `0x81`, accept-and-drop OUT on `0x02`),
  `cancel`, and `close` (scheduler teardown); drop the "TODO(M5)" note.
- `core/src/test/java/com/iotower/core/net/ConcurrencyEngineTest.java` — **new**
  L1 test (see below).

## Test plan

- **Layers:** L1 (new JUnit integration test over loopback) + L2 (desktop
  harness + stock `usbip` + `evtest`, run on the Fedora box).

- **New cases (and only new ones)** — one new test file, two `@Test`s, driving
  the *real* `UsbIpServer` over a loopback socket (same harness style as
  `ImportControlTest`), each with a purpose-built in-test `UsbBackend` whose
  completion timing the test controls:

  1. `outOfOrderInterruptCompletionsCarryCorrectSeqnums` — after IMPORT, send two
     interrupt-IN `CMD_SUBMIT`s (ep `0x81`, seqnum 1 then 2). The test backend
     hands each submitted `UsbTransfer` to the test via a queue; the test
     completes **seqnum 2's first**, then seqnum 1's, each with distinct payload
     bytes. **Assert** the first `RET_SUBMIT` read carries seqnum 2 + its
     payload and the second carries seqnum 1 + its payload — i.e. replies follow
     completion order, each correctly correlated, and neither stalls. Covers the
     async submit path + `seqnum` correlation + out-of-order + single-writer
     serialization — none of which any existing test touches (M4's
     `ImportControlTest` is ep0-only and strictly serial).
  2. `unlinkCancelsInflightAndSuppressesRetSubmit` — after IMPORT, send an
     interrupt-IN `CMD_SUBMIT` (ep `0x81`, seqnum 5) that the backend leaves
     in-flight; then send `CMD_UNLINK` targeting seqnum 5 (header seqnum 6).
     **Assert**: `backend.cancel` is invoked (latch) on the exact in-flight
     transfer; a `RET_UNLINK` (header seqnum 6, status 0) is read; and after the
     test then completes the cancelled transfer, **no** `RET_SUBMIT` for seqnum 5
     is ever read (the next read is EOF after detach). Covers the
     unlink→cancel→suppress path and the `remove`-wins race.

  Do **not** add a separate control-transfer test — `ImportControlTest` already
  covers ep0 and must continue to pass unchanged through the new engine (the
  regression guard is that it stays green). Do **not** add a standalone
  unit-level `TransferEngine` test whose coverage overlaps case 1/2.

- **Fixtures / testdata:** none new. Cases 1/2 synthesize their own frames and
  backend in-test. L2 continues to use `testdata/simple-gamepad-descriptors.bin`.

## Pass gate

- **L1:** `./gradlew :core:test` green, including the two new cases and the
  unchanged `ImportControlTest` / all M1–M3 tests.
- **L2 (Fedora box):** `./gradlew :desktop:run`, then
  `sudo usbip attach -r 127.0.0.1 -b 1-1`; the device appears and **`evtest` on
  the matching `/dev/input/event*` shows the fake's scripted button/axis events**
  streaming, with nothing plugged in; clean `sudo usbip detach`. This is the
  unambiguous "transfers work" gate and closes M5.

## Risks / open questions

- **Which PC driver binds the fake.** The capture is an F310 in **XInput** mode
  (vendor-specific class `0xff`, `046d:c21d`), so on the Fedora box `xpad` (not
  `usbhid`) binds it and `evtest` shows an `xpad` gamepad — that is fine for the
  gate (synthetic input still appears on the PC). If a future capture is a
  DirectInput/HID-class pad, `usbhid` binds instead; either way the server code
  is identical (device-agnostic). Implementer: do not tailor report bytes to a
  driver beyond a plausible 20-byte XInput frame — the point is *motion visible
  in evtest*, not fidelity.
- **Completion thread.** `UsbTransfer.completion` is a `CompletableFuture`; its
  `whenComplete` callback runs on whichever thread completes it (a backend
  thread). Keep that callback tiny (frame + enqueue) and allocation-light in
  steady state (§6). If the implementer finds the `whenComplete`-on-backend-thread
  model awkward for the single-writer guarantee, **stop and flag** — do not
  invent a second socket writer.
- **Backend lifecycle.** The engine must never `close()` the backend on detach
  (breaks desktop/Android reconnect). If the PRD and code seem to disagree here,
  **stop and flag** rather than guessing.
- **Environment note (not a design risk):** the sandbox that authored this has
  no Maven access, so the JUnit gate is executed in CI / on the Fedora box; the
  engine logic here was cross-checked with an equivalent standalone driver over
  loopback. The committed JUnit cases above are the authoritative gate.
