(ns re-frame.machine-cofx-lifecycle-test
  "Cofx ensure-set coverage for ENTRY / EXIT lifecycle actions + the
  bootstrap/birth path.

  Spec 005's EP-0017 section says a `:guard` / `:action` / `:entry` / `:exit`
  callback that depends on host facts must read DECLARED recordable coeffects,
  and it rejects inline `:rf.cofx/requires` on the `:entry` / `:exit` slots.
  That makes a NAMED `:actions` entry (carrying `:rf.cofx/requires` + `:fn`),
  referenced by keyword from an `:entry` / `:exit` slot, the ONLY legal way for
  a lifecycle boundary action to consume a recordable fact.

  The dispatch-time ensure-set unions the candidate transition `:guard` /
  `:action` diets, the `:always` guard/action closure diets, AND the
  `:actions` diet for the active-state `:exit` refs and the target-state
  `:entry` refs the exit→action→entry cascade runs. The bootstrap
  initial-entry / birth `:entry` actions are ensured before `maybe-boot`
  runs the cascade. So a named `:entry` / `:exit` action declaring a
  generator-backed or provided recordable fact gets the EP-0017 behaviour
  (generate-and-write-back in live mode; missing-required in strict/replay).

  These tests pin BOTH the white-box ensure-set derivation (`ensure-set-for`)
  and the end-to-end live behaviour (the generated value lands in the action's
  `:data` write), for: a target-state `:entry` action, an active-state `:exit`
  action, and a bootstrap initial-entry action."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.cofx-attach :as cofx-attach]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private SCRIPTED-TIME-MS 1234500000)

;; ===========================================================================
;; A. white-box — ensure-set-for includes lifecycle boundary action diets
;; ===========================================================================

(deftest ensure-set-for-includes-target-entry-action
  (testing "white-box: ensure-set-for for [:go] at :idle includes the
            :rf.cofx/requires of the TARGET state's named :entry action — the
            exit→action→entry cascade will run it"
    (rf/reg-cofx :test/entry-roll {:recordable? true} (fn [] 6))
    (let [m  (cofx-attach/index-ensure-sets
               {:initial :idle
                :actions {:stamp-entry {:rf.cofx/requires [:test/entry-roll]
                                        :fn (fn [_] nil)}}
                :states  {:idle {:on {:go :active}}
                          :active {:entry :stamp-entry}}})
          es (cofx-attach/ensure-set-for m {:state :idle :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/entry-roll)
          "target-state :entry action's required fact is in the ensure-set"))))

(deftest ensure-set-for-includes-active-exit-action
  (testing "white-box: ensure-set-for for [:go] at :active includes the
            :rf.cofx/requires of the ACTIVE state's named :exit action — it
            runs as the first leg of the exit→action→entry cascade"
    (rf/reg-cofx :test/exit-roll {:recordable? true} (fn [] 6))
    (let [m  (cofx-attach/index-ensure-sets
               {:initial :active
                :actions {:stamp-exit {:rf.cofx/requires [:test/exit-roll]
                                       :fn (fn [_] nil)}}
                :states  {:active {:exit :stamp-exit :on {:go :idle}}
                          :idle   {}}})
          es (cofx-attach/ensure-set-for m {:state :active :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/exit-roll)
          "active-state :exit action's required fact is in the ensure-set"))))

(deftest ensure-set-for-includes-initial-descent-entry-action
  (testing "white-box: a TARGET that is compound descends through :initial; the
            ensure-set includes the :entry actions of every node entered on the
            initial-descent path"
    (rf/reg-cofx :test/descent-roll {:recordable? true} (fn [] 6))
    (let [m  (cofx-attach/index-ensure-sets
               {:initial :idle
                :actions {:descent-stamp {:rf.cofx/requires [:test/descent-roll]
                                          :fn (fn [_] nil)}}
                :states  {:idle {:on {:go :parent}}
                          :parent {:initial :child
                                   :states {:child {:entry :descent-stamp}}}}})
          es (cofx-attach/ensure-set-for m {:state :idle :data {}} [:go])]
      (is (contains? (set (map :id es)) :test/descent-roll)
          "the initial-descent :child :entry action's fact is in the ensure-set"))))

;; ===========================================================================
;; B. end-to-end — the generated fact lands in the lifecycle action's :data
;; ===========================================================================

(deftest entry-action-reads-generated-fact
  (testing "end-to-end: a target-state :entry action requiring a generator-
            backed recordable fact reads the GENERATED value (not nil) and
            folds it into a durable :data write"
    (rf/reg-cofx :test/entry-gen {:recordable? true} (fn [] 6))
    (let [m {:initial :idle
             :data    {}
             :actions {:stamp-entry
                       {:rf.cofx/requires [:test/entry-gen]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data :roll (:test/entry-gen cofx))})}}
             :states  {:idle   {:on {:go :active}}
                       :active {:entry :stamp-entry}}}]
      (rf/reg-machine :lifecycle/entry-gen m)
      (rf/dispatch-sync [:lifecycle/entry-gen [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 6 (:roll (mtest/machine-data :lifecycle/entry-gen)))
          "the :entry action wrote the GENERATED fact — ensured before the cascade"))))

(deftest exit-action-reads-generated-fact
  (testing "end-to-end: an active-state :exit action requiring a generator-
            backed recordable fact reads the GENERATED value (not nil)"
    (rf/reg-cofx :test/exit-gen {:recordable? true} (fn [] 7))
    (let [m {:initial :active
             :data    {}
             :actions {:stamp-exit
                       {:rf.cofx/requires [:test/exit-gen]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data :exit-roll (:test/exit-gen cofx))})}}
             :states  {:active {:exit :stamp-exit :on {:go :idle}}
                       :idle   {}}}]
      (rf/reg-machine :lifecycle/exit-gen m)
      (rf/dispatch-sync [:lifecycle/exit-gen [:go]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 7 (:exit-roll (mtest/machine-data :lifecycle/exit-gen)))
          "the :exit action wrote the GENERATED fact — ensured before the cascade"))))

