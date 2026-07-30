# P0 — the ratom-spine narrow write (write + flush, summed)

**Bead** `rf2-2rtt6.3`

```bash
cd implementation && npm ci
node adapters/reagent/test/re_frame/bench/hicasso_narrow_run.cjs
```

**The instrument that took these readings is identified by content hash, not
by commit SHA.** That is not fastidiousness: this page has now been invalidated
by a rebase *twice*, once before the audit and once during the repair of it —
the branch was rebased onto `main` between the run and the merge and every
commit SHA moved, while the three blobs below did not move at all.

| file (`implementation/adapters/reagent/test/re_frame/bench/`) | blob |
|---|---|
| `hicasso_narrow.cljs` | `713fb3877dea102213da851bc8c21a8e0a9c835d` |
| `hicasso_narrow_app.cljs` | `a201ff16debe57b2aa297fe6ae7d27a19c3831d2` |
| `hicasso_narrow_run.cjs` | `cbffc0205675b524060f8c956efe2205f9e3570f` |

Authored as `7c51e77b4f` on `worker/bench-audit-cluster`. **If that SHA does
not resolve, a rebase moved it and the blobs above are what to trust** — this
finds a commit carrying them, and confirms it:

```bash
P=implementation/adapters/reagent/test/re_frame/bench/hicasso_narrow.cljs
git log --oneline --all -- $P
git rev-parse <candidate>:$P    # must print 713fb3877dea102213da851bc8c21a8e0a9c835d
```

The reproduction above runs unchanged at any commit whose tree answers those
three hashes.

**Runtime, and it is the same for every figure on this page:** Chromium
147.0.7727.15, `:advanced`, `goog.DEBUG=false`, Windows 11 (10.0.26200), 24
logical CPUs. Exit `0` = reportable, `1` = a gate failed, `2` = the
arm-order guard refused.

---

## What changed, and what did not

This page has been re-measured. The audit of PR #7262 found that the
subscription ladder **did not isolate subscriptions**, and the ladder's
figures and its attribution are withdrawn and replaced below. Two other
findings survive unchanged in kind and were re-measured with everything
else: the summed write+flush window, and the like-for-like correction.

The superseded publication is recorded at the foot of this page rather than
deleted. A reader must be able to see that these numbers were revisited.

---

## What this page settles

A narrow write — one cell of three hundred — on **re-frame2 running the
Reagent adapter**, which is `re-frame.substrate.spine/make-ratom-spine`:
Reagent's own `r/atom`, Reagent's own `make-reaction`, Reagent's own
batching. The figure is **write + flush, summed**, and the split is
published beside it.

It exists because the withdrawn predecessor programme's narrow-update row
was not a valid target as measured, in two separate ways, and both are
repaired here.

**The comparison arm was not the same amount of framework.** It read a
bare `reagent.core/atom` through a `reagent.core/cursor` — Reagent's own
idiom, and a fair arm for *what does Reagent cost*, but not for *what does
re-frame on Reagent cost*. A re-frame-shaped application pays a frame
write and a subscription graph the bare-ratom arm never touches. Both arms
are measured here, in the same interleave on the same page, so the size of
that framing error is a row rather than a claim.

**And the write leg alone means nothing on this substrate.** On the ratom
spine a write is a bare install into the frame's one physical container;
the reactions do not recompute there, they recompute on Reagent's flush.
A harness that timed the write leg and stopped would have published
0.0004–0.0007 ms per write for an operation that costs 0.32–0.33 — an
understatement by a factor of between 460 and 800. That is why the summed
figure is the published one.

---

## The headline

**A narrow write on the ratom spine costs 0.32–0.33 ms, and 99.8–99.9% of
it is the flush.**

Three independent runs at the one commit; 300 cells, 300 layer-1
subscriptions, one per cell. Per-write milliseconds — a sample is 20
writes and the per-write figure is the sample divided by twenty.

