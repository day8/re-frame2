(ns re-frame.root-on-target-validation-test
  "rf2-nvgolg — a NON-parallel (flat / compound) machine root's own `:on` is
  the ANCESTOR-FALLBACK transition slot: per Spec 005 §Transition resolution
  steps 6-7, `pick-transition` consults it, stamped with decl-path `[]`,
  when no state-path node handles the event. Target resolution at that
  decl-path is exactly like a state's `:on` — a keyword resolves as a
  TOP-LEVEL sibling (`target-path`'s `(drop-last [])` → `[]`).

  BEFORE this fix, `validate-transition-targets!` walked ONLY nodes INSIDE
  `:states` (`walk-state-nodes-with-scope`) — the root's OWN `:on` was never
  checked. A machine like `{:initial :a :on {:go :missing} :states {:a
  {}}}` registered cleanly and would only surface `:rf.error/machine-
  unresolved-target` LATE, at the first dispatch that fell through to the
  root fallback and tried to commit the unresolved `:missing` state —
  rather than failing fast at registration like every other transition
  slot's target.

  This suite pins:
   1. an unresolved keyword root :on target fails at REGISTRATION with
      :rf.error/machine-unresolved-target (not later, at dispatch);
   2. an unresolved VECTOR root :on target fails the same way;
   3. a malformed-shape root :on target (neither keyword nor vector) fails
      with :rf.error/machine-bad-target;
   4. a VALID root :on target registers cleanly AND fires correctly at
      runtime (the ancestor-fallback semantics are unaffected);
   5. a :type :parallel root's :on is NOT double-validated here — its
      region-qualified shape is `validate-parallel!`'s job, unaffected by
      this addition."
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

;; ---- (1) unresolved KEYWORD root :on target --------------------------------

(deftest root-on-unresolved-keyword-target-rejected-at-registration
  (testing "a root :on keyword target naming no declared state fails registration"
    (let [m {:initial :a
             :on      {:go :missing}
             :states  {:a {}}}
          thrown (registration-throws? :rf.root-on-tv/unresolved-kw m)]
      (is (some? thrown) "an unresolved root :on target SHOULD fail registration, not just at dispatch")
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))
          "error category names the unresolved-target contract")
      (is (= :missing (:target (ex-data thrown)))
          "ex-data carries the offending target")
      (is (= :rf/root (:state (ex-data thrown)))
          "ex-data attributes the failure to the machine root"))))

;; ---- (2) unresolved VECTOR root :on target ---------------------------------

(deftest root-on-unresolved-vector-target-rejected-at-registration
  (testing "a root :on absolute-vector target naming no declared state fails registration"
    (let [m {:initial :a
             :on      {:go [:a :nowhere]}
             :states  {:a {}}}
          thrown (registration-throws? :rf.root-on-tv/unresolved-vec m)]
      (is (some? thrown))
      (is (= :rf.error/machine-unresolved-target (:rf.error/id (ex-data thrown)))))))

;; ---- (3) malformed-shape root :on target -----------------------------------

(deftest root-on-malformed-target-rejected-at-registration
  (testing "a root :on target that is neither keyword nor vector is malformed shape"
    (let [m {:initial :a
             :on      {:go {:target 42}}
             :states  {:a {}}}
          thrown (registration-throws? :rf.root-on-tv/malformed m)]
      (is (some? thrown))
      (is (= :rf.error/machine-bad-target (:rf.error/id (ex-data thrown)))
          "a non-keyword/non-vector target is malformed shape, not unresolved"))))

;; ---- (4) a VALID root :on target registers AND fires -----------------------

(deftest root-on-valid-target-registers-and-fires
  (testing "a root :on target naming a real top-level state registers cleanly and fires as the ancestor fallback"
    (let [m {:initial :a
             ;; :logout is declared on NEITHER :a nor :b — only the root
             ;; :on fallback handles it, the documented "common transition
             ;; every state inherits" use case (Spec 005 §Transition
             ;; resolution).
             :on      {:logout :signed-out}
             :states  {:a         {:on {:next :b}}
                       :b         {}
                       :signed-out {}}}]
      (rf/reg-machine :rf.root-on-tv/valid m)
      (rf/dispatch-sync [:rf.root-on-tv/valid [:next]])
      (is (= :b (:state (mtest/snapshot :rf.root-on-tv/valid)))
          "(precondition) local :on transition still works")
      (rf/dispatch-sync [:rf.root-on-tv/valid [:logout]])
      (is (= :signed-out (:state (mtest/snapshot :rf.root-on-tv/valid)))
          "the root :on ancestor fallback fired from ANY state, landing on the top-level target"))))

;; ---- (5) a parallel root's :on is unaffected (validate-parallel!'s job) ---

(deftest parallel-root-on-unaffected-by-this-check
  (testing "a :type :parallel root's region-qualified :on still validates via validate-parallel!, not this new check"
    (is (nil? (machines/validate-machine!
                {:type    :parallel
                 :on      {:go-all {:target [[:a :two] [:b :two]]}}
                 :regions {:a {:initial :one :states {:one {} :two {}}}
                           :b {:initial :one :states {:one {} :two {}}}}}))
        "a valid region-qualified root :on validates cleanly (unaffected by the non-parallel-only addition)")))
