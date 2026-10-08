(ns re-frame.machine-spawn-invalid-definition-test
  "An invalid inline `:definition` is rejected AT SPAWN with the validator's own
  typed error, before anything installs — never installed as a zombie actor
  whose every event reads `:rf.error/no-such-handler`. A `:spawn-all` carrying
  one rejects the whole invoke, so its `:all` join cannot hang."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private dangling
  "A definition whose `:x` targets a state that does not exist."
  {:initial :a :states {:a {:on {:x :nowhere}}}})

(def ^:private valid
  {:initial :a :states {:a {:on {:x :b}} :b {}}})

(defn- surfaced-error-ids
  "The `:rf.error/id` of every exception an `:rf.error/fx-handler-exception`
  trace carried."
  []
  (->> (rf.machines.test-support/events-of :rf.error/fx-handler-exception)
       (keep #(some-> % :tags :exception ex-data :rf.error/id))
       vec))

(defn- hand-spawn! [event-id definition addr]
  (rf/reg-event event-id (fn [_ _] {:fx [[:rf.machine/spawn {:definition     definition
                                                             :fixed-actor-id addr}]]}))
  (rf/dispatch-sync [event-id]))

(deftest invalid-inline-definition-is-rejected-at-spawn
  (hand-spawn! :pf/go dangling :pf/kid)
  (is (nil? (rf.machines.test-support/snapshot :pf/kid)) "no zombie snapshot")
  (is (= [:rf.error/machine-unresolved-target] (surfaced-error-ids)))
  (testing "CONTROL: a valid inline definition installs and processes events"
    (hand-spawn! :pf/go2 valid :pf/kid2)
    (rf/dispatch-sync [:pf/kid2 [:x]])
    (is (= :b (rf.machines.test-support/machine-state :pf/kid2)))))

(deftest spawn-all-with-an-invalid-inline-definition-rejects-atomically
  (rf/reg-machine :pf/fork
    {:initial :idle
     :states  {:idle    {:on {:start :forking}}
               :forking {:spawn-all {:children        [{:id :ok  :definition valid    :fixed-actor-id :pf/ok}
                                                       {:id :bad :definition dangling :fixed-actor-id :pf/bad}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on        {:all/done :ready}}
               :ready   {}}})
  (rf/dispatch-sync [:pf/fork [:start]])
  (is (= [{:rf/spawn-all-rejected? true} nil nil]
         [(get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned :pf/fork [:forking]])
          (rf.machines.test-support/snapshot :pf/ok)
          (rf.machines.test-support/snapshot :pf/bad)])
      "the childless reject sentinel is seeded; neither the valid sibling nor the bad child installs")
  (is (= [:rf.error/machine-unresolved-target] (surfaced-error-ids))))
