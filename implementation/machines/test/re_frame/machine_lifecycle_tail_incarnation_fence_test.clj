(ns re-frame.machine-lifecycle-tail-incarnation-fence-test
  "The TIMER, REGISTRAR and SPAWN-WRITE callbacks in the machine lifecycle
  tails each recheck exact-incarnation ownership, so a callback that destroys
  A and publishes a same-id successor B gets no A-derived write, cancellation
  or dispatch against B:

    - TIMER: each `:rf.machine.timer/cancelled` from `cancel-actor-timers!` is
      callback-bearing; the loop short-circuits after the losing cancellation
      and finalize rechecks before its classification / spawn-order work.
    - REGISTRAR: `rf.registrar/unregister!` emits a synchronous
      `:rf.registry/handler-cleared`; finalize rechecks before the `:on-error`
      dispatch.
    - SPAWN-WRITE: a container watch can replace A DURING `install-spawn!`'s
      write; the install rides `rf.frame/swap-runtime-db-exact!`, which binds
      to A's own container and reports the loss without bumping B's epoch.

  Each fixture drives the tail DIRECTLY under A's bound event owner with the
  destroyer on the callback's own stack."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.lifecycle-fx.finalize :as rf.machines.lifecycle-fx.finalize]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Touch the artefact so the machines registration hooks are wired even when
;; this ns runs in isolation.
(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ===========================================================================
;; TIMER seam — cancel-actor-timers! per-cancellation fence + finalize recheck
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
          :reaction        nil
          :sub-watcher-key nil
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

(deftest timer-cancel-loss-fences-loop-and-tail
  (testing "A has two armed timers. The FIRST cancellation's listener destroys A,
            publishes same-id B, re-arms B's timer under the SECOND key and
            records B's spawn-order. B's timer survives, exactly one
            :on-destroy cancellation fires, finalize never forgets B's
            spawn-order entry, and finalize returns the inert outcome."
    (rf.machines.spawn-order/reset-all!)
    (reset! rf.machines.timer/after-timers {})
    (let [frame-a    :rf2-4ipqe4/timer-loss-frame
          machine-id :rf2-4ipqe4/timer-loss
          k2         (timer-key machine-id 200)
          fired?     (atom false)
          cancelled  (atom [])]
      (rf/reg-machine machine-id (finishing-machine frame-a))
      (rf/make-frame {:id frame-a})
      (seed-finishing-runtime-db! frame-a machine-id)
      (seed-timer! frame-a machine-id 100 ::a-tok-1)
      (seed-timer! frame-a machine-id 200 ::a-tok-2)
      (rf.trace.tooling/register-listener!
        ::timer-fence
        (fn [ev]
          (when (= :rf.machine.timer/cancelled (:operation ev))
            (swap! cancelled conj ev)
            (when (compare-and-set! fired? false true)
              (rf.frame/destroy-frame! frame-a)
              (rf/make-frame {:id frame-a})
              (seed-timer! frame-a machine-id 200 ::b-tok-2)
              (rf.machines.spawn-order/record! frame-a machine-id)))))
      (try
        (let [rt-a (rf.machines.test-support/runtime-db frame-a)
              ret  (finalize-under-owner frame-a machine-id rt-a)]
          ;; destroy-frame! cancels A's other timer itself, with
          ;; :reason :on-frame-destroy — the loop never reaches it.
          (is (= {:b-k2-token  ::b-tok-2
                  :on-destroy  1
                  :spawn-order [machine-id]
                  :ret         {:rf.db/runtime rt-a :fx []}}
                 {:b-k2-token  (get-in @rf.machines.timer/after-timers [frame-a k2 :token])
                  :on-destroy  (count (filter #(= :on-destroy (get-in % [:tags :reason])) @cancelled))
                  :spawn-order (rf.machines.spawn-order/frame-order frame-a)
                  :ret         ret})))
        (finally
          (rf.trace.tooling/unregister-listener! ::timer-fence))))))

(deftest timer-cancel-live-owner-cancels-all
  (testing "control: with no loss, finalize cancels BOTH timers and tears the
            actor's snapshot down — the fence is scoped to owner loss"
    (rf.machines.spawn-order/reset-all!)
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

;; ===========================================================================
;; REGISTRAR seam — recheck after handler-cleared before the :on-error dispatch
;; ===========================================================================

(deftest registrar-clear-loss-fences-on-error-dispatch
  (testing "a :rf.registry/handler-cleared listener (fired by the erroring
            child's unregister) destroys A and publishes same-id B: the stale
            spawn-error (:on-error) dispatch never routes into B and finalize
            returns the inert outcome"
    (rf.machines.spawn-order/reset-all!)
    (let [frame-a        :rf2-4ipqe4/registrar-loss-frame
          parent-id      :rf2-4ipqe4/registrar-loss-parent
          child-id       :rf2-4ipqe4/registrar-loss-child
          invoke-id      [:waiting]
          child-snapshot {:state :failed
                          :data  {:err "boom" :rf/parent-id parent-id :rf/invoke-id invoke-id}}
          fired?         (atom false)
          dispatches     (atom [])
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (rf/reg-machine parent-id
        {:initial :waiting
         :states  {:waiting {:spawn {:machine-id :rf2-4ipqe4/some-child
                                     :on-error   {:target :recover}}}
                   :recover {}}})
      ;; A PLAIN entry, not a reg-machine definition: finalize clears only a
      ;; per-instance entry, and unregister! emits handler-cleared only when
      ;; something was present.
      (rf.registrar/register! :event child-id {:fn (fn [db _] db) :rf/provenance :A})
      (rf/make-frame {:id frame-a})
      ;; The parent needs a live INSTANCE, or the :on-error dispatch is
      ;; stale-suppressed before the fence is consulted.
      (rf.frame/swap-runtime-db!
        frame-a (fn [rt] (-> rt
                             (assoc-in [:rf.runtime/machines :snapshots child-id] child-snapshot)
                             (assoc-in [:rf.runtime/machines :snapshots parent-id]
                                       {:state :waiting :data {}}))))
      (rf.trace.tooling/register-listener!
        ::registrar-fence
        (fn [ev] (when (and (= :rf.registry/handler-cleared (:operation ev))
                            (compare-and-set! fired? false true))
                   (rf.frame/destroy-frame! frame-a)
                   (rf/make-frame {:id frame-a}))))
      (try
        (rf.late-bind/set-fn! :router/dispatch!
                           (fn [ev opts] (swap! dispatches conj [ev opts]) nil))
        (let [rt-a (rf.machines.test-support/runtime-db frame-a)
              ret  (rf.frame/call-with-event-owner-token frame-a (rf.frame/frame-incarnation-token frame-a)
                     (fn []
                       (rf.machines.lifecycle-fx.finalize/finalize-machine
                         {:initial  :failed
                          :rf/frame :ignored
                          :rf/cofx  {:rf/time-ms 0}
                          :data     (:data child-snapshot)
                          :states   {:failed {:final? true :error? true :output-key :err}}}
                         child-id frame-a rt-a child-snapshot [:some-completing-event] [])))]
          (is (= [[] {:rf.db/runtime rt-a :fx []}] [@dispatches ret])))
        (finally
          (rf.trace.tooling/unregister-listener! ::registrar-fence)
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!))))))

;; ===========================================================================
;; SPAWN-WRITE seam — install through swap-runtime-db-exact! (container watch)
;; ===========================================================================

(defn- install-watching-adapter!
  "Install a plain-atom-backed adapter whose `replace-container!` runs `on-write`
  exactly once, the first time a container write happens while `armed?` holds.
  The physical write always lands FIRST."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(deftest spawn-install-write-watch-loss-fences-tail
  (testing "a container watch that destroys A and publishes same-id B DURING
            the spawn install write: no A-derived snapshot lands on B, B's
            commit epoch is not bumped, and no spawn-order entry or
            :rf.machine.lifecycle/spawned is attributed after the loss"
    (rf.machines.spawn-order/reset-all!)
    (rf/reg-machine :rf2-4ipqe4/spawn-write-child
      {:initial :running
       :states  {:running {:on {:go :done}}
                 :done    {:final? true}}})
    (let [frame-a        :rf2-4ipqe4/spawn-write-frame
          armed?         (atom false)
          traces         (atom [])
          b-birth        (atom nil)
          b-commit       (atom nil)
          orig-dispatch! (rf.late-bind/get-fn :router/dispatch!)]
      (install-watching-adapter!
        armed?
        (fn []
          (rf.frame/destroy-frame! frame-a)
          (rf/make-frame {:id frame-a})
          (reset! b-birth (rf.machines.test-support/runtime-db frame-a))
          (reset! b-commit (rf.frame/frame-commit-epoch frame-a))))
      (try
        ;; A synchronous no-op router keeps any `:start` dispatch off the JVM
        ;; background executor, so nothing races the assertions.
        (rf.late-bind/set-fn! :router/dispatch! (fn [_ev _opts] nil))
        (rf/make-frame {:id frame-a})
        (rf.trace.tooling/register-listener!
          ::spawn-write-fence
          (fn [ev] (swap! traces conj ev)))
        (let [token-a (rf.frame/frame-incarnation-token frame-a)]
          (reset! armed? true)
          (rf.frame/call-with-event-owner-token frame-a token-a
            (fn [] (rf.machines.lifecycle-fx.spawn/spawn-fx {:frame frame-a}
                                   {:machine-id :rf2-4ipqe4/spawn-write-child
                                    :start      [:go]})))
          ;; b-birth is nil unless the watch ran, so this also proves the
          ;; fence was exercised.
          (is (= [@b-birth @b-commit [] []]
                 [(rf.machines.test-support/runtime-db frame-a)
                  (rf.frame/frame-commit-epoch frame-a)
                  (rf.machines.spawn-order/frame-order frame-a)
                  (filterv #(= :rf.machine.lifecycle/spawned (:operation %)) @traces)])))
        (finally
          (rf.trace.tooling/unregister-listener! ::spawn-write-fence)
          (rf.late-bind/set-fn! :router/dispatch! orig-dispatch!)
          (restore-plain-adapter!))))))
