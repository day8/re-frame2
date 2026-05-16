(ns day8.re-frame2-causa.panels.ai-co-pilot-input-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-input` (rf2-nb8if).

  Renders the `input-row` plain Reagent fn (converted from a
  `reg-view` per the canonical convention — internal sub-views are
  plain fns) and asserts the input + submit data-testid hooks."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.ai-co-pilot-input :as input]
            [day8.re-frame2-causa.panels.ai-co-pilot-subs :as subs]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest input-row-renders-input-and-submit
  (subs/install!)
  (frame/reg-frame :rf/causa {})
  (let [tree (input/input-row)]
    (is (has-testid? tree "rf-causa-copilot-input"))
    (is (has-testid? tree "rf-causa-copilot-submit"))))
