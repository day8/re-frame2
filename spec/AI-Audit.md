# AI-First Audit

> **Type:** Audit
> Applies the nine AI-first properties as a checklist against the re-frame2 corpus — the numbered Specs (000–014) plus the companion documents that participate in the AI-implementable goal (Spec-Schemas, Construction-Prompts, conformance/README). Surfaces gaps for the next round of design work.

## Scope

This audit grades every artefact in `spec/` that contributes to the **pattern's contract** or to its **AI-implementability**. Specifically:

- **Numbered Specs** (000, 002, 004, 005, 008, 009, 010, 011, 012) — the per-area normative specifications with stand-alone Per-Spec scoring tables below. Specs 001 (Registration), 006 (ReactiveSubstrate), 007 (Stories), 013 (Flows), and 014 (HTTP Requests) are graded indirectly through the cross-cutting goal sections below; they do not yet have stand-alone Per-Spec scoring tables.
- **Spec-Schemas** — the spec's own runtime-shape catalogue.
- **Construction-Prompts** — the AI-scaffolding catalogue.
- **conformance/README** — the fixture format and capability-tagging convention.

**Coverage policy.** Per-Spec scoring is intentionally selective: a Spec earns a stand-alone table once its surface has stabilised enough to grade against all nine properties without thrash. New Specs land in the cross-cutting sections first and graduate to a Per-Spec table when the next audit pass adds them. The audit therefore lags the live numbered set by design; absence from the Per-Spec list does not mean the Spec is out of scope, only that it is being graded indirectly until the next pass.

Other companion documents (Principles, Conventions, Patterns, MIGRATION, Tool-Pair, Implementor-Checklist, README) are out of scope: they are rationale, conventions, or migration artefacts, not contracts an implementation conforms to.

The audit produces three artefacts:

1. **Per-Spec scoring** — a property-by-property grade per numbered Spec.
2. **Cross-cutting goal sections** — grades a goal (rather than a property) across every Spec that touches it.
3. **Cross-cutting gaps (G-A through G-F)** — issues that recur across Specs; defined once below in §Cross-cutting gaps and referenced from the Per-Spec tables and §Headline finding by their G-letter.

Forward-references to `G-A`/`G-B`/etc. in Per-Spec tables resolve to §Cross-cutting gaps below.

## The nine properties

| # | Property | Short-form question |
|---|---|---|
| P1 | Regularity over cleverness | Is there one obvious way? |
| P2 | Named things over anonymous | Is everything addressable by a stable id? |
| P3 | Data before magic | Is behaviour expressible as data + interpreter? |
| P4 | Public query surfaces | Can an agent ask the runtime "what exists?" |
| P5 | Shape descriptions everywhere | Is every shape described — by a schema (in dynamic hosts) or by a type (in static hosts)? The pattern requires shape description; it doesn't require a runtime schema layer specifically. |
| P6 | Deterministic execution | Can an agent run a focused experiment and trust it? |
| P7 | Machine-readable errors | Are errors structured, not stringy? |
| P8 | Low hidden context | Is behaviour determined by visible inputs? |
| P9 | Name over place | Where data carries multiple values, are they named (map keys) rather than positional? Per-Spec scoring will fill in as Specs are re-audited; the principle is canonical and applies corpus-wide. |

Plus the cross-cutting:

- **Constrained execution model.** Are pure handlers, FSMs, run-to-completion, declarative DSLs preferred over Turing-complete free-form?
- **Data is code.** Is the artefact an instruction in the developer-designed DSL, or is it host-language code?

## Scoring legend

