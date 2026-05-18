# Testing plan

Four surfaces need coverage at different fidelities. See `docs/initial-spec.md` §9 for the architectural split.

The fixture app at `tests/fixture/` (rf2-cxik) backs the shim and e2e
surfaces — a minimal Reagent + shadow-cljs build that preloads
`re-frame2-pair.runtime` and renders a counter. See `tests/fixture/README.md`
for run instructions.

## 1. Runtime unit tests (`tests/runtime/`)

**Status: partial — pure helpers covered.**

`tests/runtime/*.clj` cover the pure fns in
`preload/re_frame2_pair/runtime.cljs` via bb-mirrored copies of the
parser logic:

- `parse_rf2_coord_test.clj` — `parse-rf2-coord`
- `app_db_reset_test.clj` — `app-db-reset!` failure-mode envelopes
- `multi_frame_test.clj` — operating-frame resolution
- `preload_sentinel_test.clj` — session-sentinel shape
- `snapshot_test.clj` — coarse-grained `snapshot-state` composer
- `watch_op_modes_test.clj` — watch-op predicate dispatch

The shadow-cljs `node-test` harness (planned) will exercise the `.cljs`
source in place once wired up. Until then, the bb mirrors are the
canonical drift-detector.

**To run (once set up):**

```bash
shadow-cljs compile test
node out/test.js
```

Failing points to flag on first run:

- ~~`parse-rf2-coord` assumes a `'<ns>|<file>:<line>:<col>'` shape for `data-rf2-source-coord`.~~ Resolved 2026-05-09 (rf2-7g2q): parser updated to the canonical `<ns>:<handler-id>:<line>:<col>` shape per Spec 006 §Source-coord annotation. Bb-runnable tests at `tests/runtime/parse_rf2_coord_test.clj` until the shadow-cljs harness lands.
- `parse-rc-src` assumes a `"file:line"` or `"file:line:column"` format for `data-rc-src`. Real re-com attribute format needs verification.

## 2. Bash-shim integration (`tests/shim/`)

**Status: scaffolded — 7 tests, 28 assertions, runs in changed-surface PR CI.**

