(ns re-frame.root-on-target-validation-test
  "A non-parallel (flat/compound) machine root's own `:on` is
  the ANCESTOR-FALLBACK transition slot: per Spec 005 §Transition resolution
  steps 6-7, `pick-transition` consults it, stamped with decl-path `[]`,
  when no state-path node handles the event. Target resolution at that
  decl-path is exactly like a state's `:on` — a keyword resolves as a
  TOP-LEVEL sibling (`target-path`'s `(drop-last [])` → `[]`).

  `validate-transition-targets!` checks this root slot as well as nodes under
  `:states`, so malformed or unresolved targets fail at registration.

  This suite pins:
   1. an unresolved keyword root :on target fails at REGISTRATION with
      :rf.error/machine-unresolved-target (not later, at dispatch);
   2. an unresolved VECTOR root :on target fails the same way;
   3. a malformed-shape root :on target (neither keyword nor vector) fails
      with :rf.error/machine-bad-target.

  A valid root :on target registering and firing as the ancestor fallback is
  pinned in `machine_root_on_fallback_test.clj`; a :type :parallel root's
  region-qualified :on is `validate-parallel!`'s job, pinned in
  `parallel_root_on_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
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
