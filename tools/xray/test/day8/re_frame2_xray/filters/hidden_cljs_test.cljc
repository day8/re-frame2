(ns day8.re-frame2-xray.filters.hidden-cljs-test
  "Pure-data tests for the events-ribbon 'N events filtered out' message
  model. The ribbon reads `:hidden` and `:visible?`."
  (:require #?(:clj  [clojure.test :refer [are deftest testing]]
               :cljs [cljs.test    :refer-macros [are deftest testing]])
            [day8.re-frame2-xray.filters.hidden :as hidden]))

(deftest summary-reports-hidden-count-and-visibility
  (testing "N = raw − filtered visible rows; the message shows iff N > 0,
            including when the filters leave no row at all (an empty list
            with no message looks broken)"
    (are [raw filtered expected]
         (= expected (select-keys (hidden/summary raw filtered {}) [:hidden :visible?]))
      2 1 {:hidden 1 :visible? true}
      6 0 {:hidden 6 :visible? true}
      7 7 {:hidden 0 :visible? false})))
