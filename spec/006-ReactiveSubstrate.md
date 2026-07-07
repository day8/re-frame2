# Spec 006 — Reactive Substrate

> Status: Drafting. **v1-required (CLJS reference).**
>
> For where the adapter sits in relation to the rest of the runtime — the frame container, sub-cache, drain loop, and trace bus — see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

re-frame2 separates the dataflow core from the reactivity / rendering **substrate** — the abstract surface this spec defines. The substrate-agnostic core — registrar, frames, drain, dispatch envelope, subscription topology, sub computation, effect-map interpretation, trace stream — is JVM-runnable and has no dependency on Reagent, React, or DOM. A pluggable **adapter** (each implementation of the substrate contract) supplies the reactive container for `app-db`, the change-tracking that drives view re-renders, and the render-tree → surface step.

> **Substrate scope: React + VDOM.** re-frame2 commits to **React + VDOM** as the rendering substrate. The adapter contract has *two* parts. The **reactive-container** half (entries 1-5 + 9 of [§The adapter API contract](#the-adapter-api-contract): `make-state-container`, `read-container`, `replace-container!`, `subscribe-container`, `make-derived-value`, `dispose-adapter!`) is *substrate-agnostic in shape* — its description does not mention React; it would generalise to any reactive primitive. The **render-side** half (entries 6-8 + the optional `flush-render!`: `render`, `render-to-string`, `register-context-provider`, `flush-render!`) is **React-shaped**: `render` mounts via `react-dom/client.createRoot`, `render-to-string` walks a hiccup-or-equivalent virtual-DOM tree to HTML (the contract for SSR ([Spec 011](011-SSR.md))), and `register-context-provider` returns a `React.createContext`-style provider. Ports are scoped to the eight JS-cross-compile-to-React-binding languages enumerated in [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic). Non-React substrates (Vue, Solid, Svelte, vanilla DOM, Replicant, Lit) are out of scope; substrate-agnostic shape on the reactive-container side reflects "the contract generalises if we ever wanted it to," not "we ship adapters for them."

> **Terminology.** Throughout this spec, "**substrate**" names the abstract contract — the closed set of functions an adapter must implement. "**Adapter**" names each implementation: the Reagent adapter, the UIx adapter, the Helix adapter, the plain-atom adapter. The implementation directory is `implementation/adapters/`, the CLJS namespaces are `re-frame.adapter.<name>`, and the Maven artefacts are `day8/re-frame2-<name>`.

This Spec defines:

- The **boundary** between core and adapter.
- The **adapter API contract** — the closed set of functions every adapter implements.
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
- **Subscription *tracking* — the runtime side of reactivity.** The view's `subscribe` call returns a value, and when the underlying `app-db` slice changes, the view re-renders. The view-render side is React's job; how the container's mutation feeds React's render scheduler is the adapter's call: in Reagent, Reagent's reaction graph + the React renderer; in UIx / Helix / TS-React / Fable.React / Feliz / ReasonReact / Halogen-React / kotlin-react, `useSyncExternalStore` over the container's `subscribe-container` watch.

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

**Commit boundary.** The drain's commit step (per [002 §Run-to-completion §commit](002-Frames.md#run-to-completion-dispatch-drain-semantics)) installs an app-db change (`:db` effect), a runtime-db change (`:rf.db/runtime` effect), or both as **one atomic `replace-container!` on the frame-state container** (`commit-frame-transition!`). There is never a window where one partition is committed and the other is not; an app/runtime cascade is one coherent frame-state transition. The frame-state coeffect is injected by reference (no copy), so a pure app event pays nothing for the runtime partition it never touches.

Layer-1 app subs read the **app-db** projection; framework subs (`[:rf/machine <id>]`, `[:rf.route/*]`) read the **runtime-db** projection. Both are ordinary derived-value sources to the rest of the sub-cache machinery — the projection split is invisible to the invalidation algorithm below, which sees two layer-1 inputs instead of one. These runtime-db framework subs also carry **named read sugar** over their canonical vectors — `(rf/sub-machine <id>)` for `[:rf/machine <id>]` (per [005 §The `sub-machine` named read sugar](005-StateMachines.md#the-sub-machine-named-read-sugar)), `(rf/sub-route)` for `[:rf/route]` and `(rf/sub-pending-navigation)` for `[:rf/pending-navigation]` (per [012 §Reading the route is a sub](012-Routing.md#reading-the-route-is-a-sub)), and — for the optional Resources artefact — `(rf/sub-resource <query>)` for `[:rf.resource/state <query>]` and `(rf/sub-mutation <instance>)` for `[:rf.mutation/state {:instance <instance>}]` (per [016 §Subscriptions (passive)](016-Resources.md#subscriptions-passive)). The vectors stay canonical (a `:<-` chain still names the vector) and the sugar coexists with them. The convention is uniform: **runtime-db state is eligible for named read sugar; app-db content — including flow output — is read with the ordinary `subscribe`** (per [Conventions §Reserved sub-ids](Conventions.md#reserved-sub-ids)).

## The adapter API contract

Every adapter implements the surface below. The contract is **closed for v1** — the function set is fixed, signatures are fixed, dispose-after-use is fixed; new adapter capabilities ship post-v1 additively (a new fn with a feature predicate consumers can branch on).

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

### `(read-container container) → value` and `(replace-container! container new-value) → nil`

The two basic operations on a container. `read-container` is pure; `replace-container!` is the only mutation primitive — partial updates aren't supported (the core always replaces the entire **frame-state** value after a drain — both partitions in one atomic write, per [§Frame-state container and partition projections](#frame-state-container-and-partition-projections)). The container the *core's frame* holds is the frame-state container; the per-partition `app-db` / `runtime-db` projections over it are `make-derived-value` containers (read-only — never `replace-container!`d directly).

```clojure
(read-container container)                              ;; → current frame-state value {:rf.db/app … :rf.db/runtime …}
(replace-container! container new-value)                ;; → nil; container now holds new-value (one atomic frame-state install)
```

**Nil-container guard (defense-in-depth).** The core's `replace-container!` wrapper guards against the destroy-race case where a write (router `:db` commit, drain rollback, flows recompute, epoch restore, SSR write) arrives after the owning frame has been destroyed and `frame/app-db-container` has started returning nil. When `container` is nil, the wrapper SKIPS the underlying adapter's `replace-container!` call and emits an always-on `:rf.error/write-after-destroy` error (per [009 §Where trace emission lives](009-Instrumentation.md#where-trace-emission-lives)) through the always-on error-emit axis, with `:recovery :ignored` — the write is dropped, no exception is thrown. The guard centralises destroy-race handling on the one mutation primitive that every frame app-db write flows through. Adapter implementations may assume `container` is non-nil; the guard is in the core's wrapper, not in the adapter contract.

### `(subscribe-container container on-change) → unsubscribe-fn`

Optional. Registers a callback that fires *after* `replace-container!` runs. The callback receives `(prev-value, new-value)`.

```clojure
(subscribe-container container on-change)               ;; → unsubscribe-fn
;; on-change signature: (fn [prev-value new-value] ...)
;; unsubscribe-fn signature: (fn [] nil) — idempotent
```

If the adapter supports it, the core uses `subscribe-container` to wire reactive sub-cache invalidation. The CLJS reference adapters (Reagent, UIx, Helix, plain-atom) all supply it — the `add-watch`/`remove-watch` realisation is the lowest-common-denominator listener surface that every Clojure-host atom or atom-shape exposes for free. An adapter that genuinely cannot supply listeners (a host whose container primitive offers no observer hook) signals "unsupported" by either omitting the entry from its adapter spec map or returning `nil` from `subscribe-container`; in that case the core falls back to running invalidation inline within `replace-container!` itself (the adapter must, in that case, ensure `replace-container!` runs the core's invalidation hook before returning).

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

Only when the **installed adapter** has no opinion — it publishes no hook, or its routed hook returns the `container-class-unknown` sentinel — does the choke point fall back to an atom-marker heuristic: a base container satisfies the host atom marker protocol (`clojure.lang.IAtom` on the JVM, `cljs.core/IAtom` on CLJS) while the adapter's derived value (an `IDeref`-only reify, or an `IDeref`+`IWatchable`+disposal reify) does not. Note the marker is `IAtom`, not `ISwap`/`IReset`: a ClojureScript `cljs.core/Atom` implements `IAtom` but not `ISwap`/`IReset` (`swap!` / `reset!` fast-path on the concrete `Atom` type), so only `IAtom` reliably marks a base atom on both hosts. That fall-back is sound only for adapters whose base container *is* atom-shaped — the plain-atom, test-react, UIx, and Helix reference adapters, whose derived values are not atom-shaped; the Reagent family and any custom non-atom-base adapter publish the hook so the heuristic is never reached for their containers.

The derived container's caching responsibility is **adapter discretion**: an adapter MAY memoise the derived value (and is encouraged to where the host primitive makes it cheap — Reagent's `Reaction` does this for free), or MAY recompute on every `read-container` and rely on the **per-frame sub-cache** ([§Subscription cache — contract and operational semantics](#subscription-cache--contract-and-operational-semantics)) to enforce the `=`-equality invariant across recomputes. Either shape is conformant. What is NOT conformant: a derived container whose recompute fires for an input that did not change by `=` and whose downstream propagation does not collapse on `=`-equal new values — that would break the cascade rule in [§Invalidation algorithm](#invalidation-algorithm).

CLJS-Reagent: a Reagent `reaction` — memoising; re-runs only when an input deref changes by `=`. CLJS-headless (plain-atom adapter): an `IDeref` wrapper that recomputes on every read; no memoisation at the substrate layer because SSR runs each sub at most a handful of times per request and the sub-cache (when present) handles `=`-equality cascading. TS-React / UIx / Helix / other JS-cross-compile ports: an `IDeref`+`IWatchable`-shaped wrapper that recomputes on read and broadcasts change via the source containers' watch machinery (see [§CLJS reference: UIx as alternative substrate](#cljs-reference-uix-as-alternative-substrate) and [§CLJS reference: Helix as alternative substrate](#cljs-reference-helix-as-alternative-substrate)).

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

This is **distinct** from a test-only flush. The CLJS reference also ships `flush-views!` on every React-substrate adapter (Reagent, reagent-slim, UIx, Helix) — a wrapper around React's `act()` intended for test code; `flush-render!` is the production-grade contract surface every adapter implements, callable from app or tooling code with no `act()` test-environment opt-in.

`flush-render!` must be **no-op-safe**: calling it when nothing is pending does no harm and returns `nil`. An adapter that renders without a live host commit (the plain-atom / SSR adapters render to a string, never to a live surface) ships no `flush-render!` at all; the core's delegation then no-ops.

CLJS-Reagent: `(f)` then `reagent.core/flush` — Reagent's render-queue drain forces every dirty component to re-render synchronously, bypassing its `requestAnimationFrame` `next-tick` scheduler, and (on React 19) commits via `react-dom/flushSync`.
CLJS-reagent-slim: `(f)` then `reagent2.impl.batching/flush!` — the rewrite's synchronous rea-queue + dirty-set drain (`forceUpdate` per dirty component), bypassing its microtask scheduler. Distinct from the `goog.DEBUG`-gated, `act()`-composing `reagent2.dom.client/flush-views!` test primitive.
CLJS-UIx / CLJS-Helix: `react-dom/flushSync` (the React-hook spine) — runs `f` inside `flushSync` so any `useSyncExternalStore` update commits before returning.
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

When a registered view's render-fn returns a fn (Reagent's Form-2 closure shape per [Spec 004 §Form-2](004-Views.md#form-2-closure--supported-prefer-form-1--explicit-setup-event)), the adapter wraps the returned fn so the inner-fn's hiccup output is annotated on the next call. Annotation lands on the eventual rendered DOM root, not on the outer fn (which is not a DOM element).

### Cross-host

Headless test adapters (no DOM) are exempt. Every in-scope React-binding adapter MUST honour this contract: the CLJS reference (Reagent, UIx, Helix) and every JS-cross-compile-language port (TypeScript-React, Feliz / Fable.React, scalajs-react / Slinky, React.Basic, kotlin-react, ReasonReact / Melange-React). The [JVM SSR emitter](011-SSR.md#source-coord-annotation-under-ssr) is the server-side equivalent — it injects the same attribute when emitting HTML for a registered view, so server-rendered pages carry the annotation too.

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
- React-element output (UIx, Helix): `React.cloneElement` with `{"data-rf-view": <id>}` on the same call that adds `data-rf2-source-coord`.
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

1. **Component display-name = registered view-id.** Every adapter's `reg-view` wrapper MUST stamp the React `displayName` of the wrapped component to `(str view-id)` so React DevTools' component tree shows `<:cart/total-line>` rather than the CLJS-munged function name or an anonymous Reagent wrapper. Reagent's class-component machinery reads `.-displayName` off the input fn and forwards it to the constructed component; React-hook substrates (UIx / Helix) set it directly on the wrapped function component. Gated on `interop/debug-enabled?` so the per-view id-string literal elides in production builds.

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

This is the *same* refcount-on-a-shared-cache-entry liveness lifecycle that resource `:active-owners` GC uses, with one divergence: a subscription disposes synchronously in-tick at zero, whereas a resource entry keeps a **warm** `:gc-after-ms` window after its last owner releases — see [016-Resources §Kinship with subscription disposal](016-Resources.md#the-scoped-cache-lease-lifecycle).

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

### `(subscribe-once query-v) → value` / `(subscribe-once query-v {:frame f}) → value`

The **one-shot, non-reactive read** of a subscription's current value. `subscribe-once` is the canonical end-user surface for "give me the current value of this sub *right now*, and don't retain a reference on my behalf." It is the right call from event handlers, REPL sessions, SSR builders, and any non-reactive consumer; views and tools that want to track future changes use `subscribe` instead.

> **Not from inside a machine callback.** A machine `:guard` / `:action` / `:entry` / `:exit` MUST NOT call `subscribe-once` (nor read app-db any other ambient way): an in-callback ambient read is unrecorded, so replay can select a *different* transition than the original run — breaking 005's token-grain replay contract ([005 §Causal host facts](005-StateMachines.md#causal-host-facts--rfcofx-ep-0017)) and the pure-fn conformance mode. A machine callback receives external facts by **payload threading** (the triggering event carries them) or via a **declared recordable coeffect** on the machine's `:rf.cofx` record.

```clojure
(subscribe-once query-v)                              ;; → value (uses the resolved current frame)
(subscribe-once query-v {:frame f})                   ;; → value (explicit-frame opts form)
```

**Call-shape parallel with `subscribe`.** The 2-arity is **shape-discriminated on the first arg**, exactly as [`subscribe`](API.md#dispatch-and-subscribe): a query-vector first ⇒ the public `(subscribe-once query-v opts)` form (`opts` may carry `{:frame f}`; ambient when absent); a frame target first ⇒ the internal frame-first `(subscribe-once frame-id query-v)` plumbing, retired from the taught app grammar but retained for implementation / test / tooling reach. `f` is a frame-id keyword or a live frame object. Because `subscribe-once` shares `subscribe`'s exact call shape, an author who learned `(subscribe [:x] {:frame f})` writes the same `(subscribe-once [:x] {:frame f})` and the runtime binds the frame correctly — closing the same misbinding footgun EP-0024 closed for `subscribe` (`[:x]` would otherwise bind as frame-id and `{:frame f}` as query-v). `unsubscribe` (below) deliberately does **not** gain an opts-map form — it is pure teardown, never a hot in-view call, so the frame-first form is the sole explicit-frame shape.

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
- **Frame-resolution.** The 1-arg form resolves the current frame via the resolution chain (dynamic-var tier, React-context tier when an adapter has registered the `:adapter/current-frame` late-bind hook per [§Frame-provider via React context](#frame-provider-via-react-context)). There is **no `:rf/default` fallback**: with no scope established the resolution raises `:rf.error/no-frame-context`. The 2-arg forms are explicit and bypass the chain — both the public `(subscribe-once query-v {:frame f})` opts form and the internal frame-first `(subscribe-once frame-id query-v)` target `f` / `frame-id` directly.
- **Missing frame is not an error.** `subscribe-once` against a destroyed or never-created frame returns `nil` (and emits the same always-on `:rf.error/frame-destroyed` error `subscribe` does — recovery `:replaced-with-default`, per [§Lifetime contract — frame disposal](#lifetime-contract--frame-disposal) and [002 §Destroy](002-Frames.md#destroy)); it does NOT throw. (`subscribe-once` reaches this via its internal `subscribe` call.)
- **Missing sub is not an error.** Per [§What happens when a sub references an unknown sub](#what-happens-when-a-sub-references-an-unknown-sub), an unregistered `query-v` emits `:rf.error/no-such-sub` (recovery `:replaced-with-default`) and yields `nil`; `subscribe-once` propagates the `nil`.
- **JVM-runnable.** `subscribe-once` is part of the substrate-agnostic call-site surface; it works against the plain-atom adapter (no Reagent dependency). On the JVM, the deref step reads the substrate's container directly; tests, SSR builders, and headless tools rely on this.

**Where it differs from `compute-sub`.** `compute-sub` (per [008 §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm)) is a *pure* function over an explicit `app-db` value — it bypasses the cache entirely and runs the sub's body fresh. `subscribe-once` is *cache-aware*: it materialises the cache entry (cache hit reuses; cache miss populates briefly), then immediately drops its reference (sync dispose on the 1 → 0 transition). Use `compute-sub` when you want to test a sub's body against a snapshot in isolation; use `subscribe-once` when you want what the running frame would see right now.

### `(unsubscribe query-v) → nil` / `(unsubscribe frame-id query-v) → nil`

The **explicit teardown** of a `subscribe` call. `unsubscribe` decrements the cache entry's ref-count by 1; on the 1 → 0 transition, the cache slot is disposed **synchronously** (per [§Reference counting and disposal](#reference-counting-and-disposal)). Reagent views auto-dispose via the reaction lifecycle and do not need to call `unsubscribe` explicitly; tests, REPL sessions, tools, and machine actions that subscribed imperatively are the call sites that need it.

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

**Why explicit teardown exists alongside auto-disposal.** The reactive lifecycle handles the *automatic* case: a view unmounts, the reaction disposes, the underlying `unsubscribe` fires from the reaction's on-dispose hook, the slot drains in-tick. Explicit `unsubscribe` is the imperative-callers' equivalent: tools, REPL sessions, machine actions, and tests that took out a subscription without an enclosing reaction lifecycle to manage it. Both paths funnel into the same ref-count decrement and the same synchronous-on-zero dispose — one disposal algorithm, two arrival surfaces.

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

The CLJS reference ships **two** adapters across **two Maven artefacts**: the plain-atom (JVM/headless) adapter ships in the core artefact (`day8/re-frame2`); the Reagent adapter ships in its own sibling artefact (`day8/re-frame2-reagent`). Both implement the closed ten-fn contract above; the runtime picks per platform. UIx and Helix adapters ship as further sibling artefacts as they land. Per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention).

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

**The CLJS-reference shape.** The shared `re-frame.adapter.context/frame-context` primitive lives in the **core artefact** (`day8/re-frame2`) — a CLJS-only file that the JVM build does not load (per [000 §C2 Cross-platform](000-Vision.md#c2-cross-platform-jvm-interop-preserved)). Every React-shaped CLJS adapter (Reagent, UIx, Helix) consumes it; mixed-substrate apps therefore compose providers across substrates rather than silos.

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

**Adapter responsibility — `:adapter/current-frame` late-bind hook.** Each React-shaped substrate adapter (Reagent, UIx, Helix) MUST register its React-context-aware `current-frame-id` impl through the `:adapter/current-frame` late-bind hook at namespace-load time. `re-frame.subs/subscribe`, `re-frame.subs/subscribe-once`, `re-frame.subs/unsubscribe`, and the dispatch envelope's `:frame` default consult the hook on CLJS so the React-context tier of the resolution chain is **live** rather than dead code. Without the registration the call sites fall back to `re-frame.frame/current-frame` (dynamic-var tier only); the React-context tier silently no-ops to nil. The impl MUST return **nil** (not `:rf/default`) when no scope names a frame, so a public frame-scoped op raises `:rf.error/no-frame-context` rather than synthesising a default. Hook signature: `(fn frame-id-keyword-or-nil)`.

**Hook routing is by stable token, not object identity.** A test bundle (or a port that ships more than one adapter) may load several adapter namespaces, each publishing the same `:adapter/*` hook key. Each adapter wraps its impl in a routing closure that fires **only when that adapter is the installed one**, chaining to the previously-registered handler otherwise (the CLJS reference helper is `re-frame.substrate.adapter/route-hook!`). The closure decides "is this my adapter?" by **stable token — the canonical `:kind` discriminator — NOT object identity**. This matters because the adapter spec map is a **value**: a consumer may copy, `assoc`, or `merge` a canonical adapter map (for instrumentation, local overrides, or the adapter-swap pattern) and install the copy. A copy is value-equal but a distinct object, so an object-identity guard would silently serve **stale, inert hooks** for it — `rf/init!` returns green and `current-adapter` looks right, but every routed hook falls through to its chain bottom: `:adapter/current-frame` resolution dies (the chain bottom is nil → a frame-scoped op raises `:rf.error/no-frame-context`; there is no `:rf/default` floor), source/view annotation and after-render no-op, and the ratom family's `:adapter/derived-container?` guard stops firing. Routing by the `:kind` token instead makes a copied canonical map dispatch to its adapter's **live** hooks, which is the contract the bullet above requires. A genuinely custom adapter that did not pick a canonical `:rf.adapter/*` `:kind` (its `:kind` is absent or `:custom`) carries no distinguishing token and so falls back to object identity — two distinct `:custom` adapters are never conflated by a shared `:custom` keyword. The same stable-token rule governs any adapter-side driver guard that asks "is MY adapter installed?" (e.g. the Test-React `mount!` driver accepts a copied Test-React map).

The impl is substrate-specific:

- **Reagent** registers `re-frame.views/current-frame`, which uses Reagent's class-component `(.-context cmp)` path. The path is intentionally narrow — it surfaces context only to components whose `:contextType` matches `frame-context` (i.e. components registered via `reg-view*`). A plain Reagent fn lacks the `:contextType`, so its `(.-context cmp)` is the no-provider sentinel and the reader returns nil — a public frame-scoped op then raises `:rf.error/no-frame-context`.
- **UIx / Helix** register `re-frame.adapter.context/function-component-current-frame`, which reads `_currentValue` directly off the shared context object. Function components have no `(.-context cmp)` slot, so `_currentValue` is the substrate-portable path; UIx's `use-context` and Helix's `use-context` are sugar over the same read. The no-provider sentinel resolves to nil; a non-keyword, non-sentinel value emits `:rf.error/frame-context-corrupted` (recovery `:no-frame-context`) and returns nil.

Both impls share the dynamic-var tier (`re-frame.frame/*current-frame*`, set by `with-frame` / `frame-bound-fn`) and bottom out at **nil** (no `:rf/default` tier); only the middle (React-context) tier differs. The canonical user-facing surface (`rf/frame-provider`) mounts the Provider via Reagent's `:r>` interop head so the props map bypasses `reagent.impl.template/convert-prop-value` — the `:value` keyword (namespace and all) reaches React unchanged. As defensive cover, both impls round-trip the prop-stringified shape via `re-frame.adapter.context/coerce-context-value` so a raw-hiccup `[:> Provider {:value :tenant}]` mount (not via `rf/frame-provider`) is still observed correctly by every substrate. The helper is lossy for namespaced keywords on raw-hiccup mounts under the classic adapter (`(name :foo/bar)` is `"bar"`); raw-hiccup mounts that need namespaced frame-ids should switch to `rf/frame-provider` or `re-frame.adapter.context/provider-element`.

**Plain-fn footgun is `:rf.error/no-frame-context`.** A plain Reagent fn (not registered via `reg-view`) cannot read the closest enclosing `frame-provider` because it lacks the `^{:contextType frame-context}` metadata `reg-view` attaches. Such a plain fn's ambient `(rf/subscribe ...)` / `(rf/dispatch ...)` resolves to nil and raises `:rf.error/no-frame-context` — the operation fails fast rather than silently routing to a conventional default. There is no silent fall-through to `:rf/default`. To capture the surrounding frame a plain fn must use `reg-view`, `with-frame`, or a captured `(rf/capture-frame)`; the 2-arity `(subscribe frame-id query-v)` form is the explicit opt-out for a known frame.

### The sub-override subscribe seam (debug-gated)

`re-frame.subs/subscribe` carries one **substitutive**, debug-gated late-bind hook — `:subs/resolve-sub-override` — that lets a development tool *replace* a subscription's value at the view's deref point without touching app-db. It is the read-side of [Story's `:sub-overrides` fidelity rung](../tools/story/spec/017-Testing-Story.md#view-state-subscription-overrides): a designer pins a view into an `:error` / `:loading` / `:empty` state by naming exact subscription query-vectors and the values they should surface — no events, no app-db seed.

**Carriage — React context, not a dynamic var.** The override map (`{query-vector value}`) must survive from the Story render-scope component into the **descendant** view's own, *deferred* React render — the view's `@(rf/subscribe [:q])` runs later, in its own reaction, several component layers deep. A `binding`-bound dynamic var does NOT survive that boundary (the binding unwinds before the descendant renders). The carriage that does is a **React context** — React mutates a context's `_currentValue` as Provider boundaries are entered/exited during render, so a read from inside any descendant render sees the closest enclosing Provider's value. This is the exact mechanism the frame-id uses (§Frame-provider via React context above); the override carriage mirrors it in a sibling CLJS-only context object whose default value is `nil` (no overrides in scope). The tool wraps the variant view in that Provider; core reads the closest enclosing map at subscribe time.

**Consult — `:subs/resolve-sub-override`.** Inside the same `(when interop/debug-enabled? …)` envelope that gates the observational subscribe-time hooks (`:views/record-view-deref!`, the plain-fn warning), `subscribe` consults the hook with the query-vector. The hook returns a **one-element vector `[value]`** on an exact-query-vector HIT (a one-element vector — never a bare value — so a `nil`-valued override is still honoured as a hit) or `nil` on a miss / no Provider in scope / production. On a HIT, `subscribe` short-circuits build-and-cache and returns a **constant reaction** (a derived value with no inputs that always yields the pinned value): it never recomputes, is never cached, and is never invalidated. On a miss / unbound / production, `subscribe` is byte-for-byte unchanged. The whole block elides under `:advanced` + `goog.DEBUG=false`.

**Bundle isolation.** Core only *declares* the hook key and *consults* it; the resolver that reads the override-context Provider is *published* from the tool side (Story) via late-bind, so core never statically requires a tools namespace. The context-carriage object lives in core (CLJS-only) because core already depends on React — but it is read only on the dev consult path, which DCEs in production.

**Honesty boundary (load-bearing).** The override feeds **only** the constant reaction the view derefs. It NEVER writes app-db and NEVER reaches [`compute-sub`](008-Testing.md#compute-sub-algorithm). Because `:rf.assert/sub-equals` (and every subscription assertion) evaluates a sub *through* `compute-sub` against the real app-db, an override can **never** satisfy a subscription assertion. Subscription *correctness* is proven by real setup events / a schema-checked app-db seed / `compute-sub` — never by an override. This rung is, by construction, a picture for the eye, not proof.

**Override schema-validation.** When an override HIT targets a sub that declares an output `:schema` (per [010 §Validation order step 6](010-Schemas.md#validation-order-on-event-processing)), core validates the pinned value against that schema the SAME way `:where :sub-return` does — through the registered validator reached via the `:schemas/validate-with-registered-fn` late-bind hook, dev-only. A mismatch emits `:rf.error/schema-validation-failure` with a `:where :sub-override` discriminator and surfaces `nil` (mirroring `:sub-return`'s `:replaced-with-default` recovery — observational; the failure is reported, the violating value is not surfaced). An override that violates the sub's own output contract is exactly the "pin a state the real derivation could never produce" anti-pattern; validating it closes that honesty gap. See [010 §Validation order](010-Schemas.md#validation-order-on-event-processing).

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

;; UIx (CLJS, day8/re-frame2-uix):
(require '[re-frame.core :as rf]
         '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

;; Helix (CLJS, day8/re-frame2-helix):
(require '[re-frame.core :as rf]
         '[re-frame.adapter.helix :as helix])
(rf/init! helix/adapter)

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

The CLJS adapter namespaces (Reagent, UIx, Helix) and the SSR namespace each export their `adapter` Var; the contract surface is the same ten-fn map (see [§The adapter API contract](#the-adapter-api-contract) above). The plain-atom adapter in `re-frame.substrate.plain-atom` is reachable on both JVM and CLJS — useful for headless tests on either platform.

## CLJS reference: UIx as alternative substrate

The UIx adapter ships in `day8/re-frame2-uix` and implements the same ten-fn contract as the Reagent adapter — same observable behaviour for events, subs, effects; different rendering substrate for views.

The UIx adapter's design decisions are:

1. **Hook naming.** The substrate's subscription surface is `use-subscribe`, matching the React/UIx idiom. Symmetric ergonomics to Reagent's `(rf/subscribe ...)` deref shape; asymmetric naming because hooks live in hook-named space.
2. **Frame propagation.** Both the UIx and Reagent adapters read the *same* React Context object — factored out of `re-frame.views` into `re-frame.adapter.context` (CLJS-only file in core). A future mixed-substrate app's frame-provider chain therefore composes across substrates rather than living in per-adapter silos.
3. **Auto-injection.** None for UIx. Components call `(use-subscribe [:foo])` and `(:dispatch (rf/capture-frame))` directly — there is no UIx-side analogue to `reg-view`'s `dispatch` / `subscribe` lexical bindings. The hook surface is the canonical UIx access path.
4. **`reg-view` macro scope.** `reg-view` stays Reagent-only (auto-defs the Var, auto-injects the lexical `dispatch` / `subscribe`, threads source-coords through Reagent's `:contextType` machinery). UIx users register views via `reg-view*` (the plain-fn surface in `re-frame.core`); source-coord stamping for UIx-rendered roots happens at the adapter's render-time wrapper, not at registration time.
5. **Source-coord DOM annotation.** The UIx adapter wraps user components in a thin layer that calls `React.cloneElement` to add `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element when `interop/debug-enabled?` is true. Production-elision contract: under `:advanced` + `goog.DEBUG=false` the entire wrapper branch DCEs and the literal `data-rf2-source-coord` string fragment is absent from the bundle. Fragments and non-DOM roots are exempt with the standard one-shot warning per id.
6. **Render flush for tests.** The adapter exposes `flush-views!` wrapping React's `act()`. Tests dispatching against a UIx-mounted tree call `(flush-views!)` after a dispatch to settle pending React effects before reading the DOM. The entry point is **per-adapter-require** — `(uix-adapter/flush-views!)`, NOT centralised through `re-frame.test-support` — per the adapter-dependency-direction rule in [§What an adapter MUST NOT do](#what-an-adapter-must-not-do); see [Spec 008 §Adapter-aware test helpers](008-Testing.md#adapter-aware-test-helpers--flush-views) for the test-author-facing rationale.
7. **Curated example set.** counter + login (under `examples/substrates/uix/counter/` and `examples/substrates/uix/login/`) — the representative pair that shares its substrate-agnostic dataflow (events, subs, schemas, machine, managed-HTTP stub) with the Reagent siblings, chosen because it spans the substrate-contract surface a UIx app exercises. Realworld is skipped per Decision 7 — heavy with Reagent-flavoured idioms; deferred until a UIx user wants it. **Coverage shape:** the `examples/` tree is test-free, so these two example pages carry *compile coverage* only (`test:examples-compile`); the *runtime* substrate-contract smoke (mount → subscribe → dispatch → re-render) is the single adapter-owned testbed at `implementation/adapters/uix/testbed/` (one mount+dispatch+assert smoke per adapter), not a per-example browser gate. Substrate-agnostic behaviour the login page would exercise — the login machine, its Malli schemas, the managed-HTTP stub — is covered by the canonical Reagent suite and the feature artefacts' own tests; the UIx-specific view-layer surface (`use-subscribe`, capture-frame capture, after-render flush, source-coord DOM annotation) is covered by the UIx adapter's CLJS tests under `implementation/adapters/uix/test/`. See [Conventions §Adapter test matrix policy](Conventions.md#adapter-test-matrix-policy).
8. **UIx version target.** UIx 2.x (hooks-based). UIx 1.x back-compat is explicitly out of scope.

The CLJS-reference code follows the same per-contract-fn shape as the Reagent adapter; the differences are at the React layer:

- `make-state-container` returns a `clojure.core/atom` rather than a Reagent `r/atom` — UIx has no built-in reactive atom primitive. View-side reactivity flows through `useSyncExternalStore` in `use-subscribe` rather than through Reagent reactions.
- `make-derived-value` returns an `IDeref`+`IWatchable` wrapper that recomputes on deref and broadcasts changes via the source containers' watch machinery. Equality-on-= invariants ride on the core's sub-cache (Spec 006 §Invalidation algorithm), not on the substrate's caching.
- `render` wraps `react-dom/client.createRoot` + `root.render`; the unmount-fn calls `root.unmount()`.
- `register-context-provider` returns a UIx `defui` component reading the shared `frame-context` via `use-context`.

Every other adapter primitive (read, replace, subscribe-container, dispose) is structurally identical to the Reagent adapter's — the contract is genuinely substrate-agnostic.

## CLJS reference: Helix as alternative substrate

The Helix adapter ships in `day8/re-frame2-helix` and implements the same ten-fn contract as the Reagent and UIx adapters — same observable behaviour for events, subs, effects; different rendering substrate for views. Helix occupies the *minimal-React-wrapper* niche: it is structurally similar to UIx (React + hooks; no reactive-atom primitive) but ships a smaller surface and does not auto-instrument hooks.

The Helix adapter's decisions transfer one-for-one from the UIx adapter — the React + hooks substrate model is the same:

1. **Hook naming.** `use-subscribe` (matches the React/Helix idiom).
2. **Frame propagation.** Reads the same React Context object the Reagent and UIx adapters consume (`re-frame.adapter.context/frame-context` in core).
3. **Auto-injection.** None. Components call `(use-subscribe [:foo])` and `(:dispatch (rf/capture-frame))` directly.
4. **`reg-view` macro scope.** Stays Reagent-only; Helix users register registry-keyed views via `reg-view*` (the plain-fn surface) when they need it. Most Helix components are bare `defnc` and don't need registry addressing.
5. **Source-coord DOM annotation.** The Helix adapter wraps user components in a thin layer that calls `React.cloneElement` to add `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element when `interop/debug-enabled?` is true. Production-elision contract: under `:advanced` + `goog.DEBUG=false` the entire wrapper branch DCEs. Same Fragment / non-DOM-root exemption as the UIx adapter.
6. **Render flush for tests.** `flush-views!` wrapping React's `act()` — same surface as the UIx adapter. Per-adapter-require entry point (`(helix-adapter/flush-views!)`) per the adapter-dependency-direction rule in [§What an adapter MUST NOT do](#what-an-adapter-must-not-do); see [Spec 008 §Adapter-aware test helpers](008-Testing.md#adapter-aware-test-helpers--flush-views) for the test-author-facing rationale.
7. **Curated example set.** counter + login (under `examples/substrates/helix/counter/` and `examples/substrates/helix/login/`) — the representative pair that shares its substrate-agnostic dataflow (events, subs, schemas, machine, managed-HTTP stub) with the Reagent siblings, chosen because it spans the substrate-contract surface a Helix app exercises. Realworld is skipped — same rationale as UIx (heavy with Reagent-flavoured idioms; deferred until a Helix user wants it). **Coverage shape:** the `examples/` tree is test-free, so these two example pages carry *compile coverage* only (`test:examples-compile`); the *runtime* substrate-contract smoke (mount → subscribe → dispatch → re-render) is the single adapter-owned testbed at `implementation/adapters/helix/testbed/` (one mount+dispatch+assert smoke per adapter), not a per-example browser gate. Substrate-agnostic behaviour the login page would exercise — the login machine, its Malli schemas, the managed-HTTP stub — is covered by the canonical Reagent suite and the feature artefacts' own tests; the Helix-specific view-layer surface (`use-subscribe`, capture-frame capture, after-render flush, source-coord DOM annotation) is covered by the Helix adapter's CLJS tests under `implementation/adapters/helix/test/`. See [Conventions §Adapter test matrix policy](Conventions.md#adapter-test-matrix-policy).
8. **Helix version target.** Helix 0.2.x (the latest published Helix release line). Older Helix versions are explicitly out of scope.

Implementation notes:

- `make-state-container` returns a `clojure.core/atom` rather than a Reagent `r/atom` — Helix has no built-in reactive atom primitive (same as UIx). View-side reactivity flows through `useSyncExternalStore` in `use-subscribe`.
- `make-derived-value` returns an `IDeref`+`IWatchable` wrapper that recomputes on deref and broadcasts changes via the source containers' watch machinery — structurally identical to the UIx adapter.
- `render` wraps `react-dom/client.createRoot` + `root.render` (Helix doesn't ship a `helix.dom/render-root` wrapper of its own; the lower-level call is the cross-version-stable path).
- `register-context-provider` returns a Helix `defnc` component reading the shared `frame-context` via `helix.hooks/use-context`.
- `use-subscribe` calls `React.useSyncExternalStore` directly because `helix.hooks` doesn't ship a `use-syncExternalStore` wrapper (Helix is the minimal-wrapper substrate); deps are wired through `helix.hooks/use-memo*` / `use-callback*` (the function-form hooks) so the adapter doesn't pull in Helix's macro layer.

Every other adapter primitive (read, replace, subscribe-container, dispose) is structurally identical to the Reagent and UIx adapters' — the contract is genuinely substrate-agnostic, and the Helix port surfaces no friction against the decision set.

## Cross-substrate affordance summary

The ten-fn substrate contract is identical across adapters, but the three **view-author-facing** surfaces — *read a subscription*, *scope a frame to a subtree*, *flush pending renders in a test* — differ per substrate because each rides its host's idiom (Reagent's reactive deref vs the React-hooks model). A dev moving between substrates needs the one-glance map; this table is it. Each React-shaped adapter's provider is a **native substrate component** (UIx `defui`, Helix `defnc`). The *scope* surface below is the merged `frame-provider {:frame …}` (see [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)): the merged `frame-provider` dispatches on shape — `{:frame …}` scopes an existing frame into a React subtree (failing loud if absent), `{:id …}` ensures a named frame; see [002 §the merged `frame-provider`](002-Frames.md#the-merged-config-shaped-frame-provider-cljs-reference). The provider is realized per-adapter and reads the same React context.

| Affordance | Reagent (`day8/re-frame2-reagent`) | UIx (`day8/re-frame2-uix`) | Helix (`day8/re-frame2-helix`) |
|---|---|---|---|
| **Read a subscription** | `@(rf/subscribe [:q …])` — reactive deref inside a `reg-view`/Form-2 render fn. | `(use-subscribe [:q …])` — React hook (re-renders on change via `useSyncExternalStore`). | `(use-subscribe [:q …])` — React hook (same surface as UIx). |
| **Explicit-frame read** | `@(rf/subscribe frame-id [:q …])` (2-arg). | `(use-subscribe frame-id [:q …])` (2-arg). | `(use-subscribe frame-id [:q …])` (2-arg). |
| **Frame resolution (1-arg form)** | dynamic-var → React-context (the surrounding provider, of either family member) → **nil** (no `:rf/default` floor; raises `:rf.error/no-frame-context`). | Same chain; React-context tier read via `use-context`. | Same chain; React-context tier read via `use-context`. |
| **Scope an existing frame to a subtree** (`frame-provider {:frame …}`) | Native hiccup component; **trailing-positional children**: `[rf/frame-provider {:frame :f} [header] [main]]` — provide an existing frame's id; fail loud if absent. | Native `defui` component, mounted via `$`; **idiomatic `$` trailing children**: `($ frame-provider {:frame :f} ($ header) ($ main))`. | Native `defnc` component, mounted via `$`; **idiomatic `$` trailing children**: `($ frame-provider {:frame :f} ($ header) ($ main))`. |
| **Ensure a named frame for a subtree** (`frame-provider {:id …}`) | `[rf/frame-provider {:id :f :images […]} [header] [main]]` — create-if-absent / reuse-no-reseed / provide id; **no destroy-on-unmount**; takes `make-frame` opts. | `($ frame-provider {:id :f :images […]} ($ header) ($ main))`. | `($ frame-provider {:id :f :images […]} ($ header) ($ main))`. |
| **`nil` `:frame`** (scope shape) | CONFIGURATION ERROR: the merged `frame-provider {:frame …}` SCOPE shape requires a keyword `:frame`; a `nil` `:frame` emits + throws `:rf.error/no-frame-context` — no `:rf/default` floor (see [§Frame-provider via React context](#frame-provider-via-react-context)). | Same — raises `:rf.error/no-frame-context`. | Same — raises `:rf.error/no-frame-context`. |
| **Frame keyword fidelity under the mount idiom** | `:r>` interop head bypasses Reagent prop conversion, so a namespaced frame keyword survives the React-context round trip. | The native `defui` routes props through UIx's lossless `argv` channel (and folds native trailing children onto `:children` via `glue-args`) — keyword frame-ids survive intact by construction. | The native `defnc` routes props through `extract-cljs-props` (keyword keys + preserved keyword values, native trailing children lifted onto `:children`) — survives by construction. |
| **Flush pending renders in a test** | `reagent-adapter/flush-views!` (wraps React's `act()` — the canonical cross-substrate test-flush hook); Reagent's own `r/flush!` also works, and `reagent-slim` ships `reagent2.dom.client/flush-views!`. | `(uix-adapter/flush-views!)` — wraps React's `act()` (per-adapter-require entry point). | `(helix-adapter/flush-views!)` — wraps React's `act()` (same surface as UIx). |
| **`reg-view` macro** | Available (canonical view-registration surface). | `reg-view*` (plain-fn) when registry addressing is needed; most components are bare `defui`. | `reg-view*` (plain-fn) when needed; most components are bare `defnc`. |

All three adapters read the **same** React Context object (`re-frame.adapter.context/frame-context` in core), so a mixed-substrate provider chain (either family member) composes — a UIx subtree under a Reagent provider (or vice versa) resolves the same frame.

> **Unified call shape.** The merged `frame-provider` passes children consistently across substrates in both config shapes. Reagent takes trailing-positional hiccup children; UIx and Helix take **native `$` trailing children** — `($ frame-provider {:frame :f} ($ header) ($ main))` — exactly the shape every other UIx/Helix component uses. The native `defui`/`defnc` shells read children off the `:children` key their element macro folds the trailing args onto (UIx's `glue-args`, Helix's `extract-cljs-props`), so there is no `:children`-in-props-map key for an author to forget — the silent-drop footgun is eliminated by construction.

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

This is a Reagent-substrate concern, not a core-framework one. Non-React substrates that wire reactivity through hooks (UIx, Helix) use `use-subscribe` per-call-site, which captures the dependency at hook-call time regardless of when the surrounding seq realises — they are immune to the lazy-seq trap by construction. Core's `compute-sub` is pure and orthogonal: no tracking, no scope.

## SSR-specific behaviour

Per [011](011-SSR.md), the server-side render path doesn't use the adapter's reactivity machinery at all. The flow:

1. Server creates a frame (per [002 §reg-frame](002-Frames.md#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)).
2. The frame's `app-db` is a plain atom (the **core's plain-atom adapter**, not the Reagent adapter).
3. `:initial-events` run; the drain settles.
4. The view fn is called as a *plain function* against the now-stable `app-db` value.
5. The hiccup output is rendered to a string by `render-to-string`.

No Reagent. No React. No reactivity. Pure data → pure data → string.

The adapter that the core uses on the server is the **plain-atom adapter** (or "headless adapter"). The CLJS reference ships this alongside the Reagent adapter; the runtime picks based on platform.

## CLJS reference scope

The CLJS reference ships across multiple Maven artefacts (per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention)):

- **`day8/re-frame2`** — the substrate-agnostic core (the registrar, the drain, the dispatch envelope, the trace stream, sub topology, sub computation, effect-map interpretation) plus the adapter API contract, the **plain-atom (headless) adapter** used by SSR and headless tests, and (per Decision 2) the shared React frame Context object at `re-frame.adapter.context` that every React-shaped adapter consumes.
- **`day8/re-frame2-reagent`** — the **Reagent adapter** (browser default).
- **`day8/re-frame2-uix`** — the **UIx adapter**. Targets UIx 2.x; ships the `use-subscribe` hook (Decision 1), the `flush-views!` test-flush helper (Decision 6), a source-coord wrapping component (Decision 5), and a `frame-provider` consuming the shared React context (Decision 2). Apps written for UIx call `reg-view*` (plain-fn) directly — the `reg-view` macro stays Reagent-flavoured per Decision 4.
- **`day8/re-frame2-helix`** — the **Helix adapter**. Targets Helix 0.2.x; ships the same `use-subscribe` hook, `flush-views!` test-flush helper, source-coord wrapping component, and shared-context `frame-provider` as the UIx adapter. Apps written for Helix call `reg-view*` (plain-fn) directly — the `reg-view` macro stays Reagent-flavoured per Decision 4. The eight UIx decisions transferred unchanged because Helix and UIx share the React + hooks substrate model.

In the CLJS reference repository the three adapter sources live under `implementation/adapters/<name>/` — `implementation/adapters/reagent/`, `implementation/adapters/uix/`, `implementation/adapters/helix/`. Per-feature artefacts (`schemas`, `machines`, `routing`, `flows`, `http`, `ssr`, `epoch`) stay flat under `implementation/<name>/`. The directory split surfaces the adapter-vs-per-feature distinction in the layout — adapters implement the [§adapter API contract](#the-adapter-api-contract); per-feature artefacts plug into core via the late-bind hook table per [Conventions §Independence rule](Conventions.md#independence-rule). Maven artefact names are unchanged across the move: the directory is `adapters/`, not `substrates/` — "substrate" names the abstract contract, "adapter" names each implementation.

Per-host adapters for non-CLJS implementations ship as separate packages, implementing the same contract — the per-adapter-artefact pattern is JS-cross-compile-language-agnostic across the eight in-scope hosts (TypeScript-React, Fable.React / Feliz, scalajs-react / Slinky, React.Basic, kotlin-react, ReasonReact, Melange-React, Squint-with-React). All ship a React-binding adapter; non-React substrates are out of scope per [§Abstract](#abstract).

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): these items are **post-v1, untracked notes** — design directions in scope for re-frame2 beyond v1 but with no concrete tracking bead filed yet (so none qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`). "Cooperative rendering substrate" is deferred to a later cycle's benefits-vs-cost evaluation; "Multi-adapter coexistence" is additive on the v1 single-adapter contract once a concrete use case emerges; "CEDN-float cache-key extension" is a flagged reconciliation for review (see [§Host value model](#host-value-model--rf-equality-and-value-keyed-caching)). A tracking bead is filed for each only when the reconsideration trigger below fires; until then they remain notes, not committed work.

### Cooperative rendering substrate (post-v1)

A cooperative rendering substrate — a rendering layer designed natively to cooperate with re-frame, instead of re-frame wrapping Reagent — is on the horizon. Substrate-agnostic decoupling (this Spec) is the prerequisite. Whether the cooperative variant ships depends on a benefits-vs-cost evaluation in a later cycle. Deferred to a post-v1 cycle (untracked note — no bead filed yet).

#### Post-v1 Tracking

- **Foundation in v1.** The adapter contract (per [§The adapter API contract](#the-adapter-api-contract)) is the substrate-decoupling primitive — any cooperative variant ships as another adapter, no core change required.
- **Scope deferred.** The evaluation itself: identifying the cooperation primitives a native substrate could expose (e.g., scheduler-aware re-render coalescing, subscription-graph-driven scheduling, batched view updates aligned to drain boundaries), and the benefits-vs-cost ledger against staying with Reagent / UIx / Helix adapters.
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

### Adapter introspection

Two complementary accessors:

- `(rf/current-adapter)` returns a **discriminator keyword** identifying the active adapter (the `:kind` slot of the installed adapter spec map), or `nil` if no adapter is installed. Canonical values live under the `:rf.adapter/*` reserved namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned),) so third-party adapters can publish their own unqualified `:kind` keywords without collision risk:

  - `:rf.adapter/reagent` — CLJS browser default (bridge adapter)
  - `:rf.adapter/reagent-slim` — CLJS browser, slim adapter (no stock-Reagent dep)
  - `:rf.adapter/uix` — CLJS browser, UIx substrate
  - `:rf.adapter/helix` — CLJS browser, Helix substrate
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

A disposed-breadcrumb (boolean) is set by `destroy-adapter!` and cleared by the next successful `install-adapter!`. Both states leave the install slot empty so a fresh adapter can install without a slot collision.

`(rf/adapter-disposed?)` returns the breadcrumb's value as a read-only predicate for tools and test harnesses that want to assert the lifecycle state without provoking a throw.

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
- [Derivations.md](Derivations.md) — the derivation/process algebra: subscriptions (and runtime subscriptions) are the first concrete **derivation** instance (`:storage :ephemeral`, `:evaluation :on-demand`, `:lifecycle :subscription-cache-entry`). The whole-value law every derivation obeys — memoization / equality-pruning / dirty-checks are optimizations that must not change the observable value — is owned there and cited by this substrate.
