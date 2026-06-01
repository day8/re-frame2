# 03 - Your first app

You clicked a counter in chapter 01 that I'd already wired up for you. Now you build it for real — in a project, on your own toolchain, every line yours — and in the process you meet the five primitives that *every* re-frame2 app is made of, no matter how big. The counter is trivial on purpose; the five primitives are the entire vocabulary, and once they click, the rest of the framework is just these five used with more nerve.

## What you're building, and what you need on hand

A page with a `+` button, a `-` button, and a number between them. Click `+`, the number goes up. Click `-`, it goes down. It starts at `5`. About fifty lines of ClojureScript, and — this is the part that matters — every load-bearing primitive in re-frame2 gets used at least once. Nothing's hidden. When you've read this chapter you've seen the whole alphabet.

To follow along on your own machine you want a CLJS toolchain (shadow-cljs or equivalent), Node, and a browser. The runnable form of this exact chapter lives in the repo at [`examples/reagent/counter/`](../../examples/reagent/counter/) — clone it, run `shadow-cljs watch app`, open the dev URL, and you can edit alongside me. If you don't have a toolchain set up yet, that's fine: there's a live version of the counter at the bottom of this chapter that runs in your browser with nothing installed, exactly like chapter 01's. Read first, install later.

This chapter uses **Reagent**, the canonical CLJS view substrate and the one the rest of the guide leans on. re-frame2 also drives UIx and Helix; the only thing that changes between them is a single Var passed to one `init!` call, and [chapter 22 — Adapters](22-adapters.md) is where that comparison lives. For now: Reagent.

> Yes, this is trivial. A counter in plain React is `useState(5)` and two `onClick`s and you're done. The reason we're spending a whole chapter on it is that **the shape you use to build this trivial thing is the same shape you'd use for a trading desk** — same five primitives, same wiring, same way of testing. If the small case feels clean, the large case will too. If the small case feels like ceremony — hold that thought, the last section is about exactly that.

## The whole thing, on one screen

Here's the file, in full, with the boilerplate stripped to its bones:

```clojure
(ns counter.core
  (:require [reagent.dom.client       :as rdc]
            [re-frame.core            :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; --- Events: pure functions of (db, event) -> next db ---
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

;; --- Subscription: a derivation from app-db ---
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; --- View: hiccup that subscribes and dispatches ---
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])

;; --- Mount: wire the substrate, seed the state, render ---
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; install the Reagent substrate
  (rf/dispatch-sync [:counter/initialise])  ;; seed app-db before first render
  (rdc/render root [counter]))
```

That's everything. Copy-paste-runnable. The five primitives are all in there, and I've grouped them with comments so you can see the shape before we pull it apart: **`reg-event-db`** registers the three events, **`reg-sub`** registers the one subscription, **`reg-view`** registers the view — and inside that view, **`dispatch`** sends events and **`subscribe`** reads state. Five names. That's the vocabulary. Let's go through them in the order the program uses them.

