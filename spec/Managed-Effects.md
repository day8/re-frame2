# Managed External Effects

> **Type:** Reference
> The unifying conceptual frame for every framework-owned, lifecycle-aware effect surface in re-frame2 — HTTP requests, WebSocket connections, state-machine actors, SSR per-request fxs, and managed flows (the internal-effect cousin). Names the eight properties every conformant managed-effect surface inherits, so new surfaces (managed timers, managed background jobs, managed IndexedDB transactions) can be evaluated against a single checklist instead of re-deriving the shape each time.

## Why this doc exists

A pattern is visible in re-frame2 across five existing capability surfaces: [`:rf.http/managed`](014-HTTPRequests.md), [`:rf.ws/*`](Pattern-WebSocket.md), state-machine [`:invoke` / `:invoke-all`](005-StateMachines.md), [`:rf.server/*`](011-SSR.md), and [`:rf.flow/*`](013-Flows.md). Each Spec describes its own surface in detail; this doc names the **shared shape** so the architectural concept stops living implicitly in five places and starts being citeable as a single concept with a single anchor.

**A managed external effect is an effect whose entire interaction lifecycle — issuance, observability, failure classification, retry, abort, teardown, and reply addressing — is owned by the framework, not by the calling event handler.** The handler returns *data* describing what it wants; the framework owns *how* the interaction unfolds across time.

This is the architectural contract that lets pair tools, `:fx-overrides`, error projectors, and the trace bus compose uniformly across surfaces. New surfaces inherit the contract by adopting the eight properties below; the AI-Audit grades surfaces against this checklist.

## The eight properties

Every managed-effect surface MUST satisfy these eight properties. A surface that satisfies fewer is an "ad-hoc fx" — useful, but outside the contract pair tools and the conformance corpus assume.

### 1. Effect-as-data, not callbacks

The handler returns a plain-data description of the interaction in its effect-map, never a function. The interaction's shape is a serialisable map under a registered fx-id; the framework walks the map and dispatches the work.

```clojure
;; Effect-as-data — what every managed-effect call site looks like.
{:fx [[:rf.http/managed   {:request {...} :on-success ... :retry {...}}]
      [:rf.machine/spawn  {:id ... :machine ... :data {...}}]
      [:rf.server/set-cookie {:name "session" :value ...}]]}
```

