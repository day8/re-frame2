# P0 — UIx on re-frame2 subs: the frontier arm, and the red-zones

**These figures are the red-zone thresholds.** The delegated ruling recorded on
the operator-owned standard bead **rf2-2rtt6.1** (mayor, 2026-07-30, on Mike's
explicit *"accept your recommendation and proceed"*) says it mechanically:

> RED-ZONE THRESHOLD = THE MEASURED UIx RATIO FOR THAT WITNESS FAMILY, on both
> axes (clock, retained heap). A candidate row WORSE THAN UIx on either axis is
> RED. A red row needs an explicit operator waiver naming the dogfood benefit.
> Silence is still not a pass.

So every number in the two "red-zone" tables below was set as a threshold, not
a supporting figure — and both tables have since been qualified, each by the
banner below. **Red is not fail** — the ship bar (HD-012: mount AND bulk
≤ 1.0× Reagent, like-for-like, clock only) is independent, and a row can clear
the bar and still be red. That tension is intended.

Bead **rf2-2rtt6.4**. Rows are appended to rf2-2rtt6.1; only the operator amends
the bar, the budgets, the kill criteria, or these thresholds.

> **Superseded on the clock axis only — rf2-a4x1o, PR #7268.** The clock
> thresholds in the first red-zone table below are superseded *as thresholds* by
> [the converged witness set](p0-converged-witness-set.md), which re-derived them
> on the bar denominator's own witnesses and moved three of the four verdicts.
> They remain sound *as ratios* — each is still two arms measured against each
> other in one run, on one page, through one sub graph. But the bar's denominator
> is Reagent-on-subs, so the denominator's witness set defines the comparison by
> construction, and a threshold measured on a different witness is a threshold on
> a different question. This page's own *Open items* anticipated the move; quote
> the converged table.
>
> **The retained-heap table is regime-qualified — the heap red-zone regime
> ruling (delegated by Mike, 2026-07-31; authoritative text on rf2-2rtt6.16,
> transcription on rf2-2rtt6.1).** Cache cardinality is part of the witness:
> this arm's four held roots render the *same* 300 query vectors against one
> frame, so one subscription cache serves all 1,200 boundaries and the
> per-boundary figures amortise the subscription half of every read across four
> consumers. The absolute rows below therefore stand as **fan-out 4 (amortised
> ×4), shared-query witness evidence — not comparable to distinct-query rows** —
> and the operative upper-envelope red-zone family is the
> [heap ladder](reads-per-boundary-heap-ladder.md)'s distinct-query rows
> (Q = E). The ratios stay published as the cross-regime check: the 8% agreement
> across shapes — this page's list at 2.262×, its grid at 2.254×, the ladder's
> 2.435× — is *ratio* agreement, because the amortisation is common-mode across
> both arms and cancels in the ratio. Portability of the ratio is real, and it
> is **approximate**: the [fan-out sweep](heap-fan-out-sweep.md) measured the
> ratio drifting **9.3%** across E/Q 1 → 8 in one run on one instrument, so a
> cross-regime check quoted to three decimals is quoting drift. Portability of
> the quantity is not real at all — the absolute moves 1.6–1.9× over the same
> range.

## Provenance

**The instrument is identified by content hash, not by commit SHA** — a blob
survives the rebase and the merge that rewrite a SHA, and this page's rows are
the **retained-heap red-zone thresholds**, which are operative and not
housekeeping.

