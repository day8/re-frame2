(ns re-frame.spawn-data-fn-form-test
  "A spawn's `:data` may be a fn of the triggering event and the parent's
  snapshot, for `:spawn` and each `:spawn-all` child; a throw from it is a
  `:rf.error/machine-action-exception` (Spec 005 §Spawn-spec keys, §Errors)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private child {:initial :running :data {} :states {:running {}}})

(deftest fn-form-data-sees-triggering-event
  (rf/reg-machine :worker/proc child)
  (rf/reg-machine :sup/event-form
    {:initial :idle
     :states  {:idle    {:on {:fetch :working}}
               :working {:spawn {:machine-id :worker/proc
                                 :data       (fn [{ev :event}] {:from-event ev})}}}})
  (rf/dispatch-sync [:sup/event-form [:fetch :req-1]])
  (is (= [:fetch :req-1] (get-in (snapshot :worker/proc#1) [:data :from-event]))))

(deftest fn-form-data-throw-routes-to-machine-action-exception
  (rf/reg-machine :worker/proc child)
  (rf/reg-machine :sup/throwing
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :worker/proc
                                 :data       (fn [_] (throw (ex-info "boom" {:why :test})))}}}})
  (rf.machines.test-support/with-trace-capture traces
    (rf/dispatch-sync [:sup/throwing [:start]])
    (is (nil? (snapshot :worker/proc#1)) "the cascade halted before the spawn")
    (let [parent-snap (snapshot :sup/throwing)]
      (is (or (nil? parent-snap) (= :idle (:state parent-snap)))
          "the parent did not commit the transition"))
    (is (some #(and (= :error (:op-type %))
                    (= :rf.error/machine-action-exception (:operation %)))
              @traces))))

(deftest spawn-all-child-data-fn-form-is-materialised
  (rf/reg-machine :hydra/leaf child)
  (rf/reg-machine :sup/all
    {:initial :idle
     :data    {:base "/api"}
     :states  {:idle      {:on {:fan-out :hydrating}}
               :hydrating {:spawn-all
                           {:children        [{:id         :one
                                               :machine-id :hydra/leaf
                                               :data       (fn [{snap :snapshot}]
                                                             {:url (str (-> snap :data :base) "/one")})}
                                              {:id         :two
                                               :machine-id :hydra/leaf
                                               :data       (fn [{snap :snapshot}]
                                                             {:url (str (-> snap :data :base) "/two")})}]
                            :on-all-complete [:done]}}}})
  (rf/dispatch-sync [:sup/all [:fan-out]])
  (is (= ["/api/one" "/api/two"]
         (mapv #(get-in (snapshot %) [:data :url]) [:hydra/leaf#1 :hydra/leaf#2]))))
