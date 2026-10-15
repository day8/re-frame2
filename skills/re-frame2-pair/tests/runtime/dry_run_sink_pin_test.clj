(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns dry-run-sink-pin-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest dispatch-dry-run-refuses-effect-overrides
  (let [form (rt/defn-named 'dispatch-dry-run)]
    (is (rt/mentions? :fx-overrides-unsupported form)
        "dispatch-dry-run must refuse a caller :fx-overrides with :fx-overrides-unsupported")))

(let [{:keys [fail error]} (run-tests 'dry-run-sink-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
