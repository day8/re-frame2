# P0 — the Reagent-on-subs baseline (mount + bulk)

**The ship bar's denominator.** [HD-012](../decisions.md) states the bar as *mount
AND bulk view work ≤ 1.0× Reagent, like-for-like — both sides reading re-frame2
subscriptions*. Until this page there was no such denominator in the repo: every
published Reagent row compared against Reagent reading a bare `reagent.core/atom`
through an `r/cursor`, which is Reagent's own idiom but is a denominator no
re-frame2 application has. This page supplies the one the bar names, and prices
the difference between the two.

Bead **rf2-2rtt6.2**. The rows are appended to the operator-owned standard bead
**rf2-2rtt6.1**; only the operator amends the bar, the budgets, the kill criteria
or the red-zones.

## Provenance

| | |
|---|---|
| **Producing commit** | `19401ad083e895b19d55151157f37a59551cb5e2` |
| **Reproduction** | `cd implementation && npm run bench:hicasso` |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium, via Playwright), Windows x64 |
| **Build** | `:hicasso-bench` — `:advanced`, `goog.DEBUG false` |
| **Adapter** | `:rf.adapter/reagent`; Reagent 2.0.1 |
| **Schedule** | 5 rounds × (8 warm-up + 12 samples) per arm per round, arms interleaved at the sample level, order rotating **and reflecting** on the sample index |
| **Arm-order guard** | **reportable** — no arm reads differently for its position in the plan. Self-test 8/8 before anything was measured |
| **Canonical-DOM parity** | clean under `:advanced` — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 1220** (400 mounts M1 + 400 mounts M2 + 420 writes bulk) |

All bar numbers are browser numbers. No JVM or Node figure appears on this page.

## The arms

| arm | what it is | role |
|---|---|---|
| `:floor` | the same DOM, hand-built with `react/createElement`, no substrate | the **per-round calibrator**. This box drifts further across rounds than several of the effects being measured, so every figure is a ratio to the floor measured in *that* round |
| `:reagent-subs` | `reg-view` boundaries reading `@(rf/subscribe [:p0/cell i])` against one frame, `rf/frame-provider` at the root | **the denominator.** The bar's `1.0×` is this arm |
| `:reagent-ratom` | form-2 components over `r/cursor` on a bare `r/atom` | a **labelled lower bound**, never the bar. `subs ÷ ratom` is the reactive system's price, measured rather than argued |
| `:ctl-2x` | the floor at exactly twice the boundaries | the **positive control**, predicted from the element count before the run; parity-exempt because it builds a different page on purpose |

One page hosts all four. The predecessor programme costed this arm at *"two
adapter phases bridged by the floor"* because its own view substrate was inert
under a ratom adapter; every arm here is Reagent or nothing, so one
`(rf/init! reagent/adapter)` serves all of them.

## The rows

Ranges are min–max **across the five rounds**. A range that includes 1.0 means
the two arms are **indistinguishable**, and is reported as such rather than as a
winner.

### Mount — M1: 300 sub-reading boundaries (901 elements) · **bar row**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | **3.899×** | 3.447 – 4.300 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 3.224× | 2.632 – 3.633 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | **1.218×** | 1.122 – 1.310 | disjoint from 1.0 |

Absolute p50 per round, ms: floor 1.80 / 1.90 / 1.65 / 1.50 / 1.50 ·
`reagent-subs` 7.35 / 6.55 / 6.05 / 6.45 / 6.00 · `reagent-ratom` 6.25 / 5.00 /
4.65 / 5.45 / 5.35.

### Mount — M2: the ordinary 12-field form (51 elements) · **diagnostic only**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | 1.874× | 1.750 – 2.050 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 1.785× | 1.625 – 2.083 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | 1.056× | 0.960 – 1.242 | **straddles 1.0 — indistinguishable** |

**This row is diagnostic-grade and must not be quoted against the bar.** A
51-element mount takes a few tenths of a millisecond — three to six of Chrome's
100 µs `performance.now()` quanta — so its ratios are quantised more coarsely
than a 10% effect. What it does say is that the ordinary form shape shows **no
large reactive-system penalty on mount**: at this size the subscription graph and
the bare cursor are not distinguishable.

### Bulk — one commit that all 300 sub-reading boundaries read · **bar row**

| ratio | mean | range | verdict |
|---|---|---|---|
| `reagent-subs ÷ floor` | **7.064×** | 6.200 – 7.700 | disjoint from 1.0 |
| `reagent-ratom ÷ floor` | 3.519× | 3.200 – 3.900 | disjoint from 1.0 |
| **`reagent-subs ÷ reagent-ratom`** — the reactive leg | **2.008×** | 1.938 – 2.100 | disjoint from 1.0 |

Absolute p50 per round, ms: floor 0.60 / 0.50 / 0.55 / 0.50 / 0.50 ·
`reagent-subs` 4.20 / 3.85 / 3.75 / 3.80 / 3.10 · `reagent-ratom` 2.00 / 1.95 /
1.85 / 1.90 / 1.60.

