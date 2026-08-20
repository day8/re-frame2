# The level witness, armed within one run

Seat: MEASUREMENT RECORD, EP-0039. Bead `rf2-a233t` — the floor arm is multi-modal and
**both modes certify**. The leg witness asks whether a window's legs are alike, and in
both modes they are; it has nothing to say about which of two levels a window sits at.
So a figure quoted from this arm out of a single run has been a coin toss between levels
13–21% apart, and nothing in the rig said so.

Written 2026-08-19 and re-derived 2026-08-20 on `worker/levelwit-a233t`, off
`9476293ae8b087f1125f9a721a339892a01c7b8b`, which is `origin/main` at the time of
writing and therefore an anchor a fresh clone resolves. The three commits between that
and the page's first base `fcb0afb36d` are tracker checkpoints; no dataset and no bench
file differs across them, and every figure below was recomputed on this base.

**NO RUNS WERE TAKEN FOR THIS PAGE.** Every figure below is re-derived from datasets
already committed to this repository. That is not a shortcut: the bead's claim was that
the witness is computable from data already recorded, and taking a window would have
tested a different claim. The one thing a new window could have added — more elevated
readings — the corpus already supplies forty of.

## The answer, first

**THE WITNESS IS ARMED. It is a refusal, it exits non-zero, and its control passes.**

`implementation/hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs` compares,
per segment and inside one run, the level the run **settled** at against the level it
**started** at, and refuses when the step exceeds **5% of the run's own starting level**.

Run over **every admissible floor run this repository has committed** — 101 records
across four corpora and four substrate revisions — it refuses **exactly the 40 elevated
runs and no others**: zero false refusals, zero misses.

**And the definition turned out to be the finding.** The bead warned that the normal
band depends on how the step is defined and told this page to pin a definition before
setting a threshold. It does more than depend on it. The definition `rf2-77gz8`'s
re-analysis quoted — one round against one round, −43 to +158 B normal against
3,912–3,948 B in the mode — **does not separate the populations at all** on the larger
corpus. Its normal band reaches **+2,312 B** while its elevated band descends to
**+860 B**: they overlap by 1,452 B. A bound set from the quoted figures would have
refused legitimate runs, which is the expensive failure this arm was told to avoid.

## What was re-derived before anything was sized on it

`rf2-c4hhk`'s headline was taken as a claim, not a finding, and recomputed from its
committed datasets under its own declared estimator — the median, over certified windows
at round index ≥ 6, of that window's `legMedian`, per segment; elevated when either
segment's estimator is at or above 21,000 B/write; admissible when
`controlVerdict.ok === true` and `verification.unverified === 0`.

| Quantity | `rf2-c4hhk` reported | Re-derived here |
|---|---|---|
| Admissible readings | 69 of 70 | **69** |
| Elevated readings | 37 | **37** |
| ARMED | 18 of 34 | **18 of 34** |
| UNARMED | 19 of 35 | **19 of 35** |
| Records with no `alloc` object | `armed-25` | **`armed-25`, and only that one** |

Every figure reproduces. `armed-25` is excluded and named rather than replaced, exactly
as that page states: Chromium failed to launch, no window was measured, and the driver
wrote a 118-byte record carrying `generatedAt`, `build` and `initFn` and nothing else,
then **exited 1 — the same code as all sixty-nine good runs**.

**THE EXIT CODE IS NOT THE VERDICT FOR THIS RUNNER.** `p0_run.cjs --only alloc` exits
non-zero as a matter of course: it exits on any refused window and on any collection
falling inside a measured one, and both are routine at this page. All seventy of
`rf2-c4hhk`'s runs exited 1 and all twenty of `rf2-77gz8`'s did. **The expected exit code
of a healthy run of this runner is 1.** Admissibility is read off the record's own
fields, and the witness reads the same fields rather than a status code.

## The definition, pinned

Stated before any threshold was chosen, and it is the whole of the design:

| Half | Statistic | Window |
|---|---|---|
| **AFTER** | median of `legMedian` over **certified** windows | round index **≥ 6** |
| **BEFORE** | **minimum** of `legMedian` over **certified** windows | rounds **1–5** |
| **STEP** | `AFTER − BEFORE`, per segment | — |

**AFTER is not a new quantity.** It is the published estimator, verbatim — the one
`rf2-77gz8` pre-registered and `rf2-c4hhk` carried unchanged. The witness therefore
adjudicates exactly the number the records quote, which is the only number a level gate
has any business gating.

