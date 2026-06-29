# re-frame.adapter.uix

The UIx adapter is the seam between re-frame2's substrate-agnostic core and UIx, a hooks-first React substrate. It ships in its own artefact (`day8/re-frame2-uix`) and exposes a small surface that matches the React/hooks idiom: a `use-subscribe` hook, a `frame-provider` component, the `adapter` spec map you pass to `init!`, and a few adapter-side seams for tests, SSR, and code-gen. The dependency direction is one-way — the adapter depends on `re-frame.core`, never the reverse — which is why these surfaces live in `re-frame.adapter.uix` rather than being re-exported from core: an app requires the adapter it wants and passes its `adapter` Var into `(rf/init! …)`. There is no auto-injection — UIx components call `use-subscribe` and `(rf/capture-frame)` directly.

```clojure
(:require [re-frame.adapter.uix :as uix-adapter])
```

UIx users register their views by Var (the React-component idiom) or with `rf/reg-view*` if they want registry-keyed view addressing — `reg-view` (the Reagent macro) does **not** cover UIx. A minimal app wires the adapter into `init!` and reads subscriptions through the hook:

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

## Adapter spec

### `adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:make-state-container …
   :render …
   :dispose-adapter! …}
  ```
- **Description**: The adapter spec passed to `(rf/init! ...)`.

## Hooks

### `use-subscribe`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Description**: "Subscribe inside a UIx component." The hook-shaped equivalent of `subscribe` for UIx components. Re-renders when the sub value changes.

### `use-current-frame`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw
  ```
- **Description**: "What frame am I in?" — for components that need to thread the frame through hand-written child callbacks.

> **NOT USED** — no call sites found in `implementation/`, `examples/`, or `tools/`.

## Components

### `frame-provider`

- **Kind**: UIx component (function — one component, two config shapes)
- **Signatures**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session} child…)                        ;; SCOPE an existing frame
  ($ uix-adapter/frame-provider {:id :session :images [session-image]} child…)   ;; ENSURE create-if-absent / reuse
  ```
- **Description**: The UIx-shaped merged frame provider, dispatched on the prop map: `{:frame …}` scopes an already-created frame (fails loud if absent), `{:id …}` ensures a named frame (create-if-absent / reuse-no-reseed, `make-frame` opts, no destroy-on-unmount). Children ride the idiomatic `$` trailing-args channel — pass them after the prop map, exactly as for any other UIx component (no `:children` prop-map key).
- **Example**:
  ```clojure
  ;; ENSURE shape at the render root: create the frame on first mount, seed it
  ;; once via :initial-events, reuse (no re-seed) on hot-reload re-mount.
  ($ uix-adapter/frame-provider {:id :app :initial-events [[:counter/initialise]]}
     ($ counter-app))
  ```

## Adapter seams

### `wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Description**: Adapter-side source-coord injection. Most users register through `reg-view*`; `wrap-view` is for code-gen and library scaffolding.
- **Example**:
  ```clojure
  ;; Code-gen / scaffolding seam: wrap a component head so its root DOM element
  ;; carries data-rf2-source-coord (dev only; elided in production builds).
  (def wrapped-row
    (uix-adapter/wrap-view ::row {:line 42 :column 7}
                           (fn [_props] ($ :div "row"))))
  ```

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests.
- **Example**:
  ```clojure
  ;; Test-only: flush pending renders synchronously, returns nil.
  (uix-adapter/flush-views!)               ;; 0-arity: drain queued renders + effects
  (uix-adapter/flush-views! (fn [] nil))   ;; 1-arity: run the thunk inside act()
  ```

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Install a render-tree → HTML fn. Parity with the Reagent adapter's late-bind seam for SSR.
- **Example**:
  ```clojure
  ;; SSR: install a render-tree → HTML emitter (normally wired for you by
  ;; requiring re-frame.ssr). Pass nil to reset.
  (uix-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (uix-adapter/set-hiccup-emitter! nil)
  ```

## Notes

- **Shared React Context.** The `frame-provider` in all three adapters (Reagent, UIx, Helix) consumes the **same** `createContext` object, factored into `re-frame.adapter.context` (a CLJS-only file in core). There is exactly one Context, not three, so a mixed-substrate app composes — a UIx `frame-provider` can wrap a Reagent or Helix subtree, and a UIx subtree can be wrapped by a provider from either of the other substrates.
- **DOM source-coord annotations.** Adapters inject `data-rf2-source-coord` on every registered view's root element, and `wrap-view` is the explicit seam for that injection. The attribute is gated on debug builds and elided from production `:advanced` builds via dead-code elimination, so it costs no shipped bytes; it powers click-to-source in Xray and re-frame2-pair. Full contract in the [Observability concept guide](../core/concepts/observability.md).

## See also

- [re-frame.core](re-frame.core.md) — the substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`) plus the `init!` / `install-adapter!` / `current-adapter` / `adapter-disposed?` lifecycle.
- [re-frame.adapter.helix](re-frame.adapter.helix.md) — the parallel hooks-first adapter; the UIx surface transfers to it one-for-one.
- [re-frame.adapter.reagent](re-frame.adapter.reagent.md) — the default (inline) substrate.
- [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md) — narrative coverage with worked examples and the full decision set.
- [Adapter (glossary)](../core/glossary.md#adapter) — the substrate seam, defined.
