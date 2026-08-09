(ns app.w2
  "W2 — nested map keys (design §4.2). Reagent's `convert-prop-value`
  recurses through the `map?` arm, respelling every key with
  `cached-prop-name`; Hicasso sends the same map to `clj->js`, whose keys
  keep the spelling the author wrote."
  (:require [reagent.core :as r]))

(defn the-kebab-to-camel-rule []
  [:> Chart {:options {:page-size 10 :first-name "a"}}])

(defn the-three-seeded-renames []
  [:> Chart {:cfg {:class "c" :for "f" :charset "utf-8"}}])

(defn aria-and-data-are-exempt []
  [:> Chart {:cfg {:aria-label "x" :data-kind "y" :other-key "z"}}])

(defn a-string-key-is-already-a-fixpoint []
  [:> Chart {:cfg {"first-name" 1}}])

(defn the-recursion-walks-through-maps []
  [:> Chart {:outer {:inner-map {:deep-key 1}}}])

(defn the-recursion-stops-at-the-first-non-map-collection []
  ;; Reagent reached these maps by the `coll?` arm, which is `clj->js`
  ;; and does not camelCase. So neither does W2.
  [:> Chart {:menu-items [{:day-of-week 1}] :tags #{{:other-key 2}}}])

(defn a-css-custom-property-is-repaired-not-preserved []
  [:> Chart {:style {:--brand-color "red" :font-size 12}}])

(defn a-computed-nested-key-keeps-its-spelling []
  [:> Chart {:cfg {(key-for x) 1 :page-size 2}}])
