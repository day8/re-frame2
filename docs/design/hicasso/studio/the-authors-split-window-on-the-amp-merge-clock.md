# The author's split window on the amp-merge clock, pre-registered

**`rf2-v5oto`, 2026-08-22.** This bead has two halves and the first one landed.
The rig half — the two clean-pair arms `:helper-lean` and `:no-dissoc-lean`, and
the derived ladder key `:author` — went in as PR #8383 on 2026-08-16. What has
been owed since is **one quiet-box window, and nothing else**. This page is that
window's pre-registration and its record.

It is written in two halves, and **the first half was committed and pushed
before the runner was invoked once**, so the declared invocation count, the
adjudication rules and the resolution rule below are commitments rather than
descriptions of what happened.

**The quantity is attribution, not a target.** The bead's own last line governs
the whole page: *z143r bought attribution and this buys the rest of it. Whether
any of it is worth acting on is a separate ruling nobody has asked for.* Nothing
here recommends an optimisation, and [§4.5](#45-what-this-window-may-not-do-whatever-it-reads)
says so as a rule rather than as a disposition.

---

## 1. Declared invocations

| # | purpose | figures quotable? |
|---:|---|---|
| 0 | feasibility — does the nine-arm plan build, boot, clear its pre-window controls and complete at all | **no**, by declaration and before it was taken |
| 1–3 | the evidence window | yes, if admissible by [§2](#2-what-makes-a-window-admissible-here) |

**Three evidence invocations and not one, and the reason is the estimand.** The
figures this window exists for are **differences of two within-round medians on
arms that differ by one wrapper**, priced in nanoseconds per field over 400
fields. That is the smallest signal on this lane, and the triage comment on the
bead says so in terms: it is *the case where a plausible-but-worthless number is
most likely and least detectable*. Three invocations say whether each term
reproduces before any line is called on it. Three is also what the two windows
this page inherits from took — [the null re-read](#6-the-instrument-and-the-subject)
under `rf2-adld3`, and the `U1`/`U2` and `U3`/`C3`/`C4` windows.

**"One window" in the bead is one quiet-box session, not one invocation.** That
is how `rf2-adld3` read the same phrase: its close reason reports `N1`, `N2` and
`N3` and totals *fifteen rounds*.

**Invocation 0 is declared unquotable in advance rather than discarded
afterwards**, because a feasibility invocation whose figures stay available for
quoting is a fourth run waiting for a use. A run that fails a control is
**excluded and reported, never silently replaced**, and the series is not
extended to buy a better one.

## 2. What makes a window admissible here

**An exit code is not the verdict.** `run.cjs`'s own header records that
`shadow-cljs` exits `0` on a build that merely warned, which is why
`lane_build.cjs` exists at all. A window is admissible when every check below is
read out of the run's **own output** and each is affirmative, with the captured
exit code quoted last and as corroboration.

1. **The arm-order self-test**, in the page before anything is measured.
   `-main` refuses to boot the plan if `lane/self-test!` returns false.
2. **The fairness gate**, `parity!` — `lane/canonical` over every judged arm's
   container on the seeded 1,001-element page. Seven of the nine arms are
   judged; `:floor` and `:ctl-2x` are `:parity-exempt?`. It **refuses the run**
   when the arms are not building the same page, which is the check that makes
   a nine-arm ladder a ladder rather than seven unrelated numbers.
3. **The fairness gate's own negative control**, `parity-can-fail?` — a gate
   nobody has seen refuse is a gate nobody should trust.
4. **`N unverified of M`** over every measured mount, warm-up included, read at
   the page's **far end** — the last error slot's text, not the first, which is
   the difference between proving the page rendered and proving it started.
5. **The positive control**, `:ctl-2x` — `:expanded`'s own operation performed
   twice in one window, so `2.00x` is arithmetic rather than a model, divided by
   **its own round's** `:expanded`. `lane/control-verdict-strict` at
   `control-slack` `0.25` puts the band at `[1.50, 2.50]` and **every round**
   must sit inside it.
6. **The arm-order verdict**, `lane/guard!` — every arm REPORTABLE by
   predecessor and by phase. Refusal is **exit `2`** and no figure in the run is
   reportable. [§3.2](#32-the-predecessor-mix-is-not-invariant-in-n-at-this-sampling)
   is why this check carries more weight in this window than in its
   predecessors.

**The repair for any refusal is the arm, never the tolerance.** `control-slack`
stays `0.25`, `lane/guard!`'s tolerance is not touched, and the rig is frozen
for the duration — `rf2-adld3`'s surface note governs: *rig frozen; do not edit
inside the window*.

## 3. The schedule is taken unchanged, and two source checks were run before it

The bead fixes the shape and no dispatch need re-derive it: **nine arms at
`{:warmup 8 :samples 12}` × 5 rounds**. All three are read at source and taken
unchanged. The two checks below were run **before the opening bracket** and are
premise checks, not measurements.

### 3.1 The nine arms, and where the two new ones sit

`arms` holds nine entries in this order, and every arm the published rows or the
ladder's rungs are read off keeps the slot it had before PR #8383:

| # | arm | role |
|---:|---|---|
| 1 | `:floor` | the subtracted floor; `:parity-exempt?` |
| 2 | `:expanded` | the authoring baseline, and the denominator of (1), (1') and the null |
| 3 | `:expanded-b` | **the null** — the same body under a second boundary head |
| 4 | `:merged` | the `:&` spelling, and the numerator of (3) and (3') |
| 5 | `:ctl-2x` | the positive control; `:parity-exempt?` |
| 6 | `:helper` | rung (1), `rf2-z143r` |
| 7 | `:no-dissoc` | rung (3), `rf2-z143r` |
| 8 | `:helper-lean` | **rung (1')**, this bead — the author's wrapper, cleanly |
| 9 | `:no-dissoc-lean` | **rung (3')**, this bead — the author's round trip, cleanly |

### 3.2 The predecessor mix is not invariant in `n` at this sampling

The bead's dispatch note, the rig's own docstring and this window's brief all
carry one premise: *the null's predecessor distribution is `{:expanded 20,
:merged 10}` at n = 5, 7, 8 and 9 alike*, which is what cleared the nine-arm
schedule. **No test computes that distribution at any `n`** — the schedule
replay in `lane_schedule_cljs_test` does iterate `[4 5 7 8 9]` and does assert
predecessor *correctness* against the true execution order, but the histogram
itself appears once, in prose, in the rig docstring. So it was replayed
independently before this window opened, transcribing `slot-order` from
`order_guard.cljc`, the visit order from `lane/visit-plan` and the `:prev`
pointer from `lane/observe!`.

**The premise HOLDS, exactly, at the sampling it is about** — `{:warmup 3
:samples 6}` × 5 rounds, the sampling that failed under `rf2-6ta5r`:

| `n` | null's banked predecessors | per-arm banked | phase strata (prior executions) |
|---:|---|---:|---|
| 4 | `{:expanded 25, :merged 5}` | 30 | first third 3–15, last third 32–44 |
| 5 | `{:expanded 20, :merged 10}` | 30 | first third 3–15, last third 32–44 |
| 7 | `{:expanded 20, :merged 10}` | 30 | first third 3–15, last third 32–44 |
| 8 | `{:expanded 20, :merged 10}` | 30 | first third 3–15, last third 32–44 |
| 9 | `{:expanded 20, :merged 10}` | 30 | first third 3–15, last third 32–44 |

So **nine is cleared on the axis the claim is about**, and the rig docstring's
set — `5, 7, 8, 9` — is the correct one. `rf2-6ta5r`'s close reason names
`4, 5, 7, 8` instead and calls the distribution *invariant* across it; **`n = 4`
does not belong in that set**, reading `{:expanded 25, :merged 5}`. The
correction is already in the rig and is noted here only so a reader who follows
the citation back is not misled. No window has ever run this rig at `n = 4`.

**The instrument was exercised against an input it should flag**, because a
replay that only ever returns the expected histogram has not been shown to be
able to return another. Under a **rotation with the reflection removed** — the
schedule `order_guard.cljc` keeps as `rotation-only` precisely so the reflection
can be priced — the null gets **one** predecessor and not two: `{:expanded 30}`
at `n = 7`, `8` and `9`, and `{:expanded 25, :floor 5}` at `n = 5`.

**What the replay also shows, and what no prior page records: the invariance
does not survive the change of sampling.** The rig no longer runs `{:warmup 3
:samples 6}`. At the sampling it does run, the null's mix moves with `n`:

| `n` | null's banked predecessors at `{:warmup 8 :samples 12}` | per-arm banked |
|---:|---|---:|
| 5 | `{:expanded 35, :merged 25}` | 60 |
| 7 | `{:expanded 35, :merged 25}` | 60 |
| 8 | `{:expanded 40, :merged 20}` | 60 |
| **9** | **`{:expanded 30, :merged 30}`** | 60 |

**This window therefore differs from `rf2-adld3`'s published null window in a way
that window's page does not mention**: at `n = 7` the null's samples followed
`:expanded` 35 times and `:merged` 25; at `n = 9` it is 30 and 30. `:merged` is
a different amount of work from `:expanded`, so the state the null's samples are
taken in is not identical across the two windows.

**Three things are declared about that in advance.** First, it is a property of
the reflecting schedule and not a defect: every one of the nine arms banks 60
samples across **exactly two** predecessor strata, no arm is ever its own
predecessor, and no rung's two members share a mix — at `n = 7` either. Second,
**`lane/guard!`'s predecessor factor is the instrument's own answer to it**: it
stratifies each arm's readings by what ran before them and refuses the run at
exit `2` if an arm reads differently by stratum. That check is
[§2](#2-what-makes-a-window-admissible-here) item 6, and it is why this page
quotes it rather than summarising it, and why that check carries more weight
here than in the three windows before it. Third, and this is the commitment: **if
the null misbehaves in this window, this page will not attribute it to the mix.**
One arm count, no second sampling, and a replay is not a run — the same
separation `rf2-adld3` declined to make about warm-up, for the same reason.

## 4. The pre-registered adjudication rules

### 4.1 The null is read first, and it sets the resolution bound

`rf2-adld3` closed with a standing instruction — *read the NULL FIRST and report
its per-round vector before any other figure* — and the bead repeats it. So:
**`:expanded-b`/`:expanded` is reported before any ladder figure appears on this
page**, per round, per invocation, as a ratio and in ns/field.

The null is **zero by construction**: `:expanded-b` is `:expanded`'s own body
under a second boundary head, so every nanosecond it reads is instrument error
on a difference known to be zero.

**THE RESOLUTION RULE, fixed here and not fitted later.** Let `R` be the largest
`|null ns/field|` over every measured round of every admissible evidence
invocation. Then a ladder term is **RESOLVED by this window only if** both hold:

1. its magnitude exceeds `R` in **every** admissible evidence invocation, and
2. its **sign agrees** across every admissible evidence invocation.

A term meeting neither, or only one, is reported as **NOT RESOLVED**, with its
readings published in full. `R` is a reading and not a threshold: it is computed
from data this page has not yet seen, by a rule this page fixes before seeing
it. **No band here is widened, and none is derived from a ladder figure.**

**This rule can refuse the entire window, and that outcome is accepted in
advance.** If `R` comes back larger than every ladder term, the finding is that
nothing is resolved — which is the honest content of the `rf2-z143r` result this
bead exists to improve on, and publishing it again with better arms is worth
more than a verdict fitted to what came back.

### 4.2 (1') and (3'), the two figures the bead asks for

They are **two pairs and not a chain**, and each is one author-side step against
a **frozen** arm:

| rung | ratio | what it prices |
|---|---|---|
| **(1')** | `:helper-lean` / `:expanded` | the author's **wrapper**, with the `:class` passenger removed |
| **(3')** | `:merged` / `:no-dissoc-lean` | the author's **round trip**, with the same passenger removed |

Each is published **separately**, per round and pooled, in ns/field, with (1)
and (3) beside it for comparison and never differenced against them.

**What would falsify a claim on either**: `|term| ≤ R` in any evidence
invocation, or a sign that disagrees across invocations. Either sends the term
to NOT RESOLVED under [§4.1](#41-the-null-is-read-first-and-it-sets-the-resolution-bound).

**One comparison is available and one is not.** (3) and (3') share `:merged` as
their numerator, so the difference between them is the `:class` passenger and
the arm it rides — that comparison is reported. But **this window does not
establish that the passenger CAUSED (3)'s negative sign** in the `rf2-z143r`
window. That reading was taken in a different window on a different rig at a
different sampling, and a cross-window difference carries every other difference
with it. What is claimed is only what (3') reads here.

### 4.3 `:author` beside them, and a residual that is not zero by construction

The derived key `:author` is `(1) + (3)` — the passenger-free author-side sum by
cancellation, since the `:class` key is added to (1) and subtracted from (3) in
equal measure. `:author-clean` is `(1') + (3')`, the same quantity reached by
splitting rather than by cancelling. Both are published, side by side.

`:split-residual` is `:author − :author-clean`, and the record carries
`:zero-by-construction? false` on it deliberately. **So a nonzero residual is
expected and is not a fault.** What is registered in advance is the reading:
the residual is reported against `R`, and if `|residual| ≤ R` the two routes to
the author's share are **indistinguishable at this window's resolution** — which
is a finding about the two constructions agreeing, not a proof that they must.

### 4.4 What is read but not adjudicated

`:whole`, `:wrapper` (1), `:merge` (2), `:round-trip` (3), `:ladder-sum-residual`
and the absolute per-arm figures are all reported as taken, because a reader
comparing this window with `rf2-z143r`'s needs them. **No status cell moves for
any of them**, and this window re-prices nothing that `rf2-z143r` published.

### 4.5 What this window may not do, whatever it reads

- **It may not recommend an optimisation.** Not for `:&`, not for the wrapper,
  not for the round trip. The bead forbids it and the posture behind it is the
  project's: attribution was bought here, and whether to act on it is a ruling
  nobody has asked for.
- **It may not touch `docs/design/hicasso/decisions.md`.** The bead leaves that
  page alone deliberately, *because four queued windows will each want it*.
  Appending belongs to whoever drains that queue, not to this PR.
- **It may not edit the rig**, widen a band, raise `:warmup` or `:samples`,
  change the arm count, or re-run an excluded invocation to replace it.
- **It may not move a budget or ledger cell.** This bead is registered against
  no row.

## 5. The box

Measured on 24 logical cores, standalone, never sampled inside a run. Occupancy
is summed per-process CPU-time deltas over a five-second bracket divided by the
core count, never `LoadPercentage`; the processor queue length is the decisive
number, because it says whether anything was *waiting* for a core.

The scout reading, taken while this half was being written and before anything
was built:

| quantity | reading |
|---|---|
| `\System\Processor Queue Length`, 8 samples | **0** on 5, **1** on 3 |
| real CPU occupancy, two 5 s brackets | 10.20%, 9.99% |
| top consumer, attributed | the operator's editor, 4.05–4.10% |
| `java` processes | **0** |
| `headless_shell` processes | **0** |
| `node` / `chrome` / total processes | 22 / 108 / 602 |
| free physical memory | 11.73 GB |

**The one long-lived process on the box is reported rather than counted as
zero**, and it is the same one the `U3`/`C3`/`C4` window reported a day earlier:
an orphaned SSR bench listener on **port 8139**, started `2026-08-21 02:55`. It
is not this window's, it predates it, and it is left alone — the rule on this
lane is *kill only the one you can show is yours*. It holds a socket and
contends for nothing. This window runs on a different port regardless, and
`HICASSO_PORT` is a runner variable that reaches no figure.

**The exclusivity condition this window registers.** The fleet is drained for
its duration: no peer worker is dispatched, no pull request is merged, and no
worktree is created against this checkout while a bracket is open. That is a
policy set an hour before this page was written, under `rf2-1yct`, and it exists
because the previous window on this machine voided **two of its three**
pre-registered runs when peers wrote inside its brackets — both of them the
mayor, one a mid-run merge and one an unrelated worktree creation. Five
invocations, all exit `0`, no rig fault, and the series still came back
incomplete. The gate that failed was *gate weight*, which answers whether an
item makes the machine loud and does not answer whether it breaks the bracket.

**So this window records a bracket-integrity test rather than an assurance.**
`origin/main`'s commit id and the full worktree list are captured at the opening
bracket and again at the closing one. If either moved, a peer wrote inside the
window, and [§8](#8-conditions) says so plainly whichever way it reads.

## 6. The instrument and the subject

Tree anchor `41feff66308b005bb6f59c373a8dbf6bdc62b36b`, which is `origin/main`
at the window's opening. The object ids below are the committed objects
(`git rev-parse HEAD:<path>`) rather than a byte digest of the working file,
which on a checkout with `core.autocrlf=true` is the only reading that means
anything.

| file | object |
|---|---|
| `implementation/hicasso/test/re_frame/bench/hicasso/amp_merge_clock_app.cljs` | `66bc6b1eff60b15aa3dee73edfe1c58cd8dd79be` |
| `implementation/hicasso/test/re_frame/bench/hicasso/amp_merge_arms_cljs_test.cljs` | `0fc21f48d80049bfd7827b8bce7669697eeba782` |
| `implementation/hicasso/test/re_frame/bench/hicasso/run.cjs` | `da8a2f3723bfd3345f392e29c1344c582a30b736` |
| `implementation/hicasso/test/re_frame/bench/hicasso/lane.cljs` | `3d466f77e908d502835de5682e0c6d4b20b1d39e` |
| `implementation/hicasso/test/re_frame/bench/hicasso/lane_build.cjs` | `c55771d6c90d5dab53bfb02af48c6fcbcf49cffd` |

The rig is **frozen for the window** and this branch does not edit it.

**The reproduction command**, one build id and one driver, so the arm needs no
hot-zone edit of its own:

```bash
cd implementation
HICASSO_INIT_FN=re-frame.bench.hicasso.amp-merge-clock-app/-main \
HICASSO_OUT_DIR=out/hicasso-amp-merge \
HICASSO_PORT=8132 \
  node hicasso/test/re_frame/bench/hicasso/run.cjs
```

`:advanced` with `goog.DEBUG false`, headless Chromium via Playwright 1.59.1,
shadow-cljs 3.4.10, React 19.2.0. The driver clears the `:hicasso-bench` build
cache before every build, so each invocation pays its own cold compile and two
runs of this arm emit the same bundle in any order.

**Predecessor windows this page is read against.** `rf2-z143r`'s window built
the three-rung ladder and resolved the author's share only as a sum,
`[-250 -500 -625 375 125]` ns/field, straddling zero. `rf2-6ta5r` then found the
null had degraded on that window's `{:warmup 3 :samples 6}` sampling — one round
reading `1.4737` on a quantity that is `1.0` by construction — and repaired the
shared sampling loop. `rf2-adld3` re-read the null on the warmed rig over three
invocations and fifteen rounds, every round inside ±7.9% of `1.0`, largest
demonstrated error `875` ns/field on a difference known to be zero. **This
window is the fourth on that rig and the first at nine arms.**

## 7. The runs

**The headline is a REFUSAL, and it is the outcome
[§4.1](#41-the-null-is-read-first-and-it-sets-the-resolution-bound) accepted in
advance.** Both figures this bead exists to buy — (1') and (3') — come back
**below the instrument's own demonstrated error on a difference known to be
zero**. The window does not resolve the author's share, split or whole. What it
does resolve is the codec's rung and the whole authoring change, both of which
`rf2-z143r` already had.

### 7.1 Invocation 0, and what it bought

Taken before the opening bracket, declared unquotable in
[§1](#1-declared-invocations), and it is quoted for nothing here. It
established that the nine-arm plan builds, boots, clears `lane/self-test!`, the
fairness gate and its negative control, completes five rounds and exits `0`. No
figure of it appears on this page, and no evidence run was substituted for it.

### 7.2 The six exit-bearing checks, per evidence run

All six affirmative in **all three** runs, each read out of the run's own output:

| check | run 1 | run 2 | run 3 |
|---|---|---|---|
| `lane/self-test!` before the plan boots | passed | passed | passed |
| fairness gate `:agree?`, judged arms | `true`, 7 arms × 1001 | `true`, 7 arms × 1001 | `true`, 7 arms × 1001 |
| fairness gate's negative control `:can-fail?` | `true` | `true` | `true` |
| read-back, `N unverified of M` | **0 of 1000** | **0 of 1000** | **0 of 1000** |
| positive control, band `[1.500–2.500]` | all 5 rounds inside | all 5 rounds inside | all 5 rounds inside |
| arm-order guard, by predecessor **and** by phase | **reportable** | **reportable** | **reportable** |
| captured exit code | `0` | `0` | `0` |

The control's per-round readings, which this page quotes rather than summarising
as *ok*, because a control that adjudicates the window has to show its value:

| run | `:ctl-2x`/`:expanded` per round |
|---|---|
| 1 | `[1.8876 2.0920 1.9576 1.8872 2.0488]` |
| 2 | `[2.0625 1.9737 1.9259 1.9524 1.8430]` |
| 3 | `[2.0444 1.9773 1.9431 1.9565 1.9516]` |

`:ladder-sum-residual`, zero by construction because rungs (1), (2) and (3) are
a chain, reads `0` to four decimal places in every round of every run — the
arithmetic check the three-rung ladder owes.

### 7.3 The null, first

Reported before any ladder figure, as `rf2-adld3`'s standing instruction and the
bead both require.

| run | `:expanded-b`/`:expanded` per round | ns/field per round | worst magnitude |
|---|---|---|---:|
| 1 | `[0.9326 0.9885 0.9407 0.9774 0.9675]` | `[-750 -125 -875 -375 -500]` | **875** |
| 2 | `[1.0000 1.0000 0.9753 0.9905 1.0083]` | `[0 0 -250 -125 125]` | 250 |
| 3 | `[1.0111 1.0227 0.9837 1.0087 1.0000]` | `[125 250 -250 125 0]` | 250 |

**Fifteen rounds, every one inside ±6.74% of `1.0`**, and the pooled ratio
straddles `1.0` in all three runs, as a null must. **The `1.4737` did not
recur** — this is the second consecutive window on the warmed rig in which it
has not, now at nine arms rather than seven.

**Run 1 is the loose one and it is reported rather than set aside.** Its null
sits low in four rounds of five, and its `:expanded` arm carried a single
`30.5 ms` first-third sample against a `p50` of `5.4 ms`. `p50` is what every
figure on this page rests on, and the guard passed `:expanded` by predecessor
and by phase regardless, so the run is admissible and is kept. It is also what
sets the resolution bound below, which means the bound is set by the least quiet
of the three rather than by the best.

### 7.4 The resolution bound, and the instrument's quantum

**`R = 875` ns/field** — the largest demonstrated error over fifteen rounds on a
difference that is zero by construction.

**It is identical, to the nanosecond, to `rf2-adld3`'s** published `875` ns/field
over its own fifteen rounds. Two windows, two arm counts, two sets of three
invocations, the same bound. Nothing was arranged for that, and it is not a
prediction this page made.

**The bound is seven quanta, and the quantum is the reason.** Every mount time
this rig reports is a multiple of `0.05` ms, which over `400` fields is exactly
`125` ns/field — so every ns/field figure on this page is a multiple of `125`,
and `R` is `7 × 125`. **A difference smaller than `125` ns/field cannot be
represented by this instrument at all**, which bounds what any future window on
this rig can say about the author's share without a finer clock.

### 7.5 (1') and (3'), the two figures the bead asks for

Published separately, as the bead requires, with (1) and (3) beside them and
never differenced against them. Figures are ns/field, pooled `p50` per run.

| rung | run 1 | run 2 | run 3 | ratio ranges |
|---|---:|---:|---:|---|
| **(1')** `:helper-lean`/`:expanded` | `125` | `0` | `0` | straddles `1.0` in **all three** |
| **(3')** `:merged`/`:no-dissoc-lean` | `625` | `250` | `750` | excludes `1.0` in runs 1 and 3, straddles in run 2 |
| (1) `:helper`/`:expanded` | `1000` | `625` | `750` | excludes `1.0` in all three |
| (3) `:merged`/`:no-dissoc` | `-625` | `-250` | `-750` | excludes `1.0` in runs 1 and 2, straddles in run 3 |

**Under the rule committed before any of this was read, all four are NOT
RESOLVED**, every one of them for the same reason — the magnitude clause. No
term's smallest reading exceeds `R`:

| term | smallest magnitude | signs | verdict |
|---|---:|---|---|
| (1') | `0` | `+`, `0`, `0` | **NOT RESOLVED** — below `R`, and signs do not agree |
| (3') | `250` | `+`, `+`, `+` | **NOT RESOLVED** — below `R` |
| (1) | `625` | `+`, `+`, `+` | **NOT RESOLVED** — below `R` |
| (3) | `250` | `−`, `−`, `−` | **NOT RESOLVED** — below `R` |

**(1') is the sharper of the two results.** The author's wrapper reads **exactly
zero** in two runs of three and one quantum in the third. Whatever it costs is
at or under the smallest difference this instrument can express — a tighter
statement than the ladder could previously make about it, and still not a
measurement of a value.

### 7.6 `:author` beside them, and the residual

| term | run 1 | run 2 | run 3 | verdict |
|---|---:|---:|---:|---|
| `:author` = (1)+(3), derived | `-125` | `375` | `125` | **NOT RESOLVED** — below `R`, signs disagree |
| `:author-clean` = (1')+(3') | `625` | `250` | `1000` | **NOT RESOLVED** — below `R` |
| `:split-residual` | `-625` | `125` | `-875` | **NOT RESOLVED** — below `R`, signs disagree |

**`:author` reproduces `rf2-z143r`'s result rather than improving on it.** That
window read `[-250 -500 -625 375 125]` ns/field and called it straddling zero;
this one reads `-125`, `375` and `125` across three runs and straddles zero
again. The derived sum is where it was.

**The residual is the one place the refusal carries positive content.**
[§4.3](#43-author-beside-them-and-a-residual-that-is-not-zero-by-construction)
registered in advance that a residual within `R` would mean the two routes to
the author's share are indistinguishable at this window's resolution. The
residual's largest magnitude over three runs is `875`, which is `R` exactly and
does not exceed it. **So the split route and the cancellation route do not
disagree by more than this instrument can see** — which is what a reader who
wondered whether the two constructions measure the same thing wanted to know,
and is not a proof that they must.

### 7.7 What is resolved

Two terms clear the bar in every run and with agreeing signs:

| term | run 1 | run 2 | run 3 | verdict |
|---|---:|---:|---:|---|
| **(2)** `:no-dissoc`/`:helper`, the CODEC's merge-caller | `2250` | `1500` | `2500` | **RESOLVED**, positive |
| **`:whole`** `:merged`/`:expanded` | `2500` | `2250` | `2625` | **RESOLVED**, positive |

`:whole` reads `1.1723x`, `1.1946x` and `1.1941x`, range excluding `1.0` in all
three runs. **Neither is new** — `rf2-z143r` published rung (2) as clean, and
this window re-takes it at nine arms and agrees. No status cell moves for either,
per [§4.4](#44-what-is-read-but-not-adjudicated).

### 7.8 The sign pattern the registered rule does not license

Reported because leaving it out would be selective, and labelled because
promoting it would be fitting.

**(3) reads negative in all three runs and (3') reads positive in all three.**
They share `:merged` as their numerator, so what separates them is the `:class`
passenger and the arm carrying it — precisely the reversal the lean arm was
built to look for, and it came back three for three in both directions.

**It is not a verdict and this page does not make it one.** Both terms are below
`R`, and [§4.1](#41-the-null-is-read-first-and-it-sets-the-resolution-bound) was
written before any of it was on screen specifically so that a sign pattern this
suggestive could not be promoted after the fact. A three-run sign agreement on
quantities of one to six quanta is what a small real effect looks like and also
what a small bias looks like; separating them needs a finer clock, not a better
argument. **Recorded as an observation for whoever takes the next window, and as
nothing else.**

### 7.9 The schedule replay, confirmed by the instrument

[§3.2](#32-the-predecessor-mix-is-not-invariant-in-n-at-this-sampling) predicted
the `n = 9` predecessor mix from source before the window opened. The arm-order
guard prints its own stratification, and it matches the replay **arm for arm and
count for count** in every run:

| arm | replay predicted | guard reported |
|---|---|---|
| `:expanded-b` (the null) | `expanded 30, merged 30` | `expanded n=30, merged n=30` |
| `:expanded` | `floor 35, expanded-b 25` | `floor n=35, expanded-b n=25` |
| `:ctl-2x` | `helper 30, merged 30` | `helper n=30, merged n=30` |
| `:floor` | `no-dissoc-lean 35, expanded 25` | `no-dissoc-lean n=35, expanded n=25` |

So the mix **did** move from `rf2-adld3`'s `35/25` to `30/30` at nine arms, as
predicted — and the guard passed the null **by predecessor, within 10%**, in all
three runs. The change of mix did not move the null's readings. The commitment
in [§3.2](#32-the-predecessor-mix-is-not-invariant-in-n-at-this-sampling) not to
attribute a null excursion to the mix was never called on, because there was no
excursion to attribute.

### 7.10 What this window did not do

It did not recommend an optimisation, for `:&` or anything else. It did not
touch `docs/design/hicasso/decisions.md`. It did not edit the rig, widen a band,
raise `:warmup` or `:samples`, change the arm count, re-run an excluded
invocation, or move a budget or ledger cell. **No threshold was guessed and no
band was fitted to a reading.**

## 8. Conditions

Four invocations between **05:52 and 06:03 on 2026-08-22**, the three evidence
runs back to back on a drained fleet inside a single box bracket. **The
pre-registration half was committed and pushed at `a85302095d` before the first
invocation**; a rebase preserves author dates, so the ordering is readable off
this branch's history rather than resting on this sentence.

| run | started | ended | elapsed | captured exit |
|---|---|---|---:|---:|
| 1 | 06:00:25 | 06:01:13 | 48 s | `0` |
| 2 | 06:01:14 | 06:02:00 | 46 s | `0` |
| 3 | 06:02:02 | 06:02:58 | 56 s | `0` |

Each figure includes that run's own cold `:advanced` compile — the driver clears
the `:hicasso-bench` build cache before every build, so no run inherited
another's. Chromium 147.0.7727.15 via Playwright 1.59.1, shadow-cljs 3.4.10,
React 19.2.0, `:advanced` with `goog.DEBUG false`, Windows 11, 24 logical cores.

The box was bracketed at both ends, standalone, never sampled inside a run:

| bracket | queue length | occupancy | `java` | `headless_shell` | processes | free |
|---|---|---|---:|---:|---|---|
| open, 05:59 | **0** on all 8 samples | 10.12% / 12.32% | **0** | **0** | 22 node / 108 chrome / 594 | 11.64 GB |
| close, 06:03 | **0** on 6 of 8, **1** on two | 7.32% / 8.06% | **0** | **0** | 22 node / 108 chrome / 554 | 12.43 GB |

Occupancy is attributed rather than assumed: the operator's editor reads
`4.0`–`4.1%` at both brackets, more than a third of the total on its own, and
the remainder is spread across the tracker and two agent processes at under
`1.5%` each. The single bench-pattern process at the opening bracket was **this
session's own shell**, whose recorded command line names the check it was
running — a count alone would have read `1` and said nothing.

**The bracket-integrity test, and it comes back clean.** This is the check
[§5](#5-the-box) registered because the previous window on this machine lost two
of its three runs to peer writes inside its brackets:

| quantity | at open | at close | verdict |
|---|---|---|---|
| `origin/main` | `41feff66308b005bb6f59c373a8dbf6bdc62b36b` | unchanged | **unmoved** |
| worktree list | 34 entries | 34 entries, identical | **unchanged** |
| primary checkout `git status` | clean | clean | **unchanged** |

**No pull request was merged, no worktree was created and no worker was
dispatched while the window was open.** The `rf2-1yct` policy — exclusivity
gated on whether an item breaks the bracket rather than on how loud it is — held
on its first outing. The series is complete: three declared evidence
invocations, three admissible, **none excluded**.

**Three gates cover this change and no other gate does.** The change is one new
page under `docs/design/hicasso/studio/` and nothing else — no source file, no
rig file, no configuration.

| gate | what it covers here | captured exit |
|---|---|---:|
| `scripts/check_doc_slugs.py` | this page's link targets and in-page anchors, under `docs/` | `0` |
| `scripts/check_provenance_pins.py --changed-since origin/main` | this page, as a changed page under `docs/design/hicasso/` | `0` |
| `scripts/check_provenance_pins.py --self-test` | the pin gate's own negative controls | `0` |

**Each was shown able to refuse before its green was believed.** The slug gate
returned **exit `1` on this very page**, naming two broken anchors, when the
second half was still a forward reference — a real refusal on real content
rather than a planted one, repaired by writing the sections the links pointed
at. The pin gate reports `1 page inspected, 1 cited pin — 1 landed, 0 stranded,
0 unresolvable, 0 findings`, so its zero is a positive extraction of the tree
anchor in [§6](#6-the-instrument-and-the-subject) and not an empty sweep, and
its `--self-test` exercises its own refusal paths.

**`mkdocs build --strict` is not a gate for any of this and was not run.**
`mkdocs.yml`'s `exclude_docs` block carries `design/hicasso/`, so the build
cannot see this page at all; and `--strict` gates no in-page anchor anywhere,
since `validation.links` sets only `unrecognized_links: warn` and carries no
`anchors` key. `scripts/check_readme_links.py` is not this page's gate either —
its surface is repo-root markdown and markdown beside source, and this page sits
inside the doc gate's roots.

**Verified by hand, because no gate reads them.** Nothing validates this page's
prose, its tables' column counts or its arithmetic. Every table above was
checked column for column against its header, and every ns/field figure was read
off the runs' own printed vectors rather than recomputed — except the
resolution-rule verdicts, which are the committed rule applied mechanically to
those vectors.
