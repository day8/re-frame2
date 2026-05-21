# Interactive: the counter

> **What this is.** The [first-app counter](03-first-app.md) — the canonical re-frame2 teaching example — but *live*. Every code cell below is a real, editable, runnable re-frame2 program in your browser. Read the explanation, run the cell, then change it and run it again.
>
> **You'll need.** Nothing but this page. The code runs in your browser; there's no toolchain to install. The first live cell takes a moment to come alive while the re-frame2 engine loads — after that it's instant.
>
> **What you'll learn.** The same five primitives the static chapter covers — `reg-event-db`, `reg-sub`, a view, `dispatch`, `subscribe` — except here you build the counter up one live cell at a time and prove each piece works by clicking it.

This is the **template** for re-frame2's interactive tutorials. It's deliberately short: it walks one small program — the counter — end to end, with live cells the reader edits inline. Future interactive chapters follow this same shape; the authoring conventions are written up in [Writing interactive tutorials](interactive-tutorials.md).

If you've read [03 — Your first app](03-first-app.md), this covers the same ground with your hands on the wheel. If you haven't, you can start here — the explanations stand alone — and read the static chapter afterwards for the deeper *why* behind each step.

A note on running cells: click inside any cell to edit it, then press `Ctrl-Enter` (or `Cmd-Enter` on macOS) to evaluate. Each cell below is a complete program — the live counter rebuilds from scratch every time you evaluate.

## The whole counter, running

Here's the entire counter — events, a subscription, a view — in one live cell. Run it (`Ctrl-Enter`), then click the buttons. The number goes up and down, starting at `5`.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; Events — pure functions of (db, event) -> db
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

;; Subscription — a derivation from app-db
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; View — hiccup, with explicit rf/dispatch and rf/subscribe
(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])

;; Seed app-db, then hand the view back to be rendered
(rf/dispatch-sync [:counter/initialise])
[counter]
```

That's the complete program. Every line is load-bearing and every line is on the page — there's no hidden setup. The rest of this tutorial takes it apart one piece at a time, and at each step you have a live cell to experiment with.

> **One difference from the static chapter.** Chapter 03 registers its view with `reg-view`, which auto-injects `dispatch` and `subscribe` into the view body. `reg-view` is a macro, and these live cells run in a functions-only environment — so here we write a plain `defn` view and call `rf/dispatch` / `rf/subscribe` explicitly. It's the same component; `reg-view` is just sugar over this shape. See [Writing interactive tutorials](interactive-tutorials.md#one-difference-from-the-static-listings-reg-view) for the why.

## Events: describing what happened

An **event handler** is a pure function from the current state and an event to the *next* state. Nothing more. Here are the counter's three handlers, with a cell that calls one *directly* and shows what it returns:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

;; A handler is just a function: look it up and call it directly, with no
;; browser, no buttons, no app-db. Pass in a state and an event; read the
;; counter value out of the returned state.
(defn handler-demo []
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:counter/value 5}
        after   (handler before [:counter/inc])]
    [:div
     [:div "before :counter/value = " (:counter/value before)]
     [:div "after  :counter/value = " (:counter/value after)]]))

[handler-demo]
```

Run that. The cell looks up the registered `:counter/inc` handler and *calls it like the function it is* — passing in `{:counter/value 5}` and the event vector — then reads `:counter/value` out of the state before and after. Before is `5`; after is `6`. No clicking, no rendering of a real counter — a handler is a function from a value to a value, and you can exercise it that way.

Three things to notice in the handlers:

1. **The ids are namespaced.** `:counter/initialise`, not `:initialise`. Every event id starts with the feature it belongs to. The habit matters more as an app grows; it starts here.
2. **The handlers are pure.** `(fn [db _event] (update db :counter/value inc))` reads a state, returns a new state. No side effects, no DOM, no network. The runtime applies the returned value.
3. **`update` returns a new map.** `(update db :counter/value inc)` leaves `db` untouched and returns a fresh map with the incremented value. Immutability everywhere.

> **Try it.** Change the `inc` in `:counter/inc` to `(partial + 10)`, then re-evaluate. The `after` line now reads `15` — the handler adds ten. You just changed the program's behaviour by editing one function.

## Subscriptions: deriving what to show

