# Design posture and invariants

## Product posture

This is a pre-alpha design with permission to make a clean break. That permission is used to remove accidental cost and ambiguity, not to invent a larger framework.

The priority order is:

1. Correct under React concurrent rendering, Strict Mode, hydration, and hot reload.
2. Native to re-frame2 frames, derivations, events, resources, epochs, and tooling.
3. Pleasant enough that the efficient path is the path authors naturally write.
4. Exceptionally small and predictable in production.
5. Explicit escape hatches for the uncommon cases.

Backward compatibility, generic render-tree interpretation, and substrate neutrality inside the browser are non-goals. Cross-host portability is retained at compile time and at the re-frame2 state/event/subscription contracts.

## Architectural boundary

```text
                     one .cljc source form
                              │
                       defview compiler
                              │
                 normalized template AST + manifest
                         ┌────┴────┐
                         │         │
                 CLJS React     CLJ/JVM SSR
                 jsx/jsxs code  render-tree code
                         │         │
                   React 19     re-frame2-ssr
                         │
                    one ViewCell
                         │
          re-frame2 subscription/resource/frame graph
```

The compiler owns syntax, validation, source identity, static analysis, and host code generation. The runtime owns only facts that cannot be known until an instance renders: its frame, committed dependencies, dependency versions, stable handler slots, resource owners, and current invalidation revision.

## Load-bearing invariants

### I-1: Render is speculative and pure

A render may run, restart, or be abandoned. It may read values and build a local capture. It may not acquire a global subscription ref, attach a resource owner, dispatch, mutate a committed handler slot, attach a listener, or publish debug state.

All externally visible ownership begins in commit and ends in cleanup. This follows React's purity rule rather than relying on React to commit every render.

### I-2: Only the committed render owns dependencies

Every render produces a local capture. The capture contains the subscription sites, resource lease sites, event-slot values, frame, and source sites observed by that render. A layout-phase reconciler applies only the capture belonging to the render React committed.

An abandoned first mount therefore owns nothing. An abandoned update cannot replace the dependency set or callback values of the currently visible DOM.

### I-3: One reactive bridge per view instance

A reactive `defview` calls `useSyncExternalStore` exactly once. Ten `ui/sub` reads do not become ten React Hooks, ten external-store subscriptions, or ten independent force-update callbacks.

The re-frame2 graph may still hold ten derivation-node leases. That is real application dependency, not repeated React integration machinery.

### I-4: External-store snapshots are cached scalars

`ViewCell.getSnapshot` returns a numeric revision. It returns the same number until the committed dependency set is invalidated. A source update advances it at most once per re-frame2 epoch.

The snapshot never allocates a new map or vector merely because React asks for it. Subscription values remain in re-frame2 derivation nodes and are read during render.

### I-5: Source notifications do no application computation

A derivation-node notification records its node, version, epoch, and cause on affected ViewCells. It does not execute a selector closed over component props, build a query, or read child data.

The component recomputes its reads during React render with current props and current frame. This structurally avoids the stale-props and “zombie child” family documented by React-Redux.

### I-6: One notification per cell per epoch

The substrate keeps a dirty-cell set inside the adapter's derivation epoch. At epoch close it advances and notifies each cell once, regardless of how many of that cell's dependencies changed.

This is not debounce-by-time. It is exact coalescing at the re-frame2 transaction boundary.

### I-7: Client markup is compiled, never interpreted

A literal DOM node is lowered to `jsx` or `jsxs`. Literal prop names are converted at compile time. Tag shorthand is parsed at compile time. Static subtrees are hoisted. A list form emits a JavaScript array of already-built elements.

The production runtime contains no general vector walker, tag regex, prop-map walker, sequence flattener, or component-shape detector.

### I-8: Query and event sites have stable identity

Every `ui/sub`, DOM event, local-state helper, effect, and resource lease receives a compact site index and a stable source ID at macro expansion.

Literal subscription vectors are hoisted. Dynamic query arguments reuse the prior query object while their values remain `rf=`. This prevents fresh Clojure values from churning React dependencies or fragmenting identity-keyed subscription caches.

### I-9: Event callbacks observe committed values

A data event such as `:on-click [::select id]` compiles to one function per mounted event site. The function identity remains stable. It reads the event site's latest **committed** values and committed frame, then dispatches the event with compiler-recorded provenance.

An event arriving while another render is in progress therefore sees the values represented by the DOM the user actually clicked, not speculative values from an uncommitted render.

A callback that a foreign component invokes *during render* has the opposite requirement: it must see the current render and remain pure. `ui/render-fn` marks that phase and receives no stable-identity promise. The compiler never substitutes a committed event slot for a render callback; concurrent correctness is more important than callback memoization.

### I-10: Frame identity is carried, never guessed

