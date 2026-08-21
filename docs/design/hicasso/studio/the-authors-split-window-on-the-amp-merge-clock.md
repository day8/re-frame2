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

*Written after the window. At the time this half of the page was committed and
pushed, the runner had not been invoked once.*

## 8. Conditions

*Written after the window, and carrying the bracket-integrity test
[§5](#5-the-box) registers.*
