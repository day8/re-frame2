# Does arming the census move the high level?

Seat: MEASUREMENT RECORD, EP-0038. Bead `rf2-c4hhk` — the floor arm is **multi-modal**,
and the elevated level has only ever been seen at **+2,532 B/write with the work census
ARMED** and at **+3,792 B/write with it UNARMED**. So the counter built to explain the
modes may be moving the level it was built to read, and if it is, **every high-mode
reading in the corpus is instrument-contaminated**.

Written 2026-08-19 on `worker/armconf-c4hhk`, off `7fbfa8bc96660f4124f7dfa2c86a84374486801e`,
which is `origin/main` at the time of writing and therefore an anchor a fresh clone resolves.

**This page is a MEASUREMENT WINDOW with a stopping rule fixed in advance.** The run
count below was written down and committed *before the runner was invoked once*, and
exactly that many runs were taken regardless of what they showed. The mode's low rate
makes the stop-when-it-looks-good failure the live one here, not a theoretical one.

## The answer, first

**ARMING DOES NOT MOVE THE LEVEL, and the confound is refuted from BOTH directions at
once.** The hypothesis required every armed high to sit at +2,532 and every unarmed high
to sit at +3,792. Both halves fail:

- **The +2,532 level occurs UNARMED.** Nine unarmed runs read **exactly 21,632 / 22,072**,
  the level the confound said only an armed build could produce. The literal, un-amended
  byte-equality rule fires on these, so **the verdict does not rest on the amendment**
  below.
- **The +3,792 level occurs ARMED.** `armed-13` reads **22,884 / 23,324** — 8 B from the
  level on both segments, 1,252 B from the other — the level the confound said only an
  unarmed build could produce.
- **An armed run and an unarmed run trace the elevated ramp BYTE-IDENTICALLY.** `armed-06`
  and `unarmed-05` agree on **ten consecutive rounds** of `reagent-subs` and **eleven** of
  `uix-subs`, across the step and through the settle. Not merely the same level: the same
  curve, to the byte, on two different bundles.
- **The low level is byte-identical across arms too** — 19,100 / 19,540 in all 32 low
  runs, 16 in each arm — which independently re-confirms `rf2-n1b9h` on a larger sample.

**So `rf2-77gz8`'s high-mode readings are NOT instrument-contaminated**, and neither is
any other high-mode reading in the corpus. The +2,532 and +3,792 levels are genuinely
distinct levels of the arm, and arming gates neither of them.

**Two things this window did not expect and found anyway.**

1. **THE MODE'S RATE IS NOT A PROPERTY OF THE REVISION.** It ran at **53%** today against
   `rf2-77gz8`'s **10%** and `rf2-n1b9h`'s **0%**, on the same revision, the same
   instrument and the same plan. Crucially **both arms moved together** — 52.9% armed
   against 54.3% unarmed — which is what an environment effect predicts and what an
   arming effect does not. Any future sizing that treats ~10% as the rate is sizing
   against a number that is not stable.
2. **THE ELEVATED LEVEL HAS FINE STRUCTURE.** Of 37 high readings, 19 sit exactly on
   21,632 / 22,072, nine at −12 B, five at +96 B, and the rest within 8 B — and the offset
   is **identical on both segments in 34 of 37**, so the fine structure is page-global in
   the same way the coarse step is. Under the pre-declared ±64 B margin the five +96 B
   readings classify as a FOURTH level; they appear in **both** arms (3 armed, 2 unarmed),
   so they are a property of the arm and not of the build.

## Pre-registration

**Declared before the first run, and committed in this file before the runner was
invoked once.**

| Field | Value |
|---|---|
| **Run count** | **70 runs — 35 ARMED and 35 UNARMED — taken regardless of outcome** |
| Order | strictly **alternating**, beginning ARMED, so no time-varying property of the machine aligns with an arm |
| Substrate revision | `implementation/core/src` at `4a1537cb717dc6660aa449642f198a2cc970c93b`, an ancestor of `origin/main` |
| Plan | `P0_ALLOC_PLAN=floor`, `P0_ALLOC_WRITE=all`, `P0_ROOTS=4`, `P0_ALLOC_CELLS=6`, `P0_ALLOC_ROUNDS=18` (so B = 24) — identical to `rf2-77gz8`'s window |
| **The one varied factor** | `P0_WORK_COUNT=1` on the armed arm, **unset** on the unarmed arm. Nothing else differs between the arms — not the plan, not the port, not the revision, not the rig. |
| Estimator | the **median**, over **certified** windows at **round index ≥ 6**, of that window's `legMedian` — per segment. `rf2-77gz8`'s estimator, unchanged. |
| High-mode criterion | either segment's estimator at or above **21,000 B/write**. `rf2-77gz8`'s criterion, unchanged. |
| Admissibility | `controlVerdict.ok === true` **and** `verification.unverified === 0`. The runner's **exit code is not a criterion** — see [Admissibility](#admissibility). |
| Stopping rule | **exactly 35 per arm**; the series does not stop early on a positive and does not extend on a null |

