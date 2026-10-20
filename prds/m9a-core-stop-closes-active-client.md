# PRD: M9a — `UsbIpServer.stop()` closes the active client connection

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

Sub-task of **M9 — Robustness** (§6 clean client disconnect, §7/§8 reset-reconnect).
Found during the M9 gate-1 L3 run (F310, 2026-10-19/20): on an unplug the TV
detected the device gone, parked the readers and released the wake lock, but
`evtest` on the PC never dropped the device and the serve-loop thread did not
exit. Root cause is in `:core`, so it is split out here with its own L1 test per
the feature workflow (a `:core` touch requires an L1 case).

### Root cause

`UsbIpServer.stop()` closes only the listening `ServerSocket`. During an active
session the serve-loop thread is blocked in `readFully(in, …)` on the **client**
socket (the transfer-phase loop), not in `accept()`. Unplugging the USB device
does not close the TCP connection to the PC, so that read blocks indefinitely.
Closing the listener does not unblock it, so:

1. `ServerService.teardownLocked()`'s `serverThread.join(1500)` times out (seen in
   the log as a ~1.3 s gap between "parking readers" and "wake lock released").
2. The `iotower-server` thread and its `TransferEngine` worker/writer threads leak
   — one set per reset cycle.
3. The client TCP connection is never closed, so the PC's `vhci-hcd` keeps the
   phantom device and `evtest` never quits; a manual re-attach then collides with
   the stale port.

The clean-detach path (PC runs `usbip detach`) is unaffected: it closes the
socket, `readFully` hits EOF, `runTransferPhase` returns and its `finally` already
calls `engine.shutdown()`; `handle`'s `finally` closes the client; `run()`'s
`while (running)` loop exits.

## Scope

- `UsbIpServer.stop()` additionally closes the **currently active client socket**,
  so a teardown issued while a client is attached (device-gone reset, or any
  server-initiated stop) unblocks the serve loop promptly.
- Observable outcomes after the fix:
  - On a device-gone teardown, `ServerService`'s `serverThread.join(...)` returns
    well under its 1.5 s budget (no timeout); no `iotower-server` /
    `TransferEngine` thread leak across repeated resets.
  - The PC's USB/IP connection is closed by the TV when the served device
    disappears, so `vhci-hcd` drops the device and `evtest` exits (within ~1.5 s).
