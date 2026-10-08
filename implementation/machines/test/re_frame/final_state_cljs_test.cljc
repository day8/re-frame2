(ns re-frame.final-state-cljs-test
  "The `:final?` / `:on-done` / `:output-key` contract (Spec 005 §Final
  states, D1-D10): entering a `:final?` state auto-destroys the instance,
  emits `:rf.machine/done`, and routes the `:output-key` slot to the
  spawning parent."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- traces-for
  [traces operation]
  (filter #(= operation (:operation %)) @traces))

(defn- record-traces!
  [k]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- spawned-at-working [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:working]]))

(defn- first-instance [type-id]
  (keyword (namespace type-id) (str (name type-id) "#1")))

(defn- spawn-and-finish!
  "Spawn `child-type` under `parent-id`, whose `:on-done` records its result,
  send the child `[:fin]`, and return the recorded result (`:unset` when
  `:on-done` never ran)."
  [parent-id child-type]
  (let [seen (atom :unset)]
    (rf/reg-machine parent-id
      {:initial :working
       :states  {:working {:spawn {:machine-id child-type
                                   :on-done    (fn [{d :data r :result}] (reset! seen r) d)}}}})
    (rf/dispatch-sync [parent-id [:rf.machine.spawn/spawned]])
    (rf/dispatch-sync [(first-instance child-type) [:fin]])
    @seen))

(deftest child-final-state-fires-on-done-with-output
  (rf/reg-machine :rf2-gn80/child
    {:initial :running
     :data    {}
     :states
     {:running {:on {:finish {:target :done
                              :action (fn [{data :data ev :event}]
                                        {:data (assoc data :token (second ev))})}}}
      :done    {:final?     true
                :output-key :token}}})
  (rf/reg-machine :rf2-gn80/parent
    {:initial :idle
     :data    {}
     :states
     {:idle    {:on {:start :working}}
      :working {:spawn {:machine-id :rf2-gn80/child
                        :on-done    (fn [{data :data result :result}]
                                      (assoc data :token-from-child result))}}}})
  (rf/dispatch-sync [:rf2-gn80/parent [:start]])
  (let [child (spawned-at-working :rf2-gn80/parent)]
    (rf/dispatch-sync [child [:finish :auth/secret-token]])
    (is (= [:auth/secret-token nil nil]
           [(get-in (snapshot :rf2-gn80/parent) [:data :token-from-child])
            (snapshot child)
            (spawned-at-working :rf2-gn80/parent)])
        "the parent's :on-done got the :output-key slot; the child's snapshot and spawn slot are cleared")))

(deftest done-trace-fires-with-actor-id-output-parent-id
  (let [traces (record-traces! ::done-trace)]
    (rf/reg-machine :rf2-gn80/child2
      {:initial :running
       :data    {}
       :states
       {:running {:on {:finish {:target :done
                                :action (fn [{data :data ev :event}]
                                          {:data (assoc data :result (second ev))})}}}
        :done    {:final?     true
                  :output-key :result}}})
    (rf/reg-machine :rf2-gn80/parent2
      {:initial :working
       :states  {:working {:spawn {:machine-id :rf2-gn80/child2}}}})
    (rf/dispatch-sync [:rf2-gn80/parent2 [:rf.machine.spawn/spawned]])
    (let [child (spawned-at-working :rf2-gn80/parent2)]
      (rf/dispatch-sync [child [:finish 42]])
      (is (= [{:actor-id child :output 42 :parent-id :rf2-gn80/parent2}]
             (map #(select-keys (:tags %) [:actor-id :output :parent-id])
                  (traces-for traces :rf.machine/done)))))))