| arm | write+flush (mean, r1 / r2 / r3) | write | flush | write share |
|---|---|---|---|---|
| bare `r/atom` + cursor, no re-frame | 0.0272 / 0.0221 / 0.0174 | 0.0004–0.0011 | 0.0168–0.0261 | 1.9–4.1% |
| **re-frame ratom spine, `replace-app-db!`** | **0.3208 / 0.3287 / 0.3226** | 0.0004–0.0007 | 0.3204–0.3278 | **0.1–0.2%** |
| re-frame ratom spine, `dispatch-sync` | 0.3872 / 0.3757 / 0.3762 | 0.0106–0.0151 | 0.3647–0.3719 | 2.8–3.9% |

Within-run **ranges** for the headline arm — median [min–max] over 36
samples, which is 720 verified writes per run:

| run | write+flush |
|---|---|
| 1 | 0.3075 [0.1350–0.5100] |
| 2 | 0.3225 [0.1150–0.5050] |
| 3 | 0.3250 [0.1200–0.5450] |

All three ranges overlap, so these are one measurement taken three times.
The spread within a run is still a factor of four between best and worst
sample, so **the absolute figure is quoted as a range and not as a
number**. The quantity that matters for this bead is much steadier than
the absolute: the write share holds at 0.1–0.2% on every run, exactly as
it did on the superseded publication, which was taken on a busier box at a
different absolute level.

### Both orders

Arms are interleaved at the sample level with the slot order rotating
**and reflecting** on the sample index, so every arm is measured under two
different adjacencies. A bare rotation would not vary that at all: arm `a`
sits at slot `(a − s) mod k`, so its predecessor is `(a − 1) mod k` at
every index, and only the round seam ever differs.

Run 3, per-write medians [min–max]:

| arm | forward | reflected | verdict |
|---|---|---|---|
| bare-ratom | 0.0150 [0.0050–0.0400] | 0.0175 [0.0050–0.0350] | overlapping — indistinguishable |
| spine-replace | 0.2800 [0.1600–0.5450] | 0.3750 [0.1200–0.5150] | overlapping — indistinguishable |
| spine-dispatch | 0.3525 [0.1500–1.0100] | 0.3700 [0.1400–0.6150] | overlapping — indistinguishable |
| spine-30 | 0.0100 [0.0000–0.0550] | 0.0100 [0.0000–0.0600] | overlapping — indistinguishable |
| spine-100 | 0.0450 [0.0200–0.1300] | 0.0500 [0.0200–0.1500] | overlapping — indistinguishable |

The arm-order guard returned **reportable on all three runs**: no arm's
figure is separated by what ran before it, or by where in the run it was
measured, on either factor, at a 10% tolerance.

---

## Where the money goes — and a ladder that now isolates what it names

"The flush" is one word doing two jobs — Reagent re-running every dirtied
reaction, and React committing the one cell that changed — and telling
them apart is exactly what the predecessor did not do.

**The first cut of this ladder did not tell them apart either.** Each rung
was built by handing the arm a cell count, so a rung changed the app-db
vector, the mounted component count, the DOM span count, the reaction
count and the subscription count *together*. The page then said "the React
commit is the same one-cell commit at every rung, and only the
subscription count moves". It was not, and it did not. What was measured
was a compound **fixture-size** ladder, and no figure fitted through it
could be attributed to layer-1 subscriptions.

Every rung now mounts the **same 300-cell fixture** — 300 cells seeded
into app-db, 300 `spine-cell` occurrences mounted, 300 spans rendered —
and the rung's number is how many of those cells actually hold a
subscription; the rest render an inert dash. Writes target the subscribing
range, so the React commit really is one cell at every rung, by
construction rather than by assertion. A gate in the driver refuses to fit
the ladder unless each rung's subscription count is its own.

| subscriptions | flush ms/write (r1 / r2 / r3) | per subscription | local slope vs the rung below |
|---|---|---|---|
| 30 | 0.0167 / 0.0136 / 0.0133 | 0.44–0.56 µs | — |
| 100 | 0.0471 / 0.0586 / 0.0511 | 0.47–0.59 µs | 0.44–0.64 µs/sub |
| 300 | 0.3204 / 0.3278 / 0.3221 | 1.07–1.09 µs | 1.35–1.37 µs/sub |

