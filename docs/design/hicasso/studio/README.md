# Hicasso — studio

The programme's measured record. Every number Hicasso publishes lands here and
on its bead; the operator-owned standard bead (`rf2-2rtt6.1`) carries the bar,
the budgets and the kill criteria, and this tree carries the evidence those
rulings are made against.

Minted by the first P0 worker per [HD-017](../decisions.md). **The Freehand
studio at `docs/design/freehand/studio/` is frozen and is never extended** — it
is the predecessor programme's record and it stays exactly as its own programme
left it.

## What a page in here owes its reader

The measurement discipline in [validation.md](../validation.md) is binding, and
these four obligations are the ones a page can silently fail:

- **The producing commit SHA and a reproduction command, per row.** Evidence
  must not outlive the code that produced it.
- **The runtime, beside every figure** — browser and version, build
  optimisation level, `goog.DEBUG`. All bar numbers are browser numbers
  (`:advanced`, real browser); JVM and Node figures are diagnostic-only and are
  never quotable against the bar.
- **Ranges across rounds, never a mean alone.** Overlapping ranges mean
  *indistinguishable*, and the page says so rather than quoting the mean as a
  winner.
- **`N unverified of M`** for any row that writes, and the positive control's
  **predicted vs measured**, on every run, passing or not.

A ratio stays attached to its exact witness, denominator, commit and build.
Never average across instruments.

## The lane

One build id serves the whole programme —
[`:hicasso-bench`](../../../../implementation/shadow-cljs.edn) — and one driver
rides it, so an arm needs no hot-zone edit of its own:

```bash
cd implementation

# the default entry: the P0 Reagent-on-subs baseline
npm run bench:hicasso

# any other arm, same build id, same driver
HICASSO_INIT_FN=re-frame.bench.hicasso.<arm>-app/-main \
HICASSO_OUT_DIR=out/hicasso-<arm> \
HICASSO_PORT=8132 \
  node freehand/test/re_frame/bench/hicasso/run.cjs

# an arm that needs more than one page load brings its own thin driver on
# the same build id — e.g. the converged clock table, one row per page
node freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs
```

Exit codes: `0` measured and clean, `1` the run failed, **`2` the arm-order
guard refused** — a figure that moves with its position in the plan is not a
figure, and the repair is the arm, never the tolerance.

**One build id also means one build cache, and each driver now clears it before
building** (`rf2-2rtt6.20`). shadow-cljs derives the cache directory from the
build id alone, and fixes it before any `--config-merge` data is applied — so
the arm is invisible to the cache key and every arm above shared one cache
entry. Running the commands in this section in sequence used to leave a cache
that compiled cleanly and then produced an `:advanced` bundle that **died on its
first execution**, `Cannot read properties of undefined (reading 'd')`, which
made a sound arm look broken at HEAD. The carrier was the `shadow-js/`
npm-conversion cache: its index is invalidated on shadow's own cache key, never
on the set of npm modules the build actually needs, so the UIx arm's
`react/jsx-runtime` was linked against whole-build state established for the
Reagent arms' fourteen modules. **Every reproduction command on these pages
therefore runs from a cold or a warm cache, in any order** — and emits the same
bundle either way, which a reproduction command needs and did not previously
have: two cleared-cache builds of an arm are byte-identical, where before the
fix the bundle depended on which arm had built last. The clear sits inside the
run-to-run noise, because the time is JVM start, classpath and the Closure
`:advanced` pass rather than the CLJS compile the cache holds.
`freehand/test/re_frame/bench/hicasso/lane_cache.cjs` carries the isolation, the
measurement and the alternatives that were rejected.

The instrument is
`implementation/freehand/test/re_frame/bench/hicasso/lane.cljs`; it lives in the
bench/test measurement lane HD-017 carves out of the donor freeze, and it
requires nothing from any donor `src/` tree.

## Pages

| Page | Phase | What it settles |
|---|---|---|
| [P0 — Reagent-on-subs baseline (mount + bulk)](p0-reagent-on-subs-baseline.md) | P0 | The ship bar's **denominator**: mount and bulk view work for Reagent reading re-frame2 subscriptions. |
| [P0 — ratom-spine narrow write](p0-ratom-spine-narrow-write.md) | P0 | What a narrow write on the ratom spine costs, and the write-versus-flush split. |
| [P0 — the UIx-on-subs frontier arm](p0-uix-on-subs-frontier-arm.md) | P0 | The frontier against the denominator, on the arm's own witnesses. Its **retained-heap** red-zones stand; its clock rows are superseded by the converged page. |
| [P0 — the converged witness set, and the red-zones re-derived on it](p0-converged-witness-set.md) | P0 | The **clock red-zones** on the denominator's own witnesses, and how far the earlier per-arm witness sets moved the verdicts. |
| [P0 — the reads-per-boundary heap ladder](reads-per-boundary-heap-ladder.md) | P0 | Retained heap per subscribing boundary across the 1/3/7/20 reads ladder, both donors. |
| [HD-008 — the composed donor arm](hd8-composed-donor-arm.md) | donor gate | What the composed donor arm costs against both Reagent paths and against the frontier. |
| [The UIx spine's per-read allocation, decomposed](uix-spine-per-read-decomposition.md) | P0 | Where a UIx-on-subs read's bytes go. |
| [The reagent-slim non-reactive arm — diagnosis](slim-non-reactive-arm-diagnosis.md) | P0 | Why HD-008's reagent-slim arm read `78 unverified of 78`: the **arm's composition**, not the adapter and not the mixed bundle. A single-substrate slim bundle re-renders on every write. |

**Clock red-zones live on the converged page.** Where it and the frontier arm
disagree, the converged row is the operative one: the bar's denominator is
Reagent-on-subs, so the denominator's witness set defines the comparison by
construction, and a threshold measured on a different witness is a threshold on
a different question.

**Retained-heap red-zones are not superseded.** They live on the heap ladder and
on the frontier arm's own record, and stand as published. Three shapes spanning
a 4× range in markup density agree within 8% — because retained bytes per
subscribing boundary is a property of the boundary. The clock is not: element
count decides what fraction of the window is React's own work, which is why the
clock rows had to move to the denominator's pages and the heap rows did not.
