# The in-page mount term, decomposed

`rf2-cno31` fixed the route-link term, re-took the census clock rows, and
published this split for the acceptance arm — floor `12.010 ms`;
**hicasso `16.015 = 8.254 taskNet + 6.100 in-page`**; **uix
`13.742 = 8.246 taskNet + 3.900 in-page`**. `taskNet` is indistinguishable
between the arms (8.254 against 8.246), so the whole `+2.2 ms` lives in the
**in-page** half. `rf2-2rtt6.63` had already put the interpreter walk at
**0.9636× stock Reagent**, which took the walk off the list. This page
decomposes the 6.100 ms, term by term, and then does the same to the twin's
3.900 so the two are comparable line for line.

Bead **`rf2-409ab`**. The standard is **`rf2-2rtt6.1`**.

> **DIAGNOSTIC, NOT PUBLISHED.** Every figure here is an **in-page
> `flushSync` window**, and `rf2-8nqsl` established that such a window
> mis-reads a substrate arm's ratio *to the floor* by 300–610% because it
> sees only the script half of a mount. That is exactly why it is the right
> instrument for this question: **the quantity being decomposed IS the script
> half**. No figure on this page is a gate row, no verdict here re-baselines
> HD-012, and the ruling on any of it is the operator's (`rf2-2rtt6.1`),
> never this page's.

## 1. The instrument — one door, one window, fifteen arms

**The door.** Every arm mounts through `lane/mount-arm!`: a container created
and attached *outside* the window, then `createRoot` + `.render` inside one
`react-dom/flushSync`, with `performance.now()` on either side. That is
**the same function, the same window and the same page** the published
in-page term is read through — `shapes/census_clock_app/sample!` calls
`lane/mount-arm!` and publishes `(:ms mnt)`. So the ladder's top rung *is*
the published quantity, and every rung below it is that rung with one term
removed.

**Which door, stated because `rf2-emvod` made it a question.** Nothing here
is a `TaskDuration` and nothing here goes through `page.click`, so the
protocol-door ambiguity that made `taskNet` two different quantities does
not arise. This page reports one clock and names it in every table.

**The page** is the acceptance shape: `large-template`, **1,202 elements,
ONE boundary, 141 per-instance reads, 207 route-links**. `B = 1` matters and
is the first correction this bead produces — see §5.

**The arms.** Fifteen, all building the same page; the fourteen non-control
arms' canonical DOM is proven **byte-identical to the candidate's**
(58,474 bytes) before a clock is read, and the run refuses on any
disagreement.

| arm | what it is | the subtraction it enables |
|---|---|---|
| `ship` | the real `shapes/large-template/page` through the real shell | the published 6.100 |
| `local` | the same body, re-spelled in the measuring namespace | fidelity: `local / ship` |
| `nolink` | `local` with the card's three `route-link`s spelled as literal-href anchors | `local − nolink` = **routing** |
| `nohiccup` | the 141 reads, then a hiccup tree built once at boot | `nolink − nohiccup` = **hiccup build** |
| `nowalk` | the 141 reads, then a React element tree built once at boot | `nohiccup − nowalk` = **the codec walk** |
| `noreads` | the frozen element tree, no reads at all | `nowalk − noreads` = **the 141 reads and their commit** |
| `nomemo` | `noreads` minted **without** `codec/memoize-boundary!` | `noreads − nomemo` = **the HD-028 memo fiber** |
| `bare` | a plain React function component — no shell, no hooks, no fence, no entry | `nomemo − bare` = **the shell** |
| `coarse` | `local` at the **twin's** read shape: five coarse reads, cards from data | `local − coarse` = **the read-shape asymmetry** |
| `floor` | the census floor arm — hand-written `createElement` | the calibrator |
| `uix` | the real UIx twin | the published 3.900 |
| `uixlocal` | the twin, re-spelled here | fidelity: `uixlocal / uix` |
| `uixnolink` | the twin with literal-href anchors | `uixlocal − uixnolink` = **the twin's routing** |
| `uixbare` | the twin's five reads, then the frozen element tree | `uixnolink − uixbare` = **the twin's `$` markup** |
| `ctl-2x` | the floor at twice the cards | the positive control |

`bare` is the **shared base**: React mounting 1,202 elements it was handed.
Everything above it on either arm is what that arm *adds*, and the two arms'
additions side by side are the answer to *what does UIx not do that we do?*

