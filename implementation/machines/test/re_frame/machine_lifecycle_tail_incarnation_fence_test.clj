(ns re-frame.machine-lifecycle-tail-incarnation-fence-test
  "The machine finalize tail's durable writes under an event owner: finalize
  cancels every armed `:after` timer of the finishing actor and tears its
  snapshot down. The fixture drives the tail DIRECTLY under A's bound event
  owner."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ===========================================================================
;; TIMER seam — finalize cancels the finishing actor's timers
;; ===========================================================================

(defn- finishing-machine
  "A runtime-stamped finished singleton spec resting on a `:final?` leaf."
  [frame-id]
  {:initial  :done
   :rf/frame frame-id
   :rf/cofx  {:rf/time-ms 0}
   :data     {:result 42}
   :states   {:done {:final? true :output-key :result}}})

(defn- finishing-snapshot []
  {:state :done :data {:result 42}})

(defn- seed-finishing-runtime-db! [frame-id machine-id]
  (rf.frame/swap-runtime-db!
    frame-id
    (fn [rt] (assoc-in rt [:rf.runtime/machines :snapshots machine-id]
                       (finishing-snapshot)))))

(defn- timer-key [parent-id delay-key]
  {:parent parent-id :spawn [] :delay delay-key})

(defn- seed-timer!
  "Install a minimal armed-timer entry carrying a unique `token` and a nil host
  handle (release is then a no-op) — enough for `cancel-actor-timers!` to claim
  it and emit `:rf.machine.timer/cancelled`."
  [frame-id parent-id delay-key token]
  (swap! rf.machines.timer/after-timers assoc-in
         [frame-id (timer-key parent-id delay-key)]
         {:handle          nil
          :resolved-ms     delay-key
          :epoch           0
          :state           :done
          :region          nil
          :delay-source    :literal
          :token           token}))

(defn- finalize-under-owner [frame-a machine-id rt]
  (rf.frame/call-with-event-owner-token frame-a (rf.frame/frame-incarnation-token frame-a)
    (fn []
      (rf.machines.lifecycle-fx.finalize/finalize-machine
        (finishing-machine frame-a) machine-id frame-a rt
        (finishing-snapshot) [:some-completing-event] []))))

(deftest timer-cancel-live-owner-cancels-all
  (testing "finalize cancels BOTH timers and tears the actor's snapshot down"
    (reset! rf.machines.timer/after-timers {})
    (let [frame-a    :rf2-4ipqe4/timer-live-frame
          machine-id :rf2-4ipqe4/timer-live
          cancelled  (atom 0)]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing-runtime-db! frame-a machine-id)
      (seed-timer! frame-a machine-id 100 ::live-tok-1)
      (seed-timer! frame-a machine-id 200 ::live-tok-2)
      (rf.trace.tooling/register-listener!
        ::timer-live
        (fn [ev] (when (= :rf.machine.timer/cancelled (:operation ev))
                   (swap! cancelled inc))))
      (try
        (let [ret (finalize-under-owner frame-a machine-id
                                        (rf.machines.test-support/runtime-db frame-a))]
          (is (= [2 nil nil]
                 [@cancelled
                  (not-empty (get @rf.machines.timer/after-timers frame-a))
                  (get-in (:rf.db/runtime ret) [:rf.runtime/machines :snapshots machine-id])])))
        (finally
          (rf.trace.tooling/unregister-listener! ::timer-live))))))
