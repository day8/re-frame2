# Quickstart: a counter in five minutes

You'll build the classic counter, and then you'll give it something most counters can't show you: its own history. Along the way you'll dispatch events, derive a value with a subscription, record a wall-clock fact the right way, and travel back in time in the inspector. None of that takes much code, which is sort of the point.

**The takeaway: state changes only through events, handlers stay pure, world facts arrive recorded — and time travel falls out for free.**

> **Coming from Redux?** `reg-event-db` is your reducer — but there's no store to wire, no action creators, and no `useSelector` memo dance: subscriptions *are* the selector layer, built in and cached by input.

## Beat 1 — the whole machine in 20 lines

Here's the entire app. Read it once top to bottom, then we'll walk through what each part is doing.

```clojure
(ns quickstart.counter
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; EVENTS — the only way state changes. Plain data in, new state out.
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 0}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

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

A few words on the moving parts. An **event** is a plain-data message describing something that happened — here, `[:counter/inc]`. To **dispatch** is to drop that message onto the queue. A **handler** is the pure function that receives it and computes the next state. The **app-db** is your app's single state map, the one place all state lives. A **subscription** is a named, derived read of that state, and a **view** is a component that renders from subscriptions and dispatches events back.

Now click a button and watch what happens. The loop you just ran is this: **a view dispatches an event → a pure handler computes the next state → the subscription delivers the change back to the view.** That one-way loop is the whole framework. Everything else in this guide just refines it.

**Try it.** Here's that same counter, running live in your browser — click the buttons, or edit the code and watch it re-render. (Live cells use plain `defn` views with explicit `rf/dispatch` / `rf/subscribe`, because the in-browser environment is functions-only; the shape is otherwise identical to the version above.)

```cljs-rf2
(require '[reagent2.core :as r]
         '[re-frame.core :as rf])

;; Events
(rf/reg-event-db :counter/initialise
  (fn [_db _event] {:counter/value 0}))

(rf/reg-event-db :counter/inc
  (fn [db _event] (update db :counter/value inc)))

(rf/reg-event-db :counter/dec
  (fn [db _event] (update db :counter/value dec)))

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
(rf/reg-event-fx :counter/inc
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

Two things changed here, and they're worth naming. First, the handler became `reg-event-fx`. It now receives the **coeffects** map — the bundle of outside-world facts a handler is allowed to know, like the current time or a random seed. A `reg-event-db` handler sees only db and event; the moment a handler needs the world, it graduates to the fx form. Second, it **declares** `:rf.cofx/requires [:rf/time-ms]`. Delivery is declared-only, so `time-ms` arrives flat in the coeffects map, already read at the instant the click entered the system and frozen onto the event's record. Replay this event next week and `last-clicked-at` comes out byte-for-byte identical. Notice the recorded fact is the raw milliseconds — formatting with `.toLocaleTimeString` lives in the view, because pretty-printing is presentation, not state.

> **Coming from re-frame v1?** You'd reach for `(inject-cofx :now)` — same purity instinct, but it was opt-in per handler and the value wasn't recorded, so replay re-rolled it. Declaring `:rf/time-ms` makes the same idea a recorded guarantee.

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
              [rf/frame-provider {:frame :rf/default}
               [counter/counter-app]]))
```

A **frame** is one isolated world — its own app-db, registrations, and subscriptions, all sealed off from any other frame. `frame-provider` scopes the mounted views to it, so every `subscribe` and `dispatch` inside resolves there. This app has one app, one frame, and you'll rarely think about frames again until the day you want two ([Frames](concepts/frames.md)).

---

**You can now:**

- change state with events and pure handlers (`reg-event-db` / `reg-event-fx`)
- derive values instead of storing them (`reg-sub`, `:<-`)
- record a world fact the replay-safe way (`:rf.cofx/requires [:rf/time-ms]`)
- read your app's history in Xray and travel in it