**No per-subscription cost is quoted, and that is a finding rather than a
caution.** A line fitted through the top and bottom rungs has an intercept
of **−0.017 to −0.021 ms**, and the work that does *not* scale with the
subscription count — React's one-cell commit, Reagent's drain overhead —
cannot cost less than nothing. The negative intercept is the data refusing
the linear model, and the third rung exists because of it. Ten times the
subscriptions costs **19.2× / 24.1× / 24.2×** the flush — an exponent of
**1.28 / 1.38 / 1.38**.

So a narrow write's cost on this substrate is **super-linear in the number
of layer-1 subscriptions in the frame**. That sentence is now supported by
the fixture: with the app-db, the component count, the DOM and the React
commit all held constant across the rungs, the growth has nowhere else to
live but the subscription graph — which is Reagent's reaction drain, not
React's commit.

**What the isolation cost the previous claim.** The superseded ladder read
an exponent of 1.45–1.53 and an intercept of −0.031 to −0.041 ms. Isolated,
the same question answers 1.28–1.38 and −0.017 to −0.021. Both ladders are
super-linear; the first was steeper because it was also growing the app-db
vector, the fiber tree and the DOM at every rung. **Roughly a third of the
published super-linearity was fixture size, not subscriptions**, and the
attribution the earlier page made — that the exponent described layer-1
subscriptions — was not established by the arms that produced it. It is
established by these.

What is *not* in doubt is the shape, which is substrate-independent: a
one-cell write marks **every** layer-1 subscription in the frame dirty and
re-evaluates all of them, because a layer-1 body is an opaque function of
the whole app-db and the graph holds no path. This page prices that shape
on the ratom spine; it does not discover it.

---

## The like-for-like correction

| | mean ms/write (r1 / r2 / r3) | ratio to bare ratom |
|---|---|---|
| bare `r/atom` + cursor (no re-frame) | 0.0272 / 0.0221 / 0.0174 | 1.0× |
| re-frame ratom spine, `replace-app-db!` | 0.3208 / 0.3287 / 0.3226 | **11.8× / 14.9× / 18.6×** |
| re-frame ratom spine, `dispatch-sync` | 0.3872 / 0.3757 / 0.3762 | 14.2× / 17.0× / 21.7× |

**Any narrow-update ratio quoted against the bare-ratom column is
measuring this gap and calling it something else.** A ratio of, say, 15×
against a bare ratom is not 15× against a re-frame application: on these
numbers essentially all of it is already paid by re-frame reading its own
subscriptions, whatever renders them.

The ratio's own run-to-run spread — 11.8× to 18.6× — is real and is stated
rather than averaged away. It is *wider* than the superseded publication's
8.4×–12.6×, and the reason is visible in the table: the bare-ratom arm
drifted from 0.0272 to 0.0174 across the three runs while the spine arm
held near 0.32. Both arms move with the box and they do not move together,
so **the ratio is not the stable quantity here; the write-versus-flush
split is**, and that is what this bead was asked for. The bare-ratom arm
is also clamp-limited (below), which is a second reason not to lean on it.

The event drain — the difference between the two write doors — is
**0.0664 / 0.0469 / 0.0536 ms per write**, 14–17% of the total. It is the
only part of the cost that shows up in the *write* leg at all:
`dispatch-sync` is the one arm whose write leg clears the clock grid, and
it clears it because the drain runs there.

---

## The instrument's own gates

Every one of these ran before any figure was taken, on every run.

| gate | result |
|---|---|
| **Arm-order guard self-test**, replayed from recorded fixtures, run inside the `:advanced` bundle | 11/11 passed |
| **Key-renaming integrity probe** — the bundle writes and reads its own accumulator keys under the renaming it was compiled with | passed; keys `control,write,gap,force,total,span,bad,writes` |
| **Clock quantum**, measured in-page | 0.100 ms — Chrome's documented clamp, confirmed rather than assumed |
| **Ladder isolation** — every rung mounts the same cell/component/span count, so only the subscription count moves | passed, 30/100/300 of 300 |
| **DOM read-back**, every write read back out of the page inside its own window | **0 unverified of 3600**, all three runs |
| **Empty-`flushSync` negative control** — the same arm with an empty drain, which must go red | **20 unverified of 20**, all three runs |
| **Positive control**, predicted vs measured | see below |
| **Leg identity** — write + gap + force = total, exactly | holds on every arm |
| **Position completeness** — every sample reached the guard with a finite position | 36 of 36 per arm |