Window decomposition (p50 ms): the write leg and the microtask gap both read
**0.0** on every arm — `frame/replace-app-db!` installs into the container and
returns, and Reagent commits inside its own drain — so essentially the entire
window is the drain: floor 0.50, `reagent-subs` 3.80, `reagent-ratom` 1.90,
`ctl-2x` 1.00.

**The bulk floor is not a lower bound.** It is a plain top-down React re-render,
which is what an application with no reactive substrate costs. A fine-grained
substrate can and should beat it on a narrow write; on a *broad* write, where
every boundary changes, it is the thing to beat.

## The positive control

Predicted from the element count, written down before the run, published every
run whether it passes or not.

| row | predicted | measured (mean) | range | basis | verdict |
|---|---|---|---|---|---|
| mount M1 | 1.999× | 1.839× | 1.632 – 1.944 | 1801 / 901 elements | ✅ within ±25% |
| mount M2 | 1.941× | 1.674× | 1.500 – 1.833 | 99 / 51 elements | ✅ within ±25% |
| bulk | 1.999× | 1.930× | 1.800 – 2.200 | 1801 / 901 elements | ✅ within ±25% |

The slack is 25% and is generous on purpose: the claim being certified is *the
instrument has signal*, not *the model is exact* — a top-down React re-render is
not perfectly linear in element count, because the root, the commit and the diff
walk do not double. Every measured control sits **below** its prediction, which
is the direction that fixed per-root overhead predicts.

## What this settles, and what it does not

**Settles.** The bar's denominator exists and is a browser number. Reading
re-frame2 subscriptions rather than a bare cursor costs Reagent **≈1.22× on
mount** of the 300-boundary shape and **≈2.01× on a broad commit** — both
disjoint from 1.0, so both real. That figure was previously argued rather than
measured, and it is the term that made the predecessor's `13.5×` broad-update row
an upper bound of unknown content.

**Does not settle.** Nothing about a candidate: no Hicasso arm exists, and none is
quotable against this table until it does. Nothing about UIx (rf2-2rtt6.4 owns the
frontier comparator and the red-zone ratios are set from it). Nothing about narrow
writes (rf2-2rtt6.3) or retained heap (rf2-2rtt6.5). And nothing about the
ordinary form shape at better than clock resolution.

## Instrument faults caught, and what each cost

Both were caught by the harness's own discipline **before** any number was
published, and both were repaired in the arm.

**1. A read-back that could not pass — `400 unverified of 400`.** The mount
verification probed a `data-i` cell in both witnesses; the form witness carries no
such attribute, so every M2 mount was counted unverified while the page was in
fact perfectly correct. A read-back that cannot pass is worse than none: it
manufactures a defect and would hide a real one behind it. Verification is now per
witness and reads **both ends** of the page — a page that committed only its head
passes a single-probe check at index 0.

**2. The arm-order guard refused a plausible instrument change — exit 2.**
Batching M2 to eight 51-element roots in one `flushSync` window, to lift it clear
of the clock clamp, produced this:

| arm | last third ÷ first third | predecessor factor |
|---|---|---|
| `M2/floor` | **5.017×**, ranges disjoint | clean |
| `M2/ctl-2x` | **5.427×**, ranges disjoint | clean |
| `M2/reagent-ratom` | **4.766×**, ranges disjoint | clean |
| `M2/reagent-subs` | **3.234×**, ranges disjoint | clean |

— while the unbatched M1 row, running immediately before it in the same page,
drifted 1.13×–1.16× with ranges overlapping. Position, not adjacency; a property
of the batched arm, not of anything under test. **The batch was withdrawn and the
tolerance was not touched.** The resulting clock coarseness is stated on the M2
row above and that row is graded diagnostic. A refused figure and a quantised
figure are not the same thing: the first may not be published at all, the second
may be published with its resolution named.

## Method

- **Both orders.** Every pairing runs in both arm orders — the schedule rotates
  *and reflects* on the sample index, because a bare cyclic rotation changes only
  which arm goes first and leaves every adjacency intact.
- **Position before adjacency.** Every sample carries its position in the whole
  run, and the guard partitions on first-third against last-third as well as on
  predecessor. Warm-up matters more than interleaving.
- **Ranges, never a mean alone.** Overlapping ranges mean indistinguishable.
- **Every measured write and every measured mount is read back out of the DOM
  inside its own window**, and the count is published as `N unverified of M`.
- **A positive control with predicted vs measured, every run.**
- **Arm labels are row-qualified** (`M1/floor`, `M2/floor`, `bulk/floor`) before
  they reach the guard: three witnesses' floor arms are three different amounts of
  work, and pooled under one name their ranges are disjoint by construction —
  the guard would refuse the witness table rather than a contamination.