(deftest dispatch-to-done-singleton-recreates-from-its-surviving-definition
  ;; D7: a singleton reaching :final? auto-destroys and emits one
  ;; :rf.machine/done with a nil :parent-id. D5: its surviving DEFINITION
  ;; births a fresh instance for the next ORDINARY event.
  (let [traces (record-traces! ::recreated)]
    (rf/reg-machine :rf2-gn80/finalised
      {:initial :running
       :states  {:running {:on {:fin :done}}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:rf2-gn80/finalised [:fin]])
    (rf/dispatch-sync [:rf2-gn80/finalised [:something]])
    (is (= [[nil] :running]
           [(map (comp :parent-id :tags) (traces-for traces :rf.machine/done))
            (:state (snapshot :rf2-gn80/finalised))]))))

(deftest child-snapshot-cleared-at-child-teardown
  ;; The parent's :on-done fold runs at the PARENT's boundary on the
  ;; completion carrier, after the child is torn down, so it never sees a
  ;; live child.
  (let [on-done-saw-snapshot (atom :unset)]
    (rf/reg-machine :rf2-gn80/sid-child
      {:initial :running
       :states  {:running {:on {:fin :done}}
                 :done    {:final? true}}})
    (rf/reg-machine :rf2-gn80/sid-parent
      {:initial :working
       :states  {:working {:spawn {:machine-id :rf2-gn80/sid-child
                                   :on-done    (fn [{d :data}]
                                                 (reset! on-done-saw-snapshot
                                                         (snapshot :rf2-gn80/sid-child#1))
                                                 d)}}}})
    (rf/dispatch-sync [:rf2-gn80/sid-parent [:rf.machine.spawn/spawned]])
    (rf/dispatch-sync [:rf2-gn80/sid-child#1 [:fin]])
    (is (nil? @on-done-saw-snapshot))))

(deftest final-without-output-key-passes-nil-to-on-done
  (rf/reg-machine :rf2-gn80/no-output
    {:initial :running
     :states  {:running {:on {:fin :done}}
               :done    {:final? true}}})
  (is (nil? (spawn-and-finish! :rf2-gn80/observer :rf2-gn80/no-output))))

(deftest parallel-output-key-on-non-first-region-reported
  ;; :alpha, the FIRST region, declares no :output-key; finalize scans on to :beta's.
  (rf/reg-machine :rf2-gn80/par-child
    {:type    :parallel
     :regions {:alpha {:initial :run
                       :states  {:run  {:on {:fin :done}}
                                 :done {:final? true}}}
               :beta  {:initial :run
                       :states  {:run  {:on {:fin {:target :done
                                                   :action (fn [{data :data}]
                                                             {:data (assoc data :payload :beta/value)})}}}
                                 :done {:final?     true
                                        :output-key :payload}}}}})
  (is (= :beta/value (spawn-and-finish! :rf2-gn80/par-observer :rf2-gn80/par-child))))

(deftest parallel-output-key-conflict-emits-error-and-first-region-wins
  (let [traces (record-traces! ::okey-conflict)]
    (rf/reg-machine :rf2-gn80/par-conflict-child
      {:type    :parallel
       :regions {:alpha {:initial :run
                         :states  {:run  {:on {:fin {:target :done
                                                     :action (fn [{data :data}]
                                                               {:data (assoc data :a-out :alpha/value)})}}}
                                   :done {:final?     true
                                          :output-key :a-out}}}
                 :beta  {:initial :run
                         :states  {:run  {:on {:fin {:target :done
                                                     :action (fn [{data :data}]
                                                               {:data (assoc data :b-out :beta/value)})}}}
                                   :done {:final?     true
                                          :output-key :b-out}}}}})
    (is (= :alpha/value
           (spawn-and-finish! :rf2-gn80/par-conflict-observer :rf2-gn80/par-conflict-child)))
    (is (seq (traces-for traces :rf.error/machine-parallel-output-key-conflict)))))

(deftest parallel-error-final-on-non-first-region-routes-as-error
  ;; :alpha, the FIRST region, ends on a plain final and :beta on an :error?
  ;; final: the finish is an ERROR, routed to :on-error with :beta's payload.
  (rf/reg-machine :rf2-encnvn/par-err-child
    {:type    :parallel
     :regions {:alpha {:initial :run
                       :states  {:run  {:on {:fin :done}}
                                 :done {:final? true}}}
               :beta  {:initial :run
                       :states  {:run    {:on {:fin {:target :failed
                                                     :action (fn [{data :data}]
                                                               {:data (assoc data :err :beta/boom)})}}}
                                 :failed {:final?     true
                                          :error?     true
                                          :output-key :err}}}}})
  (rf/reg-machine :rf2-encnvn/par-err-parent
    {:initial :working
     :data    {}
     :states
     {:working {:spawn {:machine-id :rf2-encnvn/par-err-child
                        ;; the event is [:rf.machine.spawn/error <invoke-id> <error>]
                        :on-error   {:target :errored
                                     :action (fn [{data :data ev :event}]
                                               {:data (assoc data :captured (nth ev 2))})}}}
      :errored {}}})
  (rf/dispatch-sync [:rf2-encnvn/par-err-parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [:rf2-encnvn/par-err-child#1 [:fin]])
  (let [parent (snapshot :rf2-encnvn/par-err-parent)]
    (is (= [:errored :beta/boom] [(:state parent) (get-in parent [:data :captured])]))))
