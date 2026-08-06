# The two-clock operating envelope — DC-09, measured

Beads: `rf2-drpa3.182.12` (the development reading, and the settlement-tail
correction and guidance that make this the third edition),
`rf2-drpa3.182.15` (the production reading). Owner of the claim: **DC-09** in
[`../product-completion-setpoint.md`](../product-completion-setpoint.md).
Owner of the method:
[`../decisions/D021-performance-budgets-and-release-evidence.md`](../decisions/D021-performance-budgets-and-release-evidence.md).
Instrument:
`implementation/freehand/test/re_frame/freehand/bench/b10_two_clock.cljs`,
its rows next door in `b10_two_clock_dom_cljs_test.cljs`, and the production
entry and driver in `b10_prod_app.cljs` / `b10_prod_run.cjs`.

**This is measurement, not a law.** No `FH-…` id is minted and nothing enrols
in the conformance roster. The deterministic property the numbers sit on top
of — a controlled input drops nothing while its frame is busy — is already
rostered as **FH-INPUT-003**, whose own suite says the latency distributions
under contention "belong to the measurement spine". This is that spine. What
is new here is the *ladder* (0, 1, 10, 50, 200 contending siblings rather than
a single dozen) and the distributions beside it.

**The recommendation, up front: add no scheduling, throttle, debounce,
coalescing, equality or preview vocabulary.** Section 8 gives the attributed
reasons, and the production reading strengthens every one of them. One
bottleneck did repeat in two witnesses; its remedy is a call-site change, not
an API, and production shrank it by an order of magnitude without changing its
shape.

**And the headline the production reading changed: 120 Hz clears its latency
budget at every rung of the ladder, including 200 dirty siblings.** In a
development build it cleared none of them. Section 6 restates the envelope.

