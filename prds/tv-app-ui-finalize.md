# PRD: Finalize + prettify the TV app UI (MainActivity)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

Not a protocol milestone — a finalization of the Android TV app's on-screen UI
(the user's "finalize the client UI and make it prettier" ask, alongside M10).
Architecture §2 (no-root premise), §8 (lifecycle). The UI is the only
human-facing surface on the TV; today it is a bare vertical `LinearLayout`
(title + status text + button) on `Theme.DeviceDefault`.

## Scope

Redesign `MainActivity`'s presentation into an intentional 10-foot TV launcher,
**changing look only — not behavior**.

In scope — observable outcomes:

- A composed, centered layout: a wordmark ("I/O TOWER") with a one-line tagline,
  a status card, and a single large Start/Stop control, on a dark themed
  background. Restrained Tron-ish palette (near-black bg, cyan accent) — the name
  is from *TRON*; make the aesthetic deliberate, not DeviceDefault gray.
- A **status indicator**: a colored state dot + a short state label
  (IDLE / REQUESTING / SERVING / WAITING / ERROR), so the current state is
  legible across the room at a glance.
- When serving, show the **exact PC attach command with the TV's own IP filled
  in** — `sudo usbip attach -r <tv-ip> -b 1-1` — in a monospace block, instead of
  the literal `<tv-ip>` placeholder. Fall back to `<tv-ip>` if the address can't
  be determined.
- **D-pad / TV focus**: the Start/Stop button must show a clear focus state for
  the remote (custom focused/pressed background), and keep initial focus.
- Build stamp kept but demoted to a dim footer (still logged in `onCreate`).

Out of scope (do not change):

- Every piece of existing **logic/lifecycle**: the `ACTION_USB_PERMISSION`
  handshake, the attach/detach `BroadcastReceiver`s, `pickDevice`
  (sole-device-or-refuse, §8), `onResume` auto-claim, `startForegroundService`
  wiring, and `ServerService` — untouched. This PRD moves no server logic.
- Device-agnostic invariant: the UI keeps showing the device as `VID:PID`
  (already hex-formatted); **no** VID/PID branching, no device names tables.
- No new permissions. Reading the local IP via `java.net.NetworkInterface`
  needs none; INTERNET is already granted.
- Invariant 5 (no third-party libs / no AndroidX): **platform widgets and
  `android.graphics` only**. No `androidx.*`, no layout XML required — keep the
  programmatic-UI approach, just styled.

## Approach

Rewrite `buildUi()` and the status rendering in `MainActivity`; leave the rest
of the class as-is.

- Build the view tree programmatically (as today): a root `LinearLayout`
  (dark background, centered) containing a "card" `LinearLayout`
  (rounded `GradientDrawable` surface + subtle accent stroke) with: header
  (wordmark `TextView` with letter-spacing + tagline), a thin divider, a state
  row (`dot` `View` with an oval `GradientDrawable` + `stateLabel` `TextView`),
  the existing `status` detail `TextView`, a monospace `hint` `TextView` for the
  attach command, and the `toggle` `Button`. A dim `footer` `TextView` carries
  the build id.
- Keep the existing fields `status` and `toggle` (logic references them). Add
  `stateLabel`, `dot`, `hint`, `footer`, a `State` enum, and a `currentState`.
- Add a `setState(State)` that recolors the dot, sets the label, and shows/hides
  the `hint`; call it at the sites that today only call `setStatus(...)`
  (start→SERVING, stop/no-device→IDLE, requesting→REQUESTING, detach→WAITING,
  permission-denied→ERROR). `setStatus(String)` stays the detail line.
- Helpers: `localIpAddress()` (first non-loopback site-local IPv4 via
  `NetworkInterface`, wrapped in try/catch → `null` on failure); `dp(float)`;
  `buttonBackground()` (a `StateListDrawable`: accent-filled when
  focused/pressed, outlined otherwise) with a matching focused/default
  `ColorStateList` for the text; `dotDrawable(int color)`; `cardBackground()`.
- API floor `minSdk 28`: everything used (`setLetterSpacing` 21,
  `GradientDrawable`/`StateListDrawable`, `ColorStateList`, `NetworkInterface`)
  is well below 28. No reflection, no new manifest entries.

Invariants preserved: no AndroidX/third-party; device-agnostic (`VID:PID` only);
`core` and the server path untouched; no new permissions.

## Files to touch

- `android/src/main/java/com/iotower/android/MainActivity.java` — UI rewrite +
  helpers only.

No change to `AndroidManifest.xml` (no new permission/feature), `ServerService`,
`AndroidUsbBackend`, `core/`, or the Gradle build.

## Test plan

- **Layer:** L3 only (Android UI; there are no `:android` unit tests by design —
  DEVELOPING.md). The logic is unchanged, so there is **no new L1/L2 test** and
  nothing for `:core:test` to cover (it stays green, untouched).
- **Compile gate:** `./gradlew :android:assembleDebug` must stay green (the CI
  `android` job). This is the primary automatable check for this change.
- **Manual L3 (user, on the TV):** `./gradlew :android:installDebug`, launch the
  app, and confirm by eye: the new layout renders; the Start button takes and
  shows D-pad focus; pressing Start claims the device and the state dot goes
  green with "SERVING" and the attach line shows the TV's real IP; unplug →
  "WAITING"; Stop → "IDLE". Behavior (claim/serve/detach) must be identical to
  before — only the presentation changed.
- No new fixtures/testdata.

## Pass gate

- `./gradlew :android:assembleDebug` compiles clean (no AndroidX, no new
  permissions). `:core:test` stays green (untouched).
- On the TV: the restyled UI renders, the button is focusable with a visible
  focus state, SERVING shows the real `usbip attach -r <ip> -b 1-1`, and
  start/serve/detach/stop behave exactly as before.

## Risks / open questions

- **Cannot be compiled in the authoring/bridge environment** (no Android SDK, no
  JDK 17 there). The code is written to the documented API floor and
  platform-only classes; the real compile + visual check are the user's L3 run.
  If `assembleDebug` reports any error, that is the gate to fix — flag rather
  than work around by pulling in AndroidX.
- `NetworkInterface` enumeration can return a VPN/secondary address first; the
  helper prefers a site-local, non-loopback IPv4 and falls back to the
  placeholder, so a wrong guess degrades to today's behavior, never worse.
- A custom button background replaces the DeviceDefault focus ripple, so the
  focused/pressed state **must** be provided explicitly (TV usability) — this is
  specified above, not optional.
