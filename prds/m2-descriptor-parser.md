# PRD: M2 — Descriptor parser + endpoint map (`core/usb`)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M2 — Descriptor parser + endpoint map (`core/usb`)**.
Architecture **§5** ("Build the endpoint map from descriptors, not assumptions")
and **§4.1** (what a `usbip_usb_device` reports). Layer **L1** only.

`DescriptorParser` walks the raw USB descriptors — exactly the bytes
`UsbDeviceConnection.getRawDescriptors()` returns, which is also what
`sudo cat /sys/bus/usb/devices/<busid>/descriptors` produces (testdata/README.md
§1) — into an `EndpointMap`: for every interface, for every endpoint, record
`(address, type, direction, maxPacketSize, interval)` plus which interface the
endpoint belongs to, and the device's interface count. URBs are later routed by
`(endpoint, direction)` against this map, never by device identity — a composite,
multi-interface device (the G29) parses by the same generic walk as a one-button
gamepad.

M0 already scaffolds this package: `EndpointInfo`, `EndpointMap`, and a stub
`DescriptorParser.parse(byte[]) -> EndpointMap` that throws
`UnsupportedOperationException`. This PRD covers only the delta: implement the
walk, extend the two value classes minimally so topology (interface membership +
interface count) is expressible, and add the L1 tests.

The authoritative layout reference is the **USB 2.0 spec, §9.5–9.6** (standard
descriptor formats) and `Documentation/usb/usbip_protocol.rst` for the §4.1
field names. Note the endianness split below — it is the single easiest thing to
get wrong here.

## Scope

**Delivers:**

- `DescriptorParser.parse(byte[] rawDescriptors)` — a pure byte-walk that returns
  a populated `EndpointMap`. Handles the device descriptor at the front, the
  configuration descriptor, interface descriptors, endpoint descriptors, and
  **skips any other descriptor type** (string, HID/class-specific `0x21`,
  interface-association, vendor) by walking `bLength`. Composite multi-interface
  and simple single-interface devices both fall out of the same loop.
- `EndpointInfo` gains an `interfaceNumber` field (which interface owns the
  endpoint) so topology is recorded, not just a flat endpoint list. This is the
  §5 "for every interface, for every endpoint" requirement and the basis for the
  claim-every-interface invariant later; it has **no existing call sites** (only
  `DeviceInfo` is referenced outside the `usb` package), so the constructor
  change is contained.
