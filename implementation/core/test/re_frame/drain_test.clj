(ns re-frame.drain-test
  "Run-to-completion drain semantics (Spec 002 §Run-to-completion dispatch):
  `:fx` dispatches drain in the same cycle, a `:drain-depth` of N admits
  exactly N events and halts the next, a terminating cascade of exactly N
  settles, a nested `dispatch-sync` is rejected, and a dispatch to another
  frame takes that frame's own drain.

  ## Posture split

  The semantics, and the drain-depth halt's always-on `:errors` record, are
  asserted unguarded and run under `scripts/test-core-prod-gate.sh` too.
  Dev-trace assertions sit in `(when rf.interop/debug-enabled? …)` arms,
  because every `trace/emit-error!` site is elided under
  `-Dre-frame.debug=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; The error-listener registry is a `defonce` atom.
  (rf.error-emit/clear-error-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; `init!` registers no frame. Explicit `{:frame …}` opts in the bodies
  ;; still win over this ambient scope.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- drain-depth-trace [traces]
  (some #(when (= :rf.error/drain-depth-exceeded (:operation %)) %) traces))

;; ---- drain depth ----------------------------------------------------------

(deftest drain-depth-halts-after-exactly-drain-depth-events
  ;; Spec 002 §Run-to-completion rule 3. The router halts at
  ;; `(>= depth drain-depth)` with an event pending, so a runaway cascade
  ;; under :drain-depth N runs exactly N handlers. Each keeps its own write
  ;; (there is no whole-drain rollback), and the halt clears the queue so no
  ;; stuck work survives it.
  (let [traces (atom [])]
    (rf/register-listener! :trace ::halt (fn [ev] (swap! traces conj ev)))
    (rf/make-frame {:id :drain.test/loop :drain-depth 4})
    (rf/reg-event :loop
      (fn [{:keys [db]} _]
        {:db (update db :n (fnil inc 0))
         :fx [[:dispatch [:loop]]]}))
    (rf/dispatch-sync [:loop] {:frame :drain.test/loop})
    (rf/unregister-listener! :trace ::halt)
    (is (= 4 (:n (rf/app-db-value :drain.test/loop)))
        "exactly :drain-depth handlers ran, and every write is durable")
    (let [router @(:router (rf.frame/frame :drain.test/loop))]
      (is (= {:queued 0 :scheduled? false}
             {:queued (count (:queue router)) :scheduled? (:scheduled? router)})
          "the halt leaves no pending work, so the next dispatch re-engages a drain"))
    (when rf.interop/debug-enabled?
      (is (= {:frame :drain.test/loop :depth 4 :last-event [:loop] :rollback? false}
             (select-keys (:tags (drain-depth-trace @traces))
                          [:frame :depth :last-event :rollback?]))))))

(deftest drain-depth-exceeded-fans-out-on-the-always-on-axis-with-cycle-evidence
  ;; The dev trace is elided in production, so the halt also fans a
  ;; structural record out on the always-on axis. `:tail-event-ids` (the last
  ;; settled ids) is the cycle evidence. Ids and counts only: no event args
  ;; (`:last-event`) and no `:reason` prose ride this axis.
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::always-on
                                           (fn [rec] (swap! records conj rec)))
    (rf/make-frame {:id :drain.test/ping-pong :drain-depth 6})
    (rf/reg-event :ping (fn [_ _] {:fx [[:dispatch [:pong]]]}))
    (rf/reg-event :pong (fn [_ _] {:fx [[:dispatch [:ping]]]}))
    (rf/dispatch-sync [:ping] {:frame :drain.test/ping-pong})
    (rf.error-emit/unregister-error-listener! ::always-on)
    (is (= {:frame          :drain.test/ping-pong
            :depth          6
            :queue-size     1
            :rollback?      false
            :recovery       :no-recovery
            :last-event-id  :pong
            :tail-event-ids [:ping :pong :ping :pong :ping :pong]}
           (select-keys (some #(when (= :rf.error/drain-depth-exceeded (:error %)) %) @records)
                        [:frame :depth :queue-size :rollback? :recovery
                         :last-event-id :tail-event-ids :reason :last-event])))))

(deftest drain-of-exactly-drain-depth-events-settles-with-no-halt
  ;; `depth` counts settled events, so a cascade that terminates after exactly
  ;; N events reaches depth N with an empty queue. A halt there would name an
  ;; event that had settled :ok; a halt presupposes a next event.
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::exact (fn [rec] (swap! records conj rec)))
    (rf/make-frame {:id :drain.test/exact :drain-depth 4})
    (rf/reg-event :tick
      (fn [{:keys [db]} _]
        (let [n (inc (:n db 0))]
          (cond-> {:db (assoc db :n n)}
            (< n 4) (assoc :fx [[:dispatch [:tick]]])))))
    (rf/dispatch-sync [:tick] {:frame :drain.test/exact})
    (rf.error-emit/unregister-error-listener! ::exact)
    (is (= 4 (:n (rf/app-db-value :drain.test/exact)))
        "the cascade ran exactly :drain-depth events")
    (is (empty? (filter #(= :rf.error/drain-depth-exceeded (:error %)) @records))
        "a clean cascade of exactly :drain-depth events does not halt")))

;; ---- dispatch-sync in a handler -------------------------------------------

(deftest dispatch-sync-in-handler-jvm
  ;; Spec 002 §Run-to-completion §Render boundaries: dispatch-sync from
  ;; inside a handler is rejected, not merely warned about.
  (testing "a handler calling dispatch-sync directly"
    (let [traces (atom [])]
      (rf/register-listener! :trace ::dsih (fn [ev] (swap! traces conj ev)))
      (rf/reg-event :leaf (fn [{:keys [db]} _] {:db (assoc db :leaf? true)}))
      (rf/reg-event :nested-direct
        (fn [_ _]
          (rf/dispatch-sync [:leaf])
          {}))
      (rf/dispatch-sync [:nested-direct])
      (rf/unregister-listener! :trace ::dsih)
      (is (nil? (:leaf? (rf/app-db-value :rf/default)))
          "the rejected inner event never ran")
      (when rf.interop/debug-enabled?
        (let [err (some #(when (and (= :rf.error/dispatch-sync-in-handler (:operation %))
                                    (= :error (:op-type %))
                                    (= :no-recovery (:recovery %)))
                           %)
                        @traces)]
          ;; Spec-Schemas §DispatchSyncInHandlerTags.
          (is (= {:rf.event/v [:leaf] :frame :rf/default}
                 (select-keys (:tags err) [:rf.event/v :frame])))))))
  (testing "dispatch-sync reached through a user fx: the guard keys off the drain, not the handler call"
    (rf/reg-event :leaf2 (fn [{:keys [db]} _] {:db (assoc db :leaf2? true)}))
    (rf/reg-fx :user.fx/sync-dispatch
      {:platforms #{:server :client}}
      (fn [_ ev] (rf/dispatch-sync ev)))
    (rf/reg-event :nested-via-fx
      (fn [_ _] {:fx [[:user.fx/sync-dispatch [:leaf2]]]}))
    (rf/dispatch-sync [:nested-via-fx])
    (is (nil? (:leaf2? (rf/app-db-value :rf/default)))
        "the inner event never ran")))

;; ---- in-cycle and cross-frame dispatch ------------------------------------

(deftest async-dispatch-resolves-after-current-drain
  ;; Spec 002 §Run-to-completion: `:fx [[:dispatch …]]` children drain in the
  ;; same cycle, so the whole cascade is visible when dispatch-sync returns.
  (let [order (atom [])]
    (rf/reg-event :seed
      (fn [_ _]
        (swap! order conj :seed)
        {:db {:n 0}
         :fx [[:dispatch [:bump]]
              [:dispatch [:bump]]]}))
    (rf/reg-event :bump
      (fn [{:keys [db]} _]
        (swap! order conj :bump)
        {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed])
    (is (= [:seed :bump :bump] @order)
        "both :fx dispatches ran inside the same dispatch-sync cycle")
    (is (= 2 (:n (rf/app-db-value :rf/default)))
        "their effects are visible the moment dispatch-sync returns")))

(deftest per-frame-drain-isolation
  ;; Spec 002 §Run-to-completion rule 1: there is no cross-frame drain. A
  ;; dispatch to frame B from inside A's handler goes through B's own async
  ;; drain, and runs against B.
  (rf/make-frame {:id :drain.test/A})
  (rf/make-frame {:id :drain.test/B})
  (let [b-done (promise)]
    ;; Delivered from an fx, which runs after B's :db commit.
    (rf/reg-fx :drain.test/b-done (fn [_ _] (deliver b-done :ok)))
    (rf/reg-event :A/work
      (fn [_ _]
        (rf/dispatch [:B/work] {:frame :drain.test/B})
        {}))
    (rf/reg-event :B/work
      (fn [{:keys [db]} _]
        {:db (assoc db :b-ran? true)
         :fx [[:drain.test/b-done]]}))
    (rf/dispatch-sync [:A/work] {:frame :drain.test/A})
    (is (= :ok (deref b-done 2000 :timeout))
        "B's drain fires on the executor")
    (is (true? (:b-ran? (rf/app-db-value :drain.test/B)))
        "B's handler ran against B")))
