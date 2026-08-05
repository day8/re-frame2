# EP-0038: The Hicasso View-Layer Programme

Status: accepted
Type: standards-track
Created: 2026-07-30
Resolution: accepted 2026-07-30 (HD-001–HD-021 resolved under delegated authority; operator-overturnable)

## Abstract

**Hicasso** (`re-frame.hicasso`, alias `h`) is re-frame2's third and final native
view-layer attempt: **interpreted Hiccup on a UIx-class React function-component
host, optimised for re-frame2** — a better UIx with hiccup interpretation, not a
better Reagent. The programme is a bounded product experiment, not a rewrite
mandate: a measured browser-only bar built first, a composed donor arm as a
stop-gate before any API exists, a two-arm spike tournament under a six-week
clock with pre-registered kill criteria, and an operator fork ruling that
graduates exactly one arm — or stops, with "adapters + sugar" recorded as a
*successful* outcome. On a win, the public Freehand and re-frame.ui surfaces are
deleted (no absorption programme); on a loss, the adapters stand. The durable
design record is `docs/design/hicasso/` (excluded from the published site, read
in the source tree — the EP-0036 precedent); every design decision is resolved
in `docs/design/hicasso/decisions.md` (HD-001–HD-021).

## Motivation

Two prior attempts (EP-0030's re-frame.ui; EP-0036's Freehand) validated the
authoring model — data views, `sub`-as-value, intent-as-data; zero correctness
failures ever recorded — and failed on runtime economics, discovering their
deficits after the build (bulk re-render ~10× Reagent in the browser;
per-boundary retained memory multiples of both Reagent and UIx). The measured
post-mortems attribute the cost to two mechanisms (a per-read dependency ledger
and a per-boundary shell) whose residual costs Hicasso re-prices under explicit
budgets with tripwires, and identify the fastest measured arm in the repo — a
UIx-class React FC — as the correct runtime parent. What users demonstrably
miss on that parent is precisely hiccup-as-data and intent-as-data: that is the
product. The ultimate test is speed **in the browser** — fast applications, not
fast SSR or fast test lanes.

## Specification

The programme runs as three waves of beads under one epic. Authority and detail
live in the design record: `charter.md`, `architecture.md`, `validation.md`,
`authoring.md`, and `decisions.md` under `docs/design/hicasso/`. Where this EP
and those pages ever differ, decisions.md governs, then validation.md.

Glossary for this EP: **"donor arm"** = the composed spike built from parts the
existing *adapters* donate (reagent-slim `:f>` + UIx `use-subscribe`) — distinct
from the **"donor trees"** (the Freehand/re-frame.ui source trees, EP-0036's
sense). **"Adapter-Prime"** = that same composition ridden forward through the
whole tournament as the adapters-plus-sugar null hypothesis — the referent of
"null" in the P2 ruling.

### Wave 0 — governance, the bar, and the draft guide (parallel-safe; off-clock)

- **The standard bead** (operator-owned): the two-number bar (ship ≤ 1.0×
  Reagent like-for-like on the clock; 1.5× bulk architecture-kill), the
  browser-numbers-only rule, the budgets, kill criteria K1–K7, the UIx red-zone
  ratio protocol, the clock definition, the HD-002 adjudication clauses, and
  the P2 decider protocol (the operator decides; recorded adversarial +
  creative reviews advise; the earlier donor-gate ruling is a delegated
  advisory ruling, operator-overturnable — HD-013).
- **The like-for-like instruments**, filed as **fresh beads** (the prior
  instrument beads rf2-mapni / rf2-m7xs7 / rf2-ssn1o are closed do-not-refile
  specification donors, per their own close direction): Reagent-on-subs mount
  and bulk arms, the ratom-spine write+flush leg, a UIx-on-subs arm, and the
  1/3/7/20 reads-per-boundary heap ladder.
- **W1 baseline carry-over** (already resolved in the tracked record): P0
  carries ≈2.99–3.08× floor / ≈1.9–2.0× Reagent into the baseline table; the
  residual dominance-attribution question rides its own existing bead.
- **The draft user guide** (parallel, operator-requested): first-cut guide
  pages into `docs/design/hicasso/draft-guide/`, written per `docs/AUTHORING.md`
  against the designed authoring surface (the grouped default *and* the
  collector-contingent sketch, clearly caveated) — explicitly disposable, to be
  redone properly after P2 against the real implementation; excluded from the
  published site with the rest of the design tree.

### Wave 1 — the stop-gate and the tournament

