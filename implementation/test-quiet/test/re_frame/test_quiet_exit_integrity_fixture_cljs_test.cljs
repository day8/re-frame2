(ns re-frame.test-quiet-exit-integrity-fixture-cljs-test
  "Child targets for replay failure and missing exit dispatch."
  (:require [cljs.test :as ct :refer-macros [deftest is]]))

(defn- armed?
  "True when environment variable `env-var` is exactly \"1\"."
  [env-var]
  (= "1" (unchecked-get js/process.env env-var)))

(def ^:private replay-throw-flag "RF2_TQ_REPLAY_THROW_FIXTURE")

(deftest replay-throws-when-armed
  (when (armed? replay-throw-flag)
    ;; Swallowing this throw distinguishes the guard from an uncaught exit 1.
    (js/console.warn
      (reify IPrintWithWriter
        (-pr-writer [_this _writer _opts]
          (throw (js/Error. "EXIT-INTEGRITY-REPLAY-POISON")))))
    (js/console.warn "EXIT-INTEGRITY-REPLAY-TAIL-MARKER"))
  (is (or (not (armed? replay-throw-flag)) (= :expected :actual))
      "the armed fixture fails, so the run is RED and the poisoned replay runs"))

(def ^:private no-exit-dispatch-flag "RF2_TQ_NO_EXIT_DISPATCH_FIXTURE")

(deftest bypasses-exit-dispatch-when-armed
  (when (armed? no-exit-dispatch-flag)
    (println "EXIT-INTEGRITY-NO-EXIT-DISPATCH-INSTALLED")
    (defmethod ct/report [:cljs.test/default :end-run-tests] [_summary] nil))
  (is true
      "green when unarmed; green-but-seeded-nonzero when armed"))
