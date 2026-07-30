# P0 — the converged witness set, and the red-zones re-derived on it

**The P0 clock table now describes one set of pages.** It did not before. This
page re-measures the frontier arm — UIx reading re-frame2 subscriptions — on
[rf2-2rtt6.2's witnesses](p0-reagent-on-subs-baseline.md), so that the ship
bar's rows and the red-zone thresholds a candidate is judged against can be
read down one column.

Bead **rf2-a4x1o**. The rows are appended to the operator-owned standard bead
**rf2-2rtt6.1**; only the operator amends the bar, the budgets, the kill
criteria or the red-zones. Nothing here amends any of them.

## The extent of the mismatch, measured rather than feared

rf2-2rtt6.4 said on its own page that its witnesses did not match
rf2-2rtt6.2's, and that its **U-broad** row was the closest correspondence.
That is right as far as it goes. Setting the two arms' declarations beside each
other gives the full extent:

| rf2-2rtt6.2 row | rf2-2rtt6.4 row | comparable? |
|---|---|---|
| **M1 mount** — 901 el, 300 boundaries, `[:p0/cell i]`, `li > span + span` | **W1 mount** — 1,203 el, 300 boundaries, `[:p0/row i]`, `li > img + span + em` with a style map and a `data-*` passthrough | **No.** Same boundary count and the same *kind* of sub graph, but 33% more elements and four per row against three. Markup density is exactly what a clock ratio is sensitive to. |
| **M2 mount** — 51 el, 12 fields, `[:p0/cell i]` | **W3 mount** — 51 el, 12 fields, `[:p0/field i]` | **Nearly.** Identical element count and identical markup skeleton (`div > label + input + p`); the class on the form differs and nothing else does. Two real differences remain: the sub's *value* is a scalar on one side and a `{:value :error}` map on the other, and the two rows are graded differently — see below. |
| **bulk broad** — the 901-el M1 page, 300 boundaries, one commit all read, `[:p0/cell i]`, written with `frame/replace-app-db!` | **U-broad** — a 301-el grid, 300 boundaries, one commit all read, `[:p0/cell i]`, written with `dispatch-sync` | **No, but closest.** Boundary count and sub graph match, as rf2-2rtt6.4 said. Markup density differs by 3× and the write path differs by an event pipeline. |
| — | **U-narrow** | **No counterpart at all.** rf2-2rtt6.2 has no narrow row. |
| — | heap: **list** and **grid** | A different axis; see *What did not need re-running*. |

**And one mismatch that is not about witnesses at all.** rf2-2rtt6.2 grades its
51-element form row **diagnostic** and says explicitly that it *must not be
quoted against the bar*, because at one mount a sample it sits within three to
six of Chrome's 100 µs quanta. rf2-2rtt6.4 batched eight mounts to a sample,
lifted the same-sized witness clear of the clamp, and published its form row as
a **threshold** — `0.893×`, disjoint from 1.0. So the table asked a candidate's
form row to be judged red or not red against a threshold whose bar-row
counterpart is, by the denominator arm's own ruling, unquotable. That is an
internal inconsistency independent of page shape, and it is resolved here by
grading the converged form row **diagnostic**, like its bar row.

**So: one arm needed re-running, not two, and not none.** The clock axis was
genuinely incomparable and is re-measured below. The heap axis was not.

## What did not need re-running, and why

Retained heap. The red-zones on that axis already rest on **two independent
witness families measured by two independent instruments**, and they agree:

| source | witness | UIx ÷ Reagent, exclusive retained per boundary |
|---|---|---|
| rf2-2rtt6.4 | list, 1,203-el page, 300 boundaries | **2.262×** [2.196 – 2.335] |
| rf2-2rtt6.4 | grid, 301-el page, 300 boundaries | **2.254×** [2.217 – 2.288] |
| [rf2-2rtt6.5](reads-per-boundary-heap-ladder.md) | 1,200 boundaries × 1 read | **2.435×** (3,811 B ÷ 1,565 B above a component-free React floor) |

Those three shapes differ in markup density by 4× and in boundary count by 4×,
and the answers sit within 8% of each other. **Retained bytes per subscribing
boundary is a property of the boundary.** The clock is not: a page's element
count decides what fraction of the measured window is React's own work, which
is precisely the term that moves a substrate ratio toward or away from 1.0.

That asymmetry is the reason exactly one arm is re-run here, and it is stated as
a claim the operator can reject rather than assumed. The three figures come from
two different instruments and are not a replication in the strict sense; what
they are is three independent shapes agreeing on an axis where the clock's four
rows do not.

## Which witness set, and why that one

**rf2-2rtt6.2's.** Four reasons, in order of weight:

1. **The bar's denominator defines the witness set by construction.**
   [HD-012](../decisions.md) states the ship number as *mount and bulk view-work
   ≤ 1.0× Reagent, like-for-like*. The red-zone is a second threshold layered
   onto the same rows — a rule about where a candidate sits relative to UIx — so
   it must be derived on the pages the denominator lives on, not the reverse.
2. **rf2-2rtt6.2 is on main and owns the measurement lane.**
   [HD-017](../decisions.md) gives `:hicasso-bench` and its driver to that arm;
   rf2-2rtt6.4's tree rides `:freehand-release`'s compiler settings through a
   `--config-merge` precisely because the lane had not landed when it was built.
   Running the frontier arm on the lane retires that workaround, and this entry
   touches no build id and no `implementation/shadow-cljs.edn`.
3. **rf2-2rtt6.4 nominated it.** Its *Open items* names rf2-2rtt6.2's set as the
   convergence target.
4. **rf2-2rtt6.2's witnesses already carry the control and the lower bound.**
   `:ctl-2x` is an in-plan positive control at exactly twice the boundaries, and
   the published `:reagent-ratom` arm is a labelled lower bound on the same page.

## Provenance

| | |
|---|---|
| **Producing commit** | `44900cd4fd35815b3e2462ae7752242efcb296b9` |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, sibling agents live on the box |
| **Build** | `:hicasso-bench` — `:browser`, `:advanced`, `goog.DEBUG false`. **No new build id; `implementation/shadow-cljs.edn` untouched** |
| **Adapters** | `:rf.adapter/reagent` and `:rf.adapter/uix`, one per segment. Reagent 2.0.1 · UIx 1.4.4 · React 19.2.0 |
| **Schedule** | 5 rounds × 2 segments × (8 warm-up + 12 samples) per arm; three arms interleaved at the sample level, order rotating **and reflecting** on the sample index; segment order alternating with the round; **one row per page** |
| **Arm-order guard** | **reportable on every row**, both factors, no refusal. Self-test 8/8 before anything was measured; driver exit **0** |
| **Canonical-DOM parity** | clean under `:advanced` in both segments **and across the seam**, every row — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 2,460** — 600 M1 mounts + 600 M2 mounts + 630 broad writes + 630 narrow writes, each read back out of the DOM inside its own window |

The reproduction command was run **at this commit, on a clean working tree**,
and the figures below are that run's. A second, independent five-round run of
the identical instrument is published beside them; where the two disagree, the
page says so rather than picking one.

All bar numbers are browser numbers. No JVM or Node figure appears on this page.

## The arms, and the seam

| arm | what it is | role |
|---|---|---|
| `:floor` | the same DOM hand-built with `react/createElement` — no substrate, no boundary, no subscription | the **calibrator**, in *both* segments, and the thing that makes a cross-segment ratio legitimate |
| `:reagent-subs` | `reg-view` boundaries reading `@(rf/subscribe [:p0/cell i])`, `rf/frame-provider` at the root, `reagent.dom.client` for the mount | **the denominator** |
| `:uix-subs` | `defui` boundaries reading `(use-subscribe [:p0/cell i])`, `frame-provider` at the root, `uix.dom` for the mount | **the frontier** |
| `:ctl-2x` | the floor at exactly twice the boundaries | the **positive control**, predicted before the run, measured *inside* the interleave |

Neither substrate arm is hand-optimised: no `set-hiccup-emitter!` on the UIx
side (that would measure a hiccup interpreter, not UIx), no `use-memo` around
subscription args, no prop threading in place of a read. The markup is
rf2-2rtt6.2's markup on both sides, and the canonical-DOM gate is what proves
it — attribute names sorted, inside each segment and across the seam.

**Three arms a segment, never two.** rf2-ouwh8 records that `slot-order` rotates
and then reflects, and at k=2 those cancel: `[0 1]` rotates to `[1 0]` and
reflects back to `[0 1]`, at every sample index, for ever, so a two-arm plan
runs in one order and *both orders* is a claim it cannot support. Keeping
`:ctl-2x` inside the interleave — which is where rf2-2rtt6.2 puts it — means
this entry never forms a two-arm plan. The property is **asserted at boot**, in
both directions (degenerate at 2, varied at 3), and the run refuses to measure
if either assertion fails.

**`install-adapter!` is once per process** (Spec 006 §Single adapter per
process), so each round runs two segments — destroy the adapter, install the
other, re-register, re-seed — with the floor in both. The floor holds no
re-frame state and is untouched by which adapter is installed, so a
UIx-over-Reagent figure is a ratio of two floor-normalised ratios and the seam
cancels. **That cancellation is published, not assumed:**

| row | floor(UIx segment) ÷ floor(Reagent segment), per round | verdict |
|---|---|---|
| mount M1 | 0.900 · 1.056 · 1.000 · 0.938 · 1.000 | straddles 1.0 |
| mount M2 | 0.800 · 1.000 · 1.167 · 0.875 · 1.000 | straddles 1.0 |
| bulk broad | 0.625 · 1.000 · 1.200 · 0.667 · 1.200 | straddles 1.0 |
| bulk narrow | 1.000 · 1.000 · 1.000 · 1.200 · 1.000 | straddles 1.0 |

The seam is indistinguishable from unity on every row — a materially quieter
seam than the one rf2-2rtt6.4 published, which moved by up to 1.8×.
**A single-segment absolute millisecond from this page is still not a quotable
figure.**

## RED-ZONE — clock, on rf2-2rtt6.2's witnesses

**UIx-on-subs ÷ Reagent-on-subs**, both floor-normalised in the same round and
the same segment. Ranges are min–max across the five rounds. A range that
includes 1.0 means the two are **indistinguishable** and is reported as such
rather than as a winner.

| witness | **threshold (mean)** | range | per round | verdict |
|---|---|---|---|---|
| **M1 mount** — 901 el, 300 boundaries | **1.2301×** | 1.1099 – 1.3538 | 1.3065 · 1.1417 · 1.2388 · 1.1099 · 1.3538 | **UIx slower**, disjoint from 1.0 |
| **M2 mount** — 51 el, 12 fields · *diagnostic* | 1.0539× | 0.8572 – 1.4286 | 1.4286 · 0.8572 · 0.8572 · 1.0550 · 1.0714 | **straddles 1.0 — indistinguishable** |
| **bulk broad** — one commit all 300 read | **0.6239×** | 0.4701 – 0.7857 | 0.7172 · 0.6046 · 0.5417 · 0.7857 · 0.4701 | **UIx faster**, disjoint from 1.0 |
| **bulk narrow** — one commit exactly one reads | 1.1556× | 1.0417 – 1.2500 | 1.1111 · 1.2500 · 1.2500 · 1.0417 · 1.1250 | UIx slower — **but see the stability note** |

Both arms against the floor, for context:

| witness | `reagent-subs ÷ floor` | `uix-subs ÷ floor` |
|---|---|---|
| M1 mount | 4.352× [4.063 – 4.625] | 5.343× [4.947 – 5.944] |
| M2 mount | 2.102× [1.625 – 2.800] | 2.261× [1.714 – 4.000] |
| bulk broad | 7.443× [7.000 – 8.000] | 4.607× [3.667 – 5.500] |
| bulk narrow | 1.820× [1.500 – 2.000] | 2.117× [1.667 – 2.500] |

### The denominator reproduces rf2-2rtt6.2

The Reagent arm here is a second implementation of rf2-2rtt6.2's arm, written
against the same witnesses in a different app namespace and run in a different
schedule. Its `reagent-subs ÷ floor` ranges **overlap that arm's published
ranges on all three shared rows**:

| row | rf2-2rtt6.2, published | here | overlap |
|---|---|---|---|
| M1 mount | 3.899× [3.447 – 4.300] | 4.352× [4.063 – 4.625] | yes, 4.063 – 4.300 |
| M2 mount | 1.874× [1.750 – 2.050] | 2.102× [1.625 – 2.800] | yes |
| bulk broad | 7.064× [6.200 – 7.700] | 7.443× [7.000 – 8.000] | yes, 7.000 – 7.700 |

That is the check that says the convergence moved the *frontier* arm onto the
denominator's pages without moving the denominator.

### Two estimators, published together

Every threshold above divides one floor-normalised ratio by another, so the two
segments' floors enter it. On the bulk rows a floor sample is only two to four
of Chrome's 100 µs quanta, which is coarse enough to be worth checking. The
**raw** cross-segment estimator — `uix-subs` p50 over `reagent-subs` p50 in the
same round, touching neither floor — is therefore reported beside it:

| row | floor-normalised | raw cross-segment | agree? |
|---|---|---|---|
| M1 mount | 1.2301 [1.1099 – 1.3538] | 1.2028 [1.0405 – 1.3538] | yes — same verdict, 2% apart on the mean |
| M2 mount | 1.0539 [0.8572 – 1.4286] | 0.9989 [0.8571 – 1.1429] | yes — both straddle |
| bulk broad | 0.6239 [0.4701 – 0.7857] | 0.5582 [0.4483 – 0.6500] | yes — same verdict, both far from 1.0 |
| bulk narrow | 1.1556 [1.0417 – 1.2500] | 1.1972 [1.1111 – 1.2500] | yes |

The two estimators agree on the verdict of every row, which is the evidence that
the floor normalisation is doing its job rather than injecting the quantisation
it is exposed to.

### Stability across runs — and the one row that is not stable

The identical instrument was run twice, five rounds each, minutes apart:

| row | run at the published commit | independent second run | stable? |
|---|---|---|---|
| M1 mount | **1.2301** [1.110 – 1.354] disjoint | 1.2103 [1.021 – 1.316] disjoint | **yes** |
| M2 mount | 1.0539 [0.857 – 1.429] straddles | 1.1000 [1.000 – 1.458] straddles | **yes** |
| bulk broad | **0.6239** [0.470 – 0.786] disjoint | 0.5682 [0.444 – 0.691] disjoint | **yes** |
| bulk narrow | 1.1556 [1.042 – 1.250] disjoint | 1.0405 [0.750 – 1.333] **straddles** | **NO** |

**The narrow row does not settle.** Four estimates of it (two runs × two
estimators) read 1.0405, 1.1556, 1.1738, 1.1972 — the *direction* is the same
every time and UIx is the slower arm — but whether the range clears 1.0 depends
on the run, and one of the four has a minimum of exactly `1.0000`, which is the
clamp's signature and not a tie. Every leg of that row is three to five quanta.
**The narrow row is therefore published as clamp-limited and NOT as a resolved
threshold**: read it as *UIx is somewhere between indistinguishable and ~1.2×
slower on a narrow write*, and do not judge a candidate red on a margin finer
than that. Lifting it would need a bigger sample per window — batching k writes
into one clock window, as rf2-2rtt6.4 did for its own clamped rows — which is a
change to the instrument, not to the witness, and is left as a stated open item
rather than made silently.

### Clock resolution, per row

Stated rather than smoothed. One sample, p50 milliseconds, expressed in Chrome's
100 µs quanta:

| row | floor | `reagent-subs` | `uix-subs` | usable? |
|---|---|---|---|---|
| M1 mount | 8 – 10 | 33 – 46 | 39 – 54 | yes |
| M2 mount | 2 – 4 | 6 – 7 | 6 – 8 | **diagnostic only** |
| bulk broad | 2.5 – 4 | 20 – 29 | 11 – 13 | yes on the substrate legs; the floor is coarse, which is why the raw estimator is published beside the normalised one |
| bulk narrow | 2 – 3 | 4 – 4.5 | 4.5 – 5 | **clamp-limited** — see above |

## What the convergence changed

Three of rf2-2rtt6.4's four clock verdicts do not survive the move onto
rf2-2rtt6.2's witnesses. That is the answer to *how much did the mismatch
matter*, and it is not small.

| question | rf2-2rtt6.4, on its own witnesses | converged, on rf2-2rtt6.2's | change |
|---|---|---|---|
| large-list mount | W1: **1.057×** [0.907 – 1.156] — indistinguishable | M1: **1.2301×** [1.110 – 1.354] — UIx slower, disjoint | **verdict flips**: an indistinguishable row becomes a resolved one |
| ordinary-form mount | W3: **0.893×** [0.843 – 0.956] — UIx faster, disjoint, published as a threshold | M2: **1.0539×** [0.857 – 1.429] — indistinguishable, graded diagnostic | **verdict flips**, and so does the grade |
| broad commit | U-broad: **0.838×** [0.760 – 0.953] — UIx faster | bulk broad: **0.6239×** [0.470 – 0.786] — UIx faster | same direction, **much larger margin** |
| narrow commit | U-narrow: **1.536×** [1.226 – 1.876] — UIx slower, disjoint | bulk narrow: 1.1556× / 1.0405× — direction the same, magnitude far smaller, **does not stably resolve** | **strength collapses** |

None of this makes rf2-2rtt6.4 wrong. Each of its ratios is still a ratio
between two arms measured in one run, on one page, through one sub graph, in
both orders — which is exactly what it claimed. What the table above shows is
that a clock ratio *on this axis* moves by more than the effects being measured
when the page changes, so the thresholds and the bar rows have to come off the
same page or the comparison means nothing.

The mechanism is visible in the two extremes. The broad row's margin widens on
the 901-element page because more of the window is markup construction, where
UIx's compile-time `$` beats Reagent's runtime hiccup walk; the narrow row's
margin narrows because on the bigger page the floor's top-down re-render — the
thing a narrow write is compared against — is three times as much work.

## Where the time goes, per leg

p50 milliseconds, split at the microtask boundary. This is the same
decomposition rf2-2rtt6.4 published on its narrow row, reproduced on the
converged witness:

| row / arm | write | microtask gap | forced drain |
|---|---|---|---|
| broad / `reagent-subs` | 0.0 | 0.0 | **2.10** |
| broad / `uix-subs` | **0.40** | **0.80** | 0.0 |
| narrow / `reagent-subs` | 0.0 | 0.0 | **0.40** |
| narrow / `uix-subs` | **0.40** | 0.10 | 0.0 |
| both / `floor` | 0.0 | 0.0 | 0.20 – 0.30 |

The two substrates put their cost in different legs and the split is total. On
Reagent everything is the drain — `reagent.core/flush`, the reaction walk. On
UIx nothing is the drain: the cost is in the **write leg and the microtask that
follows it**, before React is involved at all — the `useSyncExternalStore`
notification fanning out across 300 subscribed boundaries. On the narrow row the
UIx write leg *alone* (0.40 ms) is the whole of Reagent's window (0.40 ms) for
the same operation, which is the structural claim a native view layer has on
that row, and it is a claim about the React-hook spine's invalidation rather
than about UIx's rendering.

## The positive controls

Predicted from the element count, written down before the run, published every
run whether they pass or not. Slack 25%, and it is generous on purpose: the
claim a clock control certifies is *the instrument has signal*, not *the model
is exact*. (The ±0.001% standard this wave set belongs to the **heap** control,
where the predicted quantity is a known retained byte count; no clock control
can honestly be held to it.)

| row | predicted | Reagent segment | UIx segment | basis |
|---|---|---|---|---|
| M1 mount | 1.9989× | 1.863× [1.750 – 2.000] ✅ | 1.979× [1.895 – 2.000] ✅ | 1801 / 901 elements |
| M2 mount | 1.9412× | 1.803× [1.600 – 2.000] ✅ | 1.731× [1.333 – 2.000] ✅ | 99 / 51 elements |
| bulk broad | 1.9989× | 1.817× [1.667 – 2.000] ✅ | 1.923× [1.667 – 2.250] ✅ | 1801 / 901 elements |
| bulk narrow | 1.9989× | 2.013× [1.667 – 2.400] ✅ | 1.867× [1.667 – 2.000] ✅ | 1801 / 901 elements |

Eight controls, eight passes, and seven of the eight sit **below** their
prediction — the direction a fixed per-root term predicts, and the same
direction rf2-2rtt6.2 and rf2-2rtt6.4 both recorded.

## The guard refused this arm, twice, and the arm was repaired

The first cut ran all four rows in one page. **The arm-order guard refused it,
exit 2**, on two independent faults. Both were the arm's; the tolerance was not
touched.

**1. Phase — the page degraded as it ran.** `M1/uix-subs/floor` — an arm that
hand-builds React elements and *cannot change* — read

| arm | last third ÷ first third | ranges |
|---|---|---|
| `M1/uix-subs/floor` | **2.1739×** | **disjoint** |
| `M1/reagent-subs/floor` | 2.0909× | overlapping |
| `M1/reagent-subs/ctl-2x` | 2.0652× | overlapping |
| `M1/uix-subs/ctl-2x` | 1.9608× | overlapping |
| `M1/uix-subs/uix-subs` | 1.4086× | overlapping |

Everything on the page climbed together; the floor climbed hardest. This is the
accumulation rf2-2rtt6.4 recorded and could not explain — proportional to
mounts, surviving a forced major collection, never reaching the document — whose
repair there was *one round per page*. **The repair here is one ROW per page**,
which cuts a page's measured work from about 2,460 operations to about 600 and
is the smaller of the two changes. It was enough: every arm of every row is
`clean` on both factors in both subsequent runs, with round-per-page held in
reserve.

**2. Predecessor — the collector mislabelled adjacency.**
`narrow/reagent-subs/ctl-2x` was refused on a **two-sample stratum labelled with
an arm that ran in a different row** (`M1/reagent-subs/reagent-subs`, 3.6250×,
ranges disjoint). `lane/collect!` advances the `:previous` pointer only for
*recorded* samples, so the first recorded sample after a warm-up block carries
whatever ran twenty operations earlier. rf2-2rtt6.4 met the same class from the
other side, where the untagged samples became a `<none>` stratum reading 1.35×
its siblings. **Two repairs, both in the arm:** the warm-up now advances the
pointer without banking a sample, so every recorded sample carries its real
predecessor; and one row per page leaves no cross-row adjacency to mislabel.

A refusal is not a broken script. It is the instrument saying that a figure it
produced depends on where in the plan it was measured — and every figure the
first cut produced looked entirely plausible.

## Method

- **Both orders.** Arms rotate *and reflect* on the sample index; segment order
  alternates with the round. Three arms a segment, never two, because at two the
  rotation and the reflection cancel (rf2-ouwh8) — asserted at boot in both
  directions, and the run refuses to measure if the assertion fails.
- **Position before adjacency.** Every sample carries its position in the whole
  page and the guard partitions on first-third against last-third as well as on
  predecessor. Warm-up matters more than interleaving, and warm-up samples are
  discarded but still count as predecessors.
- **Ranges, never a mean alone.** Overlapping ranges mean indistinguishable and
  the page says so.
- **Every measured mount and every measured write is read back out of the DOM
  inside its own window**, and the count is published as `N unverified of M`.
  Mounts are checked against a *written* element count (901, 51) as well as
  their content, so the gate can answer false for an arm that rendered nothing.
- **A positive control with predicted vs measured, every run, per segment.**
- **Canonical-DOM parity before any clock**, inside each segment and across the
  seam — the two substrate arms are compared with each other directly, not
  merely each with its own floor.

## Open items — stated, not swept up

- **The narrow row is clamp-limited and does not stably resolve.** Lifting it
  needs k writes to a clock window. That is a change to the instrument and not
  to the witness, so it can be made without disturbing the convergence — but it
  is a change, and rf2-2rtt6.2 has a recorded refusal from batching *mounts*, so
  the guard must be re-run against it rather than assumed.
- **The 51-element form row cannot be lifted the same way here.** rf2-2rtt6.2
  tried batching mounts on this exact witness and the guard refused the whole
  run (exit 2, all four arms 3.2×–5.4× last-third over first-third, ranges
  disjoint). This entry does not re-litigate that; the row stays diagnostic.
- **rf2-2rtt6.4's page and tree are not on main** (PR #7265 was open when this
  was measured). Its clock rows remain sound as ratios and are superseded as
  *thresholds* by the table above. Its **heap** rows are not superseded by
  anything here — this entry measures no heap.
- **The per-page accumulation is still unexplained.** One row per page makes it
  harmless to these figures; it does not make it fixed.
