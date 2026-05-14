(ns re-frame.test-quiet-pin-passing-test
  "Tiny known-green sub-suite used by the silent-on-success pin in
  `re-frame.test-quiet-pin-test`.  A single trivially-passing
  assertion — the canonical 'Ran 1 tests containing 1 assertions'
  shape.

  Lives in its own namespace so the pin's `run-tests` call exercises
  exactly this one deftest (and only the reporter overrides
  installed by requiring `re-frame.test-quiet`)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet]))

(deftest trivially-passing
  (is (= 1 1)))
