# Getting started

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

You already know re-frame2's pipeline: events write **app-db**, subscriptions
derive values, views paint the screen. What you still choose is *how* those views
meet React.

Today that usually means a **Reagent** or **UIx adapter** — first-class,
supported, and the right production path for many apps. **Hicasso is the other
option:** re-frame2's **native** view layer. Not an adapter bolted onto someone
else's component model. A **Hiccup-first, interpreted**, data-oriented view
system designed for re-frame2 — flexibility, testability, and tight integration
with the framework you already use.

> **Hicasso — Hiccup views for re-frame2.** Same events, same app-db, same
> subscriptions. A different (and deliberate) way to write the screen.

## What kind of product it is

**Interpreted Hiccup.** You write vectors and maps. The runtime walks them into
React elements. There is no JSX transform and no second markup language. Markup
stays ordinary Clojure data — the thing you `map`, `filter`, `pprint`, and (when
the tree is pure data) assert with `=`.

**Data-oriented by default.** Handlers can be event vectors, not closures. Keys,
prevent, navigate, and controlled field values have data spellings. That is the
main product delta vs "use React, wire re-frame on the side."

**Built for re-frame2's clock.** Subscriptions are the reactive source; the
commit is the write clock. Views are pure and re-runnable. There is no second
app-facing state model — no ratom graph, no product `local` for application
state. Semantic UI state lives in app-db (with sugar where it helps).

**A peer of Reagent and UIx, not a wrapper.** Reagent is the familiar hiccup
authoring dialect with its own reactive runtime. UIx is React-with-hooks in
Clojure. Hicasso aims at what UIx users miss from Reagent (markup as data,
helpers that return data, structural tests) without becoming a better Reagent
reaction engine. The charter's line is useful: *a better UIx with hiccup
interpretation, not a better Reagent.*

## Why pick it over Reagent or UIx

**Vs Reagent**

1. **The tree stays data, including clicks.** `{:on-click [:todo/toggle id]}`
   instead of `#(rf/dispatch …)`. Tests and tools read the handler as a value; you
   do not mount-and-click to learn what a button means.
2. **Reads at the point of use, without a second local-state system.** `sub` is
   an ordinary call — legal in a `when`, a helper, a loop. Semantic UI state goes
   to app-db; no product `r/atom` for "is this open?"

**Vs UIx**

1. **You stay in re-frame's model, not React's.** UIx is hooks-first; the adapter
   is re-frame *plus* that world. Hicasso's happy path is pure bodies, data
   handlers, and app-db — hooks are a host edge, not the default architecture.
2. **Same data-tree win.** Event meaning is still data, not a function React
   holds.

**Not the pitch:** "stellar performance." The programme bar is competitive with
Reagent on the important rows — good enough to ship, not a speed-marketing
product. **For about 98% of view code, performance is not an issue**; for the
rest, there is a ladder down, not a dual mode
([Performance](11-performance.md)). Prefer Hicasso for **authoring, testing
shape, and re-frame integration**, not for winning a microbenchmark war.

## What you get (feature map)

Honest list. Each row is a real surface in this guide; none is a promise of a
finished product package.

| Capability | What it means | Where |
|---|---|---|
| **Hiccup-first views** | `defview`, boundaries vs plain helpers, attribute conversion, `:&` merge | [Views and reads](02-views-and-reads.md) |
| **Point-of-use `sub`** | One read form; framework subs (route, machines, resources) read the same way | [Views and reads](02-views-and-reads.md) |
| **Events as data** | Intent vectors, `::h/value` / `::h/checked`, prevent, key-map, `h/fn` when you need the event | [Events as data](03-events-as-data.md) |
| **Routing links as data** | `route-link` — href + click decision assertable with `=` | [Events as data](03-events-as-data.md) |
| **Controlled inputs** | Value through app-db; same-tick echo; caret preservation; careful IME on the controlled path | [Controlled inputs](04-controlled-inputs.md) |
| **Testing shape** | **Today:** assert intents / prevent / navigate / presence attrs with `=` (no browser). **Mounted** for hooks, caret, real React. **Planned:** full headless structural render (ruled, not built; no dedicated bead yet) | [Testing](08-testing.md) |
| **Interop** | `defhost` for npm React components; policies at the declaration; `[:>]` secondary and still landing | [Interop](05-interop.md) |
| **Ephemeral UI state** | No product `local`; `reg-state` sugar; placement rules | [Ephemeral state](07-ephemeral-state.md) |
| **Exit / enter animation** | `h/presence` — node can outlive app-db for a fade-out | [Ephemeral state](07-ephemeral-state.md) |
| **Lifecycle without `:on-mount`** | Page data → routes; seed → `:initial-events`; animation → presence; DOM/SDK → ref / `defhost` | [Ephemeral state](07-ephemeral-state.md) |
| **Error regions** | `h/boundary` — one broken view does not blank the page | [When a view throws](09-when-a-view-throws.md) |
| **Theming without context** | CSS tokens + app-db theme *choice*; part maps for libraries still open | [Theming](06-theming.md) |
| **Mount / unmount** | One root operation; idempotent teardown; zero leaked sub ref-counts after teardown | this page |
| **Multi-frame** | Two roots, two frames — isolated app-dbs on one page | this page |
| **SSR participation** | Same pure bodies + framework hydration; experimental doors exist; production package still open | [Server-side rendering](10-server-side-rendering.md) |
| **Xray-friendly UI** | UI state in app-db + named events shows up in app-db diffs and time-travel; not a special Hicasso Xray tab | framework Xray + this design |
| **Performance** | Good / competitive goal vs Reagent; not "fastest UI library" | programme bar, not a guide claim |
| **Going lower** | ~98% of view code needs nothing special; for the ~2%, a ladder (not a dual mode) | [Performance](11-performance.md) |

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

