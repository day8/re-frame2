(ns fresco-counter.subs
  "Subscriptions for the fresco story testbed.

  Ordinary `reg-sub`s. `h/sub` inside a boundary body resolves through
  the same registrar and the same frame the Story canvas scoped, which is
  why there is no Fresco-flavoured subscription surface to declare here."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :fresco-counter/count
  {:doc "The tally."}
  (fn [db _] (or (:count db) 0)))

(rf/reg-sub :fresco-counter/step
  {:doc "How far a bump moves the tally."}
  (fn [db _] (or (:step db) 1)))
