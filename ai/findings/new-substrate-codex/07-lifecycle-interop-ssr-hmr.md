# Lifecycle, interop, SSR, and HMR

## One lifecycle rule

Render describes. Commit owns. Cleanup releases.

| Phase | Allowed | Forbidden |
|---|---|---|
| Render | Read props, `ui/sub`, context, local Hook state; declare `ui/lease`; build React output and local capture. | Dispatch, I/O, listener attachment, resource ensure, subscription ref acquisition, committed callback mutation, global debug publication. |
| Layout commit | Publish committed frame/handler values; reconcile subscription leases; measure DOM; publish committed debug facts. | Network work, long calculations, arbitrary app events. |
| Passive commit | Ensure/release view resource leases; attach ordinary external-system effects. | Assuming the effect ran on SSR or exactly once in development. |
| Cleanup | Release the exact owned subscriptions, resources, listeners, roots, and host objects. | Inferring the target from current ambient context. Cleanup uses the target captured by its commit. |

React may replay effects and abandon renders. The design is correct under those behaviors rather than detecting them as special cases.

## React Activity

React 19.2 Activity makes “mounted” too imprecise for ownership. In `hidden` mode React preserves DOM/local state and may render at low priority, but disconnects layout/passive effects and subscriptions. ViewCell therefore has reversible connect/disconnect:

- disconnect releases subscription leases and passive resource owners;
- hidden renders may probe but do not acquire and receive no re-frame2 invalidations;
- reconnect reacquires the latest committed query/lease sites and corrects changed values before paint;
- permanent adapter/frame disposal remains a distinct dead state;
- `ui/dispatch-fn` refuses calls while disconnected, exposing leaked foreign listeners.

Activity is used as an imported React component in browser-only or explicitly `ui/client-only` UI. The initial library does not wrap it or claim selective hydration from JVM SSR: React's server Activity/resume markers belong to React DOM's server protocol, while this design intentionally emits re-frame2's serializable JVM tree. Inventing lookalike markers would depend on React internals.

For prefetch, cause re-frame2 resource work through a route/event/machine before reveal. React documents that Activity does not discover effect-based fetching, and this design does not turn passive subscription reads into Suspense fetches.

## Thin React namespace

`re-frame.ui.react` wraps current stable React APIs only where Clojure syntax benefits:

- `use-state`
- `use-reducer`
- `use-ref`
- `use-context`
- `use-id`
- `use-effect`
- `use-layout-effect`
- `use-effect-event`
- `use-memo` and `use-callback` for foreign protocols, not ordinary view optimization
- `use-imperative-handle`
- `lazy`

The wrappers:

- accept literal Clojure dependency vectors and compile them to JS arrays;
- normalize `nil` cleanup to JavaScript `undefined`;
- participate in the `defview` Hook and exhaustive-deps linter;
- attach source-site IDs in development;
- otherwise delegate directly to React.

The namespace does not wrap every React export merely for symmetry. Raw React remains importable when an advanced API has no Clojure-specific issue.

`react-dom/flushSync` is deliberately not wrapped. Calling it re-entrantly while a re-frame2 event/derivation epoch is open could commit a half-settled external-store view; supported test/tool flushes close the framework epoch first. An integration that truly needs host `flushSync` calls it only outside framework dispatch and owns the resulting React trade-off explicitly.

## Effects

### Canonical shape

```clojure
(react/use-effect [room-id]
  (fn []
    (let [connection (connect! room-id)]
      #(disconnect! connection))))
```

The dependency vector is first and literal. Every free render local used to establish or clean up the synchronization belongs in it. The analyzer verifies missing and unnecessary dependencies where it can prove them.

Effects should synchronize with an external system. “When this prop changes, dispatch an event to copy it into app-db” is normally a duplicated-state smell. Initial app work belongs in root/frame initial events; prop-driven workflow belongs in an explicit event or machine.

### Latest values inside a persistent effect

React 19.2's `useEffectEvent` is the right tool when a listener or timer should stay attached while a callback observes the latest committed value:

```clojure
(let [on-sample (react/use-effect-event
                  (fn [sample]
                    (when enabled?
                      (dispatch! [::sample sample]))))]
  (react/use-effect [stream-id]
    (fn []
      (listen-to-stream! stream-id on-sample))))
```