### The decision table, declared in advance

The levels are page-global and discrete, so the discriminator is a **byte comparison of
the estimator pair** against the two levels already in the corpus. Declared before any
run so that no reading is classified after the fact:

> **AMENDED AT RUN 3 OF 70, AND THE DISCLOSURE IS THE POINT.** The rule as first
> written asked whether a pair "reads 21,632 / 22,072" — an exact byte equality — and
> that is under-specified, because a settled level in this corpus carries a small
> jitter: `rf2-77gz8`'s `run11` reads 19,110 against the same level's 19,100 in the
> other eighteen. **What I had seen when I amended it**: three runs taken, of which
> `unarmed-01` reached the mode and read **21,620 / 22,060** — 12 B from the +2,532
> level on both segments and 1,272 B from the +3,792 level on both. So the literal
> rule classified nothing and the amendment was forced. **The amended rule**: a
> high-mode pair is assigned to the **nearest** corpus level, and the assignment
> stands only if the pair sits **within ±64 B of that level on both segments and more
> than 256 B from every other**; otherwise the run is reported as a FOURTH LEVEL and
> the two-level comparison is refused for it. ±64 B is about five times the largest
> jitter the corpus shows and about a twentieth of the smallest gap between levels
> (1,260 B), so it cannot reach across. Amended with 67 of 70 runs unrun, committed
> before they were taken, and the verdict below is read under the amended rule.

| Observation | Verdict |
|---|---|
| Both arms reach the mode; **every** armed high reads 21,632 / 22,072 and **every** unarmed high reads 22,892 / 23,332 | **ARMING SHIFTS THE HIGH LEVEL.** The two are ONE level under two builds, and every high-mode reading in the corpus — `rf2-77gz8`'s included — is instrument-dependent |
| **Any** unarmed high reads 21,632 / 22,072 | **THE LEVELS ARE DISTINCT.** Arming does not gate the +2,532 level; the arm has at least three levels of its own |
| **Any** armed high reads 22,892 / 23,332 | **THE LEVELS ARE DISTINCT.** Arming does not gate the +3,792 level either |
| A high appears in **one arm only** | **NULL ON THE COMPARISON — a refusal.** Report both arms' realised rates and state what the null bounds, and no more |
| **No** high in either arm | **NULL — a refusal.** Same |
| A high reads a level in neither set | **A FOURTH LEVEL.** Report it and refuse the two-level comparison |

### Why 35 per arm

The rate was **re-derived from the committed corpus**, not taken from prose. Every
18-round floor run ever committed at `4a1537cb71`, scored by the estimator above:

| Source | Runs | Arm | High-mode runs |
|---|---|---|---|
| `alloc-9jrhi` — `bisect-1`, `bisect-5`, `bisect-6` | 3 | unarmed | **1** (`bisect-1`) |
| `workcount-n1b9h` — `run3` … `run6` | 4 | armed | 0 |
| `alloc-77gz8` — `run01` … `run20` | 20 | armed | **2** (`run09`, `run19`) |
| **Total at this revision** | **27** | — | **3** |

So the pooled rate at the only revision the mode has ever appeared at is **3 in 27
(11.1%)**, or **3 in 25 (12.0%)** restricted to runs with a passing control. Note this
is *not* the 1-in-7 figure `rf2-77gz8` sized against: that was the corpus **before**
its own twenty runs were added, and adding them dilutes it.

**The binding constraint is the ARMED arm's own rate**, which is the lower of the two:
**2 in 24 (8.3%)** armed against **1 in 3** unarmed — and the unarmed figure rests on
three runs, so it is nearly uninformative and is not sized against.

