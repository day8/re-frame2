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
  (:require [clojure.test :refer [deftest is use-fixtures]]
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

;; ---- an unresolved or malformed root :on target fails registration ------

(deftest root-on-bad-targets-are-rejected-at-registration
  (doseq [[label machine-id target expected]
          [["an unresolved KEYWORD target: the ex-data carries the target and attributes the failure to the machine root"
            :rf.root-on-tv/unresolved-kw :missing
            {:rf.error/id :rf.error/machine-unresolved-target :target :missing :state :rf/root}]
           ["an unresolved absolute-VECTOR target"
            :rf.root-on-tv/unresolved-vec [:a :nowhere]
            {:rf.error/id :rf.error/machine-unresolved-target}]
           ["a target that is neither keyword nor vector is malformed shape, not unresolved"
            :rf.root-on-tv/malformed {:target 42}
            {:rf.error/id :rf.error/machine-bad-target}]]]
    (let [data (ex-data (registration-throws? machine-id {:initial :a
                                                          :on      {:go target}
                                                          :states  {:a {}}}))]
      (is (= expected (select-keys data (keys expected)))
          (str label " — at registration, not just at dispatch")))))
