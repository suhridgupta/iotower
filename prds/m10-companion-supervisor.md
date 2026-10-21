# PRD: M10 — PC companion supervisor (auto-attach, auto-reattach, clean teardown)

> Written by the design (Opus) pass; implemented by the build (Sonnet) pass.
> Spec only — no code here. Reference architecture sections as "§N".

## Milestone / context

MILESTONES.md **M10 — Optional PC companion (§9)**, the *supervisor* half only.
Architecture §9.1 (supervisor duties) and §7 (device reset / re-enumeration).

M10 as written has two independent halves: (a) the supervisor around the stock
`usbip` CLI, and (b) the optional authenticating PIN/TLS proxy (§9.2) plus the
server-side "require a PIN handshake at accept time" change. **This PRD covers
(a) only.** The PIN/TLS proxy and the Android-side gate are explicitly deferred
to a later pass, so M10 is **not** fully MET after this — only its supervisor
and teardown duties.

## Scope

Turn `companion/iotowerd.py` from a skeleton (preflight / list / single attach
wired; `supervise`, `detach_all` TODO) into a working hands-off supervisor.

In scope — observable outcomes:

- **Preflight** (§9.1): `usbip` present and `vhci-hcd` loadable; on failure,
  print exactly what to install and exit non-zero. (Refine the existing one.)
- **Discover + auto-attach** (§9.1): poll `usbip list -r <host>` until the
  exported busid appears, then `usbip attach`. Do not require the device to be
  present at start — wait for it, with backoff.
- **Auto-reattach on reset** (§9.1, §7): after attaching, watch the local
  attachment (`usbip port`); when it drops (unplug, or a G29 mode-switch
  re-enumeration), return to the discover+attach loop automatically — no manual
  step at the couch.
- **Stock-client wedge recovery:** the README documents that `vhci-hcd` often
  wedges after an abrupt disconnect (`usbip attach`/`usbip port` fail with
  `open vhci_driver`). Automating the `modprobe -r vhci-hcd; modprobe vhci-hcd`
  reset-and-retry is named in the README as the companion's job — do it, bounded,
  before re-attaching.
- **Clean teardown** (§9.1): on exit (Ctrl-C **and** SIGTERM, so systemd
  `stop`/`Restart` is clean) detach the port(s) this companion attached, so no
  dead vhci port is leaked.
- **Pretty console UI:** structured, timestamped, state-labelled status lines
  (DISCOVER / ATTACH / ATTACHED / RESET / DETACH / etc.) with ANSI color when
  stdout is a TTY, plain when piped or under systemd, and a `--no-color` flag.
  This is the "finalize + make it prettier" ask, applied to the companion's
  terminal output. Keep `--help` accurate and self-explanatory.

Out of scope (do not build; do not stub beyond a one-line note):

- The PIN/TLS authenticating proxy (§9.2) and any Android-server PIN handshake.
- Multi-device (v2). One exported device, busid default `1-1`.
- Any USB/IP protocol code. This stays **orchestration only** — it shells out to
  the stock `usbip` and parses its text output; it never speaks the wire.