- `EndpointMap` gains an `interfaceCount` (set by the parser from the
  configuration descriptor's `bNumInterfaces`) with a getter, so the milestone's
  "assert the interface count" is expressible without inferring it from the
  endpoint set (an interface may legitimately have no non-control endpoints).
- L1 tests asserting exact topology for a simple-gamepad descriptor and a
  composite multi-interface descriptor, plus one malformed-input case.

**Out of scope (do NOT gold-plate):**

- **No `DeviceInfo` population.** `usbip_usb_device` identity fields (idVendor,
  idProduct, class, …) come from the backend's `UsbDevice`/`deviceInfo()` in §4.1
  (M3), not from this parser. `parse` returns an `EndpointMap` only; do not have
  it build or return a `DeviceInfo`, and do not read the device descriptor's
  identity fields into anything. (The device descriptor is walked over and
  skipped.)
- **No routing, no transfers, no claiming.** Mapping URBs to Host-API calls, the
  engine, and `claimInterface` are §5/§5.1 and M5–M7. M2 only *builds* the map.
- **No multi-configuration support.** v1 is one device, one configuration
  (README "one device and one client at a time"). Parse the **first**
  configuration only; if a second configuration descriptor (`bDescriptorType ==
  0x02`) is encountered, stop the walk there. Do not merge endpoints across
  configurations.
- **No isochronous special-casing.** If an ISO endpoint ever appears
  (`bmAttributes & 0x03 == 1`) record it like any other — the parser is
  device-agnostic and type-agnostic. Rejecting ISO transfers is a later,
  higher-layer concern; the parser does not filter.
- **No new files under `testdata/`.** See Test plan / Risks — the fixtures are
  synthetic and inlined, and must not masquerade as real captures.

## Approach

One class does the work; two value classes get one field each.

### The walk (`DescriptorParser.parse`)

A single forward pass over the byte array. Every USB descriptor is
length-prefixed: byte 0 is `bLength` (total size of *this* descriptor), byte 1 is
`bDescriptorType`. Advance strictly by `bLength`, dispatching on type:

- `0x02` **CONFIGURATION** — read `bNumInterfaces` (offset +4) into a local; this
  becomes `EndpointMap.interfaceCount`. If a configuration has already been seen,
  **stop** (v1 first-config-only).
- `0x04` **INTERFACE** — read `bInterfaceNumber` (offset +2) into
  `currentInterface`; subsequent endpoints belong to it.
- `0x05` **ENDPOINT** — read the endpoint and `map.add(...)` it:
  - `address` = `bEndpointAddress` (offset +2), the full 8-bit value **including**
    the `0x80` IN direction bit — this is the map key.
  - `type` = `bmAttributes` (offset +3) `& 0x03` — maps directly onto the
    existing `EndpointInfo.TYPE_CONTROL/ISO/BULK/INTERRUPT` constants (0/1/2/3),
    which are defined to equal the USB transfer-type bits.
  - `direction` = `(address & 0x80) != 0 ? UsbIp.DIR_IN : UsbIp.DIR_OUT`.
  - `maxPacketSize` = `wMaxPacketSize` (offset +4, **u16**) `& 0x07FF` — the low
    11 bits are the byte count; bits 11–12 are the high-speed
    additional-transactions field and are not a size, so mask them off.
  - `interval` = `bInterval` (offset +6).
  - `interfaceNumber` = `currentInterface`.
- **everything else** (`0x01` device, `0x03` string, `0x21` HID and other
  class-specific, `0x0B` IAD, vendor) — skip by advancing `bLength`.

**Endianness — the one trap.** USB descriptors are **little-endian**
(`wMaxPacketSize`, `bcdUSB`, etc. are LE). The USB/IP *wire* protocol is
big-endian, but that is `core/protocol`'s concern, not this parser's. Read the
one multi-byte field this parser needs (`wMaxPacketSize`) as **LE**:
`(raw[i] & 0xFF) | ((raw[i+1] & 0xFF) << 8)`. Do **not** reuse a BIG_ENDIAN
`ByteBuffer` here. Plain `byte[]` index arithmetic is clearest; a `ByteBuffer`
set to `LITTLE_ENDIAN` is acceptable but must not be confused with the protocol
codecs' BE buffers.

**Robustness (invariant 6 — a long-running server must not die on bad input).**
The parser must never NPE, infinite-loop, or throw `ArrayIndexOutOfBounds` on
malformed descriptors; it throws a clear `IllegalArgumentException` instead:
- `raw == null` or `raw.length < 2` → `IllegalArgumentException`.
- `bLength == 0` → `IllegalArgumentException` (otherwise the walk never advances —
  a hostile/garbage buffer would spin forever).
- `pos + bLength > raw.length` (descriptor runs past the buffer) →
  `IllegalArgumentException`.
- An ENDPOINT/INTERFACE/CONFIG descriptor whose `bLength` is shorter than its
  fixed minimum (7 / 9 / 9) → `IllegalArgumentException`.
Loop condition is `pos + 2 <= raw.length` so a trailing stray byte ends the walk
cleanly rather than reading past the end.

**Invariants preserved:** device-agnostic (no VID/PID read, no device table, no
per-device branch — the walk is identical for every device); `core` stays
Android-free (plain `java.*` only, no `android.*`); the BE-wire invariant is
untouched (this parser is LE and does not touch `core/protocol`).

### Value-class deltas

- `EndpointInfo`: add `public final int interfaceNumber;` as the **last**
  constructor parameter; update the constructor and Javadoc. Keep the existing
  public-final-field style. (No external constructor call site exists — verified:
  the only cross-package reference in `core`/`desktop`/`android` is to
  `DeviceInfo`.)
- `EndpointMap`: add `private int interfaceCount;`, a `public int
  interfaceCount()` getter, and a `public void setInterfaceCount(int)` the parser
  calls once at the end. Leave `add/get/all/size` unchanged.

## Files to touch

Edit:

- `core/src/main/java/com/iotower/core/usb/DescriptorParser.java` — implement
  `parse`.
- `core/src/main/java/com/iotower/core/usb/EndpointInfo.java` — add
  `interfaceNumber`.
- `core/src/main/java/com/iotower/core/usb/EndpointMap.java` — add
  `interfaceCount` field + getter + setter.

Add:

- `core/src/test/java/com/iotower/core/usb/DescriptorParserTest.java` — the L1
  tests below.

Do **not** touch `core/protocol`, `desktop`, `android`, `DeviceInfo`,
`UsbBackend`, or `UsbTransfer`.

## Test plan

- **Layer(s):** L1 (`:core:test`).

- **Fixtures / testdata:** two descriptor byte arrays, **inlined as documented
  `byte[]` literals** in `DescriptorParserTest` (one field per line with a
  comment, in the style of `GoldenVectorTest`). They are **synthetic**, built by
  hand to the USB 2.0 descriptor layout — this environment has no bound USB
  device or root, so a real capture (testdata/README.md §1) is impossible here,
  exactly as for the M1 golden vectors. See Risks. Do **not** add `.bin` files
  under `testdata/` for these — a synthetic blob in `testdata/` would later be
  mistaken for real ground truth.

- **New cases (only new ones — this is a brand-new test class; nothing in
  `core/protocol` tests overlaps):**

  1. **`parsesSimpleGamepad`.** Fixture A: device descriptor + config with **one**
     HID interface (`bInterfaceNumber 0`) carrying **one** interrupt-IN endpoint
     `0x81` (maxPacketSize 8, interval 10), with a HID class descriptor (`0x21`)
     between the interface and endpoint to prove class-specific descriptors are
     skipped. Purpose: the single-interface happy path and the skip-unknown path.
     Assert: `map.size() == 1`; `map.interfaceCount() == 1`; endpoint `0x81` has
     `type == TYPE_INTERRUPT`, `direction == DIR_IN`, `maxPacketSize == 8`,
     `interval == 10`, `interfaceNumber == 0`.

  2. **`parsesCompositeDevice`.** Fixture B: device descriptor + config with
     **two** interfaces — interface 0 (HID) with an interrupt-IN `0x81`
     (maxPacketSize 16, interval 2) **and** an interrupt-OUT `0x01` (maxPacketSize
     16, interval 2); interface 1 (vendor-specific) with a **bulk**-IN `0x82`
     (maxPacketSize 64, interval 0). A HID class descriptor sits in interface 0.
     Purpose: the composite/multi-interface walk, IN **and** OUT endpoints, a
     **second transfer type** (bulk, so type decode isn't hardwired to
     interrupt), and correct endpoint→interface attribution. This is the code
     path that cases 1 does not cover (multiple interfaces, OUT direction, bulk
     type, per-interface grouping). Assert: `map.size() == 3`;
     `map.interfaceCount() == 2`; `0x81` → INTERRUPT/IN/16/2/iface 0; `0x01` →
     INTERRUPT/OUT/16/2/iface 0; `0x82` → BULK/IN/64/0/iface 1.

  3. **`rejectsMalformedDescriptors`.** Purpose: the robustness path (invariant
     6) that neither happy-path case exercises. Two `assertThrows`
     `IllegalArgumentException` in one method: (a) a descriptor with `bLength ==
     0` (would otherwise spin forever); (b) a descriptor whose `bLength` runs past
     the end of the buffer (truncated capture). Optionally also assert on `null`
     input. One method, not three classes (CLAUDE.md "minimize test count").

  No golden-byte / round-trip test is added — encoding is `core/protocol`'s
  concern and already covered there; M2 asserts *parsed topology*, not framing.

## Pass gate

Two runs, framed as in the M1 PRD (the in-session environment cannot run Gradle —
Maven Central is egress-blocked by org policy, 403 through the proxy; only
github.com is reachable — so JUnit's runner cannot be fetched here):

1. **In-session (no Gradle, no JUnit runner available).** Compile **all** `core`
   main sources with `javac --release 11` — this must succeed, proving the parser
   and the `EndpointInfo`/`EndpointMap` deltas build cleanly. Then run a **plain
   `java` assertion harness** (a throwaway `main`, not committed) that reproduces
   the fixtures and assertions of cases 1–3 against the compiled
   `DescriptorParser` and prints `PASS`/`FAIL`. **Gate:** main sources compile and
   the harness reports every assertion passing. Because the JUnit runner is
   unfetchable here, the committed `DescriptorParserTest` is **not executed
   in-session** — its canonical run is gate (2). State this explicitly in the
   completion note, and keep the harness's fixture bytes byte-identical to the
   committed test's so the harness genuinely validates the same thing.

2. **Canonical (user's machine).** `./gradlew :core:test` on the user's Fedora box
   (JDK 17) — runs `DescriptorParserTest` plus the existing protocol tests, all
   green. This is the authoritative gate the **user** re-runs; say so in the
   completion note.

## Risks / open questions

- **Fixtures are synthetic, not captured.** MILESTONES.md M2 says to feed a
  captured **G29** dump and a **simple-gamepad** dump from `testdata/`. This
  automated session has no bound USB device and no root, so a live capture
  (testdata/README.md §1) is impossible — identical to the M1 golden-vector
  situation. The fixtures are therefore reasoned field-by-field from the USB 2.0
  descriptor spec and inlined so the derivation is auditable in review. The
  parser is device-agnostic, so a spec-accurate synthetic composite descriptor
  exercises exactly the same code paths a real G29 dump would. **Deviation to
  flag to the human, not paper over:** when convenient, capture the real
  `g29-descriptors.bin` and a real gamepad dump on the Fedora box per
  testdata/README.md §1 and cross-check them — either point a new file-loading
  test at them or replace the inline fixtures. Residual risk until then: a blind
  spot shared between the spec and our reading of it (e.g. an unusual descriptor
  ordering real firmware emits).
- **First-configuration-only.** Parsing stops at a second `CONFIGURATION`
  descriptor by design (v1 one-config). If a real target ever exports the wheel
  on a non-default configuration, this needs revisiting — but that is out of v1
  scope; **stop and flag** rather than adding multi-config handling silently.
- **`maxPacketSize` masking.** Recording `wMaxPacketSize & 0x07FF` drops the
  high-speed additional-transaction bits. For interrupt/bulk on the target
  devices these bits are zero, so it is lossless in practice; note it in Javadoc.
  If a high-bandwidth endpoint ever needs the multiplier, revisit — do not invent
  a use for it now.
- **`interfaceNumber` added to `EndpointInfo`.** Verified no external constructor
  call sites today. If the implementer finds one (e.g. a `FakeUsbBackend` that
  constructs `EndpointInfo`), update it; if the change turns out to ripple beyond
  the `usb` package, **stop and flag** rather than reshaping other modules.