Per-push integration suite against a **stubbed nREPL**, not the live
fixture. `tests/shim/stub_nrepl.clj` is a babashka program that accepts
bencode, writes a port file at `target/shadow-cljs/nrepl.port` (the
canonical location re-frame2-pair's `ops.clj` probes), and returns canned
responses from a small needle → CLJS-value table.

Test driver `tests/shim/shim_test.clj` invokes `bb ops.clj <subcmd>`
directly (the `.sh` wrappers are 3-line `exec` shims, so running
`ops.clj` exercises the same contract on Windows + POSIX without bash).

Covers: `discover` (healthy + `:runtime-not-preloaded`), `eval`,
`dispatch --sync`, `trace-recent`, `tail-build` (no-probe soft path),
unknown-subcommand refusal.

Run:

```bash
bb tests/shim/shim_test.clj
```

The live-fixture variant (probe-flips against a real shadow-cljs
build) lives in `tests/e2e/` — see §3.

## 3. End-to-end in-browser (`tests/e2e/`)

**Status: scaffolded — 3 specs, soft-skip when fixture is down.**

Drives a headless Chromium via [playwright](https://playwright.dev/)
against the live fixture app (`tests/fixture/`). The runner
(`tests/e2e/run.cjs`) auto-detects fixture availability via an HTTP
probe and soft-skips with exit 0 when nothing's running — so the suite
is safe to run manually without false failures.

Specs (each a `*.e2e.cjs` exporting `async function run(ctx)`):

- **`connect-discover.e2e.cjs`** — `discover-app` returns `:ok? true`
  against a live build; verifies preload landed and the lone frame is
  `:rf/default`.
- **`dispatch-trace.e2e.cjs`** — `dispatch '[:counter/inc]' --sync`
  bumps the on-screen value and surfaces the epoch via `trace-recent`.
- **`hot-reload-probe.e2e.cjs`** — touch-edit `core.cljs`, confirm
  `tail-build --probe '(registrar-handler-ref :event :counter/inc)'`
  reports `:ok? true :soft? false`. This is the safety-critical
  probe-based reload contract from §4.5.

Run:

```bash
# 1. boot the fixture
cd tests/fixture && npx shadow-cljs watch app

# 2. run e2e (in a sibling terminal)
cd skills/re-frame2-pair
PAIR2_FIXTURE_URL=http://localhost:8030 node tests/e2e/run.cjs
```

Future specs to add: `dom/source-at` against the annotated DOM,
`restore-epoch` against the six documented failure modes, full
page-refresh + re-injection, multi-frame routing. See
`tests/e2e/README.md` for the scaffolded extension points.

## 4. Skill-prompt regression (`tests/prompts/`)

**Status: scaffolded — 8 tests, 27 assertions, runs in changed-surface PR CI.**

Table-driven structural regression against `references/recipes.md`,
`references/ops.md` (which now also carries the hot-reload-coordination
section and the v1 surface-map appendix), `references/errors.md`, and
`SKILL.md`. The canonical-
prompts table at the top of `tests/prompts/prompt_regression_test.clj`
binds each representative user prompt to the recipe heading that
covers it AND the ops the recipe is expected to name.

The 5 canonical prompts wired so far:

1. "What's in `app-db` under `:user/profile`?" → recipe still names
   an `app-db/snapshot` / `app-db/get` style read.
2. "Trace `[:cart/apply-coupon "SPRING25"]`" → recipe still names
   `dispatch-and-collect`, the `:rf/epoch-record` shape, and the
   `:sub-runs` / `:renders` projections.
3. "Why didn't the header update after `[:profile/save ...]`?" →
   recipe still walks `:sub-runs` and names the equality / cache-hit
   gate.
4. "Iterate on the cart handler until expired coupons are rejected" →
   recipe still names `dispatch-and-collect`, `restore-epoch`, and
   `reg-event-*`.
5. "Where in the code does this button come from?" → recipe still
   names `dom/source-at` and `data-rf2-source-coord`.

Each row matches via `clojure.string/includes?` on the section of
recipes.md under the expected heading; an alternation list lets us
catch drift when an op is renamed without bricking the test on a
single phrasing.

Run:

```bash
bb tests/prompts/prompt_regression_test.clj
```

**This is v1.** A future bead drives actual Claude conversations
through the same canonical-prompts table and asserts on the resulting
tool-invocation sequence. The structural substrate here catches the
cheapest class of drift first (recipe renamed, op disappeared, leaf
file moved); the conversation-driving variant layers on top without
re-deriving the prompts.

## CI gating

| Surface | Runs on |
|---|---|
| Runtime structural tests | PR CI when `skills/re-frame2-pair/**` changes; nightly/manual expensive workflow may also run them before release. |
| Bash-shim integration | PR CI when `skills/re-frame2-pair/**` changes. |
| End-to-end in-browser | Manual/nightly diagnostic; not required PR coverage because it depends on a live fixture. |
| Prompt regression | PR CI when `skills/re-frame2-pair/**` changes. |

Release should not be cut from an unverified re-frame2-pair surface: structural
tests must pass, and live fixture/E2E diagnostics should be green for a
release candidate even though they are not required PR checks.

### Known coverage gap — probe-based reload

`hot-reload/wait`'s probe-based confirmation (§4.5) is *safety-critical* — Claude uses it to gate dispatches after a source edit, and a false positive means Claude interacts with stale code. Yet the only way to genuinely exercise it requires a real browser + real shadow-cljs + real edit + real compile pipeline — i.e. the E2E surface, which is manual/nightly diagnostic, not required PR coverage.

Mitigation while E2E remains manual/nightly:

- **Unit-test the probe-selection heuristics** (which probe to pick for a `reg-*` edit vs a view edit vs no-good-probe-available). Cheap; catches drift in the selection logic without needing a browser.
- **Soft-confirmation signalling**: when no probe is available, `hot-reload/wait` returns `:soft? true`; SKILL.md asks Claude to surface this to the user rather than trust it as a hard landing confirmation.
- **Never force release on a broken probe path.**

## What's explicitly **not** tested yet

- Multi-frame routing under real concurrency (the e2e fixture is
  single-frame on purpose; a multi-frame fixture variant is the next
  step).
- `restore-epoch` failure-mode traces against a live runtime (only
  shape-tested via `tests/runtime/`).
- Hot-reload probe-form *selection heuristics* (the probe contract
  itself is exercised by `tests/e2e/hot-reload-probe.e2e.cjs`).
- Claude-in-the-loop prompt regression (the structural drift detector
  is in `tests/prompts/`; conversation-driving variant is a follow-up).

These remain §8a spike deliverables for the path to beta.
