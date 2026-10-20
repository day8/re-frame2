(ns re-frame.machine-cofx-mint-policy-test
  "The machine ensure path mints under the effective mint policy; a raised
  event's guard / action reads the facts ensured before the macrostep; an
  action's `:db` hard-disallow names the actor and redacts at egress."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private machine-state rf.machines.test-support/machine-state)

(defn- error-id [f]
  (try (f) nil (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- reg-roll! [] (rf/reg-cofx :mint/roll {:recordable? true} (fn [] 6)))

(defn- rolled-six [{cofx :rf.cofx}] (= 6 (:mint/roll cofx)))

(deftest strict-per-call-machine-does-not-advance
  ;; an always-:live mint would generate the fact and advance to :done
  (reg-roll!)
  (rf/reg-machine :mint/strict-noadvance
    {:rf.cofx/requires [:mint/roll]
     :initial :idle
     :guards  {:rolled-six? rolled-six}
     :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
               :done {}}})
  (rf/dispatch-sync [:mint/strict-noadvance [:go]]
                    {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
  (is (not= :done (machine-state :mint/strict-noadvance))))

(deftest strict-ensure-refuses-to-mint-throws
  (rf/reg-cofx :mint/birth-roll {:recordable? true} (fn [] 9))
  (let [m (rf.machines.cofx-attach/parse-machine-requires
            {:rf.cofx/requires [:mint/birth-roll]
             :initial :booting
             :actions {:stamp-birth (fn [_] nil)}
             :states  {:booting {:entry :stamp-birth}}})]
    (is (= :rf.error/missing-required-cofx
           (error-id #(rf.machines.cofx-attach/ensure-cofx m {} nil :mint/strict-birth :strict))))))

(deftest raised-user-event-strict-missing-fact-is-refused-before-the-macrostep
  ;; under :strict the absent generator-backed fact refuses the event at the
  ;; ensure step, before the raise's guard could read nil.
  (reg-roll!)
  (rf/reg-machine :mint/raised-strict
    {:rf.cofx/requires [:mint/roll]
     :initial :a
     :guards  {:needs-roll? (fn [{cofx :rf.cofx}] (some? (:mint/roll cofx)))}
     :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
     :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
               :b    {:on {:inner {:target :done :guard :needs-roll?}}}
               :done {}}})
  (rf/dispatch-sync [:mint/raised-strict [:go]] {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
  ;; refused before birth, so the machine was never created
  (is (= [nil 1] [(machine-state :mint/raised-strict)
                 (count (rf.machines.test-support/events-of :rf.error/missing-required-cofx))])))

(deftest compound-on-done-raised-signal-reads-the-ensured-fact
  ;; reaching :work's :final? child raises [:rf.machine/done [:work]], and the
  ;; :on-done guard reads the fact ensured for [:fin]
  (reg-roll!)
  (rf/reg-machine :raise/compound-done
    {:rf.cofx/requires [:mint/roll]
     :initial :work
     :guards  {:rolled-six? rolled-six}
     :states  {:work {:initial :step
                      :states  {:step {:on {:fin :fin}} :fin {:final? true}}
                      :on-done {:target :done :guard :rolled-six?}}
               :done {}}})
  (rf/dispatch-sync [:raise/compound-done [:fin]])
  (is (= [:done] (machine-state :raise/compound-done))))

(deftest parallel-region-raise-guard-reads-the-ensured-fact
  ;; :left raises :inner; the parent re-broadcasts it, and :right's guard reads the fact
  (reg-roll!)
  (rf/reg-machine :raise/parallel-region
    {:rf.cofx/requires [:mint/roll]
     :type    :parallel
     :guards  {:rolled-six? rolled-six}
     :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
     :regions {:left  {:initial :one
                       :states  {:one {:on {:go {:target :two :action :raise-inner}}} :two {}}}
               :right {:initial :one
                       :states  {:one {:on {:inner {:target :two :guard :rolled-six?}}} :two {}}}}})
  (rf/dispatch-sync [:raise/parallel-region [:go]])
  (is (= {:left :two :right :two} (machine-state :raise/parallel-region))))

(deftest action-wrote-db-uses-actor-id-and-redacts
  (rf/reg-machine :wrote-db/upd
    {:initial :a
     :actions {:patch (fn [_] {:db {:secret "leak-me"} :data {:n 7}})}
     :states  {:a {:on {:go {:target :a :action :patch}}}}})
  (rf.machines.test-support/with-trace-capture seen
    (rf/dispatch-sync [:wrote-db/upd [:go]])
    (let [tags (->> @seen
                    (filter #(= :rf.error/machine-action-wrote-db (:operation %)))
                    (mapv :tags))]
      (is (= [{:actor-id :wrote-db/upd :offending-value :rf/redacted}]
             (mapv #(select-keys % [:actor-id :offending-value]) tags)))
      (is (not (re-find #"leak-me" (pr-str tags)))))))
