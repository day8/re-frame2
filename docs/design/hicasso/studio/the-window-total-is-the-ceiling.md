# The window total is the ceiling, and `rise` cannot see it — R = 20's separator

Seat: RE-ANALYSIS, EP-0038. Bead `rf2-onozm`, carried out of `rf2-0gjqi` when
that bead closed on its own audit obligation. **No window was taken, no browser
was launched, no bundle was built and no rig file was edited.** Every figure
below is re-derived first-hand from datasets already committed to this
repository, on `main` at `ca0abf8971`.

The corpus is the two paired allocation runs at
`implementation/hicasso/test/re_frame/bench/hicasso/data/alloc-0gjqi/`, committed
at `e77c4969e9` beside
[the sign follows the pass, not the write](the-sign-follows-the-pass-not-the-write.md).
Runtime, carried from that record because it is the runtime these bytes were
measured in and no new bytes were measured here: Chromium via Playwright,
shadow-cljs `release` on build id `:hicasso-bench`, `:optimizations :advanced`,
`goog.DEBUG false`, `--expose-gc`, `:init-fn re-frame.bench.p0-app/-main`, taken
2026-08-18 14:26–14:31 AUSEST on branch `worker/pairedwin-0gjqi`, built at
`1f004b15ff`. Both runs' positive control passed at **8.00 B/double** against a
predicted 8, and both captured **exit 1** on the falls gate — so **no slope is
quotable and none is quoted**, exactly as on the page that took them.

Reproduction, from `implementation/`:

```bash
node hicasso/test/re_frame/bench/hicasso/alloc_window_ceiling.cjs
```

That reader launches nothing and writes nothing. Every figure on this page is in
its output.

## The answer, first

**What separates a certifying R = 20 window from a refusing one is the window's
WOULD-BE TOTAL — `6 × legMedian`, what the window would have allocated had
nothing collected inside it. The recorded `rise` cannot separate them, and the
reason is arithmetic rather than luck.**

- **`rise` drops the evidence.** `rise = Σ max(leg, 0) + Σ max(gap, 0)` — checked
  on every window in the corpus, **0 mismatches of 528**. A leg the collector
  emptied contributes zero rather than its negative reading, so a refusing window
  reports the legs it has LEFT. On this bench five refusing-family legs
  (5 × 174,308 = **871,540 B**) come to within 2.2% of six surviving-family legs
  (6 × 142,188 = **853,128 B**). Across the eight R = 20 arm families `rise`
  spans **3.42%**.
- **The would-be total separates the sixteen cell medians gap-free.** Twelve
  refusing cells at **1,028,670 – 1,055,028 B**, four certifying at
  **849,930 – 860,634 B**, a **168,036 B** gap and no overlap in 16 of 16.
- **At WINDOW granularity the separator is ONE-SIDED, and that is the finding
  rather than a caveat.** The highest would-be total any certified window in the
  corpus carries is **884,280 B**. **Zero of the 72 windows above it certify;
  420 of the 456 at or below it do (92.1%).** So the total is a **NECESSARY**
  condition for certification, **not a sufficient one** — 36 refusals sit below
  the ceiling, 16 of them with a collection inside the window and 20 without.
