# re-frame2 integration

## Integration principle

re-frame2 UI is not “React with a convenient store Hook.” The view compiler is a first-class client of the framework's carried frame, derivation graph, event router, resource owners, epoch scheduler, view registry, error surface, and instrumentation vocabulary.

It does not replace any of them.

## Contract map

| re-frame2 concern | Current contract | New substrate realization |
|---|---|---|
| Frame state | one frame-state container with app/runtime projections | Adapter implements the existing container quartet; generated views resolve and commit an exact frame ID. |
| Subscription definitions | `reg-sub`, query vectors, cached derivation graph | Unchanged. `ui/sub` is a compiled observation site over those nodes. |
| Subscription lifetime | per-frame cache ref counts, synchronous zero-owner disposal | ViewCell acquires after commit and releases on retarget, unmount, or React Activity disconnection. |
| Events | data vectors, frame-carried dispatch, run-to-completion drain | DOM event vectors compile to stable callbacks that dispatch through the existing router with an explicit committed frame. |
| Epochs | coherent derivation recompute and trace attribution | Adapter flushes each dirty ViewCell once after its derivation scheduler drains. |
| Resources | explicit ensure/refetch commands, passive reads, owner leases | `ui/lease` aggregates commit-owned view leases; `ui/sub [:rf/resource ...]` remains passive. |
| Machines | registered event-driven runtime | Views read machine subs and send machine events; no component actor system is added. |
| Views | registry metadata, source coords, instance render keys | `defview` registers compiler metadata and a stable React component; ViewCell supplies committed instance facts. |
| SSR | per-request frames, JVM render tree, hydration payload | JVM codegen uses the same template AST and existing SSR artefact; client hydrates after installing the frame payload. |
| Tooling | trace/error buses, Xray graph, production elision | Compiler and committed cell records feed existing vocab; debug-only detail is erased in production. |

## Adapter implementation

`re-frame.ui/adapter` implements the existing [Spec 006 adapter contract](../../../spec/006-ReactiveSubstrate.md):

- `make-state-container`
- `read-container`
- `replace-container!`
- `make-derived-value`
- `render`
- `render-to-string`
- optional `subscribe-container`
- optional `register-context-provider`
- optional `flush-render!`
- `dispose-adapter!`

The state/derivation side is a React-native plain-container adapter, not a Reagent ratom adapter. Its derived-value scheduler retains the current UIx/Helix guarantees: lazy computation, coherent multi-input recompute, equality suppression, and synchronous disposal.

The scheduler also owns the adapter-local dirty ViewCell set. `replace-container!` opens a reactive epoch, drains all affected derivations, then advances each dirty cell once. This is an implementation-level guarantee of the new adapter; no ViewCell callback is added to the generic public adapter map.

`render` receives a native React element emitted by the compiler. It never accepts arbitrary Hiccup on CLJS. `render-to-string` remains a late-bound SSR seam so the core artefact does not pull a server renderer into browser bundles.

## The one focused core addition