## Events: the only things that change anything

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))
```

An event handler is a pure function from `(db, event)` to a new `db`, and `reg-event-db` is how you register one under an id. Three things in that one form are worth stopping on, because each is a habit you want forming now while it's cheap:

**The id is namespaced.** `:counter/initialise`, not `:initialise`. Every event id starts with the feature it belongs to. In a fifty-line counter this looks like pure fussiness — there's only one feature, who are you disambiguating *from*? But the habit is what scales: in a real app, `:counter/inc` versus `:cart/inc` versus `:zoom/inc` are three different things, and the prefix is the difference between "grep for everything the counter feature does and find it in one place" and "good luck." It also matters for the machines: an AI scaffolding new code reads the existing ids and picks a non-colliding one, and namespacing makes that trivial. The convention costs nothing and starts here.

**The handler is pure.** `(fn [_db _event] {:counter/value 5})` — `db` and event in, new `db` out. No I/O, no clock, no globals, no `console.log`. The `_` prefixes on `_db` and `_event` are the Clojure idiom for "I'm deliberately ignoring this argument" — the initialiser doesn't care about the previous state (it's replacing it wholesale) and there's no payload on the event vector, so both get the underscore. The body just returns the new state: a map with `:counter/value 5`.

**There's no side effect, and that's not an accident — it's a guarantee the framework is making on your behalf.** The handler computes a new value and hands it back. It does not write `app-db`; the *runtime* does that, after the handler returns. The handler causes the change; the runtime performs it. (That split — cause here, perform there — is the entire subject of chapter 04, so I'll leave it as a flag for now.)

The other two events are the same shape, just less dramatic:

```clojure
(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))
```

`update` is Clojure's idiom for "transform the value at this key with this function" — `(update db :counter/value inc)` reads as "take `db`, replace its `:counter/value` with `(inc <the old value>)`." Crucially it returns a *new* map and leaves the old one untouched. This is the chapter-02 immutability story showing up in code: nobody mutates `db` in place, because you *can't* — it's a value, not a cell. You read a state, you return a state.

### Why this insistence on purity?

The cheap answer is testing, and it's a genuinely good answer, so here it is: a pure handler is the easiest thing in the world to test. Hand it a state, hand it an event, check the output. No mocks. No setup. No teardown. No browser.

```clojure
(deftest counter-inc-test
  (let [handler (:handler-fn (rf/handler-meta :event :counter/inc))
        before  {:counter/value 5}
        after   (handler before [:counter/inc])]
    (is (= 6 (:counter/value after)))))
```

That test runs on the JVM in a millisecond. `handler-meta` looks up the registered function by id, you call it with a value, you assert on the return. You can have thousands of these and run the lot before your coffee's cool. Chapter 13 is the full testing story; the point here is that it's *this easy*, and it's this easy because the handler is just a function.

But there's a deeper answer than testing, and it's the one worth internalising: **a pure function has no time and no place.** It doesn't matter *when* it ran or what the global environment looked like when it did — given the same arguments, it returns the same value, forever. That property is the entire reason you can reason about the function in isolation, in your head, without holding the rest of the app in your head at the same time. The moment a handler can hit the network, or read the wall clock, or mutate some global, that property is gone, and in its place you've got a function whose behaviour depends on the state of the universe at the instant it ran — which is the exact shape of the bug that only reproduces on Tuesdays. re-frame2 keeps handlers pure and shoves the impurity into one well-marked corner (chapter 04). The price is "do X" becoming "return a value that describes X." You pay it once, at one boundary.

## Subscriptions: reading state without knowing where it lives

```clojure
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))
```

A **subscription** is a derivation — a function from app-db to some value a view wants to see. This is the simplest one that can possibly exist: `(:counter/value db)`, just read the key out of the map. Which raises a fair question: why bother *naming* a derivation this trivial? Why not have the view read `(:counter/value db)` directly?

Because the naming is the whole point. Once `:counter/value` is a *registered, queryable thing*, three doors open:

- **The view can ask for it without knowing where it lives.** The view says `subscribe [:counter/value]`; it has no idea, and no need to know, that the value happens to sit at the `:counter/value` key right now. Move it into a richer slice later — say everything counter-related migrates under `[:counter :value]` — and you change *only the subscription*. Every view that reads it keeps working untouched. The subscription is an insulating layer between "how state is shaped" and "what views want from it."
- **Tooling can enumerate the whole derivation graph.** `(rf/registrations :sub)` hands back every subscription in the app — "show me everything anything could possibly read." That's gold for AI code generation and for a human trying to understand an unfamiliar codebase.
- **Tests can compute a sub against any state, with no React in sight.** `(rf/compute-sub [:counter/value] some-db)` runs the derivation against a value you hand it and gives you the result. Data in, data out, no rendering.

And subscriptions *chain*, which is where the performance story lives. A `:counter/doubled` built on top of `:counter/value`:

```clojure
(rf/reg-sub :counter/doubled
  :<- [:counter/value]
  (fn [count _query] (* 2 count)))
```

The `:<-` declares an input: "this sub depends on `:counter/value`." The framework reads those declarations and builds a dependency graph, so when `:counter/value` changes, `:counter/doubled` knows to recompute — but *only if something is actually reading it*. Cheap when nobody's looking, fast when they are. Chapter 05 is the whole graph; for now, just note that the simple "read a key" sub and the chained "derive from another sub" sub are the same primitive.

## Views: hiccup, and the two names you didn't define

```clojure
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "-"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

