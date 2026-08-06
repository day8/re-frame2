# The census-real pages, on the clock of record

The tier-1 shape roster (`rf2-2rtt6.51`) put four census-real pages on one
shared state layer — RealWorld/Conduit's own screens, sizes predicted by
arithmetic and held there by witnesses — and deliberately published no timing
row. This page takes the roster's **mount** rows through the clock-of-record
door and adjudicates the gated pair against the mount-gate amendment recorded
on `rf2-2rtt6.1` (2026-08-02): **mount ≤ 1.10× direct UIx-on-subs,
floor-normalised, same run, on raw `TaskDuration`** — script AND frame,
frame-settled, plumb-tared, Reagent-on-subs co-instrumented beside the gate and
never a second gate.

Bead **`rf2-2rtt6.56`**. The standard is **`rf2-2rtt6.1`**.

> **THE CANONICAL MOUNT WITNESS IS M1 AND STAYS M1.** The amendment is
> explicit that census-page rows **corroborate** the canonical witness and do
> not silently redefine it. These rows are the diagnostic ladder the roster's
> pages suggest, and the bar rows stay on their own instrument. Nothing here
> re-baselines HD-012, and the ruling on any verdict below is the operator's
> (`rf2-2rtt6.1`), never this page's.

> **RETRACTED, 2026-08-07 (`rf2-2rtt6.62`).** This page previously described
> the large-template and feed rows as *the same screen at two boundary
> decompositions, so shell cost and interpreter cost separate on one page*,
> and drew conclusions from the difference between them. **That isolation was
> never established and is withdrawn** — see [What this page does not
> isolate](#what-this-page-does-not-isolate) below. Every **within-row**
> measurement stands unchanged: each row's arms mount a canon-gated identical
> page, so the per-row verdicts, controls and bands below are exactly as
> measured. What is withdrawn is every **cross-row causal** reading.

## Provenance

| | |
|---|---|
| **Producing commit (authored)** | `7885a7c14896ae3b37e69a9257508afc7770c718` on `worker/census-2rtt6-56`, based on `origin/main` `f57808fb60`. The run executed at exactly this commit — working tree clean, so its stamped blobs are the commit's. The branch was then rebased onto `origin/main` `3bcbaf4323` (post-PR #7378), rewriting it to `5b51627520`; the mapping is checkable because **all thirteen blobs below are byte-identical at both commits** — the rebase brought in only `hd8_*` files and docs, none of them measured here |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/shapes/census_clock_run.cjs` — both adapter runs, the published shape |
| **Build** | `:hicasso-bench` (`--config-merge` entry swap; `implementation/shadow-cljs.edn` untouched) — `:advanced`, `goog.DEBUG false`, cache cleared per `rf2-2rtt6.20`. Build exit `0`, 0 warnings |
| **Runtime** | `HeadlessChrome/147.0.7727.15` (Windows NT 10.0 x64), node `v24.13.0`, hardware-concurrency 24, device-memory 32 |
| **Design** | 6 rounds × 3 blocks × (4 warmup + 10 samples) per arm per row — 18 blocks, the shape the band ceiling was calibrated on (`rf2-ymi6j`) |
| **Clock** | PUBLISHED: `Performance.getMetrics` raw `TaskDuration`, frame-settled (rAF + setTimeout) — main thread only, no raster/composite; CDP does not document its semantics (Chromium accounting read from source, `rf2-8nqsl`). DIAGNOSTIC: `taskNet` (frame-only through this door) and the in-page `flushSync` window, on the same samples |
| **Door** | every arm, plumb tare included: `page.evaluate → C56CLOCK.sample`, frame-settled in-page before the promise resolves; the tare is subtracted from every figure |
| **Guard / band** | arm-order guard tolerance 0.35 on raw `TaskDuration` — **reportable on all six row-runs**; band ceiling 35% |
| **Read-backs** | **0 unverified of 8,316** (every mount element-counted against the roster's arithmetic before release) |
| **Windows** | announced on the bead before opening; quiet-box gate (8 × 1 s < 30% CPU) **QUIET on attempt 1 before all six rows**. `uix` run `2026-08-02T05:57:06Z – 06:00:12Z`; `reagent` run `06:00:12Z – 06:03:53Z`. Sibling `worker/rlink-2rtt6-53-54` held its browser gates out; close announced on the bead |
| **Exit codes** | shakedown build `0`; instrument shakedown at deliberately shallow depth exit `2` (the guard refusing a 1-block design — recorded, nothing published from it); published run exit `0` |

Blob hashes, read at the producing commit. The measured pages' own sources are
pinned alongside the instrument's, because a sibling branch is editing the
shapes tree and a row nobody can tie to the exact page it mounted is
`rf2-cvvb7`'s recorded fault:

| file | blob |
|---|---|
| `…/bench/hicasso/shapes/census_clock_arms.cljs` | `1e38b1a7a4e980122ae7a9aeebb037eb843c313f` |
| `…/bench/hicasso/shapes/census_clock_app.cljs` | `b077ad6a11690fe5ad003e5751b9aa8d573819f8` |
| `…/bench/hicasso/shapes/census_clock_run.cjs` | `1f2a7e1c8bc8d38a8c880a6732d5becf01b7703b` |
| `…/bench/hicasso/shapes/model.cljs` | `d5abb7c05aab198c64d534698a9b0cc3edee74a3` |
| `…/bench/hicasso/shapes/card.cljs` | `d197bf0d6dedeebefe9ad68054fce2a7637f1e68` |
| `…/bench/hicasso/shapes/large_template.cljs` | `f575b78429ba1292a98a355b3ba1a8d3fac5bec6` |
| `…/bench/hicasso/shapes/feed.cljs` | `589291891fc256edc2f6d768ac4169395f13e89c` |
| `…/bench/hicasso/shapes/ordinary.cljs` | `2dea5d0b0b07eff3e768da130185c850045ae4d8` |
| `…/bench/hicasso/arm1/runtime.cljs` | `b5b79f1f8b7d9f306aec813955eeaca8882ec492` |
| `…/bench/hicasso/arm1/lang.clj` | `0151ddafb4aefe6a6a2403a349187ae5b28cc537` |
| `…/bench/hicasso/front/codec.cljs` | `92942efb0bc9eaa3539cde4fcbec1b8408048705` |
| `…/bench/hicasso/lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `implementation/core/src/re_frame/substrate/spine.cljs` | `ad7b19d9d8957e7a1872e58f9b18ace8acdc4841` |

Compact datasets (the reduced quantities every statistic below is a function
of): `implementation/freehand/test/re_frame/bench/hicasso/data/censusclock-2rtt6-56/`.

## The arms, and what each substrate can spell

The roster's pages are the candidate's (`defview` + the ambient collector).
The instrument adds, written out longhand per arm —
`shapes/census_clock_arms.cljs`:

- **floor** — hand-written `createElement` over a plain seeded value; no
  frame, no subscription, one hoisted inert handler. The calibrator, never a
  rival.
- **uix** — the amendment's anchor: `use-current-frame` + two-arity
  `use-subscribe`, primed frame-locked dispatch, `$` compile-time markup.
- **reagent** — `reg-view` components on re-frame2 subscriptions, stock
  Reagent mount door. Co-instrumented; **not a second gate**.
- **ctl-2x** — the floor at twice the row's cards; the chrome does not
  double, so the prediction is each row's own element arithmetic, not 2.00.
- **plumb** — the tare.

Canonical-DOM parity (attribute names sorted) held on **all three rows in both
runs — byte-identical across every non-control arm** (58,474 / 250,997 / 2,636
bytes), at stress AND small size, and the comparison was proven able to answer
false. Two read-shape asymmetries are structural and stamped on every row
rather than hidden:

| row | hicasso | reagent | uix |
|---|---|---|---|
| large-template (1,202 el, **1 boundary**) | 141 per-instance reads | 141 per-instance | **5 coarse reads** — no one-boundary hook surface can spell 141 per-instance reads |
| feed (5,129 el, **301 boundaries**) | 603 per-instance | 603 per-instance | 603 per-instance — the census read pair exactly |
| ordinary (51 el, **7 boundaries**) | 15 (delete-status inside `(when mine? …)`) | 15 | 18 — a hook cannot sit in a branch |

The feed row is therefore the census-real counterpart of the canonical M1
witness — same decomposition, same read shape, real cards — and the cleanest
gated pair on this page.

## What this page does not isolate

The three rows are not one screen at three sizes, and only one variable was
ever meant to move between the first two. In the landed instrument, four move
together:

| row | cards | elements | per-instance reads | boundaries |
|---|---|---|---|---|
| large-template | 69 article cards | 1,202 | 141 | **1** |
| feed | 300 article cards | 5,129 | 603 | **301** |
| ordinary | 5 comment cards (a *different screen*) | 51 | 15 | 7 |

`large_template.cljs` seeds `{:articles 69 :tags 10}` and `feed.cljs` seeds
`{:articles 300 :tags 10}`, through the **same** element arithmetic
(`19 + 10 + 17·articles`). So the step from the one-boundary row to the
301-boundary row is simultaneously a step to **4.35× the cards** — and with
them 4.27× the elements and 4.28× the subscription reads, the two lagging
slightly because the 29-element page chrome does not scale. The boundary count
meanwhile steps by 301×. A shared `card.cljs` and the one-card canonical equality
gate establish that the two pages are built from **byte-identical markup** —
that is markup parity, and it is real. It is not matched workload, and no
timing difference between these two rows can be attributed to boundary
decomposition rather than to size.

**Consequently, everything below is a within-row claim.** Within a row the
comparison is sound and unaffected: all arms mount the identical page, proven
by canonical-DOM equality before any clock is read, so each row's
hicasso/uix ratio, control, band and verdict are exactly as measured. Between
rows this page reports the **ordering of measured numbers** and nothing
causal.

**What would establish it.** Clock both decompositions at one card count — at
minimum the 69 and 300 rungs. That is a seed change (both shapes already take
`{:articles n :tags 10}`) plus a session on a quiet box, and it has not been
run: this repository has published rows taken on a contended box and had to
retract them, so the matched run waits for a real measurement window rather
than being estimated from the absolutes already on this page. Until it exists,
the isolation stays retracted. Tracked on `rf2-2rtt6.62`.

## What this page refuses, up front

The roster's **write rows** (shape 3's broad commit, shape 4's narrow commit)
are refused by construction on this box: bulk-class rows cannot hold a
difference-statistic control at the ~3.5% floor a magnitude needs
(`rf2-7iqb5`, 28–48% within-block IQR), and the narrow class sits on the clock
clamp (`rf2-d2tzk`). The mount rows below are the whole of what this
instrument publishes.

## Results — the gated pair, per row

Every ratio is same-run, same-block, plumb-tared, on raw `TaskDuration`;
ranges over 18 blocks, never a mean alone. Absolutes are printed beside
ratios. The gate line is the amendment's: **≤ 1.10× direct UIx**.

### `uix` run (the gated run)

| row | floor abs p50 | hicasso abs | uix abs | **hicasso / uix** | ctl-2x (pred) | band | **verdict vs 1.10×** |
|---|---|---|---|---|---|---|---|
| large-template | 8.237 ms | 10.777 ms | 8.484 ms | **1.3053× [1.1044 – 1.4660]** | 1.8650 [1.5349–2.0508] vs 1.9759 **PASS** | 6.7% | **FAILS THE LINE** — whole range above 1.10, margin 18.7% clears the band |
| feed | 34.837 ms | 49.832 ms | 43.771 ms | **1.1646× [1.0951 – 1.2445]** | 2.0772 [1.9219–2.3144] vs 1.9943 **PASS** | 6.7% | **INSTRUMENT-LIMITED** — the range straddles 1.10; not a pass |
| ordinary | 1.811 ms | 2.191 ms | 2.018 ms | 1.1248× [0.8939 – 1.3504] | 1.1511 [0.8461–1.5146] vs 1.7255 **FAIL** | 20.8% | **INSTRUMENT-LIMITED** — straddles 1.0 and 1.10, and the magnitude additionally carries the control's failure |

### `reagent` run (co-instrumented)

| row | **hicasso / uix** | hicasso / reagent | uix / reagent | ctl-2x (pred) | band | gated verdict |
|---|---|---|---|---|---|---|
| large-template | 1.2469× [1.0946 – 1.5161] | 1.1081× [1.0239 – 1.3061] | **0.8913× [0.7739 – 0.9835]** | 1.8879 [1.4082–2.3461] vs 1.9759 **FAIL** | 14.0% | INSTRUMENT-LIMITED (straddle + control failure) |
| feed | 1.2159× [1.1362 – 1.2892] | 1.2053× [1.1298 – 1.3024] | 0.9918× [0.9384 – 1.0539] straddles 1.0 | 2.2450 [2.0435–2.5914] vs 1.9943 **FAIL** | 10.7% | INSTRUMENT-LIMITED — whole range above 1.10 but margin 10.5% sits inside the band 10.7%, and the control failed |
| ordinary | 1.1107× [0.9386 – 1.5712] | 1.1002× [0.9278 – 1.4248] straddles 1.0 | 1.0009× [0.8590 – 1.4341] straddles 1.0 | 1.2964 [1.0128–1.4945] vs 1.7255 **FAIL** | 17.8% | INSTRUMENT-LIMITED (straddle + control failure) |

`taskNet` on the same samples reads every gated pair at **0.98 – 1.07** — the
frame halves are equal, and the whole hicasso-vs-UIx gap is **script**: the
runtime hiccup walk. That is the same decomposition the HD-008 re-take found
on its own shapes
([hd8-composed-donor-arm.md](hd8-composed-donor-arm.md#the-re-take-on-the-current-tree-rf2-2rtt631)).

## The headline: one row resolved, and it is the interpreter row

**The large-template row is the one row this instrument could adjudicate
cleanly, and it FAILS the amendment's line**: 1.3053× [1.1044 – 1.4660]
against direct UIx, control PASS, band 6.7%, margin 18.7%. This is the row the
roster built to price the hiccup interpreter with the boundary shell held at
one — 1,202 interpreted elements against a compile-time page — and its own
`taskNet` reading puts the whole gap in **script**, which is what the restated
HD-008 verdict attributes: the deficit lives in the interpreter walk. That
attribution is within-row and stands. The further claim that it *concentrates
where the interpreter term is largest* was a cross-row reading and is withdrawn
(`rf2-2rtt6.62`) — this row is not the feed row with its boundaries rearranged,
it is a smaller page. The caveat is printed on the row itself: the UIx twin reads five coarse
subscriptions where the census page reads 141 per-instance, because no
one-boundary hook surface can spell the census's read shape at all. The row
prices the whole authoring position, not the codec alone.

## What the rows say about the workload leg

The operator's open question: does the candidate's p0 mount deficit —
`1.5001×` vs UIx / `1.4896×` vs Reagent on the M1 witness (`rf2-yd52q`) —
reproduce on census-real screens?

> **THOSE TWO M1 FIGURES ARE NO LONGER PUBLISHED MAGNITUDES (`rf2-jcm3p`,
> ruled 2026-08-06).** M1 mount is stated as a **REGIME**, not an adjudicated
> magnitude: hicasso mounts materially slower than both adapters — every
> corroborated reading sits above the amended `≤ 1.10×` UIx gate — direction
> triple-corroborated (worst-case witnesses, **census rows**, outside
> benchmark), and **`≤ 1.10×` has NOT been demonstrated**. The row's positive
> control fails (`ctl-2x` 1.8173× against a predicted 2.00×, explained by the
> additive constant `c ≈ 1.04 ms`), so no magnitude is published
> ([the clock page's §4](the-candidates-clock.md#4-the-mount-row--a-regime-not-a-magnitude)).
> `1.5001×` and `1.4896×` stay visible here as historical observations
> *stated under a failing `ctl-2x`; withdrawn as magnitudes 2026-08-06*.
> **The rows measured on THIS page are untouched by that ruling** — they carry
> their own controls, adjudicated per row above — and this page is one of the
> three legs corroborating the regime's direction. What changes below is that
> M1 is compared against as a set of *readings*, never as a published
> magnitude.

**At M1's readings, no.** On the census feed — 301 boundaries of 17-element
cards, the read shape M1's 3-element rows share — the same gated ratio reads
**1.1646× [1.0951 – 1.2445]** (control-passing run), and 1.2159× [1.1362 –
1.2892] on the co-instrumented run. The deficit against the floor tells the
same story: hicasso 1.4936× floor vs uix 1.2838× floor (feed), where the p0
gated pair *read* ~1.5× *of UIx itself* — a reading, not a published
magnitude, per the note above.

The element-per-boundary scaling argument in the bead — census cards carry
~6× more interpreter work per boundary, so the interpreter-attributed deficit
should scale up — is **refuted in its magnitude prediction and confirmed in
its attribution**. The gap stays 100% script (taskNet ≈ 1.0) on every row,
i.e. it *is* the interpreter: that is a within-row decomposition, measured
three times, and it stands. The *magnitude* did not grow — the feed row reads
1.16–1.22× where M1 reads ~1.50 — so the candidate's mount deficit is
workload-dependent and the synthetic p0 witnesses sit near its worst case
rather than its typical one.

> **The explanation of *why* it compresses is withdrawn (`rf2-2rtt6.62`).**
> This paragraph previously argued that React's own per-element mount work
> grows faster than the interpreter term, so the interpreter's *share*
> shrinks — and read the large-template row's 1.3053× as that share
> re-expanding "where the shell is held at one and the interpreter term is
> maximal". That is a cross-row causal reading, and the rows do not support
> one: large-template differs from feed in cards, elements and reads as well
> as in boundaries (see [What this page does not
> isolate](#what-this-page-does-not-isolate)). The three ratios remain as
> measured — 1.3053× / 1.1646× / 1.1248× — but which term of the workload
> moves them is unresolved on this instrument.

Beside the gate: on the one-boundary census page **direct UIx beats stock
Reagent outright** — uix/reagent 0.8913× [0.7739 – 0.9835], whole range below
parity — while on the 301-boundary feed the two are at parity (0.9918×,
straddling 1.0), consistent with the HD-008 re-take's parity finding on its
own shapes.

## Predictions, registered before any clock, scored

| | registered | outcome |
|---|---|---|
| P1 | ctl-2x reads below its arithmetic prediction on every row (`rf2-jcm3p`) | **CONFIRMED on large-template** (1.8650 / 1.8879 vs 1.9759) and **on ordinary**, where the additive residual `c` is 0.90 ms on a 1.14 ms tared floor — the constant is most of the signal. **REFUTED on feed** (2.0772 / 2.2450 vs 1.9943): at 10,229 elements the doubled floor costs *more* than the arithmetic — layout 2.06×, style 1.85×, script 2.3× in the decomposition — so the superlinearity of React's own mount at 10k elements outweighs the additive undershoot. Recorded, not smoothed over |
| P2 | direction only: feed hicasso/uix wholly above 1.10 | **CONFIRMED in direction on the reagent run** (whole range above 1.10); **not resolved on the uix run** (range floor 1.0951). The magnitude *growth* the bead's scaling argument implied did not happen — the deficit shrank instead (see the workload leg above) |
| P3 | large-template is the largest hicasso/uix of the three rows | **ORDERING CONFIRMED, REASONING NOT** — 1.3053 > 1.1646 > 1.1248 is what was measured. P3 was registered on the reasoning that the shell is held at one boundary while the interpreter term is maximal; the rows cannot separate that from large-template simply being a different page at a different size (`rf2-2rtt6.62`). The ordering is a fact about three numbers, not evidence for the mechanism that predicted it |
| P4 | the ordinary row sits near this door's floor; if its control or band cannot hold, it publishes a refusal, not a number | **CONFIRMED** — both runs' ordinary controls FAILED (1.1511 / 1.2964 vs 1.7255 predicted); the row's gated magnitudes are published only as instrument-limited non-results carrying the control's failure |

## Refusals and instrument limits, with reasons

- **The ordinary row has no reportable magnitude.** A 51-element census
  screen mounts in ~1–2 ms through a door whose additive per-sample constant
  is ~0.7–0.9 ms of a ~1.2 ms tared floor; the doubling control fails far
  below its predicted 1.7255× on both runs. This is the instrument stating
  its own floor, exactly as registered in P4 — the row needs a finer door
  (or a batched mount window) before it can say anything the amendment could
  adjudicate.
- **The reagent run's controls failed strict on all three rows** (single
  block outliers on large-template and feed; the ordinary floor as above), so
  every reagent-run magnitude above carries the control's failure and none
  adjudicates the gate. The gated verdicts of record come from the `uix` run,
  whose large-template and feed controls PASSED.
- **Write rows refused by construction** (`rf2-7iqb5`, `rf2-d2tzk`) — stated
  in the driver header and above.
- The mount control cannot certify exactness, only page-proportional signal
  (`rf2-jcm3p`); the additive residual `c` is printed per row.

## The verdict, as the bead states it

Against the recorded amendment — 1.10× direct UIx, same run, clock of record,
census rows corroborating M1:

- **large-template: FAILS THE LINE** (1.3053× [1.1044 – 1.4660], control
  PASS, margin 18.7% > band 6.7%).
- **feed: INSTRUMENT-LIMITED** (1.1646× [1.0951 – 1.2445] straddles 1.10 —
  not a pass, and pointing above the line).
- **ordinary: INSTRUMENT-LIMITED with a failed control** — no reportable
  magnitude.

The corroboration cuts both ways and the page says both halves: the candidate
clears nothing here (no row passes the amendment's line), **and** M1's ~1.50×
readings do not transfer to census-real screens, where the measured deficit
across the three rows spans 1.10–1.31×. That span is an observed range over
three *different pages* — not a function of boundary decomposition, which this
instrument does not isolate (`rf2-2rtt6.62`). And the second half is a
comparison of readings, not of magnitudes: M1 publishes a regime rather than a
number (`rf2-jcm3p`, 2026-08-06 — see the note above), and what these rows
corroborate is its **direction**. The ruling on what that means for the
programme is the operator's (`rf2-2rtt6.1`); this page measures.
