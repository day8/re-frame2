# The two-clock operating envelope — DC-09, measured

Bead: `rf2-drpa3.182.12`. Owner of the claim: **DC-09** in
[`../product-completion-setpoint.md`](../product-completion-setpoint.md).
Owner of the method:
[`../decisions/D021-performance-budgets-and-release-evidence.md`](../decisions/D021-performance-budgets-and-release-evidence.md).
Instrument:
`implementation/freehand/test/re_frame/freehand/bench/b10_two_clock.cljs`
and its rows next door in `b10_two_clock_dom_cljs_test.cljs`.

**This is measurement, not a law.** No `FH-…` id is minted and nothing enrols
in the conformance roster. The deterministic property the numbers sit on top
of — a controlled input drops nothing while its frame is busy — is already
rostered as **FH-INPUT-003**, whose own suite says the latency distributions
under contention "belong to the measurement spine". This is that spine. What
is new here is the *ladder* (0, 1, 10, 50, 200 contending siblings rather than
a single dozen) and the distributions beside it.

**The recommendation, up front: add no scheduling, throttle, debounce,
coalescing, equality or preview vocabulary.** Section 7 gives the three
attributed reasons. One material bottleneck did repeat in two witnesses, and
its remedy is a call-site change, not an API.

---

## 1. Provenance

Every figure below comes from one run, at one revision, in one browser.

| | |
|---|---|
| Revision | `e2b4225d6be141c7efaaf9c59e8f45c0425b286f` |
| Build | shadow-cljs `:browser-test`, `:optimizations :none`, `goog.DEBUG true` (`:instrumentation? true`) |
| Runtime | **HeadlessChrome 147.0.7727.15**, Windows 11 x64 |
| Hardware class | `:developer-workstation` |
| D021 status | `:d021 :release-evidence` — the record passed `provenance/result` |
| Raw records | `ai/findings/2026-07-28.two-clock-envelope-raw.edn` (local-only) |
| Gate result | 30 tests, 466 assertions, 0 failures, 0 errors |

Two earlier runs at the same fixture reproduced every figure below to within
the round-to-round spread, so nothing here rests on a single sample of the
machine.

### What does not transfer

**These are Chrome numbers under a development build.** Both words matter.

*Chrome.* No figure here is quoted for Node or for the JVM, and none should
be. Absolutes do not transfer across runtimes.

*Development build.* `goog.DEBUG` is true, so the Spec 009 instrumentation
seam, schema validation and trace emission are all live on exactly the event
path this report measures. B6 records the same hazard against its own rows and
takes its published numbers from an `:advanced` bundle instead. **Every cost
here is therefore an upper bound, and every rate a lower bound, on what a
shipped application does.** The production reading was not taken — see
section 8.

---

## 2. The instrument, and the two controls that make it readable

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
and one that did not land is counted rather than dropped.** The row reports
**0 unverified of 800** (section 3) and **0 unverified of 640** (section 5).
That rule is not decorative: B6 records a first pass that timed writes inside
`flushSync` which never reached the page at all and returned entirely
reasonable milliseconds for them.

The `:fresh-equal` arm, whose whole point is that the page must **not** change,
cannot be verified by the page standing still — an arm that did nothing would
also achieve that. It is verified at the store instead: the `app-db` reference
must have moved and the value must not have.

### C1 — a control of computed duration

A busy-wait of exactly **4.00 ms** rides in every cell of every table, timed
through the same window.

| | predicted | measured |
|---|---|---|
| Standalone, n = 12 | 4.00 ms | min 4.0, p50 4.0, p95 4.0, p99 4.0, max 4.0 |
| Inside all 40 published M1 cells | 4.00 ms | p50 4.0 everywhere; p95 4.0–4.3 |

Overshoot at p50 was **0.0 ms**, and never exceeded 0.3 ms anywhere. That is
clock quantisation with nothing left over: **the machine was not contended
while these numbers were taken.** Had it been, the burn arm would have said so
in every table simultaneously, which is exactly why it is in every table.

