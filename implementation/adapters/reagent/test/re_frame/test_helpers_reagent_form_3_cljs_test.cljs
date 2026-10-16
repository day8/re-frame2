(ns re-frame.test-helpers-reagent-form-3-cljs-test
  "`re-frame.test-helpers/expand-tree` over a REAL stock Reagent Form-3
  class. `reagent.core/create-class` with `:reagent-render` sets that fn on
  the class prototype, and `expand-tree` must reach the rendered hiccup
  through it rather than call the constructor as a plain fn.

  Here rather than in core's test tree because it requires `reagent.core`,
  which core must not. Node-safe: `create-class` builds a JS class and
  nothing is mounted.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent.core :as r]
            [re-frame.test-helpers :as rf.test-helpers]))

(defn- leaf [s]
  [:span {:data-testid "leaf"} s])

(def ^:private panel
  (r/create-class
    {:display-name   "panel"
     :reagent-render (fn [{:keys [label]}]
                       [:div {:data-testid "panel"} [leaf label]])}))

(deftest expand-tree-renders-a-stock-create-class-component
  (is (= [:div {:data-testid "panel"} [:span {:data-testid "leaf"} "hi"]]
         (rf.test-helpers/expand-tree [panel {:label "hi"}]))
      "the class renders through its :reagent-render fn, and the nested fn component expands"))
