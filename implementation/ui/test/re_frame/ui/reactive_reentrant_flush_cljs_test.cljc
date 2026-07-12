(ns re-frame.ui.reactive-reentrant-flush-cljs-test
  "rf2-vxgfnd.86 — re-entrant MULTI-CELL flush semantics must be
  ORDER-INDEPENDENT.

  #5759 completes each ViewCell before the DEBUG evidence sink, but the old
  `flush-scope!` removed the whole matching batch from `dirty-cells` up front
  then completed/delivered cells one at a time from an IDENTITY SET — so whether
  a cell re-marked mid-flush (by a sink or a listener re-entered through another
  cell's notification) opened a fresh pending window or lost its mark depended on
  arbitrary set iteration order: a re-marked cell already cleared → fresh window;
  a re-marked cell still in the drained-but-uncompleted batch → mark folded into
  the window the flush was about to clear, then lost.

  The fix is an explicit batch phase boundary: PHASE 1 completes the WHOLE
  batch's scheduler state (capture evidence, clear `:dirty?`, advance revision)
  with no arbitrary user code, then PHASE 2 delivers notifications + evidence.
  A re-entrant re-mark can only fire in phase 2, by which point every drained
  cell is already completed, so it always enrols a FRESH next-batch window —
  independent of iteration order.

  These fixtures drive the two ORDERS explicitly through the `flush-batch-in-order!`
  test seam (the same two-phase core `flush-scope!` uses) and assert an identical
  outcome, cover both the DEBUG sink AND the production listener re-entry vector,
  and pin the real `flush-scope!`/`flush-pending!` primitive too.

  `.cljc` ending `-cljs-test` graft-checks on node (`test:cljs`) AND JVM
  (`clojure -M:test`)."
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

;; ===========================================================================
;; A DEBUG evidence-sink on cell A re-marks a SIBLING cell B in the same batch
;; ===========================================================================

(defn- run-sink-scenario
  "Batch {A,B}; A's evidence-sink re-marks B (epoch 99). Flush in `order-fn`'s
  order through the two-phase seam. Returns the final observable state."
  [order-fn]
  (reactive/reset-scheduler!)
  (let [ha     (atom 0)
        hb     (atom 0)
        cell-a (reactive/make-cell ::a)
        cell-b (reactive/make-cell ::b)]
    (reactive/subscribe cell-a (fn [] (swap! ha inc)))
    (reactive/subscribe cell-b (fn [] (swap! hb inc)))
    (reactive/set-evidence-sink!
      (fn [c _ev]
        (when (identical? c cell-a)
          (reactive/mark-dirty! cell-b 99))))     ;; re-mark the sibling
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (let [n (reactive/flush-batch-in-order! (order-fn [cell-a cell-b]))]
      {:flushed         n
       :rev-a           (reactive/revision cell-a)
       :rev-b           (reactive/revision cell-b)
       :hits-a          @ha
       :hits-b          @hb
       :b-dirty?        (reactive/dirty? cell-b)
       :b-pending-epoch (reactive/pending-epoch cell-b)
       :pending-count   (reactive/pending-cell-count)
       :escape          (some? (reactive/last-evidence-sink-escape))})))

(deftest re-entrant-sink-cross-cell-remark-is-order-independent
  (let [fwd (run-sink-scenario identity)              ;; A then B
        rev (run-sink-scenario (fn [[a b]] [b a]))]   ;; B then A
    (testing "both iteration orders yield the IDENTICAL final state (rf2-vxgfnd.86)"
      (is (= fwd rev)
          "the re-entrant cross-cell re-mark is order-independent"))
    (testing "…and the re-marked sibling opened a FRESH next-batch window (not lost)"
      (is (= 2 (:flushed fwd)) "both original cells flushed once")
      (is (= 1 (:rev-a fwd)) "A advanced exactly once")
      (is (= 1 (:rev-b fwd)) "B's original window advanced exactly once")
      (is (= 1 (:hits-a fwd)))
      (is (= 1 (:hits-b fwd)))
      (is (true? (:b-dirty? fwd)) "B re-enrolled by A's re-entrant mark")
      (is (= 99 (:b-pending-epoch fwd)) "…anchored to the re-mark's own epoch")
      (is (= 1 (:pending-count fwd)) "exactly the fresh B window is pending")
      (is (false? (:escape fwd)) "a well-behaved sink records no escape"))))

;; ===========================================================================
;; The PRODUCTION vector: a LISTENER on A re-marks a sibling B (useSyncExternal-
;; Store notification re-entry) — must be order-independent under the same
;; phase discipline, with no DEBUG machinery in play
;; ===========================================================================

(defn- run-listener-scenario
  [order-fn]
  (reactive/reset-scheduler!)
  (let [cell-a   (reactive/make-cell ::a)
        cell-b   (reactive/make-cell ::b)
        remarked (atom false)]
    ;; A's LISTENER (the production re-render trigger) re-marks B once.
    (reactive/subscribe cell-a
      (fn [] (when (compare-and-set! remarked false true)
               (reactive/mark-dirty! cell-b 77))))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (let [n (reactive/flush-batch-in-order! (order-fn [cell-a cell-b]))]
      {:flushed         n
       :rev-b           (reactive/revision cell-b)
       :b-dirty?        (reactive/dirty? cell-b)
       :b-pending-epoch (reactive/pending-epoch cell-b)
       :pending-count   (reactive/pending-cell-count)})))

(deftest listener-induced-cross-cell-remark-is-order-independent
  (let [fwd (run-listener-scenario identity)
        rev (run-listener-scenario (fn [[a b]] [b a]))]
    (testing "listener re-entry through a sibling is order-independent too"
      (is (= fwd rev) "the production notification path is covered by the same discipline"))
    (testing "the re-marked sibling opened a fresh next-batch window"
      (is (= 2 (:flushed fwd)))
      (is (= 1 (:rev-b fwd)) "B's original window advanced exactly once")
      (is (true? (:b-dirty? fwd)) "B re-enrolled by A's listener")
      (is (= 77 (:b-pending-epoch fwd)))
      (is (= 1 (:pending-count fwd))))))

;; ===========================================================================
;; The real primitive: flush-scope!/flush-pending! runs the same two-phase core,
;; so the invariant holds regardless of the hash-set's natural drain order
;; ===========================================================================

(deftest flush-pending-multi-cell-reentrant-remark-preserves-next-window
  (reactive/reset-scheduler!)
  (let [cell-a (reactive/make-cell ::a)
        cell-b (reactive/make-cell ::b)]
    (reactive/set-evidence-sink!
      (fn [c _ev] (when (identical? c cell-a) (reactive/mark-dirty! cell-b 42))))
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (is (= 2 (reactive/flush-pending!)) "both original cells flushed once")
    (testing "whatever order the set drained, B's fresh window survives (rf2-vxgfnd.86)"
      (is (reactive/dirty? cell-b))
      (is (= 42 (reactive/pending-epoch cell-b)))
      (is (= 1 (reactive/pending-cell-count)))
      (is (= 1 (reactive/revision cell-b)) "B's original batch advanced exactly once"))
    (testing "the preserved window flushes on the next drain"
      (reactive/set-evidence-sink! nil)
      (is (= 1 (reactive/flush-pending!)))
      (is (= 2 (reactive/revision cell-b)))
      (is (= 0 (reactive/pending-cell-count))))))

;; ===========================================================================
;; Containment survives the two-phase batch: a throwing sink that ALSO re-marks
;; a sibling neither strands nor aborts, and the outcome stays order-independent
;; ===========================================================================

(defn- run-throwing-reentrant-scenario
  [order-fn]
  (reactive/reset-scheduler!)
  (let [cell-a (reactive/make-cell ::a)
        cell-b (reactive/make-cell ::b)
        seen   (atom #{})]
    (reactive/set-evidence-sink!
      (fn [c _ev]
        (swap! seen conj c)                       ;; record delivery FIRST
        (when (identical? c cell-a)
          (reactive/mark-dirty! cell-b 55)        ;; re-mark the sibling…
          (throw (ex-info "sink boom" {})))))     ;; …then throw
    (reactive/mark-dirty! cell-a 1)
    (reactive/mark-dirty! cell-b 2)
    (reactive/flush-batch-in-order! (order-fn [cell-a cell-b]))
    {:rev-a         (reactive/revision cell-a)
     :rev-b         (reactive/revision cell-b)
     :both-seen?    (= #{cell-a cell-b} @seen)
     :b-dirty?      (reactive/dirty? cell-b)
     :b-epoch       (reactive/pending-epoch cell-b)
     :pending-count (reactive/pending-cell-count)
     :escape-a?     (identical? cell-a (:cell (reactive/last-evidence-sink-escape)))}))

(deftest throwing-re-entrant-sink-is-contained-and-order-independent
  (let [fwd (run-throwing-reentrant-scenario identity)
        rev (run-throwing-reentrant-scenario (fn [[a b]] [b a]))]
    (testing "the throwing + re-marking sink yields the same contained outcome either order"
      (is (= fwd rev)))
    (testing "both cells completed, delivery reached both, the sibling re-enrolled"
      (is (= 1 (:rev-a fwd)) "A completed despite its sink throwing")
      (is (= 1 (:rev-b fwd)) "B completed (throw did not abort the batch)")
      (is (true? (:both-seen? fwd)) "delivery reached both cells")
      (is (true? (:b-dirty? fwd)) "the re-entrant mark opened a fresh window")
      (is (= 55 (:b-epoch fwd)))
      (is (= 1 (:pending-count fwd)))
      (is (true? (:escape-a? fwd)) "the escape names the offending cell — not silent"))))
