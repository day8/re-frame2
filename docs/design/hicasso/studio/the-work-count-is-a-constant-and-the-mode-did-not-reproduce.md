# The work count is a constant, and the mode did not reproduce

Seat: INSTRUMENT + MEASUREMENT RECORD, EP-0038. Bead `rf2-n1b9h`, which asks for
the one observation `rf2-77gz8` needs: a monotone **work counter** read per
measured window, beside the existing byte counters, so that the floor arm's
3,792 B second mode can be read for *how much ran* as well as *how much was
allocated*. Written 2026-08-18 on `worker/workcount-n1b9h`, off `4a5cbfea87`.

**Two things happened here and they must not be read as one.** The instrument
was built, verified against counts it had to hit, and shown not to move the arm
it measures. The **reading the bead asks for was not obtained**, because the
second mode did not occur in any of the six runs taken — so the high-mode
against low-mode comparison has no high-mode window to stand on, and this page
**refuses** to decide between `rf2-77gz8`'s two survivors.

## The answer, first

- **The instrument exists and is verified.** Three monotone counters — event
  handler invocations, subscription recomputations, boundary renders — read at
  every window's open and close. It is verified against **predicted** counts
  rather than merely nonzero ones (below), and its null arm is the control
  windows, which read `0 / 0 / 0` in every round of every run.
- **It does not move the arm.** At `4a1537cb71` the armed instrument reproduces
  `rf2-9jrhi`'s published low-mode figures **to the byte** — 19,100 and 19,540 —
  in four independent runs. At HEAD it reads within **6 B** of
  `bisect-7-head`. The controls read the same three values the whole
  `alloc-9jrhi` corpus reads.
- **The floor arm's work count never moves.** Across 6 runs, 216 arm windows,
  1,512 writes, two substrate revisions and both segments, the census takes
  **exactly one value**: `events = 7`, `subs = 0`, `renders = 0`. Seven writes
  per window, one handler invocation per write, and — this arm holding no
  subscription and re-rendering nothing — no recomputation and no render at all.
- **THE MODE DID NOT REPRODUCE.** Six runs, none high. So the bead's decision
  rule is **not exercised**: it needs a high-mode window and a low-mode one, and
  only low-mode windows exist here.
- **What that does settle**: the round-4 step every run takes is **not** a
  work-count effect. It is present in all six runs at −232 to +176 B, and the
  census is identical on both sides of it in all six.

## The instrument

Three counters, in one preallocated `Float64Array`, incremented at the sites the
bead names:

| Counter | Incremented at | Namespace |
|---|---|---|
| `events` | each `:p0/write-all`, `:p0/write-page`, `:p0/write-one` handler body | `p0-fixture` |
| `subs` | each `:p0/row`, `:p0/field`, `:p0/cell`, `:p0/fan` computation fn | `p0-fixture` |
| `renders` | each subscribing boundary body, and the floor's own per-cell builder | `p0-reagent`, `p0-uix`, `p0-hicasso`, `p0-floor` |

Read in `p0-heap/alloc-window!` at the window's open — in the `let`, before the
first sample — and again after the last leg's closing sample. Both readings sit
**outside** the sampled region, because the snapshot allocates an object and an
allocation instrument may not allocate on the arm's behalf. The increment
itself is an unboxed store into a preallocated slot and allocates nothing, which
is the same argument `alloc-prepare!` already makes for the sample buffer.

### It is OFF at compile time, and that is the whole design

`re-frame.bench.p0-workcount/counting?` is a `goog-define` defaulting to
**false**, and all three call sites are **macros** expanding to
`(when counting? …)`. Under `:advanced` with the flag false Closure
constant-folds the gate, eliminates the branch, and drops the counter array with
it — so **a run that does not ask for the census compiles the bundle this rig
compiled before the census existed**.

That is not tidiness. The rig's blobs are the constancy guarantee the whole
`alloc-9jrhi` series and the `rf2-nkeba` figures are published against, and an
always-on counter would have moved the write path under every one of them. A
macro rather than a function for the reason `re-frame.performance` gives for
`mark-and-measure`: a function-shaped helper forces its call site to survive DCE
even when its body does nothing.

The driver arms it with `P0_WORK_COUNT=1`, which adds the closure-define to its
`--config-merge`. **Both directions are gated before anything is measured**: the
page reports what it was *compiled* with, and a run refuses if that disagrees
with what the driver asked for. Asked-for-and-absent is the dangerous one — every
counter would read 0 for the whole run, which is indistinguishable from a page
that did no work, and "counts identical" is exactly the reading this bead would
otherwise draw a conclusion from.

### Instrument revision

| File | Blob at this page's base |
|---|---|
| `core/test/re_frame/bench/p0_workcount.cljc` | `1787bc0053772c6bd72c3b665db8e8f9be87b2cd` |

The other rig files carry the census call sites and the recording; the census's
own semantics are wholly in the blob above.

## The instrument's positive control — counts it HAD to hit

