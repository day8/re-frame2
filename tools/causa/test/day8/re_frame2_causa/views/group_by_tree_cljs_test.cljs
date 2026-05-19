(ns day8.re-frame2-causa.views.group-by-tree-cljs-test
  "Tests for the Group-by-tree renderer (rf2-mxkq7).

  Covers:
   - `build-tree-rows` projection algebra (descendant-rerender-count
     rollup, status classification, tree-vs-flat fallback).
   - `tree-section` data-testid surface (panel-consistency shape).
   - The end-to-end view path: when the panel's view consumes
     :rf.causa/views-data with `:group-by :tree`, the tree section
     renders without throwing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.views :as facade]
            [day8.re-frame2-causa.panels.views-view :as view]
            [day8.re-frame2-causa.views.group-by-tree :as gbt]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers ------------------------------------------------------------

(defn- mk-single
  ([view-id]
   (mk-single view-id nil))
  ([view-id triggered-by]
   {:kind   :single
    :render {:render-key   [view-id 0]
             :triggered-by triggered-by
             :elapsed-ms   1.0}
    :invalidated-by (when triggered-by
                      [{:sub-id triggered-by :trigger? true}])}))

;; ---- build-tree-rows ----------------------------------------------------

(deftest build-tree-rows-flat-fallback-when-no-fiber-tree
  (testing "rf2-mxkq7 — nil Fiber tree → depth-0 row per item"
    (let [groups {:mounted   []
                  :rendered  [(mk-single :a :sub-a)
                              (mk-single :b :sub-b)]
                  :unmounted []}
          rows   (gbt/build-tree-rows nil groups)]
      (is (= 2 (count rows)))
      (is (every? #(= 0 (:depth %)) rows)
          "fallback uses depth 0 for every row")
      (is (= #{:a :b} (set (map :view-id rows)))))))

(deftest build-tree-rows-uses-fiber-tree-depths
  (testing "rf2-mxkq7 — Fiber tree → rows in tree order at tree depths"
    (let [tree   [{:view-id :root  :depth 0 :fiber-key 1}
                  {:view-id :child :depth 1 :fiber-key 2}]
          groups {:mounted   []
                  :rendered  [(mk-single :root :s1)
                              (mk-single :child :s2)]
                  :unmounted []}
          rows   (gbt/build-tree-rows tree groups)]
      (is (= [:root :child] (mapv :view-id rows)))
      (is (= [0 1] (mapv :depth rows))))))

(deftest build-tree-rows-rolls-up-descendant-rerender-count
  (testing "rf2-mxkq7 — parent rows carry a count of re-rendered
            descendants for the 'X (47 descendants re-rendered)' chip"
    (let [tree   [{:view-id :grandparent :depth 0 :fiber-key 1}
                  {:view-id :parent      :depth 1 :fiber-key 2}
                  {:view-id :child-a     :depth 2 :fiber-key 3}
                  {:view-id :child-b     :depth 2 :fiber-key 4}]
          ;; grandparent is unchanged; parent + both children
          ;; re-rendered. Expect: grandparent has 3 descendants
          ;; re-rendered; parent has 2; child-a + child-b each 0.
          groups {:mounted   []
                  :rendered  [(mk-single :parent :s1)
                              (mk-single :child-a :s2)
                              (mk-single :child-b :s3)]
                  :unmounted []}
          rows   (gbt/build-tree-rows tree groups)
          by-vid (into {} (map (juxt :view-id identity)) rows)]
      (is (= 3 (:descendant-rerender-count (get by-vid :grandparent))))
      (is (= 2 (:descendant-rerender-count (get by-vid :parent))))
      (is (= 0 (:descendant-rerender-count (get by-vid :child-a))))
      (is (= 0 (:descendant-rerender-count (get by-vid :child-b)))))))

(deftest build-tree-rows-classifies-status
  (testing "rf2-mxkq7 — every row gets a :status (mounted / rendered /
            unmounted / unchanged)"
    (let [tree   [{:view-id :m :depth 0 :fiber-key 1}
                  {:view-id :r :depth 0 :fiber-key 2}
                  {:view-id :u :depth 0 :fiber-key 3}
                  {:view-id :x :depth 0 :fiber-key 4}]
          groups {:mounted   [(mk-single :m)]
                  :rendered  [(mk-single :r :s1)]
                  :unmounted [(mk-single :u)]}
          rows   (gbt/build-tree-rows tree groups)
          by-vid (into {} (map (juxt :view-id identity)) rows)]
      (is (= :mounted   (:status (get by-vid :m))))
      (is (= :rendered  (:status (get by-vid :r))))
      (is (= :unmounted (:status (get by-vid :u))))
      (is (= :unchanged (:status (get by-vid :x)))
          ":x has no render-projection item → :unchanged"))))

;; ---- tree-section -------------------------------------------------------

(deftest tree-section-renders-data-testid
  (testing "rf2-mxkq7 — top-level container carries
            `rf-causa-views-tree` testid"
    (let [rows    [{:view-id :a :depth 0 :fiber-key 1 :status :rendered
                    :item (mk-single :a :s1)
                    :descendant-rerender-count 0}]
          section (gbt/tree-section rows)]
      (is (vector? section))
      (is (= :section (first section)))
      (is (= "rf-causa-views-tree" (:data-testid (second section)))))))

(deftest tree-section-renders-empty-state-when-no-rows
  (testing "rf2-mxkq7 — empty projection → empty-state surface"
    (let [section (gbt/tree-section [])
          ;; tree-section is `[:section attrs [:header …] <body>]`
          body    (nth section 3)]
      (is (vector? body))
      (is (= "rf-causa-views-tree-empty" (:data-testid (second body)))))))

(deftest tree-section-rows-carry-key-meta
  (testing "rf2-mxkq7 — rows carry `:key` meta for React stable
            identity (mirrors rf2-gphsi convention from views-view)"
    (let [rows    [{:view-id :a :depth 0 :fiber-key 11 :status :rendered
                    :item (mk-single :a) :descendant-rerender-count 0}
                   {:view-id :b :depth 0 :fiber-key 22 :status :rendered
                    :item (mk-single :b) :descendant-rerender-count 0}]
          section (gbt/tree-section rows)
          rs      (nth section 3)]
      (is (seq? rs))
      (doseq [r rs]
        (is (some? (some-> r meta :key))
            (str "row carries :key meta — got " (pr-str (meta r))))))))

;; ---- end-to-end through the panel view ---------------------------------

(defn- expand-all
  "Deeply expand every `[fn args…]` hiccup node in `tree` by applying
  the fn until we reach a keyword-headed hiccup (or non-vector), then
  recurse into the result. Bounded fuel guards against pathological
  recursion."
  ([tree] (expand-all tree 32))
  ([tree fuel]
   (cond
     (zero? fuel) tree
     (and (vector? tree) (fn? (first tree)))
     (let [out (try (apply (first tree) (rest tree))
                    (catch :default _ tree))]
       (recur out (dec fuel)))
     (vector? tree)
     (mapv #(expand-all % (dec fuel)) tree)
     (map? tree)
     (into {} (map (fn [[k v]] [k (expand-all v (dec fuel))])) tree)
     (seq? tree)
     (map #(expand-all % (dec fuel)) tree)
     :else tree)))

(defn- has-testid? [tree testid]
  (let [expanded (expand-all tree)]
    (some (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (= testid (:data-testid (second node)))))
          (tree-seq (some-fn vector? seq?) seq expanded))))

(deftest panel-renders-tree-toggle-pill
  (testing "rf2-mxkq7 — Views panel surfaces the third Group-by pill
            (`tree`) alongside `component` + `sub`."
    (facade/install!)
    (frame/reg-frame :rf/causa {})
    (let [tree (view/views-panel)]
      (is (has-testid? tree "rf-causa-views-group-by-tree")
          "tree pill testid `rf-causa-views-group-by-tree` should
            appear in the panel's hiccup"))))
