# Testing plan

Three surfaces need coverage at different fidelities.

> **Scope (current surface).** The **MCP server** (`tools/re-frame2-pair-mcp/`) is the only skill-facing transport — and the one implementation of every operation. It carries its own test suite under that directory (`shadow-cljs compile server-test`), including the live end-to-end fixture gate (`test/live-e2e-fixture.cjs`). The surfaces below cover the skill's **runtime preload** + **structural docs** that live in this tree. (The retired bash/babashka transport — `scripts/ops.clj`, its shell wrappers, and the `tests/shim/` + `tests/e2e/` suites that drove it — has been removed; the connect/dispatch/trace/hot-reload coverage now drives the MCP server over stdio from `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`.)

The fixture app at `tests/fixture/` backs the structural pure-core surface
and the pair-mcp live-e2e gate — a minimal Reagent + shadow-cljs build that
preloads `re-frame2-pair.runtime` and renders a counter. See
`tests/fixture/README.md` for run instructions.

## 1. Runtime unit tests — two halves

The runtime preload's testable logic is split so that BEHAVIOUR is tested
against the **exact shipped code** rather than a copied mirror (rf2-etsj8p):

- The genuinely-pure decision logic lives in a shipped preload namespace,
  `preload/re_frame2_pair/pure.cljc` (`re-frame2-pair.pure`) — the cascade /
  consequence projections, the multi-frame operating-frame resolver, the
  id-validation core, the streaming queue transforms (routing / eviction /
  drain), the epoch timing + matcher, the snapshot-scope resolver, and the
  orient assembler. `re-frame2-pair.runtime` `:require`s it and delegates,
  threading the live session gates (raw-state posture, privacy posture,
  operating frame, `frame-ids`) in as arguments.

### 1a. Pure-core CLJS node-test (`tests/fixture/`, build `:pure-test`)

**Status: the behaviour gate.** A shadow-cljs `:node-test` build in the Pair
fixture compiles `re-frame2-pair.pure` (on the `../../preload` source path) and
the tests at `tests/fixture/test/re_frame2_pair/pure_test.cljs`, running them
under Node. These exercise the EXACT pure fns the runtime delegates to — no
mirror — so the covered matrices (multi-frame ambiguity, redaction, streaming
eviction/timing, read-sub validation, snapshot/orient, cascade outcome, and the
hash-cache bedrock invariant) test the shipped preload directly.

**To run:**

```bash
cd tests/fixture
npm install          # one-time (shadow-cljs)
npm run test:pure    # shadow-cljs compile pure-test && node target/pure-test.js
```

> **CI note (rf2-etsj8p):** this build now has its own required job,
> `re-frame2-pair-fixture-pure`, gated on the same `skills_structural`
> changed-surface flag as the Babashka loop. The earlier note here — that
> wiring it up still needed a hot-zone edit — is superseded.

The `:pure-test` build is where `event-byte-size`'s **UTF-8 byte** discipline
is pinned (rf2-2rtt6.135): `event-byte-size-counts-utf8-bytes-not-code-units`
and `byte-budget-evicts-sooner-on-multi-byte-payload` use fixtures that share
one `pr-str` code-unit length across three different byte lengths, so the
`(count (pr-str ev))` that used to sit under the `max-buffered-bytes` gate
answers the same number for all three and reds only on the non-ASCII rows.
Note this file is `.cljc` and the `:clj` arm is genuinely loadable — but only
the `:cljs` arm has a CI lane, since the fixture's sole harness is the
node-test build.

### 1b. Structural (AST) pins (`tests/runtime/*.clj`, Babashka)

**Status: the wiring gate.** These parse `runtime.cljs` and pin its *wiring* —
not copied behaviour. They cover: the runtime→`pure` delegations and live-gate
injection (`pure_delegation_test.clj`); framework-boundary wiring the runtime
must keep (`app_db_reset_test.clj` → `rf/replace-frame-state!`,
`dom_readback_redaction_test.clj` → `rf/project-egress`,
`registrar_describe_test.clj` → `strip-fns` / `:handler-fn` hygiene,
`frame_registrar_test.clj`, `dispatch_consequence_test.clj`); and the load-time
sentinel (`preload_sentinel_test.clj`).

**To run:**

```bash
for f in tests/runtime/*_test.clj; do bb "$f"; done
```

