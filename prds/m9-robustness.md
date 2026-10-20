# PRD: M9 — Robustness

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

`MILESTONES.md` **M9 — Robustness**. The passthrough is proven end-to-end
(protocol + engine off-device through M5; Host-API claim/read on the TV through
M7; G29 OUT/FFB + composite + manual reset recovery through M8; the first-attach
stall and post-unplug busy-loop polished in M7d). M9 does **not** add capability
— it makes a working session *survive* the real world: a device that resets
mid-session, a TV with its screen off, and a client that dies or is killed.

Architecture: §6 (latency/networking — `TCP_NODELAY`, Ethernet, the measurement
target), §7 (device transparency + reset/re-enumeration — the generic lifecycle
problem), §8 (Android lifecycle — foreground service, screen-off, the
attach/detach broadcasts that drive claim/reconnect). §5.1 for the engine
teardown that a client disconnect exercises.

Seam: this is **server-side robustness only**, almost entirely `:android`
(`ServerService`, `MainActivity`, `AndroidManifest.xml`), consuming the
backend-local gone signal M7d already surfaces (`AndroidUsbBackend.isDeviceGone()`).
`:core` is expected to stay untouched and Android-free (Invariant 2); the core
accept loop and `TransferEngine.shutdown()` already re-accept and already cancel
in-flight URBs on disconnect (verified below) — M9 verifies and hardens around
them rather than rewriting them. Nothing here is device-specific (Invariant 1):
reset handling keys off USB lifecycle events and the generic gone signal, never
VID/PID.

**Measure before changing.** Each of the three scenarios is a *verification*
gate first. Reproduce it at L3 with the existing logging, confirm whether the
current code already satisfies it, and add only the smallest fix for the gaps the
run actually shows. If the existing behavior already passes a sub-gate, record
that and add nothing. If a run contradicts an assumption below, **stop and flag**.

---

## Scope

Delivers three hardening outcomes plus a measurement, each independently
observable at L3:

- **Generic reset-reconnect (robustness pass over M8/§7).** A device that resets
  or re-enumerates mid-session (the G29 PC↔native mode flip is the concrete case;
  any resetting device is the general one) is recovered generically: the TV
  releases the gone device and re-claims it when it returns, the server returns to
  a serving/ready state, and the session is re-attachable. Builds on M8's
  detect-and-reconnect by **consuming the backend's `isDeviceGone()` signal in
  `ServerService`** so recovery is driven by the backend detecting the device is
  gone *and* by the `ACTION_USB_DEVICE_DETACHED` broadcast — not the broadcast
  alone — and so a fast re-enumeration that the broadcast races is still caught.
- **Foreground-service survival.** `ServerService` stays alive, foregrounded, and
  attached with the TV screen off for 10+ minutes, and input stays responsive. A
  partial `WakeLock` keeps the CPU serving while the screen is off; the service
  declares the correct `foregroundServiceType`.
- **Clean client disconnect.** Killing the PC client (`usbip detach`, or hard
  `kill`/cable pull) returns the server to a clean listening state: engine threads
  and worker pool torn down, in-flight URBs released at the Host API, no leaked
  threads or stuck endpoints across repeated connect→kill→reconnect cycles, and
  the notification reflects "listening" again. The backend (and the claimed
  device) outlives the connection, per §5.1.
- **Latency/jitter measurement (§6).** A documented, repeatable method to measure
  input latency and jitter at the PC, compared **Ethernet vs WiFi**, with the
  numbers recorded. This is instrumentation + procedure, not shipped app code.

**OUT of scope — do not gold-plate (these are M10 / §9, the optional PC
companion):**
- Any PC-side automation: auto-`usbip list`/`attach`, the auto-reattach loop, or
  recovering the stock client when `vhci-hcd` wedges after an abrupt disconnect
  (`usbip: error: open vhci_driver`). M9's reset gate is met with a **manual**
  `usbip attach` on the PC, exactly as M8's was. Note the wedge as context only.
