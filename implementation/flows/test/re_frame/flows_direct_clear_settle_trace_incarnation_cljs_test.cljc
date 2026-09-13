(ns re-frame.flows-direct-clear-settle-trace-incarnation-cljs-test
  "rf2-gwye.63 — exact-incarnation fence for the traces the DIRECT-CLEAR SETTLE
  emits, THROUGH the synchronous trace-emit callback pipeline (classification
  projection → epoch capture → ordered tooling listeners).

  The sibling of `re-frame.flows-replace-clear-trace-incarnation-cljs-test`
  (rf2-rxsldx), which fences the direct lifecycle emits themselves. That fence
  wraps `:rf.flow/cleared` and stops at the end of `clear-flow`'s exact-owner
  postcheck. The SETTLE that runs one line later — `settle-frame-flows!`,
  recomputing the cleared producer's dependents against its absence — is a
  second, longer callback-bearing region, and before rf2-gwye.63 it supplied no
  continuation predicate at all.

  `run-flows-on-db` is called with `:exact-owner-token`, so the pass's own
  WRITES (dirty cache, output install, schema validation, the final
  `swap-frame-db-exact!`) are fenced. A trace is not a write: `trace/emit!` is
  a separate synchronous pipeline whose stages recheck ownership ONLY while a
  continuation predicate is installed — `trace/continuation-live?` reads the
  always-true default otherwise — and the settle runs INSIDE the cold serialized
  region, which DEFERS listener delivery until after the release.
  `deferred-continue` captures whatever predicate stood at emit time, so the
  ordinary always-continue default was what the whole deferred fan-out carried.

  Consequence, reproduced below: an ordered trace LISTENER destroys incarnation
  A and publishes a same-id B while the dependent's `:rf.flow/computed` is
  fanning out, and every SUBSEQUENT listener still receives A's
  incarnation-less computed event after A's destruction has been claimed. The
  event route does not behave that way (it inherits the router's exact-owner
  scope), nor do the neighbouring direct lifecycle emits, so the direct-clear
  COMPUTE stream disagreed with both at the same supported callback boundary.

  The fix wraps the whole settle pass in
  `trace/call-with-continuation-predicate` bound to A's pinned incarnation. The
  already-entered delivery (the listener that destroys A) stands once; every
  LATER listener is suppressed the instant A's exact ownership is lost. The
  scope covers the WHOLE pass rather than the computed emit alone, so the
  sibling skip / failure / schema observations cannot escape it either.

  Listener fan-out order is insertion order, so the destroyer is registered
  FIRST (the already-entered delivery that may stand) and the observer SECOND
  (the subsequent delivery the fence must suppress). Order is used only to make
  a deterministic witness — applications are not required to depend on it.

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it under CLJS AND the cognitect JVM runner
  runs it — the lifecycle code under test is all `.cljc`, so the boundary is
  exercised on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private producer-id :flow.settle.fence/producer)
(def ^:private dependent-id :flow.settle.fence/dependent)

(defn- seed-frame!
  "Stand up `frame-id` with the two-flow chain the witness needs and drive one
  ordinary event through it, so the settle triggered by clearing the producer
  has a real dependent to recompute.

      [:input] --producer--> [:source] --dependent--> [:derived]

  Returns the frame's app-db after seeding."
  [frame-id]
  (rf/make-frame {:id frame-id})
  (rf/reg-event ::seed (fn [_ _] {:db {:input 5}}))
  (rf/reg-flow producer-id
    {:frame frame-id :inputs [[:input]] :output-path [:source]}
    identity)
  (rf/reg-flow dependent-id
    {:frame frame-id :inputs [[:source]] :output-path [:derived]}
    (fn [source] (or source :absent)))
  (rf/dispatch-sync [::seed] {:frame frame-id})
  (rf/app-db-value frame-id))

(defn- dependent-computed?
  "True for the DEPENDENT's own `:rf.flow/computed` trace event."
  [ev frame-id]
  (and (= :rf.flow/computed (:operation ev))
       (= dependent-id (get-in ev [:tags :flow-id]))
       (= frame-id (get-in ev [:tags :frame]))))

;; ===========================================================================
;; THE WITNESS — a listener that destroys A mid-fan-out must fence the ones
;; behind it.
;; ===========================================================================

