(ns re-frame.router-front-of-queue-test
  "Per rf2-j20a7 / Spec 005 §Level 4 — machine-internal continuation
  events insert at the FRONT of the per-frame router queue, while
  ordinary (external) dispatches stay plain-FIFO at the back.

  This file pins the ROUTER-LEVEL mechanism in core, independent of the
  machines artefact: the marking flag is `:rf.machine/internal?`, which
  `re-frame.machines` stamps onto machine-originated child dispatches
  (covered end-to-end in
  `re-frame.machine-front-of-queue-test` in the machines artefact). Here
  we drive `dispatch*` with the flag set explicitly so the queue-
  insertion semantics are tested in isolation:

    1. a flagged dispatch leap-frogs an already-queued external event;
    2. an unflagged dispatch (even one targeting the same handler) stays
       FIFO at the back;
    3. sibling flagged dispatches preserve source order at the front;
    4. each leap-frogged event is still its own dequeued event (its own
       handler cascade / epoch) — front-of-queue changes ORDER, not
       granularity.

  Deterministic harness: everything runs inside ONE `dispatch-sync`
  drain. The seed handler issues its child dispatches in its body; they
  enqueue onto the in-progress sync drain (no async drain is scheduled
  while `:in-sync-drain?` holds), then the sync drain pops them in queue
  order. Run-order is recorded into an atom by each handler.

  EP-0002 (rf2-9wa0lf): the top-level seed dispatch carries an explicit
  `{:frame :rf/default}` (the shared fixture registers `:rf/default` as
  an ordinary frame). The CHILD `dispatch*` calls inside the seed handler
  inherit the handler's frame binding, so they need no explicit frame —
  only the rootless top-level seed does (the carried-invariant contract:
  no synthesised default floor)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private run-log (atom []))

(defn- log! [k] (swap! run-log conj k))

(defn- reg-marker
  "Register a no-op event handler that records its id into `run-log`."
  [id]
  (rf/reg-event id (fn [_ _] (log! id) {})))

;; ---- (1) flagged dispatch leap-frogs a pending external event -------------

(deftest machine-internal-dispatch-leapfrogs-external
  (testing "an :rf.machine/internal? dispatch jumps ahead of an external
   event already queued before it"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :cont)
    ;; Seed handler enqueues an EXTERNAL event first, THEN a machine-
    ;; internal continuation. Arrival order is [:ext :cont]; FIFO would
    ;; run :ext before :cont, but front-of-queue must run :cont first.
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf/dispatch* [:ext] {})
        (rf/dispatch* [:cont] {:rf.machine/internal? true})
        {}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    (is (= [:seed :cont :ext] @run-log)
        ":cont (machine-internal) leap-frogged the earlier-queued :ext")))

;; ---- (2) unflagged dispatch stays FIFO (origin, not target) ---------------

(deftest external-dispatch-stays-fifo
  (testing "an unflagged dispatch stays at the BACK even when queued after
   an external event — plain FIFO; the cut is origin, not target"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :plain)
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf/dispatch* [:ext] {})
        (rf/dispatch* [:plain] {}) ;; NOT machine-internal
        {}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    (is (= [:seed :ext :plain] @run-log)
        "both unflagged dispatches ran in arrival order (no leap-frog)")))

;; ---- (3) sibling machine-internal dispatches preserve source order --------

(deftest sibling-machine-internal-preserve-source-order
  (testing "multiple machine-internal dispatches from one macrostep keep
   their emit order at the front (first emitted is dequeued first), ahead
   of the external event"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :a)
    (reg-marker :b)
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf/dispatch* [:ext] {})
        ;; Two machine-internal continuations, emitted :a then :b. Each
        ;; front-inserts onto the head of the EXISTING queue, so the net
        ;; head order is [:a :b ...], not the reversed [:b :a ...].
        (rf/dispatch* [:a] {:rf.machine/internal? true})
        (rf/dispatch* [:b] {:rf.machine/internal? true})
        {}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    (is (= [:seed :a :b :ext] @run-log)
        ":a before :b (source order) at the front, both ahead of :ext")))

;; ---- (4) epoch-per-event preserved: each leap-frogged event runs its own
;;          full handler cascade (its own :run-start), not collapsed --------

(defn- run-starts-of
  "Filter recorded trace events down to per-event :rf.event/run-start
  markers, returning their event-ids in order. The marker's `:operation`
  is top-level; the correlated event-id rides under `:tags`."
  [evs]
  (->> evs
       (filter #(= :rf.event/run-start (:operation %)))
       (mapv #(:rf.trace/event-id (:tags %)))))

(deftest each-leapfrogged-event-is-its-own-epoch
  (testing "front-of-queue changes order only — each dequeued event
   (machine-internal or not) still runs as its own event with its own
   handler cascade / :run-start (Spec 002 §Drain versus event)"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :c1)
    (reg-marker :c2)
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf/dispatch* [:ext] {})
        (rf/dispatch* [:c1] {:rf.machine/internal? true})
        (rf/dispatch* [:c2] {:rf.machine/internal? true})
        {}))
    (let [seen (atom [])]
      (rf/register-listener! :trace ::epoch-rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/dispatch-sync [:seed] {:frame :rf/default})
        (finally (rf/unregister-listener! :trace ::epoch-rec)))
      ;; Run order reflects the leap-frog.
      (is (= [:seed :c1 :c2 :ext] @run-log))
      ;; One :run-start per dequeued event — four distinct events, none
      ;; collapsed by the front-insertion.
      (let [starts (run-starts-of @seen)]
        (is (= 4 (count starts))
            "one :run-start per dequeued event — no collapse")
        (is (= [:seed :c1 :c2 :ext] starts)
            ":run-start order matches the dequeue (leap-frogged) order")))))