**If you are here to decide what to do rather than to check what was measured,
[section 9](#9-guidance-for-a-high-rate-host) is the section — drive it
directly, spend your attention on how much you dirty rather than on how often
you dispatch, and do not build a throttle.**

**One figure in the first two editions was wrong and is corrected here.** The
settlement tail was read off a clock started at settle time rather than at the
last offer; what it published was largely the polling delay that stood in for
it. Section 6 states the correction, section 2's C3 and C4 are the controls
that now guard the anchor and the arithmetic, and the corrected number turns
out to strengthen the recommendation rather than soften it. **No figure below
changed when C4 landed** — the instrument's numbers were already right; what
was missing was a gate that could fail if they stopped being.

---

## 1. Provenance

Every figure below comes from one of three arms, at one revision each, in one
browser. The arms differ in the *bundle* and in nothing else that was measured:
same fixture, same instrument, same constants (4 rounds, 10 samples after 3
warm-ups, arm order rotating, ladder walked both ways), same machine, same
Chrome.

| | **A — development** | **B — ablation** | **C — production** |
|---|---|---|---|
| Optimisations | `:none` | `:advanced` | `:advanced` |
| `goog.DEBUG` | true | **true** | **false** |
| Instrumentation | live | live | elided |
| Build id | `:browser-test-freehand-bench` | `:freehand-release` + config-merge | `:freehand-release` + config-merge |
| Entry | `b10-two-clock-dom-cljs-test` | `b10-prod-app` | `b10-prod-app` |
| Revision | `e2b4225d6be141c7efaaf9c59e8f45c0425b286f` | `993533f2cca47bc3316e1be51997224f5b914d3c` | `993533f2cca47bc3316e1be51997224f5b914d3c` |
| Runs | 1 published (2 more reproduced it) | 1 | **3** |
| Raw records | `ai/findings/2026-07-28.two-clock-envelope-raw.edn` | `…two-clock-debug-1.edn` | `…two-clock-prod-{1,2,3}.edn` |

Runtime for all three: **HeadlessChrome 147.0.7727.15**, Windows 11 x64,
hardware class `:developer-workstation`. Arm C's records carry
`:d021 :release-evidence`; arm B's revision is real but its record reports
`:optimizations :none`, because `provenance/detect-build` infers the optimiser
from `goog.DEBUG` and cannot see the difference — **arm B is labelled by its
build command, not by its own record**, and that is the one provenance claim
here the run does not self-check.

### Why arm B exists

Arms A and C differ in three things at once — the optimiser, the
instrumentation define, *and* the build id, page and runner. Reading the whole
gap as "`:advanced` is faster" would attribute to the optimiser what might be
any of them. Arm B changes exactly one define against arm C: same entry, same
page, same driver, same optimiser, `goog.DEBUG` flipped back on. **The A→B step
is the optimiser and the harness; the B→C step is the Spec 009 instrumentation
seam alone.** Section 3 shows the two steps are not the same size and do not
act on the same half of the crossing.

### What does not transfer

**These are Chrome numbers.** No figure here is quoted for Node or for the
JVM, and none should be. Absolutes do not transfer across runtimes; the
*shares* — event flat, commit scaling, read-site constant, backlog invariant —
are the transferable claims.

The development figures are retained throughout rather than replaced, because
the **gap between them and production is itself the measurement**: it is what
the instrumentation seam costs, and it is worth publishing once.

---

## 2. The instrument, and the four controls that make it readable

A crossing is timed across `react-dom/flushSync` with the dispatch inside it,
and split at the instant the dispatch returns:

```
t0 ──flushSync[ rf/dispatch-sync ──t1── (React render + commit) ]── t2
```

`:event-ms` is `t1 − t0` — the router, the interceptor chain, the reducer, the
`app-db` install and the subscription notification. `:commit-ms` is `t2 − t1`
— React's render, commit and DOM mutation. Splitting there is DC-09's
acceptance criterion 3, and section 4 is the reason it was worth doing.

**Every measured crossing is read back out of the DOM inside its own window,
and one that did not land is counted rather than dropped.** Every arm reports
**0 unverified of 800** (section 3) and **0 unverified of 640** (section 5) —
including all three production runs and the ablation run, so **0 unverified of
5760 crossings across the whole report**. That rule is not decorative: B6
records a first pass that timed writes inside `flushSync` which never reached
the page at all and returned entirely reasonable milliseconds for them.

The `:fresh-equal` arm, whose whole point is that the page must **not** change,
cannot be verified by the page standing still — an arm that did nothing would
also achieve that. It is verified at the store instead: the `app-db` reference
must have moved and the value must not have.

### C1 — a control of computed duration

A busy-wait of exactly **4.00 ms** rides in every cell of every table, timed
through the same window.

| | predicted | measured (dev) | measured (production) |
|---|---|---|---|
| Standalone, n = 12 | 4.00 ms | min 4.0, p50 4.0, p95 4.0, p99 4.0, max 4.0 | identical, in all three runs |
| Inside all 40 published M1 cells | 4.00 ms | p50 4.0 everywhere; p95 4.0–4.3 | p50 4.0 everywhere; p95 4.0–4.4 |

Overshoot at p50 was **0.0 ms** in every arm, and never exceeded 0.4 ms
anywhere. That is clock quantisation with nothing left over: **no arm was
measured on a contended machine.** Had one been, the burn arm would have said
so in every table simultaneously, which is exactly why it is in every table —
and it is what lets the production numbers below be read as a substrate result
rather than as an idle box.

`:below-predicted` was **0 of 12** in every production run: no sample read
under its own computed duration, so the window measures what it brackets under
`:advanced` too.

### C2 — a control of computed size

Each mode's DOM mutation count is arithmetic over the fixture, and is compared
against what a `MutationObserver` measured. Predicted equalled measured at
**every one of the fifteen (mode × rung) combinations, in every arm**, with
zero spread:

| mode | predicted | measured (dev and production) |
|---|---|---|
| `:host-only` | 1 attribute | 1, at every rung |
| `:fresh-equal` | 0 | 0, at every rung |
| `:one-leaf` at k siblings | k + 1 | 1, 2, 11, 51, 201 |

Under `:advanced` this control does a second job it does not do under `:none`:
it is the **externs check**. `MutationObserver.takeRecords`, a record's
`.type`, `element.style.transform` and `setAttribute` all have to survive
Closure renaming for these counts to come out right, and the per-crossing DOM
read-back has to keep finding its nodes. Fifteen exact matches and 0 unverified
of 800 say they did. A renaming failure here would not have been subtle — it
would have been zero mutations everywhere, which is exactly the shape of the
fastest possible wrong answer.

The driven arm (section 6) reproduces it independently, in both bundles. In
development, at k = 200: 4221 mutations for 21 crossings, 6030 for 30, 6633 for
33 and 6834 for 34. In production, where the driven arm delivered several times
as many crossings: 4020 for 20, 8643 for 43, 17487 for 87 and 34371 for 171 —
**exactly 201.0 per crossing in all eight, across a 8.5× range of crossing
counts**. A different window, a different clock and a different call site
agreeing to three significant figures with the fixture's arithmetic is the
strongest statement this report can make that the thing being timed is the
thing being described.

### C3 — a control of the tail's anchor

C1 and C2 check that the window measures what it brackets and that the fixture
dirties what it claims. Neither of them could catch a clock started in the
wrong place, and one was: the driven arm's settlement tail, in the first two
editions, was read off a clock **started at settle time**. Section 6 states the
correction and what the old field was actually reporting.

The control that now stands where that fault was is a prediction. The last
offer and the settle decision are consecutive fires of one `setInterval`, so
the gap between them is **one interval** — and the run can predict that
interval from a different counter, `duration ÷ offered`, which is arithmetic
over what the driver did rather than a second reading of the same clock.

| k | rate | predicted gap (`duration ÷ offered`) | **measured gap (last offer → settle decision)** |
|---:|---:|---:|---|
| 0 | 30 | 33.3–35.0 | **33.4–37.1** |
| 0 | 60 | 16.3 | **15.4–17.5** |
| 0 | 120 | 8.0 | **7.0–8.1** |
| 0 | 240 | 4.0 | **4.2–5.0** |
| 200 | 30 | 33.3–35.0 | **32.7–41.0** |
| 200 | 60 | 16.3 | **15.3–16.2** |
| 200 | 120 | 8.0 | **7.1–7.9** |
| 200 | 240 | 4.0–4.1 | **3.5–4.6** |

Milliseconds, production, ranges over three runs. Predicted and measured agree
at every one of the eight cells; the largest single-run disagreement is 6.0 ms
at the 30 Hz rung, where one interval is 33 ms, so the worst cell is within
18% and every other is within 15%.

The control is also a **gate**, not only a table. `:tail-anchored?` fails a run
whose gap is under half a requested period — a floor `setInterval` cannot
violate, since it cannot fire faster than its period however far behind the
browser has fallen. It is asserted in the browser row and in the production
entry's coherence filter, beside "applied cannot exceed offered".

### C4 — a control of the tail's arithmetic

C3 was introduced as the assertion that would fail if the tail clock were
restarted at settle time. **It was not, and a merged-PR audit on
`rf2-drpa3.182.12` said so.** C3 is derived from the last offer against the
*settle decision*; the tail runs from the last offer to the *observer*. Two of
the three instants differ, so `:tail-ms` could be re-anchored anywhere at all
and C3 would not move — nor would the numeric and nonnegative checks beside it.
Every control the third edition shipped could stay green while the published
field measured the wrong interval again. A gate nobody has watched fail is not
known to be a gate, and this surface had already shipped one that wasn't.

Two changes close it. First, `:tail-ms` is no longer *computed* at publication
time: the `MutationObserver` calculates each generation's latency once, and the
final generation's is **retained and published verbatim**. The tail is lifted
out of the `:latency` distribution rather than recomputed from it, so there is
no start instant at the publication site left to get wrong. Second, the two
readings it is the difference of are published beside it — `:tail-offer-at-ms`
and `:tail-observed-at-ms` — and C4 asserts that exact identity. The comparison
carries no tolerance and needs none: both operands are readings on one clock
and the tail is their retained difference, so the check is a double subtraction
reproduced rather than a rounded chain of them.

The two controls are kept **separate**, and neither subsumes the other. C3 says
*where the retained offer reading sits* — one interval before the settle
decision. C4 says *the published tail was computed from that reading and the
observer's, and from nothing else*. Re-anchoring the tail at settle time keeps
C3 green and turns C4 red by a whole interval; re-anchoring the published offer
reading to keep C4 green collapses the gap and turns C3 red. There is no
remaining way to move the tail's start that leaves both standing.

That is asserted rather than argued. `c4-a-settle-anchored-tail-cannot-pass-both-controls`
performs the mutation on a synthetic record — one 30 Hz rung, one thing changed
and one only — and checks that the three pre-C4 gates all stay green on it
before checking that C4 does not. The mutation was also performed on the
instrument itself and run: restoring the first two editions' settle-time clock
turns the `bench:freehand-browser` gate red with **eight failures, all of them
C4**, publishing 4.1–6.0 ms against true tails of 6.4–68.8 ms — the same
poll-delay band the old field published, and under the third edition's gates
that run would have been green.

---

## 3. The cost of one semantic crossing

300 cells, one of them the leaf; `k` names how many siblings the application
chose to dirty alongside it. Ranges are across **four rounds** — two walking
the ladder ascending, two descending — of 10 measured samples each after 3
warm-ups, with arm order rotating on the sample index. **0 unverified of 800,
in every arm.**

### The shipped cost

Production ranges are the union across **three independent runs** (twelve
rounds); development is the published single run.

| k | **one-leaf p50, production** | one-leaf p50, dev | **one-leaf p95, production** | one-leaf p95, dev | host-only | fresh-equal (prod) | DOM mutations |
|---:|---|---|---|---|---|---|---:|
| 0 | **0.3–0.5** | 3.5–4.6 | **0.4–2.3** | 5.9–9.2 | ≤ 0.1 | ≤ 0.1 | 1 |
| 1 | **0.3–0.5** | 3.4–4.8 | **0.5–1.2** | 5.3–10.4 | ≤ 0.1 | ≤ 0.2 | 2 |
| 10 | **0.4–0.7** | 4.3–5.1 | **0.5–2.0** | 6.5–10.7 | ≤ 0.1 | ≤ 0.1 | 11 |
| 50 | **0.9–1.2** | 6.9–7.8 | **1.1–2.4** | 10.5–12.0 | ≤ 0.1 | ≤ 0.1 | 51 |
| 200 | **2.6–3.6** | 18.3–22.2 | **3.3–5.1** | 21.9–27.5 | ≤ 0.1 | 0.1 | 201 |

All figures milliseconds, Chrome.

**The measured development-to-production factor is 6.0× to 11.6×**, largest at
the empty end of the ladder and smallest at the loaded end. Per-run p50 factors
were 5.96–11.57×; the three production runs agreed to within 0.4 ms at every
rung, so the factor is a property of the bundle rather than of one afternoon.

### Where the factor comes from — and it is not mostly the optimiser

The event/commit split answers this directly, which is why the crossing was
never measured as a single number.

| k | event p50: A dev → B ablation → **C production** | commit p50: A → B → **C** |
|---:|---|---|
| 0 | 3.1–4.2 → 2.8–3.8 → **0.2–0.3** | 0.4–0.5 → 0.1–0.2 → **0.0–0.1** |
| 200 | 3.7–4.1 → 3.6–4.1 → **0.6–0.7** | 13.0–17.5 → 5.7–6.8 → **2.2–2.5** |

**The flat 3–4 ms event half was the instrumentation seam, essentially in its
entirety.** `:advanced` on its own (A→B) barely moves it — 3.1–4.2 becomes
2.8–3.8 at k = 0, and at k = 200 it does not move at all. Flipping `goog.DEBUG`
false (B→C) collapses it to 0.2–0.3 ms, a factor of about **13×**. The router,
the interceptor chain, the reducer, the `app-db` install and the notification
of 300 subscriptions together cost **under a third of a millisecond** in a
shipped bundle; what the development build was timing was Spec 009's trace
emission and schema validation sitting on that path.

**The commit half benefits from both, roughly evenly.** At k = 200 the
optimiser takes 13–17.5 ms to 5.7–6.8 (≈ 2.5×) and the define takes that to
2.2–2.5 (≈ 2.6×). React's render and DOM mutation are real work in both
bundles; they are simply cheaper compiled and cheaper without instrumentation
hooks interleaved.

**So the shape of the claim survives and its magnitude does not.** The event
half is still flat across the ladder (0.2–0.3 → 0.6–0.7 ms) and the commit half
is still what scales — but the crossing is now **commit-dominated at every rung
above k = 10**, where in the development build it was event-dominated below
k = 50. In production, *"if a crossing is expensive, it is expensive in React"*
is true almost everywhere; the per-dirtied-cell commit cost is about **0.012
ms**, down from 0.085.

### The arms that were already free stayed free

**The host clock is free, and it is free at every rung and in every arm.** The
`:host-only` arm — a behavior writing its own node's transform, with nothing
crossing into `app-db` — reads at or below Chrome's 0.1 ms `performance.now()`
clamp in all 40 readings of all five runs. Its cost is *"under 0.1 ms"*, not
*"zero"*; the instrument cannot see smaller, in either bundle. Its **flatness**
is a gate: measured spread across the ladder was **0.0 ms** in every production
run.

**A fresh-but-`rf=`-equal write cost 0.2–0.3 ms in development and now sits on
the clock clamp**, ≤ 0.1 ms at every rung, still with 0 DOM mutations every
time and the store reference still demonstrably moved. It was the most
load-bearing number in the report; production made it smaller than the
instrument can resolve, which strengthens section 8 rather than complicating
it.

**Both ladder orders agree**, in production as in development. The
first-round warm-up effect the development run recorded (per-round p50
declining monotonically at every rung in both orders) is present but far
smaller in absolute terms, because everything is smaller.

---

## 4. Where the subscription is read costs more than how much changed

This was not on the matrix. It came out of it, and it is the most actionable
result in the report.

The `surface` view exists in two spellings that differ in exactly one thing:
whether the behavior's config subscription is read in the view that also
builds the 300 cells (`:parent`), or one boundary down inside the behavior's
own panel (`:leaf`). Same behavior, same config, same cells, same field.

| config shape | leaves | read site | **one-leaf p50, production** | one-leaf p50, dev | stable / fresh-equal (prod) | DOM mutations |
|---|---:|---|---|---|---|---:|
| Vega-shaped (nested spec) | 96 | parent | **4.1–4.7** | 39.5–46.4 | ≤ 0.1 / ≤ 0.1 | 4 |
| Vega-shaped | 96 | leaf | **1.0–1.1** | 4.9–5.6 | ≤ 0.1 / ≤ 0.1 | 1 |
| Spread-shaped (flat range) | 900 | parent | **13.1–14.5** | 60.7–66.4 | ≤ 0.1 / 0.1–0.2 | 4 |
| Spread-shaped | 900 | leaf | **10.0–11.1** | 18.5–19.2 | ≤ 0.1 / 0.2 | 1 |

**The read-site penalty survives as a roughly constant term and shrinks by an
order of magnitude.** In production Vega pays 4.4 → 1.05 and Spread pays
13.8 → 10.55: a constant of **3.1–3.3 ms**, against 40–44 ms in development.
The structural claim is the one that transferred — *the penalty does not scale
with the payload* — and it now holds across a 9× payload range with the two
constants within 0.2 ms of each other, which is a tighter agreement than the
development run could show.

What did **not** transfer is the ratio. "Between eight and nineteen times"
becomes **between 1.3× and 4.2×**: 4.2× at the Vega shape, 1.3× at the Spread
shape, where the payload term now dominates the read-site term. The advice is
unchanged and still free; the urgency is not what the development numbers
implied.

The ablation arm locates the shrink precisely. At the `:parent` read site,
`:advanced` alone takes Vega from 39.5–46.4 to 14.3–20.2 and Spread from
60.7–66.4 to 29.4–36.4 — roughly halving each — because that arm's cost is
subtree reconciliation, which is React work the optimiser compiles. At the
`:leaf` read site the ablation arm is *no faster than development* (Vega
6.6–6.7 against 4.9–5.6; Spread 22.3–26 against 18.5–19.2), because there is no
subtree to reconcile and what remains is the instrumented event half. Only
`goog.DEBUG false` moves that. **The two halves of the penalty respond to
different switches**, which is as strong a confirmation of the attribution as
this fixture can give.

Once the read site is controlled for, the residual **is** payload size: 96
leaves cost 1.05 ms, 900 leaves cost 10.55 ms — about **0.012 ms per leaf** for
a config that genuinely changed, down from 0.017.

Compare with section 3: *the same statement* — "one leaf of `app-db` changed"
— costs **0.3–0.5 ms** when the value is read at the leaf that uses it and the
subtree is cells, and **4.1–14.5 ms** when it is read at a parent that also
builds a collection.

### Freehand already elides an unchanged config, and it is `=` that decides

The behavior's `:update` was called **exactly 52 times** in each of the four
(shape × read-site) combinations — which is exactly the number of `:one-leaf`
crossings (4 rounds × 13 samples). Stable-reference crossings and
fresh-but-equal crossings both produced **zero** `:update` calls.

**All sixteen counts are 52 under `:advanced` too**, in every arm: four
combinations × three arms, no exceptions. That matters more than it looks. The
elision is a behavioural property that could plausibly have been an artefact of
a dev-only equality check, and it is not — a shipped bundle elides an `:update`
whose config is equal exactly as the development one does.

So the elision is not identity-based; a deep-fresh copy with no identical
substructure above its scalar leaves is elided just as a re-installed
reference is. This is **counted, not read off the runtime**, which is why it is
here rather than in a footnote.

---

## 5. The equality question, answered

The bead asks for stable-reference against fresh-but-`rf=`-equal at
Vega-shaped and Spread-shaped sizes. The answer is in the table above and it
is a null result of the useful kind:

**Stable-reference and fresh-but-equal are indistinguishable at both shapes and
both read sites, in both bundles.** In development, 0.2–0.3 ms against
0.2–0.3 ms at 96 leaves and 0.4–0.5 against 0.4–0.6 at 900. In production both
arms sit on or below the 0.1 ms clock clamp at 96 leaves, and at 900 leaves
read ≤ 0.1 against 0.1–0.2 — ranges that overlap or touch, at the resolution
floor. Neither repaints anything in either bundle.

A full structural walk of a 900-leaf map, then, costs **at most a couple of
tenths of a millisecond in a shipped bundle**, and the difference between doing
that walk and short-circuiting on identity remains below what this instrument
can resolve. Production did not merely preserve the null result; it pushed both
arms under the clamp, so the conclusion is now limited by Chrome's timer rather
than by any measurable difference. **0 unverified of 640, in every arm.**

---

## 6. The envelope, driven rather than derived

A `setInterval` offers a semantic event every `1000/R` ms for 700 ms through
the **ordinary asynchronous `rf/dispatch`** — what a pointer handler calls, and
the only crossing that *can* build a backlog, since a synchronous one is
finished before the next offer is made.

`:expected` is computed — `floor(duration × rate / 1000)` — and is not a
measurement. It is what the application asked the browser for.

| k | rate asked | **expected** | offered, dev | **offered, production** | applied = offered? | peak backlog | latency p50, dev → **prod** |
|---:|---:|---:|---:|---:|:-:|---:|---|
| 0 | 30 | 21 | 20 | **21** | yes / yes | 1 / 1 | 5.8 → **1.0** |
| 0 | 60 | 42 | 43 | **43** | yes / yes | 1 / 1 | 4.4 → **0.9** |
| 0 | 120 | 84 | 87 | **87** | yes / yes | 1 / 1 | 4.3 → **0.6** |
| 0 | 240 | 168 | **133** | **174** | yes / yes | 1 / 1 | 4.3 → **0.5** |
| 200 | 30 | 21 | 21 | **20** | yes / yes | 1 / 1 | 20.9 → **3.8** |
| 200 | 60 | 42 | **30** | **43** | yes / yes | 1 / 1 | 22.3 → **3.8** |
| 200 | 120 | 84 | **33** | **87** | yes / yes | 1 / 1 | 20.4 → **3.0** |
| 200 | 240 | 168 | **34** | **171** | yes / yes | 1 / 1 | 19.9 → **3.0** |

Milliseconds. `outstanding` was 0 in all eight runs of both arms — every
generation offered reached the page before the run was declared settled.

### The three findings, restated

**The k = 200 ceiling was an artefact of the development build.** In
development the offer rate saturated at 43–49/s at 200 dirty siblings no matter
what was asked. In production the browser delivers **61, 124 and 244 offers per
second** for 60, 120 and 240 Hz requested — essentially the full asked rate,
at the *loaded* end of the ladder. The ablation arm places the recovery in
both switches: `:advanced` alone lifts k = 200 from ~47/s to ~104/s at 120 Hz,
and `goog.DEBUG false` takes it the rest of the way to ~124/s. The mechanism
the development run identified was right — the timer callback *is* the main
thread, so a browser stops offering when a crossing is expensive — and
production simply made the crossing cheap enough that it never stops.

**Accepted = offered = committed, in all sixteen runs across both arms.**
Nothing was refused, dropped or coalesced away by the framework, in either
bundle. This is the finding that transferred completely.

**Peak backlog was 1 at every rate, both loads and both bundles.** Not
"small" — *one*, the single event in flight. The queue never grew. This was the
load-bearing observation for section 8's throttle argument, and production
tested it under genuinely higher throughput (244 offers/s at k = 200 rather
than 49) and found the same answer.

### The settlement tail — corrected, and it is one crossing

**The first two editions measured this wrong, and the wrong number was
published.** `:tail-ms` claimed to run "from the last offer to the DOM showing
the last generation". It was read off a clock started at *settle* time: the
interval callback that notices the drive duration has elapsed is the one
**after** the last offer — a whole period later, by construction, since it is
the next fire of the same `setInterval` — and the tail clock started there. Its
own docstring named a quantity it did not measure. Where the last generation
had already committed, which at low rates is most of the time, what it
published was approximately the 4 ms polling delay.

So `≤ 5.4 ms even at 240 Hz against a 20 ms crossing` was not a tail. It was
the poll. The correction reads the tail from the last offer's retained
timestamp to the `MutationObserver`'s sighting of the last generation — the
same clock the latency distribution comes from, so the poll's 4 ms resolution
no longer quantises a quantity that is often smaller than 4 ms. The poll and
its 2 s ceiling remain, as the `settled?` detector they always were. C3 and C4
in section 2 are the controls that now stand where the fault was — C3 on where
the tail's anchor sits, C4 on the tail actually having been measured from it,
the second added because a merged-PR audit showed the first could not fail on
the value it existed to protect.

| k | rate | **tail, production** | tail, dev | *what the old field published (the poll delay)* |
|---:|---:|---|---|---|
| 0 | 30 | **0.8–1.1** | 5.1 | *4.1–5.3* |
| 0 | 60 | **0.5–0.8** | 3.9 | *4.1–4.5* |
| 0 | 120 | **0.3–0.5** | 3.8 | *4.2–6.0* |
| 0 | 240 | **0.4–0.5** | 3.0 | *4.1–5.8* |
| 200 | 30 | **4.6–5.4** | 15.8 | *4.3–5.1* |
| 200 | 60 | **2.2–2.5** | 24.3 | *4.3–4.8* |
| 200 | 120 | **2.4–3.7** | 14.0 | *4.7–5.4* |
| 200 | 240 | **3.2–3.5** | 21.1 | *4.5–6.0* |

Milliseconds, Chrome. Production ranges are across three runs at revision
`65abc3b99e`; the dev column is a single run at the same revision through the
`bench:freehand-browser` gate. `:tail-source` was `:observer` in all sixteen —
the poll never had to answer. **This is a different run from the offered/latency
table above**, which is why the offer counts are not restated here; the dev
build's offer ceiling at k = 200 varies run to run and the published counts
belong to their own run.

**The tail is one crossing.** Compare it against section 3's synchronous
window: at k = 200 the production tail of 2.2–5.4 ms straddles that window's
p50 of 2.6–3.6 and p95 of 3.3–5.1; at k = 0 the tail of 0.3–1.1 straddles a p50
of 0.3–0.5. In the development build, where a crossing at k = 200 costs
18.3–22.2 ms, the tail is 14.0–24.3. At every rung and in both bundles, **the
time between the host's last offer and the page being current is one
crossing — and nothing after it.**

That identity is not a coincidence, and it is worth being precise about what it
does and does not prove. The tail *is* the last generation's own offer→DOM
latency: same two timestamps, so it is one sample of the distribution beside it
rather than an independent measurement of it. What makes it a result is what
sits next to it — **`outstanding` was 0 and `settled?` true in all sixteen
runs.** A settlement tail of one crossing means there was never a second one
waiting. Had the queue been growing, the tail would have been the backlog
draining, and it would have scaled with the requested rate. It does not: at
k = 200 the production tail at 240 Hz (3.2–3.5) is *smaller* than at 30 Hz
(4.6–5.4), which is noise around one crossing and not a queue.

**The corrected number strengthens section 8 rather than weakening it**, and it
strengthens it in both directions. At k = 200 in a development build the real
tail is three to four times what was published — the old figure was flattering
— and it is still exactly one crossing, which is the claim that matters. At
k = 0 in production the real tail is *under a millisecond*, six to ten times
smaller than the poll delay that stood in for it.

### Derived and driven agree — in production too

Two independent windows — a synchronous `flushSync`-bracketed crossing and an
asynchronous timer-driven one — still predict each other, and the production
comparison is the sharper test because the driven rates are no longer clipped:

| k | section 3 p50 → implied ceiling | driven sustained rate |
|---:|---|---|
| 0, dev | 3.5–4.6 ms → 217–286/s | 190/s |
| 200, dev | 18.3–22.2 ms → 45–55/s | 49/s |
| 0, **production** | 0.3–0.5 ms → 2000–3300/s | **249/s — timer-bound, not crossing-bound** |
| 200, **production** | 2.6–3.6 ms → 278–385/s | **244/s** |

At k = 200 the two windows agree within about 15%. At k = 0 they no longer
agree, and that is the result: a 0.4 ms crossing implies a ceiling in the
thousands, while `setInterval` at a requested 240 Hz cannot offer more than
about 250 times a second. **In production the k = 0 seam is limited by the
browser's timer, not by the crossing** — the substrate has left the frame.

### The envelope, stated

Two criteria, because they answer different questions.

**Throughput** — how many crossings actually land:

| k | sustained rate, dev | **sustained rate, production** |
|---:|---|---|
| 0 | ~190/s | **~249/s (timer-bound)** |
| 200 | ~49/s | **~244/s** |

**Latency quality** — the largest offered rate at which 95% of crossings finish
inside one budget of `1000/R` ms. Production p95 is the worst of twelve rounds
across three runs:

| k | p95 dev | **p95 prod** | 30 Hz (33.3 ms) | 60 Hz (16.7) | **120 Hz (8.33)** | 240 Hz (4.17) |
|---:|---:|---:|:-:|:-:|:-:|:-:|
| 0 | 9.2 | **2.3** | ✔ | ✔ | **✔** (was ✗) | ✔ (was ✗) |
| 1 | 10.4 | **1.2** | ✔ | ✔ | **✔** (was ✗) | ✔ (was ✗) |
| 10 | 10.7 | **2.0** | ✔ | ✔ | **✔** (was ✗) | ✔ (was ✗) |
| 50 | 12.0 | **2.4** | ✔ | ✔ | **✔** (was ✗) | ✔ (was ✗) |
| 200 | 27.5 | **5.1** | ✔ | ✔ (was ✗) | **✔** (was ✗) | ✗ |

**So the 120 Hz row flipped, and it flipped at every rung.** In a development
build 120 Hz cleared the p95 bar nowhere, even with nothing else dirty. In the
shipped bundle it clears everywhere, including 200 dirty siblings, with the
worst rung reading 5.1 ms against an 8.33 ms budget — 39% of headroom to spare
rather than a 10% miss. 60 Hz, which broke between 50 and 200 siblings in
development, is now comfortable across the whole ladder. **240 Hz clears the
latency bar up to 50 siblings and misses it only at k = 200 (5.1 against
4.17)** — and at that rate the ceiling is the browser's timer anyway, since the
driven arm tops out near 250 offers a second.

Only k = 0 and k = 200 were driven; no intermediate throughput row is quoted,
interpolated or otherwise.

---

## 7. Controlled input: no drops first, then latency

DC-09's acceptance criterion 2, in the order it is written.

**This section is development-build only and was not re-taken in production.**
Its verdict is a correctness one — no drops, intact caret — which the browser
suite already gates deterministically at every rung, and correctness does not
move with the bundle. Its latency table is therefore an upper bound like the
rest of the development figures, and by section 3's factor the production
per-keystroke cost would be expected somewhere between a sixth and a tenth of
what is tabulated. That expectation is *not* measured and is not quoted as a
result anywhere in this report.

Thirty characters are appended one at a time through `HTMLInputElement`'s own
prototype value setter and delivered as real bubbling `InputEvent`s outside
React's `act` environment, so the controlled-input door takes the synchronous
round trip it takes for a user. **Every keystroke also dirties `k` siblings**,
so the frame-scoped flush has to settle all of them before the listener
returns.

At **every** rung — 0, 1, 10, 50, 200 — all five assertions held:

- the field holds all 30 characters (a character the round trip failed to land
  is one React *erases* at the end of the discrete event, so a drop appears as
  a shorter string, not a late one);
- the caret is at `[30 30]`;
- the leaf settled on the last keystroke's state;
- so did the furthest contending sibling;
- and the cell beyond the rung did not move.

Only then, the distribution — the whole round trip per keystroke:

| k | min | **p50** | p95 | p99 | drops |
|---:|---:|---:|---:|---:|---:|
| 0 | 4.0 | **5.4** | 7.7 | 10.3 | 0 / 30 |
| 1 | 4.1 | **4.9** | 8.4 | 8.5 | 0 / 30 |
| 10 | 4.8 | **5.6** | 9.4 | 11.2 | 0 / 30 |
| 50 | 7.6 | **9.5** | 13.7 | 13.8 | 0 / 30 |
| 200 | 16.4 | **21.0** | 26.1 | 26.5 | 0 / 30 |

Development build; see the note opening this section.

**k = 0, 1 and 10 are indistinguishable** — 5.4, 4.9 and 5.6 ms at p50 with
overlapping ranges. Contention only becomes visible at 50 siblings and only
becomes a *user-visible* cost at 200, where p50 exceeds a 60 Hz frame (16.7 ms)
and p95 exceeds a 30 Hz one.

Read as a typing budget: 21 ms a keystroke supports about 47 characters per
second, and no human types at 47 cps. **Correctness is unconditional across the
whole ladder; only the smoothness of the accompanying repaint degrades.**

---

## 8. Recommendation: add nothing

DC-09's bar is that new scheduling, preview or equality vocabulary is proposed
"only for a material attributed bottleneck repeated in two realistic
witnesses". Three of the four candidate vocabularies have no bottleneck to
attribute at all.

**The production reading did not soften this recommendation; it removed the
only grounds on which it could have been reconsidered.** Every candidate
vocabulary was argued down from a development figure that was an upper bound on
cost, so the honest reading in section 9 of the first edition was that the case
could only get stronger. It did, by 6–11×, and the one row that might have
argued for scheduling help — 120 Hz missing its p95 budget — now clears it at
every rung.

**No equality hook.** A fresh-but-`rf=`-equal crossing costs 0.2–0.6 ms in a
development build and **sits on Chrome's 0.1 ms clock clamp in a shipped one**,
at 96 leaves and at 900, at both read sites, indistinguishable from
re-installing the identical reference. Freehand already elides an `:update`
whose config is equal — proved by counting `:update` calls, 52 of 52 matching
the changed crossings exactly, in *all three* bundles. There is nothing here
for an equality hook to buy, and production made the thing it would optimise
too small to measure.

**No throttle, debounce or coalescing verb.** Peak backlog was **1** at every
rate, both sibling loads and both bundles; `outstanding` was 0 in all sixteen
driven runs. The browser's own timer back-pressure already coalesces — under
overload you receive *fewer offers*, never a growing queue. A throttle verb
would suppress offers the browser has already stopped making, and would trade
away the low-load responsiveness that costs nothing. Production tested this at
**five times the development throughput** at k = 200 (244 offers/s against 49)
and the backlog was still 1.

The settlement tail says the same thing from the other end, and it says it
*better* now that it is measured correctly. When the host stops offering, the
page is current **one crossing later** — 0.3–1.1 ms at k = 0, 2.2–5.4 at
k = 200 — with `outstanding` 0 in all sixteen runs. A throttle exists to stop a
queue forming. There is no queue: the thing a throttle would drain is one event
that has already been dispatched. Note that this argument survived the
correction *against* its own interest at k = 200, where the true tail is three
to four times the figure previously published.

**No host-local state model.** The `:host-only` arm is under Chrome's clock
resolution at every rung. The existing behavior boundary is already the answer;
nothing needs adding to make host-rate motion cheap, because it already is.

**No preview lane.** Nothing measured here distinguishes a preview crossing
from any other crossing. `started` / `preview` / `committed` is an application's
choice about which events are worth having, and it costs nothing structural.

### The one bottleneck that did repeat — and why it is not an API

Section 4's read-site penalty *did* repeat in two witnesses (Vega-shaped and
Spread-shaped) and it *is* attributed — subtree reconciliation, not DOM work,
not equality, not the reducer; the ablation arm confirms the attribution by
showing the penalty responds to the optimiser while the leaf-read baseline
responds only to the instrumentation define.

