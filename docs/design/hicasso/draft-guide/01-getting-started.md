# Getting started

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-027), witnessed by the bench
> arm's tests under `implementation/freehand/test/re_frame/bench/hicasso/` — but no
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze.

Booting a re-frame2 app today takes about thirty lines: create a React root, install
an adapter, render a `frame-root` inside it, remember the root across hot reloads so
React doesn't get a second `create-root` for a live node. Every app writes the same
thirty lines and every app gets one of them subtly wrong at least once.

Hicasso collapses that into one call. HD-021 pins the semantics: **one root
operation associates a DOM node, a frame, and initial events, and returns an
idempotent teardown.** The names wait for the API freeze; the behaviour does not.

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

That view reads with `sub` — an ordinary function call, and the ruled read surface
(the fork the earlier draft hedged on was decided on 2026-07-31).
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

That is the whole boot. `h/root!` **[unfrozen]** takes the DOM node, a config map,
and one view, and hands back a teardown function.

`:initial-events` behaves exactly as it does under
[`frame-root`](../../../core/how-to/boot-and-mount-an-app.md): ordinary events, run
once in order, seeding app-db before first paint. Even initial values arrive by
event — that rule does not change because the view layer did.

## The teardown

`h/root!` returns a function. Call it and the root unmounts, the subscriptions
release, and the DOM node is yours again:

```clojure
(stop!)   ;; idempotent — calling it twice is not an error
```

Idempotence is in the ruling, not a courtesy. Teardown paths get called from
`finally` blocks, test fixtures, and reload hooks that don't coordinate, so a second
call has to be a no-op rather than a crash.

The standing assertion behind it is worth knowing even if you never write it
yourself: **zero leaked subscription ref-counts after teardown**. If a root goes
away and a subscription cache entry survives, that is a bug in Hicasso, not a
lifecycle subtlety you were supposed to manage.

## Hot reload

HD-021 gives hot reload a floor rather than a mechanism. After a body swap:

- the root, the frame, and app-db survive;
- the changed view body is the one that runs;
- no subscriptions leak.

Preserving hook-local state across a swap is explicitly optional, so don't build on
it.

Note what the floor implies for the code above. `defonce` keeps the root alive
across reloads, and the guarantee says the *changed body* is used — so on the
designed behaviour a `^:dev/after-load` remount hook is not part of the story.
Whether a swap needs an explicit re-render call, or `defview` re-reads its var and
the next commit picks the change up for free, is a mechanism question the record
leaves open. See **Not settled yet**.

If you edit `:todo/initialise` itself and want the new seed to run, reset the frame
or reload the page. Hot reload preserves state by design, and it will happily
preserve it right past your edited setup event.

## More than one frame

`:frame` names the frame the root ensures, the same way `:id` does for `frame-root`.
Two roots with two different frame ids give you two isolated apps on one page — own
app-db, own queue, own subscription cache. This is how the multi-frame witnesses in
[validation.md](../validation.md) are built, and views inside one root never reach
into another frame's state.

## Troubleshooting

No boot-path error ids are minted yet, so this table names mechanisms rather than
`:rf.error/*` ids.

| Symptom | What went wrong | Fix |
|---|---|---|
| `sub` throws outside a view | `sub` is render-scoped; there is no `@`-anywhere in Hicasso | Use `rf/subscribe-once` in handler and utility code |
| Async callback dispatches and nothing happens | A bare `dispatch` from a timeout has no frame | Callbacks generated from intent vectors carry their boundary's frame; hand-written async needs the frame explicitly |
| First paint shows empty state, then flickers | Seeding raced the first render | Seed through `:initial-events`, not a post-mount dispatch |
| A view renders twice on mount in dev | React StrictMode double-invokes bodies | Nothing to fix — bodies are pure and re-runnable by contract |
| Second `h/root!` on the same node | The node already has a live root | Keep the root in a `defonce`; call the teardown before re-rooting |

## When not to use this

**Server-side rendering is out of v0**, explicitly (HD-020). `defview` bodies stay
`.cljc`-compatible by construction, so nothing is being painted into a corner, but no
JVM or SSR render path ships in v0 and `defhost`'s SSR-placeholder default is inert
policy for a later phase. If you need SSR now, use a
[Reagent or UIx adapter](../../../core/views.md), which are first-class and
supported.

The same applies to the whole programme, not just this page: adapters continuing to
be the answer is a *successful* outcome for Hicasso, not a failure mode.

## Not settled yet

| Question | Status |
|---|---|
| The root operation's name, its config keys, and the teardown's name | Semantics pinned by HD-021; names explicitly unfrozen until the API freeze |
| Does `rf/init!` and adapter installation still apply? | **Not addressed.** Hicasso is a native view layer rather than an adapter, but the record never says whether the root operation subsumes process setup or whether an `init!`-equivalent survives |
| May a boundary child omit its props map — `[views/todo-app]` rather than `[views/todo-app {}]`? | **Not addressed.** HD-016 says bodies take a single props map; whether the map is optional at the call site is unstated. This page writes `{}` to stay inside what is ruled |
| Does a hot-reload body swap need an explicit re-render call? | **Not addressed.** HD-021 pins the guarantees, not the mechanism |
| Where the frame config other than `:initial-events` goes | **Not addressed.** `frame-root` takes a config map today; whether the root operation passes one through is unstated |