- The PIN/TLS authenticating proxy (§9.2) — no accept-time auth changes.
- Masking a reset from the client / seamless re-enumeration (§7 "slicker build").
  M9 is detect → release → re-claim → re-attachable, not transparent-to-client.
- Multi-device / multi-client (v2, §10).
- Any new device-specific handling.

---

## Approach

### 1. Reset-reconnect — wire the gone signal into the service (§7, §8)

Today `AndroidUsbBackend` detects a gone device locally (M7d: `deviceGone`,
`isDeviceGone()`, readers parked, submits fail-fast `-ESHUTDOWN`), and recovery
is driven only by `MainActivity`'s `ACTION_USB_DEVICE_DETACHED`/`_ATTACHED`
receiver (§8 status). M9 makes `ServerService` an active participant so recovery
doesn't depend on the broadcast firing/winning the race:

- `ServerService` **observes** the backend going gone. Prefer a push over a poll:
  add a lightweight gone-callback the service registers on the backend (a
  `Runnable`/listener invoked once on the `deviceGone` transition M7d already logs
  — set beside `markDeviceGone`). A low-frequency poll of `isDeviceGone()` on an
  existing service thread is an acceptable fallback if a callback complicates the
  backend surface — **flag which was chosen**. Either way the backend change, if
  any, stays a tiny additive hook at the `UsbBackend`/`AndroidUsbBackend` seam,
  device-agnostic, `:core` untouched.
