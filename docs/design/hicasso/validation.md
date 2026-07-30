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
| Exclusive retained per boundary | target ~0.4–0.5 KB; > 1 KB fails on paper |
| Per-read | tier-3 survival metric (HD-002): steady-state allocation slope across warm 1/3/7/20 reads, zero retained per-occurrence objects after commit/teardown |
| Per-keystroke | stated path for a 4-field form and a 100-cell grid; requires sub-recompute localization (which subs recompute, not merely which boundaries re-run) |
| Template identity | a stated cache key for any cached shape work |
| Host boundaries | priced separately (foreign components are census-rare) |

Sub-key identity: `(query-id, args)` under value equality; unstable map args
thrash the index — documented, programmer-trusted. A missed invalidation is a P0
bug class: the staged-stale case is a CI witness for any asynchronous-host
variant.

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
