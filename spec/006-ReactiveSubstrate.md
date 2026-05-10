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

The v1 adapter surface is **six required functions, two optional functions, and one lifecycle function** — nine entries in total. The Normative contract section below specifies the call-shape for each; [§Operational semantics](#subscription-cache--contract-and-operational-semantics) covers cache-invalidation behaviour the adapter must respect; [§CLJS reference: Reagent as default adapter](#cljs-reference-reagent-as-default-adapter) covers reference-host implementation notes.

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
- Cache derived values keyed on identity rather than value — caches must invalidate on `=`-equality of inputs (per [§Subscription cache invalidation](#subscription-cache--contract-and-operational-semantics)) so that a revert to a prior `=`-equal state surfaces the prior derived values.
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

## Source-coord annotation (mandatory; rf2-z7f7 / rf2-z9n1)

Every substrate adapter MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. The annotation is a **normative entry on the adapter contract** — devtools and pair-shaped tools (re-frame-pair, re-frame-10x, IDE jump-to-source per [Tool-Pair §Source-mapping UI clicks back to code](Tool-Pair.md#source-mapping-ui-clicks-back-to-code)) consume it to map a clicked DOM node back to the reg-view call site. Without this annotation an adapter is non-conformant.

### Capture mechanism

Source coordinates are captured at `reg-view` macro-expansion time from `(meta &form)` (`:line`, `:column`) and the compile-time `*ns*` / `*file*` (per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). The macro stamps them onto the registry slot's metadata; the substrate adapter reads them at render time when wiring the wrapper that produces the annotated DOM element. No runtime cost in the hot path: the coord string is computed once at registration time, then merged into attrs each render.

### Attribute value format

The attribute value is a colon-separated four-segment string — the committed public contract `:rf/source-coord-attr` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-attr):

```
data-rf2-source-coord="<ns>:<sym>:<line>:<col>"
```

- `<ns>` is the keyword id's namespace — typically `(namespace (registry-id))`.
- `<sym>` is the keyword id's name — `(name (registry-id))`. Note this is the **registry handler-id**, not a file path.
- `<line>` is the integer source line; `?` when not captured.
- `<col>` is the integer source column; `?` when not captured.

A registration that bypassed the macro path (programmatic `reg-view*` with no captured coords) still annotates with `<ns>:<sym>:?:?` — degrading gracefully so pair tools can still resolve `<ns>/<sym>` via the registrar's `:rf/id` lookup. To recover the registration's full source-coord shape (including `:file`), pair tools follow up with `(rf/handler-meta :view <handler-id>)` which returns `:rf/source-coord-meta` per [Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta) — `:file` is **not** encoded in the attribute string.

### Production elision (mandatory)

The annotation site MUST sit inside `(when interop/debug-enabled? ...)` (the CLJS mirror of `goog.DEBUG`). Production builds (`:advanced` + `goog.DEBUG=false`) MUST NOT emit the attribute — the entire injection branch dead-code-eliminates so the literal `data-rf2-source-coord` string fragment does not appear in the bundle. Per [Spec 009 §Production builds](009-Instrumentation.md), the elision is verified by a grep against the production bundle (`scripts/check-elision.cjs`); the `data-rf2-source-coord` sentinel is part of the standard sentinel set.

### Documented exemption: non-DOM roots

A registered view whose root element is one of:

- a React Fragment (`:<>`),
- a host-component head (`:>` in Reagent — the React-interop marker),
- a function/component head (e.g. another reg-view'd component),

…is **exempt** from the annotation. The adapter MUST emit a one-shot warning per id (so the developer learns the pair-tool footgun without spamming the console on re-render) and MUST NOT inject the attribute in these cases. Pair tools fall back to `(rf/handler-meta :view id)` for these nodes — the registry slot still carries the captured `:ns` / `:line` / `:file`; only the DOM-node-level mapping is skipped.

The exemption is principled: a Fragment has no DOM element to annotate, and a `[:> Cmp …]` interop call hands the props map straight through to React's component (which may not be a DOM-tag, may not accept arbitrary HTML attributes, and certainly should not have framework-derived strings inserted into it). Annotating these would either be a no-op (Fragment) or risk mutating semantics (interop).

### Form-2 handling

When a registered view's render-fn returns a fn (Reagent's Form-2 closure shape per [Spec 004 §Form-2](004-Views.md#form-2-closure--supported-prefer-form-1--explicit-setup-event)), the adapter wraps the returned fn so the inner-fn's hiccup output is annotated on the next call. Annotation lands on the eventual rendered DOM root, not on the outer fn (which is not a DOM element).

### Cross-host

Substrate adapters that do not expose a DOM-attribute concept (Solid scopes, function-arg-explicit, headless test adapters) are exempt. Adapters whose host is React-shaped (Reagent, UIx [rf2-3yij], Helix [rf2-2qit]) MUST honour this contract. The [JVM SSR emitter](011-SSR.md#source-coord-annotation-under-ssr) is the server-side equivalent — it injects the same attribute when emitting HTML for a registered view, so server-rendered pages carry the annotation too.

### Source-coord stamping for state machines (rf2-8bp3)

The view-side annotation above is one half of the tool-pair source-mapping contract. The other half is **the spec-side stamping for state machines**: per [Spec 005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping-rf2-8bp3), the `reg-machine` macro walks its literal spec form at expansion time and attaches a `:rf.machine/source-coords` index keyed by spec-path tuples (`[:guards :form-valid?]`, `[:states :idle :on :submit]`, etc.). Pair tools that surface a "click on a transition's call site" gesture read the index back via `(:rf.machine/source-coords (rf/machine-meta machine-id))` — symmetric to how they consume `data-rf2-source-coord` for views.

Both surfaces share the production-elision contract: the stamping branch is gated on `interop/debug-enabled?`, so under `:advanced` + `goog.DEBUG=false` the closure compiler folds it away. The `rf.machine/source-coords` keyword is part of the standard `scripts/check-elision.cjs` sentinel set (verified ABSENT in the production bundle, PRESENT in the control bundle).

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
  :pending-dispose   <handle>   ;; opaque timer-handle iff disposal is scheduled (rf2-s9dn)
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

1. **De-duplication.** Concurrent equal subscriptions share one cached computation. The cache key is the query-vector itself. v2 has a single disposal algorithm (deferred ref-counting; see [§Reference counting and disposal](#reference-counting-and-disposal)); the v1-era composite key with `:re-frame/q` and `:re-frame/lifecycle` is gone (rf2-7cb2 / rf2-s9dn — the `re-frame.alpha` namespace and its lifecycle policies were dissolved before v1 ship).
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

The cache is **not** strong-referenced from the frame for the lifetime of the frame; entries dispose when their last reader goes away. The disposal algorithm is **deferred ref-counting with a grace-period** — a single algorithm. There are no pluggable lifecycle policies; the v1 alpha namespace's `:safe`, `:no-cache`, `:reactive`, and `:forever` lifecycles are not part of v2 (rf2-7cb2 / rf2-s9dn).

When the last subscriber drops, the entry is **scheduled** for disposal after the configured grace-period elapses. If a new subscriber arrives within that window, the scheduled disposal is **cancelled** and the cached value is reused.

```
On subscriber detach (view unmounts, tool disconnects):
  entry.ref-count -= 1
  If entry.ref-count == 0:
    handle ← schedule-after grace-period-ms:
               (when entry.ref-count == 0:
                  for each dispose-fn in entry.on-dispose: dispose-fn()
                  F.sub-cache.dissoc(k)
                  trace! :sub/disposed {:query-v k :frame F.id})
    entry.pending-dispose ← handle

On subscriber attach (cache HIT; the slot already exists):
  entry.ref-count += 1
  if entry.pending-dispose is not nil:
    cancel entry.pending-dispose
    entry.pending-dispose ← nil
```

#### Disposal guarantees

- **Zero-subscriber → grace-period elapses → disposed.** When `ref-count` reaches 0 and no resubscribe arrives within `grace-period-ms`, the on-dispose callbacks fire, the cache slot is removed, and a `:sub/disposed` trace event is emitted.
- **Resubscribe within grace-period → disposal cancelled, value reused.** If a new subscriber arrives before the timer fires, the timer is cancelled and the existing reaction (and its cached value) is returned. The new subscriber observes no recomputation; the underlying substrate-specific container is the same one previously cached.
- **Synchronous disposal when `grace-period-ms = 0`.** Setting the grace-period to 0 yields the v1-style "ref-count → 0 → dispose immediately" semantic. Useful for tests, REPL sessions, and any context that wants deterministic teardown without timer-driven races.
- **Hot-reload preserves the contract.** Re-registering a sub disposes every cached slot for that query (regardless of ref-count) and cancels any pending grace-period timers — the next subscribe builds afresh against the new body.
- **Frame teardown preserves the contract.** Destroying a frame disposes every cached slot and cancels every pending grace-period timer; see [§Lifetime contract — frame disposal](#lifetime-contract--frame-disposal).

#### The grace-period parameter

The grace-period is a per-runtime configuration knob:

| Knob | Default | Configure |
|---|---|---|
| `grace-period-ms` | **50ms** | `(rf/configure :sub-cache {:grace-period-ms N})` |

The default of **50ms** is chosen empirically: long enough to bridge React re-render churn (where a Reagent component briefly unmounts then re-renders with the same subscription), short enough that genuine disposal is observable promptly and memory does not accumulate under load. Implementations targeting non-React substrates may pick a different default but should document it.

`N` is a non-negative integer; `0` selects synchronous disposal.

#### On-dispose hooks

The `on-dispose` hook lets the substrate adapter release substrate-specific resources (a Reagent reaction, a Solid signal, a Vue computed) before the cache slot is removed. Hooks fire **after** the grace-period elapses (or synchronously when `grace-period-ms = 0`). The CLJS reference uses `interop/add-on-dispose!` per the Reagent realisation in [§Sub-cache wiring](#sub-cache-wiring-reagent-realisation).

#### Three subtleties

1. **A sub can become live again after disposal.** A view unmounts; if no resubscribe arrives within the grace-period, the slot disposes. Later, the same view re-mounts (cache miss, fresh computation). This is correct — the cache is performance, not state. The recomputed value will equal what was disposed (same body, same `app-db`); no observable difference.
2. **Eager subs.** A future `:reg-sub-by-path` (post-v1) might keep its cache slot live regardless of ref-count, for performance. v1 has no eager subs; if added, the contract surface is `entry.eager? = true` and the disposal path skips the slot.
3. **Disposal cascades.** When a layer-2 sub disposes, its layer-1 inputs lose one reader each; if they were held only by that layer-2 sub, they enter their own grace-period. Cascading disposals each pay the grace-period independently, but the timers run concurrently — total wall-clock disposal time is one grace-period regardless of chain depth.

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
- **`clear-sub` is a registry-only operation** (rf2-79tl): `(clear-sub id)` and `(clear-sub)` remove `:sub` registrations but leave already-materialised per-frame cache slots in place. Caching is governed by the disposal contract above (ref-count + grace-period, hot-reload eviction, frame-destroy eviction); cache eviction independent of those triggers is `clear-subscription-cache!`'s job. This split preserves v1's documented contract — see the `clear-sub` docstring's note: "Depending on the usecase, it may be necessary to call `clear-subscription-cache!` afterwards."

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

A related case is `subscribe` itself naming an unregistered sub-id — most often a boot-order or lazy-load race where the consumer subscribes before the registering namespace has loaded. The runtime emits the same `:rf.error/no-such-sub` trace event, returns a nil-yielding reaction (recovery `:replaced-with-default`), and **does not** populate the per-frame sub-cache. Skipping the cache on miss preserves the v1 semantic that a later registration is observed by the next subscribe — no stale `nil`-reaction lingers (rf2-l9u5).

## CLJS reference: Reagent as default adapter

The CLJS reference ships **two** adapters across **two Maven artefacts**: the plain-atom (JVM/headless) adapter ships in the core artefact (`day8/re-frame-2`); the Reagent adapter ships in its own sibling artefact (`day8/re-frame-2-reagent`). Both implement the closed nine-fn contract above; the runtime picks per platform. UIx and Helix adapters ship as further sibling artefacts as they land. Per [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention) and rf2-0hxm.

This section is the **bridging pseudocode** for both. For each contract function, the pseudocode shows which Reagent (or, on the JVM, plain-Clojure) primitive realises it. An AI implementing the CLJS reference can lift this directly; non-CLJS implementors read it as one worked example of the contract.

> **Reading note.** v1 of re-frame already implements most of these primitives (`re-frame.interop`, `re-frame.subs`, `re-frame.subs/cache-and-return`, `reagent.core/atom`, `reagent.ratom/make-reaction`). The pseudocode below tracks v1's working code closely; what's *new* is the contract surface itself (the v1 code does not separate "core" from "adapter" — the substrate decoupling is the v2 work). Use v1 source as the implementation reference for everything below the contract line.

### Per-contract-fn pseudocode

```clojure
(ns re-frame.adapter.reagent
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

The per-frame **sub-cache** ([§Subscription cache invalidation](#subscription-cache--contract-and-operational-semantics)) is the bridge between `reg-sub` and a Reagent reaction. v1's working algorithm in `re-frame.subs` is the reference. The CLJS-reference v2 wiring:

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

(defn provider []
  ;; Returns a Reagent component the user includes in their tree:
  ;;   [provider :auth
  ;;     [some-view ...]]
  ;; The Provider's value is the keyword, never the frame record;
  ;; consumers resolve the keyword against the global frame registry on
  ;; every read, so re-registering frames is picked up automatically.
  ;; 0-arity (rf2-4y60): a single built component services every frame —
  ;; the frame keyword lives in the Provider's :value at render time, not
  ;; in a build-time closure.
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

Per [rf2-84po](#) (resolves [rf2-4cb6](#)) `(rf/init!)` resolves the adapter through a default-adapter registry populated by substrate-adapter ns-loads:

```clojure
;; Adapter side: each substrate ns calls register-default-adapter!
;; at load time. The registration is idempotent — wrapping in defonce
;; means a hot-reload doesn't churn the registry.

;; In re-frame.adapter.reagent (CLJS, day8/re-frame-2-reagent):
(defonce ^:private __register
  (re-frame.substrate.adapter/register-default-adapter! :reagent adapter))

;; In re-frame.adapter.uix (CLJS, day8/re-frame-2-uix):
(defonce ^:private __register
  (re-frame.substrate.adapter/register-default-adapter! :uix adapter))

;; In re-frame.substrate.plain-atom (JVM only — see below):
#?(:clj
   (defonce ^:private __register
     (re-frame.substrate.adapter/register-default-adapter! :plain-atom adapter)))
```

`(rf/init!)` accepts three argument shapes:

- `(rf/init!)` — **no args**, resolve through the registry.
- `(rf/init! :reagent)` — **keyword pick** from the registry.
- `(rf/init! adapter-map)` — **literal adapter spec**, installed as-is.

The no-arg resolver enumerates the registered keys and dispatches:

| Registered count | Behaviour | Error category |
|---|---|---|
| 1 | Install that adapter | — |
| 0 | Raise | `:rf.error/no-adapter-registered` |
| >1 | Raise (consumer must disambiguate via the keyword form) | `:rf.error/multiple-default-adapters` |

The multi-adapter case is a **hard error**, not a last-wins or first-wins fallback. Rationale: post [rf2-3yij](#), Reagent and UIx can both be on the classpath in mixed-substrate apps; silently picking one would surface as a subtle, hard-to-diagnose bug. The thrown ex-info's `:keys` enumerate the registered candidates so the consumer can disambiguate via `(rf/init! :reagent)` / `(rf/init! :uix)` without spelunking through deps.

**JVM-only plain-atom registration.** The plain-atom adapter auto-registers as default **on the JVM only** (`#?(:clj …)`). On CLJS the registry is populated only by the substrate ns the consumer explicitly required (`re-frame.adapter.reagent` or `re-frame.adapter.uix`). Rationale: on CLJS, `re-frame.core` transitively requires `re-frame.substrate.plain-atom` (its render-to-string wiring still flows through), so if plain-atom auto-registered on CLJS too, every CLJS app would have plain-atom + the consumer's substrate registered and hit the multi-adapter error. The JVM-only registration keeps the policy meaningful: on the JVM, plain-atom is the only candidate; on CLJS, only the explicitly-required substrate is. CLJS apps that want plain-atom (rare — Node-side SSR not via the JVM SSR artefact) call `(rf/init! :plain-atom)` or `(rf/init! plain-atom/adapter)` explicitly.

`install-adapter!` is called once per process by `init!`'s implementation. Subsequent calls without an intervening `dispose-adapter!` raise `:rf.error/adapter-already-installed` ([§Single adapter per process](#single-adapter-per-process)). The default-adapter registry is **decoupled** from the installed-adapter slot: dispose only clears the slot, leaving registrations in place so a subsequent `(rf/init!)` resolves cleanly.

Other CLJS adapters (UIx, Helix) plug in via the same shape, replacing only the React-rendering and context-provider primitives; the sub-cache wiring and the plain-atom JVM half are unchanged.

## CLJS reference: UIx as alternative substrate (rf2-3yij)

The UIx adapter ships in `day8/re-frame-2-uix` and implements the same nine-fn contract as the Reagent adapter — same observable behaviour for events, subs, effects; different rendering substrate for views.

Per [rf2-3yij](#) the locked decisions (2026-05-09) are:

1. **Hook naming.** The substrate's subscription surface is `use-subscribe`, matching the React/UIx idiom. Symmetric ergonomics to Reagent's `(rf/subscribe ...)` deref shape; asymmetric naming because hooks live in hook-named space.
2. **Frame propagation.** Both the UIx and Reagent adapters read the *same* React Context object — factored out of `re-frame.views` into `re-frame.adapter.context` (CLJS-only file in core). A future mixed-substrate app's frame-provider chain therefore composes across substrates rather than living in per-adapter silos.
3. **Auto-injection.** None for UIx. Components call `(use-subscribe [:foo])` and `(rf/dispatcher)` directly — there is no UIx-side analogue to `reg-view`'s `dispatch` / `subscribe` lexical bindings. The hook surface is the canonical UIx access path.
4. **`reg-view` macro scope.** `reg-view` stays Reagent-only (auto-defs the Var, auto-injects the lexical `dispatch` / `subscribe`, threads source-coords through Reagent's `:contextType` machinery). UIx users register views via `reg-view*` (the plain-fn surface in `re-frame.core`); source-coord stamping for UIx-rendered roots happens at the substrate adapter's render-time wrapper, not at registration time.
5. **Source-coord DOM annotation.** The UIx adapter wraps user components in a thin layer that calls `React.cloneElement` to add `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element when `interop/debug-enabled?` is true. Production-elision contract per rf2-z7f7: under `:advanced` + `goog.DEBUG=false` the entire wrapper branch DCEs and the literal `data-rf2-source-coord` string fragment is absent from the bundle. Fragments and non-DOM roots are exempt with the standard one-shot warning per id.
6. **Render flush for tests.** The adapter exposes `flush-views!` wrapping React's `act()`. Tests dispatching against a UIx-mounted tree call `(flush-views!)` after a dispatch to settle pending React effects before reading the DOM.
7. **Smoke-test example set.** counter + login (under `examples/uix/counter_uix/` and `examples/uix/login_uix/`). Realworld is skipped per Decision 7 — heavy with Reagent-flavoured idioms; deferred until a UIx user wants it.
8. **UIx version target.** UIx 2.x (hooks-based). UIx 1.x back-compat is explicitly out of scope.

The CLJS-reference code follows the same per-contract-fn shape as the Reagent adapter; the differences are at the React layer:

- `make-state-container` returns a `clojure.core/atom` rather than a Reagent `r/atom` — UIx has no built-in reactive atom primitive. View-side reactivity flows through `useSyncExternalStore` in `use-subscribe` rather than through Reagent reactions.
- `make-derived-value` returns an `IDeref`+`IWatchable` wrapper that recomputes on deref and broadcasts changes via the source containers' watch machinery. Equality-on-= invariants ride on the core's sub-cache (Spec 006 §Invalidation algorithm), not on the substrate's caching.
- `render` wraps `react-dom/client.createRoot` + `root.render`; the unmount-fn calls `root.unmount()`.
- `register-context-provider` returns a UIx `defui` component reading the shared `frame-context` via `use-context`.

Every other adapter primitive (read, replace, subscribe-container, dispose) is structurally identical to the Reagent adapter's — the contract is genuinely substrate-agnostic.

## CLJS reference: Helix as alternative substrate (rf2-2qit)

The Helix adapter ships in `day8/re-frame-2-helix` and implements the same nine-fn contract as the Reagent and UIx adapters — same observable behaviour for events, subs, effects; different rendering substrate for views. Helix occupies the *minimal-React-wrapper* niche: it is structurally similar to UIx (React + hooks; no reactive-atom primitive) but ships a smaller surface and does not auto-instrument hooks.

Per [rf2-2qit](#) the locked decisions (2026-05-10) transfer one-for-one from [rf2-3yij](#) — the React + hooks substrate model is the same:

1. **Hook naming.** `use-subscribe` (matches the React/Helix idiom).
2. **Frame propagation.** Reads the same React Context object the Reagent and UIx adapters consume (`re-frame.adapter.context/frame-context` in core).
3. **Auto-injection.** None. Components call `(use-subscribe [:foo])` and `(rf/dispatcher)` directly.
4. **`reg-view` macro scope.** Stays Reagent-only; Helix users register registry-keyed views via `reg-view*` (the plain-fn surface) when they need it. Most Helix components are bare `defnc` and don't need registry addressing.
5. **Source-coord DOM annotation.** The Helix adapter wraps user components in a thin layer that calls `React.cloneElement` to add `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element when `interop/debug-enabled?` is true. Production-elision contract per rf2-z7f7: under `:advanced` + `goog.DEBUG=false` the entire wrapper branch DCEs. Same Fragment / non-DOM-root exemption as the UIx adapter.
6. **Render flush for tests.** `flush-views!` wrapping React's `act()` — same surface as the UIx adapter.
7. **Smoke-test example set.** counter + login (under `examples/helix/counter_helix/` and `examples/helix/login_helix/`). Realworld is skipped — same rationale as UIx (heavy with Reagent-flavoured idioms; deferred until a Helix user wants it).
8. **Helix version target.** Helix 0.2.x (the latest published Helix release line). Older Helix versions are explicitly out of scope.

Implementation notes:

- `make-state-container` returns a `clojure.core/atom` rather than a Reagent `r/atom` — Helix has no built-in reactive atom primitive (same as UIx). View-side reactivity flows through `useSyncExternalStore` in `use-subscribe`.
- `make-derived-value` returns an `IDeref`+`IWatchable` wrapper that recomputes on deref and broadcasts changes via the source containers' watch machinery — structurally identical to the UIx adapter.
- `render` wraps `react-dom/client.createRoot` + `root.render` (Helix doesn't ship a `helix.dom/render-root` wrapper of its own; the lower-level call is the cross-version-stable path).
- `register-context-provider` returns a Helix `defnc` component reading the shared `frame-context` via `helix.hooks/use-context`.
- `use-subscribe` calls `React.useSyncExternalStore` directly because `helix.hooks` doesn't ship a `use-syncExternalStore` wrapper (Helix is the minimal-wrapper substrate); deps are wired through `helix.hooks/use-memo*` / `use-callback*` (the function-form hooks) so the adapter doesn't pull in Helix's macro layer.

Every other adapter primitive (read, replace, subscribe-container, dispose) is structurally identical to the Reagent and UIx adapters' — the contract is genuinely substrate-agnostic, and the Helix port surfaces no friction against the rf2-3yij decision set.

## Subscription topology vs subscription tracking

A subtle distinction worth pulling out: **the static topology of the sub graph is core; the runtime tracking is adapter**.

The topology is "what depends on what" — the static `:<-` chain you can derive from registrations alone, without running any code. `(rf/sub-topology)` returns this graph as data, shaped `{sub-id {:inputs [<input-sub-ids>] :doc :ns :line :file}}` per [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api). `:inputs` is always present (empty for layer-1 / direct-app-db subs) and lists the upstream sub-ids in declaration order; `:doc` and the source-coord keys are present when the registration carries them. JVM-runnable. No adapter needed.

`sub-topology` is a *literal projection* of the registrar — it does not validate the resulting graph. Cycle detection, "this `:<-` references an unregistered sub", and similar diagnostics are debugger / tool-pair concerns that traverse the returned map; the topology query itself reports verbatim what was registered. (Cycles in `:<-` are not legal at runtime — the resolved sub will throw — but the topology query stays a static projection.)

The tracking is "when source X changes, recompute everyone who depends on X" — the runtime mechanism that makes views update reactively. This requires the adapter's `make-derived-value` and is substrate-specific.

In CLJS dev-mode tests, you often want sub computation without tracking: `(compute-sub [:total] db-value)` runs the sub's body against a static `app-db` value and returns the computed result. Pure function. No Reagent, no reactions. This is the "JVM-runnable" path that [008-Testing](008-Testing.md) and [011-SSR](011-SSR.md) use.

## SSR-specific behaviour

Per [011](011-SSR.md), the server-side render path doesn't use the adapter's reactivity machinery at all. The flow:

1. Server creates a frame (per [002 §reg-frame](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)).
2. The frame's `app-db` is a plain atom (the **core's plain-atom adapter**, not the Reagent adapter).
3. `:on-create` events run; the drain settles.
4. The view fn is called as a *plain function* against the now-stable `app-db` value.
5. The hiccup output is rendered to a string by `render-to-string`.

No Reagent. No React. No reactivity. Pure data → pure data → string.

The adapter that the core uses on the server is the **plain-atom adapter** (or "headless adapter"). The CLJS reference ships this alongside the Reagent adapter; the runtime picks based on platform.

## CLJS reference scope

The CLJS reference ships across multiple Maven artefacts (rf2-0hxm; per [Conventions §Substrate-adapter shipping convention](Conventions.md#substrate-adapter-shipping-convention)):

- **`day8/re-frame-2`** — the substrate-agnostic core (the registrar, the drain, the dispatch envelope, the trace stream, sub topology, sub computation, effect-map interpretation) plus the adapter API contract, the **plain-atom (headless) adapter** used by SSR and headless tests, and (per [rf2-3yij](#) Decision 2) the shared React frame Context object at `re-frame.adapter.context` that every React-shaped substrate adapter consumes.
- **`day8/re-frame-2-reagent`** — the **Reagent adapter** (browser default).
- **`day8/re-frame-2-uix`** — the **UIx adapter** (rf2-3yij). Targets UIx 2.x; ships the `use-subscribe` hook (Decision 1), the `flush-views!` test-flush helper (Decision 6), a source-coord wrapping component (Decision 5), and a `frame-provider` consuming the shared React context (Decision 2). Apps written for UIx call `reg-view*` (plain-fn) directly — the `reg-view` macro stays Reagent-flavoured per Decision 4.
- **`day8/re-frame-2-helix`** — the **Helix adapter** (rf2-2qit). Targets Helix 0.2.x; ships the same `use-subscribe` hook, `flush-views!` test-flush helper, source-coord wrapping component, and shared-context `frame-provider` as the UIx adapter. Apps written for Helix call `reg-view*` (plain-fn) directly — the `reg-view` macro stays Reagent-flavoured per Decision 4. The eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model.

In the CLJS reference repository the three per-substrate adapter sources live under `implementation/adapters/<name>/` — `implementation/adapters/reagent/`, `implementation/adapters/uix/`, `implementation/adapters/helix/`. Per-feature artefacts (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`) stay flat under `implementation/<name>/`. The directory split surfaces the substrates-vs-per-feature distinction in the layout — the substrates implement the [§reactive-substrate adapter contract](#the-reactive-substrate-adapter-contract); per-feature artefacts plug into core via the late-bind hook table per [Conventions §Independence rule](Conventions.md#independence-rule). Maven artefact names are unchanged across the move per [rf2-zha9](#).

Per-host adapters for non-CLJS implementations (TS+React, TS+Solid, Vue, Python+RxPy, etc.) ship as separate packages, implementing the same contract — the per-substrate-artefact pattern is host-language-agnostic.

## Open questions

### Cooperative rendering substrate

A cooperative rendering substrate — a rendering layer designed natively to cooperate with re-frame, instead of re-frame wrapping Reagent — is on the horizon. Substrate-agnostic decoupling (this Spec) is the prerequisite. Whether the cooperative variant ships depends on a benefits-vs-cost evaluation in a later cycle.

### Multi-adapter coexistence

The current contract is single-adapter-per-process. If a concrete use case for per-frame adapter selection emerges, multi-adapter support can be added additively without breaking the single-adapter contract.

## Resolved decisions

### Adapter selection

Per [rf2-84po](#) (resolves [rf2-4cb6](#)) the default adapter is selected through a registry populated by substrate-adapter ns-loads — see [§Adapter selection at boot](#adapter-selection-at-boot) above for the mechanism, the three-case resolver table, and the rationale for hard-erroring rather than silently picking when more than one adapter has registered.

Tests and atypical setups override via `(rf/init! :plain-atom)`, `(rf/init! :reagent)`, or `(rf/init! my-adapter-map)` at startup, before any frame is created. Re-installing after frames exist is an error (`:rf.error/adapter-already-installed` trace event; recovery: `:no-recovery`, the call is rejected).

SSR-on-Node-in-CLJS (rare but valid) installs `:plain-atom` explicitly via the keyword form; the plain-atom adapter is reachable by name on CLJS even though it does not auto-register as a default-resolution candidate there.

Other-language ports follow the same pattern: a default-adapter registry populated by substrate-package ns-load side-effects, with the `(init! key)` and `(init! adapter-map)` overrides available.

### Adapter introspection

`(rf/current-adapter)` returns a keyword identifying the active adapter:

- `:reagent` — CLJS browser default
- `:plain-atom` — CLJS JVM (SSR, headless tests) or Node-based CLJS
- `:custom` — user installed a custom adapter (UIx, Helix, hypothetical TS-side adapter)

Tools (10x, re-frame-pair) use this to branch on host capabilities — for instance, the time-travel UI is meaningful in browser-Reagent but not in plain-atom.

The keyword is informational. Behaviour-affecting decisions should be based on `:platforms` metadata (per [011 §S-3](011-SSR.md#effect-handling-on-the-server)) or on explicit configuration, not on which adapter is loaded.

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
