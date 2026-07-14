(ns re-frame.ui.reactive-flush-completion-race-jvm-test
  "rf2-vxgfnd.180 — linearize ViewCell invalidation against JVM flush completion.

  THE RACE (JVM-only; CLJS is single-threaded and never yields between a
  scheduler read and its write). Before the fix, an invalidation folded evidence
  and flipped `:dirty?` in SEPARATE transitions, while `complete-flush!` read the
  pending window into a local and THEN, in a separate write, cleared
  `:dirty?`/`:evidence`. A `mark-dirty!` that linearized after completion had
  READ the window but before it CLEARED the cell was lost twice over: it folded
  fresh evidence that the clear then erased, and — seeing a still-set `:dirty?` —
  it skipped re-enrolment, so the flush left the cell CLEAN and ABSENT from the
  dirty registry. A real update disappeared with no next revision or correction
  (the exact loss the audit of rf2-vxgfnd.74 reproduced deterministically).

  The fix gives each cell ONE linearizable pending-window transition:
  `enrol-dirty!` folds evidence AND flips `:dirty?` false→true in a single
  `swap-vals!`, enrolling in the registry EXACTLY on that edge; `complete-flush!`
  captures-and-clears in a single compare-and-set! retry loop. A mark racing
  completion now linearizes cleanly — EITHER before the CAS (its evidence joins
  the captured window; the CAS over the stale value fails and re-reads) OR after
  the clear (it observes a cleared `:dirty?` and enrols a FRESH next window).

  The first fixture opens the capture→clear window deterministically with the
  `reactive/*completion-barrier*` seam + a `CountDownLatch` handoff (no sleeps):
  the flusher parks between reading the window and the clearing CAS; a racing
  thread commits a second invalidation; the flusher resumes. WITHOUT the fix the
  second invalidation vanishes — so the `:latest-epoch 2` assertion FAILS on
  pre-fix code. The second fixture drives the same two operations from opposing
  threads under a start barrier across many rounds and asserts that no
  interleaving loses an invalidation, double-mints a revision, or diverges the
  dirty flag from registry enrolment.

  `.clj` (JVM-only) — a genuine two-thread interleaving; the latch handoff makes
  the first probe controlled, not timing-dependent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

;; ===========================================================================
;; The window: an invalidation between flush CAPTURE and flush CLEAR
;; ===========================================================================

(deftest invalidation-in-the-capture-clear-window-joins-the-delivered-window
  (let [cell      (reactive/make-cell ::v)
        delivered (atom [])
        hits      (atom 0)
        at-clear  (CountDownLatch. 1)   ;; the flusher parked at the pre-clear seam
        marked    (CountDownLatch. 1)]  ;; the racing invalidation committed
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/set-evidence-sink!
      (fn [c ev] (when (identical? c cell) (swap! delivered conj ev))))
    ;; Epoch 1: the cell is dirty with a pending window anchored at epoch 1.
    (reactive/mark-dirty! cell 1)
    (is (true? (reactive/dirty? cell)) "precondition: pending")
    (is (= 1 (reactive/pending-epoch cell)) "precondition: window anchored at 1")

    ;; Park the flusher at the ONE point between reading the pending window and
    ;; the compare-and-set! that clears it; while it is parked, commit epoch 2
    ;; from another thread. `future` conveys this dynamic binding to the flusher.
    (binding [reactive/*completion-barrier*
              (fn [c]
                (when (identical? c cell)
                  (.countDown at-clear)                    ;; captured, now parked
                  (.await marked 20 TimeUnit/SECONDS)))]     ;; hold the window open
      (let [flusher (future (reactive/flush-dirty! cell))]
        (is (true? (.await at-clear 20 TimeUnit/SECONDS)) "flusher reached the seam")
        ;; Epoch 2 races IN — it folds into the pending window; the cell is still
        ;; dirty, so no fresh enrolment is owed.
        (reactive/mark-dirty! cell 2)
        (.countDown marked)                                ;; release the flusher
        (is (nil? (deref flusher 20000 ::timeout)) "flush completed cleanly")))

    (testing "epoch 2 was NOT lost — it joined the captured, delivered window"
      (is (= 1 (count @delivered)) "exactly one coherent window delivered")
      (let [ev (first @delivered)]
        (is (= 1 (:first-epoch ev)) "anchored at epoch 1")
        (is (= 2 (:latest-epoch ev))
            "epoch 2 is present in the delivered window — RED pre-fix (the clear
             erased the freshly-folded evidence, delivering only epoch 1)")
        (is (= 2 (:count ev)) "both invalidations folded into the one window")))

    (testing "completion captured + cleared exactly one window; revision advanced once"
      (is (= 1 (reactive/revision cell)) "one revision advance, not zero and not two")
      (is (= 1 @hits) "the listener fired exactly once for the coalesced window")
      (is (false? (reactive/dirty? cell)) "the cell is settled")
      (is (nil? (reactive/pending-evidence cell)) "no residual pending evidence"))

    (testing "the forbidden state is impossible: epoch 2 was delivered, so nothing is owed"
      (is (= 0 (reactive/pending-cell-count))
          "the cell is neither clean-with-a-lost-update nor spuriously re-enrolled"))))

;; ===========================================================================
;; No interleaving of mark || flush loses an invalidation or diverges the
;; dirty flag from registry enrolment
;; ===========================================================================

(deftest concurrent-mark-and-flush-never-lose-an-invalidation
  (dotimes [round 200]
    (reactive/reset-scheduler!)
    (let [cell      (reactive/make-cell (keyword "vr" (str round)))
          delivered (atom [])
          barrier   (CyclicBarrier. 2)]
      (reactive/set-evidence-sink!
        (fn [c ev] (when (identical? c cell) (swap! delivered conj ev))))
      ;; The window opens at epoch 1; the flush and a second invalidation race.
      (reactive/mark-dirty! cell 1)
      (let [flush (future (.await barrier 20 TimeUnit/SECONDS)
                          (reactive/flush-dirty! cell))
            mark  (future (.await barrier 20 TimeUnit/SECONDS)
                          (reactive/mark-dirty! cell 2))]
        (is (not= ::timeout (deref flush 20000 ::timeout)) (str "round " round ": flush"))
        (is (not= ::timeout (deref mark 20000 ::timeout))  (str "round " round ": mark")))
      ;; A final drain settles any FRESH window the mark opened after the clear.
      (reactive/flush-pending!)
      (let [evs (vec @delivered)]
        (testing (str "round " round)
          (is (false? (reactive/dirty? cell)) "fully settled after the final drain")
          (is (= 0 (reactive/pending-cell-count)) "nothing lingers in the registry")
          (is (= 2 (reduce max 0 (map :latest-epoch evs)))
              "epoch 2 reached delivery in SOME window — joined the captured one
               or opened a fresh one; it is NEVER dropped (RED pre-fix on the
               lost interleaving)")
          (is (= 1 (reduce min Long/MAX_VALUE (map :first-epoch evs)))
              "epoch 1 anchored the first window")
          (is (= (count evs) (reactive/revision cell))
              "exactly one revision advance per delivered window — the dirty flip
               and registry enrolment never diverged (no phantom empty window,
               no double count)"))))))