- ✓ — property is well-served.
- ◐ — partial; gap noted.
- ✗ — gap; remediation needed.
- n/a — the property does not apply to this artefact (e.g., runtime properties P4/P6/P7 against a doc-level Spec like 000-Vision; or capability-conformance grading against a Spec that isn't capability-graded).

## Per-Spec scoring

References to `G-A`/`G-B`/`G-C`/`G-D`/`G-E`/`G-F` in the tables below resolve to entries in [§Cross-cutting gaps](#cross-cutting-gaps-across-multiple-specs).

### Spec 000 — Vision

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | Pattern/reference-impl split makes the canonical shape unambiguous. |
| P2 Named things | ✓ | Goals, Specs, constraints all stably-id'd. |
| P3 Data before magic | ✓ | Doc itself is structured data the reader interprets. |
| P4 Public query surfaces | n/a | (Doc-level, not runtime.) |
| P5 Schemas | ◐ | Goal #4 (Clojure ethos / open shape descriptions) names schemas-everywhere; the doc itself isn't schema-described. Probably out-of-scope for a vision doc. |
| P6 Deterministic execution | n/a | |
| P7 Machine-readable errors | n/a | |
| P8 Low hidden context | ✓ | Cross-references to source rationale (on-dynamics, data-oriented-design) are explicit. |

**Gaps:** none significant. The doc serves its purpose.

### Spec 002 — Frames

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | Two registration shapes (`reg-frame` / `make-frame`) is right. View invocation has two forms (`view` / Var) — the `h` macro was dropped (rf2-n4um). |
| P2 Named things | ✓ | Frames, handlers, views, fx all stably-id'd. Anonymous lambdas survive only inside view bodies (`:on-click #(dispatch ...)`) which is borderline acceptable. |
| P3 Data before magic | ◐ | Dispatch envelope, effect map, frame metadata all data. `:fx-overrides` and `:interceptor-overrides` accept function values at the CLJS reference level. Pattern-level contract is id-based; CLJS reference also accepts fn values. |
| P4 Public query surfaces | ✓ | `registrations`, `frame-meta`, `frame-ids`, `get-frame-db`, `snapshot-of`, `sub-topology` all in. |
| P5 Schemas | ◐ | `:spec` in registration metadata is documented for handlers/subs/fx but the *frame's `:on-create` event* and *override map shape* aren't schema-described. |
| P6 Deterministic execution | ✓ | Run-to-completion drain, depth limit, no-cross-frame-drain — all locked. |
| P7 Machine-readable errors | ◐ | "Errors are maps with documented keys" stated as an aspiration but the actual error shapes aren't enumerated. Needs a §Error contract subsection. |
| P8 Low hidden context | ◐ | **The biggest gap.** React-context-driven `dispatch`/`subscribe` injection is hidden context by definition. The pattern contract is explicit-frame addressing with React context positioned as a CLJS optimisation, but 002's body still leans on the React-context machinery as primary. A full rewrite of the View Ergonomics section would close this. |
| Constrained model | ✓ | Frame-as-actor-boundary, event-as-FSM-step, drain-as-RtC. |
| Data is code | ✓ | Events, effects, frames, registrations all data interpreted by the runtime. |

**Gaps:**
1. Override seam still presents function-valued as default; pattern-level id-valued should lead the §Per-frame and per-call overrides section. (Tracked as **G-C**.)
2. Error contract is gestured at but not specified. (Tracked as **G-A**.)
3. View ergonomics section's narrative still reads CLJS-context-primary; needs a top-down rewrite to lead with explicit-frame.
4. View invocation has three forms — pick a canonical and document the others as alternatives. (Tracked as **G-E**.)

**`:preset` audit row.** Frames declare a `:preset` (`:default`, `:test`, `:story`, `:ssr-server`); the runtime expands and `(frame-meta <id>)` records the applied preset. AI-amenable scaffolding should:

- ✓ Use the locked closed set; never invent unknown preset values (would emit `:rf.error/unknown-preset` at registration).
- ✓ Read the *expanded* metadata to see the effective config rather than re-deriving from the preset name.
- ✓ Override individual keys when the preset's default doesn't fit, rather than hand-rolling a full metadata map.

Worked-example check: `examples/reagent/realworld/auth.cljs` (test fixture frames) should declare `:preset :test`; story-driven examples should declare `:preset :story`; the SSR example should declare `:preset :ssr-server`. Drift here surfaces as a follow-up bead.

### Spec 004 — Views

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ◐ | Three hiccup-invocation forms (see 002 above). Form-1/2/3 component handling adds three more shape variants for the registration. |
| P2 Named things | ✓ | All views id'd. |
| P3 Data before magic | ✓ | View output is hiccup (data); render-tree contract is serialisable. |
| P4 Public query surfaces | ✓ | View registry queryable via `(registrations :view)`. |
| P5 Schemas | ◐ | `:spec` for the view's *props vector* is documented but most examples don't use it. Construction Prompts CP-4 enforces. |
| P6 Deterministic execution | ✓ | Pure render-tree per pattern contract. |
| P7 Machine-readable errors | n/a | (Errors during render are mostly substrate concerns.) |
| P8 Low hidden context | ◐ | Plain Reagent fns "silently route to `:rf/default`" if rendered under a non-default frame. Documented as a known limitation. Removing this footgun (or making it loud at runtime) closes the gap. |
| Constrained model | ✓ | Pure `(state, props) → render-tree`. |
| Data is code | ✓ | Hiccup is the canonical example of data-is-code. |

**Gaps:**
1. Pick a canonical hiccup-invocation form and document the others as alternatives — three forms is a P1 hit. (Tracked as **G-E**.)
2. Make the plain-Reagent-fn-routes-to-default footgun loud (warn at render time when a plain fn renders under a non-default frame). (Tracked as **G-D**.)
3. Consider whether Form-1/2/3 are all needed or whether Form-2's outer-fn-side-effects should be discouraged in favour of Form-1 + an explicit setup event. (Tracked as **G-F**.)

### Spec 005 — State Machines

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One transition-table shape; one `create-machine-handler` call site; one snapshot shape (`{:state :data}`). |
| P2 Named things | ✓ | Machines, states, transitions, guards, actions all id'd. The machine itself is the registered event handler. |
| P3 Data before magic | ✓ | Transition table is data; action and guard slots are fns (per the data-DSL-vs-fn rule), but the references and the structure are data. |
| P4 Public query surfaces | ✓ | A machine *is* an event handler — enumerated via `(registrations :event)`, filterable by `:rf/machine?` metadata; the discovery lens `(rf/machines)` / `(rf/machine-meta id)` makes this a first-class operation. Snapshot reachable via the framework-registered `:rf/machine` parametric sub (`@(rf/sub-machine <machine-id>)` or `@(rf/subscribe [:rf/machine <machine-id>])`) or `(get-in (get-frame-db f) [:rf/machines <id>])`; guards/actions are **machine-scoped** — declared in the machine's `:guards` / `:actions` maps and visible via `(machine-meta <id>)` (the registration metadata exposes the transition table including the `:guards` / `:actions` slots). |
| P5 Schemas | ✓ | `:rf/transition-table` and `:rf/machine-snapshot` registered in [Spec-Schemas](Spec-Schemas.md); `:rf.fx/spawn-args` for spawn specs. |
| P6 Deterministic execution | ✓ | Pure `machine-transition`; pure factory `create-machine-handler`; finite states; explicit transitions; deterministic four-level drain. |
| P7 Machine-readable errors | ✓ | Trace events on machine lifecycle; `:rf.error/machine-action-wrote-db`, `:rf.error/machine-raise-depth-exceeded`, `:rf.error/machine-grammar-not-in-v1`, `:rf.error/machine-unresolved-guard`, `:rf.error/machine-unresolved-action`. |
| P8 Low hidden context | ✓ | All machine state lives at the reserved runtime-managed path `[:rf/machines <id>]`. Strict encapsulation: actions and guards see `{:state :data}` only — no `:db`, no cofx. |
| Machine table inspectability | ✓ | Both keyword-reference and inline-fn forms are first-class at the grammar level, but [005 §Inspectability bias](005-StateMachines.md#inspectability-bias) makes named entries in the machine's `:guards` / `:actions` map the **default** form for non-trivial logic. The CP-5 auth-flow example uses keyword references throughout. In the Circle Drawer example in 005 the right-click action (compound three-key seed) is named `:begin-edit` in the machine's `:actions` map, the close-dialog action (emits `:fx` AND clears `:data`) is `:commit`, and the drag-slider and cancel-dialog actions stay inline because their bodies are single non-branching expressions. Both examples uniformly demonstrate the inspectability-bias rule. |
| Constrained model | ✓ | The strongest example. |
| Data is code | ✓ | Transition tables ARE the canonical "data is code" example for stateful flows. |

**Gaps:**
1. Register transition tables as a queryable `:machine` kind in the registrar. (Resolved in part by `(rf/machines)` / `(rf/machine-meta id)` — a derived lens over `(registrations :event)` filtered by `:rf/machine?` metadata; see [005 §Querying machines](005-StateMachines.md#querying-machines).)
2. Ship a Malli schema for the transition-table grammar.

### Spec 008 — Testing

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One fixture lifecycle (`make-frame` / `destroy-frame!`); one synchronous trigger; one assertion macro. |
| P2 Named things | ✓ | Frames, handlers, fixtures all id'd. |
| P3 Data before magic | ✓ | `compute-sub` operates on data; `dispatch-sequence` is a vector of events. |
| P4 Public query surfaces | ✓ | Tests use the same query API tooling does. |
| P5 Schemas | ✓ | Tests against schema-bound paths inherit validation in dev. |
| P6 Deterministic execution | ✓ | Run-to-completion drain makes tests deterministic. |
| P7 Machine-readable errors | ✓ | Test failures route through assertion macros; trace events available. |
| P8 Low hidden context | ✓ | Each test runs in a fresh frame. |

**Gaps:** none significant. Spec 008 is the strongest of the existing Specs on AI-first grounds — testing is *intrinsically* AI-amenable when state is bounded.

### Spec 009 — Instrumentation

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One trace event shape; one listener API. |
| P2 Named things | ✓ | `:id`, `:operation`, `:op-type` all id'd. |
| P3 Data before magic | ✓ | Trace events are open maps. |
| P4 Public query surfaces | ✓ | The trace stream IS the query surface for runtime behaviour. |
| P5 Schemas | ◐ | Trace event shape has stable required keys but no Malli schema is registered for it. Should be `(rf/reg-app-schema [:rf/trace] TraceEventSchema)` or similar. |
| P6 Deterministic execution | ✓ | Per-trace events for every drain step. |
| P7 Machine-readable errors | ◐ | Errors emit trace events but the *error event shape* isn't formally defined alongside the others. |
| P8 Low hidden context | ✓ | All emit sites are visible in source. |

**Gaps:**
1. Register a Malli schema for the trace event shape.
2. Define error trace events as a first-class subset. (Tracked as **G-A**.)

### Spec 010 — Schemas

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One `:spec` key; one `reg-app-schema` call. |
| P2 Named things | ✓ | Schemas attached to id'd registrations. |
| P3 Data before magic | ✓ | Malli schemas are data. |
| P4 Public query surfaces | ✓ | `(app-schemas)`, `(app-schema-at path)`. |
| P5 Schemas | ✓ | (Self-referential, of course.) |
| P6 Deterministic execution | ✓ | Validation runs at deterministic boundaries. |
| P7 Machine-readable errors | ◐ | Validation errors include the failing path and value but the explicit error envelope isn't defined as a structured map alongside other errors. |
| P8 Low hidden context | ✓ | Every validation point is explicit. |

**Gaps:** validation-error envelope as a structured shape (alongside other errors). (Tracked as **G-A**.)

### Spec 011 — SSR

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | Symmetric server/client flow; one `:rf/hydrate` event. |
| P2 Named things | ✓ | `:rf/hydrate`, `:rf.fx/skipped-on-platform`, `:rf.ssr/hydration-mismatch`. |
| P3 Data before magic | ✓ | `:platforms` metadata, render-tree as data, hydration payload as data. |
| P4 Public query surfaces | ✓ | Inherits from 002/009. |
| P5 Schemas | ◐ | Hydration payload shape (the EDN/JSON crossing the wire) isn't formally schema'd. Should be. |
| P6 Deterministic execution | ✓ | Server-side run-to-completion drain. |
| P7 Machine-readable errors | ✓ | Hydration mismatch is a structured trace event. |
| P8 Low hidden context | ✓ | `:platforms` makes server/client gate explicit at the fx registration. |

**Gaps:** schema for the hydration payload format.

## Cross-cutting goal: AI-implementable from the spec alone

[000-Vision §AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) (Goal 2) is the meta-property that grades the spec corpus's *completeness* — can an AI build v1 from `/spec/` alone? Audit dimension: **spec self-containedness** — every spec must be readable without consulting re-frame v1 source, and every shape on the wire must be schema'd.

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Names the goal; explains rationale, what-this-implies, connection-to-other-goals, and failure mode. Capability matrix entries (FSM-richness + actor-model rows) added to the host-profile matrix. |
| 001-Registration | ◐ | Metadata-map shape is well-specced; the *exact* set of metadata keys per `reg-*` kind needs a single canonical table. |
| 002-Frames | ◐ | Drain semantics, envelope shape, view ergonomics all specced; error contract is gestured at but not enumerated (G-A in §Cross-cutting gaps). |
| 005-StateMachines | ◐ | Foundation specced in detail; capability matrix in place; hierarchical / eventless / delayed / declarative-`:invoke` capabilities are claimed but drain-rule extensions for compound entry/exit are still being elaborated. |
| 008-Testing | ✓ | Three test levels, fixture lifecycle, framework adapters all specced. |
| 009-Instrumentation | ◐ | Trace event shape stable; error event shape not formally enumerated. Closes alongside G-A. |
| 010-Schemas | ✓ | Self-referential — schemas are specced via examples in re-frame2's own conventions. |
| 011-SSR | ◐ | Hydration payload schema missing (existing gap). |
| 012-Routing | ✓ | URL ↔ params grammar, route metadata shape, fx ids all specced. |
| Spec-Schemas | ◐ | Most shapes covered; declarative-`:invoke` schema additions remain to be written; hydration payload schema and trace-event schema (G-A) are tracked elsewhere. |
| conformance/README | ✓ | Cross-references the goal; capability-tagging convention added to fixture metadata. |

**Gaps:** G-A (error envelope), G-B (CP depth), and schema-on-the-spec's-own-shapes coverage.

## Cross-cutting goal: Capability-conformance clarity

[000-Vision §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) (Goal 6) makes conformance graded against the implementor's claimed capability list. Audit dimension: **capability-conformance clarity** — does each spec section name which capabilities it depends on?

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Goal text + the FSM-richness / actor-model breakdown name each capability and its v1-claim status. Host-profile matrix has capability-list rows. |
| 005-StateMachines | ✓ | §Capability matrix is the canonical list; v1 grammar subset table aligned with the matrix; parallel regions and tags are now first-class capabilities (rf2-l67o / rf2-ee0d); substitute for history specced. |
| Spec-Schemas | ✓ | `:rf/transition-table` schema covers flat / hierarchical / eventless / delayed / declarative-`:invoke` / `:tags` / `:type :parallel` + `:regions`; `:rf/machine-snapshot` widened to the third `:state` arm for parallel regions. |
| Construction-Prompts | ✓ | CP-5 forward-points at the parallel-regions first-class capability and the N-machines substitute for conceptually-independent features, plus the history-state snapshot-capture substitute. |
| conformance/README | ✓ | Capability-tagging convention specifies how fixtures self-declare; harness runs only the matching subset. |
| Other Specs (002, 008, 009, 011, 012) | n/a | Capability-list scoping is FSM-and-actor-specific; other Specs aren't capability-graded in the same way (they are pattern-required as a whole). |

**Gaps:** none currently outstanding for the FSM-richness / actor-model schema surface.

## Cross-cutting goal: Frame state revertibility

[000-Vision §Frame state revertibility](000-Vision.md#frame-state-revertibility) (Goal 2) is a top-level goal that several Specs are responsible for satisfying together. Audit summary:

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Names the goal; explains rationale, implications, and the boundary at the registered-fx seam (external side effects need compensation, not reversal). Marks persistent data structures as pattern-required in the host-profile matrix. |
| 002-Frames | ✓ | Frame state is one persistent value (`app-db`, router, sub-cache, lifecycle). Run-to-completion drain provides atomic per-event transitions — every settled state is a snapshottable boundary. The two-tier registry (central boot-time vs frame-local) is articulated under §State machines are just event handlers; frame-local registrations live inside the frame's value. The epoch surface (`epoch/restore-epoch` + `epoch/reset-frame-db!`) exposes the goal as user-facing API. |
| 005-StateMachines | ✓ | Machine snapshots live at `[:rf/machines <id>]` inside `app-db`. Strict encapsulation prevents leakage outside the frame value. Spawn registers in the frame-local tier so undo rolls back spawned actors with their snapshots. §What the Single Store gives us for free enumerates the concrete revertibility wins (undo/redo, time-travel, persistence, snapshot-and-replay). |
| 008-Testing | ✓ | Per-test frames + value-shape rollback via `epoch/reset-frame-db!` rely on the goal directly; teardown is "drop the frame's value." |
| 011-SSR | ✓ | Hydration replaces `app-db` with the server-supplied value; machine snapshots ride along. SSR is a special case of "snapshot-and-replay." |
| 006-ReactiveSubstrate | ✓ | [006 §Revertibility constraints on adapters](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters) locks the rule: adapter-internal state is allowed iff derivable from the frame's value. Memoisation caches, reaction caches, and listener registries are derivable; non-derivable observer state is prohibited. Reagent and plain-atom reference adapters audited compliant. DOM render output sits at the registered-fx seam (per Goal 3) and is out of scope for frame-state revert. |
| 009-Instrumentation | ✓ | Trace stream is append-only and not part of frame state, so revert is well-defined: "drop trace events after timestamp T" is a separate operation from frame-state revert. |
| 010-Schemas | n/a | Schemas are static registry data; not in scope for frame revert. |
| 012-Routing | ✓ | `:route` lives at a reserved app-db key, so route state reverts with the rest of `app-db`. |

**Gaps:** none. Spec 006's §Revertibility constraints on adapters closes the previously-noted gap; every Spec scores ✓ on Goal 3 alignment.

## Cross-cutting gaps (across multiple Specs)

### G-A. Error envelope is gestured at but not standardised

Errors are mentioned in 002, 009, 010 but no single doc defines the canonical structured-error shape. Proposal: a small section in 009 enumerating error event shapes (validation failure, handler exception, hydration mismatch, drain depth exceeded, override fallthrough), each with a registered Malli schema and a unique `:op-type`. This closes P7 in three Specs simultaneously.

### G-B. Construction prompts not yet wired into pre-flight tooling

Construction-Prompts.md describes how an AI uses the prompts but doesn't specify what API calls satisfy the "verify the id is unused" / "consult registered schemas" pre-flight checks. Each prompt should reference the exact registry-query API (e.g., `(rf/registrations :event)`). CP-1 / CP-4 / CP-6 do this; CP-2/3/5/7/8/9 still need filling in.

### G-C. The override seam is still mixed function/id

Pattern contract is id-based (per recent edits), but the CLJS reference accepts function values. The two forms should be clearly separated — id-valued for portable testing, function-valued for one-off CLJS lambdas — and the documentation should lead with id-valued in the pattern's primary examples.

### G-D. The plain-Reagent-fn footgun

Plain Reagent fns rendered inside a non-default frame silently route to `:rf/default`. This violates P8 (hidden context). Either remove the footgun (mandate `reg-view`) or make it loud (runtime warning).

### G-E. View invocation has two forms — Var canonical, `(view :id)` for late-binding

The Var reference (`[counter "Hello"]`) is the canonical call-site form: `reg-view` defs the symbol, hiccup picks it up directly. `(view :id)` is the documented escape hatch for late-binding by id (cross-module reference, runtime-computed ids, hot-reload-sensitive call sites). The earlier `h` macro draft has been dropped (rf2-n4um); two forms is the v1 surface.

### G-F. Form-1 / Form-2 / Form-3 component shapes

Three component shapes inherit from Reagent. Form-2's outer-fn-side-effects pattern violates P8 (the side-effect is invisible from the call site). Consider deprecating Form-2 in favour of Form-1 + an explicit setup event.

## Headline finding

The Specs score uniformly well on P1–P3 (regularity, naming, data-orientation) and P6 (determinism). The recurring weak points are P7 (machine-readable errors), P8 (low hidden context), and P5 (schemas applied to the spec's own shapes). The cross-cutting gaps section above enumerates the specific findings.

## SA-3 schema-coverage report (rf2-baj2g)

[SPEC-AUTHORING.md §SA-3](SPEC-AUTHORING.md) commits the corpus to: "Every shape that flows on the wire or appears in a spec example MUST have a schema in [Spec-Schemas.md](Spec-Schemas.md)." This report is the audit's running cross-reference table: every shape-shaped artefact named in the numbered specs MUST map to one of (a) a `:rf/<id>` schema entry in Spec-Schemas.md, (b) an explicit host-type exemption (a host's primitive that doesn't need cross-host schema coverage), or (c) a generated EDN catalogue derived from the corpus.

**Current state.** The shape catalogue carries 37 schema sections; the per-section Owner / Status / Conformance metadata that makes the projection auditable was pinned at rf2-baj2g and demonstrated on 5 load-bearing schemas (`:rf/dispatch-envelope`, `:rf/effect-map`, `:rf/trace-event`, `:rf/epoch-record`, `:rf/hydration-payload`). The full sweep across the remaining ~32 schema sections is tracked at **rf2-vpu5c**.

**Audit cadence.** This report is regenerated per AI-Audit run. Per-section completeness gates on SA-3:

| Section count | SA-3 status |
|---|---|
| 5 / 37 | Demonstration (rf2-baj2g — Owner + Status + Conformance present) |
| 32 / 37 | Pending sweep (rf2-vpu5c — Owner / Status / Conformance still to add) |

**SA-3 violation rule.** A spec example or wire payload that does NOT map to either a schema entry or an explicit host-type exemption is an SA-3 violation. The fix is to add the missing entry to Spec-Schemas.md (not to add an exemption). Per-cycle the audit names any newly-surfaced violations under this section.

**Scope clarification.** This report covers shapes that flow *between* implementation surfaces (wire payloads, returned shapes, registration metadata). It does NOT cover host-primitive shapes (a CLJS map, a TypeScript object type) — those are local to the host's type system and need no cross-host schema. Per [§Scope](#scope), per-Spec scoring is selective; the SA-3 report is corpus-wide and is the SA-3 enforcement surface.
