# Comparative analysis

This analysis treats libraries as evidence. It borrows mechanisms that fit re-frame2 and rejects mechanisms that would create a parallel state, lifecycle, or debugging model.

The local code and specifications are more important than marketing comparisons. The current adapters, especially the detailed concurrency history in [`spine.cljs`](../../../implementation/core/src/re_frame/substrate/spine.cljs), show the actual problems the new substrate must make impossible.

## The three direct predecessors

### Reagent

What it gets right:

- Hiccup is compact, compositional Clojure syntax.
- Reading reactive values where they are used is exceptionally pleasant.
- Clojure value equality and persistent data make unchanged projections cheap to recognize.
- A plain function is enough for most UI.
- The same broad source shape can be rendered on client and server.

What the new design takes:

- literal vector markup;
- value-returning reactive reads in the render body;
- late, leaf-level reads rather than parent prop plumbing;
- a pure functional default;
- equality-aware publication.

What it rejects:

- interpreting vectors, attrs, tags, and sequences during browser render;
- automatic deref capture through a global ratom context;
- a Reagent render queue in addition to React scheduling;
- runtime Form-1/Form-2/Form-3 detection;
- lazy-sequence dependency discovery after the component's capture scope;
- broad legacy component and lifecycle surfaces.

Reagent's great idea is the authoring experience, not the runtime representation. The new compiler preserves the former and removes the latter.

### UIx

