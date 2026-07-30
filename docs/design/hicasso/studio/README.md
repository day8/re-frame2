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
```

Exit codes: `0` measured and clean, `1` the run failed, **`2` the arm-order
guard refused** — a figure that moves with its position in the plan is not a
figure, and the repair is the arm, never the tolerance.

The instrument is
`implementation/freehand/test/re_frame/bench/hicasso/lane.cljs`; it lives in the
bench/test measurement lane HD-017 carves out of the donor freeze, and it
requires nothing from any donor `src/` tree.

## Pages

| Page | Phase | What it settles |
|---|---|---|
| [P0 — Reagent-on-subs baseline (mount + bulk)](p0-reagent-on-subs-baseline.md) | P0 | The ship bar's **denominator**: mount and bulk view work for Reagent reading re-frame2 subscriptions. |
| [The reagent-slim non-reactive arm — diagnosis](slim-non-reactive-arm-diagnosis.md) | P0 | Why HD-008's reagent-slim arm read `78 unverified of 78`: the **arm's composition**, not the adapter and not the mixed bundle. A single-substrate slim bundle re-renders on every write. |
