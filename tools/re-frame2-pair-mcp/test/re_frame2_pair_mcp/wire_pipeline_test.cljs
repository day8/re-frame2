(ns re-frame2-pair-mcp.wire-pipeline-test
  "`:elided-large` indicator counting in `wire-pipeline/run-wire-pipeline`.
  The `:epoch-vector` arm counts markers over the PRE-dedup payload,
  because dedup pools N identical `:rf.size/large-elided` maps into one
  cache entry. The `:snapshot-map` arm counts the markers it ships."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(defn- large-marker [path]
  {:rf.size/large-elided
   {:path path :bytes 102400 :type :string
    :handle [:rf.elision/at path]}})

(defn- epoch-with-marker
  "`:db-after` equals `:db-before`, so the diff is empty and the marker
  survives only on `:db-before`; records built from one marker are equal
  there, which is what makes dedup pool them."
  [n marker]
  {:epoch-id  n
   :event-id  (keyword (str "ev" n))
   :db-before {:slot marker}
   :db-after  {:slot marker}})

(deftest epoch-vector-counts-all-elided-markers-pre-dedup
  (let [elided (fn [epochs]
                 (-> (wp/run-wire-pipeline epochs {:kind :epoch-vector :incl? false
                                                   :mode :diff :dedup? true})
                     :indicators :elided))]
    (is (= 3 (elided (vec (for [n (range 3)] (epoch-with-marker n (large-marker [:slot]))))))
        "every one of 3 identical markers is counted, not pooled to 1 by dedup")
    (is (= 0 (elided [{:epoch-id 1 :event-id :ev1 :db-before {:a 1} :db-after {:a 2}}
                      {:epoch-id 2 :event-id :ev2 :db-before {:a 2} :db-after {:a 3}}])))))

;; The snapshot eval form counts markers over the whole walked state, but
;; the path slice and the summary pass remove markers. Each case passes
;; the `:server-elided` figure the eval form would report, so it is red on
;; a tree that takes that figure verbatim.

(def ^:private docs-marker (large-marker [:docs :body]))

(def ^:private declared-db
  {:docs {:body docs-marker} :user {:id 7 :name "ann"}})

(defn- snapshot-count
  [snap overrides]
  (-> (wp/run-wire-pipeline snap (merge {:kind        :snapshot-map
                                         :incl?       false
                                         :mode        :diff
                                         :dedup?      true
                                         :slice-mode  :summary
                                         :slice-modes {}}
                                        overrides))
      :indicators :elided))

(deftest snapshot-summary-mode-counts-no-marker-it-does-not-ship
  (testing "a summarised :app-db ships no marker"
    (is (= 0 (snapshot-count {:rf/default {:app-db declared-db}}
                             {:server-elided 1}))))
  (testing "a summarised :sub-cache ships no marker"
    (is (= 0 (snapshot-count {:rf/default {:app-db {} :sub-cache {[:doc] {:value docs-marker}}}}
                             {:server-elided 1})))))

(deftest snapshot-path-slice-counts-only-the-addressed-subtree
  (is (= 0 (snapshot-count {:rf/default {:app-db declared-db}}
                           {:path [:user] :server-elided 1})))
  (is (= 1 (snapshot-count {:rf/default {:app-db declared-db}}
                           {:path [:docs] :server-elided 1}))
      "control: a path whose subtree carries the marker counts it"))

(deftest snapshot-full-epochs-are-counted-pre-dedup
  (let [epochs (vec (for [n (range 3)] (epoch-with-marker n (large-marker [:slot]))))]
    (is (= 3 (snapshot-count {:rf/default {:app-db {} :epochs epochs}}
                             {:slice-modes {:epochs :full}})))
    (is (= 0 (snapshot-count {:rf/default {:app-db {} :epochs epochs}} {}))
        "control: a summarised :epochs slice ships no marker")))