**The ablation arms are written in the measuring namespace** (`rf2-2rtt6.32`:
a local arm timed against a foreign one compares call conventions as much as
terms). `local` is a re-spelling of the real page and the whole candidate
ladder descends from it; `uixlocal` does the same for the twin. Both
fidelities are gated twice — canonical-DOM byte-identity with the real arm at
boot, fatal, and the timing ratio published on every run.

**The frozen trees** are built once at boot inside a real body door on a
capture frame. React elements are immutable and every sample mounts a fresh
root, so React performs a full mount either way; what the freeze removes is
the *building*, which is the term being priced.

**Coldness between samples.** Cells are global per (frame, query), so every
arm has **its own frame**, and between samples the mount is released and one
macrotask settles — which is what lets the cell reaper run and what makes the
next sample's 141 reads cold again. The commit micro (§6) additionally resets
the runtime synchronously between windows, because its loop never yields.

**Why deltas are quoted on a 10% trimmed mean.** Chrome clamps
`performance.now` to 100 µs, so a single-mount reading is a grid value and so
is a median of them: a term worth 0.05 ms is invisible to a p50 and a term
worth 0.25 ms is quoted to ±0.05. A mean over 60 quantised samples estimates
the underlying mean off-grid, and the clamp's bias — identical for every arm
through one door — cancels in a difference. Trimmed rather than raw because
this box produces occasional 2× outliers. **p50, min and max are published
beside every trimmed mean**, and the raw per-sample rounds are in the
dataset.

**The instrument's floor is ~0.15 ms.** Terms below it are reported as
*indistinguishable from zero* and never quoted as a magnitude. Two of the
bead's candidates land there, and that is a finding rather than a
measurement failure — see §5.

## 2. Provenance

| | |
|---|---|
| **Producing commit** | `b8e6da66814e380cafe08a9ebdf58d74f3730828` on `worker/inpage-409ab`, based on `origin/main` `667c744dc8`. Working tree clean at every run (`out/` and `logs/` are ignored), so the stamped blobs are the commit's. **Authored, and rebase-merged, so this SHA is on no branch and will not resolve in a fresh clone**; it landed on main as **`4866dfa90c`** (same patch — identical `git patch-id --stable`), with the blob it contributed unchanged. The landed SHA is the one to check out; it sits on a later base, so it carries the change rather than the whole measured tree |
| **Reproduction** | `HICASSO_INIT_FN=re-frame.bench.hicasso.inpage-ladder-app/-main HICASSO_OUT_DIR=out/hicasso-inpage-ladder HICASSO_PORT=8152 node implementation/freehand/test/re_frame/bench/hicasso/run.cjs` |
| **Re-deriving every figure below** | `node implementation/freehand/test/re_frame/bench/hicasso/inpage_ladder_aggregate.cjs` — fail-closed, see §7 |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap; `implementation/shadow-cljs.edn` untouched) — `:advanced`, `goog.DEBUG false`, cache cleared per `rf2-2rtt6.20`. 199 files, 144 compiled, **0 warnings** |
| **Runtime** | `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), node `v24.13.0`, hardware-concurrency 24, device-memory 32 |
| **Design** | 6 rounds × (4 warmup + 10 samples) per arm — 60 samples per arm per run, four runs |
| **Clock** | in-page `performance.now()` around one `react-dom/flushSync` mount, **diagnostic**; deltas on the 10% trimmed mean |
| **Door** | every arm: `lane/mount-arm!`, container attached outside the window |
| **Guard** | arm-order guard, tolerance 10%, by predecessor **and** by phase — **no refusal on any arm of any of the four runs** |
| **Parity** | 14 of 15 arms canonical-DOM byte-identical to the candidate's page, all four runs; `ctl-2x` exempt by construction |
| **Read-back** | harvest gate fatal unless the real page mounts 1,202 elements and resolves exactly 3 + 2 × 69 = 141 distinct reads — **passed on all four runs** |
| **Windows** | announced on `rf2-409ab` before opening. Run A closed `2026-08-03 05:28:12 AUSEST`, runs B/C/D `05:29:17 – 05:32:13`. **Run A was taken from the working file that commit `b8e6da6681` then recorded unchanged**, so its blob is the producing commit's; B, C and D ran at the commit |
| **Quiet box** | **FAILED, and recorded rather than hidden** — see §8 |
| **Exit codes** | four publication runs, **all exit `0`**; aggregator exit `0`; two deliberate mutations of the dataset each exit `1` (§7) |

Blob hashes, read at the producing commit:

| file | blob |
|---|---|
| `…/bench/hicasso/inpage_ladder_app.cljs` | `0b6a07734670c93dd7f23b6d18c5dc7f13d83609` **(this bead's instrument)** |
| `…/bench/hicasso/shapes/large_template.cljs` | `f575b78429ba1292a98a355b3ba1a8d3fac5bec6` |
| `…/bench/hicasso/shapes/card.cljs` | `07458921f7830b99b60a90262cbb974f7e05d5c7` |
| `…/bench/hicasso/shapes/census_clock_arms.cljs` | `de6bacfad4c46ca491569ffbe72f4adc0cfa3f36` |
| `…/bench/hicasso/arm1/runtime.cljs` | `0cd4311c228fd26e66a6c1639dcc3d969dce8950` |
| `…/bench/hicasso/front/codec.cljs` | `874bd699aa4015fec847d0b4cc699b299e9ca0bd` |
| `…/bench/hicasso/front/route_link.cljs` | `e093d729322e239d15235bcc11992102826aa977` |
| `…/bench/hicasso/lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `…/bench/hicasso/run.cjs` | `07575b38d950eeee86b54df259c4d059c21c0295` |
| `implementation/core/src/re_frame/substrate/spine.cljs` | `ad7b19d9d8957e7a1872e58f9b18ace8acdc4841` |