A frame-capable generated component resolves its frame through re-frame2's established precedence: explicit pin, dynamic binding, React context, otherwise a loud no-frame-context error. The committed frame is used by subscriptions, event slots, dispatchers, and resource leases. A production props-only view has no frame capability and pays no context read; a reactive/eventful descendant resolves its own frame.

There is no conventional `:rf/default` fallback from absence.

### I-11: Loading remains explicit state

`ui/sub` is passive. It never starts a request. Resource acquisition is an explicit lifecycle declaration and route/event resource planning remains preferred. Suspense is not a hidden second loading-state system.

### I-12: Development facts vanish from production

View instance tokens, source paths, template manifests, dependency histories, render timings, prop diffs, DOM annotations, warning registries, and explanation records are guarded by a compile-time debug define and verified absent from advanced builds.

Production errors that are part of re-frame2's always-on error contract remain. Development tracing does not.

### I-13: One template controls both renderers

The compiler first produces a host-neutral AST. The CLJS and JVM emitters consume that AST. Neither emitter reparses the author's forms independently.

The template fingerprint rides SSR build metadata. Hydration refuses to treat different fingerprints as equivalent and reports the first structural mismatch through the existing SSR diagnostics.

### I-14: Escape hatches advertise their cost

Dynamic components, dynamic prop spreads, raw React elements, foreign React components, and client-only branches are supported. Their spellings are explicit. A call site never silently falls from compiled code into a runtime Hiccup interpreter.

### I-15: Capability-specific output

The compiler does not install a ViewCell in every production component merely for uniformity.

- A pure props-only view compiles to a memoized function and direct JSX calls.
- An eventful but non-reactive view gets stable event slots, without `useSyncExternalStore`.
- A reactive view gets one full ViewCell.
- Resource and effect machinery appears only when its forms are present.

Development uses a fixed full substrate Hook skeleton for every `defview` so adding/removing subscription, event, or lease sites during HMR does not change React Hook order; unused operations remain inert. Production specializes the skeleton away by capability. That deliberate dev-only overhead buys observability and safe hot editing without weakening the production contract.

## Ergonomic rules

The API is designed so that ordinary code is also optimized code:

- one props map;
- literal Clojure markup;
- `ui/sub` returns a value, not a dereferenceable handle;
- conditional subscription reads are legal;
- event vectors are data;
- component calls and DOM nodes use the same vector grammar;
- a compiler error explains an unsupported dynamic shape and names the explicit escape hatch;
- no `useMemo`, `useCallback`, or subscription-hook dependency vectors are required for ordinary re-frame2 views.

The author still learns React's commit/effect model for actual imperative integration. The library removes bookkeeping, not the host's semantics.

## Focused changes to current contracts

### Spec 004: source portability replaces client-result serializability

Current [Spec 004](../../../spec/004-Views.md) says every client view returns serializable structured data. That entails a client interpreter. The proposed replacement is:

> A view is a pure function of frame state and props whose UI structure originates in a serializable compiler AST. Each host emitter produces its native render value deterministically from that AST. The JVM emitter's value remains serializable; a React client's native value need not be.

The conceptual tag/attrs/children model remains. Pure headless tests use the JVM/tree emitter. The browser no longer pays for that test representation.

### Spec 006: observation is separate from ownership

The existing `subscribe` API couples “obtain a handle” with incrementing its global ref count. The UIx/Helix spine has to perform a balanced render-phase subscribe/unsubscribe, then acquire the durable handle after commit. The new internal port separates the concepts:

```clojure
(probe-sub frame-id query)              ; pure, owner-free snapshot
(acquire-sub! frame-id query owner cb)  ; commit only
(read-sub lease)                        ; cached value + version
(release-sub! lease)                    ; cleanup
```

This is a substrate-internal observation port, not a second application subscription API.

### Spec 006: epoch-close notification

The new adapter's epoch scheduler owns one fixed internal final phase: flush its dirty ViewCell set after derivations settle and before the epoch closes. This is an implementation guarantee of that adapter, not a callback added to the generic adapter map, and it imposes no ViewCell concept on other adapters.

## Non-goals

- Inferring network loads from rendered subscription reads.
- Replacing re-frame2 subscriptions with property-level signals.
- Normalizing app-db or introducing component-owned query schemas.
- Making arbitrary runtime-generated Hiccup fast.
- Hiding imperative libraries behind a universal lifecycle DSL.
- Bypassing React to mutate text nodes directly.
- Shipping a general React wrapper for applications that do not use re-frame2.
- Recreating React Compiler for arbitrary ClojureScript. The template compiler performs a small set of sound transformations it can prove.

## Decision rule

A feature enters the core only when it does at least one of these:

1. removes recurring author bookkeeping while preserving explicit semantics;
2. eliminates a correctness bug class structurally;
3. removes measurable production work or bytes from normal views;
4. exposes a re-frame2 fact that Xray or a test can use directly.

An idea that is merely expressive, fashionable, or theoretically optimizable stays out until a concrete use site and benchmark justify it.
