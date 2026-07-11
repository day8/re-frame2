# Research sources

Reviewed 2026-07-11. External links are project-owned documentation or repositories. The analysis paraphrases mechanisms; it does not depend on third-party benchmark claims.

## Local re-frame2 sources

| Source | Use in this design |
|---|---|
| [`spec/004-Views.md`](../../../spec/004-Views.md) | View purity, frame explicitness, current serializable render-tree requirement, loading/state placement, view identity, source coordinates, render anti-patterns, HMR. |
| [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) | Adapter contract, equality/cache/ref-count lifecycle, source annotation, UIx/Helix/Reagent reference realizations, lazy-seq behavior, cooperative-rendering direction. |
| [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md) | Trace/error vocab, production DCE, classification, privacy/size elision, epoch/view/sub causality. |
| [`spec/011-SSR.md`](../../../spec/011-SSR.md) | Per-request frames, hydration equivalence, emitters, mismatch diagnosis, streaming boundaries, teardown. |
| [`spec/016-Resources.md`](../../../spec/016-Resources.md) | Passive reads versus causal commands, scope resolution, resource view model, owner leases, SSR/hydration, tooling. |
| [`implementation/core/src/re_frame/substrate/adapter.cljc`](../../../implementation/core/src/re_frame/substrate/adapter.cljc) | Installed adapter validation and lifecycle. |
| [`implementation/core/src/re_frame/substrate/spine.cljs`](../../../implementation/core/src/re_frame/substrate/spine.cljs) | Current React-hook adapter scheduler, `useSyncExternalStore` bridge, stable query identity, abandoned-render ownership, committed handle, sibling watch keys, flush/dispose behavior. |
| [`implementation/core/src/re_frame/subs.cljc`](../../../implementation/core/src/re_frame/subs.cljc) | Subscription cache/query identity, ref counts, warnings, pure computation seam. |
| [`implementation/core/src/re_frame/subs/memo.cljc`](../../../implementation/core/src/re_frame/subs/memo.cljc) | Equality memoization, result publication, trace attribution, fixed-arity hot paths. |
| [`implementation/core/src/re_frame/adapter/resource_lease.cljs`](../../../implementation/core/src/re_frame/adapter/resource_lease.cljs) | Shared lease owner mint, commit effect semantics, frame carry, Strict Mode idempotency. |
| [`implementation/adapters/reagent/README.md`](../../../implementation/adapters/reagent/README.md) | Reagent adapter authoring and lifecycle shape. |
| [`implementation/adapters/uix/README.md`](../../../implementation/adapters/uix/README.md) and [`uix.cljs`](../../../implementation/adapters/uix/src/re_frame/adapter/uix.cljs) | Current value-returning Hook surface and thin shared-spine adapter. |
| [`implementation/adapters/helix/README.md`](../../../implementation/adapters/helix/README.md) and [`helix.cljs`](../../../implementation/adapters/helix/src/re_frame/adapter/helix.cljs) | Current raw-React-oriented Hook surface and shared-spine adapter. |
| [`implementation/adapters/reagent-slim/DESIGN-RATIONALE.md`](../../../implementation/adapters/reagent-slim/DESIGN-RATIONALE.md) | Scope-by-evidence, savings by absence, debug/production distinction, quantified claim discipline. |
| [`implementation/adapters/reagent-slim/IMPL-SPEC.md`](../../../implementation/adapters/reagent-slim/IMPL-SPEC.md) | Runtime Hiccup pipeline and compatibility costs that a clean break can remove. |
| [`implementation/scripts/check-perf-bundle.cjs`](../../../implementation/scripts/check-perf-bundle.cjs) | Existing bundle-gate style and baseline for a future comparative harness. |

## React

