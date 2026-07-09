# re-frame.adapter.helix

`re-frame.adapter.helix` binds re-frame2 to the Helix React substrate. It ships as its own artefact (`day8/re-frame2-helix`) and depends on `re-frame.core`, never the reverse. A Helix app requires this namespace and passes its `adapter` Var into `(rf/init! ...)`. It exposes:

- the Helix-shaped hooks `use-subscribe`, `use-resource-lease`, and `use-current-frame`;
- the merged `frame-provider` component;
- the `wrap-view` view-wrapping seam;
- the `adapter` spec.

The substrate-agnostic carry and scoping primitives (`capture-frame`, `with-frame`, `with-new-frame`) live on [`re-frame.core`](re-frame.core.md). The surface mirrors the [UIx adapter](re-frame.adapter.uix.md) one-for-one. For choosing between substrates, see [Use UIx, Helix, or reagent-slim](../core/how-to/use-uix-helix-or-slim.md).

```clojure
(:require [re-frame.adapter.helix :as helix-adapter])
```

## Initialisation

### `adapter`

- **Kind**: Var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/helix
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
- **Description**: The adapter spec passed to `(rf/init! ...)`. Implements the same adapter contract as the Reagent and UIx adapters. `:kind` is `:rf.adapter/helix`.

## Hooks

### `use-subscribe`

- **Kind**: Helix hook (function)
- **Signature**:
  ```clojure
  (use-subscribe query-v) → current sub value
  (use-subscribe frame-kw query-v) → current sub value
  ```
- **Description**: The hook-shaped equivalent of `subscribe` for Helix components. Returns the current sub value and re-renders the calling component when it changes.
  - The 1-arg form resolves the frame through the surrounding scope: dynamic-var first, then React context. It raises `:rf.error/no-frame-context` when no frame is in scope.
  - The 2-arg form pins an explicit frame id.

### `use-resource-lease`

- **Kind**: Helix hook (function)
- **Signature**:
  ```clojure
  (use-resource-lease descriptor) → nil
  (use-resource-lease descriptor opts) → nil
  ```
- **Description**: Holds a resource liveness lease for the calling component's mounted lifetime. On mount it dispatches `:rf.resource/ensure` with an app-minted `[:lease …]` owner; on unmount it dispatches `:rf.resource/release-owner` for that lease. Returns nil: it manages liveness only. Read the data with `use-subscribe` on a `[:rf.resource/*]` query.
  - `descriptor` — resource-instance identity `{:resource … :scope … :params …}`.
  - `opts` — `:cause` (recorded on the ensure; defaults to `[:lease :mount]`) and `:frame` (pin to an explicit frame id, bypassing ambient resolution).
  - Without `:frame`, the frame resolves through the same chain as `use-subscribe`, raising `:rf.error/no-frame-context` when no frame is in scope.
  - Inert unless the resources artefact (`day8/re-frame2-resources`) is on the classpath to register the resource events.
- **Example**:
  ```clojure
  (use-resource-lease {:resource :my/feed :scope :rf.scope/global :params {:page 0}})
  ```

### `use-current-frame`

- **Kind**: Helix hook (function)
- **Signature**:
  ```clojure
  (use-current-frame) → frame-kw, or :rf.frame/no-provider when no provider is above
  ```
- **Description**: A raw React-context read of the surrounding `frame-provider`'s frame keyword. When no frame-provider sits above, it returns `:rf.frame/no-provider` — never a synthesised default. It consults the React-context tier only, not the dynamic-var tier; for the full resolution chain, use `(rf/current-frame-id)`.

> **NOT USED** — no call sites found in `implementation/`, `examples/`, or `tools/`.

## Components

### `frame-provider`

- **Kind**: Helix component (function — one component, two config shapes)
- **Signatures**:
  ```clojure
  ($ helix-adapter/frame-provider {:frame :session} child…)                        ;; SCOPE an existing frame
  ($ helix-adapter/frame-provider {:id :session :images [session-image]} child…)   ;; ENSURE create-if-absent / reuse
  ```
- **Description**: The Helix-shaped merged frame provider, dispatched on the prop map. Children ride the `$` trailing-args channel. Pass them after the prop map, as for any Helix component (there is no `:children` prop-map key).
  - `{:frame …}` scopes an already-created frame; it fails loud if the frame is absent (`:rf.error/frame-provider-frame-absent`). A nil `:frame` raises `:rf.error/no-frame-context`; a non-keyword raises `:rf.error/bad-frame-provider-arg`.
  - `{:id …}` ensures a named frame: create it if absent, or reuse it without re-seeding. This shape accepts `make-frame` opts and never destroys the frame on unmount. A missing, nil, or non-keyword `:id` raises `:rf.error/ensure-frame-provider-missing-id`.
- **Example**:
  ```clojure
  ;; ENSURE shape at the render root: create the frame on first mount, seed it
  ;; once via :initial-events, reuse (no re-seed) on hot-reload re-mount.
  ($ helix-adapter/frame-provider {:id :app :initial-events [[:counter/initialise]]}
     ($ counter-app))
  ```

## View wrapping

### `wrap-view`

- **Kind**: function
- **Signature**:
  ```clojure
  (wrap-view id metadata user-fn) → wrapped fn
  ```
- **Description**: Adapter-side source-coord injection. Wraps `user-fn` in a function component that injects `data-rf2-source-coord` on the rendered root DOM element in debug builds. Production builds elide the injection and return `user-fn` unchanged. The returned fn has the same call signature as `user-fn` and is suitable as a Helix component head.
- **Example**:
  ```clojure
  ;; Code-gen / scaffolding seam: wrap a component head so its root DOM element
  ;; carries data-rf2-source-coord (dev only; elided in production builds).
  (def wrapped-row
    (helix-adapter/wrap-view ::row {:line 42 :column 7}
                             (fn [_props] (d/div "row"))))
  ```

## Test and SSR seams

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Wraps React's `act()` for tests. The 1-arity form calls `(act f)`; the 0-arity form flushes pending effects. Returns nil.
- **Example**:
  ```clojure
  ;; Test-only: flush pending renders synchronously, returns nil.
  (helix-adapter/flush-views!)               ;; 0-arity: drain queued renders + effects
  (helix-adapter/flush-views! (fn [] nil))   ;; 1-arity: run the thunk inside act()
  ```

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Installs a render-tree → HTML fn. This is the Helix side of the SSR late-bind seam, at parity with the Reagent and UIx adapters. Normally you don't call this directly; requiring `re-frame.ssr` wires the emitter for you. Pass `nil` to reset.
- **Example**:
  ```clojure
  ;; SSR: install a render-tree → HTML emitter (normally wired for you by
  ;; requiring re-frame.ssr). Pass nil to reset.
  (helix-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (helix-adapter/set-hiccup-emitter! nil)
  ```

## Worked example

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.helix :as helix-adapter]
          [helix.core :refer [defnc]]
          [helix.dom :as d])

(rf/init! helix-adapter/adapter)

(defnc cart-row [{:keys [item]}]
  (let [count (helix-adapter/use-subscribe [:cart/count])]
    (d/tr
      (d/td (:name item))
      (d/td count))))
```
