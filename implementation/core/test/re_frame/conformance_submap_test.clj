(ns re-frame.conformance-submap-test
  "The conformance matchers compare a partial expectation submap-wise at every
  depth. A fixture names only the keys it cares about, so a nested map such as
  a trace's `:tags {:context {...}}` must tolerate keys the fixture leaves out
  however deep they sit, while a value the fixture DOES name still has to
  match. An expected `nil` matches a `nil` or an absent key.

  Both order-preserving matchers are pinned here: the shared trace matcher
  `re-frame.conformance/check-trace-emissions` and the core corpus runner's
  `:error-emit-records` matcher."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.conformance :as rf.conformance]
            [re-frame.conformance-runner :as rf.conformance-runner]))

(def ^:private check-error-emit-records
  @#'rf.conformance-runner/check-error-emit-records)

(defn- nested-cases
  "Assert the shared nested-match contract through `check`, an
  `(fn [actual expected] failures)` order-preserving matcher."
  [check]
  (let [actual [{:operation :op/a :tags {:context {:id 1 :extra 2}}}
                {:operation :op/b :tags {:context {:id 7 :nested {:k 1 :more 3}}}}]]
    (testing "an extra actual key at depth 2 is tolerated"
      (is (empty? (check actual [{:tags {:context {:id 1}}}]))))

    (testing "an extra actual key at depth 3 is tolerated"
      (is (empty? (check actual [{:tags {:context {:nested {:k 1}}}}]))))

    (testing "a mismatched value at depth 2 still fails"
      (is (= 1 (count (check actual [{:tags {:context {:id 2}}}])))))

    (testing "a mismatched value at depth 3 still fails"
      (is (= 1 (count (check actual [{:tags {:context {:nested {:k 2}}}}])))))

    (testing "matching stays order-preserving"
      (is (empty? (check actual [{:operation :op/a} {:operation :op/b}])))
      (is (seq (check actual [{:operation :op/b} {:operation :op/a}])))))

  (testing "an expected nil matches a nil or an absent key, at any depth"
    (is (empty? (check [{:tags {:context {:id nil}}}] [{:tags {:context {:id nil}}}])))
    (is (empty? (check [{:tags {:context {}}}]        [{:tags {:context {:id nil}}}])))
    (is (empty? (check [{:operation :op/a}]           [{:operation :op/a :tags nil}])))
    (is (seq    (check [{:tags {:context {:id 1}}}]   [{:tags {:context {:id nil}}}])))))

(deftest trace-emissions-match-nested-maps-submap-wise
  (nested-cases rf.conformance/check-trace-emissions))

(deftest error-emit-records-match-nested-maps-submap-wise
  (nested-cases check-error-emit-records))