**The denominator is 3,600 and not 4,320.** The `instrument` pseudo-arm
renders no cell and forces its read-back verdict true, so its 720 windows
per run were never evidence that anything reached a page. The superseded
publication folded them in and claimed "0 unverified of 4320; every write
read out of the DOM", which was true of 83% of the writes it named. Arms
that cannot be verified are now excluded from the tally and reported on
their own line.

**Three of these gates now fail the run.** They used to be printed and
stored and nothing else, so a run could report `VERDICT: reportable`
beside a column saying some of its writes never reached the page. A
nonzero unverified count on a real arm, a broken leg identity, and a
missing sample position are each an exit now.

### The positive control

A predicted burn inside every measured window, as its own leg, read three
ways. The prediction is fixed in the driver before the run, at 0.3 and
0.9 ms per write.

| | predicted | measured (p50, run 1 / 2 / 3) |
|---|---|---|
| rung 1 | 0.3000 ms/write | 0.3250 / 0.3250 / 0.3250 |
| rung 2 | 0.9000 ms/write | 0.9650 / 0.9650 / 0.9700 |
| **slope** | 0.6000 ms | 0.6400 / 0.6400 / 0.6450 — **6.7–7.5% high** |

The direct readings run 0.025–0.070 ms above prediction and the slope runs
6.7–7.5% high; both are the spin loop's own clock-read overhead, which the
slope reduces but does not remove, since the loop reads the clock at both
rungs. The control is a *floor* on the instrument's resolution, not a
correction applied to anything.

The third reading is the one that matters, because it is falsifiable: **an
arm's write+flush must not move when the control's size changes.** It
does not — every arm's rung-1 and rung-2 ranges overlap on every run. Had
the control's time leaked into the arms' legs, the leg accounting would be
wrong and nothing on this page would be quotable.

### What is *not* quotable

The clamp check names these, and they are listed rather than quietly
rounded:

- **`write` on every arm but `spine-dispatch`** sits under the 100 µs
  grid. Its median is 0 by construction, so no median-derived write share
  appears anywhere on this page; the split above is taken from the
  **quantisation-unbiased mean**, which recovers a sub-quantum leg because
  `floor(t₁/q)·q − floor(t₀/q)·q` has expectation exactly `t₁ − t₀`.
- **`bare-ratom` and `spine-30` totals** carry only 2–3 multiples of the
  quantum per sample on these runs — fewer than the superseded ones did,
  because the box was quieter and the arms therefore faster. They are
  reported, and they are not absolute figures, which is one more reason
  the like-for-like *ratio* is quoted as a range and the *split* is quoted
  as the finding.

### Warm-up

Each arm is warmed at full window size until the first third of its
trajectory stops separating from the last third by median, floor 8 windows
and ceiling 20. **On these runs several arms reached the ceiling still
trending** — `bare-ratom` on all three, `spine-30` on the third — and that
is stated rather than smoothed.

What says the figures are nevertheless usable is not the warm-up rule but
the guard: its **phase** factor partitions each arm's 36 measured samples
into thirds by position in the run and compares the first third against
the last, and it came back clean on every arm of every run. A site that
was still climbing through the measured window would separate there. The
whole trajectory is printed on every run, so this is checkable rather than
asserted.

The settle test is the median one **only**. Accepting "medians within
tolerance *or* ranges overlap" let every arm settle at the floor while
still visibly trending — on a loaded box the ranges always overlap, so
that test passes by being uninformative. Overlapping ranges are the right
rule for adjudicating two measured strata; they are the wrong rule for
deciding a site has stopped moving.

