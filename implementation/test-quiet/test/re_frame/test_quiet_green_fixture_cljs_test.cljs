(ns re-frame.test-quiet-green-fixture-cljs-test
  "A deliberately minimal, always-GREEN CLJS suite used as the focused
  target for the process-level quiet-shape and `--test=` selection
  regressions in `re-frame.test-quiet-shadow-node-cljs-test`.

  It carries exactly TWO passing tests, and the count is load-bearing.  The
  namespace selector `--test=<this-ns>` must run BOTH (`Ran 2 tests`) while
  the qualified selector `--test=<this-ns>/a-passing-test` must run exactly
  ONE (`Ran 1 tests`); that difference is the only end-to-end proof that the
  runner's simple-symbol and qualified-symbol selector branches are distinct,
  and a one-test namespace makes them indistinguishable (rf2-6r9j.76).
  Adding or removing a test here moves both counts.

  The quiet-shape regression additionally asserts the green stdout collapses
  to exactly the canonical summary, so this suite must emit NOTHING of its
  own (no `println`, no warnings).  Keep it that way: any output here would
  be a false positive for that regression."
  (:require [cljs.test :refer-macros [deftest is]]))

(deftest a-passing-test
  (is (= 1 1)))

(deftest another-passing-test
  ;; The second var: selected by the namespace selector, NOT by
  ;; `--test=<this-ns>/a-passing-test`.
  (is (= 2 2)))
