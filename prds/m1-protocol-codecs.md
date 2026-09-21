# PRD: M1 — Protocol codecs (`core/protocol`)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M1 — Protocol codecs (`core/protocol`)**. Architecture §4 (the
wire protocol / structs) and §4.2 (transfer-phase blocks). Layer **L1** only.

M0 already delivers three of the eight wire structs:

- `UsbIp` — constants (op codes, commands, `VERSION 0x0111`, `PORT 3240`,
  directions). **Done.**
- `OpHeader` — the 8-byte negotiation header (§4.1). **Done, tested.**
- `UsbIpHeaderBasic` — the 20-byte transfer header `usbip_header_basic` (§4.2).
  **Done, tested** in `HeaderCodecTest`.

This PRD covers only the **delta**: the six remaining wire structs, their
round-trip tests, and golden-byte vectors.

The authoritative layout reference is the kernel's
`Documentation/usb/usbip_protocol.rst` and `drivers/usb/usbip/usbip_common.h`
(`struct usbip_usb_device`, `usbip_usb_interface`, `usbip_header_cmd_submit`,
`usbip_header_ret_submit`, `usbip_header_cmd_unlink`, `usbip_header_ret_unlink`).
Architecture §4 is a map to it, and all layouts below were reconciled against
both.

## Scope

**Delivers** — encode/decode codecs (each in `core/protocol`, pure Java, no
Android imports, `ByteBuffer` BIG_ENDIAN) for the six structs not yet
implemented:

1. `UsbIpUsbDevice` — `usbip_usb_device`, **312 bytes** (§4.1).
2. `UsbIpUsbInterface` — `usbip_usb_interface`, **4 bytes** (§4.1).
3. `CmdSubmit` — the 28-byte `USBIP_CMD_SUBMIT` command block (§4.2).
4. `RetSubmit` — the 28-byte `USBIP_RET_SUBMIT` command block (§4.2).
5. `CmdUnlink` — the 28-byte `USBIP_CMD_UNLINK` command block (§4.2).
6. `RetUnlink` — the 28-byte `USBIP_RET_UNLINK` command block (§4.2).

Each exposes the same shape as the existing codecs: a `public static final int
BYTES`, a `writeTo(ByteBuffer)`, and a `static readFrom(ByteBuffer)`, with public
final fields. Match the style of `OpHeader` / `UsbIpHeaderBasic` exactly.

**Plus** L1 tests: one parameterized round-trip test across the six new structs,
and one golden-byte-vector test asserting exact bytes for a fixed input.

**Out of scope** (do NOT gold-plate):

- **The transfer_buffer / setup payload.** The submit/ret command blocks are the
  fixed 28-byte structs *only*. `setup[8]` is part of the 28 bytes and is
  encoded; the variable `transfer_buffer[]` that follows an OUT `CMD_SUBMIT` or
  an IN `RET_SUBMIT` is **not** handled here — payload framing belongs to the
  engine (M4/M5).
- **Devlist/import message assembly.** The `ndev` count, the per-device interface
  list that follows a `usbip_usb_device` in `OP_REP_DEVLIST`, and gluing an
  `OpHeader` + device into a full IMPORT reply are **M3/M4**. M1 ships only the
  struct codecs; how they are strung into a negotiation message is later.
- The 20-byte basic header (`UsbIpHeaderBasic`) — already done; the four
  transfer-phase blocks in this PRD are the *command-specific* 28-byte blocks
  that follow it, not full 48-byte messages.
- No new constants unless a struct genuinely needs one (e.g. the non-ISO
  `number_of_packets` sentinel `0xFFFFFFFF` — add to `UsbIp` if referenced).

## Approach

