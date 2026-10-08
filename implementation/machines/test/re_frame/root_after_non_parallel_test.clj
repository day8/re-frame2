(ns re-frame.root-after-non-parallel-test
  "Spec 005 §Root-level `:after` scopes a root `:after` to the `:type :parallel`
  machine root, the only root the runtime schedules (pinned in
  `parallel_root_on_test.clj`). Registration rejects the unscheduled shapes
  with `:rf.error/machine-non-parallel-root-after-not-supported`: a flat or
  compound machine root's `:after`, and a parallel region body's own
  `:after`, whether hand-authored or lowered from a `:timeout` /
  `:on-timeout`. The ex-data carries the offending (lowered) `:after` map and,
  for a region, its name."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- registration-error
  "The ex-data of the error `machine` throws at registration, or nil when it
  registers cleanly."
  [machine]
  (try (rf/reg-machine (keyword "rf.root-after-np" (str (gensym "m"))) machine) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest non-parallel-root-after-fails-registration
  (doseq [[label machine expected]
          [["flat machine, hand-authored root :after"
            {:initial :a
             :after   {5000 {:target :b}}
             :states  {:a {} :b {}}}
            {:after {5000 {:target :b}}}]
           ["flat machine, root :timeout lowered onto :after"
            {:initial    :a
             :timeout    "PT5S"
             :on-timeout {:target :timed-out}
             :states     {:a {} :timed-out {}}}
            {:after {5000 {:target :timed-out}}}]
           ["parallel machine, region-root :after"
            {:type    :parallel
             :regions {:left  {:initial :a
                               :after   {5000 {:target :b}}
                               :states  {:a {} :b {}}}
                       :right {:initial :x
                               :states  {:x {}}}}}
            {:region :left :after {5000 {:target :b}}}]
           ["parallel machine, region-root :timeout lowered onto :after"
            {:type    :parallel
             :regions {:left {:initial    :a
                              :timeout    "PT5S"
                              :on-timeout {:target :b}
                              :states     {:a {} :b {}}}}}
            {:region :left :after {5000 {:target :b}}}]]]
    (let [expected (assoc expected :rf.error/id :rf.error/machine-non-parallel-root-after-not-supported)]
      (is (= expected (select-keys (registration-error machine) (keys expected))) label))))