A **subscription** is a derivation: a function from `app-db` to some value a view can read. The counter's is the simplest possible — it just reads the stored value. This cell *computes* it against a state value and shows the result:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; compute-sub runs the registered sub against any state you hand it —
;; no view, no React, no rendering. Pure data in, data out.
(defn sub-demo []
  (let [db    {:counter/value 5}
        value (rf/compute-sub [:counter/value] db)]
    [:div "for a db whose :counter/value is "
     (:counter/value db) ", the :counter/value sub computes " value]))

[sub-demo]
```

Run it. `compute-sub` runs the `:counter/value` subscription against the state `{:counter/value 5}` and the cell shows `5`. Like a handler, a subscription is just data-in, data-out — `compute-sub` runs it against any state you hand it.

Why name a derivation this trivial? Because once it's a registered, queryable thing:

- A view subscribes to it without knowing where the value lives in `app-db`. Move the value into a richer slice later and only the subscription changes.
- Tooling can list every derivation in the app — useful for inspection and for AI code generation.
- Tests compute it against any state value, as the cell above just did.

Subscriptions also chain. A doubled-counter sub built *on top of* `:counter/value`:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

(rf/reg-sub :counter/doubled
  :<- [:counter/value]
  (fn [count _query] (* 2 count)))

(defn doubled-demo []
  [:div "doubled: " (rf/compute-sub [:counter/doubled] {:counter/value 5})])

[doubled-demo]
```

The `:<-` declares an input: `:counter/doubled` depends on `:counter/value`. Run it and the cell shows `10`. The framework builds a dependency graph from these declarations — when `:counter/value` changes, `:counter/doubled` recomputes, but only while something is reading it. Cheap when nobody's looking, fast when they are.

## The view: hiccup that subscribes and dispatches

The view is the only piece with substantive shape. It's a function returning **hiccup** — a Clojure data structure that describes a DOM tree:

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em"}} @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])

(rf/dispatch-sync [:counter/initialise])
[counter]
```

This is the full counter again — run it and click the buttons. Reading the view:

- `[:div ...]` is a `<div>`; `[:button {:on-click ...} "-"]` is a `<button>` with a click handler and the text `-`.
- `@(rf/subscribe [:counter/value])` reads the current value of the subscription. The `@` unwraps the reactive value to its current contents — it's how the view re-renders when the value changes.
- `#(rf/dispatch [:counter/dec])` is a click handler that puts the `:counter/dec` event on the queue. The handler runs, `app-db` updates, the subscription notices, the view re-renders. That's the whole cycle.

The view never imperatively touches a DOM node. It declares *what the screen should look like given the current state*, and the runtime makes the DOM match.

> **Try it.** Add a third button between the existing two:
> `[:button {:on-click #(rf/dispatch [:counter/initialise])} "reset"]`
> Re-evaluate, click `+` a few times, then click `reset` — the counter snaps back to `5`. You added a feature by dispatching an event that already existed; no new handler needed.

## What just happened

When you ran the full cell and clicked `+`, here's the cascade:

1. The button's `:on-click` fired `(rf/dispatch [:counter/inc])`. The event joined the queue.
2. The runtime popped the event and ran the `:counter/inc` handler: it read `{:counter/value 5}`, returned `{:counter/value 6}`. The runtime updated `app-db`.
3. The `:counter/value` subscription noticed `app-db` changed and recomputed to `6`.
4. The view re-rendered. The `<span>` now shows `6`.

Five named steps, no surprises — the same cascade every re-frame2 event runs through, whether the app is a counter or a trading desk.

## Where this goes

You've now built and run every load-bearing primitive: pure event handlers, a subscription (including a chained one), a view that subscribes and dispatches, and the seed-before-render mount. You tested handlers and subscriptions as plain functions, and you changed the program's behaviour by editing live code.

This is the template interactive tutorial. The same *why → live cell → what to try* rhythm scales to the rest of the framework:

- **Effects that aren't state changes** — HTTP, navigation, storage. Static chapter: [04 — Events](04-events.md).
- **Subscriptions, in depth** — the graph, caching, layered derivations. Static chapter: [07 — Views](07-views.md).
- **State machines** — for flows where "what's the next state?" is the load-bearing question. Static chapter: [11 — Machines](11-machines.md).

For the full *why* behind the counter — `dispatch-sync` at the mount boundary, why views are registered, the testing story in depth — read the static companion, [03 — Your first app](03-first-app.md). To write the next interactive tutorial, start from [Writing interactive tutorials](interactive-tutorials.md).
