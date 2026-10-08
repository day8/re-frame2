(ns re-frame.parallel-spawned-mirror-test
  "A region's spawn keeps ONE `[:data :rf/spawned …]` entry on its parallel
  parent, keyed by the IN-REGION path (the key a region action can name), while
  the registry slot is region-qualified. Two regions spawning at one in-region
  path would share that key, so registration refuses them."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private machine-data rf.machines.test-support/machine-data)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registry-slot [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- reg-child! [id]
  (rf/reg-machine id {:initial :running :states {:running {}}}))

(defn- reg-region-parent!
  "A parallel parent whose region `:a` declares `spawn-slot` (`{:spawn …}` or
  `{:spawn-all …}`) on `[:loading]`; region `:b` idles."
  [parent-id spawn-slot]
  (rf/reg-machine parent-id
    {:type    :parallel
     :data    {}
     :regions {:a {:initial :idle
                   :states  {:idle    {:on {:go-a :loading}}
                             :loading (merge spawn-slot {:on {:back-a :idle}})}}
               :b {:initial :idle
                   :states  {:idle {}}}}}))

(deftest region-spawn-mirror-holds-one-in-region-entry
  (reg-child! :psm1/kid)
  (reg-region-parent! :psm1/p {:spawn {:machine-id :psm1/kid}})
  (rf/dispatch-sync [:psm1/p [:go-a]])
  (is (= [:psm1/kid#1 {[:loading] :psm1/kid#1}]
         [(registry-slot :psm1/p [:a :loading]) (:rf/spawned (machine-data :psm1/p))])))

(deftest region-spawn-all-mirror-holds-one-in-region-entry
  (reg-child! :psm2/kid)
  (reg-region-parent! :psm2/p {:spawn-all {:children        [{:id :c1 :machine-id :psm2/kid}]
                                           :on-all-complete [:all-done]}})
  (rf/dispatch-sync [:psm2/p [:go-a]])
  (is (= [{:c1 :psm2/kid#1} {[:loading] {:c1 :psm2/kid#1}}]
         [(:children (registry-slot :psm2/p [:a :loading])) (:rf/spawned (machine-data :psm2/p))])))

(deftest region-exit-clears-its-mirror-entry
  (reg-child! :psm5/kid)
  (reg-region-parent! :psm5/p {:spawn {:machine-id :psm5/kid}})
  (rf/dispatch-sync [:psm5/p [:go-a]])
  (is (some? (snapshot :psm5/kid#1)))
  (rf/dispatch-sync [:psm5/p [:back-a]])
  (is (not (contains? (machine-data :psm5/p) :rf/spawned))
      "no mirror entry outlives the child"))

(deftest region-reentry-mirrors-the-successor
  (reg-child! :psm6/kid)
  (rf/reg-machine :psm6/p
    {:type    :parallel
     :data    {}
     :regions {:a {:initial :idle
                   :states  {:idle    {:on {:go-a :loading}}
                             :loading {:spawn {:machine-id :psm6/kid}
                                       :on    {:again {:target :loading :reenter? true}}}}}
               :b {:initial :idle
                   :states  {:idle {}}}}})
  (rf/dispatch-sync [:psm6/p [:go-a]])
  (rf/dispatch-sync [:psm6/p [:again]])
  (is (= [:psm6/kid#2 {[:loading] :psm6/kid#2}]
         [(registry-slot :psm6/p [:a :loading]) (:rf/spawned (machine-data :psm6/p))])))

(defn- two-region-parent [a-loading b-loading]
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :idle
                 :states  {:idle {:on {:go-a :loading}} :loading a-loading}}
             :b {:initial :idle
                 :states  {:idle {:on {:go-b :loading}} :loading b-loading}}}})

(defn- nested-spawn-region []
  {:initial :idle
   :states  {:idle {}
             :work {:initial :loading
                    :states  {:loading {:spawn {:machine-id :psm/kid}}}}}})

(deftest regions-sharing-a-spawn-path-are-refused
  (doseq [[label machine path]
          [[":spawn in both regions"
            (two-region-parent {:spawn {:machine-id :psm/kid}} {:spawn {:machine-id :psm/kid}})
            [:loading]]
           [":spawn in one region, :spawn-all in the other"
            (two-region-parent {:spawn {:machine-id :psm/kid}}
                               {:spawn-all {:children        [{:id :c1 :machine-id :psm/kid}]
                                            :on-all-complete [:all-done]}})
            [:loading]]
           ["a nested shared path"
            {:type :parallel :regions {:a (nested-spawn-region) :b (nested-spawn-region)}}
            [:work :loading]]]]
    (let [data (try (rf/reg-machine :psm7/p machine) nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/machine-parallel-bad-shape :regions #{:a :b} :state-path path}
             (update (select-keys data [:rf.error/id :regions :state-path]) :regions set))
          label))))

(deftest regions-spawning-at-distinct-paths-register
  (testing "control: two regions spawning at different in-region paths register, each keeping its own entry"
    (reg-child! :psm9/kid)
    (rf/reg-machine :psm9/p
      {:type    :parallel
       :data    {}
       :regions {:a {:initial :idle
                     :states  {:idle      {:on {:go-a :loading-a}}
                               :loading-a {:spawn {:machine-id :psm9/kid}}}}
                 :b {:initial :idle
                     :states  {:idle      {:on {:go-b :loading-b}}
                               :loading-b {:spawn {:machine-id :psm9/kid}}}}}})
    (rf/dispatch-sync [:psm9/p [:go-a]])
    (rf/dispatch-sync [:psm9/p [:go-b]])
    (is (= {[:loading-a] :psm9/kid#1 [:loading-b] :psm9/kid#2}
           (:rf/spawned (machine-data :psm9/p))))))
