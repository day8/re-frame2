# EP-0036: The Freehand View-Substrate Programme

Status: accepted
Type: standards-track
Created: 2026-07-22
Resolution: accepted 2026-07-22 (programme ratification — decision register D001–D021 ratified and folded)

## Abstract

This is the umbrella EP of the Freehand programme. It records **one decision
surface**: re-frame2's view layer is **one re-frame-native substrate,
Freehand** — public namespace `re-frame.freehand`, documented alias `v` — with
**two required execution modes over one semantic model**. The interpreted mode
is the paved path: views are ordinary Clojure functions of one props map
returning hiccup, `(sub [:q])` returns a plain value, and event intent lives in
the tree as data. The compiled mode is the hot tier: a manually selected view
compiles under the finite, versioned grammar `:re-frame.freehand/v1`, chosen by
`{:compiled true}` on the one declaration form — promotion edits one definition
site and no test.

The compiled mode is not built new. By operator ruling the useful machinery of
`re-frame.ui` — analyzer, both emitters, the ViewCell reactor, the presence
runtime, the manifest/elision and diagnostic machinery, and the test surface —
is **absorbed as the compiled mode's implementation**. `re-frame.ui` is in
**donor mode now**: no new standalone surface, and the standalone artifact is
**deleted when the conformance contract is green, the component/library pilots
pass, and consumers have migrated — a gate, not a date**.

Delivery is waves W1–W6, specified below. The normative contracts land first as
the `spec/0XX-Freehand` family (W1); the ratified design record lives at
`docs/design/freehand/` — the argued dossier (`fable-design.md`), the product
spine (`codex-design.md`), the acceptance harness
(`studio/fitness-harness.md`), and the twenty-one ratified decisions
(`decisions/D001`–`D021`) — a transitional tracked snapshot that the spec
family supersedes and deletes.

## Motivation

EP-0030 built the compiled view substrate and proved it end-to-end: the
compiler, the ownership protocol, evidence, SSR, and the S7-entry proofs all
shipped, and the friction programme took the authoring surface to 9/10
(`rf2-u53yy`). What it could not deliver is a paved path. A closed compiled
grammar has a permanent residue by design — the defhook gap, dynamic heads,
forwarding wrappers — so everyday application code keeps meeting rejections
that exist for the compiler's benefit, not the author's. `local` survives only
via an outside-epochs carve-out that strains the one-state-system principle.
And the production interpreter an application actually runs should be the same
artifact its tests exercise.

The alternatives were genuine and each was worked to a verdict in the argued
dossier (`docs/design/freehand/fable-design.md` §6): whole-root re-rendering
(the Replicant model), Reagent plus discipline, interpreted-only, a thin
ergonomic layer over `re-frame.ui`, UIx plus better bindings, a macro-thin
"veneer" compiler, and a plan-cache JIT interpreter. Each loses; each losing
argument constrains the winning design and is retained as rationale. The
resulting two-document design (product spine + argued dossier), the fitness
harness, and the twenty-one-decision register are a conversation that does not
fit in a bead. This EP is that record's durable home and the programme's
delivery plan.

## Specification

### Governing premises

The premises that govern everything below
(`docs/design/freehand/fable-design.md` §1; settled constraints in
`docs/design/freehand/decisions/README.md`):

1. **The compiled tier is assumed (operator axiom).** Some applications will
   have performance-critical components that must be compiled. The design does
   not argue this; it designs for it. Both modes ship together. Corpus
   evidence governs *placement* — where each mode applies — never whether the
   second mode exists.
2. **One reactive state system: re-frame.** Events and subscriptions are the
   only reactive model. No ratoms, no hooks-shaped state, no component-local
   reactive cells in the neutral core; React protocols live only inside
   visibly React-bound wrappers. `local` and the neutral React hook tier do
   not survive absorption.
3. **One substrate, one declaration form.** Compilation is selected with
   `{:compiled true}` at the definition site; `:re-frame.freehand/v1` is the
   versioned compiled grammar, not a compatibility profile between products.
   Promotion changes one definition site and no call site or test.
4. **Events are data; one event per user action.** A user action produces one
   semantic event vector or `nil` — never a vector-of-vectors event language.
   Mount/unmount and host lifecycle facts go to tools, never to domain events.
