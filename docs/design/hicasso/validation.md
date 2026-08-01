# Hicasso — validation

The bar, the budgets, the phase plan, the witnesses, and the kill criteria. This
is the programme's proof spine; EP-0038 sequences it into beads. Decisions cited
as HD-nnn are normative in [decisions.md](decisions.md).

## The bar (HD-012)

- **Ship bar (clock)**: mount AND bulk view-work **≤ 1.0× Reagent,
  like-for-like** — both sides reading re-frame2 subscriptions — on the witness
  shapes. The only ship *number*.
- **Memory is first-class and co-instrumented on every arm**, governing through
  the kill rules (K3; the UIx material-cost rule), not the ship number.
- **UIx is the mandatory co-instrumented comparator** on every measurement, wired
  into the kill rules (material latency or memory cost against direct UIx without
  a commensurate ergonomic win kills the candidate). Target: near-UIx.
  **Red-zone ratios (adversarial review, adopted)**: when P0 publishes the
  baseline, one UIx red-zone ratio for clock and one for retained heap are set
  per witness family, *before* candidate results are opened. A red row requires
  an explicit operator waiver naming the observed dogfood benefit — silence
  never counts as a pass. The donor-arm stop rule's "acceptably close" is
  judged against these same ratios.
- **Architecture-kill tripwire**: bulk still > 1.5× Reagent after two serious
  runtime iterations — change arm or stop; never add features to outrun a red
  gate.
