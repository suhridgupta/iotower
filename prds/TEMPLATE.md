# PRD: <feature / milestone>

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context
Which MILESTONES.md item this is, and the relevant architecture sections.

## Scope
What this delivers. Bullet the observable outcome. State explicitly what is OUT
of scope so the implementer doesn't gold-plate.

## Approach
The intended design: key classes/methods, data flow, the seam(s) touched. Call
out any invariant that must be preserved (device-agnostic; core stays
Android-free; big-endian wire).

## Files to touch
Exact paths to add or change. Keep the surface minimal.

## Test plan
- **Layer(s):** L1 / L2 / L3.
- **New cases (and only new ones):** list each with its purpose and the exact
  assertion. For every case, state the code path it covers that no existing test
  already covers. If an existing test can be extended/parameterized instead of
  adding one, say so.
- **Fixtures / testdata** needed (e.g. a captured descriptor dump).

## Pass gate
The unambiguous, observable condition that means "done" (a command's output, a
green suite, a device in `lsusb`, events in `evtest`, etc.).

## Risks / open questions
Anything the implementer should stop and flag rather than guess.
