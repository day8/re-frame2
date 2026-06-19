# Quickstart: a counter in five minutes

You'll build the classic counter, and then you'll give it something most counters can't show you: its own history. Along the way you'll dispatch events, derive a value with a subscription, record a wall-clock fact the right way, and travel back in time in the inspector. None of that takes much code, which is sort of the point.

**The takeaway: state changes only through events, handlers stay pure, world facts arrive recorded — and time travel falls out for free.**

> **Coming from Redux?** A `reg-event` handler is your reducer — but there's no store to wire, no action creators, and no `useSelector` memo dance: subscriptions *are* the selector layer, built in and cached by input. The one shape difference: a re-frame2 handler returns `{:db next-state}` — the next state *and* anything else to do — rather than returning the state bare.

## Beat 1 — the whole machine in 20 lines

Here's the entire app. Read it once top to bottom, then we'll walk through what each part is doing.

```clojure
(ns quickstart.counter
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; STATE TRANSITIONS — pure functions of state, easy to read and to test.
(defn inc-value [db] (update db :counter/value inc))
(defn dec-value [db] (update db :counter/value dec))

;; EVENTS — the only way state changes. A handler takes the coeffects (the
;; facts it's given — :db is one) and returns a map: the next state under
;; :db, plus anything else to do. Here the only thing to do is update state.
(rf/reg-event :counter/initialise
  (fn [_cofx _event] {:db {:counter/value 0}}))

(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (inc-value db)}))

(rf/reg-event :counter/dec
  (fn [{:keys [db]} _event] {:db (dec-value db)}))

;; SUBSCRIPTION — a named, derived read. Views never touch app-db directly.
(rf/reg-sub :counter/value
  (fn [db _query] (:counter/value db)))

;; VIEW — reg-view injects `dispatch` and `subscribe`, already bound to
;; the frame this component renders inside.
(reg-view counter-app []
  [:div
   [:button {:on-click #(dispatch [:counter/dec])} "−"]
   [:span @(subscribe [:counter/value])]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]])
```

A few words on the moving parts. An **event** is a plain-data message describing something that happened — here, `[:counter/inc]`. To **dispatch** is to drop that message onto the queue. A **handler** is the pure function that receives it and returns a map describing what should happen next: `{:db next-state}` here, where `:db` is the new value of app-db. Read that map as *"the next state, and anything else to do"* — for the counter there's nothing else, so it's just `:db`, but the same shape grows to carry an HTTP request or a follow-up event without changing the handler's signature. The **app-db** is your app's single state map, the one place all state lives. A **subscription** is a named, derived read of that state, and a **view** is a component that renders from subscriptions and dispatches events back.

Notice the two little `inc-value` / `dec-value` functions above the events. The pure state transition lives in a plain `(fn [db] …)` — "events are pure functions of state" stated literally — and the handler is the thin wrapper that hands it `:db` and wraps the result as `{:db …}`. You don't have to factor it out for something this small, but it's a habit worth forming early: the bare function is trivially testable (`(inc-value {:counter/value 5})` → `{:counter/value 6}`, no runtime), and the handler stays one obvious line.

Now click a button and watch what happens. The loop you just ran is this: **a view dispatches an event → a pure handler computes the next state → the subscription delivers the change back to the view.** That one-way loop is the whole framework. Everything else in this guide just refines it.

**Try it.** Here's that same counter, running live in your browser — click the buttons, or edit the code and watch it re-render. (Live cells use plain `defn` views with explicit `rf/dispatch` / `rf/subscribe`, because the in-browser environment is functions-only; the shape is otherwise identical to the version above.)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; State transitions — pure functions of state
(defn inc-value [db] (update db :counter/value inc))
(defn dec-value [db] (update db :counter/value dec))

;; Events — coeffects in, an effects map out ({:db next-state} here)
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

