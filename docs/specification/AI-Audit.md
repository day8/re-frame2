# AI-First Audit

> Status: First pass. Per [reorient.md](reorient.md): the eight AI-first properties are a checklist EPs are graded against. This doc applies that checklist to the existing EPs and surfaces gaps that the next round of design work should close.

## The eight properties

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

Plus the cross-cutting:

- **Constrained execution model.** Are pure handlers, FSMs, run-to-completion, declarative DSLs preferred over Turing-complete free-form?
- **Data is code.** Is the artefact an instruction in the developer-designed DSL, or is it host-language code?

## Scoring legend

- ✓ — property is well-served.
- ◐ — partial; gap noted.
- ✗ — gap; remediation needed.

## Per-EP scoring

### EP 000 — Vision

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | Pattern/reference-impl split makes the canonical shape unambiguous. |
| P2 Named things | ✓ | Goals, EPs, constraints all stably-id'd. |
| P3 Data before magic | ✓ | Doc itself is structured data the reader interprets. |
| P4 Public query surfaces | n/a | (Doc-level, not runtime.) |
| P5 Schemas | ◐ | Goal #2 names schemas-everywhere; the doc itself isn't schema-described. Probably out-of-scope for a vision doc. |
| P6 Deterministic execution | n/a | |
| P7 Machine-readable errors | n/a | |
| P8 Low hidden context | ✓ | Cross-references to source rationale (reorient + on-dynamics + data-oriented-design) are explicit. |

**Gaps:** none significant. The doc serves its purpose.

### EP 002 — Frames

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ◐ | Two registration shapes (`reg-frame` / `make-frame`) is right. View invocation has *three* (`get-view` / Var / `h` macro) — that's drifty. CP-4 should specify when each is canonical. |
| P2 Named things | ✓ | Frames, handlers, views, fx all stably-id'd. Anonymous lambdas survive only inside view bodies (`:on-click #(dispatch ...)`) which is borderline acceptable. |
| P3 Data before magic | ◐ | Dispatch envelope, effect map, frame metadata all data. **`:fx-overrides` and `:interceptor-overrides` accept *function values*** at the CLJS reference level — not data. Pattern-level contract is now id-based (per the recent edit), but the body of 002 still has function-valued examples prominent. |
| P4 Public query surfaces | ✓ | `handlers`, `frame-meta`, `frame-ids`, `get-frame-db`, `snapshot-of`, `sub-topology` all in. |
| P5 Schemas | ◐ | `:spec` in registration metadata is documented for handlers/subs/fx but the *frame's `:on-create` event* and *override map shape* aren't schema-described. |
| P6 Deterministic execution | ✓ | Run-to-completion drain, depth limit, no-cross-frame-drain — all locked. |
| P7 Machine-readable errors | ◐ | "Errors are maps with documented keys" stated as an aspiration but the actual error shapes aren't enumerated. Needs a §Error contract subsection. |
| P8 Low hidden context | ◐ | **The biggest gap.** React-context-driven `dispatch`/`subscribe` injection is hidden context by definition. Recent reorient edit reframes this as a CLJS optimisation atop an explicit-frame contract, but 002's body still leans on the React-context machinery as primary. Full rewrite of the View Ergonomics section would close this. |
| Constrained model | ✓ | Frame-as-actor-boundary, event-as-FSM-step, drain-as-RtC. |
| Data is code | ✓ | Events, effects, frames, registrations all data interpreted by the runtime. |

**Gaps:**
1. Override seam still presents function-valued as default; pattern-level id-valued should lead the §Per-frame and per-call overrides section.
2. Error contract is gestured at but not specified.
3. View ergonomics section's narrative still reads CLJS-context-primary; needs a top-down rewrite to lead with explicit-frame.
4. View invocation has three forms — pick a canonical and document the others as alternatives.

