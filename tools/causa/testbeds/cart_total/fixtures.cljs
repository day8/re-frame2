(ns cart-total.fixtures
  "Deterministic cart-total state builders shared by the live testbed,
  Story variants, and browser tests.

  Keep these as plain event vectors where possible. Story can replay
  them directly into a variant frame, while the live app can dispatch the
  same sequence into :rf/default."
  (:require [re-frame.core :as rf]))

(def initialise-events
  [[:cart/initialise]])

(def tutorial-seed-events
  "Apple x2 + Bread x1. This is the tutorial's obvious-$6.50 basket.
  Because :cart/total deliberately reads :checkout/items, the visible
  total remains $0.00 before checkout starts."
  (into initialise-events
        [[:cart/add-item :apple]
         [:cart/add-item :apple]
         [:cart/add-item :bread]]))

(def checkout-snapshot-events
  "The seeded basket has been snapshotted into :checkout/items and the
  live basket has been cleared."
  (conj tutorial-seed-events [:checkout/start]))

(def live-basket-drift-events
  "The user starts checkout, then adds a new Apple to the live basket.
  The cart visibly contains Apple x1, but :cart/total still reads the
  $6.50 checkout snapshot."
  (conj checkout-snapshot-events [:cart/add-item :apple]))

(def discounted-snapshot-events
  "A discount applied to the wrong checkout snapshot. This pins the
  projection bug through both subscription and discount paths."
  (conj checkout-snapshot-events [:cart/apply-discount "STUDENT"]))

(defn dispatch-sync-events!
  "Replay an event fixture into the current frame."
  [events]
  (doseq [event events]
    (rf/dispatch-sync event))
  nil)
