(ns day8.re-frame2-causa.panels.subscriptions-projection-cljs-test
  "Per-leaf smoke test for `subscriptions-projection` (rf2-nb8if).

  Pure CLJC; JVM-runnable. Existing aggregate coverage lives in
  `subscriptions_helpers_cljs_test.cljc` against the helpers
  facade; this file pins the projection leaf as an independently
  callable unit so a facade rewrite that drops a re-export is
  caught at the leaf level."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-causa.panels.subscriptions-projection
             :as projection]))

(deftest compute-status-classifies-error
  (is (= :error
         (projection/compute-status {:ref-count 1} nil true))))

(deftest project-rows-returns-row-per-cache-entry
  (let [rows (projection/project-rows
               {[:cart/total] {:layer 1 :ref-count 1 :input-subs []}}
               []
               {})]
    (is (= 1 (count rows)))
    (is (= [:cart/total] (:query-v (first rows))))))

(deftest status-counts-tallies-frequencies
  (is (= {:fresh 2}
         (projection/status-counts
           [{:status :fresh} {:status :fresh}]))))

(deftest filter-by-status-restricts-to-keep-set
  (is (= [{:status :error}]
         (projection/filter-by-status
           [{:status :error} {:status :fresh}]
           #{:error}))))
