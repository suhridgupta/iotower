# PRD: M7d — first-attach stall + post-unplug busy-loop

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

Cleanup of the two rough edges the **M7** L3 run left open and recorded in
`bugs/m7-interrupt-in-empty-reports.md` (RESOLVED section, "Known rough edges").
Both are post-gate polish, not blockers; both live entirely in `AndroidUsbBackend`
and build on **M7b** (demand-driven read path) and **M7c** (`SET_CONFIGURATION`
no-op + `CLEAR_FEATURE(ENDPOINT_HALT)` recovery). Architecture: §5/§5.1 (transfer
engine, the IN read loop), §7 (reset/lifecycle — issue 2 is the first backend-side
piece of it), §8 (Android lifecycle).

Seam: `com.iotower.core.usb.UsbBackend`. Only `:android` changes. `:core` is
untouched and stays Android-free (Invariant 2). Both fixes decide by transfer
type / standard request number only — no VID/PID, no device tables (Invariant 1).

**Measure before changing.** The M7 logs are retained. The implementer should
first reproduce each symptom with the existing logging and attribute the cause
from the timestamps, then apply the smallest fix that moves the number. If the
logs contradict the hypotheses below, **stop and flag** — do not "fix" a cause
the logs don't show.

---

## Issue 1 — ~24 s first-attach stall

### Symptom (recorded)
After `usbip attach`, the `0x81` endpoint errors (~5/s, each with
`clearHalt(ENDPOINT_HALT) -> ret=0`) for ~24 s before reports begin flowing. The
M7c interception + halt-clear *do* recover it — just slowly.

### Hypothesis
`ret=0` means each halt-clear succeeds, yet the next read still errors, and the
cadence is capped at one clear per `HALT_CLEAR_INTERVAL_MS` (1000 ms). So either
(a) recovery needs several clears and the 1 Hz cap stretches that to tens of
seconds, or (b) the endpoint genuinely needs a settling period after
claim/config and the first ~24 s of reads are expected to fail regardless of how
often we clear. These have different fixes, so the log must disambiguate first.

### Scope
Delivers (in `AndroidUsbBackend`):
- A **fast initial halt-clear schedule** on an IN endpoint that is erroring from
  the start: the first few clears fire quickly (e.g. an initial burst at the
  read cadence, bounded by count or a short window), then back off to the
  existing `HALT_CLEAR_INTERVAL_MS` steady rate. This caps ep0 traffic in a
  sustained-error state (the post-unplug case) while letting a transient
  attach-time stall clear in well under a second.
- **Timestamped instrumentation** sufficient to prove the stall shrank:
  transfer-phase start, first IN URB queued per endpoint, first successful read
  per endpoint (`t_first_ok`), and each halt-clear with its `ret`. The pass
  condition is expressed against `t_first_ok`.

