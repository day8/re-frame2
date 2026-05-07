# Spec 006 — Reactive Substrate

> Status: Drafting. **v1-required (CLJS reference).**
>
> For where the substrate adapter sits in relation to the rest of the runtime — the frame container, sub-cache, drain loop, and trace bus — see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

re-frame2 separates the dataflow core from the reactivity / rendering substrate. The substrate-agnostic core — registrar, frames, drain, dispatch envelope, subscription topology, sub computation, effect-map interpretation, trace stream — is JVM-runnable and has no dependency on Reagent, React, or DOM. A pluggable **substrate adapter** supplies the reactive container for `app-db`, the change-tracking that drives view re-renders, and the render-tree → surface step.

This Spec defines:

- The **boundary** between core and adapter.
- The **adapter API contract** — the closed set of functions every substrate adapter implements.
- Subscription cache invalidation semantics that adapters must respect.

The CLJS reference ships two adapters: a **Reagent adapter** (browser default) and a **plain-atom adapter** (JVM, used by SSR and headless tests). The same core runs against both; the observable behaviour of events, subs, and effects is identical across adapters given the same core inputs.

## The boundary

re-frame2 splits into three layers:

```
┌─────────────────────────────────────────────────────────────────────┐
│   Application code (events, subs, views, fx, machines)               │
│   ────────────────────────────────────────────────────────────────  │
│   Substrate-agnostic core (frame, registrar, drain, dispatch)        │
│   - Pure data flow                                                   │
│   - JVM-runnable                                                     │
│   - No Reagent, no React, no DOM                                     │
│   ────────────────────────────────────────────────────────────────  │
│   Substrate adapter (Reagent in CLJS reference; or others)           │
│   - Reactivity primitives (atom-equivalent, derived-value-equivalent)│
│   - Render-tree → DOM (or to string for SSR)                         │
└─────────────────────────────────────────────────────────────────────┘
```

The substrate-agnostic core is what every implementation supplies. The adapter is where host-specific choices live.

## What the core owns

The core is the substrate-agnostic part. It owns:

