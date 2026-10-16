(ns re-frame.parallel-spawned-mirror-test
  "A region's spawn keeps ONE `[:data :rf/spawned …]` entry on its parallel
  parent, keyed by the region-qualified invoke-id `[<region> & <in-region-path>]`
  — the key the registry slot and the child's `:rf/invoke-id` carry. Regions are
  true scopes: two regions may spawn from same-named states, and each
  region's install, exit and rejection touch only its own entry."
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

(deftest region-spawn-mirror-holds-one-region-qualified-entry
  (reg-child! :psm1/kid)
  (reg-region-parent! :psm1/p {:spawn {:machine-id :psm1/kid}})
  (rf/dispatch-sync [:psm1/p [:go-a]])
  (is (= [:psm1/kid#1 {[:a :loading] :psm1/kid#1}]
         [(registry-slot :psm1/p [:a :loading]) (:rf/spawned (machine-data :psm1/p))])))

(deftest region-spawn-all-mirror-holds-one-region-qualified-entry
  (reg-child! :psm2/kid)
  (reg-region-parent! :psm2/p {:spawn-all {:children        [{:id :c1 :machine-id :psm2/kid}]
                                           :on-all-complete [:all-done]}})
  (rf/dispatch-sync [:psm2/p [:go-a]])
  (is (= [{:c1 :psm2/kid#1} {[:a :loading] {:c1 :psm2/kid#1}}]
         [(:children (registry-slot :psm2/p [:a :loading])) (:rf/spawned (machine-data :psm2/p))])))

(deftest region-exit-clears-its-mirror-entry
  (reg-child! :psm5/kid)
  (reg-region-parent! :psm5/p {:spawn {:machine-id :psm5/kid}})
  (rf/dispatch-sync [:psm5/p [:go-a]])
  (is (some? (snapshot :psm5/kid#1)))
  (rf/dispatch-sync [:psm5/p [:back-a]])
  (is (not (contains? (machine-data :psm5/p) :rf/spawned))
      "no mirror entry outlives the child"))

(defn- two-region-parent
  "Regions `:a` and `:b` each declare their slot on a same-named state,
  `[:loading]`, entered by `:go-a` / `:go-b` and left by `:back-a` / `:back-b`."
  [a-slot b-slot]
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :idle
                 :states  {:idle {:on {:go-a :loading}} :loading (merge a-slot {:on {:back-a :idle}})}}
             :b {:initial :idle
                 :states  {:idle {:on {:go-b :loading}} :loading (merge b-slot {:on {:back-b :idle}})}}}})

(defn- nested-spawn-region []
  {:initial :idle
   :states  {:idle {}
             :work {:initial :loading
                    :states  {:loading {:spawn {:machine-id :psm/kid}}}}}})

(defn- spawn-all-of [& machine-ids]
  {:spawn-all {:children        (vec (map-indexed (fn [i m] {:id (keyword (str "c" (inc i))) :machine-id m})
                                                  machine-ids))
               :on-all-complete [:all-done]}})

(deftest regions-sharing-a-spawn-path-register
  (doseq [[label machine]
          [[":spawn in both regions"
            (two-region-parent {:spawn {:machine-id :psm/kid}} {:spawn {:machine-id :psm/kid}})]
           [":spawn-all in both regions"
            (two-region-parent (spawn-all-of :psm/kid) (spawn-all-of :psm/kid))]
           [":spawn in one region, :spawn-all in the other"
            (two-region-parent {:spawn {:machine-id :psm/kid}} (spawn-all-of :psm/kid))]
           ["a nested shared path"
            {:type :parallel :regions {:a (nested-spawn-region) :b (nested-spawn-region)}}]]]
    (is (nil? (try (rf/reg-machine (keyword "psm7" (str (gensym "p"))) machine) nil
                   (catch clojure.lang.ExceptionInfo e (ex-data e))))
        label)))

(deftest regions-sharing-a-spawn-path-keep-their-own-entries
  (reg-child! :psm8/kid)
  (rf/reg-machine :psm8/p (two-region-parent {:spawn {:machine-id :psm8/kid}}
                                             (spawn-all-of :psm8/kid)))
  (rf/dispatch-sync [:psm8/p [:go-a]])
  (rf/dispatch-sync [:psm8/p [:go-b]])
  (testing "each region's spawn writes its own entry, equal to its registry slot's child"
    (is (= {[:a :loading] :psm8/kid#1
            [:b :loading] {:c1 :psm8/kid#2}}
           (:rf/spawned (machine-data :psm8/p))))
    (is (= [:psm8/kid#1 {:c1 :psm8/kid#2}]
           [(registry-slot :psm8/p [:a :loading])
            (:children (registry-slot :psm8/p [:b :loading]))])))
  (testing "re-entering one region's spawning state rewrites only that region's entry"
    (rf/dispatch-sync [:psm8/p [:back-a]])
    (is (= {[:b :loading] {:c1 :psm8/kid#2}} (:rf/spawned (machine-data :psm8/p)))
        "region :a's exit clears its own entry and leaves :b's")
    (is (some? (snapshot :psm8/kid#2)) "region :b's child is untouched")
    (rf/dispatch-sync [:psm8/p [:go-a]])
    (is (= {[:a :loading] :psm8/kid#3
            [:b :loading] {:c1 :psm8/kid#2}}
           (:rf/spawned (machine-data :psm8/p))))))

(deftest a-rejected-region-spawn-clears-only-its-own-entry
  (reg-child! :psm10/kid)
  (testing "a single :spawn the fx rejects (unregistered type) in region :a"
    (rf/reg-machine :psm10/p (two-region-parent {:spawn {:machine-id :psm10/ghost}}
                                                {:spawn {:machine-id :psm10/kid}}))
    (rf/dispatch-sync [:psm10/p [:go-a]])
    (rf/dispatch-sync [:psm10/p [:go-b]])
    (is (= {[:b :loading] :psm10/kid#1} (:rf/spawned (machine-data :psm10/p)))))
  (testing "a :spawn-all the fx rejects whole in region :b"
    (reg-child! :psm11/kid)
    (rf/reg-machine :psm11/q (two-region-parent {:spawn {:machine-id :psm11/kid}}
                                                (spawn-all-of :psm11/kid :psm11/phantom)))
    (rf/dispatch-sync [:psm11/q [:go-a]])
    (rf/dispatch-sync [:psm11/q [:go-b]])
    (is (= {[:a :loading] :psm11/kid#1} (:rf/spawned (machine-data :psm11/q))))))
