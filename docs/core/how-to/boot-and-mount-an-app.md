# Boot and mount an app

Your app's entry namespace has three jobs:

- require the namespaces that register the app's behaviour;
- install the reactive adapter for the process;
- render a view tree inside a frame.

On first page load that creates and seeds the app. On hot reload it re-renders
the changed views without losing app-db.

A [frame](../glossary.md#frame) is one isolated running instance of your app,
with its own [app-db](../glossary.md#app-db), event queue, and subscription
cache. `rf/init!` does not create one. It only installs the
[adapter](../glossary.md#adapter) for your React substrate, such as Reagent or
UIx. The frame is created when the [frame-root](../glossary.md#frame-root) you
render first mounts.

!!! note "Fresco apps boot the same way"

    [Fresco](../fresco/00-installation.md#fresco-needs-a-substrate-adapter)
    still takes its reactive container from an adapter, so a Fresco app opens
    with the same `(rf/init! …)` line. Its tree carries `[h/frame-root {:id …}]`
    where a Reagent tree carries `[rf/frame-root {:id …}]`, with the same
    options and behaviour, and it mounts through the same
    `client-root` / `render!` / `unmount!` trio.

## The small shape

For an app with no browser listeners, you don't need a separate `boot!`
function. Keep the process setup inline in `run`, and put the DOM work in `mount!`.

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; These namespaces are required for their registrations.
            [counter.events]
            [counter.subs]
            [counter.views :refer [counter]]))

;; Namespace load does no DOM work. The handle is inert until the first
;; render! through it creates the React root.
(defonce app-root (reagent-adapter/client-root))

(def app-frame :app)

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (reagent-adapter/render! app-root
      [rf/frame-root {:id             app-frame
                      :initial-events [[:initialise 0]]}
       [counter]]
      el)))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (mount!))
```

Wire `run` as the build's `:init-fn` — for example
`:init-fn counter.core/run` in `shadow-cljs.edn`.

`rf/init!` is process setup and `mount!` is browser setup. Keeping the DOM work
inside `mount!` lets the namespace load in tests or Node hosts where
`js/document` is not present.

The `ns` form is also part of boot. `counter.events` and `counter.subs` look
unused, but requiring them runs their `reg-event` and `reg-sub` forms. Loading
the code is the registration; there is no manifest and no wiring step. A real
app's entry namespace requires every namespace whose registrations must exist
before the app runs: events, effects, coeffects, subscriptions, views, routes,
resources, machines, and schemas.

## What the root does

This form:

```clojure
[rf/frame-root {:id             app-frame
                :initial-events [[:initialise 0]]}
 [counter]]
```

is `frame-root`, the component that *ensures* a frame exists. On the first
mount it:

- creates the frame named by `:id`;
- applies the frame config;
- runs the `:initial-events` once, in order, to seed app-db (they are ordinary
  events with ordinary handlers);
- scopes descendant views, subscriptions, and dispatches to that frame.

On a later remount under the same `:id`, such as a hot reload, it reuses the
live frame. It does not replay `:initial-events`, and it does not destroy the
frame on unmount. That is why app-db survives a reload.

It also means an edit to the setup event does not run on reload. To run the new
setup, reload the page, or [reset the frame](../frames.md#ending-and-resetting-a-frame)
(`rf/destroy-frame!` followed by `rf/make-frame` with the same config).

## Hot reload

Two pieces make hot reload work:

```clojure
(defonce app-root (reagent-adapter/client-root))

(defn ^:dev/after-load mount! []
  ...)
```

`defonce` keeps the same handle across reloads, and the handle keeps the same
React root: the first `render!` through it creates the root, and every later one
updates that root. You never hold a raw React root or write a create-or-render
branch.

`^:dev/after-load` tells shadow-cljs to call `mount!` after a successful
reload. That re-renders the edited views into the same root and the same frame.
It does not re-run `run`.

The adapter tracks that root like every other root it creates, so
`rf/destroy-adapter!` releases it too. `reagent-adapter/unmount!` releases it
explicitly; both are safe to repeat.

## Host listeners

Some apps also install browser listeners, such as `storage`, `online`, or
`visibilitychange`. They are browser wiring, separate from frame creation.

The browser removes a listener by function identity, and after a hot reload
your namespace holds *new* function objects. Removing "the listener" by name
removes nothing, and each reload stacks another copy. Keep the installed
listener in a `defonce` cell and remove that stored value before adding the new
one. When listener code can change during development, reinstall it from the
hot-reload hook as well as from `run`.

```clojure
(defonce storage-listener (atom nil))

(defn- on-storage [event]
  ;; A browser callback has no frame in scope, so name the frame explicitly.
  (rf/dispatch [:todo/storage-changed (.-key event)]
               {:frame app-frame}))

