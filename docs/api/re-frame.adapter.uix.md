# re-frame.adapter.uix

The UIx adapter is the seam between re-frame2's substrate-agnostic core and UIx, a hooks-first React substrate. It ships in its own artefact (`day8/re-frame2-uix`) and exposes a small surface that matches the React/hooks idiom: `use-subscribe` and `use-resource-lease` hooks, a `frame-provider` component, the `adapter` spec map you pass to `init!`, and a few adapter-side seams for tests, SSR, and code-gen. The dependency direction is one-way — the adapter depends on `re-frame.core`, never the reverse — which is why these surfaces live in `re-frame.adapter.uix` rather than being re-exported from core: an app requires the adapter it wants and passes its `adapter` Var into `(rf/init! …)`. There is no auto-injection — UIx components call `use-subscribe` and `(rf/capture-frame)` directly.

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
  {:kind                      :rf.adapter/uix
   :make-state-container      …
   :read-container            …
   :replace-container!        …
   :subscribe-container       …
   :make-derived-value        …
   :render                    …
   :render-to-string          …
   :register-context-provider …
   :flush-render!             …
   :dispose-adapter!          …}
  ```
- **Description**: The adapter spec passed to `(rf/init! ...)`. Implements the same substrate adapter contract as the Reagent and Helix adapters; `:kind` is `:rf.adapter/uix`.
- **Example**:
  ```clojure
  (rf/init! uix-adapter/adapter)
  ```

## Hooks

### `use-subscribe`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Description**: "Subscribe inside a UIx component." The hook-shaped equivalent of `subscribe` for UIx components. Returns the current sub value; re-renders the calling component when the value changes. The 1-arg form resolves the frame through the standard chain — `with-frame` dynamic scope first, then the surrounding `frame-provider` — and raises `:rf.error/no-frame-context` when neither is in scope (no `:rf/default` floor). The 2-arg form pins to an explicit frame-id, bypassing the chain.
- **Example**:
  ```clojure
  (defui cart-total []
    (let [total (uix-adapter/use-subscribe [:cart/total])]
      ($ :span total)))
  ```

### `use-resource-lease`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-resource-lease descriptor) → nil
  (use-resource-lease descriptor opts) → nil
  ```
- **Description**: Takes a resource liveness lease for the calling component's mounted lifetime: on mount dispatches `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on unmount dispatches `:rf.resource/release-owner` for that same lease. Returns nil — a lifecycle hook, not a read; pair with `use-subscribe` on a `[:rf.resource/*]` query to read the data. `descriptor` is the resource-instance identity `{:resource … :scope … :params …}`. `opts` keys: `:cause` (recorded on the ensure; defaults to `[:lease :mount]`) and `:frame` (pin to an explicit frame-id). Without `:frame`, frame resolution mirrors `use-subscribe`, raising `:rf.error/no-frame-context` when no frame is in scope. Changing the resolved frame, descriptor, or cause releases the old lease and takes a fresh one; the events are inert when the resources artefact is not loaded.
- **Example**:
  ```clojure
  ;; Hold a liveness lease on a polled resource while the widget is mounted.
  (uix-adapter/use-resource-lease {:resource :my/feed
                                   :scope    :rf.scope/global
                                   :params   {:page 0}})
  ```

### `use-current-frame`

- **Kind**: UIx hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw, or :rf.frame/no-provider
  ```
- **Description**: "What frame am I in?" — for components that need to thread the frame through hand-written child callbacks. React-context tier only: returns the surrounding `frame-provider`'s frame keyword, or the no-provider sentinel `:rf.frame/no-provider` when no provider sits above — never nil, never a synthesised default. Does not consult the `with-frame` dynamic var; for the full resolution chain use `rf/current-frame-id`.

> **NOT USED** — no call sites found in `implementation/`, `examples/`, or `tools/`.

## Components

### `frame-provider`

- **Kind**: UIx component (function — one component, two config shapes)
- **Signatures**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session} child…)                        ;; SCOPE an existing frame
  ($ uix-adapter/frame-provider {:id :session :images [session-image]} child…)   ;; ENSURE create-if-absent / reuse
  ```
- **Description**: The UIx-shaped merged frame provider, dispatched on the prop map: `{:frame …}` scopes an already-created frame — raises `:rf.error/frame-provider-frame-absent` when the frame does not exist, `:rf.error/no-frame-context` on a nil `:frame`, and `:rf.error/bad-frame-provider-arg` on a non-keyword `:frame`; `{:id …}` ensures a named frame (create-if-absent / reuse-no-reseed, `make-frame` opts incl. `:images` / `:initial-events`, no destroy-on-unmount) — `:id` must be a keyword, and a missing / nil / non-keyword `:id` raises `:rf.error/ensure-frame-provider-missing-id`. Re-mounting the ENSURE shape under the same `:id` neither destroys durable state nor re-runs `:initial-events`. Children ride the idiomatic `$` trailing-args channel — pass them after the prop map, exactly as for any other UIx component (no `:children` prop-map key).
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
- **DOM source-coord annotations.** Adapters inject `data-rf2-source-coord` on every registered view's root element, and `wrap-view` is the explicit seam for that injection. The attribute is gated on debug builds and elided from production `:advanced` builds via dead-code elimination, so it costs no shipped bytes; it powers click-to-source in Xray and re-frame2-pair. Full contract in the [Observability concept guide](../core/observability.md).

## See also

- [re-frame.core](re-frame.core.md) — the substrate-agnostic ergonomic surface (`capture-frame`, `with-frame`, `with-new-frame`, `frame-provider`) plus the `init!` / `install-adapter!` / `current-adapter` / `adapter-disposed?` lifecycle.
- [re-frame.adapter.helix](re-frame.adapter.helix.md) — the parallel hooks-first adapter; the UIx surface transfers to it one-for-one.
- [re-frame.adapter.reagent](re-frame.adapter.reagent.md) — the default (inline) substrate.
- [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md) — narrative coverage with worked examples and the full decision set.
- [Adapter (glossary)](../core/glossary.md#adapter) — the substrate seam, defined.