At the armed rate of 8.7% (2 of 23 admissible), 35 runs give
P(at least one high) = 1 − (1 − 0.087)³⁵ ≈ **96%**, and **P(both arms reach it) ≈ 92%**.
At the pooled 12% the same 35 gives ≈ 99% per arm. A run costs about 62 seconds
including its `:advanced` build — re-derived from the `generatedAt` spacing of
`rf2-77gz8`'s twenty datasets — so 70 runs is roughly 75 minutes.

**Both arms must reach the mode for the comparison to exist.** A series long enough for
one arm and not the other answers nothing, which is why the count is symmetric and why
the null is pre-declared as a refusal rather than as a bound on the effect.

## The reading

**Seventy runs were taken. Exactly seventy.** Sixty-nine produced a reading; the
seventieth is dealt with under [Admissibility](#admissibility) and is not replaced.

| Arm | Readings | High-mode | Rate | `new` (+2,532) | `high` (+3,792) | FOURTH (+96 on `new`) |
|---|---|---|---|---|---|---|
| ARMED | 34 | 18 | 52.9% | 14 | **1** | 3 |
| UNARMED | 35 | 19 | 54.3% | 17 | 0 | 2 |

Every high-mode estimator pair observed, with its offset from the nearest corpus level:

| Pair (`reagent-subs` / `uix-subs`) | Level | Offset | ARMED | UNARMED |
|---|---|---|---|---|
| 21,632 / 22,072 | `new` | 0 / 0 | 10 | 9 |
| 21,620 / 22,060 | `new` | −12 / −12 | 3 | 6 |
| 21,640 / 22,072 | `new` | +8 / 0 | 1 | 1 |
| 21,632 / 22,076 | `new` | 0 / +4 | 0 | 1 |
| 21,728 / 22,168 | FOURTH | +96 / +96 | 3 | 2 |
| 22,884 / 23,324 | `high` | −8 / −8 | **1** | 0 |
| 19,100 / 19,540 (low) | `low` | 0 / 0 | 16 | 16 |

**Both pre-declared "the levels are distinct" branches fire**, and either alone would
settle it. Nine unarmed runs read the +2,532 level exactly; one armed run reads the
+3,792 level within 8 B on both segments. The branch that would have shown contamination
required *every* armed high at one level and *every* unarmed high at the other, and it is
falsified twice over.

### The same curve, on two different bundles

The sharpest form of the result is not the tally but a pair of individual runs. `armed-06`
and `unarmed-05` were taken nine minutes apart, one with the census compiled into the
write path and one with it folded away, and they trace the elevated ramp **byte for
byte**:

| Round | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `reagent-subs` ARMED | 19,928 | 19,876 | 21,804 | 21,796 | 21,772 | 21,648 | 21,648 | 21,632 | 21,632 | 21,632 | 21,632 |
| `reagent-subs` UNARMED | 19,910 | 19,876 | 21,804 | 21,796 | 21,772 | 21,648 | 21,648 | 21,632 | 21,632 | 21,632 | 21,632 |
| `uix-subs` ARMED | 20,316 | 20,620 | 22,244 | 22,236 | 22,128 | 22,096 | 22,072 | 22,072 | 22,072 | 22,072 | 22,072 |
| `uix-subs` UNARMED | 20,316 | 20,620 | 22,244 | 22,236 | 22,128 | 22,096 | 22,072 | 22,072 | 22,072 | 22,072 | 22,072 |

Ten consecutive identical rounds on `reagent-subs` (5–14) and eleven on `uix-subs`
(4–14), through the step and into the settle. A counter that moved the level could not
produce this.

### The census, where it exists

The armed arm carries the census; the unarmed arm cannot, by construction, and this bead
is about the LEVEL only. Across the 34 armed readings the census takes **one value** in
**1,224 arm windows** — `events = 7, subs = 0, renders = 0` — with **zero** control
windows moved and **zero** windows off the expected count. That includes `armed-13`, the
one +3,792 run, which reads `7 / 0 / 0` on both sides of its own step exactly as
`rf2-77gz8`'s two +2,532 runs did. **So the work-count exclusion `rf2-77gz8` established
at the +2,532 level now also holds at the +3,792 level**, which is a small extension of
that finding rather than a new one, and it carries the same limit: on this arm `subs` and
`renders` are structural zeros, so the census rests on one counter summed over seven
writes.

## Admissibility

`--only alloc` **exits non-zero as a matter of course** — it exits on any refused window
and on any collection falling inside a measured one, and both are routine at this page.
All twenty of `rf2-77gz8`'s runs exited 1, including the nineteen it accepted and the one
it excluded. **The exit code is therefore not the verdict here**, and neither is it
evidence of one. Admissibility is decided by the three things the driver's own header
names as exit-bearing checks and this window reads out of the committed record instead:

1. the **positive control**, adjudicated by `lane/control-verdict` — `controlVerdict.ok`;
2. the **read-back verification** — `verification.unverified`, which must be 0;
3. the **per-window certificate** — only windows with `certified: true` enter the
   estimator, which is inside the estimator rather than a run-level gate.

A run failing (1) or (2) is **excluded and named**, never silently replaced.

### What actually happened, and it makes the point better than the argument does

**All seventy runs exited 1.** **Sixty-nine of them are admissible** — every one passes
its positive control and every one reads zero unverified read-backs. Not a single run was
excluded on either gate.

**`armed-25` produced no reading at all.** Chromium failed to launch (`exitCode
3221225794`, i.e. `0xC0000142` — a transient Windows loader failure), no page was served,
no window was measured, and the driver wrote a 118-byte record containing `generatedAt`,
`build` and `initFn` and **no `alloc` object whatsoever**. It then exited **1** — the same
code as all sixty-nine good runs.

That is the cleanest demonstration in this corpus of why a runner's exit code cannot be
the verdict. A reader scoring this window by exit code would count seventy readings and
have sixty-nine, and the missing one is invisible in the code and obvious in the record.
**It is committed rather than deleted**, because the file is the evidence of the
exclusion, and **it is not replaced with a seventy-first run**: the stopping rule counted
runs TAKEN, and thirty-five were taken on each arm.

**One disclosure that is not a criterion.** `lane/control-verdict` adjudicates on
**overlap** — whether the measured range contains a value within slack of the prediction
— not on the point estimate. `rf2-egdaq`, the ruling on whether it tightens, has since
been SETTLED, and neither half of it reaches this row's criterion; see below.
Six of the sixty-nine runs pass with a mean `perDouble` above 9 against the predicted 8
(the loosest is 11.06); the other sixty-three sit at 8.08–8.11. Four of the six loose runs
read high and two read low, against 33 of 63 high among the tight ones, and the loose runs
fall in both arms. **`controlVerdict.ok` was the pre-declared criterion and it is the one
applied**; the point estimates are published here so a reader adjudicating under a future
stricter rule can re-score this window without re-running it.

### The rule since settled: a split, and what it does not reach (rf2-egdaq)

`rf2-egdaq` was open when this window was published and is **no longer open**. The answer
is a **SPLIT** — one rule per instrument, not one rule for both arms — landed in PR #8574
and completed by the operator's records ruling of 2026-08-21.

- **The HEAP arm went STRICT.** `re-frame.bench.p0-heap`'s positive control is adjudicated
  by `lane/control-verdict-strict`: EVERY ROUND inside the ±25% band. On a byte counter
  overlap is not a weaker gate but an absent one — the failure this control exists to
  catch is a collector that has stopped seeing transient garbage, and its shape is a round
  reading ~0 B, which under overlap PASSES, because `min` 0 sits under the roof while
  `max` ~4,700,000 sits over the floor and a good round vouches for a dead one.
- **The CLOCK arm REFUSED strict, and that refusal STANDS.** `re-frame.bench.p0-app`'s
  control is a clock RATIO, and the 2026-07-31 quantum ruling keeps **overlap** where the
  legs sit within a few of Chrome's 100 µs `performance.now()` quanta and a low round is
  the clock's resolution rather than a defect. Eighty controls re-adjudicated from the
  committed record read **80 of 80** under overlap and **64 of 80** under strict, every
  miss LOW and every one on the two rows whose legs are one to three quanta wide, while
  the 20-plus-quantum rows pass strict 40 of 40 —
  [the breakdown](p0-converged-witness-set.md#the-strict-reading-over-eighty-controls-rf2-egdaq).
  Nothing here reopens it, and the split is the point.

**The ten published heap-control figures are RE-ADJUDICATED under strict, and all ten
pass.** That is the operator's 2026-08-21 call, taken so that the published evidence and
the current rule agree with no two-rules asterisk. **No window was re-run**: a published
`[min–max]` whose two ends both sit inside the band bounds every round inside it, so the
committed records settle it as they stand. The widest excursion either way across that
series is **4,690,838 B against a prediction of 4,700,000 B — 0.195% low, against a band
of ±25%** and better than two orders of magnitude inside it. Nothing flips.

**None of that moves a figure on this page, and the reason is stated rather than left to
be inferred.** This is the `--only alloc` row, and its `controlVerdict.ok` is neither lane
rule: it is that row's own test in `p0_run.cjs`, an absolute-error check on the **mean**
`perDouble` and on the differential slope, at **±75%**. The split above adjudicates the
heap and clock rows and does not reach this one, whose control is `rf2-rs8q6` territory.
So the six loose runs disclosed just above stand on exactly the terms they were published
under, and so do all sixty-nine admissible readings.

## What this window does not settle

- **It says nothing about the MECHANISM.** `rf2-77gz8` left per-invocation allocation —
  a runtime codegen effect — as the surviving candidate, and this window neither
  strengthens nor weakens that. No dataset here records V8 tier state either.
- **The +3,792 level is RARE and this window barely touched it**: one occurrence in
  sixty-nine readings, plus `alloc-9jrhi/bisect-1`. Two observations total, one in each
  build. That is enough to refute "arming gates it" — a single armed occurrence does that
  — and **not** enough to say anything about its rate, or whether it is the top of the
  ladder.
- **What the arm's full set of levels is remains open.** This window saw four distinct
  settled values and has no basis for claiming that is all of them.
- **The rate finding is an observation, not a model.** That the rate moved from 0% to 10%
  to 53% across three windows at one revision says the rate depends on something not
  recorded; it does not say what.

## The rig is not touched

Nothing under `implementation/core/test/re_frame/bench/` is modified by this window. The
rig blobs are the constancy guarantee the whole published allocation series rests on, and
the census is off at compile time by construction — `counting?` is a `goog-define`
defaulting to false and all three call sites are macros, so `:advanced` folds it away and
an unarmed build compiles the bundle this rig compiled before the counters existed. That
property is the *subject* of this window, so it is stated and not assumed.

| File | Blob at this page's base |
|---|---|
| `core/test/re_frame/bench/p0_run.cjs` | `ce6363ff774d8049c07b58513d708687a73e937e` |
| `core/test/re_frame/bench/p0_heap.cljs` | `5e174327ac17feac2f46ccbdf2bc4f89accf624f` |
| `core/test/re_frame/bench/p0_workcount.cljc` | `033f00470c380a17664a1dabffa0768f0e22c671` |
| `core/test/re_frame/bench/p0_fixture.cljc` | `1f066a05365e9f47b76b887a3d98e7cd8a9152e8` |
| `core/test/re_frame/bench/p0_floor.cljs` | `3a14ff96414f9a77a7612f56181444155b582620` |

Every one matches the table on
[The second mode: a pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md),
and no bench file has changed since `408dfb0aa8`, so **this window runs the same
instrument that produced the `alloc-77gz8` datasets**.

## Reproduction

From the **repository root**. The substrate is checked out at the revision the mode has
been seen at; the file set is identical either side (88 files, none added and none
removed), so the plain checkout is exact and nothing is left behind.

```bash
git checkout 4a1537cb71 -- implementation/core/src

# ARMED, repeated for the odd-numbered runs
P0_WORK_COUNT=1 P0_PORT=8473 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/armed-01-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# UNARMED, repeated for the even-numbered runs — P0_WORK_COUNT simply absent
P0_PORT=8473 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/unarmed-01-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

git checkout HEAD -- implementation/core/src
```

The datasets are committed beside this page under
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-c4hhk/`, on the convention
`rf2-2rtt6.138` set and `rf2-erre5` wrote down. Each retains every window's raw sample
stream, so the estimator can be re-derived without a browser.

## Related

- [The second mode: a pre-registered twenty-run window](the-second-mode-a-pre-registered-twenty-run-window.md)
  — `rf2-77gz8`, which found the +2,532 B level armed and left this confound open.
- [The work count is a constant, and the mode did not reproduce](the-work-count-is-a-constant-and-the-mode-did-not-reproduce.md)
  — `rf2-n1b9h`, which built the census and showed arming does not move the **low** level.
- [The bisect is flat and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md)
  — `rf2-9jrhi`, whose `bisect-1` is the only unarmed high-mode run in the corpus.
- [The dispersion follows the controls, not the round](the-dispersion-follows-the-controls-not-the-round.md)
  — `rf2-rs8q6`, whose once-per-window **+748 B** rider is a different and much smaller
  term than these page-global steps; do not let one explain the other.