- **The composed donor arm** (HD-008): two rungs — (i) `:f>` + runtime hiccup +
  UIx subs; (ii) plus one frame-context hook and native event-vector lowering.
  Off-clock; its stop/continue ruling is a delegated advisory ruling issued
  only against the published P0 baseline table. Stop here — before any API —
  if it cannot clearly beat both Reagent paths and stay inside the UIx
  red-zones.
- **The shared front half** is built once, as the **first post-gate tournament
  work item**, alongside the witness-set build: the codec (extracted from
  reagent-slim's measured plumbing), the subscription→boundary index
  (graduating the spike-01 model into the tracked bench/test tree, HD-017),
  and intent lowering. The donor arm's rung-(ii) lowering is a minimal
  disposable slice, not the shared build. The dogfood screen's shared app/state
  code lives with the bench trees per HD-017.
- **The tournament** (HD-007; the six-week clock starts at the first
  Hicasso-arm commit that mounts the dogfood screen, HD-014): Hicasso
  lean-React and Hicasso/PATCH arms on the shared front half and identical
  witnesses, Adapter-Prime riding every measurement; the HD-002 sub-read tiers
  instrumented; the dogfood screen written in **three renderings** (collector,
  grouped, raw UIx) as the ergonomics half of the verdict; witness set,
  red-zones, and measurement discipline per validation.md.

### Wave 2 — the fork ruling and (on a go) v0

- **P2 ruling** (HD-013): **the decider is the operator**; one adversarial and
  one creative review pass over the P0/P1 evidence are prepared and recorded on
  the standard bead to advise the ruling. Outcomes: graduate exactly one arm
  into a tracked `implementation/hicasso/` artefact and build v0 (five tier-1
  shapes, controlled input R-A1/R-A2, one host hatch, the short guide — charter
  §Use cases), or stop with adapters-as-success.
- On a go, wave 3 is filed only then: donor-surface deletion per HD-018, the
  real guide (superseding the wave-0 draft), skill/migration work, and the
  product-phase roster.

### Sequencing law

Wave-0 beads are parallel-safe (bench trees, the design tree, and the tracker
only). The donor arm may run alongside wave 0; tournament arms dispatch only
after the donor gate passes and the standard bead carries P0's numbers.
**Before the P2 graduation**, nothing in this programme edits `spec/*`, release
workflows, or the Freehand/re-frame.ui `src` trees — with one carve-out: the
bench/test measurement lane (e.g. `implementation/freehand/test/.../bench/`) is
the programme's measurement apparatus and accepts instrument-only merges
(HD-017). Runtime skeletons stay off `main` until the P2 graduation; arms
needing new build ids or dev-http ports touch the hot-zone
`implementation/shadow-cljs.edn` and are sequenced, never parallel. Results
publish to beads and `docs/design/hicasso/studio/`. The programme does not
contend with the release train.

## Rationale

Measure-first with a null control is the direct inversion of both predecessors'
failure shape (bars discovered at 90% built; no dignified stop; success defined
as machinery absorbed rather than product preferred). The two-arm tournament
exists because the lean arm's honest ceiling inside React may be UIx-parity
while only the own-renderer family can beat the frontier on bulk and memory —
a question only instruments can settle. The read-mechanism ladder (grouped
default, collector challenger, scalar comparator) puts the burden of proof on
the mechanism class that killed the predecessor while keeping the authoring
surface that justifies the product alive as the hardest-ridden challenger. Full
per-decision rationale lives with each HD record.

## Backwards Compatibility

Nothing published changes during waves 0–2: the adapters (Reagent,
reagent-slim, UIx) remain first-class and untouched; the Freehand and
re-frame.ui trees are frozen except the bench/test measurement lane; no released
artefact depends on any programme output. On a P2 "go", wave 3 executes HD-018
(delete the public donor surfaces; `spec/004`-family re-homing per Graduation
below). On a stop, the repo's shipped surface is exactly what it was.

## Resolved Decisions

All twenty-one design decisions are resolved in
`docs/design/hicasso/decisions.md`; dispositions in brief: HD-001 name/alias ·
HD-002 sub-read tiers (grouped default / collector challenger / scalar
comparator; both-fail→null) · HD-003 hooks placement rule (taught, not policed)
· HD-004 accelerants arm-scoped · HD-005 evidence seam only · HD-006 no default
memoization · HD-007 two equal-class arms · HD-008 donor stop-gate · HD-009 no
`local`, sugar unfrozen · HD-010 theming laws, no Hicasso context · HD-011
`defhost` + the one `[:>]` escape · HD-012 clock-only ship bar, UIx red-zones ·
HD-013 deciders (operator at P2; delegated advisory at the donor gate) · HD-014
the clock · HD-015 start now · HD-016 invocation + component ABI · HD-017 code
residence + bench-lane carve-out · HD-018 delete-on-win · HD-019 the
synchronous door · HD-020 v0 host mechanics · HD-021 root/HMR/headless.
**Future operator rulings made under this EP — the donor-gate ruling and the P2
fork ruling — are recorded here when made** (per EP-0009 rule 2), with their
evidence on the standard bead.

### Addendum, 2026-07-31 — Hicasso is a React adapter; Arm 2 (PATCH) is dropped

**Operator ruling (Mike), verbatim:** *"I want hicasso to be an adaptor for React
that is optimised for re-frame2, user ergonomics and performance. I don't want
ARM 2 (PATCH)."*

Recorded here per rule 2, and as an addendum rather than an edit per EP-0009
rule 3 — the wave-1/wave-2 text above is left as written, because it is the
proposal that was accepted and the tournament it describes did run.

- **HD-007 (two equal-class arms) is superseded.** There is one arm: lean-React,
  and it is the product line rather than a contender.
- **The tournament ended on product direction, not on measurement.** Arm 2 *met*
  its hard gate — controlled-restore on the 100-cell grid in real Chromium, 920
  tests / 5,743 assertions / 0 failures, no `act()`, no `flushSync`, no rAF. It
  is dropped because the operator does not want an own renderer, not because it
  could not build one. Its bench tree was retired afterwards (`rf2-m6if4`).
- **What the ruling did not decide.** The arm-versus-null question stands: the
  Adapter-Prime null control still rides every measurement, adapters-only is
  still a *successful* outcome, and the kill criteria still bite. The P2 ruling
  is now a two-way choice, not a three-way one.
- **What survives from Arm 2:** the laziness finding (a `for`'s reads happen when
  the children are walked, not when the body returns — a Surface B property that
  applies to the adapter directly); the structural observation that HD-002(a)'s
  ownership state machine is near-vacuous when the body runs inside the commit
  and **live** when React owns the render phase, which puts the candidate-ledger
  tripwire fully in force for the adapter; and the controlled-restore witness as
  evidence of what correct input behaviour looks like.

Evidence and full text on `rf2-2rtt6`. Design record updated in
`docs/design/hicasso/` (`rf2-m6if4`).

### Addendum, 2026-07-31 — Surface B (the ambient collector) is the only ergonomically acceptable read surface; HD-002's outcome 2 is closed

**Operator ruling (Mike), verbatim:** *"Only surface B is acceptable from an
ergonomics point of view"* (citing
`docs/design/hicasso/draft-guide/02-views-and-reads.md`), **reinforced,
verbatim:** *"use-subs (Surface A) is not sufficiently ergonomic for a
programmer to use."*