- The existing clean teardown path (engine shutdown in `runTransferPhase`'s
  `finally`, client close in `handle`'s `finally`, loop exit on `running=false`)
  is reused — do not duplicate it.

### Out of scope

- Any distinct "listening" vs "serving" **notification** state — leave the
  notification text as-is (that was a separate M9 gap and is not required here).
- Any PC-side automation (auto-detach, `vhci-hcd` reload, auto-reattach) — that is
  §9 / M10. This PRD only makes the **TV** close its side cleanly.
- `TransferEngine` internals and the `AndroidUsbBackend` gone-signal path (already
  landed in M9). No `android` change.
- Any protocol/wire change. No new op codes.

## Approach

In `UsbIpServer`:

- Track the connection currently being served in a `volatile Socket activeClient`
  (null when idle). Set it in the accept loop (or at the top of `handle`) when a
  connection is accepted; clear it in `handle`'s existing `finally` after the
  client is closed. Only one client is served at a time, so a single field is
  sufficient.
- In `stop()`, after closing the `ServerSocket`, also close `activeClient` if
  non-null (guard with try/catch, ignore `IOException` — the socket may already be
  closing). Closing the socket from the `stop()` thread while the serve thread is
  blocked in `readFully` is the standard way to unblock it: the read throws
  `SocketException`, `runTransferPhase` unwinds (its `finally` shuts the engine
  down), `handle`'s `finally` closes/clears the client, and `run()`'s
  `while (running)` sees `running == false` and exits.

Invariants preserved: **no third-party libraries** (plain `java.net`); **big-endian
wire** untouched (no protocol change); `:core` stays **Android-free** (no new
imports beyond `java.net.Socket`, already used); **device-agnostic** (no VID/PID
logic). Invariant 6 (never NPE mid-session) — the `activeClient` read in `stop()`
is a local-copy null-guard, matching the existing `serverSocket` pattern.

Thread-safety note for the implementer: `stop()` may race `handle` setting/clearing
`activeClient`. A `volatile` field with a local copy in `stop()` is enough — worst
case `stop()` closes a socket that `handle` is already finishing with, which is
harmless (double close is caught). Do not add locking that could deadlock against
the serve thread.

## Files to touch

- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — add the
  `activeClient` field, set/clear it around the served connection, close it in
  `stop()`.
- `core/src/test/java/com/iotower/core/net/<new or existing net test>` — the L1
  case below. Prefer extending the existing concurrency/import net test harness if
  it already stands up a real `UsbIpServer` over loopback with a fake backend;
  otherwise add one focused test class.

No `:android` change. No doc change beyond noting, in `MILESTONES.md` M9 status,
that the stop()-closes-client fix landed (do NOT mark the M9 gate MET — the L3
re-run is the user's).

## Test plan

- **Layer(s):** L1 (plus the user re-runs the L3 gate-1 sequence afterwards).
- **New case(s) — only this one:**
  - **`stop()` unblocks an attached client and the serve thread terminates.**
    Purpose: covers the previously-untested path where `stop()` is called *while a
    client is mid-session* (blocked in the transfer-phase read) — no existing test
    stops the server with a live attached client (the concurrency/import tests
    drive requests then let the client close, i.e. the EOF path). Steps: start a
    real `UsbIpServer` on an ephemeral port over loopback with a fake backend;
    connect a client and complete `OP_REQ_IMPORT` so the server is in the transfer
    phase and the serve thread is blocked reading; with no further command
    pending, call `server.stop()` from the test thread; assert (a) the serve
    `Thread.join(…)` returns within a short timeout (e.g. ≤2 s) — i.e. the thread
    actually exits, proving no leak — and (b) the client socket observes the close
    (its `InputStream.read()` returns -1 or throws). Reuse the existing loopback
    fake-backend scaffolding; parameterize/extend an existing net test if it
    already exposes it, rather than duplicating setup.
- **Fixtures / testdata:** none new — reuse the fake backend already used by the
  net tests (e.g. the one behind `ImportControlTest` / `ConcurrencyEngineTest`).

## Pass gate

- `./gradlew :core:test` green, including the new case; the new test fails against
  the current `stop()` (join times out) and passes with the fix — state that you
  confirmed the fail-without / pass-with direction, or if the toolchain is
  unavailable in the build env, say so and leave the gate to the user.
- `./gradlew :android:assembleDebug` still compiles (no `:android` change).
- L3 (user-run, not part of this PRD's done-in-env gate): re-run M9 gate 1 —
  on F310 unplug, `evtest` drops the device within ~1.5 s and
  `serverThread.join` no longer times out (no ~1.3 s park→release gap, no leaked
  `iotower-server` threads across repeated resets).

## Risks / open questions

- **Writer thread mid-write during close.** When `stop()` closes `activeClient`,
  the `TransferEngine` writer may be mid-write and get an `IOException`. That is
  expected during teardown and must not crash the process — confirm the engine's
  shutdown swallows it (it already runs in `runTransferPhase`'s `finally`). If the
  engine instead propagates it in a way that bypasses clean shutdown, **flag it**
  rather than widening the change.
- **Don't add locking that can deadlock.** The serve thread holds no lock while
  blocked in the read; keep `activeClient` a plain `volatile` with a local copy in
  `stop()`. If you find yourself needing a lock shared with the serve loop, stop
  and flag.
- **Scope creep.** If tempted to also add the "listening" notification state or a
  connection-state callback, don't — it's out of scope here (see Scope).