(defn- install-host-listeners! []
  (when-let [previous @storage-listener]
    (.removeEventListener js/window "storage" previous))
  (.addEventListener js/window "storage" on-storage)
  (reset! storage-listener on-storage))

(defn mount! []
  ...)

(defn ^:dev/after-load reload! []
  (install-host-listeners!)
  (mount!))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (install-host-listeners!)
  (mount!))
```

`install-host-listeners!` earns its own name because it runs in two
situations: first page load and hot reload. A separate `boot!` wrapper is
optional; use one only if it makes your app's entry point clearer.

In this shape, put `^:dev/after-load` on `reload!`, not on `mount!`, so a
reload reinstalls listeners and renders once.

URL routing needs none of this: a frame declared `:url-bound? true` installs
and manages its own `hashchange` / `popstate` listener (see the TodoMVC example
below).

## Two frame components

Roots ensure a frame exists; providers scope a frame that already exists. The
full split and its edge cases are in
[Frames](../frames.md#frame-provider-and-frame-root).

| Need | Use |
|---|---|
| Subtree brings its own frame into being | `frame-root {:id … :initial-events …}` |
| Frame already exists (boot, test, SSR, tooling) | `make-frame` then `frame-provider {:frame …}` |

```clojure
;; App root — ensure + scope in one form
[rf/frame-root {:id app-frame :initial-events [[:initialise 0]]}
 [counter]]

;; Pre-created frame — scope only
(rf/make-frame {:id :todos/work :initial-events [[:todo/initialise]]})
[rf/frame-provider {:frame :todos/work}
 [todo-app]]
```

## The boot lifecycle

The whole recipe, one moment per row:

| Moment | What should happen |
| --- | --- |
| Namespace load | The entry/boot namespace requires the registration namespaces, so their `reg-*` forms run. |
| First page load | `run` installs the adapter, installs any host listeners, and mounts the view. |
| First mount of `frame-root {:id ...}` | Ensure creates the frame (if absent) and runs `:initial-events`. |
| Hot reload | The reload hook re-renders into the same root and reuses the same frame. |
| Host listener edit | Reinstall the stored listener so the browser calls the current code. |
| Fresh setup wanted | Reload the page, or destroy and re-create the frame; remounting does not replay setup. |

## No DOM work at namespace load

Top-level `reg-*` forms and allocating the `client-root` handle are fine at
namespace load; `client-root` touches nothing until the first `render!`. Keep
`render!` and listener installation out of top-level code, because the
namespace may be loaded by a test host, a Story tool, or another namespace that
wants the registrations without mounting the app.

```clojure
;; Don't do this at namespace load.
(reagent-adapter/render! app-root [counter] (js/document.getElementById "app"))
```

## Troubleshooting

Each of these wiring mistakes [fails loud](../glossary.md#fail-loud-not-silent)
with a named [error](../errors.md) rather than a blank page.

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/no-adapter-installed` when the `frame-root` creates its frame | `mount!` ran before `(rf/init! …)` | Call `rf/init!` first, as `run` does above |
| `:rf.error/no-frame-context` from a `dispatch` or `subscribe` | Called from a top-level form, or from a callback, with no frame in scope | Dispatch from views or handlers inside the `frame-root`, or pass `{:frame …}` |
| `:rf.error/frame-provider-frame-absent` | A `frame-provider {:frame …}` names a frame that was never created | Create it with `make-frame` first, or use `frame-root {:id …}` ([the two components](#two-frame-components)) |
| `:rf.error/no-such-handler` / `:rf.error/no-such-sub` naming an id you did register | The namespace holding that `reg-event` / `reg-sub` is never required, so it never loaded | Add it to the entry namespace's `:require` list |

## Worked examples

- [`examples/core/counter/core.cljs`](../../../examples/core/counter/core.cljs)
  shows the smallest app shape.
- [`examples/core/todomvc/core.cljs`](../../../examples/core/todomvc/core.cljs)
  adds URL routing with `:url-bound? true` and a hash `:url-strategy`; the
  frame installs the `hashchange` listener, so the example writes none.
- [`examples/substrates/uix/counter/core.cljs`](../../../examples/substrates/uix/counter/core.cljs)
  is the same lifecycle on UIx: the UIx adapter has the same `client-root` /
  `render!` / `unmount!` trio, taking a React element instead of hiccup.

??? info "From re-frame v1"

    The old `mount-root` pattern rendered again after a hot reload while a
    global app-db survived as a top-level value. In re-frame2 the state
    container is an explicit frame: `frame-root` ensures it, and
    `:initial-events` seed it through the normal event pipeline. The full
    frame lifecycle is in [Frames](../frames.md).
