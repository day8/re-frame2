# 03 — Your first app

> **What you'll build.** A counter with a `+` button, a `-` button, and a number between them. Click `+`, number goes up. Click `-`, number goes down. Defaults to `5`. About fifty lines of CLJS, every load-bearing primitive in re-frame2 used at least once.
>
> **You should have working before you start.** A CLJS toolchain (shadow-cljs or equivalent), Node, and a browser. The [`examples/reagent/counter/`](../../examples/reagent/counter/) directory in the repo is the runnable form of this chapter — clone, `shadow-cljs watch app`, browse to the dev URL, and you can edit alongside.
>
> **What you'll learn.** The five primitives — `reg-event-db`, `reg-sub`, `reg-view`, `dispatch`, `subscribe`. The shape of `app-db`. How a click becomes an event becomes a state change becomes a re-render. Why none of those steps imperatively touches a DOM node. Where `init!` sits at the boundary.

The smallest interesting program is a counter: a number, two buttons, the number changes. Let's build it.

The full source is in [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs). This chapter walks through it section by section, explaining what each piece is doing and *why it's shaped the way it is*.

This chapter uses **Reagent** — the canonical CLJS view substrate, the one the rest of the guide uses. (re-frame2 also has UIx and Helix adapters; for adapter comparisons and the `init!` call shape across substrates, see [chapter 21 — Adapters](21-adapters.md).)

## What we're building

A page with a `+` button, a `-` button, and a number between them. Click `+`: the number goes up. Click `-`: it goes down. Default value: `5`.

> 📸 **Screenshot needed**: the running counter app in the browser, with the dev tools panel open showing `app-db`. Annotate (1) the `[-]`, `[5]`, `[+]` row in the page, (2) the `app-db` value `{:counter/value 5}` in the inspector, (3) the trace stream showing the most recent `:event/dispatched` entry.
>
> Save as: `/docs/images/guide/03-counter-running.png`

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
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

;; Subscription
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; View
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
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

> **Chapter vs the runnable file.** The listing above is the teaching shape: one view, `reg-event-db` for the initialiser, the smallest thing that runs. The runnable in [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs) is intentionally a little richer — it splits the UI into two views (`counter-buttons` + `counter-app`) and widens `:counter/initialise` to `reg-event-fx` with a `:fx` walk so the perf-instrumented build can exercise the `rf:fx:*` perf bucket on init. Both shapes are equivalent for the counter's behaviour.

## Initialisation

```clojure
(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter]))
```

Two things have to happen before the view can render: the runtime needs an adapter installed (so subscriptions know how to track reactivity), and `app-db` needs an initial value (so the first read of `@(subscribe [:counter/value])` returns something sensible). The `run` function does both.

`(rf/dispatch-sync [:counter/initialise])` runs the `:counter/initialise` event **synchronously**, in-line, before `run` returns. By the time `rdc/render` mounts the view on the next line, `app-db` is `{:counter/value 5}` and the first render shows `5`.

Why `dispatch-sync` rather than plain `dispatch`? Plain `dispatch` puts the event on the queue and returns immediately — the handler runs on the next animation frame. If the view tried to render against an empty `app-db` on the way there, it would either show nothing or pop briefly before the seeded value arrived. `dispatch-sync` is the right hammer for *seed-before-render*: drain this one event right now, treat the result as part of mount.

You'll only reach for `dispatch-sync` in two places, both at app boundaries: at mount, like this, and inside tests where you want to assert on the post-handler state without yielding to the queue. Everywhere else, `dispatch` is what you want — fire-and-forget, the runtime handles ordering.

## Events

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))
```

Three things to notice:

1. **The id is namespaced.** `:counter/initialise`, not `:initialise`. The convention is that every event's id starts with the feature it belongs to. This matters more as the app grows, but the habit starts here. An AI scaffolding new code reads existing event ids and picks a non-colliding one — namespacing makes that easy.

2. **The handler is a pure function** of `(db, event) → db`. The arguments start with `_` because we ignore them: this event doesn't read the previous state, and the event vector has no payload. The body returns the *new* state — a map with `:counter/value 5`.

3. **There's no side-effect**. The handler doesn't write to anything, doesn't fire HTTP requests, doesn't call `console.log`. It computes a new state and hands it back. The runtime applies it.

The other two events are similarly pure:

```clojure
(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))
```

`update` is Clojure's idiom for "transform a value at this key with this function." It returns a new map, leaving the old one untouched. Immutability everywhere.

### Why pure handlers?

Because pure handlers are *the easiest possible thing to test* — pass in a state, pass in an event, check the output. No mocks. No setup. No teardown.

```clojure
(deftest counter-inc-test
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:counter/value 5}
        after   (handler before [:counter/inc])]
    (is (= 6 (:counter/value after)))))