(deftest direct-clear-settle-trace-listener-loss-fences-subsequent-listeners
  ;; rf2-gwye.63 (red before the fix). Clearing the producer settles the
  ;; dependent, which emits `:rf.flow/computed` with A live. The destroyer
  ;; listener — the already-entered delivery — destroys A and publishes same-id
  ;; B exactly once. Before the fix the settle pass installed no continuation
  ;; predicate, so the deferred fan-out carried the always-true default and the
  ;; observer still received A's computed event after A's destruction was
  ;; claimed. After the fix the pinned-A predicate suppresses every listener
  ;; past the loss.
  (let [frame-id        :flow.settle.fence/subject
        destroyer-hits  (atom 0)
        observer-dep    (atom [])       ;; the dependent's computed events seen
        armed?          (atom true)
        b-token         (atom nil)
        b-flow-registry (atom ::unset)]
    (is (= {:input 5 :source 5 :derived 5} (seed-frame! frame-id))
        "precondition — the seeding drain materialised the chain in topological order")
    ;; Listener 1 (registered FIRST → fans out FIRST): the DESTROYER.
    (rf.trace.tooling/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (dependent-computed? ev frame-id)
                   (compare-and-set! armed? true false))
          (swap! destroyer-hits inc)
          (rf.frame/destroy-frame! frame-id)
          (rf/make-frame {:id frame-id})
          (reset! b-token (rf.frame/frame-incarnation-token frame-id))
          (reset! b-flow-registry (get (rf.flows/flows-snapshot) frame-id ::none)))))
    ;; Listener 2 (registered SECOND): the SUBSEQUENT observer.
    (rf.trace.tooling/register-listener!
      ::observer
      (fn [ev]
        (when (dependent-computed? ev frame-id)
          (swap! observer-dep conj ev))))
    (try
      (is (= producer-id (rf/clear :flow producer-id {:frame frame-id}))
          "clear returns the id even though A's owner was lost mid-emit")
      (is (= 1 @destroyer-hits)
          "the destroyer received the dependent's `:rf.flow/computed` once — the
           already-entered delivery stands")
      (is (some? @b-token) "the destroyer published a same-id B")
      (is (identical? @b-token (rf.frame/frame-incarnation-token frame-id))
          "B remains the live incarnation")
      ;; THE TOOTH: the subsequent listener never receives A's stale event.
      (is (empty? @observer-dep)
          (str "the SUBSEQUENT listener received ZERO of A's `:rf.flow/computed` "
               "events — the fence suppresses every trace stage after A's exact "
               "ownership is lost (removing the settle pass's "
               "`call-with-continuation-predicate` wrapper makes this fail); saw "
               (pr-str (mapv #(get-in % [:tags :result]) @observer-dep))))
      ;; B is never mutated by A's stale settle tail.
      (is (= ::none @b-flow-registry) "B started with an empty flow registry")
      (is (= {} (rf/app-db-value frame-id))
          "A's stale settle tail never installed a db into the successor")
      (finally
        (rf.trace.tooling/unregister-listener! ::destroyer)
        (rf.trace.tooling/unregister-listener! ::observer)))))

;; ===========================================================================
;; GREEN CONTROL / over-fence tooth — with A live through the fan-out the
;; ordinary settle trace still reaches the subsequent listener exactly once,
;; and the synchronous clear behaves exactly as before.
;; ===========================================================================

(deftest direct-clear-settle-trace-with-live-owner-emits-once
  ;; rf2-gwye.63 mutation tooth. The exact-incarnation fence must NOT suppress
  ;; the ordinary settle trace when A stays live through the fan-out, and must
  ;; not change what the settle computes or installs.
  (let [frame-id :flow.settle.fence/live
        touched  (atom 0)
        observed (atom [])]
    (is (= {:input 5 :source 5 :derived 5} (seed-frame! frame-id))
        "precondition — the seeding drain materialised the chain")
    (rf.trace.tooling/register-listener!
      ::live-touch
      (fn [ev]
        (when (dependent-computed? ev frame-id)
          (swap! touched inc))))            ;; observe only — A stays live
    (rf.trace.tooling/register-listener!
      ::live-observer
      (fn [ev]
        (when (dependent-computed? ev frame-id)
          (swap! observed conj ev))))
    (try
      (is (= producer-id (rf/clear :flow producer-id {:frame frame-id})))
      (is (= 1 @touched) "the first listener saw the dependent's computed event")
      (is (= 1 (count @observed))
          "the SUBSEQUENT listener also received it — the live-owner settle is
           not over-fenced")
      (is (= :absent (get-in (first @observed) [:tags :result]))
          "the observed computed event carries the dependent's recomputation
           against the producer's absence")
      (is (= {:input 5 :derived :absent} (rf/app-db-value frame-id))
          "ordinary synchronous direct-clear behaviour is preserved — the
           producer's leaf is vacated and the dependent settled before return")
      (finally
        (rf.trace.tooling/unregister-listener! ::live-touch)
        (rf.trace.tooling/unregister-listener! ::live-observer)))))
