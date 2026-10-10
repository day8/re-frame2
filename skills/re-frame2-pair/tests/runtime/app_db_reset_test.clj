;;;; tests/runtime/app_db_reset_test.clj — `app-db-reset!` must write through
;;;; `rf/replace-frame-state!` (Tool-Pair §Pair-tool writes), the surface that
;;;; also appends the synthetic epoch `restore-epoch` rewinds past. That
;;;; surface's semantics are covered by implementation/epoch/test/re_frame/epoch_test.clj:
;;;;   replace-frame-state-app-only-replaces-container
;;;;   replace-frame-state-app-only-records-undo-epoch
;;;;   replace-frame-state-app-only-emits-trace
;;;;   replace-frame-state-app-only-fires-listeners
;;;;   replace-frame-state-app-only-failure-unknown-frame
;;;;   replace-frame-state-app-only-failure-during-drain
;;;;   replace-frame-state-app-only-failure-schema-mismatch
;;;; The preload is CLJS-only, so bb pins its source shape.
;;;;
;;;; Run: bb tests/runtime/app_db_reset_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns app-db-reset-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest app-db-reset-writes-through-replace-frame-state
  (let [form (rt/defn-named 'app-db-reset!)]
    (is (rt/calls? 'rf/replace-frame-state! form)
        "app-db-reset! must delegate to rf/replace-frame-state!, which records the epoch restore-epoch needs")
    (is (rt/calls? 'tap> form)
        "app-db-reset! must tap> the change so the human sees what the agent wrote (docs/capabilities.md guardrails)")
    (is (rt/form-contains? #(and (map? %) (= false (:ok? %)) (= :reset-rejected (:reason %))) form)
        "a rejected reset must return {:ok? false :reason :reset-rejected}, never a silent success")))

(let [{:keys [fail error]} (run-tests 'app-db-reset-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