**Its materiality is the one claim production revised down.** In a shipped
bundle the penalty is a constant **3.1–3.3 ms**, not 40–44, and the ratio is
1.3–4.2× rather than eight to nineteen. It is still a real cost — 3.3 ms is a
fifth of a 60 Hz frame, paid for nothing — and it is still worth stating,
because the fix is free. But it no longer looks like the kind of finding that
would motivate new surface even if surface were on the table.

Its remedy is a call site, not a verb. DC-09 already says to keep host-local
geometry and motion out of `app-db` unless the application genuinely needs the
live values. This adds the sharper corollary, which belongs in the guide:

> **If a high-rate value must cross into `app-db`, read its subscription at the
> boundary that uses it — never in a view that also builds a collection.**
> Moving one `v/sub` down one boundary took a config change from 4.4 ms to
> 1.05 ms in a shipped bundle, while the number of DOM nodes that moved went
> *down*.

That is documentation and guidance work with a named owner (`rf2-fby7o`, the
guide), not new Freehand surface.

---

## 9. Guidance for a high-rate host

Section 8 answers a framework question: should Freehand grow vocabulary? This
section answers the one an application author actually has — *I have a pointer
drag, a camera, an animation loop or a scroll handler producing updates faster
than a human can perceive them. What do I do?*