This is the only primitive with real shape, and even it is boring on purpose. Let's read it carefully, because two of the five primitives — `dispatch` and `subscribe` — live inside it.

`reg-view` is a `defn`-shaped macro. It registers a render function (under a namespaced keyword auto-derived from where you wrote it — here, `:counter.core/counter`) *and* defs the symbol `counter` in your namespace, bound to the wrapped function, so hiccup elsewhere can mount it as `[counter]`. The render function returns **hiccup** — a Clojure data structure that describes a DOM tree. `[:div ...]` is a `<div>`. `[:button {:on-click ...} "-"]` is a `<button>` with a click handler and the text `-`. It's just data; nothing renders yet.

Two names show up inside the body that you never defined:

- **`subscribe`** — `@(subscribe [:counter/value])` reads the current value of the `:counter/value` subscription. The `@` is Clojure's deref: "unwrap this reactive thing to its current contents." It's also the move that *makes the view re-render when the value changes* — by deref-ing the subscription inside the render body, the view declares a dependency on it, and Reagent's reactivity does the rest. The view doesn't poll and isn't told to update; it asked for a value, and the runtime re-runs it whenever that value changes.
- **`dispatch`** — `#(dispatch [:counter/dec])` is the click handler. It puts the `:counter/dec` event vector on the queue and returns, and that is the *entire* job a click has in re-frame2: announce that something happened. It does not reach into state. It does not touch the DOM. It dispatches an event and walks away, and the cascade picks it up from there.

These two names are *injected* by `reg-view` — the macro makes `dispatch` and `subscribe` available as lexical bindings inside the body so you can write them bare. (This is the one place a live cell differs from the static listing, and it's worth knowing now: in the browser playground, cells are functions-only, so you write a plain `defn` view and call the qualified `rf/dispatch` / `rf/subscribe` explicitly. `reg-view` is exactly sugar over that shape — same component, the macro just injects the two names and registers the result. You'll see the expanded form in the live cell at the end of this chapter.)

Notice what the view *never* does: it never imperatively pokes a DOM node. There's no `element.textContent = ...`, no `appendChild`. It *declares what the screen should look like given the current state* — return this hiccup — and the runtime makes the DOM match. That's the chapter-01 "derivative, not causal" claim made concrete: the view is a function of subscription values that happens to paint pixels, and it owns nothing.

### Why register views at all?

Plain Reagent `defn` functions also work as views, with no observable difference for a counter. So why `reg-view`? Two reasons, both about the machine reading your app rather than the human:

1. **Introspection.** `(rf/registrations :view)` returns every registered view in the app. An AI — or a devtool, or you at the REPL — can list, filter, and inspect them without parsing source files.
2. **Hot reload that actually works.** Re-evaluating a `reg-view` form replaces the registered view; mounted instances pick up the new code on the next render, cleanly.

The tradeoff is real but small: plain views skip the registry introspection. For a tiny app, use whichever; `reg-view` is the safer default and the one the guide uses.

<a id="initialisation"></a>

## The mount: where the impurity is allowed to live

```clojure
(defonce root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; install the Reagent substrate
  (rf/dispatch-sync [:counter/initialise])  ;; seed app-db before first render
  (rdc/render root [counter]))
```

Everything above this point was pure data and pure functions. The mount is where the app touches the real world, and it's deliberately tiny and deliberately at the boundary. Four things happen.

**`(defonce root ...)`** creates the React root exactly once. The `defonce` is load-bearing: when the file hot-reloads, you want the existing root to survive so React can patch it in place rather than tear down and re-mount from scratch. `defonce` means "def this if it isn't already defined" — survive the reload.

**`(rf/init! reagent-adapter/adapter)`** wires re-frame2 to the Reagent substrate. The runtime needs to know which view library it's driving — Reagent vs UIx vs Helix vs server-side rendering — because that's how subscriptions know how to hook their reactivity into the right reactive system. We required `re-frame.adapter.reagent :as reagent-adapter` up top and pass its exported `adapter` Var. The call is **idempotent** — calling it twice is a no-op — so hot-reload is safe. This one Var is the *only* thing that differs between substrates; swap it for the UIx adapter and the entire rest of the file is unchanged, which is the whole adapter story in one line.

