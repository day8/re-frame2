# 03 — Your first app

The smallest interesting program is a counter: a number, two buttons, the number changes. Let's build it.

The full source is in [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs). This chapter walks through it section by section, explaining what each piece is doing and *why it's shaped the way it is*. By the end you'll have seen every load-bearing primitive in re-frame2 at least once.

This chapter uses **Reagent** — the canonical CLJS view substrate, the one the rest of the guide uses. (re-frame2 also has UIx and Helix adapters; for adapter comparisons and the `init!` call shape across substrates, see [chapter 19 — Adapters](19-where-next.md#adapters--the-pattern-across-substrates).)

## What we're building

A page with a `+` button, a `-` button, and a number between them. Click `+`: the number goes up. Click `-`: it goes down. Default value: `5`.

Yes, this is trivial. But the shape we use to build it is the same shape we'd use for a real app — same primitives, same wiring, same testing approach. If the small case doesn't feel right, the large case won't either.

## The whole thing

Here's the file, in full, with the surrounding ceremony removed:

```clojure
(ns counter.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; Events
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:count 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))

;; Subscription
(rf/reg-sub :count
  (fn [db _query] (:count db)))

;; View
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; Mount
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; wire the Reagent substrate
  (rf/dispatch-sync [:counter/initialise])  ;; seed app-db before first render
  (rdc/render root [counter]))
```

That's everything. Copy-paste-runnable. Let's take it apart.

> **Chapter vs the runnable file.** The listing above is the teaching shape: one view, `reg-event-db` for the initialiser, the smallest thing that runs. The runnable in [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs) is intentionally a little richer — it splits the UI into two views (`counter-buttons` + `counter-app`) and widens `:counter/initialise` to `reg-event-fx` with a `:fx` walk so the perf-instrumented build can exercise the `rf:fx:*` perf bucket on init. Both shapes are equivalent for the counter's behaviour. Likewise, seeding `app-db` via `dispatch-sync [:counter/initialise]` at mount (as the chapter does) and seeding it via `make-frame {:on-create [:counter/initialise]}` (as the test below does) are equivalent ways to land the initial state before the first read — pick whichever fits the call site.

## Initialisation

```clojure
(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter]))
```

Two things have to happen before the view can render: the runtime needs an adapter installed (so subscriptions know how to track reactivity), and `app-db` needs an initial value (so the first read of `@(subscribe [:count])` returns something sensible). The `run` function does both.

`(rf/dispatch-sync [:counter/initialise])` runs the `:counter/initialise` event **synchronously**, in-line, before `run` returns. By the time `rdc/render` mounts the view on the next line, `app-db` is `{:count 5}` and the first render shows `5`.

Why `dispatch-sync` rather than plain `dispatch`? Plain `dispatch` puts the event on the queue and returns immediately — the handler runs on the next animation frame. If the view tried to render against an empty `app-db` on the way there, it would either show nothing or pop briefly before the seeded value arrived. `dispatch-sync` is the right hammer for *seed-before-render*: drain this one event right now, treat the result as part of mount.

You'll only reach for `dispatch-sync` in two places, both at app boundaries: at mount, like this, and inside tests where you want to assert on the post-handler state without yielding to the queue. Everywhere else, `dispatch` is what you want — fire-and-forget, the runtime handles ordering.

## Events

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:count 5}))
```

Three things to notice:

1. **The id is namespaced.** `:counter/initialise`, not `:initialise`. The convention is that every event's id starts with the feature it belongs to. This matters more as the app grows, but the habit starts here. An AI scaffolding new code reads existing event ids and picks a non-colliding one — namespacing makes that easy.

2. **The handler is a pure function** of `(db, event) → db`. The arguments start with `_` because we ignore them: this event doesn't read the previous state, and the event vector has no payload. The body returns the *new* state — a map with `:count 5`.

3. **There's no side-effect**. The handler doesn't write to anything, doesn't fire HTTP requests, doesn't call `console.log`. It computes a new state and hands it back. The runtime applies it.

The other two events are similarly pure:

```clojure
(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :count inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :count dec)))
```

`update` is Clojure's idiom for "transform a value at this key with this function." It returns a new map, leaving the old one untouched. Immutability everywhere.

### Why pure handlers?

Because pure handlers are *the easiest possible thing to test* — pass in a state, pass in an event, check the output. No mocks. No setup. No teardown.

```clojure
(deftest counter-inc-test
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:count 5}
        after   (handler before [:counter/inc])]
    (is (= 6 (:count after)))))