**BEFORE is a minimum, and the asymmetry is deliberate.** The error available to an
early round is one-signed: those rounds carry warm-up allocation the settled rounds do
not, so an early window can read above the run's floor and cannot read below it. A
window reading below its cohort would already have been refused by the leg witness — *"a
leg BELOW its cohort is a leg something removed bytes from, and nothing in the work unit
removes bytes; the collector does"* — so a certified window is not available to be
spuriously low. The minimum of the certified early windows is therefore the best estimate
of the level the run started at, and a median over as few as one or two windows is not:
**five runs read a NEGATIVE step under the median form, the worst at −1,014 B**, every
one of them a normal run with a single inflated early round inside a two-window median.

Named, because a count is not checkable and a list is. Each row is that run's certified
rounds 1–3 — two windows in every case, which is what makes one inflated round decisive:

| Run | Segment | Certified r1–3 | Step under the median form |
|---|---|---|---|
| `alloc-c4hhk/unarmed-35` | `uix-subs` | r1 21,688 · r3 19,420 | **−1,014 B** |
| `alloc-77gz8/run20` | `uix-subs` | r2 19,930 · r3 19,444 | −147 B |
| `workcount-n1b9h/run6` | `reagent-subs` | r1 19,316 · r2 19,004 | −60 B |
| `alloc-9jrhi/bisect-3-b` | `reagent-subs` | r1 19,586 · r2 19,256 | −43 B |
| `workcount-n1b9h/run2` | `reagent-subs` | r1 19,610 · r3 19,208 | −31 B |

Under the pinned minimum every one of these reads its lower window and steps positive.

Round 0 is outside the window: it is the prime round and is uncertified in every run in
the corpus.

**Rounds 4–5 are inside the BEFORE window even though they are the transition**, because
a minimum cannot be raised by them and the coverage is free. Measured over every
admissible run, the BEFORE minimum came from rounds 1–3 in **201 of 202 segment-halves**
— the ramp is never the lowest certified window when a pre-ramp one exists. The one half
where it is, `alloc-c4hhk/armed-03`'s `reagent-subs`, has *none* of rounds 1–3 certified;
under a rounds-1–3 window that run has no BEFORE at all and the witness must refuse a
run whose level is not elevated. Under this window it is scored, at +0.494%, and
certifies.

That fallback is disclosed rather than hidden. When it happens on an elevated run it
sits *between* the levels and so **understates** the step — it biases toward missing a
mode run, never toward refusing a normal one. Forcing every elevated run in the corpus to
that worst case, 38 of 40 still exceed the bound and two would slip. `beforeFromRamp` is
set on any reading scored that way, and **no elevated run in the corpus is one**.

## The bands, under every definition the corpus has used

Each definition scored over the same admissible runs, taking each run's worst segment.
The byte band and the fraction band are each the minimum and maximum over that
population, so the two extremes need not come from the same run.

| Definition | Normal (n=60) | Elevated (n=40) | Gap | Undefined on |
|---|---|---|---|---|
| **`min(cert r1–5)` → `median(cert r≥6)` — PINNED** | 96..194 B · 0.494–1.015% | 2,616..3,984 B · 13.766–21.070% | **+2,422 B** | 1 run |
| `median(cert r1–3)` → `median(cert r≥6)` | 96..168 B · 0.494–0.887% | 2,616..3,948 B · 13.516–20.840% | +2,448 B | 2 runs |
| `median(cert r1–3)` → `median(cert r4–6)` | 90..896 B · 0.456–4.653% | 1,655..4,083 B · 8.634–21.553% | +759 B | 1 run |
| `cert r3` → `cert r4`, one round each | 80..**2,312** B · 0.411–12.228% | **860**..4,946 B · 4.428–26.026% | **−1,452 B (OVERLAP)** | 95 runs |

**The bottom row is the finding this page owes the bead.** It is the form whose bands the
bead quoted from `rf2-77gz8`, and on 101 runs rather than 27 it is not a discriminator:
its normal population reaches 12.2% while its elevated population descends to 4.4%. It is
also undefined on at least one segment in **95** of the 101 admissible runs and on both
segments in **16** of them, because a single round is not always certified — a definition
that cannot be evaluated is not a gate.

The `median(r4–6)` row separates but keeps only 759 B of gap, because rounds 4–5 sit in
its AFTER half where they belong to neither level.

