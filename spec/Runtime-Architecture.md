# Runtime Architecture

> **Type:** Reference
> Bird's-eye view of the re-frame2 runtime — the components that sit between a `dispatch` call and a rendered view, what each owns, and how they fit together. Companion to the per-area normative Specs; redefines nothing.

The numbered Specs each own a slice of the contract. This doc shows the slices as one machine. It exists for two audiences:

1. An AI implementing the CLJS reference, who needs the picture in front of them while [Spec 002](002-Frames.md), [005](005-StateMachines.md), [006](006-ReactiveSubstrate.md), and [009](009-Instrumentation.md) each contribute pieces.
2. A non-CLJS implementor (per [Goal 2 — AI-implementable from the spec alone](000-Vision.md#ai-implementable-from-the-spec-alone)) who cannot read the v1 source as a runtime spec because v1 has no machines, no frames-as-values, no substrate boundary.

If a claim here disagrees with the owning Spec, the owning Spec wins and this doc has drifted.

## Components at a glance

The runtime is eight components plus a host-side **interop layer** that the CLJS reference puts behind `re-frame.interop`. Each component is small, has a single owner Spec, and interacts with the others through stable surfaces.

| # | Component | One-line role | Owner |
|---|---|---|---|
| 1 | **Registrar** | `(kind, id) → metadata` lookup. The single source for handler resolution. | [001-Registration](001-Registration.md) |
| 2 | **Frame container** | Per-frame runtime object: `app-db` reactive container, router queue, sub-cache, lifecycle. | [002-Frames](002-Frames.md) |
| 3 | **Router** | Per-frame FIFO event queue. Decides which event drains next. | [002-Frames §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics) |
| 4 | **Drain loop** | The execution engine: dequeue → run interceptor chain → apply effects → settle machines → invalidate sub-cache. The four Levels in [005 §Drain semantics](005-StateMachines.md#drain-semantics). | [002-Frames](002-Frames.md), [005-StateMachines](005-StateMachines.md) |
| 5 | **Effect interpreter (`do-fx`)** | Walks the `:fx` vector in source order, dispatching each entry to its registered fx handler. | [002-Frames §`:fx` ordering](002-Frames.md#fx-ordering-and-atomicity-guarantees) |
| 6 | **Sub-cache** | Per-frame derivation graph + memoised values. Invalidates on `app-db` change; disposes on frame destroy. | [006-ReactiveSubstrate §Subscription cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics) |
| 7 | **Reactive substrate adapter** | Bridges the core to a reactivity library (Reagent in CLJS reference). Turns container reads into view re-renders. | [006-ReactiveSubstrate](006-ReactiveSubstrate.md) |
| 8 | **Trace bus** | Per-process event stream; listeners notified after debounce window. Carries every dispatch, fx, machine transition, error, and registry mutation. | [009-Instrumentation](009-Instrumentation.md) |

The **interop layer** (`re-frame.interop` in the CLJS reference) is not a runtime component — it is the host-abstraction surface the components call into for `next-tick`, `after-render`, mutable references, host-clock `now-ms`. Other hosts implement it natively; the interface stays small (per [Spec 002 §Interop layer](002-Frames.md#interop-layer--clock-primitives--see-spec-005)).

## Data flow

External event ingress to settled view, in one diagram:

```
                                   ┌────────────────┐
   user code ──(dispatch [ev])──►  │ Router         │ ◄── :fx [[:dispatch ev]] re-enqueue
                                   │  FIFO queue    │     (during do-fx)
                                   └───────┬────────┘
                                           │ dequeue (next-tick)
                                           ▼
                                   ┌────────────────────────────┐
                                   │ Drain loop                 │
                                   │   1. resolve handler ──────┼──► Registrar  (001)
                                   │   2. interceptor chain     │
                                   │   3. handler returns       │
                                   │      effects-map           │
                                   │   4. apply :db (1 step)    │      ┌─── Trace bus (009)
                                   │   5. do-fx :fx in order ───┼──┬──►│ :event/run
                                   │   6. for machine events:   │  │   │ :rf.machine/transition
                                   │      Level-3 cascade       │  │   │ :registry/handler-...
                                   │      (raise queue + always)│  │   │ :rf.error/...
                                   │   7. commit snapshot to    │  │   └────────┬────────────
                                   │      [:rf/machines <id>]   │  │            │
                                   └─────────┬──────────────────┘  │            ▼
                                             │                     │   listener delivery
                                             │ container.replace!  │   (debounced batches)
                                             ▼                     │
                                   ┌────────────────────────────┐  │
                                   │ Frame container            │  │
                                   │   app-db (reactive)  ◄─────┼──┘
                                   │   router queue             │
                                   │   sub-cache                │
                                   │   lifecycle                │
                                   └─────────┬──────────────────┘
                                             │ change-listener
                                             ▼
                                   ┌────────────────────────────┐
                                   │ Sub-cache (006)            │
                                   │   recompute affected subs  │
                                   │   propagate to derived     │
                                   └─────────┬──────────────────┘
                                             │ subscriber notify
                                             ▼
                                   ┌────────────────────────────┐
                                   │ Reactive substrate adapter │
                                   │   re-render affected views │  (CLJS: Reagent → React)
                                   └────────────────────────────┘
```

The arrow that re-enters the Router from `do-fx` is what makes the loop a loop: a `:dispatch` effect appends to the same FIFO queue and is processed FIFO with whatever else has accumulated. The arrow from the drain loop's machine path is the **macrostep boundary** ([005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event)) — sub-cache invalidation fires once at the end of the cascade, not after each microstep.

## Component contracts

Each section below states **inputs**, **outputs**, **invariants**, and **who calls in / who calls out**. The point is not to redefine the contract — it is to put the contract on one page.

### 1. Registrar

**Role.** Hold every registered handler, sub, fx, cofx, view, machine action, machine guard, route, head, and error projector. Look-up is `(kind, id) → metadata-map`. The metadata map carries the handler fn under a closed key per [001 §Registration grammar](001-Registration.md#registration-grammar).

**Inputs.** Calls from `reg-event-fx`, `reg-event-db`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-view`, `reg-machine-action`, `reg-machine-guard`, `reg-machine`, `reg-frame`, `reg-route`, `reg-head`, `reg-event-error-handler` — the closed registry-kind set in [001](001-Registration.md).

**Outputs.** Lookup returns the metadata map (or `nil`); query API returns id sets per [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api).

**Invariants.**
- One global registry; not per-frame. Frames isolate state, not behaviour.
- Surgical re-registration is the only mutation primitive — replace the slot atomically, emit a `:rf.registry/handler-replaced` trace, in-flight events keep the old fn (per [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics)).
- Reserved namespaces ([Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) are protected; user registrations under them warn.

**Who reads.** Drain loop (handler resolution), sub-cache (sub fn lookup), tooling (query API), trace bus consumer (resolves handler-id to source coords).

**Who writes.** All `reg-*` calls; hot-reload re-registration.

### 2. Frame container

**Role.** The per-frame runtime object. The shape is fixed by [002 §What lives in a frame](002-Frames.md#what-lives-in-a-frame):

```clojure
{:id        :todo
 :app-db    <reactive-container>     ;; opaque to the core; held by the substrate adapter
 :router    {...}                    ;; FIFO queue + drain-state FSM
 :sub-cache {...}                    ;; signal-graph cache for this frame
 :lifecycle {:created-at <ts> :destroyed? false :listeners [...]}
 :config    {...}}                   ;; the metadata reg-frame was given (incl. :preset expansion)
```

**Inputs.** `reg-frame` (atomic create-and-register), `make-frame` (anonymous instance), `reset-frame` (full replace, opt-in per [002 §reset-frame](002-Frames.md#reset-frame--full-replace-opt-in)), `destroy-frame` (lifecycle teardown).

**Outputs.** Frame-keyword handles. Tools query via `frame-meta`, `frame-ids`.

**Invariants.**
- The `app-db` reactive container is opaque to the core; the [substrate adapter](006-ReactiveSubstrate.md) decides what it is (Reagent ratom in CLJS reference; plain atom for JVM/SSR/headless).
- The frame's full state is reconstructible from its `app-db` *value* — adapter-internal state (Reagent reactions, React fibers, etc.) is not part of the frame value (load-bearing for [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) per [006 §Revertibility constraints](006-ReactiveSubstrate.md#revertibility-constraints-on-adapters)).
- `:rf/default` is always present; user code that omits a frame on dispatch lands here ([002 §`:rf/default`](002-Frames.md#rfdefault)).
- Reserved `app-db` keys (`:rf/machines`, `:route`) are owned by the runtime ([Conventions §Reserved app-db keys](Conventions.md#reserved-app-db-keys)).

### 3. Router (per-frame FIFO)

**Role.** Hold dispatched events, in arrival order, until the drain loop is ready to process the next one. Per [005 §Drain semantics §Level 4](005-StateMachines.md#level-4--across-the-runtime): no priority lanes, no front-of-queue insertion at this layer, no reordering.

**Inputs.** `dispatch` (back of queue), `dispatch-later` (timer fires → back of queue), `:fx [[:dispatch ev]]` from `do-fx` (back of queue, in source order), routing changes from the navigation layer ([012-Routing](012-Routing.md)).

**Outputs.** One event at a time to the drain loop.

**Invariants.**
- Per-frame. Cross-frame dispatch is ordinary async — no drain spans frames ([002 §Run-to-completion §Rules](002-Frames.md#rules)).
- FIFO. Dispatch ordering is identical to trace's `:dispatched-at` ordering.
- The router schedules drain via the interop layer's `next-tick` (CLJS reference: `goog.async.nextTick`); the loop yields between drain cycles so the host's event loop can interleave rendering and other work.

**Note on `:raise`.** `:raise` is **not** a router-layer effect. It is a machine-internal pre-commit queue, drained inside one Level-3 cascade ([005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event)). External observers see the macrostep, never the raise queue.

### 4. Drain loop

**Role.** The execution engine. Dequeue an event from the router, resolve and run its handler (through the interceptor chain), apply the resulting effects map, settle any machine cascades, fire sub-cache invalidation, repeat.

**Inputs.** Events from the router. Handler fns from the registrar. Frame's current `app-db` value (read once per event).

**Outputs.** Side-effects. New `app-db` value committed to the frame container. `:fx` entries dispatched to `do-fx`. Trace events emitted at every phase boundary.

**Invariants — the four Levels** (the drain-loop pseudocode itself is tracked separately and will land as a section in 002):

| Level | Scope | Locked by |
|---|---|---|
| 1 | One action's `{:data :fx}` effect map | [005 §Level 1](005-StateMachines.md#level-1--within-a-single-actions-effect-map) |
| 2 | The action slots in one transition (`:exit`, transition `:action`, `:entry`, initial cascade) | [005 §Level 2](005-StateMachines.md#level-2--across-the-action-slots-in-one-transition) |
| 3 | One machine event — including raise-cascade and `:always` microsteps; commits one snapshot at the end | [005 §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event) |
| 4 | The runtime-wide FIFO router; one dequeue runs to completion before the next | [005 §Level 4](005-StateMachines.md#level-4--across-the-runtime), [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics) |

**Run-to-completion guarantee.** Once an event begins draining, every event it dispatches synchronously — and every event those handlers dispatch in turn — drains to fixed point before any further external event for this frame is processed, and before any view re-renders. Async effects (HTTP, timers, sockets) yield back to the loop; their replies arrive as fresh dispatches that re-engage the cascade for their own run. Bounded by `:drain-depth` (default 100; [002 §Run-to-completion §Rules](002-Frames.md#rules)) and by `:raise-depth-limit` / `:always-depth-limit` (both default 16) inside Level 3.

**Render boundary.** Views render once after the cascade settles; intermediate states are never displayed.

### 5. Effect interpreter (`do-fx`)

**Role.** Walk the `:fx` vector in source order, look up each `[fx-id args]` entry's handler in the registrar, invoke it.

**Inputs.** The `:fx` vector from a handler's effects map. Per-frame and per-call `:fx-overrides` ([002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides)).

**Outputs.** Side-effects (HTTP, navigation, dispatch, etc.). For `:fx [[:dispatch ev]]`, append to the router queue.

**Invariants** — the four locked rules from [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees):

1. `:db` commits first — atomically, before any `:fx` entry runs.
2. `:fx` entries run in **source order**.
3. Each entry's handler returns synchronously before the next begins (async work the handler kicks off is not awaited).
4. Subscriptions observe the post-`:db` state — invalidation happens once between the `:db` write and the first `:fx` entry.

If an `:fx` entry's handler throws, subsequent entries **continue** ([002 §Error during `:fx`](002-Frames.md#fx-ordering-and-atomicity-guarantees)) — `:fx` ordering means *order*, not *dependency*. The thrown error traces as `:rf.error/fx-handler-exception`.

**Reserved fx-ids inside machines.** `:raise` and `:spawn` are machine-internal — the machine handler routes them locally before forwarding the rest to `do-fx` ([Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids), [005 §Reserved fx-ids inside `:fx`](005-StateMachines.md#raise-spawn-and-destroy-machine-are-reserved-fx-ids-inside-fx)).

### 6. Sub-cache

**Role.** Per-frame memoised derivation graph. Each `(subscribe q)` call returns a value; if the underlying `app-db` slice changes, dependents recompute on the next deref. The full contract — invalidation algorithm, ref-counting, layer-1/2/3 sub semantics, disposal — is tracked separately and will be locked in [006 §Subscription cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics).

**Inputs.** Sub registrations from the registrar (`reg-sub`). `replace-container!` calls from the drain loop (cache-invalidation hook). `subscribe` calls from views.

**Outputs.** Sub values to views (through the substrate adapter). Disposal calls when subs lose their last reader.

**Invariants.**
- Per-frame. Two frames running the same sub query compute against their own `app-db`s, cache against their own sub-caches.
- Invalidation is triggered after the `:db` write and before the first `:fx` entry runs ([002 §`:fx` ordering rule 4](002-Frames.md#fx-ordering-and-atomicity-guarantees)).
- A frame's disposal releases every cached sub for that frame ([006 §Adapter disposal lifecycle](006-ReactiveSubstrate.md#adapter-disposal-lifecycle)).
- The cache survives the round-trip "dispatch → :db change → sub recompute → view re-render" and adds no observable state beyond the frame's `app-db` value.

### 7. Reactive substrate adapter

**Role.** Bridge the core's pure-data primitives (containers, derived values, render trees) to a host reactivity library. The adapter contract is closed for v1 — six required functions, two optional, one lifecycle ([006 §Normative contract](006-ReactiveSubstrate.md#normative-contract)).

**Inputs.** Core calls: `make-state-container` (frame creation), `replace-container!` (drain-loop commit), `make-derived-value` (sub-cache derivation), `render` / `render-to-string`, `register-context-provider` (frame-provider for views), `dispose-adapter!` (teardown).

**Outputs.** Substrate-specific containers (Reagent ratoms in CLJS reference; plain atoms on JVM; Solid signals in a TS port). Render side-effects on the host.

**Invariants.**
- Adapter-internal state is **derivable from the frame's `app-db` value alone**. No private side channel ([006 §What an adapter MUST NOT do](006-ReactiveSubstrate.md#what-an-adapter-must-not-do)).
- One adapter per process ([006 §Single adapter per process](006-ReactiveSubstrate.md#single-adapter-per-process)).
- The adapter is replaceable. The CLJS reference ships Reagent-default; SSR uses a plain-atom adapter; tests use headless.

The Reagent-specific bridging pseudocode — which Reagent primitive realises which contract function — is tracked separately and will land as a Reference-adapter appendix in [006-ReactiveSubstrate](006-ReactiveSubstrate.md).

### 8. Trace bus

**Role.** Per-process stream of trace events. Every dispatch, fx, machine transition, registry mutation, and error projects to an event with a closed envelope ([009 §Core fields](009-Instrumentation.md#core-fields-required-on-every-event)) and a per-domain `:operation` keyword ([Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).

**Inputs.** Emit calls from every other component — registrar (mutations), router (enqueue/drain markers), drain loop (event lifecycle), `do-fx` (per-fx events), sub-cache (recompute markers), substrate (render markers, adapter lifecycle), error projector ([009 §Error contract](009-Instrumentation.md#error-contract)).

**Outputs.** Batched delivery to listeners ([009 §Listener API](009-Instrumentation.md#the-listener-api)). Listener-side: 10x panel, re-frame-pair, agent tools.

**Invariants.**
- Open shape ([009 §Open shape; new fields are additive](009-Instrumentation.md#open-shape-new-fields-are-additive)).
- Compile-time elidable in production ([009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code)).
- Listener invocation is async (debounce window batched) by default; opt-in per-event delivery for tools that need it.

## Lifecycles

Three lifecycles touch every component. Each is short. Putting them in one place lets an implementor see the whole timeline.

### Boot

1. Host starts. The interop layer (`re-frame.interop` in CLJS) is available — `next-tick`, `now-ms`, `after-render`.
2. **Registrar** is created (empty maps for each `kind`). Reserved-namespace policy installed.
3. User code runs `reg-*` calls — handlers, subs, fx, cofx, views, machines, routes, frames. Each emits a `:rf.registry/handler-registered` trace event. Frames declared with `reg-frame` create their **frame container** (per-frame `app-db`, router, sub-cache, lifecycle).
4. The **substrate adapter** initialises. CLJS reference: load Reagent, install the React-context shim used by `frame-provider` ([002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part)).
5. `:rf/default` frame is guaranteed present.
6. Each frame's `:on-create` dispatches (per [Pattern-Boot](Pattern-Boot.md)). The drain loop runs them; views mount; first render lands.

### Per-event drain

Anchor: [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics) and [005 §Drain semantics](005-StateMachines.md#drain-semantics).

1. `dispatch` enqueues an envelope on the **router**. If no drain is in flight, schedule one via `next-tick`.
2. **Drain loop** dequeues. Runs the interceptor chain; the handler returns an effects map.
3. `:db` commits to the frame container — one atomic `replace-container!`. **Sub-cache** invalidation fires.
4. **Effect interpreter** walks `:fx` in source order. `:dispatch` entries land back on the router; other entries call their fx handlers. Async fx kicks off external work; the handler returns immediately.
5. If the dequeued event was a machine event, steps 2–4 are wrapped in the Level-3 cascade ([005 §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event)): raise-queue drain, `:always` microstep loop, single snapshot commit at the end.
6. **Trace bus** has by now emitted `:event/run`, possibly `:rf.machine/transition`, every fx event, every error.
7. Drain loop checks the router queue. If non-empty, loop. If empty, exit; the substrate's render commits in this turn (CLJS reference: React's `useSyncExternalStore` reads the now-consistent state and re-renders affected components).

Async replies arrive later as fresh `dispatch` calls and re-enter at step 1.

### Teardown

Two sub-cases: per-frame teardown and per-process teardown.

**Per-frame** (`destroy-frame`, [002 §Destroy](002-Frames.md#destroy)):

1. The frame is marked `:destroyed?`. New dispatches to it are rejected.
2. Active machine instances run their `:exit` cascades up to root ([005 §Hierarchical compound states](005-StateMachines.md#hierarchical-compound-states)). Pending `:after` timers cancel.
3. Sub-cache disposes every cached sub for this frame.
4. The substrate adapter releases any frame-scoped resources (Reagent reactions, React subtree).
5. Lifecycle listeners fire. Trace event `:rf.frame/destroyed`.

**Per-process** (host shutting down, SSR request finishing per [011 §Server frame lifecycle](011-SSR.md), test run ending):

1. Every frame is destroyed in reverse-creation order.
2. The adapter's `dispose-adapter!` runs ([006 §Adapter disposal lifecycle](006-ReactiveSubstrate.md#adapter-disposal-lifecycle)).
3. The trace bus drains its pending batch to listeners; subscriptions are released.
4. The registrar is held by the host; in a long-lived process it persists across test runs (per [008-Testing](008-Testing.md) fixture conventions).

## What is new versus re-frame v1

Most of these components have v1 ancestors. The CLJS-reference implementor can lean on v1 source as the runtime spec for the parts that are unchanged; this section names the parts that have shifted, so an AI given v1 source as reference does not silently inherit v1 behaviour where re-frame2 has moved on.

| Component | Mostly inherited from v1 | New in re-frame2 |
|---|---|---|
| Registrar | Lookup shape, mutation primitive, reserved unqualified fx-ids | Two-form middle slot, schema attachment, frame as a registry kind, machine kinds (`:machine`, `:machine-action`, `:machine-guard`), source-coord capture |
| Frame container | The shape of `app-db` as a value | Frames as a layer (v1 has one global `app-db`; v2 has per-frame containers), the `:preset` expansion, the lifecycle vocabulary |
| Router | Per-frame FIFO, `next-tick` scheduling | Per-frame queues (v1 is global), `:drain-depth` limit, run-to-completion guarantee in spec |
| Drain loop | Level 4 (the FIFO router-layer drain) | Levels 1–3 (machine cascades, `:always` microsteps, snapshot commit), `:raise` as a pre-commit primitive distinct from `:dispatch`, structured drain-depth/raise-depth/always-depth limits |
| `do-fx` | `:fx` walking, fx handler resolution | Source-order rule made normative; per-frame and per-call `:fx-overrides`; `:platforms` metadata for SSR |
| Sub-cache | Layer-1/2/3 caching algorithm; invalidation on app-db change | Per-frame caches (v1 is global), explicit disposal-on-frame-destroy, derived-container contract on the substrate adapter |
| Reactive substrate adapter | Implicit Reagent fusion | The boundary itself — substrate decoupling, the closed nine-fn contract, plain-atom adapter for JVM/SSR/headless, revertibility constraints |
| Trace bus | Trace-event idea, listener registration | Closed core fields, structured error contract, `reg-event-error-handler`, server error projection, `:platforms` on advisories |
| Interop layer | Most of `re-frame.interop` carries over | Extended slightly for machine-clock primitives ([002 §Interop layer](002-Frames.md#interop-layer--clock-primitives--see-spec-005)) |

For a non-CLJS implementor: the components are the same shape, but **the boundaries between them are the load-bearing ones**. Implement each component as an independent module that talks to its neighbours through the surfaces named here; do not fuse the substrate adapter into the drain loop, do not put sub-cache invalidation behind a substrate primitive, do not let the trace bus see the registrar's internal map. The decoupling is what makes the reference adapter swappable, the testing infrastructure JVM-runnable, and Goal 3 (revertibility) achievable.

## Cross-references

- [000-Vision](000-Vision.md) — goals and host-profile matrix this runtime exists to satisfy.
- [001-Registration](001-Registration.md) — registrar contract and registration grammar.
- [002-Frames](002-Frames.md) — frame container, router, run-to-completion drain, `:fx` ordering, overrides.
- [005-StateMachines](005-StateMachines.md) — Levels 1–4 of drain, machine action effect maps, hierarchical states.
- [006-ReactiveSubstrate](006-ReactiveSubstrate.md) — adapter contract, sub-cache invalidation operational semantics, revertibility constraints.
- [008-Testing](008-Testing.md) — synchronous trigger semantics over this runtime.
- [009-Instrumentation](009-Instrumentation.md) — trace bus, listener API, error contract.
- [011-SSR](011-SSR.md) — server-side runtime variant (request-scoped frames, plain-atom adapter, pure hiccup → HTML).
- [012-Routing](012-Routing.md) — routing as state on top of this runtime (events, sub, navigation effects).
- [Conventions](Conventions.md) — reserved namespaces, fx-ids, app-db keys.
- [Implementor-Checklist](Implementor-Checklist.md) — the decision-ordered companion: which capabilities to ship, which technologies to choose, how conformance is graded.
