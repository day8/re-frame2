# The `U1`/`U2` window on the slice echo clock, pre-registered

**`rf2-85og2`, 2026-08-22.** The bead's three budget gates each need their own
quiet-box window, and gate 1 has split: `U1` and `U2` parted company with `U3`,
`U4`, `C3` and `C4` when PR #8589 landed a driver whose window is one discrete
interaction through to the paint that follows it, on the witness application
those rows are stated over. What `U1` and `U2` have owed since is one quiet-box
window and nothing else — no gate to build, because
[budgets §7](../../../../implementation/hicasso/spec/budgets.md#7-where-each-row-is-enforced)
routes every distributional row to pinned evidence runs and forbids converting
one into a pull-request threshold. This page is that window's pre-registration
and its record.

It is written in two halves, and **the first half was committed before the
runner was invoked once**, so the declared invocation count and the adjudication
rule below are commitments rather than descriptions of what happened.

---

## 1. Declared invocations

| # | purpose | figures quotable? |
|---:|---|---|
| 0 | feasibility — does the rig build, boot, discriminate and complete at all | **no**, by declaration and before it was taken |
| 1–3 | the evidence window | yes, if admissible by [§2](#2-what-makes-a-window-admissible-here) |

**Three and not one, and the reason is the estimand rather than taste.** `C1`'s
anchor needed three because a 5% regression line cannot be read against an
anchor whose own run-to-run spread is unknown. Here the reason is different: the
quantity is a **tail quantile of a latency**, which one scheduler hiccup can
move where a heap slope cannot. Three invocations say whether a `p95` reproduces
before a line is called on it. A verdict is taken only where all three agree;
where they do not, the disagreement is the finding and no cell moves.

**Invocation 0 is declared unquotable in advance rather than discarded
afterwards.** A feasibility invocation whose figures stay available for quoting
is a fourth run waiting for a use, and the rule this page inherits is the C1
anchor's: a series that stops early is not a measurement, and a run that fails a
control is excluded and reported, never silently replaced.

**All three are taken.** If invocation 0 shows the rig is broken, that is the
window's finding and no evidence invocation follows it.

## 2. What makes a window admissible here

**An exit code is not the verdict**, and on this lane that is a recorded
incident rather than a caution: `run.cjs`'s own header records a driver that
printed a full table and exited `0` on a build that had merely warned. So a
window is admissible when every check below is read out of the run's **own
output** and each is affirmative, with the captured exit code quoted last and as
corroboration.

The slice echo driver carries five exit-bearing checks, and two of them are its
own rather than the lane's:

1. **The arm-order self-test**, run in the page before anything is measured.
   `-main` refuses to boot the plan if it returns false.
2. **The echo's negative control**, `echo-discrimination!` — the keystroke arm's
   setup mutation performed **alone**, with no DOM event and therefore no
   handler, no dispatch, no state write and no commit — which the driver
   requires the very check the arms use to **refuse**. It runs before the first
   warm-up visit, and a run whose echo check does not discriminate dies there
   rather than publishing a record.
3. **`N unverified of M`** over every window taken, warm-up included.
   `lane/assert-verified!` throws on any nonzero count.
4. **The positive control**, `:ctl-blocked` — the keystroke arm plus 50 ms of
   blocked main thread on the seam **between the commit and the frame**,
   adjudicated by `lane/control-verdict-strict` at ±50%, every round inside the
   band and not merely the range.
5. **The arm-order verdict** over the samples — exit `2`, figures not quotable.

**Check 4 adjudicates the window and not merely the sensitivity, which is why
this page reports its measured value rather than the word `ok`.** The injected
cost sits after the commit it would have stopped at, so a rig that had quietly
reverted to a commit-bounded window would report a control that predicted 50 ms
and measured nothing. The ±50% band is derived from the frame grid rather than
tuned: both arms start at the same phase, so each round's figure is a difference
of two windows that each end at the first rendering opportunity at or after
their own work, and the injected duration reaches that difference **rounded to
the grid** — within one rendering interval either way with nothing wrong with
the instrument.

Two further conditions are this window's own:

- **Five rounds**, which is what `lane/across-rounds` and
  `control-verdict-strict` are shaped for, and which this page does not touch.
- **A quiet box**, on the standard `rf2-w01c` states and `rf2-0puue`'s park
  ruling assumes: no other worker holding the machine at all — not one
  bench-class worker, not a peer running any gate. The brackets are
  [§5](#5-the-box) and [§8](#8-conditions).

## 3. The schedule is taken UNCHANGED, and that is a derivation rather than a default

The instrument's `sampling` knob reads `{:warmup 8 :samples 12}` and its
docstring anticipates this window raising it: *a tail quantile over 12 is mostly
interpolation … so the run that reads this instrument will want more, and
raising these two is how it gets them.* The knobs are schedule knobs and never
lines, so raising them would breach nothing. This window does not raise them,
and the reason is arithmetic rather than restraint.

**What `n` the published figures are actually taken over.** `:summary` is
`lane/summarise` over each arm's readings **pooled across rounds**, so at
`{:warmup 8 :samples 12}` over five rounds it is `n = 60` per arm and not 12.
Under `lane/quantile`'s `h = (n-1)q` that puts `p95` at `h = 56.05`, with three
measured readings above it, and `p99` at `h = 58.41` — between the top two order
statistics, which is exactly the region `lane/quantile`'s own docstring prices
as a value *no reading in the sample ever took*.

**But the verdict does not turn on that region in the direction that matters,
because no quantile estimator can answer above the sample maximum.** If an arm's
`:max` sits at or below the line, then every quantile of that arm sits at or
below it too — `p95` and `p99` alike — and the fact is carried by a reading that
was **taken** rather than interpolated. `lane/summarise` publishes `:max` beside
`:n`, so the check is in the record the driver already prints.

So this window pre-registers the interpolation as a **refusal condition** rather
than paying for it in advance:

- Where `:max` meets the line, the line is met at every quantile and the
  estimator is not load-bearing.
- Where `:max` misses it but the published `p99` meets it, **that half is not
  decided here**. The figure is reported with its interpolation weight, and the
  finding is that resolving it wants a longer schedule — which is a second
  window and not this one's to open.

`rounds` is left at five for the reason its own docstring gives. And leaving
both alone has a second payment: the instrument this window reads is
**byte-identical to the one `rf2-xa8wo` landed**, so the blob table in
[§6](#6-the-instrument-and-the-subject) pins the shipped file and nothing here
had to be re-validated.

## 4. The pre-registered adjudication rule

The two rows, transcribed from
[the ledger](../../../../implementation/hicasso/spec/budgets.md#9-the-budget-line-reconciliation-ledger):

| row | registered line | estimand | arms in the population |
|---|---|---|---|
| `U1` | echo within one 60 Hz frame at `p95` | latency to visible echo | `:keystroke` |
| `U2` | ≤ 50 ms `p95` and ≤ 100 ms `p99` to next paint | latency to next paint | `:keystroke`, `:toggle` |

`:idle-frame` is the floor and `:ctl-blocked` is the control. Neither is in
either population, and neither is read against a line.

### 4.1 The phase caveat, which cuts one way only

Every window on this instrument starts in the first task after a paint — the
phase with the **longest** wait to the next rendering opportunity — because the
alternative is a predecessor-dependent reading the arm-order guard correctly
refuses. The instrument states what the alignment costs the estimand: a reading
taken here is the conservative end of the phase distribution, *the end that
cannot flatter the application*.

So a **meet** here holds a fortiori at a user's uniformly random phase, and a
**miss by less than one measured rendering interval** is precisely the region
where the difference between the worst phase and the mean phase decides the row.
The instrument names the randomised-phase driver that would resolve that region
and records that it is **not built**. This window therefore does not decide a
row inside it; it reports the figure, the measured interval, and that the
trigger for building that driver has fired.

### 4.2 `U2`

For each of `:keystroke` and `:toggle`, in each admissible invocation:

1. `:max` ≤ 50 ms → both halves met on measured readings, whatever the
   estimator.
2. `:max` > 50 ms and published `p95` ≤ 50 ms → the `p95` half is met on an
   estimate with three measured readings above it; report the figure and say so.
   Apply §4.1 to the `p99` half against 100 ms.
3. Published `p95` > 50 ms (or `p99` > 100 ms) by more than the spread across
   the three invocations, and by more than one measured rendering interval →
   the line is missed and the row is a `BREACH`.
4. Otherwise → not decided by this window; report and refuse.

A verdict requires the same outcome on **both** arms in **all three**
invocations. `U2` is stated over *ordinary discrete interactions*, and two event
paths disagreeing is a finding about the rows rather than a reason to quote the
kinder one.

### 4.3 `U1`, where the line's unit is a frame and the reading's is a millisecond

`U1` is registered *within one 60 Hz frame*, and this instrument's alignment
puts a whole rendering interval into every reading by construction. Reading
`:ms` against `16.7 ms` directly would therefore charge the application for the
grid it waits on, which is not what the row is stated over; but silently
subtracting the floor and reporting the remainder against the same line would be
inventing an operationalisation the ledger does not carry. So both are computed
and **neither is privileged**:

- **(i) literal** — `p95(:keystroke :ms) ≤ 16.7 ms`.
- **(ii) floor-relative** — `p95(:keystroke :ms) − p50(:idle-frame :ms) ≤ 16.7 ms`:
  the application's own contribution over a frame containing no application work
  costs less than an additional 60 Hz frame.

**`U1` is decided only where (i) and (ii) agree, in all three invocations.**
Where they disagree the row is **not decided by this window**, the two figures
are published side by side, and choosing between the two readings is recorded as
a ruling for the operator rather than settled by a worker. No line moves either
way, and the ledger keeps whatever status it had.

### 4.4 `U1`'s structural half is witnessed here and is NOT the distributional half

The row's first clause — *controlled updates correct same-turn* — is witnessed
independently of any quantile, and at **every** sample rather than at `p95`. The
echo is read out of React's own committed mirror (the `value` **content
attribute**, which only a commit writes) inside the frame's rendering steps,
`bank-aux!` banks every window including warm-up, and `assert-verified!` refuses
the run at any nonzero count. A run reporting `0 unverified of M` therefore
witnesses same-turn correctness on all `M` windows.

That is reported, and it is **not** a `U1` verdict.
[Budgets §9.2](../../../../implementation/hicasso/spec/budgets.md#92-what-each-not-green-row-is-waiting-on)
already refuses exactly this substitution for `rf2-hic-045`'s census: *the echo
is present before the turn yields* does not imply *the echo reaches the glass
within 16.7 ms at `p95`*, and conflating the structural reading with the
distributional one is what `D9`'s counters being kept apart from `S5`'s bytes
exists to prevent.

### 4.5 What this window may not do, whatever it reads

- **No threshold is guessed and no band is widened.** The `50 ms`, `100 ms` and
  one-frame lines are the registered ones; `control-slack` and the guard
  tolerance are the instrument's and are not this window's to move.
- **No gate is built.** `U1` and `U2` are distributional rows: §7 routes them to
  pinned evidence runs and forbids a pull-request threshold,
  [§9.1](../../../../implementation/hicasso/spec/budgets.md#91-how-to-read-a-row)
  says such a row may never name the first lane, and `check_budget_ledger.py`
  enforces both. The whole edit-shaped half of gate 1 for these two rows **was**
  the instrument, and `rf2-xa8wo` built it.
- **A population cell is promoted only by the deliberate act the gate names.**
  `POPULATION_PIN` pins `U1`–`U4` to `—`, and a `—` row must read `UNPINNED`
  with no figure. Moving either cell is *a new measurement window and an edit
  here*, in the constant's own words — so it happens, if it happens, with this
  window's evidence beside it and never as a cell edit.

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
| real CPU occupancy, two 5 s brackets | 13.52%, 11.12% |
| top consumer, attributed | the operator's editor, 5.79% |
| second consumer | this probe's own shell, 1.74% |
| `java` processes | **0** |
| shadow-cljs / bench / Playwright command lines | **0** |
| `headless_shell` processes | **0** |
| `node` / `chrome` / total processes | 22 / 108 / 550 |
| free physical memory | 14.72 GB |

The opening and closing brackets of the window itself are in
[§8](#8-conditions), and the opening one was taken **immediately before the
first evidence invocation** rather than at the time of writing.

## 6. The instrument and the subject

Tree anchor `cc563fd8fbf331421e928d5d862db73400722278`, which is `origin/main`
at the window's opening — the measured tree and the published tree are the same
tree. Object ids below are the committed objects (`git rev-parse HEAD:<path>`)
rather than a byte digest of the working file, which on a checkout with
`core.autocrlf=true` is the only reading that means anything.

The instrument:

| file | blob |
|---|---|
| `bench/hicasso/slice_echo_clock_app.cljs` | `979dd3413390e489beab26f004743c53da72fd07` |
| `bench/hicasso/lane.cljs` | `3d466f77e908d502835de5682e0c6d4b20b1d39e` |
| `bench/hicasso/run.cjs` | `da8a2f3723bfd3345f392e29c1344c582a30b736` |
| `bench/hicasso/lane_build.cjs` | `c55771d6c90d5dab53bfb02af48c6fcbcf49cffd` |
| `bench/order_guard.cljc` | `d57c25473360ba8a464cb9107152288b79303e84` |
| `bench/navigate.cjs` | `d8f30bbab93e850ec6b37b9a6de50f295601b02b` |
| `bench/lane_cache.cjs` | `ec3c60f44fa46f2bbb11ae908749cf93742b27dc` |

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

The package doors it mounts through, under `implementation/hicasso/src/`:

| file | blob |
|---|---|
| `re_frame/hicasso.cljc` | `c64d048e4ff2d72756b5b52329f2a40b8e015dcf` |
| `re_frame/hicasso/impl/mount.cljs` | `77c367ca6324435d4ae83146bb3a152833ef17c3` |
| `re_frame/hicasso/impl/controlled.cljs` | `dbe21f4ebe8c21eece34ef4414524d0052c662dd` |
| `re_frame/hicasso/impl/collector.cljs` | `bfb1c37f84b14c8d701da41c97883a24740dba52` |

Reproduce, from the repository root:

```bash
HICASSO_INIT_FN=re-frame.bench.hicasso.slice-echo-clock-app/-main \
HICASSO_OUT_DIR=out/hicasso-slice-echo \
HICASSO_PORT=8137 \
  node implementation/hicasso/test/re_frame/bench/hicasso/run.cjs
```

No new build id: the driver takes its entry from `HICASSO_INIT_FN` and rides
`:hicasso-bench`, the id the whole lane already shares, so this arm costs
`implementation/shadow-cljs.edn` — an HD-017 hot-zone file — nothing. Exit `1`
on a build that merely warned, a page error, a fatal the page recorded, an
unverified echo or a failed positive control; exit `2` if the arm-order guard
refuses.

---

## 7. The runs

*To be written after the window closes. Nothing above this line was edited once
the runner was first invoked.*

## 8. Conditions

*To be written after the window closes.*
