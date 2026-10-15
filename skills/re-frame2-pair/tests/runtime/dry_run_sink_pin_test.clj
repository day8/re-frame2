(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-sink-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest dispatch-dry-run-refuses-effect-overrides
  (is (rt/mentions? :fx-overrides-unsupported (rt/defn-named 'dispatch-dry-run))
      "effect overrides must be refused instead of silently ignored"))

(let [{:keys [fail error]} (run-tests 'dry-run-sink-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
