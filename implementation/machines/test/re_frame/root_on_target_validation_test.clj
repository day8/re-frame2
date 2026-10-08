(ns re-frame.root-on-target-validation-test
  "A non-parallel machine root's own `:on` is the ancestor-fallback transition
  slot (Spec 005 §Transition resolution, decl-path `[]`), and
  `validate-transition-targets!` checks its targets at REGISTRATION: a keyword
  resolves as a top-level sibling. A firing root fallback is pinned by the
  `machine-root-on-fallback` conformance fixture; a parallel root's
  region-qualified `:on` is pinned in `parallel_root_on_test.clj`."
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
