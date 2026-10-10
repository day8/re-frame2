(ns re-frame2-pair.runtime-restore-epoch-test
  "The SHIPPED preload's `restore-epoch` against a REAL frame: with opts
  `{:to :before}` it installs the named record's `:frame-state-before`.

  Undoing a `replace-frame-state!` injection is the case that needs it. On
  a fresh frame the injection's synthetic record is the only one retained,
  so restoring it to its after-state reinstalls the injection; only its
  before-state reaches the state the injection replaced."
  (:require [cljs.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.epoch]
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
      (is (true? (rf/replace-frame-state! frame-id {:rf.db/app {:injected true}})))
      (let [record (peek (rf/epoch-history frame-id))]
        (is (= [:rf.epoch/db-replaced] (mapv :event-id (rf/epoch-history frame-id)))
            "the injection's synthetic record is the only one retained")
        (f before record)))
    (finally (rf/destroy-frame! frame-id))))

(deftest restore-to-before-installs-the-records-before-state
  (with-injected-frame
    (fn [before record]
      (let [result (rt/restore-epoch (:epoch-id record) frame-id {:to :before})]
        (is (true? (:restored? result)))
        (is (= (:frame-state-before record) (rf/frame-state-value frame-id))
            "the record's before-state is installed")
        (is (= before (rf/frame-state-value frame-id))
            "the injection is undone")
        (is (= [[:injected]] (get-in result [:cascade-summary :db-diff :removed-paths]))
            "the diff runs from the pre-restore db to the state installed")))))

(deftest restore-without-opts-installs-the-records-after-state
  (with-injected-frame
    (fn [_ record]
      (let [result (rt/restore-epoch (:epoch-id record) frame-id)]
        (is (true? (:restored? result)))
        (is (= (:frame-state-after record) (rf/frame-state-value frame-id)))
        (is (= {:injected true} (rf/app-db-value frame-id)))))))