| file (`implementation/core/test/re_frame/bench/`) | blob at `bc5f38f10a` **and** `1415d9f7af` | on main `32cb224d6e` |
|---|---|---|
| `p0_run.cjs` | `e2d6eb6c722ad142123c34b99f9292328ddb11be` | `eec62f5a727e…` — **moved** |
| `p0_heap.cljs` | `28caa45a31e4cb27b3305b1abf63d97dcc2c1a5e` | `d4dcb3f6e4a4…` — **moved** |
| `p0_app.cljs` | `047ff2c0fdb1faaa4554778873e5f7f715befaf9` | `095731f2880c…` — **moved** |
| `p0_harness.cljs` | `f33cb6449743c9e7e830c373683f40038d6bd132` | `74672756f712…` — **moved** |
| `order_guard.cljc` | `adf59ca03cfe8e2639de97c031c138838f2d34b7` | `e42450ef1c77…` — **moved** (#7267) |
| `p0_arms.cljs` | `89d26f8b9853d4fd06abfbdd99e6c7ac8a4cc060` | **unchanged** |
| `p0_uix.cljs` | `45654d01eaefed42e61d4520ed48be0cebb08057` | **unchanged** |
| `p0_reagent.cljs` | `f13d7e9cf0e59c498eae3358a14d239cf5338b35` | **unchanged** |
| `p0_fixture.cljc` | `f5f615972f29c5c1418c0a788ffc8d84945d865a` | **unchanged** |
| `p0_floor.cljs` | `66016daefb3c5dcf7e9601bc7a7ea6e8f68b8678` | **unchanged** |

**Five of the ten instrument blobs have moved since these rows were taken, and
this page says so rather than leaving a reader to discover it.** The three arm
definitions and both fixtures are byte-identical on main; the driver, the heap
probe, the app shell, the harness and the guard are not. The heap rows are
**not** re-run on that account: under the heap-regime ruling (rf2-2rtt6.16)
they stand as shared-query fan-out evidence rather than as the operative
red-zone family, and the sweep that prices the regimes against each other is
rf2-5prok's, not a re-take of this arm. The cross-regime check — this page's
list at 2.262×, its grid at 2.254×, and [the heap
ladder](reads-per-boundary-heap-ladder.md)'s 2.435× — agrees within 8% because
the amortisation cancels in the ratio. **The ladder was itself
re-run under the repaired guard and every threshold moved by less than 0.2%**,
which is the best available evidence that #7267's guard change is inert. A
reader who wants the stronger check has the blob table above and the command
below.

```bash
P=implementation/core/test/re_frame/bench/p0_heap.cljs
git rev-parse 1415d9f7af:$P   # 28caa45a31e4cb27b3305b1abf63d97dcc2c1a5e
git merge-base --is-ancestor 1415d9f7af origin/main && echo on-main
```

| | |
|---|---|
| **Producing commit** | `bc5f38f10a` — **off main.** Its landed equivalent is **`1415d9f7af`**, whose instrument tree is **byte-identical** (every blob in the table above matches); this page landed as **`462bcbffd4`**. The rebase rewrote the ids and moved no instrument byte |
| **Reproduction (clock)** | `P0_ROUNDS=5 P0_SAMPLES=10 P0_WARMUPS=4 node implementation/core/test/re_frame/bench/p0_run.cjs --only clock` |
| **Reproduction (heap)** | `P0_ROUNDS=5 P0_ROOTS=4 node implementation/core/test/re_frame/bench/p0_run.cjs --only heap` |
| **Runtime** | HeadlessChrome **147.0.7727.15** (Chromium via Playwright), Windows x64 |
| **Build** | `:advanced`, `goog.DEBUG false`, plain `:browser` module. Compiler settings donated by the `:freehand-release` build id via `--config-merge`; **no `implementation/shadow-cljs.edn` change** (the epic's sequencing law gives that file to rf2-2rtt6.2) |
| **React** | 19.2.0 · **Reagent** 2.0.1 · **UIx** `com.pitch/uix.core` 1.4.4 |
| **Adapters** | `:rf.adapter/reagent` and `:rf.adapter/uix`, one per segment |
| **Schedule** | 5 rounds × (4 warm-up + 10 samples) per arm per round; arms interleaved at the sample level, order chosen by measured adjacency; one round per page |
| **Arm-order guard** | **reportable on every row**, clock and heap. Self-test 8/8 before anything was measured; driver exit 0 |
| **Canonical-DOM parity** | clean under `:advanced` in both segments and across the seam, all 5 rounds — `{:problems [] :ok? true}` |
| **Verification (clock)** | **0 unverified of 6,600** — 200 mounts W1 + 1,600 mounts W3 + 800 broad writes + 4,000 narrow writes, every one read back out of the DOM inside its own window |
| **Verification (heap)** | **0 unverified of 48 mounts** |

All bar numbers here are browser numbers. No JVM or Node figure appears on this
page.

## The arms

Every figure is a ratio, and every ratio is to the floor measured in the **same
round and the same segment**.

| arm | what it is | role |
|---|---|---|
| `:floor` | the same DOM hand-built with `react/createElement` — no substrate, no boundary, no subscription | the **calibrator**, and the thing that makes the cross-segment ratio legitimate |
| `:reagent-subs` | `reg-view` boundaries reading `@(subscribe [:p0/… i])` against one frame, `rf/frame-provider` at the root, `reagent.dom.client` for the mount | **the denominator** |
| `:uix-subs` | `defui` boundaries reading `(use-subscribe [:p0/… i])` — the existing spine — `frame-provider` at the root, `uix.dom` for the mount | **the frontier** |

Both substrate arms read **re-frame2 subscriptions**, both write through
`dispatch-sync` on a shared event, and both run against one frame. Neither is
hand-optimised: no `set-hiccup-emitter!` on the UIx side (that would measure a
hiccup interpreter, not UIx), no `use-memo` around subscription args, no prop
threading in place of a read.

### Two segments, and why

`install-adapter!` is once per process (Spec 006 §Single adapter per process), so
the two candidate arms **cannot be interleaved inside one round**. Each round
therefore runs two segments — destroy the adapter, install the other, re-seed —
with the **floor in both**. The floor holds no re-frame state and is untouched by
which adapter is installed, so a UIx-over-Reagent figure is a ratio of two
floor-normalised ratios, and the seam cancels provided the segment drift is
common-mode.

That cancellation is relied upon heavily and is **published rather than assumed**.
The floor's own UIx-segment p50 over its Reagent-segment p50, per round:

| witness | seam control, per round |
|---|---|
| W1-list | 1.762 · 0.938 · 1.406 · 0.860 · 1.486 |
| W3-form | 1.531 · 0.552 · 1.317 · 0.627 · 1.250 |
| U-broad | 1.038 · 0.846 · 0.821 · 0.800 · 0.688 |
| U-narrow | 0.861 · 0.896 · 0.670 · 0.738 · 0.646 |

The seam moves by up to 1.8×, and the red-zone ratios derived through it move by
far less (W3-form: 0.843–0.956 across the same rounds) — which is the evidence
that the normalisation is doing its job rather than an argument that it should.
**A single-segment absolute millisecond from this page is not a quotable figure.**

## The witnesses

| id | shape | elements | sub read per boundary |
|---|---|---|---|
| **W1-list** | a large template: 300 rows, multi-class sugar, style map, `data-*` passthrough, image + label + number | 1,203 | `[:p0/row i]` |
| **W3-form** | the ordinary 12-field form: label, controlled input, error line | 51 | `[:p0/field i]` |
| **U-broad** | 300 independently-subscribed cells; one commit every cell reads | 301 | `[:p0/cell i]` |
| **U-narrow** | the same grid; one commit exactly ONE cell reads | 301 | `[:p0/cell i]` |

One read per boundary — the first rung of the HD-002 1/3/7/20 ladder. Sub keys are
`(query-id, long)` under value equality. A map would be equally safe — a freshly
allocated but `=`-equal map is one cache key; only value-unstable args thrash the
index — but a bare long is value-stable by construction, so key churn cannot enter
what this arm measures.

## RED-ZONE — clock

**Superseded as thresholds** by [the converged witness set](p0-converged-witness-set.md);
retained here as the ratios this arm measured, on this arm's witnesses.

**UIx-on-subs ÷ Reagent-on-subs**, both floor-normalised in the same round.
Ranges are min–max across the five rounds. A range that includes 1.0 means the
two are **indistinguishable** and is reported as such rather than as a winner.

| witness | **threshold (mean)** | range | per round | verdict |
|---|---|---|---|---|
| **W1-list** mount | **1.057×** | 0.907 – 1.156 | 0.907 · 1.156 · 1.071 · 1.008 · 1.141 | **straddles 1.0 — indistinguishable** |
| **W3-form** mount | **0.893×** | 0.843 – 0.956 | 0.843 · 0.956 · 0.852 · 0.914 · 0.899 | UIx faster, disjoint from 1.0 |
| **U-broad** bulk | **0.838×** | 0.760 – 0.953 | 0.760 · 0.857 · 0.832 · 0.786 · 0.953 | UIx faster, disjoint from 1.0 |
| **U-narrow** bulk | **1.536×** | 1.226 – 1.876 | 1.361 · 1.226 · 1.876 · 1.588 · 1.630 | **UIx slower**, disjoint from 1.0 |

Both arms against the floor, for context:

| witness | `reagent-subs ÷ floor` | `uix-subs ÷ floor` |
|---|---|---|
| W1-list | 2.350× [2.077 – 2.651] | 2.472× [2.196 – 2.710] |
| W3-form | 1.948× [1.857 – 2.028] | 1.738× [1.633 – 1.827] |
| U-broad | 8.064× [7.250 – 8.769] | 6.729× [6.100 – 7.318] |
| U-narrow | 4.277× [3.875 – 4.731] | 6.502× [5.800 – 7.271] |

### The floor-free counterpart

Every row in the red-zone table divides one floor-normalised ratio by another,
so the two segments' floors both enter it: `(U/F_U) ÷ (R/F_R)` is
`(U/R) × (F_R/F_U)`, and that second term is the seam control published above.
Both tables are per round, so the floor-free estimator — `uix-subs` p50 over
`reagent-subs` p50, touching neither floor — is their exact product. The
per-round p50s themselves did not survive this arm, so the column below is
recovered from the two rounded tables rather than re-measured; read two
decimals, not three.

| witness | floor-normalised | floor-free | floor-free per round | verdict |
|---|---|---|---|---|
| **W1-list** mount | 1.057× | **1.350×** [0.867 – 1.696] | 1.598 · 1.084 · 1.506 · 0.867 · 1.696 | straddles 1.0 in both |
| **W3-form** mount | 0.893× | **0.928×** [0.528 – 1.291] | 1.291 · 0.528 · 1.122 · 0.573 · 1.124 | floor-free straddles; the normalised row does not |
| **U-broad** bulk | 0.838× | **0.696×** [0.629 – 0.789] | 0.789 · 0.725 · 0.683 · 0.629 · 0.656 | UIx faster in both |
| **U-narrow** bulk | 1.536× | **1.150×** [1.053 – 1.257] | 1.172 · 1.098 · 1.257 · 1.172 · 1.053 | UIx slower in both |

The two estimators keep three verdicts of four and disagree on the means by up
to 28% — nothing like the 0.06 – 5.6% agreement [the converged witness
set](p0-converged-witness-set.md) reports, and the reason is visible in the
seam: that page's seam straddles unity on every row, and this one's row means
run 0.762 – 1.290. The floor-free column is not thereby the better number. Its
per-round values alternate with segment order, which is the confound the
normalisation exists to remove. What it does establish is that `F_R/F_U` is
bounded here by this page's own control rather than unknown, and these four
rows are superseded as thresholds, so nothing gates on the gap.

### Reading the narrow row

U-narrow is the localisation row — one commit that exactly one of 300 subscribed
cells reads — and it is the one place UIx-on-subs is materially behind. The
window decomposition says where the time goes: on the Reagent arm the cost is in
the drain (`r/flush` inside the flush), while on the UIx arm it is in the
**write** leg, before React is involved at all — the `useSyncExternalStore`
notification path fanning out across 300 subscribed boundaries. That is a
statement about the React-hook spine's invalidation, not about UIx's rendering,
and it is the row a native view layer has the clearest structural claim on.

## Retained heap — shared-query fan-out evidence, and the cross-regime check

**Regime-qualified, not operative as absolutes** — the heap red-zone regime
ruling (rf2-2rtt6.16; transcription on rf2-2rtt6.1). The operative
upper-envelope red-zone family is the
[heap ladder](reads-per-boundary-heap-ladder.md)'s distinct-query rows (Q = E);
this section's ratios stay published as the cross-regime check, and its
absolute rows stand as **fan-out 4 (amortised ×4), shared-query witness
evidence — not comparable to distinct-query rows**. A candidate row is judged
only against donor rows measured under the same regime on the same witness.

**Witness stamp — B/E/Q.** Four held roots render the *same* 300 query vectors
against one frame, so one subscription cache serves every root: **B = 1,200**
boundaries (300 × 4 roots), **E = 1,200** boundary-query edges (one read per
boundary, E/B = 1), **Q = 300** unique live query keys — **mean fan-out
E/Q = 4**. The production cache key is the query vector itself, so four
consumers of one `(frame, query-vector)` retain one reaction plus four
attachments, and dividing the held delta by 1,200 boundaries amortises the
subscription half of every read across four of them.

Retention, never allocation: V8's sampling heap profiler drops the samples of
collected objects, and on the predecessor's instrument the same 80,000 objects
read 4.77 MB when a global held them and 0.00 MB when nothing did. Here the
driver forces a collection, reads, asks the page to mount 4 roots and keep them,
collects, reads again, releases, collects, reads a third time.

**Exclusive** = `arm − floor` in the same segment: the substrate's own standing
cost. [validation.md](../validation.md)'s budget on this axis — ~0.4–0.5 KB per
boundary target, > 1 KB fails on paper — is the **R = 0 boundary shell**, not a
boundary including its reads (heap-regime ruling, rf2-2rtt6.16, Part 3).

| family | **cross-regime check — exclusive (mean)** | range | absolute (mean) | range |
|---|---|---|---|---|
| **list** (300 sub-reading rows) | **2.262×** | 2.196 – 2.335 | 1.580× | 1.564 – 1.603 |
| **grid** (300 sub-reading cells) | **2.254×** | 2.217 – 2.288 | 1.989× | 1.968 – 2.002 |

Bytes per boundary — **fan-out 4 (amortised ×4), shared-query witness evidence,
not comparable to distinct-query rows**. CDP `Runtime.getHeapUsage` after a
forced collection, 4 roots held:

| arm | B/boundary (mean) | range |
|---|---|---|
| `reagent-subs \| list/floor` | 1,105 | 1,099 – 1,108 |
| `reagent-subs \| list/reagent` | 2,059 | 2,040 – 2,086 |
| `uix-subs \| list/floor` | 1,096 | 1,084 – 1,113 |
| `uix-subs \| list/uix` | **3,252** | 3,210 – 3,271 |
| `reagent-subs \| grid/floor` | 248 | 244 – 259 |
| `reagent-subs \| grid/reagent` | 1,195 | 1,190 – 1,208 |
| `uix-subs \| grid/floor` | 244 | 239 – 247 |
| `uix-subs \| grid/uix` | **2,378** | 2,372 – 2,388 |

**Exclusive per boundary, fan-out 4 (amortised ×4)**: Reagent-on-subs ≈
**954 B** (list) and **947 B** (grid); UIx-on-subs ≈ **2,156 B** (list) and
**2,134 B** (grid). Their distinct-query counterparts on the
[ladder](reads-per-boundary-heap-ladder.md) read ≈ **1,562 B** and ≈ **3,807 B**
at one read — the same boundaries, carrying the full subscription instead of a
quarter of it.

Against the paper budget these figures decide nothing:
[validation.md](../validation.md)'s ~0.4–0.5 KB target / > 1 KB paper-fail line
is the **R = 0 boundary shell**, which both measured arms comply with (Reagent
~418–428 B, UIx ~208 B), and a boundary *including its read* — amortised or not
— is judged on the per-read axis instead, under the ruling's regime-matched
gates: UIx **2,935 B/read** [2,852–3,055] on the P0 bench instrument, worse is
RED and needs an explicit operator waiver; Reagent **943 B/read** [935–944],
worse with no named paper path down is K3 territory; between the two is
"UIx-rule cleared, K3 open", never plain green.

**The UIx line was restated on 2026-07-31** (Mike's ruling, option (a),
`rf2-e3flf`). It read 3,552 B/read while it was sourced from the ladder's
pre-`rf2-2rtt6.13` spine; the sweep's post-landing rungs read the same quantity
directly — `R2Q2B − R1Q1`, two rungs at Q = E one read apart — and its
decomposition explains it as per-edge 1,345 B + per-unique-key 1,590 B. The
[ladder](reads-per-boundary-heap-ladder.md)'s §5 carries the arithmetic and
says plainly which of its readings are independent of each other and which are
one run stated twice. Reagent's line did not move — neither landing touches the
ratom path. `rf2-2rtt6.25` (`f784ab0adb`) is also an ancestor of the sweep's
tree, but no retained-heap delta from it is established and none is claimed
here; it is open on its own bead. **This page's own UIx heap absolutes predate
both landings** and are ~769/4 B per boundary high at E/Q = 4 on `.13`'s
account: the sweep re-ran this grid arm unchanged and read 1,996 B against the
2,134 B below.

DOM nodes live in Blink's C++ heap, not V8's, so none of these figures contain
the elements themselves. Every arm builds the identical DOM — the canonical-DOM
parity gate is what establishes that — so the omission cancels exactly in
`arm − floor`. The absolute column is a JS-heap figure and is labelled as one.

## The positive controls

Every run reports predicted against measured. A figure nobody can falsify is not
a measurement.

**Heap.** A dense JS array of 587,500 doubles, which V8 stores as unboxed 8-byte
slots — **predicted 4,700,000 B**, measured **4,700,317 B** [4,699,074 –
4,700,974], **ratio 1.0001**. The instrument reads a known retained size to four
significant figures.

**Clock.** The floor's W1 page against the floor's W1 page with twice the rows
and nothing else changed. Element count fixes the prediction before the run:
`w1-elements(600) / w1-elements(300)` = 2403 / 1203 = **1.9975**. Measured
**1.8381** [1.8182 – 1.8548] — an **8% undershoot**, stable across all five
rounds.

That miss is characterised rather than waved through. Mount time is
`fixed + k · elements`, and the element-count prediction models only the second
term. Solving `(f + 2403k)/(f + 1203k) = 1.8381` gives `f ≈ 229k` — a fixed
per-mount cost worth about 229 element-equivalents, roughly 19% of the
1,203-element page, which is the React root creation and the `flushSync` bracket.
So the clock window behaves exactly as a linear model of it predicts, and its
work-proportionality is understated by that fixed term. **Consequence for the
thresholds: the clock instrument compresses ratios slightly toward 1.0, so a
threshold above 1.0 is if anything conservative and one below 1.0 is if anything
generous to UIx.**

## Instrument faults found and repaired

Each was caught by one of this arm's own gates, and each had produced a plausible
precise wrong number first. They are recorded because the next arm will meet them.

1. **The reflecting schedule degenerates at two arms.** Rotate `[0 1]` → `[1 0]`,
   reflect → `[0 1]` again: the shared `slot-order` emits the identical order at
   every sample index, every arm has one predecessor for ever, and the guard
   refused as `:unchecked` on every substrate row. The schedule is now *chosen* —
   both candidates are scored with the guard's own `adjacency` over the run about
   to be performed, and the one that actually gives every arm two distinct
   predecessors with the lowest modal share wins. At two arms that is the plain
   rotation; at three or more it is the reflection, exactly as the guard's
   self-test proves.
2. **The 51-element form sat on the timer's clamp.** At one mount per sample both
   segments returned exactly 0.75 ms and the ratio came out at precisely 1.0000 —
   a "tie" that was Chrome's 100 µs quantum. Eight mounts to a sample; the
   witness is unchanged, only the sample is bigger.
3. **The broad write sat on it too.** A single broad write read 0.25–0.50 ms on
   the floor, and the floor's two segment readings came out exactly 2× apart on
   quantisation alone. Four writes to a sample.
4. **A separate warm-up loop leaves the first sample of every round with no
   predecessor.** That `<none>` stratum read 1.35× its siblings with disjoint
   ranges and the guard refused. The warm-up now runs *inside* the round with
   `:previous` threaded through it, so the first recorded sample has a real
   predecessor like every other.
5. **The page accumulated ~12 MB per segment entry** — with `body-children`
   pinned at 2 throughout, so nothing was leaking into the document — and on that
   heap the floor arm, which cannot change, drifted 3.4 → 5.8 → 7.0 ms. The guard
   refused on phase, correctly. Three repairs were tried and are recorded because
   each narrows what the cause can be: a forced *major* collection between every
   sample (no effect), hoisting each arm so every round calls identical closures
   (no effect), and unwrapping the `flushSync` around every unmount (no effect on
   the climb — though a root's own `unmount()` opens a flush internally and the
   nested one is scheduled rather than performed, so the unwrapping is right and
   stays). **One round per page removes the drift by construction.** The
   accumulation itself is a finding, not swept up: see the open items below.
6. **A fixed witness order makes "second" a permanent property of one witness.**
   With W1 always first, W3's UIx segment read LAST-THIRD 1.4815× FIRST-THIRD,
   ranges disjoint, while W1 came out clean. Witness order now alternates with
   the round, exactly as the segment order does.
7. **A cold page pulls a work-proportional ratio toward 1.0.** With one round per
   page the positive control fell to 1.7727 / 1.7681 / 1.8000 against a page that
   ran three rounds and settled at 1.9167 / 1.9574 / 1.9574. The control gets its
   own, larger warm-up, and the residual 8% is the fixed-cost term quantified
   above.

## Open items — stated, not swept up

- **The ~12 MB-per-segment accumulation is unexplained.** It is proportional to
  mounts, survives a forced major collection (so it is retained, not garbage),
  does not reach the document, and is absent from the floor-only positive
  control — which places it on the substrate arms and their adapter segments, not
  on the harness. One round per page makes it harmless to these figures; it does
  not make it fixed.

  **Settled — and the retention is not retention.** rf2-flqpd's diagnostic
  (`retention_{probe,run}`) drove this arm's own `window.P0H` door and took every
  reading **twice**: as the page has it, and after a forced major collection.
  The collected column climbs 0.90 MB in total across six segments and settles;
  the **uncollected** column reaches the reported magnitude and **sawtooths**,
  which a leak cannot do. So the 12 MB is *garbage*: `usedJSHeapSize` read
  without a collection immediately before it measures how much rubbish is lying
  about, not how much is held. The premise above — *"survives a forced major
  collection"* — is what did not hold: a bare `gc()` is a **scavenge**.
  H1 (unmount does not unsubscribe) is dead, positive-controlled at 300 live
  entries and 0 after release; H2 (the segment entry retains its predecessor) is
  dead, because the collected heap does not step at a seam. **Nothing shipped is
  leaking** *within the tested 12 MB phenomenon and those two hypotheses* —
  sawtoothing alone cannot exclude a smaller leak, and the collected baseline
  and the cache census are the actual evidence.

  This page's mitigation — one round per page — **was right for a reason it did
  not have**: 30–80 MB of uncollected rubbish in a page is exactly what makes an
  unchanging floor arm drift 3.4 → 7.0 ms, because a scavenge inside a timed
  window is a measurement of the collector. **A heap figure must be preceded by
  a forced MAJOR collection at the reading**, not merely somewhere earlier in
  the run.

  **The diagnostic itself now fails closed (PR #7267 audit).** It had the same
  defect shape as the rig on the sibling page: it printed its refusals and
  exited 0.

    - `p0_arms/enter-segment!` **caught and discarded** both `destroy-frame!`
      and `destroy-adapter!` — while this bead's whole argument rejects H2 *from
      those exact transitions*. It now raises with the failing phase named, so
      every caller (this page's clock rounds, the heap row, the diagnostic)
      fails closed without having to remember to ask. *Mutation:* make a
      teardown throw; the run stops and names `destroy-frame!`.
    - SERIES C claimed all 50 cycles per segment were DOM-verified, but the
      page loop **overwrote `last`** and returned only the final cycle's
      verdict — 49 of 50 could render nothing and the row still printed `ok`.
      It now folds every cycle, counting failures and keeping the **first**.
      *Mutation:* fail cycle 0 only, leaving the last cycle good; the old fold
      passed, the new one refuses and names the cycle.
    - The **validity gates** were prints. A failed census positive control
      printed `[FAIL] the census cannot see a live subscription` and then
      `[ret] ok`; an absent heap column printed `n/a`; a census answering
      `:no-frame` printed the token; and in `repro` mode an early `return`
      skipped the page-error and unverified-cycle gates entirely. All of them
      now exit 1, in one named `failures` list. The empty-`SERIES B` case is
      reported as a **narrowed scope** rather than compared as `0 of 0`, which
      would have printed `[ok]` for a control that never ran.
    - `retention_run`'s **`RETENTION_COLLECT` and `RETENTION_ONLY` were
      matched, never validated** — `collect()` silently did nothing on an
      unrecognised value, so `RETENTION_COLLECT=bogus` forced no collection
      while the header still printed it as the collector forcing every
      COLLECTED reading, and the run exited 0: a typo certifying uncollected
      heap as collected. A misspelt `RETENTION_ONLY` was the same trap one
      door over, silently narrowing the run to `repro`. Both enums now refuse
      **before anything is built or measured**, with the value and the legal
      set named; `none` stays legal, because asking for no collector is a
      question this probe exists to compare. *Mutation:* the audit's exact
      one-cycle bogus-collector repro; it exited 0 at the landed head and now
      refuses up front, exit 1 (merged-PR audit #7281; PR #7285, landed
      `a36272fa3e`).

    The reaction-**watchers** column is deliberately still not a gate: it is
    blind under `:advanced` and carries no information in either direction, so
    it decides nothing in either direction. **Exit 0 still means the probe ran
    and its readings are valid — never that the heap is clean.** Rows on this
    programme stay operator-owned (rf2-2rtt6.1).
- **The witness set does not match rf2-2rtt6.2's.** That arm's branch (not yet
  merged) uses a 901-element M1 list reading `[:p0/cell i]` and a 51-element M2
  form reading the same sub; this page uses a 1,203-element W1 reading
  `[:p0/row i]`, a 51-element W3 reading `[:p0/field i]`, and a 301-element
  300-boundary grid reading `[:p0/cell i]`. **The thresholds above are still
  sound as thresholds**, because each is a ratio between two arms measured in the
  same run, on the same shapes, through the same sub graph, in the same rounds —
  but they are thresholds *on this witness set*. If P0's witness set converges on
  rf2-2rtt6.2's, the UIx arm must be re-pointed and the red-zones re-derived; the
  closest existing correspondence is this page's **U-broad** (300 boundaries,
  one commit all read, `[:p0/cell i]`), which matches that arm's bulk row in
  boundary count and sub graph and differs only in markup density.
  **Settled — this happened.** rf2-a4x1o re-pointed the UIx arm at rf2-2rtt6.2's
  witnesses and re-derived the clock red-zones there; three of the four verdicts
  moved, and U-broad's margin widened from 0.838× to 0.624×. See
  [the converged witness set](p0-converged-witness-set.md). The clock rows above
  are superseded as thresholds by that page; the heap rows were deliberately not
  re-run, and have since been regime-qualified as shared-query fan-out evidence
  by the heap-regime ruling (rf2-2rtt6.16), with the operative family on
  [the ladder](reads-per-boundary-heap-ladder.md).
- **This arm rides `:freehand-release` for its compiler settings** because the
  epic's sequencing law gives `implementation/shadow-cljs.edn` to rf2-2rtt6.2 and
  that arm had not merged. `P0_BUILD` points the driver at another build id;
  when the measurement-lane id lands, set it and delete the paragraph in
  `p0_run.cjs` that explains this.