The pinned form and the `median(r1–3)` form are within 26 B of each other on gap. The
pinned one is chosen for **coverage** — it scores one more run — and for the negative-step
argument above, not for width.

## The bound

**5% of the run's own BEFORE level**, which is about 950 B at this arm's level.

It is a **fraction rather than a byte count** so that it carries no constant tied to this
plan's roots and cells: a plan sitting at a different level is gated at the same relative
strictness, and no one has to remember to re-derive a threshold when the level moves.

| | Value | Distance from the bound |
|---|---|---|
| Largest step the normal population has produced | **1.015%** (192 B, `alloc-c4hhk/unarmed-06`) | bound is **4.9×** above it |
| Largest normal step in bytes | **194 B** (`alloc-9jrhi/bisect-7`) | — |
| Smallest step the elevated population has produced | **13.766%** (2,616 B, `alloc-c4hhk/armed-18`) | bound is **2.75×** below it |
| Largest elevated step | **21.070%** (3,984 B, `alloc-9jrhi/bisect-1`) | — |

Nothing was fitted. The number is one twentieth; the corpus was consulted only to check
that both populations sit far from it, and it is biased toward the loose side because a
refusal that fires on a legitimate run is the expensive error here.

### The bound does not depend on the mode's rate

`rf2-6kxub` measured the elevated mode at **0%, 10% and 53% across three windows at one
revision**, on one instrument, with both arms moving together. A witness calibrated
against an assumed rate would be calibrated against that unstable number.

**This one is not, and the independence is structural rather than lucky.** Every quantity
above is a comparison inside a single run, between two windows of that run's own rounds.
No step is compared against another run, no threshold is derived from how often the mode
appears, and no count of runs enters the rule. A window of one run and a window of
seventy are adjudicated identically, and a window taken on a day when the mode never
appears is scored by the same rule as one taken on a day when it appears in half the
runs. The rate governs *how often the witness fires*; it does not enter *when* it fires.

## The decision: a refusal, not a recorded verdict

The bead asks which of two this becomes — a **refusal** that exits non-zero like the leg
witness, or a **recorded verdict** that gates only what may be quoted — and frames the
trade honestly: the arm's figures are load-bearing for the published allocation series,
so a refusal firing on a legitimate run is expensive, while a verdict nobody reads is
useless.

**It is a refusal.** Three things decide it, and the first is the one that actually moves
the argument.

**A recorded verdict is what already exists, and it is what failed.** Every byte this
witness reads was already in the record. The per-window certificate was already computed,
already written, already carried in every dataset — and both modes still certified, and
figures from this arm were still quoted out of single runs. That is the defect the bead
is named for. Adding a second advisory number to a record that already contained the
answer is not a fix; it is the same failure with more provenance.

**The expensive-refusal worry is about a gate inside the rig, and this is not one.** A
refusal that fires wrongly *during a bench window* costs a re-run: ninety minutes of
browser time, a fresh box, and a plan that has to be re-declared. This refusal fires over
a **written record**, after the fact, from a command that takes seconds. A false refusal
here costs a re-read and an argument, not a window. That asymmetry is what makes the
strict form affordable, and it is a direct consequence of the bead's own constraint that
the witness be analysis-side.

**And the false-refusal rate is measured rather than assumed.** Zero, over 100 scored runs
across four corpora and four substrate revisions, with the bound sitting 4.9× above the
worst normal step the corpus has ever produced. The bead's "expensive" is a real cost
attached to a rate, and the rate is the part that was unknown when the bead was filed. It
is no longer unknown.

### What would change this

Stated now, so it is a prediction rather than a defence:

- **An intermediate level.** The bound is blind between 1.015% and 13.766%. The corpus
  contains nothing there, and the two-population model is what licences a hard threshold.
  A run that settles in that gap falsifies the model, and the right response is to widen
  the record — not to nudge the bound until the awkward run passes.
- **A single false refusal on a run that is demonstrably not elevated.** One is enough to
  reopen this, because the whole cost argument above rests on a rate of zero.
- **The ramp-fallback hole biting.** Two of forty elevated runs would slip if their
  rounds 1–3 all failed to certify. None does today. If one ever does, the fallback needs
  closing rather than disclosing.
- **A second instrument.** Every figure here comes from one rig, unchanged since
  `408dfb0aa8`. If the arm is ever measured another way and the levels do not reproduce,
  this witness is measuring the instrument and not the arm.