Datasets — the raw per-sample rounds every statistic below is a function of:
`implementation/freehand/test/re_frame/bench/hicasso/data/inpage-ladder-409ab/`.

## 3. The instrument ties to the published row

The ladder's `ship` and `uix` arms are the published in-page arms, on a box
that is roughly 15% faster today than the one `rf2-cno31` ran on. The
**relationship** is what carries across, and it does:

| | this page | `rf2-cno31` published | agreement |
|---|---|---|---|
| `ship` in-page | **5.209 ms** [5.142 – 5.325] | 6.100 ms | box drift |
| `uix` in-page | **3.352 ms** [3.179 – 3.567] | 3.900 ms | box drift |
| **`ship` / `uix`** | **1.5543×** | **1.5641×** | **0.6% apart** |

A decomposition of a quantity the instrument reproduces to within 0.6% of the
published ratio is a decomposition of the published quantity.

## 4. The two ladders

Ensemble of four runs; every cell is the run's 10%-trimmed mean, ranges are
across runs. Absolutes in ms, in-page window.

**The candidate — `ship` 5.209 ms**

| term | ms | range | share of `ship` |
|---|---|---|---|
| React mounts the tree it was handed (`bare`) | **1.623** | [1.594 – 1.679] | 31% |
| routing — 207 `route-link`s (`local − nolink`) | **1.295** | [1.098 – 1.448] | 25% |
| the 141 reads **and their commit** (`nowalk − noreads`) | **1.213** | [1.129 – 1.390] | 23% |
| the codec walk (`nohiccup − nowalk`) | **0.687** | [0.581 – 0.790] | 13% |
| hiccup materialisation (`nolink − nohiccup`) | **0.291** | [0.158 – 0.342] | 6% |
| the ≤2-hook shell (`nomemo − bare`) | 0.054 | [−0.060 – 0.208] | **indistinguishable from zero** |
| the HD-028 memo fiber (`noreads − nomemo`) | −0.008 | [−0.115 – 0.073] | **indistinguishable from zero** |
| — sum | 5.155 | | residual 0.054 (1%) |

**The control — `uix` 3.352 ms**

| term | ms | range | share of `uix` |
|---|---|---|---|
| React mounts the tree it was handed (`bare`) | **1.623** | [1.594 – 1.679] | 48% |
| routing — the same 207 anchors (`uixlocal − uixnolink`) | **1.139** | [0.996 – 1.375] | 34% |
| `$` markup + the card function (`uixnolink − uixbare`) | **0.425** | [0.235 – 0.531] | 13% |
| 5 coarse reads + `use-current-frame` (`uixbare − bare`) | **0.189** | [0.077 – 0.348] | 6% |
| — sum | 3.376 | | residual −0.024 (−1%) |

**Copy fidelity**, published because the ablations descend from the copies:
`local / ship` **0.9891** [0.9607 – 1.0160]; `uixlocal / uix` **1.0082**
[0.9317 – 1.0772]. Both straddle 1.0.

**Calibration.** `floor` — hand-written `createElement`, built *inside* the
window — reads 1.917 ms [1.740 – 2.117] against `bare`'s 1.623, so
**building 1,202 elements by hand costs ~0.29 ms**. The candidate's
build + walk is 0.978 ms and the twin's `$` markup is 0.425 ms; both are
above the hand-written floor because both carry the card function, the `for`
seqs and the props conversion the floor's literal `#js` maps do not.

