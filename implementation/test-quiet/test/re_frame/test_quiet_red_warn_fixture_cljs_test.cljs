(ns re-frame.test-quiet-red-warn-fixture-cljs-test
  "A CLJS fixture suite for the warning-buffer red-replay regression in
  `re-frame.test-quiet-shadow-node-cljs-test` (rf2-8dfq5j.2).

  The buffered-`console.warn` contract (quiet on green, replayed on red)
  can only be observed across a process boundary: the buffer is replayed
  from the `:end-run-tests` reporter just before `js/process.exit`, which
  would tear down the in-process runner.  So that regression spawns the
  REAL built `out/node-test.js` runner focused on this ns.

  This suite must NOT make the consolidated `npm run test:cljs`
  (`run-all-tests`) run RED — so its fail-and-warn behaviour is GATED on
  the `RF2_TQ_RED_WARN_FIXTURE` env var.  Unset (the default for the
  whole-suite run) it is trivially GREEN and emits nothing.  The
  process-level regression spawns the runner with that env var set, so
  ONLY then does the fixture emit a `console.warn` and fail — proving the
  withheld warning is replayed on the red exit while the warning stays
  quiet on every green run."
  (:require [cljs.test :refer-macros [deftest is]]))

(def ^:private armed?
  (boolean (some-> (.. js/process -env -RF2_TQ_RED_WARN_FIXTURE)
                   (= "1"))))

(deftest warns-then-fails-when-armed
  (when armed?
    ;; A stray runtime warning of exactly the class the green-path stub
    ;; withholds. It must survive to the RED output via the buffer replay.
    (js/console.warn "RED-WARN-FIXTURE-MARKER diagnostic context"))
  ;; GREEN unless armed: the consolidated whole-suite run keeps passing.
  (is (or (not armed?) (= :expected :actual))
      "armed fixture fails so the run is RED and the buffer replays"))