**In one line: drive it directly, and spend your attention on how much you
dirty rather than on how often you dispatch.**

### The default, named

**A behavior may own high-rate host motion outright, while only semantic
`started`, optional `preview` and `committed` events cross into `app-db`.**
That is not a compromise made for performance; it is what the behavior boundary
is for. It happens also to be free: the `:host-only` arm — a behavior writing
its own node's transform with nothing crossing — sits at or under Chrome's
0.1 ms clock clamp at every rung of the ladder, in every bundle, and its
spread across the ladder measured 0.0 ms. Host-rate motion already costs
nothing measurable. Nothing has to be added to make it cheap, and nothing you
add can make it cheaper.

So keep host-local style, geometry and motion out of `app-db` **unless the
application genuinely needs the live value** — because another view renders
from it, because it is persisted, because a machine transitions on it. "The
number happens to be in my hand" is not a reason.

### When it does have to cross, it crosses cheaply

If the live value *is* semantically required, the measured envelope is wide.
In a shipped bundle, at 120 Hz, with two hundred siblings dirtied by every
single update, 95% of crossings finish in 5.1 ms against an 8.33 ms budget.
60 Hz is comfortable across the entire ladder. The framework refused nothing,
dropped nothing and coalesced nothing away in any of the sixteen driven runs;
`applied` equalled `offered` equalled committed every time.