It is not a general DOM handler and React documents that its identity is intentionally not stable. `ui/event` and `ui/handler` remain the DOM/component callback forms.

### Effect ownership and frame carry

`ui/dispatch-fn` returns one stable function per ViewCell. It dispatches through the cell's committed frame and fails while disconnected or permanently dead. An effect captures that function during render and needs no dynamic-var restoration:

```clojure
(let [dispatch! (ui/dispatch-fn)]
  (react/use-effect [topic]
    (fn []
      (subscribe-js! topic
        #(dispatch! [::received topic %])))))
```

For a deliberate explicit target, use re-frame2's existing `(rf/capture-frame target)` operation bundle. Do not add a second UI-owned explicit dispatcher. Ordinary views should follow their surrounding committed frame through `ui/dispatch-fn`.

## Imperative library pattern

An imperative widget has a small inner bridge and a normal re-frame2 outer view:

```clojure
(ui/defview chart-bridge [{:keys [series options]}]
  (let [node (react/use-ref nil)]
    (react/use-layout-effect [series options]
      (fn []
        (let [chart (chart/create (.-current node) options)]
          (chart/set-data! chart series)
          #(chart/destroy! chart))))
    [:div.chart-host {:ref node}]))

(ui/defview sales-chart []
  (let [series  (ui/sub [::sales-series])
        options (ui/sub [::chart-options])]
    [chart-bridge {:series series :options options}]))
```

Use layout effect only when the library must measure or mutate before paint; use passive effect otherwise. Cleanup is mandatory. The bridge accepts plain immutable inputs and contains the host lifecycle. It does not read re-frame2 from a callback outside a captured frame.

For a library with separable mount and update APIs, two effects may avoid recreation:

- mount/unmount effect keyed by host identity;
- update effect keyed by data/options.

That optimization belongs in the bridge, not in a universal substrate lifecycle DSL.

## Refs

React 19 treats `ref` as a prop for function components. The compiler nevertheless treats `:ref` as a reserved React slot:

- native refs compile directly;
- internal views must declare `:ref` in their props to forward it;
- a ref is never included in a data-event vector or SSR output;
- callback refs use an explicitly unstable `ui/raw-handler` closure because React invokes them during commit, before the owning view's layout effect may publish committed handler slots; object refs are preferred;
- string refs are build errors.

Reading or writing `ref.current` during render is rejected except for the ViewCell's internal idempotent initialization. Application ref work belongs in handlers or effects.

## Foreign React components

### Calling foreign components

Any React function/class imported from JavaScript can appear as a template head:

```clojure
[VirtualList
 {:items items
  :item-key (ui/render-fn [item] (.-id item))
  :on-range-change (ui/handler [range]
                     (dispatch! [::range-visible range]))}]
```

The boundary rules are simple:

- props are a JavaScript object with compile-time converted literal keys;
- JS objects/arrays remain JS values;
- Clojure values remain Clojure values unless the foreign API requires conversion;
- callbacks invoked after commit use `ui/handler` or `ui/event`;
- callbacks invoked during foreign render use pure `ui/render-fn`;
- an unusual identity/lifecycle protocol uses explicitly unoptimized `ui/raw-handler`;
- children are React children;
- the foreign component's internal renders and subscriptions are not attributed as re-frame2 ViewCells.

### Receiving foreign callbacks/render props

Use `ui/render-fn` when a foreign API requires a render prop:

```clojure
[Measure
 {:children
  (ui/render-fn [{:keys [bounds ref]}]
    [:div {:ref ref}
     (str (:width bounds) " px")])}]
```

The body is part of the foreign component's render and must remain pure. It cannot use Hooks, `ui/sub`, or `ui/lease`. Extract a named `defview` and return that child when the subtree is reactive, reused, or important enough to inspect independently in Xray.

Do not use a stable committed `ui/handler` for a render prop. On an update it would observe values represented by the old committed DOM while the foreign component is constructing the new tree.

### Exporting a `defview`

A `defview` Var is already a React component and can be exported to JavaScript. Its external wrapper translates a JS props object to the internal encoded slots once at the boundary. Internal calls bypass that wrapper.

Exports should declare an explicit JS prop schema/name map. The compiler generates TypeScript declarations as an optional build artefact; TypeScript generation is tooling output, not a runtime dependency or a type-system promise for arbitrary Clojure values.

## Portals

`ui/portal` is a thin compiler/runtime form over `react-dom/createPortal`:

