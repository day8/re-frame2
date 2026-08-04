# Getting started

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Behaviour matches the experimental arm under `implementation/freehand/test/re_frame/bench/hicasso/`.

Booting a re-frame2 app today takes about thirty lines: create a React root,
install an adapter, render a `frame-root` inside it, remember the root across
hot reloads so React doesn't get a second `create-root` for a live node. Every
app writes the same ceremony and gets one of the lines subtly wrong at least
once.

Hicasso collapses that into one call.

> **One root associates a DOM node, a frame, and initial events, and returns
> an idempotent teardown.**

## Your first app

Three namespaces, in the shape any re-frame2 app already uses.

```clojure
(ns todo.events
  (:require [re-frame.core :as rf]))

(rf/reg-event :todo/initialise
  (fn [_cofx _event]
    {:db {:todos [{:id 1 :title "Read the draft guide" :done? false}]}}))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos
                 (fn [todos]
                   (mapv #(cond-> % (= id (:id %)) (update :done? not))
                         todos)))}))
```

```clojure
(ns todo.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :todo/all
  (fn [db _query] (:todos db)))
```

```clojure
(ns todo.views
  (:require [re-frame.hicasso :as h :refer [defview sub]]))

(defview todo-app [_]
  [:ul
   (for [{:keys [id title done?]} (sub [:todo/all])]
     [:li {:key id}
      [:label
       [:input {:type :checkbox :checked done? :on-change [:todo/toggle id]}]
       title]])])
```

That view reads with `sub` — an ordinary function call at the point of use.
[Views and reads](02-views-and-reads.md) covers it.

And the boot namespace, which is the actual subject of this page:

```clojure
(ns todo.main
  (:require [re-frame.hicasso :as h]
            ;; Required for their registrations, not for anything named here.
            [todo.events]
            [todo.subs]
            [todo.views :as views]))

(defonce stop!                                    ;; h/root! is [unfrozen]
  (h/root! (js/document.getElementById "app")
           {:frame          :rf/default
            :initial-events [[:todo/initialise]]}
           [views/todo-app {}]))
```

That is the whole boot. `h/root!` **[unfrozen]** takes the DOM node, a config
map, and one view, and hands back a teardown function. Exact names and arities
are not frozen; treat the shape — node, config, view → teardown — as the
contract this guide teaches.

The `{}` on `[views/todo-app {}]` is optional — `[views/todo-app]` renders the
same thing, and the body receives an empty props map either way. Write whichever
reads better at the call site.

`:initial-events` behaves exactly as it does under
[`frame-root`](../../../core/how-to/boot-and-mount-an-app.md): ordinary events,
run once in order, seeding app-db before first paint. Even initial values arrive
by event — that rule does not change because the view layer did.

## The teardown

`h/root!` returns a function. Call it and the root unmounts, the subscriptions
release, and the DOM node is yours again:

```clojure
(stop!)   ;; idempotent — calling it twice is not an error
```

Idempotence is part of the contract, not a courtesy. Teardown paths get called
from `finally` blocks, test fixtures, and reload hooks that don't coordinate, so
a second call has to be a no-op rather than a crash.

After teardown, **subscription ref-counts drop to zero**. A surviving cache
entry after a root goes away is a Hicasso bug, not something you manage.

## Hot reload

After a body swap:

- the root, the frame, and app-db survive;
- the changed view body is the one that runs;
- no subscriptions leak.

Preserving hook-local state across a swap is optional, so don't build on it.

`defonce` keeps the root alive across reloads, and the guarantee says the
*changed body* is used — so a `^:dev/after-load` remount hook is not required.
`defview` expands to a `def` of a freshly minted head: re-evaluating the
namespace produces a *new* head, which is a new React element type, so React
replaces that subtree rather than reconciling past the default bail-out. What
is still open is what *triggers* the next render; see **Not settled yet**.

If you edit `:todo/initialise` itself and want the new seed to run, reset the
frame or reload the page. Hot reload preserves state by design — including past
your edited setup event.

## More than one frame

`:frame` names the frame the root ensures, the same way `:id` does for
`frame-root`. Two roots with two different frame ids give you two isolated apps
on one page — own app-db, own queue, own subscription cache. Views inside one
root never reach into another frame's state.

## Troubleshooting

No boot-path error ids are minted yet, so this table names mechanisms rather
than `:rf.error/*` ids.

| Symptom | What went wrong | Fix |
|---|---|---|
| `sub` throws outside a view | `sub` is render-scoped; there is no `@`-anywhere in Hicasso | Use `rf/subscribe-once` in handler and utility code |
| Async callback dispatches and nothing happens | A bare `dispatch` from a timeout has no frame | Callbacks from intent vectors carry their boundary's frame; hand-written async needs the frame explicitly |
| First paint shows empty state, then flickers | Seeding raced the first render | Seed through `:initial-events`, not a post-mount dispatch |
| A view renders twice on mount in dev | React StrictMode double-invokes bodies | Nothing to fix — bodies are pure and re-runnable by contract |
| Second `h/root!` on the same node | The node already has a live root | Keep the root in a `defonce`; call the teardown before re-rooting |

## When not to use this

**SSR is in scope for Hicasso**, via the framework's Spec 011 path rather than a
private one. [Server-side rendering](10-server-side-rendering.md) covers the
Hicasso side. Until there is a product `implementation/hicasso/` package, if you
need a published production SSR path today, a
[Reagent or UIx adapter](../../../core/views.md) remains first-class and
supported.

## Not settled yet

| Question | Status |
|---|---|
| Root operation name, config keys, teardown name | Behaviour fixed; names **[unfrozen]** |
| Does `rf/init!` and adapter installation still apply? | **Open.** Hicasso is a native view layer, not an adapter — unclear whether the root subsumes process setup |
| What triggers re-render after a hot-reload body swap | **Open.** Guarantees and `defview` expansion settle how the new body is picked up; not who asks for the render |
| Where frame config other than `:initial-events` goes | **Open.** `frame-root` takes a config map today; whether the root passes one through is unstated |
