(ns re-frame.lifecycle-composed-corners-test
  "A composed leak audit: after `destroy-frame!`, every per-frame piece of
  machines bookkeeping — the timer table, the `[:rf.runtime/machines
  :spawned]` slot and the spawn-order channel — is cleared together.

  Per-edge coverage lives in `timer_frame_scope_test`, `after_test`,
  `spawn_all_test`, `final_state_cljs_test`, `spawn_registry_test`,
  `frame_destroy_cascade_test` and `destroyed_trace_shape_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; Composed leak audit after frame destroy
;;
;; Build a frame holding: (a) an :after timer; (b) a spawned actor;
;; (c) a :spawn-all parent with a join slot.
;; Destroy. Pin that EVERY per-frame bookkeeping slot in the machines
;; artefact is cleared in a single composed assertion — guards against
;; a future regression that fixes each leak in isolation while breaking
;; the destroy step list's ordering.
;; ---------------------------------------------------------------------------

(deftest composed-timer-join-and-actor-cleanup-on-frame-destroy
  (testing "destroy-frame! clears timer table + [:rf.runtime/machines :spawned]
            + [:rf.runtime/machines :snapshots] + spawn-order in one cascade (spawned actors carry no registrar entry)"
    (rf/make-frame {:id :corner.leak/scoped :doc "leak-audit"})

    ;; --- (a) :after timer --------------------------------------------------
    (let [timer-m {:initial :idle
                   :data    {}
                   :states  {:idle    {:on {:fetch :loading}}
                             :loading {:after {600000 :timeout}}
                             :timeout {}}}]
      (rf/reg-machine :corner.leak/timer timer-m)
      (rf/dispatch-sync [:corner.leak/timer [:fetch]] {:frame :corner.leak/scoped}))

    ;; --- (b) spawned actor -------------------------------------------------
    (let [child {:initial :running :data {} :states {:running {}}}
          boot  {:initial :idle
                 :data    {}
                 :states
                 {:idle {:on {:go {:action (fn [_]
                                     {:fx [[:rf.machine/spawn
                                            {:machine-id :corner.leak/child
                                             :id-prefix  :corner.leak/child}]]})}}}}}]
      (rf/reg-machine :corner.leak/child child)
      (rf/reg-machine :corner.leak/boot  boot)
      (rf/dispatch-sync [:corner.leak/boot [:go]] {:frame :corner.leak/scoped}))

    ;; --- (c) :spawn-all parent + join slot -------------------------------
    (let [ia-child  {:initial :running
                     :data    {:id nil}
                     :actions {:set-id (fn [{d :data ev :event}] {:data (assoc d :id (second ev))})}
                     :states  {:running {:on {:set-id {:action :set-id}
                                              :go     :done}}
                               :done    {}}}
          ia-parent {:initial :idle
                     :states
                     {:idle      {:on {:start :hydrating}}
                      :hydrating {:spawn-all
                                  {:children        [{:id :a :machine-id :corner.leak/ia-child
                                                      :start [:set-id :a]}
                                                     {:id :b :machine-id :corner.leak/ia-child
                                                      :start [:set-id :b]}]
                                   :join            :all
                                   :on-all-complete [:done!]}}}}]
      (rf/reg-machine :corner.leak/ia-child  ia-child)
      (rf/reg-machine :corner.leak/ia-parent ia-parent)
      (rf/dispatch-sync [:corner.leak/ia-parent [:start]] {:frame :corner.leak/scoped}))

    ;; --- preconditions ----------------------------------------------------
    (is (contains? @rf.machines.timer/after-timers :corner.leak/scoped)
        "precondition: timer table holds the :after entry for the frame")
    (let [db (:rf.db/runtime (rf/frame-state-value :corner.leak/scoped))]
      (is (map? (get-in db [:rf.runtime/machines :spawned :corner.leak/ia-parent [:hydrating]]))
          "precondition: invoke-all join slot is seeded")
      (is (some? (get-in db [:rf.runtime/machines :snapshots :corner.leak/child#1]))
          "precondition: spawned actor snapshot is live"))
    (is (pos? (count (rf.machines.spawn-order/frame-order :corner.leak/scoped)))
        "precondition: spawn-order channel has entries")
    ;; A spawned actor carries NO per-instance registrar entry; its
    ;; liveness is its snapshot's presence in runtime-db (asserted live above).
    ;; The registrar precondition is therefore inverted.
    (is (nil? (rf.registrar/lookup :event :corner.leak/child#1))
        "precondition: spawned actor has no per-instance registrar entry (liveness lives in its runtime-db snapshot)")

    ;; --- destroy ----------------------------------------------------------
    (rf.frame/destroy-frame! :corner.leak/scoped)

    ;; --- composed post-condition: every per-frame slot is cleared -------
    (is (not (contains? @rf.machines.timer/after-timers :corner.leak/scoped))
        "post: timer table no longer holds an entry for the destroyed frame")
    (is (nil? (rf.frame/frame :corner.leak/scoped))
        "post: frame is dissoc'd from the frames atom")
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :corner.leak/scoped))
                      [:rf.runtime/machines :snapshots :corner.leak/child#1]))
        "post: spawned actor's snapshot is gone — its liveness (snapshot-based) is cleared")
    (is (= [] (rf.machines.spawn-order/frame-order :corner.leak/scoped))
        "post: spawn-order channel is empty for the destroyed frame")
    ;; The singletons (:corner.leak/timer, :corner.leak/boot, :corner.leak/
    ;; ia-parent, :corner.leak/ia-child, :corner.leak/child) stay
    ;; registered — they're global handlers, not frame-scoped.
    (is (some? (rf.registrar/lookup :event :corner.leak/timer))
        "post: singleton timer handler stays globally registered (not frame-scoped)")
    (is (some? (rf.registrar/lookup :event :corner.leak/ia-parent))
        "post: singleton invoke-all parent handler stays globally registered")))