```clojure
(ui/portal modal-node
  [dialog {:on-close [::close-dialog]}])
```

React context passes through portals, so the child ViewCell resolves the same re-frame2 frame. The portal root does not own or destroy a frame. Source ownership records the logical parent view as well as the physical DOM target.

## Error boundaries

Function components still cannot implement the full React error-boundary contract. The library ships one small framework-owned class boundary rather than a public class-component authoring system:

```clojure
[ui/error-boundary
 {:fallback error-panel
  :fallback-props {:retry-event [::retry route-id]}
  :reset-key route-id}
 [risky-foreign-widget {:value value}]]
```

It emits the existing view-render error category with view/frame/component-stack evidence, then calls the supplied fallback `defview` with `:error` plus the literal `:fallback-props`. Changing `:reset-key` clears the caught error. The fallback receives no magic retry side effect: its retry event must change application state so the parent supplies a new reset key. The boundary does not swallow event/effect errors and does not invent application recovery; handlers and resources keep their typed error paths.

Root APIs also expose React's `onCaughtError`, `onUncaughtError`, and `onRecoverableError` options and route them through re-frame2's error projection.

## Code splitting and Suspense

`react/lazy` and Suspense remain available for JavaScript module loading. They are not the re-frame2 data-loading model.

```clojure
(ui/client-only
  {:fallback [page-code-skeleton]}
  [React/Suspense
   {:fallback [page-code-skeleton]}
   [lazy-admin-page {:user-id user-id}]])
```

Here `React/Suspense` is an explicitly imported foreign React component and `ui/client-only` supplies the JVM fallback. The manifest records both boundaries. The initial API does not add a special code-boundary wrapper merely to rename this composition. Resource/data status still lives in app-db/runtime-db and is rendered explicitly.

If a foreign component suspends, ViewCell render probes remain owner-free. A suspended first mount retains nothing, and an already committed instance retains its prior dependency set until a new render commits.

React Server Components are out of the initial design. The canonical server is re-frame2's per-request JVM SSR path; adding RSC would introduce a second server component protocol, bundler, and wire format before a concrete consumer exists.

## JVM SSR

### Same AST, native output per host

`defview` is a `.cljc` macro. It normalizes one template AST and chooses an emitter:

- CLJS browser: React `jsx`/`jsxs` calls;
- CLJ/JVM: canonical serializable render-tree values consumed by `day8/re-frame2-ssr`.

This differs from running React on the JVM. No JavaScript component or Hook is required on the server. Pure view logic, Clojure data operations, subscription computation, and template branching run normally.

### SSR subscription reads

The server binds the per-request frame snapshot while invoking the root view. `ui/sub` site code uses the pure snapshot computation path rather than ViewCell ownership. It performs no watch, ref-count acquire, or debug instance mount.

The same subscription registration and schemas are used. Unknown subs and invalid results produce the existing SSR-projected errors.

### Event output

Direct event vectors remain in the JVM render-tree attrs for headless testing, but the HTML emitter ignores behavior attrs. The browser reruns the view during hydration and creates stable callbacks from the same event sites.

Opaque `ui/handler` forms emit no server behavior. The template manifest still records their presence and source site.

### Foreign components

In the initial API, every JavaScript-only component reachable from shared SSR source must sit inside `ui/client-only` with a capability-free fallback. An integrating library can expose a normal `.cljc` `defview` facade that keeps shared markup outside and isolates only its JS leaf, but there is no second registrable “foreign SSR adapter” or static-projection protocol in alpha.

There is no attempt to execute a foreign React function on JVM, serialize a JS closure, or imitate React DOM server output.

### Streaming

The existing re-frame2 `:rf/suspense-boundary` marker remains a lower-level SSR protocol. The initial UI API does not expose `ui/stream-boundary`: a correct spelling needs a dual-host parity proof and a concrete streaming consumer first. If that evidence arrives, a compiler form may emit the canonical marker on JVM and a matching React boundary on CLJS. It must remain a spelling over the existing SSR protocol, not a general promise/pause abstraction, and application resource loading must remain explicit.

## Hydration

### Protocol

1. Server creates a per-request frame and runs server-init/loaders.
2. JVM `defview` output is rendered by `day8/re-frame2-ssr`.
3. The response includes the existing hydration payload plus:
   - root view ID;
   - root template fingerprint/build digest;
   - identifier prefix used for React IDs where applicable.