A census that is merely nonzero has proved nothing. This one was checked against
numbers fixed by the plan before it ran: B = 24 boundaries, a 7-write window, so
a subscribing arm must render **24 × 7 = 168** boundary bodies and recompute
**168 × R** subscriptions. One round of the full ladder plan, census armed:

| Arm | `events` | `subs` | `renders` |
|---|---|---|---|
| `grid/floor` | 7 | 0 | 0 |
| `lad/*#R0` | 7 | 0 | 0 |
| `lad/*#R1` | 7 | 168 | 168 |
| `lad/*#R3` | 7 | 504 | 168 |
| `lad/*#R7` | 7 | 1176 | 168 |
| `lad/*#R20` | 7 | 3360 | 168 |

Every cell is the predicted number, on all four substrate columns
(`lad/reagent`, `lad/uix`, `lad/hicasso` in both segments). The `R0` and floor
rows are the ones that matter for this bead: an arm that reads nothing
recomputes nothing and renders nothing, and the census says so rather than the
docstring saying so.

**The null arm.** The three control windows — `idle`, and the two `.slice`
controls — dispatch nothing and render nothing, and their census read `0 / 0 / 0`
in every round of every run reported here. They are interleaved with the arms on
the same page in the same round, so this is an in-situ null and not a separate
experiment.

## The instrument does not move the arm

The strongest available check, and it is a replication rather than an argument:
run the armed instrument at the substrate revisions `rf2-9jrhi` published, and
compare the steady-state estimand it declared — the median, over certified
windows at round index ≥ 6, of that window's `legMedian`.

| Revision | `rf2-9jrhi`, census OFF | this page, census ARMED | difference |
|---|---|---|---|
| `4a1537cb71` reagent-subs | 19,100 (`bisect-5`, `bisect-6`) | 19,100 (runs 3, 4, 5, 6) | **0** |
| `4a1537cb71` uix-subs | 19,540 (`bisect-5`, `bisect-6`) | 19,540 (runs 3, 4, 5, 6) | **0** |
| `88411ed803` / HEAD reagent-subs | 19,378 (`bisect-7`) | 19,378 / 19,384 (runs 2, 1) | 0 / +6 |
| `88411ed803` / HEAD uix-subs | 19,826 (`bisect-7`) | 19,820 / 19,824 (runs 2, 1) | −6 / −2 |

Four independent armed runs at `4a1537cb71` land on 19,100 and 19,540 **to the
byte**, ten days after the runs they are being compared with. Against a mode of
3,792 B, the instrument's own footprint is at most 6 B and is usually zero.

The controls say the same thing from the other side. Across all six runs, every
round, `idle` / `ctl1` / `ctl2` take exactly **one distinct value each — 16 /
8,064 / 3,264** — which is precisely what `rf2-77gz8` found across the eight
unarmed datasets. The census did not perturb the page the controls see either.

## The census reading

Every arm window of every run, both segments, certified and uncertified alike:

| Run | `implementation/core/src` | reagent-subs | uix-subs | round-4 step | census |
|---|---|---|---|---|---|
| 1 | HEAD (`4a5cbfea87`) | 19,384 | 19,824 | +104 / +176 | 7 / 0 / 0 |
| 2 | HEAD (`4a5cbfea87`) | 19,378 | 19,820 | −232 | 7 / 0 / 0 |
| 3 | `4a1537cb71` | 19,100 | 19,540 | +96 | 7 / 0 / 0 |
| 4 | `4a1537cb71` | 19,100 | 19,540 | +96 | 7 / 0 / 0 |
| 5 | `4a1537cb71` | 19,100 | 19,540 | +96 | 7 / 0 / 0 |
| 6 | `4a1537cb71` | 19,100 | 19,540 | −60 | 7 / 0 / 0 |

`unverified` was **0** in all six runs. The round-4 step column is blank where
no certified window survives in the pre-subset to difference against.

**216 arm windows, 1,512 writes, one distinct value per counter.** The floor arm
does exactly seven handler invocations per window, no subscription
recomputations, and no renders — and it does so identically at both revisions,
in both segments, in every round, on both sides of the universal round-4 step.

## What this decides, and what it refuses

**REFUSED: the choice between `rf2-77gz8`'s two survivors.** The bead's rule
needs a high-mode window and a low-mode window read on the same instrument. Six
runs produced no high-mode window, so the rule has nothing to arbitrate. Nothing
here excludes "different work per write" **as the carrier of the 3,792 B
rider**, and nothing here establishes it. The candidate list is exactly as
`rf2-77gz8` left it.

**Not a rig fault, and the controls are why that can be said.** A run that
failed to reproduce the mode *because the instrument had changed the arm* would
be an instrument fault, and it is the reading this page most had to rule out.
The byte-exact replication above rules it out: at `4a1537cb71` the armed
instrument returns the published number four times over.

