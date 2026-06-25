# Quickstart: a counter in five minutes

You'll build the classic counter, and then you'll give it something most counters can't show you: its own history. Along the way you'll [dispatch](glossary.md#dispatch) [events](glossary.md#event), derive a value with a [subscription](glossary.md#subscription), record a wall-clock fact the right way, and travel back in time in the inspector. None of that takes much code, which is sort of the point.

**The takeaway: state changes only through events, handlers stay pure, world facts arrive recorded — and time travel falls out for free.**

> **Coming from Redux?** An [event handler](glossary.md#event-handler) is your reducer — but there's no store to wire, no action creators, and no `useSelector` memo dance: [subscriptions](glossary.md#subscription) *are* the selector layer, built in and cached by input. The one shape difference: a re-frame2 handler returns an [effect map](glossary.md#effect-map) `{:db next-state}` — the next state *and* anything else to do — rather than returning the state bare.

## Beat 1 — the whole machine in 20 lines

Here's the entire app. Read it once top to bottom, then we'll walk through what each part is doing.

```clojure
(ns quickstart.counter
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; STATE TRANSITIONS — pure functions of state, easy to read and to test.
(defn inc-value [db] (update db :counter/value inc))
(defn dec-value [db] (update db :counter/value dec))

;; EVENTS — the only way state changes. A handler is handed a map of the
;; facts it gets to see (:db, the current state, is one) and returns a map:
;; the next state under :db, plus anything else to do. Here the only thing
;; to do is update state.
(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (inc-value db)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (dec-value db)}))

;; SUBSCRIPTION — a named, derived read. Views never touch app-db directly.
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; VIEW — reg-view hands the component a ready-to-use `dispatch` and
;; `subscribe`, so you don't import or wire them yourself.
(reg-view counter-app []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "−"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

A few words on the moving parts — five nouns and one verb, and they're the whole vocabulary you need to read the rest of this page. (Each links to its [glossary](glossary.md) entry if you want the longer story.)

- An [**event**](glossary.md#event) is an inert data vector — a fact that something happened — here, `[:counter/inc]`. To [**dispatch**](glossary.md#dispatch) is to drop that event onto a queue.
- The [**app-db**](glossary.md#app-db) is your app's single state map: the one place all the state you own lives. (`db` is short for database, and it really is the whole database — one map.)
- An [**event handler**](glossary.md#event-handler) (a "handler") is the pure function that receives an event and returns a map describing what should happen next: `{:db next-state}` here, where `:db` is the new value of app-db. Read that map as *"the next state, and anything else to do."* For the counter there's nothing else, so it's just `:db` — but the same shape grows to carry an HTTP request or a follow-up event without changing the handler's signature.
- A [**subscription**](glossary.md#subscription) is a named, derived read of app-db, and a [**view**](glossary.md#view) is a component that renders from subscriptions and dispatches events back.

(That last line — `reg-view` — needs the `:require-macros` form at the top because `reg-view` is a macro; a plain `:require` covers everything else. Don't sweat the distinction; it's the one piece of Clojure boilerplate on the page.)

That returned map is the handler's [**effect map**](glossary.md#effect-map), and its keys are a small, closed set: for application handlers, just `:db` (replace app-db) and `:fx` (an ordered vector of further [effects](glossary.md#effect) to run — dispatch another event, fire an HTTP request, write to local storage). Anything else in that set is reserved for the framework. Returning a key the framework doesn't recognise is a loud error rather than a silent no-op — there's no "I'll quietly ignore the typo" mode; this is the framework's standing [fail-loud](glossary.md#fail-loud-not-silent) posture, which you'll see again and again. (`:fx` is the subject of [Effects and coeffects](concepts/effects-and-coeffects.md); for the counter, `:db` alone is the whole story.)

Notice the two little `inc-value` / `dec-value` functions above the events. The pure state transition lives in a plain `(fn [db] …)` — "events are pure functions of state" stated literally — and the handler is the thin wrapper that hands it `:db` and wraps the result as `{:db …}`. You don't have to factor it out for something this small, but it's a habit worth forming early: the bare function is trivially testable (`(inc-value {:counter/value 5})` → `{:counter/value 6}`, no runtime, no mocks, no `render`), and the handler stays one obvious line. This is the seam re-frame2 keeps coming back to — pure data transformation in the middle, the messy outside world held at arm's length on either side.

Now click a button and watch what happens. One dispatch sets off a fixed, ordered run — **the [event cascade](glossary.md#event-cascade)**: **a view dispatches an event → a pure handler computes the next state → the subscription delivers the change back to the view.** Run that cascade over and over and you have the re-frame2 loop, and that one-way loop is the whole framework. Everything else in this guide just refines it — adds effects to the handler's output, adds layers to the subscription graph, adds isolation around the whole loop — but it never changes its shape. ([Events and the cascade](concepts/events-and-the-cascade.md) walks the full ordered run, stage by stage.)

> **`dispatch` vs `dispatch-sync`.** `dispatch` is the one you almost always want: it *queues* the event and returns immediately, so the handler runs on the next tick of the event loop. That asynchrony is deliberate — it keeps a click handler from blocking the browser and lets the framework batch a burst of dispatches into one render. [`dispatch-sync`](glossary.md#dispatch-sync) runs the handler *right now*, before it returns, and you reach for it in exactly two places: seeding app-db before the very first paint (so the first render has state to read), and inside tests (so the assertion can run on the next line). Default to `dispatch`; use `dispatch-sync` only at those boundaries — and never from *inside* a running handler, which raises `:rf.error/dispatch-sync-in-handler` rather than re-entering the loop (to fan out from a handler, return `:fx [[:dispatch [:some/event]]]` instead).

> **Going deeper.** That one-way loop is a *fold*. If you squint, app-db is an accumulator and your handlers are the reducing function: `db' = (handler db event)`, threaded over the stream of dispatched events — `(reduce step initial-db events)`, spread out over wall-clock time. Most of re-frame2's nicer properties are corollaries of that framing. Time travel is just keeping the intermediate accumulator values instead of throwing them away (Beat 4). The handler returning a *map* of effects rather than a bare `db` is the reducing function lifted into a command structure: it *describes* the next state and the other things to do, and a separate interpreter runs them — the same move a free monad makes, trading "do the effect" for "return a value that names the effect". You never need this vocabulary to use the framework, but if reductions and effect-as-data already live in your head, that's the shape you're looking at.

**Try it.** Here's that same counter, running live in your browser — click the buttons, or edit the code and watch it re-render. (Live cells use plain `defn` views with explicit `rf/dispatch` / `rf/subscribe`, because the in-browser environment is functions-only; the shape is otherwise identical to the version above.)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; State transitions — pure functions of state
(defn inc-value [db] (update db :counter/value inc))
(defn dec-value [db] (update db :counter/value dec))

;; Events — coeffects in, an effect map out ({:db next-state} here)
(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (inc-value db)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (dec-value db)}))

