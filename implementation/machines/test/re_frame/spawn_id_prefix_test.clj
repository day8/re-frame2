(ns re-frame.spawn-id-prefix-test
  "A declarative `:spawn` / `:spawn-all` mints its generated `<prefix>#<n>`
  address — and keys its `:rf/spawn-counter` sequence — from the spawn-spec's
  `:id-prefix`, defaulting to `:machine-id` (Spec 005 §Spawn-spec keys). A
  distinct prefix is the author's escape from the generated-address collision
  reject."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- reg-leaf! [id]
  (rf/reg-machine id {:initial :running :data {} :states {:running {}}}))

(deftest supplied-id-prefix-decides-the-generated-address
  (reg-leaf! :idp/child)
  (doseq [[parent spawn-spec] [[:idp/prefixed {:machine-id :idp/child :id-prefix :idp/mine}]
                               [:idp/plain    {:machine-id :idp/child}]]]
    (rf/reg-machine parent {:initial :idle
                            :data    {}
                            :states  {:idle {:on {:go :working}} :working {:spawn spawn-spec}}})
    (rf/dispatch-sync [parent [:go]]))
  (is (= {:idp/mine 1} (:rf/spawn-counter (snapshot :idp/prefixed)))
      "sequenced under the supplied prefix, not under :machine-id")
  ;; Two parents spawning one TYPE: had the prefix been ignored, both would
  ;; mint :idp/child#1 and the second would be refused as a collision.
  (is (= [{:machine-id :idp/child :id-prefix :idp/mine  :spawned-id :idp/mine#1}
          {:machine-id :idp/child :id-prefix :idp/child :spawned-id :idp/child#1}]
         (mapv #(select-keys (:tags %) [:machine-id :id-prefix :spawned-id])
               (rf.machines.test-support/events-of :rf.machine.spawn/spawned)))
      "each spawn installs at the address minted from its effective prefix, and its trace reports that prefix"))

(deftest spawn-all-children-honour-their-own-id-prefix
  (reg-leaf! :idp4/child)
  (rf/reg-machine :idp4/parent
    {:initial :idle
     :data    {}
     :states  {:idle      {:on {:fan-out :hydrating}}
               :hydrating {:spawn-all {:children        [{:id :a :machine-id :idp4/child :id-prefix :idp4/alpha}
                                                         {:id :b :machine-id :idp4/child}]
                                       :join            :all
                                       :on-all-complete [:idp4/done]}
                           :on        {:idp4/done :ready}}
               :ready     {}}})
  (rf/dispatch-sync [:idp4/parent [:fan-out]])
  (is (= {:a :idp4/alpha#1 :b :idp4/child#1}
         (get-in (rf.machines.test-support/runtime-db)
                 [:rf.runtime/machines :spawned :idp4/parent [:hydrating] :children])))
  (is (every? snapshot [:idp4/alpha#1 :idp4/child#1]) "both children are live")
  (is (= {:idp4/alpha 1 :idp4/child 1} (:rf/spawn-counter (snapshot :idp4/parent)))
      "one independent sequence per distinct prefix"))
