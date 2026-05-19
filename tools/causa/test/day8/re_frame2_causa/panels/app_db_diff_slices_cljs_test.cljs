(ns day8.re-frame2-causa.panels.app-db-diff-slices-cljs-test
  "Per-leaf smoke test for `app-db-diff-slices` (rf2-nb8if).

  Renders each public slice fn (`empty-state`, `slice-row`,
  `changed-slices-stack`) once and asserts the load-bearing
  `data-testid` hook is present in the produced hiccup."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.panels.app-db-diff-slices :as slices]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest empty-state-renders
  (let [tree (slices/empty-state :rf/default)]
    (is (vector? tree))
    (is (has-testid? tree "rf-causa-app-db-diff-empty"))))

(deftest slice-row-renders-with-action-buttons
  (testing "rf2-e9tb0 — Pin button dropped from slice-row in lockstep
            with the pinned-watches strip removal. Show-me-when stays
            (independent affordance)."
    (let [tree (slices/slice-row {:op :modified :path [:cart :items]
                                  :before [] :after [{:id 7}]})]
      (is (has-testid? tree "rf-causa-app-db-diff-slice-[:cart :items]"))
      (is (nil? (some (fn [node]
                        (and (vector? node)
                             (map? (second node))
                             (= "rf-causa-app-db-diff-pin-[:cart :items]"
                                (:data-testid (second node)))))
                      (tree-seq (some-fn vector? seq?) seq tree)))
          "Pin button is gone — pinned-watches feature dropped (rf2-e9tb0)")
      (is (has-testid? tree "rf-causa-app-db-diff-show-when-[:cart :items]")))))

(deftest changed-slices-stack-renders-no-changes-state-on-empty
  (is (has-testid? (slices/changed-slices-stack [])
                   "rf-causa-app-db-diff-no-changes")))

(deftest changed-slices-stack-renders-slice-container-when-non-empty
  (let [tree (slices/changed-slices-stack
               [{:op :added :path [:user] :before nil :after "ada"}])]
    (is (has-testid? tree "rf-causa-app-db-diff-slices"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard.
;;
;; `changed-slices-stack` iterates triples and emits one `slice-row` per
;; triple. Previously the per-row child wrapped a `(slice-row t)` list
;; form under `^{:key …}` reader meta — Reagent's `get-react-key` only
;; reads `:key` from vector meta, so the key was silently lost and React
;; emitted "unique key prop" warnings. The fix routes the per-row child
;; through `with-meta` so the `:key` meta lands on the `[:div …]` vector.
;; This test asserts every per-row child carries `:key` meta so the
;; regression cannot recur silently. (rf2-ppzid)
;; ---------------------------------------------------------------------------

(deftest changed-slices-stack-children-carry-key-meta
  (let [triples [{:op :added    :path [:user]       :before nil :after "ada"}
                 {:op :modified :path [:cart :items] :before [] :after [{:id 7}]}
                 {:op :removed  :path [:token]      :before "x" :after nil}]
        tree    (slices/changed-slices-stack triples)
        ;; `into [:div …] (for [t …] (with-meta (slice-row t) …))` packs
        ;; the for-seq into the tail of the container vector.
        rows    (->> tree (drop 2))]
    (is (= 3 (count rows)) "one row per triple")
    (doseq [row rows]
      (is (vector? row) (str "row is a hiccup vector — got " (pr-str (type row))))
      (is (some? (some-> (meta row) :key))
          (str "row carries :key meta — got " (pr-str (meta row)))))
    (is (= ["[:user]" "[:cart :items]" "[:token]"]
           (mapv #(-> % meta :key) rows))
        "each row's :key is the pr-str of its :path")))
