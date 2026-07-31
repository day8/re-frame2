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
  issued only against the published P0 baseline table.

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
rows from cross-instrument algebra; it gated them on a bench sweep that verified
the additive heap model and priced the terms on one instrument in one run. That
sweep has landed — [the fan-out heap sweep](studio/heap-fan-out-sweep.md), commit
`61dd44950a` (PR #7306): E/Q 1-2-4-8 at ROOTS=4 and ROOTS=1, six rounds each,
both runs exit 0, arm-order guard reportable, 0 unverified of 126 mounts per run,
dense-array positive control within 0.007%. It prices two of the three rows on
both substrates and **refuses** the third on one.

| Component (per boundary, distinct-query witness) | Reagent-on-subs | UIx-on-subs |
|---|---|---|
| Boundary shell (R = 0), two-prop boundary | 501–524 B | 221–231 B |
| Per unique subscription key | ~866 B [823–939] | ~1,590 B [1,525–1,659] |
| Per edge (consumer attachment) | **refused — measured, not identified** | ~1,345 B [1,327–1,396] |

**The refused row is a result, not an omission.** On UIx the two independent
identifications of the edge term agree within 3.8% and the surviving model
predicts a held-out rung it never saw to within 2%. On Reagent they disagree by
160 B — 80–84 B priced from the contrast against 234–244 B priced from the
intercept, more than twice the smaller of the two — and the criterion that
refuses them was written down before the run. What a Reagent boundary pays for
its *first* read beyond the per-key term is ~234–244 B, and that total is
quotable; how it splits between an attachment and a per-subscribing-boundary step
of ~150–163 B, which is neither shell nor edge, is not. Budget against the total.
Never against either half.

Two caveats ride these rows. Reagent's additive verdict is **marginal**: it
reaches the four-term model in both runs but by different failing checks — a
10.44% held-out miss against a 10% threshold in one run, a 163 B step against a
160 B band in the other — and its per-round verdicts flip. Neither threshold was
moved after the fact. And separating Reagent's step from its edge wants one rung
the sweep does not have, R = 3 at fixed Q, which would over-determine the pair;
the studio page carries that as an Open item. Until it runs, the Reagent per-edge
row stays refused.

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
ladder, which measured a spine two production landings ago: `rf2-2rtt6.13`
(`9df5094816`) stopped retaining a disposed render-phase reaction, worth 769 B
per unique query key and so 769 B per read where Q = E, and `rf2-2rtt6.25`
(`f784ab0adb`) removed the cold read's double build. A gate measured on a spine
that no longer ships is looser than the tree warrants, which is the unsafe
direction for a gate, so the line was restated rather than stamped historical.
It is now the sum of the two terms the fan-out sweep priced after both landings
— per-edge 1,345 B [1,327–1,396] plus per-unique-key 1,590 B [1,525–1,659] —
and three checks the derivation did not use agree with it: the sweep's held-out
two-point rung, the ladder's own slope less `.13`'s term, and the spine
decomposition's post-fix reading. The working is in
[the reads ladder](studio/reads-per-boundary-heap-ladder.md#5-what-this-hands-the-programme).

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

### P1 — the tournament (the six-week clock starts at the first Hicasso-arm commit that mounts the dogfood screen — HD-014)

Two kill-bounded Hicasso arms plus controls, on one shared minimal codec and
identical witnesses; challengers time-boxed to 1–3 days:

- **Controls**: direct UIx `$` (floor); stock Reagent and reagent-slim on the
  identical sub graph (comparators); **Adapter-Prime** — the composed donor-arm
  composition (reagent-slim `:f>` + UIx `use-subscribe`) ridden forward as the
  adapters-plus-sugar null hypothesis; the same referent as “the null” in the
  P2 ruling — rides every measurement.
- **Hicasso lean-React** (architecture.md Arm 1): instrument hooks/boundary,
  retained size, bulk K=100/300, the per-read and per-keystroke paths, and the
  sub-read rungs (HD-002 ladder).
- **Hicasso/PATCH** (Arm 2): the own differ patching the same witness DOM;
  controlled-restore hard-gated.
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
selection, IME composition, unchanged-model rejection, async normalisation);
keyed insert/delete/reorder; changing query identity through an abandoned render;
a foreign hook/context/ref component and a real error boundary (the runtime's
`h/boundary` class component, HD-020); StrictMode, abandoned first mount, root
teardown, HMR body swap. Assert the DOM, actual
commits, and **zero leaked subscription ref-counts after teardown**; an unchanged
hot read performs no new attach/release.

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

On P0/P1 numbers: **Hicasso/lean-React vs Hicasso/PATCH vs null**. **The decider
is the operator** (HD-013); one adversarial and one creative review pass over the
evidence are prepared and recorded on the standard bead to advise the ruling. A
candidate dies if it does not clearly beat the better Reagent path on matched
witnesses, costs material latency or memory against direct UIx without a
commensurate ergonomic win, or its win disappears once the sub graph and writes
are matched. On a "go", exactly one arm graduates into a tracked
`implementation/hicasso/` artefact and the v0 build proceeds under EP-0038's
wave 2; on a stop, adapters win and the donors' status quo stands.

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
