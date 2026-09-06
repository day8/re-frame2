(ns fixture.arrow.positive.arrow-single-input
  "POSITIVE fixture (rule f): the retired single-input `reg-sub` chain. One
  finding — the one line carrying the retired keyword token."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _] (reduce + (map :price items))))