**So: dispatch on every move.** Not on every third move, not on a
`requestAnimationFrame` you installed yourself, not behind a 16 ms trailing
edge you wrote by hand.

### What not to bother building

Each of these is a thing hosts commonly build, and each is answered by a
measurement rather than by taste.

- **A throttle, a debounce, or a rAF coalescer in front of `dispatch`.** Peak
  backlog was **1** — one event in flight — at every rate, both loads and both
  bundles, and the settlement tail is one crossing with nothing behind it. The
  browser's timer is already the back-pressure: under load you get *fewer
  offers*, never a growing queue. A throttle would suppress offers the browser
  has already stopped making, and would cost you responsiveness at the low
  load where you were paying nothing.
- **Memoising or ref-caching a config to dodge an equality walk.** A
  fresh-but-`rf=`-equal config sits on the 0.1 ms clock clamp at 96 leaves and
  at 900, indistinguishable from re-installing the identical reference, and
  Freehand already elides an `:update` whose config is equal — counted, 52 of
  52, in every bundle. Building the config fresh each frame is fine. A full
  structural walk of a 900-leaf map costs at most a couple of tenths of a
  millisecond.
- **A "settling" or "in flight" UI state.** After the last offer the page is
  current within about a millisecond at k = 0 and within one crossing at
  k = 200. There is no interval a user could observe, and nothing to show them
  during it.
