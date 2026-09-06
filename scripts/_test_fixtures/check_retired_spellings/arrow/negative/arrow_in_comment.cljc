(ns fixture.arrow.negative.arrow-in-comment
  "NEGATIVE fixture (rule f): the retired spelling named in a `;` comment and
  in a docstring is documentation, not a reintroduction. Comment + string
  masking must keep the gate GREEN — the same treatment rules (a)-(c) get."
  (:require [re-frame.core :as rf]))

(def migration-note
  "M-75: (reg-sub id ?meta :<- q1 :<- qn f) becomes
  (reg-sub id (assoc meta :inputs [q1 qn]) f).")

;; Historical note: this sub used to be written `:<- [:cart/items]` before
;; rf2-kuky.50 deleted the arrow grammar. It declares `:inputs` now.
(rf/reg-sub :cart/total
  {:inputs [[:cart/items]]}
  (fn [[items] _] (reduce + (map :price items))))