### C2 — a control of computed size

Each mode's DOM mutation count is arithmetic over the fixture, and is compared
against what a `MutationObserver` measured. Predicted equalled measured at
**every one of the fifteen (mode × rung) combinations**, with zero spread:

| mode | predicted | measured |
|---|---|---|
| `:host-only` | 1 attribute | 1, at every rung |
| `:fresh-equal` | 0 | 0, at every rung |
| `:one-leaf` at k siblings | k + 1 | 1, 2, 11, 51, 201 |

The driven arm (section 6) reproduces it independently: at k = 200 it recorded
4221 mutations for 21 crossings, 6030 for 30, 6633 for 33 and 6834 for 34 —
**exactly 201.0 per crossing in all four**.

---

## 3. The cost of one semantic crossing

300 cells, one of them the leaf; `k` names how many siblings the application
chose to dirty alongside it. Ranges are across **four rounds** — two walking
the ladder ascending, two descending — of 10 measured samples each after 3
warm-ups, with arm order rotating on the sample index. **0 unverified of 800.**

| k | host-only p50 | fresh-equal p50 | **one-leaf p50** | **one-leaf p95** | event p50 | commit p50 | DOM mutations |
|---:|---|---|---|---|---|---|---:|
| 0 | ≤ 0.1 | 0.2–0.3 | **3.5–4.6** | 5.9–9.2 | 3.1–4.2 | 0.4–0.5 | 1 |
| 1 | ≤ 0.1 | 0.2–0.3 | **3.4–4.8** | 5.3–10.4 | 2.9–4.0 | 0.5–0.7 | 2 |
| 10 | ≤ 0.1 | 0.2–0.3 | **4.3–5.1** | 6.5–10.7 | 3.2–3.9 | 1.0–1.2 | 11 |
| 50 | ≤ 0.1 | 0.2 | **6.9–7.8** | 10.5–12.0 | 3.3–3.8 | 3.5–4.0 | 51 |
| 200 | ≤ 0.1 | 0.2–0.3 | **18.3–22.2** | 21.9–27.5 | 3.7–4.1 | 13.0–17.5 | 201 |

All figures milliseconds, Chrome, dev build.

Four things this table says.

**The host clock is free, and it is free at every rung.** The `:host-only` arm
— a behavior writing its own node's transform, with nothing crossing into
`app-db` — reads at or below Chrome's 0.1 ms `performance.now()` clamp in all
40 readings. Its cost is *"under 0.1 ms"*, not *"zero"*; the instrument cannot
see smaller. Its **flatness** is asserted as a gate: a host arm that rose with
`k` would mean the sibling knob was leaking into the one arm that must not feel
it.

**A fresh-but-`rf=`-equal write costs 0.2–0.3 ms and repaints nothing.** Flat
across the whole ladder, 0 DOM mutations every time, while the store reference
demonstrably moved. This is the single most load-bearing number in the report
and section 7 leans on it.

**The event half is flat; the commit half is what scales.** `:event-ms` sits at
3–4 ms from k = 0 to k = 200 — the reducer, the install and the notification of
300 subscriptions cost the same whether one cell moved or 201. `:commit-ms`
goes 0.4 → 17.5 ms, about **0.085 ms per dirtied cell**. If a crossing is
expensive, it is expensive in React, not in re-frame.

**Both ladder orders agree.** The ascending and descending ranges overlap at
every rung except k = 200, where ascending reads ~1–3 ms higher. That
difference is *not* a ladder-order effect: the per-round p50 declines
monotonically from round 1 to round 4 at **every** rung in both orders
(k = 200: 22.2 → 19.8 → 19.1 → 18.3), so it is first-round warm-up that three
warm-up samples per cell did not fully absorb. Running both orders is what let
that be said rather than guessed.

---

