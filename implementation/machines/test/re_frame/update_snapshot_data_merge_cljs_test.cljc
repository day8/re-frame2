(ns re-frame.update-snapshot-data-merge-cljs-test
  "The `:rf.machine/update-snapshot` `:data` leg MERGES onto the actor's `:data`,
  so a patch naming one user key keeps the reserved `:rf/*` slots the runtime
  keeps there. Each loss fails silently: a wiped `:rf/after-epoch` makes an
  in-flight `:after` timer arrive as stale and vanish, and a wiped
  `:rf/parent-id` makes a spawned child finish as a singleton, so its parent's
  `:on-done` never runs."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

;; The patch comes from a plain event handler: a machine action would need a
;; self-transition, which re-arms the `:after` timer and masks the loss. It is
;; registered inside each test because the reset fixture drops registrations
;; made at ns top level below `use-fixtures`.
(defn- reg-patch-event! []
  (rf/reg-event :dm/patch-data
    (fn [_ [_ actor-id data-patch]]
      {:fx [[:rf.machine/update-snapshot {:rf/machine-id actor-id
                                          :rf/patch      {:data data-patch}}]]})))

(deftest update-snapshot-data-patch-keeps-after-timer-live
  (testing "a {:data {:status :degraded}} patch leaves the actor's in-flight :after timer live"
    (reg-patch-event!)
    (rf/reg-machine :dm/timer
      {:initial :idle
       :data    {:status :ok}
       :states  {:idle    {:on {:fetch :loading}}
                 :loading {:after {5000 :timeout}}
                 :timeout {}}})
    (rf/dispatch-sync [:dm/timer [:fetch]])
    (let [epoch (get-in (snapshot :dm/timer) [:data :rf/after-epoch [:loading]])]
      ;; A zero epoch would still match the 0 a wiped epoch map reads as.
      (is (= 1 epoch))
      (rf/dispatch-sync [:dm/patch-data :dm/timer {:status :degraded}])
      (rf/dispatch-sync [:dm/timer [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
      (is (= [:timeout :degraded]
             ((juxt :state (comp :status :data)) (snapshot :dm/timer)))))))

(deftest update-snapshot-data-patch-keeps-spawned-child-parented
  (testing "a {:data ...} patch on a spawned child keeps :rf/parent-id, so the child still
            completes to its parent's :spawn :on-done"
    (reg-patch-event!)
    (rf/reg-machine :dm/child
      {:initial :running
       :data    {:status :ok}
       :states  {:running {:on {:finish {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
                 :done    {:final?     true
                           :output-key :token}}})
    (rf/reg-machine :dm/parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :dm/child
                                   :on-done    (fn [{data :data result :result}]
                                                 (assoc data :token-from-child result))}}}})
    (rf/dispatch-sync [:dm/parent [:start]])
    (let [child-id (get-in (snapshot :dm/parent) [:data :rf/spawned [:working]])]
      (rf/dispatch-sync [:dm/patch-data child-id {:status :degraded}])
      (is (= :degraded (get-in (snapshot child-id) [:data :status])))
      (rf/dispatch-sync [child-id [:finish :auth/token]])
      (is (= :auth/token (get-in (snapshot :dm/parent) [:data :token-from-child]))))))
