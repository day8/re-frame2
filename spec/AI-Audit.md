# AI-First Audit

> **Type:** Audit
> Applies the nine AI-first properties as a checklist against the re-frame2 corpus — the numbered Specs (000–016) plus the companion documents that participate in the AI-implementable goal (Spec-Schemas, Construction-Prompts, conformance/README). Surfaces gaps for the next round of design work. Per §Coverage policy the audit lags the live numbered set by design: a Spec earns a stand-alone Per-Spec table once its surface has stabilised enough to grade against all nine properties without thrash, and is graded indirectly through the cross-cutting sections until then. Specs 013–016 graduated to stand-alone tables in the 2026-07-04 pass (their surfaces stabilised — 016 declares first-public-beta complete, 013/014 pinned in conformance, 015 landed corpus-wide); Specs 001, 006, 007 remain graded indirectly.

## Scope

This audit grades every artefact in `spec/` that contributes to the **pattern's contract** or to its **AI-implementability**. Specifically:

- **Numbered Specs** (000, 002, 004, 005, 008, 009, 010, 011, 012, 013, 014, 015, 016) — the per-area normative specifications with stand-alone Per-Spec scoring tables below. Specs 001 (Registration), 006 (ReactiveSubstrate), and 007 (Stories) are graded indirectly through the cross-cutting goal sections below; they do not yet have stand-alone Per-Spec scoring tables. (Specs 013–016 graduated to stand-alone tables in the 2026-07-04 pass per [§Coverage policy](#scope) — their surfaces stabilised enough to grade against all nine properties without thrash.)
- **Spec-Schemas** — the spec's own runtime-shape catalogue.
- **Construction-Prompts** — the AI-scaffolding catalogue.
- **conformance/README** — the fixture format and capability-tagging convention.

**Coverage policy.** Per-Spec scoring is intentionally selective: a Spec earns a stand-alone table once its surface has stabilised enough to grade against all nine properties without thrash. New Specs land in the cross-cutting sections first and graduate to a Per-Spec table when the next audit pass adds them — the 2026-07-04 pass graduated Specs 013–016 once that condition fired (016 declares its first-public-beta surface complete; 013/014 are pinned in conformance; 015 landed corpus-wide). The audit therefore lags the live numbered set by design; absence from the Per-Spec list does not mean the Spec is out of scope, only that it is being graded indirectly until the next pass.

Other companion documents (Principles, Conventions, Patterns, MIGRATION, Tool-Pair, Implementor-Checklist, README) are out of scope: they are rationale, conventions, or migration artefacts, not contracts an implementation conforms to.

The audit produces three artefacts:

1. **Per-Spec scoring** — a property-by-property grade per numbered Spec.
2. **Cross-cutting goal sections** — grades a goal (rather than a property) across every Spec that touches it.
3. **Cross-cutting gaps (G-A through G-F)** — issues that recur across Specs; defined once below in §Cross-cutting gaps and referenced from the Per-Spec tables and §Headline finding by their G-letter.

Forward-references to `G-A`/`G-B`/etc. in Per-Spec tables resolve to §Cross-cutting gaps below.

## The nine properties

The grading rubric below is **nine** scorable properties (P1–P9), drawn from the discipline principles in [Principles.md](Principles.md) (the SSOT, which catalogues **11** discipline principles). The rubric is a deliberate scoring subset: two discipline principles — *Uniform context-map callbacks* and *No silent swallow* — are family-level disciplines graded indirectly through the per-Spec notes and the cross-cutting sections rather than as stand-alone score rows, and the two foundational essays (*a simple dynamic model* / *data is code*) are scored as the cross-cutting axis below. The rubric count (9) and the principle count (11) are therefore both correct for their own purpose; they are not in conflict.

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

**Freshness watermark.** Every scoring table below carries an **_As-of `<date>`_** line naming the last audit pass that verified it against the live corpus. A table is only trustworthy as of its watermark: a Spec edited after its table's As-of date may have falsified a row, and an AI reading a stale row generates against a shape the corpus no longer has. The watermark is the audit's own freshness contract — when a Spec change falsifies a row (per the co-edit question in [SPEC-AUTHORING §SA-9](SPEC-AUTHORING.md)), the fixing PR re-verifies the affected table and advances its As-of date. Cross-cutting goal tables and the SA-3 / SA-4 reports carry the same watermark line.

### Spec 000 — Vision

_As-of 2026-07-04._

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

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | Two registration shapes (`reg-frame` / `make-frame`) is right. View invocation has two forms (`view` / Var) — the `h` macro was dropped. |
| P2 Named things | ✓ | Frames, handlers, views, fx all stably-id'd. Anonymous lambdas survive only inside view bodies (`:on-click #(dispatch ...)`) which is borderline acceptable. |
| P3 Data before magic | ◐ | Dispatch envelope, effect map, frame metadata all data. `:fx-overrides` and `:interceptor-overrides` accept function values at the CLJS reference level. Pattern-level contract is id-based; CLJS reference also accepts fn values. |
| P4 Public query surfaces | ✓ | `registrations`, `frame-meta`, `frame-ids`, `app-db-value`, `snapshot-of`, `sub-topology` all in. |
| P5 Schemas | ◐ | `:schema` in registration metadata is documented for handlers/subs/fx but the *frame's `:initial-events`* and *override map shape* aren't schema-described. |
| P6 Deterministic execution | ✓ | Run-to-completion drain, depth limit, no-cross-frame-drain — all locked. |
| P7 Machine-readable errors | ✓ | Error shapes are enumerated in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (the single normative catalogue) and the thrown-error `:rf.error/id` ex-data contract; 002's own error ids (`:rf.error/no-frame-context`, `:rf.error/unknown-preset`, drain-depth) carry catalogue rows (per **G-A**, RESOLVED). |
| P8 Low hidden context | ◐ | **The biggest gap.** React-context-driven `dispatch`/`subscribe` injection is hidden context by definition. The pattern contract is explicit-frame addressing with React context positioned as a CLJS optimisation, but 002's body still leans on the React-context machinery as primary. A full rewrite of the View Ergonomics section would close this. |
| Constrained model | ✓ | Frame-as-actor-boundary, event-as-FSM-step, drain-as-RtC. |
| Data is code | ✓ | Events, effects, frames, registrations all data interpreted by the runtime. |

**Gaps:**
1. ~~Override seam still presents function-valued as default.~~ **Resolved** — the override seam is id-based and canonical-reference-matched, id-valued leading (see **G-C** below, RESOLVED).
2. ~~Error contract is gestured at but not specified.~~ **Resolved** — [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) is the single normative catalogue (see **G-A** below, RESOLVED).
3. View ergonomics section's narrative still reads CLJS-context-primary; needs a top-down rewrite to lead with explicit-frame.
4. View invocation has two forms — Var canonical, `(view :id)` for late-binding. (Tracked as **G-E**; the `h` macro draft was dropped, so two forms is the v1 surface.)

**`:preset` audit row.** Frames declare a `:preset` (`:default`, `:test`, `:story`, `:ssr-server`); the runtime expands and `(frame-meta <id>)` records the applied preset. AI-amenable scaffolding should:

- ✓ Use the locked closed set; never invent unknown preset values (would emit `:rf.error/unknown-preset` at registration).
- ✓ Read the *expanded* metadata to see the effective config rather than re-deriving from the preset name.
- ✓ Override individual keys when the preset's default doesn't fit, rather than hand-rolling a full metadata map.

### Spec 004 — Views

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ◐ | Three hiccup-invocation forms (see 002 above). Form-1/2/3 component handling adds three more shape variants for the registration. |
| P2 Named things | ✓ | All views id'd. |
| P3 Data before magic | ✓ | View output is hiccup (data); render-tree contract is serialisable. |
| P4 Public query surfaces | ✓ | View registry queryable via `(registrations :view)`. |
| P5 Schemas | ◐ | `:schema` for the view's *props vector* is documented but most examples don't use it. Construction Prompts CP-4 enforces. |
| P6 Deterministic execution | ✓ | Pure render-tree per pattern contract. |
| P7 Machine-readable errors | n/a | (Errors during render are mostly substrate concerns.) |
| P8 Low hidden context | ✓ | **Closed by EP-0002.** A plain Reagent fn that can't read its `frame-provider`'s frame no longer silently routes to `:rf/default` — there is no ambient default; its ambient `subscribe`/`dispatch` raise `:rf.error/no-frame-context` (loud at runtime). Frame identity is *carried, not found*. |
| Constrained model | ✓ | Pure `(state, props) → render-tree`. |
| Data is code | ✓ | Hiccup is the canonical example of data-is-code. |

**Gaps:**
1. Pick a canonical hiccup-invocation form and document the others as alternatives — three forms is a P1 hit. (Tracked as **G-E**.)
2. ~~Make the plain-Reagent-fn-routes-to-default footgun loud.~~ **Resolved by EP-0002** (see **G-D** below): there is no ambient `:rf/default`, so a plain fn that can't read the provider's frame raises `:rf.error/no-frame-context` — a structured runtime error, not a warning.
3. ~~Consider whether Form-2's outer-fn-side-effects should be discouraged.~~ **Resolved** — Form-1 is canonical, Form-2 stays supported but Form-1 + an explicit setup event is preferred (see **G-F** below, RESOLVED).

### Spec 005 — State Machines

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One transition-table shape; one `make-machine-handler` call site; one snapshot shape (`{:state :data}`). |
| P2 Named things | ✓ | Machines, states, transitions, guards, actions all id'd. The machine itself is the registered event handler. |
| P3 Data before magic | ✓ | Transition table is data; action and guard slots are fns (per the data-DSL-vs-fn rule), but the references and the structure are data. |
| P4 Public query surfaces | ✓ | A machine *is* an event handler — enumerated via `(registrations :event)`, filterable by `:rf/machine?` metadata; the discovery lens `(rf/machines)` / `(rf/machine-meta id)` makes this a first-class operation. Snapshot reachable via the framework-registered `:rf/machine` parametric sub (`@(rf/subscribe [:rf/machine <machine-id>])`) or `(get-in (runtime-db-value f) [:rf.runtime/machines :snapshots <id>])`; guards/actions are **machine-scoped** — declared in the machine's `:guards` / `:actions` maps and visible via `(machine-meta <id>)` (the registration metadata exposes the transition table including the `:guards` / `:actions` slots). |
| P5 Schemas | ✓ | `:rf/transition-table` and `:rf/machine-snapshot` registered in [Spec-Schemas](Spec-Schemas.md); `:rf.fx/spawn-args` for spawn specs. |
| P6 Deterministic execution | ✓ | Pure `machine-transition`; pure factory `make-machine-handler`; finite states; explicit transitions; deterministic four-level drain. |
| P7 Machine-readable errors | ✓ | Trace events on machine lifecycle; `:rf.error/machine-action-wrote-db`, `:rf.error/machine-raise-depth-exceeded`, `:rf.error/machine-grammar-not-in-v1`, `:rf.error/machine-unresolved-guard`, `:rf.error/machine-unresolved-action`. |
| P8 Low hidden context | ✓ | All machine state lives at the reserved runtime-managed path `[:rf.runtime/machines :snapshots <id>]` in runtime-db. Strict encapsulation: actions and guards see `{:state :data}` only — no `:db`, no cofx. |
| Machine table inspectability | ✓ | Both keyword-reference and inline-fn forms are first-class at the grammar level, but [005 §Inspectability bias](005-StateMachines.md#inspectability-bias) makes named entries in the machine's `:guards` / `:actions` map the **default** form for non-trivial logic. The CP-5 auth-flow example uses keyword references throughout. In the Circle Drawer example in 005 the right-click action (compound three-key seed) is named `:begin-edit` in the machine's `:actions` map, the close-dialog action (emits `:fx` AND clears `:data`) is `:commit`, and the drag-slider and cancel-dialog actions stay inline because their bodies are single non-branching expressions. Both examples uniformly demonstrate the inspectability-bias rule. |
| Constrained model | ✓ | The strongest example. |
| Data is code | ✓ | Transition tables ARE the canonical "data is code" example for stateful flows. |

**Gaps:**
1. Register transition tables as a queryable `:machine` kind in the registrar. (Resolved in part by `(rf/machines)` / `(rf/machine-meta id)` — a derived lens over `(registrations :event)` filtered by `:rf/machine?` metadata; see [005 §Querying machines](005-StateMachines.md#querying-machines).)
2. Ship a Malli schema for the transition-table grammar.

### Spec 008 — Testing

_As-of 2026-07-04._

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

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One trace event shape; one listener API. |
| P2 Named things | ✓ | `:id`, `:operation`, `:op-type` all id'd. |
| P3 Data before magic | ✓ | Trace events are open maps. |
| P4 Public query surfaces | ✓ | The trace stream IS the query surface for runtime behaviour. |
| P5 Schemas | ✓ | Trace event shape has stable required keys and a registered Malli schema — [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md) — one of the five load-bearing schemas carrying Owner/Status/Conformance metadata (see §SA-3 below). The schema is a spec-catalogue entry, **not** `reg-app-schema`: the trace stream is not app-db data (`reg-app-schema` validates app-db paths only), so it is not registered through the app-schema surface. |
| P6 Deterministic execution | ✓ | Per-trace events for every drain step. |
| P7 Machine-readable errors | ✓ | The error event shape is formally defined — [009 §The error event shape](009-Instrumentation.md#the-error-event-shape) + [§Error event catalogue](009-Instrumentation.md#error-event-catalogue) enumerate every category with its `:tags`; `:rf/error-event` + per-category `:tags` schemas registered (per **G-A**, RESOLVED). |
| P8 Low hidden context | ✓ | All emit sites are visible in source. |

**Gaps:**
1. ~~Register a Malli schema for the trace event shape.~~ **Resolved** — [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md) registers it (one of the five load-bearing schemas per §SA-3).
2. ~~Define error trace events as a first-class subset.~~ **Resolved** — [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) defines them as the single normative subset (per **G-A**, RESOLVED).

### Spec 010 — Schemas

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One `:schema` key; one `reg-app-schema` call. |
| P2 Named things | ✓ | Schemas attached to id'd registrations. |
| P3 Data before magic | ✓ | Malli schemas are data. |
| P4 Public query surfaces | ✓ | `(app-schemas)`, `(app-schema-at path)`. |
| P5 Schemas | ✓ | (Self-referential, of course.) |
| P6 Deterministic execution | ✓ | Validation runs at deterministic boundaries. |
| P7 Machine-readable errors | ✓ | The validation-error envelope is a structured shape alongside the other errors — `:rf.error/schema-validation-failure` carries the failing path, value, and explainer output per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (per **G-A**, RESOLVED). |
| P8 Low hidden context | ✓ | Every validation point is explicit. |

**Gaps:** none outstanding. The validation-error envelope is now a structured shape alongside the other errors (per **G-A**, RESOLVED).

### Spec 011 — SSR

_As-of 2026-07-04._

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

### Spec 013 — Flows

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One `reg-flow` map shape; one flow-transform position (outermost `:after`); one dirty-check rule; one topsort per drain. `:rf.fx/reg-flow` / `:rf.fx/clear-flow` are the toggle pair — no second lifecycle surface. |
| P2 Named things | ✓ | Flows, inputs, `:output-path`, `:derive` all id'd; the `:flow` registrar kind is reserved. Registration is frame-scoped, so `(frame-id, flow-id)` is the stable address. |
| P3 Data before magic | ◐ | The flow map, `:inputs` paths, and `:output-path` are data; the dependency graph is statically derivable from them. `:derive` is a fn slot (per the data-DSL-vs-fn rule) — the references and structure are data, the compute step is a host fn. |
| P4 Public query surfaces | ✓ | `(registrations :flow)` (which flows exist), `re-frame.flows/flow-meta-at` and `flows-snapshot` (the frame-scoped `{frame-id {flow-id flow-map}}` view), plus `(sub-cache-consumers-of-path …)` for output consumers. The single-store per-frame registry is the sole authoritative surface — a frame-blind `handler-meta :flow` deliberately returns nil. |
| P5 Schemas | ✓ | `:rf/flow-meta` registers the `reg-flow` map in [Spec-Schemas](Spec-Schemas.md#rfflow-meta); the optional `:schema` key declares a Malli schema for the flow output, validated on every recompute in dev ([§Flow output validation](013-Flows.md#flow-output-validation)). |
| P6 Deterministic execution | ✓ | Pure `:derive`; deterministic topological order each drain; single-pass evaluation (cycles rejected at registration); `=`-equality dirty-check; exactly one `app-db` install per event. |
| P7 Machine-readable errors | ✓ | `:rf.error/flow-cycle` (with `:cycle` closing-repeat vector), `:rf.error/flow-path-overlap`, `:rf.error/flow-frame-not-live`, `:rf.error/flow-bad-marks`, and the run-level `:rf.error/flow-eval-exception` on the always-on error substrate; `:rf.flow/*` trace taxonomy per [009 §Flow trace events](009-Instrumentation.md#flow-trace-events). Registered `:tags` schemas (`FlowCycleTags`, `FlowEvalExceptionTags`). |
| P8 Low hidden context | ✓ | Flow output lives at a known `app-db` path — visible to the inspector, downstream handlers, schemas, and SSR. Input partition is explicit (bare = app-db, `[:rf.db/runtime …]` = runtime-db). The one-event lag on `:rf.fx/reg-flow` is a structural consequence loudly signposted, not hidden. |
| P9 Name over place | ◐ | `:inputs` is a **positional** vector matching `on-changes` and destructured by position in `:derive` — a place-based binding. A map-keyed alternative (name-over-place) is a documented, untracked post-v1 note (falsifiable trigger: N≥3 corpus/consumer flows at 4+ inputs bitten by positional fragility). Ships positional for v1 migration ergonomics. |
| Constrained model | ✓ | One pure function + one output path + a static dependency graph; no parallel scheduler, no side effects, no per-flow `:fx` (all v1-alpha carry-overs dropped). |
| Data is code | ✓ | A flow is a materialised `:after-event` derivation (per [Derivations](013-Flows.md#cross-references)) — the same whole-value function as the equivalent subscription, differing only in storage/evaluation/lifecycle policy. |

**Gaps:**
1. P3/P9 — `:derive` is a host fn (data-DSL-vs-fn rule) and `:inputs` is positional. The map-keyed `:inputs` reconsideration is an untracked post-v1 note with a falsifiable trigger ([013 §Open questions](013-Flows.md#open-questions)); not a v1 blocker.
2. None blocking. The v1 design (vector `:inputs`, pending-`:db`-effect transform as the outermost `:after`, atomic-commit-on-throw, always-on error substrate) is settled in [§Resolved decisions](013-Flows.md#resolved-decisions).

### Spec 014 — HTTP requests

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One fx-id (`:rf.http/managed`), one args map, **one** canonical reply envelope (the framework-wide uniform reply envelope; no second `{:kind :success/:failure}` dialect — the compat reshape is retired). Two reply-addressing forms (two explicit handlers / co-located `:rf/reply` branch) are the documented pair; per-verb call-site helpers (`rf.http/get` …) synthesise the same envelope. |
| P2 Named things | ✓ | The fx, the failure categories (closed `:rf.http/*` set), the `:work/id` `[:rf.work/http …]` head, `:request-id`, and the reply-target spelling `:rf/reply-to` are all stable ids. |
| P3 Data before magic | ✓ | Request envelope, `:retry` policy, `:decode` (schema / keyword / fn), reply envelope, and failure maps are all data; `:accept` / custom `:decode` are the fn slots. |
| P4 Public query surfaces | ✓ | `(handler-meta :event …)` surfaces `:rf.http/decode-schemas` reflection; the in-flight request registry and `:rf.http.interceptor/*` traces are enumerable; every completion rides the trace stream (`:rf.http/replied`). |
| P5 Schemas | ✓ | `:rf/http-managed-meta` (the `reg-fx` metadata incl. `:carriers`), `:rf/reply-map` / `:rf/reply-target` (the uniform envelope, EP-0011), and the `:decode` schema surface are registered in [Spec-Schemas](Spec-Schemas.md#rfhttp-managed-meta); the args map, failure categories, and reply shape are schema-described. |
| P6 Deterministic execution | ◐ | Classification order (status-before-decode), retry/backoff, abort precedence, and the once-only finalisation CAS are deterministic and conformance-pinned. HTTP is inherently a side-effecting external boundary (transport, wall-clock timeouts, host CORS) — the *managed* surface is deterministic given a fixed transport; the transport itself is not. Stub-mode (`with-managed-request-stubs`) makes tests deterministic. |
| P7 Machine-readable errors | ✓ | Eight-category closed `:rf.http/*` failure taxonomy, each failure map self-identifying (request echo + `:request-id` + `:attempt` + `:work/id`); dispatch-time guards (`:rf.error/http-bad-request`, `:rf.error/http-bad-retry-on`, `:rf.error/http-bad-reply-target`) per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). |
| P8 Low hidden context | ✓ | The args map is the whole contract at the call site; per-frame `reg-http-interceptor` is the one seam for cross-cutting request/response transforms (auth headers, base-URL) and is itself id'd and trace-visible. CLJS-only keys emit a `:rf.http/cljs-only-key-ignored-on-jvm` warning rather than silently diverging. |
| P9 Name over place | ✓ | The reply envelope is a named map (`:status` / `:value` / `:error` / `:work/id` / `:correlation`); the request envelope and failure maps are map-keyed. No positional payloads. |
| Constrained model | ✓ | Effect-as-data via the args map; framework-owned lifecycle (retry / abort / teardown); a managed external effect satisfying the nine [Managed-Effects](Managed-Effects.md) properties. |
| Data is code | ✓ | The request, retry policy, and reply are data the runtime interprets; `:decode` as a Malli schema is the canonical data-driven form. |

**Gaps:**
1. P6 — the managed surface is deterministic; the underlying transport is not (inherent to HTTP). Stub-mode covers the test path.
2. None blocking. The v1 contract (closed failure set, one uniform reply envelope, CORS heuristic emission, frame-aware reply dispatch, `:sensitive?` privacy) is settled in [§Resolved decisions](014-HTTPRequests.md#resolved-decisions). Streaming / pluggable backoff are post-v1 open questions.

### Spec 015 — Data Classification

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | **One axis vocabulary** (`:sensitive` / `:large`) over `:rf/path` vectors, in three lowering shapes (commit-plane effects / registration metadata / subsystem projection-relative declarations). One projection primitive (`project-egress` over `elide-wire-value`); one clear-mirrors-set rule per axis. |
| P2 Named things | ✓ | The four commit-plane effects, the `:rf.egress/*` profile enum, the `:rf.observe/*` record kinds, the `:rf.size/*` walker flags, and the sentinels (`:rf/redacted`, `:rf.size/large-elided`) are all reserved, named values. |
| P3 Data before magic | ✓ | Classification is declared data (effect payloads / registration metadata / subsystem declarations) recorded in the per-frame elision registry at `[:rf.runtime/elision …]`; projection is a pure record-level transform. No imperative `add-marks` / `set-marks` API. |
| P4 Public query surfaces | ✓ | The elision registry is runtime-db data (reverts with the frame); `project-egress` and the `:rf.egress/*` profile enum are the enumerable egress surface; every profile is exercised by a real consumer surface. |
| P5 Schemas | ✓ | `:rf/elision-marker` (the `:rf.size/large-elided` shape) and `:rf/project-egress-opts` are registered in [Spec-Schemas](Spec-Schemas.md#rfelision-marker); the `:sensitive` / `:large` axis is `:rf/path` vectors (the shared path algebra, EP-0012). |
| P6 Deterministic execution | ✓ | Projection is pure and value-independent; **sensitive wins over large** is a fixed precedence; fail-closed on unknown frame / profile is deterministic; classification-as-no-op over absent data is well-defined. |
| P7 Machine-readable errors | ✓ | `:rf.error/unknown-egress-profile` (fail-closed on a bad profile), `:rf.error/bad-frame-classification` (a rejected `reg-frame` `:sensitive`/`:large` block), `:rf.error/flow-bad-marks` / malformed-path rejection at registration — structured, not stringy. |
| P8 Low hidden context | ✓ | Classification lives at the fact's **definition site** (the writing handler / the registration / the subsystem definition) — visible where the data's meaning is authored. **No propagation, no taint**: a classification never silently flows input→output; a derived secret is a new path the author classifies directly. On-box reveal is a per-(tool, frame) trace-visible operator act, not a hidden global toggle. |
| P9 Name over place | ✓ | Classifications are `:rf/path` vectors (named-key paths into shapes); the egress choice is a named profile (`:rf.egress/off-box-tool` …), not a remembered boolean combination. |
| Constrained model | ✓ | A declarative leak-prevention overlay on observability — record the path, redact at egress. No runtime cost on the happy path; real values flow through the app unchanged. |
| Data is code | ✓ | Classifications and egress profiles are data the projector interprets at the boundary; the model ships no host-code hooks. |

**Gaps:** none significant. Posture is explicitly **hygiene, not security** ([§The north star](015-Data-Classification.md#the-north-star--a-helper-not-a-contract)); fail-open by design (an unclassified path ships raw). The removed EP-0015 surfaces (frame annotation, schema-prop durable classification, imperative marks, propagation) are catalogued in [§What is removed](015-Data-Classification.md#what-is-removed-and-what-is-kept).

### Spec 016 — Resources

_As-of 2026-07-04._

| Property | Score | Notes |
|---|---|---|
| P1 Regularity | ✓ | One `reg-resource` shape; one scoped resource key `[cache-scope resource-id canonical-params]`; one built-in transport (managed HTTP); one lifecycle transition fn; one canonical reply map (the uniform reply envelope). `reg-mutation` mirrors the `reg-resource` registration gate. |
| P2 Named things | ✓ | Resources, mutations, scope policies, the work ledger, and the `:rf.resource/*` / `:rf.scope/*` / `:rf.work/*` surfaces are all id'd. **One name per fact** (EP-0007): `:resource/key` is the single spelling of the scoped key on data shapes, distinct from `:resource/id`. |
| P3 Data before magic | ✓ | The scoped resource key, cache entries, the work ledger, scope policies, and route `:resources` plans are all serializable EDN data an AI/devtool enumerates; host values (functions, promises, AbortControllers) are rejected from params/scope. |
| P4 Public query surfaces | ✓ | Views read resources through passive subscriptions; the cache lives in the known runtime partition `:rf.runtime/resources`; Xray and SSR enumerate the same scoped-key shapes; the `:rf.runtime/resources` / `:rf.runtime/work-ledger` runtime subsystems each answer the five Runtime-Subsystems clauses (subtree / write authority / read API / projection / teardown). |
| P5 Schemas | ✓ | `:rf/scoped-resource-key`, `:rf/resource-entry`, `:rf/resource-work-record`, `:rf/scope-policy`, `:rf/resource-scope-resolver`, `:rf/invalidation-descriptor`, and `:rf/infinite-resource-args` are all registered in [Spec-Schemas](Spec-Schemas.md#rfscoped-resource-key-rfresource-entry-rfresource-work-record-resources-spec-016); params conform to `:params-schema`. |
| P6 Deterministic execution | ✓ | Canonicalization is a pure function over EDN (key order irrelevant to identity); the mutation attempt order and optimistic-rollback disposal are normative and keyed on recorded facts (work-id + generation verdict + `:revision`) — **no wall-clock race**; the generation allocator is monotonic and host-side, never rewinding across restore. |
| P7 Machine-readable errors | ✓ | Fail-closed registration and use-time errors: `:rf.error/resource-missing-scope-policy`, `:rf.error/resource-bad-spec`, `:rf.error/resource-scope-required-from-caller`, `:rf.error/resource-sub-unresolved-scope`, `:rf.error/resource-invalidate-scope-required`, `:rf.error/infinite-missing-next-page-param` — structured, each pointing at its fix. |
| P8 Low hidden context | ✓ | **No silent default scope** — every resource declares an explicit, auditable scope policy at registration; a user-scoped read can never be silently global. Sub-side scope resolution fails closed (`:rf.error/resource-sub-unresolved-scope`) rather than reading `:idle` or global — the read-side counterpart of the write-side gate. Every resource carries its explicit frame (EP-0002 carried invariant). |
| P9 Name over place | ✓ | The scoped key is `[scope resource-id params]` with map-form scope/params (canonicalized so key order is irrelevant); the resource entry, work record, and reply are map-keyed. The storage tuple vs map-form authoring input is an input-vs-storage distinction (EP-0007 rule 3), not two spellings. |
| Constrained model | ✓ | A runtime-managed read model over a frame work ledger with a compact lifecycle FSM; views are passive, events are causal, server state lives in the framework-owned runtime partition — the re-frame2 answer to TanStack Query / RTK Query / SWR re-expressed in the model. |
| Data is code | ✓ | Resource registrations, scope policies, invalidation descriptors, and route `:resources` plans are data the resource runtime interprets. |

**Gaps:** none blocking for the first-public-beta surface ([§Implementation status](016-Resources.md#implementation-status) — read MVP, mutations, focus/reconnect, optimistic rollback, polling all complete). Post-v1 open questions: the work-ledger multi-writer authority for out-of-artefact writers (`:post-v1 tracked` for v1) and warm-mode route-plan prefetch (`:post-v1 tracked`). GraphQL is a deferred later slice, explicitly out of this contract.

## Cross-cutting goal: AI-implementable from the spec alone

[000-Vision §AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone) (Goal 2) is the meta-property that grades the spec corpus's *completeness* — can an AI build v1 from `/spec/` alone? Audit dimension: **spec self-containedness** — every spec must be readable without consulting re-frame v1 source, and every shape on the wire must be schema'd.

_As-of 2026-07-04._

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Names the goal; explains rationale, what-this-implies, connection-to-other-goals, and failure mode. Capability matrix entries (FSM-richness + actor-model rows) added to the host-profile matrix. |
| 001-Registration | ◐ | Metadata-map shape is well-specced; the *exact* set of metadata keys per `reg-*` kind needs a single canonical table. |
| 002-Frames | ✓ | Drain semantics, envelope shape, view ergonomics all specced; the error contract is now enumerated in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (G-A RESOLVED). |
| 005-StateMachines | ◐ | Foundation specced in detail; capability matrix in place; hierarchical / eventless / delayed / declarative-`:spawn` capabilities are claimed but drain-rule extensions for compound entry/exit are still being elaborated. |
| 008-Testing | ✓ | Three test levels, fixture lifecycle, framework adapters all specced. |
| 009-Instrumentation | ✓ | Trace event shape stable; error event shape formally enumerated in [§Error event catalogue](009-Instrumentation.md#error-event-catalogue) (G-A RESOLVED). |
| 010-Schemas | ✓ | Self-referential — schemas are specced via examples in re-frame2's own conventions. |
| 011-SSR | ◐ | Hydration payload schema missing (existing gap). |
| 012-Routing | ✓ | URL ↔ params grammar, route metadata shape, fx ids all specced. |
| Spec-Schemas | ◐ | Most shapes covered; declarative-`:spawn` schema additions remain to be written. The trace-event, error-event (G-A RESOLVED), and hydration payload schemas are now registered; the remaining gap is the residual declarative-`:spawn` additions. |
| conformance/README | ✓ | Cross-references the goal; capability-tagging convention added to fixture metadata. |

**Gaps:** ~~G-A (error envelope)~~ RESOLVED; ~~G-B (CP depth)~~ RESOLVED. Remaining: schema-on-the-spec's-own-shapes coverage (the declarative-`:spawn` additions).

## Cross-cutting goal: Capability-conformance clarity

[000-Vision §Hierarchical FSM substrate](000-Vision.md#hierarchical-fsm-substrate-with-implementor-chosen-capabilities) (Goal 6) makes conformance graded against the implementor's claimed capability list. Audit dimension: **capability-conformance clarity** — does each spec section name which capabilities it depends on?

_As-of 2026-07-04._

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Goal text + the FSM-richness / actor-model breakdown name each capability and its v1-claim status. Host-profile matrix has capability-list rows. |
| 005-StateMachines | ✓ | §Capability matrix is the canonical list; v1 grammar subset table aligned with the matrix; parallel regions, tags, and history states are now first-class capabilities (the earlier snapshot-as-value history substitute is withdrawn). |
| Spec-Schemas | ✓ | `:rf/transition-table` schema covers flat / hierarchical / eventless / delayed / declarative-`:spawn` / `:tags` / `:type :parallel` + `:regions`; `:rf/machine-snapshot` widened to the third `:state` arm for parallel regions. |
| Construction-Prompts | ✓ | CP-5 forward-points at the parallel-regions first-class capability and the N-machines substitute for conceptually-independent features, plus the first-class history-states grammar (`:type :history`). |
| conformance/README | ✓ | Capability-tagging convention specifies how fixtures self-declare; harness runs only the matching subset. |
| Other Specs (002, 008, 009, 011, 012) | n/a | Capability-list scoping is FSM-and-actor-specific; other Specs aren't capability-graded in the same way (they are pattern-required as a whole). |

**Gaps:** none currently outstanding for the FSM-richness / actor-model schema surface.

## Cross-cutting goal: Frame state revertibility

[000-Vision §Frame state revertibility](000-Vision.md#frame-state-revertibility) (Goal 3) is a top-level goal that several Specs are responsible for satisfying together. Audit summary:

_As-of 2026-07-04._

| Spec | Score | Notes |
|---|---|---|
| 000-Vision | ✓ | Names the goal; explains rationale, implications, and the boundary at the registered-fx seam (external side effects need compensation, not reversal). Marks persistent data structures as pattern-required in the host-profile matrix. |
| 002-Frames | ✓ | Frame state is one persistent value (`app-db`, router, sub-cache, lifecycle). Run-to-completion drain provides atomic per-event transitions — every settled state is a snapshottable boundary. The two-tier registry (central boot-time vs frame-local) is articulated under §State machines are just event handlers; frame-local registrations live inside the frame's value. The epoch surface (`epoch/restore-epoch!` + `epoch/replace-app-db!`) exposes the goal as user-facing API. |
| 005-StateMachines | ✓ | Machine snapshots live at `[:rf.runtime/machines :snapshots <id>]` inside the frame's runtime-db partition. Strict encapsulation prevents leakage outside the frame value. Spawn registers in the frame-local tier so undo rolls back spawned actors with their snapshots. §What the Single Store gives us for free enumerates the concrete revertibility wins (undo/redo, time-travel, persistence, snapshot-and-replay). |
| 008-Testing | ✓ | Per-test frames + value-shape rollback via `epoch/replace-app-db!` rely on the goal directly; teardown is "drop the frame's value." |
| 011-SSR | ✓ | Hydration replaces `app-db` with the server-supplied value; machine snapshots ride along. SSR is a special case of "snapshot-and-replay." |
| 006-ReactiveSubstrate | ✓ | [006 §Revertibility constraints on adapters](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters) locks the rule: adapter-internal state is allowed iff derivable from the frame's value. Memoisation caches, reaction caches, and listener registries are derivable; non-derivable observer state is prohibited. Reagent and plain-atom reference adapters audited compliant. DOM render output sits at the registered-fx seam (per Goal 3) and is out of scope for frame-state revert. |
| 009-Instrumentation | ✓ | Trace stream is append-only and not part of frame state, so revert is well-defined: "drop trace events after timestamp T" is a separate operation from frame-state revert. |
| 010-Schemas | n/a | Schemas are static registry data; not in scope for frame revert. |
| 012-Routing | ✓ | The route slice lives in the frame's **runtime-db** partition at `[:rf.runtime/routing :current]` (per [012 §`runtime-db` slices](012-Routing.md#runtime-db-slices) and [Conventions §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)); runtime-db is part of the frame value, so route state reverts with the rest of the frame on epoch restore. |

**Gaps:** none. Spec 006's §Revertibility constraints on adapters closes the previously-noted gap; every Spec scores ✓ on Goal 3 alignment.

## Cross-cutting gaps (across multiple Specs)

### G-A. Error envelope standardised — RESOLVED

Errors used to be gestured at in 002, 009, 010 with no single doc defining the canonical structured-error shape. **[009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) closes this**: it is the single normative catalogue of every error / warning / advisory event, enumerating each category (validation failure `:rf.error/schema-validation-failure`, handler exception `:rf.error/handler-exception`, hydration mismatch, drain depth exceeded `:rf.error/drain-depth-exceeded`, override fallthrough, …) with its `:op-type`, channel, default `:recovery`, and `:tags` payload. The canonical structured shapes are defined at [009 §The error event shape](009-Instrumentation.md#the-error-event-shape) (the trace shape) and [009 §The thrown-error shape — the `:rf.error/id` ex-data contract](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract) (the thrown contract; `:rf.error/id` is the single normative discriminator). Registered Malli schemas: `:rf/error-event` plus per-category `:tags` schemas in [Spec-Schemas](Spec-Schemas.md#rferror-event). P7 closed in three Specs simultaneously, as the proposal intended.

### G-B. Construction prompts wired into pre-flight tooling — RESOLVED

Construction-Prompts.md used to describe how an AI uses the prompts without specifying the API calls that satisfy the "verify the id is unused" / "consult registered schemas" pre-flight checks. **[Construction-Prompts §Shared pre-flight (applies to every CP)](Construction-Prompts.md) closes this**: a single shared pre-flight preamble gives *every* CP the exact registry-query API — `(rf/registrations :event)` / `:sub` / `:fx` / `:view` / `:route` for the id-uniqueness check, and `(rf/app-schemas)` / `(rf/app-schema-at <path>)` for the schema-consult check. Each CP now carries only a "Pre-flight delta" over the shared preamble, so the earlier per-CP gap (CP-2/3/5/7/8/9 missing the API) is closed structurally by hoisting it into the shared block.

### G-C. The override seam is id-based and canonical-reference-matched — RESOLVED

The pattern contract was id-based while the CLJS reference also accepted function values, with no clear separation. **[008-Testing §Disabling a logging interceptor in tests](008-Testing.md#disabling-a-logging-interceptor-in-tests) closes this**: `:interceptor-overrides` is **reference-based** (a bare keyword matches the registered interceptor; a parameterized `[id arg]` 2-vector matches the full reference), matched by the canonical (CEDN-1) reference — the id-valued (portable) form leads the primary examples, and function values appear only as the one-off `:fx-overrides` HTTP-stub lambdas. [Conventions §Reserved fx-id override tiering](Conventions.md#reserved-fx-id-override-tiering) adds the complementary tiering (reserved fx tiered OVERRIDABLE vs REJECT by the state-installation criterion, with a dedicated `Override tier` column in the reserved-fx table).

### G-D. The plain-Reagent-fn footgun — RESOLVED (EP-0002)

Plain Reagent fns rendered inside a `frame-provider` used to silently route to `:rf/default` (a P8 hidden-context violation). **EP-0002 closes this**: there is no ambient `:rf/default`, so a plain fn that can't read the provider's frame raises `:rf.error/no-frame-context` — the footgun is now loud at runtime (the "make it loud" resolution, taken to its strongest form: a structured error, not a warning). Fix at the call site is `reg-view`, `with-frame`, or a captured `capture-frame`.

### G-E. View invocation has two forms — Var canonical, `(view :id)` for late-binding

The Var reference (`[counter "Hello"]`) is the canonical call-site form: `reg-view` defs the symbol, hiccup picks it up directly. `(view :id)` is the documented escape hatch for late-binding by id (cross-module reference, runtime-computed ids, hot-reload-sensitive call sites). The earlier `h` macro draft has been dropped; two forms is the v1 surface.

### G-F. Form-1 / Form-2 / Form-3 component shapes — RESOLVED

Three component shapes inherit from Reagent, and Form-2's outer-fn-side-effects pattern violates P8 (the mount-time side-effect is invisible from the call site). **[004-Views §Form-1, Form-2, Form-3 components](004-Views.md#form-1-form-2-form-3-components) closes this** with a "supported, prefer Form-1" resolution rather than deprecation: Form-1 is canonical; Form-2 stays **supported** for Reagent compatibility but is used only when the setup work genuinely depends on per-mount props, with the [Form-2 (closure — supported, prefer Form-1 + explicit setup event)](004-Views.md#form-2-closure--supported-prefer-form-1--explicit-setup-event) subsection steering stable setup to Form-1 + an explicit setup event / the frame's `:initial-events`. The P8 concern is addressed by preferring the visible form, keeping Form-2 available where it earns its keep.

## Headline finding

The Specs score uniformly well on P1–P3 (regularity, naming, data-orientation) and P6 (determinism). The historically-weak P7 (machine-readable errors) and P8 (low hidden context) closed materially in the 2026-07-04 pass: **G-A** (the [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) standardises the structured-error shape across 002/009/010), **G-D** (the plain-Reagent-fn footgun is now a loud `:rf.error/no-frame-context`), and **G-F** (Form-1 preferred over Form-2's hidden mount-time side-effect) all landed. All six cross-cutting gaps (G-A through G-F) are now RESOLVED. The remaining weak point is P5 (schemas applied to the spec's own shapes) — the residual declarative-`:spawn` schema additions and the SA-3 sweep tracked below. The cross-cutting gaps section above enumerates the specific findings.

## SA-3 schema-coverage report

[SPEC-AUTHORING.md §SA-3](SPEC-AUTHORING.md) commits the corpus to: "Every shape that flows on the wire or appears in a spec example MUST have a schema in [Spec-Schemas.md](Spec-Schemas.md)." This report is the audit's running cross-reference table: every shape-shaped artefact named in the numbered specs MUST map to one of (a) a `:rf/<id>` schema entry in Spec-Schemas.md, (b) an explicit host-type exemption (a host's primitive that doesn't need cross-host schema coverage), or (c) a generated EDN catalogue derived from the corpus.

_As-of 2026-07-04._

**Current state.** The shape catalogue carries **52 schema sections** (`###` headings), of which **50 are schema-bearing** (the other two are prose sub-headings). The per-section Owner / Status projection metadata that makes the projection auditable — required per [Spec-Schemas §Traceability metadata](Spec-Schemas.md#traceability-metadata) — is now present on **all 50 schema-bearing sections** (the full sweep has landed, superseding the earlier five-schema demonstration on `:rf/dispatch-envelope`, `:rf/effect-map`, `:rf/trace-event`, `:rf/epoch-record`, `:rf/hydration-payload`). Of those, **9 also carry the optional Conformance pointer** (present when a fixture / per-artefact test asserts the schema directly; the SA-3 header spec marks Conformance "when fixture/test exists", not universally required).

**Audit cadence.** This report is regenerated per AI-Audit run. Per-section completeness gates on SA-3:

| Section count | SA-3 status |
|---|---|
| 50 / 50 | Owner + Status present (the required projection metadata — full sweep landed) |
| 9 / 50 | Conformance pointer also present (optional — where a fixture/test asserts the schema directly) |

The Owner + Status sweep obligation from the prior pass is **discharged**: every schema-bearing section now names its canonical owning spec and its API-status tier, so a reader maps any schema row to its owner and status without consulting the source docs. The residual work is additive Conformance pointers as fixture coverage grows, not a pending Owner/Status sweep.

**SA-3 violation rule.** A spec example or wire payload that does NOT map to either a schema entry or an explicit host-type exemption is an SA-3 violation. The fix is to add the missing entry to Spec-Schemas.md (not to add an exemption). Per-cycle the audit names any newly-surfaced violations under this section. **No new violations surfaced in the 2026-07-04 pass** — the 013/014/015/016 shapes referenced by the newly-graduated Per-Spec tables (`:rf/flow-meta`, `:rf/http-managed-meta`, `:rf/reply-map`, `:rf/scoped-resource-key` / `:rf/resource-entry` / `:rf/resource-work-record` / `:rf/scope-policy` / `:rf/infinite-resource-args`, `:rf/elision-marker` / `:rf/project-egress-opts`) all already carry catalogue entries.

**Scope clarification.** This report covers shapes that flow *between* implementation surfaces (wire payloads, returned shapes, registration metadata). It does NOT cover host-primitive shapes (a CLJS map, a TypeScript object type) — those are local to the host's type system and need no cross-host schema. Per [§Scope](#scope), per-Spec scoring is selective; the SA-3 report is corpus-wide and is the SA-3 enforcement surface.

## SA-4 open-questions report

[SPEC-AUTHORING.md §SA-8](SPEC-AUTHORING.md) commits the AI-Audit pass to "produce a corpus-wide report enumerating every `## Open questions` heading across the per-Spec docs, with each item's SA-4 classification (one of `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking`) and its required cross-link." SA-8 names this report's home as the AI-Audit doc; this section is that home.

_As-of 2026-07-04._

**Current state.** Sixteen numbered/companion docs carry a `## Open questions` heading (000, 001, 002, 004, 005, 006, 007, 008, 009, 010, 011, 012, 013, 014, 016, and Design-TransducerRouter). The per-item SA-4 classification enumeration — the table of every open-question item with its `:resolved` / `:host-choice` / `:post-v1 tracked` / `:still-blocking` verdict and cross-link — has **not yet been generated**; it is the next AI-Audit sweep's deliverable and is the open SA-8 obligation. Until that sweep lands, SA-4 compliance is verified per-Spec by narrative review (the prior pass moved the three overdue `(RESOLVED)`-labelled specs — 002, 005, 013 — out of `## Open questions`), not mechanically through this table.

**Audit cadence.** This report is regenerated per AI-Audit run. A `:resolved` item still sitting under `## Open questions`, or a `:post-v1 tracked` item lacking a tracker reference, is an SA-4 violation surfaced here once the per-item enumeration is in place.