OUT of scope (flag, don't gold-plate):
- Any change to the steady-state cadence once reports flow (that is working).
- Proactively poking the endpoint before the client submits its first IN URB
  **unless** the log shows the endpoint is already erroring before any client
  URB arrives — if so, flag it as a distinct finding before adding that path.
- Anything touching `:core`, ep0 semantics beyond the halt-clear, or OUT.

### Approach
Replace the single `lastHaltClearMs` gate in the `n < 0` branch of `readLoop`
with a small per-`EndpointReader` schedule: track clears-since-first-error (or a
first-error timestamp) and choose the next allowed interval from it — short while
`consecErr` is small and the endpoint has never delivered (`ok == 0`), widening
to `HALT_CLEAR_INTERVAL_MS` after the initial burst or once the endpoint has ever
succeeded. Keep `ERROR_BACKOFF_MS` as the read-loop backoff so the loop can't
spin. New tuning constants beside `HALT_CLEAR_INTERVAL_MS`
(e.g. `HALT_CLEAR_INITIAL_MS`, `HALT_CLEAR_BURST`). No new threads, no lock
changes.

---

## Issue 2 — post-unplug busy-loop

### Symptom (recorded)
Once the device is physically removed, the reader spins (~96/s) on an immediate
`-1` from `bulkTransfer`, each with `clearHalt(ENDPOINT_HALT) -> ret=-1`. (~96/s
matches the 10 ms `ERROR_BACKOFF_MS`: it is a hot loop of useless control
transfers + `-EPIPE` completions the client keeps re-driving, not a pure spin.)

### Key distinction
A **halted endpoint** on a present device returns a clear that *succeeds*
(`ret >= 0`, the M7c/attach case). A **gone device** fails the clear itself
(`ret < 0`) because the control transfer has nowhere to go. That `ret < 0` on the
halt-clear — i.e. ep0 itself failing, not just the IN endpoint — is the
backend-local "device is gone" signal, distinct from an ordinary endpoint stall.

### Scope
Delivers (in `AndroidUsbBackend`):
- **Detect a gone device** from within the backend: a run of IN read errors
  whose halt-clears also fail (`consecErr` past a threshold **and** the last
  halt-clear returned `< 0`). On detection, set a `volatile boolean deviceGone`.
- **Park the readers.** Once `deviceGone`, each reader stops polling and waits
  (on `inLock`, like the idle-no-URB wait) instead of re-reading every
  `ERROR_BACKOFF_MS`; no more halt-clear churn on ep0.
- **Fail submits cleanly while gone.** New IN/OUT URBs submitted after detection
  complete immediately with a disconnect status (not a spin, not a slow
  per-URB timeout). Proposed status `-ESHUTDOWN` (`-108`) — see open questions.
- **Surface the disconnect** so §7/M8 reset-recovery can hook it: a single clear
  log line on the gone→parked transition, and a backend-queryable state (e.g. an
  `isDeviceGone()` / a listener the service can poll or be notified on). The
  backend does **not** attempt to re-acquire — that is M8.

OUT of scope (this is M8, flag if the test device forces it):
- Re-acquiring the device when it returns, rebuilding the backend, or any
  `usbip` re-attach. M7d only detects + parks + surfaces.
- `ACTION_USB_DEVICE_DETACHED` broadcast handling in `ServerService`/
  `MainActivity` — that is the M8 reset-recovery entry point; M7d stays inside
  the backend so the signal exists for M8 to consume.
- Distinguishing "unplugged" from "re-enumerating" — both look gone here; M8
  decides what to do when a device reappears.

### Approach
In `readLoop`'s `n < 0` branch, after the (now scheduled) halt-clear, evaluate
the gone condition; on first detection flip `deviceGone`, complete the head URB
with the disconnect status, log the transition once, and `notifyAll()` so readers
re-evaluate. The `while (running)` reader wait-loop gains a `&& !deviceGone` guard
so parked readers block instead of polling. `submit` checks `deviceGone` up front
and completes immediately with the disconnect status. `close()` already fails
queued URBs and joins threads — confirm it also wakes parked readers (the existing
`notifyAll()` + `running=false` covers it). Reset `deviceGone`/`consecErr` only
via a fresh backend instance (M8 rebuilds), never silently mid-life.

---

## Files to touch
- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — the
  halt-clear schedule (issue 1); `deviceGone` detection + reader park + submit
  fast-fail + transition log + state accessor (issue 2); new constants and
  per-`EndpointReader` fields (`firstErrMs`/clear count, `lastHaltClearRet`). No
  other `:android` file (the service/UI hook is M8).
- Docs audit (same change): `architecture.md` §5.1 (note the gone→park state and
  the fast-initial halt-clear) and/or §7 (backend-side detect-and-park is the
  first half of reset recovery); `MILESTONES.md` M7d status; append an M7d result
  section to `bugs/m7-interrupt-in-empty-reports.md` recording the measured
  `t_first_ok` before/after and the post-unplug reads/s before/after.

## Test plan
- **Layers:** L3 is the real gate for both. No `:core` change ⇒ **no new L1
  test**; `./gradlew :core:test` must stay green (DoD). `./gradlew
  :android:assembleDebug` must compile.
- **L3 — issue 1:** `./gradlew :android:installDebug`; `adb logcat -s
  IoTowerBackend`; from Fedora `sudo usbip attach -r <tv-ip> -b <busid>`;
  measure `t_first_ok` (attach → first delivered report) across a few attaches.
- **L3 — issue 2:** with input flowing, physically unplug the pad; confirm the
  reader parks (reads/s drops to ~0, halt-clear churn stops) and the client sees
  clean disconnect-status completions rather than an endless `-EPIPE` flood.
- **Fixtures:** none new.

## Pass gate
1. **Issue 1:** first report after attach arrives in **well under the recorded
   ~24 s** — target sub-second in the common case — with the steady stream
   unchanged afterward. Exact target to be set from the measured baseline; state
   the before/after in the bug doc.
2. **Issue 2:** after unplug, the per-endpoint read rate falls to ≈0 (readers
   parked), no sustained `clearHalt -> ret=-1` churn, and submits complete with
   the disconnect status. `evtest`/client no longer sees a report flood.
3. `./gradlew :core:test` green and `:android:assembleDebug` compiles either way.

## Risks / open questions (stop and flag — do not guess)
- **Disconnect status code.** Proposed `-ESHUTDOWN` (`-108`) for "device gone",
  distinct from `-EPIPE` (`-32`, endpoint stall) and `-ECONNRESET` (`-104`,
  cancel/close). Confirm the stock vhci client tolerates it (it should treat any
  negative status as a failed URB). If it misbehaves, fall back to `-ENODEV`
  (`-19`) or the existing `-EPIPE`; flag the choice.
- **Gone-detection false positives.** A merely slow/halted endpoint on a *present*
  device must not be mistaken for gone and parked — hence requiring the
  **halt-clear itself to fail** (`ret < 0`), not just IN read errors. If a present
  device ever fails halt-clears transiently, the threshold must be high enough to
  ride it out; flag if the F310 shows transient ep0 failures.
- **Issue 1 cause (b).** If the log shows the endpoint simply needs ~tens of
  seconds to settle regardless of clear frequency (clears succeed, reads keep
  erroring), tightening the cadence won't help — **stop and flag**; the fix would
  lie elsewhere (client-side timing, or a one-shot post-claim settle), not in this
  loop.
- **Interaction with M8 reset recovery.** Issue 2 deliberately stops at
  detect+park+surface. Do not start re-acquiring the device here on a guess — the
  reappear/rebuild path is M8 (`prds/m8-*.md`) and needs the service-layer
  broadcast, not the backend alone.

---

## Implementation note (2026-10-19) — measured baseline supersedes the Issue 1 approach

The baseline capture (recorded in `bugs/m7-interrupt-in-empty-reports.md`,
"M7d baseline measurement") resolves the Issue 1 open question in favour of
**cause (b)**: during the 24 s stall every `CLEAR_FEATURE(ENDPOINT_HALT)`
returned `0` (success) yet reads kept erroring, each taking ~200 ms. So the
"fast initial halt-clear schedule" specified under Issue 1 above would not have
helped and was **not** implemented. Root cause instead: Android `bulkTransfer`
returns `-1` for a timeout identically to a stall, so idle (no-input) polls were
misread as endpoint errors.

**As built (both issues, one coherent change in `AndroidUsbBackend`):**
- **Issue 1 — timeout reclassification.** A `-1` whose read took ≳
  `TIMEOUT_FLOOR_MS` (150 ms, vs the 200 ms `READ_TIMEOUT_MS`) is treated as a
  no-data poll: keep the URB queued and re-poll; no `CLEAR_FEATURE`, no `-EPIPE`.
  This replaces the halt-clear-cadence approach and also removes the mid-stream
  dips. `consecErr` resets on a timeout so only *immediate* errors accumulate.
- **Issue 2 — gone detect + park (as specified).** Gone =
  `lastHaltClearRet < 0 && consecErr >= GONE_ERROR_THRESHOLD` (20). Read latency
  (≈0 ms immediate vs ≈200 ms timeout) corroborates but the failing halt-clear is
  the primary signal. `markDeviceGone` fails queued URBs `-ESHUTDOWN` (−108) and
  parks readers (`while (running && (deviceGone || urbs.isEmpty()))`); `submit`
  fail-fasts; `isDeviceGone()` surfaces it for M8.

Unchanged from the spec above: device-agnostic, `:core` untouched, no new L1 test,
status-code choice (`-ESHUTDOWN`), and the L3 pass gate. Build + L3 re-run on the
Fedora box still outstanding (the authoring/VM environment cannot build `:android`).
