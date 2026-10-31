# PRD: v2 — Multiple simultaneous controllers (USB hub)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **"Beyond v1 — v2 · Multiple simultaneous inputs"** and
architecture **§10 (v2)**. The user's hardware workflow: a **USB hub** is plugged
into the TV, and **several USB controllers** are plugged into that hub. Each
controller must appear on the Fedora PC as its **own independent device**, read
by its own driver, with no cross-talk — e.g. two gamepads become two separate
`/dev/input/event*` nodes streaming simultaneously.

This is an **extension, not a redesign** (§10): the v1 wire protocol already
carries everything needed to disambiguate devices — `OP_REQ_DEVLIST` is built to
advertise a device *count* and a struct per device (§4.1), and every transfer
header carries `devid = (busnum << 16) | devnum` (§4.2). v1 simply pinned those
to a single hardcoded device (`UsbIpServer.EXPORTED_BUSID = "1-1"`, one backend,
one client at a time). This feature lifts that pin.

**Prerequisite (met):** single-device passthrough is proven end-to-end and
hardened (M8 G29/FFB, M9 gates 1–3, M10 companion). Per §10 this must be
rock-solid before adding multi-device concurrency — it is.

### How differentiation on the PC actually happens (the core idea)

Differentiation on the PC is **not engineered on the Fedora side at all** and is
**not keyed on VID/PID**. The discriminator is the **`busid`**. The entire job is:
give each claimed controller a unique, stable `busid` on the TV and advertise
them all. The PC then runs `usbip attach -b <busid>` once per controller; the
stock kernel client gives each attached busid its own vhci port → its own `/dev`
node → its own independent driver instance. **Two physically identical
controllers (same VID/PID) stay distinct because the kernel keys on the vhci
port/busid, not VID/PID.** Nothing device-specific is added anywhere
(Invariant 1).

## Scope

Delivers:

- **Core server exports N devices at once.** A registry of claimed devices, each
  with a unique identity (`busid`, `busnum`, `devnum`, `devid`, `path`).
  `OP_REQ_DEVLIST` advertises *all* of them; `OP_REQ_IMPORT` matches the
  requested `busid` to the right backend.
- **Concurrent clients.** The kernel opens one TCP connection per `usbip attach`,
  so the server serves **several connections simultaneously** — one independent
  reader + `TransferEngine` per connection, routing that connection's URBs to its
  imported device by `devid`. A busid already in its transfer phase is **not**
  importable a second time (USB/IP "device in use").
- **Android multi-claim lifecycle.** Claim and hold **N** controllers plugged
  into the hub (not the single-device-only rule of today), each with its own
  `AndroidUsbBackend`, all served by one `ServerService` / one `UsbIpServer`
  listener. Per-device USB-permission grant, per-device Eject, independent
  unplug/reset of one device without disturbing the others.
- **Desktop harness proves it with zero hardware (the headline L2 gate).** The
  harness registers **multiple** `FakeUsbBackend`s — deliberately with the
  **same descriptor/VID:PID** but **distinct busids and distinct scripted
  reports** — so `usbip attach` of each busid yields two `/dev/input/event*`
  nodes, each showing its *own* stream in `evtest` at the same time. This
  directly proves the user's concern: identical controllers are differentiated
  by busid and read independently.

**Staged delivery (three commits, each with its own pass gate — mirrors how the
project split M7/M7b–d, M9/M9a, M10/M11):**

- **Stage A — core + desktop (L1 + L2).** Multi-device registry, concurrent
  clients, multi-fake harness. **At the end of Stage A the whole multi-device
  protocol and PC-side differentiation are proven with zero Android and zero
  hardware** — this is where the "differentiated on the PC" question is settled.
- **Stage B — Android (L3).** Multi-claim lifecycle, per-device
  permission/eject/wake, service registry; validated on the TV with a real hub
  and two controllers.
- **Stage C — companion catch-all (L1 + L3).** `companion/iotowerd.py` becomes a
  single daemon that supervises **all** exported busids at once (auto-attach each,
  auto-reattach each on its own drop, clean detach of all on exit) — no more one
  process per device.

The implement pass should complete and gate the stages **in order** (A → B → C),
committing each: A and B must land first so C is built and tested against a TV
that actually exports multiple busids. Proposed milestone labels: **M12**
(Stage A), **M13** (Stage B), **M14** (Stage C) under the MILESTONES.md "v2"
section.

