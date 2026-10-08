(ns re-frame.destroy-exit-cascade-test
  "Every destroy path runs the actor's active `:exit` cascade before teardown,
  and each `:phase :destroy-exit` `:rf.machine/action-ran` row names the
  destroyed INSTANCE and its frame (Spec 005 §Declarative `:spawn`, §Final
  states; the Xray Handler section and epoch capture key on both)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- destroy-exit-rows
  "Every `:phase :destroy-exit` action-ran as `[actor-id action-id frame]`."
  []
  (into []
        (comp (map :tags)
              (filter #(= :destroy-exit (:phase %)))
              (map (juxt :actor-id :action-id :frame)))
        (rf.machines.test-support/events-of :rf.machine/action-ran)))

(def ^:private noop-actions {:ex-a (fn [_] {}) :ex-b (fn [_] {})})

(deftest destroy-exit-row-names-an-explicitly-destroyed-singleton
  (rf/reg-machine :dea/single {:initial :a :actions noop-actions :states {:a {:exit :ex-a}}})
  (rf/reg-event :dea/kill-single (fn [_ _] {:fx [[:rf.machine/destroy :dea/single]]}))
  (rf/dispatch-sync [:dea/single [:rf.machine/start]])
  (rf/dispatch-sync [:dea/kill-single])
  (is (= [[:dea/single :ex-a :rf/default]] (destroy-exit-rows))))

(deftest destroy-exit-row-names-a-destroyed-spawned-child
  (rf/reg-machine :dea/kid {:initial :working :actions noop-actions :states {:working {:exit :ex-a}}})
  (rf/reg-machine :dea/parent
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id :dea/kid}
                         :on    {:stop :idle}}}})
  (rf/dispatch-sync [:dea/parent [:start]])
  (rf/dispatch-sync [:dea/parent [:stop]])
  (is (= [nil [[:dea/kid#1 :ex-a :rf/default]]]
         [(rf.machines.test-support/snapshot :dea/kid#1) (destroy-exit-rows)])))

(deftest destroy-exit-row-names-a-finalised-actor
  (rf/reg-machine :dea/finisher
    {:initial :running
     :actions noop-actions
     :states  {:running {:on {:finish :done}}
               :done    {:final? true :exit :ex-b}}})
  (rf/reg-machine :dea/final-parent
    {:initial :working
     :states  {:working {:spawn {:machine-id :dea/finisher}}}})
  (rf/dispatch-sync [:dea/final-parent [:rf.machine.spawn/spawned]])
  (rf/dispatch-sync [:dea/finisher#1 [:finish]])
  (is (= [nil [[:dea/finisher#1 :ex-b :rf/default]]]
         [(rf.machines.test-support/snapshot :dea/finisher#1) (destroy-exit-rows)])))