```

But there's a deeper reason. **A pure function has no time and no place.** It doesn't matter when it ran or what the global environment looked like; given the same arguments, it returns the same value. That property is what lets you reason about the function in isolation. As soon as a handler can call out to the network, mutate global state, or check the wall clock, you've lost that property — and you've gained a class of bugs that are dynamic, time-dependent, and very hard to fix.

re-frame2 keeps handlers pure. When side-effects need to happen, the handler *describes* them as data and returns them. The runtime interprets. We'll see this when we move on to a more complex example.

## Subscriptions

```clojure
(rf/reg-sub :count
  (fn [db _query] (:count db)))
```

A **subscription** is a derivation: a function from `app-db` to some value. Here, `:count` is just `(:count db)` — read the `:count` key.

But the framing matters. By naming this derivation `:count` and making it a registered, queryable thing, we get:

- A view can subscribe to it without knowing the path in `app-db`. If we move `:count` into `[:counter :count]` later, only the subscription changes.
- Tooling can list every derivation in the app: "show me everything anyone could read." Useful for AI code generation and human inspection.
- Tests can compute the subscription against any state value: `(rf/compute-sub [:count] some-db)` — no React, no rendering, just data → data.

Subscriptions can also chain. A `:count-doubled` sub that depends on `:count` would be:

```clojure
(rf/reg-sub :count-doubled
  :<- [:count]
  (fn [count _query] (* 2 count)))
```

The `:<-` is the input declaration. The framework builds a dependency graph from these declarations; when `:count` changes, `:count-doubled` recomputes — but only when something is reading it. Cheap when nobody's looking, fast when they are.

## The view

```clojure
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:count])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

This is the only piece with substantive shape. Let's look at it carefully.

`reg-view` is a defn-shape macro. It registers a render function (under a namespaced keyword auto-derived from the registration site — for this file, `:counter.core/counter`) and defs the symbol `counter` in the current namespace, bound to the wrapped fn. Hiccup elsewhere can reference it as `[counter]`. The render function returns **hiccup** — a Clojure data structure that describes a DOM tree. `[:div ...]` is a `<div>`. `[:button {:on-click ...} "-"]` is a `<button>` with a click handler and the text `"-"`.

Inside the body, two names are available that you didn't define:

- **`subscribe`** — frame-bound. `@(subscribe [:count])` reads the current value of the `:count` subscription, scoped to the surrounding frame.
- **`dispatch`** — frame-bound. `(dispatch [:counter/inc])` dispatches the event to the surrounding frame.