This property is the architectural foundation. Without it none of the remaining seven mean anything — callbacks are opaque to overrides, to time-travel, to elision, and to pair-tools. See [Pattern-AsyncEffect §Why this shape](Pattern-AsyncEffect.md#why-this-shape) for the underlying rationale; managed effects specialise that pattern with a fixed contract.

### 2. Framework-owned execution lifecycle

The framework — not the calling event handler — owns issuance, intermediate state, and teardown. The handler does not call `.then`, does not register completion listeners, does not unregister, does not free resources. The runtime tracks the interaction from dispatch to terminal state (success / failure / abort / teardown) and emits trace events at each transition.

### 3. Structured failure taxonomy under `:rf.<surface>/*`

Failures are classified into a closed, enumerable taxonomy under a single reserved namespace per surface (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). Examples in re-frame2 today:

| Surface | Failure namespace | Spec |
|---|---|---|
| HTTP requests | `:rf.http/*` (eight categories: `:rf.http/transport`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode`, ...) | [014 §Failure taxonomy](014-HTTPRequests.md) |
| WebSocket connections | `:rf.ws/*` (`:rf.ws/transport`, `:rf.ws/auth`, `:rf.ws/stale-socket`, ...) | [Pattern-WebSocket](Pattern-WebSocket.md) |
| State-machine actors | `:rf.machine/*` (`:rf.machine/invoke-failed`, `:rf.machine/snapshot-version-mismatch`, ...) | [005 §Error contract](005-StateMachines.md) |
| SSR per-request | `:rf.ssr/*` (`:rf.ssr/hydration-mismatch`, `:rf.ssr/render-failed`, ...) | [011 §Error contract](011-SSR.md) |
| Managed flows | `:rf.flow/*` (`:rf.flow/cycle-detected`, `:rf.flow/output-throw`, ...) | [013 §Errors](013-Flows.md) |

Every failure is a structured trace event with `:operation`, `:tags`, and `:recovery` per [009 §Error contract](009-Instrumentation.md#error-contract). No surface invents its own error shape.

### 4. Observable via the trace bus

Every issuance, intermediate transition, retry attempt, and terminal outcome emits a trace event on the single global trace bus (per [009 §The trace event model](009-Instrumentation.md#the-trace-event-model)). Pair tools, 10x, and off-box monitors consume the same stream — no surface-specific observation API.

### 5. `:sensitive?` + `:large?` composition

Every wire-bearing slot in a managed-effect trace passes through [`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker), the single shared walker that composes privacy elision (`:sensitive?` per [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)) and size elision (`:large?` per [009 §Size elision](009-Instrumentation.md#size-elision-in-traces)). Surfaces MUST NOT roll their own wire-boundary elision; the walker is the single point of truth. A slot carrying both `:sensitive? true` and `:large? true` redacts on sensitivity — the `:rf.size/large-elided` marker itself would leak `:path` / `:bytes` / `:digest` and is suppressed.

### 6. Built-in retry / abort / teardown semantics

Each surface ships first-class **retry-with-backoff**, **abort**, and **teardown** semantics as data on the args map, not as caller code. The vocabularies differ (HTTP has `:retry {:on ... :max-attempts ... :backoff {...}}`; machines have `:after` + `:always` guards; WebSocket reconnect lives on the `:reconnecting` state), but the contract is identical: the handler declares the policy; the framework executes it.

### 7. In-flight registry

Each surface maintains a framework-private registry of currently-in-flight interactions, keyed by an addressable id (HTTP request-id, machine instance-id, socket-id, flow-id, SSR request-id). The registry is queryable via the public registrar API (per [001 §The query API](001-Registration.md#the-query-api)); pair tools list active interactions, and the runtime emits a structured warning if a frame is destroyed while interactions are still in-flight.

### 8. Per-frame interceptors / scoping

Managed effects MUST honour the dispatching frame's `:fx-overrides`, `:interceptor-overrides`, and `:platforms` filters (per [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) and [011 §`:platforms`](011-SSR.md)). Tests stub `:rf.http/managed` to a canned reply via `:fx-overrides`; SSR builds short-circuit `:rf.machine/spawn` for actors that aren't `:platforms #{:client}`; stories install per-story `:interceptor-overrides`. The override seam is id-based — overrides cite the registered fx-id, not a function value, so they round-trip through the wire.

## Instances today

The five surfaces in the v1 corpus that satisfy the eight properties. Each Spec owns its own contract surface in detail; this section is informational — the spec text below points back to each canonical home.

### `:rf.http/managed` — HTTP requests ([Spec 014](014-HTTPRequests.md))

Single-request / single-reply HTTP. Args map shape: `:request`, `:decode`, `:accept`, `:on-success`, `:on-failure`, `:retry`. Eight-category failure taxonomy under `:rf.http/*`. Frame-aware reply addressing via co-located request-and-reply handlers (the `(:rf/reply msg)` branch). Specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md); pins [Pattern-RemoteData](Pattern-RemoteData.md)'s lifecycle slice.

### `:rf.ws/*` — WebSocket / SSE / WebRTC connections ([Pattern-WebSocket](Pattern-WebSocket.md))

Long-lived connection lifecycle as a state machine that owns the socket actor. Connection states: `:disconnected` / `:active{:connecting, :authenticating, :connected}` / `:reconnecting` / `:failed`. Subscription state and queued sends survive reconnects via the machine's `:data`. The connection epoch (the socket-actor's gensym'd id) gates stale replies — events from a replaced socket fail the check and surface as `:rf.ws/stale-socket`.

### `:invoke` / `:invoke-all` — state-machine actors ([Spec 005](005-StateMachines.md))

Declarative actor spawn anchored on a state node. The framework owns the actor's lifetime: spawned on entry, destroyed on exit (or when an ancestor's `:invoke` boundary closes). `:invoke-all` parallel-fans children with a join condition. Reply addressing uses the carry-the-id-back-to-the-parent idiom; stale replies (from an actor whose owning state has already exited) are dropped.

### `:rf.server/*` — SSR per-request fxs ([Spec 011](011-SSR.md))

Six server-side response-shape fxs (`set-status`, `set-header`, `append-header`, `set-cookie`, `delete-cookie`, `redirect`) plus the `reg-error-projector` registry kind and the per-request HTTP response accumulator. The "interaction" here is the HTTP response itself; the framework owns building it across the per-request frame's lifetime, then emitting it as the response.

### `:rf.flow/*` — managed derived computation ([Spec 013](013-Flows.md))

The internal-effect cousin. The "external system" is the framework's own scheduler — flows are registered rules that compute on input change and materialise their output into `app-db`. They satisfy the eight properties applied to a derived-state surface: effect-as-data (the registration map), framework-owned execution (the scheduler runs them after each event drain in topological order), failure taxonomy (`:rf.flow/cycle-detected`, `:rf.flow/output-throw`), trace-bus observability (`:rf.flow/evaluated` per evaluation), elision composition (`:sensitive?` on flow outputs), retry/abort/teardown (runtime-toggleable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`), in-flight registry (queryable via the registrar), per-frame scoping (flows are frame-local — see [013 §Frame-scoping](013-Flows.md#frame-scoping)).

## How new managed-effect surfaces inherit the contract

A future surface — managed timers, managed IndexedDB transactions, managed background jobs, managed WebAuthn flows — becomes a "managed effect" by adopting all eight properties above. Concretely, a new surface SHOULD:

1. Register a single fx-id under a new reserved sub-namespace `:rf.<surface>/*` ([Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) MUST be amended to add the namespace).
2. Define a closed args-map shape with a registered schema in [Spec-Schemas](Spec-Schemas.md).
3. Enumerate the failure taxonomy under `:rf.<surface>/*` in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).
4. Emit trace events at issuance, intermediate transitions (if any), retries, and terminal outcomes via the trace bus.
5. Route every wire-bearing slot through `rf/elide-wire-value` at the trace-emit site.
6. Ship retry / abort / teardown as data on the args map (not as caller code).
7. Maintain a framework-private in-flight registry keyed by an addressable id; expose via the registrar query API.
8. Honour the dispatching frame's `:fx-overrides`, `:interceptor-overrides`, and `:platforms` filters.

A surface that satisfies fewer than eight remains useful but does **not** carry the "managed external effect" label; pair tools, the conformance corpus, and the AI-Audit treat it as out-of-contract.

## What this concept replaces

Before naming this concept, each downstream Spec independently described its own slice of the shape. The risk was drift — two Specs answering "how do we elide a sensitive request body?" with subtly different mechanisms; a new Spec inventing a new failure-vocabulary scheme. Naming the concept makes the contract a **single point of accretion**: future surfaces are graded against the same eight properties, and the shared infrastructure (the trace bus, `rf/elide-wire-value`, the registrar's in-flight queries, the `:fx-overrides` seam) is the single point of implementation for all of them.

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — the underlying generic shape; managed effects specialise it with a fixed contract.
- [009 §Error contract](009-Instrumentation.md#error-contract) — the structured-error shape every managed-effect failure conforms to.
- [009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces) and [§Size elision](009-Instrumentation.md#size-elision-in-traces) — the single shared `rf/elide-wire-value` walker.
- [API §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) — the wire-boundary walker public surface.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.<surface>/*` namespace policy new surfaces extend.
- [Ownership](Ownership.md) — the contract-surface → owning-Spec map; consult before naming a new managed-effect surface.
