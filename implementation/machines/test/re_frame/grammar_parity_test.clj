(ns re-frame.grammar-parity-test
  "Registration's verdict on transition targets: a malformed shape (an empty
  vector included) is `:rf.error/machine-bad-target`, a well-formed path naming
  no state is `:rf.error/machine-unresolved-target`, and inside a parallel
  region a vector target resolves within that region's scope only. The shared
  form recogniser `candidate-targets` is pinned directly, because no machine in
  the suite carries an action-only candidate in a candidate vector."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.grammar :as rf.machines.grammar]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-error-id
  "Register `machine` and return the thrown `:rf.error/id` discriminator,
  or nil if registration succeeded."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest candidate-targets-present-marker
  (is (= [[]
          [{:present? true :target :authed}]
          [{:present? true :target [:a :b]}]
          [{:present? true :target :x} {:present? false :target nil}]
          [{:present? false :target nil}]]
         (mapv rf.machines.grammar/candidate-targets
               [nil :authed [:a :b] [{:target :x} {:action :a}] {:action :a}]))))

(deftest malformed-target-shape-rejected-at-registration
  (doseq [[target expected] [[42 :rf.error/machine-bad-target]
                             [[] :rf.error/machine-bad-target]
                             [[:missing] :rf.error/machine-unresolved-target]]]
    (is (= expected (registration-error-id (keyword "bad" (str (gensym)))
                                           {:initial :idle
                                            :states  {:idle {:on {:go {:target target}}}}}))
        (pr-str target))))

(deftest parallel-region-scope-parity
  (let [machine (fn [target] {:type    :parallel
                              :regions {:r1 {:initial :a
                                             :states  {:a {:on {:go target}}
                                                       :b {}}}
                                        :r2 {:initial :x
                                             :states  {:x {}}}}})]
    (testing "a vector target inside a parallel region resolves within that region's scope"
      (is (nil? (registration-error-id :par/ok (machine [:b])))))
    (testing "a target that resolves only in a sibling region is rejected"
      (is (= :rf.error/machine-unresolved-target
             (registration-error-id :par/bad (machine [:x])))))))
