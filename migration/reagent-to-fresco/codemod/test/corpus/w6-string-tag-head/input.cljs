(ns app.w6
  "W6 — the string tag head (design §4.6). Under Reagent a string head at
  `input` or `textarea` took the controlled-input wrapper; under Hicasso
  a string head is refused at the crossing."
  (:require [reagent.core :as r]))

(defn a-controlled-input []
  [:> "input" {:value v :on-change f}])

(defn an-ordinary-tag []
  [:> "div" {:class-name "wide"} "text"])

(defn the-shorthand-was-already-broken []
  ;; The `:>` path never parsed the shorthand, so this asked React for an
  ;; element that does not exist. Rewriting it would make it start working.
  [:> "div#id" {}])

(defn a-carrier-would-go-live-at-a-native-tag []
  ;; INERT under Reagent (`coll?` precedes `ifn?`), LOWERED at a native
  ;; Hicasso tag. The design's one genuinely fatal class, so the head stays.
  [:> "button" {:on-click [:boom]} "Go"])

(defn w1-and-w4-still-apply-at-a-native-destination []
  ^{:key 1} [:> "input" {:on-change (r/partial handler @cart)}])
