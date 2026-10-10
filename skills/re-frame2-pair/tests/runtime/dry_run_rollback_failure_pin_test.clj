;;;; tests/runtime/dry_run_rollback_failure_pin_test.clj — when
;;;; `dispatch-dry-run`'s rollback fails, the live app-db IS mutated, and the
;;;; MCP tool routes isError on `(false? (:ok? result))`. So the
;;;; not-rolled-back arm must return `:ok? false :reason :rollback-failed`,
;;;; never a success that reads green over a mutated app. The successful
;;;; rollback is exercised against a real frame by the fixture's
;;;; runtime_dry_run_test.cljs; the failure arm needs a failing
;;;; `replace-frame-state!`, so bb pins its source shape.
;;;;
;;;; Run: bb tests/runtime/dry_run_rollback_failure_pin_test.clj

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-rollback-failure-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.walk :as walk]
            [runtime-support :as rt]))

(defn- else-arm-of-if-rolled-back
  "The `(assoc …)` failure arm of `(if rolled-back? <success> <failure>)`,
   as a key → value map."
  [form]
  (let [found (atom nil)]
    (walk/postwalk (fn [node]
                     (when (and (seq? node) (= 'if (first node)) (= 'rolled-back? (second node)))
                       (reset! found node))
                     node)
                   form)
    (let [else (nth @found 3 nil)]
      (when (and (seq? else) (= 'assoc (first else)))
        (apply hash-map (drop 2 else))))))

(deftest failed-rollback-reports-ok-false
  (let [form (rt/defn-named 'dispatch-dry-run)
        else (else-arm-of-if-rolled-back form)]
    (is (rt/mentions? 'rf/replace-frame-state! form)
        "dispatch-dry-run must attempt the rollback via rf/replace-frame-state!")
    (is (= [false :rollback-failed] ((juxt :ok? :reason) else))
        "the not-rolled-back arm must return :ok? false :reason :rollback-failed")))

(let [{:keys [fail error]} (run-tests 'dry-run-rollback-failure-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
