(ns re-frame.root-after-non-parallel-test
  "rf2-b6znpi — a non-parallel (flat / compound) machine root's `:after` has
  NO runtime scheduling / resolution path: `transition/schedule-root-after-
  fx` (the birth-time scheduler) is called ONLY from `parallel/run-initial-
  cascade`'s parallel branch, and there is no root resolver that would fire
  a flat root `:after` at the empty decl-path (`grammar/node-at` resolves
  an empty path to nil). BEFORE this fix, `timeout/validate-timeouts!` +
  `validate-after-delays!` happily accepted a well-formed root `:timeout` /
  `:after` on a flat/compound machine — it registered cleanly and its
  \"whole-machine deadline\" simply never fired, silently.

  Per the bead's two offered remedies (schedule it for real, OR reject it
  loud at registration), the operator-ruled fix is REJECT: Spec 005
  §Root-level `:after` scopes the feature to a `:type :parallel` root only,
  so accepting it on a flat/compound root was never a documented capability
  — only a validation gap. `validate-non-parallel-root-after!` closes it.

  This suite pins:
   1. a hand-authored root `:after` on a flat machine is rejected;
   2. a hand-authored root `:after` on a COMPOUND machine (nested `:states`)
      is rejected too;
   3. a root `:timeout` / `:on-timeout` on a flat machine — which LOWERS
      onto `:after` — is rejected via the SAME error category;
   4. a flat machine with NO root `:after` / `:timeout` is unaffected;
   5. a `:type :parallel` root's `:after` is UNAFFECTED (still the
      supported, scheduled, resolved feature)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- registration-throws?
  "Try registering `machine` under `machine-id`. Returns the ExceptionInfo
  if registration threw, else nil."
  [machine-id machine]
  (try (rf/reg-machine machine-id machine) nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---- (1) flat machine, hand-authored root :after ---------------------------

(deftest flat-root-after-rejected
  (testing "a flat machine's hand-authored root :after fails registration"
    (let [m {:initial :a
             :after   {5000 {:target :b}}
             :states  {:a {}
                       :b {}}}
          thrown (registration-throws? :rf.root-after-np/flat m)]
      (is (some? thrown) "a non-parallel root :after SHOULD fail registration")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))
          "error category names the non-parallel-root-after-not-supported contract")
      (is (= {5000 {:target :b}} (:after (ex-data thrown)))
          "ex-data carries the offending root :after map"))))

;; ---- (2) compound machine (nested :states), hand-authored root :after -----

(deftest compound-root-after-rejected
  (testing "a compound machine's hand-authored root :after fails registration too"
    (let [m {:initial :outer
             :after   {5000 {:target :outer}}
             :states  {:outer {:initial :inner
                               :states  {:inner {}}}}}
          thrown (registration-throws? :rf.root-after-np/compound m)]
      (is (some? thrown) "a compound machine is STILL non-parallel — root :after rejected")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))))))

;; ---- (3) flat machine, root :timeout / :on-timeout (lowers onto :after) ---

(deftest flat-root-timeout-rejected-via-lowered-after
  (testing "a flat machine's root :timeout / :on-timeout is rejected via its lowered :after form"
    (let [m {:initial    :a
             :timeout    "PT5S"
             :on-timeout {:target :timed-out}
             :states     {:a          {}
                          :timed-out {}}}
          thrown (registration-throws? :rf.root-after-np/timeout m)]
      (is (some? thrown) "a well-formed root :timeout on a flat machine SHOULD still fail registration")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))
          "the SAME error category catches both hand-authored :after and lowered :timeout")
      (is (= {5000 {:target :timed-out}} (:after (ex-data thrown)))
          "ex-data carries the LOWERED :after map (the desugared form)"))))

;; ---- (4) sanity: no root :after / :timeout — unaffected --------------------

(deftest flat-machine-without-root-after-registers-fine
  (testing "a flat machine with no root :after / :timeout registers and runs normally"
    (let [m {:initial :a
             :states  {:a {:on {:go :b}}
                       :b {}}}]
      (rf/reg-machine :rf.root-after-np/plain m)
      (rf/dispatch-sync [:rf.root-after-np/plain [:go]])
      (is (= :b (:state (mtest/snapshot :rf.root-after-np/plain)))
          "unaffected machine transitions normally"))))

;; ---- (5) sanity: a :type :parallel root's :after is UNAFFECTED ------------

(deftest parallel-root-after-still-registers
  (testing "a :type :parallel root's :after is NOT rejected — it is the supported feature"
    (is (nil? (machines/validate-machine!
                {:type    :parallel
                 :after   {1000 {:target [:a :two]}}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "a parallel root's :after validates cleanly — unaffected by the non-parallel rejection")))
