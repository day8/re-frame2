# Boot and mount an app

An app's entry/boot namespace has three jobs. Only three, and always the same three:

- require the namespaces that register the app's behaviour;
- install the reactive adapter for the process;
- render a view tree inside a frame.

A [frame](../glossary.md#frame) is one isolated running instance of your app: its own
[app-db](../glossary.md#app-db), event queue, runtime state, and subscription cache.
One division of labour catches nearly everyone, so hear it before the code:
`rf/init!` does not create a frame. It only installs the
[adapter](../glossary.md#adapter) for your React substrate, such as Reagent, UIx,
or Helix. The frame is created later by the rendered
[frame-provider](../glossary.md#frame-provider).

The whole recipe serves two moments. First page load should create and seed the
app. Hot reload should re-render changed views without losing app-db.

## The small shape

For an app with no browser listeners, you don't need a separate `boot!`
function. Keep the process setup inline in `run`, and put the DOM work in `mount!`.

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

Wire `run` as the build's `:init-fn` — for example
`:init-fn counter.core/run` in `shadow-cljs.edn`.

`rf/init!` is process setup. `mount!` is browser setup. The split is not
ceremony: keeping the DOM touch inside `mount!` lets the namespace load in tests
or Node hosts where `js/document` is not present.

The `ns` form is also part of boot. `counter.events` and `counter.subs` look
unused in this namespace — nothing in the file names them again — but requiring
them loads their `reg-event` and `reg-sub` forms. Registration happens as a
direct result of loading the code; there is no manifest and no wiring step. A
real app's entry/boot namespace usually requires every namespace whose
top-level registrations must exist before the app runs: events, effects,
coeffects, subscriptions, views, routes, resources, machines, and schemas.

## What the provider does

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

Notice how the seeding happens. `:initial-events` are ordinary events, handled
by ordinary handlers — even the initial values arrive by event. Those are the
rules.

On a later remount under the same `:id` — a hot reload, say — it reuses the
live frame. It does not replay `:initial-events`, and it does not destroy the
frame on unmount. That sentence is the whole hot-reload story: it is why app-db
survives a reload.

Which cuts both ways. If you edit the setup event itself and want the new setup
to run, reset the frame or reload the page. Hot reload preserves state by
design, and it will preserve it right past your edited setup event.

## Hot reload

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

## Host listeners

Some apps also install browser listeners: `hashchange`, `popstate`, `storage`,
or similar. Those listeners are process/browser wiring, not frame creation.

Here's the trap. The browser removes listeners by exact function object
identity, and after a hot reload your namespace holds *new* function objects —
so removing "the listener" by name removes nothing, and each reload stacks
another copy. The cure: keep the installed listener in a `defonce` cell, and
remove that stored value before adding the new one. When listener code can
change during development, reinstall the listener from a hot-reload hook as
well as from `run`.

```clojure
(defonce hash-listener (atom nil))

(defn- current-path []
  (subs (.-hash js/location) 1))   ;; your URL-reading helper — hash-based here

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

`install-host-listeners!` earns its own name because it runs in two
situations: first page load and hot reload. A separate `boot!` wrapper is
optional; use one only if it makes your app's entry point clearer.

In this shape, put `^:dev/after-load` on `reload!`, not on `mount!`, so a
reload reinstalls listeners and renders once.

For history routing, prefer the routing helper where it fits. It already owns
this same hot-reload-safe listener pattern.

## Two frame shapes

`frame-provider` has two useful shapes, and the difference fits in one
question: does the subtree *bring* its frame, or *borrow* it?

Use `{:id ...}` when the rendered subtree should ensure its own frame exists —
it brings one:

```clojure
[rf/frame-provider {:id             :counter/widget
                    :initial-events [[:counter/initialise]]}
 [counter-app]]
```

This creates the frame if absent, reuses it if present, and scopes descendants to
it.

Use `{:frame ...}` when the frame was already created somewhere else — it
borrows:

```clojure
(rf/reg-frame :checkout {:initial-events [[:checkout/initialise]]})

[rf/frame-provider {:frame :checkout}
 [checkout-app]]
```

This shape only scopes an existing frame into the React subtree. It creates
nothing and destroys nothing. Use it when boot, tests, SSR/request setup, or a
tooling harness needs to create the frame before rendering.

## The boot lifecycle

The whole recipe, one moment per row:

| Moment | What should happen |
| --- | --- |
| Namespace load | The entry/boot namespace requires the registration namespaces, so their `reg-*` forms run. |
| First page load | `run` installs the adapter, installs any host listeners, and mounts the view. |
| First mount of `{:id ...}` | The provider creates the frame and runs `:initial-events`. |
| Hot reload | The reload hook re-renders into the same root and reuses the same frame. |
| Host listener edit | Reinstall the stored listener so the browser calls the current code. |
| Fresh setup wanted | Reset the frame or reload the page; remounting does not replay setup. |

## No DOM work at namespace load

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

Why so strict? The namespace may be loaded by a test host, a Story tool, or
another namespace that wants the registrations without mounting the app. Lazy
DOM work keeps all of those safe.

## When boot goes wrong

Boot is where the "did you wire it up?" mistakes surface, and each one [fails loud](../glossary.md#fail-loud-not-silent) with a named [error](../errors.md) rather than a blank page. Three, in the order you're likely to meet them.

**You touched the substrate before `init!`.** `rf/init!` installs the
[adapter](../glossary.md#adapter); until it runs there is nothing to render
through. A `render` or `mount!` that beats `(rf/init! …)` throws
`:rf.error/no-adapter-installed`, naming the call; a `dispatch` or `subscribe`
fired from a bare top-level form fails on its missing frame scope first
(`:rf.error/no-frame-context`). Either way the cure is the same: boot before
anything runs — in the shapes above, `run` installs the adapter on its first
line.

**You pointed `{:frame …}` at a frame that doesn't exist.** The scope shape
only *scopes* a frame someone else created; it creates nothing. Point
`[rf/frame-provider {:frame :checkout} …]` at a frame that was never
`reg-frame`'d (nor ensured by an `{:id}` provider) and it throws
`:rf.error/frame-provider-frame-absent`. When the subtree should bring its own
frame into being, use the **ensure** shape `{:id …}` instead — that's the whole
difference between [the two shapes](#two-frame-shapes).

**You forgot to require a registration namespace.** A `reg-event` or `reg-sub`
runs only when its namespace loads, so a missing `require` means the
registration never happened. Drop `counter.events` from the entry `ns` and the
first `[:counter/inc]` dispatch — or `[:counter/value]` subscribe — is a loud
`:rf.error/no-such-handler` / `:rf.error/no-such-sub` naming the missing id,
not a silent dead button. The fix is the require list in the `ns` form above.

## Worked examples

- [`examples/core/counter/core.cljs`](../../../examples/core/counter/core.cljs)
  shows the smallest app shape.
- [`examples/core/todomvc/core.cljs`](../../../examples/core/todomvc/core.cljs)
  adds URL routing, a `hashchange` listener, and `:url-bound? true`.

The UIx and Helix examples use the same lifecycle. Only the adapter, root
creation, and render calls change.

??? info "From re-frame v1"

    The old `mount-root` pattern rendered again after a hot reload while a
    global app-db survived as a top-level value — the state container was
    ambient. In re-frame2 it is explicit: a frame. The provider ensures or
    scopes that frame, and `:initial-events` seed it through the normal event
    pipeline.

That's the recipe. The full frame lifecycle is covered in
[Frames](../frames.md).