```

But there's a deeper reason. **A pure function has no time and no place.** It doesn't matter when it ran or what the global environment looked like; given the same arguments, it returns the same value. That property is what lets you reason about the function in isolation. As soon as a handler can call out to the network, mutate global state, or check the wall clock, you've lost that property — and you've gained a class of bugs that are dynamic, time-dependent, and very hard to fix.

re-frame2 keeps handlers pure. When side-effects need to happen, the handler *describes* them as data and returns them. The runtime interprets. We'll see this when we move on to a more complex example.

## Subscriptions

```clojure
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

A **subscription** is a derivation: a function from `app-db` to some value. Here, `:counter/value` is just `(:counter/value db)` — read the feature-scoped counter key.

But the framing matters. By naming this derivation `:counter/value` and making it a registered, queryable thing, we get:

- A view can subscribe to it without knowing the path in `app-db`. If we move the stored value into a richer counter slice later, only the subscription changes.
- Tooling can list every derivation in the app: "show me everything anyone could read." Useful for AI code generation and human inspection.
- Tests can compute the subscription against any state value: `(rf/compute-sub [:counter/value] some-db)` — no React, no rendering, just data → data.

Subscriptions can also chain. A `:counter/doubled` sub that depends on `:counter/value` would be:

```clojure
(rf/reg-sub :counter/doubled
  :<- [:counter/value]
  (fn [count _query] (* 2 count)))
```

The `:<-` is the input declaration. The framework builds a dependency graph from these declarations; when `:counter/value` changes, `:counter/doubled` recomputes — but only when something is reading it. Cheap when nobody's looking, fast when they are.

## The view

```clojure
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

This is the only piece with substantive shape. Let's look at it carefully.

`reg-view` is a defn-shape macro. It registers a render function (under a namespaced keyword auto-derived from the registration site — for this file, `:counter.core/counter`) and defs the symbol `counter` in the current namespace, bound to the wrapped fn. Hiccup elsewhere can reference it as `[counter]`. The render function returns **hiccup** — a Clojure data structure that describes a DOM tree. `[:div ...]` is a `<div>`. `[:button {:on-click ...} "-"]` is a `<button>` with a click handler and the text `"-"`.

Inside the body, two names are available that you didn't define:

- **`subscribe`** — `@(subscribe [:counter/value])` reads the current value of the `:counter/value` subscription.
- **`dispatch`** — `(dispatch [:counter/inc])` puts an event on the queue.

These are *injected* by `reg-view`. The body uses them just like the original re-frame's `subscribe` and `dispatch`. No ceremony. The `@(...)` syntax is Clojure's "unwrap this reactive value to its current contents" — it's how Reagent (the underlying view substrate) tracks dependencies.

### Why register views?

Two reasons:

1. **AI inspection**. `(rf/registrations :view)` returns every registered view in the app. An AI can list, filter, and inspect them without parsing source files.
2. **Hot reload that works**. Re-evaluating the `reg-view` form replaces the registered view; mounted instances pick up the new code on next render.

There's a tradeoff: plain Reagent functions also work, but they don't get registry introspection. For a small app you can use plain `defn` views with no observable difference; `reg-view` is the safer default.

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

`(rf/dispatch-sync [:counter/initialise])` runs the initialiser inline — by the time the next line runs, `app-db` is `{:counter/value 5}`. The role of this call is covered above in [Initialisation](#initialisation); we list it here for completeness because, in source order, it's part of `run`.

`(rdc/render root [counter])` is the React/Reagent runtime asking "render this hiccup at this root." `[counter]` is hiccup referencing the Var that `reg-view` defed.

Without `init!`, the runtime has no adapter installed and the first `subscribe` / `dispatch` from a view would not know how to wire its reactivity to React. For the call shape across UIx, Helix, and SSR — and why the call is explicit at every call site — see [chapter 21 — Adapters](21-adapters.md).

## What just happened

When the page loads, here's what runs:

1. `run` is called. `(rf/init! reagent-adapter/adapter)` installs the Reagent substrate adapter into the runtime.

2. `(rf/dispatch-sync [:counter/initialise])` runs the `:counter/initialise` handler synchronously. The handler returns `{:counter/value 5}`. `app-db` is now `{:counter/value 5}`. Then `rdc/render` mounts the `counter` view at the root.

3. The view's body runs. `@(subscribe [:counter/value])` returns `5`. The hiccup tree is `[:div [:button "-"] [:span 5] [:button "+"]]`. Reagent renders that as DOM.

4. The user clicks `+`. The button's `:on-click` fires `(dispatch [:counter/inc])`. The event joins the queue.

5. The runtime pops the event. The `:counter/inc` handler runs: it reads `{:counter/value 5}`, returns `{:counter/value 6}`. The runtime updates `app-db`. The `:counter/value` subscription notices it changed. The view re-renders. The DOM shows `6`.

That's the entire dynamic story. Five steps, all named, no surprises.

## Testing what we just built

The handler is a pure function — given `db` and `event`, return new `db`. That alone is testable directly:

```clojure
(deftest counter-handlers
  (let [inc-handler (:handler-fn (rf/handler-meta :event :counter/inc))
        dec-handler (:handler-fn (rf/handler-meta :event :counter/dec))]
    (is (= {:counter/value 6} (inc-handler {:counter/value 5} [:counter/inc])))
    (is (= {:counter/value 4} (dec-handler {:counter/value 5} [:counter/dec])))))
