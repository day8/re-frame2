(ns re-frame.parallel-spawned-mirror-test
  "A region's spawn keeps ONE `[:data :rf/spawned …]` entry on its parallel
  parent, keyed by the IN-REGION path.

  Inside a region, a spawn's invoke-id is the region machine's own path
  (`[:loading]`). The runtime registry slot, the child's `:rf/invoke-id` and
  the attempt token carry it region-qualified (`[:a :loading]`), so sibling
  regions' slots never collide. The parent's `:rf/spawned` mirror keeps the
  in-region key, because that is the key a region action can name: a region
  name is not addressable from inside a region. The reducer binds that key,
  and the install, a rejected spawn's clear and the teardown projection all
  write and clear the same key, so the mirror

    1. holds one entry for a live region spawn, never a second
       region-qualified copy;
    2. drops the entry when the region's spawn is rejected;
    3. drops the entry when the region exits the spawning state.

  Two regions declaring a spawn at one in-region path would share that key,
  so registration refuses such a machine with
  `:rf.error/machine-parallel-bad-shape`, naming both regions and the path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private machine-data rf.machines.test-support/machine-data)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- registry-slot
  "The runtime registry slot the mirror mirrors."
  [parent-id invoke-id]
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

(defn- refusal
  "Register `definition` and return the refusal's ex-info, or nil when it
  registers."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; (1) One entry per live region spawn.
;; ---------------------------------------------------------------------------

(deftest region-spawn-mirror-holds-one-in-region-entry
  (testing "a live region :spawn is mirrored once, under its in-region path"
    (reg-child! :psm1/kid)
    (reg-region-parent! :psm1/p {:spawn {:machine-id :psm1/kid}})
    (rf/dispatch-sync [:psm1/p [:go-a]])
    (is (= :psm1/kid#1 (registry-slot :psm1/p [:a :loading]))
        "the registry slot is region-qualified")
    (is (= {[:loading] :psm1/kid#1} (:rf/spawned (machine-data :psm1/p)))
        "the mirror holds exactly the in-region entry")))

(deftest region-spawn-all-mirror-holds-one-in-region-entry
  (testing "a live region :spawn-all is mirrored once, under its in-region path"
    (reg-child! :psm2/kid)
    (reg-region-parent! :psm2/p {:spawn-all {:children        [{:id :c1 :machine-id :psm2/kid}]
                                             :on-all-complete [:all-done]}})
    (rf/dispatch-sync [:psm2/p [:go-a]])
    (is (= {:c1 :psm2/kid#1} (:children (registry-slot :psm2/p [:a :loading]))))
    (is (= {[:loading] {:c1 :psm2/kid#1}} (:rf/spawned (machine-data :psm2/p)))
        "the mirror holds exactly the in-region children map")))

;; ---------------------------------------------------------------------------
;; (2) A rejected region spawn leaves no entry.
;; ---------------------------------------------------------------------------

(deftest rejected-region-spawn-clears-its-mirror-entry
  (testing "a region :spawn of an unregistered type leaves no mirror entry"
    ;; :psm3/ghost is never registered.
    (reg-region-parent! :psm3/p {:spawn {:machine-id :psm3/ghost}})
    (rf/dispatch-sync [:psm3/p [:go-a]])
    (is (= :loading (get-in (snapshot :psm3/p) [:state :a]))
        "the region still enters :loading — only the child spawn is rejected")
    (is (= 1 (count (rf.machines.test-support/events-of
                      :rf.error/machine-spawn-unregistered-type))))
    (is (nil? (registry-slot :psm3/p [:a :loading])))
    (is (not (contains? (machine-data :psm3/p) :rf/spawned))
        "the emptied :rf/spawned map is pruned")))

(deftest rejected-region-spawn-all-clears-its-mirror-entry
  (testing "a region :spawn-all rejected as a whole leaves no mirror entry"
    (reg-region-parent! :psm4/p {:spawn-all {:children        [{:id :c1 :machine-id :psm4/ghost}]
                                             :on-all-complete [:all-done]}})
    (rf/dispatch-sync [:psm4/p [:go-a]])
    (is (not (contains? (machine-data :psm4/p) :rf/spawned)))))

;; ---------------------------------------------------------------------------
;; (3) A region exit leaves no entry.
;; ---------------------------------------------------------------------------

(deftest region-exit-clears-its-mirror-entry
  (testing "the region leaves the spawning state: the child dies and the
            mirror entry goes with it"
    (reg-child! :psm5/kid)
    (reg-region-parent! :psm5/p {:spawn {:machine-id :psm5/kid}})
    (rf/dispatch-sync [:psm5/p [:go-a]])
    (is (some? (snapshot :psm5/kid#1)))
    (rf/dispatch-sync [:psm5/p [:back-a]])
    (is (nil? (snapshot :psm5/kid#1)) "the child is destroyed")
    (is (nil? (registry-slot :psm5/p [:a :loading])))
    (is (not (contains? (machine-data :psm5/p) :rf/spawned))
        "no mirror entry outlives the child")))

(deftest region-reentry-mirrors-the-successor
  (testing "exiting and re-entering the spawning state in one macrostep
            leaves the mirror naming the successor child"
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
    (is (= :psm6/kid#2 (registry-slot :psm6/p [:a :loading])))
    (is (= {[:loading] :psm6/kid#2} (:rf/spawned (machine-data :psm6/p))))))

;; ---------------------------------------------------------------------------
;; (4) Two regions spawning at one in-region path are refused.
;; ---------------------------------------------------------------------------

(defn- two-region-parent [a-loading b-loading]
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :idle
                 :states  {:idle {:on {:go-a :loading}} :loading a-loading}}
             :b {:initial :idle
                 :states  {:idle {:on {:go-b :loading}} :loading b-loading}}}})

(deftest regions-sharing-a-spawn-path-are-refused
  (doseq [[label a b] [[":spawn in both regions"
                        {:spawn {:machine-id :psm/kid}}
                        {:spawn {:machine-id :psm/kid}}]
                       [":spawn in one region, :spawn-all in the other"
                        {:spawn {:machine-id :psm/kid}}
                        {:spawn-all {:children        [{:id :c1 :machine-id :psm/kid}]
                                     :on-all-complete [:all-done]}}]]]
    (testing label
      (let [e    (refusal :psm7/p (two-region-parent a b))
            data (ex-data e)]
        (is (= :rf.error/machine-parallel-bad-shape (:rf.error/id data)))
        (is (= #{:a :b} (set (:regions data))) "the ex-data names both regions")
        (is (= [:loading] (:state-path data)) "and the shared in-region path")
        (is (re-find #":a" (:reason data)))
        (is (re-find #":b" (:reason data)))
        (is (re-find #"\[:loading\]" (:reason data)))
        (is (re-find #"[Rr]ename" (:reason data)) "the message names the remedy"))))
  (testing "a nested shared path is refused too"
    (let [nested (fn [] {:initial :idle
                         :states  {:idle {}
                                   :work {:initial :loading
                                          :states  {:loading {:spawn {:machine-id :psm/kid}}}}}})
          e      (refusal :psm8/p {:type    :parallel
                                   :regions {:a (nested) :b (nested)}})]
      (is (= :rf.error/machine-parallel-bad-shape (:rf.error/id (ex-data e))))
      (is (= [:work :loading] (:state-path (ex-data e)))))))

(deftest regions-spawning-at-distinct-paths-register
  (testing "control: two regions spawning at different in-region paths
            register, and each keeps its own mirror entry"
    (reg-child! :psm9/kid)
    (is (nil? (refusal :psm9/p
                       {:type    :parallel
                        :data    {}
                        :regions {:a {:initial :idle
                                      :states  {:idle      {:on {:go-a :loading-a}}
                                                :loading-a {:spawn {:machine-id :psm9/kid}}}}
                                  :b {:initial :idle
                                      :states  {:idle      {:on {:go-b :loading-b}}
                                                :loading-b {:spawn {:machine-id :psm9/kid}}}}}})))
    (rf/dispatch-sync [:psm9/p [:go-a]])
    (rf/dispatch-sync [:psm9/p [:go-b]])
    (is (= {[:loading-a] :psm9/kid#1 [:loading-b] :psm9/kid#2}
           (:rf/spawned (machine-data :psm9/p))))))