## 5. The deficit, by term — and three candidates acquitted

Subtracting the two ladders line for line:

| term | candidate | control | **gap** | share of the deficit |
|---|---|---|---|---|
| the reads | 1.213 | 0.189 | **+1.025** | **55%** |
| the markup (build + walk vs `$`) | 0.978 | 0.425 | **+0.553** | **30%** |
| routing | 1.295 | 1.139 | **+0.156** | 8% |
| shell + memo fiber | 0.046 | — | +0.046 | 2% |
| residual | | | 0.079 | 4% |
| **deficit (`ship − uix`)** | | | **1.858** [1.654 – 2.004] | 100% |

**THE BOUNDARY SHELL IS ACQUITTED, and the arithmetic acquits it before the
clock does.** The bead's first candidate reads *"207 boundaries ×
`useContext` + `useSyncExternalStore`"*. **The acceptance page has ONE
boundary.** `B = 1` is stamped on `rf2-cno31`'s own row table; 207 is the
**route-link** count, and a route-link is a plain function that mints no
boundary, adds no hook and reads no subscription. So the shell's React work
on this page is **two hooks in total**, and the measurement agrees: the shell
term is 0.054 ms [−0.060 – 0.208], straddling zero, below this
instrument's floor. There is no hook-per-boundary cost to remove here
because there are no boundaries to remove it from.

**THE MEMO WRAPPER'S FIBER IS TIMED FOR THE FIRST TIME, and it is zero.**
HD-028 records one `MemoComponent` fiber per boundary, measured at ~200 B and
never timed. Timed: **−0.008 ms [−0.115 – 0.073]** — one fiber for one
boundary, indistinguishable from zero on a 5.2 ms mount. The heap ledger's
entry stands; the clock adds that it costs no measurable time at this
decomposition. (On the 301-boundary `feed` row it would be 301 fibers, and
this page does not speak for that row.)

**ELEMENT CREATION IS NOT THE STORY EITHER.** The whole markup term — hiccup
materialisation plus the codec walk, 0.978 ms — exceeds the twin's compiled
`$` markup by 0.553 ms, which is 30% of the deficit and the second-largest
term. It is real, it is the interpreter, and it is consistent with
`rf2-2rtt6.63`'s finding rather than against it: our *walk* is at or below
stock Reagent's, and stock Reagent is not what UIx does. **UIx has no
interpreter at all** — `$` is a compile-time macro that emits
`createElement` — so the comparison is not walk-against-walk but
walk-against-nothing, and the residual is the price of hiccup as a runtime
data structure. Notably **0.291 ms of it is building the hiccup**, a term no
prior page priced because the walk instruments all walk an
*already-realized* witness.

**THE READ TERM IS THE STORY: 55% of the deficit.** 141 per-instance reads
plus the commit that installs their 141 cells, against the twin's five coarse
reads. §6 prices it two more ways.

## 6. The read term, measured three ways

**(a) By ablation, at the mount.** `nowalk − noreads` = **1.213 ms**
[1.129 – 1.390]. `noreads` has an *empty* read set, so its boundary commits
nothing; this delta is therefore the render half **and** the commit half
together — which is correct, because both happen inside the timed window.

**That the commit is inside the window is measured, not assumed.**
Immediately after `flushSync` returns — outside the window, before any settle
— the runtime holds **141 live cells, on 84 of 84 sampled mounts, in every
one of the four runs**. React flushes the `useSyncExternalStore` subscription
inside the synchronous flush, so the boundary's whole commit is billed to the
mount.

**(b) By micro, on the page's own roster.** Eight body-door passes per
window, the shape `rf2-6c237` used, so the numbers are directly comparable:

| | this page | `rf2-6c237` | |
|---|---|---|---|
| 141 cold reads, render half | **0.341 ms/pass** (2.42 µs/read) | 0.2875 ms/pass (2.04 µs/read) | +19% |
| the commit half, per 141-key `commit-boundary!` | **0.827 ms** | 0.7625 ms | +8% |
| sum | 1.168 ms | 1.050 ms | |

**So the cold-read term is still the term it was.** Both halves reproduce
within box drift (this box is demonstrably noisier — §8), and their sum,
1.168 ms, agrees with the mount ablation's 1.213 ms to within 4%. Nothing has
regressed since `rf2-6c237` landed its 0.49×, and nothing has improved
either.

