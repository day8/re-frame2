(ns re-frame.test-quiet-skipped-thunk-fixture-cljs-test
  "Fault fixture for the EXECUTED-test floor in
  `re-frame.test-quiet.shadow-node`: a run is green only if it executed at
  least its floor of tests, not merely discovered them.

  Armed, this namespace's `:once` fixture never calls its thunk, so its one
  test var is discovered — a `--test=` selector naming this namespace
  matches it — yet never runs, and `cljs.test` closes the run with a clean
  `Ran 0 tests` tally. Only the executed count can tell that apart from a
  pass.

  GATED on its own env var like the other fault fixtures beside it. Unset —
  the default for the consolidated whole-suite run — the fixture runs the
  test, which passes. `re-frame.test-quiet-shadow-node-cljs-test` spawns the
  REAL built `out/node-test.js` runner focused on this namespace with the
  flag set."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]))

(def ^:private skipped-thunk-flag "RF2_TQ_SKIPPED_THUNK_FIXTURE")

(defn- armed?
  "True when the fixture's environment variable is exactly \"1\"."
  []
  (= "1" (unchecked-get js/process.env skipped-thunk-flag)))

(use-fixtures :once
  (fn [run-tests]
    (if (armed?)
      ;; A reached-state marker: a child that exits nonzero without printing
      ;; it never got as far as skipping anything.
      (println "SKIPPED-THUNK-FIXTURE-ARMED")
      (run-tests))))

(deftest runs-unless-the-fixture-skips-it
  ;; Fails if it runs while armed, so a fixture that stopped skipping cannot
  ;; pass for the floor doing its job.
  (is (not (armed?)) "the armed fixture must skip this test, not run it"))