**`(rf/dispatch-sync [:counter/initialise])`** runs the initialiser *synchronously, in-line, right now* — by the time the next line executes, app-db is `{:counter/value 5}` and the first render shows `5` instead of flickering through an empty state. Why `dispatch-sync` here and not plain `dispatch`? Because plain `dispatch` puts the event on the queue and returns immediately — the handler runs a tick later. If the view rendered against an empty app-db on the way there, it'd show nothing, or pop briefly before the seeded value landed. `dispatch-sync` is the right hammer for *seed-before-render*: drain this one event right now, treat its result as part of mounting. You'll reach for `dispatch-sync` in exactly two places — at the boot boundary like this, and in tests where you want to assert on post-handler state without yielding to the queue. **Everywhere else, plain `dispatch`** — fire and forget, let the runtime order things.

**`(rdc/render root [counter])`** asks Reagent to render this hiccup at this root. `[counter]` is hiccup referencing the Var that `reg-view` defed. Without the `init!` above it, the runtime would have no adapter installed, and the first `subscribe`/`dispatch` from the view wouldn't know how to wire its reactivity to React. The `init!` is what makes the rest of the file mean anything. (For the call shape across UIx, Helix, and SSR — and why it's an explicit call at every boot site rather than magic — see [chapter 22 — Adapters](22-adapters.md).)

## The cascade, end to end

Put it all together and here's the dynamic story, from page load to the first click:

1. `run` is called. `(rf/init! reagent-adapter/adapter)` installs the Reagent substrate into the runtime.
2. `(rf/dispatch-sync [:counter/initialise])` runs the initialiser synchronously. It returns `{:counter/value 5}`; app-db is now that value. Then `rdc/render` mounts `counter` at the root.
3. The view's body runs. `@(subscribe [:counter/value])` returns `5`. The hiccup is `[:div [:button "-"] [:span 5] [:button "+"]]`. Reagent paints it.
4. The user clicks `+`. The button's `:on-click` fires `(dispatch [:counter/inc])`. The event vector joins the queue. The click handler's job is over.
5. The runtime pops the event, runs the `:counter/inc` handler: reads `{:counter/value 5}`, returns `{:counter/value 6}`. The runtime swaps app-db. The `:counter/value` subscription notices its input changed and recomputes to `6`. The `counter` view, which deref'd that sub, re-renders. The `<span>` now reads `6`.

Five steps, all named, no surprises, no magic. Chapter 04 takes that single click and walks all six dominoes one at a time, with a trace you can watch fire. For now, the thing to feel is that the *same* cascade runs whether the app is this counter or something handling ten thousand events an hour. The machine doesn't get more complicated as your app does. You just register more instructions.

## The counter, live and in pieces

Here's that same counter running in your browser — a real re-frame2 program, no toolchain, nothing hidden off-screen. Click into the cell and press **`Ctrl-Enter`** (or **`Cmd-Enter`** on a Mac) to evaluate it. The first run takes a beat while the engine wakes up; after that it's instant. Then click the buttons.

Two differences from the static listing above, both from the cell environment being functions-only: the view is a plain `defn` (not `reg-view`), and it calls the *qualified* `rf/dispatch` / `rf/subscribe` rather than the injected bare names. As promised, `reg-view` is just sugar over exactly this `defn`-plus-explicit-`rf/`-verbs shape — same component, two spellings. Everything else is line-for-line the program you just read.

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; --- Events: pure functions of (db, event) -> next db ---
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 5}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

;; --- Subscription: a derivation from app-db ---
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; --- View: a plain defn (the cell is functions-only),
;;     calling rf/dispatch and rf/subscribe explicitly.
;;     reg-view is sugar over exactly this. ---
(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "-"]
   [:span {:style {:margin "0 1em" :font-size "1.4em"}}
    @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])