`rf2-6kxub` is deliberately **not** on that list. The mode's rate swinging 0% → 10% → 53%
changes how often this fires; it does not change whether the rule is right, because no
quantity in the rule is derived from a rate.

## The control

The claim that licences arming this at all is that the bound refuses exactly the elevated
runs and nothing else. Every dataset it rests on is committed, so it is re-derived by
`alloc_level_witness.test.cjs` on every run of that gate rather than asserted here once.

| Corpus | Records | Inadmissible | Scored | Elevated | Refused | False refusals | Misses | Not computable |
|---|---|---|---|---|---|---|---|---|
| `alloc-c4hhk` | 70 | 1 | 69 | 37 | 37 | **0** | **0** | 0 |
| `alloc-77gz8` | 20 | 1 | 19 | 2 | 2 | **0** | **0** | 0 |
| `alloc-9jrhi` | 8 | 1 | 6 | 1 | 1 | **0** | **0** | 1 |
| `workcount-n1b9h` | 6 | 0 | 6 | 0 | 0 | **0** | **0** | 0 |
| **Total** | **104** | **3** | **100** | **40** | **40** | **0** | **0** | **1** |

Within `alloc-c4hhk` the refusals split **18 of 34 armed** and **19 of 35 unarmed** — the
same split the arming window reported, which is the expected result of a witness that
reads levels and is blind to which bundle produced them.

**The three inadmissible records, named.** `alloc-c4hhk/armed-25` carries no `alloc`
object. `alloc-77gz8/run12` and `alloc-9jrhi/bisect-5` fail their positive control. Each
is excluded by the record's own criteria, not by this witness, and each is reported
rather than skipped.

**The one run that cannot be scored**, `alloc-9jrhi/pilot-rounds6`, is a six-round pilot.
It has no round ≥ 6, so **the published estimator does not exist for it either**;
refusing it and quoting nothing from it are the same statement, and the refusal carries
its own code (`level-window`) so it is never confused with a level step.

### The controls that show the gate bites

A gate nobody has watched cross its own threshold is a gate nobody has watched. Both
directions are asserted in the test:

- Loosened to **25%**, the witness refuses **nothing** and all 40 elevated runs become
  misses.
- Tightened to **0.4%**, it refuses **all 60** normal runs.
- At the shipped bound, one byte past 5% refuses and exactly 5% certifies.

And a planted mutation shows the gate reaches the code it claims to. Turning BEFORE's
minimum into a maximum — one character, `<` to `>`, at the `reduce` that picks the lowest
certified early window — takes the gate from **9/9 to 3/9**, and every one of the six
reds is specific rather than incidental:

- the two fixtures that pin the minimum fail *by name* — "certified ramp rounds do not
  raise BEFORE" and "one inflated early round does not raise BEFORE";
- `alloc-c4hhk/armed-13` and `alloc-9jrhi/bisect-1` become **newly-missed elevated runs**,
  which is the failure the bound exists to prevent;
- the refusal count falls 40 → 38, the worst normal step inverts to −216 B, the
  ramp-fallback reading count goes 1 → 99, and the 0.4% tightening control collapses
  60 → 6.

The file was then restored and its content hash compared against the committed object —
`3dd95e0c268db8b517c802142b2a37de0acbae67` both before the plant and after the restore —
and the gate re-run green at 9/9. A green run under sabotage would have meant the gate
was not reading this tree; it was not green.

## The rig is not touched

Nothing under `implementation/core/test/re_frame/bench/` is modified, and nothing this
page adds is compiled into the bundle the arm builds. The witness reads committed records
and never writes one.

That is a requirement rather than a convenience. **No bench file has changed since
`408dfb0aa82ee97aee47753660b05ae99d8fab0f`**, which is what lets `rf2-6kxub` compare rates
across three windows and what lets `rf2-c4hhk` claim its window ran the instrument that
produced the `alloc-77gz8` datasets. A witness soldered into `p0_run.cjs` would end that
constancy and would put a gate inside the rig whose invariance the published series rests
on.

| File | Blob at this page's base | Blob at `rf2-c4hhk`'s base |
|---|---|---|
| `core/test/re_frame/bench/p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` | `ce6363ff774d8049c07b58513d708687a73e937e` |
| `core/test/re_frame/bench/p0_heap.cljs` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` |
| `core/test/re_frame/bench/p0_workcount.cljc` | `033f00470c380a17664a1dabffa0768f0e22c671` | `033f00470c380a17664a1dabffa0768f0e22c671` |
| `core/test/re_frame/bench/p0_fixture.cljc` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` |
| `core/test/re_frame/bench/p0_floor.cljs` | `3a14ff96414f9a77a7612f56181444155b582620` | `3a14ff96414f9a77a7612f56181444155b582620` |