5. **Compilation is manual and evidence-guided.** No automatic promotion, no
   second compiler, no permanent interpreted fallback hidden inside generated
   code. Keyed presence and separate React/JVM emitters are required
   capabilities.
6. **React is the primary host.** The renderer-neutral core is the data plane
   — trees, event vectors, controller addresses, the structural test tree —
   not a portability layer.

### Non-goals

Stated once so absence reads as decision
(`docs/design/freehand/fable-design.md` §6 "Deliberate non-goals";
`docs/design/freehand/codex-design.md` §8): no second compiler and no
compiled-with-interpreted-fallback hybrid; no plan-cache/JIT interpreter as
shipped machinery; no automatic promotion or "hot 5%" quota; no multi-intent
handler vectors or recursive event DSL; no second component-local reactive
state system (no ratoms, no useState-alike — `local` is deleted with the
donor); no `:reads` declaration language in v1 (inline `v/sub` is the one read
language; static dependency data comes from compiled manifests); no public
occurrence reader (`v/self` does not exist — writable state takes explicit
addresses); no app-dispatched lifecycle; no neutral hooks/refs/effects/portals
vocabulary beyond the behavior registry and the top-layer intrinsics; no
parallel test or simulation API; no serialization of host objects into app-db,
intent vectors, or traces; no tree→tree transforms as a theming seam and no
layout DSL; no whole-app closure claims from the interpreted mode; and no
permanent standalone compiled-only product — the donor's useful code folds in
and the artifact retires at the gate.

### The decision

**Freehand** (D001: `re-frame.freehand`, alias `v`; edge namespaces
`re-frame.freehand.host` and `re-frame.freehand.test` only where a host-only
or test-only capability materially clarifies; `re-frame.view` is not published
as an alias, and `re-frame.ui` is donor-only) is re-frame2's native view
substrate. The product topology:

| Surface | Relationship to re-frame | Role |
|---|---|---|
| interpreted Freehand | native; assumes re-frame | unrestricted default authoring and execution mode — the paved path |
| compiled Freehand | native; assumes re-frame | finite-site lowering over the same declarations and ABI — the hot tier |
| `re-frame.ui` | native; donor only | temporary source and alpha-train migration surface; no new standalone features; deleted at the conformance-and-pilots gate |
| existing adapters (Reagent, UIx, …) | independent | external-renderer escape hatches; retained per their own EP-0030 rulings |
| Replicant | independent | the whole-state, subscription-free architectural comparison point; never co-mounted |

**The staged donor posture** — direction ruled now, deletion gated, never
dated: (1) `re-frame.ui` enters donor mode immediately — no new standalone
surface; its machinery evolves only as Freehand's compiled-mode
implementation. (2) Transitional coexistence while Freehand is built over the
absorbed machinery — the shipped alpha-train surface keeps working; the Spec
004 family migrates by rename into Freehand's compiled-grammar spec. (3)
Deletion when the conformance contract is green, the component and library
pilots pass, and consumers have migrated. Freehand never depends on the donor;
any temporary forwarding facade lives only inside `re-frame.ui`, gains no
semantics, and is not Freehand API.

### The wave plan

Delivery is six waves. Waves land in order where a dependency is stated;
otherwise their beads parallelize under the repo's dispatch rules. The
programme epic in the beads tracker parents every wave; W3–W6 beads beyond
those filed at bootstrap are created as their waves open.

**W1 — spec authoring (sequential; hot-zone).** The `spec/0XX-Freehand` family
is authored first — the spec is the artefact; implementation follows it.
Numbering is assigned at authoring time. All W1 documents touch the hot-zone
`spec/` tree and are **sequential, never parallel**. Each document carries its
ratified decisions and cites its `docs/design/freehand/` sources:

| Spec document (surface) | Decisions carried |
|---|---|
| view model and boundaries — declared views, `[view props]` call semantics, props/children/keys, state identity and addressing | D002, D004 |
| event grammar — vectors + options, projection tokens and payload materialization, key-condition maps, callback forms, the controlled-input synchronous door | D005, D006, D007, D008, D009 |
| controllers — the reusable-control state model, buffered/revision controls, framework control and policy vocabulary | D003, D016, D017 |
| host integration — registered behaviors and commands, the outward React bridge, top-layer overlays, error boundaries and production reports; presence | D013, D014, D015, D019 |
| theming and parts | D018 |
| evidence and tooling — compiled props schemas, declared reads and evidence levels, tool evidence retention and warning policy | D011, D012, D020 |
| the conformance contract — the cross-mode parity rows, the compiled seam laws and dynamic-markup crossing, and the donor deletion gate | D010; `fable-design.md` §3.6, `codex-design.md` §6 |

