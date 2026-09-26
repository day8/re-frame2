(ns re-frame.root-after-non-parallel-test
  "A non-parallel (flat/compound) machine root's `:after` has
  NO runtime scheduling / resolution path: `transition/schedule-root-after-
  fx` (the birth-time scheduler) is called ONLY from `parallel/run-initial-
  cascade`'s parallel branch, and there is no root resolver that would fire
  a flat root `:after` at the empty decl-path (`grammar/node-at` resolves
  an empty path to nil). Registration therefore rejects this shape. Spec 005
  §Root-level `:after` scopes the feature to a `:type :parallel` root only,
  so `validate-non-parallel-root-after!` enforces the supported scope.

  A parallel machine's REGION-ROOT `:after` has the SAME
  unscheduled shape — it sits on the region body itself, not on an entered
  leaf, and `bootstrap-step` / `schedule-root-after-fx` never reach it — so it
  is rejected with the SAME category, keeping the runtime honest (no
  accept-but-inert path) and consistent with the flat machine-root rejection.
  The machine's OWN parallel-root `:after` stays the one supported form
  (pinned in `parallel_root_on_test.clj`), and a region STATE's `:after` is
  scheduled normally.

  This suite pins that each of these fails registration with the same
  category, its ex-data carrying the offending (for `:timeout`, the lowered)
  `:after` map and, for a region, the region name:
   - a hand-authored root `:after` on a flat machine;
   - a hand-authored root `:after` on a COMPOUND machine (nested `:states`);
   - a root `:timeout` / `:on-timeout` on a flat machine, which LOWERS onto
     `:after`;
   - a parallel machine's REGION-ROOT `:after`;
   - a region-root `:timeout` / `:on-timeout` (lowered onto `:after`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
           ["compound machine, hand-authored root :after"
            {:initial :outer
             :after   {5000 {:target :outer}}
             :states  {:outer {:initial :inner
                               :states  {:inner {}}}}}
            {:after {5000 {:target :outer}}}]
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
    (testing label
      (let [data (registration-error machine)]
        (is (= :rf.error/machine-non-parallel-root-after-not-supported (:rf.error/id data)))
        (is (= expected (select-keys data (keys expected))))))))
