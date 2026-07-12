(ns re-frame.ui.reactive-tear-cljs-test
  "rf2-vxgfnd.40 — the watch-fired uSES TEAR WINDOW is closed before paint.

  The scenario (03 §2 two-guard rule, guard-1 leg; Spec 006 invariant 5
  'host corrects before paint'): a mounted ViewCell A has committed observing
  sub S. Under React time-slicing React renders A (value v1) then YIELDS. A
  drain moves S; S's watch fires A's `on-change`, marking A dirty — a
  WATCH-FIRED invalidation, NOT an epoch-batched one, so it rides the
  notification scheduler, not a synchronous flush. React resumes and renders a
  sibling B, which reads the moved value v2. At the synchronous commit instant
  A's revision is UNMOVED (React's `checkIfSnapshotChanged` sees no change), so
  the frame is momentarily TORN (A=v1, B=v2). The correction MUST land before
  the host paints.

  Before rf2-vxgfnd.40 the flush rode `interop/next-tick` = `goog.async.nextTick`
  — a MACROTASK (`setImmediate`/`MessageChannel`/`setTimeout`) that yields to
  the event loop, so a torn frame could paint before the correction ran. The
  fix schedules the flush on the host MICROTASK queue (`queue-microtask!`),
  whose checkpoint runs after the synchronous drain unwinds and BEFORE the
  'update the rendering' step — closing the window.

  These fixtures PIN the timing deterministically on Node, the host where
  microtask-vs-macrotask ordering is observable (a real React concurrent tear
  is not deterministically reproducible; the full observation-port → uSES →
  React browser gate is rf2-vxgfnd.65). The discriminator: in a microtask
  queued right after the mark, the flush has ALREADY run (revision advanced).
  Were the flush still a macrotask, that microtask observer would run FIRST and
  see the revision unmoved — the pre-fix torn state."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.ui.reactive :as reactive]))

(deftest watch-fired-flush-corrects-before-paint-via-a-microtask
  (reactive/reset-scheduler!)
  (let [cell-a (reactive/make-cell ::a)
        cell-b (reactive/make-cell ::b)
        hits   (atom 0)]
    (reactive/subscribe cell-a (fn [] (swap! hits inc)))
    (reactive/subscribe cell-b (fn []))
    (async done
      ;; --- the watch fires on the move (A already committed, mid-yield) ---
      (reactive/mark-dirty! cell-a 7)
      ;; SYNCHRONOUS commit instant: the flush is DEFERRED (coalescing is
      ;; preserved — it is NOT a synchronous per-mark flush), so A's revision
      ;; is still unmoved. This is the torn instant B would render against.
      (testing "deferred off the synchronous drain (torn instant)"
        (is (= 0 (reactive/revision cell-a)))
        (is (reactive/dirty? cell-a) "A carries a pending correction")
        (is (= 0 @hits)))
      ;; --- MICROTASK checkpoint (before paint) ---
      ;; Queued AFTER the flush microtask, so if the flush rides the microtask
      ;; queue the correction has already landed here. A macrotask flush (the
      ;; pre-fix `goog.async.nextTick`) would still be pending at this point.
      (js/queueMicrotask
        (fn []
          (testing "the flush ran in the MICROTASK phase — before any paint"
            (is (= 1 (reactive/revision cell-a))
                "A corrected before paint (microtask, not macrotask)")
            (is (= 1 @hits) "exactly one coalesced notification")
            (is (not (reactive/dirty? cell-a))))
          ;; --- MACROTASK (a later task / where a paint could interleave) ---
          ;; The corrected, coalesced state is stable one macrotask on.
          (js/setTimeout
            (fn []
              (testing "stable across the macrotask boundary"
                (is (= 1 (reactive/revision cell-a)))
                (is (= 1 @hits)))
              (done))
            0))))))

(deftest many-watch-fired-marks-in-one-task-coalesce-to-one-microtask-flush
  ;; N watch-fired invalidations arriving within one synchronous task arm ONE
  ;; microtask flush and advance the cell ONCE — the drain-quiescence render
  ;; batch, realised on the microtask queue rather than a macrotask.
  (reactive/reset-scheduler!)
  (let [cell (reactive/make-cell ::c)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (async done
      (dotimes [_ 6] (reactive/mark-dirty! cell 1))
      (is (= 0 (reactive/revision cell)) "still deferred after 6 marks")
      (is (= 1 (reactive/pending-cell-count)) "enrolled once")
      (js/queueMicrotask
        (fn []
          (is (= 1 (reactive/revision cell)) "6 marks in one task ⇒ ONE advance")
          (is (= 1 @hits) "⇒ ONE notification — one render batch")
          (done))))))