Six small immutable value classes in the existing `com.iotower.core.protocol`
package, each a codec over a `ByteBuffer` the caller supplies in BIG_ENDIAN order
(the caller allocates/positions the buffer, exactly as `HeaderCodecTest` does).
Signed vs unsigned: read unsigned fields back masked (`& 0xFF`, `& 0xFFFF`) like
the existing code; `u32` fields are carried as `int` and written/read verbatim
(two's-complement round-trips bit-for-bit, so `0xFFFFFFFF` and negative `-errno`
status both survive). Fixed char arrays are written as ASCII bytes, NUL-padded to
the field width, and truncated if longer; on read, trailing NULs are trimmed back
to a `String`.

**Invariants preserved:** device-agnostic (these are pure framing — no VID/PID
branch, no device table); `core` stays Android-free (plain `java.nio` only);
big-endian wire (every `putInt`/`putShort` via a BIG_ENDIAN buffer, matching §4).

### Exact layouts (verify each against §4 / usbip_protocol.rst before coding)

**`UsbIpUsbDevice` — 312 bytes** (`char[]` fields are ASCII, NUL-padded):

| offset | field | type | notes |
|---|---|---|---|
| 0 | path | char[256] | any stable string, e.g. `/sys/devices/tv/1-1` |
| 256 | busid | char[32] | e.g. `1-1` |
| 288 | busnum | u32 | |
| 292 | devnum | u32 | |
| 296 | speed | u32 | USB_SPEED_* |
| 300 | idVendor | u16 | |
| 302 | idProduct | u16 | |
| 304 | bcdDevice | u16 | |
| 306 | bDeviceClass | u8 | |
| 307 | bDeviceSubClass | u8 | |
| 308 | bDeviceProtocol | u8 | |
| 309 | bConfigurationValue | u8 | |
| 310 | bNumConfigurations | u8 | |
| 311 | bNumInterfaces | u8 | |

Total = 256 + 32 + 4·3 + 2·3 + 1·6 = **312**. Field order is **path then busid**
(matches both §4.1 and the kernel struct).

**`UsbIpUsbInterface` — 4 bytes:** `bInterfaceClass` u8, `bInterfaceSubClass` u8,
`bInterfaceProtocol` u8, `padding` u8 (write 0; preserve/ignore on read).

**`CmdSubmit` — 28 bytes** (the block after the 20-byte basic header):

| offset | field | type |
|---|---|---|
| 0 | transfer_flags | u32 |
| 4 | transfer_buffer_length | s32 |
| 8 | start_frame | s32 (ISO only) |
| 12 | number_of_packets | s32 (`0xFFFFFFFF` when not ISO) |
| 16 | interval | s32 |
| 20 | setup | u8[8] (control transfers; zero-filled otherwise) |

**`RetSubmit` — 28 bytes:**

| offset | field | type |
|---|---|---|
| 0 | status | s32 (0 ok, else `-errno`) |
| 4 | actual_length | s32 |
| 8 | start_frame | s32 |
| 12 | number_of_packets | s32 |
| 16 | error_count | s32 |
| 20 | padding | u8[8] (write 0) |

**`CmdUnlink` — 28 bytes:** `unlinkSeqnum` u32 at offset 0 (this is the **seqnum
of the SUBMIT being cancelled** — distinct from the *own* seqnum in the enclosing
`UsbIpHeaderBasic`; name it `unlinkSeqnum` to make that unambiguous), then 24
bytes padding to fill the 28-byte command block.

**`RetUnlink` — 28 bytes:** `status` s32 at offset 0, then 24 bytes padding.

The four transfer blocks are all 28 bytes because the kernel `usbip_header`
overlays them in a union sized by the largest member (`cmd_submit`); `ret_submit`
carries 20 bytes of data + 8 pad, `cmd_unlink`/`ret_unlink` 4 bytes + 24 pad.

## Files to touch

Add (all under `core/src/main/java/com/iotower/core/protocol/`):

- `UsbIpUsbDevice.java`
- `UsbIpUsbInterface.java`
- `CmdSubmit.java`
- `RetSubmit.java`
- `CmdUnlink.java`
- `RetUnlink.java`

