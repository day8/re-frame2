(ns re-frame.machine-lifecycle-schema-incarnation-fence-test
  "The completion-output schema diagnostic is fenced to the exact frame
  incarnation: a validator that destroys the incarnation A whose owner is bound
  and publishes a same-id successor B gets no diagnostic attributed to B. The
  destroyer runs on the callback's own stack, so the fixture is deterministic
  and single-threaded."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation (`re-frame.machines` require has side effects).
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- completion-output finalize diagnostic fence --------------------------

(deftest completion-output-validator-honors-exact-continuation
  (testing "a completion-output validator that destroys A, publishes same-id B
            and reports a violation emits no :where :machine-output diagnostic —
            it would otherwise be attributed to B"
    (let [id      :rf2-vxgfnd153/completion-frame
          traces  (atom [])
          orig    (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id id})
      (let [token-a (rf.frame/frame-incarnation-token id)]
        (rf/register-listener! :trace ::completion-fence
          (fn [ev] (when (and (= :rf.error/schema-validation-failure (:operation ev))
                              (= :machine-output (get-in ev [:tags :where])))
                     (swap! traces conj ev))))
        (try
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
            (fn [schema _result]
              (when (= schema ::output-schema)
                (rf.frame/destroy-frame! id)
                (rf/make-frame {:id id}))
              false))
          (rf.frame/call-with-event-owner-token id token-a
            (fn []
              (rf.machines.data-validation/validate-completion-output!
                :rf2-vxgfnd153/completion-actor
                {:schemas {:output ::output-schema}}
                "bad-output")))
          (is (empty? @traces))
          (finally
            (rf/unregister-listener! :trace ::completion-fence)
            (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)))))))