The framework needs an internal subscription observation port separating probe from ownership, as specified in [ViewCell reactivity](04-view-cell-reactivity.md#subscription-observation-port).

This belongs beside the existing cache implementation, not in application API:

```clojure
(probe-sub frame-id query-v)
(acquire-sub! frame-id query-v owner on-change)
(read-sub lease)
(release-sub! lease)
```

Every existing public subscription operation can keep its current shape. Other adapters do not have to migrate. The new port makes a React render owner-free by construction and removes the current balanced subscribe/unsubscribe workaround.

No new event, resource, machine, or frame store is required.

## Frames

### Resolution

Every production view with a frame-dependent capability (`ui/sub`, events/handlers, `ui/dispatch-fn`, or `ui/lease`) reads the shared React frame context, so a provider change schedules it correctly. A props-only production view omits the context read; any frame-dependent child resolves context itself. Development's fixed full Hook skeleton reads it for HMR stability.

The actual target uses re-frame2's canonical resolution chain:

1. an explicit frame supplied at the read/site or root;
2. current dynamic frame binding;
3. surrounding React frame context;
4. loud `:rf.error/no-frame-context`.

The resolved ID is placed in the local render capture. It becomes the cell's committed frame only if that render commits.

This distinction is load-bearing for callbacks: speculative render must not retarget the click handlers attached to visible old DOM.

### Root mounting

Mounting stays explicit:

```clojure
(ns app.client
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui]
            [app.views :as views]))

(defonce root
  (ui/create-root (js/document.getElementById "app")))

(defn ^:dev/after-load render! []
  (ui/render! root views/app {}
    {:frame {:id :app/main
             :initial-events [[::boot]]}}))

(defn start! []
  (rf/init! ui/adapter)
  (render!))
```

`create-root` is a thin React DOM root. `render!` takes a `defview`, a Clojure props map at the root boundary, and options. Its `:frame` map accepts the existing merged frame-provider shapes: `{:frame existing-target}` scopes a live frame ID/value without creating it; `{:id ... :images ... :initial-events ...}` ensures a named frame, reuses it without reseeding, and leaves destruction explicit.

The explicit `rf/init!` is retained. Adapter selection is framework state and should not happen as a side effect of requiring or rendering a namespace.

### Nested frame scopes

The compiler recognizes the shared provider component:

```clojure
[ui/frame {:frame :preview/existing}
 [preview-pane {:document-id id}]]

[ui/frame {:id :sandbox/new
           :images [sandbox-image]}
 [sandbox]]
```

The first shape scopes an existing live frame and fails if absent. The second ensures a named frame and does not destroy it on unmount. These are direct spellings of the current re-frame2 provider contract, not a UI-owned frame lifecycle.

### Frame destruction

Destroying a frame disposes its derivation nodes and container state under existing rules. The adapter additionally detaches ViewCell subscription leases and resource owners targeting that frame, removes those cells from pending epoch sets, and schedules a loud error/render for any still-mounted view whose scope now resolves to the destroyed frame.

Mounted UI never silently migrates to another frame.

## Subscriptions and the derivation graph

### Definitions remain separate

`defview` does not define a selector language:

```clojure
(rf/reg-sub ::visible-todos
  :<- [::todos]
  :<- [::filter]
  (fn [[todos filter] _]
    ...))

(ui/defview todo-list []
  (let [todos (ui/sub [::visible-todos])]
    ...))
```

The subscription registration continues to own computation, schemas, sensitivity, trace identity, input topology, memoization, and reuse outside the view. The compiler-owned read site contributes consumer identity and source position.

### Static and runtime topology

The view manifest records potential literal read sites. The committed ViewCell records the actual sites and concrete query vectors used by that instance. Xray can therefore distinguish:

- “this view can read `::item`” from compile metadata;
- “instance 84 currently reads `[::item 42]`” from runtime ownership;
- “that node depends on `[::items]`” from the derivation graph.

Static metadata is never treated as a fetch plan or as proof a conditional read is active.

### Query stability

Literal queries are module constants. Parameterized sites retain the exact prior query object while args remain `rf=`. This directly prevents the fresh-equal nonprimitive argument fragmentation warning described in the current subscription implementation.

The runtime still keys and validates using the canonical query vector. The compiler is an identity stabilizer, not an alternate query representation visible to application code.

### Equality and publication

re-frame2 subscriptions decide semantic change with `rf=`. View sites preserve the previous exact value when a new read is `rf=`. This gives three layers a consistent boundary:

1. the derivation node avoids notifying on equal output;
2. the ViewCell avoids changing its revision for that node;
3. a child view's generated prop comparator receives the stable reference/value.

Mutable foreign JS objects remain identity-based unless an application subscription explicitly projects an immutable value. The substrate does not deep-clone or proxy them.

## Events

### Dispatch path

The generated callback routes through the same event router as `rf/dispatch`, supplying:

- the evaluated event vector;
- the cell's committed frame;
- development call-site metadata for the event site;
- current view render key and template path when debugging is enabled.

No dispatch occurs during render. `ui/event` describes a callback; direct event vectors are inert template data until React invokes the generated function.

### Stable callbacks

The event function is allocated once per mounted site and reads a committed slot. It is stable across renders even when the event vector contains props or subscription values.

This removes a common cascade:

```text
parent renders
  → allocates fresh onClick closure
    → memoized child sees changed prop
      → child renders for no semantic change
```

The callback still uses current committed data, unlike a careless empty-deps `useCallback`.

### Event intent across components

Reusable children should accept event data rather than frame-bound functions where practical:

```clojure
[delete-button {:event [::delete item-id]}]
```

The child turns it into a handler with `(ui/event event)`. This keeps event intent serializable on JVM, lets tests assert it, and dispatches in the child's committed frame. When a true callback protocol is required, `ui/handler` or foreign React interop remains available.

### Causality

The event-site source and render instance are attached to the dispatch trace in development. The existing epoch/run-cause machinery then connects:

```text
DOM site → dispatched event → handler/effects → changed derivations → dirty ViewCells → committed renders
```

No parallel UI trace bus or monkey patch is introduced.

## Resources

### Passive reads remain passive

```clojure
(ui/sub [:rf/resource descriptor])
```

does not ensure or refetch. It is exactly the existing resource subscription.

### View lifetime declaration

`ui/lease` is the new substrate spelling of the current UIx/Helix `use-resource-lease` and Reagent `with-resource-lease` behaviors. Differences are mechanical:

- it is not a Hook;
- all sites in a view share one passive React effect;
- each lexical site retains a distinct owner;
- descriptors can be conditional because the commit reconciler owns the set;
- source/site metadata is native.

On commit, a new site dispatches `:rf.resource/ensure` with its owner and cause. On removal, unmount, or Activity disconnection it dispatches `:rf.resource/release-owner`. A target change releases the old target and ensures the new target under the same site owner, matching current semantics.

### Preferred causal owners

View leases are appropriate for resources whose liveness genuinely follows mounted UI, such as a polled dashboard tile. Route resources remain preferred for navigation data; event and machine commands remain preferred for workflow data. The compiler does not infer causality from a passive read.

### SSR

Lease effects do not run on JVM. Server init/loaders populate the per-request frame before view rendering. The client hydration payload installs that resource projection before React hydrates; after commit, view leases attach live client ownership and polling as applicable.

## Local state and effects

The current Spec 004 placement rule remains:

- app-observable, replayable, schema-relevant, or handler-read state belongs in app-db;
- uncommitted IME composition, host focus/hover mechanics, refs, and animation interpolation may remain local.

`re-frame.ui.react` exists for the second category and for integration with external systems. It does not offer atom-like local domain state.

Effect callbacks that need re-frame2 dispatch obtain a stable committed dispatcher:

```clojure
(ui/defview media-bridge [{:keys [stream-id]}]
  (let [dispatch! (ui/dispatch-fn)]
    (react/use-effect [stream-id]
      (fn []
        (let [stop (listen! stream-id
                     #(dispatch! [::sample-received %]))]
          stop)))
    ...))
```

`dispatch-fn` is stable per cell and reads the committed frame. An effect sees the render that committed it; it never depends on a dynamic frame binding that has already unwound. It fails while the cell is disconnected (including a hidden Activity) and after permanent disposal, surfacing a foreign listener whose effect cleanup failed.

## View registry and hot replacement

`defview` registers under re-frame2's existing `:view` kind. The descriptor extends current metadata with compiler facts:

```clojure
{:id :app.article/article-row
 :source {:ns ... :file ... :line ... :column ...}
 :props  ...
 :template-fingerprint "..."
 :hook-signature "..."
 :capabilities #{:subscriptions :events}
 :sites {:subs ... :events ... :leases ...}}
```

The stable shell delegates to the current registered implementation. A hot replacement with the same Hook signature preserves mounted cells and local React state, marks cells stale, and swaps the body at the next render. A changed Hook signature deliberately changes the shell generation/key so React remounts safely.

Programmatic lookup remains possible:

```clojure
(ui/view :app.article/article-row) ; public-props React wrapper
```

It is for runtime-computed component selection and tooling. An unknown ID fails before React with `:rf.error/view-not-found` and available/source evidence. The result is the view's public-props wrapper, suitable for `ui/element`; it performs one boundary translation and does not expose the compact internal props ABI. Literal calls use the Var so the compiler validates props and emits direct code with no wrapper.

## Root render, hydration, and flush

### Client root API

```clojure
(ui/create-root dom-node)
(ui/render! root view props opts)
(ui/hydrate-root dom-node view props opts)
(ui/unmount! root)
```

`opts` includes the root frame config and React root error callbacks. Mounting a plain runtime Hiccup value is not supported.

### Flush behavior

`ui/flush!` is the test `act` surface. The adapter's existing `flush-render!` remains the production-capable synchronous DOM-settlement seam used by headless tooling. Ordinary application code should not force either.

An event drain first settles derivations and advances dirty cells. React then owns rendering/commit. The adapter does not maintain a second component render queue.

## Error behavior

Framework contract failures use existing structured categories where possible:

- missing/destroyed frame;
- unknown subscription;
- bad query or subscription result schema;
- resource scope/registration errors;
- SSR/hydration mismatch;
- view render exception.

Compiler failures are build diagnostics, not runtime error events. New runtime categories are limited to genuinely new failures such as a stale template generation reaching commit or a foreign SSR fallback contract violation.

Render exceptions are routed through the root/error-boundary integration and existing always-on error emission. Development records include view/site/source facts; production keeps only the bounded error payload required by Spec 009.

## Disposal

`dispose-adapter!` performs, in order:

1. stop accepting new roots/cells;
2. mark existing cells permanently dead;
3. clear the dirty-cell set;
4. release committed subscription leases;
5. release committed resource owners;
6. unmount/clear adapter-owned driver roots and callbacks;
7. clear development instance and warning indexes;
8. dispose derived containers and late-bound hooks under current core rules.

Every step is idempotent. A late callback observes the dead bit and does nothing. Tests can reset the entire substrate without retaining view instances or source registrations from a prior case.

## Why the integration remains small

The new library adds a compiler, a ViewCell, and one internal observation port. Everything else is an adapter-shaped use of facilities re-frame2 already has. It specifically avoids importing the larger architectures that inspired individual mechanisms:

- no Relay/Fulcro data layer;
- no MobX/Jotai atoms;
- no TanStack cache;
- no XState actor runtime;
- no React Hook Form registry;
- no signals-to-DOM renderer.

That boundary is the main defense against over-engineering.
