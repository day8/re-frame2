(ns re-frame.test-quiet-fixture-integrity-test
  "Lookup fixtures cannot silently suppress a JVM namespace."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

(deftest a-map-fixture-is-named-under-once-key
  ;; :each is exercised by map-literal-fixture-refuses-the-run.
  (let [ns-sym 'probe.map-fixture-ns
        fixture {:before (fn []) :after (fn [])}
        ns-obj (create-ns ns-sym)]
    (try
      (alter-meta! ns-obj assoc :clojure.test/once-fixtures [fixture])
      (is (= [[ns-sym :clojure.test/once-fixtures fixture]]
             (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))
      (finally (remove-ns ns-sym)))))
