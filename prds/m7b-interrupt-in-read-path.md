# PRD: M7b — interrupt-IN read path (demand-driven delivery + diagnostics)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

Fixes the open blocker on the **M7** L3 gate (MILESTONES.md "M7 — Android
integration"; bug write-up: `bugs/m7-interrupt-in-empty-reports.md`).
Architecture: §5 (transfer engine), §5.1 (reader → engine → writer, seqnum
arbitration), §8 (Android service / backend). The only seam touched is
`com.iotower.core.usb.UsbBackend`; just `:android` implements it and `:core`
stays Android-free (Invariant 2).

What the 2026-10-04 hardware run (full logcat analysed) actually showed — this
supersedes the "endpoint is permanently halted" leading hypothesis in the bug
doc:

- `bulkTransfer` DID read one real 20-byte F310 report on `0x81` **after** attach
  (`bulkRead ep=0x81 n=20 ... (#1000)`). The endpoint is not dead and
  `bulkTransfer` can read it once a client is driving it.
- That report was **dropped**: it arrived with `waiters=0`. The current
  free-running reader discards any `n>0` read that completes when no client URB
  is queued (`readLoop`: `pollFirst()` → if `null`, data lost).
- The reader **busy-spins**: once the endpoint errors it returns `-1`
  immediately, ~120k reads/s with no backoff (`#1000` → `#2.65M` in ~22 s).
- Client IN URBs are almost never present as waiters (`waiters=0` throughout,
  `=1` exactly once). IN URBs are only ever answered on a *successful* read, so
  any URB that doesn't coincide with a success is never completed — a protocol
  gap — and there is no seqnum-level logging to see why.
- The only non-descriptor control traffic on attach is `SET_CONFIGURATION(1)`
  (29×, every one `ret=0`) and an XInput class-init request (succeeds). No
  `SET_INTERFACE`, no `CLEAR_FEATURE`.

Design history to respect: the committed M7 backend (`75a691a`) was a
`UsbRequest`-per-URB model (demand-driven, but `requestWait` hides URB status, so
errors looked like 0-byte successes). The investigation WIP (`0aed9f1`) replaced
it with a free-running `bulkTransfer` pump — which gained explicit `-1` status but
introduced the drop + spin above. This PRD keeps `bulkTransfer` (for its explicit
status) but makes it **demand-driven** again, so we get both: real status *and*
no dropped/orphaned URBs.

## Scope

Delivers:

- A **demand-driven** interrupt/bulk-IN read path in `AndroidUsbBackend`: each
  client IN URB drives exactly one device read bound to that URB. Every IN URB is
  eventually completed (with data, or with an error status); no successful report
  is ever read without a URB to receive it; and there is no busy-spin.
- **Comprehensive, toggleable diagnostics** across the seam — backend URB
  lifecycle + read-state transitions + a 1 Hz per-endpoint summary, and
  engine-side seqnum-level `CMD_SUBMIT` / `RET_SUBMIT` / `CMD_UNLINK` — so the
  next capture shows exactly where reports are lost.
- Retains the ep0 control-transfer logging already present in `controlTransfer`.

Explicitly OUT of scope (do not gold-plate — these are a deliberate follow-up PRD
once the logs confirm the trigger):

- `CLEAR_FEATURE(ENDPOINT_HALT)` recovery on `0x81`.
- Intercepting / not-forwarding the redundant `SET_CONFIGURATION`.
- Any change to the OUT path (`0x02`, worker-pool `bulkTransfer`) or the ep0
  control path beyond logging.
- Any device-specific logic (Invariant 1 — F310/G29 must not be named anywhere;
  route by `(endpoint, direction)` and generic endpoint type only).

## Approach

Replace the free-running reader + lossy waiter FIFO with a **per-IN-endpoint
single worker thread that reads only on demand**, one read bound to the head
client URB.

Read model (per interrupt/bulk-IN endpoint):

- `submitIn(ep)` — unchanged seam: create a `UsbTransfer`, append it to that
  endpoint's URB FIFO, signal the worker, return it.
- Worker loop:
  1. Block until the URB FIFO is non-empty or shutdown. (No demand → no reads →
     no spin and nothing to drop.)
  2. Peek the head URB (do not remove yet). Keeps all `bulkTransfer` calls on one
     thread per endpoint (single-threaded endpoint access).
  3. `n = connection.bulkTransfer(ep, buf, maxPacketSize, READ_TIMEOUT_MS)`.
  4. `n > 0`: remove the head URB; complete it with
     `Result(status=0, copyOf(buf, n), n)`; reset the consecutive-error counter.
  5. `n == 0` (timeout / NAK — on-change device simply had nothing): leave the URB
     queued and loop. The URB legitimately stays pending, as on real hardware;
     the client CMD_UNLINKs it if it wants it back (handled by `cancel`). The
     200 ms timeout itself paces this — no extra sleep.
  6. `n < 0` (device/endpoint error): remove the head URB; complete it with an
     error status (`-EPIPE = -32`, the USB stall/endpoint-error status a client
     expects — distinct from `-ECONNRESET = -104`, which means cancelled);
     increment the consecutive-error counter; sleep a short backoff before
     looping (flat ~10 ms) so a persistently erroring endpoint cannot busy-spin.
  7. Re-check `running` each iteration.
- `cancel(transfer)`: remove it from whichever endpoint FIFO holds it; if not
  already done, complete with `-ECONNRESET`. Same contract as today, new
  structure.
- `close()`: stop workers; drain and fail every queued URB with `-ECONNRESET`;
  release interfaces and close the connection as today (connection.close()
  unblocks any in-flight `bulkTransfer`).

Why this removes each observed defect:
- Dropped `n=20`: a read happens only when a URB is queued and its result goes to
  that URB — a success can never be discarded.
- Spin: reads happen only under demand; errors back off — bounded rate.
- Orphaned URBs: every URB completes on data or error; only a no-data timeout
  keeps it pending (correct interrupt semantics).

Diagnostics (all behind a single `static final boolean DEBUG = true;` per file,
trivially flipped off later; logging must not change behaviour or break
`:core:test`):

Backend (`Log.i`, tag `IoTowerBackend`):
- `urbIN ep=0x81 submit depth=<queueLen>` — on each `submitIn`.
- `urbIN ep=0x81 deliver n=<n> depth=<queueLen>` — on each successful delivery.
- `urbIN ep=0x81 error status=-32 consec=<k> depth=<queueLen>` — on each error
  completion, rate-limited (first few, then every 500th) so a stall can't flood.
- `urbIN ep=0x81 cancel depth=<queueLen>` — on cancel.
- **1 Hz per-endpoint summary** (the key instrument):
  `urbIN ep=0x81 1s reads=<> ok=<> timeout=<> err=<> depth=<> maxDepth=<>`.

Engine (`:core`, `System.out`, `[iotower]` prefix → appears under logcat tag
`System.out`; keeps `:core` Android-free, matching the file's existing logging
convention):
- `[iotower] CMD_SUBMIT seq=<> ep=<> dir=IN|OUT len=<>` — in `submit`.
- `[iotower] RET_SUBMIT seq=<> ep=<> dir=<> status=<> len=<>` — in
  `completeSubmit`, plus a `RET_SUBMIT seq=<> suppressed(unlinked)` line when the
  inflight-remove race drops it.
- `[iotower] CMD_UNLINK target=<seq> found=<bool>` — in `unlink`.

Together these answer the open question directly: do IN `CMD_SUBMIT`s arrive, are
they answered, and are they unlinked before data?

Invariants preserved: device-agnostic routing by `(ep, direction)` and generic
endpoint type only (Invariant 1); `:core` stays Android-free, all engine logging
via `System.out` (Invariant 2); wire stays big-endian (untouched, Invariant 4);
null Host-API returns stay guarded (Invariant 6).

## Files to touch

- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — replace
  the free-running `readLoop` + waiter-drop with the demand-driven per-endpoint
  worker + URB FIFO; adjust `submitIn` / `cancel` / `close`; add the backend
  diagnostics and the 1 Hz summary. Keep the existing `controlTransfer` logging.
  Update the class Javadoc EXPERIMENT note to describe the demand-driven model
  (it currently documents the free-running experiment).
- `core/src/main/java/com/iotower/core/engine/TransferEngine.java` — additive
  seqnum-level `CMD_SUBMIT` / `RET_SUBMIT` / `CMD_UNLINK` logging behind `DEBUG`,
  via `System.out`. No semantic change.
- Docs audit (feature-workflow step 3, same commit): append this run's findings
  and the read-path redesign to `bugs/m7-interrupt-in-empty-reports.md`; update
  the `MILESTONES.md` M7 status note (read path reworked to demand-driven; L3
  still pending). Touch `architecture.md` only if a §5/§8 sentence now
  contradicts the backend — the read model is a backend implementation detail, so
  at most a one-line note.

## Test plan

- **Layers:** L1 (regression only) + L3 (the real gate); L2 optional smoke.
- **L1 (`:core:test`):** the engine change is additive logging → **no new test**;
  the existing suite must stay green (DoD). Do not add a test that only exercises
  logging. *Only if* the implementer finds the engine's IN-error framing path
  (a `RET_SUBMIT` with a non-zero status and no payload) is genuinely not covered
  by any existing engine test, add **one** case to the existing engine test class
  (extend/parameterize, do not create a new class): a backend IN transfer that
  completes with status `-32` frames a `RET_SUBMIT` carrying status `-32` and
  zero payload. State which existing test was extended and why it was uncovered.
- **L2 (optional, desktop + stock `usbip`):** `FakeUsbBackend` already streams
  scripted `0x81` reports; confirm `evtest` still sees that stream through the
  engine with logging on — a manual regression smoke check, not a new test.
- **L3 (the gate, on the TV):**
  - `./gradlew :android:installDebug`
  - `adb logcat -c && adb logcat -s IoTowerBackend System.out AndroidRuntime:E`
    (note the added `System.out` tag — it carries the `[iotower]` engine lines)
  - Fedora: `sudo modprobe vhci-hcd; sudo usbip attach -r <tv-ip> -b 1-1`
  - `evtest` the F310 and move sticks / press buttons.
- **Fixtures / testdata:** none new.

## Pass gate

Primary (the M7 L3 gate): moving the pad produces `EV_ABS` / `EV_KEY` events in
`evtest` on the PC — real input over the network.

This PRD is additionally done — and the investigation unblocked even if the
hardware still stalls — when the capture shows the read-path defects are gone and
the fault is localised:

1. **No busy-spin** — the 1 Hz summary shows a bounded `reads` rate (hundreds,
   not tens of thousands) even while the endpoint is erroring.
2. **No dropped success** — every `deliver n>0` has a URB to go to; no `n>0` read
   occurs with an empty queue.
3. **No orphaned URBs** — every IN `CMD_SUBMIT seq=…` is matched by a later
   `RET_SUBMIT seq=…` (data or error) or a `CMD_UNLINK target=…`.
4. The log unambiguously shows whether IN `CMD_SUBMIT`s arrive and whether device
   reads succeed / error / time out per URB — enough to choose the follow-up
   (endpoint-halt recovery vs server-side URB handling).

`:core:test` green is a hard requirement either way.

## Risks / open questions (stop and flag — do not guess)

- **`-EPIPE` vs `-ECONNRESET` for IN errors.** Spec uses `-EPIPE (-32)` for a
  device read error and reserves `-ECONNRESET (-104)` for cancel/close. If the
  stock `usbip` client reacts badly to a flood of `-EPIPE` (e.g. tears the import
  down), stop and flag — the alternative is to keep erroring URBs pending (don't
  answer) + backoff. Do not silently invent a third behaviour.
- **Timeout = keep pending.** On-change interrupt endpoints return no data for
  long stretches; the design keeps the URB queued on `n==0`. If this regresses
  the FakeUsbBackend L2 stream (which completes IN promptly), flag it rather than
  answering timeouts with empty `RET_SUBMIT`s.
- **Concurrency.** IN reads (per-endpoint worker) and OUT (`0x02`, worker pool)
  share one `UsbDeviceConnection` but different endpoints; if any Host-API
  instability shows up under concurrency, flag it.
- **DEBUG volume.** Per-URB engine logging at interrupt rates is heavy; keep it
  behind `DEBUG`. If it measurably perturbs timing (Heisenbug), note it and lean
  on the 1 Hz summaries.
- **Does not fix the stall itself.** If reads still mostly error after attach,
  that is the expected handoff to the follow-up PRD (CLEAR_FEATURE /
  SET_CONFIGURATION handling). This PRD's job is correct delivery + instrumentation.
