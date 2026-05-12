# Testing plan

Four surfaces need coverage at different fidelities. See `docs/initial-spec.md` §9 for the architectural split.

## 1. Runtime unit tests (`tests/runtime/`)

**Status: not yet written.**

`runtime_test.cljs` (planned) covers pure fns in `preload/re_frame_pair2/runtime.cljs` — `parse-rf2-coord`, `parse-rc-src`, `epoch-matches?`, `current-frame` resolution, `epochs-since` semantics, the session-sentinel shape, time-travel sugar. These can run via `shadow-cljs compile test` + `node out/test.js` without a browser or live re-frame2 app.

**To run (once set up):**

```bash
shadow-cljs compile test
node out/test.js
```

Failing points to flag on first run:

- ~~`parse-rf2-coord` assumes a `'<ns>|<file>:<line>:<col>'` shape for `data-rf2-source-coord`.~~ Resolved 2026-05-09 (rf2-7g2q): parser updated to the canonical `<ns>:<handler-id>:<line>:<col>` shape per Spec 006 §Source-coord annotation. Bb-runnable tests at `tests/runtime/parse_rf2_coord_test.clj` until the shadow-cljs harness lands.
- `parse-rc-src` assumes a `"file:line"` or `"file:line:column"` format for `data-rc-src`. Real re-com attribute format needs verification.

## 2. Bash-shim integration (`tests/shim/`)

**Status: not yet written.**

End-to-end against the fixture app. For each shell script in `scripts/*.sh`, assert:

- exit code matches the documented contract
- stdout is parseable as edn
- structured result has expected keys

Recommended approach: [`bats`](https://bats-core.readthedocs.io/) or a simple bash test harness. One `.bats` file per script; the fixture is started/stopped per test suite (not per test).

## 3. End-to-end in-browser (`tests/e2e/`)

**Status: not yet written. Blocked on the fixture app.**

Drives a headless Chrome via [playwright](https://playwright.dev/) against the fixture. Exercises:

- `watch-epochs.sh` against the assembled-epoch stream
- `tail-build.sh` probe-based confirmation after a live source edit
- `dom/source-at`, `dom/find-by-src`, `dom/fire-click-at-src` against an annotated DOM (`:annotate-dom? true`)
- `restore-epoch` against each of the six documented failure modes
- Full page refresh -> re-injection via session-sentinel miss
- Multi-frame routing: dispatch with `--frame :stories` lands in the right history

This is where uncertainty about `data-rf2-source-coord` format and the `cljs-eval` round-trip gets flushed out.

## 4. Skill-prompt regression (`tests/prompts/`)

**Status: not yet written.**

A fixture app plus a harness that feeds representative Claude conversations and asserts the set of `scripts/*` invocations (and optionally the shape of Claude's reply). This catches silent drift in the skill's description and recipes as Claude's behaviour changes.

Candidate prompts:

- "What's in `app-db` under `:user/profile`?" -> should call `app-db/get`
- "Trace `[:cart/apply-coupon "SPRING25"]`" -> should call `dispatch.sh --trace`
- "Why didn't the header update after `[:profile/save ...]`?" -> should walk `:sub-runs`, identify the equality gate
- "Iterate on the cart handler until expired coupons are rejected" -> should use the experiment-loop recipe (dispatch-and-collect, restore-epoch, reg-event-fx, repeat)

## CI gating

| Surface | Runs on |
|---|---|
| Runtime unit tests | every push |
| Bash-shim integration | every push (once fixture exists) |
| End-to-end in-browser | `main` + nightly |
| Prompt regression | `main` + nightly |

Release gates on all four passing.

### Known coverage gap — probe-based reload

`hot-reload/wait`'s probe-based confirmation (§4.5) is *safety-critical* — Claude uses it to gate dispatches after a source edit, and a false positive means Claude interacts with stale code. Yet the only way to genuinely exercise it requires a real browser + real shadow-cljs + real edit + real compile pipeline — i.e. the E2E surface, which runs nightly and on `main`, not per-push.

Mitigation until we can run E2E per-push:

- **Unit-test the probe-selection heuristics** (which probe to pick for a `reg-*` edit vs a view edit vs no-good-probe-available). Cheap; catches drift in the selection logic without needing a browser.
- **Soft-confirmation signalling**: when no probe is available, `hot-reload/wait` returns `:soft? true`; SKILL.md asks Claude to surface this to the user rather than trust it as a hard landing confirmation.
- **Never force release on a broken probe path.**

## What's explicitly **not** tested yet

- Connection against a real shadow-cljs build with nREPL enabled
- Multi-frame routing under real concurrency
- `restore-epoch` failure-mode traces
- Hot-reload probe-form selection heuristics

These are §8a spike deliverables.
