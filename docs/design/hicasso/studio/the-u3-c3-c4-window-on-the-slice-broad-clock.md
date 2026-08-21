# The `U3`/`C3`/`C4` window on the slice broad clock, pre-registered

**`rf2-9wmqd`, 2026-08-22.** This bead has three items and two of them have
landed: item (a), the donor arm and the second driver, in PR #8599, and item
(b), the suite-level self-test plus the two measurement-fidelity repairs PR
#8599's audit found, in PR #8606. What has been owed since is **item (c) — one
quiet-box window, and nothing else**. There is no gate to build, because
[budgets §7](../../../../implementation/hicasso/spec/budgets.md#7-where-each-row-is-enforced)
routes every distributional row to pinned evidence runs and forbids converting
one into a pull-request threshold. This page is that window's pre-registration
and its record.

It is written in two halves, and **the first half was committed before the
runner was invoked once**, so the declared invocation count and the adjudication
rules below are commitments rather than descriptions of what happened.

**One thing is decided in advance and is not a reaction to a number**: this
window can move a status cell for at most a proper subset of the three rows it
reads, and [§4.1](#41-u3-is-narrower-than-its-name-and-the-narrowing-is-mechanical)
and [§4.6](#46-c3s-best-relevant-adapter-is-not-this-windows-to-settle-either)
say which and why, from the instruments' own docstrings, before any figure
exists.

---

## 1. Declared invocations

| # | purpose | figures quotable? |
|---:|---|---|
| 0 | feasibility — does the rig build, boot, get past its five pre-window controls and complete a plan at all | **no**, by declaration and before it was taken |
| 1–3 | the evidence window | yes, if admissible by [§2](#2-what-makes-a-window-admissible-here) |

**Three and not one, and the reason is the estimands rather than taste.** Two
different kinds of quantity are read here and each wants the same answer to the
same question. `U3` is a **tail quantile of a latency**, which one scheduler
hiccup can move; `C3` and `C4` are a **ratio of two within-round medians**,
which is a difference of two small numbers and can move for reasons neither arm
owns. Three invocations say whether each reproduces before a line is called on
it. A verdict is taken only where all three agree; where they do not, the
disagreement is the finding and no cell moves.

**Invocation 0 is declared unquotable in advance rather than discarded
afterwards.** A feasibility invocation whose figures stay available for quoting
is a fourth run waiting for a use. The rule this page inherits from
[the `U1`/`U2` window](the-u1-u2-window-on-the-slice-echo-clock.md) is the `C1`
anchor's: a series that stops early is not a measurement, and a run that fails a
control is excluded and reported, never silently replaced.

**All three are taken.** If invocation 0 shows the rig is broken, that is the
window's finding and no evidence invocation follows it.

## 2. What makes a window admissible here

**An exit code is not the verdict**, and on this lane that is a recorded
incident rather than a caution: `run.cjs`'s own header records that
`shadow-cljs` exits `0` on a build that merely warned, which is why
`lane_build.cjs` exists. So a window is admissible when every check below is
read out of the run's **own output** and each is affirmative, with the captured
exit code quoted last and as corroboration.

This driver carries **seven** exit-bearing checks, two more than its sibling,
and the two extra are the ones a comparative row needs:

1. **The arm-order self-test**, run in the page before anything is measured.
   `-main` refuses to boot the plan if `lane/self-test!` returns false.
2. **The fairness gate**, `assert-parity!` — `lane/canonical` over both
   containers on the seeded page, attribute names sorted so the comparison is of
   the DOM and not of two serialisers. It **refuses the run** when the two arms
   are not building the same page.
3. **The fairness gate's own negative control**, `parity-discrimination!` —
   the donor frame's locale is moved to the other locale outside every window
   and the same comparison is required to **disagree**, then put back and
   required to agree again. A gate nobody has seen refuse is a gate nobody
   should trust.
4. **The four echo negative controls**, `echo-discrimination!` — the two
   `:locale` arms' setup mutation performed **alone**, with no `change` event
   and therefore no handler, no dispatch, no state write and no commit; and the
   two `:theme` arms with no interaction at all, asked for the other theme's
   token. All four must **refuse**.
5. **`N unverified of M`** over every window taken, warm-up included.
   `lane/assert-verified!` throws on any nonzero count. The `:locale` echo is
   the application's own `<h1>` text in the target language — which only a React
   commit writes — and the `:theme` echo is the page root's inline
   `background-color` against the target theme's `:surface` token, which a
   button's activation behaviour does not write.
6. **The positive control**, `:ctl-blocked` — the locale operation plus 50 ms of
   blocked main thread on the seam **between the commit and the frame**,
   adjudicated by `lane/control-verdict-strict` at ±50%, so the band is
   `[25, 75]` ms and **every round** must sit inside it, not merely the range.
7. **The arm-order verdict** over the samples, `lane/guard!` at tolerance
   `0.10` — exit `2`, figures not quotable.

**Check 6 adjudicates the window and not merely the sensitivity, which is why
this page reports its measured value rather than the word `ok`.** The injected
cost sits after the commit it would have stopped at, so a rig that had quietly
reverted to a commit-bounded window would report a control that predicted 50 ms
and measured nothing. The ±50% band is derived from the frame grid rather than
tuned: every window ends at the first rendering opportunity at or after its own
work, so the injected duration reaches the difference **rounded to the grid**.

**Checks 2 and 3 are what a comparative row rests on and check 5 cannot
replace.** A per-sample echo says each arm's operation reached its own page; it
says nothing about whether the two pages were the same page to begin with. The
sibling window needed neither because it has one arm.

Two further conditions are this window's own:

- **Five rounds**, which is what `lane/across-rounds` and
  `control-verdict-strict` are shaped for, and which this page does not touch.
- **A quiet box**: no other worker holding the machine at all — not one
  bench-class worker, not a peer running any gate. The brackets are
  [§5](#5-the-box) and [§8](#8-conditions).

## 3. The schedule is taken unchanged, and that is a derivation rather than a default

The instrument's `sampling` knob reads `{:warmup 8 :samples 12}` and its
docstring anticipates this window raising it: *a tail quantile over 12 is mostly
interpolation … so the run that reads this instrument will want more, and
raising these two is how it gets them.* It also states that raising them is
**safe here**, which is a property of `pre-state` rather than of the numbers:
each arm's page state is a pure function of its own visit index, so two paired
arms see the same state mix at every `:samples` — where the file's first cut,
which let the arms inherit each other's state, equalised at 12 and diverged at
13 and at 20. The knobs are schedule knobs and never lines, so raising them
would breach nothing.

This window does not raise them, for three reasons, none of them restraint.

**First, what `n` the published figures are actually taken over.** `:summary` is
`lane/summarise` over each arm's readings **pooled across rounds**, so at
`{:warmup 8 :samples 12}` over five rounds it is `n = 60` per arm and not 12.
Under `lane/quantile`'s `h = (n-1)q` that puts `p95` at `h = 56.05`, with three
measured readings above it, and `p99` at `h = 58.41` — between the top two order
statistics, the region `lane/quantile`'s own docstring prices as a value *no
reading in the sample ever took*.

**Second, the interpolation is not load-bearing in the direction that decides
`U3`, and that is arithmetic rather than hope.** No quantile estimator can
answer above the sample maximum. If an arm's `:max` sits at or below the line
then every quantile of that arm sits at or below it too, on a reading that was
**taken**. `lane/summarise` publishes `:max` beside `:n`, so the check is in the
record the driver already prints. Where `:max` misses and the published `p95`
meets, that half is **not decided here** — reported with its interpolation
weight, with the finding that resolving it wants a longer schedule, which is a
second window and not this one's to open.

**Third, the comparative figures do not use a tail quantile at all.**
`:comparative` is `lane/ratio-between` over the per-round arm-to-floor ratios,
each of which is built from `lane/normalise`'s within-round **`:p50`**. A median
at `n = 12` is an order statistic, not an interpolation into a tail, so
`:samples` buys `C3` and `C4` far less than it buys `U3` — and it buys `U3`
nothing at all if `:max` decides it.

`rounds` is left at five for the reason its own docstring gives. And leaving
both alone has a second payment: the instrument this window reads is
**byte-identical to the pair that landed under PR #8599 and PR #8606**, so the
blob table in [§6](#6-the-instrument-and-the-subject) pins the shipped files and
nothing here had to be re-validated.

## 4. The pre-registered adjudication rules

The three rows, transcribed from
[the ledger](../../../../implementation/hicasso/spec/budgets.md#9-the-budget-line-reconciliation-ledger)
and, for `C3` and `C4`, from
[§4's comparative table](../../../../implementation/hicasso/spec/budgets.md#the-comparative-and-regression-rules):

| row | registered line | estimand | arms in the population |
|---|---|---|---|
| `U3` | ≤ 100 ms `p95` for broad operations | operation latency | `:locale`, `:theme` |
| `C3` | ≤ 1.25x the best relevant adapter on broad updates | ratio of broad-update latency | `:locale`/`:donor-locale`, `:theme`/`:donor-theme` |
| `C4` | no sustained 1.5x as ordinary Hicasso | the same ratio, sustained | the same two pairs |

`:idle-frame` is the floor and `:ctl-blocked` is the control. Neither is in any
population, and neither is read against a line.

**All three are distributional, and the mayor was right not to assert it
without a reading.** `U3` is named so twice and in terms: §6's *user-visible
budgets* table gives its Family as `distributional`, and §7's routing table
carries it in `S1–S8, U1–U4`. `C3` and `C4` are distributional **by the gate's
own rule rather than by a roster**: `check_budget_ledger.py`'s
`DETERMINISTIC_IDS` is `D1–D26`, `U5`, `U6` and `I9`, every other registered id
falls to the `else` branch, and that branch refuses any row naming the `PR gate`
lane with the words *is a distributional row wired to the PR-gate lane*. §9.1
states the same rule in prose — *a distributional row may never name the first
lane at all* — and the driver's own docstring already applies it to these exact
three. **§7's prose table is where this is thinnest**: its distributional row
enumerates `S1–S8, U1–U4` and names neither `C3` nor `C4`, and in fact §7 names
no enforcement home at all for `C1`, `C2`, `C3`, `C4`, `C7` or `C8`. The gate
covers them; the roster does not mention them. That gap is recorded here and on
the bead as a finding, and it is **not** repaired inside this window — a
measurement window does not edit the page it is measured against, and the repair
is a roster line, not a line, band or threshold.

### 4.1 `U3` is narrower than its name, and the narrowing is mechanical

`U3` is registered over *broad application operations*. The sibling driver
glosses that class with three exemplars — *a route change, a reset, a save
reply* — and **not one of the three is measured by this window**, for reasons
this driver states at source before any number exists:

- **A route change and a save reply cannot be bracketed by this window at all.**
  `routing/activate-link!` ends at `router/dispatch!`, the **async door**: the
  click returns with the navigation merely enqueued and the router drains it on
  `interop/next-tick`, a next-turn task. A window that stops at the first paint
  after the click stops **before the operation has begun**. The slice's own
  `flow-dom-cljs-test` records the split as *two clicks, two settling rules*,
  and every async mutation reply in any re-frame2 application arrives through a
  router drain, so a save reply is the same case. A row over either would be a
  reading of the click that requested the operation, not of the operation.
- **A reset is synchronous and reachable, and is simply not on this page.** It
  lives on the article route behind a draft that has to be dirtied outside the
  window, and this driver opens on the feed.

What **is** measured is two operations of the same **class** as the reset —
synchronous, through the runtime's frame-locked door, each moving one key in
`app-db` and invalidating every boundary on the page that read a string or a
token. So the instrumented population is *one class of broad operation out of
two*, and the uninstrumented class is the larger of the two by exemplar count.

**This is stated here because a `MET` on `U3` would be read as a statement about
all broad operations.** The consequence for the ledger is
[§4.3](#43-u3s-two-limbs); the consequence for a reader is that no figure on
this page may be quoted as *`U3` on the slice* without the words *on the
synchronous class*.

### 4.2 The phase caveat, which cuts one way only

Every window on this instrument starts in the first task after a paint — the
phase with the **longest** wait to the next rendering opportunity — because the
alternative is a predecessor-dependent reading the arm-order guard correctly
refuses. A reading taken here is the conservative end of the phase distribution,
*the end that cannot flatter the application*. So a **meet** holds a fortiori at
a user's uniformly random phase.

Against a 100 ms line the caveat's other half — that a miss by less than one
rendering interval is undecidable without a randomised-phase driver — is
unlikely to bind, because one interval is `~16.7 ms` and the line is six of
them. It is registered anyway, as clause 4 of [§4.3](#43-u3s-two-limbs), so that
it is not invented afterwards if it does.

**On the comparative rows the caveat cuts differently and worse**, and that is
[§4.4](#44-the-resolution-gate-which-runs-before-any-c3-or-c4-figure-is-quoted):
the same interval sits in *both* arms of a ratio, so it does not cancel out of
the estimand, it drives the ratio toward `1.00x`.

### 4.3 `U3`'s two limbs

**The numeric limb**, which decides the class that is measured. For each of
`:locale` and `:theme`, in each admissible invocation:

1. `:max` ≤ 100 ms → the line is met on measured readings, whatever the
   estimator, and [§3](#3-the-schedule-is-taken-unchanged-and-that-is-a-derivation-rather-than-a-default)'s
   refusal condition never fires.
2. `:max` > 100 ms and published `p95` ≤ 100 ms → met on an estimate with three
   measured readings above it; report the figure and say so.
3. Published `p95` > 100 ms by more than the spread across the three invocations
   **and** by more than one measured rendering interval → the line is missed on
   the measured class.
4. Otherwise → not decided by this window; report and refuse.

A numeric outcome requires the **same** outcome on both arms in **all three**
invocations. Two event paths disagreeing is a finding about the row rather than
a reason to quote the kinder one.

**The coverage limb**, which decides the **row**, and which is settled before
the run rather than after it. `U3`'s registered line quantifies over *broad
application operations*; [§4.1](#41-u3-is-narrower-than-its-name-and-the-narrowing-is-mechanical)
establishes from the instruments' own docstrings that one structural class of
that population is unreachable by this window's mechanism. **Whether a line
stated over the whole class is decided by the synchronous class alone is a
ruling, not a measurement**, and this window declares in advance that it will
not make it — for exactly the reason the sibling refused to choose between
`U1`'s two readings of *within one 60 Hz frame*.

So, pre-registered:

- **`U3`'s status cell does not move in this window**, whatever the numbers say.
- What the window publishes instead is the figure on the measured class, pinned
  to a tree; the margin; the named reason the second class is out; and the shape
  of the instrument that would reach it — a window bounded by the **router
  drain** and the paint that follows it, which is not this mechanism and is not
  built.
- If the operator rules that the synchronous class decides the row, the cell
  move is a one-line edit in each of two files with this window's evidence
  beside it. That is stated so the ruling is cheap to act on, not to invite it.

### 4.4 The resolution gate, which runs before any `C3` or `C4` figure is quoted

**A paint-bounded window is dominated by the wait for the browser's next
rendering opportunity, and that wait is in every arm.** Two arms that each wait
one interval read `1.00x` whatever their substrates cost. The driver publishes
`:over-floor` for precisely this: `lane/across-rounds` over each arm's ratio to
`:idle-frame`, whose `:straddles-1?` flag is the resolution test. An arm whose
range against an **empty frame** includes `1.0` was not separated from the floor
by this window, and no ratio between two such arms is a reading about anything.

Pre-registered, per pair:

> If **either** arm of the pair carries `:straddles-1? true` in `:over-floor`,
> in **any** admissible invocation, the pair is **not resolved** and no `C3` or
> `C4` verdict is taken from it. The `:comparative` range is still published,
> labelled unresolved, and it may not be quoted as a substrate comparison.

**Where the gate refuses, the conclusion is the driver's own and is written down
in advance so it cannot be read as a rationalisation**: if the shipped seed
cannot separate the measured arms from the floor, `C3` needs a **broader
population** than the slice's feed page — which is a finding about the row, and
never a licence to widen a band or to quote an unresolved ratio. The `:structure`
block's `:commit` leg is reported beside it, because the application's own work
sits outside the frame grid entirely and says how much there was to separate.

### 4.5 `C3` and `C4`, where the gate passes

For each resolved pair, over `:comparative`'s per-round ratios — each of which
is a ratio of two within-round `:p50`s, the floor having cancelled:

**`C3`**, against `≤ 1.25x`:

1. The per-round range lies entirely at or below `1.25` in all three
   invocations → decided on the meeting side.
2. The range lies entirely above `1.25` in all three → decided on the failing
   side. Where it also lies entirely at or below `1.5`, §4's disposition is the
   **warning band** — *attribute cause, one bounded topology pass, test a local
   island* — which is work with an owner, not a second line.
3. The range crosses `1.25` in any invocation → not decided; report and refuse.

**`C4`**, against *no sustained 1.5x*:

1. Every per-round ratio in all three invocations, on both resolved pairs, at or
   below `1.5` → nothing sustained above the line was observed **on this
   population**.
2. Every per-round ratio in all three invocations above `1.5` on at least one
   pair → sustained, and the rule fires.
3. Anything between → not decided. A ratio above `1.5` in some rounds and not
   others is the one thing the word *sustained* excludes.

### 4.6 `C3`'s *best relevant adapter* is not this window's to settle either

The donor arm's own docstring is unambiguous and is quoted rather than
paraphrased: **this does not decide `C3`'s *best relevant adapter*, and no file
in a bench tree could.** What a run reading this instrument may conclude is
`Hicasso / UIx` on the slice's broad updates; concluding *≤ 1.25x the best
relevant adapter* additionally needs the Reagent-on-subs arm, and that arm needs
a second page because `rf/init!` installs one adapter per process.

The same docstring carries the argument that would bridge the gap — UIx was
chosen because `hd8-composed-donor-arm.md`'s re-taken rows read *on this clock,
direct UIx is now the faster donor*, so a `≤ 1.25x` claim against the faster of
the two donors survives the other. **That argument is transferred, not
measured**: those rows were taken on a different clock over a different page,
and whether UIx is also the faster donor on the slice's feed page is not read
here. So it is reported as a transfer with its source named, and it does not
convert a `Hicasso / UIx` ratio into a `C3` verdict.

Pre-registered, therefore, and symmetrically with `U3`:

- **`C3`'s status cell does not move in this window.** What moves the row is
  either a ruling that UIx is the relevant adapter for this population, or a
  second-donor window on a second page.
- **`C4`'s cell may move only on the failing side.** *Sustained > 1.5x* is a
  prohibition: observing it fire against **any** relevant adapter decides it,
  because a ratio above `1.5` against the faster donor is above `1.5` against a
  donor no faster. Observing it **not** fire against one donor does not decide
  it, for the same reason `C3`'s meeting side does not.
- Where the resolution gate refuses, neither cell moves and the reason is
  [§4.4](#44-the-resolution-gate-which-runs-before-any-c3-or-c4-figure-is-quoted)'s.

### 4.7 What this window may not do, whatever it reads

- **No threshold is guessed and no band is widened.** The `100 ms`, `1.25x` and
  `1.5x` lines are the registered ones; `control-slack`, `blocked-ms` and the
  guard tolerance are the instrument's and are not this window's to move.
- **No gate is built.** All three rows are distributional
  ([§4](#4-the-pre-registered-adjudication-rules)): §7 routes them to pinned
  evidence runs, §9.1 says such a row may never name the first lane, and
  `check_budget_ledger.py` enforces it. The edit-shaped half of this bead **was**
  the instrument, and items (a) and (b) built it.
- **No instrument is edited, and no arm is added.** Not the reset arm, not a
  drain-bounded window, not a Reagent donor, however obvious each becomes once
  the numbers are in. The window's own terms forbid improving the rig
  mid-measurement; each is filed and run as its own window.
- **A population cell is promoted only by the deliberate act the gate names.**
  `POPULATION_PIN` pins `U3` and `C3`/`C4` to `—`, and a `—` row must read
  `UNPINNED` with no figure. Moving one is *a new measurement window and an edit
  here*, in the constant's own words.
- **`U4` is not in this window.** It is registered over dragging and animation,
  the slice publishes neither, and the repair is a third driver on the ledger
  witness — filed as `rf2-xc0bw` and landed there.

## 5. The box

Measured on 24 logical cores, standalone, never sampled inside a run. Occupancy
is summed per-process CPU-time deltas over a five-second bracket divided by the
core count, never `LoadPercentage`; the processor queue length is the decisive
number, because it says whether anything was *waiting* for a core.

The scout reading, taken while this half was being written and before anything
was built:

| quantity | reading |
|---|---|
| `\System\Processor Queue Length`, 8 samples | **0** on every sample |
| real CPU occupancy, two 5 s brackets | 6.52%, 7.90% |
| top consumer, attributed | the operator's editor, 4.14% |
| `java` processes | **0** |
| shadow-cljs / bench / Playwright command lines | **0** |
| `headless_shell` processes | **0** |
| `node` / `chrome` / total processes | 22 / 109 / 554 |
| free physical memory | 14.17 GB |

**One long-lived process on the box is reported rather than counted as zero.**
An orphaned `npm run ssr:hicasso-serve -- --port 8139` has been listening since
`2026-08-21 02:55`, and in the 24.8 hours since it has consumed **0.77 seconds**
of CPU in total. It is not this window's, it predates it by a day, and it is
left alone rather than killed — the rule on this lane is *kill only the one you
can show is yours*. It is not a peer running a gate and it does not contend for
a core; what it does hold is **port 8139**, which is the port this driver's own
docstring names. [§6](#6-the-instrument-and-the-subject) therefore runs on a
different port, and `HICASSO_PORT` is a runner variable that reaches no figure.

The opening and closing brackets of the window itself are in
[§8](#8-conditions), and the opening one is taken **immediately before the first
evidence invocation** rather than at the time of writing.

## 6. The instrument and the subject

Tree anchor `c23a23dd1da44bb2b9eb84d11846dd714d04bc3a`, which is `origin/main`
at the window's opening. Object ids below are the committed objects
(`git rev-parse HEAD:<path>`) rather than a byte digest of the working file,
which on a checkout with `core.autocrlf=true` is the only reading that means
anything.

**This page's branch was rebased once after the last invocation**, onto a trunk
that had taken twelve commits — two pull requests plus tracker checkpoints — so
the published tree is not literally the measured tree. **Every object id in the
three tables below was re-read at the new base and every one is unchanged**,
which is the claim that matters: the rebase moved nothing this window measured.
It is stated rather than assumed because a pre-rebase green is evidence about a
tree that no longer exists, and the four gates in [§8](#8-conditions) were
re-run on the final base for the same reason.

The instrument:

| file | blob |
|---|---|
| `bench/hicasso/slice_broad_clock_app.cljs` | `bc3863e9c6946fd84b9f2965b9fd5ad8d38bbac7` |
| `bench/hicasso/slice_donor_views.cljs` | `45a864a3716f158d70a0a36a96fea43c136abaf0` |
| `bench/hicasso/slice_echo_clock_app.cljs` | `979dd3413390e489beab26f004743c53da72fd07` |
| `bench/hicasso/lane.cljs` | `3d466f77e908d502835de5682e0c6d4b20b1d39e` |
| `bench/hicasso/run.cjs` | `da8a2f3723bfd3345f392e29c1344c582a30b736` |
| `bench/hicasso/lane_build.cjs` | `c55771d6c90d5dab53bfb02af48c6fcbcf49cffd` |
| `bench/order_guard.cljc` | `d57c25473360ba8a464cb9107152288b79303e84` |
| `bench/navigate.cjs` | `d8f30bbab93e850ec6b37b9a6de50f295601b02b` |
| `bench/lane_cache.cjs` | `ec3c60f44fa46f2bbb11ae908749cf93742b27dc` |

`slice_echo_clock_app.cljs` is in that table because this driver **requires**
its `window!`, `after-paint`, `measured-mask` and `structure-over-measured`
rather than transcribing them, so the two drivers cannot disagree about what a
window is. Its blob is the one the `U1`/`U2` window read.

The subject is the slice witness application, mounted through the package's own
`h/mount!` with the application's own views and its own initial events. Its
files, under `implementation/hicasso/test/re_frame/hicasso/examples/slice/`:

| file | blob |
|---|---|
| `events.cljs` | `8e7d80f102606663b0ff8402c992ccd7dcbcbb96` |
| `routes.cljs` | `76c1aa5314a8f827d5179dc06f654d4c9d68d8d3` |
| `views.cljs` | `dd6ca5425827bbecbf990622e02232f494ade1f4` |
| `subs.cljs` | `aeb28c93792c8e426d8c83e5e635db7681a75fa7` |
| `db.cljs` | `57ea222f7bc8b5d2335d6ac4d3c1ca734e7ea65f` |
| `i18n.cljs` | `1ff3dbc140d51adec9b6b74792a567b7aaa8c3e4` |

The package doors it mounts through, under `implementation/hicasso/src/`:

| file | blob |
|---|---|
| `re_frame/hicasso.cljc` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` |
| `re_frame/hicasso/impl/mount.cljs` | `77c367ca6324435d4ae83146bb3a152833ef17c3` |
| `re_frame/hicasso/impl/controlled.cljs` | `dbe21f4ebe8c21eece34ef4414524d0052c662dd` |
| `re_frame/hicasso/impl/collector.cljs` | `bfb1c37f84b14c8d701da41c97883a24740dba52` |

Reproduce, from the repository root:

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.slice-broad-clock-app/-main \
HICASSO_OUT_DIR=out/hicasso-slice-broad \
HICASSO_PORT=8143 \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

The driver's docstring names `8139`; this window used `8143` for the reason
[§5](#5-the-box) gives, and the variable reaches nothing but the local HTTP
server the runner starts. No new build id: the driver takes its entry from
`HICASSO_INIT_FN` and rides `:hicasso-bench`, the id the whole lane already
shares, so this arm costs `implementation/shadow-cljs.edn` — an HD-017 hot-zone
file — nothing. Exit `1` on a build that merely warned, a page error, a fatal
the page recorded, a parity refusal, a control that did not discriminate, an
unverified echo or a failed positive control; exit `2` if the arm-order guard
refuses.

---

## 7. The runs

**One feasibility invocation and three evidence invocations, as declared. Three
admissible, none excluded, nothing above this line edited once the runner was
first invoked.** Each invocation cleared the build id's cache entry and
recompiled its own `:advanced` bundle, so no two shared a build.

### 7.1 Invocation 0, and what it bought

The feasibility invocation built in 21.76 s (202 files, 147 compiled, **0
warnings**) and completed in about a minute, with every check in
[§2](#2-what-makes-a-window-admissible-here) affirmative and captured exit `0`.
Its figures are not quoted anywhere on this page, which is what was declared.

What it established is that the rig builds, mounts both arms, gets past the
parity gate **and** past the parity gate's own negative control — the two checks
the sibling window has no counterpart for — and completes a full plan, so the
three evidence invocations could be taken back to back inside one box bracket.

It bought one thing more, and it is worth naming because it is the reason the
window is worth reading at all: `shadow-cljs` printed its own config path,
`…\re-frame2-worktrees\broadwin\implementation\shadow-cljs.edn`, so the build
that produced every figure below is positively shown to have read this
worktree's tree and not a sibling's.

### 7.2 The seven exit-bearing checks, per evidence run

| check | run 1 | run 2 | run 3 |
|---|---|---|---|
| arm-order self-test, 12 cases | all `ok` | all `ok` | all `ok` |
| parity gate — both arms build the same page | passed | passed | passed |
| parity gate's negative control — a moved donor locale must DISAGREE | passed | passed | passed |
| four echo negative controls — all four must REFUSE | passed | passed | passed |
| echo tally, `N unverified of M` | **0 of 600** | **0 of 600** | **0 of 600** |
| positive control, per-round difference against a `50 ms` prediction | 34.90, 34.45, 34.80, 33.90, 34.50 | 34.95, 34.60, 34.80, 34.65, 34.75 | 35.25, 33.95, 34.40, 34.55, 34.30 |
| `control-verdict-strict`, band `[25–75]`, every round | `ok`, `:outside []` | `ok`, `:outside []` | `ok`, `:outside []` |
| arm-order verdict, by predecessor and by phase, tolerance 10% | `reportable` | `reportable` | `reportable` |
| rounds completed | 5 | 5 | 5 |
| captured exit code | `0` | `0` | `0` |

**The positive control's measured value is `~34.5 ms` against a `50 ms`
prediction, and that is the instrument behaving as its own docstring predicts
rather than a control missing by a third.** Both arms start at the same phase,
so each round's figure is a difference of two paint-bounded windows; the
injected block does not add to the wait the unblocked arm pays, it **subsumes**
it and leaves whatever is left of the interval it landed inside. The residual is
`50 − 34.5 = 15.5 ms`, one measured rendering interval — the floor arm reads
`p50 16.6`–`16.7 ms` — so the control lands one grid interval below its
prediction, in the direction and by the amount `control-slack` derives from the
grid. It is also the sibling instrument's figure, `~34.3 ms`, taken on the same
`window!` over a different page, which is what a shared mechanism should do.

**The parity gate and the echo controls are affirmed by the run's progress past
them rather than by a printed verdict, and that is worth saying plainly.** Each
throws or it does not, so its all-clear is an absence. What positively witnesses
them is `slice_broad_window_dom_cljs_test.cljs`, the suite item (b) landed,
whose namespace the PR-blocking `:browser-test` selector matches — so those
mechanisms are exercised on every pull request that arms this surface, not only
here.

### 7.3 The readings, `:ms` — one broad operation through to the paint that follows it

`n = 60` measured windows per arm per run, pooled across five rounds.
Milliseconds.

| arm | run | min | `p50` | `p95` | `p99` | max |
|---|---|---:|---:|---:|---:|---:|
| `:idle-frame` (floor) | 1 | 15.2 | 16.60 | 17.60 | 17.82 | 18.0 |
| | 2 | 14.9 | 16.70 | 18.00 | 18.21 | 18.5 |
| | 3 | 15.1 | 16.60 | 17.61 | 18.28 | 18.4 |
| `:locale` | 1 | 15.6 | 17.35 | 18.41 | 18.96 | **19.2** |
| | 2 | 15.8 | 17.30 | 18.51 | 18.86 | 19.1 |
| | 3 | 16.3 | 17.35 | 18.50 | 19.06 | **19.3** |
| `:donor-locale` | 1 | 16.5 | 17.35 | 18.41 | 18.70 | 18.7 |
| | 2 | 15.8 | 17.20 | 18.40 | 18.54 | 18.6 |
| | 3 | 15.7 | 17.25 | 18.41 | 18.84 | 18.9 |
| `:theme` | 1 | 15.5 | 16.90 | 18.20 | 18.44 | 18.5 |
| | 2 | 16.1 | 17.10 | 18.00 | 18.04 | 18.1 |
| | 3 | 15.9 | 17.20 | 18.40 | 18.44 | 18.5 |
| `:donor-theme` | 1 | 16.2 | 17.10 | 18.51 | 18.84 | 18.9 |
| | 2 | 15.6 | 16.90 | 18.21 | 18.38 | 18.5 |
| | 3 | 15.5 | 17.10 | 18.12 | 18.40 | 18.4 |
| `:ctl-blocked` (control) | 1 | 51.3 | 51.90 | 52.50 | 52.81 | 53.1 |
| | 2 | 51.3 | 52.00 | 52.50 | 52.88 | 53.0 |
| | 3 | 51.3 | 51.90 | 52.41 | 52.58 | 52.7 |

The three runs reproduce closely on the Hicasso arms: across them the `:locale`
`p95` spans `18.41`–`18.51 ms` (**0.5%**) and the `:theme` `p95` spans
`18.00`–`18.40 ms` (**2.2%**).

### 7.4 The decomposition, and what it rules out

Published per arm because a rig that had reverted to a commit-bounded window
would show the first leg and nothing else. Measured visits only, `p50` in
milliseconds, run 1 / run 2 / run 3.

| arm | `:commit` | `:to-raf` | `:raf-to-paint` |
|---|---|---|---|
| `:idle-frame` | 0.0 / 0.0 / 0.0 | 16.50 / 16.60 / 16.60 | 0.1 / 0.1 / 0.1 |
| `:locale` | 1.0 / 0.9 / 1.0 | 16.45 / 16.50 / 16.55 | 0.8 / 0.8 / 0.7 |
| `:donor-locale` | 0.9 / 0.8 / 0.8 | 16.50 / 16.40 / 16.40 | 0.8 / 0.8 / 0.8 |
| `:theme` | 0.4 / 0.4 / 0.4 | 16.35 / 16.40 / 16.55 | 0.5 / 0.6 / 0.6 |
| `:donor-theme` | 0.3 / 0.4 / 0.4 | 16.60 / 16.40 / 16.50 | 0.6 / 0.6 / 0.6 |
| `:ctl-blocked` | 1.0 / 1.0 / 0.9 | 51.10 / 51.10 / 51.05 | 0.8 / 0.85 / 0.8 |

All three legs are present and non-degenerate on every arm, the floor's
`:commit` is exactly `0` because it performs no interaction, and
`:ctl-blocked`'s injected 50 ms lands in `:to-raf` and not in `:commit` — which
is the seam the control was placed on. **The application's own work is `p50 0.9`
to `1.0 ms` on a locale switch and `0.4 ms` on a theme switch**, against a
`:to-raf` leg of `~16.5 ms` that is the wait for a rendering opportunity. The
theme update being the cheaper of the two is what the driver says it is — the
narrower broad update, which is why both are carried.

**No ratio is formed from the `:commit` column and that is deliberate.** The
two arms differ there by `0.0`–`0.2 ms`, which is one or two of Chrome's
`100 µs` `performance.now()` quanta. A ratio of two quantised readings a
quantum apart is an artefact of the clamp, and this lane has a standing ruling
about exactly that shape.

### 7.5 `U3`: the numeric limb meets by `5.2x`, and the row is still not moved

Against `≤ 100 ms p95`, over `:locale` and `:theme`:

| quantity | run 1 | run 2 | run 3 |
|---|---:|---:|---:|
| worst single measured window, both arms | 19.2 ms | 19.1 ms | **19.3 ms** |
| headroom to the `100 ms` `p95` line | 5.21x | 5.24x | **5.18x** |

**Clause 1 of [§4.3](#43-u3s-two-limbs) fires on both arms in all three runs, so
[§3](#3-the-schedule-is-taken-unchanged-and-that-is-a-derivation-rather-than-a-default)'s
refusal condition never does.** No quantile estimator can answer above the
largest reading in the sample, and the largest of the **360** measured windows
across both operations and all three runs is `19.3 ms`. The line is met at every
quantile on readings that were **taken**, and the interpolation that hangs over
a `p99` at `n = 60` bears on nothing here. [§4.2](#42-the-phase-caveat-which-cuts-one-way-only)
applies in the meeting direction, so the reading is conservative twice over.

**The margin is wide enough that the positive control also meets the line**:
`:ctl-blocked` injects 50 ms of blocked main thread and still reads a maximum of
`53.1 ms`, `1.88x` inside `U3`'s line. That is context rather than a verdict,
and it is the sharpest way to say how far this page's broad operations sit from
`100 ms`.

**The row does not move, and [§4.3](#43-u3s-two-limbs) said so before the
numbers existed.** What was measured is the synchronous, frame-locked class; the
async-door class — a route change, a save reply — is structurally outside a
paint-bounded window, and the ruling on whether a line stated over *broad
application operations* is decided by the synchronous class alone is the
operator's. So `U3` keeps `UNPINNED` and its population cell keeps `—`.

**One sentence settles it**, and the alternative is named so neither is guessed:

1. **A ruling** that the synchronous class decides `U3` for this population. The
   cell move is then a one-line edit in each of two files — the ledger row and
   `POPULATION_PIN` — with this section as its evidence.
2. **A drain-bounded driver**, whose window opens at the click and closes at the
   paint that follows the router's `interop/next-tick` drain, which would reach
   the async-door class on its own terms. It is a third window on this
   mechanism and it is not built.

### 7.6 The resolution gate bit, and it bit on the theme pair

[§4.4](#44-the-resolution-gate-which-runs-before-any-c3-or-c4-figure-is-quoted)'s
gate is each measured arm's `:over-floor` range against an **empty frame**.
Per run, `:straddles-1?`:

| arm | run 1 | run 2 | run 3 |
|---|---|---|---|
| `:locale` | `false` — `[1.0029–1.0741]` | `false` — `[1.0118–1.0805]` | `false` — `[1.0150–1.0689]` |
| `:donor-locale` | `false` — `[1.0058–1.0864]` | `false` — `[1.0149–1.0712]` | `false` — `[1.0090–1.0633]` |
| `:theme` | `false` — `[1.0090–1.0453]` | `false` — `[1.0118–1.0433]` | `false` — `[1.0241–1.0480]` |
| `:donor-theme` | **`true`** — `[0.9825–1.0833]` | **`true`** — `[1.0000–1.0495]` | `false` — `[1.0181–1.0480]` |

**`:donor-theme` failed to separate from an empty frame in two of the three
evidence invocations**, so by the rule declared before the first one **the theme
pair is not resolved and carries no `C3` or `C4` verdict**. Its `:comparative`
range is published for completeness and is labelled unresolved: `[0.9545–1.0297]`,
`[0.9941–1.0118]`, `[0.9914–1.0059]`.

The locale pair passes the gate in all three runs. Its `:comparative` per-round
ranges are `[0.9856–1.0231]`, `[0.9716–1.0118]` and `[0.9941–1.0289]`, every one
of them carrying `:straddles-1? true` — which says the two arms are
**indistinguishable**, not that either won.

### 7.7 `C3` and `C4`: not decided, and now with a number on why

Two independent reasons, and the second is the one this window adds.

**First, the one declared in [§4.6](#46-c3s-best-relevant-adapter-is-not-this-windows-to-settle-either).**
`C3` is stated against the *best relevant adapter*, one donor cannot settle
*best*, and the argument that UIx is the faster of the two available donors is
transferred from a different clock over a different page rather than measured
here. That alone keeps `C3`'s cell where it is.

**Second, and this is measured: on this page the window could not have seen
`C3`'s line even if it had been crossed.** The arms sit `2`–`8%` above the floor
because a paint-bounded window is `~96%` frame grid, and the ratio the row is
read on is a ratio of whole windows. Work out what a substrate difference would
do to it, from this run's own published `p50`s and nothing else:

| pair | run | donor's excess over floor | ratio if Hicasso's own work were `1.25x` | ratio if `1.5x` | observed per-round spread |
|---|---:|---:|---:|---:|---:|
| locale | 1 | 0.75 ms | `1.011` | `1.022` | 3.75 pp |
| | 2 | 0.50 ms | `1.007` | `1.015` | 4.02 pp |
| | 3 | 0.65 ms | `1.009` | `1.019` | 3.48 pp |
| theme | 1 | 0.50 ms | `1.007` | `1.015` | 7.52 pp |
| | 2 | 0.20 ms | `1.003` | `1.006` | 1.77 pp |
| | 3 | 0.50 ms | `1.007` | `1.015` | 1.45 pp |

**A `1.25x` difference in the arms' own work displaces the window-level ratio by
`0.3`–`1.1` percentage points, against a per-round spread of `1.5`–`7.5`
points.** A `1.5x` difference — `C4`'s line — displaces it by `0.6`–`2.2`
points, still at or inside that spread. So the observed `~1.00x` is consistent
with `1.00x`, with `1.25x` and with `1.5x` alike, and reading it as a pass would
be reading the frame grid.

**This is not a new adjudication rule and it does not move anything.** It is
arithmetic on the same `:summary` and `:comparative` figures the pre-registered
rule reads, it can only ever narrow a claim, and the claim it narrows is one
this page had already declined to bank. It is written down because the number is
what makes the next dispatch decidable.

**So the conclusion is the driver's own, in the driver's own words, now with
evidence rather than as a possibility**: *if the shipped seed cannot separate
the measured arms from the floor, the honest conclusion is that `C3` needs a
broader population than the slice's feed page.* `C3` and `C4` keep `UNPINNED`
and their population cells keep `—`. **No band was widened and no ratio was
quoted as a verdict.**

What a broader population means, stated so it is not rediscovered: a page whose
broad update costs enough that the arms clear the frame grid rather than sitting
`2`–`8%` above it. `re-frame.hicasso.examples.ledger`'s virtualized 10,000-row
list is the witness this repository already has at that scale, and it is the
page `rf2-xc0bw`'s per-frame driver was built on.

### 7.8 A finding about the gate itself: necessary, and not sufficient

`:over-floor`'s `:straddles-1?` asks whether **one arm** separated from an empty
frame. `C3` is a question about whether **two arms** separate from **each
other** at a stated line. [§7.7](#77-c3-and-c4-not-decided-and-now-with-a-number-on-why)
is a case where the first answers yes and the second answers no: the locale pair
cleared the gate in all three runs and still could not resolve `1.25x`.

**The gate is right to be where it is** — an arm that has not separated from the
floor certainly cannot carry a ratio, so it is a sound necessary condition, and
it did real work here by refusing the theme pair. What it cannot do is decide
whether a comparison has the power to see the line it is read against. The
instrument publishes everything that reading needs (`:summary`'s `p50`s per arm
including the floor, and `:comparative`'s per-round spread); what it does not do
is compute it, so it falls to whoever reads a run. That is filed as a finding
for the next window on this mechanism rather than fixed here, because the
window's own terms forbid improving the rig mid-measurement.

### 7.9 What this window did not do

No threshold was guessed and no band widened: the `100 ms`, `1.25x` and `1.5x`
lines are the registered ones, and `control-slack`, `blocked-ms` and the guard
tolerance are the instrument's. **No gate was built** — all three rows are
distributional, so [§7](../../../../implementation/hicasso/spec/budgets.md#7-where-each-row-is-enforced)
forbids them a pull-request threshold and `check_budget_ledger.py` enforces it.
**No instrument was edited and no arm added**: the driver's and the donor's
blobs in [§6](#6-the-instrument-and-the-subject) are the ones PRs #8599 and
#8606 landed, and the schedule they shipped with is the schedule they were read
on. **No ledger cell moved** — 49 rows, 32 `MET`, 5 `BREACH`, 3 `UNRESOLVED`,
9 `UNPINNED`, before this window and after it. What moved is the prose that says
what each not-green row is waiting on.

## 8. Conditions

Four invocations between **03:58 and 04:05 on 2026-08-22**, the three evidence
runs back to back on one drained fleet inside a single box bracket, each about
80 seconds including its own cold `:advanced` compile. Captured exits `0`, `0`,
`0`. React 19.2.0, UIx 1.4.4, shadow-cljs 3.4.10, `:advanced` with `goog.DEBUG
false`, headless Chromium 147.0.7727.15 via Playwright 1.59.1, Windows 11, 24
logical cores, `navigator.deviceMemory` 32.

The box was bracketed at both ends, standalone, never sampled inside a run:

| bracket | queue length | occupancy | `java` | live bench runs | `headless_shell` | processes | free |
|---|---|---|---:|---:|---:|---|---|
| open, 04:00 | **0** on 7 of 8 samples, **1** on one | 7.27% / 8.02% | **0** | **0** | **0** | 22 node / 109 chrome / 633 | 13.19 GB |
| close, 04:05 | **0** on 6 of 8 samples, **1** on two, **4** on one | 10.22% / 10.38% | **0** | **0** | **0** | 22 node / 109 chrome / 717 | 12.89 GB |

Occupancy is attributed rather than assumed: the operator's editor reads
`3.9`–`4.1%` at both brackets, and at the closing bracket the tracker's own
process reads `1.7`–`1.9%` — together more than half the total. The single
queue-length sample of `4` sits in the closing bracket, after the last
invocation had finished.

**The live-bench column is a judgement and the judgement is shown.** A pattern
match for `shadow-cljs`, `clojure`, `p0_run`, `run.cjs`, `playwright` and the
usual runners returns four processes at both brackets, and **not one of them is
a bench executing**: two are shells whose recorded command line merely names one
— a terminal host matched on the substring `workbench`, and this session's own
shell, whose command line retains the runner it last invoked — and the other two
are the single orphaned `ssr:hicasso-serve` listener
[§5](#5-the-box) reports, which has used `0.77 s` of CPU in the 24.8 hours since
it started and holds nothing but a socket. A count alone would have read `4` and
said nothing; a count alone would equally have read `0` for the wrong reason if
the pattern had been narrower.

No worker was dispatched against this box while the window was open, and nothing
else was run alongside the runs: the two pull requests in CI at the time
(`#8677`, `#8678`) run on hosted runners, were checked green before the opening
bracket, and neither touches this surface. The source reading behind
[§3](#3-the-schedule-is-taken-unchanged-and-that-is-a-derivation-rather-than-a-default)
and [§4](#4-the-pre-registered-adjudication-rules) was taken **before** the
first invocation, as were the first two discrimination proofs below; every gate
run was taken **after** the last one, and every gate was re-run on the final
base after the rebase [§6](#6-the-instrument-and-the-subject) records.

**Four gates cover this change, each proved able to go red on a fault planted at
a line this change touches, and each restore verified by comparing the working
file's blob hash against the committed object rather than by reading a diff.**
The pairing is not obvious from the job names, which is why it is written down:

| gate | what it covers here | the planted fault, and what it returned |
|---|---|---|
| `scripts/check_doc_slugs.py` | this page, under `docs/` | a broken internal anchor → exit `1`, naming it |
| `scripts/check_readme_links.py --ci` | `implementation/hicasso/spec/budgets.md`, which is **outside** the doc gate's roots | a broken relative target in the amendments → exit `1` here and **exit `0` from `check_doc_slugs.py` on the same break** |
| `scripts/check_provenance_pins.py` | this page, as a changed page under `docs/design/hicasso/` | an unresolvable commit pin → exit `1`, classified by the word on its left |
| `implementation/hicasso/scripts/check_budget_ledger.py` | the ledger in `budgets.md` | `C3`'s instrument cell moved into the `PR gate` lane → exit `1`, *is a distributional row wired to the PR-gate lane* |

That last red is worth keeping for a second reason: it is the gate stating in
its own words the classification [§4](#4-the-pre-registered-adjudication-rules)
derives by exclusion, which is the one claim on this page that no roster
asserts.

**`mkdocs build --strict` is not a gate for any of this and was not run.**
`mkdocs.yml`'s `exclude_docs` block carries `design/hicasso/`, so the build
cannot see this page at all; and `budgets.md` sits outside `docs_dir` entirely,
which is a second and independent reason. One planted-fault run confirmed the
first half of that pairing by returning green from the doc gate on a break the
README gate caught.
