# P0 — the Reagent-on-subs baseline (mount + bulk)

**The ship bar's denominator.** [HD-012](../decisions.md) states the bar as *mount
AND bulk view work ≤ 1.0× Reagent, like-for-like — both sides reading re-frame2
subscriptions*. Until this page there was no such denominator in the repo: every
published Reagent row compared against Reagent reading a bare `reagent.core/atom`
through an `r/cursor`, which is Reagent's own idiom but is a denominator no
re-frame2 application has. This page supplies the one the bar names, and prices
the difference between the two.

Bead **rf2-2rtt6.2**. The rows are appended to the operator-owned standard bead
**rf2-2rtt6.1**; only the operator amends the bar, the budgets, the kill criteria
or the red-zones.

> **Re-certified on main, 2026-07-31 — the rows below reproduce.** This page was
> the last P0 arm whose producing SHA was not on main, whose instruments carried
> no blob anchor, and which had never been re-run under the repaired arm-order
> guard. All three are closed by **[the re-certification below](#the-re-certification-rf2-2rtt6.17)**:
> the landed instrument commit is recovered and byte-identical, the blobs are
> printed, and two independent five-round runs at `32cb224d6e` reproduce every
> row. **No published figure below is superseded.** The rows stand as measured.

## Provenance

| | |
|---|---|
| **Producing commit** | `19401ad083e895b19d55151157f37a59551cb5e2` — **off main.** Its landed equivalent is **`f03960a8da`** (see [the re-certification](#the-re-certification-rf2-2rtt6.17)); the rebase rewrote the id and moved no instrument byte |
| **Reproduction** | `cd implementation && npm run bench:hicasso` |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium, via Playwright), Windows x64 |
| **Build** | `:hicasso-bench` — `:advanced`, `goog.DEBUG false` |
| **Adapter** | `:rf.adapter/reagent`; Reagent 2.0.1 |
| **Schedule** | 5 rounds × (8 warm-up + 12 samples) per arm per round, arms interleaved at the sample level, order rotating **and reflecting** on the sample index |
| **Arm-order guard** | **reportable** — no arm reads differently for its position in the plan. Self-test 8/8 before anything was measured. *This was the pre-#7267 guard; the same verdict has since been re-taken under the repaired one with **zero lost positions on every arm** — see [the re-certification](#the-re-certification-rf2-2rtt6.17)* |
| **Canonical-DOM parity** | clean under `:advanced` — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 1220** (400 mounts M1 + 400 mounts M2 + 420 writes bulk) |

All bar numbers are browser numbers. No JVM or Node figure appears on this page.

## The arms

| arm | what it is | role |
|---|---|---|
| `:floor` | the same DOM, hand-built with `react/createElement`, no substrate | the **per-round calibrator**. This box drifts further across rounds than several of the effects being measured, so every figure is a ratio to the floor measured in *that* round |
| `:reagent-subs` | `reg-view` boundaries reading `@(rf/subscribe [:p0/cell i])` against one frame, `rf/frame-provider` at the root | **the denominator.** The bar's `1.0×` is this arm |
| `:reagent-ratom` | form-2 components over `r/cursor` on a bare `r/atom` | a **labelled lower bound**, never the bar. `subs ÷ ratom` is the reactive system's price, measured rather than argued |
| `:ctl-2x` | the floor at exactly twice the boundaries | the **positive control**, predicted from the element count before the run; parity-exempt because it builds a different page on purpose |

One page hosts all four. The predecessor programme costed this arm at *"two
adapter phases bridged by the floor"* because its own view substrate was inert
under a ratom adapter; every arm here is Reagent or nothing, so one
`(rf/init! reagent/adapter)` serves all of them.

## The rows

Ranges are min–max **across the five rounds**. A range that includes 1.0 means
the two arms are **indistinguishable**, and is reported as such rather than as a
winner.

### Mount — M1: 300 sub-reading boundaries (901 elements) · **bar row**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | **3.899×** | 3.447 – 4.300 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 3.224× | 2.632 – 3.633 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | **1.218×** | 1.122 – 1.310 | disjoint from 1.0 |

Absolute p50 per round, ms: floor 1.80 / 1.90 / 1.65 / 1.50 / 1.50 ·
`reagent-subs` 7.35 / 6.55 / 6.05 / 6.45 / 6.00 · `reagent-ratom` 6.25 / 5.00 /
4.65 / 5.45 / 5.35.

### Mount — M2: the ordinary 12-field form (51 elements) · **diagnostic only**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | 1.874× | 1.750 – 2.050 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 1.785× | 1.625 – 2.083 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | 1.056× | 0.960 – 1.242 | **straddles 1.0 — indistinguishable** |

**This row is diagnostic-grade and must not be quoted against the bar.** A
51-element mount takes a few tenths of a millisecond — three to six of Chrome's
100 µs `performance.now()` quanta — so its ratios are quantised more coarsely
than a 10% effect. What it does say is that the ordinary form shape shows **no
large reactive-system penalty on mount**: at this size the subscription graph and
the bare cursor are not distinguishable.

### Bulk — one commit that all 300 sub-reading boundaries read · **bar row**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | **7.064×** | 6.200 – 7.700 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 3.519× | 3.200 – 3.900 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | **2.008×** | 1.938 – 2.100 | disjoint from 1.0 |

Absolute p50 per round, ms: floor 0.60 / 0.50 / 0.55 / 0.50 / 0.50 ·
`reagent-subs` 4.20 / 3.85 / 3.75 / 3.80 / 3.10 · `reagent-ratom` 2.00 / 1.95 /
1.85 / 1.90 / 1.60.

Window decomposition (p50 ms): the write leg and the microtask gap both read
**0.0** on every arm — `frame/replace-app-db!` installs into the container and
returns, and Reagent commits inside its own drain — so essentially the entire
window is the drain: floor 0.50, `reagent-subs` 3.80, `reagent-ratom` 1.90,
`ctl-2x` 1.00.

**The bulk floor is not a lower bound.** It is a plain top-down React re-render,
which is what an application with no reactive substrate costs. A fine-grained
substrate can and should beat it on a narrow write; on a *broad* write, where
every boundary changes, it is the thing to beat.

## The positive control

Predicted from the element count, written down before the run, published every
run whether it passes or not.

| row | predicted | measured (mean) | range | basis | verdict |
|---|---|---|---|---|---|
| mount M1 | 1.999× | 1.839× | 1.632 – 1.944 | 1801 / 901 elements | ✅ within ±25% |
| mount M2 | 1.941× | 1.674× | 1.500 – 1.833 | 99 / 51 elements | ✅ within ±25% |
| bulk | 1.999× | 1.930× | 1.800 – 2.200 | 1801 / 901 elements | ✅ within ±25% |

The slack is 25% and is generous on purpose: the claim being certified is *the
instrument has signal*, not *the model is exact* — a top-down React re-render is
not perfectly linear in element count, because the root, the commit and the diff
walk do not double. Every measured control sits **below** its prediction, which
is the direction that fixed per-root overhead predicts.

## The re-certification (rf2-2rtt6.17)

The audit's cold read found this page carried the wave's **least-anchored**
measurement and its **first headline**: a producing SHA not on main, no blob
anchor, four of five instrument blobs moved since, and no re-run under the
guard repair that stopped a verdict reading `[ok]` while adjudicating on a
fraction of its samples. Headline 1 — the reactive leg — rested on it alone,
because the converged arm reproduces `reagent-subs ÷ floor` but carries no
`reagent-ratom` arm.

**The instrument commit is recovered, and it is byte-identical.** The rebase
that took `19401ad083` onto main produced **`f03960a8da`**, which *is* an
ancestor of main. Every instrument file matches at the byte:

| file | blob at `19401ad083` **and** `f03960a8da` | on main `32cb224d6e` |
|---|---|---|
| `…/hicasso/lane.cljs` | `d32312d9c562f0b6aa7d7f84538eb81ffc18e61c` | `885592cf9fdd…` — **moved** (#7267, #7270) |
| `…/hicasso/p0_reagent_app.cljs` | `a4aefdd825fef83cc1810b05926a488eee69613d` | `b7dfb2452b8a…` — **moved** (#7267) |
| `…/hicasso/p0_reagent_views.cljs` | `6daefef0479e0a7247e0deda6f2c574d9c04bd93` | `4032e39779ce…` — **moved** |
| `…/hicasso/run.cjs` | `3dc92c316191b8f52ab04bfced399192203cf95d` | `3dc92c316191…` — **unchanged** |
| `…/bench/order_guard.cljc` | `adf59ca03cfe8e2639de97c031c138838f2d34b7` | `e42450ef1c77…` — **moved** (#7267) |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_app.cljs
git rev-parse f03960a8da:$P   # a4aefdd825fef83cc1810b05926a488eee69613d
git merge-base --is-ancestor f03960a8da origin/main && echo on-main
```

**Four blobs have moved, so the wave's rule applies: re-run or mark superseded.
It was re-run.** Twice, five rounds each, at main `32cb224d6e`, under the
repaired guard — `npm run bench:hicasso`, **exit 0 both times**.

### Every row reproduces

Published against both re-runs. Overlap of the min–max ranges is the test; a
verdict flip would be the finding.

| row | figure | published (`f03960a8da`) | re-run 1 (`32cb224d6e`) | re-run 2 (`32cb224d6e`) | verdict |
|---|---|---|---|---|---|
| **M1 mount** | `reagent-subs ÷ floor` | 3.899 [3.447 – 4.300] | 3.973 [3.556 – 4.571] | 3.513 [3.313 – 3.688] | **reproduces**, all disjoint from 1.0 |
| | `reagent-ratom ÷ floor` | 3.224 [2.632 – 3.633] | 3.266 [3.000 – 3.714] | 2.913 [2.625 – 3.375] | **reproduces** |
| | **reactive leg** | **1.218 [1.122 – 1.310]** | **1.216 [1.185 – 1.241]** | **1.213 [1.093 – 1.273]** | **reproduces — disjoint from 1.0 on all three** |
| **M2 mount** *(diagnostic)* | `reagent-subs ÷ floor` | 1.874 [1.750 – 2.050] | 1.650 [1.500 – 2.000] | 1.700 [1.333 – 2.000] | reproduces |
| | `reagent-ratom ÷ floor` | 1.785 [1.625 – 2.083] | 1.433 [1.333 – 1.500] | 1.633 [1.333 – 2.000] | run 2 overlaps; **run 1 does not** — see below |
| | **reactive leg** | 1.056 [0.960 – 1.242] | 1.150 [1.000 – 1.333] | 1.050 [1.000 – 1.250] | **same verdict — straddles 1.0 on all three** |
| **bulk broad** | `reagent-subs ÷ floor` | 7.064 [6.200 – 7.700] | 7.280 [6.400 – 8.000] | 7.330 [6.400 – 8.500] | **reproduces**, all disjoint |
| | `reagent-ratom ÷ floor` | 3.519 [3.200 – 3.900] | 3.707 [3.200 – 4.000] | 3.540 [3.000 – 4.000] | **reproduces** |
| | **reactive leg** | **2.008 [1.938 – 2.100]** | **1.965 [1.875 – 2.000]** | **2.073 [2.000 – 2.167]** | **reproduces — disjoint from 1.0 on all three** |

**Headline 1 is corroborated for the first time.** *Reading re-frame2
subscriptions rather than a bare cursor costs Reagent ≈1.22× on mount and
≈2.01× on a broad commit.* Both legs now rest on three independent five-round
runs instead of one. The mount leg's three means sit inside 0.5% of each other
(1.218 / 1.216 / 1.213); the broad leg's three all clear 1.0 with their whole
range, and the widest spread across them is 1.875 – 2.167.

**The one range that does not overlap, stated rather than smoothed.** Run 1's
`M2/reagent-ratom ÷ floor` reads [1.333 – 1.500] against a published [1.625 –
2.083]. It is on **the row this page already grades diagnostic and forbids
quoting against the bar**, whose absolute p50 is two to four of Chrome's 100 µs
quanta — the ratio's denominator moves by a whole quantum between adjacent
rounds. Run 2 overlaps the published range on the same figure ([1.333 – 2.000]),
and the **leg** that row actually reports straddles 1.0 on all three runs, which
is the verdict. Nothing here changes; the row's grading is what already said it.

### The re-certification's own provenance

| | |
|---|---|
| **Commit measured at** | `32cb224d6e5dde730d1e7ddc99c062656cb68155` — `origin/main`, clean tree |
| **Reproduction** | `cd implementation && npm run bench:hicasso` — **run twice at this instrument, exit 0 both times** |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, 24 logical CPUs, sibling agents live on the box |
| **Instrument** | `lane.cljs` `885592cf9fdd79f701d6353fc5d3dae0868d74f1` · `p0_reagent_app.cljs` `b7dfb2452b8a7984237e0c2db5e117dc72001638` · `p0_reagent_views.cljs` `4032e39779ce55fee1e1cd4f7a8e9561237e2cfd` · `run.cjs` `3dc92c316191b8f52ab04bfced399192203cf95d` · `order_guard.cljc` `e42450ef1c7759b51feca6a3d1bae2c0eb8ab323` |
| **Arm-order guard** | **no refusal on either run** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10 |
| **Position completeness** | **60 of 60 per arm, on all 12 arms, both runs** — zero `:lost-positions`, every phase contrast adjudicated on a full 20-against-20 |
| **Canonical-DOM parity** | clean under `:advanced` — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 1,220 on each run** (400 M1 mounts + 400 M2 mounts + 420 bulk writes) |

**The guard repair is inert to this arm, and that is now measured rather than
assumed.** The audit's third finding was that this page's whole warrant against
position effects came from the *pre-repair* guard — the one that could drop a
sample carrying a non-finite `:position` from the phase contrast while printing
the arm's full count. Both re-runs report `unchecked? false` with **zero lost
positions on every arm**, so each phase contrast was adjudicated on the full
first-third-against-last-third split (20 against 20 of 60) rather than on a
silently smaller set. The verdict *reportable* is the same verdict, now taken
under an instrument that cannot narrow the question without saying so.

### The controls, under both readings of the rule

`lane/control-verdict` implements the **overlap** rule; HD-008 states a
**strict** every-round-inside rule; **rf2-egdaq** is the open operator question
of which governs. Both readings are reported here rather than adjudicated:

| run | row | predicted | measured range | ±25% band | overlap rule | strict rule |
|---|---|---|---|---|---|---|
| 1 | M1 | 1.9989× | 1.778 – 2.071 | 1.499 – 2.499 | ✅ | ✅ |
| 1 | M2 | 1.9412× | 1.500 – 2.000 | 1.456 – 2.427 | ✅ | ✅ |
| 1 | bulk | 1.9989× | 1.600 – **2.500** | 1.499 – **2.499** | ✅ | ❌ by 0.0014 |
| 2 | M1 | 1.9989× | 1.750 – 1.938 | 1.499 – 2.499 | ✅ | ✅ |
| 2 | M2 | 1.9412× | 1.500 – 2.000 | 1.456 – 2.427 | ✅ | ✅ |
| 2 | bulk | 1.9989× | 1.833 – **2.500** | 1.499 – **2.499** | ✅ | ❌ by 0.0014 |

**Six controls, six passes under the rule actually installed.** The two that
would fail a strict reading fail it by **0.0014**, and they fail it for a reason
that is arithmetic rather than instrumental: the bulk floor's p50 is two to
three of Chrome's 100 µs quanta, so the control's ratio can only land on a
coarse lattice, and `2.5` is the lattice point immediately above a ceiling of
`2.4986`. That is the shape of evidence rf2-egdaq needs and it points against
tightening: **a strict rule would retroactively fail a control this page already
published as passing**, on a quantisation artefact rather than on a loss of
signal. Nothing here installs it.

## What this settles, and what it does not

**Settles.** The bar's denominator exists and is a browser number. Reading
re-frame2 subscriptions rather than a bare cursor costs Reagent **≈1.22× on
mount** of the 300-boundary shape and **≈2.01× on a broad commit** — both
disjoint from 1.0, so both real. That figure was previously argued rather than
measured, and it is the term that made the predecessor's `13.5×` broad-update row
an upper bound of unknown content. **It has now been measured three times** — the
publication run and two independent re-runs at main — and all three agree; see
[the re-certification](#the-re-certification-rf2-2rtt6.17).

**Does not settle — one arm, three runs.** Repetition is not replication. The
reactive leg still comes from *one implementation of one arm*: three runs of it
bound the instrument's run-to-run noise, and they do not bound a systematic
error in how `:reagent-ratom` is written. The converged witness set is a genuine
second implementation of the **denominator** and its `reagent-subs ÷ floor`
ranges overlap this page's on all three shared rows, but it carries **no
`reagent-ratom` arm**, so nothing yet corroborates the reactive leg from a
second author. A second witness for that term remains worth having, and this
page does not claim otherwise.

**Does not settle.** Nothing about a candidate: no Hicasso arm exists, and none is
quotable against this table until it does. Nothing about UIx (rf2-2rtt6.4 owns the
frontier comparator and the red-zone ratios are set from it). Nothing about narrow
writes (rf2-2rtt6.3) or retained heap (rf2-2rtt6.5). And nothing about the
ordinary form shape at better than clock resolution.

## Instrument faults caught, and what each cost

Both were caught by the harness's own discipline **before** any number was
published, and both were repaired in the arm.

**1. A read-back that could not pass — `400 unverified of 400`.** The mount
verification probed a `data-i` cell in both witnesses; the form witness carries no
such attribute, so every M2 mount was counted unverified while the page was in
fact perfectly correct. A read-back that cannot pass is worse than none: it
manufactures a defect and would hide a real one behind it. Verification is now per
witness and reads **both ends** of the page — a page that committed only its head
passes a single-probe check at index 0.

**2. The arm-order guard refused a plausible instrument change — exit 2.**
Batching M2 to eight 51-element roots in one `flushSync` window, to lift it clear
of the clock clamp, produced this:

| arm | last third ÷ first third | predecessor factor |
|---|---|---|
| `M2/floor` | **5.017×**, ranges disjoint | clean |
| `M2/ctl-2x` | **5.427×**, ranges disjoint | clean |
| `M2/reagent-ratom` | **4.766×**, ranges disjoint | clean |
| `M2/reagent-subs` | **3.234×**, ranges disjoint | clean |

— while the unbatched M1 row, running immediately before it in the same page,
drifted 1.13×–1.16× with ranges overlapping. Position, not adjacency; a property
of the batched arm, not of anything under test. **The batch was withdrawn and the
tolerance was not touched.** The resulting clock coarseness is stated on the M2
row above and that row is graded diagnostic. A refused figure and a quantised
figure are not the same thing: the first may not be published at all, the second
may be published with its resolution named.

## Method

- **Both orders.** Every pairing runs in both arm orders — the schedule rotates
  *and reflects* on the sample index, because a bare cyclic rotation changes only
  which arm goes first and leaves every adjacency intact.
- **Position before adjacency.** Every sample carries its position in the whole
  run, and the guard partitions on first-third against last-third as well as on
  predecessor. Warm-up matters more than interleaving.
- **Ranges, never a mean alone.** Overlapping ranges mean indistinguishable.
- **Every measured write and every measured mount is read back out of the DOM
  inside its own window**, and the count is published as `N unverified of M`.
- **A positive control with predicted vs measured, every run.**
- **Arm labels are row-qualified** (`M1/floor`, `M2/floor`, `bulk/floor`) before
  they reach the guard: three witnesses' floor arms are three different amounts of
  work, and pooled under one name their ranges are disjoint by construction —
  the guard would refuse the witness table rather than a contamination.