Possibly edit:

- `UsbIp.java` — only if a shared constant is referenced (e.g.
  `NUMBER_OF_PACKETS_NON_ISO = 0xFFFFFFFF`). Optional; keep the surface minimal.

Add tests (under `core/src/test/java/com/iotower/core/protocol/`):

- `StructCodecTest.java` — parameterized round-trip across the six new structs.
- `GoldenVectorTest.java` — exact-byte assertions for fixed inputs.

Do **not** modify `OpHeader`, `UsbIpHeaderBasic`, or `HeaderCodecTest`.

## Test plan

- **Layer(s):** L1 (`:core:test`).

- **New cases (only new ones):**

  1. **`StructCodecTest.roundTrip` (parameterized over all six new structs).**
     Purpose: prove `writeTo` then `readFrom` reconstructs every field, and that
     exactly `BYTES` bytes are consumed/produced, for each struct. Covered path:
     the encode+decode of each new struct — **none** is exercised by
     `HeaderCodecTest` (which only covers `OpHeader` and `UsbIpHeaderBasic`).
     Assertions per case: buffer position after `writeTo` equals `BYTES`; every
     field on the decoded instance `assertEquals` its input, including
     string fields (`path`, `busid` round-trip as the original un-padded
     `String`), `setup[8]` (array equality), the `0xFFFFFFFF` non-ISO
     `number_of_packets`, and a **negative** `RetSubmit.status` (e.g. `-32`, an
     `-errno`) to prove signed u32 survives. Use one representative value per
     field; this is a single test method driven by a parameter source, not one
     class per struct (per CLAUDE.md "minimize test count").

  2. **`GoldenVectorTest.exactBytes`.** Purpose: assert framing matches the
     **documented** byte layout for a fixed input, catching offset/endianness/
     size regressions that a self-consistent round-trip cannot. Covered path: the
     absolute byte offsets and big-endian ordering of the encoders — a path
     round-trip does not check (a mirrored bug in read+write passes round-trip
     but fails here). The expected bytes below are derived **by hand from the
     offsets in §4**, independently of what the code emits (see Risks). Assert on
     these fixed inputs:

     **`UsbIpUsbDevice`** with `path="/sys/devices/tv/1-1"`, `busid="1-1"`,
     `busnum=1`, `devnum=2`, `speed=3`, `idVendor=0x046d`, `idProduct=0xc294`,
     `bcdDevice=0x0100`, `bDeviceClass=0`, `bDeviceSubClass=0`,
     `bDeviceProtocol=0`, `bConfigurationValue=1`, `bNumConfigurations=1`,
     `bNumInterfaces=1`. Assert: encoded length == 312; bytes 0..18 equal the
     ASCII of `/sys/devices/tv/1-1` and byte 19 == 0 (NUL pad begins); bytes
     256..258 == ASCII `1-1` and byte 259 == 0; and the numeric tail at its
     offsets:
     `288: 00 00 00 01`, `292: 00 00 00 02`, `296: 00 00 00 03`,
     `300: 04 6d`, `302: c2 94`, `304: 01 00`,
     `306: 00`, `307: 00`, `308: 00`, `309: 01`, `310: 01`, `311: 01`.

     **`CmdSubmit`** control-IN GET_DESCRIPTOR(device): `transfer_flags=0`,
     `transfer_buffer_length=18`, `start_frame=0`,
     `number_of_packets=0xFFFFFFFF`, `interval=0`,
     `setup = {0x80,0x06,0x00,0x01,0x00,0x00,0x12,0x00}`. Expected 28 bytes:
     `00 00 00 00 | 00 00 00 12 | 00 00 00 00 | ff ff ff ff | 00 00 00 00 | 80 06 00 01 00 00 12 00`.

     **`RetSubmit`** success, 18 bytes returned: `status=0`, `actual_length=18`,
     rest 0. Expected 28 bytes:
     `00 00 00 00 | 00 00 00 12 | 00 00 00 00 | 00 00 00 00 | 00 00 00 00 | 00 00 00 00 00 00 00 00`.

     **`CmdUnlink`** cancelling SUBMIT seqnum 42: `unlinkSeqnum=42`. Expected 28
     bytes: `00 00 00 2a` then 24 × `00`.

     **`RetUnlink`** status 0: `00 00 00 00` then 24 × `00`.

     `UsbIpUsbInterface` is covered by the round-trip test only (its 4-byte layout
     is trivial and adding a golden vector would overlap; skip it here).