- **A preview lane distinct from an ordinary crossing.** Nothing measured here
  tells the two apart. `started` / `preview` / `committed` is worth having
  because those are different *facts*, not because they cost different amounts.

### What to do instead, in priority order

1. **Read the subscription at the boundary that uses it.** This is the one
   attributed bottleneck in the report and the whole remedy is a call site.
   Reading a high-rate value in a view that also builds a collection costs a
   constant **3.1–3.3 ms** more than reading it one boundary down — while
   moving *more* DOM nodes, not fewer. Move the `v/sub` down. It is free and it
   is the largest single number in this report that you control.
2. **Count how many cells one update dirties.** The event half of a crossing is
   flat — 0.2–0.7 ms whether one cell moved or two hundred and one. The commit
   half is what scales, at roughly **0.012 ms per dirtied cell** in a shipped
   bundle. Your lever is the size of the dirty set, and it is React's cost, not
   re-frame's. If a crossing is expensive, it is expensive in React.
3. **Measure dispatch-per-move before adding any policy.** A single move that
   dispatches once is a 0.3–0.5 ms crossing. A handler that dispatches four
   times per move is a 2 ms one, and no throttle fixes that — it is four events
   where the application meant one. Count the dispatches first; it is nearly
   always the answer when a high-rate host feels slow, and it is the only
   diagnosis in this list that does not need a profiler.
