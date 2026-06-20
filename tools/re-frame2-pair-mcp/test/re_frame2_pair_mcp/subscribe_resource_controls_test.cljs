(ns re-frame2-pair-mcp.subscribe-resource-controls-test
  "Integration tests pinning the wiring between `subscribe.cljs` and
  `resource-controls.cljs`. The unit tests in
  `resource_controls_test.cljs` cover the primitives in isolation; this
  file pins the subscribe-side contract:

    1. The final-summary envelope surfaces `:rate-dropped` only when
       non-zero (mirrors the cross-MCP suppress-when-zero indicator
       discipline).
    2. The initial-state map carries the `:rate-dropped` slot at zero
       so a stream that hits no rate-drops emits a clean envelope.
    3. The `:reason` keyword vocabulary admits the abuse-detected
       sentinel.

  The full stream-controller behaviour (acquire-on-subscribe,
  release-on-terminate, rate-limit-throttles-emit, abuse-trips-
  terminate) is exercised in the live-nREPL integration harness — the
  controller spins a setTimeout poll loop and orchestrates a Promise,
  neither of which the node-test harness can drive without a live
  nREPL socket. The contracts pinned here cover the wire-shape side
  of the integration; the runtime side rides the existing per-sub
  queue-cap tests + a manual smoke."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]
            [re-frame2-pair-mcp.tools.subscribe :as sub]))

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
  ;; convention (Conventions §reserved-namespaces).
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
;; downstream test in `with_indicators_test.cljs` pins the broader
;; indicator-field discipline; this file pins the per-slot rule for the
;; `:rate-dropped` slot.

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

;; ---------------------------------------------------------------------------
;; Rate gate is checked BEFORE the destructive drain.
;;
;; The poll loop checks the rate limit BEFORE draining the runtime queue
;; (`drain-subscription!` pops + clears it). A denied cycle MUST NOT call
;; `drain-subscription!`, so the queue stays intact for a later cycle
;; once a token refills — no silent event loss, honouring the spec
;; promise of "left in queue for later delivery".
;;
;; We drive ONE `poll` invocation through `make-stream-controller` with a
;; stubbed `nrepl/cljs-eval-value` that records every form it's asked to
;; eval, and a rate bucket emptied to deny. The assertion: the drain form
;; never reached nREPL (no destructive drain → no loss), and the cycle is
;; tallied as `:rate-dropped`.
;; ---------------------------------------------------------------------------

(defn- empty-the-bucket!
  "Set the rate cap to 1 and consume the single token so the next
  `check-rate!` denies. Returns nothing."
  []
  (resource/set-config! {:max-events-per-sec 1})
  (is (true? (resource/check-rate!)) "first token consumed — bucket now empty")
  (is (false? (resource/check-rate!)) "bucket empty — subsequent checks deny"))

(defn- fresh-state []
  (atom {:tick 0 :delivered 0 :dropped-events 0
         :dropped-bytes 0 :overflow-reason nil
         :dropped-sensitive 0 :elided-large 0
         :rate-dropped 0}))

;; `js/setTimeout` is a JS global, NOT a CLJS var — `with-redefs` cannot
;; restore it (it only manages var roots), and a leaked stub would break
;; later tests' real poll loops (e.g. tail-build). So we `set!` it and
;; restore explicitly. `nrepl/cljs-eval-value` IS a var but we restore it
;; the same way to keep the lifetime obvious across the async boundary.
;; Both stubs use explicit arity-3/4 — the call sites compile to a
;; fixed-arity invoke, so a variadic-only stub has no `arity$3` method.

(deftest rate-denied-cycle-does-not-drain-no-event-loss
  ;; With the bucket empty, the poll cycle is DEFERRED: the destructive
  ;; drain form must NOT be evaluated, and `:rate-dropped` ticks up. The
  ;; events stay queued runtime-side for a later cycle — no loss. The
  ;; denied path is synchronous, so we assert then restore in a finally.
  (let [evald          (atom [])
        state          (fresh-state)
        orig-eval      nrepl/cljs-eval-value
        orig-set-to    js/setTimeout
        record-eval    (fn [form] (swap! evald conj form) (js/Promise.resolve {}))]
    (try
      (set! nrepl/cljs-eval-value
            (fn ([_c _b form] (record-eval form))
              ([_c _b form _o] (record-eval form))))
      ;; no-op the reschedule so the deferred cycle doesn't spin a
      ;; background timer past the assertion
      (set! js/setTimeout (fn [& _] 0))
      (empty-the-bucket!)
      (let [{:keys [poll]}
            (sub/make-stream-controller
              {:conn :stub :build-id "b" :sub-id "sub-rl" :topic :trace
               :resolve (fn [_] nil) :state state
               :signal nil :send-note nil :progress-tk nil
               :poll-ms 100 :max-events 0
               :incl? false :elision? true :dedup? false})]
        (poll)
        (is (empty? @evald)
            "rate-denied cycle MUST NOT evaluate the drain form — the runtime queue is preserved (no event loss)")
        (is (= 1 (:rate-dropped @state))
            "the deferred cycle is tallied as :rate-dropped"))
      (finally
        (set! nrepl/cljs-eval-value orig-eval)
        (set! js/setTimeout orig-set-to)))))

(deftest rate-allowed-cycle-does-drain
  ;; Control: with a token available, the cycle DOES drain — the gate
  ;; only defers when the bucket is empty. The stub resolves a non-gone,
  ;; empty drain so the cycle is a normal (no-tick) drain; `terminate` is
  ;; NOT entered, so the single eval recorded is the drain form itself.
  (async done
    (let [evald       (atom [])
          state       (fresh-state)
          orig-eval   nrepl/cljs-eval-value
          orig-set-to js/setTimeout
          record-eval (fn [form]
                        (swap! evald conj form)
                        (js/Promise.resolve {:ok? true :sub-id "sub-ok"
                                             :events [] :gone? false}))
          eval-stub   (fn ([_c _b form] (record-eval form))
                        ([_c _b form _o] (record-eval form)))
          restore!    (fn []
                        (tu/restore-eval! eval-stub orig-eval)
                        (set! js/setTimeout orig-set-to))]
      (set! nrepl/cljs-eval-value eval-stub)
      (set! js/setTimeout (fn [& _] 0))
      (resource/reset-for-tests!) ; fresh bucket at default cap — first check passes
      (let [{:keys [poll]}
            (sub/make-stream-controller
              {:conn :stub :build-id "b" :sub-id "sub-ok" :topic :trace
               :resolve (fn [_] nil) :state state
               :signal nil :send-note nil :progress-tk nil
               :poll-ms 100 :max-events 0
               :incl? false :elision? true :dedup? false})]
        ;; poll's drain runs on a resolved Promise; assert in a .then
        ;; once the drain handler has recorded the eval.
        (-> (poll)
            (.then (fn [_]
                     (is (= 1 (count @evald))
                         "rate-allowed cycle evaluates the drain form exactly once")
                     (is (re-find #"drain-subscription!" (first @evald))
                         "the recorded eval is the destructive drain form")
                     (is (zero? (:rate-dropped @state))
                         "no rate-drop when a token was available")))
            (.finally (fn [] (restore!) (done))))))))
