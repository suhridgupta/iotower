# PRD: M7c — interrupt-IN endpoint-halt fix (SET_CONFIGURATION no-op + CLEAR_FEATURE recovery)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

Second and (expected) final fix for the **M7** L3 gate blocker
(`bugs/m7-interrupt-in-empty-reports.md`); builds directly on **M7b**
(`prds/m7b-interrupt-in-read-path.md`). Architecture: §2 (no-root claim —
Android configures the device when we claim it), §5 (transfer engine / control
path), §7 (reset recovery). Seam: `com.iotower.core.usb.UsbBackend`; only
`:android` changes. `:core` is untouched (M7b already added its logging).

What the 2026-10-16 hardware run proved, now that M7b delivers reports correctly:
- The read path is fixed — `reads≈50/s` (no spin), `depth=1` steady (client IN
  URBs arrive and are held), every IN URB answered (8533 `RET_SUBMIT dir=IN
  status=-32`), stock `usbip` tolerates the `-EPIPE` flood and keeps re-driving.
- The remaining fault is isolated: **every `0x81` read returns an immediate
  `-32` error — never a timeout, never a success.** An idle healthy interrupt
  endpoint would time out (`n=0`); immediate error = the endpoint is
  **halted/stalled**.
- Trigger confirmed: the stall begins the instant the client's forwarded
  `SET_CONFIGURATION(1)` lands (`ctrl ... bRequest=0x09 wValue=0x0001 -> ret=0`
  at 19:48:51.827 → first `0x81 error` at 19:48:51.991). Android already
  configured the device at `claimInterface` (§2), so re-issuing
  `SET_CONFIGURATION` as a raw control transfer resets/halts the endpoints. No
  `SET_INTERFACE` or `CLEAR_FEATURE` is sent by the client.

## Scope

Delivers (both in `AndroidUsbBackend`):

- **Prevent the stall:** the Android backend no-op-ACKs a `SET_CONFIGURATION`
  standard request instead of forwarding it to the device, because the Android
  framework already configured the device when we claimed it (§2). Returns
  success so the client proceeds; the raw reset never reaches the device.
- **Recover if it still stalls:** when an IN read errors, issue a standard
  `CLEAR_FEATURE(ENDPOINT_HALT)` on that endpoint and let the next read retry —
  rate-limited so it cannot flood ep0.
- Keeps the M7b read path and all M7b/step-1 logging; adds a log line for each
  intercepted `SET_CONFIGURATION` and each halt-clear attempt + its return.

