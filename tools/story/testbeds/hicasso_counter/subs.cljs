(ns hicasso-counter.subs
  "Subscriptions for the hicasso story testbed.

  Ordinary `reg-sub`s. `h/sub` inside a boundary body resolves through
  the same registrar and the same frame the Story canvas scoped, which is
  why there is no Hicasso-flavoured subscription surface to declare here."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :hicasso-counter/count
  {:doc "The tally."}
  (fn [db _] (or (:count db) 0)))

(rf/reg-sub :hicasso-counter/step
  {:doc "How far a bump moves the tally."}
  (fn [db _] (or (:step db) 1)))
