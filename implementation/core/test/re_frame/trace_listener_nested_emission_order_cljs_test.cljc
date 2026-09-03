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

  A reentrant emit from inside a listener body drives a SHARED fan-out schedule
  (`re-frame.trace.tooling/*fanout-ctx*`): it advances the paused outer delivery
  to every remaining listener FIRST, then delivers its own event, all before the
  nested `emit!` returns. Every listener therefore observes A before B, and — the
  rf2-s522m half — the nested `emit!` returns only after B has reached every
  listener (see `nested-emit-completes-synchronously` below). The delivery
  schedule is the same FIFO order the prior fire-and-drain-later design produced;
  only the nested `emit!`'s return is now blocked until its event is delivered.

  Runs on both hosts (`npm run test:cljs` + `clojure -M:test`). The two listeners
  live in a 2-entry array-map, which preserves insertion order on both hosts, so
  the EMITTER (registered FIRST) fans out before the OBSERVER — the ordering the
  defect needs to manifest. Composes with rf2-eaxnai's neutral-continuation work:
  the nested work is a listener body's own authored work and runs under ordinary
  (neutral) authority."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            [re-frame.trace                :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private outer :trace.order/outer)
(def ^:private inner :trace.order/inner)

(defn- ops-only
  "Keep just this test's two operations, in the order the listener saw them."
  [evs]
  (filterv #{outer inner} (mapv :operation evs)))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `rf.trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug nested-emission-preserves-per-listener-event-order
  ;; L0 (emitter, registered first) reentrantly emits `inner` while handling the
  ;; outer event. L1 (observer, registered second) must still see `outer` before
  ;; `inner`. Under the bug L1 saw `[inner outer]` — the nested emit reached L1
  ;; before the outer loop resumed.
  (let [seen-observer (atom [])
        seen-emitter  (atom [])
        emitted?      (atom false)]
    (rf.trace/register-listener! ::emitter
      (fn [ev]
        (swap! seen-emitter conj ev)
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (rf.trace/emit! :info inner {}))))
    (rf.trace/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (= [outer inner] (ops-only @seen-observer))
          "the observer sees the outer event before the reentrantly-emitted inner one")
      (is (= [outer inner] (ops-only @seen-emitter))
          "the emitter, too, sees outer before its own nested inner")
      (finally
        (rf.trace/unregister-listener! ::emitter)
        (rf.trace/unregister-listener! ::observer)))))

(deftest ^:requires-debug nested-emit-completes-synchronously
  ;; rf2-s522m: a nested emit must return only AFTER its event has reached every
  ;; listener (Spec 009 §Emitting trace events: "the emit returns once every
  ;; listener has been invoked"). L0 (emitter, first) handles outer, emits inner,
  ;; then — still on the emit call stack, the instant `emit!` RETURNS — records
  ;; whether L1 (observer, second) has already received inner. Under the
  ;; fire-and-drain-later bug the nested emit returned before ANY listener saw
  ;; inner, so L0 observed STALE state (the observer had not caught up); the
  ;; recorded order was [[:first outer] [:after-inner-return false] [:second outer]
  ;; [:first inner] [:second inner]]. The fix advances the paused outer delivery
  ;; and then delivers inner to every listener BEFORE the nested emit returns, so
  ;; L0 sees the observer already caught up — while both listeners still observe
  ;; outer before inner.
  (let [seen-observer   (atom [])
        seen-emitter    (atom [])
        emitted?        (atom false)
        ;; :not-recorded until the emitter's outer-callback reaches the check.
        observer-caught-up? (atom :not-recorded)]
    (rf.trace/register-listener! ::emitter
      (fn [ev]
        (swap! seen-emitter conj ev)
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (rf.trace/emit! :info inner {})
          ;; Back on the outer-emit call stack the instant the nested emit
          ;; returned: the observer MUST already have received inner.
          (reset! observer-caught-up?
                  (boolean (some #{inner} (ops-only @seen-observer)))))))
    (rf.trace/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (true? @observer-caught-up?)
          (str "the observer had NOT received inner when the nested emit "
               "returned — the nested emit is not synchronously complete "
               "(rf2-s522m). Recorded: " (pr-str @observer-caught-up?)))
      (is (= [outer inner] (ops-only @seen-observer))
          "the observer still sees outer before the reentrantly-emitted inner")
      (is (= [outer inner] (ops-only @seen-emitter))
          "the emitter, too, sees outer before its own nested inner")
      (finally
        (rf.trace/unregister-listener! ::emitter)
        (rf.trace/unregister-listener! ::observer)))))

(deftest ^:requires-debug stateful-tool-finishes-in-the-authored-state
  ;; The bead's stateful-tooling evidence: a tool folds the event stream into a
  ;; live/cleared state. `outer` = the flow was CLEARED; the emitter reacts by
  ;; re-registering (emitting `inner` = REGISTERED). Authored order is
  ;; outer→inner, so the tool must finish REGISTERED (live). Under the reversed
  ;; delivery it folded inner→outer and finished CLEARED — reporting a live flow
  ;; as gone.
  (let [tool-state (atom :unknown)
        emitted?   (atom false)]
    (rf.trace/register-listener! ::reregistrar
      (fn [ev]
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (rf.trace/emit! :info inner {}))))
    (rf.trace/register-listener! ::stateful-tool
      (fn [ev]
        (case (:operation ev)
          :trace.order/outer (reset! tool-state :cleared)
          :trace.order/inner (reset! tool-state :registered)
          nil)))
    (try
      (rf.trace/emit! :info outer {})
      (is (= :registered @tool-state)
          "the stateful tool folds outer(cleared)→inner(registered) and finishes live")
      (finally
        (rf.trace/unregister-listener! ::reregistrar)
        (rf.trace/unregister-listener! ::stateful-tool)))))

(deftest ^:requires-debug nested-exception-isolation-preserves-order
  ;; A listener that THROWS between the emitter and the observer must neither
  ;; stop delivery nor corrupt ordering: per-listener exception isolation is
  ;; preserved AND the observer still sees outer before the reentrant inner.
  (let [seen-observer (atom [])
        threw?        (atom 0)
        emitted?      (atom false)]
    (rf.trace/register-listener! ::emitter
      (fn [ev]
        (when (and (= outer (:operation ev))
                   (compare-and-set! emitted? false true))
          (rf.trace/emit! :info inner {}))))
    (rf.trace/register-listener! ::thrower
      (fn [_ev]
        (swap! threw? inc)
        (throw (ex-info "listener boom" {}))))
    (rf.trace/register-listener! ::observer
      (fn [ev] (swap! seen-observer conj ev)))
    (try
      (rf.trace/emit! :info outer {})
      (is (= [outer inner] (ops-only @seen-observer))
          "a throwing intermediate listener does not reorder or drop the observer's stream")
      (is (pos? @threw?) "the throwing listener was actually invoked (isolation, not skip)")
      (finally
        (rf.trace/unregister-listener! ::emitter)
        (rf.trace/unregister-listener! ::thrower)
        (rf.trace/unregister-listener! ::observer)))))
