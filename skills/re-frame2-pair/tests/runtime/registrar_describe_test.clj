(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns registrar-describe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest registrar-describe-strips-fns
  (is (rt/mentions? 'strip-fns (rt/defn-named 'registrar-describe))
      "nested handler functions must remain readable on the MCP wire"))

(let [{:keys [fail error]} (run-tests 'registrar-describe-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