## 4. Where the subscription is read costs more than how much changed

This was not on the matrix. It came out of it, and it is the most actionable
result in the report.

The `surface` view exists in two spellings that differ in exactly one thing:
whether the behavior's config subscription is read in the view that also
builds the 300 cells (`:parent`), or one boundary down inside the behavior's
own panel (`:leaf`). Same behavior, same config, same cells, same field.

| config shape | leaves | read site | stable p50 | fresh-equal p50 | **one-leaf p50** | DOM mutations |
|---|---:|---|---|---|---|---:|
| Vega-shaped (nested spec) | 96 | parent | 0.2–0.3 | 0.2–0.3 | **39.5–46.4** | 4 |
| Vega-shaped | 96 | leaf | 0.2–0.3 | 0.2 | **4.9–5.6** | 1 |
| Spread-shaped (flat range) | 900 | parent | 0.5 | 0.4–0.6 | **60.7–66.4** | 4 |
| Spread-shaped | 900 | leaf | 0.4 | 0.5 | **18.5–19.2** | 1 |

**The read-site penalty is a roughly constant 40–44 ms and does not scale with
the payload.** Vega pays 45.6 → 5.0; Spread pays 63 → 18.8. That constant is
the reconciliation of the 300-cell subtree whose parent now depends on a value
that changed — and it happens while moving *three extra DOM nodes*. The
cheapest reading of the expensive arm is that it is doing almost no DOM work
at all; it is re-rendering a subtree to discover that.

Separating the two terms is what the pair bought. Once the read site is
controlled for, the residual **is** payload size: 96 leaves cost 5.0 ms, 900
leaves cost 18.8 ms — about **0.017 ms per leaf** for a config that genuinely
changed.

Compare with section 3: *the same statement* — "one leaf of `app-db` changed"
— costs **3.5–4.6 ms** when the value is read at the leaf that uses it, and
**39.5–66.4 ms** when it is read at a parent that also builds a collection.
Between eight and nineteen times, in the same fixture, in the same build, for
the same size of change.

### Freehand already elides an unchanged config, and it is `=` that decides

The behavior's `:update` was called **exactly 52 times** in each of the four
(shape × read-site) combinations — which is exactly the number of `:one-leaf`
crossings (4 rounds × 13 samples). Stable-reference crossings and
fresh-but-equal crossings both produced **zero** `:update` calls.

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
both read sites.** 0.2–0.3 ms against 0.2–0.3 ms at 96 leaves; 0.4–0.5 ms
against 0.4–0.6 ms at 900 leaves. The ranges overlap completely. Neither
repaints anything. Two `p95` outliers (3.0 ms and 12.7 ms) appeared in earlier
runs at single samples and did not recur; p50 was unmoved by them.

A full structural walk of a 900-leaf map, then, costs **under a millisecond**,
and the difference between doing that walk and short-circuiting on identity is
below what this instrument can resolve. **0 unverified of 640.**

---

## 6. The envelope, driven rather than derived

A `setInterval` offers a semantic event every `1000/R` ms for 700 ms through
the **ordinary asynchronous `rf/dispatch`** — what a pointer handler calls, and
the only crossing that *can* build a backlog, since a synchronous one is
finished before the next offer is made.

`:expected` is computed — `floor(duration × rate / 1000)` — and is not a
measurement. It is what the application asked the browser for.

