(ns re-frame.root-after-non-parallel-test
  "A non-parallel (flat/compound) machine root's `:after` has
  NO runtime scheduling / resolution path: `transition/schedule-root-after-
  fx` (the birth-time scheduler) is called ONLY from `parallel/run-initial-
  cascade`'s parallel branch, and there is no root resolver that would fire
  a flat root `:after` at the empty decl-path (`grammar/node-at` resolves
  an empty path to nil). Registration therefore rejects this shape. Spec 005
  §Root-level `:after` scopes the feature to a `:type :parallel` root only,
  so `validate-non-parallel-root-after!` enforces the supported scope.

  A parallel machine's REGION-ROOT `:after` (rf2-x76af2.10) has the SAME
  unscheduled shape — it sits on the region body itself, not on an entered
  leaf, and `bootstrap-step` / `schedule-root-after-fx` never reach it — so it
  is rejected with the SAME category, keeping the runtime honest (no
  accept-but-inert path) and consistent with the flat machine-root rejection.
  The machine's OWN parallel-root `:after` stays the one supported form.

  This suite pins:
   1. a hand-authored root `:after` on a flat machine is rejected;
   2. a hand-authored root `:after` on a COMPOUND machine (nested `:states`)
      is rejected too;
   3. a root `:timeout` / `:on-timeout` on a flat machine — which LOWERS
      onto `:after` — is rejected via the SAME error category;
   4. a flat machine with NO root `:after` / `:timeout` is unaffected;
   5. a `:type :parallel` root's `:after` is UNAFFECTED (still the
      supported, scheduled, resolved feature);
   6. a parallel machine's REGION-ROOT `:after` is rejected (rf2-x76af2.10);
   7. a region-root `:timeout` / `:on-timeout` (lowered onto `:after`) is
      rejected via the SAME error category too."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
      (is (= :b (:state (rf.machines.test-support/snapshot :rf.root-after-np/plain)))
          "unaffected machine transitions normally"))))

;; ---- (5) sanity: a :type :parallel root's :after is UNAFFECTED ------------

(deftest parallel-root-after-still-registers
  (testing "a :type :parallel root's :after is NOT rejected — it is the supported feature"
    (is (nil? (rf.machines/validate-machine!
                {:type    :parallel
                 :after   {1000 {:target [:a :two]}}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "a parallel root's :after validates cleanly — unaffected by the non-parallel rejection")))

;; ---- (6) region-root :after on a :type :parallel machine — rejected --------
;;
;; rf2-x76af2.10: a REGION-ROOT :after (on the region body itself, decl-path []
;; WITHIN the region) has the same unscheduled shape as a flat machine-root
;; :after — bootstrap-step schedules only the region's entered leaves, and
;; schedule-root-after-fx schedules only the MACHINE root's own :after — so it
;; registered-but-never-fired. Reject it for consistency with the flat-root
;; rule (a region body is structurally a flat/compound mini-machine). Pins the
;; ALREADY-DESUGARED :after shape (distinct root cause from rf2-x76af2.7's
;; desugar-cache miss).

(deftest region-root-after-rejected
  (testing "a parallel machine's region-root :after fails registration"
    (let [m {:type    :parallel
             :regions {:left {:initial :a
                             :after   {5000 {:target :b}}
                             :states  {:a {} :b {}}}
                       :right {:initial :x
                              :states  {:x {}}}}}
          thrown (registration-throws? :rf.root-after-np/region m)]
      (is (some? thrown) "a region-root :after SHOULD fail registration")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))
          "the SAME error category catches the region-root :after")
      (is (= :left (:region (ex-data thrown)))
          "ex-data names the offending region")
      (is (= {5000 {:target :b}} (:after (ex-data thrown)))
          "ex-data carries the offending region-root :after map"))))

;; ---- (7) region-root :timeout / :on-timeout (lowers onto :after) — rejected

(deftest region-root-timeout-rejected-via-lowered-after
  (testing "a region-root :timeout / :on-timeout is rejected via its lowered :after form"
    (let [m {:type    :parallel
             :regions {:left {:initial    :a
                             :timeout    "PT5S"
                             :on-timeout {:target :b}
                             :states     {:a {} :b {}}}}}
          thrown (registration-throws? :rf.root-after-np/region-timeout m)]
      (is (some? thrown) "a well-formed region-root :timeout SHOULD still fail registration")
      (is (= :rf.error/machine-non-parallel-root-after-not-supported
             (:rf.error/id (ex-data thrown)))
          "the SAME category catches both hand-authored region-root :after and lowered :timeout")
      (is (= {5000 {:target :b}} (:after (ex-data thrown)))
          "ex-data carries the LOWERED region-root :after map (the desugared form)"))))

;; ---- (8) sanity: a region STATE-level :after is UNAFFECTED -----------------
;;
;; The rejection is scoped to the region ROOT only — an :after on a region's
;; STATE node (entered leaf) is scheduled normally and MUST still register.

(deftest region-state-after-still-registers
  (testing "an :after on a region STATE (not the region root) is unaffected"
    (is (nil? (rf.machines/validate-machine!
                {:type    :parallel
                 :regions {:left {:initial :a
                                 :states  {:a {:after {5000 {:target :b}}}
                                           :b {}}}}}))
        "a region STATE-level :after validates cleanly — only the region ROOT is rejected")))