4. Client validates version/schema/template digests.
5. Client installs the frame payload before calling `hydrateRoot`.
6. The root hydration-phase context is `:fallback`; every `ui/client-only` site emits its JVM fallback, so the first CLJS output still matches.
7. The CLJS emitter produces direct React output from the same template AST and hydration commits.
8. A root passive effect changes the phase to `:client`, replacing all client-only fallbacks in one React update. A non-hydrating root starts in this phase.
9. First connected commits acquire live subscription and resource ownership; resources do not refetch merely because hydration attached an owner if current freshness policy says the cache is fresh.

### Equivalence

The AST, not the host output object, is the source of truth. Parity fixtures compare normalized semantic nodes:

- tag and namespace;
- emitted attr names/values;
- child order and text escaping;
- key-driven list order;
- boolean/void-element behavior;
- fragment shape;
- explicit client-only fallback;
- source-coordinate policy in matching debug modes.

Randomness, clocks, locale, request facts, and generated IDs must be supplied as deterministic frame/prop inputs. `useId` uses a matching root identifier prefix.

### Mismatch handling

Template digest mismatch fails before hydration and routes to the existing mismatch policy. Markup mismatch uses React's recoverable-error callback plus re-frame2's first-diff-path diagnostics. It is not dismissed as a warning; React documents server/client equality as a correctness requirement.

`ui/client-only` never asks React to hydrate unlike markup. Its small generated boundary reads root phase, so the authoring view gains no conditional Hook. The first client output is the declared fallback; replacement happens only after hydration has committed. Multiple sites share one root phase flip rather than each installing an effect. There is no blanket `suppressHydrationWarning` escape hatch.

The debugger reports whether disagreement came from:

- different template build;
- different frame payload;
- a host codegen parity bug;
- browser-only branch without a declared fallback;
- nondeterministic application logic.

## Hot module replacement

### Stable shell

Each `defview` exports a stable component shell keyed by view ID. The registry holds the current implementation descriptor. Re-evaluating the namespace replaces that descriptor and increments its generation without changing the shell identity.

Mounted cells are indexed by view ID only in development. Replacement marks them dirty; their next render calls the new implementation.

### Hook signature

Development shells always contain one fixed, full substrate Hook skeleton. The compiler separately hashes the ordered sequence of user Hook sites and their kinds. It excludes `ui/sub`, `ui/lease`, event, and `ui/render-fn` sites because those forms are not user React Hooks; their site sets reconcile through the cell/manifest.

- Same signature: preserve React state, refs, ViewCell, instance token, and resource owners; reconcile changed template sites at commit.
- Changed signature: deliberately change the implementation key and remount. Cleanup releases old ownership before the new instance commits.

This is safer than hoping React Refresh recognizes macro-expanded Hook changes and more ergonomic than remounting for every markup edit.

Production builds do not carry that maximal development skeleton. They are compiled afresh with capability-specialized Hooks, so there is no live production HMR transition whose Hook order must survive a capability edit.

### Registration and subscription changes

When a `reg-sub` body is replaced, the existing re-frame2 cache invalidation/rebuild rules remain authoritative. ViewCells read the newly live canonical node after notification. No cell snapshot may remain pinned to a disposed old reaction—the exact failure the current spine's committed reaction ref had to repair.

### Template site evolution

Site identity is derived from a stable source anchor plus compiler path, not merely ordinal position. Adding a sibling before an existing event or lease should not retarget its owner if the source anchor survives. When identity cannot be preserved safely, the compiler reports a development remount/release rather than guessing.

State preservation is a convenience. Ownership correctness wins every ambiguity.

## Interop quality gates

The implementation must include fixtures for:

- native React function and class components;
- render props and callback props;
- refs and imperative handles;
- portals retaining frame context;
- error boundaries and root error callbacks;
- lazy code suspension with abandoned first mount;
- Activity hide/render/reveal with subscription/resource disconnect and local-state preservation;
- imperative library attach/update/cleanup under Strict Mode;
- JVM fallback for client-only components;
- SSR/hydration equivalence, including lists, forms, boolean attrs, and fragments;
- HMR with unchanged and changed Hook signatures;
- subscription registration replacement without stale output.

These are contract tests, not examples added after the architecture. A React substrate that is elegant only before real interop is not elegant.
