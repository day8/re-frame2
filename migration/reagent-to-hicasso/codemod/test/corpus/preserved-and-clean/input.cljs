(ns app.clean
  "§5.4 — divergences the tool deliberately does NOT repair, each because
  Hicasso is better than the donor or the difference is invisible. The
  report is written on a clean run too, so \"not in the report\" is never
  ambiguous."
  (:require [reagent.core :as r]))

(defn a-class-collection-under-any-spelling []
  ;; Reagent read the literal `:class` key only, so this reached React as
  ;; a `clj->js` array and the DOM wrote it "a,b". Hicasso coerces and
  ;; composes at the slot in every spelling.
  [:> Btn {:className ["a" "b"]}])

(defn a-nested-class-collection []
  ;; Reagent's `class-names` does not recurse; Hicasso's does.
  [:> Btn {:class ["a" ["b" "c"]]}])

(defn a-literal-nil-at-the-props-slot []
  [:> Btn nil "child"])

(defn scalars-and-functions-cross-unchanged []
  [:> Btn {:label "due date" :count 7 :open true :on-click handler}])

(defn a-js-literal-is-passed-through-by-both []
  [:> Btn {:opts #js {:first-name "a"}}])

(defn no-props-and-no-children []
  [:> Btn])