**W2 — common ABI + atomic shell.** The mode-neutral substrate both modes
share: the view descriptor (a non-`IFn` value; vector-head classification is
total), props/children/key/schema metadata, the semantic structural tree, the
event materializer with options and key maps, the controlled-input predicate,
frame binding, controller identity and evidence, presence, top-layer facts,
the behavior/command marker, the error boundary, the Root Descriptor, and the
evidence schema — then the atomic shell: one selected render-bundle commit,
with abandoned-render, HMR, frame-retarget, key-reorder, disconnect, and
same-thread capture tests. W2 starts when the W1 documents governing its
surfaces have merged (the W1 beads block the W2 bead).

**W3 — the interpreter (Freehand mode 1).** The full-Clojure interpreted mode
over the W2 ABI: dynamic target capture, HMR descriptors, JVM structural
rendering, props-only/read evidence, and qualified host descriptors.

**W4 — the compiled profile over the absorbed machinery.** Fold the donor's
analyzer and emitters into Freehand: recognize common vars/descriptors and
`{:compiled true}`, reject unsupported forms didactically, normalize forwarded
projections, retain separate React/JVM emitters, then the checker and the
cross-mode conformance corpus. The absorption worklist rows
(`docs/design/freehand/fable-design.md` §3.4; dispositions in
`codex-design.md` §8) become this wave's child beads: `local` deleted;
placeholder provenance moved to materialize-at-the-adapter with the permanent
`::v/value`/`::v/checked`/`::v/key` spellings; instance state on explicit
semantic addresses in both modes; the compiled-parent→interpreted-child
descriptor boundary (the one genuinely new emitter capability); one
controlled-scheduling implementation proven against the real-browser
caret/IME matrix; host forms replaced by the behavior registry and wrapper
with `spread-safe`/`spread` and `render-fn`/`slot` folded in as common
grammar; the key-condition-map grammar delta added to the absorbed analyzer;
presence unified under the absorbed runtime; and the donor's callable JVM
view value switched to the shared non-`IFn` descriptor.

**W5 — pilots and real-browser matrices.** Component pilots: the controlled
field and the **buffered field proven against the `rf2-nzst23` pin suite**
(caret/IME/HMR/JVM/reset-revision pins), the dropdown/popup, the async
typeahead, and the virtual table with a caller row slot — with public schemas,
tokens, and semantic parts. Library pilots: React-Vega/Vega, a
SpreadJS-class editor behavior with commands, Radix, TanStack Table, and an
AG-Grid-style `host/as-react` cell — each through the appropriate leaf,
bridge, behavior, or wrapper. The real-browser matrices (controlled
input/IME under contention, keyed presence re-entry and accessibility,
top-layer reconciliation, behavior command/commit/replay/cleanup, error
containment, root teardown, fallback hydration) run here against the
acceptance harness (`docs/design/freehand/studio/fitness-harness.md`).

**W6 — evidence, conformance green, donor deletion.** The B1–B5 measurement
harness (`docs/design/freehand/codex-design.md` §8): B1 direct lowering, B2
capability elision, B3 generated row comparison, B4 controlled editing under
contention, B5 shipped cost. Per D021 the harness is **evidence-only**:
deterministic properties (equal output, zero dropped input, exact commit
counts, manifest/cell elision, bundle reachability) are hard CI gates;
wall-clock and byte distributions are mandatory published evidence with named
baselines, never automatic numeric pass/fail thresholds. B1–B5 fixtures
arrive with the first slice that can run each workload, not as a final
optimization project. W6 closes the programme: every conformance row green,
pilots passed, donor worklist disposed, consumers migrated, `re-frame.ui`
deleted, and `docs/design/freehand/` retired in favour of the spec family.

### The conformance contract is the release gate

