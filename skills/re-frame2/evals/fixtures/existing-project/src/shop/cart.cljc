(ns shop.cart
  "The cart slice of a small existing re-frame2 application (eval fixture)."
  (:require [re-frame.core :as rf]))

(rf/reg-event :cart/add-item
  (fn [{:keys [db]} [_ item]]
    {:db (update db :cart/items (fnil conj []) item)}))

(rf/reg-sub :cart/items
  (fn [db _]
    (:cart/items db [])))