- **The handler registrar.** `(kind, id) → metadata` lookup. Pure data. JVM-runnable.
- **The frame contract.** Each frame holds an `app-db` *value*, a queue, a sub-cache, and an id. The "value" interface is what the core requires; the adapter provides the reactive container that holds it.
- **The dispatch envelope and event queue.** Per [002 §Routing](002-Frames.md#routing-the-dispatch-envelope). Pure data, FIFO.
- **The drain mechanism.** Run-to-completion drain (per [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Pure logic over the queue.
- **Subscription topology.** The static `:<-` graph derived from `reg-sub` chains. Pure data, JVM-runnable.
- **Subscription computation.** `(compute-sub query-v db)` — running a sub's body against an `app-db` value. Pure function. JVM-runnable.
- **Effect map interpretation.** Walking `:fx` and dispatching to registered fx handlers. Per [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map).
- **The trace event stream.** Per [009](009-Instrumentation.md). Pure data.

If you can plumb a runtime through these primitives, you have re-frame2's substrate-agnostic spine. None of it requires a reactivity library.

## What the adapter owns

The adapter is the substrate-specific part. It owns:

- **The reactive container for `app-db`.** In CLJS, this is a Reagent ratom. In a TypeScript port, it might be a [Solid](https://www.solidjs.com/) signal or a `useSyncExternalStore` snapshot. In Python, a `Subject`-like observable.
- **Subscription *tracking* — the runtime side of reactivity.** The view's `subscribe` call returns a value, and when the underlying `app-db` slice changes, the view re-renders. This auto-rerender behaviour is the adapter's job.
- **Render-tree consumption.** Walking the hiccup (or equivalent) and producing DOM. In CLJS, Reagent does this via React. In TypeScript with Solid, Solid's renderer. In Vue, Vue's. In SSR, a hiccup → HTML emitter.
- **Component lifecycle.** Mount, update, unmount hooks. Substrate-specific.
- **Frame-routing for views (CLJS only).** React context, per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part). Other hosts use their own equivalents (hooks, dependency injection).

Adapter behaviour is *observably equivalent* across substrates given the same core: the same events produce the same state, the same subs return the same values. The adapter only changes *how* the view sees those values reactively.

## The adapter API contract

Every substrate adapter implements the surface below. The contract is **closed for v1** — the function set is fixed, signatures are fixed, dispose-after-use is fixed; new adapter capabilities ship post-v1 additively (a new fn with a feature predicate consumers can branch on).

> **The adapter contract is the canonical mechanism for bridging external reactive sources** (timers, JS event streams, external pub/sub, signals from other libraries). The v1 `reg-sub-raw` escape hatch — which v1 users sometimes leaned on for non-app-db reactivity — is not shipped in v2 (per [MIGRATION §M-18](MIGRATION.md)). A custom adapter brings the external source into the substrate; subs consume normally via `reg-sub`. State that needs to live across [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) must reach `app-db` through an event handler (Pattern-AsyncEffect plus a registered fx), not through an adapter-private side channel — see [§What an adapter MUST NOT do](#what-an-adapter-must-not-do).

The v1 adapter surface is **six required functions, two optional functions, and one lifecycle function** — nine entries in total. The Normative contract section below specifies the call-shape for each; [§Operational semantics](#subscription-cache-invalidation--operational-semantics) covers cache-invalidation behaviour the adapter must respect; [§CLJS reference: Reagent as default adapter](#cljs-reference-reagent-as-default-adapter) covers reference-host implementation notes.

### Normative contract

**Required (6):** every adapter must implement.

| Fn | Purpose |
|---|---|
| `make-state-container` | Create a reactive container holding an `app-db` value. |
| `read-container` | Read the current value (pure). |
| `replace-container!` | Mutate the container with a new value (the only mutation primitive). |
| `make-derived-value` | Construct a derived (memoised) container from one or more sources. |
| `render` | Render a render-tree onto the substrate's surface; return an unmount fn. |
| `render-to-string` | Pure render to an HTML string (JVM-runnable). |

**Optional (2):** adapters may omit; the core falls back when an optional fn is absent.

| Fn | Purpose | Fallback when absent |
|---|---|---|
| `subscribe-container` | Register a change-listener for invalidation. | Core runs invalidation inline within `replace-container!`. |
| `register-context-provider` | Return a context-provider component that scopes a frame to a subtree. | Core falls back to explicit-frame-as-argument; the user's view code threads the frame. |

**Lifecycle (1):** every adapter must implement.

| Fn | Purpose |
|---|---|
| `dispose-adapter!` | Tear down: release listeners, caches, host resources. |

### `(make-state-container initial-value) → container`

Returns a **container** that holds an `app-db` value. The container is opaque to the core; the adapter exposes operations on it via the next three functions.

```clojure
;; Type sketch:
(make-state-container value)                            ;; → container
```

`value` is an immutable map (the initial `app-db`). The container's identity is stable — operations later in this section refer to the *same* container.

CLJS-Reagent: returns a Reagent `r/atom`.
CLJS-headless: returns a `clojure.core/atom`.
TS-Solid: returns a Solid signal.
Python-RxPy: returns a `BehaviorSubject`.

### `(read-container container) → value` and `(replace-container! container new-value) → nil`

The two basic operations on a container. `read-container` is pure; `replace-container!` is the only mutation primitive — partial updates aren't supported (the core always replaces the entire `app-db` after a drain).

```clojure
(read-container container)                              ;; → current app-db value
(replace-container! container new-value)                ;; → nil; container now holds new-value
```

### `(subscribe-container container on-change) → unsubscribe-fn`

Optional. Registers a callback that fires *after* `replace-container!` runs. The callback receives `(prev-value, new-value)`.

```clojure
(subscribe-container container on-change)               ;; → unsubscribe-fn
;; on-change signature: (fn [prev-value new-value] ...)
;; unsubscribe-fn signature: (fn [] nil) — idempotent
```

If the adapter supports it, the core uses `subscribe-container` to wire reactive sub-cache invalidation. If the adapter does NOT support it (returns `nil` from `subscribe-container`), the core falls back to running invalidation inline within `replace-container!` itself (the adapter must, in that case, ensure `replace-container!` runs the core's invalidation hook before returning).

CLJS-Reagent: Reagent's reaction machinery handles this implicitly; `subscribe-container` returns a function that cancels the registration.
CLJS-headless: not supported; returns `nil` (tests poll via `read-container`).
TS-Solid: returns a function that calls Solid's `dispose` for the listener.

### `(make-derived-value source-containers compute-fn) → container`

Returns a derived container whose value is computed from one or more source containers. The derived container updates automatically when any source's value changes (transitively).

```clojure
(make-derived-value source-containers compute-fn)       ;; → container
;; source-containers: vector of containers
;; compute-fn signature: (fn [& source-values] ...) — pure; called with deref'd values
```

The returned container supports `read-container`; `replace-container!` is **not** supported on derived containers (errors with `:rf.error/derived-container-replaced`). `subscribe-container` works as on a base container.

The implementation is responsible for caching the derived value and recomputing only when an input changes by `=`.

CLJS-Reagent: a Reagent `reaction`. CLJS-headless: not used (the core's `compute-sub` runs derivations on demand without caching). TS-Solid: `createMemo`. Vue: `computed`.

### `(render render-tree mount-point opts) → unmount-fn`

Renders the render-tree onto the substrate's surface and returns a function that unmounts.

```clojure
(render render-tree mount-point opts)                   ;; → unmount-fn
;; render-tree: a serialisable nested data structure (per Spec 004)
;; mount-point: implementation-specific (DOM element, Solid root, etc.)
;; opts: open map; standard keys: :on-mismatch (per Spec 011), :hydrate? (boolean)
;; unmount-fn signature: (fn [] nil) — idempotent; releases all resources
```

CLJS-Reagent: wraps `reagent.dom.client/render`; returns `(fn [] (rdc/unmount mount-point))`.
SSR-on-JVM: this function isn't called server-side — `render-to-string` is used instead. The adapter may stub `render` to throw on the JVM.

### `(render-to-string render-tree opts) → string`

Pure function. Renders the render-tree to an HTML string. JVM-runnable in the CLJS reference.

```clojure
(render-to-string render-tree opts)                     ;; → string
;; opts: open map; standard keys: :doctype? (boolean), :frame (frame-id for resolving registered views)
```

The implementation is the per-host pure walk of the render-tree (per Spec 011 §The render-tree → HTML emitter).

### `(register-context-provider frame-keyword) → component`

Optional. For substrates with a context concept, returns a component that scopes a frame to a subtree.

```clojure
(register-context-provider frame-keyword)               ;; → component (substrate-specific shape)
```

CLJS-Reagent: returns the `frame-provider` Reagent component (a React Context Provider).
TS-React: returns an equivalent React Context Provider component.
Substrates without context (Solid scopes, function-arg-explicit): not supplied — the core falls back to explicit-frame-as-argument; the user's view code must thread the frame.

### Adapter disposal lifecycle

Every adapter exposes:

```clojure
(dispose-adapter!)                                      ;; → nil
```

Called by the core when the runtime shuts down (process exit, test-frame teardown, or explicit `(rf/shutdown-runtime!)`). The adapter must:

1. Cancel all in-flight reactive subscriptions.
2. Release any host-specific resources (DOM event listeners, websocket subscribers, timers).
3. Discard internal caches.
4. Make subsequent calls to other adapter functions return `:rf.error/adapter-disposed` (or throw, host-dependent).

The adapter is single-use after disposal; restart requires `(install-adapter!)` again.

In CLJS-Reagent: clears Reagent's reaction caches, unmounts any active root.
In CLJS-headless: no-op (no resources held).

## Revertibility constraints on adapters

Per [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility), Goal 3 commits that a frame's complete runtime state is a single persistent value — reverting that value to any prior point fully reverts the frame. The adapter sits *between* the core and the host's reactivity layer, so its contract has to honour the goal explicitly: an adapter must not stash information that survives a revert of the frame value.

The rule:

> **An adapter may hold internal state if and only if that state is *derivable* from the frame's value.** State that adds information not present in the frame value is prohibited.

"Derivable" means: dropping the adapter's internal state and recomputing it from the frame's current value yields equivalent observable behaviour. Memoisation caches, reaction caches, and listener-registration tables are derivable — they exist for performance and reattachment, not to hold information. State that *adds* information (an undo stack the adapter owns; a counter the adapter increments per render and reads back later; observer-side data that survives `replace-container!`) is **not** derivable and is therefore prohibited.

### What this means per adapter primitive

- **`make-state-container`** — the container holds the frame's `app-db` value. The container's *identity* is stable but its *value* is the frame value; nothing else lives there. ✓
- **`read-container`** — pure read of the held value. No state. ✓
- **`replace-container!`** — single mutation primitive; after it returns, the container's value IS the supplied new value. The framework's revert path is `(replace-container! container prior-value)`; this is the entire mechanism. ✓
- **`subscribe-container`** — registers a change-listener. The adapter's listener registry is *transient infrastructure*: dropping it and re-registering listeners is observably equivalent (modulo a tick of latency). The registry holds no information about the frame value. ✓
- **`make-derived-value`** — caches a derived value computed from sources. The cache is a pure memoisation of `(compute-fn @source-1 @source-2 ...)`; if the cache were dropped, the next read would recompute and produce an equal value. Derivable. ✓
- **`render`** — produces DOM/UI as an external side effect. The DOM is *outside* the frame value entirely; reverting the frame value does NOT un-paint the DOM. This is the registered-fx seam Goal 3 names: external side effects need compensation, not reversal. The view layer re-renders on the next dispatch cycle and the UI follows. ✓
- **`register-context-provider`** — returns a stateless component (the host's context-provider). No state. ✓
- **`dispose-adapter!`** — tears down the adapter. After disposal, `install-adapter!` recreates a fresh one; no state survives. ✓

### Reference-adapter compliance

- **CLJS-Reagent.** Reagent's `Reaction` machinery caches derived values (memoisation: derivable). The track-cache that Reagent maintains for reaction graphs is regenerable from the underlying ratoms (which hold the frame value) — drop the cache, the next deref rebuilds it. Reagent's listener registry is transient. No observer state outside the frame value. ✓
- **CLJS plain-atom (headless).** The container is a `clojure.core/atom`; the only operation is `reset!`. No reactivity layer, no caches, no listeners. Trivially compliant. ✓
- **Hypothetical TS-Solid / Vue / RxPy.** Same constraint applies: signal libraries' caches and dependency-tracking tables are derivable; ports must verify their signal library doesn't squirrel away non-derivable state.

### What an adapter MUST NOT do

These would all violate revertibility and are prohibited by the adapter contract:

- Maintain a *separate* "previous values" history outside the frame's epoch buffer — any history-of-state lives in the framework's epoch-history (per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)), not inside the adapter.
- Hold an adapter-private mutable cell that view code can read or write through a side channel — every view-visible value must come through `read-container` (transitively, through `make-derived-value` / `subscribe-container`), so that reverting the container reverts what views see.
- Cache derived values keyed on identity rather than value — caches must invalidate on `=`-equality of inputs (per [§Subscription cache invalidation](#subscription-cache-invalidation--operational-semantics)) so that a revert to a prior `=`-equal state surfaces the prior derived values.
- Persist any internal state across `dispose-adapter!` / `install-adapter!`. Disposal is total.

### Verifying compliance

The conformance corpus does not currently include an adapter-revertibility fixture, but the operational test for any adapter is:

1. Create a frame; dispatch some events; capture the frame's value as `V1`.
2. Run more events; the container now holds `V2`.
3. Call `(replace-container! container V1)`.
4. Re-read everything that `subscribe-container` / `make-derived-value` / views can see.
5. The observable behaviour MUST equal what step 1 produced.

If any value differs, the adapter is holding state outside the frame value — a revertibility violation.

Cross-reference: [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility) names the goal; this section locks the adapter-contract obligation that follows from it.

## Subscription cache — contract and operational semantics

A subscription's value lives in the per-frame **sub-cache**. This section defines the contract: the cache shape, the lookup algorithm, the invalidation algorithm, the ref-counting and disposal rules, the layer-1/2/3 sub semantics, and the lifetime contract that ties them together. The contract is host-agnostic; the [Reagent reference adapter §Sub-cache wiring](#sub-cache-wiring-reagent-realisation) shows the CLJS realisation.

> **v1 reference.** v1's `re-frame.subs` namespace already implements most of this — the invalidation algorithm, the cache de-duplication, the disposal-on-no-readers behaviour. What is *new* in re-frame2: the cache is **per-frame** (v1 has one global cache); disposal-on-frame-destroy is a contract, not an implementation detail; the layer-1/2/3 framing is named explicitly so non-CLJS implementors can satisfy the contract without leaning on Reagent's reaction machinery.

### Cache shape

Each frame holds one sub-cache, keyed by `[query-vector]`:

```clojure
;; Per-frame sub-cache, conceptual shape.
;; In CLJS the values are Reagent Reactions; on plain-atom hosts they are
;; thunks that recompute on deref. The shape is the same.

{[query-vector]
 {:value             v          ;; current cached value
  :derived-container c          ;; substrate-specific container (per [§make-derived-value])
  :inputs            [[q1] [q2]] ;; resolved :<- chain (vector of query-vectors)
  :ref-count         n          ;; how many readers currently hold a reference
  :on-dispose        [...]      ;; callbacks that fire when ref-count drops to 0
  :registered-at     <ts>}}     ;; for trace correlation
```

The cache is held inside the frame container (per [002 §What lives in a frame](002-Frames.md#what-lives-in-a-frame)). Two frames running the same `(rf/subscribe [:cart/total])` compute against their own `app-db`s and cache against their own caches; isolation is automatic.

### Lookup algorithm

```
Lookup [query-v] in frame F:
  k ← cache-key(query-v)
  If F.sub-cache[k] exists:
    F.sub-cache[k].ref-count += 1
    return F.sub-cache[k].derived-container
  Otherwise (cache miss):
    meta    ← registrar.lookup(:sub, first(query-v))
    inputs  ← resolve-inputs(meta, F)         ;; recurses for :<- inputs
    body    ← meta.fn
    derived ← substrate.make-derived-value(
                inputs.map(c → c.derived-container),
                (in-vals) → body(in-vals, query-v))
    F.sub-cache[k] ← {:value (read derived)
                      :derived-container derived
                      :inputs inputs
                      :ref-count 1
                      :on-dispose [(fn [] (dispose-cache-slot! F k))]}
    trace! :sub/registered {:query-v query-v :frame F.id}
    return derived
```

Two properties this guarantees:

1. **De-duplication.** Concurrent equal subscriptions share one cached computation. The cache key is the query-vector; v1's `re-frame.subs/cache-key` shape is the reference (composite key with `:re-frame/q` and `:re-frame/lifecycle`).
2. **Layer-1/2/3 chaining.** A layer-2 sub's `:<-` inputs are themselves resolved via this same lookup, recursively. The recursion terminates at layer-1 subs whose inputs are not other subs but readers over `app-db` directly.

### Invalidation algorithm

The contract:

> A subscription's cached value is invalidated **only when an input the subscription depends on changes value** (by `=` equality).

The algorithm, host-agnostic:

```
On replace-container!(F.app-db, new-db):           ;; called from drain loop step 2
  ;; Phase 1: layer-1 subs (those whose inputs are app-db readers).
  For each k → entry in F.sub-cache where entry is layer-1:
    new-val ← (entry.body new-db query-v)
    If new-val = entry.value:                      ;; value-equal: keep cache
      no-op
    Else:
      entry.value ← new-val
      mark-dirty entry
      trace! :sub/recomputed {:query-v k :frame F.id}

  ;; Phase 2: layer-2+ subs cascade in topological order.
  For each k → entry in F.sub-cache where entry is layer-2+:
    If any input in entry.inputs is marked-dirty:
      new-val ← (entry.body (read-inputs entry.inputs) query-v)
      If new-val = entry.value:
        no-op
      Else:
        entry.value ← new-val
        mark-dirty entry
        trace! :sub/recomputed {:query-v k :frame F.id}

  ;; Phase 3: notify subscribers (views, tools).
  For each entry that is marked-dirty:
    notify each registered subscriber
```

Three load-bearing properties:

1. **No path-overlap means no recompute.** A `:cart/total` sub depending on `[:cart :items]` does not recompute when `:user-profile` changes. (How the implementation knows: `=`-equality on the input value. If the input is value-equal, the sub stays cached.)
2. **Value-equal means no propagation.** A no-op `(assoc db :x (:x db))` produces a `=`-equal `app-db`; no sub recomputes; no view re-renders.
3. **Topological cascade.** Layer-2 subs see the new layer-1 values when they recompute. Layer-3 subs see new layer-2 values. The cascade respects the static `:<-` topology recorded during registration.

Reagent realises this automatically: each `Reaction` re-runs only when its derefs change by `=`; the reactive graph is built from the `:<-` chain. Non-CLJS implementations (or the plain-atom adapter) must satisfy the contract explicitly — Phase 1 / Phase 2 / Phase 3 above is the fallback algorithm.

### Layer-1, layer-2, layer-3 sub semantics

The terminology comes from re-frame v1; the semantics carry over.

| Layer | Inputs | Example | Recompute trigger |
|---|---|---|---|
| **Layer-1** | Reads `app-db` directly | `(reg-sub :user (fn [db _] (:user db)))` | The path it reads from `app-db` changes by `=`. |
| **Layer-2** | Reads other subs via `:<-` | `(reg-sub :user-name :<- [:user] (fn [u _] (:name u)))` | Any input sub's value changes by `=`. |
| **Layer-3** | Reads other subs via `:<-`, where one or more inputs are themselves layer-2 | `(reg-sub :user-greeting :<- [:user-name] :<- [:locale] (fn [...] ...))` | Any input sub's value changes by `=`. |

Layers ≥ 3 are conventionally just "layer-2+" — the algorithm treats them all the same. The distinction matters for understanding the cascade order (layer-1 settles before layer-2, layer-2 before layer-3) but not for the implementation, which uses `:<-` chain depth implicitly via topological iteration.

### Reference counting and disposal

The cache is **not** strong-referenced from the frame for the lifetime of the frame; entries dispose when their last reader goes away.

```
On subscriber detach (view unmounts, tool disconnects):
  entry.ref-count -= 1
  If entry.ref-count == 0:
    For each dispose-fn in entry.on-dispose:
      dispose-fn()
    F.sub-cache.dissoc(k)
    trace! :sub/disposed {:query-v k :frame F.id}
```

The `on-dispose` hook lets the substrate adapter release substrate-specific resources (a Reagent reaction, a Solid signal, a Vue computed) before the cache slot is removed. The CLJS reference uses `interop/add-on-dispose!` per the Reagent realisation in [§Sub-cache wiring](#sub-cache-wiring-reagent-realisation).

Three subtleties:

1. **A sub can become live again after disposal.** A view unmounts (ref-count → 0, slot disposed); later, the same view re-mounts (cache miss, fresh computation). This is correct — the cache is performance, not state. The recomputed value will equal what was disposed (same body, same `app-db`); no observable difference.
2. **Eager subs.** A future `:reg-sub-by-path` (post-v1) might keep its cache slot live regardless of ref-count, for performance. v1 has no eager subs; if added, the contract surface is `entry.eager? = true` and the disposal path skips the slot.
3. **Disposal cascades.** When a layer-2 sub disposes, its layer-1 inputs lose one reader each; if they were held only by that layer-2 sub, they also dispose. The cascade is automatic via `on-dispose` and ref-counts.

### Lifetime contract — frame disposal

When a frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)):

```
On destroy-frame F:
  For each k → entry in F.sub-cache:
    For each dispose-fn in entry.on-dispose:
      dispose-fn()
  F.sub-cache.clear()
  trace! :sub-cache/cleared {:frame F.id}
```

Three contract guarantees this enforces:

1. **No leaks.** Every cached substrate-specific resource (Reagent reaction, Solid signal) is released. Long-lived processes that create and destroy frames (test runs, SSR request handling) reach steady-state memory.
2. **No stale reads.** After `destroy-frame`, attempts to subscribe to `F` raise `:rf.error/frame-destroyed`. There is no path that returns a value from a destroyed frame's cache.
3. **Adapter symmetry.** The adapter's `dispose-adapter!` ([§Adapter disposal lifecycle](#adapter-disposal-lifecycle)) is the per-process counterpart; it disposes every frame's sub-cache as part of process teardown.

### Cross-spec interactions

- **Drain-loop integration** ([002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode)): invalidation fires once per `process-event!` step 2, after the `:db` write and before the `:fx` walk. A handler can rely on subscriptions reflecting the new `app-db` from inside `do-fx`.
- **Hot reload** ([001-Registration](001-Registration.md)): re-registering a sub disposes the cache slot for that query (regardless of ref-count); next subscribe rebuilds with the new body. Tracked with the rest of hot-reload semantics in the bead-tracked work.
- **Machine subscriptions** ([005 §Subscribing to machines via `sub-machine`](005-StateMachines.md#subscribing-to-machines-via-sub-machine)): a machine's snapshot lives at `[:rf/machines <id>]` and is read like any other slice of `app-db`; `sub-machine` is a thin convenience over `reg-sub`. Sub-cache invalidation works the same.

### Per-host implementation notes

- **CLJS-Reagent.** Reagent's `Reaction` handles invalidation, ref-counting, and disposal automatically. Layer-1 reads via `r/atom` deref; layer-2+ build a graph of reactions; equality checks happen at each layer. The cache wraps Reagent's own machinery — see [§Sub-cache wiring (Reagent realisation)](#sub-cache-wiring-reagent-realisation).
- **CLJS-headless / SSR.** No caching. `compute-sub` is a pure function that runs the sub's body fresh every time it's called. Cheap because no SSR run does it twice. The contract above is satisfied trivially: no cached values means no invalidation question.
- **TypeScript with Solid.** Solid's `createMemo` does the equivalent: dependency tracking, equality check, automatic disposal when the owning scope tears down.
- **Other-host implementations.** Must satisfy the algorithm above explicitly. Tools relying on the trace stream's `:sub/recomputed` events depend on the equality-check-on-invalidation rule.

## What happens when a sub references an unknown sub

A sub registered via `:<-` referencing an undefined input is an error:

```clojure
(rf/reg-sub :cart/total
  :<- [:cart/items]                                 ;; OK
  :<- [:nonexistent/data]                           ;; ❌ no :nonexistent/data registered
  (fn [...] ...))
```

The behaviour is environment-specific:

- **At registration time** (when the macro runs), the runtime cannot fully validate `:<-` — the input might be registered later in the load order.
- **At first use** (when something tries to subscribe to `:cart/total`), the runtime resolves all inputs. If any input is unregistered, the runtime emits a `:rf.error/no-such-sub` trace event (per [009 §Error contract](009-Instrumentation.md#error-contract)) and returns `nil` for that input. Recovery: `:replaced-with-default`.
- **In strict mode** (`:strict-subs` config), the unresolved-input error escalates to a thrown exception so the bug is loud during dev.

The subscription's body still runs with `nil` substituted for the unresolved input. This is intentional: it keeps the trace stream readable (the agent sees one error event rather than a chain of cascading throws) and lets the caller handle the missing data gracefully if it can.

## CLJS reference: Reagent as default adapter

The CLJS reference ships **two** adapters in one package: a Reagent adapter (browser default) and a plain-atom adapter (JVM, used by SSR and headless tests). Both implement the closed nine-fn contract above; the runtime picks per platform.

This section is the **bridging pseudocode** for both. For each contract function, the pseudocode shows which Reagent (or, on the JVM, plain-Clojure) primitive realises it. An AI implementing the CLJS reference can lift this directly; non-CLJS implementors read it as one worked example of the contract.

> **Reading note.** v1 of re-frame already implements most of these primitives (`re-frame.interop`, `re-frame.subs`, `re-frame.subs/cache-and-return`, `reagent.core/atom`, `reagent.ratom/make-reaction`). The pseudocode below tracks v1's working code closely; what's *new* is the contract surface itself (the v1 code does not separate "core" from "adapter" — the substrate decoupling is the v2 work). Use v1 source as the implementation reference for everything below the contract line.

### Per-contract-fn pseudocode

```clojure
(ns re-frame.substrate.reagent
  (:require [reagent.core       :as r]
            [reagent.ratom      :as ratom]
            [reagent.dom.client :as rdc]
            [re-frame.frame-context :as fc]            ;; the frame-keyword React Context
            [re-frame.render.hiccup-to-html :as hiccup]
            [re-frame.subs.cache :as sub-cache]))

;; -- 1. make-state-container ------------------------------------------------
;; A Reagent ratom holds the frame's app-db. r/atom is the only mutation point;
;; reagent.ratom captures all the change-tracking semantics for free.
(defn make-state-container [initial-value]
  (r/atom initial-value))                             ;; → IReactiveAtom

;; -- 2. read-container ------------------------------------------------------
;; Plain deref. Outside a reactive context this does not register a dependency;
;; inside one, Reagent automatically wires the dependency edge.
(defn read-container [container]
  @container)

;; -- 3. replace-container! --------------------------------------------------
;; The single mutation primitive. Reagent's reset! schedules dependent
;; reactions; the core's invalidation hook runs synchronously *before* the
;; first :fx entry per [002 §:fx ordering] — Reagent's batching cooperates
;; because reactions only re-fire on next deref or the next animation frame.
(defn replace-container! [container new-value]
  (reset! container new-value)
  nil)

;; -- 4. subscribe-container -------------------------------------------------
;; Reagent itself drives invalidation through reactions; the explicit
;; subscribe-container surface exists for non-reactive substrates and tools
;; that want raw change events. Implemented via add-watch on the underlying
;; ratom — observably equivalent across substrates per [§Operational semantics].
(defn subscribe-container [container on-change]
  (let [k (gensym "rf-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn unsubscribe [] (remove-watch container k))))

;; -- 5. make-derived-value --------------------------------------------------
;; reagent.ratom/make-reaction wraps a compute-fn in a Reaction that
;; (a) re-runs only when its derefs change by =, (b) caches the result,
;; (c) participates in the reactive graph so dependent views auto-rerender.
;; Equality-on-=-of-inputs is the rule the sub-cache invariant relies on.
(defn make-derived-value [source-containers compute-fn]
  (ratom/make-reaction
    (fn [] (apply compute-fn (map deref source-containers)))))

;; -- 6. render --------------------------------------------------------------
;; reagent.dom.client/render mounts the React 18 root; the returned unmount-fn
;; is the only handle the runtime keeps. Idempotent: calling unmount twice
;; is a no-op.
(defn render [render-tree mount-point opts]
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (rdc/hydrate-root mount-point render-tree)
      (rdc/render        mount-point render-tree))
    (fn unmount [] (rdc/unmount mount-point))))

;; -- 7. render-to-string ----------------------------------------------------
;; Pure JVM-runnable walk over the hiccup render-tree per [011-SSR
;; §The render-tree → HTML emitter (CLJS reference)]. No Reagent, no React;
;; the same pure emitter the plain-atom adapter uses.
(defn render-to-string [render-tree opts]
  (hiccup/emit render-tree opts))

;; -- 8. register-context-provider -------------------------------------------
;; Returns the frame-provider component (a React Context Provider whose value
;; is the frame keyword, never the frame record — see [002 §Reading the frame
;; from React context]). Re-registering a frame is picked up on next render
;; because the context value is a keyword resolved against the registry.
(defn register-context-provider [frame-keyword]
  (fc/provider frame-keyword))

;; -- 9. dispose-adapter! ----------------------------------------------------
;; Total disposal. Order matters: tear down sub-cache reactions first (so
;; nothing observes the ratom going away), then the frame-providers, then
;; release the Reagent reaction caches Reagent itself owns.
(defn dispose-adapter! []
  (sub-cache/dispose-all!)                            ;; per-frame sub-cache disposal
  (fc/dispose-providers!)                             ;; release any cached providers
  (ratom/flush!)                                      ;; drain Reagent's pending queue
  nil)
```

### Sub-cache wiring (Reagent realisation)

The per-frame **sub-cache** ([§Subscription cache invalidation](#subscription-cache-invalidation--operational-semantics)) is the bridge between `reg-sub` and a Reagent reaction. v1's working algorithm in `re-frame.subs` is the reference. The CLJS-reference v2 wiring:

```clojure
;; The cache is per-frame: keyed by [query-vector], stored on the frame.
;; Each entry points to a Reagent Reaction that wraps the sub's body.

(defn subscribe [frame query-v]
  (let [k (cache-key query-v)
        cache (:sub-cache frame)]
    (or (get @cache k)                                ;; cache hit: existing reaction
        (let [r (compute-and-cache frame query-v)]     ;; cache miss: build chain
          r))))

(defn- compute-and-cache [frame query-v]
  (let [meta     (registrar/lookup :sub (first query-v))
        inputs   (mapv (fn [input-q] (subscribe frame input-q))   ;; recurse for :<-
                       (:input-signals meta))
        body-fn  (:fn meta)
        ;; The Reaction wraps the sub body. Reagent re-runs body-fn only when
        ;; one of its derefs (the inputs) changes by =. This is the layer-1/2/3
        ;; sub semantics from v1 — same algorithm, now scoped per frame.
        r        (ratom/make-reaction
                   (fn [] (apply body-fn (conj (mapv deref inputs) query-v))))]
    (swap! (:sub-cache frame) assoc k r)
    ;; When this reaction's last reader disposes, GC the cache slot.
    (interop/add-on-dispose! r
      (fn []
        (swap! (:sub-cache frame)
               (fn [cm] (if (identical? r (get cm k)) (dissoc cm k) cm)))))
    r))

(defn dispose-frame-subs! [frame]
  (let [cache (:sub-cache frame)]
    (doseq [[_ r] @cache] (interop/dispose! r))
    (reset! cache {})))
```

What this gives:

- **Hot reload** ([001-Registration](001-Registration.md), bead-tracked): re-registering a sub disposes the cache slot for that query; next subscribe rebuilds with the new body.
- **Frame teardown** ([002 §Destroy](002-Frames.md#destroy)): `dispose-frame-subs!` fires from the frame's lifecycle hook; every reaction is disposed; no leaks.
- **Layer-1/2/3 semantics**: the recursion in `compute-and-cache` builds a chain. A layer-2 sub's reaction `:<-`s into a layer-1 sub's reaction; Reagent's tracking propagates `=`-equality up the chain.

### Frame-provider via React context

`register-context-provider` returns the **frame-provider** component. The CLJS implementation lives in `re-frame.frame-context`; the design is owned by [002 §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail) — this section names the adapter-side hook into it.

```clojure
;; The single React Context. Default value is :rf/default — the Spec
;; guarantees this frame always exists per [002 §:rf/default].
(defonce ^:private frame-context
  (.createContext js/React :rf/default))

(defn provider [frame-keyword]
  ;; Returns a Reagent component the user includes in their tree:
  ;;   [provider :auth
  ;;     [some-view ...]]
  ;; The Provider's value is the keyword, never the frame record;
  ;; consumers resolve the keyword against the global frame registry on
  ;; every read, so re-registering frames is picked up automatically.
  (fn [frame-kw & children]
    (into [:> (.-Provider frame-context) {:value frame-kw}] children)))
```

The `read-frame-from-context` lookup chain (`*current-frame*` dynamic var → React context → `:rf/default`) is documented in [002 §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail).

**Plain-fn-under-non-default-frame warning.** A plain Reagent fn (not registered via `reg-view`) cannot subscribe to the closest enclosing `frame-provider` because plain fns lack the `^{:context-type frame-context}` metadata `reg-view` attaches. When such a plain fn calls `(rf/subscribe ...)` *and* the React-context tier resolves to a non-default frame, the runtime emits `:rf.warning/plain-fn-under-non-default-frame-once` (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)) — once per (fn, frame) pair, not per render — pointing the user at `reg-view` or `with-frame`.

The detection sits in `subscribe`: if `(reagent.core/current-component)` returns a component whose `contextType` does not match `frame-context`, the dynamic-var tier is checked; if neither names a non-default frame, no warning fires; if the closest enclosing provider names a non-default frame and `*current-frame*` is unset, the warning fires.

### Plain-atom adapter (JVM, SSR, headless)

The **plain-atom adapter** is the same nine-fn contract realised against `clojure.core/atom` instead of Reagent. It is what runs on the JVM (per [000 §C2. Cross-platform: JVM interop preserved](000-Vision.md#c2-cross-platform-jvm-interop-preserved)) and what SSR and headless tests use ([§SSR-specific behaviour](#ssr-specific-behaviour), [008-Testing](008-Testing.md)).

How it differs from the Reagent adapter:

```clojure
(ns re-frame.substrate.plain-atom
  (:require [re-frame.render.hiccup-to-html :as hiccup]))

(defn make-state-container [initial-value]
  (atom initial-value))                               ;; clojure.core/atom; no reactivity

(defn read-container [container]    @container)
(defn replace-container! [container nu] (reset! container nu) nil)

(defn subscribe-container [container on-change]
  (let [k (gensym "rf-sub-")]
    (add-watch container k (fn [_ _ prev nu] (on-change prev nu)))
    (fn [] (remove-watch container k))))

;; No Reaction — derived values are computed on every read. SSR runs each
;; sub once, so caching wouldn't help. Tests that want caching swap in the
;; Reagent adapter via the reagent-cljs-jvm interop layer.
(defn make-derived-value [source-containers compute-fn]
  (reify clojure.lang.IDeref
    (deref [_] (apply compute-fn (map deref source-containers)))))

;; render is not used on the JVM — render-to-string is the only path.
(defn render [_ _ _]
  (throw (ex-info "render not supported on plain-atom adapter; use render-to-string"
                  {:rf.error :rf.error/render-on-headless-adapter})))

(defn render-to-string [render-tree opts]
  (hiccup/emit render-tree opts))                     ;; same emitter as Reagent

;; No React, no context concept. The pattern's explicit-frame addressing
;; (per [002 §Routing]) handles frame routing without a context provider.
(defn register-context-provider [_frame-keyword]
  nil)                                                ;; optional fn, returning nil is the spec'd absence

(defn dispose-adapter! []
  ;; Watch handles are GC'd with their atoms; nothing else to clean up.
  nil)
```

Three design decisions worth naming:

1. **No caching for derived values.** SSR runs each sub at most a handful of times per request; caching would add complexity for negligible gain. Tests that want repeatable performance characteristics can swap in the Reagent adapter on the JVM.
2. **`render` throws.** SSR uses `render-to-string` exclusively; calling `render` on the JVM is a programmer error worth surfacing loudly. The conformance fixture for `:rf.error/render-on-headless-adapter` pins this.
3. **No context provider.** The pattern-level contract is explicit-frame addressing. Hosts without a context concept fall back to threading the frame as an argument; the headless adapter is the simplest such host.

The plain-atom adapter is **trivially** revertibility-compliant ([§Reference-adapter compliance](#reference-adapter-compliance)) because it holds no state outside the container.

### Adapter selection at boot

```clojure
;; CLJS reference boot path, simplified
(defn install-default-adapter! []
  #?(:cljs (re-frame.substrate/install! re-frame.substrate.reagent/adapter)
     :clj  (re-frame.substrate/install! re-frame.substrate.plain-atom/adapter)))
```

`install-adapter!` is called once per process. Subsequent calls without an intervening `dispose-adapter!` raise `:rf.error/adapter-already-installed` ([§Single adapter per process](#single-adapter-per-process)).

Other CLJS adapters (UIx, Helix) plug in via the same shape, replacing only the React-rendering and context-provider primitives; the sub-cache wiring and the plain-atom JVM half are unchanged.

## Subscription topology vs subscription tracking

A subtle distinction worth pulling out: **the static topology of the sub graph is core; the runtime tracking is adapter**.

The topology is "what depends on what" — the static `:<-` chain you can derive from registrations alone, without running any code. `(rf/sub-topology)` returns this graph as data. JVM-runnable. No adapter needed.

The tracking is "when source X changes, recompute everyone who depends on X" — the runtime mechanism that makes views update reactively. This requires the adapter's `make-derived-value` and is substrate-specific.

In CLJS dev-mode tests, you often want sub computation without tracking: `(compute-sub [:total] db-value)` runs the sub's body against a static `app-db` value and returns the computed result. Pure function. No Reagent, no reactions. This is the "JVM-runnable" path that [008-Testing](008-Testing.md) and [011-SSR](011-SSR.md) use.

## SSR-specific behaviour

Per [011](011-SSR.md), the server-side render path doesn't use the adapter's reactivity machinery at all. The flow:

1. Server creates a frame (per [002 §reg-frame](002-Frames.md#reg-frame-is-atomic)).
2. The frame's `app-db` is a plain atom (the **core's plain-atom adapter**, not the Reagent adapter).
3. `:on-create` events run; the drain settles.
4. The view fn is called as a *plain function* against the now-stable `app-db` value.
5. The hiccup output is rendered to a string by `render-to-string`.

No Reagent. No React. No reactivity. Pure data → pure data → string.

The adapter that the core uses on the server is the **plain-atom adapter** (or "headless adapter"). The CLJS reference ships this alongside the Reagent adapter; the runtime picks based on platform.

## CLJS reference scope

The CLJS reference ships:

- The substrate-agnostic core (the registrar, the drain, the dispatch envelope, the trace stream, sub topology, sub computation, effect-map interpretation) as substrate-independent code.
- A **Reagent adapter** as the default for the browser.
- A **plain-atom (headless) adapter** for the JVM, used by SSR and headless tests.
- The adapter API contract documented above.

Per-host adapters for non-CLJS implementations (TS+React, TS+Solid, Vue, Python+RxPy, etc.) ship as separate packages, implementing the same contract.

## Open questions

### Cooperative rendering substrate

A cooperative rendering substrate — a rendering layer designed natively to cooperate with re-frame, instead of re-frame wrapping Reagent — is on the horizon. Substrate-agnostic decoupling (this Spec) is the prerequisite. Whether the cooperative variant ships depends on a benefits-vs-cost evaluation in a later cycle.

### Multi-adapter coexistence

The current contract is single-adapter-per-process. If a concrete use case for per-frame adapter selection emerges, multi-adapter support can be added additively without breaking the single-adapter contract.

## Resolved decisions

### Adapter selection

The CLJS reference's `re-frame.core` namespace uses reader conditionals: `:cljs` branch requires `re-frame.substrate.reagent`; `:clj` branch requires `re-frame.substrate.plain-atom`. The default adapter is wired automatically based on which target is being compiled.

Tests and atypical setups override via `(re-frame.core/install-adapter! :plain-atom)` at startup, before any frame is created. Calling it after frames exist is an error (`:rf.error/adapter-already-installed` trace event; recovery: `:no-recovery`, the call is rejected).

SSR-on-Node-in-CLJS (rare but valid) installs `:plain-atom` explicitly; the default browser-targeted Reagent adapter doesn't try to require Reagent dependencies it can't load.

Other-language ports follow the same pattern: a default per build target, explicit override available.

### Adapter introspection

`(rf/current-adapter)` returns a keyword identifying the active adapter:

- `:reagent` — CLJS browser default
- `:plain-atom` — CLJS JVM (SSR, headless tests) or Node-based CLJS
- `:custom` — user installed a custom adapter (UIx, Helix, hypothetical TS-side adapter)

Tools (10x, re-frame-pair) use this to branch on host capabilities — for instance, the time-travel UI is meaningful in browser-Reagent but not in plain-atom.

The keyword is informational. Behaviour-affecting decisions should be based on `:platforms` metadata (per [011 §S-3](011-SSR.md#s-3-effect-handling-on-the-server--resolved)) or on explicit configuration, not on which adapter is loaded.

### Single adapter per process

One adapter per process. Frames within a process all use the same adapter.

Reasons:

1. Per-frame adapter selection adds complexity in the runtime, the registry, and the dispatch envelope (which adapter's reactivity is in scope?).
2. The use cases people propose for multi-adapter (headless tests inside a browser app; mixed Reagent and UIx) are better served by separate processes (test JVMs, separate apps) or by the existing `compute-sub` headless path (no reactivity at all).

Re-installing an adapter after frames exist is rejected (per [Adapter selection](#adapter-selection) above).

## Cross-references

- [000 §Substrate decoupling](000-Vision.md#substrate-decoupling-reagent-fusion) — the framework-level commitment to substrate decoupling.
- [011-SSR.md](011-SSR.md) — SSR uses the plain-atom adapter on the JVM.
- [008-Testing.md](008-Testing.md) — the headless-test path uses the plain-atom adapter.
- [002-Frames.md](002-Frames.md) — frames are the core's primary structure; the adapter holds their `app-db` containers.
- [004-Views.md](004-Views.md) — view rendering is the adapter's job.