UIx is the closest implementation precedent. Its AOT compiler lowers `($ :div {:on-click f})` toward a direct `React.createElement` call, its macro emits a React component on CLJS and a plain function on JVM, and its analyzer-backed linter checks Hooks, keys, DOM properties, and literal props. These are all sound choices documented in [UIx internals](https://github.com/pitch-io/uix/blob/master/docs/internals.md) and the [UIx linter guide](https://github.com/pitch-io/uix/blob/master/docs/code-linting.md).

What the new design takes:

- ahead-of-time DOM and prop lowering;
- a dual CLJS/JVM component macro;
- readable display names and React Refresh integration;
- analyzer-backed build failures for unsafe Hooks, missing keys, invalid DOM props, and malformed components;
- explicit React interop and a small wrapper over native Hooks;
- dev-only/runtime-elided prop validation.

What it changes:

- Markup does not require `$` around every node; `defview` owns a strict template position.
- Dynamic props never silently fall back to generic interpretation. `ui/spread` marks the cost boundary.
- `ui/sub` is a captured re-frame2 read, not a Hook.
- All reads in a view share one ViewCell and one external-store hook.
- Props destructuring compiles to direct reads from the JS props object instead of reconstructing a shallow CLJS map.
- Event vectors compile to stable frame-aware callbacks with source provenance.
- The component manifest is shaped for re-frame2/Xray rather than only React DevTools.

UIx proves the compiler direction is practical. The new work is principally tighter semantics and re-frame2-native integration, not inventing another element compiler for sport.

### Helix

Helix's stated philosophy is a Clojure-friendly API to raw React with minimal interop ceremony. Its `defnc` creates a real function component and intentionally performs only shallow prop conversion; its docs make the trade-off explicit. See [Helix component creation](https://github.com/lilactown/helix/blob/master/docs/creating-components.md).

What the new design takes:

- every generated view is a real React component;
- foreign React components work without wrapper classes;
- JS values remain JS values across the interop boundary;
- class components are not a general authoring lane;
- advanced React remains reachable through a narrow explicit namespace.

What it rejects:

- manual `use-subscribe` at each read;
- application-managed `useCallback` and `useMemo` for ordinary event and prop stability;
- rebuilding a CLJS props facade on every component render;
- requiring authors to manually carry re-frame2 source identity and frame dispatch through effects.

Helix supplies the correct interop attitude: do not pretend React is something else. The new substrate adds a compiler and framework integration, not an alternate component object model.

## The local reagent-slim experiment

[`DESIGN-RATIONALE.md`](../../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) contributes four important disciplines:

1. **Savings by absence are trustworthy.** Removed namespaces and unsupported surfaces contribute zero bytes.
2. **Compile errors beat warning stubs.** A clean break should fail at build time, not ship a compatibility branch that throws later.
3. **Development and production claims must be separated.** Source-coordinate work can materially improve development while having zero production effect.
4. **Performance adjectives require measurements.** reagent-slim explicitly records that its production runtime improvement is marginal even when its bundle and development wins are real.

The experiment also exposes the ceiling of compatibility. Its [`IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md) still needs a browser Hiccup pipeline, component-shape plumbing, sequence expansion, prop conversion, and a Reagent scheduler because preserving Reagent source semantics requires them. A blank-slate substrate can remove the ceiling rather than shaving it.

## Other Clojure and ClojureScript attempts

| Library | Mechanism worth taking | What not to import |
|---|---|---|
| [Rum](https://github.com/tonsky/rum) | Compile known Hiccup forms ahead of time; warn when interpretation is required; a small explicit extension surface; equality-based static components. | Runtime interpretation fallback, lifecycle mixin algebra, and a separate atom architecture. The new substrate makes unsupported dynamic markup explicit instead of silently slower. |
| [Fulcro](https://book.fulcrologic.com/) | Stable component identity, co-located data-dependency metadata, locally understandable child requirements, and rich compiler/registry metadata. | Normalized client database, EQL, mutations, and targeted refresh. re-frame2 already owns state and derivations. Fulcro's own guide now marks its ident-optimized renderer obsolete under React 18 async rendering, useful evidence against imperative component targeting. |
| [Keechma/next](https://cljdoc.org/d/keechma/next/0.1.3/doc/readme) | Dependency-owned lifecycle, guaranteed start/stop, thin integration controllers, and graph-driven cleanup. | A controller graph beside re-frame2's event/sub/resource/machine graphs. Resource lease sites should compile into existing re-frame2 ownership. |
| [Replicant](https://replicant.fun/) | Event handlers as data, pure headless UI tests, stateless aliases, and declarative mount/unmount intent. | Whole-root rerendering and runtime Hiccup as the React client strategy. Event data is retained; React remains the reconciler. |
| Rumext | Macros that emit real function components and keep interop light. | Another general-purpose wrapper surface. UIx and Helix already provide stronger evidence for this layer. |
| Om Next | Co-locating component data requirements and composing them through the UI tree. | Reconciler/query language, normalization, and remote parsing. Static `ui/sub` sites are metadata, never a new data language. |

Two conclusions stand out.

First, **co-location is valuable even when the state model is not component-owned**. A `defview` manifest can list static subscription, event, resource, prop, and effect sites without moving those definitions into the component or changing re-frame2's registries.

Second, **event intent is better data than a fresh closure**. A compiler can keep the data-shaped source, generate the necessary React function once, and retain the original event vector for tests and Xray.

## React-targeted JavaScript and TypeScript libraries

### React itself and React Compiler

React's rules make render purity and commit ownership non-negotiable. [`useSyncExternalStore`](https://react.dev/reference/react/useSyncExternalStore) requires a cached snapshot and a stable subscribe function, and React may call or restart render repeatedly. [React Compiler](https://react.dev/learn/react-compiler/introduction) demonstrates that build-time memoization can make the authored API simpler while removing cascading work.

Take:

- cached scalar snapshots;
- commit-only external subscriptions;
- compiler-generated memoization and stable values;
- direct JSX-runtime output;
- source metadata through the development JSX runtime;
- effects only for synchronization with external systems.
- React 19.2 [`Activity`](https://react.dev/reference/react/Activity)'s distinction between preserved component state and disconnected effects: a hidden fiber is not an active re-frame2 owner;
- React Performance Tracks as the host-side half of a correlated React/re-frame2 profile.

Reject:

- assuming the JavaScript React Compiler can safely optimize arbitrary advanced-compiled ClojureScript output;
- treating `startTransition` as a way to make mutable external-store updates non-blocking;
- using Suspense to hide re-frame2 loading state.
- wrapping Canary-only `ViewTransition` or React DOM partial-prerender/resume protocols before the JVM renderer has an honest equivalent.

### React-Redux and Zustand

[React-Redux's hooks guide](https://react-redux.js.org/api/hooks) is valuable for both its selector equality checks and its candid “stale props / zombie children” history. [Zustand](https://zustand.docs.pmnd.rs/learn/getting-started/introduction) demonstrates a tiny store/select interface and equality-controlled rerendering.

Take:

- development checks for unstable results and overly broad reads;
- stable selector/query identity;
- explicit equality as the render boundary;
- source notification separated from render-time selection;
- one update after batching/coalescing.

Improve:

- React-Redux creates one subscription per `useSelector` call. re-frame2 UI aggregates a component's reads behind one ViewCell.
- No prop-dependent selector runs inside a store callback. The callback only invalidates; the query is evaluated in render with current props.

Reject:

- a second store;
- arbitrary equality functions at every call site;
- requiring authors to hand-memoize selectors or tuple results.

### Jotai and MobX

[Jotai](https://jotai.org/docs/core/atom) shows that ordinary reads can dynamically track dependencies and that definitions benefit from stable debug labels and mount/unmount ownership. [MobX React](https://mobx.js.org/react-integration.html) shows the ergonomic and performance value of subscribing only to values actually read during render.

Take:

- conditional dependency capture;
- read where the value is rendered;
- automatic disposal when the last committed owner leaves;
- generated, distinguishable debug identity.

Reject:

- mutable observable objects;
- property-level proxy/mutation tracking;
- implicit write authority;
- a second atom graph. `ui/sub` captures re-frame2 derivation nodes, not arbitrary derefs.

### TanStack Query and Valtio

[TanStack Query's render optimization guide](https://tanstack.com/query/latest/docs/framework/react/guides/render-optimizations) emphasizes structural sharing, stable data properties, tracked result fields, and selection. [Valtio snapshots](https://valtio.dev/docs/api/advanced/snapshot) preserve the exact prior snapshot reference when values did not change and reuse unchanged subtrees.

Take:

- stable publication: when a subscription result is `rf=` to the prior site value, return the prior exact value;
- persistent structural sharing as a memoization asset;
- fine projections through registered subscriptions;
- development diagnostics when a view reads a whole broad value and rerenders frequently.

Reject:

- Proxy get-traps to discover which map fields were used;
- a query cache or stale-time policy in the UI substrate;
- callbacks attached to passive observers. re-frame2 Resources already owns fetch, cache, stale, retry, owner, and continuation semantics.

### Relay

[Relay fragments](https://relay.dev/docs/guided-tour/rendering/fragments/) co-locate each component's data needs, give those needs globally unique compiler identity, generate artefacts, and let a child subscribe to its own fragment.

Take:

- globally stable view and read-site IDs;
- compile artefacts that let tools understand potential dependencies before the app runs;
- passing identity to children and letting each child read its own projection;
- local declarations composing into a global debugging graph.

Reject:

- GraphQL fragments, data masking, generated data types, and normalized records as substrate requirements;
- inferring fetch plans from view reads. Routes, events, and machines continue to cause resources explicitly.

### XState

[XState actors](https://stately.ai/docs/actors) make ownership and teardown clear: invoked work follows parent state, actors expose immutable snapshots, and stopping a parent stops descendants.

Take:

- a view instance is an owner with an explicit lifetime;
- subscriptions expose stable snapshots;
- child resource work must be released with its owner;
- debugging should show lifecycle and causal events, not merely current values.

Reject:

- embedding actors in the UI layer. re-frame2's machine and resource artefacts already own long-lived processes and effects.

### React Hook Form

[React Hook Form](https://www.react-hook-form.com/) demonstrates that high-frequency field edits do not need to rerender an entire application tree and that subscription locality matters.

Take:

- treat mutation frequency as an architectural input;
- use uncontrolled DOM state for truly uncommitted, render-mechanical input state such as IME composition;
- subscribe validation/error displays to the smallest useful projection;
- dispatch semantic application events rather than blindly sending every pointer or animation tick through app-db.

Reject:

- a bundled form registry or validation system. The guide gives patterns; a form library remains an interop choice.

### Signals

[Preact Signals](https://preactjs.com/guide/v10/signals/) demonstrates automatic dependency capture, batching, and—in Preact—direct text/prop updates that can bypass VDOM rendering.

Take:

- stable reactive identities;
- dependency capture during reads;
- lazy derived computation;
- one batch notification at a semantic transaction boundary.

Reject:

- mutating React-owned DOM nodes behind React's reconciler;
- passing signal objects through app code as a second state model;
- leaf bindings that undermine hydration parity, component traces, or React ownership.

The closest safe React equivalent is a small child component with its own external-store subscription. That is opt-in component factoring, not a hidden DOM mutation optimization.

## Synthesis: ideas that survive the re-frame2 filter

| Surviving idea | Source evidence | re-frame2 UI realization |
|---|---|---|
| Clojure literal markup | Reagent, Rum, Replicant | Strict compile-time template AST |
| Direct host code | UIx, Helix, React JSX | `jsx`/`jsxs`, direct JS props, static hoists |
| Dynamic read capture | Reagent, MobX, Jotai, Signals | `ui/sub` sites captured by one ViewCell |
| Commit-owned lifetime | React, Jotai, Keechma, XState | Probe during render; acquire/release on commit |
| Data events | Replicant, Redux/re-frame | Stable compiler-generated frame dispatch slots |
| Stable snapshots/references | React external stores, Valtio, TanStack Query | Scalar cell revision and `rf=` result stabilization |
| Co-located compiler metadata | Relay, Fulcro, UIx | View manifest consumed by registry and Xray |
| Build-time guardrails | UIx, Relay | Analyzer lints and literal prop/schema validation |
| Preserved but inactive UI | React Activity | Reversible ViewCell disconnect: preserve local state, release framework owners |
| Honest bundle discipline | reagent-slim, Zustand | Capability-specific runtime and absence gates |
| Frequency-local state | React Hook Form | Strict app-state versus render-mechanical rule |

## Attractive ideas rejected from the core

### Per-property proxies

They make ordinary Clojure map operations and destructuring semantically surprising, add proxy traps to the hot path, complicate SSR, and duplicate the derivation graph. A focused subscription is clearer and easier to explain.

### Direct signal-to-DOM binding

It is exceptionally fast in a renderer designed for it. In React it creates two owners for the same DOM, weakens hydration and tooling, and turns a local optimization into a global correctness liability.

### Component queries and normalized UI state

Fulcro and Relay make this coherent because the query/compiler/runtime/database are one system. Adding only their surface syntax to re-frame2 would create two sources of truth. Static subscription-site metadata delivers the debugging benefit without importing their state architecture.

### Suspense-driven resources

It hides loading behind thrown promises, conflicts with passive external-store constraints, and makes state less inspectable by re-frame2 tools. Explicit remote-data/resource state remains the better fit.

### A universal lifecycle DSL

Resources get a dedicated declaration because re-frame2 owns that lifecycle. Arbitrary CodeMirror, Mapbox, media, or DOM lifecycles remain React effects with mandatory cleanup. Generalizing all of them would be gold plating.

### Runtime compatibility mode

A mode that accepts arbitrary Reagent Hiccup would force the interpreter, conversion branches, and ambiguity back into the new bundle. Migration can mount old and new React subtrees at explicit boundaries; the new core stays clean.
