# re-frame.adapter.reagent

Use this namespace to run a re-frame2 app on Reagent. It provides the `adapter` you pass to `rf/init!` at boot, and the `client-root`, `render!` and `unmount!` functions your entry namespace mounts the app through.

Pick it when your views are hiccup; the Core guide uses it. If your components are React function components written with UIx hooks, use [`re-frame.adapter.uix`](re-frame.adapter.uix.md) instead. It ships in `day8/re-frame2-reagent`; [Artefact and floor](#artefact-and-floor) has the coordinate and the React and Reagent versions it needs.

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))
```

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _]
    {:db (update db :counter/value (fnil inc 0))}))

(rf/reg-sub :counter/value
  (fn [db _] (:counter/value db 0)))

(rf/reg-view counter []
  [:button {:on-click #(dispatch [:counter/inc])}
   "Clicked " @(subscribe [:counter/value]) " times"])

(defonce app-root (reagent-adapter/client-root))

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (reagent-adapter/render! app-root
      [rf/frame-root {:id :app/main}
       [counter]]
      el)))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (mount!))
```

Everything else a Reagent app calls is on [`re-frame.core`](re-frame.core.md): `reg-view`, the `frame-root` and `frame-provider` components, `capture-frame`, `with-frame`, and the lifecycle functions `init!`, `destroy-adapter!` and `current-adapter`. There are no hooks here: a view registered with `reg-view` gets `dispatch` and `subscribe` injected instead. A plain Reagent function rendered as a component cannot read the surrounding `frame-root` or `frame-provider`, so an ambient `rf/subscribe` or `rf/dispatch` in its body raises `:rf.error/no-frame-context`; register it with `reg-view` (or `reg-view*`), or name the frame with `{:frame …}`. The dependency is one-way: this namespace requires `re-frame.core`, and core never requires it. [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) walks through the entry namespace.

## Adapter spec

### `adapter`

