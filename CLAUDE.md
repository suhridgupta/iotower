# CLAUDE.md

Guidance for Claude working in this repo. Kept lean — it loads every session.

## Start here (required reading)

At the start of every session, read:

- **`README.md`** — what the project is and the quick start.
- **`architecture.md`** — the full design and the source of truth. Do not
  contradict it; if a change requires deviating, update the doc in the same change.

When doing feature / milestone work, also read **`MILESTONES.md`** (delivery plan
+ per-milestone test gates) and **`DEVELOPING.md`** (build/test commands).

## What this is

A **no-root USB/IP server on Android TV** that exposes a plugged-in USB device to
a remote Linux PC via the stock `usbip` client. Java. A pure-Java `core` plus a
single Android app; the PC side is stock tools only.

## Invariants — never violate (these *are* the project)

1. **Device-agnostic.** No VID/PID logic, no device tables, no per-device
   branches anywhere. Forward raw transfers/descriptors; all device semantics
   live in the PC's drivers. Never special-case the G29 — route by
   `(endpoint, direction)` from the parsed descriptors (§5, §7).
2. **`core` has zero Android imports.** Everything above the `UsbBackend` seam is
   plain Java, JVM-testable. Only `:android` touches the Host API. Never add an
   `android.*` import to `core`.
3. **No protocol code on the PC.** It uses the stock in-kernel client; do not
   write a custom client (§1).
4. **Speak stock USB/IP**, big-endian on the wire (`ByteBuffer` BIG_ENDIAN, §4).
5. **No third-party libraries** in the app; JUnit is the only test dependency (§13).
6. **Guard null-returning Host API calls** (`openDevice`, `getRawDescriptors`,
   endpoint lookups) — a long-running server must not NPE mid-session.

## Module layout

- `core/` — protocol, descriptor parsing, endpoint map, engine, socket server (pure Java).
- `android/` — the TV app: `AndroidUsbBackend`, `ServerService`, UI. Depends on `:core`.
- `desktop/` — local harness: the real server + `FakeUsbBackend` for L2 testing.
- `companion/` — optional Python PC daemon (§9); not in the Gradle build.
- `testdata/` — descriptor dumps + usbmon captures (ground truth).
- `prds/` — per-feature implementation specs (see workflow below).

## Feature workflow (two agents, for token efficiency)

For any non-trivial feature or milestone:

1. **Design — Opus subagent.** Writes a PRD to `prds/<feature>.md` from
   `prds/TEMPLATE.md`. It reads `architecture.md` and the relevant milestone, and
   specifies scope, files to touch, approach, the exact test plan (which layer,
   which cases), and the pass gate. **Spec only — it writes no implementation.**
2. **Implement — Sonnet subagent.** Implements strictly from the PRD. It does not
   redesign; if the PRD is wrong or underspecified, it **stops and flags** rather
   than improvising.
3. **Audit & update the docs — same commit.** After a feature is implemented,
   review the docs and bring them current as part of the same change: mark the
   milestone done in `MILESTONES.md`, and fix `README.md`, `architecture.md`,
   `DEVELOPING.md`, `testdata/README.md` (and this file) wherever the feature
   changed reality — status, build order, commands, wire/descriptor layouts,
   testdata. Never leave a doc contradicting the code.

Keep this split even for medium tasks — the committed PRD is the handoff and the
token-efficient contract, and it stays in the repo for review.

## Testing

- Ship every feature with tests at the right layer: **L1** `:core:test` (JVM
  logic), **L2** desktop harness + stock `usbip`, **L3** the TV (see MILESTONES.md).
- **Minimize test count, maximize coverage.** Add a new test only when it
  genuinely exercises a **new code path**. Do not add a test whose coverage
  overlaps an existing one — extend or parameterize the existing test instead.
  Prefer a few high-value tests (round-trip, golden-byte vectors, out-of-order
  completion) over many redundant ones.
- **Definition of done:** `./gradlew :core:test` green, and the milestone's L2/L3
  gate met where applicable. State which gate was checked and how. Docs audited
  and updated in the same commit (feature-workflow step 3).

## Conventions

- Java, package root `com.iotower`. `minSdk 28`, `compileSdk 35`. Build needs JDK 17.
- Reference architecture sections (e.g. "§5.1") in code comments and PRDs.
- Do not commit secrets or signing keys (see `.gitignore`).

## Version control

- **Commit when a feature is complete.** Once a PRD has been written and fully
  actioned — implementation plus tests, with the done gate met — make **one
  commit** containing all of that feature's changes together: the PRD, the code,
  and the tests. One completed feature = one commit.
- **Never push.** Do not `git push` or publish to the remote origin under any
  circumstances — the user does that. Commit locally only.
- **No session metadata in commits.** Never put a Claude / Cowork session URL
  or session identifier in a commit message or PR description — no
  `Claude-Session:` trailer and no `claude.ai/…` session link. They leak an
  internal identifier into shared history. A `Co-Authored-By:` attribution line
  is fine; a session link is not.
- Message: summarize the feature and reference the milestone, e.g.
  `M1: protocol codecs + golden-byte vectors`.
