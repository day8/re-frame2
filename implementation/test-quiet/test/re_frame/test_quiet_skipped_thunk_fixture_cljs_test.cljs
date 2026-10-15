(ns re-frame.test-quiet-skipped-thunk-fixture-cljs-test
  "An armed fixture discovers its test while skipping execution."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]))

(def ^:private skipped-thunk-flag "RF2_TQ_SKIPPED_THUNK_FIXTURE")

(defn- armed?
  "True when the fixture's environment variable is exactly \"1\"."
  []
  (= "1" (unchecked-get js/process.env skipped-thunk-flag)))

(use-fixtures :once
  (fn [run-tests]
    (if (armed?)
      (println "SKIPPED-THUNK-FIXTURE-ARMED")
      (run-tests))))

(deftest runs-unless-the-fixture-skips-it
  (is (not (armed?)) "the armed fixture must skip this test, not run it"))