The per-surface parity table (`docs/design/freehand/fable-design.md` §3.6;
row-complete form in `codex-design.md` §6 "Conformance contract and donor
deletion gate") is Freehand's internal two-mode contract **and the donor
deletion gate** — parity is internal work, not a cross-product compatibility
negotiation. Its rows cover: calls, identity/HMR, children, props, events,
controlled input, frame, subscriptions, controller state, state/host
rejection, presence, top layer, behavior, outward React, errors, roots/SSR,
structure, and diagnostics/tools. Release acceptance is this table going
green — not aspirational — with the staged evidence policy of
`codex-design.md` §8 "Release acceptance". The React and JVM emitters remain
separate; they share host-neutral normalizers where practical and prove
parity through the cross-mode conformance corpus.

### The design-record posture

`docs/design/freehand/` is a **transitional tracked exception** to the
local-only `ai/` rule: the ratified design snapshot is tracked so beads and
this EP can cite stable, reviewable paths while W1 authors the normative
family. The snapshot is copied verbatim (per-dossier `Status:` lines under
`decisions/` predate the ratification recorded in `fable-design.md`'s
header) and is excluded from the mkdocs site build — its relative links are
written for source-tree browsing. It is deleted when the `spec/0XX-Freehand`
family supersedes it, the same retirement pattern EP-0030 applied to its
synthesis dossier.

### Guide impact

No human-facing guide change in this bootstrap. During coexistence the
existing `docs/core/re-frame.ui` guide keeps working as the donor's teaching
surface; it retires with the donor at the deletion gate. Freehand's own
teaching (the paved-path chapters and the promotion workflow) lands once
W3/W5 stabilize the authoring surface, and is tracked as W5/W6-adjacent work,
not promised page-by-page here.

## Rationale

**One substrate with two modes** beats every alternative that was actually
priced. Whole-root re-rendering couples keystroke cost to page size and makes
reusable controls prop-plumbing exercises; Reagent-plus-discipline cannot
demonstrate intent-as-data or remove the render-closure failure classes;
interpreted-only is overridden by the operator axiom (and its best evidence
survives as placement policy); a thin layer over `re-frame.ui` cannot deliver
full-language views or instance state without `local`'s carve-out; UIx-plus-
bindings cannot test as data without mounting React; the veneer compiler
produces speed without knowledge (a partial site table falsifies every
completeness claim); and the JIT plan-cache buys an unknown fraction of the
walk at the price of a third execution model. Each verdict is argued in
`docs/design/freehand/fable-design.md` §6.

**Absorption** beats building the compiled mode new and beats preserving two
products. The arithmetic that killed building new: a fresh analyzer front,
React emitter, and diagnostic taxonomy (donor: 3,490 + 1,055 lines plus the
taxonomy) to save a 492-line JVM emitter — while adjudicating a second
forever-grammar in parallel. And because no second product's contract
survives, there is no compatibility ledger to maintain — only the absorption
worklist, every row resolved in Freehand's direction. The coordination tax of
coupling a hot tier to a separately-owned artifact's evolution evaporates
with the second product.

**Spec-first W1** because the spec is the artefact and the code is
downstream: EP-0030's stages proved that contracts which land atomically with
their fixtures stay honest, and the hot-zone rule (spec documents sequential,
never parallel) is what makes a seven-document family land without merge
churn. The wave order otherwise follows the product spine's implementation
sequence (`docs/design/freehand/codex-design.md` §8), which exists precisely
so the ABI is decided once, the interpreter proves it, and the transplant
lands against a fixed target.

**A gate, not a date** for donor deletion, because the deletion is downstream
of proof: the conformance table, the pilots, and consumer migration are
checkable facts, and dating the deletion would convert an evidence obligation
into a calendar promise — the failure mode D021 exists to prevent.

## Backwards Compatibility

Pre-alpha: no compatibility shims. `re-frame.ui`'s shipped alpha-train
surface keeps working through the coexistence window; the Spec 004 family
migrates by rename into Freehand's compiled-grammar spec; donor `:rf.ui/*`
placeholder spellings are rewritten mechanically at migration, never aliased.
Freehand never depends on the donor, and any temporary forwarding facade
lives only inside `re-frame.ui` and is not Freehand API. The external-
renderer adapters are untouched by this EP; their disposition remains
EP-0030's executed record. EP-0030 and its sibling EPs (EP-0031–EP-0035)
stand as the donor programme's historical record; their machinery's future is
governed by this EP's absorption ruling, and the donor artifact's deletion
discharges — rather than reopens — their delivery story.

## Resolved Decisions

- **Programme ratification (2026-07-22).** All twenty-one design decisions
  D001–D021 are ratified and folded into the design record (dossiers at
  `docs/design/freehand/decisions/`; ratification recorded in
  `fable-design.md`'s header). The body above carries them as settled design.
  Headline rulings: Freehand named through `re-frame.freehand`/`v` with no
  second public namespace (D001); one substrate with interpreted and compiled
  modes, compiled required (operator axiom); **absorption** — `re-frame.ui`'s
  useful machinery becomes the compiled mode's implementation, the standalone
  artifact enters donor mode now and is deleted at the
  conformance-and-pilots gate; `local`, its placement machinery, and the
  neutral React hook tier do not survive absorption; performance policy is
  evidence-only (D021).
- **Bootstrap shape (2026-07-22, this EP).** The programme boots as: the
  tracked design snapshot at `docs/design/freehand/`, this umbrella EP, and
  the bootstrap beads — one programme epic, seven W1 spec-authoring beads
  (hot-zone, sequential), one W2 ABI/shell bead gated on W1, and the W5/W6
  pilot-gate and B1–B5 harness beads. Later waves are filed as children of
  the epic when their entry conditions approach.

## Open Issues

- **Graduation only.** This EP stays `accepted` while the waves land and
  moves to `final` when W6 completes: conformance table green, pilots passed,
  donor deleted, `docs/design/freehand/` retired into the spec family.
- **Spec numbering.** The `spec/0XX-Freehand` family's numbers are assigned
  at W1 authoring time against the then-current spec index; the W1 beads
  carry the surface-to-document mapping above, not fixed numbers.

## References

- **Design record (transitional tracked snapshot; deleted when the spec
  family supersedes it):** `docs/design/freehand/README.md` — index;
  `docs/design/freehand/fable-design.md` — the argued dossier (§1 premises,
  §3.4 absorption worklist, §3.6 conformance contract, §6 alternatives and
  non-goals); `docs/design/freehand/codex-design.md` — the product spine
  (§6 conformance and donor deletion gate, §8 measurement obligations,
  absorption dispositions, implementation sequence, release acceptance);
  `docs/design/freehand/studio/fitness-harness.md` — the acceptance harness;
  `docs/design/freehand/decisions/` — D001–D021.
- **[EP-0030](EP-0030-the-compiled-view-substrate-program.md)** — the donor
  programme (`re-frame.ui`), whose useful machinery this programme absorbs;
  its sibling contracts
  [EP-0031](EP-0031-re-frame-ui-programming-model.md),
  [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md),
  [EP-0033](EP-0033-re-frame-ui-view-evidence.md),
  [EP-0034](EP-0034-re-frame-ui-production-ssr-testing.md), and
  [EP-0035](EP-0035-component-library-readiness.md) are the donor-era
  per-domain records the W1 family draws on where the absorbed machinery's
  contracts carry forward.
- **[EP-0014](EP-0014-derivation-and-process-algebra.md)** — the one reactive
  grammar Freehand reads through; premise 2 adds no second one.
- **Specs:** the Spec 004 family
  ([`spec/004-Views.md`](../../spec/004-Views.md),
  [`spec/004B-UI-Tree-and-Conversion.md`](../../spec/004B-UI-Tree-and-Conversion.md),
  [`spec/004C-Roots-and-Mount.md`](../../spec/004C-Roots-and-Mount.md)) —
  migrates by rename into the compiled-grammar spec during coexistence;
  [`spec/006-ReactiveSubstrate.md`](../../spec/006-ReactiveSubstrate.md) —
  the observation-port home the reactor builds on;
  [`spec/008-Testing.md`](../../spec/008-Testing.md),
  [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md),
  [`spec/011-SSR.md`](../../spec/011-SSR.md) — rows the W1 family amends.
- **Beads:** the programme epic and bootstrap beads filed with this EP
  (tracker ids recorded on the epic); `rf2-nzst23` — the buffered-field
  reset-revision pin suite W5's pilot must satisfy; `rf2-u53yy` — the donor
  friction programme whose residue motivates the paved path.