;; --- Seed app-db synchronously, then hand the view back to mount ---
(rf/dispatch-sync [:counter/initialise])
[counter]
```

> **Try it.** First, prove the handler is just a pure function you can rewrite at will: change the `inc` in `:counter/inc` to `(partial + 10)`, re-evaluate, then click `+`. The counter jumps by ten — you changed your program's behaviour by editing one line of one pure function, no rebuild, no reload. Now add a feature *without writing a handler*: drop `[:button {:on-click #(rf/dispatch [:counter/initialise])} "reset"]` in among the other buttons, re-evaluate, click `+` a few times, then `reset`. It snaps back to `5`. You added a reset button by dispatching an event that already existed. That's the shape paying off in miniature — and it's the same property the next section is about, just small.

## "This is a lot of ceremony for a counter"

It is. I won't pretend otherwise. Plain React is `useState(5)` and two `onClick`s and you're home in six lines; we're at fifty. If your whole app is a counter, use `useState`, godspeed, close the tab. I mean that — for a genuinely tiny thing, the ceremony is not worth it, and you should not feel clever for paying it.

But your whole app is not a counter, and the ceremony you just paid is *amortised over the entire lifetime of a real application*, which is the point at which it stops being ceremony and starts being the only thing keeping you sane. Here's the actual claim, the one this whole guide exists to earn:

**The cost of adding a feature is bounded by the size of the feature, not the size of the app.**

That is the opposite of how most codebases age, so sit with it. In a normally-shaped app, adding a feature starts with *reading a substantial fraction of the existing code* to figure out where on earth to wire it in: which components own the relevant state, which effects might fire, what'll break if you touch this. The marginal cost of a feature grows with the size of the app. That's the death spiral every large frontend eventually circles, and everyone who's been on a five-year-old React project knows the feeling in their bones.

In a re-frame2 app, you read the events, the subs, and the one view that touches the area you're changing — and that is *enough*, because there is nowhere else for the relevant logic to hide. State is in one place. Changes happen in one place. There's no `useEffect` in some leaf component quietly mutating the thing you're working on. The reset button you added above is the small version of this: you wanted a feature, you reused an existing event, you touched one view, done. That property scales up linearly while the app scales up quadratically, and the gap between those two lines is the whole pitch.

Want proof it scales? Suppose you want the counter to also remember its history of values. You'd: seed `:counter/initialise` to `{:counter/value 5 :counter/history [5]}`; update `:counter/inc` and `:counter/dec` to push onto `:counter/history` as well; add a `:counter/history` subscription; render the history in the view. Four changes, each in exactly the place you'd guess, no new primitive to learn. The shape did not change. It never does — you just register more instructions.

## Four ways it goes wrong the first time

A few mistakes bite people new to the pattern. The good news, which is also half the pitch, is that **the trace surface catches every one of them by name** — if something silently doesn't work, your first move is to read the trace stream, where there's usually an event saying exactly what didn't fire.

- **Calling `init!` more than once.** It's a one-shot at the boot boundary, and subsequent calls are diagnostic-emitting no-ops — but if your hot-reload setup re-evaluates the namespace on every save, wrap the `init!` in a `defonce`-shaped guard or you'll hand the app a fresh substrate on every keystroke.
- **`dispatch` at the top of a namespace.** A top-level `dispatch` runs at *load* time — before the substrate is installed, before any frame exists. Boot-time events belong on the `:on-create` slot of a registered frame (or at the `init!` call site, *after* the adapter installs), not floating at the top of a file.
- **`@(subscribe ...)` outside a view.** `subscribe` hands back a reactive thing; deref-ing it outside a view body works exactly once and then goes stale — there's no surrounding component to re-render when its value changes. Outside views, reach for `(rf/subscribe-once [:my-sub])`, the snapshotting read that doesn't try to set up a reactive dependency.
- **Renaming the boot namespace and dropping the `init!` line.** Refactor `counter.core` into `myapp.boot` without carrying `init!` forward and the page mounts but every dispatch is a silent no-op against a substrate-less runtime. The trace stream shows `:rf.error/no-substrate` on the first event — which is exactly the kind of "the framework told me what I did wrong" moment the trace bus exists for.

That's chapter 03: five primitives — `reg-event-db`, `reg-sub`, `reg-view`, `dispatch`, `subscribe` — used once each, on a counter you can now build, edit, and extend on your own toolchain. Chapter 04 takes the single click you just watched and slows it down to all six dominoes, with the machine's own trace running underneath it.