**SETTLED: the universal round-4 step is not a work-count effect.** `rf2-77gz8`
established that every run changes level at the round 3 → 4 boundary — the
normal population at −43 to +158 B, the mode at 3,912–3,948 B — and that the
driver has no round-4 behaviour. All six runs here take that step (−232 to
+176 B, widening the normal population slightly at both ends), and in all six
the census is **identical on both sides of it**. So whatever moves the level by
~100 B at round 4, it is not the page doing more work per write. That is a
genuine narrowing and it is the one this page contributes.

It is also, deliberately, a narrowing about the **step** and not about the
**rider**. The mode is the universal step *plus* a discrete 3,792 B, and only
the rider is in question. A reader must not carry this result across.

**The rate estimate, updated but still without useful precision.** The mode has
now been seen **once in fourteen runs** — `rf2-9jrhi`'s eight plus these six —
and once in **seven** at `4a1537cb71` specifically, the only revision it has ever
been seen at. `rf2-77gz8` declined to bound the rate from one observation in
eight and this page declines on the same grounds; what has changed is only that
the denominator is larger.

**One hypothesis is raised and not tested.** `rf2-9jrhi`'s eight runs were taken
beside a working fleet; these six were taken on a box carrying no other
bench-class work, because two heavyweight runs on one machine wedge. If the
transition is a runtime tiering decision sensitive to what else the machine is
doing, an idle box could suppress it. **This is a conjecture with no evidence
behind it** — six runs cannot separate "idle box" from "one in seven" — and it is
recorded so a later window can test it deliberately rather than rediscover it.

**Nothing is concluded about wall-clock**, and no published figure moves. The
estimand, the certificate, τ, the fall threshold and the window shape are all
untouched; the only rig change is a census that compiles to nothing unless asked
for.

## What the next run needs to do, and what it no longer needs to build

The instrument is committed and armed by one environment variable. **A run that
reaches the high mode now answers `rf2-77gz8` from its own record**, with no new
instrument and no second run: the record carries `work0`, `work` and
`workDelta` on every window beside `legMedian`, so the comparison is a read
rather than a measurement. `rf2-77gz8`'s second deliverable — the within-run
level witness, held at n = 1 — is likewise still waiting on the same event, and
this page did not supply it.

## Reproduction

From the **repository root**. The census is the only thing added to
`rf2-9jrhi`'s command:

```bash
# runs 1-2, at HEAD
P0_WORK_COUNT=1 P0_PORT=8449 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/workcount-n1b9h/run1-head.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc

# runs 3-5, with the substrate at the revision the mode was seen at
git checkout 4a1537cb71 -- implementation/core/src
P0_WORK_COUNT=1 P0_PORT=8451 P0_ALLOC_PLAN=floor P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=18 \
P0_RAW_OUT=implementation/hicasso/test/re_frame/bench/hicasso/data/workcount-n1b9h/run3-a4a1537cb71.json \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
git checkout HEAD -- implementation/core/src
```

The file set under `implementation/core/src` is identical at the two revisions
(88 files either side, none added and none removed), so the plain checkout is
exactly equivalent to `rf2-9jrhi`'s `rm -rf` form and no file is left behind.

Every run exits **1** as captured by the invoking shell. That is the expected
code and not a failure of the window, exactly as `rf2-9jrhi` recorded for all
eight of its runs: `--only alloc` exits non-zero whenever any window is refused
or any collection falls inside a measured one, and both are routine at this
page. The controls, the read-back verification and the per-window certificate
are what decide admissibility.

The instrument's positive control is one round of the full ladder plan:

```bash
P0_WORK_COUNT=1 P0_ALLOC_PLAN=full P0_ALLOC_WRITE=all \
P0_ROOTS=4 P0_ALLOC_CELLS=6 P0_ALLOC_ROUNDS=1 \
  node implementation/core/test/re_frame/bench/p0_run.cjs --only alloc
```

Its arm figures are refused — one round is too few, and the ladder's wide rungs
collect inside their windows — and nothing here quotes them. What it is run for
is the census table above, which is not a measurement and does not need a
certified window.

The six datasets are committed beside this page under
`implementation/hicasso/test/re_frame/bench/hicasso/data/workcount-n1b9h/`, on
the convention `rf2-2rtt6.138` set and `rf2-erre5` wrote down. Each retains
every window's raw sample stream **and its work census**, so both can be
re-derived without a browser.

## Related

- [The second mode is per-write, and the controls never move](the-second-mode-is-per-write-and-the-controls-never-move.md)
  — `rf2-77gz8`, which narrowed the mode to two candidates and named the
  instrument this page builds.
- [The bisect is flat, and the floor has a second mode](the-bisect-is-flat-and-the-floor-has-a-second-mode.md)
  — `rf2-9jrhi`, the window that measured the mode and committed the eight
  datasets this page replicates against.
- [The arms' spread does not collapse (τ refused)](the-arms-spread-does-not-collapse.md)
  — `rf2-rs8q6`, the SPREAD within a window; this page is about the LEVEL, and
  about what ran inside it.