4. **Type into it.** Controlled input is the case where correctness, not
   smoothness, is at stake, and correctness held unconditionally: no dropped
   characters and an intact caret at every rung, including 200 siblings dirtied
   by every keystroke. Only the smoothness of the accompanying repaint
   degrades, and only past 50 siblings.

### The one caveat that would change this advice

**Real pointer streams were not measured.** The driver is a `setInterval`, and
a `setInterval` hands the main thread one event per task. A browser delivering
coalesced `pointermove`, or `pointerrawupdate` bursts, can hand *several* events
to one task — the single shape that could produce a backlog greater than 1, and
therefore the single shape that could change the throttle answer. If you are
driving from raw pointer events at a rate the display cannot show, read
`getCoalescedEvents` and dispatch what you mean rather than what arrived. That
is not a framework verb; it is the pointer API's own answer to its own
question. The backlog-of-1 result should not be quoted for a raw-pointer burst
without re-measuring, and section 10 records this as undetermined.

---

## 10. What this report could not determine

**~~The production envelope.~~** *Resolved by `rf2-drpa3.182.15`; this is what
the second edition adds.* The first edition recorded that every figure came
from a development build, that the direction was known and safe, and that the
120 Hz row was exactly the row the unmeasured factor could flip. The factor is
now measured at **6.0–11.6×**, decomposed into an optimiser term and an
instrumentation term by the ablation arm, and **the 120 Hz row did flip, at
every rung**.