- [Components and Hooks must be pure](https://react.dev/reference/rules/components-and-hooks-must-be-pure) — render can restart; side effects belong outside render.
- [`useSyncExternalStore`](https://react.dev/reference/react/useSyncExternalStore) — stable subscribe function, cached immutable snapshots, server snapshot, and external-store transition caveats.
- [React Compiler introduction](https://react.dev/learn/react-compiler/introduction) — build-time memoization as an ergonomic/performance strategy; not assumed to compile ClojureScript output.
- [`memo`](https://react.dev/reference/react/memo) — prop-based render skipping and its semantic limits.
- [`useEffectEvent`](https://react.dev/reference/react/useEffectEvent) — reading latest committed values inside persistent effects; explicitly not a general event handler.
- [`hydrateRoot`](https://react.dev/reference/react-dom/client/hydrateRoot) — exact server/client output, identifier prefix, root error handling, and unmount obligations.
- [`flushSync`](https://react.dev/reference/react-dom/flushSync) — bounded synchronous integration/tooling seam, not normal scheduling.
- [`useLayoutEffect`](https://react.dev/reference/react/useLayoutEffect) — pre-paint commit work and cleanup semantics.
- [`Activity`](https://react.dev/reference/react/Activity), [React Performance Tracks](https://react.dev/reference/dev-tools/react-performance-tracks), and the [React 19.2 release](https://react.dev/blog/2025/10/01/react-19-2) — hidden trees preserve UI state but disconnect effects/subscriptions; host tooling distinguishes reconnect/disconnect; selective hydration constraints.
- [React Server Components security follow-up](https://react.dev/blog/2025/12/11/denial-of-service-and-source-code-exposure-in-react-server-components) — `react-server-dom-*` packages require a patched 19.2.4+ line in any RSC-capable host; the UI artefact itself does not adopt RSC.

## Clojure and ClojureScript UI libraries

- [Reagent repository/readme](https://github.com/reagent-project/reagent) — Hiccup authoring, reactive deref, plain functions, local/lifecycle forms, caching.
- [UIx repository/readme](https://github.com/pitch-io/uix) — modern React/CLJS component API and current React compatibility.
- [UIx internals](https://github.com/pitch-io/uix/blob/master/docs/internals.md) — AOT element compiler, direct JS-oriented output, dual CLJS/JVM macro, analyzer linter.
- [UIx code linting](https://github.com/pitch-io/uix/blob/master/docs/code-linting.md) — build-failing Hook rules, exhaustive deps, keys, DOM props, Reagent/re-frame read diagnostics.
- [UIx props validation](https://github.com/pitch-io/uix/blob/master/docs/props-validation.md) — literal required-prop checks and production assertion elision.
- [UIx server rendering](https://github.com/pitch-io/uix/blob/master/docs/server-side-rendering.md) — JVM emitter, hydration, foreign-JS limits.
- [Helix component guide](https://github.com/lilactown/helix/blob/master/docs/creating-components.md) — real React functions, shallow props conversion, raw interop, memo/HOC and class escape hatch.
- [Rum](https://github.com/tonsky/rum) — small explicit surface, mixins, reactive reads, static equality, and Daiquiri's compile-known/interpret-unknown split.
- [Fulcro Developers Guide](https://book.fulcrologic.com/) — co-located queries/identity and local reasoning; also documents that ident-optimized targeted rendering is obsolete with React 18 async rendering/hooks.
- [Keechma/next](https://cljdoc.org/d/keechma/next/0.1.3/doc/readme) — dependency-owned controller lifecycle, automatic start/stop, UI independence.
- [Replicant](https://replicant.fun/) — UI and handlers as data, headless testing, aliases, whole-root data flow.
- [Rumext user guide](https://funcool.github.io/rumext/latest/user-guide.html) — macro-generated function components in a production CLJS UI system.
- [Om repository](https://github.com/omcljs/om) — historical component query/reconciler and normalization influence, considered through Fulcro's current successor documentation.

## React-targeted state, data, and lifecycle libraries

- [React-Redux Hooks](https://react-redux.js.org/api/hooks) — selector equality, dev stability checks, per-call subscriptions, stale props and zombie children.
- [Zustand introduction](https://zustand.docs.pmnd.rs/learn/getting-started/introduction), [`useStoreWithEqualityFn`](https://zustand.docs.pmnd.rs/reference/hooks/use-store-with-equality-fn), and [`useShallow`](https://zustand.docs.pmnd.rs/learn/guides/prevent-rerenders-with-use-shallow) — small selector/store boundary, equality-driven skips, concurrency concerns.
- [Jotai atom](https://jotai.org/docs/core/atom) — tracked read dependencies, referential identity, debug labels, mount/unmount ownership.
- [MobX React integration](https://mobx.js.org/react-integration.html) — render-time dependency capture, leaf reads, automatic relevant-only rerendering, trace tools.
- [TanStack Query render optimizations](https://tanstack.com/query/latest/docs/framework/react/guides/render-optimizations) — structural sharing, stable data, tracked properties, selection, memoization.
- [Valtio snapshots](https://valtio.dev/docs/api/advanced/snapshot) — immutable cached snapshots, exact reference reuse, copy-on-write structural sharing.
- [Relay fragments](https://relay.dev/docs/guided-tour/rendering/fragments/) — co-located component data requirements, globally unique compiler artefacts, automatic fragment subscription and composition.
- [XState actors](https://stately.ai/docs/actors) — explicit parent/child lifetime, snapshots, subscription cleanup, causal event model.
- [React Hook Form](https://react-hook-form.com/) — narrow subscriptions, isolated rerenders, HTML/uncontrolled input leverage, form-local frequency.
- [Preact Signals](https://preactjs.com/guide/v10/signals/) — automatic dependency capture, batching, computed values, direct-renderer optimizations and their React boundary trade-offs.

## How sources influenced the result

No single source supplies the proposed architecture. The combination is:

- Reagent's read/markup ergonomics;
- UIx's AOT and lint architecture;
- Helix's honest React boundary;
- current re-frame2's frame/derivation/resource/trace contracts;
- React's commit and external-store rules;
- data-shaped events from Replicant;
- compiler identity from Relay/Fulcro;
- conditional read capture from Jotai/MobX/signals;
- stable publication from Valtio/TanStack Query;
- stale-props avoidance from React-Redux;
- lifetime clarity from Keechma/XState;
- frequency locality from React Hook Form;
- absence and measurement discipline from reagent-slim.

Ideas that require a second state model were used as constraints or rejected, not embedded.