### EP 004 — Views

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ◐ | Three hiccup-invocation forms (see 002 above). Form-1/2/3 component handling adds three more shape variants for the registration. |
| P2 Named things | ✓ | All views id'd. |
| P3 Data before magic | ✓ | View output is hiccup (data); render-tree contract is serialisable per the recent SSR-audit edit. |
| P4 Public query surfaces | ✓ | View registry queryable via `(handlers :view)`. |
| P5 Schemas | ◐ | `:spec` for the view's *props vector* is documented but most examples don't use it. Construction Prompts CP-4 enforces. |
| P6 Deterministic execution | ✓ | Pure render-tree per pattern contract. |
| P7 Machine-readable errors | n/a | (Errors during render are mostly substrate concerns.) |
| P8 Low hidden context | ◐ | Plain Reagent fns "silently route to `:re-frame/default`" if rendered under a non-default frame. Documented as a known limitation. Removing this footgun (or making it loud at runtime) closes the gap. |
| Constrained model | ✓ | Pure `(state, props) → render-tree`. |
| Data is code | ✓ | Hiccup is the canonical example of data-is-code. |

**Gaps:**
1. Pick a canonical hiccup-invocation form and document the others as alternatives — three forms is a P1 hit.
2. Make the plain-Reagent-fn-routes-to-default footgun loud (warn at render time when a plain fn renders under a non-default frame).
3. Consider whether Form-1/2/3 are all needed or whether Form-2's outer-fn-side-effects should be discouraged in favour of Form-1 + an explicit setup event.

### EP 005 — State Machines

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One transition-table shape; one machine-handler call site; one snapshot shape. |
| P2 Named things | ✓ | Machines, states, transitions, guards, actions all id'd. |
| P3 Data before magic | ✓ | Transition table is data. xstate adoption strengthens this. |
| P4 Public query surfaces | ◐ | Snapshot reachable via `(snapshot-of path)` but the *transition table* registration isn't enumerated as its own kind in the registry. Consider `(handlers :machine)`. |
| P5 Schemas | ◐ | `:spec` for the transition table itself isn't specified — the table has its own grammar (`:initial`, `:states`, `:on`, `:entry`, `:exit`, ...) which is itself a schema-able thing. Worth a Malli schema. |
| P6 Deterministic execution | ✓ | Pure `machine-transition`; finite states; explicit transitions. |
| P7 Machine-readable errors | ✓ | Trace events on machine lifecycle. |
| P8 Low hidden context | ✓ | All machine state lives at the explicit `:machine-path`. |
| Constrained model | ✓ | The strongest example. |
| Data is code | ✓ | Transition tables ARE the canonical "data is code" example for stateful flows. |

**Gaps:**
1. Register transition tables as a queryable `:machine` kind in the registrar.
2. Ship a Malli schema for the transition-table grammar.

### EP 008 — Testing

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One fixture lifecycle (`make-frame` / `destroy-frame`); one synchronous trigger; one assertion macro. |
| P2 Named things | ✓ | Frames, handlers, fixtures all id'd. |
| P3 Data before magic | ✓ | `compute-sub` operates on data; `dispatch-sequence` is a vector of events. |
| P4 Public query surfaces | ✓ | Tests use the same query API tooling does. |
| P5 Schemas | ✓ | Tests against schema-bound paths inherit validation in dev. |
| P6 Deterministic execution | ✓ | Run-to-completion drain makes tests deterministic. |
| P7 Machine-readable errors | ✓ | Test failures route through assertion macros; trace events available. |
| P8 Low hidden context | ✓ | Each test runs in a fresh frame. |

**Gaps:** none significant. EP 008 is the strongest of the existing EPs on AI-first grounds — testing is *intrinsically* AI-amenable when state is bounded.

### EP 009 — Instrumentation

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One trace event shape; one listener API. |
| P2 Named things | ✓ | `:id`, `:operation`, `:op-type` all id'd. |
| P3 Data before magic | ✓ | Trace events are open maps. |
| P4 Public query surfaces | ✓ | The trace stream IS the query surface for runtime behaviour. |
| P5 Schemas | ◐ | Trace event shape has stable required keys but no Malli schema is registered for it. Should be `(rf/reg-app-schema [:re-frame/trace] TraceEventSchema)` or similar. |
| P6 Deterministic execution | ✓ | Per-trace events for every drain step. |
| P7 Machine-readable errors | ◐ | Errors emit trace events but the *error event shape* isn't formally defined alongside the others. |
| P8 Low hidden context | ✓ | All emit sites are visible in source. |

