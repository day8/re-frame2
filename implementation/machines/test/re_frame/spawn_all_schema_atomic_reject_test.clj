(ns re-frame.spawn-all-schema-atomic-reject-test
  "A `:spawn-all` whose children fail spawn-time `[:schemas :data]`
  validation is rejected ATOMICALLY by the invoke-level preflight: one
  childless reject sentinel, nothing installed, one schema error per invalid
  child. Validating only inside each per-child spawn would publish a live join
  naming a child that can never complete, and install its valid siblings."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            ;; the registered-validator path and Malli's validate/explain
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private strict-child
  "Its registered default `:data` conforms; a per-child `:data` override is what violates."
  {:initial :running
   :data    {:n 1}
   :schemas {:data [:map [:n pos-int?]]}
   :states  {:running {}}})

(def ^:private plain-child
  {:initial :running
   :data    {}
   :states  {:running {}}})

(defn- start-parent! [parent-id children]
  (rf/reg-machine :sa/strict strict-child)
  (rf/reg-machine :sa/plain plain-child)
  (rf/reg-machine parent-id
    {:initial :idle
     :states  {:idle    {:on {:start :forking}}
               :forking {:spawn-all {:children children :on-all-complete [:all/done]}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- machines-db []
  (:rf.runtime/machines (rf.machines.test-support/runtime-db)))

(defn- schema-failures []
  (filterv #(= :spawn (get-in % [:tags :phase]))
           (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

(deftest mixed-invalid-child-rejects-the-whole-invoke-atomically
  (testing "invalid children first and last around a valid sibling: one childless sentinel,
            no child installed or ordered, exactly one schema failure per invalid child"
    (start-parent! :sup/mixed [{:id :bad1 :machine-id :sa/strict :data {:n -1}}
                               {:id :ok   :machine-id :sa/plain}
                               {:id :bad2 :machine-id :sa/strict :data {:n -2}}])
    (is (= {:rf/spawn-all-rejected? true} (get-in (machines-db) [:spawned :sup/mixed [:forking]])))
    (is (= #{:sup/mixed} (set (keys (:snapshots (machines-db))))))
    (is (= [] (rf.machines.spawn-order/frame-order :rf/default)))
    (is (= [{:n -2} {:n -1}]
           (sort-by :n (mapv #(select-keys (get-in % [:tags :value]) [:n]) (schema-failures)))))))

(deftest all-valid-spawn-all-still-installs-a-live-join
  (testing "the preflight validates the same stamped value the install builds, so an
            all-valid :spawn-all is not falsely rejected"
    (start-parent! :sup/valid [{:id :good :machine-id :sa/strict :data {:n 7}}
                               {:id :ok   :machine-id :sa/plain}])
    (is (= {:good :sa/strict#1 :ok :sa/plain#1}
           (get-in (machines-db) [:spawned :sup/valid [:forking] :children])))
    (is (empty? (schema-failures)))
    (is (= {:n 7} (select-keys (rf.machines.test-support/machine-data :sa/strict#1) [:n])))
    (is (some? (rf.machines.test-support/snapshot :sa/plain#1)))))