---

## Five faults this harness found in itself

Recorded because each produced a plausible, precise, wrong number first.

1. **A shadowed binding silenced three quarters of the guard.** The
   destructured `{:keys [samples …]}` in the round driver shadowed the
   numeric `samples` parameter, so the running position counter came back
   `NaN` and every sample from round two onward reached the arm-order
   guard with a non-finite position. The guard filters those — so it went
   on reporting "24 samples" per arm while adjudicating the phase question
   on the **six** that survived. Nothing threw; the verdict read `[ok]`.
   The driver now prints the finite-position count per arm beside the
   strata and **fails the run** if any is missing.

2. **A median write-share read a flat 0%.** With the write leg under the
   clock grid its median is exactly zero, so a share computed from it was
   0% on every arm — a precise wrong number that looked like a finding.
   The split moved to the mean and the median table lost its share column.

3. **A two-rung ladder fitted a negative intercept.** Reported above; the
   third rung exists because of it.

4. **The three-rung ladder then varied five things at once.** Found by
   audit, not by the harness. A rung was a whole smaller fixture, so the
   slope described fixture size while the page attributed it to
   subscriptions. Repaired above, and the repair moved the exponent from
   1.45–1.53 to 1.28–1.38.

5. **The verification denominator counted windows that verified nothing.**
   Also found by audit. `0 unverified of 4320` included 720 windows of a
   pseudo-arm that renders no cell and forces its own verdict true.

---

## Lane note

This bead adds **no build id**. `implementation/shadow-cljs.edn` is
hot-zone and `rf2-2rtt6.2` owns the lane's id, so the driver overrides the
`:hicasso-bench` build's entry point and output directory with
`--config-merge` — the technique the donor's own `b8_run.cjs` uses. The
`:freehand-release` fallback this page used to describe was removed by
`rf2-uhw11`: that build is cleaned and rebuilt by two other gates, so a
bench run and a gate run raced one compile cache, and a figure taken
through the fallback would not have been comparable with one taken through
the lane.

The template is now `:hicasso-bench` unconditionally and **there is no
environment override**. `HN_BASE_BUILD` went with the fallback; a reader
working from notes that mention it should know that setting it today does
nothing. No figure on this page depended on either knob — every row was
taken through `:hicasso-bench`, which is what the fallback selected once
the lane's build id existed.

---

## Superseded

**The publication of 2026-07-30, withdrawn 2026-07-31.** Its rows are on
`rf2-2rtt6.1`; nothing there has been amended, and this section says why
the earlier rows should not be quoted.

- **Producing commit as published:** `828d4f1ac0cff850b6fcb44a7fa2d0729fc175d8`
  — the authored PR commit, which is not on `main`. **The durable landed
  instrument commit for that publication is `f047011184`**, and the full
  authored-to-landed mapping is `bf240f3180=2122f7c5d6`,
  `f74540571e=678afb6759`, `828d4f1ac0=f047011184`, `e229fef1f0=1c03cadd4c`.
  Recorded here because a page whose SHA cannot be checked out cannot be
  reproduced, and this page has now been invalidated by a rebase twice.
- **The ladder rows and their attribution are withdrawn.** 0.0154/0.0182/
  0.0114 at 30, 0.0932/0.0946/0.0557 at 100, an exponent of 1.45–1.53 and
  an intercept of −0.031 to −0.041 ms were measured on a ladder whose rungs
  were whole smaller fixtures. They are real measurements of fixture size
  and they are not measurements of subscription count.
- **The verification claim is withdrawn.** "0 unverified of 4320" should
  have read "0 unverified of 3600, 720 not verifiable".
- **The headline, the split and the like-for-like correction stand in
  kind** and were re-measured for this page. The absolute level moved
  (0.39–0.52 ms then, 0.32–0.33 now) because the box was carrying six
  sibling agents then and fewer now, and because the page holds 1,500
  mounted cells under the isolating ladder against 1,030 under the old
  one. The write share — the figure the bead asked for — did not move at
  all: 0.1–0.2% on both publications.