That view already shows two Hicasso habits: **`sub` at the point of use**, and an
**intent vector** on `:on-change` (here a toggle by id — no value to read from the
event). [Views and reads](02-views-and-reads.md) and
[Events as data](03-events-as-data.md) go deeper.

And the boot namespace:

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

> **One root associates a DOM node, a frame, and initial events, and returns
> an idempotent teardown.**

`h/root!` **[unfrozen]** takes the DOM node, a config map, and one view, and
hands back a teardown function. Exact names and arities are not frozen; treat
the shape — node, config, view → teardown — as the contract this guide teaches.

The `{}` on `[views/todo-app {}]` is optional — `[views/todo-app]` renders the
same thing, and the body receives an empty props map either way.

`:initial-events` behaves as under
[`frame-root`](../../../core/how-to/boot-and-mount-an-app.md): ordinary events,
run once in order, seeding app-db **before** first paint. Initial values arrive
by event — that rule does not change because the view layer did.

## The teardown

```clojure
(stop!)   ;; idempotent — calling it twice is not an error
```

The root unmounts, subscriptions release, the DOM node is yours again. Teardown
paths run from `finally` blocks, fixtures, and reload hooks that do not
coordinate — a second call is a no-op, not a crash.

After teardown, **subscription ref-counts drop to zero**. A surviving cache
entry is a Hicasso bug, not something you manage.

## Hot reload

After a body swap:

- the root, the frame, and app-db survive;
- the changed view body is the one that runs;
- no subscriptions leak.

Preserving hook-local state across a swap is optional — do not build on it.
`defonce` keeps the root; `defview` mints a new React element type on re-eval, so
React replaces that subtree. What *triggers* the next render is still open; see
**Not settled yet**.

If you edit `:todo/initialise` and want the new seed, reset the frame or reload
the page. Hot reload preserves state — including past your edited setup event.

## More than one frame

`:frame` names the frame the root ensures (like `:id` on `frame-root`). Two roots
with two frame ids → two isolated apps on one page: own app-db, queue, and
subscription cache. Views in one root never read another frame's state.

## Troubleshooting

No boot-path error ids are minted yet; this table names mechanisms.

| Symptom | What went wrong | Fix |
|---|---|---|
| `sub` throws outside a view | `sub` is render-scoped; there is no `@`-anywhere | Declare `:rf.cofx/requires` in an event handler; `rf/subscribe-once` with an explicit `{:frame …}` everywhere else |
| Async callback dispatches and nothing happens | Bare `dispatch` from a timeout has no frame | Own the async work through `:fx` — an fx handler receives the frame id in its context. Intent callbacks carry their [boundary](02-views-and-reads.md#boundaries-and-inlining)'s frame; only a closure crossing to foreign code needs [the explicit carry](03-events-as-data.md#callbacks-carry-their-frame) |
| First paint empty, then flickers | Seed raced first render | Seed through `:initial-events`, not a post-mount dispatch |
| View renders twice on mount in dev | StrictMode double-invokes bodies | Expected — bodies are pure and re-runnable |
| Second `h/root!` on the same node | Live root already owns the node | `defonce` the teardown; call it before re-rooting |

## When not to use Hicasso

**You are happy on Reagent or UIx.** Stay. Adapters are first-class. Hicasso is a
different view layer (`defview` / `sub`, intent vectors, no product `r/atom`), not
"the same thing with shorter boot."

**You need a published production SSR path today.** SSR is in scope
([Server-side rendering](10-server-side-rendering.md)), but there is no product
package under `implementation/hicasso/` yet. Use a
[Reagent or UIx adapter](../../../core/views.md) and Spec 011 for production now.

**You wanted React-first authoring.** Heavy hooks, render props everywhere, a
React design system as the centre of the app — UIx (or raw React) may fit better.
Hicasso meets foreign React at `defhost`; it does not try to be the best pure-React
CLJS library.

**You only wanted thinner mount glue.** If data handlers, point-of-use `sub`, and
the rest of the map above are not the draw, adapter `frame-root` is enough.

## Not settled yet

| Question | Status |
|---|---|
| Root operation name, config keys, teardown name | Behaviour fixed; names **[unfrozen]** |
| Does `rf/init!` / adapter install still apply? | **Open.** Native view layer — unclear whether the root subsumes process setup |
| What triggers re-render after a hot-reload body swap | **Open** |
| Where frame config other than `:initial-events` goes | **Open** |
| Full headless view render for tests | **Designed, unbuilt** — see [Testing](08-testing.md) |