*Both commit-half figures in that table predate `rf2-aqgr2` (`f7fd0c6a52`,
2026-08-03 12:58), which stopped the runtime minting a `Keyword` per key
cell — this run closed at 05:28 and `rf2-6c237`'s at 21:43 the night before.
The comparison is therefore still like for like, but 0.827 and 0.7625 are
both upper bounds on today's seam (rf2-gttif).*

**(c) By counterfactual, on our own arm.** The decisive arm. `coarse` is the
candidate's page reading **the twin's five coarse subscriptions** at five
fixed sites, cards rendered from the collections — byte-identical DOM, five
reads instead of 141, everything else unchanged:

| | ms | range |
|---|---|---|
| `local` (141 per-instance reads) | 5.154 | |
| `coarse` (5 coarse reads) | **3.854** | [3.788 – 3.938] |
| **`local − coarse` — the read-shape asymmetry** | **1.300** | [1.152 – 1.458] |
| **`coarse − uix` — the gap at MATCHED read shape** | **0.503** | [0.279 – 0.617] |

**1.300 ms is 70% of the 1.858 ms deficit, and it is a read-shape difference
rather than a substrate inefficiency.** The acceptance row is the one census
row where the two arms *cannot spell the same reads*: a hook cannot sit
inside a `for`, so the UIx twin reads whole collections at fixed sites where
the census page reads instances inside the loop. The roster stamps that
asymmetry on every row already; this measures what it is worth.

**And the roster's own second row corroborates it.** On `feed` — 301
boundaries, **603 per-instance reads on BOTH arms** — `rf2-cno31` published
**1.0737×** [0.9669 – 1.1859]. On `large-template` — 141 against 5 — it
published **1.1884×**. Where the read shapes match, the deficit is gone.
This page is the mechanism behind that pair.

## 7. Reproducing every figure — fail-closed

