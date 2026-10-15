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
    (is (= [false :rollback-failed] ((juxt :ok? :reason) else))
        "the not-rolled-back arm must return :ok? false :reason :rollback-failed")))

(let [{:keys [fail error]} (run-tests 'dry-run-rollback-failure-pin-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
