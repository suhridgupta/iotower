# I/O Tower — Milestones

Testability-ordered delivery plan. The whole USB/IP protocol is proven locally —
JVM unit tests plus the desktop harness against the stock `usbip` client — before
any Android work. Only then does it move to the TV, then to the G29, then to
hardening. Each milestone below is distinct and has an unambiguous pass gate.

See [`architecture.md`](architecture.md) for the design and [`DEVELOPING.md`](DEVELOPING.md)
for the build/test commands.

## Test layers

Every gate is expressed against one of three layers:

- **L1 — JVM unit tests** (`./gradlew :core:test`): pure logic, no device,
  milliseconds, runs in CI.
- **L2 — desktop harness + stock usbip** (`./gradlew :desktop:run`, then the
  stock `usbip` tool against `127.0.0.1` on the same Fedora box): validates the
  real wire protocol against the in-kernel client, with no Android and no
  hardware.
- **L3 — the TV + a real USB device**: validates the Host API and real hardware.
  The slow, expensive loop — used last and sparingly.

**Key L2 enabler:** the desktop `FakeUsbBackend` serves a real HID gamepad
descriptor and canned reports, so on `usbip attach` the kernel's own `usbhid`
driver binds to the fake and creates an input device — you get `evtest` showing
synthetic button presses on the PC, sourced entirely from Java, with nothing
plugged in. That is what lets "transfers work" be gated locally.

---

## M0 — Build & CI gate  *(essentially done)*

- **Build:** the scaffolded multi-module project.
- **Test:** `./gradlew :core:test`; `./gradlew :desktop:run` opens `:3240` and
  accepts a TCP connection; `./gradlew :android:assembleDebug` produces an APK.
- **Gate:** all three succeed; wire them into CI as the green baseline every
  later milestone builds on.
- **Where:** L1 + smoke.

## M1 — Protocol codecs  (`core/protocol`)  *(done)*

- **Build:** encode/decode for every wire struct — `OpHeader`,
  `usbip_usb_device`, `usbip_usb_interface`, `header_basic`, `cmd_submit`,
  `ret_submit`, `cmd_unlink`, `ret_unlink` (§4).
- **Test:** JUnit round-trip for each struct, **plus golden-byte vectors** —
  capture a real exchange from the reference `usbipd` server (or `usbmon` /
  `tcpdump` on `:3240`) and assert your framing matches byte-for-byte.
- **Gate:** `:core:test` green, including the golden vectors.
- **Where:** L1.

## M2 — Descriptor parser + endpoint map  (`core/usb`)  *(done)*

- **Build:** `DescriptorParser` walks raw configuration descriptors into an
  `EndpointMap` (§5).
- **Test:** feed a captured **G29 descriptor dump** and a **simple-gamepad dump**
  (both under `testdata/`) and assert the exact topology — interface count and
  each endpoint's address / type / direction / maxPacketSize / interval (e.g. the
  interrupt-IN `0x81`).
- **Gate:** `:core:test` green; both composite and simple devices parse
  correctly.
- **Where:** L1.
- **Status:** done. Parser + endpoint map implemented with L1 tests; a real
  Logitech F310 capture (`testdata/simple-gamepad-descriptors.bin`) is the
  ground-truth cross-check. The G29 dump is still to be captured.

## M3 — DEVLIST negotiation  (`core/net`)

- **Build:** the server answers `OP_REQ_DEVLIST` from a backend-advertised
  device (§4.1).
- **Test:** an **in-process integration test** — a tiny Java client that speaks
  the bytes to `UsbIpServer` over loopback and asserts the reply, so it runs in
  CI. Then confirm at L2: `usbip list -r 127.0.0.1` prints the fake device with
  the correct busid / VID:PID.
- **Gate:** both the in-process test and the `usbip list` output show the device
  correctly.
- **Where:** L1 + L2.
- **Status:** done. `UsbIpServer` answers `OP_REQ_DEVLIST` with the one
  backend-advertised device (invented busid `1-1`); the interface
  class/subclass/protocol triples come from an extended `DescriptorParser`
  (`InterfaceInfo` on the `EndpointMap`). L1 in-process integration test
  (`DevlistNegotiationTest`) asserts the framed reply field-by-field; the
  L2 `usbip list -r 127.0.0.1` check against the desktop harness remains to
  be run on the Fedora box. `OP_REQ_IMPORT` handled in M4.

