(ns fixture.arrow.negative.declared-inputs
  "NEGATIVE fixture (rule f): every shipped `reg-sub` shape — the layer-1
  app-db reader, a literal `:inputs` list, an empty declaration, and a
  parametric producer fn — must stay GREEN."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :cart/items (fn [db _] (:items db)))
(rf/reg-sub :cart/none {:inputs []} (fn [in _] in))
(rf/reg-sub :cart/total {:inputs [[:cart/items]]} (fn [[items] _] (count items)))
(rf/reg-sub :cart/visible {:inputs [[:cart/total] [:cart/filter]]}
  (fn [[total f] _] (f total)))
(rf/reg-sub :article/page {:inputs (fn [[_ id]] [[:article/by-id id]])}
  (fn [[article] _] article))
