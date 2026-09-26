# re-frame.adapter.uix

Use this namespace to run a re-frame2 app on UIx, a hooks-first React substrate. It provides the `adapter` you pass to `rf/init!` at boot, the `use-sub` and `use-frame` hooks components read and dispatch through, the `frame-root` and `frame-provider` components, and the `client-root`, `render!` and `unmount!` functions your entry namespace mounts the app through.

Pick it when your components are React function components written with `defui` and hooks, usually because the code around the app already is; for hiccup views, use [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md) or [Fresco](re-frame.fresco.md). It ships in the `day8/re-frame2-uix` artefact, which brings in `com.pitch/uix.core`.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.adapter.uix :as uix-adapter]
          [uix.core :refer [$ defui]])
```

```clojure
(defui counter-app []
  (let [n                  (uix-adapter/use-sub [:counter/value])
        {:keys [dispatch]} (uix-adapter/use-frame)]
    ($ :button {:on-click #(dispatch [:counter/inc])} n)))

(defonce app-root (uix-adapter/client-root))

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (uix-adapter/render! app-root
      ($ uix-adapter/frame-root {:id :rf/default :initial-events [[:counter/initialise]]}
         ($ counter-app))
      el)))

(defn run []
  (rf/init! uix-adapter/adapter)
  (mount!))
```

Everything else a UIx app calls is on [`re-frame.core`](re-frame.core.md), including the lifecycle functions `init!`, `destroy-adapter!` and `current-adapter`. Nothing is injected into a UIx component: it reads with `use-sub` and takes `dispatch` from `use-frame`. The dependency is one-way: this namespace requires `re-frame.core`, and core never requires it.

Components are plain `defui` functions that you mount by referring to their Var, the React idiom; the `reg-view` macro is Reagent-only. Register one with `rf/reg-view*` only when the call site holds an id rather than the Var (see [Registry-keyed views](#registry-keyed-views)). [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md) covers choosing and switching substrate.

## Adapter spec

### `adapter`

- **Kind**: var (map)
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
- **Description**: The adapter map you pass to `rf/init!` to install UIx as the substrate. It implements the same adapter contract as the Reagent adapter; `(:kind (rf/current-adapter))` reads `:rf.adapter/uix`.
- **Example**:
  ```clojure
  (rf/init! uix-adapter/adapter)   ;; install the substrate once, at boot
  ```

## Hooks

### `use-sub`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (use-sub query-v)                  → current sub value
  (use-sub query-v {:frame target})  → current sub value, from `target`
  ```
- **Description**: Returns the current value of the subscription `query-v` and re-renders the component when that value changes. Where `subscribe` returns a subscription, this returns its value. [`re-frame.fresco.native`](re-frame.fresco.native.md#use-sub) publishes the same hook under the same name for React islands under Fresco.
    - The 1-arity reads the frame from React context only: the nearest `frame-provider` (SCOPE) / `frame-root` (ENSURE) above the component. With no boundary above it, it raises `:rf.error/no-frame-context`; there is no fallback to `:rf/default`.
    - A `with-frame` scope around a render does not reach a hook, even when the render is synchronous (under `act()`, `flushSync` or a server render). React may render the same tree later, outside that scope, and the tree must resolve the same frame however the render was driven. `re-frame.fresco.native`'s hooks follow the same rule.
    - To set the frame explicitly, wrap the component in a `frame-provider`, or use the opts form. The opts form, `{:frame target}`, reads that one value from `target` and bypasses context; `target` is a frame-id keyword or a live frame value, as for `subscribe`, and `:frame` is required. When a whole subtree shares a frame, scope it with `frame-provider {:frame target}` and use the 1-arity.
- **Example**:
  ```clojure
  (defui cart-total []
    (let [total (uix-adapter/use-sub [:cart/total])]
      ($ :span total)))

  ;; The opts form: compare one query across two frames in one component.
  (defui price-compare [{:keys [sym]}]
    (let [a (uix-adapter/use-sub [:quote/price sym] {:frame :tenant-a})
          b (uix-adapter/use-sub [:quote/price sym] {:frame :tenant-b})]
      ($ :span a " / " b)))
  ```

### `use-frame`

- **Kind**: function (React hook)
- **Signature**:
  ```clojure
  (use-frame) → {:frame … :dispatch … :dispatch-sync … :subscribe …}
  ```
- **Description**: Returns the frame-locked ops map for the frame the component renders under — the same map `(rf/capture-frame)` returns. Destructure `dispatch` from it to dispatch from a UIx component; it is the hook form of the ops Reagent's `reg-view` injects.
    - It resolves the frame as `use-sub` does: from React context only, so a `with-frame` scope around a render does not reach it, and with no boundary above it raises `:rf.error/no-frame-context`.
    - It takes no options. For a named frame, call `(rf/capture-frame frame-id)` directly.
    - The map is the same object across re-renders while the resolved frame stays the same, so it is safe in effect deps and child props. A provider that switches frames re-renders the caller with a map for the new frame, and so does destroying the frame and creating another under the same id: the map belongs to the frame it was captured from, not to the id.
    - There is no hook that reads the frame context alone. `(:frame (use-frame))` gives the frame from React context; `(rf/current-frame-id)` checks a `with-frame` binding first, so inside a `with-frame` around a render the two can differ.
- **Example**:
  ```clojure
  (defui inc-button []
    (let [{:keys [dispatch]} (uix-adapter/use-frame)]   ;; take dispatch during render
      ($ :button {:on-click #(dispatch [:counter/inc])} "+")))
  ```

## Components

`frame-root` and `frame-provider` write the same React context as Reagent's `rf/frame-root` / `rf/frame-provider` and Fresco's `h/frame-root` / `h/frame-provider`, so the substrates nest: a UIx `frame-provider` can wrap a Reagent subtree, and the reverse.

### `frame-provider`

- **Kind**: component (UIx)
- **Signature**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session} child…)   ;; SCOPE an existing frame
  ```
- **Description**: Scopes a subtree to a frame that already exists; it creates, refreshes and destroys nothing. To create the frame if it is absent, use [`frame-root`](#frame-root): roots ensure, providers scope.
    - Pass children after the props map, as for any UIx component; there is no `:children` prop key.
- **Errors**:
    - `:rf.error/frame-provider-frame-absent` when the frame does not exist
    - `:rf.error/no-frame-context` on a nil `:frame`
    - `:rf.error/bad-frame-provider-arg` on a `:frame` that is neither a keyword nor a live frame value
    - `:rf.error/frame-provider-given-id` when given an `:id` (the ENSURE key; use `frame-root`)
- **Example**:
  ```clojure
  ($ uix-adapter/frame-provider {:frame :session}
     ($ dashboard))
  ```

### `frame-root`

- **Kind**: component (UIx)
- **Signature**:
  ```clojure
  ($ uix-adapter/frame-root {:id :session :images [session-image]} child…)   ;; ENSURE create-if-absent / reuse
  ```
- **Description**: Creates the named frame if it is absent, or reuses it without re-seeding if it is live, and provides it to the subtree. It takes the `rf/make-frame` options, including `:images` and `:initial-events`, and never destroys the frame on unmount.
    - `:id` is required and must be a keyword; a missing, nil or non-keyword `:id` raises `:rf.error/frame-root-missing-id`.
    - The frame is created and seeded in a client `useLayoutEffect` at commit, not during render. The first render emits no children; they render once the frame is live. A render React discards before commit creates and seeds nothing.
    - Re-mounting under the same `:id` (hot reload, React StrictMode's double mount in development) keeps the frame's state and does not re-run `:initial-events`.
    - Changing a mounted boundary's `:id` or opts raises `:rf.error/frame-root-reconfigured`; to switch frames, give the `frame-root` a React `:key` that changes. A `:frame` key (the SCOPE key) raises `:rf.error/frame-root-given-frame`, naming `frame-provider`.
    - Pass children after the props map.
- **Example**:
  ```clojure
  ;; create the frame on first mount, seed it once via :initial-events,
  ;; reuse (no re-seed) on hot-reload re-mount.
  ($ uix-adapter/frame-root {:id :app :initial-events [[:counter/initialise]]}
     ($ counter-app))
  ```

## The client root

A browser app needs one React root for the life of the page: created once, updated on every hot reload, released on teardown. `client-root`, `render!` and `unmount!` manage that root, so your entry namespace never creates one or builds a `uix.dom` root itself. Allocate the handle under a `defonce` and call `render!` from the `^:dev/after-load` hook, as in the example at the top of this page, with `run` as the build's `:init-fn`. On a hot reload shadow-cljs calls `mount!` again: the `defonce` keeps the handle, `render!` updates the same root, and `frame-root` reuses the live frame without re-running `:initial-events`, so app-db survives the reload. [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) has the whole recipe.

These are the same three functions as on [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md#the-client-root), with the same behaviour, except that `render!` here takes a React element built with `uix.core/$` rather than hiccup. The root is created through `react-dom/client`, so the app needs no `com.pitch/uix.dom` dependency to mount. The raw React root is never exposed, and `rf/destroy-adapter!` also releases it, exactly once.

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
  (defonce app-root (uix-adapter/client-root))   ;; inert until the first render!
  ```

### `render!`

- **Kind**: function
- **Signature**:
  ```clojure
  (render! handle element mount-point)
  (render! handle element mount-point opts)
  ```
- **Description**: Renders `element`, a React element built with `uix.core/$`, into the DOM element `mount-point` through `handle`: the first call creates the React root, and every later call updates it. Returns nil.
    - With `{:hydrate? true}` the first call hydrates the server-rendered markup already inside `mount-point` instead (see [`re-frame.ssr`](re-frame.ssr.md)). Later calls never create a second root or hydrate a second time.
    - Because later calls update the same root, one call serves as both the boot path and the `^:dev/after-load` hook. `mount-point` is read on the first call only.
    - `opts` takes `:hydrate?` and `:on-recoverable-error`. When hydrating, the adapter's hydration-mismatch reporter wraps your `:on-recoverable-error` and still calls it. There are no UIx-only keys.
    - Hiccup or other CLJS data in the element position (a vector, seq or map) raises `:rf.error/hiccup-on-element-render-slot`, on the first render and every later one. Hiccup mounts only on the Reagent adapters.
    - After `unmount!`, or after `rf/destroy-adapter!` has released the root, the next `render!` mounts afresh.
- **Example**:
  ```clojure
  (uix-adapter/render! app-root ($ app-view) el)                   ;; first call: create + render
  (uix-adapter/render! app-root ($ app-view) el)                   ;; later calls: update the same root
  (uix-adapter/render! app-root ($ app-view) el {:hydrate? true})  ;; SSR page: hydrate once, then update
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
  (uix-adapter/unmount! app-root)   ;; releases the root; a repeat call is a no-op
  ```

## Registry-keyed views

Mounting a component by its Var is the idiom, and most UIx code needs nothing else. Use the registry when the call site cannot name the Var: a view chosen at runtime, a component used across a module boundary, or a library that ships ids rather than symbols.

`rf/reg-view*` takes an id and a component; `rf/view` returns a UIx component head, which you mount with `$` like any other:

```clojure
(defui cart-row [{:keys [item]}]
  (let [count (uix-adapter/use-sub [:cart/count])]
    ($ :tr
       ($ :td (:name item))
       ($ :td count))))

(rf/reg-view* ::cart-row cart-row)

;; …anywhere, including a module that cannot see the Var:
($ (rf/view ::cart-row) {:item item})
```

The head behaves like the registered component itself:

- Hand it to `$` as the component type. Do not call it inside a host component of your own: the hooks and the instance lifetime would then belong to your host rather than to the registered view.
- Props and children arrive unchanged. The head is marked as a UIx component, so `$` passes the original ClojureScript map through UIx's `argv` channel: namespaced keywords stay keywords, nested maps stay maps, and trailing `$` children reach the component as `:children`.
- It does not matter whether `rf/reg-view*` runs before or after `rf/init!`: `rf/view` resolves the head against the adapter installed when you look it up. The usual order is to register at namespace load and call `init!` afterwards, as [Boot and mount an app](../core/how-to/boot-and-mount-an-app.md) does. Repeat lookups return the same object, so React reconciles it as one component type instead of remounting.

`rf/view` returns `nil` for an unregistered id.

In development builds, a registered view's root element gets a `data-rf2-source-coord` attribute, which Xray and re-frame2-pair use for click-to-source. Production builds remove it, so it costs no shipped bytes. See [Observability](../core/observability.md).

## Controlled inputs and the caret

A UIx `:input` with a `:value` and an `:on-change` is a plain React controlled input, whatever else is in the bundle. Left unset, UIx chooses between React's implementation and a port of Reagent's controlled-input workaround by checking whether Reagent is on the classpath, so adding the Reagent adapter beside UIx would change how the UIx app's inputs behave. Requiring this namespace sets `uix.compiler.input/*use-reagent-input-enabled?*` to `false` at load, which pins React's implementation.

When your handler rejects or rewrites a keystroke, React restores the field inside the event: the rejected character is gone before `dispatchEvent` returns, with nothing re-rendered. Writing `value` moves the caret to the end of the field, though. Type `z` into `"12345"` with the caret at position 2, have the handler reject it, and you get `"12345"` with the caret at 5. This is React's own controlled-input caret jump, and it happens on every write React makes. A handler that accepts the keystroke unchanged never triggers a write and never moves the caret.

The port makes the element uncontrolled and restores both value and caret itself, but one animation frame later, off Reagent's `requestAnimationFrame` queue, never inside the event. So React's implementation restores the value in time but moves the caret, and the port keeps the caret but restores a frame late. The adapter pins React's path because inputs that behave differently depending on what else is in the bundle are the worse problem.

To use the port instead, set the var yourself after requiring the adapter and before you render:

```clojure
(:require [re-frame.adapter.uix :as uix-adapter]
          [uix.compiler.input])

(set! uix.compiler.input/*use-reagent-input-enabled?* true)
```

`true` selects UIx's port, `false` selects React's implementation, and `nil` restores UIx's classpath check. The port uses `reagent.impl.batching`, so it needs Reagent on the classpath and is not an option for a UIx-only bundle.

## Test helpers

### `flush-views!`

- **Kind**: function
- **Signature**:
  ```clojure
  (flush-views!)
  (flush-views! f)
  ```
- **Description**: Flushes pending renders synchronously inside React's `act()`, for tests. Returns nil.
    - 0-arity: flushes pending renders and effects.
    - 1-arity: runs the thunk `f` inside `act()`.
    - When `act()` is not available (React's production bundle omits it), it does nothing, and `f` does not run.
    - It settles what the test drives: a mount, or a `dispatch-sync` run inside `f`. A real DOM event's `dispatch` queues on the router, and settles with `poll-until` instead. Do not call it from inside a `dispatch-sync` handler; it runs a render.
    - The complete component test — mount, click, settle, assert, unmount — is in [Test a view §4](../core/testing/views.md#4-uix-hook-components-mount-it-for-real).
- **Example**:
  ```clojure
  (uix-adapter/flush-views!)                                    ;; flush pending renders + effects
  (uix-adapter/flush-views! #(rf/dispatch-sync [:counter/inc]))  ;; dispatch, then commit the re-render
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
    - The Reagent adapter has the same function.
- **Example**:
  ```clojure
  (uix-adapter/set-hiccup-emitter! (fn [tree _opts] (str tree)))
  (uix-adapter/set-hiccup-emitter! nil)   ;; reset
  ```

## See also

- [`re-frame.adapter.reagent`](re-frame.adapter.reagent.md) — the default browser adapter, with the same client-root functions.
- [`re-frame.fresco.native`](re-frame.fresco.native.md) — the same `use-sub` / `use-frame` hooks for React islands under Fresco.
- [Use UIx or reagent-slim](../core/how-to/use-uix-or-slim.md) — worked examples and the full substrate decision.
- [Adapter](../core/glossary.md#adapter) in the glossary.
