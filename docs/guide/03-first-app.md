# 03 - First app

You want the first real program, not another architecture diagram pretending to be a tutorial. This chapter gives you the boot shape for a small Reagent app: initialise the adapter, register events and subscriptions, define a view, seed the frame, and mount.

A tiny app normally has these namespaces: `events`, `subs`, `views`, and `core`. You can split later by feature. At the beginning, the important thing is learning which kind of code belongs where.

## Events

```clojure
(ns app.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-db :counter/initialise
  (fn [_db _]
    {:counter/value 0}))

(rf/reg-event-db :counter/inc
  (fn [db _]
    (update db :counter/value inc)))
```

Handlers are registered at load time. A `reg-event-db` handler is the smallest shape: `(db, event) -> new-db`. It is pure, so it is easy to test and hard to be surprised by.

## Subscriptions

```clojure
(ns app.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :counter/value
  (fn [db _]
    (:counter/value db)))
```

A subscription names a read. The view asks for `[:counter/value]`; it does not know whether that value lives at the top level today or inside a richer model tomorrow.

## Views

```clojure
(ns app.views
  (:require [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

(reg-view counter []
  [:main
   [:h1 "Counter"]
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:counter/value])]])
```

`reg-view` is `defn`-shaped sugar. It defines `counter`, registers it, and injects `dispatch` and `subscribe` as lexical bindings. In live docs cells we use plain `defn` because the cell runtime is function-only, but real Reagent app code can use `reg-view` directly.

## Boot

```clojure
(ns app.core
  (:require [app.events]
            [app.subs]
            [app.views :refer [counter]]
            [re-frame.adapter.reagent :as reagent]
            [re-frame.core :as rf]
            [reagent.dom.client :as rdc]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:export init []
  (rf/init! reagent/adapter)
  (rf/dispatch-sync [:counter/initialise])
  (rdc/render root [counter]))
```

`init!` installs the React substrate. `dispatch-sync` seeds the frame before the first render, which avoids a blank-state flicker and makes app boot deterministic. After that, user interactions use ordinary `dispatch`.

## The first debugging move

If nothing happens when you click, ask the runtime before blaming React. Did `init!` run? Did `:counter/inc` register? Did the view subscribe to the right query? The whole design is meant to make those questions answerable from the trace, not from superstition.
