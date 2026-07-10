(ns re-frame.test-quiet-green-fixture-cljs-test
  "A deliberately minimal, always-GREEN CLJS suite used as the focused
  target for the process-level quiet-shape regression in
  `re-frame.test-quiet-shadow-node-cljs-test`.

  The quiet-shape regression spawns the REAL built `out/node-test.js`
  shadow-node entry point with `--test=<this-ns>` and asserts the green
  stdout collapses to exactly the canonical summary — so this suite must
  stay trivially green and emit NOTHING of its own (no `println`, no
  warnings).  Keep it that way: any output here would be a false
  positive for the regression."
  (:require [cljs.test :refer-macros [deftest is]]))

(deftest a-passing-test
  (is (= 1 1)))
