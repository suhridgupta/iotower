# PRD: One USB permission prompt per enumeration (de-dupe the dialog)

> Design (Opus) pass; implemented by the build (Sonnet) pass. Spec only.

## Milestone / context

Not a new milestone — a §8 lifecycle fix to the permission handshake. User report:
the USB permission dialog appears **several times** for one device (worst on the
G29, where a missed confirmation lets the kernel input driver re-grab the wheel
and flood the TV UI — the "infinite scroll"). Their TV firmware **never shows the
"use by default for this USB device" checkbox**, so the persistent-association
path (the only fully dialog-free route) is unavailable.

## Scope

Reduce the prompt to **one per device enumeration** by removing *duplicate*
prompts for the same device instance. Explicitly NOT in scope (documented as
impossible on this firmware, not a bug): a single lifetime prompt for a
**re-enumerating** device (G29 mode/reset) — each new enumeration is a new
`UsbDevice` with no persisted grant, so it needs its own one grant. Also out of
scope: the scan/select UI, the logo, PIN/TLS.

Observable outcome: a stable controller (F310) prompts **once**; the G29 prompts
at most **once per enumeration**, never stacking two dialogs for one instance.

## Approach (MainActivity + manifest, presentation/lifecycle only)

Root cause: two paths handle one attach — the manifest `USB_DEVICE_ATTACHED`
**intent** (`maybeHandleAttachIntent`) and the runtime **broadcast** receiver —
and `requestOrStart` can call `requestPermission` again while a prompt is pending.

- **`android:launchMode="singleTask"`** on `MainActivity` so a while-running
  re-attach is delivered to the existing instance via `onNewIntent` (the
  intent path auto-grants where the firmware allows) rather than only as a
  broadcast that forces a fresh `requestPermission`.
- **In-flight guard:** new field `requestingDeviceName`. `requestOrStart` sets it
  before `requestPermission` and returns early if a request for that exact device
  name is already pending; the permission-result receiver clears it; `DETACHED`
  of that device and `stopServer`/eject clear it.
- **De-dupe the attach handlers:** both `maybeHandleAttachIntent` and the
  broadcast `ATTACHED` branch skip a device that is already `requestingDeviceName`
  (on top of the existing served/ejected skips).
- **Guard `startServer`** against re-starting for the device already being served.

Device-agnostic (keyed on the opaque `/dev/bus/usb/...` name, no VID/PID);
`core`/`ServerService` untouched; no new permissions.

## Files to touch

- `android/src/main/java/com/iotower/android/MainActivity.java`
- `android/src/main/AndroidManifest.xml` (launchMode only)

## Test plan

- **Layer:** L3 (Android; no `:android` unit tests by design). Compile gate
  `:android:assembleDebug` must stay green. `:core:test` unaffected.
- **Manual (user, on TV):** (1) F310 — plug in → exactly one prompt → serving.
  (2) G29 — plug in → one prompt per enumeration, never two dialogs stacked for
  the same instance; count the prompts and note each dialog's exact wording
  ("Allow … to access" = permission vs "Open … to handle" = chooser).

## Pass gate

`:android:assembleDebug` compiles. On the TV, no duplicate dialogs for one device
instance; F310 → a single prompt. The per-re-enumeration prompt that remains is
an OS/firmware limitation (no "use by default"), recorded, not a failure.

## Risks / open questions

- The existing code comment notes the manifest intent "only covers a cold launch"
  on this setup — if foreground re-attaches still arrive only as broadcasts with
  no intent grant, `singleTask` won't make them dialog-free; the in-flight guard
  still removes the *duplicate* prompts. If a single instance still shows 2+
  dialogs, capture the dialog wording + logcat and flag — do not add VID/PID
  special-casing.
