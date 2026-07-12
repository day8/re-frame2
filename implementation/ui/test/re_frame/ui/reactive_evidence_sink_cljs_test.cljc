(ns re-frame.ui.reactive-evidence-sink-cljs-test
  "rf2-vxgfnd.73 — a THROWING `evidence-sink` (the DEBUG invalidation-evidence
  consumer, e.g. Xray) must never corrupt ViewCell flush completion. The
  evidence-sink is a CONSUMER of the scheduler, never an authority over it, so
  a sink that throws is CONTAINED: every drained cell still leaves `:dirty?`,
  advances its revision exactly once, notifies its listeners, and stays
  re-enrollable; the throw neither strands a cell in the
  dirty-removed-but-unflushed limbo nor aborts the rest of the batch; a normal
  sink still receives the exact pre-clear bounded summary once per cell; a
  re-entrant sink that re-marks a cell keeps its freshly-scheduled window; and
  the escape is surfaced (not silent) via `last-evidence-sink-escape` (plus a
  host `console.warn` on CLJS).

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node) AND
  `clojure -M:test` (JVM), so the containment is graft-checked on both hosts."
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

(defn- dirty-cell!
  "A fresh cell (view `id`) marked dirty at `epoch` (folds DEBUG evidence),
  carrying a listener that counts notifications into `hits`."
  [id epoch hits]
  (let [cell (reactive/make-cell id)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/mark-dirty! cell epoch)
    cell))

;; ===========================================================================
;; The bug: a throwing sink must not strand a cell nor abort the batch
;; ===========================================================================

(deftest a-throwing-sink-does-not-strand-cells-and-is-not-silent
  ;; rf2-vxgfnd.73: the sink was called INSIDE flush-one! BEFORE the scheduler
  ;; state was completed, and its throw PROPAGATED — so the throwing cell was
  ;; left `:dirty? true` yet already removed from `dirty-cells` (permanent
  ;; limbo; a later mark-dirty! finds it still dirty and refuses to re-enrol),
  ;; and the throw aborted the doseq so every later cell in the same batch was
  ;; stranded too. On current code `flush-pending!` THROWS here (an errored
  ;; test); on the fixed path the flush COMPLETES and the throw is CONTAINED.
  (let [ha     (atom 0)
        hb     (atom 0)
        cell-a (dirty-cell! ::a 1 ha)
        cell-b (dirty-cell! ::b 2 hb)
        seen   (atom [])]
    ;; a sink that throws deterministically for cell-a BY IDENTITY (not by
    ;; delivery order) — so every assertion holds regardless of the batch's
    ;; set-iteration order. It records each delivery FIRST, then throws.
    (reactive/set-evidence-sink!
      (fn [c ev]
        (swap! seen conj [c ev])
        (when (identical? c cell-a)
          (throw (ex-info "evidence-sink boom" {})))))
    (is (= 2 (reactive/pending-cell-count)) "both cells pending before the flush")

    (reactive/flush-pending!)          ;; must NOT throw — the sink throw is contained

    (testing "the throwing cell's flush still COMPLETED (was stranded pre-fix)"
      (is (not (reactive/dirty? cell-a)) ":dirty? cleared despite the sink throw")
      (is (nil? (reactive/pending-evidence cell-a)) "evidence cleared")
      (is (= 1 (reactive/revision cell-a)) "revision advanced exactly once")
      (is (= 1 @ha) "listeners notified exactly once"))
    (testing "later cells in the same batch also completed (throw did not abort)"
      (is (not (reactive/dirty? cell-b)))
      (is (= 1 (reactive/revision cell-b)))
      (is (= 1 @hb)))
    (testing "no cell left in the dirty-removed-but-unflushed limbo"
      (is (= 0 (reactive/pending-cell-count))))
    (testing "evidence delivery reached BOTH cells despite the first throw (AC3)"
      (is (= #{cell-a cell-b} (set (map first @seen)))
          "one cell's tool failure did not abort delivery to the other"))
    (testing "the escape is VISIBLE (not silent)"
      (let [escape (reactive/last-evidence-sink-escape)]
        (is (some? escape) "the contained escape is surfaced for tooling")
        (is (identical? cell-a (:cell escape)) "…naming the offending cell")
        (is (some? (:error escape)) "…and carrying the thrown value")))
    (testing "both cells remain RE-ENROLLABLE on a later invalidation (AC1)"
      (reactive/set-evidence-sink! nil)          ;; drop the broken tool
      (reactive/mark-dirty! cell-a 3)
      (reactive/mark-dirty! cell-b 4)
      (is (reactive/dirty? cell-a) "the cleared cell re-enrols on a fresh mark")
      (is (reactive/dirty? cell-b))
      (is (= 2 (reactive/pending-cell-count)))
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell-a)))
      (is (= 2 (reactive/revision cell-b)))
      (is (= 2 @ha))
      (is (= 2 @hb)))))