- **Kind**: var (map)
- **Signature**:
  ```clojure
  {:kind                      :rf.adapter/reagent
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
    - Once it is installed, `(rf/current-adapter)` returns this map, and its presence is how you ask whether an adapter is installed. `(:kind (rf/current-adapter))` reads `:rf.adapter/reagent`.
    - Frames are scoped with core's `frame-provider` and `frame-root`, whose children are trailing hiccup: `[rf/frame-provider {:frame …} & children]`.
- **Example**:
  ```clojure
  (rf/init! reagent-adapter/adapter)   ;; install the substrate once, at boot
  ```

## Artefact and floor

The adapter ships in `day8/re-frame2-reagent`, which carries stock Reagent (`reagent.core`, `reagent.dom.client`, `reagent.dom.server`). It needs React 19 and runs on Reagent 2.x; there is no React 17/18 or Reagent 1.x path.

A view rendered in a pass that React discards before committing keeps its subscriptions for the life of the page. Such passes include a Suspense boundary suspending on first mount, an error boundary catching on mount, and a hidden `Activity` that is never shown. Each change to one of those subscriptions force-updates the never-mounted instance, and React's development build warns about it. Stock Reagent gives the adapter no commit signal to release them. UIx releases them within one macrotask, so prefer it where those patterns matter.

## The client root

A browser app needs one React root for the life of the page: created once, updated on every hot reload, released on teardown. `client-root`, `render!` and `unmount!` manage that root, so your entry namespace never creates one or touches `reagent.dom.client`. Allocate the handle under a `defonce` and call `render!` from the `^:dev/after-load` hook, as in the example at the top of this page, with `run` as the build's `:init-fn`. On a hot reload shadow-cljs calls `mount!` again: the `defonce` keeps the handle, `render!` updates the same root, and `frame-root` reuses the live frame without re-running `:initial-events`, so app-db survives the reload.

The raw React root is never exposed. `rf/destroy-adapter!` also releases it, exactly once.

### `client-root`

- **Kind**: function
- **Signature**:
  ```clojure
  (client-root) → handle
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
  (render! handle render-tree mount-point)      → nil
  (render! handle render-tree mount-point opts) → nil
  ```
- **Description**: Renders `render-tree` (hiccup) into the DOM element `mount-point` through `handle`: the first call creates the React root, and every later call updates it. Returns nil.
    - With `{:hydrate? true}` the first call hydrates the server-rendered markup already inside `mount-point` instead (see [`re-frame.ssr`](re-frame.ssr.md)). Later calls never create a second root or hydrate a second time.
    - A hydrating first call also reads `:on-recoverable-error`, which React calls with each error it recovers from (in a development build, a hydration mismatch emits `:rf.ssr/hydration-mismatch` first), and `:identifier-prefix`, which React's `useId` needs to match the server's and must be the prefix the server rendered under.
    - Because later calls update the same root, one call serves as both the boot path and the `^:dev/after-load` hook. `mount-point` is read on the first call only.
    - After `unmount!`, or after `rf/destroy-adapter!` has released the root, the next `render!` mounts afresh.
    - Call `rf/init!` before the first `render!`. `render!` does not check for an adapter, but the first frame or subscription the tree creates raises `:rf.error/no-adapter-installed`, or `:rf.error/adapter-disposed` after `rf/destroy-adapter!`. Install an adapter again before rendering afresh.
- **Example**:
  ```clojure
  (reagent-adapter/render! app-root [rf/frame-root {:id :app/main} [counter]] el)
  ;; Later calls update the same root and reuse its frame.

  ;; Alternative boot for an SSR page: hydrate on this handle's FIRST render.
  (defonce hydrated-root (reagent-adapter/client-root))
  (reagent-adapter/render! hydrated-root
    [rf/frame-provider {:frame :app/main} [counter]] el {:hydrate? true})
  ;; Hydration has already installed :app/main; later renders update this root.
  ```

### `unmount!`

- **Kind**: function
- **Signature**:
  ```clojure
  (unmount! handle) → nil
  ```
- **Description**: Unmounts the React root `handle` holds and returns the handle to inert, so a later `render!` mounts afresh. Returns nil.
    - Unmounting releases the view tree; it does not destroy the frames used by that tree. A `frame-root` reuses its live frame on remount. If the app owns a frame that should end here, call [`rf/destroy-frame!`](re-frame.core.md#destroy-frame) separately.
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
- **Description**: Flushes pending Reagent renders synchronously inside React's `act()`, for tests. Returns nil. Use it when a test mounts into a real DOM and must let React commit before it reads the DOM; most Reagent view tests need no DOM, and call the view and walk its hiccup with [`re-frame.test-helpers`](re-frame.test-helpers.md) instead.
    - 0-arity: drains the queued renders and effects.
    - 1-arity: runs the thunk `f`, then drains the renders, inside `act()`.
    - When `act()` is not available in the current React build, it flushes without it: `f` still runs and the render queue still drains.
    - It settles what the test drives: a mount, or a `dispatch-sync` run inside `f`. A `dispatch` queues on the router instead, so wait for it with `re-frame.test-support/poll-until`. Do not call it from inside a `dispatch-sync` handler; it runs a render.
    - React's `act()` expects the test to set `globalThis.IS_REACT_ACT_ENVIRONMENT` to `true` while it drives React through `flush-views!`, and to set it back while it waits on React's own schedule. [Test a view §4](../core/testing/views.md#4-uix-hook-components-mount-it-for-real) shows the pattern.
    - [`re-frame.adapter.uix`](re-frame.adapter.uix.md#flush-views) publishes a `flush-views!` with the same name and nil return; without `act()`, that one does nothing and does not run `f`.
- **Example**:
  ```clojure
  (reagent-adapter/flush-views!)                                    ;; drain queued renders + effects
  (reagent-adapter/flush-views! #(rf/dispatch-sync [:counter/inc]))  ;; dispatch, then commit the re-render
  ```

## Server-side rendering

### `set-hiccup-emitter!`

- **Kind**: function
- **Signature**:
  ```clojure
  (set-hiccup-emitter! f)
  ```
- **Description**: Installs the function the adapter's `render-to-string` uses to turn a render tree into HTML. Requiring [`re-frame.ssr`](re-frame.ssr.md) installs it for you, so you rarely call this directly.
    - `f` takes the render tree and an opts map, and returns an HTML string.
    - Last call wins; pass `nil` to reset.
    - With no emitter installed, `render-to-string` raises `:rf.error/no-hiccup-emitter-bound`.
    - `rf/destroy-adapter!` clears the installed emitter. The next `rf/init!` re-installs `re-frame.ssr`'s emitter when that namespace is loaded, and otherwise leaves the slot empty. An emitter you install before `rf/init!` is kept, so install a custom emitter again after re-initialising.
    - The UIx adapter has the same function.
- **Example**:
  ```clojure
  (reagent-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (reagent-adapter/set-hiccup-emitter! nil)   ;; reset
  ```

## See also

- [`re-frame.adapter.uix`](re-frame.adapter.uix.md) — the hooks-first React adapter, with the same client-root functions.
- [`re-frame.ssr`](re-frame.ssr.md) — server-side rendering; installs `set-hiccup-emitter!` for you.
- [Use UIx](../core/how-to/use-uix-or-slim.md) — choosing a substrate, and moving an app's views to UIx.
- [Views](../core/views.md) — why the substrate only shows up in the view body.
- [Adapter](../core/glossary.md#adapter) and [substrate](../core/glossary.md#substrate) in the glossary.