## M4 — IMPORT + control transfers → enumeration

- **Build:** `OP_REQ_IMPORT` handling, the socket switches to the transfer phase,
  and the control (ep0) path so `GET_DESCRIPTOR` requests are served (§4, §5).
- **Test:** in-process — send IMPORT then a control `GET_DESCRIPTOR` submit and
  assert the `RET_SUBMIT` payload. L2 — `usbip attach -r 127.0.0.1 -b 1-1`, then
  `lsusb` shows the device and `lsusb -v` shows descriptors matching what the
  fake serves.
- **Gate:** the fake device fully enumerates in `lsusb` with correct
  descriptors; clean detach.
- **Where:** L1 + L2.
- **Status:** done. `UsbIpServer` answers `OP_REQ_IMPORT` (matching the invented
  busid `1-1`), replies `OP_REP_IMPORT` with the single `usbip_usb_device` (no
  interface array), then switches the same socket into the transfer phase and
  serves ep0 control transfers via a **provisional single-threaded synchronous
  loop** (`runTransferPhase`): each `CMD_SUBMIT` on ep0 has its setup packet
  decoded generically and is forwarded to `UsbBackend.controlTransfer`, then a
  framed `RET_SUBMIT` is returned. This loop is correct only for serial
  enumeration traffic and is replaced by the async engine in M5 — ep≠0 submits
  get a provisional `-EPIPE` `RET_SUBMIT` and `CMD_UNLINK` a status-0
  `RET_UNLINK`, inert acks that keep the stream framed and detach clean.
  `FakeUsbBackend.controlTransfer` now synthesizes `GET_DESCRIPTOR`
  DEVICE/CONFIGURATION from the F310 capture so the harness enumerates. L1
  `ImportControlTest` asserts IMPORT framing + two sequential ep0
  `GET_DESCRIPTOR` round-trips + the reject branch. The L2 `usbip attach` /
  `lsusb -v` / clean-detach flow remains to be run on the Fedora box (the
  Gradle build there needs JDK 17).

## M5 — Interrupt IN + the concurrency engine  (`core/engine`)  *(done)*

- **Build:** the asynchronous submit/ret path, the reader → engine → writer
  threading, seqnum correlation, and `CMD_UNLINK` / cancel (§5.1).
- **Test:** L1 — a fake backend that completes URBs **out of order** and with
  delays; assert every reply carries the right seqnum and nothing stalls; a
  cancel test. L2 — the fake streams canned HID reports and **`evtest` on the PC
  shows the scripted button/axis events**, no hardware.
- **Gate:** `evtest` displays the fake's input locally, and the out-of-order /
  cancel unit tests pass.
- **Where:** L1 + L2. **At this point the entire protocol is proven with zero
  Android and zero hardware.**
- **Status:** done. `TransferEngine` implements the §5.1 model — a single writer
  thread (sole owner of the socket `OutputStream`, so replies never interleave),
  a 2-thread worker pool for the blocking ep0 `controlTransfer`, and a
  `seqnum`-keyed `ConcurrentHashMap` whose atomic `remove` is the single arbiter
  between a completion and its cancel (so a cancelled URB never also emits a
  `RET_SUBMIT`). `UsbIpServer.runTransferPhase` is now only the reader; the M4
  provisional control loop and the ep≠0 `-EPIPE` stub are gone. ep≠0 IN/OUT go
  through the async `UsbBackend.submit`; `desktop/FakeUsbBackend` now replays a
  scripted XInput report cycle on `0x81` and accept-drops OUT on `0x02`. L1
  `ConcurrencyEngineTest` drives the real server over loopback and asserts
  out-of-order `seqnum` correlation and unlink→cancel→suppression;
  `ImportControlTest` (ep0) still passes through the new engine. The L2
  `usbip attach` + `evtest` run remains to be done on the Fedora box (the Gradle
  build there needs JDK 17). *Note: the sandbox that implemented this had no
  Maven access, so `:core:test` was cross-checked with an equivalent standalone
  loopback driver and a full `javac` compile of the main + test tree; the JUnit
  gate runs in CI / on the Fedora box.*

## M6 — Android claim & read  (Host-API spike, no network)

