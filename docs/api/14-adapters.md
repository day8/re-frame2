# 14 — Adapters

An adapter is the seam between re-frame2's substrate-agnostic core and a specific React-flavoured reactive system. Reagent ships as the default. UIx and Helix are hooks-first React substrates with their own idioms; their adapters live in separate artefacts and expose a small, parallel surface that matches the React/hooks convention without forcing it onto the Reagent path.

This chapter is the per-substrate surface reference. The substrate-agnostic ergonomic surface (`dispatcher`, `subscriber`, `with-frame`, `bound-fn`, `frame-provider`) is rowed in [02 — Views](02-views.md) because those compose across all three substrates. This chapter is for the per-substrate hooks and the adapter-spec map.

Architecturally, the dependency direction is one-way: the adapter artefacts depend on `re-frame.core`; `re-frame.core` does not depend on any adapter. That's why UIx and Helix surfaces live in `re-frame.adapter.uix` / `re-frame.adapter.helix` rather than being re-exported from core — apps require the adapter they want and pass its `adapter` Var into `init!`.

## The Reagent adapter

The CLJS reference's default substrate. Reagent ships in `re-frame.adapter.reagent` (full) and `re-frame.adapter.reagent-slim` (without the React server-rendering tax, for SSR pipelines that don't want to ship `react-dom/server`).

There's no per-substrate hook surface — Reagent's idiom is "views are plain functions returning hiccup," and `reg-view` is the typed sugar over that pattern. The substrate-agnostic surface (`dispatcher`, `subscriber`, `with-frame`, `frame-provider`) is the full Reagent-side API; everything else flows through `reg-view*` and `dispatch` / `subscribe`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.reagent :as reagent])

(rf/init! reagent/adapter)
```

The two Reagent adapters differ in what they include:

| Adapter | Includes | Use case |
|---|---|---|
| `re-frame.adapter.reagent/adapter` | Full Reagent (`reagent.core`, `reagent.dom`, `reagent.dom.server`) | Client-side apps that may also render to string on the JVM. |
| `re-frame.adapter.reagent-slim/adapter` | Reagent without `reagent.dom.server` | Client-side apps that ship their bundle to browsers. Drops ~30KB by excluding `react-dom/server`. |

The slim variant is bundle-isolated — the `npm run test:reagent-slim:bundle-isolation` gate verifies stock Reagent / `react-dom/server` don't leak into builds that select the slim adapter.

## The UIx adapter

UIx-specific surfaces live in `re-frame.adapter.uix` (artefact `day8/re-frame2-uix`). The hook is named `use-subscribe` (matching the React/UIx idiom); there is no auto-injection — UIx components call the hook and `(rf/dispatcher)` directly. The full decision set lives at [Spec 006 §CLJS reference: UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate-rf2-3yij).

### `uix-adapter/adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:make-state-container …
   :render …
   :dispose-adapter! …}
  ```
- **Status**: v1
- **Description**: The adapter spec passed to `(rf/init! ...)`.

### `uix-adapter/use-subscribe`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Status**: v1
- **Description**: "Subscribe inside a UIx component." The hook-shaped equivalent of `subscribe` for UIx components. Re-renders when the sub value changes.

### `uix-adapter/use-current-frame`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw
  ```
- **Status**: v1
- **Description**: "What frame am I in?" — for components that need to thread the frame through hand-written child callbacks.

### `uix-adapter/frame-provider`

- **Kind**: UIx component (function)
- **Signature**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session :children […]})
  ```
- **Status**: v1
- **Description**: The UIx-shaped frame provider.

### `uix-adapter/wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Status**: v1
- **Description**: Adapter-side source-coord injection. Most users register through `reg-view*`; `wrap-view` is for code-gen and library scaffolding.

### `uix-adapter/flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Status**: v1
- **Description**: Wraps React's `act()` for tests.

### `uix-adapter/set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Status**: v1
- **Description**: Install a render-tree → HTML fn. Parity with the Reagent adapter's late-bind seam for SSR.

UIx users register their views by Var (the React-component idiom) or with `rf/reg-view*` if they want registry-keyed view addressing — `reg-view` (the Reagent macro) does **not** cover UIx. Full rationale: [Spec 006 §CLJS reference: UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate-rf2-3yij).

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.uix :as uix-adapter]
          [uix.core :refer [$ defui]])

