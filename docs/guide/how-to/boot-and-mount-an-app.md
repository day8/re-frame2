# Boot and mount an app

An app's entry/boot namespace has three jobs:

- require the namespaces that register the app's behaviour;
- install the reactive adapter for the process;
- render a view tree inside a frame.

A [frame](../glossary.md#frame) is one isolated running instance of your app: its own
[app-db](../glossary.md#app-db), event queue, runtime state, and subscription cache.
`rf/init!` does not create a frame. It only installs the
[adapter](../glossary.md#adapter) for your React substrate, such as Reagent, UIx,
or Helix. The frame is created later by the rendered
[frame-provider](../glossary.md#frame-provider).

The goal is simple: first page load should create and seed the app; hot reload
should re-render changed views without losing app-db.

## The Small Shape

For an app with no browser listeners, there is no need for a separate `boot!`
function. Keep the process setup inline in `run`, and put DOM work in `mount!`.

```clojure
(ns counter.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; These namespaces are required for their registrations.
            [counter.events]
            [counter.subs]
            [counter.views :refer [counter-app]]))

;; Namespace load does no DOM work. The root is created lazily by mount!.
(defonce react-root (atom nil))

(def app-frame :rf/default)

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (when-not @react-root
      (reset! react-root (rdc/create-root el)))
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:counter/initialise]]}
                 [counter-app]])))

(defn run []
  (rf/init! reagent-adapter/adapter)
  (mount!))
```

Wire `run` as the build's `:init-fn`, for example
`:init-fn counter.core/run` in `shadow-cljs.edn`.

`rf/init!` is process setup. `mount!` is browser setup. Keeping the DOM touch
inside `mount!` lets the namespace load in tests or Node hosts where
`js/document` is not present.

The `ns` form is also part of boot. `counter.events` and `counter.subs` may look
unused in this namespace, but requiring them loads their `reg-event` and
`reg-sub` forms. A real app's entry/boot namespace usually requires every
namespace whose top-level registrations must exist before the app runs: events,
effects, coeffects, subscriptions, views, routes, resources, machines, and
schemas.

## What the Provider Does

This form:

```clojure
[rf/frame-provider {:id             app-frame
                    :initial-events [[:counter/initialise]]}
 [counter-app]]
```

uses the **ensure** shape of `frame-provider`.

On the first mount it:

- creates the frame named by `:id`;
- applies the frame config;
- runs the `:initial-events` once, in order, to seed app-db;
- scopes descendant views, subscriptions, and dispatches to that frame.

On a later remount under the same `:id`, such as a hot reload, it reuses the
live frame. It does not replay `:initial-events`, and it does not destroy the
frame on unmount. That is why app-db survives reload.

If you edit the setup event itself and want the new setup to run, reset the
frame or reload the page. Hot reload preserves state by design.

## Hot Reload

Two pieces make hot reload work:

```clojure
(defonce react-root (atom nil))

(defn ^:dev/after-load mount! []
  ...)
```

`defonce` keeps the same React root across reloads. React should not get a
second `create-root` call for a live DOM node.

`^:dev/after-load` tells shadow-cljs to call `mount!` after a successful
reload. That re-renders the edited views into the same root and the same frame.
It does not re-run `run`.

## Host Listeners

Some apps also install browser listeners: `hashchange`, `popstate`, `storage`,
or similar. Those listeners are process/browser wiring, not frame creation.

When listener code can change during development, reinstall the listener from a
hot-reload hook as well as from `run`. The browser removes listeners by exact
function object identity, so keep the installed listener in a `defonce` cell and
remove that stored value before adding the new one.

```clojure
(defonce hash-listener (atom nil))

(defn- on-hashchange [_event]
  (rf/dispatch [:rf.route/handle-url-change (current-path)]
               {:frame app-frame}))

(defn- install-host-listeners! []
  (when-let [previous @hash-listener]
    (.removeEventListener js/window "hashchange" previous))
  (.addEventListener js/window "hashchange" on-hashchange)
  (reset! hash-listener on-hashchange))

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

The separate `install-host-listeners!` function earns its name because it runs in
two situations: first page load and hot reload. A separate `boot!` wrapper is
optional; use one only if it makes your app's entry point clearer.

In this shape, put `^:dev/after-load` on `reload!`, not on `mount!`, so a reload
reinstalls listeners and renders once.

For history routing, prefer the routing helper where it fits. It already owns the
same hot-reload-safe listener pattern.

## Two Frame Shapes

`frame-provider` has two useful shapes.

Use `{:id ...}` when the rendered subtree should ensure its own frame exists:

```clojure
[rf/frame-provider {:id             :counter/widget
                    :initial-events [[:counter/initialise]]}
 [counter-app]]
```

This creates the frame if absent, reuses it if present, and scopes descendants to
it.

Use `{:frame ...}` when the frame was already created somewhere else:

```clojure
(rf/reg-frame :checkout {:initial-events [[:checkout/initialise]]})

[rf/frame-provider {:frame :checkout}
 [checkout-app]]
```

This shape only scopes an existing frame into the React subtree. It creates
nothing and destroys nothing. Use it when boot, tests, SSR/request setup, or a
tooling harness needs to create the frame before rendering.

## Lifecycle Summary

| Moment | What should happen |
| --- | --- |
| Namespace load | The entry/boot namespace requires the registration namespaces, so their `reg-*` forms run. |
| First page load | `run` installs the adapter, installs any host listeners, and mounts the view. |
| First mount of `{:id ...}` | The provider creates the frame and runs `:initial-events`. |
| Hot reload | The reload hook re-renders into the same root and reuses the same frame. |
| Host listener edit | Reinstall the stored listener so the browser calls the current code. |
| Fresh setup wanted | Reset the frame or reload the page; remounting does not replay setup. |

## No DOM Work at Namespace Load

Keep `create-root`, `render`, and browser listener installation out of top-level
namespace code. Requiring registration namespaces is fine; browser work is not.

Top-level registration is fine:

```clojure
(rf/reg-event :counter/initialise ...)
(rf/reg-sub :counter/value ...)
```

Top-level DOM work is not:

```clojure
;; Avoid this at namespace load.
(defonce root (rdc/create-root (js/document.getElementById "app")))
```

The namespace may be loaded by a test host, a Story tool, or another namespace
that wants the registrations without mounting the app. Lazy DOM work keeps that
safe.

## Worked Examples

- [`examples/reagent/counter/core.cljs`](../../../examples/reagent/counter/core.cljs)
  shows the smallest app shape.
- [`examples/reagent/todomvc/core.cljs`](../../../examples/reagent/todomvc/core.cljs)
  adds URL routing, a `hashchange` listener, and `:url-bound? true`.

The UIx and Helix examples use the same lifecycle. Only the adapter, root
creation, and render calls change.

> **From re-frame v1.** The old `mount-root` pattern rendered again after a hot
> reload while global app-db survived as a top-level value. In re-frame2, the
> state container is explicit: a frame. The provider ensures or scopes that
> frame, and `:initial-events` seed it through the normal event cascade.

The full frame lifecycle is covered in [Frames](../concepts/frames.md) and
[`spec/002-Frames.md`](../../../spec/002-Frames.md).
