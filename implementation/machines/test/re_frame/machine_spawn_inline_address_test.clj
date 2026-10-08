(ns re-frame.machine-spawn-inline-address-test
  "An inline `:definition` spawn must name an address — `:id-prefix` or
  `:fixed-actor-id` — since it has no registered type to default its prefix
  to. Registration refuses the unaddressed declarative shapes; the spawn fx
  refuses a hand-emitted one before anything installs at address nil."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private valid
  {:initial :wait :states {:wait {}}})

(defn- refusal
  "The `:rf.error/id` `reg-machine` refuses `definition` with, or nil."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- spawning-parent [spawn-spec]
  {:initial :working :data {} :states {:working {:spawn spawn-spec}}})

(defn- forking-parent [child]
  {:initial :forking
   :data    {}
   :states  {:forking {:spawn-all {:children        [(assoc child :id :kid)]
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(deftest declarative-spawn-without-an-address-is-refused-at-registration
  (is (= [:rf.error/machine-spawn-bad-shape :rf.error/machine-spawn-all-bad-shape]
         [(refusal :addr/parent (spawning-parent {:definition valid}))
          (refusal :addr/fork (forking-parent {:definition valid}))]))
  (testing "CONTROL: either address key lets it register and spawn"
    (doseq [[parent definition] [[:addr/prefixed      (spawning-parent {:definition valid :id-prefix :addr/kid})]
                                 [:addr/fixed         (spawning-parent {:definition valid :fixed-actor-id :addr/the-kid})]
                                 [:addr/fork-prefixed (forking-parent {:definition valid :id-prefix :addr/fork-kid})]
                                 [:addr/fork-fixed    (forking-parent {:definition valid :fixed-actor-id :addr/fork-the-kid})]]]
      (rf/reg-machine parent definition)
      (rf/dispatch-sync [parent [:rf.machine/start]]))
    (is (= [:wait :wait :wait :wait]
           (mapv rf.machines.test-support/machine-state
                 [:addr/kid#1 :addr/the-kid :addr/fork-kid#1 :addr/fork-the-kid])))))

(defn- hand-spawn! [event-id spawn-args]
  (rf/reg-event event-id (fn [_ _] {:fx [[:rf.machine/spawn spawn-args]]}))
  (rf/dispatch-sync [event-id]))

(deftest hand-emitted-spawn-without-an-address-is-refused
  (hand-spawn! :addr/go {:definition valid})
  (is (empty? (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots]))
      "no actor at address nil")
  (is (= [:rf.error/machine-spawn-bad-shape]
         (keep #(some-> % :tags :exception ex-data :rf.error/id)
               (rf.machines.test-support/events-of :rf.error/fx-handler-exception))))
  (testing "CONTROL: :id-prefix addresses it"
    (hand-spawn! :addr/go-prefixed {:definition valid :id-prefix :addr/hand})
    (is (= :wait (rf.machines.test-support/machine-state :addr/hand#1)))))
