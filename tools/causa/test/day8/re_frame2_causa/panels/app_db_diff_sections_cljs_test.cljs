(ns day8.re-frame2-causa.panels.app-db-diff-sections-cljs-test
  "Per-leaf smoke test for `app-db-diff-sections` (rf2-nb8if).

  Renders the three section-level public fns (`reserved-group`,
  `pinned-group`, `focus-result-panel`) and asserts the
  `data-testid` hooks the parent panel relies on. Walks the
  hiccup tree directly — no DOM mount, no Reagent runtime."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.app-db-diff-sections :as sections]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest reserved-group-renders-when-pairs-present
  (let [tree (sections/reserved-group [[:rf/route {:id :app/cart}]])]
    (is (vector? tree))
    (is (has-testid? tree "rf-causa-app-db-diff-reserved-group"))
    (is (has-testid? tree "rf-causa-app-db-diff-reserved-:rf/route"))))

(deftest reserved-group-collapses-when-empty
  (is (nil? (sections/reserved-group []))))

(deftest pinned-group-renders-when-slices-present
  (let [tree (sections/pinned-group
               [{:path [:cart :items] :value [{:id 7}]}])]
    (is (has-testid? tree "rf-causa-app-db-diff-pinned-group"))
    (is (has-testid? tree "rf-causa-app-db-diff-pinned-[:cart :items]"))))

(deftest focus-result-panel-renders-no-hits-message
  (let [tree (sections/focus-result-panel [:cart :items] [])]
    (is (has-testid? tree "rf-causa-app-db-diff-focus-result"))
    (is (has-testid? tree "rf-causa-app-db-diff-clear-focus"))))

(deftest focus-result-panel-renders-hit-list
  (let [tree (sections/focus-result-panel
               [:cart :items]
               [{:epoch-id :e-1 :event [:cart/add] :op :added
                 :before nil :after [{:id 7}]}])]
    (is (has-testid? tree "rf-causa-app-db-diff-focus-hits"))
    (is (has-testid? tree "rf-causa-app-db-diff-focus-hit-:e-1"))))