The retired Babashka *behaviour mirrors* (app-db hash, cascade outcome/redaction,
multi-frame, read-sub, sub-cache, streaming, snapshot, orient,
source-coord/view parse) were DELETED: their behaviour is now tested against the
shipped `pure.cljc` in §1a, and the source-coord/view parsers are canonical
core aliases (`re-frame.source-coords/*`) covered by
`implementation/core/test/re_frame/source_coords_cljs_test.cljs`.

## 2. End-to-end live fixture (`tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`)

**Status: the ground-truth gate — soft-skips when the fixture is down.**

The live connect/dispatch/trace/hot-reload coverage lives with the one
implementation, the MCP server. `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`
spawns the compiled `out/server.js`, boots a headless Chromium via
[playwright](https://playwright.dev/) so the fixture's `re-frame2-pair.runtime`
preload runs, and drives the three flows through the real MCP boundary
against the live fixture app (`tests/fixture/`). It auto-detects fixture
availability (server bundle + HTTP probe + nREPL port file + Playwright)
and soft-skips with exit 0 when anything's missing — safe to run anywhere
without false failures.

The flows (each an MCP `tools/call` against the booted runtime):

- **connect** — `discover-app` returns `:ok? true` / `:debug-enabled? true`
  against a live build; verifies the preload landed and the lone frame is
  `:rf/default`.
- **dispatch + trace** — `dispatch {event "[:counter/inc]" sync true}`
  bumps the on-screen `#value` to `6` and surfaces the epoch via
  `trace-window`.
- **hot-reload** — touch-edit `core.cljs`, confirm
  `tail-build {probe "(re-frame2-pair.runtime/registrar-handler-ref :event :counter/inc)" wait-ms …}`
  reports `:soft? false` once the probe flips. This is the safety-critical
  probe-based reload contract from §4.5.

Run:

```bash
# 1. boot the fixture
cd skills/re-frame2-pair/tests/fixture && npx shadow-cljs watch app

# 2. build the server + run the gate (sibling terminal)
cd tools/re-frame2-pair-mcp && npm run build
RE_FRAME2_PAIR_FIXTURE_URL=http://localhost:8030 npm run test:live-e2e-fixture
```

The mcp-conformance hermetic live suite
(`tools/mcp-conformance/scripts/run-re-frame2-pair-live-hermetic-suite.cjs`)
runs the same class of coverage in CI by booting the fixture itself and
driving the pair-mcp server over stdio.

## 3. Skill-prompt regression (`tests/prompts/`)

**Status: scaffolded — see `tests/prompts/prompt_regression_test.clj` for the current deftests; runs in changed-surface PR CI.**

Table-driven structural regression against `references/recipes.md`,
`references/ops.md` (which now also carries the hot-reload-coordination
section and the v1 surface-map appendix), `references/errors.md`, and
`SKILL.md`. The canonical-
prompts table at the top of `tests/prompts/prompt_regression_test.clj`
binds each representative user prompt to the recipe heading that
covers it AND the ops the recipe is expected to name.

The 5 canonical prompts wired so far:

1. "What's in `app-db` under `:user/profile`?" → recipe still names
   a `snapshot` / `get-path` style read (the `:must-mention`
   alternation also tolerates the legacy `app-db/snapshot` /
   `app-db/get` vocabulary names).
2. "Trace `[:cart/apply-coupon "SPRING25"]`" → recipe still names
   `dispatch-and-collect`, the `:rf/epoch-record` shape, and the
   `:sub-runs` / `:renders` projections.
3. "Why didn't the header update after `[:profile/save ...]`?" →
   recipe still walks `:sub-runs` and names the equality / cache-hit
   gate.
4. "Iterate on the cart handler until expired coupons are rejected" →
   recipe still names `dispatch-and-collect`, `restore-epoch`, and
   `reg-event` (the one public event registrar).
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
| MCP server suite (`tools/re-frame2-pair-mcp/`) | The skill-facing transport's own tests — `shadow-cljs compile server-test` + the descriptor-manifest drift gate. Run under the tool's directory, not this skill's `tests/`. |
| Runtime structural tests | PR CI when `skills/re-frame2-pair/**` changes; nightly/manual expensive workflow may also run them before release. |
| End-to-end live fixture (`tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`) | Runnable on demand (soft-skips without a live fixture). The mcp-conformance hermetic live suite runs the equivalent stdio coverage in CI by booting the fixture itself. |
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
  itself is exercised by the hot-reload flow in
  `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`).
- Claude-in-the-loop prompt regression (the structural drift detector
  is in `tests/prompts/`; conversation-driving variant is a follow-up).

These remain §8a spike deliverables for the path to beta.
