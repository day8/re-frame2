(ns day8.re-frame2-causa.panels.subscriptions-format
  "Display formatting helpers for Causa's Subscriptions panel."
  (:require [clojure.string :as str]))

(defn format-query-v
  "Pretty-print a query vector for display."
  [q-v]
  (try
    (pr-str q-v)
    (catch #?(:clj Throwable :cljs :default) _
      (str q-v))))

(defn format-sub-id
  "Format a sub id for compact display in the row's mono column."
  [sub-id]
  (cond
    (keyword? sub-id) (str sub-id)
    (symbol? sub-id)  (str sub-id)
    :else             (str/trim (str sub-id))))
