# Hicasso dispositions — coverage classification and per-surface server policy

Phase 0 owes two ledgers, and this document is both of them: the classification of every
[specification section 7](specification.md#7-complete-use-case-coverage) use case into the layer that answers it, and a
server/hydration disposition for every proposed public surface. The second ledger is owed whether or not the optional
Node service is ever activated — the [decision brief](decision-brief.md) states the rule as "the server/hydration
contract is core, per public surface", and [design law Language 8](lanes/design-laws.md#language-and-interop) repeats it.

This document classifies and dispositions. It does not decide policy: the
[canonical SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) owns the two
policies and the witness obligations for each surface class, the specification owns capability placement, and
[`completeness-audit.md`](lanes/completeness-audit.md) owns the proof suites. What is new here is the per-surface
accounting: a unique inventory id for every public surface, so that a surface cannot be added, shipped, or forgotten
without a row.

## How to read this document

Three conventions carry the whole document.

**Every name here is provisional; every id is not.** API spellings freeze at their named witnesses (ordinary surfaces
after Phase 2, host and native surfaces after Phase 3), and open naming questions live in
[`naming-ledger.md`](naming-ledger.md). Inventory ids are permanent from the moment they are minted. A renamed surface
keeps its id; a new surface takes the next free id; ids are never reused and never renumbered.

**Nothing in this document is a claim of evidence.** Phase 0 precedes every witness. The coverage table records where
each job is answered, not that it has been proved; the surface table records what each surface's server behavior is
required to become, not what it does. Both tables carry an explicit status column, and both say "unwitnessed" wherever
that is the truth.

**The default is refusal with recovery.** Where server behavior is not yet proven, the operative disposition is
Client-only: refuse at the declaration source and name a deterministic fallback. This is what keeps the Phase 4 witness
matrix bounded. Section [2.4](#24-the-default-rule-and-how-a-row-is-upgraded) states the rule and the only route out of
it.

## 1. Coverage classification — specification section 7

Each of the twenty rows in [specification section 7](specification.md#7-complete-use-case-coverage) is assigned a
**primary home** from the five permitted layers — core, optional module, recipe, host escape, didactic refusal — plus the
additional surfaces that complete the answer and the refusals that bound it. The primary home is where an application
finds the answer first; the additional-surface column carries the rest.

This table is the normative record of that classification. [`requirements-mine.md`](requirements-mine.md) repeats each
job's layer in its Home column so that a ledger row reads on its own, and this table governs wherever the two disagree.

No row's primary home is a didactic refusal. That is a finding, not an omission: section 7 exists precisely because every
recurring job has a maintained answer somewhere, so refusals in this table appear as bounds on an answer rather than as
the answer itself. The refusals that stand alone are catalogued in
[`completeness-audit.md`](lanes/completeness-audit.md) and
[design law Economics 5](lanes/design-laws.md#economics-and-scope).

### 1.1 Classification table

| Use case (section 7) | Primary home | Additional surfaces | Refusal or non-goal that bounds it | Planned witness | Source |
|---|---|---|---|---|---|
| Ordinary pages, conditional UI, dynamic lists | Core | None | No compiled Hiccup mode, no automatic specialization, no execution-mode flag | Claimed — `rf2-hic-025` and `rf2-hic-074` | 7; [3.3](specification.md#33-dynamic-composition-is-a-feature) |
| Forms and controlled fields | Core (controlled law and revision) | Optional forms module; forms recipes; buffered-draft helper at its second caller | Buffered drafts, touched/submit-attempt validation and mutation status stay out of the boundary shell | Claimed — `rf2-hic-078` | [4.2](specification.md#42-controlled-fields) |
| Validation and async normalization | Core (derived subscriptions and event data) | Validation-gating and settle-merge recipes | No adapter validation framework | Claimed | [4.2](specification.md#42-controlled-fields); 7 |
| Routing and navigation | Optional module (routing integration) | Dirty-leave, scroll-restoration and focus-on-route recipes | No router in core; routing stays small | Claimed | 7; [`use-cases.md`](lanes/use-cases.md) |
| Async resources and mutations | Core (the re-frame2 resource/event model, outside the adapter) | Mutation-status and settle-merge recipes; committed-read resource demand as a gated spike | No automatic fetching or Suspense inferred from reads; no second per-read ledger | Claimed | 7; [`completeness-audit.md`](lanes/completeness-audit.md) |
| Errors | Core (`h/error-boundary`) | Expected failures remain data | No second exception model | Claimed — `rf2-hic-074` | 7; [4](specification.md#4-target-programming-model) |
| Foreign React ecosystem and native hot work | Host escape (`h/defhost`, raw element, `h/as-element`) | Optional Hicasso-native namespace; library-specific wrappers; optional UIx route; outward bridge | No general React wrapper language, no UIx clone, no host-schema language | Claimed | [4.3](specification.md#43-host-interop); [native-boundary law](lanes/design-laws.md#native-boundary) |
| Large collections | Core (read topology and keys) | Blessed foreign virtualizer recipe | No keyed-list maintenance machinery before a red bulk verdict | Claimed | [Rung 2](specification.md#rung-2--tune-hicasso-topology); 7 |
| Imperative SDKs | Host escape (declared host ownership) | Acquire/release recipe | No lifecycle or effect DSL | Claimed | [4.3](specification.md#43-host-interop) |
| Overlays and focus | Core (native HTML where possible) | Optional popover/modal module on top-layer primitives | No overlay manager in core | Claimed | 7; [Phase 5](specification.md#phase-5--decide-differentiators-and-add-high-value-optional-products) |
| Motion and high-rate input | Optional module (presence posture) | Native host-local animation and drag state | No animation system in core; high-rate mechanics stay host-private | Claimed — `rf2-hic-053` | 7; [6](specification.md#6-performance-contract) |
| Code splitting | Core (small `React.lazy` boundary-ABI bridge) | Hiccup-aware Suspense/error host | No late-bound view-id registry | Claimed | 7; [`completeness-audit.md`](lanes/completeness-audit.md) |
| Multiple frames and roots | Core (shared frame context with isolated ownership) | Explicit independent root when isolation requires one | An independent root is an isolation choice, never a performance tier | Claimed | [Rung 5](specification.md#rung-5--native-screen); 7 |
| Suspense and Activity | Host escape (React-owned lifecycle behind a declared host) | — | No Activity DSL; compatibility only | Claimed | 7; [Activity witness](lanes/react-compatibility-notes.md#activity-lifecycle-witness) |
| SSR and hydration | Core (per-surface policy — section [2](#2-public-surface-ssrhydration-dispositions) of this document) | Optional Node service for a named caller | No JVM twin, no second renderer, no hydration-free inference | Claimed | 7; [8](specification.md#8-modern-react-compatibility); [Language 8](lanes/design-laws.md#language-and-interop) |
| Accessibility | Core (semantic Hiccup and native controls) | Structural a11y assertions plus browser focus tests | No accessibility subsystem | Claimed — `rf2-hic-049` | 7 |
| i18n and theming | Recipe (ordinary data, classes, CSS variables, context through hosts) | — | No adapter subsystem; parts registries and tree rewriting deferred | Claimed — `rf2-hic-025` | 7; [`use-cases.md`](lanes/use-cases.md) |
| Testing | Core developer product (the supported test namespace, L0–L4) | Mounted DOM and browser tiers | No shallow renderer, no fake hooks runtime, no retired test renderer | Claimed — `rf2-hic-020` | [9](specification.md#9-testing-as-a-product-surface) |
| Diagnostics | Core developer product (versioned evidence projection) | Xray/Pair causal and heat views | No parallel graph or history system; no production sentinels | Claimed — `rf2-hic-023` | [10](specification.md#10-xray-and-runtime-evidence) |
| Migration | Developer product (reporter and explicit refusal classes) | Shadow DOM/intent comparison; cautious codemod only afterwards | No rewriting codemod before reporter and shadow evidence | Claimed — `rf2-hic-055` | 7; [Phase 5](specification.md#phase-5--decide-differentiators-and-add-high-value-optional-products) |

The Source column cites specification sections by number; a bare number is a section of
[`specification.md`](specification.md). A bead id in the Planned witness column names the bead that *owns* that row's
proof; it is never a statement that the proof exists. Section [1.2](#12-rows-without-a-complete-planned-witness) defines
the two values that column takes.

### 1.2 Rows without a complete planned witness

No row above currently stands as Gap. The distinction that column draws is deliberate and narrow, and it matters just as
much when the count reaches zero:

- **Claimed** means the programme names a bead that intends to witness the row. It does not mean the witness exists, and
  it does not mean the bead's acceptance covers the whole row.
- **Gap** means that even after the programme's final coverage audit completes, the row can still lack the proof
  section 7 requires — because no bead owns that proof, or because the owning bead's acceptance does not reach it.

Nine rows stood as Gap when this document was first written, on the evidence of section 1.3 of the bead-set review —
`codex/beads-review.md`, review-staging material in the operator-local set, deliberately not published in this tree.
That review is a snapshot of one moment in the tracker, and the tracker has since moved: every proof it found unowned
is now named in the deliverables of a specific bead. The reconciliation is recorded here rather than silently deleted,
because the bar for the change is the strict one [section 3](#3-append-protocol-and-ownership) sets — the bead must
*actually own* the missing proof, not merely sit adjacent to the row — and the next reader is entitled to re-check the
arithmetic:

- **Ordinary pages, conditional UI, dynamic lists** — `rf2-hic-025` builds the RealWorld-class flow (routing, keyed list,
  article edit, async mutation, controlled fields, error region), and `rf2-hic-074` extends it with the pagination and
  runtime-selected content the review found unowned.
- **Forms and controlled fields** — `rf2-hic-078` owns the four-field editor and the 100-cell grid as public-package
  applications on public namespaces only, which is the form section 7 requires and the explanatory pages were not.
- **Errors** — `rf2-hic-074` owns the nested error region, and its acceptance names the path: inner region catches, outer
  survives, retry works.
- **Motion and high-rate input** — `rf2-hic-053` folds the frame-budget measurement into the transition witness and into
  its acceptance, alongside interruption, rapid toggle and teardown.
- **Accessibility** — `rf2-hic-049` owns keyboard conduct across the slice, virtualizer and overlay surfaces, and depends
  on the beads that build them; that dependency is the ordering edge and the later integration run the review found
  missing.
- **i18n and theming** — `rf2-hic-025` owns the runtime locale and theme-change witness: a live switch re-renders
  correctly with strings and tokens as ordinary data, and no adapter subsystem.
- **Testing** — `rf2-hic-020` owns the L0 public contract and the L1 codec/intent/native-expansion surface, with positive
  and sabotage controls for every tier it delivers; the mounted tier's controls are `rf2-hic-027`'s.
- **Diagnostics** — `rf2-hic-023` owns the privacy projector as a witnessed contract, proved by a seeded sensitive value
  that must not leave unredacted.
- **Migration** — `rf2-hic-055` requires three representative repositories — the in-repo census corpus plus two named
  external re-frame v1 applications — and states that the in-repo-only fallback does not satisfy section 7.

Every one of those beads is open and not one of those witnesses exists. An owner is not evidence: what changed is that
each proof is now owed by name, and being owed by name is the whole of what Claimed asserts.

Closing a gap is not this document's work. The classification is only honest if it says which rows are answered on paper
and unproved in fact; making them proved belongs to the beads that own the coverage matrix.

## 2. Public-surface SSR/hydration dispositions

Every proposed public surface — view, read, root, host, escape, native-tier form, and optional module — has a row here
with a unique inventory id. The two policies are the canonical ones:

- **Render** — produce deterministic React server output from an immutable request frame or snapshot, and support
  matching hydration.
- **Client-only** — refuse server use at the declaration source and name an explicit deterministic fallback or recovery.
  Returning a silent `nil` is not a policy.

Each row carries both a **target policy** and an **operative disposition**. The target policy is the surface class's
default in the [canonical matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) — what the
surface is expected to become once its behavior is proved. The operative disposition is what the surface is entitled to
do *today*, and today it is Client-only for every row without exception, because no server witness exists for any
Hicasso surface yet. The operative column is therefore the upgrade slot, owned by the per-surface SSR/hydration witness
bead `rf2-hic-046`: see [2.4](#24-the-default-rule-and-how-a-row-is-upgraded).

### 2.1 Surface inventory and dispositions

| Id | Public surface | Source | Canonical matrix class | Target policy | Operative disposition (today) |
|---|---|---|---|---|---|
| HS-01 | `h/defview` boundary | 4 | `h/defview` with `h/sub` | Render | Client-only — refusal until rf2-hic-046 |
| HS-02 | `h/sub` read during a server render | 4 | `h/defview` with `h/sub` | Render | Client-only — refusal until rf2-hic-046 |
| HS-03 | `h/handler` and literal intent vectors | 4.1 | Intrinsic/fragment Hiccup | Render | Client-only — refusal until rf2-hic-046 |
| HS-04 | Intrinsic element head, including SVG and custom elements | 4 | Intrinsic/fragment Hiccup | Render | Client-only — refusal until rf2-hic-046 |
| HS-05 | Fragment head | 4 | Intrinsic/fragment Hiccup | Render | Client-only — refusal until rf2-hic-046 |
| HS-06 | Props map and canonical slot naming | 4 | Intrinsic/fragment Hiccup | Render | Client-only — refusal until rf2-hic-046 |
| HS-07 | Reserved data vocabulary: event value, checked value, explicit prevention, controlled revision | 4 | Controlled DOM fields | Render | Client-only — refusal until rf2-hic-046 |
| HS-08 | Controlled DOM fields as a class (per-control rows in [2.3](#23-per-control-and-dom-conformance-dispositions)) | 4.2 | Controlled DOM fields | Render | Client-only — refusal until rf2-hic-046 |
| HS-09 | `h/error-boundary` | 4 | `h/error-boundary` | Render | Client-only — refusal until rf2-hic-046 |
| HS-10 | `h/mount!` | 4 | Root/frame provider | Client-only (client root creation; recovery is the server render entry plus `h/hydrate!`) | Client-only — refusal until rf2-hic-046 |
| HS-11 | `h/hydrate!` | 4 | Root/frame provider | Client-only (the adoption half of every Render row) | Client-only — refusal until rf2-hic-046 |
| HS-12 | `h/render!` | 4 | Root/frame provider | Client-only | Client-only — refusal until rf2-hic-046 |
| HS-13 | `h/unmount!` | 4 | Root/frame provider | Client-only | Client-only — refusal until rf2-hic-046 |
| HS-14 | Root and frame-provider element, including `identifierPrefix` | 4 | Root/frame provider | Render | Client-only — refusal until rf2-hic-046 |
| HS-15 | Attribute-merge helper (public only if a witness needs it) | 4 | Intrinsic/fragment Hiccup | Render | Client-only — refusal until rf2-hic-046 |
| HS-16 | `h/defhost` declaration | 4.3 | Declared host | Client-only until the host declaration selects Render | Client-only — refusal until rf2-hic-046 |
| HS-17 | Declared ReactNode positions and named content slots | 4.3 | Declared host | Client-only until the host declaration selects Render | Client-only — refusal until rf2-hic-046 |
| HS-18 | Render-prop callback lowered through `h/as-element` | 4.3 | Declared host | Client-only until the host declaration selects Render | Client-only — refusal until rf2-hic-046 |
| HS-19 | Raw React element head | 4 | Raw React or opaque component | Client-only until classified by an enclosing view or host policy | Client-only — refusal until rf2-hic-046 |
| HS-20 | Portal helper | 4.3 | Portal helper | Client-only | Client-only — refusal until rf2-hic-046 |
| HS-21 | Outward bridge: a Hicasso view under a native React parent | 4.3 | Root/frame provider and outward bridge | Render | Client-only — refusal until rf2-hic-046 |
| HS-22 | `React.lazy` boundary-ABI bridge and Hiccup-aware Suspense host | 7 | Lazy/Suspense/error boundary | Client-only until every server branch and the selected React server API are declared | Client-only — refusal until rf2-hic-046 |
| HS-23 | Activity-hosted subtree | 8 | Declared host | Client-only | Client-only — refusal until rf2-hic-046 |
| HS-24 | `n/$` intrinsic form | [native surface](lanes/ergonomics-api.md#optional-native-surface) | Intrinsic `n/$` | Render | Client-only — refusal until rf2-hic-046 |
| HS-25 | `n/$` component-headed form | [native surface](lanes/ergonomics-api.md#optional-native-surface) | `n/defcomponent` and component-headed `n/$` | Client-only until the component declaration selects Render | Client-only — refusal until rf2-hic-046 |
| HS-26 | `n/props` marker | [n/$ grammar](lanes/ergonomics-api.md#provisional-n-grammar) | Intrinsic `n/$` | Render | Client-only — refusal until rf2-hic-046 |
| HS-27 | `n/defcomponent` | [native surface](lanes/ergonomics-api.md#optional-native-surface) | `n/defcomponent` | Client-only unless the declaration selects `:render` | Client-only — refusal until rf2-hic-046 |
| HS-28 | `n/use-sub` | [native surface](lanes/ergonomics-api.md#optional-native-surface) | `n/defcomponent` | Client-only until its component selects Render | Client-only — refusal until rf2-hic-046 |
| HS-29 | `n/use-frame` | [native surface](lanes/ergonomics-api.md#optional-native-surface) | `n/defcomponent` | Client-only until its component selects Render | Client-only — refusal until rf2-hic-046 |
| HS-30 | Native ABI helpers: memo, lazy, ref, and both embedding directions | [native surface](lanes/ergonomics-api.md#optional-native-surface) | Memo/lazy/ref helpers | Client-only until the component declaration selects Render | Client-only — refusal until rf2-hic-046 |
| HS-31 | Optional forms module | 4.2 | Optional module | Client-only until its module contract selects Render | Client-only — refusal until rf2-hic-046 |
| HS-32 | Optional overlay module (popover and modal) | 7 | Optional module | Client-only until its module contract selects Render | Client-only — refusal until rf2-hic-046 |
| HS-33 | Optional motion and presence module | 7 | Optional module | Client-only until its module contract selects Render | Client-only — refusal until rf2-hic-046 |
| HS-34 | Optional routing-integration module | 7 | Optional module | Client-only until its module contract selects Render | Client-only — refusal until rf2-hic-046 |
| HS-35 | Committed-read resource-demand boundary (conditional on its graduating verdict) | 7 | Resource-demand boundary | Client-only until its module contract selects Render | Client-only — refusal until rf2-hic-046 |

Three notes on the target-policy column, so no reader mistakes a Client-only target for a defect:

1. **The root lifecycle functions are Client-only by nature, not by default.** `h/mount!`, `h/hydrate!`, `h/render!` and
   `h/unmount!` are client entry points; none of them emits server bytes. Their obligation is to refuse a server call at
   source with the correct recovery, and — for HS-11 — to adopt server bytes with a matching `identifierPrefix` across
   two simultaneous roots without a process-global adoption window. The Render obligation on the tree they mount belongs
   to HS-14.
2. **Client-only-until is a declaration gate, not a rejection.** HS-16 to HS-19 and HS-25 to HS-30 become Render when
   the host or component declaration selects it and the corresponding witness passes. The default exists so an
   undeclared foreign or native component cannot silently enlarge the matrix.
3. **A Client-only row still owes a witness.** The refusal must be shown to fire, at source, with its recovery — an
   unproved refusal is not a disposition. The
   [canonical matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) states the required states
   for both policies.

### 2.2 Public surfaces with no server-render behavior

These are public surfaces of the product but not of the rendered tree, so they carry no Render/Client-only policy. They
are inventoried anyway, so that "every public surface has a disposition" is a countable claim rather than a claim about
the surfaces someone remembered.

| Id | Public surface | Source | Disposition |
|---|---|---|---|
| HS-36 | Supported test namespace | 9 | Development-only; absent from production and server bundles; proves its own erasure |
| HS-37 | Xray and Pair evidence projection | 10 | Development-only; versioned, privacy-projected, loss-accounted; no production sentinels |
| HS-38 | clj-kondo exports and optional dev schemas | [editor ergonomics](lanes/ergonomics-api.md#editor-and-diagnostic-ergonomics) | Development-only; no production cost; no whole-program analysis implied |
| HS-39 | Bounded Node/React SSR service | [Phase 5](specification.md#phase-5--decide-differentiators-and-add-high-value-optional-products) | Caller-gated deployable service, not a view surface; its operational contract is separate from every surface policy above |

HS-39 is the only genuinely optional part of the server story. Its absence changes nothing in
[2.1](#21-surface-inventory-and-dispositions): a client-only application still owes every surface a server policy and a
hydration contract.

### 2.3 Per-control and DOM-conformance dispositions

This section is a distinct axis from [2.1](#21-surface-inventory-and-dispositions). HS-08 dispositions controlled fields
as a class for *server rendering*; this table dispositions each control type for *support or refusal* under the
controlled-field law, across the three supported browser engines.

The roster below is the countable one: every control and DOM conformance case named in
[specification section 4.2](specification.md#42-controlled-fields) and in the control-conformance programme, which is
owned by `rf2-hic-040`. No control may be silently unsupported, so a row that never acquires a policy is itself a
failure.

| Control or conformance case | Support-or-refusal policy | Witness |
|---|---|---|
| Text input | Owed | Owed |
| Textarea | Owed | Owed |
| Checkbox | Owed | Owed |
| Radio | Owed | Owed |
| Select (single) | Owed | Owed |
| Select (multiple) | Owed | Owed |
| File input | Owed | Owed |
| Number input | Owed | Owed |
| Date input | Owed | Owed |
| Range input | Owed | Owed |
| Contenteditable | Owed | Owed |
| Composition and IME | Supported. The recurring cross-engine witness is the composition event **sequence**; real native-IME conduct is dispositioned per engine in the block below | `implementation/hicasso/testbed/spec.cjs` on all three engines (`rf2-hic-016`); native ranges on Chromium by `implementation/freehand/test/re_frame/bench/hicasso/ime_run.cjs` |
| Caret and selection preservation | Owed | Owed |
| Blur after unmount | Owed | Owed |
| Async normalization | Owed | Owed |
| Autofill | Owed | Owed |
| Form reset | Owed | Owed |
| FormData extraction | Owed | Owed |
| SVG attributes | Owed | Owed |
| Custom elements | Owed | Owed |

Every remaining cell reads Owed because Phase 0 precedes the conformance run. The rows exist now so the run has a fixed
roster to fill and cannot quietly shrink; `rf2-hic-040` replaces the cells in place and adds a row only for a control
case this roster missed.

**Native IME, per engine (`rf2-hic-016`).** The Composition-and-IME row above is filled in two parts because the
evidence has two tiers, and collapsing them would claim more than is held. The recurring witness is the composition
event **sequence** — `compositionstart`, `beforeinput` and `input` carrying `isComposing`, `compositionend`, dispatched
at the real node through real React — and it is green on all three engines in the required `cljs-hicasso-controlled`
gate. It reaches the carve-out, the draft shadow and React's end-of-event restore; it does not reach the browser's
composition **range**, because `Input.imeSetComposition` is a CDP method and CDP is Chromium's protocol.

Per the operator ruling of 2026-08-10, that synthetic sequence **is** the ratified recurring witness, and real
native-IME conduct beyond Chromium is settled once by a bounded manual session rather than by automation — the
checklist is [`../native-ime-manual-witness.md`](../native-ime-manual-witness.md). Engine builds are those the pinned
Playwright 1.59.1 installs.

| Engine | Playwright build | Synthetic sequence | Native IME | Session date |
|---|---|---|---|---|
| Chromium | `chromium-1217` (147.0.7727.15) | Green | **Witness-verified** — real composition ranges driven over CDP by `bench/hicasso/ime_run.cjs` | n/a — automated |
| Firefox | `firefox-1511` (148.0.2) | Green | **Pending the manual session** | — |
| WebKit | `webkit-2272` (26.4) | Green | **Pending the manual session** | — |

`rf2-hic-016` closes when the two pending cells are filled from that session, not before. Anything the session finds
strange becomes a bead.

### 2.4 The default rule and how a row is upgraded

**The rule.** Where server behavior is not yet proven, the disposition is refusal with recovery. A surface may not be
described as rendering because it plausibly would, because its class default says Render, or because a bead intends to
prove it. This is what keeps the Phase 4 witness matrix bounded: the matrix has to close over what is claimed, so
nothing is claimed before it is shown.

**The upgrade.** One route only, owned by `rf2-hic-046`. A surface moves from Client-only to Render when a witness
proves, for that surface:
deterministic server bytes from an immutable request snapshot; matching hydration; deliberate mismatch attributed to
source; two simultaneous hydrating roots with a stable `identifierPrefix`; and exact cleanup on unmount. Rows that read
or demand resources additionally prove no duplicate acquisition. The witness id then replaces "witness owed" in the
operative column and the policy is rewritten to Render.

**The downgrade.** A surface whose witness later fails returns to Client-only in the same edit that reds the witness. A
row is never left claiming a policy its evidence no longer supports.

## 3. Append protocol and ownership

This file is written once and amended many times, by beads that do not otherwise share a surface. The protocol keeps
those amendments from colliding.

| Section | Owner after Phase 0 | Permitted amendment |
|---|---|---|
| [1.1](#11-classification-table) | The coverage-matrix owner | Change a Gap to Claimed only when a bead actually owns the missing proof; never change a Home without a specification change behind it |
| [1.2](#12-rows-without-a-complete-planned-witness) | The coverage-matrix owner | Remove a bullet when its gap is genuinely owned; add one when a new gap is found |
| [2.1](#21-surface-inventory-and-dispositions) | Per-surface SSR/hydration witnesses (`rf2-hic-046`) | Rewrite the operative-disposition cell of an existing row; append a row for a surface created after Phase 0 |
| [2.2](#22-public-surfaces-with-no-server-render-behavior) | Per-surface SSR/hydration witnesses (`rf2-hic-046`) | Append a row for a new non-rendering public surface |
| [2.3](#23-per-control-and-dom-conformance-dispositions) | Control/DOM conformance (`rf2-hic-040`); the native-IME block within it is `rf2-hic-016`'s under the 2026-08-10 ruling | Fill the policy and witness cells; append a row for a missed control case. `rf2-hic-040` fills the roster around the native-IME block rather than through it — the two tiers of composition evidence are kept apart deliberately |
| [2.4](#24-the-default-rule-and-how-a-row-is-upgraded) | Product operator | Change the rule itself |

Two constraints apply to every amendment. **Amend in place; do not restructure.** Two beads in the same wave write into
sections [2.1](#21-surface-inventory-and-dispositions) and
[2.3](#23-per-control-and-dom-conformance-dispositions) respectively, and they only stay out of each other's way if both
append into their named section rather than rewriting the document. **Adding a public surface adds an id.** A surface
that reaches the facade without a row here has escaped the inventory, and the Phase 4 exit — every inventory id pointing
at an applicable green row — silently stops meaning anything.
