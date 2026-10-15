(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns registrar-describe-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [runtime-support :as rt]))

(deftest registrar-describe-strips-fns
  (let [form (rt/defn-named 'registrar-describe)]
    (is (rt/mentions? 'strip-fns form) "registrar-describe must run strip-fns over the meta map")
))

(let [{:keys [fail error]} (run-tests 'registrar-describe-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