| k | rate asked | **expected** | **offered** | **applied** | **committed** | peak backlog | latency p50 / p95 / p99 | tail |
|---:|---:|---:|---:|---:|---:|---:|---|---:|
| 0 | 30 | 21 | 20 | 20 | 20 | 1 | 5.8 / 8.8 / 9.3 | 4.2 |
| 0 | 60 | 42 | 43 | 43 | 43 | 1 | 4.4 / 5.8 / 6.1 | 5.0 |
| 0 | 120 | 84 | 87 | 87 | 87 | 1 | 4.3 / 5.5 / 6.5 | 4.0 |
| 0 | 240 | 168 | **133** | 133 | 133 | 1 | 4.3 / 6.8 / 8.5 | 4.1 |
| 200 | 30 | 21 | 21 | 21 | 21 | 1 | 20.9 / 25.8 / 30.4 | 5.4 |
| 200 | 60 | 42 | **30** | 30 | 30 | 1 | 22.3 / 24.0 / 24.2 | 4.2 |
| 200 | 120 | 84 | **33** | 33 | 33 | 1 | 20.4 / 25.6 / 27.6 | 5.2 |
| 200 | 240 | 168 | **34** | 34 | 34 | 1 | 19.9 / 23.6 / 24.1 | 4.1 |

Milliseconds; "committed" is the DOM mutation count divided by the exact
per-crossing figure C2 predicts. `outstanding` was 0 in all eight runs — every
generation offered reached the page before the run was declared settled.

### The three findings

**Offered ≠ asked, and that is where the ceiling lives.** At k = 0 the browser
delivered 133 of 168 requested ticks at 240 Hz — a sustained **190/s**. At
k = 200 the offer rate saturates at **30–49/s no matter what is asked**: 30, 43,
47 and 49 per second for 30, 60, 120 and 240 Hz requested. The timer callback
*is* the main thread, so when a crossing takes 20 ms the browser simply stops
offering.

**Accepted = offered = committed, in all eight runs.** Nothing was refused,
dropped or coalesced away by the framework. The gap is entirely between what
the application asked for and what the browser was able to offer.

**Peak backlog was 1 at every rate and both loads.** Not "small" — *one*, which
is the single event in flight. The queue never grew, latency stayed bounded at
the cost of one crossing, and the settlement tail stayed at 4.0–5.4 ms even at
240 Hz requested against a 20 ms crossing. **The two-clock seam does not
degrade by queueing; it degrades by the browser making fewer offers.**

### Derived and driven agree

The strongest validation in the report is that two independent windows — a
synchronous `flushSync`-bracketed crossing and an asynchronous timer-driven one
— predict each other:

| k | section 3 p50 → implied ceiling | section 6 sustained rate |
|---:|---|---|
| 0 | 3.5–4.6 ms → 217–286/s | 190/s |
| 200 | 18.3–22.2 ms → 45–55/s | 49/s |

Within about 10% at both ends, from different code paths, different call sites
and different clocks.

### The envelope, stated

Two criteria, because they answer different questions.

**Throughput** — how many crossings actually land:

| k | sustained semantic rate (Chrome, dev build) |
|---:|---|
| 0 | ~190/s |
| 1–10 | ~150–190/s (interpolated; not driven) |
| 50 | ~100/s (interpolated; not driven) |
| 200 | **~49/s** |

**Latency quality** — the largest offered rate at which 95% of crossings finish
inside one budget of `1000/R` ms:

| k | crossing p95 (worst round) | 30 Hz (33.3 ms) | 60 Hz (16.7) | 120 Hz (8.33) | 240 Hz (4.17) |
|---:|---:|:-:|:-:|:-:|:-:|
| 0 | 9.2 | ✔ | ✔ | ✗ | ✗ |
| 1 | 10.4 | ✔ | ✔ | ✗ | ✗ |
| 10 | 10.7 | ✔ | ✔ | ✗ | ✗ |
| 50 | 12.0 | ✔ | ✔ | ✗ | ✗ |
| 200 | 27.5 | ✔ | ✗ | ✗ | ✗ |

**So: 60 Hz semantic crossing is comfortable up to 50 dirty siblings and breaks
between 50 and 200. 120 Hz never quite clears the p95 bar in a development
build even with nothing else dirty, although throughput reaches it easily. 240
Hz is not achievable at any load — the browser stops offering first.**

The rows marked interpolated were not driven; only k = 0 and k = 200 were.

---

