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

A second, independent five-round run of the identical instrument is published
beside the **narrow** row; where the two disagree, the page says so rather than
picking one. The other three rows have one run each and a reproduction check,
which is a weaker warrant and is labelled as one wherever it appears.

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
| **M1 mount** — 901 el, 300 boundaries | ~~**1.2301×**~~ **MAGNITUDE WITHDRAWN** — direction only, ≈1.11 – 1.35 | 1.1099 – 1.3538 | 1.3065 · 1.1417 · 1.2388 · 1.1099 · 1.3538 | **UIx slower** here and in 2 of the 3 other runs; **see [the segment-order warrant](#the-segment-order-warrant--and-why-12301-does-not-have-one)** |
| **M2 mount** — 51 el, 12 fields · *diagnostic* | 1.0539× | 0.8572 – 1.4286 | 1.4286 · 0.8572 · 0.8572 · 1.0550 · 1.0714 | **straddles 1.0 — indistinguishable** |
| **bulk broad** — one commit all 300 read | **0.6239×** | 0.4701 – 0.7857 | 0.7172 · 0.6046 · 0.5417 · 0.7857 · 0.4701 | **UIx faster**, disjoint from 1.0 |
| **bulk narrow** — 10 commits, each read by exactly one boundary, one window | ~~**1.1540×**~~ **MAGNITUDE WITHDRAWN** — direction only; balanced 1.1457 | 1.0570 – 1.2053 | 1.2053 · 1.1515 · 1.1860 · 1.0570 · 1.1700 | **UIx slower**, disjoint from 1.0 in every run — *but its order strata are disjoint too; see [the segment-order warrant](#the-segment-order-warrant--and-why-12301-does-not-have-one)* |
| ~~bulk narrow, **unbatched window** (superseded)~~ | ~~1.1556×~~ | ~~1.0417 – 1.2500~~ | ~~1.1111 · 1.2500 · 1.2500 · 1.0417 · 1.1250~~ | ~~UIx slower, but did not stably resolve across runs~~ |

> **Two magnitudes are withdrawn and no number is deleted.** Every figure above
> is exactly as measured; what changed is what may be **quoted** from it, and it
> is now the instrument that decides ([the segment-order
> warrant](#the-segment-order-warrant--and-why-12301-does-not-have-one)).
> The two rows whose order strata OVERLAP keep their magnitudes, with the
> order-balanced value beside the raw one: **M2 1.0539 → 1.0376** (diagnostic
> either way), **bulk broad 0.6239 → 0.6357**.

Both arms against the floor, for context:

| witness | `reagent-subs ÷ floor` | `uix-subs ÷ floor` |
|---|---|---|
| M1 mount | 4.352× [4.063 – 4.625] | 5.343× [4.947 – 5.944] |
| M2 mount | 2.102× [1.625 – 2.800] | 2.261× [1.714 – 4.000] |
| bulk broad | 7.443× [7.000 – 8.000] | 4.607× [3.667 – 5.500] |
| bulk narrow *(batched window)* | 1.880× [1.8167 – 1.9259] | 2.168× [2.0357 – 2.2500] |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.820× [1.500 – 2.000]~~ | ~~2.117× [1.667 – 2.500]~~ |

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

**This is a reproduction check, not a re-publication. No figure on this page
moves.** Ranges are min–max across the five rounds; overlap with the published
range and an unchanged verdict is the test.

| row | published | reproduction sweep | overlap | verdict |
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
| **Positive controls** | **this sweep's** four rows × two segments = eight, **eight passes** under the overlap rule; see below for the strict reading. Not the page's live count — the [positive-control table](#the-positive-controls) carries ten entries, because the narrow row is published from two runs and the superseded one is struck |

**The M2 control that rf2-egdaq holds open reads differently on this sweep,
and the ruling is still the operator's.** The published M2 control range
`[1.333 – 2.000]` sits against a ±25% band whose floor is `1.4559`: it passes
the overlap rule `lane/control-verdict` implements and fails a strict
every-round-inside reading. **This sweep's M2 controls are `[1.500 – 2.000]`
(Reagent segment) and `[1.600 – 2.000]` (UIx segment) — both wholly inside the
band, so both pass under *either* reading.** That is reported, not used: a
re-run producing a friendlier control is not an argument for a rule, and
rf2-egdaq is not settled here. The broad row's controls on this sweep, `[1.600
– 2.500]` and `[1.667 – 2.500]`, miss the strict reading by `0.0014` — the
lattice point above a `2.4986` ceiling on a floor of two to three quanta, the
same quantisation artefact the denominator page records.

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
| bulk narrow *(batched window)* | **1.1540** [1.0570 – 1.2053] | **1.1714** [1.0962 – 1.2316] | yes — same verdict, both disjoint from 1.0 |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.1556 [1.0417 – 1.2500]~~ | ~~1.1972 [1.1111 – 1.2500]~~ | ~~yes~~ |

The two estimators agree on the verdict of every row, which is the evidence that
the floor normalisation is doing its job rather than injecting the quantisation
it is exposed to.

### Stability across runs

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

| row | floor | `reagent-subs` | `uix-subs` | usable? |
|---|---|---|---|---|
| M1 mount | 8 – 10 | 33 – 46 | 39 – 54 | yes |
| M2 mount | 2 – 4 | 6 – 7 | 6 – 8 | **diagnostic only** |
| bulk broad | 2.5 – 4 | 20 – 29 | 11 – 13 | yes on the substrate legs; the floor is coarse, which is why the raw estimator is published beside the normalised one |
| **bulk narrow** — 10 commits a sample | 21 – 30 | 47.5 – 55.5 | 57 – 64.5 | **yes** |
| ~~bulk narrow — 1 commit a sample (superseded)~~ | ~~2 – 3~~ | ~~4 – 4.5~~ | ~~4.5 – 5~~ | ~~**clamp-limited**~~ |

## What the convergence changed

Three of rf2-2rtt6.4's four clock verdicts do not survive the move onto
rf2-2rtt6.2's witnesses. That is the answer to *how much did the mismatch
matter*, and it is not small.

| question | rf2-2rtt6.4, on its own witnesses | converged, on rf2-2rtt6.2's | change |
|---|---|---|---|
| large-list mount | W1: **1.057×** [0.907 – 1.156] — indistinguishable | M1: **1.2301×** [1.110 – 1.354] — UIx slower, disjoint | **verdict flips**: an indistinguishable row becomes a resolved one |
| ordinary-form mount | W3: **0.893×** [0.843 – 0.956] — UIx faster, disjoint, published as a threshold | M2: **1.0539×** [0.857 – 1.429] — indistinguishable, graded diagnostic | **verdict flips**, and so does the grade |
| broad commit | U-broad: **0.838×** [0.760 – 0.953] — UIx faster | bulk broad: **0.6239×** [0.470 – 0.786] — UIx faster | same direction, **much larger margin** |
| narrow commit | U-narrow: **1.536×** [1.226 – 1.876] — UIx slower, disjoint | bulk narrow: **1.1540×** [1.0570 – 1.2053] — UIx slower, disjoint | same direction and both resolve, but **the margin roughly halves** |

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
| **bulk narrow** *(batched, run 1)* | 1.9989× | 1.976× [1.917 – 2.038] ✅ | 1.949× [1.821 – 2.000] ✅ | 1801 / 901 elements |
| **bulk narrow** *(batched, run 2)* | 1.9989× | 1.941× [1.905 – 1.977] ✅ | 1.935× [1.814 – 2.000] ✅ | 1801 / 901 elements |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.9989×~~ | ~~2.013× [1.667 – 2.400] ✅~~ | ~~1.867× [1.667 – 2.000] ✅~~ | ~~1801 / 901 elements~~ |

**Ten controls, ten passes, and all ten sit below their prediction** — the
direction a fixed per-root term predicts, and the same direction rf2-2rtt6.2
and rf2-2rtt6.4 both recorded.

**This summary read *"Eight controls, eight passes, and seven of the eight sit
below"*, and the table it described no longer exists.** It was right when the
table held M1, M2, broad and the unbatched narrow row — four entries across two
segments, of which exactly one, the narrow Reagent segment's `2.013×`, sat above
its prediction. The batched re-take struck that row and added two batched runs
in its place, so the live table is **five entries × two segments = ten**, the
one entry that sat above prediction is the superseded one, and the direction is
now unanimous. Counting the struck row would be counting a figure this page has
withdrawn.

The narrow row's controls were **predicted before the run** at `1801 / 901 =
1.9989×` and re-measured on the batched window; all four ranges above sit
entirely inside the ±25% band `[1.4992 – 2.4986]`. They therefore pass under the
**strict** every-round-inside reading as well as the overlap rule they were
adjudicated under — which matters because `lane/control-verdict` implements the
overlap rule and **rf2-egdaq** is the open question of whether to tighten it.
Nothing here tightens it; the narrow row simply would not be affected either way.

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

## The segment-order warrant — and why `1.2301` does not have one

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

### The partition, confirmed

The audit's M1 reading reproduces **exactly** from the published per-round
vector. Rounds 0, 2, 4 are Reagent-first; rounds 1, 3 are UIx-first.

| row | Reagent-first (3 rounds) | UIx-first (2 rounds) | strata | raw mean | **order-balanced** |
|---|---|---|---|---|---|
| **M1 mount** | **1.2997** [1.2388 – 1.3538] | **1.1258** [1.1099 – 1.1417] | **DISJOINT** | 1.2301 | **1.2128** |
| M2 mount | 1.1191 [0.8572 – 1.4286] | 0.9561 [0.8572 – 1.0550] | overlap | 1.0539 | 1.0376 |
| bulk broad | 0.5763 [0.4701 – 0.7172] | 0.6951 [0.6046 – 0.7857] | overlap | 0.6239 | 0.6357 |
| **bulk narrow** *(batched — the published row)* | **1.1871** [1.1700 – 1.2053] | **1.1042** [1.0570 – 1.1515] | **DISJOINT** | 1.1540 | 1.1457 |
| ~~bulk narrow *(unbatched, superseded)*~~ | ~~1.1620 [1.1111 – 1.2500]~~ | ~~1.1459 [1.0417 – 1.2500]~~ | ~~overlap~~ | ~~1.1556~~ | ~~1.1539~~ |

**One correction to the audit's reading, stated because it changes a row's
status.** The audit reported M2, broad *and* narrow as overlapping. Two of the
three reproduce; **the current batched narrow row's strata are disjoint too**
([1.1700 – 1.2053] against [1.0570 – 1.1515]). Only the **superseded unbatched**
narrow row overlaps. So the exposure was never bounded to M1: `1.1540` is in the
same position as `1.2301`. This partition is pinned in
`p0_converge_order_cljs_test.cljs`, which replays these exact vectors.

### Disjointness is not the evidence — the SIGN is

**A disjoint 3:2 split is weak evidence on its own.** Under the null — no order
effect, the five rounds exchangeable — the 2-round stratum is one of
`C(5,2) = 10` equally likely subsets and exactly two of them separate the
strata. **A disjoint partition therefore arises 20% of the time per row with no
order effect at all**, and *which* row splits is not stable: M1 and narrow split
in the published run, **broad** splits in the rf2-rjfz1 sweep, and **no row
splits in both**. Three disjoint rows out of eight row-runs is what chance looks
like.

**The sign is a different matter, and it is decisive.** Across **three
independent five-round runs** — the published run, the rf2-rjfz1 reproduction
sweep, and the corrected-instrument run below — the cross-segment figure reads
**higher when the Reagent segment ran first in 11 of 12 row-runs**. One-sided
binomial, **p = 0.0032**.

| run | M1 | M2 | broad | narrow |
|---|---|---|---|---|
| published | R 1.2997 > U 1.1258 | R 1.1191 > U 0.9561 | R 0.5763 **<** U 0.6951 | R 1.1871 > U 1.1042 |
| rf2-rjfz1 sweep | R 1.3253 > U 1.2338 | R 1.0909 > U 0.9455 | R 0.6426 > U 0.5410 | R 1.1790 > U 1.1549 |
| corrected instrument | R 1.2183 > U 1.0217 | R 0.9345 > U 0.9025 | R 0.6139 > U 0.5466 | R 1.2236 > U 1.1692 |

**There is a systematic segment-order effect.** It is small — one to nineteen
percent between strata — and it is real. The per-row disjointness test could not
see it because it has no power at 3:2; the sign test across rows and runs can.

**Two consequences, and the first is arithmetic.** Five rounds cannot balance
two orders: the split is 3:2, so the raw mean **over-weights whichever order got
the extra round**, and with a real effect present that bias has a sign. Every
published threshold on this page is a 3:2 **Reagent-first-heavy** mean of a
quantity that reads high Reagent-first, so **every one of them is biased upward
by roughly one to two percent.** The design-unbiased estimator under an
alternating schedule is the mean of the two stratum means, and it is now
published on every row as `:order-balanced-mean`. An **even** round count would
make the two coincide by construction; it is named as the arm-level repair
rather than taken here, because it would move four published rows without
deciding the question.

### The verdict on `1.2301` — the hold stands

**rf2-2rtt6.1's hold — *do not use 1.2301 as a precise red-zone threshold* — is
NOT lifted.** Three measured reasons, in order of weight:

1. **It is biased by a design the effect exploits.** 3:2 Reagent-first on a
   quantity that reads high Reagent-first. The order-balanced estimate on the
   published rounds is **1.2128**, not 1.2301.
2. **Its two strata do not meet.** [1.2388 – 1.3538] against [1.1099 – 1.1417].
   A mean over a split whose halves are disjoint is not a threshold, and
   `:magnitude-resolved?` is false for that row.
3. **Four independent estimates do not agree to better than ±6%, and the newest
   does not resolve at all:** 1.2301, 1.2103 (same-instrument stability run),
   1.2887 (rf2-rjfz1 sweep), and **1.1397 [0.9130 – 1.3201] — straddling 1.0 —**
   on the corrected instrument below. A figure quoted to four decimal places on
   that spread was never a threshold.

**What IS warranted, and it is not nothing.** UIx-on-subs is slower than
Reagent-on-subs on the M1 mount witness in **three of four runs**, and every
Reagent-first stratum of every run is disjoint above 1.0. The defensible
statement is a **direction with a range — *UIx slower on M1 mount, roughly
1.11× to 1.35× across the runs that resolve, with one run of four
indistinguishable*** — not a point. **A candidate row is red on M1 if it is
worse than that range; inside it, the answer is `not resolved` and not a pass.**

**`0.6239×` broad is in better shape, and its magnitude carries the same 1–2%
caveat.** Its **direction survives everywhere**: all six order strata across the
three runs sit wholly below 1.0, and all four run means (0.6239 / 0.5682 /
0.6020 / 0.5870) are disjoint from 1.0. Its strata overlap in the published run
and in the corrected run, and are disjoint in the sweep — so it passes the test
that broke M1's magnitude twice out of three, and its order-balanced published
value is **0.6357**. The narrow row is the same shape as M1: direction resolved
in every run, magnitude disjoint on the published rounds, balanced value
**1.1457**.

### The corrected instrument's own run

Four rows, five rounds, both segments, at this branch's instrument.
`node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` —
**exit 0**.

| row | published | corrected instrument | overlap | verdict |
|---|---|---|---|---|
| **M1 mount** | 1.2301 [1.1099 – 1.3538] disjoint | **1.1397 [0.9130 – 1.3201] — straddles 1.0** | 1.1099 – 1.3201 | **does NOT reproduce as resolved** — see above |
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
differently for its position. The reading that fits is the one the four
estimates already showed: **M1's red-zone is a noisy quantity that was published
as a precise one.**

**No published figure on this page is deleted or rewritten.** The rows above
stand as measured; what changes is what may be **quoted** from them, and that is
now decided by the instrument (`:claim :magnitude` vs `:claim :direction-only`)
rather than by whoever writes the page.

### The corrected run's provenance

| | |
|---|---|
| **Branch** | `worker/control-verify` (rf2-2rtt6.2 / rf2-a4x1o) — the landed SHA follows the merge |
| **Reproduction** | `node implementation/freehand/test/re_frame/bench/hicasso/p0_converge_run.cjs` — **exit 0** |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows 11 x64, 24 logical CPUs |
| **Arm-order guard** | **no refusal on any of the four rows** — `refuse? false`, `contaminated? false`, `unchecked? false`, tolerance 0.10 |
| **Canonical-DOM parity** | clean in both segments and across the seam, every row — `{:problems [] :ok? true}` |
| **Verification** | **0 unverified of 7,860** — 600 M1 mounts + 600 M2 mounts + 630 broad writes + 6,030 narrow writes, and `unverified > 0` now **fails the run** |
| **Segment-order control** | **no refusal on any row**; `magnitude-resolved? true` on all four of this run's rows, and the published M1 and narrow rounds are the ones it marks unresolved |
| **Positive controls** | eight, eight passes — and, for the first time, **adjudicated against the doubled page they claim**: 1,801 elements with a probe at index 599, 99 with a probe at field 23 |

### What would have been appended to rf2-2rtt6.1, had it not been size-locked

**rf2-2rtt6.1 is size-locked (rf2-0znkn, the operator's) and cannot accept an
append**, so this is what a worker would have added to the governance record's
P0 table, verbatim. **Only the operator may amend the standard**; this is a
statement of what the measurement supports, not an amendment.

> **CLOCK RED-ZONES — amendment under RULING 1.** RULING 1 makes the red-zone
> threshold *the measured UIx ratio for that witness family*. On two of the four
> converged rows that ratio is **not resolved to a magnitude**, so the rule needs
> its consequence stated rather than a number substituted:
>
> - **M1 mount — the magnitude `1.2301×` is withdrawn.** Its two segment-order
>   strata are disjoint, the 3:2 design biases it upward on a quantity now shown
>   to carry a systematic order effect (11 of 12 row-runs, p = 0.0032), and four
>   independent estimates read 1.2301 / 1.2103 / 1.2887 / 1.1397 with the last
>   straddling 1.0. The red-zone this row supports is **a direction and a range:
>   UIx slower, ≈1.11 – 1.35×**. A candidate worse than 1.35× is RED; inside the
>   range the answer is **not resolved**, which under the standard is still not a
>   pass.
> - **bulk narrow — the magnitude `1.1540×` is withdrawn** on the same test;
>   direction (UIx slower) resolves in every run. Order-balanced value 1.1457.
> - **bulk broad — `0.6239×` stands as a threshold**, with the order-balanced
>   value **0.6357** recorded beside it. Direction survives in all six strata of
>   all three runs.
> - **M2 mount — unchanged and still diagnostic.** Balanced value 1.0376.
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
- **The per-page accumulation is still unexplained.** One row per page makes it
  harmless to these figures; it does not make it fixed.
- ~~**`p0_converge_app.cljs`'s own row table still describes the pre-batch
  narrow window, and this page cannot fix it.**~~ **CLOSED by rf2-95m11
  (PR #7288).** The sibling worker finished, and the correction was the one
  line predicted: the namespace docstring's `bulk-narrow` row now reads
  *"`narrow-batch-k` batched commits, each of which exactly ONE boundary
  reads, all in one timed window"*, matching the `:doc` on the entry itself.
  The file agrees with itself, and the summary table a reader stops at is the
  window that ran.