```

That test runs on the JVM. There's no browser, no React, no runtime needed — `handler-meta` looks up the registered function, you call it with a value, you assert on the return. Tests like this run in milliseconds and you can have thousands of them. [Chapter 15 — Testing](15-testing.md) covers the richer testing primitives (driving a full event through the dispatch loop, sub computation, fixtures) for when "call the handler as a function" isn't enough.

## What the example covered

We touched every load-bearing primitive at least once:

- ✓ Three event handlers.
- ✓ A subscription.
- ✓ A view.
- ✓ A test.

What we didn't cover yet:

- **Effects** that aren't state changes — HTTP, navigation, localStorage. Coming in [04 — Events, state, and the cycle](04-events.md).
- **State machines** — for flows where "what's the next state?" is the load-bearing question. Coming in [09 — State machines](11-machines.md).
- **HTTP requests, the canonical way** — the `:rf.http/managed` fx with retry, abort, decode, and reply addressing. Coming in [10 — Doing HTTP requests](12-http.md).

## A small extension

If you wanted the counter to also remember a history of past values, you'd:

1. Change `:counter/initialise` to seed `{:counter/value 5 :counter/history [5]}`.
2. Change `:counter/inc` to update both `:counter/value` and `:counter/history`.
3. Add a `:counter/history` subscription.
4. Display the history in the view.

That's it. The shape doesn't change. There's no new primitive to learn. Every change happens in the place you'd expect: a new event handler, a new sub, a new view fragment.

This is the real claim of re-frame2: **the cost of new features is bounded by the size of the feature, not by the size of the app**. In a poorly-shaped app, adding a feature requires reading a substantial fraction of the existing code to know where to wire it in. In a re-frame2 app, you read the events, the subs, and the view that touches the area you're changing, and that's enough.

For how this same counter looks under UIx and Helix, what `init!` is doing under the hood, and the slim-Reagent option for ship-size builds, see [chapter 21 — Adapters](21-adapters.md).

## Common mistakes the first time through

A few that bite people new to the pattern:

- **Calling `init!` more than once on the same page.** It's a one-shot at the boot boundary. Subsequent calls are diagnostic-emitting no-ops, but if your app has a hot-reload setup that re-evaluates the namespace on every save, wrap the `init!` call in a `defonce`-shaped guard or the rest of the app will get a fresh substrate every reload.
- **`dispatch` from the top of a namespace.** Top-level `dispatch` runs at *load* time, before the substrate is installed and before any frame exists. The right place for boot-time events is the `:on-create` slot on a registered frame (or the `init!` call site, after the adapter has installed).
- **`@(subscribe ...)` outside a registered view.** `subscribe` returns a Reagent reaction; deref-ing it outside a view body works once and then becomes stale — there's no surrounding component to re-render when the reaction's value changes. Outside views, reach for `(rf/sub-value [:my-sub])` instead — the snapshotting read that doesn't try to set up a reactive dependency.
- **Renaming the namespace and forgetting `init!`.** If you refactor `counter.core` into `myapp.boot` and the new namespace doesn't carry the `init!` line forward, the page mounts but every dispatch is a no-op against a substrate-less runtime. The trace stream will show `:rf.error/no-substrate` on the first event.

The trace surface catches all four of these by name. If something silently doesn't work, the first move is to check the trace stream — there's usually an event there saying exactly what didn't fire.

## Next

- [04 — Events, state, and the cycle](04-events.md) — what the dynamic story looks like when handlers also produce side-effects, not just state changes.
- [05 — Schemas](05-schemas.md) — what changes when you start declaring what `app-db` is supposed to look like. The counter doesn't need this; the form in chapter 10 will.
- [21 — Adapters](21-adapters.md) — the same counter written under UIx and Helix, plus what `init!` is doing under the hood.