Recorded here per rule 2, and as an addendum rather than an edit per EP-0009
rule 3 — the Resolved Decisions summary line for HD-002 above ("sub-read
tiers (grouped default / collector challenger / scalar comparator;
both-fail→null)") is left as written, because it is the proposal that was
accepted and the adjudication it describes did run.

- **What is ruled.** Of the two real candidates the draft guide sets out,
  **Surface B — the ambient collector**, where `sub` is an ordinary function
  call usable inside a `when`, inside a `for`, and inside an inlined helper,
  with one fixed runtime hook collecting reads and the runtime diffing the
  edge set after the body returns — is **the only ergonomically acceptable
  authoring surface**. **Surface A (grouped `use-subs`)** — one hook at the
  top of the body receiving the complete query collection before the body
  runs — is **ergonomically rejected**, judged **below the usability bar**
  for the programmer this library exists to serve, not merely less pleasant
  than Surface B.
- **What is *not* ruled, and must not be read into this.** The collector's
  correctness and cost gates are untouched and still bind: the standing
  **tripwire that overrides the clock** (the first time correctness requires
  a candidate ledger or generic post-render dependency reconciliation, the
  collector dies however good its numbers look); the **four pre-registered
  adjudication clauses** ((a) the render/commit ownership state machine,
  (b) the exact allowed edge-diff operation vs. the forbidden ledger class,
  (c) two pre-registered strategy hypotheses each counted only by a
  benchmarked commit, (d) the survival metric); and
  `docs/design/hicasso/hd-002-adjudication.md` stands as written. This
  ruling was decided on **ergonomics, not on HD-002's own benchmarked-win
  condition** — no bar row for the survival metric existed at ruling time
  (H1 implemented, H2 untried), so grouped is superseded without having lost
  a bench it was never run against; the distinction is carried in
  `implementation/freehand/test/re_frame/bench/hicasso/arm1/runtime.cljs`'s
  docstring, next to the code that implements the ruled surface.
- **The consequence.** HD-002's outcome 2 — "collector loses and grouped
  stays the default" — is **closed**. If the ambient collector trips its
  ledger tripwire or fails its survival metric, the outcome is **null** (no
  Hicasso read surface ships), or a mechanism not currently on the table
  must earn its way in. Shipping grouped `use-subs` as the product read
  surface is ruled out on ergonomics, independently of any measurement.

Evidence and full text on `rf2-2rtt6.1` ("RULING — HD-002 FORK, ERGONOMICS
HALF"). Design record: `docs/design/hicasso/decisions.md` HD-002 (superseded
blockquote),
`docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md` §2.

### Addendum, 2026-08-04 — SSR + hydration is required Hicasso scope; the programme rides Spec 011

**Operator ruling (Mike), verbatim:** *"SSR is an important part of re-frame2.
If hicasso is to be the re-frame native view layer then it has to be used with
SSR"* — **and, earlier the same day, verbatim:** *"hicasso is useless unless it
does SSR."*

Recorded here per rule 2, and as an addendum rather than an edit per EP-0009
rule 3 — the wave text and the Non-goals section below are left as written.
HD-020(d) ("SSR is out of v0") carried its own reopening clause — "at product
phase (SSR, richer boundary API) by ordinary ruling" — and this is that ruling,
taken by the operator. The design record carries the matching HD-020 addendum
(`docs/design/hicasso/decisions.md`).

- **The requirement set, R0–R8.**
  - **R0 — one SSR story.** Hicasso participates in re-frame2's *existing*
    Spec 011 (`spec/011-SSR.md`) mechanism — the payload policy, the
    `#__rf_payload` EDN embed, the `hydrate!` boot helper and the reserved
    `:rf/hydrate` db adoption before first render, the hydration-mismatch
    machinery, `ssr-ring` as the HTTP host — **never a parallel Hicasso-only
    mechanism**.
  - **R1 — pure server render.** A server render is produced purely from a db
    snapshot.
  - **R2 — hydration adopts.** Zero hydration mismatch, server node identity
    preserved (React adopts the server DOM, never re-creates it), and exactly
    one body pass.
  - **R3 — reactivity adopted on hydrateRoot's schedule.** Subscriptions come
    live on React's own hydration schedule; the settle horizon is best-effort,
    never a caller contract.
  - **R4 — the live page.** Events and the controlled door work
    post-hydration to the `rf2-2rtt6.67` equivalence standard.
  - **R5 — the `defhost` SSR policy is activated.** HD-011's declared
    placeholder becomes the real `:ssr` option.
  - **R6 — the ledger discipline holds server-side.** HD-002's discipline is
    unbroken on the server: a server render is an abandoned render, leaving
    zero durable registration.
  - **R7 — scope is stated known-losses style.**
  - **R8 — witnesses carry SHA + repro commands.**
- **Non-goals, explicitly:** streaming, RSC, islands/partial hydration, no-JS
  progressive enhancement, SEO metadata, and SSR-speed-as-bar — HD-012 and the
  Motivation's "fast applications, not fast SSR" line stand unchanged.
- **The sitting linkage — one sitting, no separate gate.** The X1–X5 spike
  witness on the hydrated dogfood screen (`rf2-2rtt6.87`) is a **required
  feasibility input** to the P2/HD-013 sitting, and the production-server-arm
  choice — JVM structural walk (the default direction to be priced) vs Node
  sidecar (`rf2-2rtt6.88`) — is priced and ruled **at that same sitting**.
- **A stale blocker, corrected on the record.** The 2026-08-04 audits' claim
  that the spine's `useSyncExternalStore` lacks `getServerSnapshot` — so
  "hydration throws by construction" — was stale, refuted independently three
  times: the spine passes its snapshot fn as both the 2nd and 3rd arguments
  (`implementation/core/src/re_frame/substrate/spine.cljs:3031`); the arm-1
  shells do likewise
  (`implementation/freehand/test/re_frame/bench/hicasso/arm1/runtime.cljs:1540`,
  `:1595`); and the `{:hydrate? true}` adoption-reporter tier ships with DOM
  tests (`implementation/ssr/src/re_frame/ssr/boot.cljc:202-213`). **No core
  code change is needed for hydration snapshots.**

Status is honest tense: the hydration door, the `defhost` `:ssr` policy, the
Node render entry and the spike witness are dispatched as `rf2-2rtt6.84`–`.87`;
nothing has landed at the time of this addendum. Evidence and full text on
`rf2-2rtt6` and `rf2-2rtt6.83`.

### Addendum, 2026-08-04 — K5, the ergonomics kill, is withdrawn; nothing replaces it and no identifier is renumbered

**Operator ruling (Mike), 2026-08-04.** K5 — the criterion whose row read
`> ~8 public concepts or > ~8 guide pages to ship CRUD` — is removed as an
operative kill criterion. No verbatim text of the ruling is on the record; its
authoritative statement is the dated note beside the kill-criteria table in
`docs/design/hicasso/validation.md`.

Recorded here per rule 2, and as an addendum rather than an edit per EP-0009
rule 3 — the wave-0 line above ("the budgets, kill criteria K1–K7") is left as
written, and stays true on its own terms: it is a historical statement of what
wave 0 pre-registered, and nothing was renumbered.

- **What is withdrawn.** The one criterion that could stop this programme on
  ergonomics rather than on measurement. **No criterion replaces it**, and K6
  and K7 keep their identifiers, so every citation of them elsewhere resolves.
- **What is not.** Consumer code from day one, the diff judgement and the
  public-door-only witness tests all stand (`charter.md` item 4, which restated
  the criterion inline without naming it, is superseded in place with its
  sentence kept). Every by-name "against K5" argument in the design record is
  left intact and reads as historical — it says why a shape was chosen while the
  criterion stood.

Design record: `docs/design/hicasso/validation.md` (the dated note),
`charter.md` item 4, and the note heading the HD-022 … HD-026 block in
`decisions.md`. This addendum answers `rf2-825ft`.

## Open Issues

1. The donor-gate ruling (delegated advisory; expected days after P0 publishes).
2. The P2 fork ruling (operator; end of the tournament). **Narrowed 2026-07-31**
   by the addendum above: the choice between the two Hicasso arms is settled, so
   what remains is the surviving arm against the null. **Amended 2026-08-04** by
   the SSR addendum above: the sitting additionally takes the X1–X5 SSR spike
   witness (`rf2-2rtt6.87`) as a required feasibility input, and prices + rules
   the production-server-arm choice (`rf2-2rtt6.88`) at the same sitting — one
   sitting, no separate gate.
3. The HD-002 read-mechanism adjudication (resolved by P1 instrumentation).
   **Narrowed 2026-07-31** by the addendum above: the ergonomics half is
   decided — Surface B (the ambient collector) is the only acceptable read
   surface, and grouped `use-subs` is not a viable fallback. The cost/
   correctness half is unaffected and still runs under the unwaived tripwire
   and the four adjudication clauses.
4. The residual W1 dominance-attribution bead (does not gate the baseline).

## Graduation

Standards-track terminal states, made explicit per exit:

- **Go**: v0 lands; the winning public contract graduates narrowly — the
  `spec/004` view-family re-homes from Freehand-voiced text to Hicasso, and
  EP-0036 is marked superseded by this EP for the view-layer surface; guide
  impact is assessed then (the wave-0 draft guide is replaced by real
  `docs/core/…` pages under the sample gates). This EP goes `final` when v0 and
  that graduation land.
- **Stop (donor gate or P2)**: this EP goes `final` with the Resolution line
  amended to record the null outcome and its evidence; no spec change; open
  programme beads are closed with the ruling; the adapters are affirmed as the
  answer. Both exits are successful deliveries of a *decided* question with
  receipts.

## Non-goals

A compiler or analyzer; a second authoring mode; a ViewCell-class per-boundary
runtime; a component-local state system; a Hicasso context abstraction;
batteries, overlay, SSR-identity, or devtools-glass programmes before the P2
gate; any absorption/migration programme over the donor trees; SSR/JVM render
speed as a bar input. The anti-regression fence and banned vocabulary in
charter §Constraints are normative for every brief filed under this EP.

## References

- Design record: `docs/design/hicasso/` (README · charter · decisions ·
  architecture · validation · authoring; `studio/` and `draft-guide/` minted by
  wave-0/1 workers).
- Predecessor EPs: [EP-0030](EP-0030-the-compiled-view-substrate-program.md),
  [EP-0036](EP-0036-the-freehand-view-substrate-programme.md).
- Measured record: `docs/design/freehand/studio/` (incl.
  `bulk-rerender-where-the-time-goes.md` and the fitness harness
  `fitness-harness.md`).
- Closed instrument-spec donor beads: rf2-mapni, rf2-m7xs7, rf2-ssn1o
  (do-not-refile); the retired Freehand standard rf2-lbs3y.
- The operator-owned standard bead and the EP-0038 epic bead (ids recorded on
  the epic at filing).
