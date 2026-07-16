(ns re-frame.ui.reactive-flush-convergence-bound-cljs-test
  "rf2-0faipl — the SHARED bounded flush-convergence law
  (`re-frame.ui.reactive/converge-flush!` + `flush-convergence-budget` +
  `flush-nonconvergence!`).

  A synchronous forcing caller (the first-party adapter's render-commit; the
  test all-roots flush) drains the ViewCell registry then RE-DRAINS to a fixed
  point, because a commit-triggered re-dirty — a layout effect that dispatches,
  a `useSyncExternalStore` listener that re-marks — can enrol a cell AFTER the
  pass that flushed it. That re-drain must TERMINATE. The single-pass ambient
  guards (the router's `:rf.error/drain-depth-exceeded`, React's
  maximum-update-depth) bound ONE synchronous pass and cannot see a re-enrolment
  landing across two SEPARATE `flushSync` passes, so before this bound an
  unstable commit->re-dirty cycle could spin the synchronous forcing call
  forever. These fixtures drive the driver directly with a re-marking listener
  (the exact non-quiescence vector) and pin:

    - NO-OP SAFETY — a quiescent registry loops zero times, never touching the
      pass thunk;
    - ORDINARY CONVERGENCE — a one-shot AND a fixed-N commit-triggered re-dirty
      both drain to zero pending without tripping the bound;
    - THE BOUND — a listener that re-marks EVERY pass trips the budget and fails
      loud with the typed `:rf.error/flush-convergence-exceeded` diagnostic
      carrying `:passes` (the exhausted budget) + `:pending` (the residual);
    - THE SHARED DIAGNOSTIC — `flush-nonconvergence!` is the one typed throw both
      the synchronous loop and the CLJS async `ui.test/flush!` cycle rest on.

  `.cljc` ending `-cljs-test` graft-checks on node (`test:cljs` / `test:ui`)
  AND JVM (`clojure -M:test`), so the bound holds on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private where 're-frame.ui.substrate/flush-render!)

(defn- ex-of
  "Run `thunk`; return the thrown ExceptionInfo (or nil if it returned)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e)))

;; ===========================================================================
;; No-op safety — a quiescent registry never touches the pass thunk
;; ===========================================================================

(deftest quiescent-registry-runs-zero-passes
  (reactive/reset-scheduler!)
  (let [passes (atom 0)]
    (is (nil? (reactive/converge-flush! where (fn [] (swap! passes inc))))
        "converge-flush! returns nil")
    (is (= 0 @passes)
        "nothing pending on entry -> the pass thunk is never invoked (no-op safe)")
    (is (= 0 (reactive/pending-cell-count)))))

;; ===========================================================================
;; Ordinary convergence — a one-shot commit-triggered re-dirty drains cleanly
;; ===========================================================================

(deftest one-shot-commit-triggered-re-dirty-converges
  (reactive/reset-scheduler!)
  (let [cell    (reactive/make-cell ::one-shot)
        again   (atom false)
        passes  (atom 0)]
    ;; A listener that re-marks the cell EXACTLY once (a legit commit-triggered
    ;; follow-up: a layout effect that dispatches on first commit).
    (reactive/subscribe cell
      (fn [] (when (compare-and-set! again false true)
               (reactive/mark-dirty! cell 2))))
    (reactive/mark-dirty! cell 1)
    ;; Caller runs the INITIAL flush (the substrate write+publish pass); the
    ;; driver drains the single follow-up.
    (reactive/flush-pending!)                        ;; pass 0 (caller) -> re-marks
    (is (nil? (reactive/converge-flush!
                where (fn [] (swap! passes inc) (reactive/flush-pending!)))))
    (testing "the one-shot re-dirty drained in exactly ONE further pass"
      (is (= 1 @passes) "the driver flushed once to settle the follow-up")
      (is (= 0 (reactive/pending-cell-count)) "registry quiesced")
      (is (false? (reactive/dirty? cell)))
      (is (= 2 (reactive/revision cell))
          "revision advanced twice — the initial publish + the follow-up"))))

(deftest fixed-n-multi-pass-re-dirty-converges-without-tripping-the-bound
  (reactive/reset-scheduler!)
  (let [cell   (reactive/make-cell ::multi)
        n      (atom 0)
        passes (atom 0)]
    ;; A listener that re-marks for exactly THREE further passes, then stops —
    ;; ordinary (terminating) multi-pass convergence, well under the budget.
    (reactive/subscribe cell
      (fn [] (when (< @n 3)
               (swap! n inc)
               (reactive/mark-dirty! cell 9))))
    (reactive/mark-dirty! cell 1)
    (let [ex (ex-of #(reactive/converge-flush!
                       where (fn [] (swap! passes inc) (reactive/flush-pending!))))]
      (is (nil? ex) "a terminating multi-pass cascade never trips the bound")
      (is (= 4 @passes)
          "four driver passes: three that re-dirtied + one clean settle")
      (is (= 0 (reactive/pending-cell-count)) "registry quiesced")
      (is (false? (reactive/dirty? cell))))))

;; ===========================================================================
;; The bound — a never-quiescent cascade fails loud with the typed diagnostic
;; ===========================================================================

(deftest non-quiescent-re-enrolment-trips-the-bound
  (reactive/reset-scheduler!)
  (let [cell   (reactive/make-cell ::runaway)
        passes (atom 0)]
    ;; A listener that re-marks the cell on EVERY notification: the registry can
    ;; never quiesce across passes — exactly the re-enrolment the single-pass
    ;; ambient guards cannot see.
    (reactive/subscribe cell (fn [] (reactive/mark-dirty! cell 3)))
    (reactive/mark-dirty! cell 1)
    (let [ex   (ex-of #(reactive/converge-flush!
                         where (fn [] (swap! passes inc) (reactive/flush-pending!))))
          data (some-> ex ex-data)]
      (is (some? ex) "the runaway re-drain fails loud rather than spinning forever")
      (is (= :rf.error/flush-convergence-exceeded (:rf.error/id data))
          "the typed non-quiescence diagnostic")
      (is (= reactive/flush-convergence-budget (:passes data))
          "carries the exhausted pass budget")
      (is (= 1 (:pending data)) "carries the residual pending-cell count")
      (is (= where (:where data)) "names the forcing site")
      (is (= :no-recovery (:recovery data)))
      (is (= reactive/flush-convergence-budget @passes)
          "the driver ran exactly `budget` passes before failing loud — bounded"))))

;; ===========================================================================
;; The shared diagnostic — one typed throw, reused by every forcing site
;; ===========================================================================

(deftest flush-nonconvergence-is-the-single-shared-diagnostic
  (let [data (ex-data (ex-of #(reactive/flush-nonconvergence! 'rf.ui.test/flush! 7)))]
    (is (= :rf.error/flush-convergence-exceeded (:rf.error/id data)))
    (is (= reactive/flush-convergence-budget (:passes data)))
    (is (= 7 (:pending data)) "the caller's residual pending count rides through")
    (is (= 'rf.ui.test/flush! (:where data)) "and the forcing site")
    (is (= :no-recovery (:recovery data)))))