One correction is worth recording, because it was the stated reason the
reading had not been taken. The first edition said a production run "needs a
`:browser` entry point plus a build target in
`implementation/shadow-cljs.edn`", a hot-zone file. The entry point was
needed; **the build target was not.** B6 had already established the pattern —
`--config-merge` supplies an `:output-dir` and an `:init-fn` on top of the
existing `:freehand-release` id, which already carries `:optimizations
:advanced` and `goog.DEBUG false`, the only two properties the reading is
about. `b10_prod_run.cjs` does the same and touches no shared configuration at
all. The hot-zone contention was real; the dependency on it was not.

**~~The settlement tail.~~** *Corrected by the third edition.* The first two
editions published a tail read off a clock started at settle time — a precise,
plausible number that was not the quantity its own docstring named, and at low
rates was approximately the 4 ms polling delay. It is now anchored at the last
offer's retained timestamp and read from the `MutationObserver`, with C3
(section 2) gating the anchor and section 6 stating both the correction and
what the old field had been publishing. The corrected tail is one crossing at
every rung of both bundles. **What this cost is worth recording**: the wrong
number was quoted in a durable recommendation for four days, and only reading
the code found it. No clock caught it, because a clock cannot tell you where it
was started.

**~~The control that guarded that correction.~~** *Closed by the fourth
edition.* C3 shipped described as the assertion that would fail if the tail
clock were restarted at settle time, and a merged-PR audit found that it would
not: C3 is derived from the settle decision, the tail from the observer, and no
gate compared `:tail-ms` to anything. The repair is in section 2 — the tail is
retained from the observer rather than recomputed, the two readings it is the
difference of are published, and C4 asserts the identity, with C3 left standing
separately so the one escape from C4 lands on it. **The lesson is the sharper
half of the one above**: the wrong number was found by reading the code, and so
was the fact that the control added to stop it recurring could not fire. Both
times the instrument's own output looked fine. A control is worth what its
demonstrated failure is worth, which is why the mutation is now performed in
the suite and was performed once on the instrument itself, red, before this was
written.

**Real pointer streams.** The driver is a `setInterval`. A browser delivering
coalesced `pointermove`, or `pointerrawupdate` bursts, can hand several events
to one task, which is the one shape that could produce a backlog greater than 1.
The backlog-of-1 result is measured against a timer and should not be quoted
for a raw-pointer burst without re-measuring. Section 9 carries this as the one
caveat that would change its advice.

**Sub-0.1 ms costs, and there are now many more of them.** Chrome clamps
`performance.now()` to 100 µs. In the development build only the `:host-only`
arm and the bottom of the `:fresh-equal` range sat on that clamp. In production
the `:host-only` arm, the whole `:fresh-equal` arm at every rung, and both
config-equality arms at 96 leaves all sit there. Their costs are "under
0.1 ms", and **nothing finer can be said with this instrument** — which means
the production numbers are, at the small end, a statement about Chrome's timer
rather than about Freehand. Resolving them would need a different clock, not
another run.

**The k = 0 driven rate is timer-bound, not crossing-bound.** Section 6's
~249/s at k = 0 is what `setInterval` could offer, not what the seam could
absorb; the synchronous window implies a ceiling in the thousands. The two
windows agreeing was a validation in the development build and is no longer
available at the empty end of the ladder.

**Arm B's optimiser label.** `provenance/detect-build` infers `:optimizations`
from `goog.DEBUG`, so the ablation arm's own record reports `:none` although it
was compiled `:advanced`. The arm is identified by its build command. Every
other provenance claim in this report is self-checked by the running bundle.

**Rungs between 0 and 200 in the driven arm.** Only k = 0 and k = 200 were
driven, in either bundle. No intermediate throughput figure is quoted.

**Controlled input in production.** Section 7 was not re-taken; see the note
there.

**One machine.** Single workstation, single browser version, five runs. The
*shares* — event flat, commit scaling, read-site constant, backlog invariant,
elision exact — are the transferable claims, and all five survived the change
of bundle. The absolutes are not.

---

## 11. Reproducing

**Arm C — the production reading (sections 3–6's headline figures).** No shared
build configuration is involved; the driver builds, serves, drives and prints
on its own.

```bash
cd implementation
RF2_REVISION=$(git rev-parse HEAD) \
RF2_HARDWARE_CLASS=developer-workstation \
  node freehand/test/re_frame/freehand/bench/b10_prod_run.cjs
```

**Arm B — the ablation.** Identical, plus one define:

```bash
B10_DEBUG=true \
RF2_REVISION=$(git rev-parse HEAD) \
RF2_HARDWARE_CLASS=developer-workstation \
  node freehand/test/re_frame/freehand/bench/b10_prod_run.cjs
```

Both print every published record as EDN on stdout, tagged
`;; ==== B10 PROD … ====`, and exit non-zero if either positive control failed.
Each arm writes to its own output directory, so the two bundles cannot be
confused for one another.

**Arm A — the development reading (sections 3–7's dev columns).**

```bash
cd implementation
RF2_REVISION=$(git rev-parse HEAD) \
RF2_HARDWARE_CLASS=developer-workstation \
RF2_VERBOSE_TESTS=1 \
BROWSER_TEST_TIMEOUT_MS=900000 \
  npx shadow-cljs compile browser-test-freehand-bench \
  && node scripts/serve-and-run-browser-tests.cjs \
       --root out/browser-test-freehand-bench --port 8024
```

The records are printed to the browser console as EDN, tagged `;; B6 B10 …`,
and the runner flushes the console under `RF2_VERBOSE_TESTS=1`. Without
`RF2_REVISION` the records still publish, carrying
`:d021 :not-release-evidence` and the reason — the rows run in the ordinary
`npm run test:browser` gate too, where no revision is available, and they say
so rather than routing around D021.
