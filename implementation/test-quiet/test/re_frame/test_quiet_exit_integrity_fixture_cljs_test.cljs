(ns re-frame.test-quiet-exit-integrity-fixture-cljs-test
  "Fault fixtures for the two EXIT-CODE INTEGRITY safeguards in
  `re-frame.test-quiet.shadow-node` (see the comment above its
  `:end-run-tests` defmethod).  Both exist so a red or torn-down run can
  never drain to exit 0, and NEITHER is reachable from an ordinary red run —
  an ordinary red already exited 1 before either safeguard existed.  So each
  needs a run that deliberately enters the failure mode it guards
  (rf2-6r9j.89).

  Like the warning-replay fixture beside it, each fault is GATED on its own
  env var.  Unset — the default for the consolidated whole-suite run — both
  tests here are trivially green and emit nothing.  The regressions in
  `re-frame.test-quiet-shadow-node-cljs-test` spawn the REAL built
  `out/node-test.js` runner focused on this ns with exactly one flag set.

  Each armed fixture emits a distinctive REACHED-STATE marker before it
  breaks anything, because a nonzero child status on its own proves nothing:
  a spawn error, a timeout, a parse error, an unrelated uncaught exception
  and a skipped suite are all nonzero too."
  (:require [cljs.test :as ct :refer-macros [deftest is]]))

(defn- armed?
  "True when environment variable `env-var` is exactly \"1\"."
  [env-var]
  (= "1" (unchecked-get js/process.env env-var)))

;; ----------------------------------------------------------------------
;; Fault 1 — the red warning replay itself throws.
;;
;; `replay-buffered-warnings!` is diagnostic-only, so the `:end-run-tests`
;; defmethod wraps it: a throw there must never pre-empt `js/process.exit 1`.
;; Nothing about an ordinary red run makes the replay throw, so this fixture
;; buffers a warning whose PRINTING throws — the replay renders buffered
;; values through `println`, and a value whose `-pr-writer` throws aborts the
;; replay part-way.  A plain `js/Error` (not `ex-info`) carries the marker so
;; that, with the guard REMOVED, the escaping exception's message is printed
;; by node's uncaught handler: the regression discriminates the guarded case
;; from the unguarded one by that message being ABSENT.  Status cannot
;; discriminate — an uncaught exception exits 1 as well.
;;
;; A second, ordinary warning is buffered AFTER the poison: the replay's own
;; header reaches stderr, that tail marker never does, which is how the
;; regression tells "entered and aborted" from "completed".

(def ^:private replay-throw-flag "RF2_TQ_REPLAY_THROW_FIXTURE")

(deftest replay-throws-when-armed
  (when (armed? replay-throw-flag)
    ;; Buffered FIRST, so the replay hits it before the tail marker.
    (js/console.warn
      (reify IPrintWithWriter
        (-pr-writer [_this _writer _opts]
          (throw (js/Error. "EXIT-INTEGRITY-REPLAY-POISON")))))
    (js/console.warn "EXIT-INTEGRITY-REPLAY-TAIL-MARKER"))
  ;; GREEN unless armed: the consolidated whole-suite run keeps passing.
  (is (or (not (armed? replay-throw-flag)) (= :expected :actual))
      "the armed fixture fails, so the run is RED and the poisoned replay runs"))

;; ----------------------------------------------------------------------
;; Fault 2 — the run never dispatches the exit defmethod.
;;
;; Reaching a green `:end-run-tests` is the ONLY path that clears the
;; `process.exitCode = 1` that `execute-cli` seeds before a run.  A run that
;; executes tests but never dispatches the runner's defmethod — a torn-down
;; async run, or a reporter env that does not match the dispatch key — must
;; drain to that seeded 1 rather than a silent false green.  This fixture
;; reproduces it by replacing the defmethod with a no-op IN THIS CHILD
;; PROCESS ONLY (the swap is inside an env-gated test body, so it never runs
;; in the whole-suite build), then passing: every test is green, nothing
;; calls `js/process.exit`, and the seed is the only thing left that can make
;; the child exit nonzero.

(def ^:private no-exit-dispatch-flag "RF2_TQ_NO_EXIT_DISPATCH_FIXTURE")

(deftest bypasses-exit-dispatch-when-armed
  (when (armed? no-exit-dispatch-flag)
    ;; Printed BEFORE the swap, so a child that never got here is
    ;; distinguishable from one that did.
    (println "EXIT-INTEGRITY-NO-EXIT-DISPATCH-INSTALLED")
    (defmethod ct/report [:cljs.test/default :end-run-tests] [_summary] nil))
  (is true
      "green when unarmed; green-but-seeded-nonzero when armed"))
