# Boot and mount an app

An app's entry/boot namespace has three jobs. Only three, and always the same three:

- require the namespaces that register the app's behaviour;
- install the reactive adapter for the process;
- render a view tree inside a frame.

A [frame](../glossary.md#frame) is one isolated running instance of your app: its own
[app-db](../glossary.md#app-db), event queue, runtime state, and subscription cache.
One division of labour catches nearly everyone, so hear it before the code:
`rf/init!` does not create a frame. It only installs the
[adapter](../glossary.md#adapter) for your React substrate, such as Reagent or
UIx. The frame is created later by the rendered
[frame-root](../glossary.md#frame-root).

The whole recipe serves two moments. First page load should create and seed the
app. Hot reload should re-render changed views without losing app-db.

!!! note "Hicasso apps boot the same way"

    The three jobs are the substrate's, not Reagent's, and
    [Hicasso](../hicasso/00-installation.md#hicasso-needs-a-substrate-adapter)
    does not exempt itself from the second: it interprets Hiccup and owns the
    render boundary, but the reactive container still comes from an adapter, so
    a Hicasso app opens with the same `(rf/init! …)` line. Only the third job is
    spelled differently. `h/mount!` is a function that *ensures* its frame
    before it renders, rather than a `frame-root` element that ensures one
    during the first commit — so a Hicasso tree has no frame component in it,
    and `:initial-events` are already drained when the first paint happens.

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
            [counter.views :refer [counter-app]]))

;; Namespace load does no DOM work. The handle is inert until the first
;; render! through it creates the React root.
(defonce app-root (reagent-adapter/client-root))

(def app-frame :rf/default)

(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (reagent-adapter/render! app-root
      [rf/frame-root {:id             app-frame
                      :initial-events [[:counter/initialise]]}
       [counter-app]]
      el)))

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

## What the root does

This form:

```clojure
[rf/frame-root {:id             app-frame
                :initial-events [[:counter/initialise]]}
 [counter-app]]
```

is `frame-root`, the **ensure** component — *roots ensure; providers scope*.

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
(defonce app-root (reagent-adapter/client-root))

(defn ^:dev/after-load mount! []
  ...)
```

`defonce` keeps the same handle across reloads, and the handle keeps the same
React root: the first `render!` through it creates the root, every later one
updates that root. React should not get a second `create-root` call for a live
DOM node, and with the adapter owning the root it never does — you hold no raw
root, and there is no create-or-render branch to get right.

`^:dev/after-load` tells shadow-cljs to call `mount!` after a successful
reload. That re-renders the edited views into the same root and the same frame.
It does not re-run `run`.

The adapter tracks that root like every other root it creates, so
`rf/destroy-adapter!` releases it too. `reagent-adapter/unmount!` releases it
explicitly; both are safe to repeat.

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

## Two frame components (recipe only)

**Roots ensure; providers scope.** Full split and edge cases:
[Frames](../frames.md#frame-provider-and-frame-root).

| Need | Use |
|---|---|
| Subtree brings its own frame into being | `frame-root {:id … :initial-events …}` |
| Frame already exists (boot, test, SSR, tooling) | `make-frame` then `frame-provider {:frame …}` |

```clojure
;; App root — ensure + scope in one form
[rf/frame-root {:id app-frame :initial-events [[:counter/initialise]]}
 [counter-app]]

;; Pre-created frame — scope only
(rf/make-frame {:id :checkout :initial-events [[:checkout/initialise]]})
[rf/frame-provider {:frame :checkout}
 [checkout-app]]
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
| Fresh setup wanted | Reset the frame or reload the page; remounting does not replay setup. |

## No DOM work at namespace load

Keep `render!` and browser listener installation out of top-level namespace
code. Requiring registration namespaces is fine; browser work is not.
Allocating the handle is fine too — `client-root` touches nothing until the
first `render!`.

Top-level registration is fine:

```clojure
(rf/reg-event :counter/initialise ...)
(rf/reg-sub :counter/value ...)
```

Top-level DOM work is not:

```clojure
;; Avoid this at namespace load.
(reagent-adapter/render! app-root [counter-app] (js/document.getElementById "app"))
```

Why so strict? The namespace may be loaded by a test host, a Story tool, or
another namespace that wants the registrations without mounting the app. Lazy
DOM work keeps all of those safe.

## Troubleshooting

Boot is where the "did you wire it up?" mistakes surface, and each one [fails loud](../glossary.md#fail-loud-not-silent) with a named [error](../errors.md) rather than a blank page. Three, in the order you're likely to meet them.

**You touched the substrate before `init!`.** `rf/init!` installs the
[adapter](../glossary.md#adapter); until it runs there is nothing to render
through. A `mount!` that beats `(rf/init! …)` fails at the `frame-root`'s
frame creation with `:rf.error/no-adapter-installed`, naming the call; a
`dispatch` or `subscribe`
fired from a bare top-level form fails on its missing frame scope first
(`:rf.error/no-frame-context`). Either way the cure is the same: boot before
anything runs — in the shapes above, `run` installs the adapter on its first
line.

**You pointed `{:frame …}` at a frame that doesn't exist.** The scope shape
only *scopes* a frame someone else created; it creates nothing. Point
`[rf/frame-provider {:frame :checkout} …]` at a frame that was never
created (nor ensured by a `frame-root`) and it throws
`:rf.error/frame-provider-frame-absent`. When the subtree should bring its own
frame into being, use the **ensure** component `frame-root {:id …}` instead —
that's the whole difference between [the two components](#two-frame-components-recipe-only).

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

The UIx examples use the same lifecycle. Only the adapter and the render call
change — UIx apps hold a `uix.dom` root themselves.

??? info "From re-frame v1"

    The old `mount-root` pattern rendered again after a hot reload while a
    global app-db survived as a top-level value — the state container was
    ambient. In re-frame2 it is explicit: a frame. The provider ensures or
    scopes that frame, and `:initial-events` seed it through the normal event
    pipeline.

That's the recipe. The full frame lifecycle is covered in
[Frames](../frames.md).
