(ns re-frame2-pair.runtime-restore-epoch-test
  "Restoring an injection to :before undoes it even when its synthetic epoch is the only retained record."
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame2-pair.runtime :as rt]))

(def ^:private frame-id :review/restore-before)

(defn- with-injected-frame
  "A fresh frame whose app-db was seeded and then replaced by an injection.
  Calls `f` with the state before the injection and the injection's record."
  [f]
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf/make-frame {:id frame-id})
  (try
    (let [before (rf/frame-state-value frame-id)]
      (is (:ok? (rf.epoch/replace-frame-state! frame-id {:rf.db/app {:injected true}})))
      (let [record (peek (rf.epoch/epoch-history frame-id))]
        (is (= [:rf.epoch/db-replaced] (mapv :event-id (rf.epoch/epoch-history frame-id)))
            "the injection's synthetic record is the only one retained")
        (f before record)))
    (finally (rf/destroy-frame! frame-id))))

(deftest restore-to-before-installs-the-records-before-state
  (with-injected-frame
    (fn [before record]
      (let [result (rt/restore-epoch (:epoch-id record) frame-id {:to :before})]
        (is (true? (:restored? result)))
        (is (= before (:frame-state-before record) (rf/frame-state-value frame-id))
            "the injection is undone to its recorded before-state")
        (is (= [[:injected]] (get-in result [:cascade-summary :db-diff :removed-paths]))
            "the diff runs from the pre-restore db to the state installed")))))

(deftest restore-without-opts-installs-the-records-after-state
  (with-injected-frame
    (fn [_ record]
      (let [result (rt/restore-epoch (:epoch-id record) frame-id)]
        (is (true? (:restored? result)))
        (is (= (:frame-state-after record) (rf/frame-state-value frame-id)))
        (is (= {:injected true} (rf/app-db-value frame-id)))))))
