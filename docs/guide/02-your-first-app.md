# 02 — Your first app

The smallest interesting program is a counter: a number, two buttons, the number changes. Let's build it.

The full source is in [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs). This chapter walks through it section by section, explaining what each piece is doing and *why it's shaped the way it is*. By the end you'll have seen every load-bearing primitive in re-frame2 at least once.

This chapter uses **Reagent** — the canonical CLJS view substrate, the one the rest of the guide uses. (re-frame2 also has UIx and Helix adapters; the [Same pattern on other adapters](#same-pattern-on-other-adapters) section at the end covers how this same code looks under those substrates, plus the Maven artefact details. You can skip that section on a first read.)

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

;; Frame
(rf/reg-frame :rf/default
  {:on-create [:counter/initialise]})

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
  (rf/init! reagent-adapter/adapter)   ;; wire the Reagent substrate
  (rdc/render root [counter]))
```

That's everything. Copy-paste-runnable. Let's take it apart.

## The frame

```clojure
(rf/reg-frame :rf/default
  {:on-create [:counter/initialise]})
```

A **frame** is the boundary that holds the app's state. In re-frame2, every app has at least one frame; here we're using the *default* frame, which the runtime ships with. A frame has its own `app-db` (a single immutable map — see [01a — app-db](01a-app-db.md) if you haven't yet), its own event queue, and its own subscription cache.

The `:on-create` line says: when this frame comes to life, dispatch `[:counter/initialise]`. That event will set the initial state.

You can think of `reg-frame` as "make me a fresh runtime with this initialisation step." For multi-frame apps (story tools, server-side rendering, devcards, isolated widgets) you'd register multiple frames with different ids. For an app that never thinks about isolation, the default frame is enough.

> **A note on globals.** This chapter registers the frame at the top level for brevity — fine for a counter demo. In a real app, frames aren't usually globals. They're typically created **per test** (e.g. inside `(rf/with-frame [f ...] ...)`, scoped to one test and destroyed on exit), or **attached to a view** (created once when a parent mounts, accessed by all child views via the `*current-frame*` dynamic binding — see ch.04 and Pattern-MultiFrame). The top-level `reg-frame` here is a minimal-mount shortcut, not the idiom you ship.

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

`reg-view` is a defn-shape macro. It registers a render function (under the auto-derived id `(keyword *ns* "counter")`) and defs the symbol `counter` in the current namespace, bound to the wrapped fn. Hiccup elsewhere can reference it as `[counter]`. The render function returns **hiccup** — a Clojure data structure that describes a DOM tree. `[:div ...]` is a `<div>`. `[:button {:on-click ...} "-"]` is a `<button>` with a click handler and the text `"-"`.

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
  (rf/init! reagent-adapter/adapter)   ;; wire the Reagent substrate
  (rdc/render root [counter]))
```

Three things happen here.

`defonce root` creates the React root once. The `defonce` matters: if the file is hot-reloaded, we want the existing root to survive so React can patch it in place rather than re-mount from scratch.

`(rf/init! reagent-adapter/adapter)` wires re-frame2 to the Reagent substrate. The runtime needs to know which view library it's driving (Reagent vs UIx vs Helix vs SSR), and `init!` is where that binding happens. We require `re-frame.adapter.reagent :as reagent-adapter` at the top of the file and pass its exported `adapter` Var. The call is **idempotent** — calling it twice is a no-op — so hot-reload is safe.

`(rdc/render root [counter])` is the React/Reagent runtime asking "render this hiccup at this root." `[counter]` is hiccup referencing the Var that `reg-view` defed.

Why is `init!` essential? Without it, the runtime has no adapter installed and the first `subscribe` / `dispatch` from a view would not know how to wire its reactivity to React. The next section unpacks why the call shape is the way it is.

You may notice there's no `dispatch-sync [:counter/initialise]` call — we don't need one because the frame's `:on-create [:counter/initialise]` fires that event synchronously at `reg-frame` time. `dispatch-sync` is the alternative when you'd rather seed `app-db` at mount time than at frame-creation time; either works.

### `init!` and how the adapter gets wired

The line `(rf/init! reagent-adapter/adapter)` deserves a closer look — it's where the adapter is bound to the runtime.

The call shape is fixed:

```clojure
;; Pass the adapter you want — explicit, always.
(rf/init! reagent-adapter/adapter)
```

Each adapter namespace exports an `adapter` Var (the nine-fn spec map Spec 006 documents). You require the namespace and pass the Var. **Explicit at the call site, every time.** Reading any app's `run` function tells you exactly which adapter the runtime is wired to, with no ns-load side-effects to chase.

Calling `(rf/init!)` with no args (or with a keyword like `:reagent`, or with `nil`) raises `:rf.error/no-adapter-specified` — the only legal call shape is `(rf/init! adapter-map)`. The error message points back at the adapter-ns + adapter-Var pattern so the recovery path is obvious.

(The same call shape applies to the other adapters — UIx, Helix, SSR. The [Same pattern on other adapters](#same-pattern-on-other-adapters) section at the end of this chapter shows what that looks like.)

## What just happened

When the page loads, here's what runs:

1. `reg-frame :rf/default` registers (and creates) the default frame. The `:on-create` event `[:counter/initialise]` fires synchronously. The handler returns `{:count 5}`. The frame's `app-db` is now `{:count 5}`.

2. `run` is called. `(rf/init! reagent-adapter/adapter)` installs the Reagent substrate adapter into the runtime. Then `rdc/render` mounts the `counter` view at the root.

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

- **Effects** that aren't state changes — HTTP, navigation, localStorage. Coming in [03 — Events, state, and the cycle](03-events-state-cycle.md).
- **Multiple frames** — you only need them when you need them. Coming in [04 — Views and frames](04-views-and-frames.md).
- **State machines** — for flows where "what's the next state?" is the load-bearing question. Coming in [05 — State machines](05-state-machines.md).
- **HTTP requests, the canonical way** — the `:rf.http/managed` fx with retry, abort, decode, and reply addressing. Coming in [06 — Doing HTTP requests](06-doing-http-requests.md).

## A small extension

If you wanted the counter to also remember a history of past values, you'd:

1. Change `:counter/initialise` to seed `{:count 5 :history [5]}`.
2. Change `:counter/inc` to update both `:count` and `:history`.
3. Add a `:history` subscription.
4. Display the history in the view.

That's it. The shape doesn't change. There's no new primitive to learn. Every change happens in the place you'd expect: a new event handler, a new sub, a new view fragment.

This is the real claim of re-frame2: **the cost of new features is bounded by the size of the feature, not by the size of the app**. In a poorly-shaped app, adding a feature requires reading a substantial fraction of the existing code to know where to wire it in. In a re-frame2 app, you read the events, the subs, and the view that touches the area you're changing, and that's enough.

## Same pattern on other adapters

Now that you've seen the counter end-to-end on Reagent, here's the wider picture: re-frame2's runtime — the registry, the dispatch loop, events, fx, subs, machines — is **substrate-agnostic**. The primitives you've used in `app-db`, in event handlers, and in subs are identical regardless of which substrate is rendering. What differs is how the view layer is wired into React.

Three substrate adapters are available today:

- **Reagent** — the canonical CLJS reference. Hiccup-shaped views, deref-tracking subscriptions (`@(subscribe ...)`), the substrate this chapter and the rest of the guide uses. Pick this if you have no other constraint.
- **UIx** — a CLJS adapter targeting React function components and hooks idiomatically. Useful if you're integrating with a JS-side codebase that expects React fn-components, or if you want UIx's compile-time JSX-style ergonomics.
- **Helix** — same shape as UIx in spirit; pick it if your team already uses Helix.

Each ships as its own Maven artefact alongside core (per Strategy B — see [`spec/MIGRATION.md`](../../spec/MIGRATION.md)):

```clojure
;; deps.edn — Reagent (the canonical "first app" stack)
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-reagent  {:mvn/version "2.0.0"}
        reagent                   {:mvn/version "2.0.0"}}}

;; deps.edn — UIx
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-uix      {:mvn/version "2.0.0"}
        com.pitch/uix.core       {:mvn/version "..."}}}

;; deps.edn — Helix
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-helix    {:mvn/version "2.0.0"}
        lilactown/helix          {:mvn/version "..."}}}
```

Per the [feature-opt-in story](../../spec/MIGRATION.md), core ships with **none** of the substrate adapters baked in — you add the artefact for the substrate you've picked. The same pattern applies to optional capabilities (state machines, routing, HTTP, schemas, SSR, time-travel) — each ships as its own artefact, and an app that doesn't use a feature doesn't bundle its code.

The `dispatch`, `subscribe`, and `reg-view` primitives are identical across substrates; the difference shows up in the mount call (`reagent.dom.client/render` vs `uix.dom/render-root` vs Helix's mount fn) and in how the view body composes — Reagent uses hiccup, UIx and Helix use their own component DSLs. The pattern survives.

The `init!` call follows the same shape on every adapter — pick the right `adapter` Var and pass it:

```clojure
(require '[re-frame.adapter.uix :as uix])
(rf/init! uix/adapter)

(require '[re-frame.adapter.helix :as helix])
(rf/init! helix/adapter)

(require '[re-frame.ssr :as ssr])   ;; JVM-side server bootstrap
(rf/init! ssr/adapter)
```

For a mixed-substrate app — say a build that imports both Reagent and UIx — pick the active adapter by passing the right Var. There is no multi-adapter ambiguity to resolve at boot: only one adapter is ever installed, and the call site names it.

## Next

- [03 — Events, state, and the cycle](03-events-state-cycle.md) — what the dynamic story looks like when handlers also produce side-effects, not just state changes.