Explicitly OUT of scope (flag, don't gold-plate):
- Intercepting `SET_INTERFACE (0x0b)` or any other standard request — only
  `SET_CONFIGURATION` is the confirmed trigger, and blanket-intercepting
  `SET_INTERFACE` would break legitimate alt-setting switches on composite
  devices (M8). Leave it forwarded.
- Full device reset / re-enumeration recovery (§7, that is M8).
- Any `:core` change, any OUT/ep0 change beyond the two above, any device-specific
  logic (Invariant 1 — route/decide by standard request number only, never
  VID/PID).

## Approach

In `AndroidUsbBackend` only:

1. **`controlTransfer` — intercept `SET_CONFIGURATION`.** A standard
   `SET_CONFIGURATION` is `(bmRequestType & 0xFF) == 0x00 && (bRequest & 0xFF) ==
   0x09`. When matched, do **not** call `connection.controlTransfer` (that is what
   resets the endpoints); return `0` (a no-op success — the device is already in
   that configuration from `open()`/`claimInterface`, §2). Everything else is
   forwarded exactly as today. Log the interception on the existing `ctrl …` line
   with an `[intercepted: already configured]` marker. Protocol-generic — this is
   an Android-host configuration quirk handled in `:android`, keeping `:core` a
   faithful raw forwarder (the desktop/`FakeUsbBackend` path still forwards
   `SET_CONFIGURATION` unchanged).

2. **IN read loop — `CLEAR_FEATURE(ENDPOINT_HALT)` recovery.** On a read error
   (`n < 0`), before completing the URB, attempt — rate-limited to at most once
   per `HALT_CLEAR_INTERVAL_MS` (first error fires immediately, then ~once/sec) —
   a control transfer `bmRequestType=0x02` (OUT, standard, recipient=endpoint),
   `bRequest=0x01 (CLEAR_FEATURE)`, `wValue=0x0000 (ENDPOINT_HALT)`,
   `wIndex=<endpoint address>`, `length=0`, short timeout. Then proceed as in M7b:
   complete the head URB with `-EPIPE` and back off. If the clear worked, the next
   read returns data (`n>0`) and the normal M7b delivery takes over. Rate-limiting
   keeps ep0 from being flooded (reads error ~50/s; halt-clears stay ~1/s).

Both are expected to be complementary: (1) should stop the stall happening at all;
(2) is the safety net and an independent confirmation. If (1) alone fixes it, (2)
never fires and its log stays silent.

Invariants: device-agnostic (decisions by standard request number only — no
VID/PID, Invariant 1); `:core` stays Android-free and untouched (Invariant 2);
wire unchanged (Invariant 4); null Host-API returns stay guarded (Invariant 6).

## Files to touch

- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — the
  `SET_CONFIGURATION` no-op in `controlTransfer`; a `clearEndpointHalt(address)`
  helper; the rate-limited halt-clear in the IN read-error branch; a
  `lastHaltClearMs` field on the per-endpoint reader; `HALT_CLEAR_INTERVAL_MS`
  constant; class-Javadoc note. No other file in `:android`.
- Docs audit (same change): `architecture.md` — one documented exception under
  the "Transparency over cleverness" principle (§1) and/or §2, noting the Android
  backend no-op-ACKs `SET_CONFIGURATION` because the framework owns configuration;
  `MILESTONES.md` M7 status; append an M7c result section to
  `bugs/m7-interrupt-in-empty-reports.md`.

## Test plan

- **Layers:** L3 (the real gate); L1 regression only.
- **L1 (`:core:test`):** no `:core` change → **no new test**; suite must stay
  green (DoD).
- **L2 (optional smoke):** `FakeUsbBackend` does not send `SET_CONFIGURATION` and
  streams `0x81` promptly, so its behaviour is unchanged; a quick desktop +
  `usbip` + `evtest` run should still show the fake stream. Not a new test.
- **L3 (the gate, on the TV):** `./gradlew :android:installDebug`; capture
  `adb logcat -s IoTowerBackend System.out AndroidRuntime:E`; from Fedora
  `sudo usbip attach -r <tv-ip> -b <busid>`; `evtest` the F310 and move it.

## Pass gate

Primary (M7 gate): moving the pad produces `EV_ABS`/`EV_KEY` events in `evtest`.
In the log, the 1 Hz summary shows `ok>0` while moving (reports delivered) and the
`ctrl … SET_CONFIGURATION … [intercepted: already configured]` line confirms the
reset was not forwarded. If the stall is gone via (1) alone, no `clearHalt` lines
appear; if (2) is carrying it, `clearHalt(… ) -> ret=0` lines precede `ok>0`.
`:core:test` green is required either way.

## Risks / open questions (stop and flag — do not guess)

- **No-op ACK vs real re-config.** Spec no-op-ACKs `SET_CONFIGURATION` on the
  assumption the device is already in that configuration from `claimInterface`
  (§2). If a device ever needs a genuine config change at attach, this would mask
  it — acceptable for the single-configuration gamepad case; flag if a
  multi-config device appears (M8).
- **`CLEAR_FEATURE` from the reader thread.** The halt-clear runs a control
  transfer on the reader thread while the engine's control workers may also use
  the connection; different endpoints, but if any Host-API instability shows up
  under concurrency, flag it. Keep the halt-clear rate-limited and short-timeout.
- **If the stall persists even with both** — i.e. reads still error and
  `clearHalt` returns negative or does not help — that points to a deeper
  host-controller / toggle issue and is the handoff to §7 reset-recovery (M8);
  flag rather than escalating blindly (do not start forwarding `SET_INTERFACE`
  interception or device resets on a guess).