(rf/init! uix-adapter/adapter)

(defui cart-row [{:keys [item]}]
  (let [count (uix-adapter/use-subscribe [:cart/count])]
    ($ :tr
       ($ :td (:name item))
       ($ :td count))))
```

## The Helix adapter

Helix-specific surfaces live in `re-frame.adapter.helix` (artefact `day8/re-frame2-helix`). The Helix adapter mirrors the UIx adapter exactly — the eight UIx decisions transfer one-for-one. The surface below is identical in shape to the UIx surface above.

### `helix-adapter/adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:make-state-container …
   :render …
   :dispose-adapter! …}
  ```
- **Status**: v1
- **Description**: The adapter spec passed to `(rf/init! ...)`.

### `helix-adapter/use-subscribe`

- **Kind**: Helix hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Status**: v1
- **Description**: "Subscribe inside a Helix component."

### `helix-adapter/use-current-frame`

- **Kind**: Helix hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw
  ```
- **Status**: v1
- **Description**: "What frame am I in?"

### `helix-adapter/frame-provider`

- **Kind**: Helix component (function)
- **Signature**:
  ```clojure
  ($ helix-adapter/frame-provider {:frame :session :children […]})
  ```
- **Status**: v1
- **Description**: The Helix-shaped frame provider.

### `helix-adapter/wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Status**: v1
- **Description**: Adapter-side source-coord injection.

### `helix-adapter/flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Status**: v1
- **Description**: Wraps React's `act()` for tests.

### `helix-adapter/set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Status**: v1
- **Description**: Install a render-tree → HTML fn. Parity with the Reagent and UIx adapters' late-bind seam.

The duplication between UIx and Helix is intentional — both expose the same hooks-first idiom; both decisions sets transfer; both surfaces are structurally identical. The two adapters are separate artefacts because the *underlying React-substrate libraries* are separate, not because the re-frame2 contract differs between them.

## The shared React Context

The `frame-provider` in all three adapters (Reagent, UIx, Helix) consumes the **same** `createContext` object, factored into `re-frame.adapter.context` (a CLJS-only file in core). The shared context is what makes a future mixed-substrate app compose: a Reagent `frame-provider` can wrap a UIx subtree; a Helix subtree can be wrapped by a UIx provider; the chain composes across substrate boundaries because there's exactly one Context, not three. Full rationale: [Spec 006 §CLJS reference: UIx as alternative substrate](../../spec/006-ReactiveSubstrate.md#cljs-reference-uix-as-alternative-substrate-rf2-3yij).

## DOM source-coord annotations

Every adapter whose host has a DOM-attribute concept (all three — Reagent / UIx / Helix on the browser) injects `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions (Fragments, non-DOM roots) are documented in [Spec 006 §Source-coord annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1).

Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the contract per [Spec 011 §Source-coord annotation under SSR](../../spec/011-SSR.md#source-coord-annotation-under-ssr).

## The Plain Atom adapter

There's a fourth adapter — Plain Atom — that ships in core and exists for two purposes:

1. **Tests that don't need a substrate.** JVM-runnable unit tests of pure handlers / subs / machines use Plain Atom as the no-render baseline.
2. **Non-rendering hosts.** JVM CLI tools, scheduler runners, conformance harnesses — anything that wants the re-frame2 cascade without the React layer.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.plain-atom :as plain])

(rf/init! plain/adapter)
```

`current-adapter` returns `:rf.adapter/plain-atom` when Plain Atom is installed. There's no view registry surface from this adapter — calling `reg-view` against a Plain Atom runtime works (the registry accepts the registration) but rendering is the host's problem.

## See also

- [02 — Views](02-views.md) — the substrate-agnostic ergonomic surface (`dispatcher`, `subscriber`, `with-frame`, `bound-fn`, `frame-provider`).
- [13 — Lifecycle](13-lifecycle.md) — `init!`, `install-adapter!`, `destroy-adapter!`, `current-adapter`, `adapter-disposed?`.
- [Spec 006 — Reactive Substrate](../../spec/006-ReactiveSubstrate.md) — the adapter contract.
- [Guide ch.21 — Adapters](../guide/21-adapters.md) — narrative coverage with worked examples.