- The bar's numbers come from P0. Until the like-for-like arms exist, no spike
  figure is quotable against the bar — and the donor-arm stop ruling (HD-008) is
  issued only against the published P0 baseline table. **That ruling was issued
  on 2026-07-31** against the published table; the verdict and its win conditions
  are under [the P1 gate](#p1-gate--the-composed-donor-arm-hd-008).

## The budgets (paper before code)

| Budget | Rule |
|---|---|
| Runtime/shell hooks per product boundary | ≤ 2 (the subscription/epoch hook + the frame hook, HD-020); a ViewCell-class object graph = failed spike. The scalar *comparator* arm is exempt — it is priced by the 1/3/7/20 heap ladder, not this row |
| Exclusive retained per boundary (**the R=0 boundary shell**) | target ~0.4–0.5 KB; > 1 KB fails on paper. Shell only — a boundary's *reads* are priced on the per-read axis, against [the regime-matched red-zone gates](#the-per-read-gates) (rf2-2rtt6.16, UIx line restated on the post-landing tree per rf2-e3flf) |
| Per-read | tier-3 survival metric (HD-002): steady-state allocation slope across warm 1/3/7/20 reads, zero retained per-occurrence objects after commit/teardown |
| Per-keystroke | stated path for a 4-field form and a 100-cell grid; requires sub-recompute localization (which subs recompute, not merely which boundaries re-run) |
| Template identity | a stated cache key for any cached shape work |
| Host boundaries | priced separately (foreign components are census-rare) |

The shell budget line is the R=0 boundary shell by ruling (heap red-zone regime
ruling, delegated by Mike, 2026-07-31; authoritative text on rf2-2rtt6.16,
transcription on rf2-2rtt6.1): a candidate cannot pass the line by amortising
subscriptions across boundaries, because the distinct-query heap ladder (Q = E)
is the mandatory worst-case witness and the operative upper-envelope red-zone
family. The shell is component-shape sensitive at the ~75 B level, so the line
means nothing without a named boundary shape: the ladder's one-prop boundary
reads Reagent ~418–428 B and UIx ~208 B; the fan-out sweep's two-prop boundary
reads Reagent 501–524 B and UIx 221–231 B. Both donors clear the 1 KB paper-fail
line on either shape, but on the two-prop shape Reagent sits at the *top* of the
0.4–0.5 KB target band rather than inside it.

### The component budgets

Part 3 of the same ruling would not freeze the shell / per-edge / per-unique-key
rows from cross-instrument algebra; it gated them on a bench sweep that would
verify the additive heap model and price the terms on one instrument in one run.
That sweep has landed — [the fan-out heap sweep](studio/heap-fan-out-sweep.md),
commit `61dd44950a` (PR #7306): E/Q 1-2-4-8 at ROOTS=4 and ROOTS=1, six rounds
each, both runs exit 0, arm-order guard reportable, 0 unverified of 126 mounts
per run, dense-array positive control within 0.007%. It prices two of the three
rows on both substrates and **refuses** the third on one. It **fits** the
additive model rather than validating it out of sample: the warrant column below
says what each row therefore rests on, and the paragraphs under it say why the
stronger claim was withdrawn.

| Component (per boundary, distinct-query witness) | Reagent-on-subs | UIx-on-subs | Warrant |
|---|---|---|---|
| Boundary shell (R = 0), two-prop boundary | 501–524 B | 221–231 B | a measured rung, read directly — no model |
| Per unique subscription key | ~866 B [823–939] | ~1,590 B [1,525–1,659] | a direct slope, plus corroboration from outside the sweep |
| Per edge (consumer attachment) | **refused — measured, not identified** | ~1,345 B [1,327–1,396], **provisional** | a direct contrast; the *split* is model-dependent within ~50 B |

**Read the warrant column with the numbers.** The sweep's first publication
offered these rows as validated by a model that predicted a rung held out of
every identification. The audit of PR #7306 established that no rung was held
out — the rung in question feeds a mandatory model-admissibility check — so
**that warrant is withdrawn and each row now stands on its own evidence**
([the sweep's §4 and §6](studio/heap-fan-out-sweep.md#4-the-additive-model)).
The magnitudes are unchanged; none was re-measured, because the operator
deferred new heap measurement on 2026-07-31.

- **The shell is a measurement**, not a fit: `R0` is a rung that was mounted and
  read. Nothing in the correction touches it.
- **The per-unique-key row is the strongest of the three.** It is the slope of
  four rungs with edges pinned, r² ≥ 0.9973 in every round of both runs,
  re-priced from a disjoint rung pair, and matched from *outside* the sweep by
  `rf2-2rtt6.12`'s direct ablation — 866 B on Reagent, dead centre of the range;
  1,684 B on UIx once `.13`'s 769 B is taken out, 1.5% above the top of it.
- **The UIx per-edge row is a direct contrast and its label is provisional.**
  Two rungs at the same Q, one read apart, price an attachment at 1,344–1,345 B
  whichever model is selected; what the model decides is only whether a further
  38–52 B is called *edge* or *step*. Budget against ~1,345 B and treat the last
  ~50 B as unallocated — not as a validated decomposition.

**The refused row is a result, not an omission** — and the refusal is a
judgement, stated as one. On Reagent the two routes to the edge term disagree by
160 B: 80–84 B priced from the contrast against 234–244 B priced from the
intercept, more than twice the smaller of the two. The **precommitted** criterion
(r² floor, key agreement within 15%, step ≤ 10% of the fan-out-1 boundary,
model reproduces the R = 2 rung within 10%) selected the four-term model on both
runs and would have published the 84 B figure; no "twice the smaller term"
threshold appears in it, and no threshold in it was moved after the run. What the
precommitted machinery *did* record is the instability: the two runs reach the
four-term model by different failing checks, the step sits inside its band in one
and outside it in the other, and the per-round verdicts flip 5-of-6 and 2-of-6.
**The refusal itself is the studio page's post-run editorial call** on that
record — conservative, in the safe direction, and left standing here, but not
the output of a rule written in advance. What a Reagent
boundary pays for its *first* read beyond the per-key term is ~234–244 B, and
that total is quotable; how it splits between an attachment and a
per-subscribing-boundary step of ~150–163 B, which is neither shell nor edge, is
not. Budget against the total. Never against either half.

One more caveat rides these rows. Separating Reagent's step from its edge wants
one rung the sweep does not have, R = 3 at fixed Q, which would over-determine
the pair; the studio page carries that as an Open item, alongside what an
adjudication genuinely independent of the model would require. Until that runs,
the Reagent per-edge row stays refused.

Sub-key identity: `(query-id, args)` under value equality; value-unstable map args
thrash the index — documented, programmer-trusted. A missed invalidation is a P0
bug class: the staged-stale case is a CI witness for any asynchronous-host
variant.

### The per-read gates

Part 2 of the same ruling judges a boundary's *reads* on their own axis, against
two regime-matched lines. Both are stated on the mandatory distinct-query
witness (Q = E), as **marginal slopes** — what one more read costs a boundary
that already reads — and not as boundary totals:

| Gate | Line | Verdict beyond it |
|---|---:|---|
| UIx material-cost red-zone | **2,935 B/read** [2,852–3,055] | RED — requires an explicit operator waiver naming the observed dogfood benefit |
| K3, Reagent-sourced | **943 B/read** [935–944] | K3 territory unless a paper path down is named |

Between the two lines a candidate is *"UIx-rule cleared, K3 open until a path
down is named"* — never plain green. **943 B/read remains the number a native
layer actually has to beat.**

**The UIx line was restated on 2026-07-31** (Mike's ruling, option (a),
`rf2-e3flf`). It stood at 3,552 B/read while it was sourced from the reads
ladder, which measured a spine one heap-moving production landing ago:
`rf2-2rtt6.13` (`9df5094816`) stopped retaining a disposed render-phase
reaction, worth 769 B per unique query key and so 769 B per read where Q = E.
A gate measured on a spine that no longer ships is looser than the tree
warrants, which is the unsafe direction for a gate, so the line was restated
rather than stamped historical.

**What the restated line rests on.** Its primary source is a **direct two-point
contrast** on the fan-out sweep, taken in this gate's own Q = E regime:
`R2Q2B − R1Q1` reads 2,887 B/read at B = 1,200 and 2,970 B/read at B = 300,
mean 2,929 B — no model required. The sweep's additive decomposition (per-edge
1,345 B [1,327–1,396] plus per-unique-key 1,590 B [1,525–1,659] = 2,935 B) lands
0.2% away and is what makes the line *interpretable*. **The two are not
independent evidence**: they come from one run on one instrument and share the
`R1Q1` observation, and the sweep's model verdict itself depends on `R2Q2B`
through a mandatory admissibility check, so no rung was held out of anything.
Two readings from *other* instruments agree in the same direction — the ladder's
own slope less `.13`'s term (2,783 B/read) and the spine decomposition's
post-fix reading (2,774 B/read scaled) — and the ~5% between the instruments is
the offset the next paragraph governs. The working, and the withdrawn
independence claim, are in
[the reads ladder](studio/reads-per-boundary-heap-ladder.md#5-what-this-hands-the-programme).

**`rf2-2rtt6.25` is not part of this.** It landed in the same window
(`f784ab0adb`) and is an ancestor of the sweep's tree, but its single-build
benefit was measured under a forced synchronous commit; the audit of PR #7305
drove the shipped bare `createRoot().render` path and measured 2N. **No
retained-heap delta from it is established**, so no gate line here is
attributed to it and the public-schedule question stays with its open owner,
`rf2-2rtt6.25`.

**Reagent's line did not move.** Neither landing goes near the ratom path, and
the sweep's own post-landing reading of the Reagent slope straddles the
published figure.

**Both lines carry an instrument stamp, and 5% of margin is not a pass.** The
UIx line above is the P0 bench instrument's (`p0_run.cjs` / `p0_heap.cljs`),
which is where a candidate arm is measured; the reads ladder's instrument reads
the same tree at 2,783 B/read. The ~5% between the two is a measured
common-mode offset between harnesses and is unexplained, so a candidate is
judged against the donor row taken on *its own* instrument, and a margin under
5% is instrument-limited rather than cleared. The same caution applies to any
cross-regime ratio: the sweep measured the UIx/Reagent heap ratio drifting 9.3%
across fan-out E/Q 1 → 8, so a ratio ports approximately — far better than an
absolute, which moves 1.6–1.9× over that range, but not exactly.

## Phase plan

### P0 — build the bar (does not start the clock)

- The Reagent-on-subs arms, the ratom-spine write+flush leg, a **UIx-on-subs
  arm**, and the **1/3/7/20 reads-per-boundary heap ladder** measured directly —
  never inferred from sub-free rungs. The first three instrument specs live on
  the *closed* beads rf2-mapni / rf2-m7xs7 / rf2-ssn1o (do-not-refile; spec
  donors only) — **wave 0 files fresh beads** under EP-0038, per those beads'
  own close direction.
- **All bar-relevant numbers are browser numbers** (real browser, `:advanced`);
  JVM/Node figures are diagnostic-only and never quotable against the bar.
  Fast applications are the goal — never SSR or test-lane speed.
- **W1 baseline carry-over**: the discrepancy is already resolved in the
  tracked record (`docs/design/freehand/studio/bulk-rerender-where-the-time-goes.md`
  appendix — 2.987 operative; 1.904 was a different witness/door; re-run 3.075).
  P0 carries ≈2.99–3.08× floor / ≈1.9–2.0× Reagent into the baseline table and
  links the residual attribution bead; no re-litigation.
- Output: the standard bead's numbers.

### P1 gate — the composed donor arm (HD-008)

Before any API exists: reagent-slim's `:f>` function-component path + the
existing UIx `use-subscribe` spine already compose the central hypothesis
(FC + hooks + interpreted hiccup, no deref capture). Two rungs:

1. `:f>` + runtime hiccup + UIx subs — prices markup and reactivity;
2. plus one frame-context hook and native event-vector lowering — prices the
   product shell.

**Stop rule**: if this composed arm cannot clearly beat both Reagent paths and
stay acceptably close to direct UIx on the witness shapes, the programme stops
before an API is designed. Adapters + sugar is the recorded successful outcome.

**The stop rule has been consumed. The advisory was issued on 2026-07-31** as a
delegated advisory ruling under HD-013, against the published P0 baseline table,
and the outcome is recorded on `rf2-2rtt6.1` and `rf2-2rtt6.7`. This section no
longer describes a gate awaiting its ruling.

**Verdict: the stop rule was NOT met as written.** The composed arm beat neither
Reagent path on mount — 1.333–1.473× stock Reagent on the `M` page, 1.448–1.542×
on `U`, and indistinguishable from `reagent-slim` on both — and beat both on bulk
only on the weaker cross-run warrant. **Continuation is therefore an operator
override of a pre-registered gate, not a passing grade**, and the record says so
in those words. What the gate did establish is worth more than the verdict it
missed: the product shell is free (rung 2 indistinguishable from rung 1 on six of
eight rows), and rung 1 *is* `reagent-slim` — so the residual mount deficit is the
hiccup interpreter rather than the spine.

Given continuation, the win conditions are (1) mount ≤ 1.0× Reagent-on-subs, same
run and same instrument, with the codec inside 1.10× of direct UIx `$` on `M1`;
(2) bulk broad ≤ 1.0× Reagent, red below UIx's measured broad ratio, K2 at 1.5×;
(3) narrow as a **law** rather than a ratio — commit-side dirty-set flat in `B`
across 300/600/1,200/2,400 mounted subscribing boundaries, and ≤ 1.0× Reagent,
materially below UIx's measured narrow ratio; (4) grouped per-read retained heap
≤ ≈2,000 B/read on the bench instrument, with a named paper path toward 943 B;
and (5) a measured ergonomic preference over **Adapter-Prime**, never over the
floor. The nine pre-registered kill signals are on `rf2-2rtt6.7`.

**The advisory named one precondition, and it is now met.** The clock gate lines
carried no post-landing restatement when the advisory was issued — the M1 mount
red-zone was measured on the pre-`rf2-2rtt6.25` spine — so a candidate mount row
judged against it was scored against a line the tree no longer justified. Mike
ruled option (a) on 2026-07-31: re-take on the converged instrument and restate
from same-instrument data. That re-take is `rf2-b0tz5`.

### The clock gates, restated on the post-`.25` tree

**INSTRUMENT STAMP: the converged `p0_converge_app` instrument, `rf2-2rtt6.2`'s
witness family, post-`.25` spine `8d20218fd1`, whole-tree anchor `7f54c67d5a`.**
All four published converged rows were measured on anchor `4e4a68fa1f`, whose
`spine.cljs` blob is byte-identical to the **pre-`rf2-2rtt6.13`** spine — so all
four were stale, not just the mount row. Five six-round runs, every validity gate
clean:

| bar row | published *(pre-landing)* | **restated, post-`.25`** | disposition |
|---|---|---|---|
| **M1 mount** | ~~1.2310×~~ | **1.0150×** [0.9820 – 1.0480] | **RESTATED — the L1 mount red zone has CLOSED.** Under Ruling 1 the red-zone *is* the measured UIx ratio, so the mount line now sits at parity |
| **bulk broad** | ~~0.6291×~~ | ~~not distinguishable at n = 5~~ | **WITHDRAWN (rf2-yd52q)** — the row's window closes before the frame it causes, and that frame is **half the operation**. On a clock that sees both, the re-take reads **0.8602×** [0.7709 – 0.9058] and publishes **no magnitude**: the direction stands, the 37% does not. See [the re-take](studio/bulk-broad-re-taken.md) |
| **bulk narrow** | 1.1754× | not distinguishable at n = 5 | **stands** — direction unchanged, UIx slower |
| M2 mount *(diagnostic)* | 1.0601× | not distinguishable at n = 5 | **stands**, still not quotable against the bar |

> **THE INSTRUMENT BEHIND ALL FOUR ROWS IS AN IN-PAGE WINDOW (rf2-8nqsl).**
> Every clock row this programme has published — these four, the coldmount
> witness and the HD-008 donor rows — was taken on `lane/now-ms`, a
> `performance.now()` window that closes the instant `flushSync` returns, before
> the style recalculation, layout and paint the operation caused. Measured
> against a second clock on the same samples, substrate arms **split
> +268% to +704%** apart while the pure-React control arms split by under 13% —
> which is exactly how a lane that certifies its instrument on its controls never
> saw it. **That second clock was `taskNet`, a frame-ONLY reading** (below), so
> the two windows are near complements and the separation is a *split* between
> two halves rather than one instrument's error rate. **`M1 mount 1.0150×`
> survives**: `taskNet` puts the same components at `1.0110×` and the corrected
> clock at `1.0011×`, both inside the published interval, as does
> [the coldmount page](studio/coldmount-double-build-priced.md)'s `1.0054×`.
> **`bulk broad 0.6291×` does not**: the cross-check's in-page reading of
> `0.6924×` lands inside this row's own run-mean spread, and no clock that sees
> the whole operation comes near it. **`rf2-yd52q` has since re-taken the row and
> withdrawn it** — and found that the audit's frame-inclusive clock subtracted
> `DevToolsCommandDuration`, which **carries the operation's own script**, making
> it a frame-**only** reading rather than a frame-inclusive one. On raw
> `TaskDuration` — script and frame together — the row reads **0.8602×**
> [0.7709 – 0.9058], and that same corrected clock reproduces `M1 mount 1.0150×`
> at **1.0011×** in the same runs. `bulk narrow 1.1754×` is exposed and not yet
> adjudicated. Full record, method and controls: [the clock behind the published
> rows](studio/the-clock-behind-the-published-rows.md) and
> [bulk broad, re-taken](studio/bulk-broad-re-taken.md).

**The arithmetic.** The threshold is a quotient of two floor-normalised legs. The
denominator, `reagent-subs ÷ floor`, reads **4.425×** against the published
**4.358×** — unmoved within 1.5%. The numerator, `uix-subs ÷ floor`, falls
**5.343× → 4.475×**, −16.2%, an order of magnitude further. `4.475 ÷ 4.425 =
1.011`. **The tightening is a property of the UIx arm, not of a drifting
baseline** — the check `rf2-2rtt6.21` made necessary by measuring that same
denominator differing +9.7% between two authors.

**A candidate at 1.15× Reagent on mount was red-free yesterday and is RED
today.** Every candidate mount row between `1.0150` and the old `1.2310` flips,
and that tightening is the intended consequence rather than a problem to soften.
The converged instrument independently agrees with
[the coldmount page](studio/coldmount-double-build-priced.md)'s `1.0054×` on the
same witness — 1.0 point apart, from two instruments and two authors. *(PR #7315
first published this row at ~~`1.0243×`~~ over four of the five runs; the fifth
was dropped for the way its order strata split, which is a selection on the
result, and it is back in. No disposition changed.)* Full record, the launch set
in full, and the committed observation table every figure is derived from:
[the re-take](studio/p0-converged-witness-set.md#the-re-take-on-the-post-25-tree-rf2-b0tz5).

### The candidate's own clock rows (rf2-0qj9w)

Every row above is about the **donors**. The candidate had no wall-clock
measurement at all until `rf2-0qj9w`: hook count and per-read retained heap were
measured, mount, bulk and per-keystroke were not. It now has mount and
per-keystroke on Chrome's own renderer counters over CDP, read after the browser
has produced the frame the operation caused, so the style, layout and paint an
in-page `performance.now()` window excludes are inside the number. Per-keystroke
is Event Timing, which captures the paint. Full record, both positive controls,
and the two instrument repairs a refusing control forced:
[the candidate's clock](studio/the-candidates-clock.md).

> **THAT INSTRUMENT PUBLISHED ON `taskNet`, WHICH IS FRAME-ONLY (`rf2-yd52q`,
> `rf2-emvod`).** It reported `TaskDuration` less `DevToolsCommandDuration`, and
> the subtracted term carries the page script a protocol command invokes — so on
> every row driven through `page.evaluate` it removed the operation itself. The
> mount magnitude below is superseded: on raw `TaskDuration`, script and frame in
> one number, `hicasso / reagent-subs` on `M1` reads **1.4896×**
> [1.3488 – 1.5989], so **the deficit is worse than published, not milder**. Both
> bulk rows move above parity and `bulk100` changes sign. The `per-keystroke` row
> is driven through the Input domain and was **never affected** — its two clocks
> agree to 0.3%. Full re-adjudication:
> [the corrected clock's page](studio/rows-re-adjudicated-on-the-corrected-clock.md).

| row | candidate | disposition |
|---|---|---|
| **M1 mount** | ~~1.2107× Reagent-on-subs~~ *(frame-only)* → **1.4896×** [1.3488 – 1.5989] on script-and-frame | above the `≤ 1.0×` win condition and above the `1.0150×` red zone on either clock. The frame-only range straddled 1.0 so a deficit was not *established* at n = 6; the corrected clock's interval does **not** straddle it, and the deficit is larger. The deficit is the runtime hiccup **codec**, not the spine: the candidate pays +1.06 ms over its own floor where `uix-subs` — same spine, same adapter, same 300 reads, byte-identical DOM — pays +0.38 ms |
| **per-keystroke** | one frame, as are both donors | Event Timing puts every arm at its 16 ms reporting floor; the finer clock reads 2.0–2.3 ms of main-thread work inside a 16.7 ms budget. Indistinguishable, and a pass for all three |
| bulk K=100/300, narrow | **refused** | the doubling control failed the strict rule in every run, and the rows move more between runs (0.87 → 1.38 on one) than the effect they report. The page names three repairs, one of which is that `ctl-2x` is mis-specified for an *update* row — it doubles the page, and on an update the work does not follow |

**One methodological finding rides these rows and outranks them.** On the same
samples, an in-page `performance.now()` window and `taskNet` **split** a
substrate arm 300–610% apart, **and by a different factor per arm** — on M1 the
in-page window puts the candidate at 1.56× Reagent where `taskNet` reads 1.21×
and the clock holding both halves reads 1.4896×. It is not a scale error that
cancels in a ratio. The pure-React control arms differ by only 6–13%, which is
how a lane that checks only its controls in-page would never see it — and,
because those arms behave alike under either half, is equally why the controls
could not catch that the second clock was frame-only. When this row was
published no clock row in the programme had ever been checked against an
instrument that sees past `flushSync`; **`rf2-8nqsl` has since done that
check**, and it
splits the record: `M1 mount 1.0150×` and coldmount's `1.0054×` survive it,
`bulk broad 0.6291×` does not and **has since been withdrawn** by its re-take
([`rf2-yd52q`](studio/bulk-broad-re-taken.md)), and `bulk narrow` plus the HD-008
donor rows remain exposed and unadjudicated (`rf2-ph85f`). See [the clock behind
the published rows](studio/the-clock-behind-the-published-rows.md). That audit
also sharpens
`rf2-rguy1` — the external cross-check against an instrument built on Chrome's
timeline — from a nicety to the next question, and names **bulk broad** as the
row to point it at first.

**`rf2-rguy1` has now run, and it answers both halves.**
[Cross-checked against an instrument nobody here wrote](studio/cross-checked-against-an-outside-instrument.md)
implements the krausest/js-framework-benchmark app in **three** re-frame2 arms —
Reagent-on-subs, UIx-on-subs and Hicasso Arm 1, one shared model, canonically
identical DOM — and runs them under **the benchmark's own driver** as well as
ours.

| row | outside instrument | our published figure | disposition |
|---|---|---|---|
| **candidate mount** | `1.1756×` on create-1,000 | ~~`1.2107×`~~ **`1.4896×`** on M1 | **corroborated in direction, and the span was across two clocks** (`rf2-emvod`). `1.2107×` is a frame-**only** figure and the outside instrument's is script-and-frame, so "1.18–1.28" compared unlike things. On one clock the gap is **workload (~7–17%)** plus an **instrument-window 8.8%** with a named mechanism — and the leg that was previously invisible, the clock definition, is eliminated rather than estimated. The mount deficit is not a harness artefact and is **larger on our own witness** than this row read |
| **donor bulk broad** | `0.9740×` on replace-all, `1.1419×` on swap | ~~`0.6291×`~~ **withdrawn** | **refused a third time, and now [re-taken](studio/bulk-broad-re-taken.md).** No instrument that sees the whole operation reproduces the 37% win; the re-take reads `0.8602×`, keeps the direction and publishes no magnitude. It expected parity and did not find it either — the row is a real but much smaller win, at the edge of the instrument's resolution |
| candidate bulk | `1.6216×` theirs / `1.4260×` ours on replace-all | **refused** by the clock page | the candidate's **worst** row, agreed by both instruments and materially worse than its mount |
| candidate narrow | `0.7203×` theirs / `0.7583×` ours on partial-update | **refused** by the clock page | a **win** — 24–28% faster than Reagent-on-subs. UIx wins it slightly harder |

Six of ten comparable rows agree within a pre-declared 15% band. The largest
disagreement — teardown, `1.8638×` ours against `1.2877×` theirs — has a named
mechanism rather than a shrug: our window runs to `setTimeout(0)` and contains the
post-paint macrotask where disposals land, theirs ends at the paint commit. Its
positive control **failed** (13.1–13.7× against a pre-registered 8–13×, on all
three arms alike) and the page records that rather than widening the band. The
benchmark's app has trivial state, so it can price rendering and says nothing
about subscription fan-out, frame isolation or boundary-scoped reactivity.

### P1 — the tournament, and its one surviving arm (the six-week clock starts at the first Hicasso-arm commit that mounts the dogfood screen — HD-014)

> **Since 2026-07-31 there is one arm.** Mike ruled Hicasso a React adapter and
> dropped Arm 2 (PATCH) on **product direction, not on measurement** — it met its
> hard gate in real Chromium. P1 is therefore no longer a two-arm contest: the
> lean-React arm is the product line, measured against the same controls and the
> same witness set. The Arm 2 bullet is kept below as the record of what ran.

One kill-bounded Hicasso arm plus controls, on the minimal codec and identical
witnesses; challengers time-boxed to 1–3 days. (It was written for **two**
kill-bounded arms, and both did run before the ruling.)

- **Controls**: direct UIx `$` (floor); stock Reagent and reagent-slim on the
  identical sub graph (comparators); **Adapter-Prime** — the composed donor-arm
  composition (reagent-slim `:f>` + UIx `use-subscribe`) ridden forward as the
  adapters-plus-sugar null hypothesis; the same referent as “the null” in the
  P2 ruling — rides every measurement.
- **Hicasso lean-React** (architecture.md Arm 1): instrument hooks/boundary,
  retained size, bulk K=100/300, the per-read and per-keystroke paths, and the
  sub-read rungs (HD-002 ladder).
- **Hicasso/PATCH** (Arm 2): the own differ patching the same witness DOM;
  controlled-restore hard-gated. **Withdrawn 2026-07-31** on Mike's ruling that
  Hicasso is a React adapter — on direction, not on measurement, the arm having
  met that gate. Its tree is retired (`rf2-m6if4`); the controlled grid it was
  gated on now lives at
  `bench/hicasso/controlled_restore_dom_cljs_test.cljs`, re-taken on React —
  and re-taken **twice**, because UIx selects between plain React and a port
  of Reagent's controlled-input workaround on what else is on the classpath.
  The grid pins the implementation it measures rather than inheriting one; the
  matrix is on
  [the studio page](studio/controlled-input-two-implementations.md)
  (`rf2-n3dxw`).
- A root-pull arm may run as a non-product assumption-challenge that leaves no
  API behind.
- **The dogfood screen**: one list + one controlled field + sub reads, written
  in **three renderings** (HD-002) — the collector surface, the grouped
  `use-subs` surface (its canonical spelling pre-declared), and raw UIx —
  judged on diff and preference by its authors. The grouped rendering rides the
  comparator spine, not a third runtime. The ergonomics half of the verdict,
  and the guarantee that a collector loss promotes an already-scored surface
  rather than triggering a mid-clock API rewrite.

**Witness set**: the 300-boundary shapes; 1/3/7/20 reads-per-boundary as two
separated scaling curves (fixed reads × growing boundaries; fixed boundaries ×
growing reads); the 100-cell controlled grid (same-turn echo, mid-string caret,
selection, unchanged-model rejection, async normalisation — IME composition is
named here but **not asserted**, and `rf2-n3dxw` says why);
keyed insert/delete/reorder; changing query identity through an abandoned render;
a foreign hook/context/ref component and a real error boundary (the runtime's
`h/boundary` class component, HD-020); StrictMode, abandoned first mount, root
teardown, HMR body swap. Assert the DOM, actual
commits, and **zero leaked subscription ref-counts after teardown**; an unchanged
hot read performs no new attach/release.

#### The four deferred-read crossings, named (rf2-2rtt6.32)

The read-outside-the-render family was enumerated as four cases, and it is
carried here as four **named** rows rather than as one property, because the
verdicts differ and two of them are not a throw. Each row asserts what the
runtime does; where that is not a refusal the row says why, and where it is a
limit rather than a repair it says that instead.

| # | The crossing | Verdict | Witnesses |
|---|---|---|---|
| 1 | a lazy seq handed straight to React as children — iterated during the **parent's** reconciliation, after the body returned | **repaired, so it never reaches React unrealised** — and a throw would be wrong here: a seq is structure, and forcing it changes nothing an author can observe. `expand-seq` drives a child seq to exhaustion, `realize-children` folds one into a vector, and `realize-deep` covers the boundary hand-off | `a-lazy-for-registers-its-edges-and-its-readers-re-run`, `a-lazy-seq-returned-as-the-body-root-registers-its-edges-too`, `a-lazy-seq-handed-across-a-boundary-is-the-parents-read`, `the-childs-second-render-reads-nothing-which-is-why-the-first-row-matters` |
| 2 | a `(sub …)` inside a render prop handed to a foreign component | **split, and one half is a declared limit.** Invoked outside any render it throws. Invoked inside **another** boundary's live render it does not: `read-key!` asks whether *any* body is running, not whether *this* one is, so the read is attributed to whichever boundary is rendering. The `defhost` / `[:> …]` spelling is not in this codec at all — HD-011 owns that surface — so the row is named against the general shape it is an instance of | `a-function-prop-not-called-in-the-render-is-already-loud`, `a-function-prop-keeps-its-edge-because-the-child-re-runs-it`, `a-body-that-stops-calling-a-render-prop-simply-holds-no-edge` |
| 3 | a `(sub …)` inside an event handler | **throws.** The render frame is set in a `try` and cleared in the matching `finally`, so a handler the browser invokes later finds none and says so | `every-read-that-escapes-the-render-is-loud-rather-than-a-missing-edge` (the stored handler, and the handler actually called), `a-read-outside-a-render-is-a-loud-error` |
| 4 | a `(sub …)` inside a `delay` or a lazy seq the author returns as a **prop value** | the seq is **repaired** at the crossing; the `delay` is **refused** there, inside the render of the body that wrote it. Refused at **both halves of a map entry** since `rf2-2rtt6.32`: a `Delay` hashes by object identity and a small map literal never hashes its keys at all, so a key position holds an unforced one as readily as a value position does | `an-unforced-delay-crossing-a-boundary-is-refused`, `the-refusal-reaches-wherever-the-walk-reaches`, `a-delay-held-as-a-map-key-is-refused-exactly-as-a-value-is`, `a-key-held-delay-is-refused-before-the-child-can-cache-it` |

**Two limits, stated rather than discovered.** Row 2's second half is one: a read
landing inside another boundary's live extent is attributed to it, and refusing
that needs per-boundary render identity, which the HD-002 adjudication rejected
as one line from the candidate-ledger tripwire and as an error made of a
legitimate authoring shape. The other is a deferral parked in a **mutable
reference** — an atom, or a module-level var the codec never sees — which no
structural walk reaches;
`a-deferral-parked-in-a-mutable-reference-is-outside-the-walk` asserts the
unrepaired behaviour so it is a property of Surface B rather than a later
surprise. React with hooks has the identical hole.

**Every row of this family is asserted two renders deep, never at first paint.**
A `Delay` and a `LazySeq` both cache, so a broken runtime's first render is
correct and its second is empty — a witness that stopped at the first paint
passes on every fault this section names, which is how the map-key hole survived
the walk that was built to close it.

### Measurement discipline

Same React version, build settings, tree, frame, queries, writes, and data across
arms. Bare ratoms/cursors are labelled lower bounds, never fair comparisons.
React Compiler enters a comparator only when a real CLJS toolchain can ship it on
the code under test. "Faster" and "leaner" are separate claims; one must not hide
the other. Every reported ratio stays attached to its exact witness, denominator,
commit, and build — never average across instruments; **every P1 evidence row
cites its producing commit SHA and reproduction command** (evidence must not
outlive the code that produced it). The bench harness's
recorded instrument-fault classes (both-orders runs, computable-size controls,
zero-reading NOOP arms, arm-order contamination, floor-arm certification) are
binding method.

### P2 — the fork ruling (HD-013)

On P0/P1 numbers: **Hicasso/lean-React vs null**. **The decider is the operator**
(HD-013); one adversarial and one creative review pass over the evidence are
prepared and recorded on the standard bead to advise the ruling. The candidate
dies if it does not clearly beat the better Reagent path on matched witnesses,
costs material latency or memory against direct UIx without a commensurate
ergonomic win, or its win disappears once the sub graph and writes are matched.
On a "go", it graduates into a tracked `implementation/hicasso/` artefact and the
v0 build proceeds under EP-0038's wave 2; on a stop, adapters win and the donors'
status quo stands.

**What the 2026-07-31 ruling did and did not decide.** It removed Arm 2 from this
comparison, so the ruling is no longer a three-way choice and no longer picks a
winner between two Hicasso arms — that half is settled, on direction rather than
on numbers. It did **not** decide the arm-versus-null question: the null control
(Adapter-Prime, adapters-plus-sugar) still rides every measurement, adapters-only
is still a *successful* outcome, and the kill criteria below still bite. The
comparison this section schedules was originally written as
*lean-React vs PATCH vs null*.

## Kill criteria (any tripping = stop or narrow; adapters-only is success)

| # | Kill if |
|---|---|
| K1 | Mount > Reagent on the reference list+form after two serious runtime iterations |
| K2 | Bulk (≥ ~100 boundaries, one commit) > 1.5× Reagent view work after those iterations |
| K3 | Per-boundary heap worse than Reagent with no paper path to the floor |
| K4 | Controlled text fails same-tick echo / IME on Chromium and WebKit for a simple form |
| K5 | > ~8 public concepts or > ~8 guide pages to ship CRUD |
| K6 | A compiler/analyzer/dual mode is required to meet K1–K3 |
| K7 | Six weeks (HD-014) with no path that is both preferable and ≤ Reagent on K1–K2 |

Red gates shrink scope; they never expand features.

## Timing (HD-015)

The programme starts immediately: P0 and the donor arm are ordinary bench-lane
work and do not contend with the release train's operator actions. The six-week
clock starts only when a Hicasso arm first mounts the dogfood screen (HD-014).
Results publish to each bead and to `docs/design/hicasso/studio/` (minted by the
first P0 worker; HD-017). Arms needing new build ids or dev-http ports touch the
hot-zone `implementation/shadow-cljs.edn` and are sequenced, never parallel.
