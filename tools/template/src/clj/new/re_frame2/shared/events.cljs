(ns {{namespace}}.events
  "Event handlers — the second domino in the re-frame2 pipeline.

   The handler is a pure function `(fn [db event] new-db)`. The runtime
   applies the returned db to the frame; views fan out from there."
  (:require [re-frame.core :as rf]))

;; --- Initialisation --------------------------------------------------------
;;
;; `:counter/initialise` seeds the app-db at app boot. It's invoked from
;; core/run via `dispatch-sync` so the first render sees the initial
;; value rather than a transient empty frame.

(rf/reg-event-db
  :counter/initialise
  (fn [_db _event]
    {:counter/value 0}))

;; --- Counter increment -----------------------------------------------------
;;
;; The button in views.cljs dispatches this event. Pure update — no
;; effects, no side-channels. Everything else (re-render, trace emission,
;; epoch tagging, schema validation) is the runtime's job.

(rf/reg-event-db
  :counter/increment
  (fn [db _event]
    (update db :counter/value inc)))
