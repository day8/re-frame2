(ns re-frame.spawn-rejected-mirror-test
  "A rejected spawn leaves no `[:data :rf/spawned <invoke-id>]` entry on its
  parent, for each rejection (unregistered type, generated-address collision,
  `:spawn-all` rejected whole, spawn-time `[:schemas :data]`), and the schema
  rejection's cleanup never touches a same-id successor frame's mirror."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; The schemas artefact runs the spawn-time `[:schemas :data]` gate;
            ;; the `.malli` adapter publishes Malli's validate/explain.
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private machine-data rf.machines.test-support/machine-data)
(def ^:private machine-state rf.machines.test-support/machine-state)
(def ^:private events-of rf.machines.test-support/events-of)

(defn- reg-child! [id]
  (rf/reg-machine id {:initial :running :states {:running {}}}))

(defn- reg-parent!
  "A parent whose `:busy` state spawns `spawn-spec` on entry."
  [id spawn-spec]
  (rf/reg-machine id
    {:initial :idle
     :states  {:idle {:on {:start :busy}}
               :busy {:spawn spawn-spec}}}))

(deftest unregistered-type-reject-clears-the-mirror
  (reg-parent! :srm/p1 {:machine-id :srm/ghost})
  (rf/dispatch-sync [:srm/p1 [:start]])
  (is (= :busy (machine-state :srm/p1)) "the parent entered :busy, so the reducer bound the mirror")
  (is (not (contains? (machine-data :srm/p1) :rf/spawned))
      "the mirror entry is cleared and the emptied map pruned"))

(deftest generated-address-collision-reject-clears-the-mirror
  (testing "two parent TYPES each mint :srm/worker#1; the second spawn is rejected"
    (reg-child! :srm/worker)
    (reg-parent! :srm/pa {:machine-id :srm/worker})
    (reg-parent! :srm/pb {:machine-id :srm/worker})
    (rf/dispatch-sync [:srm/pa [:start]])
    (rf/dispatch-sync [:srm/pb [:start]])
    (is (= 1 (count (events-of :rf.error/machine-spawn-all-duplicate-id))))
    (is (not (contains? (:rf/spawned (machine-data :srm/pb)) [:busy]))
        "the rejected parent's mirror does not name the first parent's live child")))

(deftest spawn-all-reject-clears-the-mirror
  (testing "a :spawn-all with an unregistered child type is rejected whole"
    (reg-child! :srm/real)
    (rf/reg-machine :srm/fan
      {:initial :idle
       :states  {:idle {:on {:start :busy}}
                 :busy {:spawn-all {:children        [{:id :a :machine-id :srm/real}
                                                      {:id :b :machine-id :srm/phantom}]
                                    :on-all-complete [:done]}}}})
    (rf/dispatch-sync [:srm/fan [:start]])
    (is (= [true false]
           [(get-in (rf.machines.test-support/runtime-db)
                    [:rf.runtime/machines :spawned :srm/fan [:busy] :rf/spawn-all-rejected?])
            (contains? (:rf/spawned (machine-data :srm/fan)) [:busy])]))))

(def ^:private StrictData [:map [:n pos-int?]])

(defn- reg-strict-child!
  "A child whose registered `:data` conforms, so the spawn's `:data` override
  is what the gate judges."
  [id]
  (rf/reg-machine id {:initial :running
                      :data    {:n 1}
                      :schemas {:data StrictData}
                      :states  {:running {}}}))

(deftest schema-reject-clears-the-mirror
  (reg-strict-child! :srm/strict)
  (reg-parent! :srm/ps {:machine-id :srm/strict :data {:n 0}})
  (rf/dispatch-sync [:srm/ps [:start]])
  (is (= [:busy 1 false]
         [(machine-state :srm/ps)
          (count (filter #(= :spawn (get-in % [:tags :phase]))
                         (events-of :rf.error/schema-validation-failure)))
          (contains? (machine-data :srm/ps) :rf/spawned)])))

(deftest schema-reject-after-owner-loss-leaves-the-successor-mirror-alone
  (testing "a failing validator that destroys the owning frame and publishes a
            same-id successor seeded with a mirror entry for the same address:
            the cleanup is bound to the owner's exact incarnation"
    (reg-strict-child! :srm/strict-lost)
    (let [frame-a  :srm/lost-frame
          parent   :srm/lost-parent
          address  :srm/lost#1
          seed     (fn [rt]
                     (assoc-in rt [:rf.runtime/machines :snapshots parent]
                               {:state :busy
                                :data  {:rf/spawned {[:busy] address}}}))
          fired?   (atom false)
          b-seeded (atom nil)
          orig     (rf.late-bind/get-fn :schemas/validate-with-registered-fn)]
      (rf/make-frame {:id frame-a})
      (rf.frame/swap-runtime-db! frame-a seed)
      (try
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [_schema _data]
            (if (compare-and-set! fired? false true)
              (do (rf.frame/destroy-frame! frame-a)
                  (rf/make-frame {:id frame-a})
                  (rf.frame/swap-runtime-db! frame-a seed)
                  (reset! b-seeded (rf.machines.test-support/runtime-db frame-a))
                  false)
              true)))
        ;; Driven directly under A's event-owner token: B is published on the
        ;; validator's own stack, which a live `dispatch-sync` drain forbids.
        (rf.frame/call-with-event-owner-token frame-a
          (rf.frame/frame-incarnation-token frame-a)
          (fn []
            (rf.machines.lifecycle-fx.spawn/spawn-fx
              {:frame frame-a}
              {:machine-id    :srm/strict-lost
               :data          {:n 0}
               :rf/parent-id  parent
               :rf/invoke-id  [:busy]
               :rf/spawned-id address})))
        (is (= @b-seeded (rf.machines.test-support/runtime-db frame-a))
            "B's runtime-db, mirror entry included, is untouched")
        (finally
          (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig)
          (rf.frame/destroy-frame! frame-a))))))