Every one is byte-identical to the table on
[Does arming the census move the high level?](does-arming-the-census-move-the-high-level.md).

## The corpus this was calibrated against

All datasets are committed under
`implementation/hicasso/test/re_frame/bench/hicasso/data/`. The plan is identical across
every one of them — `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`,
`P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18`, so B = 24 — except the six-round pilot noted
above. Every figure is a browser figure: `:advanced`, real Chromium under playwright,
`--enable-precise-memory-info`, in-page `performance.memory.usedJSHeapSize` sampled at
every leg boundary. The runtime is the one each record carries; no run was re-taken.

| Corpus | Records | Substrate revision(s) | Census |
|---|---|---|---|
| `alloc-c4hhk/` | 70 | `4a1537cb717dc6660aa449642f198a2cc970c93b` | 35 armed, 35 unarmed, strictly alternating |
| `alloc-77gz8/` | 20 | `4a1537cb717dc6660aa449642f198a2cc970c93b` | all armed |
| `alloc-9jrhi/` | 8 | `4a1537cb71`, `a158c40288`, `48c715f97c`, `9d20be1d00`, and `88411ed803` for the two named `head` runs | unarmed |
| `workcount-n1b9h/` | 6 | `4a1537cb717dc6660aa449642f198a2cc970c93b` for runs 3–6; runs 1–2 were taken at that page's own HEAD, which its record does not pin and neither does its dataset | all armed |

That the witness holds across every substrate revision the corpus contains, and across
both census arms, is worth stating: the levels it discriminates are a property of the
arm, not of a build.

## Reproduction

From `implementation/`, on this page's base
`9476293ae8b087f1125f9a721a339892a01c7b8b`. Nothing here opens a browser; the whole
control is a read over committed JSON and takes a few seconds.

```bash
# the full corpus control, printing the bands and every exclusion by name
node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs --corpus

# the fixtures alone
node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs --self-test

# fixtures plus the corpus control plus the loosen/tighten mutation proofs
node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.test.cjs

# one run, or a window's worth: exits non-zero on any refusal
node hicasso/test/re_frame/bench/hicasso/alloc_level_witness.cjs \
  hicasso/test/re_frame/bench/hicasso/data/alloc-77gz8/run09-a4a1537cb71.json
```

`run09` is the worked example the bead cites. It prints:

```text
;;   reagent-subs   before     18950 @r 1 (n=4)  after     21632 (n=10)  step   +2682 B   14.153%
;;   uix-subs       before     19372 @r 2 (n=4)  after     22072 (n=10)  step   +2700 B   13.938%
;;   REFUSED [level-step] reagent-subs: settled at 21632 B/write against 18950 B/write …
```

## What this does not settle

- **It does not explain the mode.** Nothing here says what the elevated level *is*. The
  witness refuses a contaminated reading; it does not diagnose one. `rf2-6kxub` holds the
  open question of what the rate depends on.
- **It does not gate a run at the moment it is taken.** The refusal is over a written
  record, so a window still has to invoke it. That is the deliberate cost of leaving the
  rig alone, and the invocation is one line.
- **It is not registered in the fast-PR spine.** `alloc_level_witness.test.cjs` follows
  `clock_witness.test.cjs`'s shape exactly and belongs in `test:script-helpers`, but that
  list lives in `implementation/package.json`, outside this change's fence. Filed as
  `rf2-zu82n`; until it lands, the gate runs by name.
- **The ramp fallback is a real hole, bounded rather than closed.** A segment whose
  rounds 1–3 all fail to certify is scored from a ramp round and its step is understated.
  No elevated run in the corpus is scored that way, and two of forty would slip if one
  were. The reading is marked; it is not repaired.
- **The bound rests on 40 elevated readings, 37 of them from one window on one day.**
  `rf2-6kxub` established that the rate is not a property of the revision, so the
  *levels* seen in that window may not be the only ones the arm has. A fourth level at
  +96 B on `new` is already recorded there; it is far inside the refusal region, but a
  level between 1.015% and 13.766% would be invisible to this bound and nothing here
  excludes one.
