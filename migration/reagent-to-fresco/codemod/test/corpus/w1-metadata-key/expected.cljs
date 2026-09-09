(ns app.w1
  "W1 — the metadata key (design §4.1). `grep` for `(meta ` across the
  codec returns nothing, so a migrated seq of keyed crossings loses every
  key at every build with no diagnostic. This is the highest-value
  rewrite in the design."
  (:require [reagent.core :as r]))

(defn extends-an-existing-props-map []
  (for [row rows]
    [:> Row {:label (:label row) :key (:id row)}]))

(defn mints-a-props-map-when-there-is-none []
  (for [row rows]
    [:> Row {:key (:id row)}]))

(defn mints-one-in-front-of-children []
  (for [row rows]
    [:> Row {:key (:id row)} "just a child"]))

(defn keeps-other-metadata []
  ^{:tag "x"} [:> Row {:a 1 :key 1}])

(defn refuses-a-conflict []
  ;; Reagent's metadata key WON here, so relocating it would have to
  ;; overwrite the props `:key` the author wrote. That is a decision.
  ^{:key (:id row)} [:> Row {:key (:fallback row)}])

(defn refuses-when-the-props-slot-is-computed []
  ^{:key 1} [:> Row (merge base extra)])