## 7. Controlled input: no drops first, then latency

DC-09's acceptance criterion 2, in the order it is written.

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

**No equality hook.** A fresh-but-`rf=`-equal crossing costs 0.2–0.6 ms and
repaints nothing, at 96 leaves and at 900, at both read sites, indistinguishable
from re-installing the identical reference. Freehand already elides an
`:update` whose config is equal — proved by counting `:update` calls, 52 of 52
matching the changed crossings exactly. There is nothing here for an equality
hook to buy.

**No throttle, debounce or coalescing verb.** Peak backlog was **1** at every
rate and both sibling loads; `outstanding` was 0 in all eight driven runs; the
settlement tail never exceeded 5.4 ms. The browser's own timer back-pressure
already coalesces — under overload you receive *fewer offers*, never a growing
queue. A throttle verb would suppress offers the browser has already stopped
making, and would trade away the low-load responsiveness that costs nothing.

**No host-local state model.** The `:host-only` arm is under Chrome's clock
resolution at every rung. The existing behavior boundary is already the answer;
nothing needs adding to make host-rate motion cheap, because it already is.

**No preview lane.** Nothing measured here distinguishes a preview crossing
from any other crossing. `started` / `preview` / `committed` is an application's
choice about which events are worth having, and it costs nothing structural.

### The one bottleneck that did repeat — and why it is not an API

Section 4's read-site penalty *did* repeat in two witnesses (Vega-shaped and
Spread-shaped), it *is* material (40–44 ms, eight to nineteen times the leaf
cost) and it *is* attributed (subtree reconciliation, not DOM work, not
equality, not the reducer). It clears DC-09's bar for evidence.

Its remedy is a call site, not a verb. DC-09 already says to keep host-local
geometry and motion out of `app-db` unless the application genuinely needs the
live values. This adds the sharper corollary, which belongs in the guide:

> **If a high-rate value must cross into `app-db`, read its subscription at the
> boundary that uses it — never in a view that also builds a collection.**
> Moving one `v/sub` down one boundary took a config change from 45.6 ms to
> 5.0 ms in this fixture, while the number of DOM nodes that moved went *down*.

That is documentation and guidance work with a named owner (`rf2-fby7o`, the
guide), not new Freehand surface.

---

## 9. What this report could not determine

**The production envelope.** Every figure is from a development build with
`goog.DEBUG true`, so the Spec 009 instrumentation seam, schema validation and
trace emission all sit on the measured path. B6 publishes its own numbers from
an `:advanced` bundle for exactly this reason. Taking the same reading for B10
needs a `:browser` entry point plus a build target in
`implementation/shadow-cljs.edn`, which is a hot-zone file that was carrying
four concurrent siblings when this was measured; it was left alone rather than
raced. **The direction is known and safe — production is faster, so every rate
here is a lower bound — but the factor is not measured, and the 120 Hz row in
section 6 is exactly the row that factor could flip.**

**Real pointer streams.** The driver is a `setInterval`. A browser delivering
coalesced `pointermove`, or `pointerrawupdate` bursts, can hand several events
to one task, which is the one shape that could produce a backlog greater than 1.
The backlog-of-1 result is measured against a timer and should not be quoted
for a raw-pointer burst without re-measuring.

**Sub-0.1 ms costs.** Chrome clamps `performance.now()` to 100 µs. The
`:host-only` arm and, at the bottom of its range, the `:fresh-equal` arm sit on
that clamp. Their costs are "under 0.1 ms", and nothing finer can be said with
this instrument.

**Rungs between 0 and 200 in the driven arm.** Only k = 0 and k = 200 were
driven; the k = 1/10/50 throughput figures in section 6 are interpolated from
section 3 and are marked as such.

**One machine.** Single workstation, single browser version. The *shares* —
event flat, commit scaling, read-site constant, backlog invariant — are the
transferable claims. The absolutes are not.

---

## 10. Reproducing

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
