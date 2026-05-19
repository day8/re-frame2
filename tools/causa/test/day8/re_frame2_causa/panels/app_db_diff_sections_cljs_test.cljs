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

;; rf2-e9tb0 — `pinned-group` and `pinned-row` were dropped when path-
;; segment click-to-inspect replaced the pinned-watches strip; the
;; matching render tests went with them.

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

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard.
;;
;; Three per-row `for` loops previously wrapped a function-call list form
;; under `^{:key …}` reader meta — Reagent's `get-react-key` only reads
;; `:key` from vector meta, so the key was silently lost and React
;; emitted "unique key prop" warnings. The fix routes every per-row child
;; through `with-meta` so the `:key` meta lands on the returned vector.
;; Walks the rendered tree, finds each `for`-seq (the list child of a
;; container vector), and asserts every per-row child carries `:key`
;; meta. (rf2-ppzid)
;; ---------------------------------------------------------------------------

(defn- find-vectors-with-key-meta [tree]
  ;; Walk every vector + seq node in `tree`, descending children in
  ;; place (no `mapv`) so the keyed vectors retain their original
  ;; meta. Returns every vector that carries `:key` meta — the for-
  ;; loops in this ns produce one keyed vector per row.
  (->> (tree-seq (some-fn vector? seq?)
                 (fn [n]
                   (cond
                     (vector? n) (if (map? (second n)) (drop 2 n) (rest n))
                     (seq? n)    n))
                 tree)
       (filter (every-pred vector? #(some-> (meta %) :key)))))

(deftest reserved-group-rows-carry-key-meta
  (let [tree (sections/reserved-group
               [[:rf/route {:id :app/cart}]
                [:rf/db    {:k :v}]])
        rows (find-vectors-with-key-meta tree)]
    (is (>= (count rows) 2) "at least one row per pair carries :key meta")
    (is (every? vector? rows) "every keyed row is a vector")))

(deftest focus-result-panel-hits-carry-key-meta
  (let [tree (sections/focus-result-panel
               [:cart :items]
               [{:epoch-id :e-1 :event [:cart/add]    :op :added
                 :before nil    :after [{:id 7}]}
                {:epoch-id :e-2 :event [:cart/remove] :op :removed
                 :before [{:id 7}] :after []}])
        rows (find-vectors-with-key-meta tree)]
    (is (>= (count rows) 2))
    (is (every? vector? rows))))
