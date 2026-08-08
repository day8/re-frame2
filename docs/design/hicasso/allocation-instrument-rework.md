# The allocation instrument's rework — a boundary-proportional write and an observed-collection witness

Seat: DESIGN BRIEF, EP-0038. Bead `rf2-2rtt6.140`, criterion 1 of six.
Written 2026-08-08, before any code.

**This page is a design, not a measurement, and not an implementation.** It is
the one brief the ruling on `rf2-2rtt6.140` requires before either artefact is
built: the **boundary-proportional write** and the **in-window collection
witness**, designed together because neither works alone. Nothing here has been
run. Every figure it reasons from was measured by `rf2-2rtt6.138`'s window and
is recorded on
[the survival metric's allocation half](studio/the-survival-metrics-allocation-half.md);
every figure it predicts is a prediction, and the witness this page specifies is
what will decide whether the predictions were right.

The ruling's own fence is part of the work: **bounded — no statistics research
project.** Two artefacts, four validity witnesses, a stated disposition for
every test the change supersedes, and one option explicitly held in reserve
rather than taken.

## The answer, first

- **The measured unit cannot be shrunk any further, because it has a floor and
  the floor is a constant.** `:p0/write-all` rebuilds a 300-element vector and
  drives the whole event pipeline whether one boundary is mounted or 1,200. On
  this rig that costs **F ≈ 24.4 KB per write** (24,108 B on `reagent-subs`,
  24,730 B on `uix-subs`), and **F does not shrink when B does**.
- **The write becomes proportional to the page.** A new `:p0/write-page` event
  rebuilds a vector of exactly the width the mounted page reads, so the term
  that was 300 cells wide on a 4-boundary page becomes 4 cells wide. What
  remains is the event pipeline itself, which is the cost a real application
  pays and which the floor arm exists to subtract.
- **Certification stops being an argument and becomes an observation.** The
  `(W+1)·perWrite ≤ 300,000 B` bound is retired — not widened, retired — and
  replaced by a rule read off the window's own samples: the legs of a window are
  repetitions of **one work unit**, so a leg that reads materially below its
  cohort is a leg something removed bytes from. That is a collection, observed.
- **The two executable probes from the merged-PR audit of #7682 are refused for
  every tolerance below 1**, and the refusal does not depend on the calibration.
  Both were admitted at `headroom = 0` by the old bound with true allocations of
  300 KB and 600 KB.
- **Neither half works alone, and the arithmetic says so numerically** — see
  [Why both halves, in numbers](#why-both-halves-in-numbers). Route (a) alone
  admits a one-boundary ladder; route (b) alone admits a three-boundary page on
  which the un-modelled constant is two to four times the entire first-read
  signal. Composed, the ladder gets a page it can be read on.
- **What this brief does not settle** is listed in full at
  [What this brief could not settle](#what-this-brief-could-not-settle). The
  largest item is honest and load-bearing: nobody has measured how much of F is
  the vector rebuild and how much is the pipeline. The first validity witness
  measures exactly that, and it is the gate on the whole write half.

## What was measured, and why the present route is dead

The full record is on
[the studio page](studio/the-survival-metrics-allocation-half.md#the-small-witness-arm-measured);
this is the part the design turns on.

`rf2-2rtt6.138` built the small-witness arm — B = 24 boundaries, six writes a
window, six rounds — and ran it once. The controls were the cleanest the row has
taken (differential exactly 8.00 B/double against a predicted 8) and the
read-back was clean across all 132 windows. Both collector gates refused anyway:
101 falling steps across 132 windows, and 98 of 132 windows over the 300 KB
masking budget. **The 34 windows that certified were exactly the ones with no
subscription in them** — every floor arm and almost every R = 0 anchor.

The cause was a term the sizing arithmetic never modelled. The floor arm is
precisely a write with no subscription under it, so it measures the write's own
machinery directly, and it read **F ≈ 24.4 KB per write**. The bound charges
`(W+1)·perWrite`, which at the six-write averaging floor allows 42,857 B per
write — so **F alone is 57% of the allowance before a single boundary has been
measured**.

Modelling `perWrite ≈ F + B·s(R)` off the run's own arms gives the largest page
each rung admits. A fixed-B ladder is sized by its worst rung, so the bottom row
is the one that decides:

| rung | measured `s` (B/boundary/write) | largest B at W = 1 | at W = 3 | at W = 6 |
|---|---|---|---|---|
| R = 1 | 2,031 – 4,067 | 30 | 12 | 4 |
| R = 3 | 4,888 – 5,904 | 21 | 8 | 3 |
| R = 7 | 9,246 – 10,158 | 12 | 4 | 1 |
| R = 20 | 6,800 – 22,174 | 5 | 2 | **0** |

**At six writes there is no page of one boundary or more that certifies the
1/3/7/20 ladder.** At one write there is — B ≤ 5 — but one write is the
configuration that produced r² 0.75 / 0.28 / 0.94 / 0.31 for want of averaging,
and at B = 5 the un-modelled constant is F/B ≈ 4,884 B per boundary per write,
close to twice the entire first-read signal it would have to be read against.

Every `s(R)` above is read off a window the run refused as under-reading, so
each is a **lower** bound and every largest-B figure is an **over**-estimate.
The squeeze is at least this tight.

## Why both halves, in numbers

The ruling requires the composed package and gives the reason in prose. Here it
is arithmetically, at the ladder's worst rung (R = 20, worst arm `s` = 22,174
B/boundary/write) and at the six-write averaging floor.

Two different constraints are in play. The **bound** charges `(W+1)·perWrite ≤
300,000 B`. The **witness** charges nothing in advance: what limits a window is
whether a collection actually falls inside it, which on this rig first becomes
*visible* at roughly 600 KB of cumulative garbage — so a six-write window has
roughly 100 KB per write before the collector has a reason to run.

| route | what limits the page | largest B at R = 20, W = 6 | and then |
|---|---|---|---|
| today | `(42,857 − 24,400) / 22,174` | **0** | no ladder at any page |
| (a) alone — cheaper write, bound kept | `(42,857 − F₀) / 22,174` | **1** | a one-boundary "ladder" is not one |
| (b) alone — witness, write kept | `(100,000 − 24,400) / 22,174` | **3** | F/B = 8,133 B/bnd — 2× to 4× the whole R = 1 signal |
| composed | `(100,000 − F₀) / 22,174` | **4** | F/B ≈ F₀/4 — the target is under the R = 1 signal |

Read the last column as carefully as the third. Route (b) alone does buy a page,
and a three-boundary page is arithmetically admissible — but on it the fixed
residue is larger than the quantity being measured at the bottom of the ladder,
so the rung with the least signal is mostly machinery, and the floor's own
round-to-round spread (21,840 – 24,432 B per write across six rounds) lands on
the fit as independent noise on that rung. That is what "sound but useless"
means here, and it is why the ruling refuses to take (b) on its own.

**These four rows are a sizing sketch and not a certificate**, exactly as
`ALLOC_B_PER_BOUNDARY_WRITE`'s own header says of the prediction it replaced.
The 600 KB figure is an UPPER bound on where the first collection runs, so the
table is optimistic in the direction that matters. Nothing in the new
instrument certifies a window by this arithmetic; the witness reads the window's
own samples and decides there.

## Part A — the boundary-proportional write

### What changes

Today the allocation window drives `arms/write-all!`, which is
`dispatch-sync` of `[:p0/write-all v]`, whose handler is

```clojure
(rf/reg-event :p0/write-all
  (fn [{:keys [db]} [_ v]] {:db (assoc db :cells (vec (repeat cells-n v)))}))
```

`cells-n` is the compile-time published grid width, 300, and it is 300 whatever
page is mounted. On the allocation row's 24-boundary page, 276 of the 300
rebuilt cells were read by nothing at all. On a 4-boundary page it would be 296.
**The write was paying to change data the page does not read.**

The change is one parameter and one new event.

1. **The grid width becomes a property of the seeded app-db** rather than a
   compile-time constant baked into three places. `seed-db` takes the width;
   `:p0/seed` carries it as an event argument; `enter-segment!` and
   `p0-heap/prepare!` thread it from the plan. It defaults to `cells-n`, so
   every caller that passes nothing gets today's page to the byte.
2. **`:p0/fan`'s modulus is read off the db it is handed** — `(mod i (count
   (:cells db)))` in place of `(mod i cells-n)` — so the key rule stays exactly
   `(n·R + j) mod Q` folded into whatever grid is actually seeded, and there is
   no second place for the width to live and drift.
3. **A new event, `:p0/write-page`**, rebuilds `:cells` at the db's own width:
   `(vec (repeat (count (:cells db)) v))`. Its cost is O(mounted page).
4. **`:p0/write-all` is left exactly as it is**, literal `cells-n` and all. It
   is the bulk clock row's write, its rows are published, and it stays
   byte-identical. The allocation row stops dispatching it.
5. **`arms/write-page!`** joins `arms/write-all!` as a public door, and
   `p0-heap/alloc-window!`'s `"write"` kind drives the new one.

Recommended shape for threading the width, and the reason: pass it as an
argument rather than setting a page-scoped `volatile!` beside `fx/set-fan-keys!`.
The fan-key volatile is defensible because Q cannot be read off the DOM and has
to be asserted afterwards; the grid width can simply be `(count (:cells db))`
wherever it is needed, and a parameter that is never ambient cannot be set in
the wrong order relative to seeding. An implementer who finds the argument
thread ugly at `prepare!` may use a volatile instead — but then a structural pin
has to assert that the width is set **before** `make-frame`, because a width set
after seeding is a page whose sub graph and whose db disagree.

### The equivalence argument

This is the crux of the write half, and the ruling says so: *a cheaper write
that measures something else is worse than no write.*

The ladder claims to measure **steady-state allocation per boundary per warm
read**. For that claim, the stimulus has one job: for every mounted boundary, it
must invalidate all R of its subscriptions, recompute all R layer-1 subs,
deliver R changed values to the boundary, and drive the boundary's re-render and
commit. `:p0/write-page` does all four, unchanged, and here is each in turn.

1. **It is the same write, through the same pipeline.** It is a `dispatch-sync`
   of an ordinary re-frame event with an ordinary `:db` effect. It does not
   reach past the event pipeline or the signal graph into a raw atom or a direct
   app-db replace — the thing `p0_fixture`'s own registration comment forbids,
   because in re-frame2 the commit *is* the write.
2. **The invalidation set is identical.** Every mounted key `[:p0/fan k]` folds
   into `db[:cells]` under `(mod k width)`, and the write replaces every slot of
   that vector with a new value. So every one of the E = B·R edges sees a changed
   value, exactly as under `:p0/write-all`. Neither write changes anything a
   mounted boundary does not read; the old one additionally changed a great deal
   that no boundary read.
3. **The rendered page is identical.** A boundary's text is the sum of its R
   reads, so at a page written to `v` it is `(str (* R v))` under either write.
   The DOM read-back gate, the canonical-DOM comparison and the floor
   subtraction are all unchanged, and the read-back still states its expectation
   against the tick actually reached.
4. **B, E, Q and the key rule are untouched.** The ladder still runs at Q = E
   with `(n·R + j) mod Q` the identity over `0 … B·R−1`, and `live-key-count`
   still proves Q on every mount. Two keys folding onto one db slot is not new —
   `:p0/fan` has folded `mod cells-n` since `rf2-5prok`, and at B = 24, R = 20
   the published page already folds 480 keys onto 300 slots.
5. **The only difference is the width of the vector rebuilt inside the event
   handler**, which is machinery on both sides of the floor subtraction and is
   read by no boundary beyond its own slot.

Stated as one sentence, which is the sentence a reviewer should be handed:
**the new write changes exactly the data the mounted page reads, where the old
write changed that data and 296 slots besides; the per-boundary read and render
work either write provokes is the same work, and the floor arm subtracts what is
left.**

And the falsifiable form, because an equivalence argument that cannot fail is
not one: if the new write measured something else, the per-boundary signal
`arm − floor` would move. **V2 measures both writes on the same page in the same
run and requires that it does not.**

### What deliberately does not change

- **The window shape.** Same `dotimes`, same two samples per iteration, same
  preallocated `Float64Array`, same `flushSync` drain split per segment. Nothing
  inside the window allocates on the instrument's behalf, and that rule is not
  relaxed by a byte.
- **The falls gate.** It is the half that works and it is untouched, as
  `rf2-n6w7o` instructed.
- **`cdpBracket`.** Recorded on every window, in no failure path, exactly as
  today. `rf2-n6w7o`'s fence against promoting it stands.
- **The retention rows, the fan-out sweep and the clock rows.** They pass no
  width, seed at 300, and dispatch `:p0/write-all`. The one thing that touches
  them is `:p0/fan`'s modulus gaining a `(count …)`, which is a field read on a
  `PersistentVector`: it allocates nothing and retains nothing, and those rows
  publish retained bytes. **No published figure moves.** A structural pin says so.

### Rejected alternatives

| considered | rejected because |
|---|---|
| **A width of one cell**, every key folding onto one slot, machinery O(1) | It converts a full-page data change into a point change. HD-002's law is stated about *the change* — "allocation is proportional to the CHANGE, not to the read count" — so a ladder driven by a point change is testing a different regime and invites exactly the objection this brief exists to close. One cell per boundary keeps the change full-page and is still O(B). |
| **Writing through a raw atom or replacing app-db directly**, skipping the pipeline | It would delete F entirely and measure a page no application has. `p0_fixture`'s registration comment already refuses it, and it would break equivalence at point 1. |
| **Keeping `:p0/write-all` and parameterising its width in place** | Cheaper diff, worse record: the same event id would mean different things on different pages, and a reader of the 2026-08-07/08 rows could no longer tell from the row which write produced it. Criterion 6 is about exactly that. |
| **Hand-rolling a transient/loop rebuild to shave the lazy-seq constant** | Instrument code in a fixture, and it makes the write less like the write an application performs. The width parameter is the honest lever; the constant is what it is. |

### Validity witness V1 — the floor at three page sizes

Criterion 2 asks for a floor arm at **≥ 2 page sizes showing the fixed residue
is no longer dominant**. Take three, and take both writes, because that turns an
assertion into an experiment with a control in it.

**Configuration.** Floor arm only — no ladder rungs, no fits, no candidate. Six
rounds, six writes a window, three warm-up windows, at `P0_ALLOC_CELLS` ∈ {1, 6,
24} against the default `P0_ROOTS = 4`, i.e. **B ∈ {4, 24, 96}**, on both
segments. Each page measured under `:p0/write-all` and under `:p0/write-page`.

**What it answers.**

- `F_old(B)` must come back **flat in B** and must land on the 2026-08-08
  figures at B = 24 (24,108 / 24,730 B per write). That is the control: it says
  the rig has not moved under the instrument, and it is the only way the two
  writes can be compared like for like.
- `F_new(B) = F₀ + B·w` is a two-unknown fit on three points. `F₀` is the event
  pipeline plus the empty `flushSync` plus the sampler's own footprint — the
  idle control already prices that last term at 32 B per iteration, so it is
  negligible and `F₀` is essentially the pipeline. `w` is the per-cell rebuild
  cost.
- **The criterion**: `F_new(B)/B` at the ladder's operating page must sit below
  the R = 1 signal, `s(1) = 2,031 – 4,067 B/boundary/write` — call the bar
  **2 KB per boundary per write**, as the bead does.

**Reporting.** Both writes, all three pages, both segments, ranges across the
six rounds — never a mean alone. `F_old − F_new` at each page is the number the
whole write half is for.

**If V1 fails**, i.e. `F₀/B` is still over the bar at every B the worst rung
admits, then the write half has not discharged the bead and the contingencies
are, in order: (i) raise B — `F₀/B` falls as B rises while the R = 20 rung pushes
B down, and V1 plus the witness say whether those two constraints cross; (ii)
accept that `F₀` is the pipeline's real cost, which the floor subtracts, and
carry it as a stated constant of the instrument; (iii) if neither, the write half
is insufficient on its own and
[the option held in reserve](#the-option-held-in-reserve) is what raises the
admissible page. **Do not respond by shrinking the page further** — that is the
move this whole bead exists to record as a dead end.

### Validity witness V2 — the equivalence cross-check

**Configuration.** One page, `P0_ALLOC_CELLS = 1` (B = 4), the full 1/3/7/20
ladder plus the floor, on both segments — measured twice, once under each write.
**One write per window**, and at least five independent windows per cell,
compared by median.

One write per window is deliberate and it is not a fit. At B = 4 a single-write
window is a few tens of KB under either write, so both are comfortably clean and
the comparison is not taken across a refusal. V2 makes no fit and quotes no
slope; it compares `arm − floor` per boundary at each rung between the two
writes.

**The criterion.** `(arm − floor) / B` must agree between the two writes at every
rung, within the observed round-to-round spread. `floor` itself must **drop**.
An agreement in the difference with a drop in the floor is the equivalence
argument's empirical form: the same signal, less machinery. A difference that
moved would say the new write provokes different per-boundary work, and the
write half would be withdrawn rather than re-tuned.

## Part B — the observed-collection witness

### The sample stream, precisely

`alloc-window!` fills `[s0, pre₀, post₀, pre₁, post₁, …]`: one sample before the
window, then a pair around each of the W iterations. So

- **work legs** are `post_k − pre_k`, one per write, and
- **gaps** are `pre_k − post_{k−1}` (and `pre₀ − s0`), between iterations, where
  nothing happens but a loop increment and two array stores.

The witness reads the **legs**. Gaps stay in `rise` as they are today, and are
reported separately as a diagnostic. Note the property gaps have for free:
**nothing allocates in a gap, so a collection in a gap cannot be masked** — it
lands as a negative step and the untouched falls gate takes it.

### The rule

The legs of one window are W repetitions of **one work unit** — the same event,
the same page, the same drain. Absent a collection they should be alike. So:

> **Let `m` be the median of the window's work legs. REFUSE the window if any
> leg deviates from `m` by more than `τ·m`, where `τ` is `ALLOC_LEG_TOLERANCE`.**

Two-sided, on purpose, and the median rather than the mean because the first leg
of a window is the one most likely to sit high.

- A leg **below** the cohort is a leg something removed bytes from. Nothing in
  the work unit removes bytes; the collector does. That is the observation.
- A leg **above** the cohort is not evidence of a collection, but it is evidence
  that the "one work unit" premise the whole witness rests on has failed in this
  window. Refusing is then correct rather than merely conservative, and refusal
  is safe in every reading.

`allocSteps` grows `legs`, `gaps`, `legMedian`, `legWorstDeviation` (relative,
signed) and — mirroring `allocArmSizing` since `rf2-2rtt6.142` — a `refusals`
array with `certified: refusals.length === 0`. One verdict shape across the
preflight and the window gate. `maskable` and `headroom` go with the budget;
`rise`, `fall`, `falls`, `maxStep` and `endpoints` all survive unchanged, so the
published totals keep their definitions.

Worked, against the fixtures the structural file already carries:

| window (per-leg true allocation, reclaim) | legs observed | median | verdict |
|---|---|---|---|
| clean small — `[20K ×4]` | 20K, 20K, 20K, 20K | 20K | **certified** — the gate is not vacuous |
| idle — `[0 ×3]` | 0, 0, 0 | 0 | **certified** — zero deviation from a zero median |
| net-growth masking — `[200K ×4]`, third reclaims 200K | 200K, 200K, 0, 200K | 200K | **REFUSED** — one leg 100% below cohort |
| audit probe A — `[60K ×5]`, fifth reclaims 60K | 60K ×4, 0 | 60K | **REFUSED** — true allocation 300 KB |
| audit probe B — `[50K ×5, 350K]`, sixth reclaims 350K | 50K ×5, 0 | 50K | **REFUSED** — true allocation 600 KB |
| a visible collection — `[20K, 20K]`, second reclaims 40K | 20K, −20K | — | **REFUSED** by the untouched falls gate |

**The probes are refused for every τ < 1**, because in both the offending leg
reads exactly zero against a strictly positive median. So the pinned regression
tests do not depend on the calibration, and a later re-calibration cannot
silently re-admit them. That property is worth a test of its own.

### What it certifies, and what it does not

The old bound tried to prove *no collection ran*. This one does not, and saying
what it does prove instead is the point.

Under the cohort premise — that in the absence of a collection each leg's true
allocation `A_k` lies within `τ` of `m` — an **admitted** window's masked
reclamation in any leg is at most `2τ·m`: a leg can sit `τ·m` high on its own
merits and still be admitted after losing `2τ·m`. So the certificate reads:

> **This window's `rise` under-reads its true allocation by at most 2τ, and here
> is the worst leg deviation actually observed.**

That is a weaker claim than "no collection ran" and a far stronger one than the
old bound could support, because it is *checkable from the window itself* rather
than from a premise about where V8 first collects. And it is the right shape for
this row: under-reading is the direction that manufactures HD-002's predicted
flat-at-zero, so a bounded under-read with the bound printed beside the figure
is precisely the guarantee the row needs.

**What it does not close**, stated rather than glossed, because a witness that
oversells itself is worse than none:

- **A window in which every leg is masked by a similar amount** is homogeneous
  and passes. This is the hole `rf2-n6w7o` already named as unreachable in-page,
  and closing it needs a per-leg allocation counter V8 does not expose. Three
  things stand against it, none of them a proof: the falls gate takes it the
  moment one collection overshoots; this gate takes it the moment one leg runs
  unmasked; and the controls in the same round would read low against their own
  8 B/double prediction if the collector were running that hard.
- **Masking smaller than `τ·m` in a single leg** is invisible, by construction —
  that is what the 2τ statement is for. It is a bounded under-read, not a silent
  one.

### Why this is an observation and not another disputed model

The #7682 audit's objection to the old bound was not that it was too loose. It
was that its premises did not support it: 600 KB is an UPPER bound on where the
first collection runs while safety needs a LOWER one, and halving an upper bound
does not create one; and a masked leg's true `A_k` is not bounded by the
observed `maxStep`, which sees only NET positive deltas. `rf2-2rtt6.141` accepted
both, demoted the bound to refusal-only, and named this witness as its
replacement.

This rule cites neither premise. It makes no claim about where V8 first
collects, and it never infers a leg's true allocation from another leg's
observed step. It asks one question of the data in hand — *do these repetitions
of one work unit look alike?* — and the answer is in the window.

`rf2-2rtt6.141` also carried an acceptance criterion into this package, from the
audit's own closing line: *a witness whose own reliability is unmeasured is a
second thing to distrust.* So `τ` is not chosen by taste.

**τ is calibrated on windows that are independently corroborated clean.** The
control windows are exactly that: a dropped `.slice` of D doubles per iteration
costs a *predicted* 8D bytes, and a control that hit its prediction is positive
evidence of no collection in a way a zero fall count is not — that is the
defence's one valid half, preserved by `rf2-2rtt6.141` and reused here. The two
control sizes bracket the arms' own magnitude (D = 1,000 is 8 KB a leg, D = 400
is 3.2 KB), so the natural leg spread is measured at the scale it will be applied
at. **V3 is that calibration.**

`τ` is **not an env knob**, for `ALLOC_MASK_BUDGET_B`'s reason unchanged: it
decides whether a measurement may be published, and a gate with a dial on it is a
gate that gets dialled.

### Rejected alternatives

| considered | rejected because |
|---|---|
| **`WeakRef` / `FinalizationRegistry` canary** dropped before the window and checked after | Two independent faults. It allocates *inside* the measured region, which is the one thing this instrument may not do. And V8 clears weak references at major-GC boundaries; the collections that fall inside these windows at a few hundred KB are young-generation scavenges, which a canary need not observe at all. Its reliability would itself need measuring — `rf2-n6w7o`'s stated reason for declining route (a) the first time, and still correct. |
| **Promoting `cdpBracket` into a failure path** | It brackets the window from outside and cannot speak to what happened between the in-page samples. `rf2-n6w7o` fenced this explicitly and the fence stands. |
| **`--js-flags=--trace-gc`, parsing the browser's own GC log** | Genuinely independent, and the honest escape hatch if this witness proves under-sensitive. Not taken now: it needs the browser process's stderr piped out of Playwright and correlated to a synchronous in-page loop across two clock epochs that share no origin, and `--trace-gc`'s output is not a stable contract. That is a research task, and the ruling fences one out. |
| **Re-taking a refused window until it certifies** | Selection bias, in the exact direction the row may not fail in. Refused windows are reported and the fit refuses; nothing is re-rolled. |
| **Finer sampling — three or four samples per write instead of two** | A real sensitivity improvement (smaller legs mask less) and allocation-free, since the buffer is preallocated. Deferred rather than rejected: it changes the leg cohort's meaning and would need its own equivalence statement. File it if V3 shows the natural spread is too wide for a useful τ. |

### Validity witness V3 — calibrating the tolerance

**Configuration.** The controls only, at both D values, six rounds, six writes a
window — the row's existing control path, run with the new `allocSteps` fields
recorded. No arms, no browser page beyond the one the controls already need.

**What it answers.** The maximum relative leg deviation across every control
window whose direct reading lands within `ALLOC_CONTROL_SLACK` of 8 B/double.
That is the natural spread of a corroborated-clean window on this rig, at the
arms' own magnitude.

**Setting τ.** A stated multiple of the observed worst deviation, rounded up to
a round number, with both the observed figure and the margin recorded here, in
`p0_run.cjs`'s header, and in the structural pin. The multiple is the
implementer's to choose and to justify in the PR body; a small integer is the
expected answer. **If the observed spread is so wide that no τ below 1 leaves
margin, the witness is not usable as specified** — report that rather than
picking a τ that certifies everything, and the finer-sampling option above is
the first thing to try.

**The floor arm's legs are the harder case** and V3 should say so: under the new
write the floor allocates a few KB a leg, so its relative spread may exceed the
controls'. V3 records the floor's leg spread at each of V1's three pages beside
the controls'. If the floor cannot pass τ, that is a finding about the floor's
measurability at small B, not a reason to widen τ.

### Validity witness V4 — the pinned probes

Hermetic, no browser, in `p0_ladder_structural.test.cjs` beside the existing
masking pins, built from the same `stream(legs, reclaim)` fixture helper.

1. **Probe A** — `stream([60000,60000,60000,60000,60000], [0,0,0,0,60000])`.
   True allocation 300,000 B. Assert the old admission is reproduced as fact
   (`falls === 0`, `rise === 240000`, `maxStep === 60000`) **and** that the
   window is now refused, naming the leg.
2. **Probe B** — `stream([50000,50000,50000,50000,50000,350000],
   [0,0,0,0,0,350000])`. True allocation 600,000 B. `falls === 0`,
   `rise === 250000`, `maxStep === 50000`, refused.
3. **The net-growth masking case**, the fixture already in the file, refused on
   the new grounds rather than on the retired budget.
4. **τ-independence** — both probes refused across a sweep of τ in `(0, 1)`, so
   the pins do not depend on the calibration.
5. **Not vacuous** — the existing clean small window and the idle window both
   certify.

Red-then-green in two commits, as `rf2-n6w7o` and `rf2-2rtt6.142` both did:
the tests first against the unrepaired `allocSteps`, then the repair.

## Constraint semantics — what is retired and what stands

Stated explicitly, because the bead's fences and this package's content can be
read as contradicting one another and they do not.

- **The budget is RETIRED, and retiring is not widening.** `rf2-2rtt6.140`
  criterion 4 sanctions exactly this: replacing the `(W+1)·perWrite ≤
  ALLOC_MASK_BUDGET_B` mechanism with the witness is route (b) itself.
  `ALLOC_MASK_BUDGET_B` and `allocMaxWrites` are deleted, not loosened. Retaining
  the bound as a belt-and-braces refusal was considered and **rejected on
  arithmetic**: at the composed operating point (B = 4, R = 20, W = 6) a window
  is ≈ 630 KB of rise-plus-largest-step, so the retained bound would refuse
  every window the witness certifies and the package would deliver nothing.
- **`ALLOC_FALL_THRESHOLD_B` is not loosened.** It stays at its measured 600,000
  B and stays a recorded fact about the rig, quoted in the header and used for
  reporting — a window's `rise` as a fraction of the threshold is a useful thing
  to print. **It gates nothing.**
- **R = 20 stays.** The ladder is HD-002's own 1/3/7/20 and no rung is dropped.
  The R = 20 rung is what sizes the page, and it is the rung the composed package
  exists to make measurable.
- **`ALLOC_MIN_WRITES = 6` stays a floor**, enforced in the preflight, exactly as
  `rf2-2rtt6.142` left it. The witness has nothing to say about how few writes
  are averaged inside a window; that gate is independent and is not touched.
- **The falls gate stays untouched.** Every window it refuses today it refuses
  after.
- **No published figure is restated** on the strength of this brief. Nothing in
  `validation.md`, `hd-002-adjudication.md` or `decisions.md` moves.

## What this changes downstream

### The preflight, and the seam with rf2-2rtt6.139

`rf2-2rtt6.139` retired `ALLOC_B_PER_BOUNDARY_WRITE = 1655` as a sizing input
effective immediately, ruled that no replacement constant may be substituted, and
sequenced its own final shape — per-rung sizing re-derived from the **new**
instrument's floor data — behind this package. Its interim posture is that the
preflight refuses only on grounds it can defend without a sizing model.

So this package leaves the preflight in exactly that state:

- `ALLOC_B_PER_BOUNDARY_WRITE`, `predictedWindowB`, `headroom`, `maxBoundaries`,
  `floorBoundaries` and the `headroom < 0` refusal all go with the budget.
- **`P0_ALLOC_CELLS` becomes mandatory for the allocation row.** With no sizing
  model there is no honest default, and inventing a literal would be substituting
  a number the bead forbids. An unstated page is refused by name — *"the
  allocation row's page is not derivable until `rf2-2rtt6.139` re-derives sizing
  from the new instrument; state `P0_ALLOC_CELLS`"* — which has the additional
  virtue of making an accidental publication run impossible while criterion 5's
  measurement freeze is in force.
- **`P0_ALLOC_WRITES` defaults to `ALLOC_MIN_WRITES`**, the averaging floor,
  rather than being derived from a bound that no longer exists.
- `rf2-2rtt6.139` then re-derives sizing per rung from V1's floor data and V2's
  per-rung signal, and may restore a derived default on those grounds. **This
  brief derives no sizing constant** — that is `.139`'s and it is sequenced
  behind.

### rf2-2rtt6.142, which is closed and whose shipped behaviour this changes

`rf2-2rtt6.142`'s refusal text **branches**: a page that can still carry six
writes is sent to raise `P0_ALLOC_WRITES`; a page that cannot is sent to shrink
`P0_ROOTS` / `P0_ALLOC_CELLS` and is deliberately told no window count at all,
because naming what such a page admits is naming a below-floor window. The
second branch is computed from the budget. **With the budget retired, its
trigger no longer exists**, and the refusal collapses to the first branch.

That is not a regression — with no page-size model there is nothing to name, and
the property the mayor endorsed (never advise the shape being refused) is
preserved by there being no such advice to give. But `rf2-2rtt6.142` is CLOSED
and its close reason describes the two-branch behaviour, so **the implementer
records the collapse on that bead** when the change lands. Its `ROUTE 2` pin (an
explicit one-write window is refused) survives unchanged and must stay green;
its `ROUTE 1` pin (a large-root default deriving a two-write window) is
superseded by the mandatory-page refusal and is re-pointed there.

### The structural tests, one disposition each

`p0_ladder_structural.test.cjs` is where this change is proved without a
browser, and the implementer should treat this table as the work list.

| current test | disposition |
|---|---|
| `THE DEFECT — a collection fully masked by net growth is REFUSED` | **KEPT**, same fixture, re-pointed at the leg verdict |
| `the gate is not vacuous — a small clean window passes it` | **KEPT**, re-pointed |
| `THE FALLS GATE IS UNTOUCHED — a visible collection still counts as one` | **KEPT VERBATIM** |
| `` `maxStep` is the largest single rising step, not the mean or the last `` | **KEPT** — `maxStep` survives as a reported diagnostic |
| `an idle window is neither maskable nor a fall` | **KEPT**, re-pointed: an idle window is homogeneous at zero and certifies |
| `a row of small clean windows is not a failure` | **KEPT**, re-pointed |
| `a row with no rounds at all is not a failure` | **KEPT**, re-pointed |
| `every over-budget window is named, on every round, with its overshoot` | **KEPT**, renamed — every refused window is named with its reason |
| `the driver exits on THIS function and does not re-derive the budget` | **KEPT**, re-pointed at the new verdict function |
| `` `ladderPlan` states the page on every arm, floor included `` | **KEPT** |
| `the plan the small arm mounts is the ladder plan, at a smaller page` | **KEPT** |
| `the RETENTION ladder is not moved by any of this` | **KEPT and STRENGTHENED** — it must now also assert the retention rows still drive `:p0/write-all` at the published width |
| `the driver REFUSES a mis-sized arm before it launches a browser` | **KEPT** for the surviving refusals |
| `the averaging floor is ENFORCED in the sizing, not merely derived from` | **KEPT** |
| `the refusal names SHRINKING THE PAGE and never shrinking the window` | **AMENDED** — no page-size model remains, so the refusal names the window; the "never advise a below-floor window" property is preserved |
| `the budget is the measured fall threshold HALVED, and pinned at its value` | **RETIRED** → replaced by the tolerance's own pin |
| `the budget is DERIVED from the measured threshold and has no dial on it` | **RETIRED** → replaced by "τ is calibrated, pinned, and has no dial on it" |
| `the boundary is exact — at budget passes, one byte over refuses` | **REPLACED** — at τ passes, one byte past refuses |
| `NET GROWTH CANNOT DEFEAT IT — masking only moves a window toward refusal` | **REPLACED** — a masked leg reads BELOW its cohort, and that is what refuses |
| `THE BOUND IS THE SIZING — (W+1).B.c is what the arithmetic computes` | **RETIRED** with the budget |
| `THE SHIPPED ARM is a few dozen boundaries and is inside the bound` | **RETIRED** — `rf2-2rtt6.139` rebuilds sizing and its pins |
| `the shipped arm takes EVERY write the bound admits at its page` | **RETIRED** |
| `THE PUBLISHED WITNESS is refused by the same arithmetic` | **RETIRED** |
| `the predicted window PASSES the real gate, and the published one does not` | **RETIRED** |
| `a MIS-SIZED arm is refused — one boundary over the bound is over it` | **RETIRED** |
| `` `allocMaxWrites` inverts the BOUND exactly `` | **RETIRED** with `allocMaxWrites` |
| `the sizing cannot buy headroom by shrinking the WINDOW alone` | **RETIRED** |
| `THE CHANGE ONLY EVER REFUSES MORE — admissible is a subset of the old bound` | **RETIRED** — it compares against a bound that no longer exists |
| `` `floorBoundaries` is the largest page that carries the averaging floor `` | **RETIRED** |
| `the window is DERIVED from the bound and the arm from the measured cost` | **RETIRED** |
| `ROUTE 1 — the large-root DEFAULT that cannot fit six writes is refused` | **RE-POINTED** at the mandatory-page refusal |
| `ROUTE 2 — an explicit one-write window on the shipped page is refused` | **KEPT** — must stay green |
| `THE CONTROL — the shipped six-write arm is still admitted` | **KEPT**, at an explicitly stated page |
| `the floor follows ALLOC_MIN_WRITES rather than a number typed beside it` | **KEPT** |
| `a refused arm says WHY, one reason per thing wrong with it` | **KEPT** |
| `a window with no work in it, and a page with no boundaries, are NOT admissible` | **KEPT** |

**New**, beyond V4's five: a CLJS unit test for the write half, beside
`calibration_cljs_test.cljs`, that registers the fixture into a frame seeded at
width 4, dispatches `:p0/write-page`, and asserts the db's `:cells` is four wide
and every mounted `:p0/fan` key answers the new value — hermetic, no browser.
The implementer confirms that build's test roster picks the directory up before
relying on it. Plus wiring pins on `p0_run.cjs` and the CLJS sources: the
allocation window drives `write-page!` and **not** `write-all!`, and the clock
and bulk rows drive `write-all!` and not `write-page!`.

## Supersession honesty

Criterion 6, and it is a fence rather than a formality.

**Once the new write lands, the 2026-08-07 and 2026-08-08 floor-arm and ladder
rows are superseded-not-comparable.** They were taken with a 300-cell write on
pages of 24 and 1,200 boundaries; nothing measured under `:p0/write-page` can be
differenced against them, and no `s(R)` from those runs may be quoted as a
property of the new instrument. They remain valid as what they are: the record
of the write that was, and the evidence base for this brief.

[the-survival-metrics-allocation-half.md](studio/the-survival-metrics-allocation-half.md)
**annotates, never erases.** The implementation PR adds a dated note to that
page marking the affected sections superseded, naming this brief and the bead,
and leaving every figure and every table standing. The one figure that keeps its
full force across the change is `F_old` — V1 re-measures it as its control, and
an agreement at B = 24 is what licenses the comparison at all.

The three instrument blob pins on that page are pins on the *old* instrument.
The new instrument carries its own.

## The option held in reserve

Recorded because it is real and because the next reader should not have to
rediscover it, and **not taken**, because the ruling fences a statistics research
project out and this is a third change to the instrument's shape.

The binding constraint on the page is a window's *total* allocation against the
collection cadence, and the window is `W` writes. But the row already averages on
two axes: `ALLOC_WRITES` inside a window, and `ALLOC_ROUNDS` across windows, with
a mean-of-rounds fit reported beside the per-round fits. **Six single-write
windows, each with its own forced collection before it, carry the same averaging
as one six-write window and are six times smaller** — which multiplies the
admissible B by roughly W at every rung.

The costs are a six-fold multiplication of forced collections and CDP round
trips per rung per round, and a per-round fit that becomes a one-write fit again
unless the fit rule moves to the mean. **Trigger**: adopt it only if V1 and the
witness together show the operating page is too small for a per-boundary figure
to be credible — for instance if B lands at 3 or fewer. File it as its own bead
at that point, with V1's data attached.

## Implementation order

One worker, in this order, so that nothing is measured before the thing that
certifies it exists.

1. **The witness**, in `p0_run.cjs` and `p0_ladder_structural.test.cjs`, with V4
   red-then-green and τ left as a named placeholder the tests do not depend on.
   No browser.
2. **The write**, in `p0_fixture.cljc`, `p0_arms.cljs` and `p0_heap.cljs`, with
   the CLJS unit test and the wiring pins. No browser.
3. **The preflight seam** — the mandatory page, the `ALLOC_MIN_WRITES` default
   window, the retired sizing — and the structural disposition table above.
4. **V3**, calibrating τ; then τ is pinned and the placeholder goes.
5. **V1**, the three-page floor under both writes.
6. **V2**, the equivalence cross-check.
7. **The annotation** on the studio page, and the record of what V1–V4 read.

Criterion 5 stands over all of it: **no allocation window, including any
`rf2-2rtt6.138` re-run, before these validity witnesses are green.** V1–V4 are
the permitted measurements and they publish no slope. `rf2-2rtt6.139` and any
re-run follow them.

## What this brief could not settle

Left open deliberately, with what would settle each.

1. **How much of F ≈ 24.4 KB is the 300-cell rebuild and how much is the event
   pipeline.** Nobody has measured it and this brief may not. It is the single
   number the write half turns on, and **V1 is designed to answer it directly**.
   If `F₀` — the pipeline — dominates, the write half narrows the gap without
   closing it, and the contingency ladder under V1 is what to do next.
2. **The value of τ.** V3's to measure, on the calibration rule stated above.
   The pinned probes are deliberately independent of it.
3. **The operating page.** B, and whether one B serves all four rungs, is
   `rf2-2rtt6.139`'s per-rung re-derivation off the new instrument's own floor
   data. This brief names B ∈ {4, 24, 96} for V1 and B = 4 for V2 as *measurement
   configurations*, not as the row's page.
4. **Whether a 3-to-5 boundary page is a credible witness of a per-boundary
   quantity at all.** The arm's per-page constants — the React root, the frame
   provider, the root subscription — do not cancel against the floor and are
   divided by a very small B. The published quantity is the *slope* across
   rungs, and an R-independent constant lands in the intercept rather than the
   slope, which is the reason to expect this to be survivable; the R = 0 shell
   rung is the direct check, and at B = 24 it already reads close to the floor.
   V2's per-rung comparison is where it will show if it is wrong.
5. **Whether the finer-sampling option is needed.** V3 decides; it is filed as
   an option above rather than designed here.