(deftest exit-action-missing-provided-fact-throws
  (testing "a PROVIDED (non-generator) recordable fact required by an :exit
            action, absent from the in-flight record, raises missing-required
            from the dispatch-time ensure step — the :exit diet IS in the
            ensure-set (drives the real ensure path)"
    (let [m (cofx-attach/index-ensure-sets
              {:initial :active
               :data    {}
               :actions {:needs-time {:rf.cofx/requires [:rf/time-ms]
                                      :fn (fn [{:keys [data] cofx :rf.cofx}]
                                            {:data (assoc data :t (:rf/time-ms cofx))})}}
               :states  {:active {:exit :needs-time :on {:go :idle}}
                         :idle   {}}})
          e (is (thrown? ExceptionInfo
                         (cofx-attach/ensure-cofx
                           m {:state :active :data {}} [:go]
                           {} nil :lifecycle/exit-missing)))]
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data e)))
          "the :exit-required provided fact, absent, throws missing-required —
           it WAS in the ensure-set, not a silent nil"))))

;; ===========================================================================
;; C. bootstrap / initial-entry — ensured before maybe-boot runs the cascade
;; ===========================================================================

(deftest initial-entry-action-reads-generated-fact
  (testing "end-to-end: the INITIAL state's :entry action requiring a
            generator-backed recordable fact reads the GENERATED value at
            BIRTH (the bootstrap cascade runs in maybe-boot, which previously
            ran BEFORE any ensure step — the rf2-knxbok bootstrap hole)"
    (rf/reg-cofx :test/birth-gen {:recordable? true} (fn [] 9))
    (let [m {:initial :booting
             :data    {}
             :actions {:stamp-birth
                       {:rf.cofx/requires [:test/birth-gen]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data :birth (:test/birth-gen cofx))})}}
             :states  {:booting {:entry :stamp-birth}}}]
      (rf/reg-machine :lifecycle/birth-gen m)
      ;; The eager start kick runs the initial-entry cascade in maybe-boot.
      (rf/dispatch-sync [:lifecycle/birth-gen [:rf.machine/start]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 9 (:birth (mtest/machine-data :lifecycle/birth-gen)))
          "the initial-entry action wrote the GENERATED fact — ensured before
           maybe-boot ran the bootstrap cascade"))))

(deftest initial-descent-entry-action-reads-generated-fact
  (testing "end-to-end: a compound initial state descends through :initial at
            birth; a named :entry action on the descent leaf reads the
            GENERATED recordable fact"
    (rf/reg-cofx :test/descent-gen {:recordable? true} (fn [] 11))
    (let [m {:initial :outer
             :data    {}
             :actions {:descent-stamp
                       {:rf.cofx/requires [:test/descent-gen]
                        :fn (fn [{:keys [data] cofx :rf.cofx}]
                              {:data (assoc data :d (:test/descent-gen cofx))})}}
             :states  {:outer {:initial :inner
                               :states  {:inner {:entry :descent-stamp}}}}}]
      (rf/reg-machine :lifecycle/descent-gen m)
      (rf/dispatch-sync [:lifecycle/descent-gen [:rf.machine/start]]
                        {:rf.cofx {:rf/time-ms SCRIPTED-TIME-MS}})
      (is (= 11 (:d (mtest/machine-data :lifecycle/descent-gen)))
          "the initial-descent :entry action read the GENERATED fact at birth"))))