**Gaps:**
1. Register a Malli schema for the trace event shape.
2. Define error trace events as a first-class subset.

### EP 010 — Schemas

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

**Gaps:** validation-error envelope as a structured shape (alongside other errors).

### EP 011 — SSR

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

## Cross-cutting gaps (across multiple EPs)

### G-A. Error envelope is gestured at but not standardised

Errors are mentioned in 002, 009, 010 but no single doc defines the canonical structured-error shape. Proposal: a small section in 009 enumerating error event shapes (validation failure, handler exception, hydration mismatch, drain depth exceeded, override fallthrough), each with a registered Malli schema and a unique `:op-type`. This closes P7 in three EPs simultaneously.

### G-B. Construction prompts not yet wired into pre-flight tooling

Construction-Prompts.md describes how an AI uses the prompts but doesn't specify what API calls satisfy the "verify the id is unused" / "consult registered schemas" pre-flight checks. Each prompt should reference the exact registry-query API (e.g., `(rf/handlers :event)`). CP-1 / CP-4 / CP-6 do this; CP-2/3/5/7/8/9 still need filling in.

### G-C. The override seam is still mixed function/id

Pattern contract is id-based (per recent edits), but the CLJS reference accepts function values. The two forms should be clearly separated — id-valued for portable testing, function-valued for one-off CLJS lambdas — and the documentation should lead with id-valued in the pattern's primary examples.

### G-D. The plain-Reagent-fn footgun

Plain Reagent fns rendered inside a non-default frame silently route to `:re-frame/default`. This violates P8 (hidden context). Either remove the footgun (mandate `reg-view`) or make it loud (runtime warning).

### G-E. View invocation has three forms (P1 violation)

`(get-view :id)`, Var reference, `h` macro. Pick a canonical for primary use; treat the others as alternatives with documented use cases.

### G-F. Form-1 / Form-2 / Form-3 component shapes

Three component shapes inherit from Reagent. Form-2's outer-fn-side-effects pattern violates P8 (the side-effect is invisible from the call site). Consider deprecating Form-2 in favour of Form-1 + an explicit setup event.

## Headline finding

The EPs score uniformly well on P1–P3 (regularity, naming, data-orientation) and P6 (determinism). The recurring weak points are:

- **P7 (machine-readable errors)** — gestured at but not standardised. Worth a focused EP-fragment.
- **P8 (low hidden context)** — the biggest single delta from "current re-frame" to "AI-first re-frame," concentrated in 002's view ergonomics and 004's plain-Reagent-fn footgun.
- **P5 (schemas everywhere)** — the spec hasn't yet been applied to the spec's *own* shapes (trace events, hydration payload, transition tables, error envelopes). Closing this is high-leverage: the schemas become the conformance test corpus implementations validate against (per [reorient.md §Open questions](reorient.md) Q10).

Closing P7 + P5-on-the-spec's-own-shapes is the single most impactful next move.

## Recommended next actions, in priority order

1. **Standardise the error envelope** (G-A). One doc, several Malli schemas, used across 002/009/010/011.
2. **Schema the spec's own shapes** (P5 follow-through). Trace event, hydration payload, transition table, dispatch envelope, registration metadata. Each becomes a registered `app-schema` and a conformance check.
3. **Rewrite 002's View Ergonomics narrative** to lead with explicit-frame and treat React-context as the optimisation (G-C, P8).
4. **Resolve view-invocation regularity** (G-E). Pick the canonical.
5. **Fix the plain-Reagent-fn footgun** (G-D). Loud warning at minimum.
6. **Fill in CP-2/3/5/7/8/9** with the same depth as CP-1/4/6.
