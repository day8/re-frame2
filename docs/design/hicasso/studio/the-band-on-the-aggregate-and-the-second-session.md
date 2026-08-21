# The band on the aggregate, and the second session

**`rf2-fk6pj`, phase 4.** Eight twelve-round allocation runs across **two
sessions**, read against a band **declared before run 1** and computed on the
same object as the figure it adjudicates.

This window owes three things and takes no rig change. `implementation/core/test/re_frame/bench/p0_run.cjs`
is byte-identical to `origin/main` throughout — the bead's own words are *"WHAT
THE NEXT WINDOW OWES. No rig change; `p0_run.cjs` needs nothing."*

## What the three predecessors left

| phase | design | what it returned |
|---|---|---|
| [`rf2-0gjqi`](the-sign-follows-the-pass-not-the-write.md) | `parity`, 2 × 6 rounds | The pass that ran second read lower in **10 of 12** blocks. Could not tell PASS from PARITY: the rig tied them. |
| [phase 2](the-pass-order-drawn-independently-of-parity.md) (PR #8601) | `seeded`, 2 × 6 rounds | 10-of-12 did **not** reproduce (7 of 12, chance). The decomposed PASS term did, in sign. Only one run separated, so the claim rested on *n* = 1. |
| [phase 3](the-design-control-comes-before-the-window.md) (PR #8615) | `seeded`, 4 × 12 rounds | The design **identified** — `q·parity = 0` inside every run. PASS term +0.65, +0.64, +0.20, +0.29, pooling to **+0.45%**. **The pre-registered band REFUSED it.** |

Phase 3 then recorded two defects in its own design rather than repairing them,
because an estimator must not change between a pre-registration and the runs it
is read on. The merged-PR audit of #8615 found a third, in the arbiter. **This
window owes all three:**

1. **A band on the AGGREGATE.** Phase 3's compared a contrast of medians over
   316 cells against a per-observation noise scale — too strict by roughly the
   square root of the count.
2. **More than one session.** Phase 3's four runs were one session, so its
   session-level independent count was 1.
3. **A fail-closed boundary inside `alloc_pass_position.cjs`,** not in front of
   it.

## A. The repair to the arbiter, which had to land before the pre-registration

The reader used to **print** `controlVerdict`, `verification.unverified`,
`passOrder`, `parityTied` and `scheduleDrove` and then feed every row to the
blocks, the headline and both decompositions **regardless**. A copy of a real
record with `controlVerdict.ok = false` still produced the headline.

The audit of #8615 found the front-door copy in `alloc_pass_design.cjs` failing
open on corpus **shape**: `scheduleDrove` iterated only the rows **present**, so
a record with `perRound` emptied or truncated to one row returned `true`
**vacuously** — it reported agreement between a draw of twelve rounds and a
drive of one — and `--admit` exited 0 on an empty corpus.

Both are repaired at source, and the boundary now lives in the reader with the
front door delegating to it, so the two cannot drift. Reproduced against the
committed phase-3 corpus:

| corpus shape | before | now |
|---|---|---|
| intact | admit | **admit** |
| `perRound` emptied | admit | **refuse** — `the drive covers 0 round(s), not the scheduled 12` |
| `perRound` truncated to one row | admit | **refuse** — `the drive covers 1 round(s), not the scheduled 12` |
| a duplicated round | admit | **refuse** — `12 round(s) (11 distinct)` |
| an out-of-range round | admit | **refuse** — `round 0 is missing from the drive` |
| `boundaries` 4 → 8 | admit | **refuse** |
| `writes` 6 → 5 | admit | **refuse** |
| `roots` 4 → 2 | admit | **refuse** |
| `plan` `full` → `narrow` | admit | **refuse** |
| `--admit` with no files | exit 0 | **exit 1** |

**The last four needed two changes, not one, and the second is the interesting
half.** The clause list alone was inert against them: a clause is only checked
against a parameter something **declares**, and `WINDOW` declared none of
`plan`, `roots`, `boundaries` or `writes`. Adding the clauses without adding the
declarations would have looked like a repair and changed nothing. `boundaries`
is the *B* every *d* on this page is divided by, so a run that moved it is not a
run of the same estimator at all.

**Phase 3's four committed runs still admit**, which is the half that proves the
boundary does not simply refuse everything.

## B. The design

Phase 3's design, unchanged, **run twice**. That is the point rather than a
convenience: repeating one design in a second session makes the SESSION the only
thing that differs between the two halves, which is the one contrast neither
this bead nor `rf2-0eu1s` has ever had.

Twelve rounds, four schedules per session, chosen by `alloc_pass_design.cjs`'s
enumeration **rule** rather than by a pick. Runs 3 and 4 are the exact
complements of 1 and 2, and 7 and 8 of 5 and 6; complementing inverts the pass
order at every round **index**, so each pair balances the pass contrast against
the index itself and not merely against its parity and its trend.

| run | session | seed | rounds that ran `page` first | `q·parity` | `q·linear` |
|---|---|---|---|---|---|
| 1, 5 | A, B | `fk6pj-9` | {0, 3, 4, 7, 8, 11} | 0 | 0 |
| 2, 6 | A, B | `fk6pj-11` | {0, 3, 4, 7, 9, 10} | 0 | 0 |
| 3, 7 | A, B | `fk6pj-2386` | {1, 2, 5, 6, 9, 10} | 0 | 0 |
| 4, 8 | A, B | `fk6pj-33` | {1, 2, 5, 6, 8, 11} | 0 | 0 |

**Every run yielded 12 blocks of 12, and the realised `q·parity` is 0 in all
eight.** That distinction is worth stating rather than gliding over: the
boundary checks the balance of the schedule that was **drawn** and the
completeness of the drive, but the design's identification is a property of the
labelling that actually **carried data**. A round whose four windows all failed
to certify would leave a short block set with a broken balance and the boundary
would not have caught it. It did not happen here — 96 blocks of a possible 96 —
and the realised balance is verified above rather than assumed.

**The two sessions are genuinely two.** The rig's own session logic says so, not
my clock: run 5 records `sameSession false`, `sinceEndMs` 63 minutes, and
`runsInSession` 1 against a new `sessionStartedAt`. The 60-minute dial was left
at its default.

## The estimator, unchanged and deliberately so

Verbatim from phase 3, which took it from phase 2, which took it from
`rf2-0gjqi`. For every round *r*, segment *s* and arm *a*:

```
d_all (r,s,a) = (arm@all .legMedian − floor@all .legMedian) / B
d_page(r,s,a) = (arm@page.legMedian − floor@page.legMedian) / B
Δ     (r,s,a) = d_page − d_all
```

A round contributes a cell only if **all four** of its windows certified. *B* = 4.
**THE FLOOR MUST BE SUBTRACTED** — reading the same blocks off the raw
`perBoundaryPerWrite` field gives 3 of 6 and the wrong magnitude, and the
reader's self-test pins that disagreement in both directions. **The block is the
ROUND, not the rung.** Which pass ran first is read off
`perRound[r].writeLegs[0]`, never recomputed.

**The headline statistic is the MEAN OF THE PER-RUN PASS TERMS**, and that is a
change from phase 3's pooled term, declared before the runs with its reason.
Each run is a complete balanced design on its own, so a run-level offset —
phase 2 measured one of 20% on a floor — cancels exactly out of every per-run
contrast before the runs are combined. Pooling all blocks first does not have
that property. The pooled term is reported beside it.

## C. The band

**A restricted randomisation of the pass labels, on the aggregate.**

The reference distribution of a term is what that term reads when the pass
**labels** are re-drawn from the schedules the design would equally have
admitted, with the block **values** left exactly where they were measured. The
noise scale and the signal scale are therefore **the same object** — the same
statistic, on the same blocks, at the same aggregation level. That is precisely
what phase 3's band was not.

The restriction is the design's own: a re-labelling is admissible only if it is
balanced with `q·parity = 0` and `q·linear = 0`. At twelve rounds there are
exactly **48** such schedules and **the set is closed under complement**, so
every reference distribution here is exactly symmetric about zero by
construction rather than by assumption, and any parity structure or linear drift
in the block values enters every re-labelling symmetrically.

Each run's own reference is **exact** — all 48 re-labellings, so a per-run
p-value is a rank among 48 and involves no sampling. Only the combination across
runs is sampled, because the product is 48⁸: **20,000 draws from the committed
seed `legorder-band-1`**, α = 0.05 two-sided, the observation counted, so the
smallest attainable p is 1/20001.

**What it does not control, named rather than assumed away:** residual functions
of the round index that the two balanced columns do not span — `r mod 4` among
them — are as free in the reference as in the observation. That is the same
residual the design names and does not filter on.

### Why it is not built on the null arm's p90

[`rf2-0eu1s`](the-floor-is-two-populations.md) measured that the R = 0 null arm
is **two disjoint populations** —
242 cells running 0..21, then nothing at all, then 44.5..135.5 — so a pooled
percentile of it is not a magnitude, and the ten-times rule returns 45 on one
window and 610 on the next. **That bead is awaiting an operator ruling and
nothing here pre-empts it:** this band takes no percentile of the null arm,
cites no published floor, and is not a byte threshold in either direction. It is
a rank of one number among others computed the same way.

### The null arm is the band's own negative control

The identical machinery runs on the R = 0 null arm, whose true term is zero by
construction. At R = 0 the denominator `d_all` is the quantity the arm is
**supposed** to read as zero, so the null-arm block statistic is the median of
`Δ` in bytes per boundary rather than the ratio. **The two readings are never
compared to each other** — the null arm is a control on the BAND, not a noise
scale a magnitude is measured against.

### The band's own controls, driven over the declared eight-run design

| fixture | term returned | verdict required | got |
|---|---|---|---|
| pure PASS 0.6% | +0.60% | must clear | p ≤ 0.05 |
| no effect | 0.00% | must not clear | p > 0.05 |
| pure PARITY 1.0 | **0.00%** | must not clear | p > 0.05 |
| PARITY 1.0 + PASS 0.6% | **+0.60%** | must clear | p ≤ 0.05 |

The third row is the failure mode this bead exists to prevent, tested on the
band rather than on the schedule: a parity term **166 times** the size of the
pass term the window is looking for moves the pass reading not at all. The
fourth shows the two together recover the pass term rather than a blend.

**One run cannot say much however large its term.** A single run's exact
reference has 48 points, so the smallest p it can attain is 1/48 — the corpus
size is load-bearing, exactly as the round count is.

## The outcomes, declared before the runs

1. **ESTABLISHED** — signal p ≤ 0.05 **and** null-arm control p > 0.05.
2. **NOT ESTABLISHED** — signal p > 0.05.
3. **NO VERDICT** — the null-arm control itself returns p ≤ 0.05.

The **session qualifier** bounds the claim rather than deciding it: if both
sessions agree in sign with the pooled term the claim reads "carried by both
sessions taken", otherwise it is capped at a single session whatever outcome 1
says. With two sessions that is a sign agreement on two blocks and nothing
stronger. **The independent block count is 2 at the session level**, not 8 and
certainly not 96.

## D. What the window found

### The pass term is established, and the parity term is not

**OUTCOME 1**, on the pre-registration's own terms.

| | term | two-sided *p* | reference p97.5 |
|---|---|---|---|
| **MID-RUNG (signal)** | **+0.31%** | **0.00005** = 1/20001 | +0.176% |
| R = 0 NULL (control) | 2.25 B/boundary | 0.0934 | 3.03 B/boundary |

**Not one of the 20,000 re-labellings reached the observed term**, so *p* sits
at the floor the pre-registration named — the smallest value the declared draw
count can return. The observed term is 1.8× the reference's own 97.5th
percentile. And the null arm, whose true term is zero by construction, **did not
fire**: *p* = 0.0934, clear of α with room, so the band is not one that reports
a term on anything handed to it.

**All eight runs read positive**, and the parity term straddles zero:

| run | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | mean |
|---|---|---|---|---|---|---|---|---|---|
| **PASS** | +0.50% | +0.23% | +0.41% | +0.31% | +0.32% | +0.16% | +0.04% | +0.55% | **+0.31%** |
| PARITY | −0.05% | −0.01% | −0.21% | +0.01% | +0.20% | −0.02% | −0.14% | −0.01% | **−0.03%** |

On the **complementary pairs** — the only contrasts balanced against the round
index itself, and therefore the ones no per-index nuisance survives — PASS reads
**+0.45%, +0.27%, +0.18%, +0.36%**, four of four positive, while PARITY reads
**−0.13%, 0.00%, +0.03%, −0.01%**.

Pooled over all 96 blocks the way phase 3 pooled its 48: PASS **+0.34%**, PARITY
**−0.04%**. `parity·pass` over those 96 blocks reads **0** — orthogonal, so the
two columns are separately readable, which is the property this bead was filed
to obtain and phase 2 could not deliver.

The raw sign count, reported and **not** the headline because phase 2 established
it conflates the order term with the run's level: the pass that ran second read
lower in **78 of 96** blocks, median second−first **−0.33%**.

### Across every window that could read the term, it has never read negative

| window | design | runs | per-run PASS terms |
|---|---|---|---|
| phase 2 | 2 × 6 rounds | 2 | +0.56%, +0.64% |
| phase 3 | 4 × 12 rounds | 4 | +0.64%, +0.64%, +0.20%, +0.29% |
| **phase 4** | **8 × 12 rounds, 2 sessions** | **8** | **+0.50, +0.23, +0.41, +0.31, +0.32, +0.16, +0.04, +0.55%** |

**Fourteen runs, fourteen positive.** Phase 2's two are on a design the audit of
#8601 showed cannot separate pass from parity, so they are page-first *grouping*
contrasts rather than pass-specific replications and are quoted as such; the
twelve from phases 3 and 4 are on designs that identify.

### Phase 3's refusal was its band's, not its data's

Phase 3's four committed runs, re-read through this window's band and nothing
else changed:

| | term | two-sided *p* | verdict |
|---|---|---|---|
| phase 3 under **its own** band | +0.45% pooled | — | **REFUSED**, outcome 3 |
| phase 3 under **this** band | +0.44% mean | **0.0013** | **clears α = 0.05** |

**Phase 3's own diagnosis of its band is confirmed.** It recorded that the band
compared an aggregate against a per-observation noise scale and was therefore
"too strict by roughly the square root of the count", and refused to repair it
after seeing the answer — which was the correct call and is why the corpus was
still there to re-read. The data always cleared; the ruler did not fit.

Its published figures reproduce: the pooled term reads **+0.4522%** against the
published +0.45%, and three of the four per-run terms to the digit. Run 1's
re-derived **+0.6448%** rounds to +0.64 against a published +0.65 — a
last-digit discrepancy in one per-run figure, recorded rather than smoothed.

### What is NOT established, so it is not rediscovered as new

- **THE INDEPENDENT UNIT IS THE SESSION, AND THERE ARE TWO.** Session A reads
  +0.36% and session B +0.27%; they agree in sign. **That is a sign agreement on
  two blocks and nothing stronger** — not eight, and certainly not 96. The
  randomisation *p* is a statement about the association between labels and
  values **within this window given this design**. It is not evidence that the
  term survives a different box, build or date.
- **NO MECHANISM IS PROPOSED AND NONE IS EXCLUDED.** Nothing here says what the
  second pass does differently.
- **THE `r mod 4` RESIDUAL IS NOT CONTROLLED.** Two columns are balanced and
  every other function of the round index is residual, in the reference exactly
  as in the observation. It cancels in each complementary pair and in the pool,
  and nowhere else.
- **THE MAGNITUDE IS NOT COMPARABLE TO `rf2-0gjqi`'s 1.68%.** This is a
  within-round pass-position term; that was a between-process confound. Right
  sign, different estimand.

### The null arm corroborates `rf2-0eu1s` without pre-empting it

This window adds 327 fresh null-arm cells, and — because it is one design held
still across two sessions — it happens to supply exactly the measurement
`rf2-0eu1s` named as missing: **two sessions on one design.**

**The two populations reproduce.** 269 of 327 cells sit at or below 21
B/boundary and 57 at or above 44.5, the same two modes on the same boundaries.
The pooled median is **0** and the pooled absolute median **1.5 B/boundary** —
both exactly the published central figures. The step is not quite as clean as it
was: **one cell of 327 landed inside the (21, 44.5) span that held zero of 242**
— 38.5 B/boundary, run 4 round 5, `reagent-subs | lad/reagent`. Recorded rather
than rounded away, because "the span is empty" was load-bearing in that bead's
argument and it is now empty-but-for-one.

**The over-bar fraction is NOT session-carried, on this evidence:**

| | over the 45 B/boundary bar | |
|---|---|---|
| session A | 29 of 164 | **17.7%** |
| session B | 28 of 163 | **17.2%** |

Two sessions, one design, half a percentage point apart. `rf2-0eu1s` could not
separate session from design because every previous window changed both at once;
holding the design still separates them, and the session does not carry it. That
narrows the bead's open question without answering it — **the mechanism is still
unproposed, and nothing here touches the 1.5 / 4.5 / 45 triple or the ruling
that bead waits on.** No percentile of the null arm entered any decision on this
page.



## E. Runtime, beside every figure

Chromium via Playwright at build **`chromium/147.0.7727.15`**, Node **v24.13.0**,
`win32/x64/10.0.26200` — all recorded by the run itself (`rf2-24o2z`), and the
same Chromium build phase 3 ran on, so the two windows are not two V8s.
shadow-cljs `release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`. Every
run's build reports **195 files, 140 compiled, 0 warnings**, and every run's
`shadow-cljs - config:` line names this worktree's own
`implementation/shadow-cljs.edn`. **No `java` process existed on the box when
the window opened**, so no shadow-cljs server could have served a stale build.

| run | session | seed | opened (UTC) | wall clock | falls | refused windows | busy fraction | free memory at open (GB) | in session |
|---|---|---|---|---|---|---|---|---|---|
| 1 | A | `fk6pj-9` | 01:23:32 | 2 m 49 s | 98 | 96 | 0.204 | 18.75 | 1 |
| 2 | A | `fk6pj-11` | 01:27:47 | 2 m 48 s | 98 | 99 | 0.151 | 19.42 | 2 |
| 3 | A | `fk6pj-2386` | 01:31:25 | 2 m 48 s | 111 | 100 | 0.135 | 19.78 | 3 |
| 4 | A | `fk6pj-33` | 01:35:03 | 2 m 54 s | 107 | 101 | 0.252 | 18.88 | 4 |
| 5 | B | `fk6pj-9` | 02:41:21 | 2 m 51 s | 113 | 104 | 0.220 | 16.19 | 1 |
| 6 | B | `fk6pj-11` | 02:45:32 | 3 m 01 s | 99 | 92 | 0.238 | 16.56 | 2 |
| 7 | B | `fk6pj-2386` | 02:49:50 | 2 m 51 s | 98 | 90 | 0.225 | 17.53 | 3 |
| 8 | B | `fk6pj-33` | 02:53:49 | 2 m 53 s | 103 | 97 | 0.249 | 17.58 | 4 |

**All eight runs captured exit 1**, this row's code for a run carrying refused
windows, on the falls gate and the leg-tolerance gate. **No slope is quotable
from such a run and none is quoted here**; every figure on this page is a
per-window figure off certified windows. **`N unverified of M`: 0 unverified
read-backs in all eight**, and the positive control's predicted-vs-measured is
**8.08 B/double against a declared 8.00 differential** in all eight.

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stayed the declared 0.25
placeholder and `ALLOC_FALL_THRESHOLD_B` stayed 600,000. W stayed 6 measured
writes after one prime (7 window writes), three warm-up windows, four roots,
*B* = 4 boundaries, R = 20 on the ladder, `segOrder: parity`, `controlSlot:
first`. **τ was neither read nor moved in either direction.** The only parameter
that moved from phase 3 is the run count, and the only thing that moved between
the two halves is the session.

**`P0_ALLOC_WRITES` was not moved to 5.** `rf2-onozm` closed saying five writes
is **SIZED, NOT SAFE** — only 5 of 12 cells land inside the certifying band, the
sizing rests on a one-sided ceiling of 884,280 B and the worst cell clears it by
0.6%. That is a sizing answer for a different estimand and no figure here is
quoted from it.

**`segOrder` stayed `parity`.** This window did not take
`P0_ALLOC_SEG_ORDER=fixed-reversed`: that arm belongs to `rf2-csca8`, which is
open with its readers under active repair, and a reading taken now would be
adjudicated by a reader about to change.

**Every attempt is reported, and there were eight.** No run was discarded,
re-taken or replaced; the pre-registration allows no replacement and none was
needed.

## The instrument did not move

Read before run 1 and again after run 8, with the same command:

| file | blob hash, before and after |
|---|---|
| `implementation/core/test/re_frame/bench/p0_run.cjs` | the identical blob `ebb08f9f10171d8b67cecee98cb7e85c0a5b9e42` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs` | the identical blob `787ffde48a07ae539d0868feec2718c0727310d5` |
| `implementation/hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs` | the identical blob `ccfa3057b3c38ab1b3610f7dd3e4e5264571a171` |

**The rig is byte-identical to `origin/main` and to the blob phase 3 ran its own
four runs against**, so phase 3's session and this window's two are the same
instrument on the same estimand — which is what licenses re-reading phase 3's
corpus in section D at all.

## Two defects found and deliberately NOT repaired

An estimator must not change between a pre-registration and the runs it is read
on, so both are recorded here and owned by the bead rather than fixed in place.

- **The reader's session line is wrong for a single-session corpus.** Re-reading
  phase 3 printed *"THE SESSIONS DO NOT AGREE IN SIGN … With 1 sessions that is
  a sign agreement on 1 blocks"*, where the honest statement is that one session
  admits no cross-session comparison at all. It is a printed-wording defect in a
  branch **this window's own corpus never takes** — eight runs across two
  sessions take the other branch — so no figure above is affected. Repairing it
  would have changed the reader's blob after the runs it was pinned for.
- **The boundary checks the DRAWN schedule's balance, not the REALISED one.** A
  round whose four windows all failed to certify would leave a short block set
  with a broken `q·parity` and be admitted. It did not occur — 96 blocks of 96,
  realised `q·parity` 0 in every run, verified in section B — but the check
  belongs in the boundary rather than in a paragraph.

## Reproduction

From `implementation/`, one run at a time:

```
P0_ALLOC_PLAN=full P0_ALLOC_CELLS=1 P0_ALLOC_WRITE=paired P0_ALLOC_ROUNDS=12 \
P0_ALLOC_PASS_ORDER=seeded P0_ALLOC_PASS_SEED=fk6pj-9 \
node core/test/re_frame/bench/p0_run.cjs --only alloc
```

and the adjudication, which needs no browser and reads the pre-registration
committed before run 1:

```
node hicasso/test/re_frame/bench/hicasso/alloc_pass_design.cjs --controls
node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs --self-test
node hicasso/test/re_frame/bench/hicasso/alloc_pass_position.cjs \
  --declared hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/pre-registration.json \
  hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/run1.json ... run8.json
```

Section D's re-adjudication of phase 3 substitutes
`data/alloc-legorder/phase3-re-adjudication.json` and phase 3's own four run
files.

Datasets: `implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-legorder/`.
Pre-registration and its prose: the same directory's `pre-registration.json` and
`README.md`. **Producing commit**: authored on `worker/legorder-fk6pj`, branched
from the landed commit `79471c9db9`, which is the anchor to resolve this page
against.