These are *injected* by `reg-view`. The reason: views need to know which frame they belong to (so that dispatching from a story-tool variant doesn't spam the production frame, for instance), and the cleanest way to make that work without forcing every view to take a frame argument is to inject the frame-bound versions implicitly.

The body uses them just like the original re-frame's `subscribe` and `dispatch`. No ceremony. The `@(...)` syntax is Clojure's "unwrap this reactive value to its current contents" — it's how Reagent (the underlying view substrate) tracks dependencies.

### Why register views?

Three reasons:

1. **Frame routing**. As above — registered views know their frame.
2. **AI inspection**. `(rf/handlers :view)` returns every registered view in the app. An AI can list, filter, and inspect them without parsing source files.
3. **Hot reload that works**. Re-evaluating the `reg-view` form replaces the registered view; mounted instances pick up the new code on next render.

There's a tradeoff: plain Reagent functions also work, but they don't get frame-routing for free. If you're writing a single-frame app, you can use plain `defn` views with no observable difference; if you're writing anything that might end up multi-frame (which, increasingly, is everything — stories, devcards, SSR), `reg-view` is the safer default.

## The mount

```clojure
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; wire the Reagent substrate
  (rf/dispatch-sync [:counter/initialise])  ;; seed app-db before first render
  (rdc/render root [counter]))
```

Four things happen here.

`defonce root` creates the React root once. The `defonce` matters: if the file is hot-reloaded, we want the existing root to survive so React can patch it in place rather than re-mount from scratch.

`(rf/init! reagent-adapter/adapter)` wires re-frame2 to the Reagent substrate. The runtime needs to know which view library it's driving (Reagent vs UIx vs Helix vs SSR), and `init!` is where that binding happens. We require `re-frame.adapter.reagent :as reagent-adapter` at the top of the file and pass its exported `adapter` Var. The call is **idempotent** — calling it twice is a no-op — so hot-reload is safe.

`(rf/dispatch-sync [:counter/initialise])` runs the initialiser inline — by the time the next line runs, `app-db` is `{:count 5}`. The role of this call is covered above in [Initialisation](#initialisation); we list it here for completeness because, in source order, it's part of `run`.

`(rdc/render root [counter])` is the React/Reagent runtime asking "render this hiccup at this root." `[counter]` is hiccup referencing the Var that `reg-view` defed.

Without `init!`, the runtime has no adapter installed and the first `subscribe` / `dispatch` from a view would not know how to wire its reactivity to React. For the call shape across UIx, Helix, and SSR — and why the call is explicit at every call site — see [chapter 19 — Adapters](19-where-next.md#adapters--the-pattern-across-substrates).

## What just happened

When the page loads, here's what runs:

1. `run` is called. `(rf/init! reagent-adapter/adapter)` installs the Reagent substrate adapter into the runtime.

2. `(rf/dispatch-sync [:counter/initialise])` runs the `:counter/initialise` handler synchronously. The handler returns `{:count 5}`. `app-db` is now `{:count 5}`. Then `rdc/render` mounts the `counter` view at the root.

3. The view's body runs. `@(subscribe [:count])` returns `5`. The hiccup tree is `[:div [:button "-"] [:span 5] [:button "+"]]`. Reagent renders that as DOM.

4. The user clicks `+`. The button's `:on-click` fires `(dispatch [:counter/inc])`. The event joins the queue.

5. The runtime pops the event. The `:counter/inc` handler runs: it reads `{:count 5}`, returns `{:count 6}`. The runtime updates `app-db`. The `:count` subscription notices it changed. The view re-renders. The DOM shows `6`.

That's the entire dynamic story. Five steps, all named, no surprises.

## Testing what we just built

```clojure
(deftest counter-flow
  (rf/with-frame [f (rf/make-frame {:on-create [:counter/initialise]})]
    (is (= 5 (rf/compute-sub [:count] (rf/get-frame-db f))))
    (rf/dispatch-sync [:counter/inc] {:frame f})
    (is (= 6 (rf/compute-sub [:count] (rf/get-frame-db f))))
    (rf/dispatch-sync [:counter/dec] {:frame f})
    (rf/dispatch-sync [:counter/dec] {:frame f})
    (is (= 4 (rf/compute-sub [:count] (rf/get-frame-db f))))))
```

That test runs on the JVM. There's no browser. There's no React. The `with-frame` block binds `f` to the freshly-made frame's id and pins `*current-frame*` to it for the body; `make-frame` returns the gensym'd id keyword and the runtime fires `:on-create` synchronously. `get-frame-db` returns the current `app-db` *value* (not a deref-able container) — `compute-sub` runs the registered `:count` sub against that value. Tests like this run in milliseconds and you can have thousands of them. (Per-test cleanup is handled by your test fixture — typically a `use-fixtures` that calls `destroy-frame!` between tests; the framework's testing helpers in [Spec 008](../../spec/008-Testing.md) wire that up for you.)

## What the example covered

We touched every load-bearing primitive at least once:

- ✓ A frame.
- ✓ Three event handlers.
- ✓ A subscription.
- ✓ A view.
- ✓ A test.

What we didn't cover yet:

- **Effects** that aren't state changes — HTTP, navigation, localStorage. Coming in [04 — Events, state, and the cycle](04-events-state-cycle.md).
- **Multiple frames** — you only need them when you need them. Coming in [06 — Views and frames](06-views-and-frames.md).
- **State machines** — for flows where "what's the next state?" is the load-bearing question. Coming in [08 — State machines](08-state-machines.md).
- **HTTP requests, the canonical way** — the `:rf.http/managed` fx with retry, abort, decode, and reply addressing. Coming in [10 — Doing HTTP requests](10-doing-http-requests.md).

## A small extension

If you wanted the counter to also remember a history of past values, you'd:

1. Change `:counter/initialise` to seed `{:count 5 :history [5]}`.
2. Change `:counter/inc` to update both `:count` and `:history`.
3. Add a `:history` subscription.
4. Display the history in the view.

That's it. The shape doesn't change. There's no new primitive to learn. Every change happens in the place you'd expect: a new event handler, a new sub, a new view fragment.

This is the real claim of re-frame2: **the cost of new features is bounded by the size of the feature, not by the size of the app**. In a poorly-shaped app, adding a feature requires reading a substantial fraction of the existing code to know where to wire it in. In a re-frame2 app, you read the events, the subs, and the view that touches the area you're changing, and that's enough.

For how this same counter looks under UIx and Helix, what `init!` is doing under the hood, and the slim-Reagent option for ship-size builds, see [chapter 19 — Adapters](19-where-next.md#adapters--the-pattern-across-substrates).

## Next

- [04 — Events, state, and the cycle](04-events-state-cycle.md) — what the dynamic story looks like when handlers also produce side-effects, not just state changes.