Out of scope (do **not** gold-plate):

- **No isochronous support** (still unsupported, §5, §7).
- **No change to the transfer engine's per-device logic.** `TransferEngine`
  already takes a `devid` and is per-connection; it is reused verbatim, one
  instance per client connection. Do not make the engine multi-device-aware.
- **No device tables, no VID/PID logic, no per-device branches** anywhere
  (Invariant 1). Routing is by `busid` (negotiation) and `(ep, direction)`
  (transfer) only.
- **No PIN/TLS** (M11, still deferred). Stage C stays orchestration-only (§9.1) —
  no protocol code on the PC, standard library only (Invariant 3).
- **No persistence of busids across app restarts** — busids are assigned per
  live session from topology (below).

## Approach

### Device identity — derived from topology, device-agnostic

Each exported device needs `(busid, busnum, devnum)` that is **unique among the
currently-exported set** and **stable for that device's lifetime on the bus**.
Derive it from the device's physical USB topology, not a counter and not VID/PID:

- **`busnum` / `devnum`** come from the device's own bus address. On Android,
  `UsbDevice.getDeviceName()` is the stable path `"/dev/bus/usb/<BBB>/<DDD>"`;
  parse `<BBB>` → `busnum` and `<DDD>` → `devnum`. The desktop fakes supply their
  own synthetic `(busnum, devnum)` pairs.
- **`busid`** = `"<busnum>-<devnum>"` (e.g. `"1-5"`), formatted without
  zero-padding, matching the stock `usbip` busid convention.
- **`devid`** = `(busnum << 16) | devnum` (§4.2), as already computed for the
  single device.

Uniqueness falls out for free: two connected devices never share a
`/dev/bus/usb/<bus>/<addr>` path, so their busids and devids differ by
construction. This replaces the `EXPORTED_BUSID` / `EXPORTED_BUSNUM` /
`EXPORTED_DEVNUM` / `EXPORTED_PATH` / `EXPORTED_DEVID` constants in
`UsbIpServer`.

> **Backward-compatibility requirement.** A single registered device supplied
> with `(busnum=1, devnum=1)` must still export **busid `"1-1"`** and **devid
> `(1<<16)|1`**, so the existing L1 tests, the companion, and the docs that
> reference `"1-1"` keep working unchanged. The desktop single-fake default path
> and the single-controller Android path both register with `(1, 1)` to preserve
> this.

### Core: the exported-device registry (`core/net`)

Introduce a small value type, e.g. `ExportedDevice`, holding one `UsbBackend`
plus its derived identity (`path`, `busid`, `busnum`, `devnum`, `devid`). The
`devid` and `busid` string are derived in its constructor from `(busnum,
devnum)`; `path` defaults to a stable synthetic string (`"/sys/devices/tv/" +
busid`). This is the single source of truth for a device's wire identity, so
DEVLIST and IMPORT for that device can never drift (same role the
`buildExportedDevice` helper plays today).

`UsbIpServer` holds a **thread-safe collection** of `ExportedDevice`s (registered
devices) instead of a single `backend` field, with:

- `addDevice(UsbBackend backend, int busnum, int devnum)` → registers and returns
  the `ExportedDevice` (or its busid). Idempotent per `(busnum, devnum)`.
- `removeDevice(busid or (busnum,devnum))` → unregisters; any client currently
  importing it has its connection closed (its reader unblocks, engine shuts down,
  backend is released by the caller).
- A constructor/registration ordering such that the listener can start before any
  device is registered (the service may start, then claim devices as they
  attach), and a DEVLIST with zero devices is a valid empty list (count 0).

`stop()` must close the listener **and all active client sockets** (today it
closes `serverSocket` + the single `activeClient`; generalize `activeClient` to a
concurrent set of active clients and close each).

### Core: DEVLIST advertises all, IMPORT matches busid

