# re-frame2 Specification

> **Type:** Reference
> Index of the design documents for re-frame2 — a re-frame-flavoured pattern for building SPAs, plus its Clojure/CLJS reference implementation.

The **load-bearing decisions** live in [000-Vision.md](000-Vision.md). Each per-area Spec consumes those decisions; downstream Specs do not reshape the foundation. The **AI-first practical principles** are collected in [Principles.md](Principles.md).

## How the corpus is organised

The directory has two orthogonal axes:

1. **Bucket axis (normative vs. companion).** Numbered Specs (000–014) plus [API.md](API.md) and [MIGRATION.md](MIGRATION.md) define the contract. Everything else explains, scaffolds, validates, or demonstrates that contract.
2. **Layer axis (foundation / capability / companion).** Three explicit layers structure the corpus:

| Layer | What it contains | Role |
|---|---|---|
| **Foundation** | [000-Vision](000-Vision.md), [001-Registration](001-Registration.md), [002-Frames](002-Frames.md) | Load-bearing decisions every other doc consumes — goals, identity primitive, registration grammar, frame model, dispatch envelope, drain semantics. Reshape these and the rest of the corpus moves. |
| **Capability** | [004-Views](004-Views.md), [005-StateMachines](005-StateMachines.md), [006-ReactiveSubstrate](006-ReactiveSubstrate.md), [007-Stories](007-Stories.md), [008-Testing](008-Testing.md), [009-Instrumentation](009-Instrumentation.md), [010-Schemas](010-Schemas.md), [011-SSR](011-SSR.md), [012-Routing](012-Routing.md), [013-Flows](013-Flows.md), [014-HTTPRequests](014-HTTPRequests.md) | Per-area normative Specs. Each consumes Foundation; none reshapes it. Optional capabilities (see the [host-profile matrix](000-Vision.md#host-profile-matrix)) live here. |
| **Companion** | [API](API.md), [MIGRATION](MIGRATION.md), [Principles](Principles.md), [Conventions](Conventions.md), [Construction-Prompts](Construction-Prompts.md), [AI-Audit](AI-Audit.md), [Tool-Pair](Tool-Pair.md), [Implementor-Checklist](Implementor-Checklist.md), [Runtime-Architecture](Runtime-Architecture.md), [Cross-Spec-Interactions](Cross-Spec-Interactions.md), [Ownership](Ownership.md), [Spec-Schemas](Spec-Schemas.md), [Conformance Corpus](conformance/README.md), the `Pattern-*` docs, [worked examples](../examples/) | Supporting / scaffolding / verifying material. None *defines* the contract. API.md and MIGRATION.md are normative companions — normative for what they describe but deferring to the per-Spec doc for design rationale. |

Within the Companion layer, each doc carries a **Type** (the companion-document genre axis): Reference, Migration, Convention, Construction Prompts, Audit, Schemas, or Pattern. Every doc declares its Type via the `> **Type:**` header. The Foundation and Capability layers do not carry a Type header — their layer is determined by numbering. The Type vocabulary is closed for v1; new Types require a companion-doc convention update.

**About Spec 003.** The numbering 000–014 has one gap: there is no `003-*.md`. Slot 003 is **reserved** for a future Spec on cross-frame composition (frame supervisors, parent/child frame relationships, frame-graph topology) — design work that depends on Specs 002 and 005 being settled. The slot is held open so existing Spec numbers do not need to renumber when 003 lands.

## Documents

### Foundation layer (load-bearing decisions)

| # | Title | One-liner |
|---|---|---|
| 000 | [Vision](000-Vision.md) | Canonical owner of goals, hard constraints, the pattern's minimal core, scope, retained-from-re-frame. Every other doc consumes this. |
| 001 | [Registration](001-Registration.md) | The metadata-map shape that every `reg-*` accepts. Standard keys; two-form middle slot; source-coord capture via macros (CLJS reference); query API; hot-reload semantics. |
| 002 | [Frames](002-Frames.md) | Frame as isolated runtime boundary. Pattern contract: explicit-frame addressing. CLJS reference: React context as an optimisation. Keyword identity, `:rf/default` fallback, run-to-completion drain, machines-as-event-handlers foundation hooks, id-valued override seam. |

### Capability layer (per-area normative Specs)

| # | Title | One-liner |
|---|---|---|
| 004 | [Views](004-Views.md) | Pattern view contract: pure `(state, props) → render-tree`. CLJS reference: `reg-view` with frame-bound `dispatch`/`subscribe`; Form-1 canonical; Form-2/3 escape hatches. SSR-renderable per [011](011-SSR.md). |
| 005 | [State Machines](005-StateMachines.md) | Transition-table grammar layered on 002's machines-as-event-handlers hooks. |
| 006 | [Reactive Substrate](006-ReactiveSubstrate.md) | Substrate-agnostic core + adapter contract. Reagent default; plain-atom for JVM/SSR/headless. |
| 007 | [Stories, Variants, and Workspaces](007-Stories.md) | Storybook/Histoire/devcards-class tooling. Story / Variant / Workspace split. Builds on 008. |
| 008 | [Testing](008-Testing.md) | Owns the testing infrastructure surface. Test fixtures, synchronous triggers, per-test fx/interceptor stubbing, headless sub/machine evaluation, framework adapters, JVM-runnable test suites. 007 cross-references 008 for portable-stories-as-tests. |
| 009 | [Instrumentation, Tracing, Performance](009-Instrumentation.md) | Trace event model, listener API, structured error contract, per-frame `:on-error` policy mechanism, forward-compat with 10x / re-frame-pair. |
| 010 | [Schemas (CLJS reference)](010-Schemas.md) | Malli-based schemas via `:spec`; path-based `app-db` schemas via `reg-app-schema`; validation timing, dev-vs-prod elision. *Schemas are opt-in at the pattern level.* |
| 011 | [SSR & Hydration](011-SSR.md) | Server frame lifecycle (per-request), pure hiccup → HTML emitter (JVM-runnable), `:platforms` metadata, `:rf/hydrate` event, hydration-mismatch detection. |
| 012 | [Routing](012-Routing.md) | URL ↔ frame state contract. Routes are registry entries; navigation is an event; `:route` is a sub. Same handler runs server- and client-side. |
| 013 | [Flows](013-Flows.md) | Registered, runtime-toggleable computed-state declarations that materialise into `app-db`. v2 incarnation of v1's `on-changes` interceptor — same compute-on-input-change semantics, but registered (not on individual events) and toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`. **Convenience for narrow use-cases; not a sub replacement.** |
| 014 | [HTTP requests](014-HTTPRequests.md) | The `:rf.http/managed` request fx (optional capability) — args-map shape, decode pipeline, `:accept` step, retry-with-backoff, abort, frame-aware reply addressing, eight-category failure taxonomy under `:rf.http/*`. Specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md); pins [Pattern-RemoteData](Pattern-RemoteData.md)'s lifecycle slice. The CLJS reference ships it on Fetch + JVM `HttpClient`. |

### Companion layer (rationale, scaffolding, verification, examples)

Normative companions (defer to per-Spec doc for design rationale):

| Title | Type | One-liner |
|---|---|---|
| [API](API.md) | Reference | Consolidated reference: signatures, status, cross-references. |
| [MIGRATION](MIGRATION.md) | Migration | Migration spec for upgrading re-frame v1.x codebases (CLJS reference). |

Reference, scaffolding, and audit:

| Title | Type | One-liner |
|---|---|---|
| [Principles](Principles.md) | Reference | The 9 AI-first discipline principles plus the two foundational essays (the layered constraint, data is code). The yardstick the [AI-Audit](AI-Audit.md) grades Specs against. |
| [Conventions](Conventions.md) | Convention | Locked runtime conventions: reserved-namespace policy for framework-owned ids, ns-prefix conventions for features, reserved fx-ids and app-db keys. |
| [Managed external effects](Managed-Effects.md) | Reference | The unifying conceptual frame: eight properties every framework-owned managed-effect surface (`:rf.http/managed`, `:rf.ws/*`, `:invoke` / `:invoke-all`, `:rf.server/*`, `:rf.flow/*`) inherits. New surfaces (managed timers, managed background jobs) are graded against the same checklist. |
| [Construction Prompts](Construction-Prompts.md) | Construction Prompts | Per-kind AI-scaffolding templates (event, sub, fx, view, machine, feature, route, schema, SSR). Sibling to MIGRATION.md. |
| [AI Audit](AI-Audit.md) | Audit | Score of every Spec against the AI-first discipline principles; surfaces gaps. |
| [Runtime-Architecture](Runtime-Architecture.md) | Reference | Bird's-eye view of the runtime — the eight components (registrar, frame container, router, drain loop, `do-fx`, sub-cache, substrate adapter, trace bus) plus the interop layer, with data-flow diagram and per-event lifecycle. Companion to the per-area Specs; redefines nothing. |
| [Cross-Spec Interactions](Cross-Spec-Interactions.md) | Reference | Edge cases at the boundaries between Specs — frame disposal with active machines, machines under SSR, routing in SSR, plain-fn under non-default frame, machine action throws, hot-reload mid-cascade, etc. Each interaction names which Specs meet, the scenario, the decided behaviour, the reason. |
| [Cross-Cutting Designs](Cross-Cutting-Designs.md) | Reference | Inventory of design surfaces that span multiple Specs / tool artefacts / skills — wire elision, retro protocol, token budgets, MCP-tool naming, origin tagging. Each entry names the canonical home, the consumers, and the resulting shape. Non-normative; the cited home wins on drift. |
| [Ownership matrix](Ownership.md) | Reference | "Where does X live?" — the contract-surface → owning-Spec → companion-citations table. Single source of truth; drift detector when a definition appears in a non-owning doc. |
| [Tool-Pair](Tool-Pair.md) | Reference | Runtime contract for pair-shaped AI tools (re-frame-pair equivalents): inspect, dispatch, hot-swap, time-travel, fx-stub, source-map. |
| [Implementor's Checklist](Implementor-Checklist.md) | Reference / Companion | Decision-ordered companion to [000 §Host-profile matrix](000-Vision.md#host-profile-matrix). Part 1 (capability declarations), Part 2 (per-capability technology choices with options-by-host), Part 3 (conformance against the claimed list). The structured form of [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone). |
| [Spec-Internal Schemas](Spec-Schemas.md) | Schemas | Malli (CLJS reference) shape descriptions for the spec's own runtime shapes. Static hosts express the same shapes as types. |
| [Conformance Corpus](conformance/README.md) | Reference | Fixture-based test suite: canonical interactions in EDN, expected emissions in EDN. Verifies the "AI can one-shot the implementation in any host" claim. |

Patterns (worked-example conventions, not Specs):

| Title | Type | One-liner |
|---|---|---|
| [Pattern — Async Effect](Pattern-AsyncEffect.md) | Pattern | The canonical six-step "register fx → return `:fx` → post work → async reply → dispatch → commit" shape every async-effecting feature follows. Foundational; specific patterns (RemoteData, WebSocket, Boot) cite it. |
| [Pattern — Nine States of UI](Pattern-NineStates.md) | Pattern | Page-level convention for rendering blank / loading / cardinality / validation / success / terminal states explicitly by composing RemoteData, Forms, and machine/domain state. |
| [Pattern — Remote Data](Pattern-RemoteData.md) | Pattern | Standard request-lifecycle slice + four standard events; SSR-friendly. The HTTP-specific case of Pattern-AsyncEffect. Convention, not Spec. |
| [Pattern — Reusable Components](Pattern-ReusableComponents.md) | Pattern | The entity-id idiom for parameterised widgets — a `customer-card` that works against any customer, where the caller supplies the id and the card subscribes and dispatches in terms of it. Multi-identity, adapter-agnostic, placefulness discussion. Convention, not Spec. |
| [Pattern — Forms](Pattern-Forms.md) | Pattern | Standard form slice + seven standard events; per-field error display only on touched. Convention, not Spec. |
| [Pattern — Stale Detection](Pattern-StaleDetection.md) | Pattern | The cross-cutting epoch idiom: capture, carry, check, suppress. Used by `:after` timers, navigation tokens, and any future async-shaped feature. |
| [Pattern — Boot](Pattern-Boot.md) | Pattern | Application boot as a chained-async sequence — chained-events form for trivial boots; state-machine canonical form (config → auth → profile → hydrate → route → ready) for non-trivial. SSR handoff and hot-reload rules included. |
| [Pattern — WebSocket](Pattern-WebSocket.md) | Pattern | Long-lived connection lifecycle (WebSocket / SSE / WebRTC peer) as a state machine — `:disconnected` / `:connecting` / `:authenticating` / `:connected` / `:reconnecting` / `:failed`. Worked example of hierarchical states, `:after`, `:always`, and `:invoke` composed. |
| [Pattern — Long-Running Work](Pattern-LongRunningWork.md) | Pattern | Modernised guidance for handling CPU-intensive work without freezing the UI. Decision tree (offload vs chunk on main thread); state-machine canonical form for chunked work with `:always` for batch progression and `:after 0` for browser yielding; cancellation via state transition; replaces the v1 `^:flush-dom` metadata. |
| [Pattern — SSR Loaders](Pattern-SSR-Loaders.md) | Pattern | Parallel data-fetch fan-out for an SSR request — N HTTP fetches spawned via `:invoke-all`, joined on all-complete, results written to `app-db` before `render-to-string` runs. Composes with `:rf.server/request` cofx (request-derived inputs) and `:rf.http/managed` (per-child retry / abort / decode). Same machine drives client-side navigation-fetch. |
| [Pattern — Form Action](Pattern-FormAction.md) | Pattern | Canonical SSR form-POST handling — `method="POST" action="/<route>"`, host adapter parses form-encoded / multipart body, `:rf/server-init` routes POST to a domain event, action handler validates via `:spec`, emits `303 See Other` on success or re-renders with errors on failure. Covers CSRF, file uploads, progressive enhancement, and the same-handler-tree-both-sides contract. |

Worked examples:

| Title | Type | One-liner |
|---|---|---|
| [Worked examples](../examples/) | Reference | Browser-runnable apps spanning the construction-prompt surface across substrates — Reagent: [counter](../examples/reagent/counter/), [counter_slim_and_fast](../examples/reagent/counter_slim_and_fast/), [counter_with_stories](../examples/reagent/counter_with_stories/README.md), [login](../examples/reagent/login/core.cljs), [todomvc](../examples/reagent/todomvc/README.md), [routing](../examples/reagent/routing/), [ssr](../examples/reagent/ssr/), [managed_http_counter](../examples/reagent/managed_http_counter/), [state_machine_walkthrough](../examples/reagent/state_machine_walkthrough/), [nine_states](../examples/reagent/nine_states/README.md), [boot](../examples/reagent/boot/README.md), [long_running_work](../examples/reagent/long_running_work/README.md), [websocket](../examples/reagent/websocket/README.md), the [7GUIs cluster](../examples/reagent/7Guis/README.md) (temperature / flight booker / timer / crud / circle drawer / cells), and [realworld](../examples/reagent/realworld/README.md); UIx: [counter](../examples/uix/counter_uix/), [login](../examples/uix/login_uix/); Helix: [counter](../examples/helix/counter_helix/), [login](../examples/helix/login_helix/). Each is paired with a Playwright smoke spec; the catalogue at [examples/README.md](../examples/README.md) maps each example to the Specs it exercises. |

## Reading paths

The corpus serves three distinct audiences. Pick the track that matches your goal; tracks are intentionally non-overlapping in their primary content.

### Track 1 — Canonical (the contract)

For implementors and AI agents reading the contract in its normative reading order.

1. [000-Vision.md](000-Vision.md) — goals, hard constraints, the minimal-core contract, identity-primitive properties.
2. [001-Registration.md](001-Registration.md) — registration grammar; the metadata-map shape every `reg-*` accepts.
3. [002-Frames.md](002-Frames.md) — frames, dispatch envelope, drain semantics, machines-as-event-handlers hooks.
4. Capability Specs in numeric order: [004](004-Views.md), [005](005-StateMachines.md), [006](006-ReactiveSubstrate.md), [007](007-Stories.md), [008](008-Testing.md), [009](009-Instrumentation.md), [010](010-Schemas.md), [011](011-SSR.md), [012](012-Routing.md), [013](013-Flows.md), [014](014-HTTPRequests.md). Each is independent of the others (with explicit cross-references where needed).
5. [API.md](API.md) — consolidated signature reference.
6. [Spec-Schemas.md](Spec-Schemas.md) — canonical runtime shapes.
7. [conformance/](conformance/README.md) — fixtures the contract is verified against.

### Track 2 — Background (rationale and design history)

For readers wanting the *why* before (or beside) the *what*. Optional in the strict sense — every normative claim in Track 1 stands without these — but they make the corpus easier to absorb.

1. **Optional upstream rationale (re-frame v1 doc set):** [on-dynamics](https://day8.github.io/re-frame/on-dynamics/), [data-oriented-design](https://day8.github.io/re-frame/data-oriented-design/), [a-loop](https://day8.github.io/re-frame/a-loop/), [application-state](https://day8.github.io/re-frame/application-state/). Why the v1 pattern has the shape it does. Skip if already familiar.
2. [Principles.md](Principles.md) — the 9 AI-first discipline principles + the two foundational essays.
3. [AI-Audit.md](AI-Audit.md) — how each Spec scores against the principles; surfaces gaps.
4. [Conventions.md](Conventions.md) — locked runtime conventions (reserved namespaces, fx-ids, `app-db` keys).
5. The `Resolved decisions` and `Open questions` appendices in each numbered Spec — design-history records embedded next to the contract.

### Track 3 — Implementor (porting to a new host)

For implementors taking the pattern to TypeScript, Python, Kotlin, Rust, or any other host. Substantial — most of the artefacts already exist; this track sequences them explicitly.

1. [000 §Host-profile matrix](000-Vision.md#host-profile-matrix) — pattern-required vs. host-discretion vs. CLJS-only, row by row. The single source of truth for "must I ship this?"
2. [Implementor-Checklist.md](Implementor-Checklist.md) — the **decision-ordered companion** to the matrix. Part 1 declares which optional capabilities the implementation includes; Part 2 walks each capability's technology and library choices with options-by-host; Part 3 explains conformance against the claimed list.
3. [Principles.md](Principles.md) — the discipline your implementation should bias toward (especially the "open maps with schemas," "data is code," and "registry-as-truth" essays).
4. [Spec-Schemas.md](Spec-Schemas.md) — the canonical shapes your runtime must produce. In static hosts, these become types.
5. [009 §Error contract](009-Instrumentation.md#error-contract) — the structured-error trace surface every implementation supplies.
6. [011-SSR.md](011-SSR.md) — server/client lifecycle; even non-SSR implementations consume the `:platforms` and frame-per-request rules.
7. [Construction-Prompts.md](Construction-Prompts.md) — the user-facing scaffolding surface; adapt to your host's idioms.
8. [conformance/README.md](conformance/README.md) — fixture-based verification suite. Run the subset matching your declared capabilities (Implementor-Checklist Part 3).

## Ownership matrix

Every contract surface in re-frame2 is owned by exactly one normative Spec. The full **owner → companion-citations** mapping lives in **[Ownership.md](Ownership.md)** — it is the single source for "where does X live?" Use it to navigate, and to detect drift if a definition ever appears in a non-owning doc.

Drift rule: if a contract surface acquires a *second* normative definition (a redefinition rather than a citation), that is a corpus bug. File it as a `spec-review` bead and resolve by collapsing back to the listed owner.