> **Coming from re-frame v1?** So far it's identical, except `reg-view` replaces bare form-1 components — it injects `dispatch`/`subscribe` pre-bound to the frame in scope, which is why the same component will later run in two isolated frames side by side, unchanged.

## Beat 2 — derive, don't store

Suppose you want to show whether the count is odd or even. The tempting move is to store a flag in app-db and keep it updated. Don't — because odd-or-even isn't a new fact, it's a consequence of one you already have. Derive it instead:

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

The `:<-` line declares the input, which means `:counter/parity` reads the *other subscription* rather than reaching into app-db. You've built a two-node spreadsheet: `:counter/value` is a cell, `:counter/parity` is a formula over it. Because the framework now knows that dependency, parity recomputes only when the value changes, and a view watching parity re-renders only when parity actually flips. That's the rule that keeps things fast as an app grows: app-db stores facts, subscriptions derive conclusions.

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

One thing changed, and it's worth naming what *didn't*. The handler is still a `reg-event` — same registration, same `(fn [coeffects event] {:db …})` shape. All we added is a line of metadata: `:rf.cofx/requires [:rf/time-ms]`. That's the payoff of one event form — needing the world is adding a key to a map, not converting to a different registration. The first argument, the `{:keys [db]}` you've been destructuring all along, is the **coeffects** map: the bundle of outside-world facts a handler is allowed to know, like the current time or a random seed. `:db` and `:event` are always there; everything else is declared. Delivery is declared-only, so once we ask for `:rf/time-ms` it arrives flat in that map, already read at the instant the click entered the system and frozen onto the event's record. Replay this event next week and `last-clicked-at` comes out byte-for-byte identical. Notice the recorded fact is the raw milliseconds — formatting with `.toLocaleTimeString` lives in the view, because pretty-printing is presentation, not state.

> **Coming from re-frame v1?** You'd reach for `(inject-cofx :now)` — same purity instinct, but it was opt-in per handler and the value wasn't recorded, so replay re-rolled it. Declaring `:rf/time-ms` makes the same idea a recorded guarantee. (And there's no `reg-event-db`/`reg-event-fx` fork to navigate any more — `reg-event` is the one form, with `:db` returned in the effects map.)

## Beat 4 — open the inspector: your app has a history

Open Xray, the dev inspector that ships with the dev build. Click `+` a few times. Every click shows up as a **row**: the event, the app-db before and after, and the recorded `:rf/time-ms` you just wired in. This is the payoff — instead of reconstructing what happened from scattered `console.log` calls, you read it straight off a ledger.

Now restore an older row. The counter returns to that exact moment — value, parity, last-clicked, all of it. This isn't a trick bolted on for the demo. It falls directly out of the three rules you've been following: state changes only through events, handlers are pure, and world facts arrive recorded. You earned time travel by construction.

## Running it locally

The snippets above are the whole app *except* for boot. Boot is the one place you name the rendering substrate and the frame, so adapt `examples/reagent/counter/` to match:

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
  (rf/reg-frame :rf/default {:doc "Counter app."}) ; the frame this app runs in
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:counter/initialise]))     ; seed before first render
  (rdc/render root
              [rf/frame-provider-existing {:frame :rf/default}
               [counter/counter-app]]))
```

A **frame** is one isolated world — its own app-db and subscription state, sealed off from any other frame. The registrations it runs come from an [image](concepts/images.md); by default that's the one implicit image projected from everything you've registered, so you don't name it. `frame-provider-existing` scopes the mounted views to the already-registered frame, so every `subscribe` and `dispatch` inside resolves there. This app has one app, one frame, and you'll rarely think about frames again until the day you want two ([Frames](concepts/frames.md)).

---

**You can now:**

- change state with events and pure handlers (`reg-event`, returning `{:db …}`)
- derive values instead of storing them (`reg-sub`, `:<-`)
- record a world fact the replay-safe way (`:rf.cofx/requires [:rf/time-ms]`)
- read your app's history in Xray and travel in it