- **`handleDevlist`** — iterate the registry. Write the device count = N, then for
  each registered device its `usbip_usb_device` struct (from its `ExportedDevice`
  identity + that backend's `deviceInfo()`/`rawDescriptors()`) followed by its
  `usbip_usb_interface` array. Null-guard each backend's descriptors/deviceInfo
  (Invariant 6): a device whose backend returns null is **skipped** and the count
  reflects only successfully-described devices (do not abort the whole reply).
  Build the buffer after counting describable devices so the count and the body
  agree.
- **`handleImport`** — read the 32-byte busid, look it up in the registry. Unknown
  busid → `OP_REP_IMPORT` status ≠ 0 (as today). Known but **already in a transfer
  phase** → status ≠ 0 ("device in use"). Otherwise mark it imported, reply status
  0 + its `usbip_usb_device`, and enter the transfer phase for **that backend**
  with **that device's `devid`**. Clear the imported flag when the transfer phase
  returns (detach/error), so a re-attach of the same device works.

### Core: concurrent per-connection handling

Today `run()` calls `handle(client)` inline, so one connection is served at a
time. Change the accept loop to **dispatch each accepted connection to its own
handler thread** (a small cached/owned thread pool, or a plain `Thread` per
connection — N is a handful). Each handler runs the existing negotiation →
IMPORT → `runTransferPhase` flow independently, builds its **own**
`TransferEngine(backend, out, devid)` for its imported device, and tears it down
in a `finally`. Nothing is shared between connections except the read-only
registry lookup and the imported-busid set (both thread-safe). Track every live
client socket in the active-clients set and remove it on close, so `stop()` and
`removeDevice()` can close the right sockets.

`runTransferPhase` keeps its current body **unchanged in substance** — it already
is only the reader + one engine. The only change is that the `backend` and
`devid` it uses are the ones selected by `handleImport` for this connection,
rather than the removed `EXPORTED_*` constants. `TransferEngine` itself is
**untouched**.

> **Invariant to preserve:** each connection still has exactly **one writer
> thread** (owned by its own `TransferEngine`) as the sole owner of *that
> socket's* `OutputStream` (§5.1). Concurrent connections write to *different*
> sockets, so there is no new cross-connection interleaving hazard — do not add
> any shared writer. `setTcpNoDelay(true)` stays set per accepted socket (§6).

### Android: multi-claim lifecycle (`:android`)

The v1 selection rule deliberately refused to serve more than one device
(`pickDevice` requires `getDeviceList().size() == 1`) specifically so it would
not grab the TV's **internal** USB devices (the Realtek BT/Wi-Fi combo) by
"taking the first" (§8). Multi-device must keep that safety while holding several
*external* controllers. **Default selection policy (device-agnostic, no table):**

- **Hot-plug claim, hold many.** Claim each device that arrives via
  `ACTION_USB_DEVICE_ATTACHED` (intent or runtime broadcast) this session and is
  not ejected, and **keep serving all previously-claimed, still-present
  devices** — drop the implicit "one served device" assumption. Internal TV
  devices are present at boot and never fire an attach event during a session, so
  they are excluded **by construction**, with no denylist and no class/VID logic.
- **Cold-start / `onResume`:** keep the existing sole-device convenience (claim
  the device iff exactly one is connected and not ejected) for the
  single-controller-no-hub case. With a hub present the count is > 1, so this path
  correctly declines and the controllers are claimed as they attach. (A hub
  **pre-populated before the app launches** is the documented limitation below.)

State that is currently **single-valued must become per-device collections**,
keyed on the opaque `UsbDevice.getDeviceName()` (never VID/PID — Invariant 1):

- `requestingDeviceName` (String) → a **set** of in-flight permission requests, so
  simultaneous attaches each get exactly one prompt and never collide. This
  firmware has no "use by default", so **N controllers = N first-time prompts**
  (one per device); the guard only removes *duplicate* prompts for the same
  device, as in v1.
- `ejectedDeviceName` (String) → a **set** of ejected device names; a device stays
  released until physically unplugged (its `DETACHED` clears it from the set),
  exactly as v1 but per-device.
- The served-device state (one `AndroidUsbBackend` + its derived `(busnum,
  devnum)`) → a **map** keyed by device name.

`ServerService` holds **one** `UsbIpServer` (one listener on `:3240`) for the
lifetime of serving, and exposes `addDevice(UsbDevice)` /
`removeDevice(deviceName)` that build/release an `AndroidUsbBackend` and
register/unregister it with the server (deriving `(busnum, devnum)` by parsing the
device name path). This replaces the v1 "restart the whole server per device"
model. Keep the `lifecycleLock` serialization so rapid concurrent attaches can't
race the registry. Lifecycle details:

- **Wake lock:** one `PARTIAL_WAKE_LOCK` held while **any** device is served
  (acquired when the first device starts serving, released when the last is
  removed / `onDestroy`), not one per device (§8, M9).
- **Foreground notification:** summarize the served set (e.g. "Serving N
  devices"), device-agnostic text.
- **Per-device teardown on gone/eject:** the M7d/M9 gone-listener and the DETACH
  handler must remove **only that one device** from the registry and close only
  its clients; the others keep serving. Generalize `setGoneListener` so the
  service knows *which* backend went gone (the backend already maps 1:1 to a
  device). Reset/re-enumeration of one controller (the §7 problem) recovers for
  that device alone.

**UI:** the launcher lists the served devices, each row showing its `VID:PID`
(presentation only) and its own D-pad-focusable **Eject** control; Eject releases
just that device (removes it from the service registry, latches its name ejected)
and leaves the rest serving. This generalizes the single-card + single-Eject UI;
keep it presentation-only and device-agnostic.

`AndroidUsbBackend` stays **one instance per device** and needs no multi-device
awareness — its contract is unchanged. The device-name → `(busnum, devnum)` parse
may live in the service or a small helper; keep `core` Android-free (the parse of
an Android device-name string is `:android`-side; `core`'s registry just receives
the two ints).

### Desktop harness (L2 enabler)

- `FakeUsbBackend` gains a constructor taking a distinct `(busnum, devnum)` and a
  distinct scripted-report cycle (e.g. a per-instance seed/label so the two
  streams are visibly different in `evtest`). Keep the **same descriptor /
  VID:PID** for both instances (that is the point — prove identical devices are
  differentiated by busid). The single-arg/default constructor keeps `(1, 1)` +
  today's report cycle for backward compatibility.
- `Main` constructs **two** `FakeUsbBackend`s with distinct `(busnum, devnum)`
  (e.g. `(1,1)` and `(1,2)` → busids `"1-1"`, `"1-2"`), registers both with one
  `UsbIpServer`, and runs it. A CLI arg to pick the count (default 2) is fine but
  not required.

### Companion catch-all (Stage C, `companion/iotowerd.py`, §9.1)

Today the companion is a **single-busid linear state machine**: one `Supervisor`
runs `discover → attach → watch(one vhci port) → clean-detach → discover` for
exactly one `--busid` (default `"1-1"`). For a hub this must become **one daemon
that supervises the whole exported set** — the user's stated goal of a catch-all
daemon, not one process per device. Keep it orchestration-only (shells out to
stock `usbip`), standard-library-only (Invariant 3).

Convert the linear state machine into a **reconciliation loop** over a set of
busids. Each tick (the existing `--interval`):

1. **Desired set** = `parse_exported_busids(usbip list -r <host>)` — every busid
   the TV currently exports (the parser already returns the full list in listing
   order; no change needed to it). Apply the optional `--busid` filter if given
   (see below).
2. **Held map** = our record of `busid → (attached vhci port, since-time)`.
3. **Ports in use** = `parse_ports_in_use(usbip port)` — the device-agnostic
   port-delta signal already used today.
4. **Reconcile** (keep this a **pure function** so it is unit-testable, matching
   the companion's "risky parsers are unit-tested" discipline):
   - For each desired busid **not** in the held map → **attach** it. Do attaches
     **one at a time within a tick** so the before/after `ports_in_use` delta
     maps unambiguously to the busid just attached (reuse the existing
     snapshot-before / poll-after logic, now recording the new port under that
     busid). Wedge recovery (`modprobe -r vhci-hcd; modprobe vhci-hcd`) stays, but
     is a **shared, once-per-tick** action (the module reload affects all ports),
     not per-busid — and only when the documented `open vhci_driver` wedge is
     seen.
   - For each held busid whose port has **dropped** out of `ports_in_use` (reset,
     re-enumeration, or TV-side eject) → **clean-detach** that port (so the stock
     client doesn't wedge) and remove it from the held map; it re-attaches on a
     later tick if still exported (its busid may differ after re-enumeration —
     that is fine, it is just a new desired entry).
   - Held busids still present → leave attached; update their up-time.

**Teardown (SIGINT/SIGTERM):** detach **every** port in the held map (not just
one), reusing the existing per-port `detach`. The teardown must detach exactly
the ports *we* created and no others (same safety property as today, now over a
set) — i.e. iterate the held map's recorded ports, do **not** detach by scanning
all ports for the host.

**CLI / behavior change:**
- `--busid` becomes **optional and repeatable**. **Absent → catch-all** (supervise
  every exported busid — the new default and the primary use). Given (one or more)
  → supervise only those busids (the power-user pin / the old behavior).
- The default invocation `iotowerd.py --host <ip>` now attaches *all* controllers.
- **Status/console UI:** generalize the single live status line to summarize the
  set — e.g. `SERVING N devices` with per-busid up-times, or a compact
  `busidA↑mm:ss busidB↑mm:ss` line. Keep the finalized, timestamped, state-labelled
  log lines per event (ATTACH/ATTACHED/RESET/DETACH) but tag each with its busid so
  concurrent devices are legible. Preserve `--no-color` / `$NO_COLOR` / piped
  suppression.

**Why this is now clean (depends on Stage A):** v1's companion carried a note that
`usbip list -r` can hang against the **single-client** TV server while a device is
already attached. Stage A makes the TV server **multi-client**, so `usbip list`
no longer contends with an active attach — the per-tick discover is safe. Stage C
must be implemented after A/B so it is tested against a TV exporting ≥ 2 busids.

The `Supervisor` likely becomes a small **manager** holding per-busid state (a
lightweight record per held busid), or a map inside one supervisor; either is
fine as long as the attach/detach/watch logic per busid is the proven v1 logic,
just keyed by busid. Do **not** spawn a thread or subprocess per device — one
reconcile loop over the set.

## Files to touch

**Stage A — core + desktop:**

- `core/src/main/java/com/iotower/core/net/ExportedDevice.java` — **new**. Value
  type: one `UsbBackend` + derived `(path, busid, busnum, devnum, devid)`.
- `core/src/main/java/com/iotower/core/net/UsbIpServer.java` — registry of
  `ExportedDevice`s (replace the single `backend` + `EXPORTED_*` constants);
  `addDevice`/`removeDevice`; DEVLIST advertises all; IMPORT matches busid +
  rejects in-use/unknown; **per-connection handler threads**; active-clients set;
  `stop()` closes all. `runTransferPhase` body unchanged except it uses the
  selected backend + devid.
- `core/src/main/java/com/iotower/core/engine/TransferEngine.java` — **unchanged**
  (confirm; it already takes `devid`).
- `desktop/src/main/java/com/iotower/desktop/FakeUsbBackend.java` — add
  `(busnum, devnum)` + distinct scripted-report constructor; keep default `(1,1)`.
- `desktop/src/main/java/com/iotower/desktop/Main.java` — register two fakes.
- `core/src/test/java/com/iotower/core/net/MultiDeviceServerTest.java` — **new**
  L1 test (below).

**Stage B — Android:**

- `android/src/main/java/com/iotower/android/ServerService.java` — one
  `UsbIpServer`; `addDevice`/`removeDevice`; per-device gone handling; one wake
  lock for any-served; foreground summary; device-name → `(busnum, devnum)` parse.
- `android/src/main/java/com/iotower/android/MainActivity.java` — multi-claim
  (drop sole-only on the hot-plug path); set-based `requesting`/`ejected`; served
  map; per-device UI rows + per-device Eject.
- `android/src/main/java/com/iotower/android/AndroidUsbBackend.java` — likely only
  the gone-listener carrying its device identity; otherwise unchanged.

**Stage C — companion:**

- `companion/iotowerd.py` — reconcile loop over the exported busid set: per-busid
  held-state map, attach-each / reattach-each / clean-detach-each, detach-all on
  teardown; `--busid` optional + repeatable (absent ⇒ catch-all); busid-tagged log
  lines + set-summary status line. Extract the attach/detach decision as a **pure
  reconcile function** for unit testing.
- `companion/test_iotowerd.py` — **new** unit cases for the reconcile function
  (below); existing parser tests stay green.

**Docs (same commit as each stage — feature-workflow step 3):**

- `MILESTONES.md` — add **M12** (Stage A, L1+L2 gate), **M13** (Stage B, L3 gate),
  and **M14** (Stage C, L1+L3 gate) under "Beyond v1 — v2"; mark done as each lands.
- `architecture.md` **§10** — move multi-device from "v2 (future)" to implemented;
  §6 (one-client note), §4.1/§4.2 updated to reflect N devices / concurrent
  clients; **§9.1** updated so the companion supervises all exported busids.
- `README.md` — update "Scope and limitations" (the "One device and one client at
  a time in v1" bullet) and the running instructions (attach per busid; the
  companion now attaches all).
- `DEVELOPING.md` — the multi-fake `:desktop:run` and the multi-attach test
  procedure.
- `companion/README.md` — the new catch-all default and the `--busid` filter.

## Test plan

- **Layers:** Stage A = L1 (new JUnit integration test over loopback) + L2
  (desktop harness + stock `usbip` + `evtest`, on the Fedora box). Stage B = L3
  (TV + real hub + two controllers).

- **New L1 cases (and only new ones)** — one new file `MultiDeviceServerTest`,
  driving the *real* `UsbIpServer` over loopback (same harness style as
  `DevlistNegotiationTest` / `ConcurrencyEngineTest`), registering **two**
  in-test backends with distinct `(busnum, devnum)` and distinct interrupt-IN
  payloads:

  1. `devlistAdvertisesAllRegisteredDevices` — register two devices, send
     `OP_REQ_DEVLIST`, **assert** count == 2 and that both devices' busids /
     identities / interface arrays are framed correctly in order. Covers the
     multi-device DEVLIST path; `DevlistNegotiationTest` only covers the
     single-device reply, so this is a genuinely new path (extend/parameterize it
     only if that is cleaner than a new case — otherwise new case).
  2. `concurrentClientsReadTheirOwnDeviceIndependently` — open **two** loopback
     connections at once; IMPORT busid A on conn1 and busid B on conn2; submit an
     interrupt-IN `CMD_SUBMIT` on each; the in-test backends complete each with
     that device's distinct payload. **Assert** conn1's `RET_SUBMIT` carries
     device A's `devid` + A's payload and conn2's carries device B's `devid` + B's
     payload, with both connections live simultaneously and no cross-talk. Covers
     concurrent-client handling + busid→backend routing + per-connection devid —
     nothing today exercises more than one connection at a time.
  3. `importOfInUseBusidIsRejected` — IMPORT busid A on conn1 (held in transfer
     phase), then IMPORT busid A again on conn2. **Assert** conn2 gets
     `OP_REP_IMPORT` status ≠ 0 while conn1 stays served. Covers the in-use guard.

  Do **not** add a standalone engine test (covered by `ConcurrencyEngineTest`,
  which must stay green through the registry change). The **regression guard** is
  that all existing single-device L1 tests (`DevlistNegotiationTest`,
  `ImportControlTest`, `ConcurrencyEngineTest`) pass unchanged with one device
  registered as `(1, 1)` → busid `"1-1"`.

- **L2 gate (Stage A, Fedora box):** `./gradlew :desktop:run` with two fakes
  (identical descriptor, busids `"1-1"` and `"1-2"`, distinct report cycles) →
  `usbip list -r 127.0.0.1` lists **both**; `sudo usbip attach -b 1-1` **and**
  `-b 1-2`; `lsusb` shows two devices; **two `/dev/input/event*` nodes each show
  their own distinct scripted stream in `evtest` at the same time**, with nothing
  plugged in; clean `detach` of each independently. This is the unambiguous
  "multiple controllers differentiated and read independently on the PC" proof,
  and it directly answers the identical-VID/PID worry.

- **L3 gate (Stage B, TV):** a real hub with **two controllers** (start with two
  cheap generic pads, then a pad + the G29): each auto-claims (its own permission
  prompt the first time); `usbip list -r <tv-ip>` shows both busids;
  `usbip attach` each; `evtest` on each shows that controller's live input
  independently; **unplug one → only that one drops on the PC, the other keeps
  streaming**; per-device **Eject** releases one and leaves the other serving.

- **New L1 cases — companion (Stage C), `companion/test_iotowerd.py`:** unit-test
  the **pure reconcile function** (given `exported` set, `held` map, `ports_in_use`
  set → `(to_attach, to_detach)`), since that is the new risky logic:

  1. `reconcileAttachesNewlyExportedBusids` — exported `{1-1, 1-2}`, held `{}` →
     `to_attach == [1-1, 1-2]`, `to_detach == []`.
  2. `reconcileDetachesDroppedPort` — held `{1-1→port3, 1-2→port4}`, `ports_in_use
     == {3}` (port 4 gone) → `to_detach` includes the `1-2` port, `1-1` left alone.
  3. `reconcileHoldsSteadyState` — held matches exported and all ports in use →
     both lists empty (no churn). Covers the "no redundant re-attach" path.

  The existing parser tests (`parse_exported_busids`, `parse_ports_in_use`) stay
  green; extend `parse_exported_busids` coverage to a **multi-busid** `usbip list`
  dump only if not already covered. Do **not** unit-test the subprocess shelling
  (unchanged, orchestration-only).

- **Fixtures / testdata:** none new. The fakes synthesize identity + reports; L2
  reuses `testdata/simple-gamepad-descriptors.bin` (shared by both instances on
  purpose). Companion tests use inline sample `usbip` output strings, as today.

## Pass gate

- **Stage A (M12):** `./gradlew :core:test` green incl. the three new cases and
  all unchanged single-device tests; **and** the L2 two-fake run above — two
  busids listed, both attached simultaneously, each streaming its own input in
  `evtest`, independent detach. Docs (§10, README scope, DEVELOPING, MILESTONES
  M12) audited and updated in the same commit.
- **Stage B (M13):** on the TV, two real controllers through a hub each read
  independently on the PC, one claimed/ejected/unplugged without disturbing the
  other. Docs (MILESTONES M13, §8 lifecycle, README) updated in the same commit.
- **Stage C (M14):** `python3 -m unittest discover -s companion` green incl. the
  reconcile cases; **and** on the Fedora box + TV, a **single** `iotowerd.py
  --host <tv-ip>` (no `--busid`) auto-attaches **all** exported controllers, each
  shows in `evtest`, one controller's reset/eject re-attaches **just that one**
  while the others stay up, and `Ctrl-C` detaches **every** port it created
  leaving no stale `usbip port` entry. Docs (MILESTONES M14, §9.1, companion
  README) updated in the same commit.

## Risks / open questions

- **Selection policy — DECIDED (user, 2026-10-31): use the specified default.**
  Hot-plug-claim this session; keep the sole-device convenience at cold start.
  This excludes the TV's internal devices *by construction*, with no VID/PID or
  class denylist (Invariant 1). The **known limitation is accepted**: a
  controller already plugged into the hub **before the app launches** is not
  auto-claimed until replugged — a replug fixes it. Document this; do **not**
  add any class/VID exclusion to make launch-present devices claimable (that
  would violate Invariant 1). If a future need makes launch-present claiming
  necessary, that is a fresh design decision — stop and flag, do not improvise.
- **Hub power / bandwidth (L3 reality, not a code issue).** Several controllers —
  the G29 especially — can brown out a bus-powered hub; recommend a
  **powered** hub for L3 and note it in the docs. Not a server concern.
- **N permission prompts.** No "use by default" on this TV firmware → one
  first-time prompt per controller. Expected; surface clearly, don't try to
  suppress legitimate per-device prompts.
- **Thread count.** One handler thread + one engine (writer + control worker pool)
  per connection. For a handful of controllers this is fine; if the implementer
  anticipates many simultaneous devices, flag before adding pooling beyond the
  simple per-connection model.
- **busid stability across re-enumeration.** `devnum` changes when a device
  re-enumerates (§7, e.g. G29 mode switch), so its busid changes mid-session —
  exactly as a v1 re-enumeration already looks like a fresh session. The PC
  re-runs `usbip list` + `attach` (or the companion re-discovers). Acceptable; do
  **not** try to pin a stable busid across re-enumeration in this PRD.
- **Backward compatibility is a hard requirement, not a nicety.** The
  single-device path must still yield busid `"1-1"` / devid `(1<<16)|1` so the
  existing tests, docs, and `companion/iotowerd.py` keep working. If the registry
  design cannot preserve this cleanly, **stop and flag**.
- **Companion catch-all (Stage C) — DECIDED (user, 2026-10-31): fold in, one
  daemon for all USBs.** Not one process per device. Build it **after** A/B so it
  is tested against a TV exporting ≥ 2 busids. The manual `usbip attach` per busid
  stays the baseline and must keep working (Invariant 3); the catch-all daemon is
  the convenience layer on top, still orchestration-only. **Reconcile-ordering
  caveat to flag:** attaches within a tick must be serial so the before/after
  port-in-use delta maps to the right busid — if the implementer finds the delta
  ambiguous when two devices attach in the same tick, **stop and flag** rather than
  guessing which new port belongs to which busid.
- **TV single-client note is removed by Stage A.** The companion's existing
  concern that `usbip list -r` can hang against the single-client TV server no
  longer applies once the TV is multi-client (Stage A). Confirm this holds at L3;
  if `usbip list` still contends with an active attach after Stage A, **stop and
  flag** — it would mean the TV accept path is not truly concurrent.
