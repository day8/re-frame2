(ns re-frame.singleton-destroy-registration-test
  "Destroying a singleton machine ends its instance and keeps its registration.

  `reg-machine` installs a DEFINITION, and `[:rf.machine/destroy <id>]` clears
  the instance's snapshot only (Spec 005 §Liveness is derived from runtime-db).
  So after the destroy every public registrar query still reports the machine:
  the registrar entry, `handler-meta` and `registrations` all read the same
  store. `rf/clear` is the one spelling that removes the registration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- store-meta [id]
  (rf/handler-meta {:source :store :kind :event :id id}))

(defn- registered-machine-ids []
  (set (keys (into {} (filter (fn [[_ m]] (:rf/machine? m)))
                   (rf/registrations {:source :store :kind :event})))))

(deftest destroying-a-singleton-keeps-its-registration
  (rf/reg-machine :sdr/session
    {:initial :idle
     :data    {}
     :states  {:idle   {:on {:go :active}}
               :active {}}})
  (rf/reg-event :sdr/stop
    (fn [_ _] {:fx [[:rf.machine/destroy :sdr/session]]}))

  (rf/dispatch-sync [:sdr/session [:go]])
  (is (= :active (:state (snapshot :sdr/session))) "the singleton is running")

  (rf/dispatch-sync [:sdr/stop])
  (is (nil? (snapshot :sdr/session)) "destroy removed the instance's snapshot")

  (testing "every registrar query still reports the machine"
    (is (true? (:rf/machine? (rf.registrar/lookup :event :sdr/session))))
    (is (true? (:rf/machine? (store-meta :sdr/session))))
    (is (= :idle (get-in (store-meta :sdr/session) [:rf/machine :initial]))
        "handler-meta still carries the machine's spec")
    (is (contains? (registered-machine-ids) :sdr/session)
        "registrations still lists it among the machines"))

  (testing "rf/clear removes the registration"
    (rf/clear :event :sdr/session)
    (is (nil? (store-meta :sdr/session)))
    (is (not (contains? (registered-machine-ids) :sdr/session)))))