- Any third-party Python package. **Standard library only** (keeps the systemd
  deploy a single-file copy, matches the project's no-deps spirit).

## Approach

Single file `companion/iotowerd.py`, standard library only. Structure as small
pure functions (parsing, decision) plus thin `subprocess` wrappers, so the risky
logic is unit-testable without a TV or root.

Key pieces:

- **CLI:** keep `--host` (required), `--busid` (default `1-1`),
  `--interval` (poll seconds, default 2.0); add `--no-color` and
  `--attach-retries`/backoff knobs only if trivial (optional). Keep behavior
  backward compatible with the existing unit file's `--host/--busid` args.
- **Pure parsers (the unit-tested core):**
  - `parse_exported_busids(list_stdout) -> list[str]`: busids from
    `usbip list -r` output (lines like ` 1-1: Vendor : product (046d:c24f)`).
  - `device_is_exported(list_stdout, busid) -> bool`: convenience over the above.
  - `parse_attached_ports(port_stdout, host=None) -> list[PortRow]`: parse
    `usbip port`, each imported port's `Port NN:` number and its
    `usbip://<host>:3240/<busid>` remote URL; filter to `host` when given. Used
    both to confirm our attach landed and to detach cleanly on exit.
- **subprocess wrappers:** `list_remote`, `attach`, `detach(port)`,
  `port_table`, `modprobe_reset` (the `-r` then load). Each returns rc/stdout;
  none raise on non-zero (the loop decides).
- **State machine (`supervise`)**: DISCOVER → (device exported?) → ATTACH →
  (confirm via `usbip port`) → ATTACHED → watch loop: each `interval`, read
  `usbip port`; if our port for `host` is gone → log RESET and go back to
  DISCOVER. On an `open vhci_driver` failure anywhere, log WEDGE, run
  `modprobe_reset` (bounded retries), continue. Backoff on repeated attach
  failure so we don't hammer.
- **Teardown:** a `Supervisor` object remembers the port(s) it attached;
  `install_signal_handlers` (SIGINT, SIGTERM) flips a stop flag and the loop
  exits to a `finally:` that calls `detach_all()` → `usbip detach -p` for each
  remembered port confirmed present in `usbip port`.
- **Output:** a tiny `Ui` helper: `ui.state("ATTACHED", msg)`,
  `ui.info/warn/error/ok`. Color via a small ANSI map, enabled only when
  `sys.stdout.isatty()` and not `--no-color` and `$NO_COLOR` unset. Timestamps
  `HH:MM:SS`. No external libs.

Invariants preserved: orchestration-only (no protocol), standard-library-only,
device-agnostic (busid/host text only, no VID/PID logic), core and Android
untouched.

## Files to touch

- `companion/iotowerd.py` — rewrite from skeleton to the above (single file).
- `companion/test_iotowerd.py` — **new**, stdlib `unittest`; parser tests.
- `companion/README.md` — update Run/Status: reattach + teardown now work, how
  to run the self-test, PIN/TLS still TODO.
- `companion/iotower.service` — note/verify clean `systemctl stop` (SIGTERM →
  teardown); keep `Restart=on-failure`.

Do **not** touch `core/`, `android/`, or the Gradle build. The companion is not
part of the Gradle build (CLAUDE.md module layout) and has no SDK/JDK need.

## Test plan

- **Layers:** L1-equivalent (pure-Python unit tests of the parsers — the only
  non-trivial logic; runs anywhere with `python3`, no root/TV) + L3 (real
  hands-off reattach on the user's TV+Fedora, which is the milestone gate and is
  the user's to run on hardware).
- **New cases (and only new ones)** in `companion/test_iotowerd.py`, each over a
  captured-real-output fixture string:
  - `parse_exported_busids`: a one-device `usbip list -r` dump → `["1-1"]`;
    an empty "Exportable USB devices" dump → `[]`. Covers the discover decision.
  - `device_is_exported`: true for `1-1` present, false when absent. Covers the
    DISCOVER→ATTACH guard.
  - `parse_attached_ports`: a `usbip port` dump with one imported port on our
    host → one row with the right port number + busid; host filter excludes a
    port on a different remote; an empty port table → `[]`. Covers both the
    attach-confirm and the clean-teardown port selection — the teardown-safety
    path (never detach a port we didn't create / a stale table) has no existing
    coverage.
  - Colorization: `Ui` with color disabled emits no ANSI escapes (so systemd
    journals stay clean). One assertion; covers the pipe/`--no-color` path.
  - No test duplicates another: parsers are distinct; the Ui test is the only
    output-path test.
- **Fixtures:** inline string constants captured from real `usbip list -r` /
  `usbip port` output (document the source in a comment). No new `testdata/`
  binary needed.
- Run: `python3 -m unittest discover -s companion` (or
  `python3 companion/test_iotowerd.py`). No network, no root.

## Pass gate

- **This pass (supervisor):** `python3 -m unittest` over
  `companion/test_iotowerd.py` is green; `python3 companion/iotowerd.py --help`
  prints the documented options; preflight exits clearly when `usbip` is absent.
  The reattach/teardown logic is exercised by the parser unit tests.
- **Milestone L3 (user, on hardware):** start `iotowerd.py --host <tv-ip>`;
  unplug/replug the device (or trigger a G29 PC↔native mode switch) → the
  companion re-attaches with no manual step; `Ctrl-C`/`systemctl stop` detaches
  the port and leaves no stale `usbip port` entry. (The PIN-gate half of the
  written M10 gate is **deferred** — see Scope.)

## Risks / open questions

- `usbip port` / `usbip list` output format varies a little by `usbip` version.
  Parsers must be line-oriented and tolerant (match the `usbip://host:port/busid`
  URL and `Port NN:` markers; ignore speed/vendor cosmetic lines). If the host's
  `usbip` prints a materially different port table than the fixtures, **stop and
  flag** rather than guessing a format.
- `modprobe -r vhci-hcd` can report "in use" and fail; the README says a reboot
  may be needed. The companion should log that clearly and keep going (bounded
  retry), not loop forever — do **not** attempt a reboot.
- Detach-on-exit must only detach ports whose remote URL matches our `--host`,
  never a port the user attached by hand to another server. If host-matching is
  ambiguous (no host in the URL on some versions), prefer to detach only ports
  this process attached (tracked by port number from our own attach), and flag.


---

## Revision — clean-detach-on-drop (2b) + compact status line (2026-10-20)

Two additions after the first supervisor pass, driven by the TV-side **Eject**
work (see `prds/tv-app-ui-finalize.md`):

- **Clean detach on drop (M10 "2b").** When the attached device drops — a reset,
  a re-enumeration, **or a TV-side Eject** — the watch loop now proactively runs
  `usbip detach` on our vhci port *before* re-discovering, instead of leaving the
  stock client to choke on the abrupt remote close (the wedge). The ports to
  detach are chosen by a new pure helper `detach_targets(our_rows, recorded_port)`
  = the union of the rows `usbip port` still shows for our host and the port we
  recorded at attach time (covers the case where the row is already gone but the
  vhci slot is half-open). On a reset the device re-appears and we re-attach; on
  an Eject it is no longer exported, so we cleanly stay detached in DISCOVER.
  This also makes the §7 reset path wedge-resistant, not just Eject.
- **Compact live status line (TTY only).** In addition to the scrolling log, a
  single rewriting bottom line shows `state  busid@host  up MM:SS`, redrawn
  around each log line and refreshed each poll. Suppressed when stdout is not a
  TTY (piped / under systemd), so the journal stays clean.

New test: `DetachTargetTests` covers `detach_targets` (live+recorded dedupe;
recorded-survives-empty-table; empty). Still standard-library only; still
orchestration-only (no protocol). PIN/TLS (§9.2) remains deferred.

---

## Revision — record-independent attach detection (2026-10-21)

L3 on the user's Fedora box exposed that `usbip port` here cannot read the
per-attach record (`libusbip: error: read_record`), so it prints
`unknown host, remote port and remote busid` instead of the
`usbip://host:port/busid` line the original parser keyed on — even under `sudo`.
Detection therefore never fired: the companion kept re-attaching a
working device and tripped the single-client TV server (`Unknown Op Common
Status`, multi-minute `usbip list` hangs).

Fix: stop depending on that record. Detection now uses a **port-in-use delta** —
snapshot the `Port NN: <Port in Use>` headers (which come from sysfs and need no
root) before `usbip attach`, and whichever vhci port is newly in use afterwards
is ours; `_attached()` is then "is that port still in use". `parse_attached_ports`
/ `detach_targets` / `PortRow` are replaced by `parse_ports_in_use`; teardown and
clean-detach act only on the port we created (safer — never touches ports we did
not). `port_table()` drops `sudo` (headers are readable without it, and it avoids
a password re-prompt in the watch poll). `_run` also gained timeouts so a hung
`usbip` (e.g. `list` refused while attached) can't block the loop. Still
device-agnostic (no host/busid/VID matching), stdlib-only. New `PortsInUseTests`
cover the root and the non-root "unknown host" outputs; parser suite green.