- **The refusal signature is AT LEAST ONE collection, whose dominant leg is of
  near-constant size.** All 72 R = 20 refusals carry a negative leg — but **76
  negative legs fall across those 72 windows**, three of them carrying more than
  one, so this is a **lower bound on the count and not an equality**. The
  *first* negative leg sits at leg 6 in 39 windows and leg 5 in 31, with one
  each at legs 3 and 4. The *deepest* leg per window has median **−803,634 B**,
  range **−817,444 to −792,368 B** — a 3.2% span on a quantity of 800 KB —
  against a cohort `legMedian` of **174,308 B**. **Both of those are
  one-reading-per-window censuses, so neither can count collections**; the four
  legs they drop are all much smaller (−377,744 to −25,704 B), which is why the
  near-constant *magnitude* survives while the *count* does not. See
  [section 4](#4-the-refusal-signature).
- **The rig change is SIZED, and the triage's own statement of it was too
  strong.** Dropping the top rung from six measured writes to five projects the
  twelve refusing cells to **857,225 – 879,190 B**. Against the certifying
  *band* (849,930 – 860,634 B) only **5 of 12** land inside — so *"inside the
  band where `uix | uix` already certifies"* does **not** hold. Against the
  corpus *ceiling* (884,280 B) **12 of 12** land at or below, with
  **5,090 – 27,055 B** of headroom. **The claim survives on the ceiling; it does
  not survive on the band.**

**Nothing was widened.** `ALLOC_LEG_TOLERANCE` stays the declared 0.25
placeholder, `ALLOC_FALL_THRESHOLD_B` stays 600,000, `ALLOC_MIN_WRITES` stays 6,
R = 20 stays on the ladder, τ is where the previous windows left it. **No
reading, threshold, band or budget status moves on this page.**

## 1. Why `rise` cannot discriminate

The bead recorded the refusals as *"dominated by a SINGLE leg — usually leg 5 or
leg 6 of 6 — reading approximately −800,000 B"*, and the triage that dispatched
this work observed that `rise` sits within a few percent across all eight
families. That observation is right; what makes it structural is the identity.

```
rise == Σ max(leg, 0) + Σ max(gap, 0)          0 mismatches of 528 windows
perWrite == rise / writes                       0 mismatches of 528 windows
```

Both are checked by the reader rather than asserted. `rise` sums the window's
POSITIVE deltas — over legs and over the sixteen-byte inter-leg gaps alike — so a
collection is invisible to it whether it lands inside a leg or between two.

The consequence is that a window which lost one leg reports the sum of five, and
five legs of a refusing family happen to weigh almost exactly six legs of the
surviving one:

| population | median `legMedian` | legs counted by `rise` | `rise` reports |
|---|---|---|---|
| R = 20 refusing (72 windows) | 174,308 B | 5 | ≈ **871,540 B** |
| R = 20 certifying (24 windows) | 142,188 B | 6 | ≈ **853,128 B** |

**2.2% apart.** That near-coincidence is a property of this bench's arm costs, not
a general one — but the blindness that makes it possible *is* general, and it is
the same blindness the masking bound inherits (see
[the fixed cost binds on one rung](the-fixed-cost-binds-on-one-rung.md#3-the-bound-and-the-certificate-disagree)).

## 2. The census, reproduced

Eight arm families, twelve windows each — two runs × six rounds — under each of
the two write selectors the paired mode drives in the same round on the same
page.

| family | certified | median `rise` | median `6 × legMedian` | `falls` |
|---|---|---|---|---|
| `reagent-subs` \| `lad/reagent` @all | 0/12 | 883,710 | **1,052,976** | 1 – 1 |
| `reagent-subs` \| `lad/reagent` @page | 0/12 | 880,268 | **1,046,682** | 1 – 1 |
| `reagent-subs` \| `lad/hicasso` @all | 0/12 | 882,814 | **1,044,594** | 1 – 3 |
| `uix-subs` \| `lad/hicasso` @all | 0/12 | 875,296 | **1,040,646** | 1 – 3 |
| `reagent-subs` \| `lad/hicasso` @page | 0/12 | 864,304 | **1,030,182** | 1 – 2 |
| `uix-subs` \| `lad/hicasso` @page | 0/12 | 863,164 | **1,028,670** | 1 – 1 |
| `uix-subs` \| `lad/uix` @all | **12/12** | 868,636 | **860,586** | 0 – 0 |
| `uix-subs` \| `lad/uix` @page | **12/12** | 854,454 | **850,050** | 0 – 0 |

**One family of four, under both writes, in both runs** — which is what
2026-08-13, 2026-08-17 and 2026-08-18 each found. `falls` is **zero in every
round of the surviving family and one or more in every round of the other six**.

Split the same windows into the sixteen run × segment × arm × write cells the
triage counted, each summarised by the median of its six rounds:

| cell | certified | `6 × legMedian` | `5 × legMedian` | median `rise` |
|---|---|---|---|---|
| run 2 \| `reagent-subs` \| `lad/reagent` \| all | 0/6 | 1,055,028 | 879,190 | 884,716 |
| run 1 \| `reagent-subs` \| `lad/reagent` \| all | 0/6 | 1,051,998 | 876,665 | 883,538 |
| run 1 \| `reagent-subs` \| `lad/reagent` \| page | 0/6 | 1,047,936 | 873,280 | 881,972 |
| run 2 \| `reagent-subs` \| `lad/reagent` \| page | 0/6 | 1,045,962 | 871,635 | 876,734 |
| run 2 \| `uix-subs` \| `lad/hicasso` \| all | 0/6 | 1,045,848 | 871,540 | 879,180 |
| run 1 \| `reagent-subs` \| `lad/hicasso` \| all | 0/6 | 1,044,666 | 870,555 | 883,192 |
| run 2 \| `reagent-subs` \| `lad/hicasso` \| all | 0/6 | 1,044,036 | 870,030 | 878,110 |
| run 1 \| `uix-subs` \| `lad/hicasso` \| all | 0/6 | 1,031,898 | 859,915 | 873,942 |
| run 1 \| `reagent-subs` \| `lad/hicasso` \| page | 0/6 | 1,030,548 | 858,790 | 871,724 |
| run 2 \| `reagent-subs` \| `lad/hicasso` \| page | 0/6 | 1,029,888 | 858,240 | 862,326 |
| run 2 \| `uix-subs` \| `lad/hicasso` \| page | 0/6 | 1,029,312 | 857,760 | 863,164 |
| run 1 \| `uix-subs` \| `lad/hicasso` \| page | 0/6 | **1,028,670** | 857,225 | 863,788 |
| run 1 \| `uix-subs` \| `lad/uix` \| all | **6/6** | **860,634** | 717,195 | 868,636 |
| run 2 \| `uix-subs` \| `lad/uix` \| all | **6/6** | 858,696 | 715,580 | 866,172 |
| run 2 \| `uix-subs` \| `lad/uix` \| page | **6/6** | 851,070 | 709,225 | 856,104 |
| run 1 \| `uix-subs` \| `lad/uix` \| page | **6/6** | 849,930 | 708,275 | 852,496 |

**Sixteen of sixteen separate at 1,028,670 B with a 168,036 B gap and no
overlap**, exactly as the triage derived. `rise` over the same sixteen cells
spans **852,496 – 884,716 B = 3.78%**, and its ordering does not follow the
verdict at all: the highest `rise` in the table belongs to a refusing cell and
the second highest to a certifying one.

## 3. The ceiling, which is what the sixteen cells were measuring

A cell median is one number from six windows. The corpus holds **528** arm
windows, and read at that granularity the separation is not a band with refusals
above and certifications below. It is a **CEILING**.

| population | n | certified |
|---|---|---|
| `6 × legMedian` **above 884,280 B** | 72 | **0** |
| `6 × legMedian` **at or below 884,280 B** | 456 | **420 (92.1%)** |

**884,280 B is the highest would-be total any certified window in this corpus
carries** — `uix-subs | lad/uix` @all at R = 20, run 1, round 1. Above it,
nothing certifies. Below it, certification is the norm and not a guarantee.

The 36 refusals below the ceiling, by rung: `floor` 3, R = 0 five, R = 1 ten,
R = 3 ten, R = 7 seven, R = 20 one. **Sixteen of them carry a negative leg and
twenty do not** — so windows well under the ceiling still meet a collection
sometimes, and still refuse for leg-shape reasons that have nothing to do with
size. Every one of the 108 refusals in the corpus is a **leg** refusal; **none**
is an intra-leg one, because `P0_ALLOC_BY_SITE` was unset and
`allocIntraLegRefusals` returns the empty list by construction under stride 2.

**What the ceiling is NOT is a measured threshold.** Between the highest
certified window (884,280 B) and the lowest refusing R = 20 cell median
(1,028,670 B) lies **168,036 B of territory this corpus never sampled**. The
ceiling is bounded from below by an observation and from above by nothing.

## 4. The refusal signature

**The count and the magnitude are two different claims, and only the second is
near-constant.** The corpus supports a **lower bound** on the number of
collections per refusing window — **one per window** — not an equality, and the
table separates the leg census from the two per-window statistics for that reason.

**A NEGATIVE LEG IS AN OBSERVATION, NOT A COLLECTION EVENT.** Corrected
2026-08-21 on the merged-PR audit of PR #8597 (`rf2-onozm`), which found this
table naming its 76 readings *events* and the record offering them as a lower
bound on the **number of collections**. That does not follow from this
instrument, and it contradicted this page's own surviving caveat. It fails in
**both directions**: one collection spanning a leg boundary can make several legs
negative, so 76 legs are not 76 collections; and one leg can contain several
collections that are never separately observable, so it is not a bound the other
way either. **The raw census below is unchanged and correct** — only what it is
called, and what is inferred from it, moves.

| quantity | reading | what it counts |
|---|---|---|
| R = 20 refusals carrying **at least one** negative leg | **72 of 72** | windows |
| negative legs **per window** | 1 × 69, 2 × 2, 3 × 1 | windows |
| negative-leg **observations** across those 72 windows | **76** | leg readings — **not** collections |
| position of the **first** negative leg (of 6) | leg 6 × 39, leg 5 × 31, leg 4 × 1, leg 3 × 1 | windows — blind to the 4 later legs |
| **deepest** leg per window, median | **−803,634 B** | windows — a magnitude, not a count |
| **deepest** leg per window, range | **−817,444 to −792,368 B** (3.2% span) | windows |
| all 76 negative legs, median | −803,480 B | leg readings |
| all 76 negative legs, range | −817,444 to −25,704 B | leg readings |
| the 4 legs the per-window statistics drop | −377,744, −312,396, −48,940, −25,704 B | leg readings |
| cohort `legMedian`, median | 174,308 B | windows |
| windows with a negative leg, corpus-wide | 88 of 528, **0 certified**; **5 carry more than one** | windows |

**The three multi-negative R = 20 windows, named in full**, because a census that
reports only the first position and only the deepest magnitude conceals them:

| window | negative legs | at | `falls` |
|---|---|---|---|
| `paired-run1` round 1, `uix-subs \| lad/hicasso @all` | **3** | legs 3, 5, 6 | 3 |
| `paired-run1` round 4, `reagent-subs \| lad/hicasso @page` | **2** | legs 5, 6 | 2 |
| `paired-run1` round 5, `reagent-subs \| lad/hicasso @all` | **2** | legs 4, 5 | 3 |

**The DOMINANT reclaim is near-constant across the corpus** while the arm costs
that fill the window are not, and it sits within 10% of the observed ceiling: a
window that allocates past roughly 880 KB meets **at least one** collection, and
a window under it meets none. **Exposing the four extra legs does not weaken
that magnitude reading** — every one of them is smaller than the dominant leg of
its own window, so the −803,634 B figure is unmoved. What it does is separate the
magnitude from a **count of leg readings**, which is a different quantity again
from a count of collections. **The collection-count lower bound stays where it
already was: one per refusing window.** The 76 does not raise it.

**One fact from the rig makes that consistent rather than merely suggestive, and
it is read from the rig's own record rather than proposed here.** Every window
opens immediately after a forced collection — three CDP
`HeapProfiler.collectGarbage` calls on an 80 ms beat, which Blink implements as
`Isolate::LowMemoryNotification()`; the prime leg exists precisely because that
collection leaves an excess the first work unit re-clears, and the prime is
excluded from the leg cohort, from `rise`, from `falls`, from `perWrite` and from
the certificate for that reason. So each window starts from a known-collected
heap and accumulates from there, and *"how much can a window allocate before the
next collection"* is a question with a single answer per window rather than a
function of what ran before it. **That fixes where the FIRST collection falls.
It says nothing about how many follow it inside the same window, and three
windows carry more than one**, so it does not license reading the signature as
exactly one collection.

**No mechanism is asserted beyond that.** The reader measures the reclaim and the
ceiling; it does not open the collector, and no nursery size, generational policy
or V8 internal is read or inferred. What can be reached about generational
behaviour on this instrument, and what cannot, is settled elsewhere and is not
re-litigated here: both retained counters are cross-generation totals, so a
promotion changes neither and no sampling density of this corpus can see one —
see the trigger row of
[the second mode is per-write, and the controls never move](the-second-mode-is-per-write-and-the-controls-never-move.md#the-candidates-and-which-the-evidence-excludes).

**The bead's standing instruction holds and nothing here weakens it: DO NOT
WIDEN A GATE TO ADMIT THESE WINDOWS.** A tolerance wide enough for a −800 KB leg
would admit every masked window the leg witness exists to refuse. What this page
adds is that the leg witness is the *only* instrument in the certificate that can
see these collections at all — `rise` and everything charged on it cannot.

## 5. The five-write projection, and what it rests on

The bead lists three candidate directions and prefers none. This page sizes the
first — *fewer measured writes per window at the top rung* — and sizes nothing
else.

Removing one measured leg from the top rung leaves `5 × legMedian`:

| comparison | result |
|---|---|
| the twelve refusing cells at 5 writes | **857,225 – 879,190 B** |
| vs the certifying band, 849,930 – 860,634 B | **5 of 12** land inside |
| vs the corpus ceiling, 884,280 B | **12 of 12** land at or below, headroom **5,090 – 27,055 B** |
| windows the corpus holds in 857,225 – 879,190 B | 6, **6 certified** |

**The triage's phrasing — that five writes puts every refusing family inside the
band where `uix | uix` already certifies — is REFUTED as stated.** Seven of the
twelve project *above* 860,634 B, by up to 18,556 B. **The conclusion survives on
a different warrant**: all twelve land under the ceiling above which nothing in
this corpus has ever certified, and inside the region where 420 of 456 windows
did.

**Three limits travel with that, and none is small.**

- **The projection is arithmetic, not a measurement.** No five-write window
  exists in any committed dataset. `5 × legMedian` is the recorded window with
  its top leg removed, which assumes the six legs are one repeated work unit —
  which is what `legMedian` and the ±25% leg tolerance already assume, but is
  assumed here for a window nobody has run.
- **The six in-band windows are all the surviving family.** The 6-of-6
  certification rate in 857,225 – 879,190 B is therefore **not independent of the
  family confound** the bead names. It is consistent with the size account and it
  is not evidence for it over a substrate account.
- **The headroom is thin at the top.** The worst cell projects to 879,190 B
  against a ceiling of 884,280 B — **5,090 B, or 0.6%**. A cell that runs 1%
  richer than this corpus's worst would sit on the ceiling. Five writes is
  **sized**, not **safe**, and only a window can tell the difference.

**And the rig change is not made here.** `implementation/core/test/re_frame/bench/p0_run.cjs`
is untouched by this page; a per-rung write count is a rig change and belongs in
its own window with the two other beads that want the same file.

## 6. What the bead's own aside now reads as

The bead recorded: *"WHY `uix-subs | uix` SURVIVES AND THE OTHER THREE DO NOT is
not established. It is the cheapest of the four per boundary, which is consistent
with the window-size account above and is not evidence for it."*

**It is now evidence, and the upgrade is specific.** The separation is on the
window TOTAL rather than on per-boundary cost, it is monotone and gap-free across
sixteen cell medians, and at window granularity it is a ceiling holding **0 of 72**
above and **420 of 456** below. Per-boundary cost enters only as what puts a
family on one side of the ceiling.

**It still does not establish causation.** `uix | uix` differs from the other
three by substrate as well as by size; n is two runs on one box; and the
168,036 B between the ceiling and the lowest refusal is unsampled. **A window at
five writes on the top rung is what would decide it**, and this page does not
take one.

## What was NOT concluded

- **No slope was fitted and none is published.** Both runs failed the falls gate,
  as their own record states.
- **The ceiling is not a threshold and is not proposed as one.** It is the
  highest certified observation in one corpus. **No gate, constant, threshold or
  rung parameter is added, moved or proposed on this page**, and none should be
  added for it.
- **The window total is NOT sufficient for certification.** Thirty-six windows
  below the ceiling refused, twenty of them without any collection inside. A
  reader who inverts the ceiling into "under 884,280 B certifies" has the
  implication backwards.
- **The COLLECTOR's behaviour is not established.** The dominant reclaim is
  near-constant and the ceiling is close to it; that is an observation, and no
  nursery size, generational policy or V8 internal is read, inferred or claimed.
- **The NUMBER of collections per window is a lower bound of ONE, and nothing
  here raises it.** The corpus establishes *at least one* per refusing window;
  76 negative-leg **observations** fall across the 72, and that is a count of
  readings rather than of collections. The reader's position and magnitude
  censuses take one reading per window each and structurally cannot even count
  negative legs. **Two corrections, both on merged-PR audits.** An earlier
  version read the signature as *exactly one* collection, which the raw corpus
  disproves in three windows (PR #8591); a later one called the 76 readings
  *events* and offered them as a lower bound on collections, which **this
  instrument cannot support in either direction** — one collection can span a leg
  boundary and mark several legs, and one leg can hide several collections
  (PR #8597, 2026-08-21 — [section 4](#4-the-refusal-signature)). Whether the
  extra legs are separate collections or one collection spanning a leg boundary
  remains **not established**, and no window was taken to decide it.
- **The five-write projection is not a measurement**, its in-band support is not
  independent of the family confound, and its worst cell clears the ceiling by
  0.6%. See [section 5](#5-the-five-write-projection-and-what-it-rests-on).
- **The other two candidate directions the bead lists are NOT sized here** — a
  ladder that stops below R = 20, and declaring R = 20 unmeasurable on this
  instrument and saying so in the validity witness. This page prices the first
  option only, because that is the one derivable without a window.
- **No rig file was edited and no window was taken.** τ untouched.
