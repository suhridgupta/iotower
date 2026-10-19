# PRD: M8 — G29: OUT transfers / FFB, composite device, reset recovery

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

**M8** in `MILESTONES.md`. By design M8 adds **nothing device-specific** — it
validates the already-generic transparent path on the hard device (the Logitech
G29). Architecture: §5 (URB→Host-API routing, build the endpoint map from
descriptors), §5.1 (engine; OUT via the worker pool), §7 (device transparency +
reset/re-enumeration handling — the hardest part of the project), §8 (Android
lifecycle: `ATTACHED`/`DETACHED` broadcasts drive claim/reconnect).

Depends on **M7** (end-to-end path proven on the F310) and **M7d** (post-unplug
detect+park, which this milestone's reset recovery consumes). Invariants hold
throughout: device-agnostic — route by `(endpoint, direction)` from the parsed
descriptors, **never special-case the G29** (Invariant 1); `:core` stays
Android-free (Invariant 2); stock in-kernel client only, no custom PC code
(Invariant 3); big-endian wire unchanged (Invariant 4).

The G29 exercises three things the F310 never did: **OUT transfers** (force
feedback), a **composite multi-interface** topology, and **mid-session
re-enumeration** (the `new-lg4ff` mode switch). Each is validated generically.

## Prerequisite — capture the G29 descriptor dump (M2 leftover)

M2 recorded the G29 descriptor dump as still-to-capture. M8 needs it as ground
truth. **First step:** capture the raw config descriptors from the G29 (on the TV
via `getRawDescriptors()` logged, or `lsusb -v`/usbmon on a host) into
`testdata/g29-descriptors.bin` with a note in `testdata/README.md`, and record
its `bNumConfigurations`, interface count, and per-endpoint `(address, type,
direction, maxPacketSize, interval)`. This both feeds the L1 parser test below
and settles the multi-config question that gates the M7c `SET_CONFIGURATION`
no-op (see risks). If the capture can't be obtained, **stop and flag** — M8's L3
work is blind without it.

## Scope

Delivers:
- **OUT / FFB validated end-to-end.** Force-feedback commands from the PC arrive
  as OUT URBs and are written to the endpoint by the existing `submitOut`
  (`bulkTransfer` on the OUT ep; `controlTransfer` for ep0 OUT). Expected to work
  with no new code — if the L3 run shows FFB not firing, the fix is a bug in the
  generic OUT path, **not** a G29 branch.
- **Composite device validated.** Claim-every-interface + the descriptor-built
  endpoint map (§5) carry the G29's multiple interfaces/endpoints with no change.
  Legitimate `SET_INTERFACE` (alt-setting) requests stay forwarded (M7c left them
  so, deliberately).
- **Reset recovery (minimum viable, §7).** When the G29 mode-switches and
  re-enumerates, the app detects the detach (consuming M7d's backend `deviceGone`
  signal and/or `ACTION_USB_DEVICE_DETACHED`), tears the backend down cleanly,
  re-acquires the device when it reappears (possibly under a different PID —
  **matched generically, never by PID**), and returns the server to a state where
  a **manual** `usbip attach` from the PC reconnects. Automation of the re-attach
  is explicitly M10.

Observable outcomes to bullet at the gate: FFB physically moves the wheel; G29
axes/buttons show in `evtest`; after a deliberate mode-switch the server recovers
and a manual re-attach brings input back.

OUT of scope (flag, don't gold-plate):
- **Auto re-attach** on the PC side (the companion's auto-reattach loop) — M10.
- **Masking the reset** from the client (seamless recovery) — §7 calls this the
  "slicker build"; M8 is detect-and-reconnect only.
- Isochronous transfers (§5, permanently out of scope).
- Any VID/PID logic, G29 report-format parsing, FFB protocol knowledge, or
  `new-lg4ff` awareness in the app (Invariant 1 — the PC driver owns all of it).
- Screen-off survival / latency budget / client-kill cleanup — that is **M9**.

## Approach

Device-agnostic throughout; the only genuinely new code is the lifecycle/reset
path, which is generic USB lifecycle handling, not wheel logic.

1. **OUT / FFB.** No design change anticipated — `submit` already routes OUT to
   `submitOut`, and §5 says output effects are ordinary OUT writes. Confirm the
   reader forwards OUT payloads and the worker-pool `bulkTransfer` writes them;
   if a defect surfaces, fix it in the generic path.
2. **Composite.** No design change anticipated — `open()` already claims every
   interface and builds the address→endpoint map across all of them. Confirm
   routing for every G29 endpoint from the captured map.
3. **Reset recovery (the real work), service layer.** In `ServerService`
   (with `MainActivity` as needed), register for `ACTION_USB_DEVICE_ATTACHED` /
   `ACTION_USB_DEVICE_DETACHED` (§8) and/or observe the backend's M7d
   `deviceGone` state. On gone: stop the `UsbIpServer`/backend cleanly
   (`backend.close()`), keep the foreground service alive, return to a
   waiting-for-device state. On the device reappearing: re-run the existing
   open → `claimInterface(forceClaim)` → build backend → start `UsbIpServer`
   sequence (the same factory path M7 uses), so the listening socket is ready and
   a manual `usbip attach` reconnects. Device identity is matched generically
   (whatever `UsbManager` hands back on attach), never by saved PID. Reuse M7d's
   park/disconnect so no spin occurs between detach and reappear.

Keep `:core` untouched if at all possible (the path is already generic). The one
acceptable `:core` touch is **additive**: extending the M2 `DescriptorParser`
test coverage to the real G29 dump if the capture exposes a parsing gap — no
change to `parse`/`net`/`engine`/`protocol` behavior.

## Files to touch
- `android/.../ServerService.java` — detach/attach (or `deviceGone`-driven)
  teardown + re-acquire + restart of `UsbIpServer`; foreground service survives
  the gap. `MainActivity` only if the permission/intent routing needs it.
- `android/.../AndroidUsbBackend.java` — only if the L3 run finds a real OUT or
  composite defect in the generic path (not expected). No G29 branch, ever.
- `testdata/g29-descriptors.bin` + `testdata/README.md` — the captured dump and
  its provenance/topology note (prerequisite above).
- `core/.../DescriptorParserTest` (+ fixture wiring) — **only if** the G29 dump
  exposes a parser gap; additive L1 case asserting the G29 topology.
- Docs audit (same commit): `MILESTONES.md` M8 → done with the gate evidence
  (mirror the M6/M7 "MET on hardware" framing); `architecture.md` §7 (record the
  implemented detect-and-reconnect recovery) and §5 (OUT/composite confirmed on
  the G29); `README.md` and `testdata/README.md` as reality changes.

## Test plan
- **Layers:** L3 is the gate. L1 additive only (the G29-dump parser case, if
  needed). No L2 change (the `FakeUsbBackend` is a single simple device; it does
  not model composite/FFB/reset — do not contort it to).
- **New L1 case (only if the capture needs it):** parse
  `testdata/g29-descriptors.bin` and assert the exact interface count and each
  endpoint's `(address, type, direction, maxPacketSize, interval)`. Purpose:
  prove the parser handles the real composite topology, not just the F310 and the
  synthetic fixtures. Covers a path no existing fixture exercises (real multi-
  interface device). If the existing `DescriptorParserTest` can be
  parameterized over dumps, extend it rather than adding a new method.
- **L3 (the gate), on the TV + G29:**
  1. `./gradlew :android:installDebug`; attach the G29 to the TV; `adb logcat -s
     IoTowerBackend`.
  2. From Fedora `sudo usbip attach -r <tv-ip> -b <busid>`; confirm `new-lg4ff`
     binds and Oversteer sees the wheel; **axes/buttons in `evtest`**.
  3. **FFB:** trigger force feedback from Oversteer's test (or a game) and
     confirm the wheel physically moves (the OUT path).
  4. **Reset:** let `new-lg4ff` flip the G29 to native mode (the re-enumeration);
     confirm the app detects the detach, recovers to a clean listening state, and
     a **manual** `usbip attach` brings input back.
- **Fixtures:** `testdata/g29-descriptors.bin` (prerequisite).

## Pass gate
On the TV with the G29 (mirror M6/M7 framing — authoring env has no SDK/TV/wheel):
- **FFB physically moves the wheel** from Oversteer's test or a game.
- **G29 axes show in `evtest`** and buttons register.
- **The mode-switch reset recovers**: after the `new-lg4ff` native-mode flip and
  re-enumeration, a manual re-attach reconnects and input resumes; the foreground
  service never died.
- `./gradlew :core:test` green (with the additive G29 parser case if added);
  `:android:assembleDebug` compiles.

## Risks / open questions (stop and flag — do not guess)
- **G29 `bNumConfigurations` vs the M7c `SET_CONFIGURATION` no-op.** M7c no-op-ACKs
  `SET_CONFIGURATION` on the single-config assumption (§2). **If the captured G29
  descriptor shows more than one configuration**, the blanket no-op can mask a
  genuine config change and must become conditional: ACK only when the requested
  config equals the one already set at claim, else forward. Resolve this from the
  capture **before** the L3 run; flag if multi-config.
- **`SET_INTERFACE` on a composite device.** M7c left `SET_INTERFACE (0x0b)`
  forwarded precisely for the G29's alt settings. If forwarding it re-stalls an
  endpoint the way raw `SET_CONFIGURATION` did, that is a new finding — flag it;
  do not reflexively start intercepting `SET_INTERFACE` (that would break
  legitimate alt-setting switches).
- **Re-enumeration identity.** On native-mode re-enumeration the G29 reappears,
  often under a different PID. The re-acquire must match **whatever device the
  framework presents**, never a remembered VID/PID (Invariant 1). If multiple USB
  devices are present on the TV, flag how the right one is chosen (busid/port,
  not PID).
- **Foreground-service survival across the gap.** The service must outlive the
  detach→reappear window (§8). If TV firmware kills it or revokes USB permission
  on re-enumeration (re-prompting), flag — permission re-grant across
  re-enumeration may need handling and borders on M9.
- **OUT/composite assumed defect-free.** The plan assumes FFB and composite work
  with zero new backend code. If the L3 run disproves that, the fix stays in the
  **generic** path and the PRD's "no new code" assumption is what was wrong —
  flag it rather than adding a device-shaped workaround.
- **Reset recovery is the hardest part of the project (§7).** If detect-and-
  reconnect proves unreliable (races between detach, backend teardown, and
  reappear), flag and settle the lifecycle contract rather than piling on retries;
  seamless masking and PC-side auto-reattach are deliberately deferred (M9/M10).
