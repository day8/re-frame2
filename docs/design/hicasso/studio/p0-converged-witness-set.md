# P0 — the converged witness set, and the red-zones re-derived on it

**The P0 clock table now describes one set of pages.** It did not before. This
page re-measures the frontier arm — UIx reading re-frame2 subscriptions — on
[rf2-2rtt6.2's witnesses](p0-reagent-on-subs-baseline.md), so that the ship
bar's rows and the red-zone thresholds a candidate is judged against can be
read down one column.

Bead **rf2-a4x1o**, re-published under a balanced design by **rf2-6i0i2**. The
rows are appended to the operator-owned standard bead **rf2-2rtt6.1**; only the
operator amends the bar, the budgets, the kill criteria or the red-zones.
Nothing here amends any of them.

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

**The hashes this page previously asserted but did not print.** The claim below
was *"the four bench files are byte-identical (same blob hashes) at the rebased
commit"* — asserted without the hashes, so a reader could not perform the check
that was the whole point of making it. Here they are, with the landed commit
the rebase produced:

| file (`implementation/freehand/test/re_frame/bench/hicasso/`) | blob at `44900cd4fd` **and** `4c3f7189c4` | on main `32cb224d6e` |
|---|---|---|
| `p0_converge_app.cljs` | `9b5c0d63db5528d8b9790111f9bc53cda052106f` | `f4b09dc20712…` — **moved** (#7275) |
| `p0_converge_run.cjs` | `9e620cdeb3a7643c74c321a358cdadaabf186465` | `253b468a6b3a…` — **moved** (#7270) |
| `lane.cljs` | `d32312d9c562f0b6aa7d7f84538eb81ffc18e61c` | `885592cf9fdd…` — **moved** (#7267, #7270) |
| `p0_reagent_views.cljs` | `4032e39779ce55fee1e1cd4f7a8e9561237e2cfd` | **unchanged** |
| `p0_uix_views.cljs` | `34e0e89d532f2af3b3289525509cf033bb03bc05` | **unchanged** |
| `order_guard.cljc` *(`implementation/core/test/re_frame/bench/`)* | `adf59ca03cfe8e2639de97c031c138838f2d34b7` | `e42450ef1c77…` — **moved** (#7267) |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs
git rev-parse 4c3f7189c4:$P   # 9b5c0d63db5528d8b9790111f9bc53cda052106f
git merge-base --is-ancestor 4c3f7189c4 origin/main && echo on-main
```

Four of the six have moved since — which is exactly why the rows above were
re-run rather than merely re-anchored; see [All four rows
reproduce](#all-four-rows-reproduce-against-the-revived-driver-rf2-rjfz1),
where the instrument is the **current** one and every published range is met.

| | |
|---|---|
| **Producing commit** | `44900cd4fd35815b3e2462ae7752242efcb296b9` — **off main.** Its landed equivalent is **`4c3f7189c4`**, whose instrument tree is byte-identical (the table above prints the hashes); this page landed as **`a987caca26`**. The rebase onto PR #7265 **rewrote the id without touching the instrument**, so the reproduction command was unaffected. Nothing was re-measured at the time. |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — **verified to run at main `32cb224d6e`, exit 0.** It did not, for the window between PR #7267 and PR #7275; that is recorded below and is now closed |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, sibling agents live on the box |
| **Build** | `:hicasso-bench` — `:browser`, `:advanced`, `goog.DEBUG false`. **No new build id; `implementation/shadow-cljs.edn` untouched** |
| **Adapters** | `:rf.adapter/reagent` and `:rf.adapter/uix`, one per segment. Reagent 2.0.1 · UIx 1.4.4 · React 19.2.0 |
| **Schedule** | 5 rounds × 2 segments × (8 warm-up + 12 samples) per arm; three arms interleaved at the sample level, order rotating **and reflecting** on the sample index; segment order alternating with the round; **one row per page** |
| **Arm-order guard** | **reportable on every row**, both factors, no refusal. Self-test 8/8 before anything was measured; driver exit **0** |
| **Canonical-DOM parity** | clean under `:advanced` in both segments **and across the seam**, every row — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 2,460** — 600 M1 mounts + 600 M2 mounts + 630 broad writes + 630 narrow writes, each read back out of the DOM inside its own window |

### The bulk-narrow row was re-taken (rf2-zb3qg)

Three of the four rows above stand as measured. **The narrow row does not — it
was re-run on a batched window** and both its figures and its provenance below
supersede the ones in the table above.

**The instrument is identified by content hash, not by commit SHA.** A rebase
moved this branch's SHAs between the run and the merge; the blobs did not move
at all, and were verified byte-identical afterwards.

| file (`implementation/freehand/test/re_frame/bench/hicasso/`) | blob |
|---|---|
| `p0_converge_app.cljs` | `f4b09dc20712e45f44f9ec9339f8dd00ce51e8f7` |
| `lane.cljs` | `885592cf9fdd79f701d6353fc5d3dae0868d74f1` |
| `p0_converge_run.cjs` | `253b468a6b3a96b3ca8e1d8e4f6d2ad6299445a1` |
| `p0_reagent_views.cljs` | `4032e39779ce55fee1e1cd4f7a8e9561237e2cfd` |
| `p0_uix_views.cljs` | `34e0e89d532f2af3b3289525509cf033bb03bc05` |

| | |
|---|---|
| **Producing commit** | `ec30ae12ef` on `worker/bench-tail-cluster`. **If that SHA does not resolve, a rebase moved it and the blobs above are what to trust.** |
| **Reproduction** | `HICASSO_ONLY=narrow node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — **run twice at this instrument, exit 0 both times** |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, 24 logical CPUs |
| **Schedule** | unchanged — 5 rounds × 2 segments × (8 warm-up + 12 samples), three arms interleaved, **10 writes per timed window** |
| **Arm-order guard** | **no refusal on either run**, tolerance 0.10, `contaminated? false`, `unchecked? false` |
| **Verification** | **0 unverified of 6,030 writes, on each run** |
| **Positive control** | predicted `1801 / 901 = 1.9989×` **before** the run; four measured ranges, all inside ±25%, all inside on **every round** |

`HICASSO_ONLY` re-takes one row rather than four, deliberately: the mount rows
and the broad row are untouched by the batching — the broad row passes a batch
of one, which is the pre-batch window exactly — so re-taking them would replace
sound published numbers with different ones for nothing. It is `hd8_run.cjs`'s
`HD8_ONLY`, for that driver's reasons.

To confirm a candidate commit carries this instrument:

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs
git rev-parse <candidate>:$P   # must print f4b09dc20712e45f44f9ec9339f8dd00ce51e8f7
```

**The reproduction command was run at this instrument, on a clean working tree
— and it produced the NARROW row and nothing else.** This paragraph used to say
*"the figures below are that run's"*, unqualified, which is not what happened:
`HICASSO_ONLY=narrow` re-took one row of four, exactly as the paragraph above
says it should. Three of the four rows below therefore come off a **different
instrument**, and a provenance block that does not partition its own table is
the blur such a block exists to prevent.

| rows | instrument | which run |
|---|---|---|
| M1 mount · M2 mount · bulk broad | the **earlier** instrument — `p0_converge_app.cljs` blob `9b5c0d63db5528d8b9790111f9bc53cda052106f`, at `4c3f7189c4` | the original five-round sweep in [Provenance](#provenance) above. Re-**checked** against the current instrument by [the rf2-rjfz1 sweep](#all-four-rows-reproduce-against-the-revived-driver-rf2-rjfz1); not re-taken |
| **bulk narrow** *(batched)* | `f4b09dc20712e45f44f9ec9339f8dd00ce51e8f7` — the blob table immediately above | run **twice** at this instrument, and both runs are published |

**Everything in this section is the provenance of the five-round rows, which
rf2-6i0i2 has since superseded.** It is kept because the five-round figures are
still quoted on the page as the values that were replaced, and a struck number
with no provenance is not a record of anything. The live rows come off **one
instrument and ten runs** — [the balanced ensemble's
provenance](#the-balanced-ensembles-provenance-rf2-6i0i2) — which retires the
partitioned table above: no row is now published off a different instrument from
any other, and no row rests on a single run.

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
both directions, and the run refuses to measure if either assertion fails.

**That defect has since been repaired** (PR #7267): at `k = 2` the reflection is
dropped and the plain rotation alternates, which is every order two arms have.
The three-arm plan is kept regardless — it is what every row on this page was
measured under, and the schedule an arm runs is part of its window. The boot
assertion now checks the *repaired* property as a canary. **It was not updated
when the repair landed, and the consequence was not cosmetic: the assertion went
false, `-main` refused to measure, and this page's reproduction command exited 1
before taking a single sample — from PR #7267 until rf2-zb3qg found it. The
figures above were unaffected (they predate the repair) but were not
reproducible at HEAD for that window.** **rf2-rjfz1 has since run the full
four-row sweep against the revived driver and every row reproduces — see
[All four rows reproduce](#all-four-rows-reproduce-against-the-revived-driver-rf2-rjfz1).**

**`install-adapter!` is once per process** (Spec 006 §Single adapter per
process), so each round runs two segments — destroy the adapter, install the
other, re-register, re-seed — with the floor in both. The floor holds no
re-frame state and is untouched by which adapter is installed, so a
UIx-over-Reagent figure is a ratio of two floor-normalised ratios and the seam
cancels. **That cancellation is published, not assumed:**

| row | floor(UIx segment) ÷ floor(Reagent segment), run 1 per round | over the ten runs | verdict |
|---|---|---|---|
| mount M1 | 1.000 · 1.105 · 1.125 · 0.900 · 1.118 · 1.000 | mean 1.029, run means 1.000 – 1.085 | straddles 1.0 in **10 of 10** |
| mount M2 | 1.167 · 1.000 · 1.000 · 1.000 · 1.125 · 1.000 | mean 1.019, run means 0.903 – 1.103 | straddles 1.0 in **10 of 10** |
| bulk broad | 1.000 · 0.857 · 1.143 · 1.000 · 0.857 · 0.857 | mean 0.971, run means 0.856 – 1.078 | straddles 1.0 in **10 of 10** |
| bulk narrow | 0.900 · 0.962 · 0.943 · 0.965 · 1.020 · 1.122 | mean 1.003, run means 0.957 – 1.029 | straddles 1.0 in **10 of 10** |

The seam is indistinguishable from unity on every row of every run — a
materially quieter seam than the one rf2-2rtt6.4 published, which moved by up to
1.8×. Forty independent chances to catch a drifting seam, and it did not drift.
**A single-segment absolute millisecond from this page is still not a quotable
figure.**

## RED-ZONE — clock, on rf2-2rtt6.2's witnesses

**UIx-on-subs ÷ Reagent-on-subs**, both floor-normalised in the same round and
the same segment. A range that includes 1.0 means the two are
**indistinguishable** and is reported as such rather than as a winner.

**These four rows are the balanced re-publication (rf2-6i0i2)**, and the
five-round figures they replace are struck in place. Every row below is the
mean over **ten independently launched six-round runs** whose starting segment
is counterbalanced five and five — the design [the segment-order
question](#the-segment-order-question-asked-properly) describes. At six rounds
the alternation splits 3:3, so the raw mean and the order-balanced mean are the
**same number by construction**; there is no longer a corrected value to print
beside a biased one.

| witness | **threshold** | 95% interval on the mean | run means (10) | verdict |
|---|---|---|---|---|
| **M1 mount** — 901 el, 300 boundaries | ~~1.2301×~~ → **1.2310×** | 1.2105 – 1.2514 | 1.1989 – 1.2931 | **UIx slower.** 59 of 60 rounds above 1.0; 19 of 20 order strata wholly above it |
| **M2 mount** — 51 el, 12 fields · *diagnostic* | ~~1.0539×~~ → **1.0601×** | 1.0017 – 1.1185 | 0.9561 – 1.1923 | **straddles 1.0 — indistinguishable**, in all ten runs |
| **bulk broad** — one commit all 300 read | ~~0.6239×~~ → **0.6291×** | 0.5996 – 0.6587 | 0.5792 – 0.6987 | **UIx faster.** All 60 rounds below 1.0; all 20 strata wholly below it |
| **bulk narrow** — 10 commits, each read by exactly one boundary, one window | ~~1.1540×~~ → **1.1754×** | 1.1579 – 1.1930 | 1.1390 – 1.2186 | **UIx slower.** All 60 rounds above 1.0; all 20 strata wholly above it |
| ~~bulk narrow, **unbatched window** (superseded twice over)~~ | ~~1.1556×~~ | — | — | ~~UIx slower, but did not stably resolve across runs~~ |

**The interval is on the mean, not on a run.** It is a Student-t interval over
the ten run means, so it says how well ten runs pin the centre; the *run means*
column is the spread a single fresh run should be expected to land in, and it is
two to four times wider. Quote the interval when comparing a candidate against
the centre; quote the run-mean spread when asking whether one run of a candidate
has cleared the threshold.

> **The two withdrawn magnitudes are restored, and no number is deleted.** M1's
> `1.2301` and narrow's `1.1540` were withdrawn because a 3:2 design biased them,
> their order strata were disjoint, and four estimates disagreed by ±6%. The
> balanced ensemble answers all three: the design is even, the strata **overlap
> in 9 of 10 runs on both rows**, and ten estimates now agree to ±4%. What is
> published is a magnitude **with an interval**, which is what the measurement
> supports; a bare four-decimal point never was. **rf2-2rtt6.1's operator hold on
> quoting `1.2301` is not lifted here** — that bead's number is superseded rather
> than rehabilitated, and only the operator amends the standard. See [the verdict
> on M1's magnitude](#the-verdict-on-m1s-magnitude).

**The pre-registered single run, printed because the design named it before it
ran.** The commit that balanced the design nominated run 1 — reagent-start, the
historic schedule — as the re-publication run *in advance*, so that the row
payloads could not be chosen after the fact. It is one of the ten, so it is
inside their spread by construction; what it is worth printing for is **where it
falls in that spread**, which is all over it — 4th lowest of ten on M1, 5th on
M2, **2nd lowest on narrow and 2nd highest on broad**. A single pre-designated
run is honest but arbitrary, and that is the case for publishing the ensemble
rather than any one member of it.

| witness | run 1 (pre-registered) | range | per round |
|---|---|---|---|
| M1 mount | 1.2159× | 0.9529 – 1.4286 | 1.4286 · 0.9529 · 1.2681 · 1.2186 · 1.1378 · 1.2892 |
| M2 mount | 1.0469× | 0.8571 – 1.3846 | 0.8571 · 1.3846 · 1.0714 · 1.0000 · 1.0794 · 0.8889 |
| bulk broad | 0.6662× | 0.5532 – 0.8485 | 0.6190 · 0.6328 · 0.6144 · 0.5532 · 0.8485 · 0.7292 |
| bulk narrow | 1.1542× | 1.0346 – 1.3602 | 1.2575 · 1.1161 · 1.0999 · 1.3602 · 1.0568 · 1.0346 |

Both arms against the floor, over the ensemble. Ranges are min–max across all
sixty rounds, which is why they are wider than the five-round ranges they
replace:

| witness | `reagent-subs ÷ floor` | `uix-subs ÷ floor` |
|---|---|---|
| M1 mount | ~~4.352×~~ → 4.358× [3.625 – 5.111] | **5.343×** [4.611 – 6.500] — the mean is unchanged to four figures |
| M2 mount | ~~2.102×~~ → 2.241× [1.250 – 4.500] | ~~2.261×~~ → 2.335× [1.375 – 4.000] |
| bulk broad | ~~7.443×~~ → 7.630× [6.167 – 10.000] | ~~4.607×~~ → 4.728× [3.333 – 6.000] |
| bulk narrow *(batched window)* | ~~1.880×~~ → 2.416× [1.965 – 2.682] | ~~2.168×~~ → 2.831× [2.618 – 3.059] |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.820× [1.500 – 2.000]~~ | ~~2.117× [1.667 – 2.500]~~ |

**The narrow row's floor-relative figures move a long way and the reason is the
floor, not the arms.** Both legs rise by almost the same factor — Reagent ×1.29,
UIx ×1.31 — because the narrow floor reads *faster* on this ensemble than it did
on the five-round run (16.5–30 quanta against 21–30). A common factor in both
numerators cancels out of a ratio of two floor-normalised ratios, which is why
the threshold itself barely moves: 2.831 ÷ 2.416 = 1.172, against the 1.1754 the
row publishes. That agreement is the floor normalisation working as designed.

### All four rows reproduce against the revived driver (rf2-rjfz1)

**Why this check exists.** `p0_converge_app` asserted at boot that
`lane/slot-order` *degenerates* at `k = 2`. PR #7267 repaired that degeneracy
and did not touch this file, so the assertion went false the moment the repair
landed: `p0_converge_run.cjs` **exited 1 before taking a single sample**, and
every row on this page was unreproducible at HEAD until rf2-zb3qg found it.
rf2-zb3qg re-ran only the narrow row, because only the narrow row's window had
moved — the right call, but it left **the M1 mount, M2 mount and broad rows
never once reproduced against a driver that runs.**

They have now been. One full four-row sweep at main `32cb224d6e`:

```bash
node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs   # exit 0
```

**This is a reproduction check, not a re-publication.** Ranges are min–max
across the five rounds; overlap with the published range and an unchanged verdict
is the test. The *published* column is the five-round table, which rf2-6i0i2 has
since superseded — this check is kept as the record that the three unmoved rows
were reproducible before they were re-taken.

| row | published *(five-round, superseded)* | reproduction sweep | overlap | verdict |
|---|---|---|---|---|
| **M1 mount** | 1.2301 [1.1099 – 1.3538] disjoint | 1.2887 [1.1462 – 1.4242] disjoint | 1.1462 – 1.3538 | **reproduces**, UIx slower both times |
| **M2 mount** *(diagnostic)* | 1.0539 [0.8572 – 1.4286] straddles | 1.0327 [0.8000 – 1.2727] straddles | 0.8572 – 1.2727 | **reproduces**, indistinguishable both times |
| **bulk broad** | 0.6239 [0.4701 – 0.7857] disjoint | 0.6020 [0.5263 – 0.7353] disjoint | wholly inside | **reproduces**, UIx faster both times |
| bulk narrow *(rf2-zb3qg's re-take, checked again here)* | 1.1540 [1.0570 – 1.2053] disjoint | 1.1693 [1.1136 – 1.2528] disjoint | 1.1136 – 1.2053 | **reproduces** |

Per round, the sweep read M1 `1.4242 · 1.1462 · 1.3611 · 1.3214 · 1.1905` ·
M2 `1.2727 · 0.8000 · 1.0000 · 1.0909 · 1.0000` · broad `0.5750 · 0.5263 ·
0.6176 · 0.5556 · 0.7353` · narrow `1.2528 · 1.1591 · 1.1705 · 1.1507 ·
1.1136`.

**Every operative clock red-zone in the table above is therefore now a
threshold that a reader can reproduce**, which for the three unmoved rows it
has never been.

| | |
|---|---|
| **Commit measured at** | `32cb224d6e5dde730d1e7ddc99c062656cb68155` — `origin/main`, clean tree |
| **Instrument** | unchanged from the rf2-zb3qg blob table above — `p0_converge_app.cljs` `f4b09dc2…`, `lane.cljs` `885592cf…`, `p0_converge_run.cjs` `253b468a…`, `p0_reagent_views.cljs` `4032e397…`, `p0_uix_views.cljs` `34e0e89d…`, all five **byte-identical on main** |
| **Arm-order guard** | **no refusal on any of the four rows** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10 |
| **Position completeness** | **zero lost positions on every arm of every row**; each phase contrast adjudicated on a full 20-against-20 of 60 |
| **Canonical-DOM parity** | clean in both segments and across the seam, every row — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 7,860** — 600 M1 mounts + 600 M2 mounts + 630 broad writes + 6,030 narrow writes |
| **Positive controls** | **this sweep's** four rows × two segments = eight, **eight passes** under the overlap rule; see below for the strict reading. Not the page's live count — the [positive-control table](#the-positive-controls) carries the ensemble's **eighty** |

**The M2 control that rf2-egdaq holds open reads differently on this sweep,
and the ruling is still the operator's.** The published M2 control range
`[1.333 – 2.000]` sits against a ±25% band whose floor is `1.4559`: it passes
the overlap rule `lane/control-verdict` implements and fails a strict
every-round-inside reading. **This sweep's M2 controls are `[1.500 – 2.000]`
(Reagent segment) and `[1.600 – 2.000]` (UIx segment) — both wholly inside the
band, so both pass under *either* reading.** That is reported, not used: a
re-run producing a friendlier control is not an argument for a rule, and
rf2-egdaq is not settled here. **Eighty controls have since been measured on the
balanced ensemble and they say something this one sweep could not** — the strict
reading fails 7 of 20 M2 controls, with a worst round 19.9% below the band floor;
[the breakdown](#the-strict-reading-over-eighty-controls-rf2-egdaq) is under the
positive controls. The broad row's controls on this sweep, `[1.600
– 2.500]` and `[1.667 – 2.500]`, miss the strict reading by `0.0014` — the
lattice point above a `2.4986` ceiling on a floor of two to three quanta, the
same quantisation artefact the denominator page records.

### The denominator reproduces rf2-2rtt6.2

The Reagent arm here is a second implementation of rf2-2rtt6.2's arm, written
against the same witnesses in a different app namespace and run in a different
schedule. Its `reagent-subs ÷ floor` ranges **overlap that arm's published
ranges on all three shared rows**:

| row | rf2-2rtt6.2, published | here, over the ten-run ensemble | overlap |
|---|---|---|---|
| M1 mount | 3.899× [3.447 – 4.300] | 4.358× [3.625 – 5.111] | yes, 3.625 – 4.300 |
| M2 mount | 1.874× [1.750 – 2.050] | 2.241× [1.250 – 4.500] | yes, wholly containing |
| bulk broad | 7.064× [6.200 – 7.700] | 7.630× [6.167 – 10.000] | yes, 6.200 – 7.700 |

That is the check that says the convergence moved the *frontier* arm onto the
denominator's pages without moving the denominator.

### Two estimators, published together

Every threshold above divides one floor-normalised ratio by another, so the two
segments' floors enter it. On the bulk rows a floor sample is only two to four
of Chrome's 100 µs quanta, which is coarse enough to be worth checking. The
**raw** cross-segment estimator — `uix-subs` p50 over `reagent-subs` p50 in the
same round, touching neither floor — is therefore reported beside it:

Both columns are now over the ten-run ensemble; the bracket is the spread of the
ten run means.

| row | floor-normalised | raw cross-segment | agree? |
|---|---|---|---|
| M1 mount | ~~1.2301~~ → **1.2310** [1.1989 – 1.2931] | ~~1.2028~~ → **1.2608** [1.2119 – 1.3607] | yes — same verdict, 2.4% apart on the mean |
| M2 mount | ~~1.0539~~ → **1.0601** [0.9561 – 1.1923] | ~~0.9989~~ → **1.0489** [0.9958 – 1.1111] | yes — both straddle |
| bulk broad | ~~0.6239~~ → **0.6291** [0.5792 – 0.6987] | ~~0.5582~~ → **0.5939** [0.5712 – 0.6282] | yes — same verdict, both far from 1.0 |
| bulk narrow *(batched window)* | ~~1.1540~~ → **1.1754** [1.1390 – 1.2186] | ~~1.1714~~ → **1.1761** [1.1323 – 1.2267] | yes — same verdict, both disjoint from 1.0 |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.1556 [1.0417 – 1.2500]~~ | ~~1.1972 [1.1111 – 1.2500]~~ | ~~yes~~ |

The two estimators agree on the verdict of every row, which is the evidence that
the floor normalisation is doing its job rather than injecting the quantisation
it is exposed to. On the narrow row they now agree to **0.06%**, which is the
tightest the two have ever been on this page.

### Stability across runs

**Superseded as the stability evidence by the ten-run ensemble** — ten runs give
a spread and an interval where two give only an anecdote, and the numbers are in
the [RED-ZONE table](#red-zone--clock-on-rf2-2rtt62s-witnesses) above. The pair
below is kept because it is what the page had at the time and because the
narrow row's story turns on it.

The identical instrument was run twice, five rounds each, minutes apart:

| row | run at the published commit | independent second run | stable? |
|---|---|---|---|
| M1 mount | **1.2301** [1.110 – 1.354] disjoint | 1.2103 [1.021 – 1.316] disjoint | **yes** |
| M2 mount | 1.0539 [0.857 – 1.429] straddles | 1.1000 [1.000 – 1.458] straddles | **yes** |
| bulk broad | **0.6239** [0.470 – 0.786] disjoint | 0.5682 [0.444 – 0.691] disjoint | **yes** |
| **bulk narrow** *(batched window, rf2-zb3qg)* | **1.1540** [1.0570 – 1.2053] disjoint | **1.1656** [1.1091 – 1.2942] disjoint | **yes** |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.1556 [1.042 – 1.250] disjoint~~ | ~~1.0405 [0.750 – 1.333] **straddles**~~ | ~~**NO**~~ |

#### The narrow row was clamp-limited, and now resolves

**As first published it did not settle.** Four estimates (two runs × two
estimators) read 1.0405, 1.1556, 1.1738, 1.1972 — the *direction* was the same
every time and UIx was the slower arm — but whether the range cleared 1.0
depended on the run, and one of the four had a minimum of exactly `1.0000`,
which is the clamp's signature and not a tie. Every leg of that row was three to
five quanta. It was therefore published as **clamp-limited and not as a resolved
threshold**, with the remedy named as an open item: batch *k* writes into one
clock window, a change to the *instrument* and not to the witness.

**rf2-zb3qg made that change and re-ran the row.** Ten single-cell commits now
share one clock, each with its own cell and its own value. Every leg is 21 to 65
quanta instead of 3 to 5, so the quantum is about 2% of a reading rather than
25% of it. All four estimates now clear 1.0:

| estimate | before (unbatched) | after (10 commits per window) |
|---|---|---|
| run 1, floor-normalised | 1.1556 [1.0417 – 1.2500] | **1.1540 [1.0570 – 1.2053]** |
| run 1, raw cross-segment | 1.1972 [1.1111 – 1.2500] | **1.1714 [1.0962 – 1.2316]** |
| run 2, floor-normalised | 1.0405 [0.7500 – 1.3330] ← *straddles* | **1.1656 [1.1091 – 1.2942]** |
| run 2, raw cross-segment | 1.1738 | **1.1447 [1.1091 – 1.1827]** |

**Lowest bound across all four is 1.0570.** The row is now published as a
resolved threshold: *UIx-on-subs is roughly 1.15× slower than Reagent-on-subs on
a narrow write, and the two are distinguishable.* The instruction not to judge a
candidate red on a finer margin than "indistinguishable to ~1.2×" is withdrawn —
the margin the row now supports is the range above.

**The centre did not move; the noise did.** The batched mean (1.1540 / 1.1656)
sits inside the spread of the four unbatched estimates, which is the outcome that
says the batching changed the *resolution* and not the *quantity*. The per-commit
cost confirms it independently: dividing each batched sample by ten gives floor
2.1–3.0 quanta, `reagent-subs` 4.75–5.55, `uix-subs` 5.70–6.45 — the same legs
the unbatched row measured directly (2–3, 4–4.5, 4.5–5).

**What the batch costs, stated rather than assumed.** A batched window verifies
all ten writes after the clock stops rather than each in its own turn, so an
early commit gets up to nine extra microtask turns before it is read back. That
is a difference of degree on a tolerance the instrument already grants — the
unbatched window tolerates exactly one such turn by construction, because that
turn *is* the harness yield. It is not a new blind spot: the fault the read-back
exists to catch is a commit React has parked at the default lane, which is
scheduled through a `MessageChannel` — a *macrotask* — and no number of
microtasks lets it land inside the window. A parked commit is still parked when
the read-backs run, and all ten would read unverified. **0 of 6,030 did, on each
run.**

### Clock resolution, per row

Stated rather than smoothed. One sample, p50 milliseconds, expressed in Chrome's
100 µs quanta:

Over the whole ensemble — 120 segment-rounds a row, so these are the widest
bounds the instrument has ever published rather than one run's.

| row | floor | `reagent-subs` | `uix-subs` | usable? |
|---|---|---|---|---|
| M1 mount | 6 – 11 | 27 – 47 | 35 – 65 | yes |
| M2 mount | 2 – 5 | 4.5 – 11 | 4.5 – 11 | **diagnostic only** — and the reason is right here: a single 100 µs quantum is up to 22% of a reading |
| bulk broad | 2 – 4 | 17 – 31.5 | 9 – 19.5 | yes on the substrate legs; the floor is coarse, which is why the raw estimator is published beside the normalised one |
| **bulk narrow** — 10 commits a sample | 16.5 – 30 | 41.5 – 66.5 | 48 – 74 | **yes** |
| ~~bulk narrow — 1 commit a sample (superseded)~~ | ~~2 – 3~~ | ~~4 – 4.5~~ | ~~4.5 – 5~~ | ~~**clamp-limited**~~ |

## The reactive leg, from a second author (rf2-2rtt6.21)

**The one term this page could not corroborate, it now measures.** The section
above shows that the Reagent-on-subs arm here reproduces rf2-2rtt6.2's
denominator. What it could not touch was rf2-2rtt6.2's **headline 1** — *reading
re-frame2 subscriptions rather than a bare cursor costs Reagent ≈1.22× on mount
and ≈2.01× on a broad commit* — because that figure needs a `:reagent-ratom` arm
beside the `:reagent-subs` arm, and this page had none. rf2-2rtt6.17 measured the
leg three times, and all three are re-runs of the **same** arm: three runs bound
an instrument's noise, and cannot bound a systematic error in how the arm is
written. Repetition, not replication.

`?ratom=on` puts that arm in the Reagent segment of the `M1` and `broad` rows.
The leg is then formed **inside one segment of one round** — both terms are
Reagent arms measured against the same floor, so the floor divides out exactly
and the segment seam is not in the arithmetic at all.

### What was added, and what deliberately was not

| | |
|---|---|
| **the arm** | `:reagent-ratom` — form-2 components minting one `r/cursor` per mounted occurrence over a bare `reagent.core/atom`, mounted through `reagent.dom.client` and drained with `reagent.core/flush` inside one `flushSync`. Everything except **where the value comes from** is held identical to the `:reagent-subs` arm beside it |
| **the rows** | `M1` mount and `bulk-broad` — the two rows the headline names |
| **not `M2`** | it is the row whose mount budget already refuses (720 mounts refused four of six), and its leg on the first author's own instrument read **exactly 1.0000 in all five rounds** of the corrected run, because its two Reagent arms returned the same p50 every time on a 4.5-to-11-quantum window. A second author cannot corroborate a number that is the clamp |
| **not `narrow`** | rf2-2rtt6.2 has no narrow row, so a ratom arm there would mint a new figure rather than corroborate one |
| **the segment** | the Reagent segment only. Putting a Reagent arm in the UIx segment would buy the leg nothing and would stand Reagent's reaction machinery on the page whose whole point is UIx's |
| **the default** | **OFF.** With the arm in it the Reagent segment runs four arms against the UIx segment's three, so the interleave and the page's total mount budget both differ from the schedule the ten-run ensemble above was measured under. An unflagged invocation of this driver is still that instrument |

**What "a second author" means here, stated rather than implied.** The two arms
share `p0_reagent_views.cljs` — `m1-cell-ratom` and `m1-ratom` are the same
functions on both pages, and that is deliberate: an arm rewritten as well as
re-scheduled would be a different arm, and a disagreement would not say which
change caused it. What is independently authored is **everything around the
components**: a different app namespace, a different round count, a segment
structure with an adapter destroyed and installed between every pair of
segments, one row per page instead of three rows in one, a different sampling
plan, a different aggregation and a different set of gates.

So this corroborates the leg against **harness, schedule and aggregation**
error, and it cannot corroborate it against an error in the component
definitions themselves. That limit is real and is left standing as an open item
below. It also makes the result below stronger rather than weaker: the
components were held fixed, and the number moved anyway.

### The budget it landed on, and the guard

Adding an arm adds mounts. `M1` runs **840 mounts a page** — 6 rounds × (4
Reagent-segment arms + 3 UIx-segment arms) × 20 samples — against the 720 every
clean six-round run of this page has used, and `broad` runs **882 writes**. The
[dose-response on the M2 row](#3-the-sixth-round-broke-the-m2-row-and-the-lever-was-the-mount-budget)
says the lever for a phase refusal is the page's total mount budget, so the risk
was named in the commit before the first sample was taken: *if the guard
refuses, it will be a last-third-slower phase split, and the repair is the
budget, never the tolerance.*

**It did not refuse.** Twelve row-runs, `refuse? false`, `contaminated? false`,
`unchecked? false`, tolerance 0.10, every one. The new arm's own worst factor
contrast across the six runs is **1.1957× on `M1` and 1.3333× on `broad`, ranges
overlapping in every case** — the same shape its `:reagent-subs` sibling shows
(1.2667× and 1.1957×, also overlapping). No budget repair was needed and none
was taken, so the 840-mount page is what these figures were measured on.

### Predicted before the run, in the commit that added the arm

`bcf80f1979` carries all five, written down before a sample existed:

| prediction | outcome |
|---|---|
| positive control, both rows, both segments — 1.9989× on 1801/901 elements, ±25% band [1.4992 – 2.4986] | **24 of 24 pass**, and all 24 pass the *strict* every-round-inside reading as well |
| `M1` leg — direction *subs slower*, disjoint from 1.0, magnitude **1.10 – 1.35** | direction and disjointness correct; magnitude **1.3353**, at the top edge of the predicted band |
| `broad` leg — direction *subs slower*, disjoint from 1.0, magnitude **1.8 – 2.3** | direction and disjointness correct; magnitude **2.4957**, **outside the predicted band** |
| `reagent-ratom ÷ floor` on `M1` — **3.3 – 3.9** | **3.2335** [3.1667 – 3.3375 across run means], just below the band |
| the guard may refuse `M1` at 840 mounts | it did not |

Two of the five predictions were wrong, both in the same direction, and that is
the finding rather than a mis-set expectation: the leg is **larger** here than
the first author's figure, because the ratom arm sits **closer** to the floor
than predicted while the subs arm sits where this page has always put it.

### `M1` mount — the leg, per run

`reagent-subs ÷ reagent-ratom`, both floor-normalised in the same round of the
same segment. Six runs, six rounds each, starting segment counterbalanced 3/3.

| run | start | mean | range | per round | Reagent-first stratum | UIx-first stratum | strata |
|---|---|---|---|---|---|---|---|
| 1 | reagent | **1.3319** | 1.2778 – 1.3788 | 1.3788 · 1.2963 · 1.3333 · 1.3396 · 1.3654 · 1.2778 | 1.3592 [1.3333 – 1.3788] | 1.3046 [1.2778 – 1.3396] | overlap |
| 2 | uix | **1.3266** | 1.2692 – 1.4000 | 1.2963 · 1.3137 · 1.2692 · 1.4000 · 1.3469 · 1.3333 | 1.3490 [1.3137 – 1.4000] | 1.3042 [1.2692 – 1.3469] | overlap |
| 3 | reagent | **1.3497** | 1.3061 – 1.4822 | 1.3621 · 1.3200 · 1.3061 · 1.4822 · 1.3214 · 1.3065 | 1.3299 [1.3061 – 1.3621] | 1.3695 [1.3065 – 1.4822] | overlap |
| 4 | uix | **1.3378** | 1.2500 – 1.4348 | 1.3725 · 1.3061 · 1.2500 · 1.4348 · 1.3636 · 1.3000 | 1.3470 [1.3000 – 1.4348] | 1.3287 [1.2500 – 1.3725] | overlap |
| 5 | reagent | **1.3284** | 1.2766 – 1.3750 | 1.3333 · 1.3750 · 1.3043 · 1.3478 · 1.3333 · 1.2766 | 1.3237 [1.3043 – 1.3333] | 1.3331 [1.2766 – 1.3750] | overlap |
| 6 | uix | **1.3373** | 1.2727 – 1.4039 | 1.3667 · 1.3333 · 1.2727 · 1.4039 · 1.3396 · 1.3077 | 1.3483 [1.3077 – 1.4039] | 1.3263 [1.2727 – 1.3667] | overlap |

**Ensemble 1.3353×**, 95% interval on the mean **1.3265 – 1.3441**, run means
spanning **1.3266 – 1.3497**. All 36 rounds are above 1.0 and all 12 order strata
are wholly above it; the strata overlap in **6 of 6** runs, so every run marks
the row `:claim :magnitude`. The start-group difference is **0.0028** —
reagent-start 1.3367 against uix-start 1.3339 — so which segment leads does not
move this figure.

### `bulk-broad` — the leg, per run

| run | start | mean | range | per round | Reagent-first stratum | UIx-first stratum | strata |
|---|---|---|---|---|---|---|---|
| 1 | reagent | **2.5504** | 2.4000 – 2.6875 | 2.5909 · 2.4000 · 2.4210 · 2.5556 · 2.6471 · 2.6875 | 2.5530 [2.4210 – 2.6471] | 2.5477 [2.4000 – 2.6875] | overlap |
| 2 | uix | **2.3613** | 2.1000 – 2.5789 | 2.5789 · 2.3000 · 2.1000 · 2.3000 · 2.5000 · 2.3889 | 2.3297 [2.3000 – 2.3889] | 2.3930 [2.1000 – 2.5789] | overlap |
| 3 | reagent | **2.4344** | 2.1363 – 2.5833 | 2.5833 · 2.3636 · 2.1363 · 2.5789 · 2.5556 · 2.3889 | 2.4251 [2.1363 – 2.5833] | 2.4438 [2.3636 – 2.5789] | overlap |
| 4 | uix | **2.4863** | 2.2105 – 2.7858 | 2.5883 · 2.3333 · 2.2105 · 2.5000 · 2.5000 · 2.7858 | 2.5397 [2.3333 – 2.7858] | 2.4329 [2.2105 – 2.5883] | overlap |
| 5 | reagent | **2.6017** | 2.3889 – 2.7778 | 2.7778 · 2.3889 · 2.5000 · 2.5625 · 2.6667 · 2.7143 | 2.6482 [2.5000 – 2.7778] | 2.5552 [2.3889 – 2.7143] | overlap |
| 6 | uix | **2.5401** | 2.3889 – 2.8750 | 2.7222 · 2.4210 · 2.4444 · 2.8750 · 2.3889 · 2.3889 | 2.5616 [2.3889 – 2.8750] | 2.5185 [2.3889 – 2.7222] | overlap |

**Ensemble 2.4957×**, 95% interval on the mean **2.4041 – 2.5873**, run means
spanning **2.3613 – 2.6017**. All 36 rounds above 1.0, all 12 strata wholly
above, strata overlapping in 6 of 6. Start-group difference **0.066** —
reagent-start 2.5288 against uix-start 2.4626 — inside this row's run-to-run
spread.

### Beside the first author: the direction agrees and the magnitude does not

| row | first author (rf2-2rtt6.2 · two re-runs, rf2-2rtt6.17) | second author (this page, six runs) | verdict |
|---|---|---|---|
| **M1 mount** | **1.218** [1.122 – 1.310] · **1.216** [1.185 – 1.241] · **1.213** [1.093 – 1.273] | **1.3353** [run means 1.3266 – 1.3497] | **direction agrees, magnitude does not.** Every second-author run mean is above every first-author mean, by 9.5% – 11.1%. The run-mean spreads are **disjoint** |
| **bulk broad** | **2.008** [1.938 – 2.100] · **1.965** [1.875 – 2.000] · **2.073** [2.000 – 2.167] | **2.4957** [run means 2.3613 – 2.6017] | **direction agrees, magnitude does not.** Every run mean is above every first-author *maximum*, by 14% – 27%. **Disjoint**, and not marginally |

**Both authors say the same thing about the world and a different thing about
the number.** Reading re-frame2 subscriptions rather than a bare cursor costs
Reagent something real and it costs it on both rows: 72 of 72 rounds across six
independent runs sit above 1.0, every order stratum sits above 1.0, and the
interval on the mean is nowhere near it. That is headline 1's *claim*, and a
second implementation corroborates it.

**Headline 1's numbers are a different matter.** `≈1.22×` and `≈2.01×` are not
reproduced: the same witness, the same view components and the same browser, run
under a second harness, give `1.34×` and `2.50×`. **The leg is not a constant of
the arm — it is a property of the arm *and its harness*, and the two harnesses
disagree by 10% on mount and by 24% on a broad commit.** Nothing here says which
harness is right, and this page does not adjudicate that: a disagreement between
two internally-clean instruments is the finding, and reconciling it away would
be inventing an answer neither instrument produced.

### Where the disagreement sits

The leg is a quotient, so it moves when either term does. Both terms are
published here for exactly that reason:

| row | figure | first author | second author (six-run ensemble) | overlap? |
|---|---|---|---|---|
| **M1** | `reagent-subs ÷ floor` | 3.899 [3.447 – 4.300] · 3.973 · 3.513 | **4.3143** [run means 4.1979 – 4.4417, widest round 3.9444 – 4.8824] | yes — and this page already published 4.358 [3.625 – 5.111] on the same figure |
| **M1** | `reagent-ratom ÷ floor` | 3.224 [2.632 – 3.633] · 3.266 · 2.913 | **3.2335** [run means 3.1667 – 3.3375, widest 2.8889 – 3.6000] | **yes, and closely — the two authors agree on this arm to 0.3% on the mean** |
| **broad** | `reagent-subs ÷ floor` | 7.064 [6.200 – 7.700] · 7.280 · 7.330 | **7.6959** [run means 7.4167 – 7.9294] | yes — and this page's own ensemble reads 7.630 |
| **broad** | `reagent-ratom ÷ floor` | 3.519 [3.200 – 3.900] · 3.707 · 3.540 | **3.0948** [run means 2.9722 – 3.2500] | barely — the second author's ratom arm sits **12% closer to the floor** |

**On `M1` the disagreement is entirely in the numerator.** The two authors'
ratom arms agree to within a third of a percent (3.2335 against 3.224), and the
subs arm is where the pages differ — which is not new, and is not this bead's
finding: [the denominator section](#the-denominator-reproduces-rf2-2rtt62) above
already published `reagent-subs ÷ floor` as 4.358 here against 3.899 there, with
overlapping ranges and a higher centre. The leg divides one by the other, so a
centre 11% higher in the numerator and unchanged in the denominator is a leg 11%
higher, which is what it reads.

**On `broad` both terms move, and they move apart.** The subs arm reads 9%
higher and the ratom arm 12% lower, and 1.09 ÷ 0.88 is the 24% the leg is out
by. The ratom arm's absolute window on this page is 0.70 – 1.20 ms against a
floor of 0.20 – 0.40 — seven to twelve of Chrome's 100 µs quanta against the
floor's two to four — so it is resolved, but it is the smallest window either
author measures a leg on.

Absolute p50 milliseconds in the Reagent segment, per round, so the quotients
above can be checked rather than taken:

| run | `M1` floor / subs / ratom | `broad` floor / subs / ratom |
|---|---|---|
| 1 | 1.00/4.55/3.30 · 0.80/3.50/2.70 · 0.80/3.40/2.55 · 0.80/3.55/2.65 · 0.80/3.55/2.60 · 0.75/3.45/2.70 | 0.35/2.85/1.10 · 0.30/2.40/1.00 · 0.30/2.30/0.95 · 0.30/2.30/0.90 · 0.30/2.25/0.85 · 0.25/2.15/0.80 |
| 2 | 0.80/3.50/2.70 · 0.80/3.35/2.55 · 0.80/3.30/2.60 · 0.80/3.50/2.50 · 0.80/3.30/2.45 · 0.80/3.20/2.40 | 0.30/2.45/0.95 · 0.30/2.30/1.00 · 0.30/2.10/1.00 · 0.30/2.30/1.00 · 0.30/2.25/0.90 · 0.30/2.15/0.90 |
| 3 | 0.90/3.95/2.90 · 0.80/3.30/2.50 · 0.80/3.20/2.45 · 0.85/4.15/2.80 · 0.90/3.70/2.80 · 0.90/4.05/3.10 | 0.40/3.10/1.20 · 0.30/2.60/1.10 · 0.30/2.35/1.10 · 0.30/2.45/0.95 · 0.30/2.30/0.90 · 0.30/2.15/0.90 |
| 4 | 0.80/3.50/2.55 · 0.80/3.20/2.45 · 0.70/3.00/2.40 · 0.75/3.30/2.30 · 0.70/3.00/2.20 · 0.70/3.25/2.50 | 0.30/2.20/0.85 · 0.30/2.10/0.90 · 0.30/2.10/0.95 · 0.30/2.00/0.80 · 0.20/2.00/0.80 · 0.30/1.95/0.70 |
| 5 | 0.90/4.00/3.00 · 0.80/3.30/2.40 · 0.70/3.00/2.30 · 0.70/3.10/2.30 · 0.70/3.00/2.25 · 0.70/3.00/2.35 | 0.35/2.50/0.90 · 0.30/2.15/0.90 · 0.30/2.00/0.80 · 0.20/2.05/0.80 · 0.30/2.00/0.75 · 0.20/1.90/0.70 |
| 6 | 0.90/4.10/3.00 · 0.90/4.00/3.00 · 0.80/3.50/2.75 · 0.90/3.65/2.60 · 0.90/3.55/2.65 · 0.80/3.40/2.60 | 0.30/2.45/0.90 · 0.30/2.30/0.95 · 0.30/2.20/0.90 · 0.30/2.30/0.80 · 0.30/2.15/0.90 · 0.30/2.15/0.90 |

**The whole page is about twice as fast in absolute terms as rf2-2rtt6.2's
publication run** — `M1` subs 3.00 – 4.55 ms here against 6.00 – 7.35 there, on
a floor of 0.70 – 1.00 against 1.50 – 1.90. That is a different session on a
differently-loaded box and it is exactly why every figure on both pages is a
*ratio*. It also means the two authors' disagreement cannot be read off the
absolute numbers: only the quotients are comparable, and the quotients are what
the tables above compare.

### The control that separates the author from the tree

**A confound has to be excluded before *second author* is the right name for
this.** Two things differ between rf2-2rtt6.17's re-runs and these six runs: the
harness, and the tree. `9df5094816` (rf2-2rtt6.13) and `98f6beee8a` (rf2-2rtt6.25)
both landed changes in `re-frame/substrate/spine.cljs` today, and although the
`:reagent-ratom` arm touches no re-frame code at all, **the Reagent adapter does
require the spine** — so a change there could in principle move the *numerator*
of the leg and produce exactly the shift measured above without any harness
being involved.

The control is one command: **run the first author's own instrument on this
tree, in this session.** `npm run bench:hicasso` drives `p0_reagent_app`, which
publishes its own reactive leg on the same two rows.

**Pre-registered, before it was run:**

- If the first author's instrument still reads near **1.21 – 1.27** on `M1` and
  **1.96 – 2.17** on `broad` — its published spread — then the tree did not move
  the leg and the difference above is the **harness**. *Second author* is the
  right name, and the disagreement stands as measured.
- If it reads near **1.33** and **2.50** instead, the difference is the **tree or
  the session**, not the author, the two implementations agree after all, and
  what has actually been found is that headline 1's published magnitudes are
  stale at HEAD. That would be a larger finding than the one this bead went
  looking for, and it would belong to rf2-2rtt6.2's page rather than to this one.

**The control has not been run at the moment this paragraph is committed.**
It is written here first so that neither branch of it can be chosen after the
numbers are in.

### Provenance

**Whole-tree anchor: `3a250838a2e3045209ecfc69402bba95ab51de8a`** — `origin/main`,
plus the two instrument files this bead changed. That is stated as a sum rather
than as a single SHA because it is the truth: the arm had not landed when it was
measured, and pretending a branch SHA is a tree anchor is how a placeholder
becomes a falsehood.

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs` | `a5177d3f0bf764917075d2a247af0ddc4684a719` — **changed by this bead** |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` | `82977240c0fcf983b286918105140f40f2a1dbc7` — **changed by this bead** |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_views.cljs` | `bf79bf304d62f679be5fca69dd7880360a1a0631` — unchanged; the ratom components are the first author's, by construction |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_app.cljs` | `cd8e7c7f3313c08e6b670a3d5acf406a04a7a2e1` — unchanged; the first author's instrument, used as the control above |
| `implementation/freehand/test/re_frame/bench/hicasso/lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` — unchanged |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_uix_views.cljs` | `34e0e89d532f2af3b3289525509cf033bb03bc05` — unchanged |
| `implementation/freehand/test/re_frame/bench/hicasso/lane_cache.cjs` | `dd85cc8bd9133b659718e1f54f286f7314420ffd` — unchanged |
| `implementation/core/test/re_frame/bench/order_guard.cljc` | `6c4097afff5afa6d64903c3be2f2f4fd6f145050` — unchanged |
| `implementation/core/src/re_frame/substrate/spine.cljs` | `8d20218fd18282265cce1f931d019ca1f4d88b41` — **carries both of today's spine fixes**, which is the tree the control above exists to price |

```bash
git merge-base --is-ancestor 3a250838a2 origin/main && echo tree-anchor-on-main
P=implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs
git rev-parse <candidate>:$P   # must print a5177d3f0bf764917075d2a247af0ddc4684a719
```

| | |
|---|---|
| **Authoring commit** | `bcf80f1979170d4d16d6e7679051de0018d410c5` on `worker/ratom2-2rtt6-21` — *the converged witness gets a reagent-ratom arm*. **A rebase-merge will mint a new landed SHA**; the blobs above are what identify the instrument, and a rebase cannot move a blob. The commit is also where the five predictions are written down, before the first sample existed |
| **Reproduction** | `HICASSO_RATOM=on HICASSO_ONLY=M1,broad HICASSO_START=reagent node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — and the same with `HICASSO_START=uix`. **Six invocations, exit 0 on all six**, launched one at a time, none re-run and none discarded |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), `:advanced`, `goog.DEBUG false`, Windows 11 x64, sibling agents live on the box |
| **Build** | `:hicasso-bench`, one build id, cold — `lane_cache.cjs` clears `.shadow-cljs/builds/hicasso-bench` before every invocation. `implementation/shadow-cljs.edn` untouched |
| **Schedule** | **6 rounds** × 2 segments × (8 warm-up + 12 samples), one row per page, start counterbalanced 3/3. **Four arms in the Reagent segment** — floor, `reagent-subs`, `reagent-ratom`, `ctl-2x` — and three in the UIx segment |
| **Budget** | **840 mounts** on the `M1` page, **882 writes** on the `broad` page, per run |
| **Arm-order guard** | **no refusal on any of the 12 row-runs** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10, every one |
| **Canonical-DOM parity** | `{:problems [] :ok? true}` on all 12 — in both segments and across the seam. The ratom arm is inside the equality, not exempt from it |
| **Verification** | **0 unverified of 10,332** — per run 840 `M1` mounts + 882 `broad` writes = 1,722, times six |
| **Segment-order control** | **no refusal on any row of any run**; `magnitude-resolved? true` on **12 of 12**, `:balanced-design? true` on all 12 — for the leg as well as for the red-zone, because the leg is held to the same rule |
| **Positive controls** | 2 rows × 2 segments × 6 runs = **24, and 24 passes**, all 24 also inside the *strict* every-round-inside band [1.4992 – 2.4986] |

**The cross-segment red-zone from these six runs is not a threshold and is not
published as one.** With four arms in the Reagent segment against three in the
UIx segment the page is not the page the [RED-ZONE
table](#red-zone--clock-on-rf2-2rtt62s-witnesses) was measured on, and every
record these runs wrote carries `:ratom-arm? true` and says so. For the record
and for nothing else, the six runs read `M1` 0.9939 – 1.1184 and `broad`
0.5808 – 0.6768, which straddle and sit below the published `1.2310` and
`0.6291` respectively — a difference this design has no standing to interpret.

### Open items from this section

- **The components are shared, so the corroboration is of the harness.**
  `m1-cell-ratom` and `m1-ratom` are the same functions on both pages. An error
  inside them would be invisible to both authors, and a third witness written
  against different components — a `use-syncExternalStore` cursor, or a
  hand-built Reagent reaction — is what would close that. It is not closed here
  and is not claimed to be.
- **The two harnesses disagree and neither is adjudicated the winner.** What is
  established is that the magnitude moves by 10% – 24% between two internally
  clean instruments measuring the same witness with the same components. Which
  number a standard should carry is the operator's, and rf2-2rtt6.1 is
  size-locked; nothing here amends it.
- **Six runs, one machine, one session.** The same limit the ten-run ensemble
  carries. Between-session spread is unmeasured and is the wider of the two.

## What the convergence changed

Three of rf2-2rtt6.4's four clock verdicts do not survive the move onto
rf2-2rtt6.2's witnesses. That is the answer to *how much did the mismatch
matter*, and it is not small.

| question | rf2-2rtt6.4, on its own witnesses | converged, on rf2-2rtt6.2's | change |
|---|---|---|---|
| large-list mount | W1: **1.057×** [0.907 – 1.156] — indistinguishable | M1: **1.2310×** [1.199 – 1.293 across ten runs] — UIx slower | **verdict flips**: an indistinguishable row becomes a resolved one |
| ordinary-form mount | W3: **0.893×** [0.843 – 0.956] — UIx faster, disjoint, published as a threshold | M2: **1.0601×** [0.956 – 1.192] — indistinguishable, graded diagnostic | **verdict flips**, and so does the grade |
| broad commit | U-broad: **0.838×** [0.760 – 0.953] — UIx faster | bulk broad: **0.6291×** [0.579 – 0.699] — UIx faster | same direction, **much larger margin** |
| narrow commit | U-narrow: **1.536×** [1.226 – 1.876] — UIx slower, disjoint | bulk narrow: **1.1754×** [1.139 – 1.219] — UIx slower | same direction and both resolve, but **the margin over parity falls to about a third** — 0.18 against 0.54 |

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

p50 milliseconds, split at the microtask boundary, min–max over the ten-run
ensemble. This is the same decomposition rf2-2rtt6.4 published on its narrow row,
reproduced on the converged witness. **The narrow legs are per timed window,
which is ten commits** — divide by ten for a per-commit figure, and the earlier
one-commit table read `0.40` on exactly the legs that now read 4.2–6.35:

| row / arm | write | microtask gap | forced drain |
|---|---|---|---|
| broad / `reagent-subs` | 0.0 | 0.0 | **1.8 – 2.6** |
| broad / `uix-subs` | **0.3 – 0.5** | **0.7 – 1.1** | 0.0 |
| narrow / `reagent-subs` *(10 commits)* | 0.0 – 0.1 | 0.0 | **4.2 – 6.15** |
| narrow / `uix-subs` *(10 commits)* | **4.6 – 6.35** | 0.4 – 0.7 | 0.0 |
| broad / `floor` | 0.0 | 0.0 | 0.2 – 0.3 |
| narrow / `floor` *(10 commits)* | 0.0 | 0.0 | 1.7 – 2.6 |

The two substrates put their cost in different legs and the split is total. On
Reagent everything is the drain — `reagent.core/flush`, the reaction walk. On
UIx nothing is the drain: the cost is in the **write leg and the microtask that
follows it**, before React is involved at all — the `useSyncExternalStore`
notification fanning out across 300 subscribed boundaries. On the narrow row the
UIx write leg *alone* (4.6 – 6.35 ms) is the whole of Reagent's window
(4.2 – 6.15 ms) for the same operation, which is the structural claim a native
view layer has on that row, and it is a claim about the React-hook spine's
invalidation rather than about UIx's rendering.

## The positive controls

Predicted from the element count, written down before the run, published every
run whether they pass or not. Slack 25%, and it is generous on purpose: the
claim a clock control certifies is *the instrument has signal*, not *the model
is exact*. (The ±0.001% standard this wave set belongs to the **heap** control,
where the predicted quantity is a known retained byte count; no clock control
can honestly be held to it.)

**The live count is the ensemble's: 4 rows × 2 segments × 10 runs = 80
controls, and 80 passes** under the overlap rule `lane/control-verdict`
implements. Each cell is the mean of the ten run means, with the widest bound any
of the ten reached:

| row | predicted | Reagent segment | UIx segment | basis |
|---|---|---|---|---|
| M1 mount | 1.9989× | 1.918× [1.714 – 2.167] ✅ | 1.917× [1.688 – 2.167] ✅ | 1801 / 901 elements |
| M2 mount | 1.9412× | 1.783× [1.167 – 2.500] ✅ | 1.778× [1.333 – 2.333] ✅ | 99 / 51 elements |
| bulk broad | 1.9989× | 1.905× [1.500 – 2.500] ✅ | 2.013× [1.333 – 2.500] ✅ | 1801 / 901 elements |
| **bulk narrow** | 1.9989× | 1.979× [1.754 – 2.294] ✅ | 1.965× [1.796 – 2.194] ✅ | 1801 / 901 elements |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.9989×~~ | ~~2.013× [1.667 – 2.400] ✅~~ | ~~1.867× [1.667 – 2.000] ✅~~ | ~~1801 / 901 elements~~ |

**Sixty-seven of the eighty sit below their prediction** — the direction a fixed
per-root term predicts, and the same direction rf2-2rtt6.2 and rf2-2rtt6.4 both
recorded. The thirteen that sit above are concentrated on the two rows whose
control legs are coarsest: seven on bulk broad, four on narrow, one each on M1
and M2. **The earlier summary said the direction was *unanimous*, which was true
of ten controls and is not true of eighty.** Ten measurements were never enough
to establish a unanimity; eighty are enough to say the tendency is real and not
universal, which is the more useful statement anyway.

### The strict reading, over eighty controls (rf2-egdaq)

`lane/control-verdict` adjudicates a control by **overlap** with the ±25% band;
**rf2-egdaq** holds open the question of whether to require **every round**
inside it. The ensemble is the first sample large enough to say what that choice
would cost, so it is stated here as an observation. **The ruling is the
operator's and nothing below decides it.**

| row | band | strict passes | worst single round |
|---|---|---|---|
| M1 mount | [1.4992 – 2.4986] | **20 of 20** | 1.6875 — inside |
| M2 mount | [1.4559 – 2.4265] | **13 of 20** — Reagent 6/10, UIx 7/10 | **1.1667**, 19.9% below the floor (Reagent segment); **1.3333**, 8.4% below (UIx segment) |
| bulk broad | [1.4992 – 2.4986] | **11 of 20** — Reagent 7/10, UIx 4/10 | **1.3333**, 11.1% below the floor |
| bulk narrow | [1.4992 – 2.4986] | **20 of 20** | 1.7544 — inside |

**64 of 80 pass strict; 2 of the 10 runs pass strict on all eight of their
controls.** The failures are not spread evenly — they land entirely on the two
rows whose control leg is a handful of 100 µs quanta, and every one of them is a
*low* outlier of exactly the shape a quantised floor produces. The rows measured
on 20-plus quanta legs, M1 and narrow, pass strict 40 times out of 40.

Two things follow, and only the first is a finding. **A strict rule would refuse
the M2 row in 6 runs of 10 and the broad row in 6 of 10, on a page where every
other gate is green** — the guard clean on all forty row-runs, parity clean,
zero unverified of 92,160. And the number rf2-egdaq holds on reproduces exactly:
its worst M2 round, `1.3333` against a floor of `1.4559`, is **8.4% below**, and
this ensemble's worst M2 UIx round is the same `1.3333` — the same lattice
point, not a coincidence but the arithmetic of a ratio built from two-to-five
quantum readings. What the ensemble adds is that the Reagent segment reaches
`1.1667`, **19.9% below the floor**, which is more than twice the excursion the
held ruling was weighing.

## The guard refused this arm three times, and the arm was repaired each time

The first cut ran all four rows in one page. **The arm-order guard refused it,
exit 2**, on two independent faults. Both were the arm's; the tolerance was not
touched. The third refusal came much later, when the sixth round was added, and
it has its own section below.

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

### 3. The sixth round broke the M2 row, and the lever was the mount budget

**This one changes how an M2 row on this page is read, so it is written up in
full.** Adding the sixth round moved the M2 page from 600 measured mounts to
720, and the guard refused **four of the first six six-round invocations** —
every refusal on M2's *phase* factor, every one a **last-third-slower** disjoint
split of 2.0000× / 2.2857× / 2.5000×, on readings of one to three of Chrome's
100 µs quanta. No other row refused, in any of them.

**The first diagnosis was wrong and the guard said so.** The obvious reading of
a phase split is a cold page — the knee — and the guard's own repair list begins
with *more warm-up*. That repair was taken first: 24 discarded mounts a
segment-round instead of 8. It made things **worse**, and unambiguously:
1,296 mounts a page, refused **three of three**, with the split widened to
2.6×–4.0×.

The direction was the tell. A cold page reads **first**-third-slower; every one
of these refusals was **last**-third-slower. The page is not warming, it is
**degrading as it runs** — the per-page accumulation rf2-2rtt6.4 recorded and
could not explain, which rf2-flqpd has since tied to collector debt (a scavenge
inside a timed window measures the collector, and the floor it measured drifted
3.4 → 7.0 ms under 30–80 MB of uncollected rubbish). Deeper warm-up *feeds* that
accumulation, because a warm-up mount is a mount. The lever is the page's
**total mount budget**, and the dose-response was measured rather than argued:

| M2 page budget | how it arises | verdict |
|---|---|---|
| **600 mounts** | 5 rounds × 2 segments × 3 arms × (8 + 12) | clean — every five-round run on this page |
| **720 mounts** | 6 rounds, same sampling | **refused 4 of 6**, splits 2.0×–2.5× |
| **1,296 mounts** | 6 rounds, warm-up deepened to 24 | **refused 3 of 3**, splits 2.6×–4.0× |
| **504 mounts** | 6 rounds, sampling 4 + 10 — *the repair* | clean — **40 of 40 row-runs of the ensemble** |

**The repair is a smaller sampling budget on this witness only:** `:sampling
{:warmup 4 :samples 10}`, carried on the M2 witness map rather than the page
default, which puts a six-round M2 page at 504 mounts — under the budget every
clean run ever ran at. **The measured window is untouched** (one mount a sample,
exactly as rf2-2rtt6.2 publishes it) and **the tolerance is untouched** (0.10).
Less warm-up is safe *here* precisely because the failure is the tail rather
than the knee: a cold early sample pulls the first third **up**, against the
climb, so it costs sensitivity in the direction that is not failing. M1 keeps
the page default — its readings are 30–50 quanta, where the same absolute drift
is comfortably inside tolerance.

**What this means for reading an M2 row.** The M2 row of this ensemble is
sampled **10 deep off a 4-mount warm-up**, where the other three rows are 12 deep
off 8 and where every five-round M2 row on this page was also 12 off 8.
Comparing an M2 figure across the two publications compares two sampling depths
as well as two designs. It is still the same witness, the same window and the
same tolerance — but the row now carries an instrument parameter the others do
not, and the reason is that this witness is the only one whose readings are
small enough for the page's own decay to show up as a refusal.

**Six invocations were run at the pre-repair instrument and none of them is
published.** They diagnosed a defect and were spent doing it; the counterbalanced
ensemble restarted from scratch at the repaired instrument, which is why the
ensemble is ten runs and not sixteen.

## Method

- **Both orders, and an even number of rounds.** Arms rotate *and reflect* on
  the sample index; segment order alternates with the round, over **six** rounds
  so the alternation splits 3:3 and the raw mean is design-unbiased. Three arms a
  segment, never two, because at two the rotation and the reflection cancel
  (rf2-ouwh8) — asserted at boot in both directions, and the run refuses to
  measure if the assertion fails.
- **A counterbalanced start, across independently launched runs.** Which segment
  leads round 0 is a per-run parameter (`?start=reagent` / `?start=uix`,
  `HICASSO_START`). Inside one run, *Reagent led* and *the even rounds* are the
  same set of rounds however many rounds there are, so an order effect and a
  temporal drift are the same partition and no single run can separate them. Half
  the runs start each way, and the inference is made **across** runs.
- **Position before adjacency.** Every sample carries its position in the whole
  page and the guard partitions on first-third against last-third as well as on
  predecessor. Warm-up matters more than interleaving, and warm-up samples are
  discarded but still count as predecessors.
- **Ranges, never a mean alone.** Overlapping ranges mean indistinguishable and
  the page says so.
- **Every measured mount and every measured write is read back out of the DOM
  inside its own window**, and the count is published as `N unverified of M` —
  and **`unverified > 0` fails the run**, which it did not until rf2-a4x1o.
  Mounts are checked against a *written* element count at **the arm's own
  scale** (901/51 for the measured arms, **1,801/99 for the control**, probed at
  **its own** far end), so the gate answers false both for an arm that rendered
  nothing and for a 2× control that rendered only its base prefix.
- **A positive control with predicted vs measured, every run, per segment.**
- **A segment-order verdict on every cross-segment figure.** The red-zone's
  rounds are partitioned by which segment ran first; opposite directions refuse
  the row, and a magnitude may be published only when the two strata overlap.
- **Canonical-DOM parity before any clock**, inside each segment and across the
  seam — the two substrate arms are compared with each other directly, not
  merely each with its own floor.

## The segment-order warrant

Bead **rf2-a4x1o**, reopened by the PR #7268 audit: *"the operative 1.2301
threshold magnitude does not yet have its stated fail-closed warrant."*
`lane/guard!` adjudicates arms **inside** a segment; the red-zone is a ratio
**across the seam**, and `:segment-seam-control` above only *records* the floor's
drift. Nothing asked whether the threshold itself moved with which segment ran
first — even though `segment-order` alternates, so every round is already
labelled with the answer.

It now does. `segment-order-verdict` partitions every cross-segment figure by
segment order and adjudicates two claims separately, because they fail
separately:

| claim | rule | consequence |
|---|---|---|
| **direction** — *UIx slower / faster / indistinguishable* | **FAIL-CLOSED.** Two strata pointing opposite ways across 1.0 sets `HICASSO_ORDER_REFUSED`; `p0_converge_run.cjs` exits **1** | the row has no direction to publish, and the figure is a measurement of the schedule |
| **magnitude** — the single number a candidate is judged against | reportable **only** when the two strata **overlap**. `:magnitude-resolved?` starts false and the overlap has to earn it | a disjoint split publishes `:claim :direction-only`; silence is not a pass |

### The partition, and what one run's split can and cannot say

The strata are keyed by **which segment actually led the round**, not by round
parity, so they stay meaningful when the start flips. Under the balanced design
each stratum holds three rounds. The pre-registered run of the ensemble reads:

| row | Reagent-first (3 rounds) | UIx-first (3 rounds) | strata | mean |
|---|---|---|---|---|
| M1 mount | 1.2782 [1.1378 – 1.4286] | 1.1536 [0.9529 – 1.2892] | overlap | 1.2159 |
| M2 mount | 1.0027 [0.8571 – 1.0794] | 1.0912 [0.8889 – 1.3846] | overlap | 1.0469 |
| bulk broad | 0.6940 [0.6144 – 0.8485] | 0.6384 [0.5532 – 0.7292] | overlap | 0.6662 |
| bulk narrow | 1.1381 [1.0568 – 1.2575] | 1.1703 [1.0346 – 1.3602] | overlap | 1.1542 |

Across the whole ensemble the strata **overlap in 37 of 40 row-runs**: M1, M2 and
narrow each go disjoint once, broad never. The three disjoint splits fall in two
runs, and no row is disjoint twice.

**A disjoint split is a statement about resolution, not a finding.** Under the
null — no order effect, the six rounds exchangeable — the three-round stratum is
one of `C(6,3) = 20` equally likely subsets and exactly two of them separate the
strata, so **a disjoint partition arises 10% of the time per row with no order
effect at all**. Three in forty is 7.5%. That is what chance looks like, and it
is the reason a disjoint split withdraws the magnitude rather than condemning
the measurement. (At five rounds the same arithmetic gave 2 of `C(5,2) = 10` —
**20%**, twice as often — which is half of why the round count is now even.)

The five-round partition that broke `1.2301` is preserved in
`p0_converge_order_cljs_test.cljs`, which replays those exact vectors with the
start stated rather than defaulted.

### The segment-order question, asked properly

**The claim under test.** Three five-round runs of this page were read as saying
that the cross-segment figure comes out **higher when the Reagent segment ran
first**, in 11 of 12 row-runs, one-sided binomial **p = 0.0032**, and the page
called that *systematic* and *real*. **That warrant was wrong, and it was wrong
in two separable ways.**

**First, the twelve were not twelve.** Four rows measured inside one browser
process share a machine, a heap and a collector; they are four correlated
readings of one run, not four Bernoulli trials. The honest unit is the **run**,
which made the sample n = 3, and no arrangement of three observations reaches
p = 0.0032.

**Second, the design could not tell an order effect from a clock.** Every one of
those runs started with Reagent, so *Reagent led* and *rounds 0, 2, 4* named the
same set of rounds in every run. A page that simply gets slower as it runs — and
[this one does](#3-the-sixth-round-broke-the-m2-row-and-the-lever-was-the-mount-budget)
— produces exactly the same partition. Adding same-start runs cannot separate
them, because each new run reproduces the same confound.

**The repair is the design, not the arithmetic.** Ten runs, launched
independently, six rounds each, **five starting with Reagent and five with UIx**.
The statistic is one number per run per row,

> `d = ln( mean of the Reagent-first stratum ÷ mean of the UIx-first stratum )`,

positive when the figure reads higher Reagent-first, which is the direction the
original claim asserted. Counterbalancing then splits `d` into two components
that a fixed start had welded together. In a Reagent-start run the Reagent-first
rounds are the even ones; in a UIx-start run they are the odd ones. So

- **the average of the two start groups isolates the ORDER effect** — the
  temporal term enters with opposite signs and cancels;
- **half their difference isolates the TEMPORAL term** — the order effect enters
  with the same sign and cancels.

Two views follow, and the page publishes both because they answer different
questions and are powered differently.

#### View 1 — between runs, one observation per run

The assumption-free test: take the run's published threshold mean, compare the
five Reagent-start runs against the five UIx-start runs, and use an **exact
permutation test** over all `C(10,5) = 252` relabellings. Nothing about the
inside of a run is assumed.

| row | Reagent-start mean | UIx-start mean | difference | exact two-sided *p* | resolution limit |
|---|---|---|---|---|---|
| M1 mount | 1.2276 | 1.2344 | −0.0068 | 0.770 | ±0.043 |
| M2 mount | 1.0461 | 1.0740 | −0.0280 | 0.611 | ±0.122 |
| bulk broad | 0.6295 | 0.6288 | +0.0007 | 0.984 | ±0.063 |
| bulk narrow | 1.1754 | 1.1755 | −0.0001 | 0.992 | ±0.037 |

The **resolution limit** is the half-width of the 95% interval on the difference
— what a start-group effect would have had to exceed for five against five to
see it. It is printed because a null result without one is not a result.

**Which segment starts a run does not detectably move the number that run
publishes.** On the narrow row the two groups agree to one part in ten thousand.
That is the practically important result: **the four rows above may be quoted
without asking which segment led**, which was not true of the five-round design.

#### View 2 — within run, the paired strata

The higher-powered test, and the direct successor to the discredited 11-of-12.
Because a six-round run now carries **both** strata by construction, `d` is a
*paired* contrast: it differences two numbers measured minutes apart in the same
process, so run-to-run variance cancels instead of being absorbed. Ten runs, one
`d` per run per row, an exact **sign-flip randomisation test** over all
`2¹⁰ = 1024` sign assignments, one-sided in the direction the original claim
named.

| row | order effect (mean `d`) | as a ratio | 95% interval | sign-flip *p* | positive |
|---|---|---|---|---|---|
| M1 mount | +0.0357 | 1.036× | 0.979× – 1.097× | 0.084 | 7 of 10 |
| M2 mount | −0.0837 | 0.920× | 0.795× – 1.065× | 0.889 | 4 of 10 |
| bulk broad | −0.0388 | 0.962× | 0.887× – 1.044× | 0.856 | 5 of 10 |
| bulk narrow | +0.0070 | 1.007× | 0.977× – 1.038× | 0.302 | 7 of 10 |
| **run-level composite** *(the pre-registered statistic: mean over the four rows)* | **−0.0200** | **0.980×** | **0.934× – 1.029×** | **0.823** | **6 of 10** |

**The pre-registered hypothesis fails, and it fails on the wrong side of zero.**
The composite was fixed in advance as one-sided positive; the ensemble puts it at
**−0.0200**, a 2% effect in the *opposite* direction, with 6 of 10 runs positive.
Two rows lean the claimed way and two lean against it. Nothing here is
significant at any conventional level, and the largest of them, M1's +3.6% at
p = 0.084, is one row of four and would not survive being asked for four times.

And the temporal component the confound hid is no better resolved:

| row | order component | temporal component | exact *p* on the start-group difference |
|---|---|---|---|
| M1 mount | +0.0357 | −0.0212 | 0.421 |
| M2 mount | −0.0837 | +0.0632 | 0.318 |
| bulk broad | −0.0388 | −0.0011 | 1.000 |
| bulk narrow | +0.0070 | −0.0063 | 0.698 |
| composite | −0.0200 | +0.0086 | 0.810 |

**And the discredited statistic itself does not survive.** Counted the way the
page counted it — 40 row-runs treated as if independent, which they are not —
the Reagent-first stratum is higher in **23 of 40**, p = 0.21. The 11-of-12 was
not a small effect measured well; on the balanced design it is not there at the
strength it was quoted.

#### Why the two views can disagree, and what n = 5 vs 5 cannot settle

They did not disagree here — both say *not resolved* — but they can, and a reader
should know which to believe about what.

View 1 is between runs and therefore **cannot see a pure order effect at all**
once the design is balanced: a six-round run averages both orders equally, so an
order effect is inside the run mean, not between the start groups. What View 1
tests is whether the *start* biases the published number, and its answer is no.
View 2 is paired, so it can see an order effect at a fraction of the size, but
its unit is a within-run difference and its four rows are correlated — which is
precisely the error the original 11-of-12 made. The rows are therefore reported
separately and pooled only through the pre-registered composite, never as four
trials.

**What n = 5 versus n = 5 cannot do is prove an absence, and this page does not
claim one.** The start-group contrast can only resolve a difference of about
0.04 on the narrow and M1 rows and about 0.12 on M2 — the last column of View 1's
table is that limit, computed rather than asserted. On the composite, the
interval admits an order effect anywhere from **6.6% against the claim to 2.9%
for it**. Stated as bounds rather than as a verdict:

- **An effect at the top of the range the page previously asserted is excluded
  on every row.** The upper end of View 2's interval — the end that would favour
  the claim — is +9.7% on M1, +6.5% on M2, +4.4% on broad and +3.8% on narrow.
  The *"one to nineteen percent between strata, systematic"* the page used to
  assert cannot be true of the top of its own range on any row.
- **An effect of one or two percent is not excluded, and ten runs could not have
  detected one.** It would also not matter: it is smaller than the gap between
  two consecutive runs of the same design.

The right conclusion is the modest one. **The segment-order effect is not
established, and the design no longer needs it to be.** With an even round count
the raw mean is design-unbiased whatever the truth is, and with a counterbalanced
start the published figures are demonstrably insensitive to the schedule. The
per-row fail-closed rule stays exactly as it is — it costs nothing when the
strata overlap, and it is the thing that would catch an order effect if one
appeared on a future witness.

### The verdict on M1's magnitude

**The three measured reasons `1.2301` was withdrawn have all been answered**,
and each was answered by the design rather than by a friendlier run:

1. ~~*It is biased by a design the effect exploits* — 3:2 Reagent-first on a
   quantity that reads high Reagent-first.~~ **Gone by construction.** At six
   rounds the split is 3:3 and the raw mean *is* the order-balanced mean; the two
   differ on 7 of 40 row-runs of the ensemble and only ever in the fourth
   decimal, where they are rounded independently.
2. ~~*Its two strata do not meet* — [1.2388 – 1.3538] against
   [1.1099 – 1.1417].~~ **The strata overlap in 9 of the 10 runs**, and the
   instrument marks the row `:claim :magnitude` in those nine.
3. ~~*Four independent estimates do not agree to better than ±6%, and the newest
   does not resolve at all.*~~ **Ten independent estimates now span
   1.1989 – 1.2931**, a spread of ±3.8% about their mean, none of them
   straddling 1.0, and 59 of the 60 rounds behind them read above 1.0.

**What is published is a magnitude with an interval: 1.2310×, 95% interval
1.2105 – 1.2514 on the mean, single runs landing anywhere in 1.199 – 1.293.**
That is the form the measurement supports. A bare four-decimal point never was —
not because the point was wrong (`1.2301` sits inside the new interval, which is
its own small vindication) but because a point carries no statement of how far a
fresh run may legitimately land from it.

**rf2-2rtt6.1's hold is not lifted here, and cannot be.** That bead is the
operator's, `1.2301` is its number, and this page supersedes that number rather
than rehabilitating it. What a worker may say is what the measurement now
supports, and that is [written out below](#what-would-have-been-appended-to-rf2-2rtt61-had-it-not-been-size-locked)
in the form the standard would take.

**The other three rows.** `0.6291×` **broad** is the strongest row on the page:
all 60 rounds below 1.0, all 20 order strata wholly below it, strata overlapping
in every run. `1.1754×` **narrow** is now its mirror: all 60 rounds above 1.0,
all 20 strata wholly above. **M2** resolves nothing and is not meant to — every
one of its ten runs straddles 1.0, and its interval `[1.0017 – 1.1185]` sits
barely clear of parity only because it is an interval on a *mean*; three of the
ten runs read below 1.0 outright. On legs of 4.5 to 11 quanta that is not a
direction, and the row stays **diagnostic**.

### The corrected instrument's own run

Four rows, five rounds, both segments, at this branch's instrument.
`node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` —
**exit 0**.

| row | the five-round publication *(superseded)* | corrected instrument | overlap | verdict |
|---|---|---|---|---|
| **M1 mount** | 1.2301 [1.1099 – 1.3538] disjoint | **1.1397 [0.9130 – 1.3201] — straddles 1.0** | 1.1099 – 1.3201 | **does NOT reproduce as resolved** — see below |
| M2 mount *(diagnostic)* | 1.0539 [0.8572 – 1.4286] straddles | 0.9217 [0.8572 – 1.0000] straddles | 0.8572 – 1.0000 | **reproduces**, indistinguishable both |
| **bulk broad** | 0.6239 [0.4701 – 0.7857] disjoint | 0.5870 [0.4762 – 0.8372] disjoint | wholly overlapping | **reproduces**, UIx faster both |
| **bulk narrow** | 1.1540 [1.0570 – 1.2053] disjoint | 1.2018 [1.1344 – 1.3395] disjoint | 1.1344 – 1.2053 | **reproduces**, UIx slower both |

**The M1 non-reproduction is reported, not explained away, and the comparison is
reflected.** This run's instrument differs from the published one in one way
that touches the measured page: the `:ctl-2x` control's element count is now
checked on **every** mount, which is a `querySelectorAll` over an 1,801-element
container between samples. It is **outside every timed window**, it falls on the
control arm in **both** segments identically, and the red-zone divides
`uix-subs` by `reagent-subs` — neither of which is the control — so it cannot
bias the ratio directionally. The arm-order guard agrees: `refuse? false`,
`contaminated? false`, `unchecked? false` on all four rows, with no arm reading
differently for its position.

**The ten-run ensemble now bounds how much of this was noise, and it does not
account for all of it.** Ten runs of the balanced design span 1.1989 – 1.2931 on
M1; this run's `1.1397` sits *below* that spread. The ten were launched within
about eight minutes of one another on one machine, so their interval measures
**within-session** run-to-run variation; the corrected run is a different session
on a different instrument, and it reads lower than any of them. **A candidate
should therefore be judged against the run-mean spread rather than the interval
on the mean, and a figure taken in a different session may legitimately land
outside even that.** The interval this page publishes is not a claim about
next week's machine.

### The corrected run's provenance

**The landed SHA is `9737ed5cf815817d856c49eefb6824856df51668`.** This block said
*"the landed SHA follows the merge"* for as long as the merge had not happened,
and then went on saying it afterwards, which is how a placeholder becomes a
falsehood. The merge has happened; the SHA is on `origin/main`; and because a
rebase can move a SHA but cannot move a blob, the instrument is pinned by content
below as well.

| file | blob at `9737ed5cf8` |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs` | `7d62bb6f4c4ce90a930bef94060ecc89772f42f1` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` | `f9c8c36ca58ccf2986f9946aee62a4666297924e` |
| `implementation/freehand/test/re_frame/bench/hicasso/lane.cljs` | `73b382cbfc17acf767e744313e60ec33c35fe6e5` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_views.cljs` | `4032e39779ce55fee1e1cd4f7a8e9561237e2cfd` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_uix_views.cljs` | `34e0e89d532f2af3b3289525509cf033bb03bc05` |
| `implementation/core/test/re_frame/bench/order_guard.cljc` | `6c4097afff5afa6d64903c3be2f2f4fd6f145050` |

```bash
git merge-base --is-ancestor 9737ed5cf815817d856c49eefb6824856df51668 origin/main && echo on-main
P=implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs
git rev-parse 9737ed5cf8:$P   # 7d62bb6f4c4ce90a930bef94060ecc89772f42f1
```

| | |
|---|---|
| **Producing commit** | `9737ed5cf815817d856c49eefb6824856df51668` — *bench(hicasso): warrant the segment-order threshold, verify the doubled control (rf2-a4x1o)*, on `origin/main`. Measured on `worker/control-verify` before the merge; the rebase rewrote the id and left every blob above untouched |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — **exit 0** |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, 24 logical CPUs |
| **Arm-order guard** | **no refusal on any of the four rows** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10 |
| **Canonical-DOM parity** | clean in both segments and across the seam, every row — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 7,860** — 600 M1 mounts + 600 M2 mounts + 630 broad writes + 6,030 narrow writes, and `unverified > 0` now **fails the run** |
| **Segment-order control** | **no refusal on any row**; `magnitude-resolved? true` on all four of this run's rows, and the published M1 and narrow rounds are the ones it marks unresolved |
| **Positive controls** | eight, eight passes — and, for the first time, **adjudicated against the doubled page they claim**: 1,801 elements with a probe at index 599, 99 with a probe at field 23 |

### The balanced ensemble's provenance (rf2-6i0i2)

**Ten runs, launched one at a time, none of them re-run and none of them
discarded.** Five started with the Reagent segment and five with UIx, in
alternation; every one exited 0 and every one is in the numbers above. A run that
had been dropped for reading badly would make the ensemble worthless, so the
count is stated as the whole record: **ten launched, ten published.**

| | |
|---|---|
| **Authored anchor** | `2a97274c0fd50dd3145ba60a33f73f663bec94b9` on `worker/balance-6i0i2` — the last commit that touches the instrument. **A rebase-merge will mint a new landed SHA for it**, and this line is deliberately not rewritten to that SHA later: the blobs below are what identify the instrument, and a rebase cannot move a blob. An audit mapping this page to `main` should expect the anchor to resolve to a commit that is *not* an ancestor of `main`, and should check the blobs |
| **Reproduction** | `HICASSO_START=reagent node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — and the same with `HICASSO_START=uix`. **Ten invocations, exit 0 on all ten** |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), `:advanced`, `goog.DEBUG false`, Windows 11 x64, 24 logical CPUs, sibling agents live on the box |
| **Schedule** | **6 rounds** × 2 segments × 3 arms interleaved, **one row per page**, start counterbalanced 5/5 across the ten launches. Sampling 8 warm-up + 12 samples on M1, broad and narrow; **4 + 10 on M2** ([why](#3-the-sixth-round-broke-the-m2-row-and-the-lever-was-the-mount-budget)) |
| **Arm-order guard** | **no refusal on any of the 40 row-runs** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10, every one |
| **Canonical-DOM parity** | `{:problems [] :ok? true}` on all 40 — in both segments and across the seam |
| **Verification** | **0 unverified of 92,160** — per run 720 M1 mounts + 504 M2 mounts + 756 broad writes + 7,236 narrow writes = 9,216, times ten |
| **Segment-order control** | **no refusal on any row of any run**; `magnitude-resolved? true` on **37 of 40**, and `:balanced-design? true` on all 40 |
| **Positive controls** | **80, and 80 passes** under `lane/control-verdict`'s overlap rule; 64 of 80 under the strict every-round-inside reading — [the breakdown](#the-strict-reading-over-eighty-controls-rf2-egdaq) |

The instrument, by content hash. These are the blobs every one of the ten runs
was measured at:

| file | blob |
|---|---|
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs` | `5a727706fcd3268027b1d1b640658ca0be7ab86f` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` | `9542c1167435e06727a28bb8af89c055bb4e4682` |
| `implementation/freehand/test/re_frame/bench/hicasso/lane.cljs` | `0642815dc234c1544d1f97bd9e1e4dd24365c027` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_reagent_views.cljs` | `bf79bf304d62f679be5fca69dd7880360a1a0631` |
| `implementation/freehand/test/re_frame/bench/hicasso/p0_uix_views.cljs` | `34e0e89d532f2af3b3289525509cf033bb03bc05` |
| `implementation/core/test/re_frame/bench/order_guard.cljc` | `6c4097afff5afa6d64903c3be2f2f4fd6f145050` |

```bash
P=implementation/freehand/test/re_frame/bench/hicasso/p0_converge_app.cljs
git rev-parse <candidate>:$P   # must print 5a727706fcd3268027b1d1b640658ca0be7ab86f
```

**What this ensemble does not establish.** All ten ran on one machine inside one
eight-minute window, so it bounds run-to-run variation *within a session* and
says nothing about a different machine, a different Chromium or a different day —
and the corrected run's `1.1397` on M1, from another session, is the standing
demonstration that between-session spread is wider. Ten runs also cannot prove an
absence: see [what n = 5 versus n = 5 cannot
settle](#why-the-two-views-can-disagree-and-what-n--5-vs-5-cannot-settle).

### What would have been appended to rf2-2rtt6.1, had it not been size-locked

**rf2-2rtt6.1 is size-locked (rf2-0znkn, the operator's) and cannot accept an
append**, so this is what a worker would have added to the governance record's
P0 table, verbatim. **Only the operator may amend the standard**; this is a
statement of what the measurement supports, not an amendment.

> **CLOCK RED-ZONES — amendment under RULING 1, restated on the balanced
> design.** RULING 1 makes the red-zone threshold *the measured UIx ratio for
> that witness family*. Ten independently launched six-round runs with the
> starting segment counterbalanced 5/5 now stand behind each row, so each is a
> **magnitude with an interval** rather than a point or a bare direction:
>
> - **M1 mount — `1.2310×`, 95% interval `1.2105 – 1.2514` on the mean, single
>   runs landing in `1.199 – 1.293`.** This **supersedes `1.2301×`**, which was
>   withdrawn as a magnitude and is not reinstated: the number the standard holds
>   was measured on a 3:2 design whose bias has since been removed by
>   construction, and it is replaced rather than restored. A candidate worse than
>   the run-mean spread is RED; inside it, the honest answer is where in the
>   spread it sits.
> - **bulk narrow — `1.1754×`, interval `1.1579 – 1.1930`, runs `1.139 – 1.219`.**
>   **Supersedes `1.1540×`**, also withdrawn. Its direction is now as strong as
>   any row on the page: all 60 rounds above 1.0, all 20 order strata wholly
>   above.
> - **bulk broad — `0.6291×`, interval `0.5996 – 0.6587`, runs `0.579 – 0.699`.**
>   Supersedes `0.6239×`, which stood. All 60 rounds and all 20 strata below 1.0.
> - **M2 mount — `1.0601×` and still diagnostic and still indistinguishable.**
>   All ten runs straddle 1.0 and three read below it outright. Not quotable
>   against the bar, exactly as before.
> - **The segment-order finding this table previously cited is withdrawn.** The
>   *11 of 12 row-runs, p = 0.0032* was four correlated rows inside each of three
>   runs, not twelve trials, and its design could not separate segment order from
>   time. On the counterbalanced ensemble the effect is not established on any
>   row, and the same statistic counted the old way reads 23 of 40.
> - **The heap red-zones are untouched.** Nothing here measures heap.

### The control on this page was never checked either

The same defect rf2-2rtt6.2 carried. `mount-round!` read
`expected (if (:parity-exempt? arm) nil elements)`; `verify-m1` probed indices 0
and 299 and `verify-m2` the shared 12-field prefix, both of which exist in the
doubled page; and `parity-of-segment!` compared counts over `lane/parity`'s map,
which excludes parity-exempt arms by construction. **Every red-zone on this page
rests on a control whose 2× claim nothing tested.** It is now held to its own
arithmetic — 1,801 / 99 elements, far probe at index 599 / field 23 — and the
mutation evidence is on
[the denominator page](p0-reagent-on-subs-baseline.md#mutation-proved-a-base-prefix-control-now-fails-three-ways).

## Open items — stated, not swept up

- ~~**The narrow row is clamp-limited and does not stably resolve.**~~
  **CLOSED by rf2-zb3qg.** Ten writes now share one clock; both independent runs
  resolve, and **the guard did not refuse either of them** (tolerance 0.10,
  `contaminated? false`, `unchecked? false`). rf2-2rtt6.2's recorded refusal was
  from batching *mounts* on the 51-element form, and it was checked rather than
  assumed: the worst phase stratum across all six arms of the batched narrow row
  reads 1.038×–1.089× last-third over first-third, ranges overlapping. The
  tolerance was not touched.
- **The 51-element form row cannot be lifted the same way here.** rf2-2rtt6.2
  tried batching mounts on this exact witness and the guard refused the whole
  run (exit 2, all four arms 3.2×–5.4× last-third over first-third, ranges
  disjoint). This entry does not re-litigate that; the row stays diagnostic.
- **rf2-2rtt6.4's page and tree were not on main when this was measured** (PR
  #7265 was still open; it has since merged, and this entry was rebased onto it
  rather than re-measured — both records keep their own producing SHAs). Its
  clock rows remain sound as ratios and are superseded as *thresholds* by the
  table above; [its page](p0-uix-on-subs-frontier-arm.md) is marked accordingly
  rather than rewritten. Its **heap** rows are not superseded by anything here —
  this entry measures no heap, and retained bytes per boundary is a property of
  the boundary rather than of the page.
- **The per-page accumulation is still unexplained — but it now has a measured
  dose-response and a named lever.** rf2-6i0i2's six-round M2 refusals put
  numbers on it: 600 mounts a page clean, 720 refused 4 of 6, 1,296 refused 3 of
  3, 504 clean over 40 row-runs, every refusal *last*-third-slower. So the page
  degrades in proportion to the mounts it has run, and the budget is the control
  knob. rf2-flqpd has tied the same shape to collector debt on the retention
  page. What is still missing is the mechanism on *this* page and a fix that does
  not cost samples; one row per page plus a per-witness budget makes it harmless
  to these figures, and neither makes it fixed.
- **The segment-order effect is not established, and one machine-session is
  what stands behind that.** rf2-6i0i2's ten counterbalanced runs exclude an
  effect of the size this page once asserted and cannot exclude one of a percent
  or two. They also all ran inside one eight-minute window on one box; a run from
  another session read below the whole ensemble's spread on M1. **Between-session
  variation is unmeasured and is the wider of the two.**
- ~~**`p0_converge_app.cljs`'s own row table still describes the pre-batch
  narrow window, and this page cannot fix it.**~~ **CLOSED by rf2-95m11
  (PR #7288).** The sibling worker finished, and the correction was the one
  line predicted: the namespace docstring's `bulk-narrow` row now reads
  *"`narrow-batch-k` batched commits, each of which exactly ONE boundary
  reads, all in one timed window"*, matching the `:doc` on the entry itself.
  The file agrees with itself, and the summary table a reader stops at is the
  window that ran.