- **Fixtures / testdata:** none as files. Golden vectors are **inlined as byte
  literals** in `GoldenVectorTest`, because they are short, hand-derived from the
  documented spec, and keeping the expected hex next to the asserting code makes
  the derivation auditable in review. Do **not** add files under `testdata/` for
  M1 — the `testdata/README.md` §2 live-capture procedure is a later
  cross-check (see Risks), not a prerequisite here.

## Pass gate

Two runs, both green:

1. **In-session (no Gradle).** Gradle cannot be used in the automated VM — the
   distribution and Maven Central are blocked (403 through the egress proxy);
   only github.com is reachable. So the implementer compiles and runs the core
   tests with `javac` + the JUnit 5 **`junit-platform-console-standalone`** jar
   (platform **1.10.2**, matching `junit-bom 5.10.2` in `core/build.gradle`),
   fetched from a github.com-reachable source. Compile `core` main + test sources
   with `javac --release 11` (core targets Java 11), then run
   `java -jar junit-platform-console-standalone-1.10.2.jar` against the compiled
   test classpath. **Gate:** the new `StructCodecTest` and `GoldenVectorTest`,
   and the existing `HeaderCodecTest`, all report pass; zero failures.

2. **Canonical (user's machine).** `./gradlew :core:test` is the authoritative
   gate the **user** re-runs on their real Fedora box (JDK 17). It must be green
   including the golden vectors. State in the completion note that (1) was run
   in-session and (2) is the user's to confirm.

## Risks / open questions

- **Golden vectors are hand-derived, not captured.** MILESTONES.md M1 says to
  capture a real exchange from the reference `usbipd` and assert byte-for-byte.
  The automated session has **no root and no bound USB device**, so a live
  capture (testdata/README.md §2) is impossible here. These vectors are instead
  reasoned field-by-field from the documented §4 / `usbip_protocol.rst` offsets.
  **Deviation to flag to the human, not to paper over.** Mitigation baked into
  the test: the expected bytes are written out independently of the code's
  output (spelled hex above) so a bug in the encoder cannot silently define its
  own "golden" value — do **not** author the expected bytes by printing what the
  codec produces. **Open question for the human:** these should later be
  cross-checked against a real `devlist.bin` / `submit-control.bin` capture per
  testdata/README.md, on their Fedora box; until then there is residual risk that
  the documented layout and our reading of it share a blind spot (e.g. a struct
  pad the doc omits).
- **`speed` / `USB_SPEED_*` numeric values** are not needed for M1 (codec is
  value-agnostic; the golden vector just uses `3`). If the implementer wants a
  named constant, that is fine but optional — do not invent a device speed table.
- **`cmd_unlink` field semantics.** Confirm the 28-byte block's first u32 is the
  *victim* SUBMIT seqnum, not a copy of the basic-header seqnum. Named
  `unlinkSeqnum` deliberately. If the kernel source disagrees with this reading,
  **stop and flag** rather than guessing.
- **String field policy.** `path`/`busid` are fixed char arrays; decide and
  document (in Javadoc) that over-long strings are truncated to the field width
  and reads trim trailing NUL. If a real busid ever needs the full 32 bytes with
  no NUL terminator, revisit — but that is out of M1 scope.
