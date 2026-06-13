# Quickstart: a counter in five minutes

You'll build the classic counter. Then you'll make it show something other counters can't: its own history. Along the way you'll dispatch events, derive a value with a subscription, record a wall-clock fact the right way, and travel in time in the inspector.

**The takeaway: state changes only through events, handlers stay pure, world facts arrive recorded — and time travel falls out for free.**

> **Coming from Redux?** `reg-event-db` is your reducer — but there's no store to wire, no action creators, and no `useSelector` memo dance: subscriptions *are* the selector layer, built in and cached by input.

## Beat 1 — the whole machine in 20 lines

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

Click a button. Here's the loop you just ran: **a view dispatches an event → a pure handler computes the next state → the subscription delivers the change back to the view.** That one-way loop is the whole framework. Everything else refines it.

> **Coming from re-frame v1?** So far it's identical, except `reg-view` replaces bare form-1 components — it injects `dispatch`/`subscribe` pre-bound to the frame in scope, which is why the same component will later run in two isolated frames side by side, unchanged.

## Beat 2 — derive, don't store

Is the count odd or even? Don't store that. It isn't a new fact. It's a consequence of one you already have. Derive it:

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

`:<-` declares the input. `:counter/parity` reads the *other subscription*, not app-db. You've built a two-node spreadsheet: `:counter/value` is a cell, `:counter/parity` is a formula. The framework now knows the dependency. Parity recomputes only when the value changes, and a view watching parity re-renders only when parity actually flips. This is the rule that scales: **app-db stores facts; subscriptions derive conclusions.**

## Beat 3 — "last clicked", and where time comes from

Show when a button was last clicked. It looks like decoration. It's the most important idea on the page.

A pure handler must not read the clock. Replay the same event tomorrow and it would compute different state — and the history you're about to inspect in Beat 4 would be a lie. So re-frame2 stamps the time **onto the event** as it enters the queue. A handler that wants the time *declares* it:

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

Two things changed. First, the handler became `reg-event-fx`. It now takes the **coeffects** map — the bundle of facts a handler is allowed to know. A `reg-event-db` handler sees only db and event. Needing the world is what graduates a handler to the fx form. Second, it **declares** `:rf.cofx/requires [:rf/time-ms]`. Delivery is declared-only, so `time-ms` arrives flat in the coeffects map. It's read once, at the moment the click entered the system, then frozen onto the event's record. Replay this event next week and `last-clicked-at` comes out identical. The recorded fact is the milliseconds. Formatting with `.toLocaleTimeString` lives in the view, because pretty-printing is presentation.

> **Coming from re-frame v1?** You'd reach for `(inject-cofx :now)` — same purity instinct, but it was opt-in per handler and the value wasn't recorded, so replay re-rolled it. Declaring `:rf/time-ms` makes the same idea a recorded guarantee.

## Beat 4 — open the inspector: your app has a history

Open Xray. It ships with the dev build. Click `+` a few times. Every click is a **row**: the event, the app-db before and after, and the recorded `:rf/time-ms` you just used. You don't reconstruct state from `console.log`. You read it off a ledger.

Now restore an older row. The counter returns to that moment — value, parity, last-clicked, all of it. This isn't a trick bolted on for the demo. It falls out of the three rules you just followed: state changes only through events, handlers are pure, world facts arrive recorded. **You earned time travel by construction.**

## Running it locally

The snippets above are the whole app *except* boot. Boot is the one place you name the rendering substrate and the frame. Adapt `examples/reagent/counter/`:

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

A **frame** is one isolated world: app-db, registrations, and subscriptions. `frame-provider` scopes the mounted views to it, so every `subscribe` and `dispatch` resolves there. One app, one frame. You'll rarely think about it again until you want two ([Frames](concepts/frames.md)).

---

**You can now:**

- change state with events and pure handlers (`reg-event-db` / `reg-event-fx`)
- derive values instead of storing them (`reg-sub`, `:<-`)
- record a world fact the replay-safe way (`:rf.cofx/requires [:rf/time-ms]`)
- read your app's history in Xray and travel in it

**Next:** [Build something real](tutorial/index.md) — the RealWorld tutorial grows this same loop into a working app with a server, routing, and auth. Or read the model behind the loop first in [Core concepts](concepts/index.md).
