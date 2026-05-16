(ns day8.re-frame2-causa.panels.ai-co-pilot-chrome-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-chrome` (rf2-nb8if).

  Renders the two public chrome fns and asserts data-testid hooks."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.ai-co-pilot-chrome :as chrome]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest cue-glyph-renders-with-testid
  (is (has-testid? (chrome/cue-glyph true) "rf-causa-copilot-cue")))

(deftest title-bar-renders-provider-and-close-buttons
  (let [tree (chrome/title-bar)]
    (is (has-testid? tree "rf-causa-copilot-provider-picker"))
    (is (has-testid? tree "rf-causa-copilot-close"))))
