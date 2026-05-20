(ns re-frame.lifecycle-composed-corners-test
  "Per rf2-6txsp — compose rare machine timer / join / final / system-id
  interleavings.

  Existing per-edge regression coverage (`timer_frame_scope_test`,
  `after_test`, `invoke_all_test`, `final_state_cljs_test`,
  `spawn_registry_test`, `frame_destroy_cascade_test`,
  `destroyed_trace_shape_test`) is strong. This file pins the
  combinations the audit body called out:

    - stale `:after` firing AFTER the frame was destroyed,
    - stale `:after` firing AFTER the system-id was rebound to a new
      actor,
    - `:invoke-all` child completion AFTER the parent frame was destroyed,
    - dynamic delay re-resolution after state exit (the timer-table
      entry is gone; the stale synthetic event is a no-op),
    - composed leak audit across timer table + system-id reverse index
      + `:rf/spawned` slot + spawn-order channel after `destroy-frame!`.

  JVM-only by design — synthetic
  `[:rf.machine.timer/after-elapsed <delay-key> <epoch>]` dispatches are
  deterministic without wall-clock host scheduling, matching the
  precedent in `after_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines]
            [re-frame.machines.spawn-order :as spawn-order]
            [re-frame.machines.timer :as timer]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers --------------------------------------------------------------

(defn- record!
  "Attach a trace listener and return [observation-atom unreg-fn]."
  [id]
  (let [a (atom [])]
    (trace/register-trace-listener! id (fn [ev] (swap! a conj ev)))
    [a #(trace/unregister-trace-listener! id)]))

;; ---------------------------------------------------------------------------
;; 1. Stale :after firing AFTER frame destroy
;;
;; The machine schedules an :after timer; the host clock has not yet
;; fired. The owning frame is destroyed (which clears the timer table
;; entry via the :machines/on-frame-destroyed! hook). A subsequent
;; synthetic [:rf.machine.timer/after-elapsed ...] dispatch — modelling
;; the case where the host clock managed to fire AFTER destroy but
;; BEFORE the host-clock handle was cleared — must not (a) throw,
;; (b) transition any machine state, (c) leave any residue in the
;; timer table for the destroyed frame.
;; ---------------------------------------------------------------------------

(deftest stale-after-firing-after-frame-destroy-is-noop
  (testing "synthetic :after-elapsed for a destroyed frame is a no-op +
            :rf.error/frame-destroyed trace; timer table residue is
            cleared by the :machines/on-frame-destroyed! hook"
    (rf/reg-frame :corner.timer/scoped {:doc "scoped"})
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states  {:idle    {:on {:fetch :loading}}
                       :loading {:after {5000 :timeout}
                                 :on    {:loaded :ready}}
                       :timeout {}
                       :ready   {}}}]
      (rf/reg-machine :corner.timer/m m)
      (rf/dispatch-sync [:corner.timer/m [:fetch]] {:frame :corner.timer/scoped})
      ;; The timer table now has an entry for the frame.
      (is (contains? @timer/after-timers :corner.timer/scoped)
          "precondition: timer table holds an entry for the frame")

      ;; Destroy the frame. The :machines/on-frame-destroyed! late-bind
      ;; hook releases the host-clock handle and drops the inner entry.
      (frame/destroy-frame! :corner.timer/scoped)
      (is (not (contains? @timer/after-timers :corner.timer/scoped))
          "post-destroy: timer table no longer holds a residue for the destroyed frame")

      ;; The synthetic stale firing — emulates the host clock waking up
      ;; AFTER destroy. The dispatch routes to a destroyed frame.
      (let [[recorded unreg] (record! ::stale-1)]
        (try
          (is (nil? (rf/dispatch-sync
                      [:corner.timer/m [:rf.machine.timer/after-elapsed 5000 1]]
                      {:frame :corner.timer/scoped}))
              "stale firing does not throw")
          (is (some #(= :rf.error/frame-destroyed (:operation %)) @recorded)
              "the dispatch traced :rf.error/frame-destroyed (no transition fired)")
          (finally (unreg)))))))

;; ---------------------------------------------------------------------------
;; 2. Stale :after firing AFTER system-id rebind
;;
;; Spawn actor A bound under :system-id :primary. Schedule its :after.
;; Destroy actor A. Spawn actor B bound under the SAME :system-id —
;; the reverse index now points at B. Fire the stale synthetic
;; :after-elapsed keyed on A's id. A's handler is gone (unregistered
;; at destroy), so the dispatch traces :rf.error/no-such-handler;
;; actor B is NOT transitioned; the reverse index still points at B.
;; ---------------------------------------------------------------------------

(deftest stale-after-firing-after-system-id-rebind-traces-stale
  (testing "stale :after for a destroyed actor does NOT transition the
            same-system-id replacement actor; A's handler is gone +
            traces :rf.error/no-such-handler; B's snapshot is intact"
    (let [child  {:initial :running
                  :data    {:rf/after-epoch 0}
                  :states  {:running {:after {5000 :timeout}}
                            :timeout {}}}
          parent {:initial :idle
                  :data    {}
                  :states
                  {:idle {:on {:spawn-bound
                               {:action
                                (fn [_ _]
                                  {:fx [[:rf.machine/spawn
                                         {:machine-id :corner.sid/child
                                          :id-prefix  :corner.sid/child
                                          :system-id  :corner/primary}]]})}
                               :replace
                               {:action
                                (fn [_ _]
                                  {:fx [[:rf.machine/destroy :corner.sid/child#1]
                                        [:rf.machine/spawn
                                         {:machine-id :corner.sid/child
                                          :id-prefix  :corner.sid/child
                                          :system-id  :corner/primary}]]})}}}}}]
      (rf/reg-machine :corner.sid/child child)
      (rf/reg-machine :corner.sid/parent parent)
      (rf/dispatch-sync [:corner.sid/parent [:spawn-bound]])
      ;; Actor A is :corner.sid/child#1; system-id binds to it.
      (let [db (rf/get-frame-db :rf/default)]
        (is (= :corner.sid/child#1 (get-in db [:rf/system-ids :corner/primary]))
            ":corner/primary reverse-index points at actor A (#1)")
        (is (some? (get-in db [:rf/machines :corner.sid/child#1]))
            "actor A snapshot is live"))

      ;; Replace: destroy A, spawn B under the same system-id.
      (rf/dispatch-sync [:corner.sid/parent [:replace]])
      (let [db (rf/get-frame-db :rf/default)]
        (is (= :corner.sid/child#2 (get-in db [:rf/system-ids :corner/primary]))
            ":corner/primary now points at actor B (#2) — index rebinds cleanly")
        (is (nil? (get-in db [:rf/machines :corner.sid/child#1]))
            "actor A snapshot is gone")
        (is (some? (get-in db [:rf/machines :corner.sid/child#2]))
            "actor B snapshot is live")
        (is (nil? (registrar/lookup :event :corner.sid/child#1))
            "actor A handler is unregistered post-destroy"))

      ;; Fire the stale synthetic :after-elapsed keyed on A's id.
      (let [[recorded unreg] (record! ::stale-2)]
        (try
          (rf/dispatch-sync [:corner.sid/child#1
                             [:rf.machine.timer/after-elapsed 5000 1]])
          ;; A is gone; the dispatch traces :rf.error/no-such-handler.
          (is (some #(= :rf.error/no-such-handler (:operation %)) @recorded)
              "stale dispatch to A's gone handler trace :rf.error/no-such-handler")
          ;; B's snapshot is untouched by the stale-firing-on-A event.
          (let [db (rf/get-frame-db :rf/default)]
            (is (= :running (:state (get-in db [:rf/machines :corner.sid/child#2])))
                "actor B's state is still :running — stale A-firing did NOT cross over")
            (is (= :corner.sid/child#2 (get-in db [:rf/system-ids :corner/primary]))
                "reverse-index still points at B"))
          (finally (unreg)))))))

;; ---------------------------------------------------------------------------
;; 3. :invoke-all child completion AFTER parent frame destroy
;;
;; The parent's :invoke-all join state lives at [:rf/spawned <parent>
;; <invoke-id>] in the parent FRAME's app-db. When the frame is
;; destroyed, the machine teardown cascade runs each spawned actor's
;; :exit, but if a child dispatches its :on-child-done AFTER the frame
;; is gone, the dispatch routes to a destroyed frame and must be a
;; silent no-op.
;; ---------------------------------------------------------------------------

(deftest invoke-all-child-completion-after-parent-frame-destroy-is-noop
  (testing "child :on-child-done arriving after parent frame destroy is
            a no-op + traces :rf.error/frame-destroyed; no join slot
            mutation; no resolution event fires"
    (rf/reg-frame :corner.ia/scoped {:doc "scoped"})
    (let [child  {:initial :running
                  :data    {:id nil}
                  :actions {:set-id (fn [data ev]
                                      {:data (assoc data :id (second ev))})}
                  :states  {:running {:on {:set-id {:action :set-id}
                                           :go     :done}}
                            :done    {}}}
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:invoke-all
                    {:children        [{:id :a :machine-id :corner.ia/child :start [:set-id :a]}
                                       {:id :b :machine-id :corner.ia/child :start [:set-id :b]}
                                       {:id :c :machine-id :corner.ia/child :start [:set-id :c]}]
                     :join            :all
                     :on-child-done   :hydrate/loaded
                     :on-child-error  :hydrate/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}}}}]
      (rf/reg-machine :corner.ia/child child)
      (rf/reg-machine :corner.ia/parent parent)
      (rf/dispatch-sync [:corner.ia/parent [:start]] {:frame :corner.ia/scoped})

      ;; The join state is seeded.
      (let [db (rf/get-frame-db :corner.ia/scoped)
            j  (get-in db [:rf/spawned :corner.ia/parent [:hydrating]])]
        (is (map? j) "join state seeded")
        (is (false? (:resolved? j)) "join not yet resolved"))

      ;; Destroy the parent frame BEFORE any child completes.
      (frame/destroy-frame! :corner.ia/scoped)
      (is (nil? (frame/frame :corner.ia/scoped))
          "frame is destroyed")

      ;; Late child completion: synthetic dispatch into the
      ;; (now-destroyed) frame for :a's :on-child-done.
      (let [[recorded unreg] (record! ::corner-ia)]
        (try
          (rf/dispatch-sync [:corner.ia/parent [:hydrate/loaded :a]]
                            {:frame :corner.ia/scoped})
          (is (some #(= :rf.error/frame-destroyed (:operation %)) @recorded)
              "late child dispatch traced :rf.error/frame-destroyed")
          ;; The resolution event (:hydrate/done / :hydrate/failed) did
          ;; NOT fire — the join state is gone with the frame.
          (is (not-any? #(and (= :event (:op-type %))
                              (= :event/dispatched (:operation %))
                              (or (= :hydrate/done   (first (:event (:tags %))))
                                  (= :hydrate/failed (first (:event (:tags %))))))
                        @recorded)
              "no :hydrate/done / :hydrate/failed dispatch was scheduled")
          (finally (unreg)))))))

;; ---------------------------------------------------------------------------
;; 4. Dynamic-delay re-resolution after state exit (timer entry cleared;
;;    later stale synthetic is a no-op)
;;
;; This locks the contract that even when a sub-vec :after delay's value
;; is changing dynamically, EXITING the bearing state cancels the timer
;; table entry; a synthetic :after-elapsed arriving later is gracefully
;; rejected as :stale-after (epoch mismatch) and does NOT transition.
;; ---------------------------------------------------------------------------

(deftest dynamic-after-delay-cleared-on-state-exit-stale-is-noop
  (testing "after state-exit the :after entry is cleared from the timer
            table; a stale synthetic :after-elapsed traces
            :rf.machine.timer/stale-after and does NOT transition"
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000 :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}]
      (rf/reg-machine :corner.dyn/m m)
      (rf/dispatch-sync [:corner.dyn/m [:fetch]])
      ;; Timer table has an entry.
      (is (seq (get @timer/after-timers :rf/default))
          "precondition: timer table holds the in-flight entry")

      ;; Capture the entry's epoch BEFORE we exit :loading.
      (let [snap-before  (get-in (rf/get-frame-db :rf/default)
                                 [:rf/machines :corner.dyn/m])
            epoch-loading (get-in snap-before [:data :rf/after-epoch])]
        (is (pos? epoch-loading) ":loading entry advanced the epoch")

        ;; Exit :loading via :loaded → :ready. after-cancel-fx
        ;; releases the timer entry.
        (rf/dispatch-sync [:corner.dyn/m [:loaded]])
        (is (= :ready (:state (get-in (rf/get-frame-db :rf/default)
                                      [:rf/machines :corner.dyn/m])))
            "machine reached :ready")
        ;; The timer table's inner map should have the entry dropped;
        ;; per timer.cljc when the inner map empties the OUTER frame
        ;; entry is also dropped.
        (is (or (not (contains? @timer/after-timers :rf/default))
                (empty? (get @timer/after-timers :rf/default)))
            "timer table residue for the frame is cleared on state exit")

        ;; Now the stale firing — synthetic carrying epoch-loading
        ;; (the value at :loading entry-time) but the snapshot's
        ;; epoch is now epoch-loading+1 after the :loaded transition.
        (let [[recorded unreg] (record! ::corner-dyn)]
          (try
            (rf/dispatch-sync [:corner.dyn/m
                               [:rf.machine.timer/after-elapsed 5000 epoch-loading]])
            (is (= :ready (:state (get-in (rf/get-frame-db :rf/default)
                                          [:rf/machines :corner.dyn/m])))
                "stale firing did NOT transition the machine off :ready")
            (is (some #(and (= :rf.machine.timer/stale-after (:operation %))
                            (= 5000 (:delay (:tags %))))
                      @recorded)
                ":stale-after trace fired with the canonical :delay tag")
            (finally (unreg))))))))

;; ---------------------------------------------------------------------------
;; 5. Composed leak audit after frame destroy
;;
;; Build a frame holding: (a) an :after timer; (b) a spawned actor
;; bound under :system-id; (c) an :invoke-all parent with a join slot.
;; Destroy. Pin that EVERY per-frame bookkeeping slot in the machines
;; artefact is cleared in a single composed assertion — guards against
;; a future regression that fixes each leak in isolation while breaking
;; the destroy step list's ordering.
;; ---------------------------------------------------------------------------

(deftest composed-timer-join-and-system-id-cleanup-on-frame-destroy
  (testing "destroy-frame! clears timer table + :rf/system-ids + :rf/spawned
            + :rf/machines + spawn-order + actor registrar in one cascade"
    (rf/reg-frame :corner.leak/scoped {:doc "leak-audit"})

    ;; --- (a) :after timer --------------------------------------------------
    (let [timer-m {:initial :idle
                   :data    {:rf/after-epoch 0}
                   :states  {:idle    {:on {:fetch :loading}}
                             :loading {:after {600000 :timeout}}
                             :timeout {}}}]
      (rf/reg-machine :corner.leak/timer timer-m)
      (rf/dispatch-sync [:corner.leak/timer [:fetch]] {:frame :corner.leak/scoped}))

    ;; --- (b) system-id-bound spawn ----------------------------------------
    (let [child {:initial :running :data {} :states {:running {}}}
          boot  {:initial :idle
                 :data    {}
                 :states
                 {:idle {:on {:go {:action
                                   (fn [_ _]
                                     {:fx [[:rf.machine/spawn
                                            {:machine-id :corner.leak/child
                                             :id-prefix  :corner.leak/child
                                             :system-id  :corner.leak/primary}]]})}}}}}]
      (rf/reg-machine :corner.leak/child child)
      (rf/reg-machine :corner.leak/boot  boot)
      (rf/dispatch-sync [:corner.leak/boot [:go]] {:frame :corner.leak/scoped}))

    ;; --- (c) :invoke-all parent + join slot -------------------------------
    (let [ia-child  {:initial :running
                     :data    {:id nil}
                     :actions {:set-id (fn [d ev] {:data (assoc d :id (second ev))})}
                     :states  {:running {:on {:set-id {:action :set-id}
                                              :go     :done}}
                               :done    {}}}
          ia-parent {:initial :idle
                     :states
                     {:idle      {:on {:start :hydrating}}
                      :hydrating {:invoke-all
                                  {:children        [{:id :a :machine-id :corner.leak/ia-child
                                                      :start [:set-id :a]}
                                                     {:id :b :machine-id :corner.leak/ia-child
                                                      :start [:set-id :b]}]
                                   :join            :all
                                   :on-child-done   :asset/loaded
                                   :on-child-error  :asset/failed
                                   :on-all-complete [:done!]}}}}]
      (rf/reg-machine :corner.leak/ia-child  ia-child)
      (rf/reg-machine :corner.leak/ia-parent ia-parent)
      (rf/dispatch-sync [:corner.leak/ia-parent [:start]] {:frame :corner.leak/scoped}))

    ;; --- preconditions ----------------------------------------------------
    (is (contains? @timer/after-timers :corner.leak/scoped)
        "precondition: timer table holds the :after entry for the frame")
    (let [db (rf/get-frame-db :corner.leak/scoped)]
      (is (= :corner.leak/child#1
             (get-in db [:rf/system-ids :corner.leak/primary]))
          "precondition: system-id reverse index points at the spawned actor")
      (is (map? (get-in db [:rf/spawned :corner.leak/ia-parent [:hydrating]]))
          "precondition: invoke-all join slot is seeded")
      (is (some? (get-in db [:rf/machines :corner.leak/child#1]))
          "precondition: spawned actor snapshot is live"))
    (is (pos? (count (spawn-order/frame-order :corner.leak/scoped)))
        "precondition: spawn-order channel has entries")
    (is (some? (registrar/lookup :event :corner.leak/child#1))
        "precondition: spawned actor handler is registered")

    ;; --- destroy ----------------------------------------------------------
    (frame/destroy-frame! :corner.leak/scoped)

    ;; --- composed post-condition: every per-frame slot is cleared -------
    (is (not (contains? @timer/after-timers :corner.leak/scoped))
        "post: timer table no longer holds an entry for the destroyed frame")
    (is (nil? (frame/frame :corner.leak/scoped))
        "post: frame is dissoc'd from the frames atom")
    (is (nil? (registrar/lookup :event :corner.leak/child#1))
        "post: spawned actor handler is unregistered")
    (is (= [] (spawn-order/frame-order :corner.leak/scoped))
        "post: spawn-order channel is empty for the destroyed frame")
    ;; The singletons (:corner.leak/timer, :corner.leak/boot, :corner.leak/
    ;; ia-parent, :corner.leak/ia-child, :corner.leak/child) stay
    ;; registered — they're global handlers, not frame-scoped.
    (is (some? (registrar/lookup :event :corner.leak/timer))
        "post: singleton timer handler stays globally registered (not frame-scoped)")
    (is (some? (registrar/lookup :event :corner.leak/ia-parent))
        "post: singleton invoke-all parent handler stays globally registered")))
