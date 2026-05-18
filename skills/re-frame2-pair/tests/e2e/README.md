# Pair2 end-to-end tests

Browser-level checks that drive the **live** fixture app
(`tests/fixture/`) through real shadow-cljs + nREPL + Playwright.
These are the "ground-truth" tests called out in `STATUS.md` §Known
unknowns and `docs/TESTING.md` §3.

## What they prove

Three flows, each end-to-end against running infrastructure:

1. **`connect-discover.e2e.cjs`** — `discover-app` connects to the
   fixture's nREPL, finds the preloaded `re-frame2-pair.runtime`, and
   returns a sane health snapshot (`:ok? true`, `:frames [:rf/default]`,
   `:debug-enabled? true`).

2. **`dispatch-trace.e2e.cjs`** — re-frame2-pair dispatches `[:counter/inc]` via
   `scripts/dispatch.sh --sync`, then observes the resulting epoch via
   `scripts/trace-window.sh`. The on-screen counter value increments,
   the trace window carries the epoch, and `:event-id :counter/inc`
   appears in the epoch record.

3. **`hot-reload-probe.e2e.cjs`** — capture the
   `registrar-handler-ref :event :counter/inc` probe value before a
   touch-edit, trigger a shadow-cljs reload by replacing the file
   contents (then restoring), confirm the probe value flips, and confirm
   `scripts/tail-build.sh --probe ...` reports `:ok? true :soft? false`.

## Why they're not required PR coverage

Each spec needs:

- a fixture build via `shadow-cljs watch app`
- a live Chromium via Playwright
- enough wallclock for compile + reload (~30s warm boot, ~3s per reload)

Per `docs/TESTING.md` §CI gating these are manual/nightly diagnostics,
not required PR coverage.

## Run locally

```bash
# 1. boot the fixture (in one terminal)
cd skills/re-frame2-pair/tests/fixture
npm install
npx shadow-cljs watch app

# wait for "build completed" and the nREPL port file at
# tests/fixture/target/shadow-cljs/nrepl.port

# 2. run the e2e suite (in another terminal)
cd skills/re-frame2-pair
PAIR2_FIXTURE_DIR="$(pwd)/tests/fixture" \
PAIR2_FIXTURE_URL="http://localhost:8030" \
node tests/e2e/run.cjs
```

Exit code 0 = pass; non-zero with structured stderr = fail.

## Skill output, not visual UI

Pair2's E2E suite asserts on **structured shim output**, not pixel
positions. The browser is along for the ride so the runtime is real;
the assertions are still edn shape on stdout. This matches the rest of
re-frame2-pair's test surfaces.

## Status

Scaffolded — runs cleanly when `PAIR2_FIXTURE_URL` is set and the
fixture is up. When neither is set, the runner exits 0 with a
`skipped — fixture not available` notice so the suite is safe to wire
into CI on non-e2e jobs without false failures.
