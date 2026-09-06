(ns re-frame.ssr-error-drain-concurrency-test
  "rf2-hzttr finding 4 — `consume-pending-traces!` MUST pull-and-clear a
  frame's pending error-trace buffer ATOMICALLY.

  The pre-fix shape DEREF'd the atom, read the frame's traces, then
  `swap! dissoc`'d the frame key in a SEPARATE transition:

      (let [snap   @pending-error-traces
            traces (get snap frame-id [])]
        (when (seq traces)
          (swap! pending-error-traces dissoc frame-id))   ;; <- separate
        traces)

  Under concurrent SSR / streaming error paths many server frames are
  live at once (the canonical shape — see ssr-ring's concurrency stress
  test). A `buffer-error-trace!` append for the SAME frame landing
  between the deref and the dissoc was silently dropped: the deref read
  the pre-append value, then the dissoc deleted the whole frame key —
  including the just-appended trace. A dropped error trace can lose a
  fail-closed status upgrade (a 200 shipped where a 5xx was due) or
  incomplete diagnostics — exactly the operational path this listener is
  meant to harden.

  The fix uses `swap-vals!` so the read and the clear happen in one
  CAS-retried transition: an append that races the drain either lands
  before the CAS (rides in the returned `old` value) or after it
  (survives in the atom for the next drain). No trace is lost either way.

  This test reproduces the race by interleaving appends and drains across
  many threads, then asserts CONSERVATION: every appended trace is either
  drained exactly once OR still buffered — none vanish. The pre-fix code
  loses traces under this load nondeterministically; the fix never does.

  JVM-only — the race requires real parallelism, which the Node CLJS
  runtime does not have. The fix itself is platform-neutral `.cljc`
  (`swap-vals!` exists on both runtimes); this test pins the JVM
  concurrency contract."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]))

;; Private fns reached via their vars — the same internal surface the
;; runtime drives (buffer on the listener path, drain on the projection
;; path).
(def ^:private consume! #'rf.ssr.error-listener/consume-pending-traces!)

(defn- buffer! [frame-id trace]
  (swap! rf.ssr.error-listener/pending-error-traces
         update frame-id (fnil conj []) trace))

(deftest consume-pending-traces-loses-no-trace-under-concurrent-append
  (testing "rf2-hzttr finding 4 — interleaved appends + drains for the SAME
            frame never lose a trace. Drained-count + still-buffered-count
            equals total-appended (conservation)."
    (let [frame-id      :rf.test/drain-race
          appends       2000
          ;; Clean slate for this frame.
          _             (swap! rf.ssr.error-listener/pending-error-traces
                               dissoc frame-id)
          drained       (atom [])
          appended      (atom 0)
          start-gate    (java.util.concurrent.CountDownLatch. 1)
          ;; Appender: buffers `appends` distinct traces, each a unique id.
          appender      (Thread.
                          (fn []
                            (.await start-gate)
                            (dotimes [i appends]
                              (buffer! frame-id {:op-type   :error
                                                 :operation :rf.error/probe
                                                 :seq       i})
                              (swap! appended inc))))
          ;; Drainer: repeatedly drains the frame, collecting whatever it
          ;; pulls, racing the appender for the full window.
          drainer       (Thread.
                          (fn []
                            (.await start-gate)
                            (dotimes [_ (* 4 appends)]
                              (let [pulled (consume! frame-id)]
                                (when (seq pulled)
                                  (swap! drained into pulled))))))]
      (.start appender)
      (.start drainer)
      (.countDown start-gate)
      (.join appender)
      (.join drainer)
      ;; Final sweep — pull anything the drainer left behind so the
      ;; conservation check sees the complete picture.
      (let [leftover (consume! frame-id)
            all-out  (into @drained leftover)
            seen-seq (set (map :seq all-out))]
        (is (= @appended appends)
            "sanity: the appender buffered every trace it intended to")
        (is (= appends (count all-out))
            (str "CONSERVATION: drained (" (count @drained) ") + leftover ("
                 (count leftover) ") must equal total appended (" appends
                 ") — no trace dropped by a non-atomic pull-then-clear"))
        (is (= (set (range appends)) seen-seq)
            "every distinct appended trace surfaced exactly once — no loss,
             no duplication")))))

(deftest consume-pending-traces-clears-the-frame-key
  (testing "rf2-hzttr finding 4 — a drain that pulls traces also removes the
            frame's key (the clear half of the atomic pull-and-clear), so a
            subsequent drain on the same frame returns empty."
    (let [frame-id :rf.test/drain-clear]
      (swap! rf.ssr.error-listener/pending-error-traces dissoc frame-id)
      (buffer! frame-id {:op-type :error :operation :rf.error/probe :seq 0})
      (buffer! frame-id {:op-type :error :operation :rf.error/probe :seq 1})
      (let [pulled (consume! frame-id)]
        (is (= 2 (count pulled)) "the drain pulls both buffered traces")
        (is (not (contains? @rf.ssr.error-listener/pending-error-traces frame-id))
            "the frame key is removed in the same transition")
        (is (= [] (consume! frame-id))
            "a second drain returns empty — the buffer was cleared")))))

(deftest consume-pending-traces-empty-frame-is-noop
  (testing "rf2-hzttr finding 4 — draining a frame with no buffered traces
            returns [] and does not introduce a spurious frame key
            (swap-vals! dissoc of an absent key is a no-op)."
    (let [frame-id :rf.test/drain-empty]
      (swap! rf.ssr.error-listener/pending-error-traces dissoc frame-id)
      (is (= [] (consume! frame-id)))
      (is (not (contains? @rf.ssr.error-listener/pending-error-traces frame-id))
          "no spurious key added for an empty drain"))))
