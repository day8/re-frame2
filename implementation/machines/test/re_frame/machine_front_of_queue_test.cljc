(ns re-frame.machine-front-of-queue-test
  "Per Spec 005 §Level 4 — end-to-end coverage that a REAL
  machine's continuation dispatch (`:fx [[:dispatch …]]` emitted from an
  action) leap-frogs to the FRONT of the router queue, while an event
  that merely TARGETS a machine but originates externally stays FIFO.

  The router-level queue-insertion mechanism is pinned in
  `re-frame.router-front-of-queue-test` (core). Here we exercise the
  marking SEAM: `re-frame.router/run-handler-cascade!` tags the in-flight
  envelope `:rf.machine/internal? true` when the handler's registration
  meta carries `:rf/machine? true` (stamped by `reg-machine*`); that flag
  rides the parent envelope into `do-fx`, and `child-dispatch-opts`
  copies it onto the `:fx [[:dispatch …]]` child — so the continuation
  front-inserts. A plain handler's identical `:fx [[:dispatch …]]` does
  NOT (the cut is the dispatch's ORIGIN).

  Deterministic harness: one `dispatch-sync` drain. The seed handler
  enqueues its events in its body; they ride the in-progress sync drain,
  which pops them in queue order. Run-order is recorded into an atom."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private run-log (atom []))

(defn- log! [k] (swap! run-log conj k))

(defn- reg-marker [id]
  (rf/reg-event id (fn [_ _] (log! id) {})))

;; ---- (1) a machine action's :fx [[:dispatch …]] leap-frogs a pending
;;          external event ----------------------------------------------------

(deftest machine-action-dispatch-leapfrogs-external
  (testing "a continuation event dispatched from a machine action runs
   BEFORE an external event that was queued earlier (Spec 005 §Level 4)"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :cont)
    (rf/reg-machine :rf2-j20a7/leapfrog
      {:initial :idle
       :data    {}
       :actions {:emit-cont
                 (fn [_]
                   (log! :machine-action)
                   ;; Machine-ORIGINATED continuation — must front-insert.
                   {:fx [[:dispatch [:cont]]]})}
       :states  {:idle {:on {:go {:target :done :action :emit-cont}}}
                 :done {}}})
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        ;; Machine event queued FIRST, external event SECOND.
        (rf/dispatch* [:rf2-j20a7/leapfrog [:go]] {})
        (rf/dispatch* [:ext] {})
        {}))
    (rf/dispatch-sync [:seed])
    (is (= [:seed :machine-action :cont :ext] @run-log)
        ":cont (machine continuation) leap-frogged the earlier-queued :ext")))

;; ---- (2) an event TARGETING a machine, but originating externally, stays
;;          FIFO -------------------------------------------------------------

(deftest external-dispatch-targeting-machine-stays-fifo
  (testing "the cut is ORIGIN not target — an externally-dispatched event
   that targets a machine goes to the BACK like any other external event"
    (reset! run-log [])
    (reg-marker :plain)
    (rf/reg-machine :rf2-j20a7/fifo-target
      {:initial :idle
       :data    {}
       :actions {:noop (fn [_] (log! :machine-ran) {})}
       :states  {:idle {:on {:go {:target :done :action :noop}}}
                 :done {}}})
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        ;; Both are EXTERNAL dispatches (origin = this plain handler's fx
        ;; emit). The machine event is queued first; targeting a machine
        ;; must NOT move it relative to the later plain event.
        (rf/dispatch* [:rf2-j20a7/fifo-target [:go]] {})
        (rf/dispatch* [:plain] {})
        {}))
    (rf/dispatch-sync [:seed])
    (is (= [:seed :machine-ran :plain] @run-log)
        "machine target ran in arrival order; no leap-frog for external origin")))

;; ---- (3) macrostep quiesces (all machine continuations settle) before the
;;          next external event ------------------------------------------------

(deftest macrostep-quiesces-before-next-external
  (testing "a chain of machine-originated continuations all settle ahead of
   the pending external event — the machine drives to quiescence first"
    (reset! run-log [])
    (reg-marker :ext)
    (reg-marker :cont-1)
    ;; :cont-2 is dispatched FROM :cont-1, which is itself a plain
    ;; (non-machine) handler — so :cont-2 is a BACK-of-queue dispatch.
    ;; This verifies the boundary precisely: only the machine-ORIGINATED
    ;; hop leap-frogs; once control leaves the machine, FIFO resumes.
    (rf/reg-event :cont-1
      (fn [_ _]
        (log! :cont-1)
        (rf/dispatch* [:cont-2] {}) ;; plain origin → back of queue
        {}))
    (reg-marker :cont-2)
    (rf/reg-machine :rf2-j20a7/quiesce
      {:initial :idle
       :data    {}
       :actions {:fire (fn [_]
                         (log! :machine-action)
                         {:fx [[:dispatch [:cont-1]]]})}
       :states  {:idle {:on {:go {:target :done :action :fire}}}
                 :done {}}})
    (rf/reg-event :seed
      (fn [_ _]
        (log! :seed)
        (rf/dispatch* [:rf2-j20a7/quiesce [:go]] {})
        (rf/dispatch* [:ext] {})
        {}))
    (rf/dispatch-sync [:seed])
    ;; Trace through the queue:
    ;;   seed do-fx:        queue [[:M :go] [:ext]]
    ;;   pop [:M :go]:      machine fronts :cont-1 → [[:cont-1] [:ext]]
    ;;   pop [:cont-1]:     plain backs :cont-2   → [[:ext] [:cont-2]]
    ;;   pop [:ext]:        (the external event)  → [[:cont-2]]
    ;;   pop [:cont-2]
    (is (= [:seed :machine-action :cont-1 :ext :cont-2] @run-log)
        ":cont-1 (machine continuation) preceded :ext; :cont-2 (plain
         origin, back-of-queue) followed :ext — FIFO resumes once control
         leaves the machine")))
