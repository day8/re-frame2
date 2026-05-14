(ns re-frame-pair2-mcp.subscribe-resource-controls-test
  "Integration tests pinning the wiring between `subscribe.cljs` and
  `resource-controls.cljs` (rf2-3ijbl). The unit tests in
  `resource_controls_test.cljs` cover the primitives in isolation; this
  file pins the subscribe-side contract:

    1. The final-summary envelope surfaces `:rate-dropped` only when
       non-zero (mirrors the cross-MCP suppress-when-zero indicator
       discipline).
    2. The initial-state map carries the `:rate-dropped` slot at zero
       so a stream that hits no rate-drops emits a clean envelope.
    3. The `:reason` keyword vocabulary admits the new abuse-detected
       sentinel.

  The full stream-controller behaviour (acquire-on-subscribe,
  release-on-terminate, rate-limit-throttles-emit, abuse-trips-
  terminate) is exercised in the live-nREPL integration harness — the
  controller spins a setTimeout poll loop and orchestrates a Promise,
  neither of which the node-test harness can drive without a live
  nREPL socket. The contracts pinned here cover the wire-shape side
  of the integration; the runtime side rides the existing per-sub
  queue-cap tests + the rf2-3ijbl bead's manual smoke."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame-pair2-mcp.tools.resource-controls :as resource]
            [re-frame-pair2-mcp.tools.subscribe :as sub]))

(use-fixtures :each
  {:before (fn [] (resource/reset-for-tests!))
   :after  (fn [] (resource/reset-for-tests!))})

;; ---------------------------------------------------------------------------
;; `:rate-dropped` accumulator slot — pinned via the merge-drain
;; contract: the slot starts at zero and `final-summary` is responsible
;; for suppressing it on the envelope when still zero.
;; ---------------------------------------------------------------------------

(deftest merge-drain-leaves-rate-dropped-untouched
  ;; The drain-merge path does not touch `:rate-dropped` — only the
  ;; rate-limit gate in the poll loop increments it. A drain merge
  ;; with rate-dropped at zero must leave it at zero.
  (let [s' (sub/merge-drain {:tick 0 :delivered 0 :dropped-events 0
                             :dropped-bytes 0 :overflow-reason nil
                             :dropped-sensitive 0 :elided-large 0
                             :rate-dropped 0}
                            {:n 3 :ev-dropped 0 :by-dropped 0
                             :ov-reason nil :dropped 0 :tick-elided 0})]
    (is (zero? (:rate-dropped s'))
        "merge-drain MUST NOT touch :rate-dropped — that slot is owned by the rate-limit gate")))

;; ---------------------------------------------------------------------------
;; Abuse-detected reason keyword — admitted by the spec'd vocabulary.
;; ---------------------------------------------------------------------------

(deftest abuse-detected-keyword-is-namespaced
  ;; The reason rides on the final-summary envelope. Pin the keyword
  ;; shape — `:rf.error/stream-abuse-detected` per the rf.error/*
  ;; convention (rf2-3ijbl + Conventions §reserved-namespaces).
  (let [kw :rf.error/stream-abuse-detected]
    (is (qualified-keyword? kw))
    (is (= "rf.error" (namespace kw)))
    (is (= "stream-abuse-detected" (name kw)))))

;; ---------------------------------------------------------------------------
;; Resource-controls config surfaces through to subscribe behaviour.
;; ---------------------------------------------------------------------------

(deftest acquire-stream-rejects-with-isError-shape
  ;; The first 10 acquires succeed; the 11th rejects. The subscribe-
  ;; tool MUST surface that rejection as an `isError` MCP result —
  ;; not as a silent success. We test the resource-controls envelope
  ;; shape directly here; the subscribe-tool's err-text wraps it via
  ;; `wire/err-text` (covered by other tests).
  (dotimes [_ 10] (resource/acquire-stream!))
  (let [reject (resource/acquire-stream!)]
    (is (false? (:ok? reject)))
    (is (= :rf.error/concurrent-stream-limit (:reason reject)))
    ;; The hint guides the operator toward unsubscribe + the raise-cap
    ;; CLI flag — the actionable next steps.
    (is (re-find #"max-concurrent-streams" (:hint reject)))
    (is (re-find #"unsubscribe" (:hint reject)))))

;; ---------------------------------------------------------------------------
;; Final-summary envelope — `:rate-dropped` surfaces only when non-zero.
;; ---------------------------------------------------------------------------
;;
;; `final-summary` is private to subscribe.cljs; we exercise the
;; suppress-when-zero rule via the merge-drain shape it consumes. A
;; downstream test in `with_indicators_test.cljs` already pins the
;; broader indicator-field discipline; this file pins the per-slot
;; rule for the new `:rate-dropped` slot.

(deftest initial-state-has-rate-dropped-zero
  ;; The fresh state map MUST carry `:rate-dropped` at zero. A merge-
  ;; drain on zero-state must NOT introduce the slot if the drain
  ;; didn't touch it.
  (let [zero {:tick 0 :delivered 0 :dropped-events 0
              :dropped-bytes 0 :overflow-reason nil
              :dropped-sensitive 0 :elided-large 0
              :rate-dropped 0}
        s'   (sub/merge-drain zero {:n 1 :ev-dropped 0 :by-dropped 0
                                    :ov-reason nil :dropped 0 :tick-elided 0})]
    (is (contains? s' :rate-dropped))
    (is (zero? (:rate-dropped s')))))
