(ns {{namespace}}.events
  "Event handlers. A handler is a pure function from the coeffects map (its
   `:db` is the current app-db) to an effects map; the runtime applies the
   returned `:db` and the views re-render from there."
  (:require [re-frame.core :as rf]))

;; Seeds app-db. core.cljs passes it as the frame's `:initial-events`, so it
;; runs once, when the frame is created — never on a hot reload.
(rf/reg-event
  :counter/initialise
  (fn [_cofx _event]
    {:db {:counter/value 0}}))

;; Dispatched by the button in views.cljs.
(rf/reg-event
  :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :counter/value inc)}))