;; Subscription
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; View — a plain defn that subscribes and dispatches
(defn counter []
  [:div
   [:button {:on-click #(rf/dispatch [:counter/dec])} "−"]
   [:span {:style {:margin "0 1em" :font-size "1.4em"}}
    @(rf/subscribe [:counter/value])]
   [:button {:on-click #(rf/dispatch [:counter/inc])} "+"]])

;; Seed app-db, then hand the view back to be rendered
(rf/dispatch-sync [:counter/initialise])
[counter]
```

> **From re-frame v1.** So far it's identical, except `reg-view` replaces a bare form-1 component (a plain `(defn [] hiccup)` that closed over the global `dispatch`/`subscribe`) — `reg-view` injects them pre-bound to the [frame](glossary.md#frame) in scope instead, which is why the same component will later run in two isolated frames side by side, unchanged.

## Beat 2 — derive, don't store

Suppose you want to show whether the count is odd or even. The tempting move is to store a flag in app-db and keep it updated alongside the value. Don't — because odd-or-even isn't a *new* fact, it's a *consequence* of one you already have. Two copies of the same truth is two chances to disagree. Derive it instead:

```clojure
(rf/reg-sub :counter/parity
  :<- [:counter/value]
  (fn [n _query] (if (odd? n) :odd :even)))
```

And read it in the view's `:span`:

```clojure
[:span @(subscribe [:counter/value])
       " is " (name @(subscribe [:counter/parity]))]
```

The `:<-` line declares the input, which means `:counter/parity` reads the *other subscription* rather than reaching into app-db. You've built a two-node spreadsheet: `:counter/value` is a cell, `:counter/parity` is a formula over it. Because the framework now knows that dependency, parity recomputes only when the value changes, and a view watching parity re-renders only when parity actually *flips* — clicking from 2 to 4 changes the value but not the parity, and the parity view stays put. That's the rule that keeps things fast as an app grows: app-db stores facts, subscriptions derive conclusions.

You've now met two of the three ways `reg-sub` produces its inputs, and it's worth naming all three so the shape is in your hands:

- **App-db reader** — `(reg-sub id (fn [db query-v] …))`. No upstream subscriptions; the computation reads app-db directly. This is `:counter/value` — a *layer-1* subscription, the leaves of the graph that touch the raw state.
- **Static inputs** — `(reg-sub id :<- [:a] :<- [:b] (fn [[a b] query-v] …))`. One or more `:<-` lines name input subscriptions known at registration; the computation receives their values, never app-db. This is `:counter/parity`. Chain as many `:<-` lines as you need — the computation fn receives the inputs in order (a single input arrives bare, multiple arrive as a vector to destructure).
- **Parametric inputs** — for when *which* subscription you depend on is computed from the [query vector](glossary.md#query-vector) itself. Picture `[:counter/by-id 7]`, where the `7` selects which item to read: the input you need (`[:item 7]`) isn't known until query time. So you supply an **input-fn** — an optional first function that maps the outer query-vector to the vector of input query-vectors to subscribe to: `(reg-sub id (fn [[_ id]] [[:item id]]) (fn [[item] query-v] …))`. One shape wrinkle to know: a parametric sub *always* hands its computation fn a **vector** of resolved inputs to destructure — even a single input arrives as `[item]`, not bare `item` (that bare-single convenience is the static-`:<-` case only). You won't need this for the counter; reach for it the day a subscription takes a parameter.

> **Coming from React?** This is `useMemo` / Reselect, except you never pass a dependency array and never wire the selectors together by hand. The `:<-` *is* the dependency edge, declared once at registration; the framework caches each node and invalidates downstream only along edges that actually changed. Derived state that can't drift out of sync with its source, for free — which is the bug `useMemo` exists to paper over and `:<-` makes structurally impossible.

> **Going deeper.** The subscription graph is a *directed acyclic graph of pure functions over a single source cell*, evaluated lazily and memoised at every node — the classic shape of incremental/self-adjusting computation, and the spine of what re-frame2 calls [the derivation graph](glossary.md#the-derivation-graph). `:counter/value` is a function of app-db; `:counter/parity` is a function of `:counter/value`; nothing is a function of anything it didn't declare. Because the only mutable input is app-db, and every node downstream is pure, a change propagates exactly as far as values actually differ and no further — equality at a node short-circuits the whole subtree above it. That's why "clicking 2 → 4 doesn't re-render the parity view" isn't an optimisation you opted into; it's a theorem about a graph of pure functions with memoised edges.

> **Gotcha — subscribe to something that isn't registered, and you'll know.** `@(subscribe [:counter/typo])` doesn't silently hand back `nil`; an unregistered query id is a loud error. The same goes for `dispatch` — drop an event whose id has no handler and the framework tells you, rather than swallowing the click. This is the framework's standing posture: [fail loud at the boundary](glossary.md#fail-loud-not-silent), never paper over a typo with a quiet `nil`. The full catalogue of these errors and how to read them lives in [Errors](concepts/errors.md).

## Beat 3 — "last clicked", and where time comes from

Let's show when a button was last clicked. It looks like a throwaway bit of decoration, but it's quietly the most important idea on the page, so it's worth slowing down for.

Here's the constraint. A pure handler must not read the clock — because if it did, replaying the same event tomorrow would compute different state, and the history you're about to inspect in Beat 4 would be a lie. So re-frame2 reads the time once, as the event enters the queue, and stamps it **onto the event** itself. A handler that wants the time then *declares* it:

```clojure
(rf/reg-event :counter/inc
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (-> db
             (update :counter/value inc)
             (assoc :counter/last-clicked-at time-ms))}))

(rf/reg-sub :counter/last-clicked-at
  (fn [db _query] (:counter/last-clicked-at db)))
```

And at the bottom of the view:

```clojure
(when-let [t @(subscribe [:counter/last-clicked-at])]
  [:p "last clicked " (.toLocaleTimeString (js/Date. t))])
```

One thing changed, and it's worth naming what *didn't*. The handler is still a `reg-event` — same registration, same `(fn [coeffects event] {:db …})` shape. All we added is a line of metadata: `:rf.cofx/requires [:rf/time-ms]`. That's the payoff of one event form — needing the world is adding a key to a map, not converting to a different registration. (That metadata map is also where `:doc` lives — a one-line docstring tools and the inspector surface — so a fuller registration reads `{:doc "Increment, stamping the click time." :rf.cofx/requires [:rf/time-ms]}`.)

The first argument, the `{:keys [db]}` you've been destructuring all along, is the [**coeffects**](glossary.md#coeffect) map: the bundle of outside-world facts a handler is allowed to know, like the current time or a random seed. `:db` and `:event` are always there; everything else is declared. Delivery is declared-only, so once we ask for `:rf/time-ms` it arrives flat in that map, already read at the instant the click entered the system and frozen onto the event's record. Replay this event next week and `last-clicked-at` comes out byte-for-byte identical. Notice the recorded fact is the raw milliseconds — formatting with `.toLocaleTimeString` lives in the view, because pretty-printing is presentation, not state.

`:rf/time-ms` is the framework's *one built-in* coeffect — the wall-clock epoch milliseconds, stamped as the event is enqueued. It's the canonical *recordable* coeffect: captured onto the event before the handler runs, so the durable result depends on a recorded value and [replays identically](glossary.md#recordable-vs-ambient-coeffects). Other world facts (a random seed, a generated id, the browser's URL) follow the same recorded-coeffect pattern but are registered by their subsystem or your own app; [Effects and coeffects](concepts/effects-and-coeffects.md) is the full tour, including how you register your own with `reg-cofx`.

> **Supplying the time yourself.** Because `:rf/time-ms` rides the dispatch envelope, you can *hand it over* instead of letting the framework read the clock: `(dispatch [:counter/inc] {:rf.cofx {:rf/time-ms 1781078400123}})`. The framework fills `:rf/time-ms` only when you omit it and never overwrites a value you supplied — which is exactly how tests pin time to a fixed instant and how replay fixtures re-present the recorded one. (The `opts` map on `dispatch` / `dispatch-sync` carries a handful of other keys too — `:frame` to route to a specific frame, `:fx-overrides` and `:interceptor-overrides` for tests, `:trace-id` / `:source` for tooling — but `:rf.cofx` is the one you'll meet first.)

> **Why this matters.** The trick is that the impurity hasn't vanished — *something* still has to call the clock. re-frame2 just moves that read to the one place where it can be captured: the dispatch boundary, before the handler runs. The handler downstream sees only a value that was handed to it, so it stays a pure function of its inputs — and because the value was recorded on the way in, "what time was it?" has a permanent, replayable answer. That's the same instinct behind functional effect systems: don't ban side effects, *push them to the edge* and make the core a function. `:rf.cofx/requires` is how you ask the edge for a fact without becoming the edge yourself.

> **Gotcha — declare what you read.** Delivery is declared-only, so `:rf/time-ms` shows up in the coeffects map *only* because the handler asked for it. Read `(:rf/time-ms cofx)` without the `:rf.cofx/requires` line and you'll get `nil` — the fact wasn't delivered. And declaring a coeffect that nothing registered (a typo like `:rf/time-msc`) is a loud error, not a silent miss: the framework distinguishes "a registered fact wasn't supplied" (`:rf.error/missing-required-cofx`) from "you required a fact that doesn't exist" (`:rf.error/unregistered-cofx`), so a typo'd requirement dies early and obviously.

> **From re-frame v1.** You'd reach for `(inject-cofx :now)` — same purity instinct, but it was opt-in per handler and the value wasn't recorded, so replay re-rolled it. Declaring `:rf/time-ms` makes the same idea a recorded guarantee. (And there's no `reg-event-db`/`reg-event-fx` fork to navigate any more — `reg-event` is the one form, with `:db` returned in the effect map. The old names aren't soft-deprecated either; a stale `reg-event-db` call raises a loud error that names `reg-event` and shows the two-line conversion.)

## Beat 4 — open the inspector: your app has a history

Open [Xray](glossary.md#xray), the dev inspector that ships with the dev build. Click `+` a few times. Every click shows up as a **row**: the event, the app-db before and after, and the recorded `:rf/time-ms` you just wired in. Each row is one [**epoch**](glossary.md#epoch) — the before/after record a single cascade leaves behind. This is the payoff — instead of reconstructing what happened from scattered `console.log` calls, you read it straight off a ledger. ([Debug with Xray](how-to/debug-with-xray.md) is the full tour of the inspector.)

Now restore an older row. The counter returns to that exact moment — value, parity, last-clicked, all of it. This [**time-travel**](glossary.md#time-travel) isn't a trick bolted on for the demo. It falls directly out of the three rules you've been following: state changes only through events, handlers are pure, and world facts arrive recorded. Given those three, the history *is* a list of `(event, recorded-facts)` pairs, and re-running any prefix of that list reconstructs the exact app-db — there's nothing else for state to depend on. You earned time travel by construction.

> **The replay-safety contract, in one breath.** Drop any one of the three rules and the ledger lies. Mutate state outside an event (reach past `dispatch` and `assoc!` something directly) and a row's "after" no longer follows from its "before". Let a handler read the clock instead of declaring it and replay re-rolls the value. Both are the kinds of impurity the framework's fail-loud posture is built to surface — which is why "derive, don't store" and "declare your world facts" aren't style advice, they're what makes the inspector *true*.

## Running it locally

The snippets above are the whole app *except* for boot. Boot is the one place you name the rendering [substrate](glossary.md#substrate) and the [frame](glossary.md#frame), so adapt `examples/reagent/counter/` to match:

```clojure
(ns quickstart.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [quickstart.counter :as counter])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defonce root (rdc/create-root (js/document.getElementById "app")))

(defn run []
  (rf/init! reagent-adapter/adapter)              ; install the Reagent adapter
  (rf/reg-frame :rf/default                        ; the frame this app runs in,
    {:doc "Counter app."                           ; seeded by its initial events
     :initial-events [[:counter/initialise]]})
  (rdc/render root
              [rf/frame-provider-existing {:frame :rf/default}
               [counter/counter-app]]))
```

Three lines, and each names exactly one decision. [`init!`](glossary.md#init) installs the [**adapter**](glossary.md#adapter) — the small map of glue functions that teaches the framework how *this* rendering library (Reagent here; UIx and Helix are the other shipped options) turns subscriptions into re-renders. `reg-frame` creates the [**frame**](glossary.md#frame) this app runs in, *and seeds it*: `:initial-events` is an ordered vector of setup events dispatched synchronously into the fresh frame, drained to settled state before `reg-frame` returns — so app-db holds `{:counter/value 0}` before the first paint, with no separate seed dispatch to remember. And [`frame-provider-existing`](glossary.md#frame-provider) scopes the mounted views to that already-registered frame, so every `subscribe` and `dispatch` inside resolves there.

> **Where did `:initial-db` go?** A frame always starts with `app-db = {}`; there is no `:db` (or `:initial-db`, or `:on-create`) config key. Seeding is *itself an event* — that's the whole reason the seed goes through `:initial-events` rather than a magic config slot. It keeps "events are the unit of state change" honest: the initial state is built by the very same dispatch-and-cascade machinery that handles every later change, so it shows up in the inspector ledger like any other event. (Need to set app-db wholesale rather than from a handler? The framework's `[:rf/set-db {…}]` event does exactly that as a first step.) If you genuinely need to dispatch into the frame *after* boot — a REPL poke, a test — [`with-frame`](glossary.md#frame) pins the frame for a body of `dispatch` / `dispatch-sync` calls, and [`frame-handle`](glossary.md#frame-handle) hands you a callable bundle of a frame's ops to carry across async.

A [**frame**](glossary.md#frame) is one isolated world — its own app-db and subscription state, sealed off from any other frame. The registrations it runs come from an [image](glossary.md#image); by default that's the one implicit image projected from everything you've registered, so you don't name it. (A frame isolates *state*, not registrations — those live in a process-wide [registrar](glossary.md#registrar) every frame shares.) This app has one app, one frame, and you'll rarely think about frames again until the day you want two ([Frames](concepts/frames.md)) — at which point the payoff lands: because your views were never bound to a global store, the same `counter-app` mounts into a second frame with a second, independent app-db, no changes.

> **`frame-provider` vs `frame-provider-existing`.** We used `frame-provider-existing` because *we* created the frame at top level with `reg-frame`, and the component's only job is to *scope* that existing frame into the React tree (it creates and destroys nothing). Its sibling [`frame-provider`](glossary.md#frame-provider) is the UI-owned form: hand it `{:id :session :initial-events [[:rf/set-db {}]]}` and *it* creates the frame on mount and destroys it on unmount — the right tool when a frame's lifetime is tied to a component being on screen (a modal, a per-tab session) rather than to the whole app. Same scoping power; the difference is who owns the frame's birth and death.
