# Ownership Matrix

> **Type:** Reference
> The single source for "where does X live?" — every contract surface in re-frame2 is owned by exactly one normative Spec; companion docs cite the owner without redefining the contract. Use this matrix to navigate, and to detect drift if a definition ever appears in a non-owning doc.

Every contract surface in re-frame2 is **owned** by exactly one normative Spec. Companion docs cite the owner; they do not redefine the contract. This matrix is the single source for "where does X live?" — use it to navigate, and to detect drift if a definition ever appears in a non-owning doc.

| Contract surface | Owning Spec | Companion citations |
|---|---|---|
| Goals, hard constraints, host-profile matrix, identity primitive, minimal-core contract | [000-Vision](000-Vision.md) | [Principles](Principles.md), [Implementor-Checklist](Implementor-Checklist.md), [API](API.md) |
| Registration grammar (`reg-*` shape, metadata map, two-form middle slot) | [001-Registration](001-Registration.md) | [API](API.md), [Spec-Schemas §`:rf/registration-metadata`](Spec-Schemas.md#rfregistration-metadata), [Construction-Prompts](Construction-Prompts.md) |
| Registry kind taxonomy (the canonical `kind` keyword set) | [001-Registration](001-Registration.md) | [API](API.md), [Spec-Schemas](Spec-Schemas.md) |
| Source-coordinate capture (macro-driven, CLJS reference) | [001-Registration](001-Registration.md) | [Tool-Pair](Tool-Pair.md), [009-Instrumentation](009-Instrumentation.md) (trace events carry coords) |
| Hot-reload semantics for registrations | [001-Registration](001-Registration.md) | [002-Frames](002-Frames.md) (sub-cache invalidation) |
| Registry query API | [001-Registration](001-Registration.md), [002-Frames](002-Frames.md) (runtime side) | [Tool-Pair](Tool-Pair.md), [007-Stories](007-Stories.md) |
| Frame model, identity, lifecycle, `:rf/default` fallback | [002-Frames](002-Frames.md) | [API](API.md), [Spec-Schemas](Spec-Schemas.md) |
| Event handlers (`reg-event-fx`, `reg-event-db`, the `(state, event) → effects-map` contract) | [002-Frames](002-Frames.md) | [API](API.md), [Construction-Prompts CP-1](Construction-Prompts.md), [Pattern-AsyncEffect](Pattern-AsyncEffect.md) |
| Dispatch envelope (explicit-frame addressing, two-arg dispatch form) | [002-Frames](002-Frames.md) | [API](API.md), [Tool-Pair](Tool-Pair.md) |
| Effect-map shape (closed `:db` + `:fx` at top level) | [002-Frames](002-Frames.md) | [Spec-Schemas §`:rf/effect-map`](Spec-Schemas.md#rfeffect-map), [API](API.md) |
| Effects (`reg-fx`) and coeffects (`reg-cofx`) | [002-Frames](002-Frames.md) | [API](API.md), [Pattern-AsyncEffect](Pattern-AsyncEffect.md), [011-SSR](011-SSR.md) (`:platforms`) |
| Run-to-completion drain semantics | [002-Frames](002-Frames.md) | [005-StateMachines](005-StateMachines.md) (drain interaction), [008-Testing](008-Testing.md) (synchronous triggers) |
| Per-frame and per-call overrides (`:fx-overrides`, `:interceptor-overrides`, `:interceptors`) | [002-Frames](002-Frames.md) | [008-Testing](008-Testing.md), [007-Stories](007-Stories.md) |
| Machines-as-event-handlers foundation hooks | [002-Frames](002-Frames.md) | [005-StateMachines](005-StateMachines.md) (full grammar) |
| View contract (`(state, props) → render-tree`, Form-1/2/3, `reg-view`) | [004-Views](004-Views.md) | [API](API.md), [011-SSR](011-SSR.md), [Construction-Prompts CP-4](Construction-Prompts.md) |
| Subscriptions (`reg-sub`, derivation graph) | [002-Frames](002-Frames.md) (registration & contract) + [006-ReactiveSubstrate](006-ReactiveSubstrate.md) (cache, invalidation, change tracking) | [API](API.md), [Construction-Prompts CP-2](Construction-Prompts.md) |
| State machine grammar (transition table, `:always`, `:after`, `:invoke`, hierarchical states, snapshot shape) | [005-StateMachines](005-StateMachines.md) | [CP-5-MachineGuide](CP-5-MachineGuide.md), [Pattern-WebSocket](Pattern-WebSocket.md), [Pattern-Boot](Pattern-Boot.md), [Pattern-LongRunningWork](Pattern-LongRunningWork.md) |
| Reactive substrate adapter contract (Reagent default, plain-atom for JVM) | [006-ReactiveSubstrate](006-ReactiveSubstrate.md) | [008-Testing](008-Testing.md), [011-SSR](011-SSR.md) |
| Stories / Variants / Workspaces | [007-Stories](007-Stories.md) | [008-Testing](008-Testing.md) (portable-stories-as-tests) |
| Testing infrastructure (fixtures, synchronous triggers, per-test stubs, headless evaluation, framework adapters, JVM-runnable suites) | [008-Testing](008-Testing.md) | [API](API.md), [007-Stories](007-Stories.md), [conformance/](conformance/README.md) |
| Trace event model (envelope, ids, listener API) | [009-Instrumentation](009-Instrumentation.md) | [Tool-Pair](Tool-Pair.md), [API](API.md) |
| Error contract (structured trace events, `reg-event-error-handler` policy) | [009-Instrumentation](009-Instrumentation.md) | [API](API.md), all Specs that emit errors cite this section |
| Schema attachment (`:spec`, `reg-app-schema`), validation timing, dev-vs-prod elision, validator-fn extension point | [010-Schemas](010-Schemas.md) | [001-Registration §Schema integration](001-Registration.md#schema-integration), [Spec-Schemas](Spec-Schemas.md) |
| SSR flow (server frame lifecycle, hydration payload, `:rf/hydrate`, hydration-mismatch detection) | [011-SSR](011-SSR.md) | [004-Views](004-Views.md), [008-Testing](008-Testing.md), [012-Routing](012-Routing.md) |
| `:platforms` metadata on `reg-fx`/`reg-cofx`; pure hiccup → HTML emitter | [011-SSR](011-SSR.md) | [002-Frames](002-Frames.md), [API](API.md) |
| Route grammar, `:route` sub, navigation events, route-not-found, navigation-blocking, fragments, scroll restoration | [012-Routing](012-Routing.md) | [011-SSR](011-SSR.md), [API](API.md) |
| Managed HTTP requests (`:rf.http/managed` fx, args-map shape, decode pipeline, `:accept` normalisation, retry-with-backoff, abort surface, frame-aware reply addressing, eight-category `:rf.http/*` failure taxonomy, schema-reflection metadata, canned stubs, `with-managed-request-stubs`) | [014-HTTPRequests](014-HTTPRequests.md) | [API](API.md), [Spec-Schemas](Spec-Schemas.md), [Conventions](Conventions.md) (`:rf.http/*` reserved), [Pattern-AsyncEffect](Pattern-AsyncEffect.md), [Pattern-RemoteData](Pattern-RemoteData.md), [Pattern-StaleDetection](Pattern-StaleDetection.md), [009-Instrumentation](009-Instrumentation.md) (`:rf.http/retry-attempt` trace) |
| Runtime shapes (Malli, CLJS reference) — `:rf/effect-map`, `:rf/registration-metadata`, `:rf/hydration-payload`, `:rf/trace-event`, etc. | per-Spec owner; [Spec-Schemas](Spec-Schemas.md) is the *projection* (collected EDN forms) | every Spec that owns a shape |
| API signatures (consolidated) | [API](API.md) is the *projection*; canonical content lives in the per-Spec owner | per-Spec owners |
| Migration rules (re-frame v1.x → re-frame2, CLJS reference) | [MIGRATION](MIGRATION.md) | per-Spec owners (contract content); Migration owns the rules |
| Pair-tool runtime contract (inspect, dispatch, hot-swap, time-travel, fx-stub, source-map) | [Tool-Pair](Tool-Pair.md) | [001-Registration](001-Registration.md), [009-Instrumentation](009-Instrumentation.md) |
| Reserved-namespace policy, reserved fx-ids and `app-db` keys | [Conventions](Conventions.md) | every Spec that uses a reserved id cites the Conventions list |
| Construction-prompt scaffolding templates | [Construction-Prompts](Construction-Prompts.md) | per-Spec owners (contract content); Construction-Prompts owns the scaffolding shape |
| Conformance fixtures (canonical interactions and expected emissions, in EDN) | [conformance/](conformance/README.md) | per-Spec owners (contract content); conformance owns the fixture corpus |

Drift rule: if a contract surface acquires a *second* normative definition (a redefinition rather than a citation), that is a corpus bug. File it as a `spec-review` bead and resolve by collapsing back to the listed owner.