;; ===========================================================================
;; A normal (non-throwing) sink is unchanged — AC6
;; ===========================================================================

(deftest a-normal-sink-still-receives-the-pre-clear-summary-once
  ;; Containment must preserve the normal contract exactly: the sink receives
  ;; the coalesced pre-clear bounded summary EXACTLY ONCE per flushed cell, and
  ;; no escape is recorded when nothing throws.
  (let [hits (atom 0)
        cell (reactive/make-cell ::v)
        seen (atom [])]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/set-evidence-sink! (fn [c ev] (swap! seen conj [c ev])))
    (doseq [e (range 1 6)] (reactive/mark-dirty! cell e))   ;; 5 folds → one window
    (reactive/flush-pending!)
    (is (= 1 (count @seen)) "the sink received the coalesced batch exactly once")
    (is (identical? cell (first (first @seen))))
    (let [ev (second (first @seen))]
      (is (= 1 (:first-epoch ev)) "…the PRE-CLEAR summary (anchored to the first epoch)")
      (is (= 5 (:count ev)) "…with the full coalesced fold count"))
    (is (= 1 (reactive/revision cell)) "one revision advance for the whole batch")
    (is (= 1 @hits) "one notification")
    (is (nil? (reactive/pending-evidence cell)) "evidence cleared after delivery")
    (is (nil? (reactive/last-evidence-sink-escape)) "a well-behaved sink records no escape")))

;; ===========================================================================
;; A re-entrant sink that re-marks a cell keeps the fresh batch — AC5
;; ===========================================================================

(deftest a-re-entrant-sink-that-remarks-preserves-the-next-window
  ;; Because the flush CLEARS `:dirty?` and advances the revision BEFORE the
  ;; sink runs, a sink that re-marks the same cell during delivery enrols a
  ;; FRESH pending window rather than folding into (and losing) the window the
  ;; flush is clearing. The newly-scheduled invalidation survives to the next
  ;; flush; the drain-coalescing contract (one enrolment / one advance per
  ;; window) is preserved.
  (let [hits     (atom 0)
        cell     (reactive/make-cell ::v)
        remarked (atom false)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (reactive/set-evidence-sink!
      (fn [c _ev]
        (when (compare-and-set! remarked false true)   ;; re-mark exactly once
          (reactive/mark-dirty! c 99))))
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (testing "the flush completed once AND the re-entrant mark opened a fresh window"
      (is (= 1 (reactive/revision cell)) "the coalesced window advanced exactly once")
      (is (= 1 @hits))
      (is (reactive/dirty? cell) "the re-entrant mark re-enrolled the cell")
      (is (= 1 (reactive/pending-cell-count)) "the freshly-scheduled batch was not lost")
      (is (= 99 (reactive/pending-epoch cell)) "…anchored to the re-mark's own epoch")
      (is (nil? (reactive/last-evidence-sink-escape)) "no throw, no escape"))
    (testing "the preserved window flushes on the next drain"
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell)) "the fresh window advances on the next flush")
      (is (= 2 @hits)))))