Two merged-PR audits on this instrument family (`rf2-yd52q` #7363,
`rf2-emvod` #7365) found published ensembles that a fresh checkout could not
regenerate, and named the remedy: land the datasets and one fail-closed
command that rebuilds every published aggregate from them. This page ships
both.

```bash
node implementation/freehand/test/re_frame/bench/hicasso/inpage_ladder_aggregate.cjs
```

It reads the four runs' **raw per-sample rounds** and recomputes, per arm,
`n`, `min`, `max`, `p50` and the trimmed mean; then recomputes every named
term of §4 and §5 from those means; then compares each against the figure the
page itself recorded, refusing on any disagreement above 5 × 10⁻⁴. Exit `0`
prints the ensemble table §4 and §5 are read off.

**Proven able to refuse**, because a gate nobody has watched go red is an
advertisement:

| mutation | expected | observed |
|---|---|---|
| a stored aggregate edited (`:deficit 1.9625 → 1.4000` in run A) | REFUSE | **exit 1** — `runA: :deficit stored 1.4 != recomputed 1.9625` |
| a single raw sample edited (`[0 :bare 2.5] → 9.9`) | REFUSE | **exit 1** — `stored max 5.9 != recomputed 9.9`, and the tmean with it |

The second mutation is why `:min` and `:max` are checked at all: the trimmed
mean deliberately discards the extremes, so a corrupted outlier moves neither
the trimmed mean nor the median, and a gate that checked only those two would
pass an edited dataset.

## 8. What this run does NOT have, stated plainly

**The quiet-box gate failed, on seven attempts across eight minutes.** The
requirement is 8 × 1 s samples of total CPU below 30%; the box read
`13,17,30,14,11,26,31,20`, then `15,15,35,15,27,17,11,31`, and five further
attempts in the same range while sibling workers held the machine. The runs
were taken anyway, and the reasons are stated rather than assumed:

- **Every arm is interleaved within each sample index** under the guard's
  reflecting schedule, so box noise is shared across arms rather than
  accumulated on one; and the published quantity is a **difference between
  arms in the same interleave**, which is the statistic least exposed to
  drift.
- **The arm-order guard adjudicated every arm on both partitions** —
  predecessor and phase — and refused nothing, on any of the four runs.
- **The positive control passed on all four runs** under the lane's overlap
  rule (predicted 1.9759×; measured 2.092 [1.842–2.300], 1.713
  [1.489–2.000], 1.863 [1.591–2.193], 1.839 [1.265–2.143]).
- **Under HD-008's stricter rule** — every round inside the ±25% band
  [1.4819 – 2.4699], which `lane/control-verdict`'s own docstring says is the
  right rule — **run D fails and A, B and C pass**. Dropping run D moves no
  conclusion: reads 50% (from 55%), markup 33% (30%), routing 12% (8%),
  shell + memo 4% (2%), read-shape term 72% (70%), `ship / uix` 1.5584
  (1.5543). The ensemble is published whole with run D's control failure on
  its face.

**What is not measured here.** Anything on the `feed` row (301 boundaries,
603 reads on both arms) — the shell and memo-fiber acquittals above are
acquittals **on a one-boundary page** and say nothing about 301 of them.
Anything on the clock of record: this instrument cannot produce a gate row
and does not try.

## 9. The answer, and the honest negative

**Where the 19% is: it is the reads, and the reads are a read-shape
asymmetry.**

1. **55–70% of the deficit is the read term**, depending on whether it is
   measured by ablation (1.025 ms) or by the matched-read-shape
   counterfactual (1.300 ms). It is 141 per-instance cold reads plus the
   141-key commit that installs their cells, against five coarse reads and
   five `useSyncExternalStore` subscriptions.
2. **The term is not a regression and not obviously reducible.** Its render
   half is 2.42 µs/read — `rf2-6c237` already took that from 6.21 to 2.04 and
   named the residue as 90% the substrate's own `compute-sub-with-memo`
   machinery, outside this arm's fences. Its commit half is 0.827 ms, whose
   dominant sub-term `rf2-6c237` priced at ~50% (reaction build + cache
   insert) and declined: escrowing it is a render-phase ref-count mutation
   the state machine forbids (`rf2-2rtt6.25`), and batching the index write
   and the cell-map insert buys tens of microseconds on a 141-key mount.
3. **30% is the interpreter against a compiler**, and the fence forbids the
   remedy. UIx's `$` is compile-time; ours is a runtime walk over a hiccup
   tree we must also *build* (0.291 ms of it). Closing that gap means
   planning the sites at compile time, which is the charter's hard fence.
4. **8% is routing spelling** — 6.26 µs per anchor for the whole `route-link`
   call against 5.50 µs for the twin's hoisted `route-attrs`, on the same two
   published seams. (Neither is comparable to `rf2-cno31`'s 3.38 µs: that is
   the `link-model` micro alone, and these are whole-anchor terms measured by
   removing the anchor's entire routing spelling.) The per-anchor late-bind resolution `route-link` pays and the twins
   hoist is **80 ns × 207 = 0.017 ms**, so it is *not* where the difference
   is; the rest is the `[::h/navigate {…}]` data vector, its intent lowering
   and the props reshaping — the price of the click being data.
5. **2% is the shell and the memo fiber together, and both are
   indistinguishable from zero.**

**So the honest negative, stated plainly.** *Nothing available inside the
fence moves this row much.* The two terms the bead nominated first — the
boundary shell's hooks and the memo wrapper's fiber — are zero on this page
and were always going to be, because the page has one boundary. The one
large term is the price of a read surface that can be called anywhere,
including inside a `for` inside a helper, which is the collector's whole
authoring claim and the thing no hook-shaped surface can spell; **the
comparison charges the candidate for a capability the control does not
have.** The 1.10× line on the acceptance row is therefore, to 55–70%, a
measurement of that capability rather than of substrate efficiency — and the
`feed` row, where both arms spell 603 per-instance reads and the published
ratio is 1.0737×, is the control for exactly that claim.

**No remedy is proposed here, because none was priced that survives the
fences.** The two that would move the number are a compiler (fenced) and a
render-phase escrow of the commit (ruled out on `rf2-2rtt6.25`). What this
page converts is the question: the acceptance row's residual is now a
**known cost with named terms and absolutes**, which is what the ship/kill
decision needs.

## 10. The hook budget, and the fences walked

- **Surface B untouched.** This bead added an instrument and a dataset;
  nothing under `implementation/*/src/` changed.
- **≤2-hook shell untouched.** The `nomemo` and `bare` arms are ablations
  living in the measuring namespace; the shipping shell's ledger is still
  `useContext` + `useSyncExternalStore`, and no arm here adds a hook to it.
- **`subscribe` closes over the read set alone** — untouched.
- **No compiler, no analyzer, no candidate ledger, no ViewCell graph.** The
  frozen trees are two values built once at boot in a bench namespace; they
  plan nothing and are consumed by nothing outside this file.
- **reagent-slim's codec is untouched**, and nothing here caches around it.
