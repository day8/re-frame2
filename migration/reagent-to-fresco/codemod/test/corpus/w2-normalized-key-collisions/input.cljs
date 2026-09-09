(ns app.w2-collisions
  "AMENDMENT (B) — W2 must refuse normalized-key collisions.

  Reagent's `kv-conv` writes each nested-map key under
  `cached-prop-name`, so these siblings each collapse onto ONE JS
  property with an iteration-order winner. Respelling both sources would
  either mint a duplicate literal map key — which is not even readable
  Clojure — or pick a winner the rewrite cannot know was the one that
  won. The whole map is refused, and every colliding source key named."
  (:require [reagent.core :as r]))

(defn an-ordinary-camel-case-collision []
  [:> C {:opts {:foo-bar 1 :fooBar 2}}])

(defn a-seeded-rename-collision []
  [:> C {:opts {:class "a" :className "b"}}])

(defn a-keyword-string-collision []
  [:> C {:opts {:foo-bar 1 "fooBar" 2}}])

(defn the-refusal-is-of-the-whole-map []
  ;; `:page-size` is rewritable and is NOT rewritten: a partial rewrite of
  ;; a map whose emitted shape is already ambiguous is worse than none.
  [:> C {:opts {:foo-bar 1 :fooBar 2 :page-size 10}}])

(defn a-non-colliding-sibling-map-still-rewrites []
  [:> C {:opts {:page-size 10} :other {:first-name "a"}}])
