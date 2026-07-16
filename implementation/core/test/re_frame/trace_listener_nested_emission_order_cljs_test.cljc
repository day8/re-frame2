(ns re-frame.trace-listener-nested-emission-order-cljs-test
  "rf2-1zxlsm — per-listener EVENT order must be preserved when trace emissions
  NEST. A listener that emits a trace B while an OUTER event A is still being
  fanned out to the remaining listeners must not cause any later listener to
  observe B before A.

  ## The defect

  `re-frame.trace.tooling/deliver-to-tooling!` fanned events out with a plain
  synchronous loop over the listener registry. When listener L0's callback
  reentrantly emitted B (dispatching, re-registering a flow, …), that nested
  emit re-entered the fan-out and delivered B to EVERY listener — L0 and the
  still-unvisited L1 — BEFORE the outer loop resumed and delivered A to L1. So a
  later listener saw `[B A]`, reversing the runtime emission order the outer A
  was authored before B.

  The bead's concrete case: L0 handles an outer `:rf.flow/cleared` by
  re-registering the same flow, synchronously emitting a nested
  `:rf.flow/registered`. L1 then observed `[:rf.flow/registered :rf.flow/cleared]`
  and a stateful tool folding those events left the flow in the CLEARED state
  though it is live. Spec 009 §The listener contract point 4 requires every
  listener to receive trace events in runtime emission order — this is a central
  trace-delivery law, not a flow-local fence.

  ## The fix

  A reentrant emit from inside a listener body is DEFERRED: it is enqueued and
  fanned out only after the outer event has finished reaching every listener,
  then drained FIFO (transitively). The nested `emit!` still returns
  synchronously (its ring push, epoch capture and classification projection all
  still happen inline) — only its listener fan-out is ordered behind the outer
  event's. Every listener therefore observes A before B.

  Runs on both hosts (`npm run test:cljs` + `clojure -M:test`). The two listeners
  live in a 2-entry array-map, which preserves insertion order on both hosts, so
  the EMITTER (registered FIRST) fans out before the OBSERVER — the ordering the
  defect needs to manifest. Composes with rf2-eaxnai's neutral-continuation work:
  the deferred nested work is a listener body's own authored work and runs under
  ordinary (neutral) authority when drained."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private outer :trace.order/outer)
(def ^:private inner :trace.order/inner)

(defn- ops-only
  "Keep just this test's two operations, in the order the listener saw them."
  [evs]
  (filterv #{outer inner} (mapv :operation evs)))

(deftest nested-emission-preserves-per-listener-event-order
  ;; L0 (emitter, registered first) reentrantly emits `inner` while handling the
  ;; outer event. L1 (observer, registered second) must still see `outer` before
  ;; `inner`. Under the bug L1 saw `[inner outer]` — the nested emit reached L1
  ;; before the outer loop resumed.
  (let [seen-observer (atom [])
        seen-emitter  (atom [])
        emitted?      (atom false)]
    (trace/register-listener! ::emitter
      (fn [ev]
        (swap! seen-emitter conj ev)
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (trace/emit! :info inner {}))))
    (trace/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (trace/emit! :info outer {})
      (is (= [outer inner] (ops-only @seen-observer))
          "the observer sees the outer event before the reentrantly-emitted inner one")
      (is (= [outer inner] (ops-only @seen-emitter))
          "the emitter, too, sees outer before its own nested inner")
      (finally
        (trace/unregister-listener! ::emitter)
        (trace/unregister-listener! ::observer)))))

(deftest stateful-tool-finishes-in-the-authored-state
  ;; The bead's stateful-tooling evidence: a tool folds the event stream into a
  ;; live/cleared state. `outer` = the flow was CLEARED; the emitter reacts by
  ;; re-registering (emitting `inner` = REGISTERED). Authored order is
  ;; outer→inner, so the tool must finish REGISTERED (live). Under the reversed
  ;; delivery it folded inner→outer and finished CLEARED — reporting a live flow
  ;; as gone.
  (let [tool-state (atom :unknown)
        emitted?   (atom false)]
    (trace/register-listener! ::reregistrar
      (fn [ev]
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (trace/emit! :info inner {}))))
    (trace/register-listener! ::stateful-tool
      (fn [ev]
        (case (:operation ev)
          :trace.order/outer (reset! tool-state :cleared)
          :trace.order/inner (reset! tool-state :registered)
          nil)))
    (try
      (trace/emit! :info outer {})
      (is (= :registered @tool-state)
          "the stateful tool folds outer(cleared)→inner(registered) and finishes live")
      (finally
        (trace/unregister-listener! ::reregistrar)
        (trace/unregister-listener! ::stateful-tool)))))

(deftest nested-exception-isolation-preserves-order
  ;; A listener that THROWS between the emitter and the observer must neither
  ;; stop delivery nor corrupt ordering: per-listener exception isolation is
  ;; preserved AND the observer still sees outer before the reentrant inner.
  (let [seen-observer (atom [])
        threw?        (atom 0)
        emitted?      (atom false)]
    (trace/register-listener! ::emitter
      (fn [ev]
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (trace/emit! :info inner {}))))
    (trace/register-listener! ::thrower
      (fn [_ev]
        (swap! threw? inc)
        (throw (ex-info "listener boom" {}))))
    (trace/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (trace/emit! :info outer {})
      (is (= [outer inner] (ops-only @seen-observer))
          "a throwing intermediate listener does not reorder or drop the observer's stream")
      (is (pos? @threw?) "the throwing listener was actually invoked (isolation, not skip)")
      (finally
        (trace/unregister-listener! ::emitter)
        (trace/unregister-listener! ::thrower)
        (trace/unregister-listener! ::observer)))))