- **Build:** on the TV, enumerate, `claimInterface(forceClaim)` on every
  interface, build the endpoint map, log interrupt-IN reports to Logcat (§2).
- **Test:** plug a device into the TV and watch Logcat.
- **Gate:** live reports appear in Logcat **and the TV UI stops scrolling**
  (proves the kernel input driver let go — the no-root premise holds on your
  hardware).
- **Where:** L3, minimal. First contact with the Host API; de-risks the port
  before wiring the network in.
- **Status:** code complete; L3 hardware gate pending on the TV. The
  `:android` module now carries the spike: `HostApiSpike` opens the device,
  `claimInterface(forceClaim=true)` on **every** interface (§2 — the
  stop-scrolling step), parses `getRawDescriptors()` through the **core**
  `DescriptorParser` to log the endpoint map (and cross-checks the parsed
  interrupt-IN addresses against the Host API's own `UsbEndpoint` list), then
  runs one reader thread per interrupt-IN endpoint queuing a `UsbRequest` and
  logging each report to Logcat under tag `IoTowerSpike`. `MainActivity` drives
  it: enumerate → USB-permission handshake (§8) → Start/Stop. No network, no
  `UsbIpServer`, no `UsbBackend` — that is M7. `core` is untouched (`:core:test`
  stays green) and stays Android-free. The gate — live reports in Logcat **and**
  the TV UI ceasing to scroll — must be run on the TV with a pad plugged in
  (`./gradlew :android:installDebug`, then `adb logcat -s IoTowerSpike`); it
  could not be run in the authoring environment (no Android SDK / no TV). PRD:
  `prds/m6-android-claim-read.md`.

## M7 — Android integration  (first true end-to-end)

- **Build:** flesh out `AndroidUsbBackend` against the same `UsbBackend`
  contract the fake already satisfied, and run the proven `UsbIpServer` inside
  the foreground `ServerService` (§8).
- **Test:** from Fedora, `usbip attach` to the **TV's IP** with the **cheap
  generic gamepad**; `evtest` on the PC shows real input coming through the TV.
- **Gate:** a real device's input appears on the PC over the network via the TV.
- **Where:** L3.

## M8 — G29: OUT transfers, composite, reset

- **Build:** nothing device-specific by design — this validates the transparent
  path on the hard device (§7).
- **Test:** swap in the G29; confirm `new-lg4ff` binds, Oversteer sees it, and
  **force feedback fires** (the OUT path) from Oversteer's test or a game;
  deliberately let `new-lg4ff` flip it to native mode and confirm you recover
  from the re-enumeration.
- **Gate:** FFB physically moves the wheel, axes show in `evtest`, and the
  mode-switch reset recovers (manual re-attach acceptable here; automation is
  M10).
- **Where:** L3.

## M9 — Robustness

- **Build:** generic reset-reconnect, foreground-service survival, clean client
  disconnect (§6, §7, §8).
- **Test:** trigger a reset → recovers; screen off for 10+ min → still attached
  and responsive; kill the client → server returns to a clean listening state;
  measure input latency / jitter and compare Ethernet vs WiFi.
- **Gate:** survives all three; latency within an interactive budget on Ethernet.
- **Where:** L3.

## M10 — Optional PC companion  (§9)

- **Build:** auto-attach, the auto-reattach loop, and the optional PIN/TLS proxy.
- **Test:** unplug/replug or trigger a G29 mode-switch → the companion
  re-attaches with no manual step; with PIN on, a stock `usbip attach` is
  rejected while the companion path succeeds.
- **Gate:** hands-off reattach works; the PIN gate rejects the bare client.
- **Where:** L2 (proxy logic) + L3 (real reattach).

---

## Beyond v1 — v2 (future)

Only after v1 (M0–M10) is complete and proven. Recorded here so it isn't lost;
not yet broken into gated milestones.

- **Multiple simultaneous inputs.** Export several controllers at once (e.g. a
  USB hub of gamepads on the TV), each read independently on Fedora. Requires
  multi-device claim + lifecycle, one transfer engine per device, and several
  concurrent client connections routed by `devid`. The protocol groundwork is
  already in place (`DEVLIST` lists all claimed devices; `devid` disambiguates);
  see architecture.md §10 (v2) for the design outline. Depends on rock-solid
  single-device passthrough first.
