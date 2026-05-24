(ns day8.re-frame2-causa.panels.reactive-flow-graph-cljs-test
  "Pure-data tests for the reactive-flow graph layout (rf2-ad7zx.6 ·
  spec/021 §3.2 · Figma `ViewsPanel`).

  Covers `shared-sub-set` (the shared-subscription detector) and `layout`
  (the node + edge geometry the Views panel renders as inline SVG).
  JVM-runnable — no re-frame frame, no browser."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.reactive-flow-graph :as g]))

;; ---- shared-sub-set ----------------------------------------------------

(deftest shared-sub-set-detects-multi-reader-subs
  (testing "a sub read by ≥2 views is in the shared set; a 0/1-reader sub
            is not"
    (let [subs [{:sub-id :a :readers [:v1 :v2]}
                {:sub-id :b :readers [:v1]}
                {:sub-id :c :readers []}
                {:sub-id :d :readers [:v1 :v2 :v3]}]]
      (is (= #{:a :d} (g/shared-sub-set subs))))))

(deftest shared-sub-set-nil-safe
  (is (= #{} (g/shared-sub-set [])))
  (is (= #{} (g/shared-sub-set [{:sub-id :x}]))))

;; ---- layout: empty -----------------------------------------------------

(deftest layout-empty-when-no-cascade
  (testing "no subs + no views → :empty? true"
    (let [out (g/layout {})]
      (is (:empty? out))
      (is (= [] (-> out :nodes :l1)))
      (is (= [] (-> out :nodes :l2)))
      (is (= [] (-> out :nodes :view)))
      (is (= [] (:edges out))))))

(deftest layout-empty-with-only-unmount-rows
  (testing "view-rows that are all unmounts don't populate the graph"
    (let [out (g/layout {:view-rows [{:view-id :v :action :unmount}]})]
      (is (:empty? out))
      (is (= [] (-> out :nodes :view))))))

;; ---- layout: nodes -----------------------------------------------------

(deftest layout-builds-app-db-source-node
  (testing "app-db source node sits at the left edge with stable geometry"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}]})]
      (is (number? (-> out :appdb :x)))
      (is (number? (-> out :appdb :y)))
      (is (= g/node-h (-> out :appdb :h))))))

(deftest layout-columns-are-ordered-left-to-right
  (testing "app-db < L1 < L2 < view in x"
    (let [out (g/layout {:level-1-subs [{:sub-id :l1 :changed? true}]
                         :level-2-subs [{:sub-id :l2 :changed? true :inputs [:l1]}]
                         :view-rows    [{:view-id :v :action :rerender}]})
          l1x (-> out :nodes :l1 first :x)
          l2x (-> out :nodes :l2 first :x)
          vx  (-> out :nodes :view first :x)]
      (is (< (-> out :appdb :x) l1x))
      (is (< l1x l2x))
      (is (< l2x vx)))))

(deftest layout-preserves-changed-flags-on-nodes
  (testing "node :changed? mirrors the input row"
    (let [out (g/layout {:level-1-subs [{:sub-id :hot :changed? true}
                                        {:sub-id :cold :changed? false}]})
          nodes (-> out :nodes :l1)]
      (is (true? (:changed? (first nodes))))
      (is (false? (:changed? (second nodes)))))))

(deftest layout-view-node-carries-cause-and-timing
  (testing "rf2-8wrzz.1 — the view node threads :triggered-by + :elapsed-ms"
    (let [out (g/layout {:view-rows [{:view-id :v :action :rerender
                                      :triggered-by :sub/x :elapsed-ms 1.5}]})
          vn  (-> out :nodes :view first)]
      (is (= :sub/x (:triggered-by vn)))
      (is (= 1.5 (:elapsed-ms vn)))
      (is (= :rerender (:action vn))))))

(deftest layout-marks-shared-sub-count
  (testing "a sub read by ≥2 views carries :shared-count"
    (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true
                                         :readers [:v1 :v2]}]})
          n   (-> out :nodes :l1 first)]
      (is (= 2 (:shared-count n))))))

(deftest layout-no-shared-count-for-single-reader
  (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true :readers [:v1]}]})]
    (is (nil? (:shared-count (-> out :nodes :l1 first))))))

;; ---- layout: edges -----------------------------------------------------

(deftest layout-app-db-fans-out-to-each-level-1
  (testing "one app-db → L1 edge per Level-1 sub (plain fan-out)"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}
                                        {:sub-id :b :changed? false}]})
          appdb-edges (filter #(= :appdb-l1 (:kind %)) (:edges out))]
      (is (= 2 (count appdb-edges)))
      (is (every? #(= :appdb (:from-id %)) appdb-edges)))))

(deftest layout-app-db-edge-changed-tracks-target-sub
  (testing "the app-db→L1 edge :changed? mirrors the target sub's state
            (changed propagates, unchanged is cut)"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}
                                        {:sub-id :b :changed? false}]})
          by-to (into {} (map (juxt :to-id identity))
                      (filter #(= :appdb-l1 (:kind %)) (:edges out)))]
      (is (true? (:changed? (get by-to :a))))
      (is (false? (:changed? (get by-to :b)))))))

(deftest layout-level-2-edges-wire-from-input-subs
  (testing "a Level-2 sub draws an edge from each of its input subs;
            edge :changed? tracks the UPSTREAM input"
    (let [out (g/layout {:level-1-subs [{:sub-id :in :changed? true}]
                         :level-2-subs [{:sub-id :derived :changed? true
                                         :inputs [:in]}]})
          sub-sub (filter #(= :sub-sub (:kind %)) (:edges out))]
      (is (= 1 (count sub-sub)))
      (is (= :in (:from-id (first sub-sub))))
      (is (= :derived (:to-id (first sub-sub))))
      (is (true? (:changed? (first sub-sub)))))))

(deftest layout-sub-view-edges-from-readers
  (testing "a sub draws an edge to each view in its :readers; a shared
            sub fans out to N view edges"
    (let [out (g/layout {:level-1-subs [{:sub-id :s :changed? true
                                         :readers [:v1 :v2]}]
                         :view-rows    [{:view-id :v1 :action :rerender}
                                        {:view-id :v2 :action :rerender}]})
          sub-view (filter #(= :sub-view (:kind %)) (:edges out))]
      (is (= 2 (count sub-view)))
      (is (= #{:v1 :v2} (set (map :to-id sub-view)))))))

(deftest layout-edges-have-numeric-endpoints
  (testing "every edge carries numeric x1/y1/x2/y2 so the SVG paints"
    (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true :readers [:v]}]
                         :view-rows    [{:view-id :v :action :rerender}]})]
      (is (seq (:edges out)))
      (is (every? (fn [e] (every? number? [(:x1 e) (:y1 e) (:x2 e) (:y2 e)]))
                  (:edges out))))))

(deftest layout-width-and-height-positive
  (let [out (g/layout {:level-1-subs [{:sub-id :a :changed? true}]
                       :view-rows    [{:view-id :v :action :rerender}]})]
    (is (pos? (:width out)))
    (is (pos? (:height out)))))