- On the gone transition the service runs the **same teardown it already has**
  (`teardownLocked()` — stop `UsbIpServer`, close the backend, join the server
  thread), serialized under `lifecycleLock`, and updates the notification to a
  ready/"waiting for device" state. It does **not** itself re-acquire — re-claim
  happens through the existing `ACTION_USB_DEVICE_ATTACHED` path in `MainActivity`
  when the device re-enumerates, which starts a fresh service session with a fresh
  backend (so `deviceGone`/`consecErr` reset exactly as M7d intended: "only via a
  fresh backend instance").
- Keep the DETACH broadcast path too; the two are complementary (broadcast =
  physical unplug/OS-visible detach; gone signal = the backend observing the
  endpoint/control path fail, which also covers a reset the broadcast is slow to
  report). Both converge on the one serialized teardown; ensure they can't
  double-teardown or race a concurrent re-attach (reuse `lifecycleLock` +
  `stopping`).

### 2. Foreground-service survival (§8)

- Hold a **partial `WakeLock`** (`PowerManager.PARTIAL_WAKE_LOCK`) for the serving
  lifetime: acquire when a session starts serving, release in `teardownLocked()`/
  `onDestroy()`. Partial wake lock keeps the CPU running with the screen off,
  which is what a socket server + USB reads need; it does not keep the screen on.
- Declare `foregroundServiceType` correctly. The service manages a connected USB
  device and a network socket — set the type (`connectedDevice`, plus
  `dataSync`/`connectedDevice` as the platform requires) in the manifest `<service>`
  entry and pass the matching type to `startForeground(...)` on API levels that
  require it (Android 10+/14 FGS-type rules). Add `WAKE_LOCK` (and any FGS-type)
  permission to the manifest.
- Confirm the existing persistent-notification + worker-thread model (§8 status)
  is retained; no `onStartCommand` work on the main thread (the ANR note stands).
- **TV firmware varies (§8).** Treat the exact combination (wake lock type, FGS
  type, any OEM battery/Doze exemption) as something to confirm on the actual TV,
  not assume. If 10 min screen-off still drops the session with a correct partial
  wake lock, **stop and flag** — the next lever (Doze/battery-optimization
  exemption prompt) is a product decision, not a guess.

### 3. Clean client disconnect (§5.1)

Core already does the right thing and this must be **verified, not rebuilt**:
`UsbIpServer.run()` loops `while (running)` on `accept()`; `runTransferPhase`
returns on EOF/truncated-frame/desync and its `finally` calls
`engine.shutdown()`, which cancels every still-in-flight URB via
`backend.cancel(...)`, clears the inflight map, `shutdownNow()`s the control
workers, and joins the writer — without closing the backend (it outlives the
connection). So after a client dies the server should fall back into `accept()`
with the device still claimed and endpoints idle-waiting.

M9's job:
- **Verify across repeated cycles** (connect → kill client → reconnect, ×N) that
  no engine/worker/writer threads leak, the backend's per-endpoint reader threads
  return to the idle `inLock.wait()` state (not spinning, not parked-gone), and a
  fresh `usbip attach` re-enters the transfer phase cleanly with input flowing.
- **Service-side reflection only:** on the reader thread seeing the serve loop
  return to listening, update the notification back to a listening state. No
  protocol change.
- **If and only if** the L3 cycle test shows a real leak (e.g. a stale URB from a
  dead connection completing into a torn-down writer, or a reader left wedged),
  fix it at the narrowest seam. The engine already cancels inflight on shutdown,
  so the likely culprit, if any, is backend-side reader state — keep the fix in
  `AndroidUsbBackend`. Touch `:core` only if the leak is provably in the engine's
  teardown; if so it stays a call to the existing `UsbBackend` seam (Android-free)
  and **must** come with an L1 test (see Test plan). Flag before making any
  `:core` change.

### 4. Latency/jitter measurement (§6)

The server never originates reports, so end-to-end latency needs a physical
stimulus; the practical, repeatable signal is **inter-report interval and its
jitter at the PC**, plus attach→first-report, measured identically on Ethernet
and WiFi:

- **Harness:** with input flowing, sample the arrival cadence of input events on
  the PC and compute rate + inter-arrival jitter (std-dev / p99). `evhz` on
  `/dev/input/eventN`, or a small throwaway script timestamping `evtest`/evdev
  reads, is sufficient. Record steady-state jitter, and attach→first-report from
  the TV's existing `t_first_ok` logging (M7d instrumentation).
- **FFB round-trip (optional, G29):** if feasible, time an OUT (FFB) command to
  its physical effect to characterize the output-loop latency §6 calls out; if not
  cleanly measurable, record inter-report jitter only and note the omission.
- **Compare Ethernet vs WiFi** on the same device/session back to back; record
  both. Confirm `TCP_NODELAY` is set on the accepted socket (§6) — if the WiFi
  numbers look Nagle-shaped, verify that first.
- This is procedure + a throwaway measurement script, **not** shipped app code and
  **not** the PC companion. See open questions for where the script/numbers live.

---

## Files to touch

Keep the surface minimal.

- `android/src/main/java/com/iotower/android/ServerService.java` — consume the
  backend gone signal (callback registration or low-rate poll) → serialized
  teardown → ready-state notification; acquire/release the partial `WakeLock`
  around the serving lifetime; pass `foregroundServiceType` to `startForeground`
  where required; reflect "listening" after a client disconnect.
- `android/src/main/java/com/iotower/android/MainActivity.java` — only if the
  reset-reconnect robustness needs the DETACH/ATTACH receiver hardened against the
  gone-driven teardown racing a re-attach (reuse the existing serialization). No
  new device-selection logic (the sole-device/attach-intent rule from §8 stands).
- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — only the
  tiny additive gone-notification hook if a callback is chosen over a poll (set it
  on the existing `markDeviceGone` transition). No behavior change to detection.
- `android/src/main/AndroidManifest.xml` — `WAKE_LOCK` permission, FGS-type
  permission(s), and `android:foregroundServiceType` on the `<service>`.
- **Docs audit (same commit, feature-workflow step 3):** `architecture.md` §7/§8
  (note the service now consumes the backend gone signal; wake lock + FGS type for
  screen-off survival; the verified clean-disconnect re-listen) and §6 (record the
  measured latency/jitter numbers and method); `MILESTONES.md` M9 status →
  done with how each sub-gate was met; `DEVELOPING.md` if any new build/run/measure
  command is introduced; append an M9 results note to
  `bugs/m7-interrupt-in-empty-reports.md` only if a disconnect leak is found/fixed.
- **No `:core` changes expected.** If L3 proves one is unavoidable, flag first; it
  stays Android-free and ships with an L1 test.

---

## Test plan

- **Layers:** L3 is the real gate for all three scenarios and the measurement
  (hardware + the stock PC client; TV with screen off). **No `:core` change ⇒ no
  new L1 test**, and `./gradlew :core:test` must stay green (DoD).
  `./gradlew :android:assembleDebug` must compile. An L1 test is required **only
  if** the clean-disconnect fix forces a `:core` teardown change — in that case add
  exactly one case to the engine test asserting that `shutdown()` with URBs still
  in-flight cancels each via the backend and leaves no reply enqueued (extend
  `ConcurrencyEngineTest` rather than add a new class if it already drives
  shutdown).

- **New cases (and only new ones), each L3 unless noted:**
  - **Reset-reconnect (generic).** Covers the gone-signal→teardown→re-claim path
    that no existing test exercises. G29: from a served PC-mode session, trigger
    the `new-lg4ff` native-mode flip (re-enumeration); assert the TV logs the gone
    transition, tears the session down, re-claims on reappearance (`opened +
    claimed N/N`), and a manual `usbip attach` restores live input. Repeat with a
    plain unplug/replug of the F310 as the device-agnostic cross-check. Assertion:
    recovery happens without app restart, and happens even when driven by the gone
    signal (confirm via the log line) rather than the broadcast alone.
  - **Foreground-service survival.** Serve the F310, turn the TV screen off, wait
    >10 min, confirm the service is still foregrounded (notification present) and
    input still reaches `evtest` without re-attach. Assertion: session alive and
    responsive after 10 min screen-off; wake lock acquired for the serving window
    and released on stop (log).
  - **Clean client disconnect (repeated).** With input flowing, kill the client
    (`usbip detach`, then a hard `kill -9`/cable pull in a second pass); assert the
    TV returns to listening (notification + log), no thread leak (inspect via
    `adb shell` thread count or logged engine-shutdown/writer-join), and a fresh
    `usbip attach` re-enters the transfer phase with input. Run the cycle ≥5× to
    surface cumulative leaks. Covers the engine/backend teardown-then-reaccept path
    end to end, which only L3 exercises against the real stock client.
  - **Latency/jitter (measurement, not pass/fail logic).** Record steady-state
    inter-report jitter + attach→first-report on Ethernet and on WiFi; capture the
    numbers in §6 of `architecture.md`.

- **Fixtures / testdata:** none new. Reuse the F310 (device-agnostic baseline) and
  the G29 (reset case). The throwaway latency script is not a committed fixture
  unless the open question below resolves to keep it.

---

## Pass gate

Restates `MILESTONES.md` M9 — "survives all three; latency within an interactive
budget on Ethernet":

1. **Reset recovery.** Trigger a device reset/re-enumeration → the TV recovers
   generically (release → re-claim on return) and the session is restored with a
   manual `usbip attach`, no app restart. Verified on the G29 mode-switch and on a
   plain F310 unplug/replug. (Manual PC re-attach is within the gate, per M8.)
2. **Screen-off survival.** Screen off for 10+ minutes → still attached and input
   still responsive in `evtest`, with the foreground service alive.
3. **Clean client disconnect.** Kill the client → server returns to a clean
   listening state and accepts a fresh `usbip attach` with input flowing; no
   thread/URB leak across ≥5 connect→kill→reconnect cycles.
4. **Latency.** Measured steady-state latency/jitter on **Ethernet** is within the
   interactive budget **set from the measured baseline** (resolved 2026-10-20):
   Ethernet median within **~+3 ms of the loopback/local baseline**, p99 jitter
   **≤ one 60 Hz frame (16 ms)**, no sustained spikes; crisp input with no
   perceptible lag/jitter in `evtest` and in use. Record the absolute Ethernet and
   WiFi figures in §6. If the baseline itself exceeds this, the baseline becomes the
   recorded budget and the excess is flagged.
5. `./gradlew :core:test` green and `./gradlew :android:assembleDebug` compiles.

For each sub-gate, state in the MILESTONES status how it was checked (command,
log line, measured number) — same rigor as the M7/M8 status entries.

---

## Risks / open questions (stop and flag — do not guess)

> **Resolved 2026-10-20 (recommended settings adopted):** latency budget pinned
> (gate item 4); latency harness = documented manual procedure, no committed
> script; gone-signal surface = one-shot callback (poll fallback allowed). The
> remaining items below stay as implement-time conditionals — proceed on the
> stated default and stop-and-flag only if the bad case appears.

- **Gone-signal surface: callback vs poll. [RESOLVED → callback.]** Push a
  one-shot callback from `markDeviceGone`; it is the cleaner, smaller change. Fall
  back to a low-rate poll of `isDeviceGone()` **only** if the callback tangles the
  backend's locking, and flag that choice. Either way it must not reintroduce
  busy-looping (M7d parked the readers precisely to stop that).
- **Double-teardown / re-attach race.** The gone signal and the DETACH broadcast
  can both fire for one event, and a fast re-enumeration can deliver ATTACH while
  teardown is still running. Serialize on the existing `lifecycleLock`/`stopping`
  and confirm a re-attach can't be dropped or race a half-done teardown. If the
  existing serialization can't express "tear down, then accept the pending
  re-claim," flag before widening it.
- **Screen-off on real TV firmware (§8).** A correct partial wake lock + FGS type
  *should* survive screen-off, but TV OEMs apply Doze/battery optimizations
  unpredictably. If 10 min still drops it, **stop and flag** — do not silently add
  an aggressive wake lock (e.g. screen/full) or assume a battery-exemption prompt;
  that is a product call.
- **`foregroundServiceType` / FGS rules by API level.** The exact required type
  and the `startForeground` signature differ across API 28→35 (and Android 14's
  stricter FGS-type enforcement). Verify against the project's `compileSdk 35` /
  `minSdk 28` and the TV's actual API level; flag if the device's level forces a
  different type than `connectedDevice`.
- **Clean-disconnect: is any fix even needed?** The core already cancels inflight
  and re-accepts. The honest expectation is that scenario 3 is mostly a
  verification gate. If the cycle test is clean, **add nothing** and record that.
  Only a demonstrated leak justifies code — and a `:core` touch justifies an L1
  test. Do not refactor teardown "to be safe."
- **`-ESHUTDOWN` under abrupt disconnect.** M7d completes gone-URBs with
  `-ESHUTDOWN` (−108). Confirm the stock vhci client tolerates that cleanly on a
  reset (it should treat any negative status as a failed URB); if it wedges, that
  is the known §9/M10 client fragility, **not** an M9 server fix — record it, don't
  chase it.
- **Latency harness placement. [RESOLVED → documented procedure.]** The method
  is a documented manual procedure in `DEVELOPING.md`, with the measured numbers
  recorded in `architecture.md` §6. No committed measurement script, so
  `companion/` stays unseeded for the §9/M10 work.
- **Interactive budget. [RESOLVED → baseline-relative, see gate item 4.]**
  Measure the loopback/local baseline first, then gate Ethernet at median within
  ~+3 ms of it and p99 jitter ≤ one 60 Hz frame (16 ms), no sustained spikes. The
  F310's ~1 kHz-class report stream is the device-agnostic cross-check. Record the
  absolute figures; if the baseline exceeds the ceiling, record the baseline as the
  budget and flag it.
