# Spec 006 — Reactive Substrate

> Status: Drafting. **v1-required (CLJS reference).**
>
> For where the adapter sits in relation to the rest of the runtime — the frame container, sub-cache, drain loop, and trace bus — see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

re-frame2 separates the dataflow core from the reactivity / rendering **substrate** — the abstract surface this spec defines. The substrate-agnostic core — registrar, frames, drain, dispatch envelope, subscription topology, sub computation, effect-map interpretation, trace stream — is JVM-runnable and has no dependency on Reagent, React, or DOM. A pluggable **adapter** (any conformant implementation of the substrate contract) supplies the reactive container for `app-db`, the change-tracking that drives view re-renders, and the render-tree → surface step.

> **Substrate scope: React + VDOM.** re-frame2 commits to **React + VDOM** as the rendering substrate. The adapter contract has *two* parts. The **reactive-container** half (entries 1-5 + 9 of [§The adapter API contract](#the-adapter-api-contract): `make-state-container`, `read-container`, `replace-container!`, `subscribe-container`, `make-derived-value`, `dispose-adapter!`) is *substrate-agnostic in shape* — its description does not mention React; it would generalise to any reactive primitive. The **render-side** half (entries 6-8 + the optional `flush-render!`: `render`, `render-to-string`, `register-context-provider`, `flush-render!`) is **React-shaped**: `render` mounts via `react-dom/client.createRoot`, `render-to-string` walks a hiccup-or-equivalent virtual-DOM tree to HTML (the contract for SSR ([Spec 011](011-SSR.md))), and `register-context-provider` returns a `React.createContext`-style provider. Ports are scoped to the eight JS-cross-compile-to-React-binding languages enumerated in [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic). Non-React substrates (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit) are out of scope; substrate-agnostic shape on the reactive-container side reflects "the contract generalises if we ever wanted it to," not "we ship adapters for them."

> **Terminology.** Throughout this spec, "**substrate**" names the abstract contract — the closed set of functions an adapter must implement. "**Adapter**" names each implementation of that contract. Adapters fill one of two roles: **view adapters** (Reagent, reagent-slim, UIx, and the experimental first-party `re-frame.ui`) drive a live render surface, while **headless adapters** (plain-atom and SSR) have no view layer. The one canonical inventory — every adapter's `:kind`, published namespace, Maven coordinate, repository home, and lifecycle role — is [§CLJS reference scope](#cljs-reference-scope); this spec's other adapter-set claims point there rather than restating it. The ruled disposition (unchanged): Reagent, reagent-slim, and UIx live on as first-class, actively-supported adapters, and `re-frame.ui` is a new experimental substrate offered alongside them. Helix — the one adapter ruled for removal — was removed at S7/W13 (rf2-d6epb, 2026-07-22).

This Spec defines:

- The **boundary** between core and adapter.
- The **adapter API contract** — the closed set of functions every adapter implements.
- Subscription cache invalidation semantics that adapters must respect.

The CLJS reference ships several adapters across sibling Maven artefacts; the full catalogue — each adapter's `:kind`, published namespace, coordinate, repository home, and lifecycle role — is the canonical inventory in [§CLJS reference scope](#cljs-reference-scope). The same core runs against every adapter; the observable behaviour of events, subs, and effects is identical across adapters given the same core inputs.

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
- **The frame contract.** Each frame holds a **frame-state** *value* (the two-partition container — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`), a queue, a sub-cache, and an id. The "value" interface is what the core requires; the adapter provides the reactive container that holds it and the two partition projections over it (per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections)).
- **The dispatch envelope and event queue.** Per [002 §Routing](002-Frames.md#routing-the-dispatch-envelope). Pure data, FIFO.
- **The drain mechanism.** Run-to-completion drain (per [002 §Run-to-completion](002-Frames.md#run-to-completion-dispatch-drain-semantics)). Pure logic over the queue.
- **Subscription topology.** The static dependency graph derived from `reg-sub` registrations — the literal `:<-` edges plus the per-sub input-kind discriminator (`:db` / `:static` / `:parametric`). Pure data, JVM-runnable. (Realized parametric edges per concrete query vector are runtime cache state, not static topology — see [§Subscription input producers](#subscription-input-producers--app-db-reader-static-parametric-input-fn).)
- **Subscription computation.** `(compute-sub query-v db)` — running a sub's body against an `app-db` value. Pure function. JVM-runnable.
- **Effect map interpretation.** Walking `:fx` and dispatching to registered fx handlers. Per [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map).
- **The trace event stream.** Per [009](009-Instrumentation.md). Pure data.

If you can plumb a runtime through these primitives, you have re-frame2's substrate-agnostic spine. None of it requires a reactivity library.

## What the adapter owns

The adapter is the substrate-specific part. Per [§Abstract](#abstract), the adapter splits into a **universal half** (the reactive-container contract — would work over any reactive primitive) and a **React-shaped half** (the render side — explicitly assumes React + VDOM).

**Universal half — the reactive container for `app-db` and its tracking.**

- **The reactive container for `app-db`.** In CLJS, this is a Reagent ratom. In CLJS-headless / SSR, a `clojure.core/atom`. In a TypeScript-React port, a tiny atom-shape over `useSyncExternalStore`'s snapshot store; same shape for the Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint ports atop their host's React binding.
- **Subscription *tracking* — the runtime side of reactivity.** The view's `subscribe` call returns a value, and when the underlying `app-db` slice changes, the view re-renders. The view-render side is React's job; how the container's mutation feeds React's render scheduler is the adapter's call: in Reagent, Reagent's reaction graph + the React renderer; in UIx / TS-React / Fable.React / Feliz / ReasonReact / Halogen-React / kotlin-react, `useSyncExternalStore` over the container's `subscribe-container` watch.

**React-shaped half — render and frame-routing.**

- **Render-tree consumption.** Walking the hiccup (or equivalent virtual-DOM tree) and producing DOM via React. In CLJS, Reagent does this through `react-dom/client`. In every in-scope JS-cross-compile port, the host's React binding (Fable.React's `createRoot`, kotlin-react's `createRoot`, ReasonReact's `createRoot`, etc.) calls into the same `react-dom` underneath. SSR is a hiccup-or-equivalent → HTML pure walk on the JVM (per [Spec 011](011-SSR.md)); equivalent on the server-side runtime in any JS-cross-compile port.
- **Component lifecycle.** Mount, update, unmount — React's lifecycle. Adapters wire into it via React's hooks or class-component machinery, host-binding-specific.
- **Frame-routing for views.** React context — per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part). The CLJS reference uses Reagent's `:contextType` (class-component path) and a function-component `_currentValue` read; other ports' React bindings expose `useContext` as the standard mechanism. The contract is "context value carrying the current frame-id; views read via the host React binding's hooks-equivalent." See [§Frame-provider via React context](#frame-provider-via-react-context) below for the per-port realisation.

Adapter behaviour is *observably equivalent* across the in-scope React-binding adapters given the same core: the same events produce the same state, the same subs return the same values. The adapter only changes *how* the view sees those values reactively and which React binding mounts the tree.

## Frame-state container and partition projections

A frame owns **two durable partitions** (per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract)): user **app-db** (`:db`) and framework **runtime-db** (`:rf.db/runtime`). The substrate holds them as **ONE physical frame-state container** with **two cached partition-projection reactions** layered over it:

```clojure
frame-state  (the physical reactive container — make-state-container holds
              {:rf.db/app <app-db> :rf.db/runtime <runtime-db>})
   ├── app-db     = (make-derived-value [frame-state] #(:rf.db/app %))      ; layer-1 input for app subs
   └── runtime-db = (make-derived-value [frame-state] #(:rf.db/runtime %))  ; layer-1 input for framework subs
```

This is **pattern contract**, not merely one acceptable representation. A conformant adapter MAY use a different internal arrangement **only if** it preserves the projection-equality semantics below; the reference adapter commits to the single container + two `make-derived-value` projections. **This section owns the projection-equality pattern-contract** (the substrate realisation and the per-partition propagation rules); [002 §One physical container, two projection reactions](002-Frames.md#one-physical-container-two-projection-reactions) states the two-partition split at the frame contract and defers the substrate mechanism here.

**Partition-aware invalidation falls out of `make-derived-value`'s memoised equality — no new machinery, no explicit dirty flags** (an adapter MAY add them if its host needs them). `make-derived-value` recomputes its `compute-fn` when its source changes but propagates only when the *result* changes (per [§`(make-derived-value …)`](#make-derived-value-source-containers-compute-fn--container) — the memoised-container contract):

- A **runtime-only commit** mutates `frame-state` (via `replace-container!` / `commit-frame-transition!`); the `app-db` projection recomputes `(:rf.db/app new)`, finds it `identical?`/`=` to the prior app-db, and **does not propagate** — app subs neither recompute nor re-render.
- An **app-only commit** is symmetric: the `runtime-db` projection does not propagate, so framework route/machine subs are untouched, and app authors never carry runtime paths in their sub code.
- A commit touching **both** partitions propagates to both projections.

**Commit boundary.** The drain's commit step (per [002 §Run-to-completion §commit](002-Frames.md#run-to-completion-dispatch-drain-semantics)) installs an app-db change (`:db` effect), a runtime-db change (`:rf.db/runtime` effect), or both as **one atomic `replace-container!` on the frame-state container** (`commit-frame-transition!`). There is never a window where one partition is committed and the other is not; an app/runtime cascade is one coherent frame-state transition. **Single-commit contract (rf2-uhk9ko):** dev-mode schema validation runs over the complete CANDIDATE transition BEFORE this install — a settling event performs at most ONE `replace-container!` on the frame-state container, and a schema-REJECTED candidate performs ZERO (no forward write, no restore write). The substrate therefore never observes an invalid candidate: no container watch fires, no derived value recomputes, and no subscriber (a Reagent reaction, a `useSyncExternalStore` snapshot, an epoch-scheduler drain) is notified for a rejected dispatch — the retired install-then-rollback write-pair, whose forward write leaked the invalid value to synchronous observers, no longer exists. The frame-state coeffect is injected by reference (no copy), so a pure app event pays nothing for the runtime partition it never touches.

Layer-1 app subs read the **app-db** projection; framework subs (`[:rf/machine <id>]`, `[:rf.route/*]`) read the **runtime-db** projection. Both are ordinary derived-value sources to the rest of the sub-cache machinery — the projection split is invisible to the invalidation algorithm below, which sees two layer-1 inputs instead of one. These runtime-db framework subs are read the same way as any other subscription — the ordinary `subscribe` naming the reserved `:rf/*` vector (`[:rf/machine <id>]` per [005 §Subscribing to machines](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub), `[:rf/route]` / `[:rf/pending-navigation]` per [012 §Reading the route is a sub](012-Routing.md#reading-the-route-is-a-sub), and — for the optional Resources artefact — `[:rf/resource <query>]` / `[:rf/mutation {:instance <instance>}]` per [016 §Subscriptions (passive)](016-Resources.md#subscriptions-passive)). There is no named-read-sugar fn layered over them: a runtime-db framework read is a subscription vector, one grammar (per [Conventions §Reserved sub-ids](Conventions.md#reserved-sub-ids)). The vectors stay canonical (a `:<-` chain still names the vector), and the same grammar covers ordinary app-db content — including flow output — read with the plain `subscribe`.

## The adapter API contract

Every adapter implements the surface below. The contract is **closed for v1** — the function set is fixed, signatures are fixed, dispose-after-use is fixed; new adapter capabilities ship post-v1 additively (a new fn with a feature predicate consumers can branch on).

> **The internal observation port is not part of this contract.** The Freehand view
> substrate reads subscriptions through an adapter-internal observation port (per
> [§The internal observation port](#the-internal-observation-port-adapter-internal))
> that lives **outside** this closed ten-fn map: no entry is added to the adapter spec
> map, no signature here changes, and existing adapters implement nothing new. The
> port's consumer is the `day8/re-frame2-freehand` view runtime — its atomic shell —
> via the core-internal `re-frame.substrate.observation` namespace on the lockstep
> release train. The closed-for-v1 statement above is unaffected by the port's
> existence.

> **The adapter contract is the canonical mechanism for bridging external reactive sources** (timers, JS event streams, external pub/sub, signals from other libraries). The v1 `reg-sub-raw` escape hatch — which v1 users sometimes leaned on for non-app-db reactivity — is not shipped in v2 (per [MIGRATION §M-18](../migration/from-re-frame-v1/README.md)). A custom adapter brings the external source into the substrate; subs consume normally via `reg-sub`. State that needs to live across [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility) must reach `app-db` through an event handler (Pattern-AsyncEffect plus a registered fx), not through an adapter-private side channel — see [§What an adapter MUST NOT do](#what-an-adapter-must-not-do).

The adapter surface is **six required functions, three optional functions, and one lifecycle function** — ten fns in total (the adapter **spec map** additionally carries the `:kind` discriminator, so it has eleven entries — API.md's "11-key adapter spec map"). The Normative contract section below specifies the call-shape for each; [§Operational semantics](#subscription-cache--contract-and-operational-semantics) covers cache-invalidation behaviour the adapter must respect; [§CLJS reference: Reagent as default adapter](#cljs-reference-reagent-as-default-adapter) covers reference-host implementation notes.

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

**Optional (3):** adapters may omit; the core falls back (or no-ops) when an optional fn is absent.

| Fn | Purpose | Fallback when absent |
|---|---|---|
| `subscribe-container` | Register a change-listener for invalidation. | Core runs invalidation inline within `replace-container!`. |
| `register-context-provider` | Return a context-provider component that scopes a frame to a subtree. | Core falls back to explicit-frame-as-argument; the user's view code threads the frame. |
| `flush-render!` | Synchronously commit the substrate's pending renders to the surface — NOT scheduled on a `requestAnimationFrame`-style tick. | Core no-ops (an adapter that renders without a live commit — plain-atom / SSR — has nothing to flush). |

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
TS-React: returns a tiny atom-shape (`{value, subscribers}`) wired into React via `useSyncExternalStore`.
Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint: same atom-shape over the host's React binding's `useSyncExternalStore` equivalent.

**Construction is failure-atomic, and the container is GC-owned (rf2-vxgfnd.198).** `make-state-container` must either **throw before it returns** or return a **disposal-free** value. "Throw before it returns" is a statement about *residue*, not about ordering: a constructor that acquires a host or registry resource — a watch, a listener, a slot in a process-global ownership table — and only then fails must **release that resource before the throw escapes** (rf2-vxgfnd.292). Nothing else can. The core never sees the unreturned container, so it holds no reference to drop and no verb to call; a resource stranded here is stranded for the process's lifetime, and a retried construction strands another. This is the same internal failure-atomicity [§`make-derived-value`](#make-derived-value-source-containers-compute-fn--container) requires of a projection that throws partway through wiring its sources, stated for the container constructor. There is no per-container teardown verb — the [ten-fn adapter surface](#the-adapter-api-contract) exposes no state-container `dispose`, and a returned container is reclaimed by GC together with the frame record that drops it (normal teardown disposes the two `make-derived-value` projections layered over the container, per [§`make-derived-value`](#make-derived-value-source-containers-compute-fn--container), but never the physical container itself). The core leans on this when frame construction fails partway: it acquires the state container and then each partition projection into locals, and if a *later* projection throws it disposes the successfully-returned projections in reverse acquisition order and drops the state container for GC — it has no verb to release it, and needs none. A conformant adapter therefore must not pin the returned container behind a strong reference the core cannot reach (a global ownership registry that outlives the container's reachability), because the core releases it only by dropping its own reference.

### `(read-container container) → value` and `(replace-container! container new-value) → nil`

The two basic operations on a container. `read-container` is pure; `replace-container!` is the only mutation primitive — partial updates aren't supported (the core always replaces the entire **frame-state** value after a drain — both partitions in one atomic write, per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections)). The container the *core's frame* holds is the frame-state container; the per-partition `app-db` / `runtime-db` projections over it are `make-derived-value` containers (read-only — never `replace-container!`d directly).

```clojure
(read-container container)                              ;; → current frame-state value {:rf.db/app … :rf.db/runtime …}
(replace-container! container new-value)                ;; → nil; container now holds new-value (one atomic frame-state install)
```

**Nil-container guard (defense-in-depth).** The core's `replace-container!` wrapper guards against the destroy-race case where a write (router `:db` commit, flows recompute, epoch restore, SSR write) arrives after the owning frame has been destroyed and `frame/app-db-container` has started returning nil. When `container` is nil, the wrapper SKIPS the underlying adapter's `replace-container!` call and emits an always-on `:rf.error/write-after-destroy` error (per [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives)) through the always-on error-emit axis, with `:recovery :ignored` — the write is dropped, no exception is thrown. The guard centralises destroy-race handling on the one mutation primitive that every frame app-db write flows through. Adapter implementations may assume `container` is non-nil; the guard is in the core's wrapper, not in the adapter contract.

### `(subscribe-container container on-change) → unsubscribe-fn`

Optional. Registers a callback that fires *after* `replace-container!` runs. The callback receives `(prev-value, new-value)`.

```clojure
(subscribe-container container on-change)               ;; → unsubscribe-fn
;; on-change signature: (fn [prev-value new-value] ...)
;; unsubscribe-fn signature: (fn [] nil) — idempotent
```

If the adapter supports it, the core uses `subscribe-container` to wire reactive sub-cache invalidation. The CLJS reference adapters (`re-frame.ui`, Reagent, reagent-slim, UIx, plain-atom, and SSR — the whole [canonical inventory](#cljs-reference-scope)) all supply it — the `add-watch`/`remove-watch` realisation is the lowest-common-denominator listener surface that every Clojure-host atom or atom-shape exposes for free. An adapter that genuinely cannot supply listeners (a host whose container primitive offers no observer hook) signals "unsupported" by either omitting the entry from its adapter spec map or returning `nil` from `subscribe-container`; in that case the core falls back to running invalidation inline within `replace-container!` itself (the adapter must, in that case, ensure `replace-container!` runs the core's invalidation hook before returning).

CLJS-Reagent: Reagent's reaction machinery handles this implicitly; `subscribe-container` returns a function that cancels the registration. The reference Reagent adapter additionally exposes the listener surface via `add-watch` on the underlying `r/atom` so the substrate contract is honoured uniformly across adapters — see [§CLJS reference: Reagent as default adapter](#cljs-reference-reagent-as-default-adapter).
CLJS-headless (plain-atom adapter, JVM and Node): supported via `add-watch` on the `clojure.core/atom` container; the returned unsubscribe-fn calls `remove-watch`. This lets headless tests and SSR builders register change-listeners without resorting to polling — see [§Plain-atom adapter (JVM, SSR, headless)](#plain-atom-adapter-jvm-ssr-headless).
TS-React / Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint: returns a function that detaches the listener from the atom-shape's subscriber list (the same store `useSyncExternalStore` consumes).

### `(make-derived-value source-containers compute-fn) → container`

Returns a derived container whose value is computed from one or more source containers. The derived container updates automatically when any source's value changes (transitively).

```clojure
(make-derived-value source-containers compute-fn)       ;; → container
;; source-containers: vector of containers
;; compute-fn signature: (fn [& source-values] ...) — pure; called with deref'd values
```

The returned container supports `read-container`; `replace-container!` is **not** supported on derived containers. A derived value is computed from its sources — there is no slot to write into — so writing to one is a programmer error. The core's `replace-container!` choke point (the single point every frame app-db write flows through, sibling to the nil-container guard in [§`read-container` and `replace-container!`](#read-container-container--value-and-replace-container-container-new-value--nil)) detects the derived container, emits a `:rf.error/derived-container-replaced` trace (so error-listeners observe it), and throws the canonical thrown-error `ex-info` carrying `:rf.error/id :rf.error/derived-container-replaced` (per [009 §The thrown-error shape](009-Instrumentation.md#the-thrown-error-shape--the-rferrorid-ex-data-contract)); the underlying adapter `replace-container!` is not invoked. `subscribe-container` works as on a base container.

Detecting a derived container is the **adapter's** responsibility, because no single host protocol separates the two shapes across every substrate: a Reagent `Reaction` reifies the host atom marker protocol (`clojure.lang.IAtom`) exactly as a base `r/atom` does, even though it is read-only — and a custom adapter's base container may not be atom-shaped at all (a JS class instance, a signal/store object, a host record). An adapter MAY therefore publish an optional `:adapter/derived-container?` late-bind hook; the choke point consults the **installed adapter's** hook first and treats it as authoritative whenever it has an opinion. The hook is **three-valued**: it returns truthy for a derived container (the choke point rejects the write), `false` for a container the adapter classifies as one of *its* writable base containers (the choke point delegates the write and does **not** apply the atom-marker heuristic), or the `container-class-unknown` sentinel to signal *no opinion* (the choke point falls back to the heuristic). The hook lives in the late-bind table rather than the adapter spec map so the ten-fn adapter contract shape (six required + three optional + one lifecycle, per §Normative contract) is unchanged. The reference Reagent and reagent-slim adapters publish one keyed on the substrate's own disposal protocol (a `Reaction` is disposable; a base `r/atom` / `RAtom` is not), answering truthy/`false` exhaustively over their own containers. A custom adapter whose base container is **not** atom-shaped publishes its own routed hook the same way, answering `false` for its base containers — without it, the choke point's heuristic would misclassify that legitimate non-atom base container as derived and reject the write before the adapter's own `replace-container!` could run.

Only when the **installed adapter** has no opinion — it publishes no hook, or its routed hook returns the `container-class-unknown` sentinel — does the choke point fall back to an atom-marker heuristic: a base container satisfies the host atom marker protocol (`clojure.lang.IAtom` on the JVM, `cljs.core/IAtom` on CLJS) while the adapter's derived value (an `IDeref`-only reify, or an `IDeref`+`IWatchable`+disposal reify) does not. Note the marker is `IAtom`, not `ISwap`/`IReset`: a ClojureScript `cljs.core/Atom` implements `IAtom` but not `ISwap`/`IReset` (`swap!` / `reset!` fast-path on the concrete `Atom` type), so only `IAtom` reliably marks a base atom on both hosts. That fall-back is sound only for adapters whose base container *is* atom-shaped — the plain-atom, test-react, and UIx reference adapters, whose derived values are not atom-shaped; the Reagent family and any custom non-atom-base adapter publish the hook so the heuristic is never reached for their containers.

The derived container's caching responsibility is **adapter discretion**: an adapter MAY memoise the derived value (and is encouraged to where the host primitive makes it cheap — Reagent's `Reaction` does this for free), or MAY recompute on every `read-container` and rely on the **per-frame sub-cache** ([§Subscription cache — contract and operational semantics](#subscription-cache--contract-and-operational-semantics)) to enforce the `=`-equality invariant across recomputes. Either shape is conformant. What is NOT conformant: a derived container whose recompute fires for an input that did not change by `=` and whose downstream propagation does not collapse on `=`-equal new values — that would break the cascade rule in [§Invalidation algorithm](#invalidation-algorithm).

CLJS-Reagent: a Reagent `reaction` — memoising; re-runs only when an input deref changes by `=`. CLJS-headless (plain-atom adapter): an `IDeref` wrapper that recomputes on every read; no memoisation at the substrate layer because SSR runs each sub at most a handful of times per request and the sub-cache (when present) handles `=`-equality cascading. TS-React / UIx / other JS-cross-compile ports: an `IDeref`+`IWatchable`-shaped wrapper that recomputes on read and broadcasts change via the source containers' watch machinery (see [§CLJS reference: UIx as alternative substrate](#cljs-reference-uix-as-alternative-substrate)).

**Watchable is necessary, not sufficient — a demand-driven derived value must be ACTIVATED (rf2-8cnxg).** The push clause at the top of this section — the derived container updates automatically when any source's value changes, and `subscribe-container` works on it as on a base container — is an obligation on *behaviour*, and reifying the host's watch interface does not on its own discharge it. On a substrate whose derived values are push-based from birth it does: the React-hook spine's `make-derived-value` wires one watch per source at construction, so the value it returns is live the moment it exists. The ratom family is **demand-driven** instead. A Reagent `Reaction` learns its sources only by being run through `deref-capture`, and a plain `read-container` taken outside a reactive context runs the compute-fn raw and leaves the reaction subscribed to nothing — a container that reifies the watch interface, accepts an `add-watch`, and then notifies nobody for as long as it lives. A Reagent *component* never meets this, because its render **is** the capture context; a compiled ViewCell ([§The Freehand atomic shell](#the-freehand-atomic-shell)) is not a component, so nothing supplies one on its behalf. An adapter on a demand-driven host is therefore conformant only if it publishes the optional `:adapter/activate-derived-value!` late-bind hook, whose one job is to put a returned derived value on the substrate's push path. Like `:adapter/derived-container?` above it rides the late-bind table rather than the adapter spec map, so the ten-fn contract shape is unchanged — and it is genuinely optional. In the CLJS reference the ratom family alone publishes it, Reagent over `reagent.ratom/run` and reagent-slim over its own `activate!`; the React-hook spine, the plain-atom adapter, test-react and the JVM derived value publish nothing at all, and the routed call bottoms out as a no-op. Absence is the correct answer for a host with no capture step to perform, not an omission to be diagnosed.

**Activation happens at acquire, never at construction.** The observation port ([§The port operations (final)](#the-port-operations-final)) calls the hook once per handle, immediately before it installs that handle's change watch and takes its baseline observation. That placement is normative, and it is what keeps activation per-observer: a subscription nothing observes is never activated, and the hook must be idempotent so the second and later handles over one cached node do not force a recompute. It also forecloses the tempting alternative reading. An adapter could satisfy the notification clause by making `make-derived-value` itself eager — Reagent's `:auto-run true` is the one-line version — and that is **not** conformant: an eager derived value recomputes every subscription over the frame-state container synchronously inside the drain's `replace-container!`, discarding the substrate's batching and turning one app-db write into a full-graph recompute, which is precisely the cascade [§Invalidation algorithm](#invalidation-algorithm) exists to collapse. The hook must also be total — safe to call on any container the port may hand it, including a base container or a derived value some *other* adapter produced in a mixed-substrate test bundle.

**Construction is failure-atomic, and the result is disposable (rf2-vxgfnd.198).** Two obligations let the core unwind a partially-built frame without adding an eleventh adapter function:

- **Internal failure-atomicity.** If `make-derived-value` throws *before* it returns, it must first release any watch or host resource it had already installed. The core's frame constructor never received the value and so cannot dispose it — an un-returned partial allocation must unwind itself. A constructor that wires **several** sources in sequence therefore needs a failure boundary around the whole wiring, not merely careful ordering within one step: on failure it releases the acquired wires in **reverse acquisition order**, **attempts every release** even if one of them throws, and re-raises the **primary** construction error rather than a secondary failure raised while unwinding (rf2-vxgfnd.292). Because an un-returned value is unreachable, this unwind is the only thing standing between a partial wiring and a permanent leak — so it is ordinary control flow on every build, never a development-only assertion.
- **Disposable result.** Every successfully returned derived value must be **safe to pass to `re-frame.interop/dispose!`** — the same seam that releases it at normal frame teardown — and that call must release whatever host resource the value installed: a source watch, a reaction subscription, an entry in an adapter-owned registry. A derived value that installs *no* host resource (a recompute-on-`read-container` value, which holds only its source references) needs no disposal, and `interop/dispose!` on it is a sound no-op; the obligation bites only where the value externally owns a resource GC alone would not reclaim.

Together these make **frame construction** failure-atomic. The core acquires the `app-db` and `runtime-db` projections into locals, and if the second throws it `interop/dispose!`s the first in reverse acquisition order before re-raising the original error, so a frame that never installs strands no watch (the physical frame-state container is left to GC per [§`make-state-container`](#make-state-container-initial-value--container) above). In the reference adapters the obligation is met three ways: the React-hook spine (`re-frame.ui` / UIx) reifies the re-frame-owned `re-frame.disposable/IDisposable` and its `-dispose` removes every source watch; the Reagent family returns a disposable `Reaction`; and the plain-atom, SSR, and headless test adapters recompute on `read-container` and own no source watch to strand (the plain-atom value additionally reifies `IDisposable` to carry the sub-cache's on-dispose callbacks — [§Reference counting and disposal](#reference-counting-and-disposal)).

#### Movement witness (optional)

A derived container that gates its own propagation on `rf=` already knows something the core cannot cheaply re-derive: **the value its most recent completed movement departed from**. Such a container **MAY** publish that value through the re-frame-owned protocol `re-frame.movement/IMovementWitness`, whose single method `(-moved-from container)` answers either that departure value or the distinguished `re-frame.movement/no-witness` sentinel. Publishing is **optional** — the same posture this spec already takes for `:adapter/derived-container?` and `:adapter/activate-derived-value!` above — and a container that publishes nothing is not deficient. It is simply one a consumer cannot short-circuit, and the consumer's fall-back is the comparison it would have performed anyway. Absence is the correct answer for a container whose propagation is not `rf=`-gated at all, such as a raw base container fanning out on every write.

Two obligations bind an implementor, and they are the whole normative content:

- **(W1) Freshness.** Answer `no-witness` unless the container's own value has completed a movement **and** no input change has been observed since. Without W1 a *pull-based* container's live value can run ahead of its last movement — its sources changed, it has not yet recomputed and notified, and a witness from the previous movement would then say nothing true about what a `read-container` returns now.
- **(W2) Soundness.** Whenever it answers some `f` other than `no-witness`, reading the container at that instant yields `v` with `(not (rf= f v))` — and therefore `(not (= f v))`, since `rf=` subsumes `=` ([§`rf=`](#rf--the-runtime-value-equality-relation)).

And one binds a consumer:

- **(C1) Verdict-preserving.** Use the witness **only** to skip a comparison whose answer it already determines, never to change an observable outcome. The set of verdicts a consumer reaches must be identical with and without the witness. This is a comparison *elimination*; a divergence in body-run counts or in the [Spec 009](009-Instrumentation.md#op-type-vocabulary) `:rf.sub/run` / `:rf.sub/skip` stream is a defect, never a new baseline.

Together W1 + W2 license exactly one inference, by pointer comparison and nothing else. Let `S` be a source, `L` the value a consumer last saw from it, and `v = (read-container S)`: if `(identical? (-moved-from S) L)` then `(not (= L v))`. That is what lets `re-frame.subs.memo`'s fixed-arity-1 wrappers skip the structural `=` walk of a changed `app-db` subtree on the write path, where it is guaranteed to fail — while keeping it on the read path, where a pull-based derived value depends on it to stop a render re-running every sub body.

The signal is **pulled by the core from the source, never pushed into the compute-fn**, which is why this capability changes neither `make-derived-value`'s `(source-containers compute-fn)` signature nor the one-argument-per-source shape of `compute-fn` pinned above. In the CLJS reference exactly one implementor exists: the React-hook spine's derived container, whose fan-out is `rf=`-gated. The Reagent and reagent-slim `Reaction`, the plain-atom derived value on both hosts, the headless test adapter's derived value, and every raw base container publish nothing — so on those substrates the consumer's guard is unchanged.

### `(render render-tree mount-point opts) → unmount-fn`

Renders the render-tree onto the substrate's surface and returns a function that unmounts.

```clojure
(render render-tree mount-point opts)                   ;; → unmount-fn
;; render-tree: a serialisable nested data structure (per Spec 004)
;; mount-point: implementation-specific (DOM element passed to react-dom/client.createRoot)
;; opts: open map; standard keys: :on-mismatch (per Spec 011), :hydrate? (boolean)
;; unmount-fn signature: (fn [] nil) — idempotent; releases all resources
```

CLJS-Reagent: wraps `reagent.dom.client/create-root` + `reagent.dom.client/render` (React 19 client-Root API; the same `createRoot` shape React 18 introduced); the unmount-fn closes over the Root and calls `(rdc/unmount root)`. Hydrate path uses `(rdc/hydrate-root mount-point render-tree)` which returns its own Root.
SSR-on-JVM: this function isn't called server-side — `render-to-string` is used instead. The adapter may stub `render` to throw on the JVM.

### `(render-to-string render-tree opts) → string`

Pure function. Renders the render-tree to an HTML string. JVM-runnable in the CLJS reference.

```clojure
(render-to-string render-tree opts)                     ;; → string
;; opts: open map; standard keys: :doctype? (boolean), :frame (frame-id for resolving registered views)
```

The implementation is the per-host pure walk of the render-tree (per Spec 011 §The render-tree → HTML emitter).

### `(flush-render! [f]) → nil`

Optional. **Synchronously** commits the substrate's pending renders to the surface. The 1-arity form runs `f` inside the substrate's synchronous-commit path so any state change `f` schedules — and any render already pending — is committed before the call returns; the 0-arity form flushes already-pending work with an empty callback.

```clojure
(flush-render!)                                         ;; → nil; flush already-pending work
(flush-render! f)                                       ;; → nil; run f, then flush synchronously
;; f signature: (fn [] ...) — its return is ignored
```

**Why this is a contract fn, not a test helper.** The reference substrates schedule re-renders through a `requestAnimationFrame`-style tick that fires *after* an evaluated `dispatch` returns and is throttled to ~never in a backgrounded / unfocused tab. A tool that drives `dispatch` and then wants to observe the *rendered* result therefore cannot rely on the scheduled commit ever arriving. `flush-render!` runs through the host's **synchronous-commit** API (it is NOT rAF-scheduled), so it fires even headless and even when the tab is backgrounded — letting headless tooling drive a `dispatch → flush-render! → observe-settled-DOM` loop deterministically. This is the framework capability the Tool-Pair headless view-lifecycle driving depends on (see [Tool-Pair §Driving the render](Tool-Pair.md), consumed by the pair MCP's *dispatch-and-settle* op).

This is **distinct** from a test-only flush. The compatibility React adapters (Reagent,
reagent-slim, UIx) ship their own `flush-views!` wrappers. The first-party
compiled substrate deliberately does not put a test helper on `re-frame.ui/adapter`:
`re-frame.ui.test/flush!` is the dev/test-scoped Promise boundary around direct React 19
`act`. `flush-render!` remains the production-grade adapter-contract surface, callable
from app or tooling code with no `act()` test-environment opt-in.

`flush-render!` must be **no-op-safe**: calling it when nothing is pending does no harm and returns `nil`. An adapter that renders without a live host commit (the plain-atom / SSR adapters render to a string, never to a live surface) ships no `flush-render!` at all; the core's delegation then no-ops.

CLJS-Reagent: `(f)` then `reagent.core/flush` — Reagent's render-queue drain forces every dirty component to re-render synchronously, bypassing its `requestAnimationFrame` `next-tick` scheduler, and (on React 19) commits via `react-dom/flushSync`.
CLJS-reagent-slim: `(f)` then `reagent2.impl.batching/flush!` — the rewrite's synchronous rea-queue + dirty-set drain (`forceUpdate` per dirty component), bypassing its microtask scheduler. Distinct from the `goog.DEBUG`-gated, `act()`-composing `reagent2.dom.client/flush-views!` test primitive.
CLJS-UIx: `react-dom/flushSync` (the React-hook spine) — runs `f` inside `flushSync` so any `useSyncExternalStore` update commits before returning.
CLJS-headless (plain-atom) / SSR: not implemented — there is no live commit to flush; `render-to-string` is the only render path.

### `(register-context-provider frame-keyword) → component`

Optional. For substrates with a context concept, returns a component that scopes a frame to a subtree.

```clojure
(register-context-provider frame-keyword)               ;; → component (substrate-specific shape)
```

CLJS-Reagent: returns the `frame-provider` Reagent component (a React Context Provider).
TS-React: returns an equivalent `React.createContext`-backed Provider component.
Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint: each returns the host React binding's `createContext`-backed Provider component (Feliz / Fable.React `createContext`, scalajs-react `createContext`, `React.Basic.Hooks.createContext`, kotlin-react `createContext`, ReasonReact `React.createContext`).
Headless / SSR (no React, no DOM): not supplied — the core falls back to explicit-frame-as-argument; the user's view code threads the frame.

### Adapter disposal lifecycle

Every adapter exposes:

```clojure
(dispose-adapter!)                                      ;; → nil
```

Called by the core when the runtime shuts down (process exit, test-frame teardown, or explicit `(rf/shutdown-runtime!)`). The adapter must:

1. Attempt cancellation of all in-flight reactive subscriptions.
2. Attempt release of every host-specific resource (DOM event listeners, websocket subscribers, timers), even when a sibling cleanup fails.
3. Discard internal caches and ownership claims in a finally-shaped boundary.
4. Make subsequent calls to other adapter functions return `:rf.error/adapter-disposed` (or throw, host-dependent).

Adapter destruction is a **one-way terminal lifecycle boundary**, not a
transaction. The core claims one opaque installed-generation token before invoking
`:dispose-adapter!`, preventing re-entrant destruction from running that generation's
cleanup twice. That claim atomically makes `adapter-disposed?` true and closes runtime
delegation; no new work can enter the partly torn-down generation. In a `finally`
boundary the core clears only the claimed generation. Cleanup failure therefore cannot
leave a half-live adapter seated, and stale finalization can never clear a replacement
generation. On failure the adapter attempts all remaining cleanup, preserves and
rethrows the first failure, and attaches or reports later failures as secondary
diagnostic evidence. A fresh adapter may install after destruction returns or throws;
that install clears the disposed breadcrumb.

For the first-party `re-frame.ui/adapter`, host resources include **every public
compiled Root** in `re-frame.ui`'s client registry, not only Roots created by the
generic React spine. Disposal fences new public Root creation across the complete
two-phase lifecycle (public-root snapshot drain **and** generic-spine cleanup), snapshots
one exact generation, and attempts every Root in that snapshot even if a sibling throws.
It never refreshes the snapshot or chases a same-id replacement it did not acquire. Each exact
incarnation releases its registry claim, ViewCells, and observation handles at the **settlement
boundary** — not merely when the host `.unmount` returns (see [§Public Root teardown lifecycle and
settlement](#public-root-teardown-lifecycle-and-settlement)); a throwing
host unmount remains observable but cannot strand siblings. If React consumed a
throwing Root handle before clearing its container, the adapter clears the remaining
DOM and the pinned React container-ownership marker and releases the **root-id and
identifier-prefix** so a subsequent `rf/init!` can re-mount the same root-id into a
**fresh** container. The **exact consumed container node** is NOT proven free — a
throwing `.unmount` may have queued late host DOM work that has not settled — so it is
recorded fail-closed (see [§Settlement independence](#settlement-independence)) and its
reuse fails loud. The first cleanup error stays primary;
later cleanup failures remain attached as diagnostic evidence.

The adapter is single-use after disposal; restart requires `(install-adapter!)` again.

In CLJS-Reagent: clears Reagent's reaction caches, unmounts any active root.
In CLJS-headless: no-op (no resources held).

### Public Root teardown lifecycle and settlement

Adapter-wide disposal (above) drains every public compiled Root; a single `unmount!` follows
the same law for one Root. Both are governed by a three-state per-Root lifecycle. The first-party
`re-frame.ui` client tracks every public compiled Root in a per-document registry keyed by
root-id. A Root's registry claim — which occupies its **root-id**, its **container** DOM node, and
its effective **identifier-prefix** as one unit, and carries a per-mount opaque **incarnation**
token — moves through three states:

- **`:live`** — registered before the first render, with root-id, container, and prefix all
  claimed. The steady state of a mounted Root.
- **`:tearing-down`** — `unmount!` marks the claim tearing-down BEFORE it drives the host React
  `.unmount`, identity-guarded to this exact Root. The claim keeps occupying id + container +
  prefix. This is a **host-ownership claim state**, not a promise that framework owners are still
  live: a deferred teardown retains them until settlement, while a throwing cleanup force-deads
  the exact incarnation immediately and leaves only the unproven host claim quarantined.
- **`:released`** — the claim is dropped (identity-guarded, so a stale handle never evicts a newer
  claim), freeing id/container/prefix for reuse.

The `:tearing-down → :released` transition happens at the **settlement boundary**, which is NOT the
moment the host `.unmount` returns.

#### Synchronous vs deferred settlement

`unmount!` drives the host `.unmount` through the root-teardown window with an `on-settled`
callback that releases the claim. There are two outcomes:

- **Synchronous teardown.** React ran this root's effect cleanups during `.unmount`. `on-settled`
  fires inline: `:tearing-down` is cleared before `unmount!` returns, so the state is never
  observable to callers, and the ratified same-container immediate re-mount is preserved.
- **Deferred teardown.** react-dom 19.2 refuses a synchronous `.unmount` from inside render/commit
  — it consumes the handle, returns normally, and schedules the teardown for a later microtask. The
  driver holds `on-settled` and schedules a **settlement microtask FIFO-ordered after React's own
  deferred teardown**; that microtask reaps the root's still-owned ViewCells to `:dead` and only
  then fires `on-settled`. The claim stays `:tearing-down` across the whole deferred window and
  reaches `:released` at the microtask.

Which outcome applies is a **root-level settlement law**, independent of ViewCell population. A
teardown is deferred iff a committed root reporter for this incarnation has not yet torn down AND
its mount-lifetime cleanup sentinel did not fire during the `.unmount`. Every rendered root —
cell-bearing, compiled-static/cell-less, or entirely Activity-hidden — commits that reporter, so
its deferral is observed regardless of whether the root owns any connected ViewCell. An explicit
`unmount!` of an unrendered / pre-commit root committed no reporter, so nothing is pending and it
settles synchronously. A failed-first-mount rollback is deliberately stricter, below.

#### Failed-first-mount rollback is one teardown transaction

If a fresh `mount` has allocated and registered its React Root but its first element/host render
throws synchronously, rollback uses the same exact-incarnation teardown machinery as `unmount!`:

1. mark the complete id/container/identifier-prefix claim `:tearing-down` **before** touching the
   host Root;
2. drive `.unmount` through the root-teardown window, so every framework owner attached to that
   exact incarnation is force-dead if cleanup throws;
3. preserve the mount error as the thrown primary value. If host cleanup also throws, attach that
   exact secondary value as `rfUiRollbackCleanupError`; and
4. on a normal cleanup return, release the identity-guarded predecessor claim at the **next FIFO
   microtask**, not inline. No commit reporter exists for a failed first render, so the current
   mount/error stack is the conservative settlement fence. Same-id and different-id/same-container
   retries in that stack fail before successor allocation; retry after the microtask is admitted.

If cleanup throws, no release is scheduled: the framework incarnation is dead but the host surface
is unproven, so the claim remains quarantined `:tearing-down`. The exact-root identity guard means a
late predecessor release can never evict a successfully mounted successor incarnation.

#### Settlement independence

Settlement fires in the two cases a cell-connectivity probe would miss:

- **Zero connected ViewCells.** The reporter wraps every render, so the host-teardown signal is
  present even for a root that owns no connected cell (a compiled-static root, or one whose whole
  tree is Activity-hidden). Settlement fires and the claim releases on that root-level signal, not
  on any cell — closing the gap where a cell-connectivity probe mis-read a still-scheduled deferred
  teardown as synchronous and released the claim early.
- **A throwing host `.unmount`.** The exact generation is force-dead (every cell of that
  incarnation reaped, its handles released) and the host error rethrows, but `on-settled` is NOT
  fired. The container cannot be proven free in-process, so the claim **fails closed** — quarantined
  `:tearing-down` (`:cleanup-failure?`), never released into a possibly-still-scheduled React
  teardown. A second `unmount!` is a no-op (the tearing-down guard); recovery is a fresh container.
  The adapter-wide drain may afterwards **reclaim** the consumed surface — clearing its DOM and
  deleting the pinned React container-ownership marker, retiring the spent reporter authority, and
  releasing the **root-id + identifier-prefix** so the same root-id re-mounts on a **fresh**
  container after re-init. That reclaim is NOT proof the surface settled: a throwing `.unmount`
  may have **queued** late host DOM work (a scheduled `replaceChildren`) before it threw, and that
  authority is unobservable in-process, so clearing the current DOM + marker is a **snapshot**, not
  a settlement boundary. The **exact container node** is therefore recorded fail-closed (a
  WeakSet denylist of poisoned nodes — not a task tracker), and its reuse is rejected with
  `:rf.error/root-container-consumed` (recovery: a fresh node). An isolated public `unmount!`
  leaves that reclaim to its caller; until it runs, the still-`:tearing-down` quarantine itself
  fences the exact id/container/prefix.

#### Tearing-down reuse diagnostics

Because a `:tearing-down` claim keeps occupying its id/container/prefix, any attempt to reuse that
exact identity during the deferred window is rejected fail-closed — never admitted onto a container
React is still scheduled to clear. A **merely deferred** teardown reuses the three catalogued root
diagnostics ([Spec 009](009-Instrumentation.md)), which each gain a tearing-down cause and emit
`:tearing-down? true` in their `:existing` evidence; a **throwing/consumed** teardown adds one new
id, `:rf.error/root-container-consumed` (rf2-sddbc), because its recovery (a fresh node — never a
wait-for-settlement) is genuinely distinct.

- **`:rf.error/duplicate-root-id`** — a reentrant `mount` / `create-root` (and `hydrate-root`, once
  S5 wires it through the same pre-render admission check) claiming the same root-id. For a
  **deferred** owner the message names the in-flight deferred unmount and directs the caller to
  re-mount after settlement or use a distinct `:root-id` with a fresh container (recovery
  `:make-root-ids-unique`); client `:existing` carries the owner's `:provenance`, `:site`, and
  `:tearing-down? true`. For a **`:cleanup-failure?`** owner (a throwing `.unmount`) it is HONEST
  that there is NO settlement to wait for — the id frees only when the adapter is destroyed and
  reinstalled, and the exact container is fail-closed regardless — so the recovery is structurally
  distinct (`:reinit-adapter-or-use-a-fresh-identity`, never a wait) and `:existing` additionally
  carries `:cleanup-failure? true`, so a structured consumer recovers the terminal-vs-deferred
  distinction the message draws. A same-id retry onto the **exact poisoned node** is reported as
  `:rf.error/root-container-consumed` (checked ahead of this arm), so the duplicate-id arm fires on a
  same-id retry onto a **different fresh node**. All arms carry the ordinary `:arriving` evidence.
- **`:rf.error/root-container-in-use`** — a mount whose container is still owned by a **merely
  deferred** tearing-down root. The node frees once teardown settles; recovery is a fresh node or a
  re-mount after settlement. Data carries `:owner-root-id` and `:existing {:tearing-down? true}`.
- **`:rf.error/root-container-consumed`** — a mount onto a container a **throwing/consumed** host
  `.unmount` poisoned (rf2-sddbc). Checked ahead of the **duplicate-root-id and in-use arms**, so a
  same-id retry onto the exact poisoned node reports the consumed node here rather than hiding it
  behind duplicate-ID ordering (rf2-h05lm). The node can NEVER be proven free (queued host work is
  unobservable), so recovery is a fresh node — never a wait-for-settlement. Two arms: an isolated
  `unmount!` quarantine still holding the `:cleanup-failure?` claim (`:owner-root-id` names it), and
  the post-reclaim `consumed-containers` denylist (id/prefix already released for a same-id re-mount
  on a fresh node). Data carries `:root-id` and optional `:owner-root-id`.
- **`:rf.error/root-not-live`** — a `render!` into a tearing-down Root. A tearing-down root is not
  live: its React tree is being unmounted, so rendering into it would drive a consumed/unmounting
  handle. It fails the same loud way as an unmounted or superseded root; recover by recreating the
  root after settlement (a throwing-cleanup quarantine recreates onto a fresh container). Data
  carries `:existing {:tearing-down? true}`.

The effective-prefix arm (`:rf.error/duplicate-identifier-prefix`) is fenced the same way, since
the prefix is quarantined as part of the one claim.

#### Adapter-destruction completion

Adapter-wide `dispose-adapter!` drains an exact one-generation snapshot of the live-root registry,
unmounting each Root under the closed root-admission fence. A deferred teardown leaves its claim
`:tearing-down` when the drain returns — settlement is still scheduled. **`dispose-adapter!` keeps
its synchronous `nil` completion; no asynchronous Promise boundary is introduced.** Completion is
honest without awaiting the microtask because the still-`:tearing-down` claims survive the drain (a
fresh `install-adapter!` / `rf/init!` does not wipe the registry), so the pre-render admission check
fail-closes reuse of the exact id/container until settlement, and the post-destroy admission
breadcrumb meanwhile rejects any fresh public-root creation with `:rf.error/adapter-disposed`. A
**deferred** exact id/container becomes reusable once the deferred settlement releases the claim. On
the **throwing** path the adapter's container reclaim releases the id/prefix (a same-id re-mount on a
fresh container) but does NOT prove the exact node free — the node is recorded fail-closed
(`:rf.error/root-container-consumed`), so on that path the exact **container** is never reusable;
recovery is a fresh node.

#### Successor-generation settlement fence

`dispose-adapter!` returning synchronously while a predecessor teardown is still deferred, composed
with an immediate `install-adapter!` / `rf/init!`, must not let the **successor** generation become
usable before the predecessor's host cleanup authority has settled. The immediate `rf/init!` clears
the disposed breadcrumb, so the breadcrumb alone no longer fences root creation; and a predecessor's
deferred host `.unmount` runs its layout/effect cleanups **after** `rf/init!` returns — a cleanup
that dispatches through a frame api captured before disposal would otherwise resolve the bare id at
call time and mutate a **same-id successor frame** the new generation reseated. Two composed fences
close this window; either is individually sufficient for its axis, and together they are exact:

1. **Successor root-admission fence.** While any live-root claim registered under a **prior** adapter
   generation is still `:tearing-down` **and its teardown is genuinely settlement-pending** (a
   deferred host teardown React has not settled — NOT a `:cleanup-failure?` quarantine), the pre-render
   admission check rejects a fresh public compiled Root — under **any** id, not merely the exact
   quarantined one — with the typed lifecycle error `:rf.error/adapter-teardown-in-flight` (Spec 009).
   This is **distinct** from the pre-`init!` `:rf.error/adapter-disposed` breadcrumb: a fresh adapter
   *is* installed, but the successor generation cannot admit a Root — and so cannot ENSURE a fresh
   same-id frame — until the predecessor settles. Because the claim records the exact generation it
   was admitted under, an ordinary **same-generation** deferred `unmount!` mid-settlement never blocks
   a sibling mount; only an unsettled **predecessor** generation does. A `:cleanup-failure?` quarantine
   is EXCLUDED (rf2-sddbc): a throwing `.unmount` never settles, so counting it would globally fence
   every successor Root **forever**; its fail-closed reach is instead the exact poisoned container
   alone (`:rf.error/root-container-consumed`), leaving unrelated fresh roots free to admit. Once the
   deferred settlement releases the predecessor claim, admission reopens and the exact id/container is
   reusable; on the throwing path the reclaim releases the id/prefix (a same-id re-mount on a **fresh**
   container) while the exact container stays fail-closed.

2. **Incarnation-keyed frame-capture fence.** A frame api (`capture-frame`, and the `reg-view`
   render-time injection) captured against a **live** frame pins that frame's exact incarnation (its
   `:drain-lock`, per [002 §capture-frame](002-Frames.md)). If the captured incarnation is later
   destroyed — the id unclaimed, **or** a same-id successor incarnation reseated under it — **every**
   op the stale api exposes behaves uniformly: `:dispatch` and `:dispatch-sync` RECOVER (the event is
   never enqueued into the successor), and `:subscribe` RECOVERS by reading nothing and returning
   **nil** (it never resolves a reaction against the successor's app-db nor caches an entry in the
   successor's sub-cache). Each emits the production-survivable `:rf.error/frame-destroyed`
   **exactly once**, exactly as an op into a destroyed frame does. The captured incarnation is carried
   through into target selection / enqueue / read, so validation and target consumption are **one
   exact-incarnation operation** — the pinned token is compared against the same record the router / sub
   resolves for the enqueue or read. There is therefore **no liveness-check-to-bare-id-use window**: on
   the concurrent JVM host, an actor that destroys the captured incarnation and reseats a same-id
   successor between the capture's liveness pre-check and its ordinary address-directed consumption can
   never redirect the stale op into that successor (rf2-dlld6). This is the async-safe, recover-but-emit
   sibling of the synchronous **throwing** incarnation fence the `(frame)` accessor bundle already
   applies; it is the last-line guard for a predecessor cleanup — including a throwing-host quarantine
   React later self-completes — that fires after both the successor adapter and a same-id successor
   frame are live. A capture whose id was **not** live at capture (the `capture-frame` 1-arity
   lock-to-id form used from outside any scope) pins nothing and stays address-directed.

This is consistent with **"Disposal is total"** and **"no state survives"** (the adapter-revertibility
contract above): the surviving `:tearing-down` claim is a **host-ownership quarantine** tracking a
teardown React itself still owns, not adapter-internal observer/value state carried across
`dispose-adapter!` / `install-adapter!`. The successor generation installs fresh and holds no
predecessor state; it is merely **fenced from admitting Roots** until the host teardown it did not
perform has settled. Cleanup failure remains **fail-closed** — a throwing host `.unmount` never fires
`on-settled`, so its claim stays quarantined `:tearing-down` (`:cleanup-failure?`); an exact-incarnation
adapter container reclaim retires its reporter and releases the id/prefix (a same-id re-mount on a
fresh container), but does NOT prove the exact node free — a throwing `.unmount` may have queued late
host DOM work, unobservable in-process. So the exact container node stays fail-closed
(`:rf.error/root-container-consumed`, rf2-sddbc); the reclaim is a snapshot, not an affirmative
settlement boundary, and re-`init!` alone never silently unblocks that node. Unlike a deferred
teardown, a cleanup-failure quarantine does NOT globally fence the successor generation — only its
exact container — so unrelated fresh roots admit normally.

### JavaScript host capability boundary

The CLJS weak root-ownership registry requires the host's standard **`WeakRef`** constructor.
Without it, the implementation has only two dishonest choices: strongly retain every ordinarily
unmounted ViewCell for the Root's lifetime, or drop Activity-hidden cells that root/frame teardown
must still discover. Root admission therefore probes and captures `WeakRef` exactly once, before
frame preflight, React Root allocation, live-root registration, or ViewCell ownership mutation.
The direct `attach-root!` seam applies the same gate before writing the cell or registry. An
unsupported host throws `:rf.error/ui-platform-incompatible` with
`:platform :javascript`, `:capability :js/WeakRef`, and recovery
`:use-a-weakref-capable-javascript-runtime`; there is no strong-reference fallback, polling loop,
or per-render capability check.

`FinalizationRegistry` is **optional**. When present, one captured module-lifetime reaper removes
collected WeakRef husks eagerly. When absent, every synchronous ownership scan compacts cleared
refs and drops empty incarnation entries; deterministic `teardown!` removal remains the normal
fast path on both arms. The JVM host uses its synchronized `WeakHashMap` membership and does not
participate in this JavaScript capability gate.

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
- **CLJS plain-atom (headless).** The container is a `clojure.core/atom`. The adapter exposes `subscribe-container` via `add-watch` / `remove-watch` and `make-derived-value` as an `IDeref` wrapper that recomputes on every read (no memoisation at the substrate layer — see [§make-derived-value](#make-derived-value-source-containers-compute-fn--container)). The watch-key registry is transient — drop it, re-register, and observable behaviour is unchanged (modulo a tick); the derived-value wrapper holds no state beyond its source-container references. No reactivity graph and no value cache live outside the frame container. ✓
- **TS-React / Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint adapters.** Same constraint applies: each port's atom-shape subscriber registry, the `useSyncExternalStore` snapshot store React caches, and any derived-value memoisation must all be derivable from the frame's value. Ports verify the host React binding doesn't squirrel away non-derivable state outside the frame container.

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

## Source-coord annotation (mandatory)

Every adapter MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. The annotation is a **normative entry on the adapter contract** — devtools and pair-shaped tools (re-frame-pair, re-frame-10x, IDE jump-to-source per [Tool-Pair §Source-mapping UI clicks back to code](Tool-Pair.md#source-mapping-ui-clicks-back-to-code)) consume it to map a clicked DOM node back to the reg-view call site. Without this annotation an adapter is non-conformant.

### Capture mechanism

Source coordinates are captured at `reg-view` macro-expansion time from `(meta &form)` (`:line`, `:column`) and the compile-time `*ns*` / `*file*` (per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference)). The macro stamps them onto the registry slot's metadata; the adapter reads them at render time when wiring the wrapper that produces the annotated DOM element. No runtime cost in the hot path: the coord string is computed once at registration time, then merged into attrs each render.

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

### Historical: JSX source-coord props (removed — never worked)

> **Status: removed (Option A).** An earlier version of this contract called for the wrapper to ALSO inject the JSX-shaped source-coord props (`_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber`) per `@babel/plugin-transform-react-jsx-source`, with the intent of making React DevTools' "View source" gesture jump to the `reg-view` definition.
>
> The feature never delivered. Two problems compounded:
>
> 1. Reagent passes these props through as DOM attributes (it does not route them to React.createElement's `__source` slot), so React's runtime emitted "does not recognize the `_jsx*` prop on a DOM element" console warnings for every annotated view's root.
> 2. React DevTools does not read "View source" from element props anyway — it reads `__source` off `React.createElement`'s third argument, which is set by the Babel plugin at JSX-compile time and is not reachable from hiccup. So the DevTools gesture never lit up for re-frame2-registered views.
>
> Net effect: dev-console noise with no DevTools benefit. The injection was dropped cleanly. The `data-rf2-source-coord` and `data-rf-view` DOM attributes (which DO work and are consumed by re-frame-pair, the view-walker, and IDE jump-to-source tooling) ride the same wrapper unchanged.
>
> If a future pass restores React DevTools "View source" integration, the correct path is to thread `__source` into the React element at element-creation time (cloneElement's third arg, or a substrate hook that participates in element construction) — not via element props.

### Documented exemption: non-DOM roots

A registered view whose root element is one of:

- a React Fragment (`:<>`),
- a host-component head (`:>` in Reagent — the React-interop marker),
- a function/component head (e.g. another reg-view'd component),

…is **exempt** from the annotation. The adapter MUST emit a one-shot warning per id (so the developer learns the pair-tool footgun without spamming the console on re-render) and MUST NOT inject the attribute in these cases. Pair tools fall back to `(rf/handler-meta :view id)` for these nodes — the registry slot still carries the captured `:ns` / `:line` / `:file`; only the DOM-node-level mapping is skipped.

The exemption is principled: a Fragment has no DOM element to annotate, and a `[:> Cmp …]` interop call hands the props map straight through to React's component (which may not be a DOM-tag, may not accept arbitrary HTML attributes, and certainly should not have framework-derived strings inserted into it). Annotating these would either be a no-op (Fragment) or risk mutating semantics (interop).

### Form-2 handling

When a registered view's render-fn returns a fn (Reagent's Form-2 closure shape per [Spec 004D §Removed forms — normative absences](004D-Freehand-Compiled-Grammar.md#removed-forms--normative-absences)), the adapter wraps the returned fn so the inner-fn's hiccup output is annotated on the next call. Annotation lands on the eventual rendered DOM root, not on the outer fn (which is not a DOM element).

### Cross-host

Headless test adapters (no DOM) are exempt. Every in-scope React-binding adapter MUST honour this contract: the CLJS reference view adapters (`re-frame.ui`, Reagent, reagent-slim, UIx) and every JS-cross-compile-language port (TypeScript-React, Feliz / Fable.React, scalajs-react / Slinky, React.Basic, kotlin-react, ReasonReact / Melange-React). The server-side equivalent is the [JVM `reg-view*` registration boundary](011-SSR.md#source-coord-annotation-under-ssr) — a debug-gated wrapper on the stored `:handler-fn` (`re-frame.views.jvm-source-coord-annotation`) that stamps BOTH `data-rf2-source-coord` and `data-rf-view` on the registered view's root, so server-rendered pages carry both. The JVM SSR emitter itself carries no annotation logic; it stringifies the hiccup the registration boundary already annotated.

### Source-coord stamping for state machines

The view-side annotation above is one half of the tool-pair source-mapping contract. The other half is **the spec-side stamping for state machines**: per [Spec 005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping), the `reg-machine` macro walks its literal spec form at expansion time and CO-LOCATES per-element source onto each guard / action entry (`:guards {:form-valid? {:fn .. :source-coords .. :source-code ..}}`), plus a reference-site `:source-coords` onto each `:states`-tree map node (`{:states {:idle {:on {:submit {… :source-coords {…}}} :source-coords {…}}}}`). Pair tools that surface a "click on a transition's call site" gesture read the co-located entry's `:source-coords` for a named guard/action, or the `:source-coords` off the state-node / transition map for a transition — symmetric to how they consume `data-rf2-source-coord` for views.

Both surfaces share the production-elision contract: the co-location dev arm is gated on `interop/debug-enabled?`, so under `:advanced` + `goog.DEBUG=false` the closure compiler folds it away — the co-located `:source-code` / `:source-coords` slots (on element entries AND on `:states`-tree map nodes) DCE. The `scripts/check-elision.cjs` sentinel set greps the co-located `:source-code` fn-body fragments (which ride the same dev arm as the state-node co-location), verified ABSENT in the production bundle and PRESENT in the control bundle.

## View tagging contract (fallback)

> **Status: fallback safety-net only.** The primary path for runtime view-hierarchy capture is the **Fiber-walker** documented in [View-Hierarchy-Capture.md](View-Hierarchy-Capture.md). This section pins the per-adapter fallback path that activates only if Fiber-reading breaks on a future React-version regression, or if a non-React substrate is ever wired in. Both paths can coexist; the fallback adds a single attribute per registered view and costs ~zero in production (elision-gated).

The same per-render wrapper that injects `data-rf2-source-coord` (§Source-coord annotation above) also injects `data-rf-view="<id>"` on the rendered root DOM element when `interop/debug-enabled?` is true. The two attributes ride the same wrapper, the same walk, and the same production-elision gate — there is no separate code path or separate elision contract.

### Attribute value format

```
data-rf-view="<id>"
```

`<id>` is the registry id keyword stringified verbatim — `(str id)`. For a namespaced keyword id `:rf.foo/bar` the attribute value is `":rf.foo/bar"` (leading colon preserved). The walker reads it back via `(keyword (subs s 1))` when the leading `:` is present, falling back to the raw string for non-keyword ids.

The committed public contract is `:rf/view-id-attr` (see [Spec-Schemas](Spec-Schemas.md)); the on-attribute representation matches the registry handler-id, not the call-site symbol — symmetric to how `data-rf2-source-coord` carries the registry id portion.

### Injection rules

The wrapper inspects the user render-fn's output and mutates the **first concrete element's existing attribute map** (the SAME element that carries `data-rf2-source-coord`). The injection rules:

- `[:tag {…attrs} & children]` — merge `:data-rf-view` into the existing attrs map (alongside `:data-rf2-source-coord`).
- `[:tag & children]` (no attrs map) — splice an attrs map in between head and children carrying both attributes.
- `[fragment / interop-head / fn-component …]` — SKIP (see §Documented edge cases below).
- React-element output (UIx): `React.cloneElement` with `{"data-rf-view": <id>}` on the same call that adds `data-rf2-source-coord`.
- Form-2: when the render-fn returns a fn, the wrapper recurses on the inner fn's output (same machinery as the source-coord walk).

### CRITICAL constraint: mutate, do not wrap

> Adapters MUST mutate the existing first element's attribute map. Adapters MUST NOT wrap the rendered tree with a synthetic host element (e.g. `[:div {:data-rf-view …} <user-tree>]`).

Wrapping is a non-starter — it breaks every layout idiom that depends on the DOM tree shape:

- **Flexbox + CSS Grid** — `display: flex` / `display: grid` parents lay out their *direct* children. A synthetic wrapper would make every reg-view'd component a single grid/flex item regardless of what its render-fn produced.
- **Table layouts** — `<table>` / `<tr>` / `<td>` is a fixed DOM contract; an interposed `<div>` between `<table>` and `<tr>` is invalid HTML and breaks the browser's table-anonymous-box generation.
- **`:nth-child` and sibling selectors** — `:nth-child(2n+1)`, `+ sibling`, `~ general-sibling` all count DOM positions. A wrapper would shift every child's index by one and break striping / first-row callouts / form-row separators.
- **Positioning ancestors** — `position: absolute` looks for the nearest `position: !static` ancestor. A wrapper that inadvertently inherits the user's `position: relative` would silently capture every descendant's absolute positioning.
- **Stacking contexts** — `z-index` resolves against the nearest stacking-context ancestor; a wrapper with `opacity < 1` or `transform` would create a new stacking context the user didn't author.
- **CSS containment** — `contain: layout / paint` boundaries depend on element identity; an interposed wrapper would either shift the boundary or invalidate the optimisation.

The mutate-existing-attrs strategy avoids every one of these failure modes — the rendered DOM tree is structurally identical to the un-instrumented version, modulo two extra attributes on the root element of each registered view.

### Documented edge cases (fidelity gaps)

The fallback is a **lossy approximation** of the Fiber-walker's hierarchy capture. These shapes are exempt from `data-rf-view` annotation (the wrapper SKIPs with a one-shot warning per id, same as the source-coord exemption):

1. **React Fragment root (`:<>` / `<Fragment>`)** — a fragment has no DOM element to annotate. The fallback walker treats the component as invisible to hierarchy capture (its children become orphans of the next-up tagged ancestor). The Fiber-walker primary path handles fragments correctly via the `child` slot.

2. **Nil / conditional root (`(when cond …)` returning nil)** — when the render-fn returns nil, no DOM element exists. Same fidelity gap as fragments: the view is invisible on the render that returned nil; subsequent re-renders that produce a DOM element are tagged correctly.

3. **Component-returning-component head (`[other-view …]`)** — when a reg-view'd component's root is another reg-view'd component, the wrapper SKIPs (the head is a fn, not a DOM-tag keyword). The inner component will tag *its own* root; the outer view is invisible to the hierarchy capture and its children become orphans of the inner tagged element. Pair tools can chase the wrapping via `(rf/handler-meta :view id)`.

4. **Portals (`React.createPortal`)** — portals teleport the rendered subtree to a different DOM location. The walker's DOM-containment inference will associate portal children with the portal target's ancestor chain, not with the portal-rendering component's ancestor chain. The Fiber-walker primary path handles portals correctly because Fiber `return` pointers follow the logical parent, not the DOM parent.

5. **`display: none` subtrees** — elements with `display: none` are present in the DOM tree (and so are walkable by `querySelectorAll`) but are not laid out. The walker reports them; consumers (Xray Views panel) may choose to filter them out. This is a known fidelity gap, not a correctness bug.

6. **Interop component head (`:>` in Reagent)** — `[:> Cmp {…props}]` hands the props map straight to React's component, which may not be a DOM-tag (and certainly should not have framework-derived strings inserted into its props). The wrapper SKIPs and emits the same warning as the source-coord exemption.

### Production elision (mandatory)

`data-rf-view` MUST elide under `:advanced` + `goog.DEBUG=false` via the SAME `(when interop/debug-enabled? …)` gate that elides `data-rf2-source-coord`. The literal `data-rf-view` string fragment is part of the standard `scripts/check-elision.cjs` sentinel set.

### Walker contract (fallback path)

When the fallback is consuming the tagged DOM, the walker:

1. Calls `document.querySelectorAll('[data-rf-view]')` to enumerate every tagged element in document order.
2. For each tagged element, reads `data-rf-view` and `data-rf2-source-coord` off the DOM node.
3. Infers parent-child by DOM containment: element B is a child of element A iff A is the nearest tagged ancestor of B (via `.contains()` walks).
4. Produces the same output shape as the Fiber-walker (per [View-Hierarchy-Capture.md §Output shape](View-Hierarchy-Capture.md#output-shape)) so consumer code is path-agnostic.

The walker implementation lives at `tools/xray/src/day8/re_frame2_xray/views/view_walker.cljs` (alongside the Fiber-walker per the spec's Ownership table). Both walkers are bundle-isolated from production builds.

## React DevTools support (zero-config, dev-only)

re-frame2 is Reagent-substrate-native (see §Reactive Substrate above). The framework MUST therefore make React DevTools — the industry-standard React-app inspection tool — work cleanly against any re-frame2 app. The two contracts below are framework-level; an app author opts into none of them, they fire by the same wrappers that handle the source-coord and view-tagging contracts.

1. **Component display-name = registered view-id.** Every adapter's `reg-view` wrapper MUST stamp the React `displayName` of the wrapped component to the view-id's **performance/display projection** — `re-frame.performance/entry-id`, the same call [009 §Naming convention](009-Instrumentation.md#naming-convention) uses to build the `<id>` half of `rf:render:<id>` — so React DevTools' component tree shows `<cart/total-line>` rather than the CLJS-munged function name or an anonymous Reagent wrapper. Reagent's class-component machinery reads `.-displayName` off the input fn and forwards it to the constructed component; React-hook substrates (UIx) set it directly on the wrapped function component. Gated on `interop/debug-enabled?` so the per-view id-string literal elides in production builds.

> **Amendment, 2026-08-07 (rf2-976bw) — the projection, not the keyword.** This item originally required `(str view-id)`, giving `<:cart/total-line>`. That conflicted with [009 §Naming convention](009-Instrumentation.md#naming-convention), which makes the `<id>` in the measure name and **the id the substrate publishes to the developer** one identifier, "so a name read off the User-Timing stream is directly jumpable in the tooling". A keyword stringifies *with* its colon, so the same view read `:cart/total-line` in DevTools while its own bracket wrote `rf:render:cart/total-line`; pasting one into the other produced `rf:render::cart/total-line` and matched nothing. 009 is the binding statement and this item now conforms to it.
>
> The distinction the amendment draws is between two things a view-id is used for, and the answer differs because the requirements differ:
>
> - The **logical registry id** is a keyword — what `reg-view` registers, what `(rf/view :cart/total-line)` looks up. It is the thing itself.
> - The **performance/display projection** is `entry-id`'s rendering of that keyword: colon-free, namespace preserved. It is what a *human or a tool reads*, and every surface that publishes a name to a developer publishes this one, through the same fn, so two spellings cannot drift into existence.
>
> `data-rf-view` is deliberately **not** amended and keeps `(str view-id)`. It is not a display projection but a round-trippable **encoding**: [§View tagging contract](#view-tagging-contract-fallback) requires the attribute to be reversible to the registered keyword, and `re-frame.source-coords/parse-view-id` reads the leading `:` back to distinguish a keyword id from a string one. A projection has no inverse to preserve; an encoding does.



2. **Frame-context display-name.** The React Context object backing the frame-provider (per §Frame-provider via React context below) MUST carry a `displayName` of `"rf2-frame"` so React DevTools' Context inspector shows the entry as `rf2-frame.Provider` rather than the opaque default `Context.Provider`. The label is distinct from any keyword namespace, keeping the elision-bundle sentinel unambiguous. The assignment site sits inside `(when interop/debug-enabled? …)` so the string literal elides in production. The per-frame value (`:rf/default`, `:tenant/admin`, etc.) is already inspectable as the Context value — DevTools renders it as the keyword's `pr-str`.

Both sites share the standard `interop/debug-enabled?` elision gate and are subject to the bundle-isolation gate (no `displayName`-assignment branches, no Context display-name string in the production bundle). React DevTools is a dev-time inspection tool; the framework pays nothing for these affordances in production.

The framework does not emit JSX source-coord props (`_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber`) for the "View source" gesture; see §Historical: JSX source-coord props (removed — never worked) above.

## Subscription cache — contract and operational semantics

A subscription's value lives in the per-frame **sub-cache**. This section defines the contract: the [host value model](#host-value-model--rf-equality-and-value-keyed-caching) (the `rf=` equality primitive and the value-keyed cache-key contract the rest of the section rests on), the cache shape, the lookup algorithm, the invalidation algorithm, the ref-counting and disposal rules, the layer-1/2/3 sub semantics, and the lifetime contract that ties them together. The contract is host-agnostic; the [Reagent reference adapter §Sub-cache wiring](#sub-cache-wiring-reagent-realisation) shows the CLJS realisation.

> **v1 reference.** v1's `re-frame.subs` namespace already implements most of this — the invalidation algorithm, the cache de-duplication, the disposal-on-no-readers behaviour. What is *new* in re-frame2: the cache is **per-frame** (v1 has one global cache); disposal-on-frame-destroy is a contract, not an implementation detail; the layer-1/2/3 framing is named explicitly so non-CLJS implementors can satisfy the contract without leaning on Reagent's reaction machinery.

### Host value model — `rf=` equality and value-keyed caching

Everything below — cache-key identity, lookup de-duplication, invalidation, derived-value propagation collapse, and the commit-plane change-detection in [002 §The `:db` commit family](002-Frames.md#the-db-commit--no-op-return-family) — is expressed in terms of value equality (`=`) over value-hashed persistent collections. On the CLJS reference this is ClojureScript `=` and persistent vectors/maps, and the sentences read as implementations. **The seven non-CLJS in-scope hosts** (TS-React / Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint) get no such primitive for free: the host's native `===`/`Object.is`/reference-keyed `Map` compares arrays and objects by identity, so a literal port silently ships a runtime where equal subscriptions never de-duplicate, ref-counts never converge, and disposal never fires — a leak the conformance corpus cannot see, because its fixtures subscribe each query once. This subsection pins the two primitives that layer needs so two implementors cannot diverge silently: the **`rf=` value-equality relation** and the **value-keyed cache-key contract**.

#### `rf=` — the runtime value-equality relation

`rf=` is the equality every reactive comparison in this spec means when it writes `=`: cache-hit detection, invalidation ("changed value"), derived-value propagation collapse, and commit no-op detection. It is **structural value equality** over the host's frame-state value domain, pinned leaf-by-leaf to the CLJS reference so a port cannot pick a subtly different off-the-shelf relation:

1. **Reference-identity short-circuit (MANDATORY).** `rf= a a` MUST return `true` without descending, at **every** level of the structure — not only at the root. This is not merely an optimisation: the fallback [invalidation algorithm](#invalidation-algorithm) re-runs every layer-1 sub body on **every** commit and `rf=`-compares its prior value, so without a per-level identity short-circuit a host on the identical algorithm is asymptotically worse than the reference (`O(Σ|sub values|)` deep-compare per event). Structural sharing (the persistent-data-structure requirement in [000 §Note on persistent data structures](000-Vision.md#note-on-persistent-data-structures)) only pays because `rf=` bails on identical subtrees — the equality cost, not just the revert cost, is the reason a port MUST supply value-hashed persistent collections.
2. **Number equality is the host's numeric equality**, matching the CLJS reference: `NaN` is **not** `rf=` to `NaN`; `-0` **is** `rf=` to `0`. (This is CLJS `=`'s behaviour and JS `SameValueZero` for `-0`, but **not** JS `SameValueZero` for `NaN` — `SameValueZero(NaN, NaN)` is `true`. See the divergence note below.)
3. **Strings, booleans, keywords/idents, and the nil/absent marker compare by value.** The host's identity primitive (per [000 §The identity primitive](000-Vision.md#the-identity-primitive--required-properties)) compares by its value contract; `nil` (or the host's canonical nil/none marker) is `rf=` only to itself.
4. **Collections compare element-wise, recursively via `rf=`.** Sequential collections compare by length then position; associative collections compare by key set then per-key value (**order-independent** — insertion order is not part of the value; see [§Value-keyed cache-key contract](#value-keyed-cache-key-contract)); sets compare by membership. A collection is never `rf=` to a collection of a different kind.
5. **An entry whose value is the host's *absent* marker is distinct from a missing entry.** `{:x nil}` (present key, nil value) is **not** `rf=` to `{}` (absent key) — the present-nil-vs-missing distinction the path algebra and CEDN-1 already pin ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity)).

> **Divergence note (load-bearing).** A deep-equal library whose leaf semantics treat `NaN` as self-equal — notably lodash `isEqual` and any relation built on JS `SameValueZero` — **diverges from `rf=` and MUST NOT be used unmodified.** On `NaN`- or `-0`-bearing app state (real state is float-bearing: `examples/core/seven_guis/temperature`, `examples/core/seven_guis/circle_drawer`) such a relation invalidates differently from the reference, and both answers pass every existing fixture. A port either writes an `===`-leaf recursive compare (whose `NaN !== NaN` matches the reference) or audits its chosen library's `NaN`/`-0` semantics against rules 2 and 4 above.

`rf=` is a **total, pure, non-throwing** relation over the frame-state value domain. Values outside that domain (host objects, functions, promises, DOM nodes) are not frame-state and are out of scope for `rf=`; a durable write that folds one in is already rejected upstream (per [002 §The `:db` commit family](002-Frames.md#the-db-commit--no-op-return-family) and the recordable-coeffect portability contract).

#### Value-keyed cache-key contract

The [cache shape](#cache-shape) and [lookup algorithm](#lookup-algorithm) say "the cache key is the query-vector itself." That sentence is an *implementation* only on a host where two equal query vectors are one map key. The normative contract, host-agnostic:

> A frame's sub-cache is keyed by **`rf=` on the whole query vector**. Two subscriptions whose query vectors are `rf=` — regardless of allocation identity, and regardless of the insertion order of any map-valued argument — resolve to **one** cache entry: one derived container, one shared computation, one ref-count. Two query vectors that are not `rf=` resolve to distinct entries.

This is what makes the two lookup guarantees ([§Lookup algorithm](#lookup-algorithm) properties) hold: **de-duplication** (concurrent equal subscriptions share one computation) and **correct ref-counting/disposal** (the ref-count converges to zero and the slot disposes only when the *last* `rf=`-equal reader drops). A reference-keyed cache breaks both — the canonical failure is a view that resubscribes `[:editor/field-error :title]` (a freshly-allocated argument vector) each render: under reference keying every render is a miss, a new derived container is allocated, ref-counts never converge, and disposal never fires (`examples/real-apps/realworld_http/article_editor.cljs`). The corpus cannot observe this — fixtures subscribe once — so the contract is stated here rather than left to a fixture to enforce.

**Conformant mechanisms.** Two are blessed; a host picks one:

- **(a) A value-keyed persistent-collection map** — the query vector is the key of a map whose key equality is `rf=` (e.g. an Immutable.js `Map` keyed by an Immutable.js `List`, or the host PDS library's equivalent). This is the reference-aligned mechanism (CLJS uses a persistent map keyed by the persistent query vector directly) and is RECOMMENDED. Note that [Implementor-Checklist F2](Implementor-Checklist.md)'s *first-listed* TS option (Immer) supplies structural sharing but **neither** a value equality **nor** a value-keyed map — a port following that suggestion must add both; the mechanism is not free with every PDS library.
- **(b) An interned canonical encoding** — the query vector is reduced to a stable canonical key (a string/bytes interning `rf=`-equal vectors to one key), e.g. the [CEDN-1 canonical byte encoding](Conventions.md#canonical-byte-encoding-cedn-1). If a host chooses (b), the query-vector **arguments** MUST lie in a portable canonical domain (so map-key order, vector-vs-list kind, and present-nil are all handled by the encoding, as CEDN-1 already pins), and a dev-mode `:rf.error/*`-family diagnostic SHOULD flag an out-of-domain argument rather than silently mis-keying it.

> **Cache-key domain vs `rf=` domain — an open reconciliation (flagged for review).** `rf=` (above) matches CLJS number equality and therefore **permits finite floats** in a query argument (`NaN !== NaN`, `-0 = 0`), whereas CEDN-1's identity domain **fails closed on all floating-point values** ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity), by design — durable identity must not hash a float). A host on mechanism **(a)** has no tension: a value-keyed map keys directly on `rf=` and admits float-bearing args natively, exactly as the reference does. A host on mechanism **(b)** inherits CEDN-1's float rejection and would either forbid float-bearing query arguments or need a **CEDN-float extension scoped to the cache-key domain only** (not to durable identity). **The minimal choice pinned here is: mechanism (a) is the reference-aligned default and admits finite floats; the CEDN-float extension for mechanism (b) is *not* specified in this pass** — a (b) host today must keep float-bearing values out of query arguments (encode them at the boundary, as CEDN-1 already requires elsewhere) or await that extension. Whether to bless a cache-key-scoped CEDN-float extension is left as an explicit decision for review; it is called out in [§Open questions](#open-questions).

**Conformance.** Two fixtures pin the observable contract for hosts whose native collections are reference-keyed:

- [`sub-cache-dedupes-equal-query-v.edn`](conformance/fixtures/sub-cache-dedupes-equal-query-v.edn) — query vectors that are `rf=`-but-not-identical (distinct allocations, differing map-arg insertion order) resolve to **one** cache key; not-`rf=` vectors resolve to distinct keys. Asserted at the cache-key identity boundary the value-keyed cache relies on.
- [`sub-cache-key-map-arg-order.edn`](conformance/fixtures/sub-cache-key-map-arg-order.edn) — two query vectors carrying a map argument in different insertion order share **one** cache key, pinned both at the byte level (one canonical token stream) and the identity level.

> **Conformance-observability note (flagged).** Both fixtures assert the cache **key** identity — the pure mechanism a value-keyed cache rests on — which is the JVM-runnable, host-portable surface the corpus already exercises for canonical identity. A deeper *live-runtime* assertion — subscribe the same query through two **distinct host allocations** in one frame and count exactly one cache-slot creation (`:rf.sub/first-run? true` once) — would catch a reference-keyed host directly, but needs a new Mode-A harness primitive (the current sub-DSL has no "subscribe this query twice through distinct instances" op, and CLJS EDN vectors are value-equal so two literals are already one key). That live-observability extension is left as follow-up.

### Cache shape

Each frame holds one sub-cache, keyed by `[query-vector]`:

```clojure
;; Per-frame sub-cache, the entry shape the reference stores.
;; The entry wraps a substrate-specific *derived container* — in CLJS a
;; Reagent Reaction; on plain-atom hosts a thunk that recomputes on deref
;; (per [§make-derived-value]). The cached value is NOT a separate slot:
;; it lives ON the derived container and is read via deref. Disposal is
;; the derived container's own on-dispose hook (CLJS: interop/add-on-dispose!
;; on the Reaction), NOT an entry-level callback vector.

{[query-vector]
 {:reaction  r            ;; the substrate-specific derived container
  :inputs    [[q1] [q2]]  ;; the realized input query-vectors for THIS cache entry —
                          ;; the literal :<- chain for a static sub, or the
                          ;; (input-fn query-v) result for a parametric sub (per
                          ;; [§Subscription input producers]). Fixed for the entry's
                          ;; lifetime (fixed-topology-per-cache-entry invariant).
  :ref-count n}}          ;; how many readers currently hold a reference
```

The cache is held inside the frame container (per [002 §What lives in a frame](002-Frames.md#what-lives-in-a-frame)). Two frames running the same `(rf/subscribe [:cart/total])` compute against their own `app-db`s and cache against their own caches; isolation is automatic.

The canonical demo of this rule is the **parallel-frames** testbed at [`tools/xray/testbeds/parallel_frames/`](../tools/xray/testbeds/parallel_frames) — one app mounted in two `frame-provider`-rooted subtrees (`:above` and `:below`) on one page. Same view source, same registered handlers and subs, two fully isolated reactive contexts that diverge as the user interacts with each independently. There is no cross-frame sub, no cross-frame data routing, no "route data home" pattern — each frame is its own world.

### Lookup algorithm

```
Lookup [query-v] in frame F:
  k ← cache-key(query-v)
  If F.sub-cache[k] exists:
    F.sub-cache[k].ref-count += 1
    return F.sub-cache[k].reaction      ;; the derived container
  Otherwise (cache miss):
    meta    ← registrar.lookup(:sub, first(query-v))
    ;; Produce this entry's input query-vectors from the sub's input producer
    ;; (per [§Subscription input producers]), then resolve each recursively.
    input-qs ← match meta.input-kind:
                 :db          → []                        ;; layer-1: no producer
                 :static      → meta.input-signals        ;; literal :<- query-vectors
                 :parametric  → validate((meta.input-fn query-v))  ;; vector of query-vectors
    inputs   ← input-qs.map(q → subscribe(F, q))  ;; recurse — resolve each input → containers
    body     ← meta.fn
    derived  ← substrate.make-derived-value(
                inputs,                            ;; the resolved input containers
                (in-vals) → body(in-vals, query-v))
    F.sub-cache[k] ← {:reaction  derived
                      :inputs    input-qs   ;; the realized input QUERY-VECTORS for this entry
                      :ref-count 1}
    ;; Wire disposal on the derived container itself — when its last
    ;; derefer drops, release input refs and dissoc the slot. The cache
    ;; holds NO entry-level dispose-fn vector; it relies on the container's
    ;; own on-dispose hook (CLJS: interop/add-on-dispose! on the Reaction).
    on-dispose(derived, () → { for q in input-qs: unsubscribe(F, q)
                               F.sub-cache.dissoc(k) })
    trace! :sub/registered {:query-v query-v :frame F.id}
    return derived
```

Two properties this guarantees:

1. **De-duplication.** Concurrent equal subscriptions share one cached computation. The cache key is the query-vector itself, compared by `rf=` (per [§Host value model](#host-value-model--rf-equality-and-value-keyed-caching) — two `rf=`-equal query vectors are one key on every host, however the host realises value-keyed lookup). v2 has a single disposal algorithm (synchronous ref-counting; see [§Reference counting and disposal](#reference-counting-and-disposal)).
2. **Layer-1/2/3 chaining.** A layer-2 sub's `:<-` inputs are themselves resolved via this same lookup, recursively. The recursion terminates at layer-1 subs whose inputs are not other subs but readers over a **partition projection** directly — the **app-db** projection for ordinary app subs, the **runtime-db** projection for framework subs (`[:rf/machine <id>]`, `[:rf.route/*]`). Per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections).

### Invalidation algorithm

The contract:

> A subscription's cached value is invalidated **only when an input the subscription depends on changes value** (by `rf=` equality — the value-equality relation pinned in [§Host value model](#host-value-model--rf-equality-and-value-keyed-caching); `=` on the CLJS reference).

The algorithm, host-agnostic. The drain commits the whole **frame-state** in one atomic write; the two partition projections (`app-db`, `runtime-db`) recompute over the new frame-state and propagate only the partition(s) that actually changed — an app-only commit leaves the runtime-db projection value-equal (so framework subs stay cached) and vice versa, **for free** from projection equality (per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections)):

```
On commit-frame-transition!(F.frame-state, new-frame-state):   ;; called from drain loop step 2
  new-app-db     ← (:rf.db/app new-frame-state)
  new-runtime-db ← (:rf.db/runtime new-frame-state)
  ;; Phase 1: layer-1 subs (those whose inputs read a partition projection).
  ;;   An app sub reads new-app-db; a framework sub reads new-runtime-db.
  ;;   A projection value-equal to its prior value propagates nothing — so a
  ;;   runtime-only commit never re-runs app subs, and an app-only commit never
  ;;   re-runs framework subs.
  For each k → entry in F.sub-cache where entry is layer-1:
    partition-val ← (if (framework-sub? entry) new-runtime-db new-app-db)
    new-val ← (entry.body partition-val query-v)
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

The `=` test in Phase 1 and Phase 2 is a contract on the *verdict*, not a mandate to walk the structure every time. A single-source consumer whose source publishes a [movement witness](#movement-witness-optional) may reach the same verdict by pointer comparison when the witness determines it, and must reach it by the `=` walk otherwise — obligation C1 there makes the two indistinguishable from outside.

**First-run discriminator on the cache-miss path.** The cache lookup step above splits cleanly into two cases: a hit (existing slot, ref-count bump) and a miss (fresh slot, body's first run). The memo wrapper threads that discrimination through to the trace stream as a `:rf.sub/first-run?` boolean on every `:rf.sub/run` emit (`true` on the run that allocated the slot, `false` on every subsequent recompute). Consumers (Xray's SUBSCRIPTIONS leaf-scalar renderer) need the discriminator to render a fresh-cache-entry run (the sub is now alive — `:added` chrome, no "was") distinctly from a recompute whose prior value happened to be `nil` (the value really changed `nil → X` — `← was nil` annotation). Both shapes report `:rf.sub/value-changed? true` and `:rf.sub/prev-value nil`; the `:first-run?` flag is the only signal that distinguishes them. See [Spec 009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) for the full `:rf.sub/run` tag-map shape.

### Layer-1, layer-2, layer-3 sub semantics

The terminology comes from re-frame v1; the semantics carry over.

| Layer | Inputs | Example | Recompute trigger |
|---|---|---|---|
| **Layer-1** | Reads `app-db` directly | `(reg-sub :user (fn [db _] (:user db)))` | The body re-runs on **every** commit (the algorithm above runs each layer-1 body against the new partition projection unconditionally); the `=` check on the *result* gates propagation, not the run. |
| **Layer-2** | Reads other subs via `:<-` | `(reg-sub :user-name :<- [:user] (fn [u _] (:name u)))` | Any input sub's value changes by `=`. |
| **Layer-3** | Reads other subs via `:<-`, where one or more inputs are themselves layer-2 | `(reg-sub :user-greeting :<- [:user-name] :<- [:locale] (fn [...] ...))` | Any input sub's value changes by `=`. |

Layers ≥ 3 are conventionally just "layer-2+" — the algorithm treats them all the same. The distinction matters for understanding the cascade order (layer-1 settles before layer-2, layer-2 before layer-3) but not for the implementation, which uses `:<-` chain depth implicitly via topological iteration.

**Layer-1 bodies MUST be cheap.** Because every layer-1 body re-runs on every commit (it is the propagation gate — it must run to decide whether to propagate), a layer-1 body must be a plain slice: a `get` / `get-in`, nothing more. Put a `sort-by` or any real computation in a layer-1 body and that work runs on every commit, in every frame, including commits that touch unrelated state. Push the computation into a layer-2 `:<-` sub, where it runs only when the extracted slice actually changes by `=`.

### Subscription input producers — app-db reader, static, parametric input-fn

Every subscription has one **input query-vector producer** — the thing that, at cache-miss time, names the upstream subscriptions this node depends on. The three producer kinds are a single unifying model:

> A subscription has an input query-vector producer. Layer-1 has **no** producer (it reads `app-db` directly); `:<-` is the **literal** producer (a fixed list of input query vectors known at registration); and an `input-fn` is the **query-parametric** producer (it computes the input query vectors from the outer `query-v`).

This is what `reg-sub`'s optional first function is. It is a v2 **`input-fn`**: a **pure** function from the outer subscription `query-v` to a vector of input query vectors — **not** a v1 reaction-returning signal function. The runtime resolves each returned query vector through the ordinary [§Lookup algorithm](#lookup-algorithm) in the same frame as the outer subscription, then calls the computation function with the resolved input *values* (in order) and the outer `query-v`. Input-production equivalence does not erase handler delivery shape: static `:<-` preserves the v1 convention (one input -> bare value, multiple inputs -> vector), while parametric `input-fn` subscriptions always deliver a vector of resolved input values, including the single-input case.

```clojure
(rf/reg-sub
  :article/page
  (fn input-fn [[_ article-id]]                ;; query-v → vector of query vectors
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     [:viewer/current]])
  (fn computation-fn [[article comments viewer] [_ article-id]]
    {:id article-id :article article :comments comments
     :can-edit? (:edit? viewer)}))
```

**Input grammar.** An `input-fn` MUST return a vector, and every element MUST be a query vector (a vector whose head is a keyword):

```clojure
query-vector := vector whose first element is a keyword
input-return := [query-vector*]
```

Accepted: `[[:a id] [:b]]` (multiple), `[[:a id]]` (single — still a vector OF query vectors), `[]` (no inputs). **Rejected** (each signals `:rf.error/sub-input-fn-bad-return` per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)): a bare keyword (`:a`), a scalar query vector (`[:a id]` — ambiguous between "one query with arg `id`" and "two inputs"), a mixed `[[:a id] :b]`, a map (`{:a [:a id]}`), or a reaction / derefable. The only accepted single-query spelling is `[[:x :y]]`. The grammar is owned by [Conventions §`reg-sub` input grammar](Conventions.md#reg-sub-input-grammar--input-fn-returns-a-vector-of-query-vectors) and mirrored in [API §`reg-sub` input-production modes](API.md#reg-sub-input-production-modes).

The `input-fn`:

- receives the full outer `query-v`;
- runs when the concrete subscription node is materialized (a cache miss), **not** on the hot recompute path;
- returns a vector of query vectors;
- MUST be pure and deterministic over `query-v`;
- MUST NOT call `subscribe`, deref `app-db`, dispatch, mutate, or perform IO.

Static `:<-` is exactly a **constant** `input-fn`. `(reg-sub :vis :<- [:items] :<- [:filter] f)` is equivalent to `(reg-sub :vis (fn [_] [[:items] [:filter]]) f)`. Use `:<-` for static inputs; reach for `input-fn` only when the upstream query vectors need values carried by the outer `query-v`.

**Fixed-topology-per-cache-entry invariant.** A subscription's cache entry (keyed by its concrete outer query vector) has a **fixed** set of upstream edges for the entry's whole lifetime. The `input-fn` runs once at materialization to compute those edges; once materialized the node follows ordinary layer-2+ semantics (upstream value changes trigger recompute; `=`-equal upstream values suppress recompute; disposal releases the realized upstream subscriptions synchronously; hot reload invalidates the affected cache entries and the `input-fn` re-runs on recreation). The realized input query vectors are stored on the cache entry (alongside `:inputs`) so disposal, trace, and Xray can read them — see [§Lookup algorithm](#lookup-algorithm) and [§Sub-cache wiring](#sub-cache-wiring-reagent-realisation).

**Impure-input-fn failure mode (unenforced).** The "MUST be pure and deterministic over `query-v`" requirement is **not** enforced at runtime: an impure `input-fn` realizes a **different topology each time its cache entry is disposed and rematerialized**, no error fires, and the symptom is Xray showing **different `realized-inputs` for the same `query-v` across remounts**. Naming it makes it diagnosable. (A dev-mode double-call guard at materialization — call the `input-fn` twice and compare returns — was considered and judged overkill: materialization is off the hot path, the corruption is rare, and this diagnostic note suffices to locate it.)

**No app-db-dependent topology.** An `input-fn` MUST NOT choose its edge set from `app-db`. The objection is **not** purity — an `app-db` value is pure and JVM-computable — it is **reactive-cache stability**: a state-dependent edge set would let a cache entry's upstream edges change as `app-db` changes, breaking the fixed-topology-per-cache-entry invariant that disposal, hot reload, live topology display, and Xray explanation rely on. When a parameter lives in `app-db`, thread it through the **outer query vector at the call site** instead:

```clojure
(let [article-id @(rf/subscribe [:current-route/article-id])
      page       @(rf/subscribe [:article/page article-id])]
  ...)
```

The graph stays dynamic at the **view boundary**, where React already manages subscription lifecycle; each concrete `[:article/page article-id]` cache entry keeps stable dependencies for its lifetime. (This is why a v1 signal function returning live reactions, or an `input-fn` reading `app-db`, are both rejected — see [Backwards compatibility / migration](../migration/from-re-frame-v1/README.md#m-71-v1-signal-functions--v2-input-fns-vector-of-query-vectors).)

**Frame resolution.** Input query vectors are frame-agnostic data. The runtime resolves them in the **same frame** as the outer subscription; the `input-fn` does not receive a frame argument and must not search for one. Frame identity is carried by the outer subscription operation and every realized input inherits that frame.

### Reference counting and disposal

The cache is **not** strong-referenced from the frame for the lifetime of the frame; entries dispose when their last reader goes away. The disposal algorithm is **synchronous ref-counting on derefer-count → 0** — a single algorithm. There are no pluggable lifecycle policies; the v1 alpha namespace's `:safe`, `:no-cache`, `:reactive`, and `:forever` lifecycles are not part of v2, and v2 does NOT carry a deferred-grace-period timer either.

This is the *same* refcount-on-a-shared-cache-entry liveness lifecycle that resource `:active-owners` GC uses, with one divergence: a subscription disposes synchronously in-tick at zero, whereas a resource entry keeps a **warm** `:gc-after-ms` window after its last owner releases — see [016-Resources §Kinship with subscription disposal](016-Resources.md#the-scoped-cache-owner-lifecycle).

When the last subscriber drops, the cache slot is evicted **in-tick**: the reaction is disposed, the on-dispose callbacks fire (releasing input ref-counts on layer-2+ subs — see [§Disposal cascades](#three-subtleties) below), and the slot is dissoc'd. A `:rf.sub/dispose` trace event with `:rf.sub/reason :no-more-derefers` is emitted at the eviction site.

```
On subscriber detach (view unmounts, tool disconnects):
  entry.ref-count -= 1
  If entry.ref-count == 0:
    dispose(entry.reaction)      ;; fires the derived container's on-dispose
                                 ;; hook → releases input refs, dissocs the slot
    trace! :rf.sub/dispose {:rf.sub/query-v k :frame F.id
                            :rf.sub/reason :no-more-derefers}

On subscriber attach (cache HIT; the slot already exists):
  entry.ref-count += 1
```

Disposal is wired on the derived container, not an entry-level callback
vector: disposing `entry.reaction` runs the on-dispose hook that
`compute-and-cache!` registered (CLJS: `interop/add-on-dispose!`), which
both releases the input refs (layer-2+ cascade) and dissoc's the slot.

A subscribe arriving AFTER the disposal is treated as a fresh cache miss: `compute-and-cache!` builds a new reaction against the registered sub body. The recomputed value will `=` what was disposed (same body, same `app-db`) so the post-rebuild render observes no value change.

**This algorithm is the whole algorithm.** [§Render-phase provisional acquisition and commit adoption](#render-phase-provisional-acquisition-and-commit-adoption) below constrains how a React-hook substrate may hold a reference between its render and its commit; it adds no cache state, no entry lifecycle, and no grace window. A provisional reference is an ordinary ref-count, counted here, disposed here, on the same in-tick 1 → 0 edge as every other.

#### Disposal guarantees

- **Zero-subscriber → disposed in-tick.** When `ref-count` reaches 0 the on-dispose callbacks fire, the cache slot is removed, and a `:rf.sub/dispose` trace event is emitted — all within the call that drove the 1 → 0 transition. No state change can land between the count reaching zero and the eviction; the reaction's watch on `app-db` is unwound before the next dispatch can observe it.
- **No wasted recompute before disposal.** Because the dispose is synchronous on the 1 → 0 edge, the cache cannot hold a sub alive across a state change that lands after the last derefer has dropped. There is no deferred-grace window in which the reaction keeps watching `app-db` and a state change forces a recompute whose result is about to be thrown away.
- **Hot-reload preserves the contract.** Re-registering a sub disposes every cached slot for that query (regardless of ref-count) — the next subscribe builds afresh against the new body.
- **Frame teardown preserves the contract.** Destroying a frame disposes every cached slot; see [§Lifetime contract — frame disposal](#lifetime-contract--frame-disposal).

#### On-dispose hooks

The `on-dispose` hook lets the adapter release substrate-specific resources (a Reagent reaction; a JS-cross-compile-port atom-shape's listener entry / derived-value memo) before the cache slot is removed. Hooks fire **synchronously** on the 1 → 0 transition. The CLJS reference uses `interop/add-on-dispose!` per the Reagent realisation in [§Sub-cache wiring](#sub-cache-wiring-reagent-realisation).

**Disposal is idempotent and re-entrant safe.** The derived-value `dispose` contract (CLJS: `re-frame.disposable/IDisposable` for the function-component substrates; the substrate's own `IDisposable` for Reagent / reagent-slim) fires every on-dispose callback **exactly once** in registration order and releases source watches once. A second `dispose` — or a `dispose` re-entered from inside an on-dispose callback (a cleanup path that defensively disposes the same derived value) — is a no-op: the implementation flips a disposed guard / snapshot-and-clears its callback holders before firing, so re-entry cannot double-release layer-2 inputs, double-fire user cleanup, or recurse.

#### Three subtleties

1. **A sub can become live again after disposal.** A view unmounts and its last subscription drops; the slot disposes. Later, the same view re-mounts (cache miss, fresh computation). This is correct — the cache is performance, not state. The recomputed value will `=` what was disposed (same body, same `app-db`); no observable difference. **Shared-component re-mount in the same cascade**: when view A unmounts and view B (which subscribes to the same `query-v`) mounts in the same React commit, the sub is disposed by A's cleanup then re-built by B's mount. The disposed reaction and the rebuilt reaction are distinct objects but compute the same value; the cost is one extra `compute-and-cache!` call (one reaction allocation, one body run) — accepted as the "most honest" cost of closing the wasted-recompute window.
2. **Eager subs.** A future `:reg-sub-by-path` (post-v1) might keep its cache slot live regardless of ref-count, for performance. v1 has no eager subs; if added, the contract surface is `entry.eager? = true` and the disposal path skips the slot. **SA-4 — untracked note (no bead filed yet):** this is a post-v1 design direction with no concrete tracking bead, so it does not qualify as `:post-v1 tracked` (which requires a `rf2-<id>`). **Fires-when trigger:** measured perf demand — a real workload where the per-subscribe rebuild cost of an always-recomputed path sub is the dominant cost. The disposal seam is already pinned (`entry.eager? = true` ⇒ disposal skips the slot), so the note tracks the *decision to add eager subs*, not an open disposal question; a tracking bead is filed only when that perf trigger fires.
3. **Disposal cascades.** When a layer-2 sub disposes, its layer-1 inputs lose one reader each (the parent's `on-dispose` callback calls `unsubscribe` on every `:<-` input symmetrically with the construction-time subscribes). If an input was held only by that layer-2 sub, it cascades to disposal in the same tick. The whole cascade — parent + every transitively-held input — completes within the call that drove the parent's 1 → 0 transition.

#### Render-phase provisional acquisition and commit adoption

*React-hook substrates only. The algorithm above is unchanged by this subsection: the cache never holds a ref-count-0 entry, and the 1 → 0 transition still disposes in-tick with no grace period. What follows constrains a particular class of **owner**, not the cache.*

A React render and the commit that owns it are two moments, and only the commit may own resources. A hook substrate that reads a subscription during render therefore faces a choice the Reagent family never does: hold a reference the commit can inherit, or hold nothing and rebuild. Holding nothing is what the earlier rule required — and on a cold read it made a single mount pay for two constructions, because the render's balanced `subscribe`/`unsubscribe` round trip crossed the 1 → 0 edge and destroyed the reaction the commit was about to want.

The contract is therefore:

- **The render phase MAY acquire a reference, PROVISIONALLY.** A hook substrate's render-phase read MAY take an ordinary ref-count and hold it for the commit to adopt. It is an ordinary reference held by an ordinary owner — the entry it keeps alive is at ref-count ≥ 1 throughout, and no cache mechanism, entry state, or reaping policy is added to satisfy it.
- **The reaper is armed at acquisition, and is a host MACROTASK.** The release MUST be scheduled before the acquiring expression returns, unconditionally, and MUST NOT depend on any subsequent React callback running. It MUST be a macrotask (a `setTimeout`-class task): React installs the subscription as a passive effect, and a microtask reaper drains at the end of the current task — before that flush — so it would reap every provisional reference before the commit that was meant to adopt it. **A macrotask is necessary and NOT sufficient: WHICH macrotask decides whether the adoption is realised.** Measured on the shipping client mount path (`createRoot(…).render(…)` through the [§`render`](#render-render-tree-mount-point-opts--unmount-fn) slot, no `act`, no `flushSync`): a `setTimeout 0` armed during the render fires *before* React returns to flush the passive effect, at one boundary and at three hundred alike, so every cold read is reaped and rebuilt and the double build is paid in full. Longer delays (4 ms, 32 ms) and `requestIdleCallback` cleared it in the same measurement; `requestAnimationFrame` cleared it at one boundary and not at three hundred; a `MessageChannel` post did not, React's own message being posted later on the same FIFO source. The reference implementation's horizon is therefore **4 ms** — the shortest measured delay that clears the double build at both sizes, so an abandoned render holds its graph no longer than the adoption requires. **None of these is a guarantee** — React specifies no maximum render-to-subscribe interval, so no horizon can be sized against a contract, and any choice among them is a margin. This bullet therefore constrains the *class* of the primitive; a substrate MAY pick any macrotask, and the last bullet is the one that carries the weight. *(rf2-2rtt6.25, merged-PR audit of #7305; horizon ruled by rf2-2rtt6.71, witness placement ruled by rf2-2rtt6.80. Conformance witness: `assert-use-subscribe-browser-runner-schedule-rebuilds`, which mounts through the adapter render slot and pins the two constructions — it witnesses the **test runner's** schedule, whose render-to-passive-flush gap measures >128 ms, and so is evidence that a macrotask reaper can lose, not evidence about the margin on a consumer's page. That the 4 ms horizon clears the double build was measured on the ruling's own single-mount instrument at N = 1 and N = 300, and that measurement is now runnable: `node implementation/freehand/test/re_frame/bench/hicasso/adoption_witness_run.cjs` is a committed adoption witness which measures a quiet single-mount page's render-to-passive-flush gap FIRST, prints it on every run, and REFUSES to read an adoption integer at all unless that gap sits comfortably inside the horizon. Nothing invokes it on a schedule — re-run it whenever the `react` / `react-dom` / `playwright` pins or the browser posture change, since those pins are exact and are the only events that can move this race.)*
- **The horizon is one host macrotask.** A render that never commits — abandoned, suspended, unmounted before commit, or thrown out of — retains no ref-count **beyond one host macrotask**. This is the ONE contract-visible change the provisional hand-off makes: the zero-leak property of an abandoned render is unchanged, but its zero-**point** is the horizon rather than the render's own return. A conformance witness asserts **== 0 past the horizon** — and *past* is the operative word, because the reference horizon is the 4 ms delay of the bullet above (rf2-2rtt6.71) rather than the immediate `setTimeout 0` it once was: a witness that settles on a bare `setTimeout 0` of its own now runs *before* the reap and measures nothing. *Within* the horizon the count is bounded by the number of render ATTEMPTS the host made, not by one: React replays a suspended render, and each attempt is a fresh fiber with fresh hook state, so each attempt holds its own provisional reference (measured: 2 for the reference Suspense-abort witness). Bounded-by-attempts and zero-at-horizon is the guarantee; a specific small integer is not.
- **Release is one-shot and identity-guarded.** Exactly one of the adopting commit and the reaper may release a given provisional reference, and the decrement applies only while the cache slot still holds that reference's reaction. Hot reload, cache clear, and frame destroy evict slots out from under live holders; that eviction takes the reference with it, so a late release MUST no-op rather than underflow a successor entry rebuilt under the same key.
- **At most one provisional reference per read site.** A render pass that re-runs its acquisition (React may discard a memo, double-render under StrictMode, or restart an interrupted render) MUST release the previous provisional reference **after** taking its replacement, so a re-rendering site cannot accumulate references and cannot cross the disposal edge between the two.
- **Correctness MUST NOT depend on the reaper losing the race.** If the horizon expires before the commit arrives, the entry disposes and the commit rebuilds — the pre-existing behaviour. The hand-off is an optimisation whose failure mode is the thing it replaced. This bullet is not decoration, and it is what makes a *timed* horizon acceptable at all. On the reference implementation's public mount path the horizon expired first, every time, until rf2-2rtt6.71 moved it to 4 ms; it clears that path now by a measured margin and by nothing stronger, so a React scheduling change could restore the old outcome without notice. Either way everything else in this subsection holds — the zero-leak property, the identity guard, the one-shot release, the disposal cascade — and the cost of losing the race is one extra construction. A substrate MAY implement this subsection and realise none of its saving; what it MUST NOT do is depend on realising it.

*CLJS reference: `re-frame.substrate.spine/use-subscribe`, released through `re-frame.subs/unsubscribe-if-reaction` (identity-guarded) — rf2-2rtt6.25, ruled on rf2-2rtt6.14. The Freehand observation port takes the other branch of the same choice and acquires nothing during render (invariant 1 of [§The six frozen invariants](#the-six-frozen-invariants)); both satisfy the cache contract, and neither is a licence for the other's mechanism.*

### `(subscribe-once query-v) → value` / `(subscribe-once query-v {:frame f}) → value`

The **one-shot, non-reactive read** of a subscription's current value. `subscribe-once` is the canonical end-user surface for "give me the current value of this sub *right now*, and don't retain a reference on my behalf." It is the right call from event handlers, REPL sessions, SSR builders, and any non-reactive consumer; views and tools that want to track future changes use `subscribe` instead.

> **Not from inside a machine callback.** A machine `:guard` / `:action` / `:entry` / `:exit` MUST NOT call `subscribe-once` (nor read app-db any other ambient way): an in-callback ambient read is unrecorded, so replay can select a *different* transition than the original run — breaking 005's token-grain replay contract ([005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017)) and the pure-fn conformance mode. A machine callback receives external facts by **payload threading** (the triggering event carries them) or via a **declared recordable coeffect** on the machine's `:rf.cofx` record — including, machines-only, the sub-valued source `{:rf/sub query-v :as fact-id}`, which records the value of `query-v` (evaluated **once** against the committed pre-cascade frame-state) on the token under `fact-id`, so replay re-presents it verbatim ([005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017)).

```clojure
(subscribe-once query-v)                              ;; → value (uses the resolved current frame)
(subscribe-once query-v {:frame f})                   ;; → value (explicit-frame opts form)
```

**Call-shape parallel with `subscribe`.** The 2-arity is `[query-v opts]` ONLY, exactly as [`subscribe`](API.md#dispatch-and-subscribe) — no `vector?` shape-discrimination, no frame-first positional form (API-shrink #1, rf2-csbbwu deleted it entirely): `opts` may carry `{:frame f}` (a frame-id keyword or a live frame value); ambient when absent. Because `subscribe-once` shares `subscribe`'s exact call shape, an author who learned `(subscribe [:x] {:frame f})` writes the same `(subscribe-once [:x] {:frame f})` and the runtime binds the frame correctly — closing the same misbinding footgun EP-0024 closed for `subscribe` (a frame-first `[:x]` would otherwise have bound as frame-id and `{:frame f}` as query-v). `unsubscribe` (below) deliberately does **not** gain an opts-map form — it is pure teardown, never a hot in-view call, so the (unaffected) frame-first form is its sole explicit-frame shape.

Semantically, `subscribe-once` is `subscribe` + deref + immediate `unsubscribe`:

```
subscribe-once(frame-id, query-v):
  r ← subscribe(frame-id, query-v)                     ;; cache hit OR miss; ref-count += 1
  v ← deref r                                          ;; current cached value
  unsubscribe(frame-id, query-v)                       ;; ref-count -= 1; on 1→0, dispose synchronously
  return v
```

**Contract MUSTs.**

- **One-shot.** Each call subscribes, derefs once, and unsubscribes. The caller does **not** receive a deref-able reaction; the returned value is a plain immutable value of whatever the sub computes.
- **Non-reactive.** The caller is not registered for re-render or change notification. A subsequent `app-db` mutation that would have invalidated the slot has no observable effect on the caller of `subscribe-once` — they got their value, they're done.
- **Synchronous teardown**. Per [§Reference counting and disposal](#reference-counting-and-disposal) the 1 → 0 transition disposes in-tick, so the one-shot read's whole lifetime — subscribe, deref, and (if this call drove the 1 → 0 transition) dispose — completes in the calling tick. A concurrent reactive subscriber (a view holding `subscribe` on the same `query-v`) keeps the slot alive via ref-count; `subscribe-once`'s decrement only triggers disposal when it owned the last reference.
- **Frame-resolution.** The 1-arg form resolves the current frame via the resolution chain (dynamic-var tier, React-context tier when an adapter has registered the `:adapter/current-frame` late-bind hook per [§Frame-provider via React context](#frame-provider-via-react-context)). There is **no `:rf/default` fallback**: with no scope established the resolution raises `:rf.error/no-frame-context`. The public `(subscribe-once query-v {:frame f})` opts form is explicit and bypasses the chain, targeting `f` directly.
- **Missing frame is not an error.** `subscribe-once` against a destroyed or never-created frame returns `nil` (and emits the same always-on `:rf.error/frame-destroyed` error `subscribe` does — recovery `:replaced-with-default`, per [§Lifetime contract — frame disposal](#lifetime-contract--frame-disposal) and [002 §Destroy](002-Frames.md#destroy)); it does NOT throw. (`subscribe-once` reaches this via its internal `subscribe` call.)
- **Missing sub is not an error.** Per [§What happens when a sub references an unknown sub](#what-happens-when-a-sub-references-an-unknown-sub), an unregistered `query-v` emits `:rf.error/no-such-sub` (recovery `:replaced-with-default`) and yields `nil`; `subscribe-once` propagates the `nil`.
- **JVM-runnable.** `subscribe-once` is part of the substrate-agnostic call-site surface; it works against the plain-atom adapter (no Reagent dependency). On the JVM, the deref step reads the substrate's container directly; tests, SSR builders, and headless tools rely on this.

**Where it differs from `compute-sub`.** `compute-sub` (per [008 §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm)) is a *pure* function over an explicit `app-db` value — it bypasses the cache entirely and runs the sub's body fresh. `subscribe-once` is *cache-aware*: it materialises the cache entry (cache hit reuses; cache miss populates briefly), then immediately drops its reference (sync dispose on the 1 → 0 transition). Use `compute-sub` when you want to test a sub's body against a snapshot in isolation; use `subscribe-once` when you want what the running frame would see right now.

### `(unsubscribe query-v) → nil` / `(unsubscribe frame-id query-v) → nil`

The **explicit teardown** of a `subscribe` call. `unsubscribe` decrements the cache entry's ref-count by 1; on the 1 → 0 transition, the cache slot is disposed **synchronously** (per [§Reference counting and disposal](#reference-counting-and-disposal)). Reagent views auto-dispose via the reaction lifecycle and do not need to call `unsubscribe` explicitly; tests, REPL sessions, and tools that subscribed imperatively are the call sites that need it. (Machine callbacks do NOT subscribe imperatively — a `:guard` / `:action` / `:entry` / `:exit` MUST NOT call `subscribe-once`; they take host facts as recorded coeffects, so there is no imperative subscription for them to release. See the callback note under [`subscribe-once`](#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value).)

```clojure
(unsubscribe query-v)                                  ;; → nil (uses the resolved current frame)
(unsubscribe frame-id query-v)                         ;; → nil (explicit-frame, frame-first form)
```

**No opts-map form (deliberate).** Unlike `subscribe` and `subscribe-once`, `unsubscribe` does **not** accept the `(unsubscribe query-v {:frame f})` opts-map call-shape — the explicit-frame form is frame-first only. The misbinding footgun the opts form closes for the read helpers does not apply here: `unsubscribe` is pure teardown (a paired release of a `subscribe` the caller already made with a known frame), never a hot in-view call an author reaches for by muscle-memory from the `subscribe` opts form. Keeping it frame-first avoids widening the teardown surface for no ergonomic gain.

**Contract MUSTs.**

- **Decrement, then destroy on the 1 → 0 edge.** `unsubscribe` decrements the slot's ref-count by 1. The slot itself disposes **synchronously** when ref-count reaches 0 (per [§Reference counting and disposal](#reference-counting-and-disposal)). A caller that holds N concurrent subscriptions to the same `query-v` must call `unsubscribe` N times to fully release; each call decrements one share, and the Nth (the one that drives 1 → 0) disposes.
- **Pair with `subscribe`.** Every `subscribe` (including the `subscribe` half of `subscribe-once`) increments the slot's ref-count by 1; every `unsubscribe` decrements by 1. Imperative subscribers are responsible for the pairing; views and tools that hold reactions through the reaction lifecycle get the decrement automatically when the reaction disposes.
- **Idempotent past zero.** Calling `unsubscribe` after the slot has already been disposed is a no-op — the entry-lookup misses, and the call returns `nil` without trace emission. A second `unsubscribe` from the same path (cleanup hook + `finally` block both running) is safe.
- **Missing frame is not an error.** `unsubscribe` against a destroyed or never-created frame returns `nil` and emits **no** trace — the frame-lookup misses and the call short-circuits before reaching the cache, exactly like the idempotent-past-zero no-op above; it does NOT throw. (Unlike `subscribe`/`subscribe-once`, a bare `unsubscribe` does not emit `:rf.error/frame-destroyed` — it is a release, not a read, so a teardown-ordering race that releases a slot in an already-destroyed frame is silently safe.)
- **Frame-resolution.** The 1-arg form resolves the current frame via the resolution chain (dynamic-var tier, React-context tier when an adapter has registered the `:adapter/current-frame` late-bind hook per [§Frame-provider via React context](#frame-provider-via-react-context)). There is **no `:rf/default` fallback**: with no scope established the resolution raises `:rf.error/no-frame-context`. The 2-arg form is explicit.

**Composability with `subscribe-once`.** `subscribe-once` internally invokes `subscribe` then `unsubscribe` — the teardown is synchronous on the 1 → 0 transition (per the unified disposal contract above). The user does NOT call `unsubscribe` for a `subscribe-once` call — the pairing is internal. Users only call `unsubscribe` for the `subscribe` calls they made themselves.

**Why explicit teardown exists alongside auto-disposal.** The reactive lifecycle handles the *automatic* case: a view unmounts, the reaction disposes, the underlying `unsubscribe` fires from the reaction's on-dispose hook, the slot drains in-tick. Explicit `unsubscribe` is the imperative-callers' equivalent: tools, REPL sessions, and tests that took out a subscription without an enclosing reaction lifecycle to manage it. Both paths funnel into the same ref-count decrement and the same synchronous-on-zero dispose — one disposal algorithm, two arrival surfaces.

### Lifetime contract — frame disposal

When a frame is destroyed (per [002 §Destroy](002-Frames.md#destroy)):

```
On destroy-frame! F:
  For each k → entry in F.sub-cache:
    dispose(entry.reaction)        ;; runs the container's on-dispose hook
  F.sub-cache.clear()
  trace! :sub-cache/cleared {:frame F.id}
```

Three contract guarantees this enforces:

1. **No leaks.** Every cached substrate-specific resource (Reagent reaction; JS-cross-compile-port atom-shape's listener entry / derived-value memo) is released. Long-lived processes that create and destroy frames (test runs, SSR request handling) reach steady-state memory.
2. **No stale reads.** After `destroy-frame!`, attempts to subscribe to `F` raise `:rf.error/frame-destroyed`. There is no path that returns a value from a destroyed frame's cache.
3. **Adapter symmetry.** The adapter's `dispose-adapter!` ([§Adapter disposal lifecycle](#adapter-disposal-lifecycle)) is the per-process counterpart; it disposes every frame's sub-cache as part of process teardown.

### Cross-spec interactions

- **Drain-loop integration** ([002 §Drain-loop pseudocode](002-Frames.md#drain-loop-pseudocode)): invalidation fires once per `process-event!`, at the single deferred `:db` install (step 2) — the flow transform has already rewritten the pending `:db` effect as the outermost `:after` (step 1, per [013 §Drain integration](013-Flows.md#drain-integration)), so the value installed is the flow-augmented db. There is exactly one invalidation per event, at that install, and subscriptions observe the **flow-augmented** db on recompute. A handler can rely on subscriptions reflecting the new `app-db` from inside `do-fx` (the `:fx` walk at step 3, after the install).
- **Hot reload** ([001-Registration](001-Registration.md)): re-registering a sub disposes the cache slot for that query (regardless of ref-count); next subscribe rebuilds with the new body. Tracked with the rest of hot-reload semantics in the bead-tracked work.
- **Machine subscriptions** ([005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub)): a machine's snapshot lives in **runtime-db** at `[:rf.runtime/machines :snapshots <id>]` and is read like any other slice of the runtime-db projection; the framework-registered `:rf/machine` sub is a thin convenience over `reg-sub` that reads the runtime-db projection rather than the app-db projection. Sub-cache invalidation works the same — a machine snapshot change is a runtime-db commit, which propagates to framework subs only (per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections)).
- **`clear-sub` is a registry-only operation**: `(clear-sub id)` and `(clear-sub)` remove `:sub` registrations but leave already-materialised per-frame cache slots in place. Caching is governed by the disposal contract above (synchronous ref-counting on derefer-count → 0, hot-reload eviction, frame-destroy eviction); cache eviction independent of those triggers is `clear-sub-cache!`'s job. This split preserves v1's documented contract — see the `clear-sub` docstring's note: "Depending on the usecase, it may be necessary to call `clear-sub-cache!` afterwards."

### Per-host implementation notes

- **CLJS-Reagent.** Reagent's `Reaction` handles invalidation, ref-counting, and disposal automatically. Layer-1 reads via `r/atom` deref; layer-2+ build a graph of reactions; equality checks happen at each layer. The cache wraps Reagent's own machinery — see [§Sub-cache wiring (Reagent realisation)](#sub-cache-wiring-reagent-realisation).
- **CLJS-headless / SSR.** No caching. `compute-sub` is a pure function that runs the sub's body fresh every time it's called. Cheap because no SSR run does it twice. The contract above is satisfied trivially: no cached values means no invalidation question.
- **In-scope JS-cross-compile-language ports (TS-React / Fable / Scala.js / PureScript / Kotlin/JS / Melange / ReScript / Reason / Squint).** Must satisfy the algorithm above explicitly — the per-port adapter implements layer-1/2/3 invalidation atop its host's React binding. The atom-shape's watch/listener machinery and any derived-value memoisation cooperate with React's `useSyncExternalStore`-driven render scheduling to surface invalidation to views. Tools relying on the trace stream's `:sub/recomputed` events depend on the equality-check-on-invalidation rule.

## The internal observation port (adapter-internal)

> **Status: normative.** Semantics frozen per R-2 (2026-07-11); shapes settled by spike
> S-3 (2026-07-11) and ruled binding (2026-07-12); the four `[S2-CONFIRM]` items were
> resolved by the S2a reference implementation (2026-07-12) — three confirmed, one
> corrected (the cold-probe sub-body-throw rule; see the error-contract section below).
> This port is INTERNAL — it is NOT part of the public adapter API contract; see the
> scope statement below.

The Freehand view substrate (`re-frame.freehand`) reads subscriptions through a
six-operation **observation port** rather than through the reactive `subscribe`/deref path
the adapter-backed view layers use. The port exists because concurrent React separates
*rendering* (which may run, restart, or be abandoned) from *committing* (which alone may own resources):
the port splits "read a subscription's value" (render-safe, ownership-free) from "own a
subscription node" (commit-only), so the sub-cache's ref-counting and synchronous
disposal contract ([§Reference counting and disposal](#reference-counting-and-disposal))
is never driven from a speculative render (invariants I-1/I-2).

### Scope — outside the closed public adapter contract, one named consumer

The port is **adapter-internal**: a private surface between the core's sub-cache and the
Freehand substrate's view runtime — the [atomic shell](#the-freehand-atomic-shell), whose
commit law is specified below. It is **not** an entry in the adapter spec map. The public
adapter API contract remains exactly as [§The adapter API contract](#the-adapter-api-contract)
states it — six required functions, three optional functions, one lifecycle function,
plus the `:kind` discriminator (the 11-key adapter spec map) — **closed for v1**.
Existing adapters (Reagent, reagent-slim, UIx, plain-atom, SSR) implement none of the
port's operations and are unchanged by this section. No feature predicate is added; a consumer cannot branch on
the port's presence because the port is not consumable.

**The seam, named.** The port's concrete surface is the namespace
**`re-frame.substrate.observation`** in the core artifact (`day8/re-frame2`), a sibling
of the existing `re-frame.substrate.*` internals. Its **consumer** is the
**`day8/re-frame2-freehand`** artifact's view runtime. The `day8/re-frame2-ui` donor
runtime also reads the same port; that is migration state, not a second contract, and it
outlives the programme that created it. The seam is versioned
by two rules (the second follows from the lockstep release train recorded in
[EP-0036 §Product topology](../docs/EP/EP-0036-the-freehand-view-substrate-programme.md#product-topology)):

1. **Lockstep release train (R-6).** The core and the view-substrate artifact release
   together; the port may change shape between releases without deprecation ceremony
   because no third party may consume it.
2. **Explicit ABI guard.** `re-frame.substrate.observation` exports an integer
   **`port-abi-version`**; the consuming view runtime records the version it compiled
   against and asserts it at load, failing loudly on skew with
   `:rf.error/observation-port-version-mismatch` (always-on; catalogued per
   [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)).
   Artifact drift is a boot error, never undefined behaviour.

### Observation targets — stable identity, never evidence

During render, each executed subscription site resolves a first-class **observation
target** via `resolve-target` — the **only** resolution point: ambient frame, explicit
frame pins, and the Story override context all resolve there, and no later phase
re-resolves context. A target is a **stable identity**; it carries **no node handle and
no `:value`/`:version`** for the `:subscription` kind.

```clojure
{:kind :subscription  :frame-id :app  :query [:cart/total]}
;; stabilized: the prior query object is reused while args are rf=

{:kind :story-override :query [:cart/total] :value 99   ; the pinned value IS
 :override-id <opaque> :version 7}                      ; the resolution
```

- A `:subscription` target names a sub-cache node **by identity** — `(frame, query)` —
  in a named frame. It deliberately does NOT capture the node: under hot reload the
  node resolved at render can be disposed by commit time, so `acquire!` re-resolves the
  canonical node by identity at acquire. A captured handle could pin a disposed node;
  an identity re-resolved at acquire cannot.
- A `:story-override` target names a pinned value resolved from the Story override
  context ([§The sub-override subscribe seam](#the-sub-override-subscribe-seam-debug-gated)).
  The pinned value rides the target because the value IS the resolution — there is no
  node to re-resolve. Resolution happens **once, at render**; the captured target —
  never a re-resolution — is what commit acquires (load-bearing: commit must not
  consult context again). An override target acquires no derivation handle and reports
  **`:owned? false`** honestly; override changes are a typed render cause; sub
  output-schema validation still applies to override values.

The `site-ctx` carrier — how a compiled site presents ambient frame, pins, and the
override context to `resolve-target` — is host-internal and not part of the port ABI.
The ABI is the target/evidence/handle value shapes plus the six operations' semantics.

### Probe evidence

`probe` is a pure, ownership-free read of a resolved target. It returns **evidence** —
what this render observed — never a handle:

```clojure
(probe target ?slice-memo)
;; => {:value <v>
;;     :node-version 42 | nil     ; nil = probed cold (no live node) — first-class
;;     :node-key k | nil
;;     :live? true|false
;;     :frame-epoch 17
;;     :registry-epoch 3}
```

Probe may read a live cached node; otherwise it computes pure against the current frame
snapshot through the slice memo (below), creating no cache entry, no watch, and no
disposal obligation. Cold probes (`:node-version nil`) are first-class: the commit
evidence comparison falls back to `rf=` on value for them.

### The six frozen invariants

These are normative (R-2). Each names the bug class it deletes.

1. **Render resolves and probes without ownership.** A render pass may resolve targets
   and probe their values; it MUST NOT increment a ref-count, register a watch or
   callback, or materialise a cache node that outlives the pass. *(Deletes:
   abandoned-render leaks; StrictMode double-render breakage; speculative publication —
   per I-1.)*
2. **Commit acquires the exact captured target.** The layout commit acquires the targets
   recorded in the committed capture — the captured *identity*, with no re-resolution of
   context (overrides, pins, ambient frame). The canonical *node* is re-resolved by
   `(frame, query)` at acquire; node identity lives only in evidence. *(Deletes:
   render→commit context tears — two lookups that could disagree; pinned disposed nodes
   under HMR; per I-2.)*
3. **Acquire before release.** On retarget or dependency change, commit acquires every
   newly-observed or retargeted target **before** releasing anything, so a shared node
   can never fall through its zero-owner disposal edge ([§Reference counting and
   disposal](#reference-counting-and-disposal)) mid-reconciliation. *(Deletes:
   zero-owner disposal churn — dispose-then-rebuild of a node both old and new sets
   use.)*
4. **Release is synchronous and idempotent.** Releasing a handle detaches ownership
   in-tick (the 1 → 0 edge disposes synchronously, per the cache contract), and a second
   release of the same handle is a no-op. *(Deletes: deferred-release windows; cleanup
   paths that double-release under error recovery.)*
5. **Moved evidence corrects before paint.** At commit, **each acquired node** — both
   the handles **retained** from the prior committed set and the newly **staged** ones —
   has its identity (`:node-key`), version, and the frame/registry epochs compared against
   the render's probe evidence; any movement in the render→commit gap — including a same-id
   frame **reincarnation** the `:node-key` axis catches when version + epochs coincide —
   advances the cell's revision and notifies, and the host corrects **before paint**. Two
   cases make that comparison load-bearing, and the second is the stronger one. On a
   **non-watchable headless host** a retained site has no value-movement watch, so this
   comparison is its *only* correction — a staged-only comparison would leave a retained
   site's headless movement caught by nothing. On a **watchable** host a newly **staged**
   site has no watch either, because `acquire!` installs the change watch *during* the
   commit that needed it — the movement the comparison exists to catch has already happened
   by the time that watch is in place. The two failures are not symmetric. A retained site
   whose commit skipped the comparison publishes stale and then self-heals one window later,
   when its earlier-installed watch fires; a **staged** site publishes stale and **nothing
   ever corrects it**, because a dependency's *first* render has no earlier channel to heal
   through. That is what a panel mounting exactly as a permission drops, a user switches, or
   a record is redacted looks like: a value wrong on arrival and wrong for as long as it is
   shown. The comparison is therefore not a headless-only concession — it is a staged site's
   only correction on **every** host, watchable ones included. Both directions are pinned by
   shipped rows: the retained case in
   `implementation/freehand/test/re_frame/freehand/shell_cljs_test.cljc`, and the staged
   case — over the real observation port and sub-cache, on a watchable browser host — in
   `implementation/freehand/test/re_frame/freehand/shell_tear_dom_cljs_test.cljs`.
   *(Deletes: painting a frame computed from stale reads; a coincident-version
   reincarnation misread as unchanged; a retained headless site that self-corrects through
   no channel; a staged site that paints stale on a dependency's first render and never
   heals.)*
6. **One notification per cell per render batch — the boundary is the host checkpoint, not
   drain quiescence and not epoch close.** A **render batch** is the pending read/render
   window that ends at the **earliest** host checkpoint to reach it — the next CLJS host
   microtask checkpoint, a host-scheduled render checkpoint a synchronous host commit can
   see, or an explicit headless/test flush ([§Render-batch
   finalization](#render-batch-finalization--the-host-checkpoint-boundary) enumerates all
   three, and states why an earlier close moves none of the four guarantees); the UI
   scheduler has no hook from router drain finalization and observes no drain boundary at
   all (rf2-vxgfnd.166). An event/frame **epoch** is a
   commit-phase + diagnostic-evidence unit (one per dequeued event — per
   [002 §Drain versus event](002-Frames.md#drain-versus-event--the-epoch-unit)); it is
   **not** a React render boundary. Source-side notification is constant work — mark the
   cell stale with target/version/epoch/cause evidence, never execute a prop-dependent
   query (per I-5) — carrying the moving epoch as **cause evidence only**. A single
   run-to-completion drain may settle several queued events, each committing its own epoch
   record; a dirty cell coalesces every epoch that marked it before the checkpoint and is
   flushed **exactly once** when the window closes (exact coalescing at the checkpoint,
   keyed on the cell's pending state — never on the epoch tag, never debounce-by-time; per
   I-3/I-6). A synchronous drain therefore cannot be split across batches, and N epochs
   settled in one drain share one batch — but the converse does not hold: several drains
   finishing before the same checkpoint may share a batch, and only a real host yield
   separates renders ([§Render-batch
   finalization](#render-batch-finalization--the-host-checkpoint-boundary) states all four
   guarantees). **No render count may be inferred from the number of event/frame epochs,
   nor from the number of drains** — "one render batch per router drain" is **retired** as
   normative and survives only as the common case, true exactly when callers yield between
   drains.
   *(Deletes: zombie children; N-notifications-per-event fan-out; the false
   N-epochs⇒N-renders equation; the false drain-quiescence render boundary.)*

### The port operations (final)

```clojure
(resolve-target site-ctx)     ; render: the ONLY resolution point → target
(probe target ?slice-memo)    ; render: pure evidence read (shape above)
(acquire! target on-change)   ; commit-only: re-resolves canonical node, +1 owner → handle
(current? handle target)       ; the commit kept-check, one predicate
(read handle)                  ; => {:value v :version n :node-key k :frame-epoch fe :registry-epoch re}; typed error after release
(release! handle)              ; synchronous, idempotent (second call no-ops)
```

Mapping onto the cache contract: `acquire!` is the ref-count attach of
[§Lookup algorithm](#lookup-algorithm) plus callback registration; `release!` is the
subscriber detach of [§Reference counting and disposal](#reference-counting-and-disposal);
`probe` is an ownership-free read with no existing public name (`subscribe-once`
attaches-and-detaches; `probe` never attaches). `resolve-target` and `current?` have no
cache-contract counterpart — they are the capture and kept-check layer a concurrent
host requires.

The movement-evidence axes are realised as: a per-node observation **version** the port
advances whenever it observes the node's value change by `rf=`; the node's process-unique
**`:node-key`** identity (the same key `probe` emits — the reincarnation-identity axis);
the frame's **commit epoch** (one bump per physical frame-state install); and a
**registry epoch** (one bump per `:sub` registration). `read` on a node handle
additionally returns the acquired node's `:node-key` and the CURRENT `:frame-epoch` /
`:registry-epoch` alongside the frozen `{:value v :version n}` keys (**additive** — the
frozen shape is unchanged), so the commit reconciler's invariant-5 comparison needs no
second probe. `:node-key` is what lets that comparison distinguish a **same-id frame
reincarnation** (`destroy-frame!` + a fresh same-id construction builds a new reaction
with a strictly-greater key) from an unmoved live node **even when version + frame /
registry epochs coincide** across the two incarnations — a version+epoch tie
`dissoc-frame!`'s commit-epoch restart can produce, which a version+epoch-only comparison
would misread as unchanged.

### Handle semantics

- **The handle IS the owner token.** Handles are opaque host objects with **identity**
  equality — never `=`. Owners are keyed by handle identity with **per-handle unique
  callbacks**, which makes the sibling-callback-clobber bug class structurally
  impossible and makes StrictMode's release/reacquire naturally balanced. ⟨S-3
  fixtures 4, 5⟩
- **`current?`** ≡ not released ∧ node not disposed ∧ same frame ∧ same stabilized
  query. It is the single commit kept-check: an unchanged live handle is **retained
  untouched**; a disposed node (HMR), a frame swap, or a restabilized query fails the
  check and classifies the site as retargeted. It is a **pure no-throw predicate** — a
  value that is not a handle reads `false`, not an error (per
  [§Error contract](#error-contract--internally-fail-loud-publicly-recover-to-nil)).
- **Read-after-release** throws typed `:rf.error/read-after-release`, always — it is a
  substrate bug, never an app error. It costs nothing: the commit path checks
  `current?` first and the render path falls back to `probe`, so the throw is
  unreachable in correct generated code. ⟨S-3 µ⟩
- **HMR node replacement.** Sub re-registration disposes the canonical node *then*
  notifies former owners once with cause `:hmr`. Two idempotence extensions carry the
  whole story: `release!` on a handle whose node was disposed out from under it is a
  no-op, and `current?` treats a disposed node as "not current", so the next render
  probes fresh and the next commit acquires the new canonical node. No cell can pin a
  disposed node. ⟨S-3 fixture 8⟩

### The static override handle

`acquire!` on a `:story-override` target returns a **static handle** — one uniform
commit path with honest ownership reporting:

- `:owned? false` — tools and instance records show the site as not owning a real
  subscription;
- `read` returns the pinned value and the override's version;
- `release!` is a no-op; **no callback is registered** (a pinned value never
  invalidates);
- `current?` holds while the site's captured override tokens still match under the
  **split equality law**, and fails when the override changed or was removed —
  retargeting through the normal staged commit path, exactly like a real node. The two
  opaque tokens are compared differently: `:override-id` is slot identity, compared by
  plain `=`; `:version` is the movement token, compared by the frozen `rf=` law (the
  port's core-local `node-value=` spelling). NaN-to-NaN therefore **retains** — the
  observable counterexample that makes the split load-bearing: a plain-`=` version
  compare would retarget a NaN-valued override on every commit, forever. Because those
  tokens are opaque and app-supplied, either comparison **may throw** through a hostile
  host `equals`/`-equiv`; `current?` is total, so a throwing token compare reads **not
  current** and the site retargets through the normal staged path rather than escaping
  the predicate — never weakening the fail-loud contract of the port's non-predicate ops.

*(Shape ruled and final; the handle semantics are pinned by the port's own fixtures, and
the Tier-3 mounted Story-context fixture landed with the ViewCell layer.)*

### Transactional multi-acquire — staging and rollback

Commit's dependency reconciliation is transactional — **binding**:

1. Every newly-observed or retargeted target is acquired **before anything is
   released** (invariant 3), and the resulting handles are **staged** — provisional,
   not yet installed.
2. **On any acquisition failure**, every newly acquired staged handle is
   **synchronously released** — in reverse acquisition order, so layered acquisitions
   unwind symmetrically (the ordering is observable only in dispose traces)
   *(confirmed, S2a: `release!` is identity-guarded and order-independent-safe, and the
   reverse-order unwind is pinned by a port fixture — shared nodes survive, solo nodes
   dispose on their zero-owner edge)* — and **the prior committed set remains
   installed**: the cell keeps its previous committed dependency set and previously
   published values, the reconcile aborts, and the acquisition's typed error
   propagates.
3. Only after every staged acquisition has succeeded does commit release the prior
   handles of dropped/retargeted sites and install retained + staged handles as the
   committed dependency set.

The first-failure case is safe by ordering alone (nothing has been released); the
k-th-failure case is safe by rollback (staged handles 1..k-1 cannot leak). Nodes shared
with the prior committed set survive rollback trivially — their prior owner is still
attached; nodes created solely by a rolled-back acquisition dispose on their zero-owner
edge, correctly. A multi-target reconcile-failure fixture at the ViewCell layer is a
named Stage-2 obligation.

### Body authority under hot reload — the two-point commit fence

Distinct from the value-movement guards (invariant 5, above), a commit also verifies the
cell's **body authority** — that the body generation the capture was rendered against is
still the cell's — so a candidate can never publish ownership on behalf of a body it did
not execute.

Authority is **one number**: the cell-local **generation**. Each stable boundary holds the
body revision its emitter currently publishes, advanced only at the reload seam — a
genuinely new body, never an ordinary re-walk of an unchanged tree — and the shell raises
the cell to that revision **before the candidate opens**. A candidate therefore carries the
revision its own render ran against, and the commit consults nothing but its own cell. The
raise is monotone, so an occurrence minted after a reload catches up on its first render
rather than walking a live cell backwards, and a direct or headless caller with no shell
above it advances the generation itself.

The capture is rejected as **`:abandoned`** when the generation has advanced past the
captured one, checked at **two points**:

1. **Render→commit (step 1).** Commit entry samples the authority once and rejects a
   stale capture before touching any ownership — the host simply re-renders.
2. **Final publication boundary.** Step 1 samples *once*, but the staging window between
   it and publication — the acquire/cache callbacks **and the commit-side evidence
   reads**, both callback-capable — can each synchronously advance the authoritative
   revision (a same-shell re-registration mid-commit). So commit **re-reads** the
   authority at the narrowest boundary — after all callback-capable work, `read`
   included, with nothing callback-capable between it and the publish swap — and refuses
   to publish a stale capture: it releases **only the newly-staged handles** (reverse
   acquisition order),
   leaves the prior committed set, published values, and lifecycle untouched, and returns
   `:abandoned`. No revision advances, because a reload publishes its new body through a
   render already in flight — a fresh candidate at that body is inbound without the cell
   asking for one.

Body authority is one half of the commit's currency check; the other is the frame
incarnation the render resolved ([§Frame binding and
retarget](#frame-binding-and-retarget)). A candidate that fails either half is abandoned
at whichever of the two points sees it first.

The body half is a development concern in effect rather than by compilation: a production
boundary is minted at revision 0 and nothing ever advances it, so the comparison always
holds and costs one integer compare. It is not gated out, because it shares a predicate
with the frame half, which is load-bearing in every build. Neither point consults a view
registry, in any build.

### Callback and reentrancy rules

Spike-validated:

- `on-change` is **constant-work**: mark-dirty with node-key/version/epoch/cause; it
  never computes (invariant 6, I-5).
- `acquire!`/`release!` called from **inside the owner-notification fan-out** throw
  `:rf.error/reentrant-graph-op` (dev-asserted). The rule is cheap because the fan-out
  is separated from the cell flush: the notification only marks cells dirty, and the
  layout commits that actually acquire and release run later, when the pending window
  closes at the next host checkpoint. They therefore run after the fan-out has
  returned and never trip the guard. Note that ownership moves in COMMITS only —
  render probes without acquiring — and that the flush is coalesced across a batch,
  so it is decoupled from the epoch count.

Conservative rules written ahead of S-3 exercise, now confirmed by the S2a
implementation:

- `acquire!` and `release!` themselves **never invoke `on-change` synchronously** — no
  fan-out during acquire/release. Acquire returns state via the handle; movement in the
  render→commit gap is the commit evidence comparison's job (invariant 5), not a
  callback's. *(Confirmed, S2a — watch registration never fires synchronously and the
  release path removes the watch before the decrement; fixture-pinned.)*
- **HMR-disposal notifications queue.** The dispose-then-notify-once-with-cause-`:hmr`
  ordering IS S-3-validated; the delivery turn is: the notification rides the same
  constant-work mark-dirty path, queued at dispose, and is flushed at the notification
  boundary the re-registration closes — coalesced once per handle, never delivered
  mid-registry-mutation. *(Confirmed, S2a — the queue drains at the port's registrar
  replacement hook, which by require order runs strictly after the cache invalidation
  hook, i.e. after the registry mutation and cache eviction complete; non-registrar
  disposal paths — frame destroy, explicit cache clears — drain on the next tick with
  cause `:disposed`.)*

### Error contract — internally fail-loud, publicly recover-to-nil

The port and the public read API split deliberately — **binding**:

- **The port is fail-loud everywhere except its two predicates.** Every port operation
  that can fail throws typed, with one deliberate exception: the kept-check predicates
  **`current?` and `owned?` are total and never throw**. Handed a released handle, a
  disposed node, or a value that is not a handle at all, each returns `false` rather than
  field-accessing the handle state and leaking a raw host error (a JVM `NullPointerException`,
  a CLJS `TypeError`) — a value that is not a live node handle is simply not current, and
  owns no node. `current?` is total across its comparisons too: the tokens it weighs —
  a static override's opaque `:override-id`/`:version`, a subscription query's app args —
  are **app-supplied, so their host equality may throw**; a comparison that cannot
  **establish** sameness classifies the site as **not current** (the conservative
  kept-check result — an unprovable site retargets through the normal staged commit path,
  never painted stale), so the throw never escapes the predicate. They are the commit
  path's cheap guard, so they answer rather than fail. Every other operation names its
  typed rejection:
  - `:rf.error/no-such-sub` — the target's own query names an unregistered sub, at
    `probe` or `acquire!`. This is the **same catalogue id** the public surface records
    ([§What happens when a sub references an unknown sub](#what-happens-when-a-sub-references-an-unknown-sub));
    the spike's `:rf.error/no-sub` spelling is **superseded and must not survive
    anywhere** — one condition, one catalogue id, two emit surfaces.
  - `:rf.error/frame-destroyed` — `probe`/`acquire!` against a destroyed frame. Again
    the existing always-on catalogue id; its 009 row carries the port's **throwing**
    emit surface (public recovery column unchanged).
  - `:rf.error/observation-malformed-target` — a target violating the port's closed
    grammar, at `probe` or `acquire!`. `resolve-target` throws the same id for a
    malformed query-vector, validating the query's shape before it inspects the query
    or mints a target: the port's only resolution point cannot hand back a target its
    own consumers would reject.
  - `:rf.error/observation-malformed-handle` — a value that is not an `ObservationHandle`,
    at `read` or `release!` — the two operations that deref the handle state. This is the
    handle-side sibling of the malformed-target category, and the boundary the predicates
    above are exempt from.
  - `:rf.error/read-after-release` (always), `:rf.error/reentrant-graph-op` (dev),
    `:rf.error/observation-retry-exhausted` (`acquire!` exhausting its bounded
    displacement-retry budget against a verifiably live frame), and
    `:rf.error/observation-port-version-mismatch` (the ABI load guard).

  The two `observation-malformed-*` categories are **diagnostic-channel only**: a
  malformed target or handle is a substrate bug unreachable in correct generated code,
  not a production condition an off-box shipper acts on, so neither fans the always-on
  error-emit axis the way the entry-condition categories do. Both carry bounded,
  normalized structural evidence — a kind class, a key count, an offending host type —
  never the field values. Every id above is catalogued with its exact throwing
  operations and evidence per
  [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).
- **The public API is untouched.** `subscribe` and `subscribe-once` keep their
  checked-in recovery-to-`nil` semantics (`:replaced-with-default`) for unknown subs
  and destroyed frames — nothing in this section changes
  [§`subscribe-once`](#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value)
  or the unknown-sub section.
- **Why the split is safe.** The port's callers are generated commit/render machinery,
  not app code; transactional staging (above) makes every acquire failure
  non-corrupting, and the ViewCell maps port throws to the view error boundary (the
  Spec 004 rewrite's surface). Loud-at-the-seam plus recover-at-the-public-surface
  keeps one catalogue and two honest behaviours.
- **In-graph input resolution is unchanged.** The fail-loud rule governs the port's
  *entry point* (the target's own query). A sub **body's** `:<-` reference to an
  unregistered input keeps the graph's own documented behaviour — one
  `:rf.error/no-such-sub` error event, `nil` substituted, body still runs —
  identically under `probe` (including cold probes) and under public `subscribe`.
  *(Cold-probe edge set confirmed/corrected, S2a: unknown input mid-graph emits the one
  always-on error event and substitutes nil, identically cold and live; a `:<-` cycle
  recovers via the structured `:rf.error/sub-cycle`, identically cold and live; a sub
  body that throws during a probe follows the graph's own documented recovery —
  `:rf.error/sub-exception` emitted, `nil` substituted — identically cold and live.
  The earlier draft's "a body throw during a probe propagates" is CORRECTED: a live
  probe reads through the reactive memo, which already recovers body throws to nil, so
  propagating only on cold probes would make probe temperature observable — cold probes
  are first-class, so both temperatures recover identically. Port-entry conditions
  remain fail-loud.)*
- **`acquire!` fails loud when the ENTRY node's own build cannot cache.** `acquire!` IS
  the ref-count **attach** ([§The port operations](#the-port-operations-final)) — it must return a handle over a REAL cached node holding a
  real reference. Three build outcomes hand back a **non-nil but never-cached, zero-ref
  recovery reaction** instead of a canonical node: a **cyclic entry sub** (the target's
  own query sits on a `:<-` cycle, so the build recovers to a nil-yielding reaction that
  is deliberately NOT cached — [§Subscription cache](#subscription-cache--contract-and-operational-semantics)), a **parametric `input-fn` failure** (the entry sub's
  `input-fn` threw or returned a value outside the input grammar — [§Subscription input
  producers](#subscription-input-producers--app-db-reader-static-parametric-input-fn)), and a **frame destroyed mid-build** (the frame's cache vanished between the
  port's liveness check and the build's cache-install step — the JVM race). In every
  case there is **no node to own**, so `acquire!` is **fail-loud** and throws the typed
  error mirroring the condition rather than handle a reaction that owns nothing:
  `:rf.error/sub-cycle` (cyclic entry sub), `:rf.error/sub-input-fn-exception` /
  `:rf.error/sub-input-fn-bad-return` (parametric failure), `:rf.error/frame-destroyed`
  (mid-build destroy race — the same catalogue id and throwing surface a
  destroyed-frame entry already uses). **The invariant is binding: a handle MUST NOT
  report `owned?` true without a real cache ref + attach** — a handle that claims
  ownership of an uncached zero-ref reaction is `current? false` from birth, so every
  commit retargets and rebuilds a fresh orphan + node record + disposal hook and
  re-emits — structural churn instead of one honest typed throw. (rf2-vxgfnd.27.)
  - **Emit discipline — one always-on record, never a duplicate.** The parametric
    categories already fan their always-on record from the build ([009 §Error event
    catalogue](009-Instrumentation.md#error-event-catalogue)), so the port re-throws the same id **without** a second fan.
    `:rf.error/frame-destroyed` fans + throws through the port's existing throwing
    surface (the build emits nothing for the race). `:rf.error/sub-cycle` **stays
    diagnostic** (its 009 channel is unchanged — it is emitted on the dev trace channel
    by the build); the port throws the typed carrier to the ViewCell error boundary but
    does **not** promote sub-cycle to the always-on axis.
  - **A live-cache DISPLACEMENT is not a destruction (rf2-vxgfnd.63).** The build's
    canonical-node re-check can also fail while the frame is **live**: a just-built
    canonical node **invalidated-and-rebuilt** to a newer node — an HMR sub
    re-registration or an explicit cache clear landing in the build→check window — leaves
    the built reaction non-canonical with the frame record untouched. That is a normal
    **displacement**, not a teardown, so `:rf.error/frame-destroyed` is **reserved for a
    verified destruction of the targeted frame incarnation**. `acquire!` disambiguates
    against the targeted frame's **incarnation token** (captured while the frame is
    verified live): on a still-live incarnation it **retargets** to the current canonical
    node by re-running the acquire — a **bounded** retry gated on the incarnation staying
    live, so it converges on a canonical current handle (no false frame-destroyed, no
    leaked displaced reaction) and cannot spin forever under repeated HMR; only a
    nil/changed incarnation is the mid-build destroy race that fans + throws
    `:rf.error/frame-destroyed`. The retry preserves the no-synchronous-`on-change`
    acquisition rule (it re-runs the acquire, never a callback).
  - **Retry exhaustion is a livelock, not a destruction (rf2-vxgfnd.79).** If the
    bounded budget is **exhausted while the targeted incarnation is still
    verifiably live** — a pathological-but-legal displacement storm (repeated HMR
    re-registrations / cache clears) winning **every** build→check window — `acquire!`
    MUST NOT reuse the `:frame-destroyed` classification: it has just PROVED the frame
    alive, so emitting `:rf.error/frame-destroyed` would tell an implementer / Xray
    user to recover a frame that was never destroyed. Instead it throws the distinct,
    truthful `:rf.error/observation-retry-exhausted` ([009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) — an acquire-path **livelock** carrying the
    frame, query, same-incarnation-live evidence, and the attempt count, fanned on the
    always-on axis before the throw (like `:rf.error/frame-destroyed`) and mapped by the
    ViewCell to the view error boundary. `:rf.error/frame-destroyed` stays **reserved for a
    verified destruction of the targeted incarnation** on every acquire path.
  - **This is the entry-node line, not the mid-graph line.** The bullet above governs a
    sub **body's** `:<-` reference to a failing/cyclic INPUT: that recovers to nil and
    the ENTRY node still caches normally, so `acquire!` takes ownership of the real
    entry node (a nested recovery never blocks the attach). The fail-loud rule here
    governs only the case where the **entry node itself** cannot be cached.
  - **`acquire!` and `probe` diverge here by design, exactly as they do for a
    destroyed frame.** `probe` recovers a cyclic / parametric-failed entry query to a
    pure evidence `nil` (a legitimate value to render, identical cold and live — the
    bullet above), because a probe produces a *value*. `acquire!` must attach a
    *reference to a node that structurally cannot exist*, so it cannot recover — it
    fails loud, and the ViewCell maps the throw to the view error boundary
    (loud-at-the-seam; the public `subscribe` surface keeps its recover-to-nil
    semantics unchanged). This is the same probe-recovers / acquire-throws split the
    port already draws for `:rf.error/frame-destroyed`.

### Disposal-notification callback failures — containment, exact-once surfacing, channel-aware classification

The two failure surfaces above (`probe`/`acquire!`/`read` port entry points, and the
`acquire!` entry-node build) are **synchronous** — the caller is generated commit/render
machinery and the throw reaches the ViewCell error boundary. One further callback
surface is **asynchronous and swallowed**: the former-owner `on-change` callbacks the
port fires while draining queued HMR / disposal notifications
(`drain-pending-disposals!`, per [§Callback and reentrancy rules](#callback-and-reentrancy-rules)). An `on-change` here is a
`day8/re-frame2-ui` ViewCell mark-dirty; if it throws, the failure is a re-frame.ui
consumer defect. This clause is **binding** (rf2-6ui49w + rf2-wbkjk9 + rf2-q3fmqm +
rf2-w55bh0):

- **Containment (full sibling drain).** Each queued handle's notification runs inside its
  **own** `try/catch`, so one owner's throwing `on-change` never starves its siblings —
  every still-live handle in the drain is notified. This mirrors the registrar's per-hook
  and the sub-cache's per-reaction dispose containment; it closes the one uncontained
  fan-out (rf2-vxgfnd.28).
- **Exact-once surfacing past a swallowing boundary.** Both real drain boundaries
  **discard** the propagated throw: the `:hmr` drain runs inside the registrar's
  replacement hook, whose per-hook `try/catch` drops it, and the `:disposed` drain rides
  an `interop/next-tick` Future whose result is never inspected. So every escape is
  **surfaced exactly once** (Spec 009's one-runtime-error law) *before* the boundary
  swallows it — correctness never depends on the rethrow being observed. The surfaced
  record IS the visibility.
- **First-escape propagation.** After the whole drain completes, the **first** escape is
  re-thrown for any **direct** caller, with its identity and cause intact — but, per the
  point above, the framework's observability guarantee never rests on that rethrow
  reaching anyone.
- **Channel-aware, opaque provenance classification.** The drain owns **production
  (always-on) coverage** for the callback failure **unless** the escape's OPAQUE,
  channel-aware provenance proves the source already fanned an **always-on** record. The
  decision is by non-forgeable provenance token — never a channel-blind `fanned` Boolean,
  never `:rf.error/id` truthiness or a reconstructible ex-data shape, never a global
  seen-error registry (rf2-w55bh0):
  - **Already covered on the always-on axis** — the port's own emit-then-throw surfaces
    that fanned through `emit-error-both!` (`read` on a released handle, the fail-loud
    probe/acquire throws, the ABI guard, the retry-exhausted throw, the acquire-recovery
    input-fn arms). Their record IS the exactly-once emission and carries the **source's**
    correct attribution, so the drain adds **nothing** on either channel — no
    double-report, no attribution overwrite.
  - **Not covered on the always-on axis** — a source that emitted **only** on the
    diagnostic trace axis (the production-elided `:rf.error/sub-cycle`), a diagnostic-only
    thrown category with no fan of its own (`:rf.error/observation-malformed-target` /
    `…-malformed-handle` / the dev `:rf.error/reentrant-graph-op` assert), a raw untyped
    consumer bug (`TypeError` / `AssertionError` / host `RuntimeException`), or an
    application ex-info **spoofing** a framework category — all read FALSE. Production
    observability is still owed, so the drain adds **exactly one** stable catalogued
    `:rf.error/observation-on-change-failed` record, carrying the original throwable as
    the record's `:exception` cause. The escape's own diagnostic category is **never**
    promoted onto the always-on axis; its detail rides as the wrapper's cause.
- **Two-channel fan-out.** The drain-owned wrapper rides the shared two-channel fan-out
  (`emit-error-both!`, rf2-q3fmqm): the always-on record for off-box shippers PLUS the
  dev diagnostic-trace event Xray's trace tooling consumes (without which a swallowed
  HMR/disposal callback failure was invisible in the primary debugging surface). The
  category-specific trace tags carry the disposal `:cause` (`:hmr` / `:disposed`), the
  former owner's entry-sub coordinates (`:rf.sub/id` / `:rf.sub/query-v`), and the
  original throwable.
- **Source attribution.** The record's `:event-id` is the former owner's **entry sub
  id**, and `error-emit` classifies the wrapper category **subscription-owned**, so its
  `:source-coord` resolves under `[:sub id]`: a macro-registered sub yields its exact
  coordinate, a programmatic one omits the slot, and a same-id **event** registration
  cannot steal the attribution.
- **HMR / disposed parity.** Containment, exact-once surfacing, provenance classification,
  and two-channel fan-out are **identical** at both boundaries; only `:cause` differs
  (`:hmr` vs `:disposed`).
- **Advanced-production channel behavior.** Under `:advanced` + `goog.DEBUG=false` the
  dev diagnostic-trace leg is DCE'd inside `trace/emit-error!` while the always-on record
  survives — **exactly one** always-on record and **zero** diagnostic trace events. The
  contained sibling drain and the direct-caller first-escape rethrow are unchanged in
  production.

The category's channel is `always-on` and its per-category `:tags` payload has a
canonical schema — [Spec-Schemas §`ObservationOnChangeFailedTags`](Spec-Schemas.md#per-category-tags-schemas) — matching the runtime record fanned at both boundaries. See
[009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (`:rf.error/observation-on-change-failed`).

### The slice-scoped probe memo

Probes are ownership-free, so N sibling sites probing the same query during one render
pass (first-mount fan-out: N rows probing `[:orders/by-id id]`) would recompute shared
derivation parents N times. The port permits one mitigation: a **slice-scoped pure memo
table** — the optional `?slice-memo` argument to `probe`. Within one slice, probes
share computed derivation parents; the table dies with the slice. No entry survives
into cache state, ownership state, or a later slice.

**Lifetime (S-3-settled).** There is no public React render-pass token, so how far a
table's sharing reaches — the *slice* it belongs to — is bounded **per-host**, because the
two runtimes reach the same law by genuinely different scheduling models. The table is
created lazily on first probe and belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` plus the exact frame incarnation, invalidated on
any mismatch.

- **JVM — a thread-local render scope; sharing ends with its synchronous render thunk.** A
  private dynamic binding (`*slice*`, opened by `with-slice-memo`) wraps every public
  Tier-1 render entry: `re-frame.ui.tree/render`, the `ui.test/render` routes
  (view-reference / literal / plan-bearing, all converging on `render-with-opts` /
  `render-plan-bearing`), and the reactive host entry (one scope per ViewCell render). The
  binding **discards the table synchronously when the render thunk returns** — there is no
  microtask on the JVM, and no scheduler- or tag-change dependency. The scope is
  re-entrant, so a Tier-1 render and the ViewCell capture nested inside it share one table,
  while a bare probe outside any render scope gets a fresh per-call handle; two sequential
  executor tasks and two concurrent renders on distinct threads never share a table, and a
  render that begins after the thunk returns opens a fresh scope.
- **CLJS — a module holder released at the microtask checkpoint; sharing MAY span later
  callbacks within the bounded host-microtask window.** The single-threaded host needs no
  thread-local scope: one module holder shares the handle across every probe until it is
  **released at the host microtask checkpoint** (`queueMicrotask`, under a CAS guard so a
  stale clear cannot erase a newer holder, aligned with the port's own table clear —
  deliberately not the macrotask `next-tick` path, which would leave a dead slice's holder
  live for one more host turn). The whole host-microtask window is therefore one CLJS
  slice: because `queueMicrotask` is FIFO, a genuinely-later render in a microtask
  interposed before that checkpoint finds the holder still installed and reuses it — a
  bounded within-window economy, never a leak — and a caught-render retry, being
  synchronous and pre-checkpoint, collapses to the same within-window sharing. No holder or
  table survives past the checkpoint into the next window (⟨rf2-2g7pxq⟩ pins the
  inverse-FIFO ordering deterministically).

Both hosts invalidate the table on any tag mismatch, and both enforce the same law: probes
may share within one slice — the JVM's synchronous render thunk, the CLJS host-microtask
window — but **no holder or table survives past that boundary into the next slice**.
Bounded reuse is never stale-value authority: an interposed later render at a moved epoch
fails the tag check and mints a fresh table rather than serving the stale memoized value. A
time-sliced pass spanning k slices builds k tables, so the economy is **once-per-slice, not
once-per-pass** — bounded, allocation-trivial, and requiring zero React internals; an
interrupted or abandoned slice's table becomes unreachable garbage.

**The memo is an economy, never an authority.** A stale memoized value that survives
into a committed capture is harmless because the **two-guard rule** already covers it:
(1) React's own snapshot re-check catches mid-pass movement of *watched* sites; (2) the
commit reconciler's evidence comparison (invariant 5) catches movement of every
*acquired* site — retained as well as newly-observed — by comparing acquired versions
against probe evidence and correcting before paint (the retained arm is what covers a
non-watchable headless site, which guard (1) never reaches). No third mechanism exists
or is needed. A memo table that outlives its slice is a conformance bug (a leak fixture
pins it).

### Render-batch finalization — the host-checkpoint boundary

On the observation-port substrate, the invalidation algorithm's Phase 3
([§Invalidation algorithm](#invalidation-algorithm) — "notify subscribers") is realised
as constant-work stale-marking (invariant 6). The commit sequence gains an
**adapter-internal final phase — the host-checkpoint render batch**. A **render batch** is
the pending read/render window that ends at the **earliest** host checkpoint to reach it:
the next CLJS host microtask checkpoint, a host-scheduled render checkpoint a synchronous
host commit can see ([§The second closer](#the-second-closer--a-synchronous-host-commit-must-be-able-to-close-the-window)),
or an explicit headless/test flush. The window is armed by the **first** dirty mark, not by
the start of a drain, and it closes at the **host's** checkpoint, not at the end of a
drain: this scheduler has no hook from router drain finalization and observes no drain
boundary at all (rf2-vxgfnd.166). A run-to-completion drain may settle several queued
events, each settling its derivations (Phases 1–2) and marking dirty cells (Phase 3) as it
commits its **own** epoch record; when the window closes, each dirty ViewCell is flushed
**once** into the host scheduler and React performs **one read/render batch** over every
epoch that marked it. The moving epoch rides the stale-mark as **cause evidence only** —
coalescing keys on the cell's pending state, never on the epoch tag.

Four guarantees follow, and they are the whole contract:

1. A synchronous run-to-completion drain **cannot** be split across batches — the window
   cannot close while the stack is still unwinding.
2. N epochs settled within **one** drain coalesce into **one** batch.
3. Several drains — or a listener re-entering after a completed batch — that finish before
   the **same** host checkpoint **may share** one batch. Two back-to-back `dispatch-sync!`
   calls in one JavaScript stack render once, not twice; so do nested cross-frame
   synchronous drains.
4. Drains separated by a real **host yield** render separately.

"One render batch per router drain" is **retired** as normative. It remains the common
case — true exactly when callers yield between drains (guarantee 4) — and it is not a rule
this scheduler enforces, or could enforce without a drain-finalization seam that
deliberately does not exist. Render separation follows host checkpoints, never the epoch
count and never the drain count (see invariant 6).

On CLJS the flush rides a **true host microtask** — `js/queueMicrotask` (a resolved-`Promise`
job where absent), deliberately **not** `goog.async.nextTick`, which is a **macrotask**
(`setImmediate`/`MessageChannel`/`setTimeout`) that yields to the event loop and could let
a torn frame paint before the correction runs. A single microtask, armed by the first
mark of the window, cannot run until the synchronous stack unwinds, so it fires strictly
**after** that stack completes — at the event loop's microtask checkpoint, which runs
**before** the next paint — never between two queued events of the same drain, and always
before a torn frame can show (rf2-vxgfnd.40). That microtask is the window's **guaranteed**
closer, and outside a synchronous host flush it is still the only one that ever runs;
[§The second closer](#the-second-closer--a-synchronous-host-commit-must-be-able-to-close-the-window)
below covers the one that runs inside one. The headless (JVM/SSR) host has no async
render loop and drains through the explicit test flush. On CLJS,
`ui.test/flush!` returns a Promise; its optional thunk runs inside direct React 19
`act`, then framework drains and React commits alternate until both are quiescent. On
the JVM the zero-arity flush is synchronous and returns nil. It is the sole public test
flush and has no public `re-frame.ui` twin. A call while an event drain is still open
throws `:rf.error/flush-in-open-epoch` synchronously before Promise construction,
notifications, or host work, carrying the active `:frame` and `:frame-epoch` (per
[009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). That guard
rejects a misuse of the **explicit** flush; it is not the automatic scheduling boundary,
which consults no drain state at all. The
adapter's distinct production/tooling `flush-render!` contract is unchanged.

#### The second closer — a synchronous host commit must be able to close the window

A dirty mark is **constant work**, and it schedules no host render work at all: it records
the cause, enrols the cell in the open pending window, and arms the microtask. Nothing a
React scheduler can see has happened. `react-dom/flushSync` returns as soon as the work
scheduled *inside its callback* has committed — so it committed nothing, reported nothing,
and a state write made inside it left the DOM showing the old value. The caller who reaches
for a synchronous flush is exactly the caller about to measure layout, move focus, set a
caret, or hand off to imperative third-party code, and that caller got a stale page in
silence (rf2-w2m25).

**The microtask is not abolished, and must not be.** Making a mark notify synchronously
would recompute and re-render inside the source write — the trap invariant 6 exists to
forbid — and would dissolve the coalescing every batch here rests on. The remedy is a
**second closer**, not a different first one.

On a host that owns a render scheduler the window is therefore armed with **two** closers,
both by the same first mark, both running the **same idempotent flush**. Whichever the host
runs first closes the window; the other finds it empty and does nothing. The third item in
the enumeration above — the explicit headless/test flush — is a caller's door rather than a
scheduled checkpoint, and nothing here touches it.

1. **The host microtask** — the guaranteed closer, armed first, unchanged, and still the
   owner of the ordinary batch.
2. **The host render checkpoint** — a render the host's own scheduler performs, whose
   *synchronous* commit phase closes the window. The CLJS realisation
   (`re-frame.freehand.checkpoint`) renders one nil-returning sentinel into a detached
   React root and closes the window from a **layout** effect: `flushSync` always runs
   layout effects synchronously, which is a documented React guarantee, whereas its
   flushing of *passive* effects is a React-19 implementation detail and not one.

Lane behaviour does the arbitration, which is what lets two closers coexist without a rule
to order them. **Inside a synchronous flush** the sentinel's update is issued during the
callback and takes the synchronous lane, so the host renders it, runs its layout effect,
closes the window, and flushes the cells' resulting updates before the flush returns — the
DOM is current on the next line. **Outside one** that update takes an ordinary lane and the
host reaches it later, by which time the microtask closer — armed first, and therefore
first out of a FIFO queue — has already closed the window and the sentinel's effect finds
nothing pending. Batching outside a synchronous flush is bit-for-bit what it was.

Two ordering rules make that safe rather than merely true, and both are normative:

- The guaranteed microtask closer is armed **first**, and the host closer is
  **best-effort**, armed inside a guard. A mark is a notification sink with no caller to
  report a scheduling failure to, so a host closer that throws must never cost the window
  its guaranteed one.
- A host with no render scheduler installs **no** second closer and is unaffected. The
  headless (JVM/SSR) host is that case: it closes its window explicitly, exactly as before.

None of the four guarantees moves. Neither closer can run while the marking stack is still
unwinding — both are armed by the mark, and a synchronous flush runs its callback to
completion before it flushes anything — so a run-to-completion drain still cannot be split
across batches (guarantee 1) and N epochs settled in one drain still coalesce into one
batch (guarantee 2). Guarantee 3 is **permissive**: drains finishing before the same host
checkpoint *may* share a batch, so an earlier close spends latitude the guarantee already
grants rather than breaching it. Guarantee 4 is untouched — drains separated by a real host
yield still render separately. What changed is only *which* checkpoint may close a window a
**synchronous commit** is waiting on.

The consumer-facing consequence is the whole point of the mechanism: **a state write made
inside a synchronous host flush is committed to the DOM before that flush returns.** The
next line may measure layout, move focus, set a caret, or assert on the DOM, and read the
value it just wrote.

## The Freehand atomic shell

> **Status: normative.** The port's one consumer, specified here because the commit law
> IS the port's usage contract: everything the port refuses to do during a render, the
> shell is what does at commit. Freehand's declaration, authoring, and semantic surface is
> owned by [Spec 004](004-Views.md); this section owns only the reactive commit.

A **mounted boundary occurrence** owns one **cell**. A render produces a **candidate** —
a value the renderer holds — and the candidate is the unit of everything a render might
later publish.

### The selected render bundle

A render against one exact frame incarnation produces a candidate bundle:

```clojure
{:body-revision      revision
 :frame-incarnation  frame
 :dependencies       candidate-dependencies
 :events             candidate-event-sites
 :evidence           candidate-evidence}
```

The bundle is published **entirely or not at all**, and only when the render is SELECTED
for commit. Publication is one synchronous step with no intervening host yield, so no
observer can see a boundary whose dependencies came from one render and whose event
bodies came from another. Subscription ownership, callback targets, and evidence are
therefore always from the same generation of the same body.

Commit **acquires before it releases** (invariant 3 above), so a node that both the prior
and the new dependency set use never falls through its zero-owner disposal edge. After
publication, and only then, the superseded handles are released.

The candidate is a **value the renderer holds**, never an ambient slot the commit reads
back. That is a structural requirement, not an implementation preference: a render is
abandoned by dropping its candidate, so an abandoned render is unable to publish rather
than prevented from publishing. A "current render" module global or thread-local would
make abandonment a flag somebody has to clear correctly, and would put the JVM and
ClojureScript hosts on two different mechanisms for one law.

### Abandoned and failed candidates

A candidate that is never selected publishes **nothing at all** — no ref-count, no watch,
no cache node, no live event site, no evidence. This holds however many speculative
renders precede the selected one, which is what makes concurrent rendering, StrictMode's
double render, and a time-sliced tear-off ordinary rather than special.

A candidate whose reconcile **fails** publishes nothing either. Acquisition failure
unwinds per [§Transactional multi-acquire](#transactional-multi-acquire--staging-and-rollback):
the staged handles are released in reverse order, the prior committed set stays installed
with its published values, and the typed error propagates. The same rollback covers a
failure in the **commit-side evidence reads**: `read` is a fail-loud port operation that
may re-enter application subscription code, so it runs inside the pre-publication
transaction, and a read that throws before the publish swap releases every handle this
candidate freshly acquired — reverse acquisition order — leaves the prior committed set
untouched, and rethrows the original failure unchanged. A partly-owned cell — one whose
dependencies came from two different renders — is not a state the shell can reach.

### Body authority across a live cell

A candidate rendered against a body revision the cell has since replaced is **`:abandoned`**
and publishes nothing. Authority is checked at the two points
[§Body authority under hot reload](#body-authority-under-hot-reload--the-two-point-commit-fence)
fixes: once at commit entry, before any ownership is touched, and once again at the
narrowest publication boundary — after the callback-capable staging work, with nothing
callback-capable between the check and the publish. A candidate abandoned at the second
point releases **only** what its own staging acquired.

The live cell the fence protects is the **compatible** reload, and it is the ordinary
case. A declared view is a descriptor VALUE, so an edit mints a fresh descriptor object;
but the emitter keys its boundary on the qualified view id, not on that object, and hands
the host the component type it already mounted. The cell and everything below it survive,
and only the published body revision advances. That surviving cell is what makes a
candidate rendered against the superseded revision reachable at all — abandoning it is the
fence's whole job.

A reload sidesteps the fence only when the redefinition is **incompatible** — when it
moves the boundary's hook skeleton, as a mode promotion (adding `{:compiled true}` and
reloading) does. That mints a new boundary, so the host remounts once, cleanly, and the
old cell's teardown releases exactly what the old body owned.
[Spec 004C §Compatible shell versus clean remount](004C-Roots-and-Mount.md#compatible-shell-versus-clean-remount)
owns that compatible/incompatible boundary law.

### Frame binding and retarget

A view is bound to its frame through the shared frame context, and the **interpreted shell
observes that context unconditionally**. A provider retarget from frame A to frame B must
rebind a child even when the child's own props are equal, so the shell must be a real
context CONSUMER — a private-slot read resolves the right value but subscribes to nothing,
and the host correctly bails a non-consumer whose props did not move, leaving its
dependencies locked to A.

Retarget is exactly a re-commit against a different frame. The whole bundle moves: the
dependencies are re-resolved and acquired against B before A's are released, and the
committed event destination becomes B — without changing one callback identity, because
identity belongs to the site and the destination belongs to the commit.

A **compiled** shell may elide this machinery only when its manifest PROVES that neither
the view nor its events are frame-sensitive. Absent that proof the machinery stays: an
unrestricted body's reads have not been enumerated, so there is nothing to base an
elision on.

### Occurrence identity across a reorder

Each occurrence owns its own cell, and a commit publishes into that cell and no other.
Two sibling occurrences carrying equal props and equal event intent still own two
independent bundles, so their dependencies, callback identities, and per-site state never
merge.

A **keyed reorder moves occurrences rather than rebuilding them**: the boundary, its cell,
and its ownership travel with the key. Within one boundary, a reorder that makes its own
sites trade targets disposes neither node, because the reconcile's acquire-before-release
covers the whole site set at once.

### Disconnect and replay

Disconnect deactivates exactly what the selected commit owned by that exact generation:
every dependency released synchronously (so each node reaches its zero-owner edge), every
published callback retired to inert, and the cell removed from the pending notification
window.

Disconnect is **not terminal**. A host may tear an effect down and run it again for the
same committed render — StrictMode's mount/cleanup/mount, and a hidden subtree later
revealed — and that replay must RECONNECT. It reconnects from a clean slate: fresh
acquisition against a freshly resolved node, never resurrection of a released handle.
What keeps a genuinely dead boundary from re-acquiring is that a host runs no commit for
a boundary it has torn down, which is structural and needs no flag.

### Same-render-thread capture

A render's reads are recorded on the render's own thread and on no other. A read reached
through `future`, `pmap`, `bound-fn`, or any other conveyed child thread **fails before it
probes**, so a forked read performs no observation work and records nothing.

The rule is enforced rather than engineered around because the alternative is silent: a
conveyed capture is mutated concurrently, sites are lost, and a capture with sites missing
reaches commit as MISSING OWNERSHIP. Refusing an unsupported parallel render body is
strictly better than losing dependencies quietly. The owning thread travels INSIDE the
captured value, because conveyance is precisely the mechanism at fault — a check held in
a dynamic var or a thread-local would be conveyed along with the capture and agree with
itself.

A read outside any active render is refused for the same reason: it has no owner, so
nothing would ever release it. Non-reactive callers use the frame-explicit one-shot read
([§`subscribe-once`](#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value)),
which resolves, probes, returns, and releases without installing a dependency.

## The subscription law

> **Status: normative.** `v/sub` is the paved path's reactive read. Its value, resolution,
> invalidation and commit-safety are stated once here and hold in **both** execution modes; the
> [atomic shell](#the-freehand-atomic-shell) owns the commit those semantics ride on, and
> [Spec 004](004-Views.md) owns the surrounding authoring surface.

`v/sub` takes a subscription **query vector** and returns that subscription's current **value**
— not a reactive reference, not a deref-able container. It reads through the adapter-internal
observation port and resolves against the frame the render is bound to, so a subscription read
in a view is the same value the rest of re-frame2 computes for that query
([§Subscription cache](#subscription-cache--contract-and-operational-semantics)). Freehand adds
no second reactive system and no second value model.

The one-shot, non-reactive read keeps its own name —
[`subscribe-once`](#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value), a
`re-frame.core` verb, never a Freehand one. The two are deliberately not one form under two
meanings (D005): `v/sub` always means *a reactive read owned by this render*, and the ownerless
read says what it does.

### A render-owned value

A `v/sub` inside a render resolves and probes and **acquires nothing** — no ref-count, no
watch, no cache node — and records the read on the render's own candidate, in document order.
The SELECTED commit is the one place those records become owned dependencies: the published
bundle's dependency set is exactly the queries the committed render read, in render order, and
a render the host never selects owns none of them. So `v/sub` is safe in a render the host may
restart or abandon — that is what lets it be the paved read rather than a resource a
speculative render could leak.

The value is **stabilized**: a recompute whose result is `rf=`-equal to the site's prior
committed value returns the exact prior value object, and an `rf=`-equal query keeps the prior
query object, so an equal value is not movement and does not churn identity downstream.

Subscription **handle counts are internal.** `v/sub` returns a value; the ref-count, the
derived container and the disposal edge belong to the port and the shell, and are never part of
what an author reads or what a return conveys.

### The render-only rule

`v/sub` is legal **only during an active declared render**. A read with no render to belong to
has no owner — nothing would ever release it — so it is refused loudly with
`:rf.error/view-read-outside-render` rather than probed and dropped to a silent `nil`. The
diagnostic is raised **before** the target is resolved, so the refused read performs no
observation work. A REPL probe, a timer, a `v/event` / `v/handler` callback, a promise
continuation, or any foreign callback that reaches for `v/sub` gets the same diagnostic at the
call site, and the recovery is the frame-explicit one-shot read.

This is the authoring name for the capture law the shell states for its port
([§Same-render-thread capture](#same-render-thread-capture)); `v/sub` inherits the same
same-thread rule below.

### Capture through helper functions

The render owns a `v/sub` wherever the call **lexically sits**, including inside an ordinary
`defn` helper the body calls:

```clojure
(defn- money [q] (format-currency (v/sub q)))   ;; a plain defn, not a view

(v/defview receipt [_]
  [:dl [:dd (money [:cart/subtotal])]           ;; captured by receipt's render
       [:dd (money [:cart/tax])]])
```

`money` is a helper, not a boundary: it owns no occurrence and no subscriptions of its own, and
its `v/sub` is recorded on the *calling* render's candidate exactly as an inline read would be.
The capture rides the active render, not the call depth, so refactoring an inline read into a
helper — or back — changes neither ownership nor evidence. It is **same-thread** capture, so a
read conveyed to a child thread (`future`, `pmap`, `bound-fn`) is refused with
`:rf.error/view-forked-capture` before it probes
([§Same-render-thread capture](#same-render-thread-capture)).

### Invalidation and atomic recommit

When an input a committed `v/sub` depends on changes value (by `rf=`), the occurrence is marked
and the host re-renders it. The new render reads whatever queries its body now reaches, and its
commit republishes the **whole bundle** — dependencies, event targets and evidence — in one
step ([§The selected render bundle](#the-selected-render-bundle)). Invalidation therefore
recomputes and recommits **atomically**: there is no window in which the occurrence's
dependencies came from one render and its committed values from another, and an `rf=`-equal
recompute republishes an identical bundle rather than churning it.

Neither mode changes these semantics. The interpreted tier records the reads a committed render
actually made — exact for that generation, not a static upper bound for the program; the
compiled tier additionally proves a finite set of possible read sites (Spec 004D). What `v/sub`
*means* — a render-owned, stabilized, same-thread reactive read — is one sentence in both.

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

The subscription's body still runs with `nil` substituted for the unresolved input. This is intentional: it keeps the trace stream readable (the agent sees one error event rather than a chain of cascading throws) and lets the caller handle the missing data gracefully if it can.

A related case is `subscribe` itself naming an unregistered sub-id — most often a boot-order or lazy-load race where the consumer subscribes before the registering namespace has loaded. The runtime emits the same `:rf.error/no-such-sub` trace event, returns a nil-yielding reaction (recovery `:replaced-with-default`), and **does not** populate the per-frame sub-cache. Skipping the cache on miss preserves the v1 semantic that a later registration is observed by the next subscribe — no stale `nil`-reaction lingers.

## CLJS reference: Reagent as default adapter

The CLJS reference ships its adapters across sibling Maven artefacts — the full catalogue (each adapter's `:kind`, namespace, coordinate, repository home, and lifecycle role) is the canonical inventory in [§CLJS reference scope](#cljs-reference-scope). Each implements the closed ten-fn contract above; the consumer chooses one and installs it **explicitly** with `(rf/init! …)` (per [§Adapter selection at boot](#adapter-selection-at-boot)) — there is no auto-by-platform selection. This section walks the **Reagent** adapter as the worked reference — the other adapters realise the same contract against their own substrate. Per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention).

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
;; React 19 takes a `Root` (from `reagent.dom.client/create-root`) — NOT
;; a raw DOM element. The non-hydrate path creates the Root then renders
;; into it; the hydrate path's `hydrate-root` returns its own Root. The
;; returned unmount-fn closes over the Root so the runtime can release it
;; without re-consulting the DOM element. Idempotent: calling unmount
;; twice is a no-op.
(defn render [render-tree mount-point opts]
  (let [hydrate? (boolean (:hydrate? opts))]
    (if hydrate?
      (let [root (rdc/hydrate-root mount-point render-tree)]
        (fn unmount [] (rdc/unmount root)))
      (let [root (rdc/create-root mount-point)]
        (rdc/render root render-tree)
        (fn unmount [] (rdc/unmount root))))))

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
;; Total disposal. Order matters: tear down sub-cache Reactions first (so
;; nothing observes a ratom going away), then unmount any active React
;; Roots, then clear adapter-private caches. Frame-providers are stateless
;; (a single zero-arity component services every frame keyword per
;;) so there is no provider-side cache to flush. Reagent's own
;; reaction-graph caches GC themselves once their last watcher drops, so
;; the explicit `(ratom/flush!)` step the v1-pseudocode named is not
;; needed — disposing the cached Reactions above is sufficient.
(defn dispose-adapter! []
  ;; Step 1 — cancel in-flight reactive subscriptions across every live
  ;; frame's per-frame sub-cache. Reaches each Reaction via
  ;; `interop/dispose!` (which routes through `:adapter/dispose!`).
  (doseq [[_ frame-record] @frame/frames]
    (when-let [cache (:sub-cache frame-record)]
      (doseq [[_ entry] @cache]
        (some-> (:reaction entry) interop/dispose!))
      (reset! cache {})))
  ;; Step 2 — unmount any active React 19 Roots.
  (doseq [root @active-roots]
    (try (rdc/unmount root) (catch :default _ nil)))
  (reset! active-roots #{})
  ;; Step 3 — clear adapter-private caches.
  (reset! hiccup-emitter nil)
  nil)
```

### Sub-cache wiring (Reagent realisation)

The per-frame **sub-cache** ([§Subscription cache invalidation](#subscription-cache--contract-and-operational-semantics)) is the bridge between `reg-sub` and a Reagent reaction. v1's working algorithm in `re-frame.subs` is the reference. The CLJS-reference v2 wiring:

```clojure
;; The cache is per-frame: keyed by [query-vector], stored on the frame.
;; Each entry is a map {:reaction r :inputs [...] :ref-count n} — the
;; Reagent Reaction wraps the sub's body; the cached value lives ON the
;; Reaction and is read via deref (no :value slot). See [§Cache shape].

(defn subscribe [frame query-v]
  (let [k     (cache-key query-v)
        cache (:sub-cache frame)]
    (if-let [entry (get @cache k)]
      (do (swap! cache update-in [k :ref-count] inc)  ;; cache hit: bump ref-count
          (:reaction entry))
      (compute-and-cache frame query-v))))            ;; cache miss: build chain

(defn- compute-and-cache [frame query-v]
  (let [meta     (registrar/lookup :sub (first query-v))
        ;; Produce the realized input query-vectors for THIS entry from the sub's
        ;; input producer (per [§Subscription input producers]):
        ;;   :db         → []                          ; layer-1 reads app-db directly
        ;;   :static     → (:input-signals meta)       ; literal :<- query-vectors
        ;;   :parametric → (normalize-sub-inputs       ; (input-fn query-v), validated
        ;;                   ((:input-fn meta) query-v))
        ;; normalize-sub-inputs enforces the input grammar (a vector of query
        ;; vectors) — a bad shape signals :rf.error/sub-input-fn-bad-return; a
        ;; throw in input-fn signals :rf.error/sub-input-fn-exception.
        input-qs (produce-input-queries meta query-v)
        inputs   (mapv (fn [input-q] (subscribe frame input-q)) input-qs) ;; recurse → containers
        body-fn  (:fn meta)
        ;; The Reaction wraps the sub body. Reagent re-runs body-fn only when
        ;; one of its derefs (the inputs) changes by =. This is the layer-1/2/3
        ;; sub semantics from v1 — same algorithm, now scoped per frame. The
        ;; entry's input topology is FIXED once materialized (the input-fn does
        ;; not re-run on recompute — fixed-topology-per-cache-entry invariant).
        r        (ratom/make-reaction
                   (fn []
                     (let [body-arg (case (:input-kind meta)
                                      :db         (adapter/read-container (frame-app-db frame))
                                      :parametric (mapv deref inputs)
                                      :static     (case (count inputs)
                                                    0 nil
                                                    1 @(first inputs)
                                                    (mapv deref inputs)))]
                       (body-fn body-arg query-v)))))]
    ;; Store the realized input QUERY-VECTORS (not the containers) so disposal,
    ;; trace, and Xray can read this entry's realized parametric edges.
    (swap! (:sub-cache frame) assoc k {:reaction r :inputs input-qs :ref-count 1})
    ;; When this reaction's last reader disposes, release the input refs
    ;; symmetrically (layer-2+ cascade) then GC the cache slot.
    (interop/add-on-dispose! r
      (fn []
        (doseq [input-q input-qs] (unsubscribe frame input-q))
        (swap! (:sub-cache frame)
               (fn [cm] (if (identical? r (get-in cm [k :reaction])) (dissoc cm k) cm)))))
    r))

(defn dispose-frame-subs! [frame]
  (let [cache (:sub-cache frame)]
    (doseq [[_ entry] @cache] (interop/dispose! (:reaction entry)))
    (reset! cache {})))
```

What this gives:

- **Hot reload** ([001-Registration](001-Registration.md), bead-tracked): re-registering a sub disposes the cache slot for that query; next subscribe rebuilds with the new body.
- **Frame teardown** ([002 §Destroy](002-Frames.md#destroy)): `dispose-frame-subs!` fires from the frame's lifecycle hook; every reaction is disposed; no leaks.
- **Layer-1/2/3 semantics**: the recursion in `compute-and-cache` builds a chain. A layer-2 sub's reaction `:<-`s into a layer-1 sub's reaction; Reagent's tracking propagates `=`-equality up the chain.

### Frame-provider via React context

`register-context-provider` returns the **frame-provider** component. The CLJS implementation lives in `re-frame.frame-context`; the design is owned by [002 §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail) — this section names the adapter-side hook into it.

```clojure
;; The single React Context. The default value is the NO-PROVIDER
;; SENTINEL, NOT :rf/default — absence of a provider must be detectable
;; as absence (per [002 §Frame target resolution], EP-0002), so the read
;; tier returns nil and a public frame-scoped op fails loudly rather than
;; synthesise a default frame from nothing.
(defonce ^:private frame-context
  (.createContext js/React ::no-provider))

(defn provider []
  ;; Returns a Reagent component the user includes in their tree:
  ;;   [provider :auth
  ;;     [some-view ...]]
  ;; The Provider's value is the keyword, never the frame record;
  ;; consumers resolve the keyword against the global frame registry on
  ;; every read, so re-registering frames is picked up automatically.
  ;; 0-arity: a single built component services every frame —
  ;; the frame keyword lives in the Provider's :value at render time, not
  ;; in a build-time closure.
  (fn [frame-kw & children]
    ;; `:r>` bypasses Reagent's `convert-prop-value` so the keyword's
    ;; namespace survives the React-context round trip — see Spec 002
    ;; §`frame-provider` for the prop-conversion rationale.
    (into [:r> (.-Provider frame-context) #js {:value frame-kw}] children)))
```

The `read-frame-from-context` lookup chain (`*current-frame*` dynamic var → React context → **nil**, no `:rf/default` floor) is documented in [002 §Reading the frame from React context](002-Frames.md#reading-the-frame-from-react-context-cljs-implementation-detail). A public frame-scoped op turns the nil into `:rf.error/no-frame-context`.

#### Frame propagation across React-binding ports

**The CLJS-reference shape.** The shared `re-frame.adapter.context/frame-context` primitive lives in the **core artefact** (`day8/re-frame2`) — a CLJS-only file that the JVM build does not load (per [000 §C2 Cross-platform](000-Vision.md#c2-cross-platform-jvm-interop-preserved)). Every React-shaped CLJS adapter (`re-frame.ui`, Reagent, reagent-slim, UIx) consumes it; mixed-substrate apps therefore compose providers across substrates rather than silos.

**Per-language ports realise the same contract via the host React binding's own context primitive.** The mechanism varies by binding; the contract — *a context value carrying the current frame-id keyword; views read it via the host React binding's hooks-equivalent* — does not. Per-port realisations:

| Port | React-context primitive | Hooks-equivalent read |
|---|---|---|
| TypeScript-React | `React.createContext<FrameId \| NoProvider>(NO_PROVIDER)` (sentinel default, not `:rf/default`) | `useContext(FrameContext)` |
| Fable (F#) — Feliz / Fable.React | `React.createContext` | `React.useContext` |
| Scala.js — scalajs-react / Slinky | `React.createContext` (binding-shaped) | `useContext` hook |
| PureScript — `React.Basic.Hooks` | `Hooks.createContext` | `Hooks.useContext` |
| Kotlin/JS — kotlin-react | `createContext` | `useContext` |
| Melange / ReScript / Reason — ReasonReact | `React.createContext` | `React.useContext` |
| Squint | reuses the CLJS-Reagent shape (Squint preserves Clojure keywords) | same as CLJS |

The spec **does not** prescribe JS implementation details (`_currentValue` reads, class-component `:contextType` shapes, prop-stringification quirks) — those are port discretion. What the spec requires is the contract: the provider's *value* is a frame-id keyword (or the host's identity-primitive equivalent), and the views inside the provider's subtree resolve subscriptions / dispatches against that frame.

**Adapter responsibility — `:adapter/current-frame` late-bind hook.** Each React-shaped substrate adapter (`re-frame.ui`, Reagent, reagent-slim, UIx) MUST register its React-context-aware `current-frame-id` impl through the `:adapter/current-frame` late-bind hook at namespace-load time. `re-frame.subs/subscribe`, `re-frame.subs/subscribe-once`, `re-frame.subs/unsubscribe`, and the dispatch envelope's `:frame` default consult the hook on CLJS so the React-context tier of the resolution chain is **live** rather than dead code. Without the registration the call sites fall back to `re-frame.frame/current-frame` (dynamic-var tier only); the React-context tier silently no-ops to nil. The impl MUST return **nil** (not `:rf/default`) when no scope names a frame, so a public frame-scoped op raises `:rf.error/no-frame-context` rather than synthesising a default. Hook signature: `(fn frame-id-keyword-or-nil)`.

**Hook routing is by stable token, not object identity.** A test bundle (or a port that ships more than one adapter) may load several adapter namespaces, each publishing the same `:adapter/*` hook key. Each adapter wraps its impl in a routing closure that fires **only when that adapter is the installed one**, chaining to the previously-registered handler otherwise (the CLJS reference helper is `re-frame.substrate.adapter/route-hook!`). The closure decides "is this my adapter?" by **stable token — the canonical `:kind` discriminator — NOT object identity**. This matters because the adapter spec map is a **value**: a consumer may copy, `assoc`, or `merge` a canonical adapter map (for instrumentation, local overrides, or the adapter-swap pattern) and install the copy. A copy is value-equal but a distinct object, so an object-identity guard would silently serve **stale, inert hooks** for it — `rf/init!` returns green and `current-adapter` looks right, but every routed hook falls through to its chain bottom: `:adapter/current-frame` resolution dies (the chain bottom is nil → a frame-scoped op raises `:rf.error/no-frame-context`; there is no `:rf/default` floor), source/view annotation and after-render no-op, and the ratom family's `:adapter/derived-container?` guard stops firing. Routing by the `:kind` token instead makes a copied canonical map dispatch to its adapter's **live** hooks, which is the contract the bullet above requires. A genuinely custom adapter that did not pick a canonical `:rf.adapter/*` `:kind` (its `:kind` is absent or `:custom`) carries no distinguishing token and so falls back to object identity — two distinct `:custom` adapters are never conflated by a shared `:custom` keyword. The same stable-token rule governs any adapter-side driver guard that asks "is MY adapter installed?" (e.g. the Test-React `mount!` driver accepts a copied Test-React map).

The impl is substrate-specific:

- **Reagent** registers `re-frame.views/current-frame`, which uses Reagent's class-component `(.-context cmp)` path. The path is intentionally narrow — it surfaces context only to components whose `:contextType` matches `frame-context` (i.e. components registered via `reg-view*`). A plain Reagent fn lacks the `:contextType`, so its `(.-context cmp)` is the no-provider sentinel and the reader returns nil — a public frame-scoped op then raises `:rf.error/no-frame-context`.
- **UIx** registers `re-frame.adapter.context/function-component-current-frame`, which reads `_currentValue` directly off the shared context object. Function components have no `(.-context cmp)` slot, so `_currentValue` is the substrate-portable path; UIx's `use-context` is sugar over the same read. The no-provider sentinel resolves to nil; a non-keyword, non-sentinel value emits `:rf.error/frame-context-corrupted` (recovery `:no-frame-context`) and returns nil.

Both impls share the dynamic-var tier (`re-frame.frame/*current-frame*`, set by `with-frame` / the router's per-handler binding) and bottom out at **nil** (no `:rf/default` tier); only the middle (React-context) tier differs. The canonical user-facing surface (`rf/frame-provider`) mounts the Provider via Reagent's `:r>` interop head so the props map bypasses `reagent.impl.template/convert-prop-value` — the `:value` keyword (namespace and all) reaches React unchanged. As defensive cover, both impls round-trip the prop-stringified shape via `re-frame.adapter.context/coerce-context-value` so a raw-hiccup `[:> Provider {:value :tenant}]` mount (not via `rf/frame-provider`) is still observed correctly by every substrate. The helper is lossy for namespaced keywords on raw-hiccup mounts under the classic adapter (`(name :foo/bar)` is `"bar"`); raw-hiccup mounts that need namespaced frame-ids should switch to `rf/frame-provider` or `re-frame.adapter.context/provider-element`.

**Plain-fn footgun is `:rf.error/no-frame-context`.** A plain Reagent fn (not registered via `reg-view`) cannot read the closest enclosing `frame-provider` because it lacks the `^{:contextType frame-context}` metadata `reg-view` attaches. Such a plain fn's ambient `(rf/subscribe ...)` / `(rf/dispatch ...)` resolves to nil and raises `:rf.error/no-frame-context` — the operation fails fast rather than silently routing to a conventional default. There is no silent fall-through to `:rf/default`. The **canonical repair** is to register the component with `reg-view`: registration installs the `^{:contextType frame-context}` wiring, so `dispatch` / `subscribe` read the provider's frame from React context at render (per [002 §View ergonomics](002-Frames.md#view-ergonomics-the-hard-part)). For code left *deliberately* unregistered, the only shapes that work carry the target **explicitly** — `(rf/capture-frame frame-id)` locked to a named frame, an explicit `{:frame …}` opt on the `subscribe` / `dispatch` call, or a frame-locked operation bundle captured in a frame-aware ancestor and threaded down as props. Two shapes that look plausible but **re-fail** with the same `:rf.error/no-frame-context`: wrapping the subtree in `with-frame` — a render-time dynamic binding that has already unwound by the time React invokes the descendant — and a no-arg `(rf/capture-frame)` from the unregistered fn, which repeats the ambient lookup that already returned nil (it captures only when a real scope exists at render, per [002 §`capture-frame`](002-Frames.md#capture-frame--the-keystone-affordance-cljs-reference)).

### The sub-override subscribe seam (debug-gated)

`re-frame.subs/subscribe` carries one **substitutive**, debug-gated late-bind hook — `:subs/resolve-sub-override` — that lets a development tool *replace* a subscription's value at the view's deref point without touching app-db. It is the render-phase half of [Story's `:sub-overrides` fidelity rung](../tools/story/spec/017-Testing-Story.md#view-state-subscription-overrides): a designer pins a view into an `:error` / `:loading` / `:empty` state by naming exact subscription query-vectors and the values they should surface — no events, no app-db seed.

**Carriage — React context, not a dynamic var.** The override map (`{query-vector value}`) must survive from the Story render-scope component into the **descendant** view's own, *deferred* React render — the view's `@(rf/subscribe [:q])` runs later, in its own reaction, several component layers deep. A `binding`-bound dynamic var does NOT survive that boundary (the binding unwinds before the descendant renders). The carriage that does is a **React context** — React mutates a context's `_currentValue` as Provider boundaries are entered/exited during render, so a read from inside any descendant render sees the closest enclosing Provider's value. This is the exact mechanism the frame-id uses (§Frame-provider via React context above); the override carriage mirrors it in a sibling CLJS-only context object whose default value is `nil` (no overrides in scope). The tool wraps the variant view in that Provider; core reads the closest enclosing map at subscribe time.

**Consult — `:subs/resolve-sub-override`.** Inside the same `(when interop/debug-enabled? …)` envelope that gates the observational subscribe-time hooks (`:views/record-view-deref!`, the plain-fn warning), `subscribe` consults the hook with the query-vector. The hook returns a **one-element vector `[value]`** on an exact-query-vector HIT (a one-element vector — never a bare value — so a `nil`-valued override is still honoured as a hit) or `nil` on a miss / no Provider in scope / production. On a HIT, `subscribe` short-circuits build-and-cache and returns a **constant reaction** (a derived value with no inputs that always yields the pinned value): it never recomputes, is never cached, and is never invalidated. On a miss / unbound / production, `subscribe` is byte-for-byte unchanged. The whole block elides under `:advanced` + `goog.DEBUG=false`.

**Bundle isolation.** Core only *declares* the hook key and *consults* it; the resolver that reads the override-context Provider is *published* from the tool side (Story) via late-bind, so core never statically requires a tools namespace. The context-carriage object lives in core (CLJS-only) because core already depends on React — but it is read only on the dev consult path, which DCEs in production.

**Honesty boundary (load-bearing).** The override feeds **only** the constant reaction the view derefs. It NEVER writes app-db and NEVER reaches [`compute-sub`](008-Testing.md#compute-sub-algorithm). Because `:rf.assert/sub-equals` (and every subscription assertion) evaluates a sub *through* `compute-sub` against the real app-db, an override can **never** satisfy a subscription assertion. Subscription *correctness* is proven by real setup events / a schema-checked app-db seed / `compute-sub` — never by an override. This rung is, by construction, a picture for the eye, not proof.

**Override schema-validation.** When an override HIT targets a sub that declares an output `:schema` (per [010 §Validation order step 6](010-Schemas.md#validation-order-on-event-processing)), core validates the pinned value against that schema the SAME way `:where :sub-return` does — through the registered validator reached via the `:schemas/validate-with-registered-fn` late-bind hook, dev-only. A mismatch emits `:rf.error/schema-validation-failure` with a `:where :sub-override` discriminator and surfaces `nil` (mirroring `:sub-return`'s `:replaced-with-default` recovery — observational; the failure is reported, the violating value is not surfaced). An override that violates the sub's own output contract is exactly the "pin a state the real derivation could never produce" anti-pattern; validating it closes that honesty gap. See [010 §Validation order](010-Schemas.md#validation-order-on-event-processing).

> **Observation-target consultation (observation-port substrate).** On the compiled UI
> substrate the override consult is folded into `resolve-target`
> ([§The internal observation port](#the-internal-observation-port-adapter-internal)):
> the render pass consults the override context **once per site, at render**, and a HIT
> resolves the site's captured target to `{:kind :story-override …}` — the pinned value
> rides the target — instead of a real sub-cache node. Commit acquires that exact
> captured target as a **static handle** (`:owned? false` reported honestly, `read`
> yields the pinned value, `release!` no-ops, no callback) — there is no deref-time
> re-consult and no constant reaction. Everything else in this section is unchanged and
> applies to both mechanisms: the honesty boundary (an override NEVER reaches
> `compute-sub`, so no subscription assertion can be satisfied by one), the override
> schema-validation rule, the production elision envelope, and the bundle-isolation
> split. The constant-reaction realisation above remains the contract for the current
> adapters' `subscribe` path.

### Plain-atom adapter (JVM, SSR, headless)

The **plain-atom adapter** is the same ten-fn contract realised against `clojure.core/atom` instead of Reagent. It is what runs on the JVM (per [000 §C2. Cross-platform: JVM interop preserved](000-Vision.md#c2-cross-platform-jvm-interop-preserved)) and what SSR and headless tests use ([§SSR-specific behaviour](#ssr-specific-behaviour), [008-Testing](008-Testing.md)).

How it differs from the Reagent adapter:

```clojure
(ns re-frame.substrate.plain-atom
  (:require [re-frame.render.hiccup-to-html :as hiccup]))

(defn make-state-container [initial-value]
  (atom initial-value))                               ;; clojure.core/atom; reactivity via add-watch (see subscribe-container)

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
                  {:rf.error/id :rf.error/render-on-headless-adapter})))

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

`(rf/init! adapter-map)` requires the consumer to pass an adapter spec map explicitly. Each adapter namespace exports an `adapter` Var (the spec map); the consumer requires the namespace and passes the Var:

```clojure
;; Reagent (CLJS, day8/re-frame2-reagent):
(require '[re-frame.core :as rf]
         '[re-frame.adapter.reagent :as reagent])
(rf/init! reagent/adapter)

;; reagent-slim (CLJS, day8/reagent-slim) — the slim jar publishes its
;; adapter at the CANONICAL `re-frame.adapter.reagent` ns (renamed from the
;; in-tree `-slim` ns at publication), so it is a drop-in swap for the stock
;; jar's require:
(require '[re-frame.core :as rf]
         '[re-frame.adapter.reagent :as reagent])
(rf/init! reagent/adapter)

;; UIx (CLJS, day8/re-frame2-uix):
(require '[re-frame.core :as rf]
         '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

;; re-frame.ui (CLJS/JVM) — first-party compiled-view, donor code, in-tree only:
;; day8/re-frame2-ui is NOT a Maven coordinate and never will be (Mike ruled
;; 2026-07-22), so consume it in-tree — there is no released dependency to add.
(require '[re-frame.core :as rf]
         '[re-frame.ui :as ui])
(rf/init! ui/adapter)

;; SSR / JVM (day8/re-frame2-ssr):
(require '[re-frame.core :as rf]
         '[re-frame.ssr :as ssr])
(rf/init! ssr/adapter)

;; Headless / plain-atom (re-frame.substrate.plain-atom in core):
(require '[re-frame.core :as rf]
         '[re-frame.substrate.plain-atom :as plain-atom])
(rf/init! plain-atom/adapter)
```

`(rf/init! …)` accepts exactly one argument shape:

- `(rf/init! adapter-map)` — install the literal adapter spec.

Calling `(rf/init!)` with no args raises a language-level `ArityException` at the call site (the no-arg arity was cut from the fn defn entirely, so the mistake surfaces at compile/load time rather than at runtime). Calling `(rf/init! :reagent)` (or any non-map value) and `(rf/init! nil)` raise `:rf.error/no-adapter-specified` at runtime — there is no default-adapter registry and no keyword-to-adapter lookup table. The runtime error message points the consumer at the adapter-ns + adapter-Var pattern.

**No registry, no implicit defaults.** There is no default-adapter registry: `(rf/init! adapter-map)` takes the adapter spec explicitly. Two reasons:

1. **Explicit > implicit at the call site.** Reading any app's `run` function tells you exactly which adapter is in use, with no need to chase ns-load side-effects through the require graph.
2. **Bundle-size.** A registry is bundle weight even when unused. An app that requires only the adapter it needs ships only that adapter's code; there are no registry-and-resolver paths to carry.

A mixed-substrate app — say a build that imports both `re-frame.adapter.reagent` (for stories) and `re-frame.adapter.uix` (for production views) — picks the active adapter by passing the right Var to `init!`. There is no multi-adapter ambiguity to resolve at boot: only one adapter is ever installed.

`install-adapter!` is called once per process by `init!`'s implementation. Subsequent calls without an intervening `dispose-adapter!` raise `:rf.error/adapter-already-installed` ([§Single adapter per process](#single-adapter-per-process)).

The CLJS adapter namespaces (`re-frame.ui`, Reagent, reagent-slim, UIx) and the SSR namespace each export their `adapter` Var; the contract surface is the same ten-fn map (see [§The adapter API contract](#the-adapter-api-contract) above). The plain-atom adapter in `re-frame.substrate.plain-atom` is reachable on both JVM and CLJS — useful for headless tests on either platform.

## CLJS reference: UIx as alternative substrate

> **A first-class adapter, alongside the others.** The UIx adapter is a **first-class, actively-supported** view adapter — it lives on alongside the stock-Reagent compatibility/interop tier and the reagent-slim adapter, and is **not** scheduled for removal. The first-party `re-frame.ui` compiled substrate is a *new, experimental* option offered alongside it, not its replacement. See [§CLJS reference scope](#cljs-reference-scope) for each adapter's lifecycle role. The section below documents it as it ships.

The UIx adapter ships in `day8/re-frame2-uix` and implements the same ten-fn contract as the Reagent adapter — same observable behaviour for events, subs, effects; different rendering substrate for views.

The UIx adapter's design decisions are:

1. **Hook naming.** The substrate's subscription surface is `use-subscribe`, matching the React/UIx idiom. Symmetric ergonomics to Reagent's `(rf/subscribe ...)` deref shape; asymmetric naming because hooks live in hook-named space.
2. **Frame propagation.** Both the UIx and Reagent adapters read the *same* React Context object — factored out of `re-frame.views` into `re-frame.adapter.context` (CLJS-only file in core). A future mixed-substrate app's frame-provider chain therefore composes across substrates rather than living in per-adapter silos.
3. **Auto-injection.** None for UIx — the hook surface is the canonical UIx access path. Components call `(use-subscribe [:foo])` to read, and hold frame ops via the `use-frame` hook (rf2-y6dz8t): `(let [{:keys [dispatch]} (use-frame)] …)` returns EXACTLY what `(rf/capture-frame)` returns — the frame-locked ops map — for the ambient provider frame, resolved through the same carried-invariant chain as the ambient `use-subscribe` and reference-stable across re-renders for the same resolved frame. There is no UIx-side analogue to `reg-view`'s `dispatch` / `subscribe` lexical bindings; `use-frame` is capture-frame in hook position, nothing more (no options map, no variants — an explicit frame is `(rf/capture-frame frame-id)`, no hook needed).
4. **`reg-view` macro scope.** `reg-view` stays Reagent-only (auto-defs the Var, auto-injects the lexical `dispatch` / `subscribe`, threads source-coords through Reagent's `:contextType` machinery). Most UIx components are bare `defui`; a UIx author reaches for `reg-view*` (the plain-fn surface in `re-frame.core`) only when a component needs registry-keyed view addressing. Source-coord stamping for UIx-rendered roots happens at the adapter's render-time wrapper, not at registration time.
5. **Source-coord DOM annotation.** The UIx adapter wraps user components in a thin layer that calls `React.cloneElement` to add `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element when `interop/debug-enabled?` is true. Production-elision contract: under `:advanced` + `goog.DEBUG=false` the entire wrapper branch DCEs and the literal `data-rf2-source-coord` string fragment is absent from the bundle. Fragments and non-DOM roots are exempt with the standard one-shot warning per id.
6. **Render flush for tests.** The adapter exposes `flush-views!` wrapping React's `act()`. Tests dispatching against a UIx-mounted tree call `(flush-views!)` after a dispatch to settle pending React effects before reading the DOM. The entry point is **per-adapter-require** — `(uix-adapter/flush-views!)`, NOT centralised through `re-frame.test-support` — per the adapter-dependency-direction rule in [§What an adapter MUST NOT do](#what-an-adapter-must-not-do); see [Spec 008 §Adapter-aware test helpers](008-Testing.md#adapter-aware-test-helpers--flush-views) for the test-author-facing rationale.
7. **Curated example set.** counter + login (under `examples/substrates/uix/counter/` and `examples/substrates/uix/login/`) — the representative pair that shares its substrate-agnostic dataflow (events, subs, schemas, machine, managed-HTTP stub) with the Reagent siblings, chosen because it spans the substrate-contract surface a UIx app exercises. Realworld is skipped per Decision 7 — heavy with Reagent-flavoured idioms; deferred until a UIx user wants it. **Coverage shape:** the `examples/` tree is test-free, so these two example pages carry *compile coverage* only (`test:examples-compile`); the *runtime* substrate-contract smoke (mount → subscribe → dispatch → re-render) is the single adapter-owned testbed at `implementation/adapters/uix/testbed/` (one mount+dispatch+assert smoke per adapter), not a per-example browser gate. Substrate-agnostic behaviour the login page would exercise — the login machine, its Malli schemas, the managed-HTTP stub — is covered by the canonical Reagent suite and the feature artefacts' own tests; the UIx-specific view-layer surface (`use-subscribe`, the `use-frame` hold hook, capture-frame capture, after-render flush, source-coord DOM annotation) is covered by the UIx adapter's CLJS tests under `implementation/adapters/uix/test/`. See [Conventions §Adapter test matrix policy](Conventions.md#adapter-test-matrix-policy).
8. **UIx version target.** UIx 2.x (hooks-based). UIx 1.x back-compat is explicitly out of scope.

The CLJS-reference code follows the same per-contract-fn shape as the Reagent adapter; the differences are at the React layer:

- `make-state-container` returns a `clojure.core/atom` rather than a Reagent `r/atom` — UIx has no built-in reactive atom primitive. View-side reactivity flows through `useSyncExternalStore` in `use-subscribe` rather than through Reagent reactions.
- `make-derived-value` returns an `IDeref`+`IWatchable` wrapper that recomputes on deref and broadcasts changes via the source containers' watch machinery. Equality-on-= invariants ride on the core's sub-cache (Spec 006 §Invalidation algorithm), not on the substrate's caching.
- `render` wraps `react-dom/client.createRoot` + `root.render`; the unmount-fn calls `root.unmount()`.
- `register-context-provider` returns a UIx `defui` component reading the shared `frame-context` via `use-context`.

Every other adapter primitive (read, replace, subscribe-container, dispose) is structurally identical to the Reagent adapter's — the contract is genuinely substrate-agnostic.

## Cross-substrate affordance summary

The ten-fn substrate contract is identical across adapters, but the three **view-author-facing** surfaces — *read a subscription*, *scope a frame to a subtree*, *flush pending renders in a test* — differ per substrate because each rides its host's idiom (Reagent's reactive deref vs the React-hooks model). A dev moving between substrates needs the one-glance map; this table is it. Each React-shaped adapter's `frame-provider` / `frame-root` is a **native substrate component** (UIx `defui`). The *scope* surface below is `frame-provider {:frame …}` (rf2-nyea0r split; see [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)): **roots ensure; providers scope** — `frame-provider {:frame …}` scopes an existing frame into a React subtree (failing loud if absent), and its sibling `frame-root {:id …}` ensures a named frame at commit; see [002 §`frame-provider`](002-Frames.md#frame-provider--the-scope-only-component-cljs-reference) and [002 §`frame-root`](002-Frames.md#frame-root--the-ensure-component-cljs-reference). Each is realized per-adapter and reads the same React context.

> **The columns and their lifecycle roles.** The Reagent column is the stock-Reagent compatibility/interop tier and the UIx column is a first-class, actively-supported adapter — both live on. **reagent-slim** (also first-class and actively-supported) shares the Reagent column's view-author affordances exactly — the same ratom-family `@(rf/subscribe …)` deref — so it takes no separate column; only its test-flush primitive differs (noted in the flush row). The first-party `re-frame.ui` compiled substrate is a *new, experimental* view layer offered alongside them. This table maps the shipping view adapters as they stand today — see the canonical inventory in [§CLJS reference scope](#cljs-reference-scope) for the complete set and each adapter's lifecycle role. (Helix was removed at S7/W13 — rf2-d6epb, 2026-07-22.)

| Affordance | Reagent (`day8/re-frame2-reagent`) | UIx (`day8/re-frame2-uix`) |
|---|---|---|
| **Read a subscription** | `@(rf/subscribe [:q …])` — reactive deref inside a `reg-view`/Form-2 render fn. | `(use-subscribe [:q …])` — React hook (re-renders on change via `useSyncExternalStore`). |
| **Explicit-frame read** | `@(rf/subscribe frame-id [:q …])` (2-arg). | `(use-subscribe frame-id [:q …])` (2-arg). |
| **Frame resolution (1-arg form)** | dynamic-var → React-context (the surrounding provider, of either family member) → **nil** (no `:rf/default` floor; raises `:rf.error/no-frame-context`). | Same chain; React-context tier read via `use-context`. |
| **Scope an existing frame to a subtree** (`frame-provider {:frame …}`) | Native hiccup component; **trailing-positional children**: `[rf/frame-provider {:frame :f} [header] [main]]` — provide an existing frame's id; fail loud if absent. | Native `defui` component, mounted via `$`; **idiomatic `$` trailing children**: `($ frame-provider {:frame :f} ($ header) ($ main))`. |
| **Ensure a named frame for a subtree** (`frame-root {:id …}`) | `[rf/frame-root {:id :f :images […]} [header] [main]]` — create-if-absent at commit / reuse-no-reseed / provide id; **no destroy-on-unmount**; takes `make-frame` opts. | `($ frame-root {:id :f :images […]} ($ header) ($ main))`. |
| **`nil` `:frame`** (scope shape) | CONFIGURATION ERROR: the SCOPE-only `frame-provider {:frame …}` REQUIRES a `:frame` — a frame-id keyword OR the live frame value `make-frame` returns (normalized one way to its id before React Context is written; the same one frame-target grammar `dispatch` / `subscribe` teach, API-shrink #1 rf2-csbbwu). A `nil` `:frame` emits + throws `:rf.error/no-frame-context` — no `:rf/default` floor (see [§Frame-provider via React context](#frame-provider-via-react-context)); a non-nil target that is *neither* a keyword nor a live frame value is the distinct `:rf.error/bad-frame-provider-arg`. | Same — raises `:rf.error/no-frame-context`. |
| **Frame keyword fidelity under the mount idiom** | `:r>` interop head bypasses Reagent prop conversion, so a namespaced frame keyword survives the React-context round trip. | The native `defui` routes props through UIx's lossless `argv` channel (and folds native trailing children onto `:children` via `glue-args`) — keyword frame-ids survive intact by construction. |
| **Hold the ambient frame's ops** (capture-frame's per-substrate spelling) | `reg-view` injection — the lexically-bound `dispatch` / `subscribe` (internally the same `make-capture-frame` ops); `(rf/capture-frame)` directly for async holds outside the injected bindings. | `(use-frame)` — React hook returning EXACTLY the `(rf/capture-frame)` ops map for the ambient provider frame; reference-stable per resolved frame. |
| **Flush pending renders in a test** | `reagent-adapter/flush-views!` (wraps React's `act()` — the canonical cross-substrate test-flush hook); Reagent's own `r/flush!` also works, and `reagent-slim` ships `reagent2.dom.client/flush-views!`. | `(uix-adapter/flush-views!)` — wraps React's `act()` (per-adapter-require entry point). |
| **`reg-view` macro** | Available (canonical view-registration surface). | `reg-view*` (plain-fn) when registry addressing is needed; most components are bare `defui`. |

> **One primitive, three faces.** `capture-frame` is THE hold primitive; `reg-view` injection and `use-frame` are its two ergonomic spellings. The hold row above adds no second primitive: each substrate spells `capture-frame` in its own idiom — Reagent injects its ops lexically at `reg-view` registration, UIx returns them from a hook — and every spelling yields the same frame-locked ops map defined in [002 §`capture-frame` — the keystone affordance](002-Frames.md#capture-frame--the-keystone-affordance-cljs-reference).

All the adapters read the **same** React Context object (`re-frame.adapter.context/frame-context` in core), so a mixed-substrate provider chain (either family member) composes — a UIx subtree under a Reagent provider (or vice versa) resolves the same frame.

> **Unified call shape.** `frame-provider` and `frame-root` pass children consistently across substrates. Reagent takes trailing-positional hiccup children; UIx takes **native `$` trailing children** — `($ frame-provider {:frame :f} ($ header) ($ main))` — exactly the shape every other UIx component uses. The native `defui` shell reads children off the `:children` key its element macro folds the trailing args onto (UIx's `glue-args`), so there is no `:children`-in-props-map key for an author to forget — the silent-drop footgun is eliminated by construction.

## Subscription topology vs subscription tracking

A subtle distinction worth pulling out: **the static topology of the sub graph is core; the runtime tracking is adapter**.

The topology is "what depends on what" — the static dependency graph you can derive from registrations alone, without running any code. `(rf/sub-topology)` returns this graph as data, shaped `{sub-id {:input-kind <kind> :inputs <inputs> :doc :ns :line :file}}` per [002 §The public registrar query API](002-Frames.md#the-public-registrar-query-api). `:input-kind` discriminates `:db` (layer-1 / direct-app-db reader; `:inputs []`), `:static` (`:<-` chains; `:inputs` lists the literal upstream query vectors in declaration order), and `:parametric` (`input-fn`; `:inputs :parametric` — the realized edge set depends on the concrete outer query vector and is therefore NOT statically enumerable). `:doc` and the source-coord keys are present when the registration carries them. JVM-runnable. No adapter needed. **Realized parametric edges** per concrete query vector are runtime cache state, surfaced by `sub-cache` / live sub-cache inspection (e.g. `{[:article/page :a1] {:sub-id :article/page :input-kind :parametric :realized-inputs [[:article/by-id :a1] ...]}}`), not by the static `sub-topology` — the static query must not pretend every possible parametric edge is enumerable before concrete query vectors exist.

`sub-topology` is a *literal projection* of the registrar — it does not validate the resulting graph. Cycle detection, "this `:<-` references an unregistered sub", and similar diagnostics are debugger / tool-pair concerns that traverse the returned map; the topology query itself reports verbatim what was registered. (Cycles in `:<-` are not legal at runtime — the resolved sub will throw — but the topology query stays a static projection.)

The tracking is "when source X changes, recompute everyone who depends on X" — the runtime mechanism that makes views update reactively. This requires the adapter's `make-derived-value` and is substrate-specific.

In CLJS dev-mode tests, you often want sub computation without tracking: `(compute-sub [:total] db-value)` runs the sub's body against a static `app-db` value and returns the computed result. Pure function. No Reagent, no reactions. This is the "JVM-runnable" path that [008-Testing](008-Testing.md) and [011-SSR](011-SSR.md) use.

### Lazy-seq deref tracking (Reagent adapter)

The Reagent adapter (and any React-shaped adapter whose render-time deref tracking uses a thread-local / dynamic-var reactive scope) only watches `@(rf/subscribe …)` derefs that fire **while the parent reg-view's render-fn is on the stack**. A `(for [x xs] [child …])` form returns a *lazy seq*; if the seq is still unrealised at the moment the render-fn returns, every deref hiding in its body fires later — when React eventually walks the hiccup — at which point the reactive scope is gone and Reagent doesn't register the dependency. Symptom: the app-db slot flips, the sub recomputes, the view does NOT re-render until an external repaint forces a fresh render-pass. Reagent surfaces the case with a console warning at render time:

```
Reactive deref not supported in lazy seq, it should be wrapped in doall: (…)
```

The fix is to **realise the seq inside the render-fn** so derefs reachable through it fire while the reactive scope is still live. Three idiomatic shapes, pick whichever reads cleanest at the call site:

```clojure
;; (1) doall — minimum change, keeps the (for …) shape
(doall (for [row @some-sub] [row-view row]))

;; (2) mapv — eager vector; reads well when no :when / :let / :while
(mapv row-view @some-sub)

;; (3) into … with-transducer or fragment — eager, composes with siblings
(into [:<>] (map row-view) @some-sub)
```

Pure helpers called from inside the seq's body inherit the same rule: any `@(rf/subscribe …)` reachable transitively from a *function call* (not a `[component args]` Reagent component-vector — those get their own reactive scope when React mounts them) MUST be reachable through a realised seq. Reagent components ride their own reactive scopes; raw render helpers ride the parent's. The audit shape is "follow every plain-fn call inside a `(for …)` body; if any of them — directly or via further helpers — derefs a sub, the `for` MUST be realised".

This is a Reagent-substrate concern, not a core-framework one. Substrates that wire reactivity through hooks (UIx) use `use-subscribe` per-call-site, which captures the dependency at hook-call time regardless of when the surrounding seq realises — they are immune to the lazy-seq trap by construction. Core's `compute-sub` is pure and orthogonal: no tracking, no scope.

## SSR-specific behaviour

Per [011](011-SSR.md), the server-side render path doesn't use the adapter's reactivity machinery at all. The flow:

1. Server creates a frame (per [002 §make-frame](002-Frames.md#make-frame--atomic-create-and-register-and-the-canonical-config-grammar)).
2. The frame's `app-db` is a plain atom — the server boots the distinct **`re-frame.ssr` adapter** (`:rf.adapter/ssr`), which is plain-atom-*shaped* (a `clojure.core/atom` container, no React reactivity) but is **not** the core plain-atom substrate; see the [canonical inventory](#cljs-reference-scope).
3. `:initial-events` run; the drain settles.
4. The view fn is called as a *plain function* against the now-stable `app-db` value.
5. The hiccup output is rendered to a string by `render-to-string`.

No Reagent. No React. No reactivity. Pure data → pure data → string.

The server-side adapter is the distinct **`re-frame.ssr` adapter** (`:kind :rf.adapter/ssr`) — a headless, plain-atom-*shaped* adapter that binds its own `render-to-string`. The caller boots it **explicitly** with `(rf/init! ssr/adapter)` (per [§Adapter selection at boot](#adapter-selection-at-boot)); nothing auto-selects it by platform. It is distinct from the core plain-atom adapter, which some headless SSR paths also use — both live in the [canonical inventory](#cljs-reference-scope).

## CLJS reference scope

> **Adapter disposition (EP-0030 Resolved Decisions, 2026-07-17, Mike).** **Reagent, UIx, and reagent-slim live on as first-class, actively-supported adapters** — not frozen, not retiring. **Only Helix was removed** — executed at S7/W13 (rf2-d6epb, 2026-07-22). `re-frame.ui` is a *new, experimental* first-party compiled substrate offered alongside them, not their replacement. The adapter API contract and each adapter's technical lifecycle role are unchanged; the "frozen tier" label is retired in favour of "compatibility/interop tier."

The **core** artefact `day8/re-frame2` carries the substrate-agnostic runtime (the registrar, the drain, the dispatch envelope, the trace stream, sub topology, sub computation, effect-map interpretation), the adapter API contract, the headless **plain-atom adapter** (in the inventory below), and — per Decision 2 — the shared React frame Context object at `re-frame.adapter.context` that every React-shaped adapter consumes.

The **canonical adapter inventory** below is the single source of truth for the adapter set — the **five shipped, published rows** (Reagent, reagent-slim, UIx, plain-atom, SSR) plus the **two in-tree rows** (`re-frame.ui` and Freehand) — carrying every adapter's `:kind`, namespace, Maven coordinate, repository home, and lifecycle role. The shipped adapters go out across sibling Maven artefacts per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention); the two in-tree rows are unpublished for **different** reasons, and the distinction is load-bearing — Freehand is pre-publication with no publication now scheduled, whereas `day8/re-frame2-ui` is **not** a Maven coordinate and never will be (see their rows and [§Adapter selection at boot](#adapter-selection-at-boot)). Every other "the reference adapters" claim in this spec points here rather than re-stating its own list.

| Adapter | `:kind` | Published namespace (exports `adapter`) | Maven coordinate | Repository home | Lifecycle role |
|---|---|---|---|---|---|
| **Reagent** | `:rf.adapter/reagent` | `re-frame.adapter.reagent` | `day8/re-frame2-reagent` | `implementation/adapters/reagent/` | **View adapter — first-class, actively-supported.** The stock-Reagent compatibility/interop tier; today's browser default and the worked reference in [§Reagent as default adapter](#cljs-reference-reagent-as-default-adapter). |
| **reagent-slim** | `:rf.adapter/reagent-slim` | `re-frame.adapter.reagent` — the **publication exception**: the in-tree source ns is `re-frame.adapter.reagent-slim`, renamed to the canonical `re-frame.adapter.reagent` at publication (IMPL-SPEC §13.1) so the slim jar is a drop-in swap for the stock jar. | `day8/reagent-slim` — drops the `re-frame2-` prefix (the lone coordinate exception, per IMPL-SPEC DECISION-1). | `implementation/adapters/reagent-slim/` | **View adapter — first-class, actively-supported.** The slim `reagent2.*` implementation with no stock-Reagent dependency (React 19). |
| **UIx** | `:rf.adapter/uix` | `re-frame.adapter.uix` | `day8/re-frame2-uix` | `implementation/adapters/uix/` | **View adapter — first-class, actively-supported.** UIx 2.x hooks substrate; see [§UIx as alternative substrate](#cljs-reference-uix-as-alternative-substrate). |
| **re-frame.ui** | `:rf.adapter/ui` | `re-frame.ui` | `day8/re-frame2-ui` *(never published)* | `implementation/ui/` | **View substrate — *new, experimental*; in-tree, donor code.** First-party compiled-view adapter offered alongside the live view adapters, not their replacement. **Never published:** `day8/re-frame2-ui` is **not** a Maven coordinate and never will be — Mike ruled on 2026-07-22 that it is not to be published, since it is donor-only code absorbed into Freehand (EP-0036). Its `implementation/ui/deps.edn` carries no deploy aliases, deliberately, and it is absent from both the release matrix and the version-lockstep inventory, so no tag can publish it — consume it in-tree via `:local/root`; the [release process §Policy](../docs/release-process.md#policy) is normative. CLJS uses the watchable native-React realization; the JVM uses the headless atom realization. |
| **Freehand** | `:rf.adapter/freehand` | `re-frame.freehand` (conventionally aliased `v`) | `day8/re-frame2-freehand` *(pre-publication)* | `implementation/freehand/` | **The Freehand view substrate's OWN observation adapter; in-tree / pre-publication.** Freehand renders itself through `react-dom/client`, so this fills the OBSERVATION half of the contract — the state container and a watchable derived value — and is not a renderer adapter: it competes with none of the view adapters above, which remain first-class and independently supported (EP-0036). Built on the shared core React spine, so it adds no dependency Freehand does not already have. Two lifecycle compositions are its own: adapter disposal unmounts every live Freehand root BEFORE disposing the spine (Freehand's roots live in its own per-document registry, invisible to the spine's) — a SAFETY NET that releases each root's claims, its frame REFERENCE and its ViewCells, and deliberately not a frame destroy recipe, since delegation is already closed by the time an adapter's teardown runs and `v/unmount!` on a live adapter owns that; and `flush-render!` closes the pending ViewCell window inside React's synchronous commit boundary and converges to a bounded fixed point, so a synchronous flush returns with the page settled. CLJS only — a JVM structural render has no React roots to compose a lifecycle over. |
| **plain-atom** | `:rf.adapter/plain-atom` | `re-frame.substrate.plain-atom` | *(none — ships inside the core artefact `day8/re-frame2`)* | `implementation/core/` | **Headless adapter (no view layer).** A `clojure.core/atom` container, reachable on both JVM and CLJS; used by headless tests and some SSR paths. **Distinct from the SSR adapter** below. |
| **SSR** | `:rf.adapter/ssr` | `re-frame.ssr` | `day8/re-frame2-ssr` | `implementation/ssr/` | **Headless adapter (no view layer).** JVM server-side rendering — carries `render-to-string` directly in its slot; the `:render` slot throws `:rf.error/render-on-headless-adapter`. **Distinct from plain-atom** (which some SSR paths also use). |

The UIx row realises the eight adapter decisions (the `use-subscribe` hook, the `use-frame` hold hook, the `flush-views!` test-flush helper, a source-coord wrapping component, and the SCOPE-only `frame-provider` / ENSURE `frame-root` pair consuming the shared React context; apps write ordinary `defui` components — `reg-view*` is optional registry addressing, since the `reg-view` macro stays Reagent-flavoured) — see [§UIx](#cljs-reference-uix-as-alternative-substrate) for the per-adapter detail.

The **repository-home** column reflects the source layout: the three shipped **view-adapter** sources live under `implementation/adapters/<name>/` — `reagent/`, `reagent-slim/`, and `uix/`. Two further `implementation/adapters/` directories are **not** adapters: `test-react/` is a **local-test-only** fixture (a pure-CLJC React class-3 lifecycle simulator — no Maven coordinate, no `:kind` in the shipped set, absent from the release matrix), and `scripts/` is **shared adapter-smoke tooling** (the `run-adapter-smokes.cjs` / `serve-and-run-adapter-smokes.cjs` runners), not a substrate. The experimental `re-frame.ui` compiled-view substrate is housed separately under `implementation/ui/`, and Freehand's own observation adapter ships inside the Freehand view artefact at `implementation/freehand/` — neither is an `implementation/adapters/` row, because in both cases the adapter is a part of a view substrate rather than a binding to a foreign one; the SSR adapter is the per-feature `implementation/ssr/` artefact; and the headless plain-atom adapter ships inside the core artefact (`implementation/core/`). The nine per-feature artefacts (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `ssr-ring`, `resources`, `epoch`) stay flat under `implementation/<name>/`. The directory split surfaces the adapter-vs-per-feature distinction in the layout — adapters implement the [§adapter API contract](#the-adapter-api-contract); per-feature artefacts plug into core via the late-bind hook table per [Conventions §Independence rule](Conventions.md#independence-rule). The directory is `adapters/`, not `substrates/` — "substrate" names the abstract contract, "adapter" names each implementation.

Per-host adapters for non-CLJS implementations ship as separate packages, implementing the same contract — the per-adapter-artefact pattern is JS-cross-compile-language-agnostic across the eight in-scope hosts (TypeScript-React, Fable.React / Feliz, scalajs-react / Slinky, React.Basic, kotlin-react, ReasonReact, Melange-React, Squint-with-React). All ship a React-binding adapter; non-React substrates are out of scope per [§Abstract](#abstract).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): these items are **post-v1, untracked notes** — design directions in scope for re-frame2 beyond v1 but with no concrete tracking bead filed yet (so none qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`). "Cooperative rendering substrate" is deferred to a later cycle's benefits-vs-cost evaluation; "Multi-adapter coexistence" is additive on the v1 single-adapter contract once a concrete use case emerges; "CEDN-float cache-key extension" is a flagged reconciliation for review (see [§Host value model](#host-value-model--rf-equality-and-value-keyed-caching)). A tracking bead is filed for each only when the reconsideration trigger below fires; until then they remain notes, not committed work.

### Cooperative rendering substrate (post-v1)

A cooperative rendering substrate — a rendering layer designed natively to cooperate with re-frame, instead of re-frame wrapping Reagent — is on the horizon. Substrate-agnostic decoupling (this Spec) is the prerequisite. Whether the cooperative variant ships depends on a benefits-vs-cost evaluation in a later cycle. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** The adapter contract (per [§The adapter API contract](#the-adapter-api-contract)) is the substrate-decoupling primitive — any cooperative variant ships as another adapter, no core change required.
- **Scope deferred.** The evaluation itself: identifying the cooperation primitives a native substrate could expose (e.g., scheduler-aware re-render coalescing, subscription-graph-driven scheduling, batched view updates aligned to drain boundaries), and the benefits-vs-cost ledger against staying with the live React view adapters (Reagent, reagent-slim, UIx) and the experimental `re-frame.ui` compiled substrate.
- **Reconsideration trigger.** Either (a) measured re-render overhead in the Reagent path becomes the dominant cost on a real workload, or (b) a tool (xray / re-frame2-pair / story) needs scheduling hooks the React substrates can't surface.
- **Out of scope for this note.** Building the cooperative substrate itself — this note tracks the *decision*, not the implementation. A tracking bead (and a separate implementation bead) is filed if the evaluation lands "yes".

### Multi-adapter coexistence (post-v1)

The current contract is single-adapter-per-process. If a concrete use case for per-frame adapter selection emerges, multi-adapter support can be added additively without breaking the single-adapter contract. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** The single-adapter contract (per [§Single adapter per process](#single-adapter-per-process)) is locked; per-frame adapter selection is an extension, not a replacement — the install slot becomes a map keyed by frame-id rather than a singleton.
- **Scope deferred.** The lifting itself: dispatch envelope carrying the in-scope adapter, registrar / tool branching on which adapter a frame uses, error categories for cross-frame view mounts that span adapters.
- **Reconsideration trigger.** A concrete app use case — e.g., a single process embedding a Reagent host alongside a UIx subtree, both backed by re-frame, where running them as separate processes is infeasible.
- **Out of scope for this note.** Multi-adapter *within a single frame* (one view tree mixing adapters) — that path is rejected per [§Single adapter per process](#single-adapter-per-process)'s reasoning and is not on the post-v1 ledger.

### CEDN-float cache-key extension (post-v1, flagged for review)

The [host value model](#host-value-model--rf-equality-and-value-keyed-caching) pins mechanism **(a)** (a value-keyed persistent-collection map keyed by `rf=`) as the reference-aligned default, which admits finite-float query arguments natively — matching the CLJS reference, which caches on the persistent query vector directly. Mechanism **(b)** (an interned CEDN-1 canonical key) inherits CEDN-1's fail-closed-on-floats identity domain, so a (b) host today cannot carry a float-bearing query argument without encoding it at the boundary first.

Whether to bless a **CEDN-float extension scoped to the cache-key domain only** (finite floats permitted as cache-key arguments, `NaN`/infinities still rejected, durable-identity CEDN-1 unchanged) is left open. It would let a (b) host admit the same float-bearing query arguments an (a) host and the reference already accept, at the cost of one paragraph reconciling it against CEDN-1's fail-closed stance ([Conventions §Canonical EDN identity](Conventions.md#canonical-edn-identity)).

#### Post-v1 Tracking

- **Foundation in v1.** Mechanism (a) is the default and needs no extension; the reference and every (a) host already admit finite floats. This note is only about widening mechanism (b)'s cache-key input domain.
- **Scope deferred.** The extension itself: a finite-float encoding for the cache-key domain (not durable identity), its `NaN`/infinity rejection, and the dev-mode out-of-domain diagnostic.
- **Reconsideration trigger.** A non-CLJS host committing to mechanism (b) (an interned canonical cache key) reports float-bearing query arguments it cannot key — the concrete case that makes the extension worth its reconciliation paragraph.
- **Out of scope for this note.** Any change to CEDN-1 durable identity's float rejection — that stance is unchanged; the extension, if blessed, is cache-key-scoped only.

## Resolved decisions

### Adapter selection

Resolved: the consumer passes an adapter spec map explicitly to `(rf/init! adapter-map)`. There is no default-adapter registry. Each adapter namespace exports an `adapter` Var; consumers require the namespace and pass the Var.

See [§Adapter selection at boot](#adapter-selection-at-boot) above for the boot-time wiring, the legal call shapes, and the rationale (explicit > implicit; bundle-size; no implicit cross-adapter coupling).

Re-installing after frames exist is an error (`:rf.error/adapter-already-installed` trace event; recovery: `:no-recovery`, the call is rejected).

Other-language ports follow the same pattern: each adapter package exports a public adapter spec; the consumer requires the package and passes the spec to the language's `init!` equivalent.

The compiled-view package supplies the first-party compiled-view adapter as
`re-frame.ui/adapter` (`day8/re-frame2-ui`). It is exactly the closed ten-function
adapter contract plus `:kind :rf.adapter/ui`; applications install it with
`(rf/init! ui/adapter)`. On CLJS its derived values are watchable and drive the
observation-port/ViewCell path without Reagent, reagent-slim, or UIx. On the JVM the same
public Var uses the headless atom realization of the contract while retaining the
same canonical discriminator. The observation port remains adapter-internal and is
not an eleventh contract function.

### Adapter introspection

Two complementary accessors:

- `(rf/current-adapter)` returns a **discriminator keyword** identifying the active adapter (the `:kind` slot of the installed adapter spec map), or `nil` if no adapter is installed. Canonical values live under the `:rf.adapter/*` reserved namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned),) so third-party adapters can publish their own unqualified `:kind` keywords without collision risk:

  - `:rf.adapter/reagent` — CLJS browser default (bridge adapter)
  - `:rf.adapter/reagent-slim` — CLJS browser, slim adapter (no stock-Reagent dep)
  - `:rf.adapter/ui` — first-party `re-frame.ui` compiled-view substrate
  - `:rf.adapter/uix` — CLJS browser, UIx substrate
  - `:rf.adapter/freehand` — the Freehand view substrate's OWN observation adapter (`re-frame.freehand/adapter`); Freehand renders itself through `react-dom/client`, so this fills the observation half of the contract only
  - `:rf.adapter/plain-atom` — CLJS JVM headless / tests / Node-based CLJS
  - `:rf.adapter/ssr` — CLJS JVM SSR (re-frame.ssr adapter)
  - `:custom` — user installed a custom adapter that didn't pick one of the canonical kinds

- `(rf/current-adapter-spec)` returns the **installed adapter spec map** (the value passed to `(rf/init! ...)`), or `nil` if no adapter is installed. This is the map carrying the contract fns (`:make-state-container`, `:replace-container!`, `:make-derived-value`, …) plus the `:kind` discriminator.

Use `current-adapter` for predicate / branch code ("what substrate am I on?"); use `current-adapter-spec` for tool code that needs the adapter fn handles, or for identity checks across the install/dispose lifecycle.

Tools (10x, re-frame-pair) use the keyword to branch on host capabilities — for instance, the time-travel UI is meaningful in browser-Reagent but not in plain-atom.

The keyword is informational. Behaviour-affecting decisions should be based on `:platforms` metadata (per [011 §S-3](011-SSR.md#effect-handling-on-the-server)) or on explicit configuration, not on which adapter is loaded.

### Disposed-vs-never-installed

Runtime delegation calls (`make-state-container`, `read-container`, `replace-container!`, `make-derived-value`, `render`, `render-to-string`, `subscribe-container`, `register-context-provider`, `flush-render!`) raise a structured ex-info when no adapter is installed. The throw shape distinguishes two states:

- **`:rf.error/no-adapter-installed`** — fresh process, no `(rf/init! …)` has fired yet. Recovery: install an adapter.
- **`:rf.error/adapter-disposed`** — an adapter was previously installed and torn down by `(rf/destroy-adapter!)` without a subsequent install. Recovery: install a fresh adapter. Common in test fixtures and hot-reload flows.

A disposed-breadcrumb (boolean) is set when `destroy-adapter!` terminally claims an
installed generation and is cleared atomically by the next successful `install-adapter!`. It
describes the terminal lifecycle/slot state, not whether host cleanup succeeded. The
claimed generation is cleared in a finally boundary even when cleanup throws, so after
destruction settles the slot is empty and a fresh adapter can install without a
collision. Exact-generation comparison prevents a stale finalizer from clearing a
replacement installation.

`(rf/adapter-disposed?)` returns the breadcrumb's value as a read-only predicate for tools and test harnesses that want to assert the lifecycle state without provoking a throw.

### Single adapter per process

One adapter per process. Frames within a process all use the same adapter.

Reasons:

1. Per-frame adapter selection adds complexity in the runtime, the registry, and the dispatch envelope (which adapter's reactivity is in scope?).
2. The use cases people propose for multi-adapter (headless tests inside a browser app; mixed Reagent and UIx) are better served by separate processes (test JVMs, separate apps) or by the existing `compute-sub` headless path (no reactivity at all).

Re-installing an adapter after frames exist is rejected (per [Adapter selection](#adapter-selection) above).

## Cross-references

- [000 §Substrate decoupling](000-Vision.md#substrate-decoupling-reagent-fusion) — the framework-level commitment to substrate decoupling.
- [011-SSR.md](011-SSR.md) — SSR boots the distinct `re-frame.ssr` adapter (`:rf.adapter/ssr`, plain-atom-shaped) on the JVM.
- [008-Testing.md](008-Testing.md) — the headless-test path uses the plain-atom adapter.
- [002-Frames.md](002-Frames.md) — frames are the core's primary structure; the adapter holds their `app-db` containers.
- [004-Views.md](004-Views.md) — view rendering is the adapter's job.
- [Derivations.md](Derivations.md) — the derivation/process algebra: subscriptions (and runtime subscriptions) are the first concrete **derivation** instance (`:storage :ephemeral`, `:evaluation :on-demand`, `:lifecycle :subscription-cache-entry`). The whole-value law every derivation obeys — memoization / equality-pruning / dirty-checks are optimizations that must not change the observable value — is owned there and cited by this substrate.
