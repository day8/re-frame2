(ns re-frame.test-quiet-fixture-integrity-test
  "Reject lookup fixtures under both JVM fixture keys."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet.runner :as rf.test-quiet.runner]))

(def ^:private map-fixture
  {:before (fn []) :after (fn [])})

(defn- with-probe-ns
  "Remove the probe namespace before the runner checks all loaded fixtures."
  [ns-sym meta-key fixtures f]
  (create-ns ns-sym)
  (alter-meta! (the-ns ns-sym) assoc meta-key fixtures)
  (try
    (f (the-ns ns-sym))
    (finally
      (remove-ns ns-sym))))

(deftest a-map-fixture-is-named-under-either-key
  (doseq [meta-key [:clojure.test/each-fixtures :clojure.test/once-fixtures]]
    (with-probe-ns 'probe.map-fixture-ns meta-key (list map-fixture)
      (fn [ns-obj]
        (is (= [['probe.map-fixture-ns meta-key map-fixture]]
               (rf.test-quiet.runner/uncallable-fixtures [ns-obj])))))))
