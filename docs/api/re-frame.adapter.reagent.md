# re-frame.adapter.reagent

Use this namespace to run a re-frame2 app on Reagent, the default browser substrate. It provides the `adapter` you pass to `rf/init!` at boot, and the `client-root`, `render!` and `unmount!` functions your entry namespace mounts the app through.

It ships in two artefacts, `day8/re-frame2-reagent` (full) and `day8/reagent-slim` (slim), and both publish this namespace; the variants are compared under [`adapter`](#adapter).

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.reagent :as reagent-adapter])
```

```clojure
(defonce app-root (reagent-adapter/client-root))

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (reagent-adapter/render! app-root
      [rf/frame-root {:id :rf/default :initial-events [[:app/initialise]]}
       [app-view]]
      el)))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (mount!))
```

Everything else a Reagent app calls is on [`re-frame.core`](re-frame.core.md), and works the same on every substrate: `reg-view`, `frame-root`, `frame-provider`, `capture-frame`, `with-frame`, and the lifecycle functions `init!`, `destroy-adapter!` and `current-adapter`. There are no hooks here: a view registered with `reg-view` gets `dispatch` and `subscribe` injected instead. The dependency is one-way: this namespace requires `re-frame.core`, and core never requires it. [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) walks through the entry namespace.

## Adapter spec

### `adapter`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/reagent   ;; :rf.adapter/reagent-slim on the slim variant
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
- **Description**: The adapter map you pass to `rf/init!` to install Reagent as the substrate (stock `reagent.core` / `reagent.dom.client`).
    - Installation is explicit: there is no default adapter and no keyword form, so require this namespace and pass `adapter` at the call site.
    - Once it is installed, `(rf/current-adapter)` returns this map, and its presence is how you ask whether an adapter is installed. `(:kind (rf/current-adapter))` reads `:rf.adapter/reagent`, or `:rf.adapter/reagent-slim` on the slim variant.
    - Frames are scoped with core's `frame-provider` and `frame-root`, whose children are trailing hiccup: `[rf/frame-provider {:frame …} & children]`.
- **Example**:
  ```clojure
  (rf/init! reagent-adapter/adapter)   ;; install the substrate once, at boot
  ```

Reagent ships in two variants. Both publish their adapter as `re-frame.adapter.reagent`, so the require and the `init!` line are the same; the Maven coordinate in your `deps.edn` selects the variant, and a build depends on one of them only.

| Variant | Maven coordinate | Adapter ns (require) | Includes | Use when |
|---|---|---|---|---|
| Full | `day8/re-frame2-reagent` | `re-frame.adapter.reagent` | stock Reagent (`reagent.core`, `reagent.dom.client`, `reagent.dom.server`) | client apps that may also render to a string on the JVM |
| Slim | `day8/reagent-slim` | `re-frame.adapter.reagent` | the `reagent2` rewrite; static HTML export via a pure-CLJS `reagent2.dom.server`, no `react-dom/server` | browser-only bundles; ~7–10 KB gzipped smaller (up to ~22–27 KB where the HTML-export path was in play) |

A `:git/sha` dependency on this repository is the exception to the shared name. The repository carries both adapters on one classpath, so there the slim adapter is `re-frame.adapter.reagent-slim`; the published jar renames it to `re-frame.adapter.reagent`.

A build on the slim variant includes neither stock Reagent nor `react-dom/server`. Switching an app from full to slim is a four-line change, shown in [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md).

## The client root

A browser app needs one React root for the life of the page: created once, updated on every hot reload, released on teardown. `client-root`, `render!` and `unmount!` manage that root, so your entry namespace never creates one or touches `reagent.dom.client`. Allocate the handle under a `defonce` and call `render!` from the `^:dev/after-load` hook, as in the example at the top of this page.

The raw React root is never exposed. `rf/destroy-adapter!` also releases it, exactly once.

### `client-root`

- **Kind**: function
- **Signature**:
  ```clojure
  (client-root)
  ```
- **Description**: Returns a new, inert client-root handle. It does no DOM work, so it is safe at namespace load under a `defonce`, in tests and on Node; the first `render!` through the handle creates (or hydrates) the React root.
    - The handle is opaque: pass it to `render!` and `unmount!` and nothing else.
- **Example**:
  ```clojure
  (defonce app-root (reagent-adapter/client-root))   ;; inert until the first render!
  ```

### `render!`

- **Kind**: function
- **Signature**:
  ```clojure
  (render! handle render-tree mount-point)
  (render! handle render-tree mount-point opts)
  ```
- **Description**: Renders `render-tree` (hiccup) into the DOM element `mount-point` through `handle`: the first call creates the React root, and every later call updates it. Returns nil.
    - With `{:hydrate? true}` the first call hydrates the server-rendered markup already inside `mount-point` instead (see [`re-frame.ssr`](re-frame.ssr.md)). Later calls never create a second root or hydrate a second time.
    - Because later calls update the same root, one call serves as both the boot path and the `^:dev/after-load` hook. `mount-point` is read on the first call only.
    - After `unmount!`, or after `rf/destroy-adapter!` has released the root, the next `render!` mounts afresh.
- **Example**:
  ```clojure
  (reagent-adapter/render! app-root [app-view] el)                   ;; first call: create + render
  (reagent-adapter/render! app-root [app-view] el)                   ;; later calls: update the same root
  (reagent-adapter/render! app-root [app-view] el {:hydrate? true})  ;; SSR page: hydrate once, then update
  ```

### `unmount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unmount! handle)
  ```
- **Description**: Unmounts the React root `handle` holds and returns the handle to inert, so a later `render!` mounts afresh. Returns nil.
    - Idempotent: a second call, or a call after `rf/destroy-adapter!` has released the root, does nothing.
- **Example**:
  ```clojure
  (reagent-adapter/unmount! app-root)   ;; releases the root; a repeat call is a no-op
  ```

## Test helpers

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Flushes pending Reagent renders synchronously inside React's `act()`, for tests. Returns nil.
    - 0-arity: drains the queued renders and effects.
    - 1-arity: runs the thunk `f`, then drains the renders, inside `act()`.
    - When `act()` is not available in the current React build, it flushes without it: `f` still runs and the render queue still drains.
    - Do not call it from inside a `dispatch-sync` handler; it runs a render.
    - [`re-frame.adapter.uix`](re-frame.adapter.uix.md#flush-views) publishes a `flush-views!` with the same name and nil return.
- **Example**:
  ```clojure
  (reagent-adapter/flush-views!)               ;; drain queued renders + effects
  (reagent-adapter/flush-views! (fn [] nil))   ;; run the thunk inside act()
  ```

## Server-side rendering

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Installs the function render-to-string uses to turn a render tree into HTML. Requiring [`re-frame.ssr`](re-frame.ssr.md) installs it for you, so you rarely call this directly.
    - Last call wins; pass `nil` to reset.
    - The UIx adapter has the same function.
- **Example**:
  ```clojure
  (reagent-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (reagent-adapter/set-hiccup-emitter! nil)   ;; reset
  ```

## See also

- [`re-frame.adapter.uix`](re-frame.adapter.uix.md) — the hooks-first React adapter, with the same client-root functions.
- [`re-frame.ssr`](re-frame.ssr.md) — server-side rendering; installs `set-hiccup-emitter!` for you.
- [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md) — choosing a substrate, including the switch to slim.
- [Views](../core/views.md) — why the substrate only shows up in the view body.
- [Adapter](../core/glossary.md#adapter) and [substrate](../core/glossary.md#substrate) in the glossary.
