(ns re-frame.machine-cofx-mint-policy-test
  "The machine ensure path mints under the effective mint policy; a raised
  event's guard / action facts are ensured before its selection; an action's
  `:db` hard-disallow names the actor and redacts at egress."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private machine-state rf.machines.test-support/machine-state)

(defn- error-id [f]
  (try (f) nil (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- reg-roll! [] (rf/reg-cofx :mint/roll {:recordable? true} (fn [] 6)))

(def ^:private rolled-six
  {:rf.cofx/requires [:mint/roll]
   :fn (fn [{cofx :rf.cofx}] (= 6 (:mint/roll cofx)))})

(deftest strict-per-call-machine-does-not-advance
  ;; an always-:live mint would generate the fact and advance to :done
  (reg-roll!)
  (rf/reg-machine :mint/strict-noadvance
    {:initial :idle
     :guards  {:rolled-six? rolled-six}
     :states  {:idle {:on {:go {:target :done :guard :rolled-six?}}}
               :done {}}})
  (rf/dispatch-sync [:mint/strict-noadvance [:go]]
                    {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
  (is (not= :done (machine-state :mint/strict-noadvance))))

(deftest strict-bootstrap-ensure-refuses-to-mint-birth-fact-throws
  (rf/reg-cofx :mint/birth-roll {:recordable? true} (fn [] 9))
  (let [m (rf.machines.cofx-attach/index-ensure-sets
            {:initial :booting
             :actions {:stamp-birth {:rf.cofx/requires [:mint/birth-roll] :fn (fn [_] nil)}}
             :states  {:booting {:entry :stamp-birth}}})]
    (is (= :rf.error/missing-required-cofx
           (error-id #(rf.machines.cofx-attach/bootstrap-ensure-cofx m {} nil :mint/strict-birth :strict))))))

(deftest raised-user-event-strict-missing-fact-throws
  ;; :rf/time-ms is a PROVIDED fact: absent from the token, the in-drain raise
  ;; ensure surfaces missing-required instead of the guard reading nil.
  (let [m (-> (rf.machines.cofx-attach/index-ensure-sets
                {:initial :a
                 :guards  {:needs-time? {:rf.cofx/requires [:rf/time-ms]
                                         :fn (fn [{cofx :rf.cofx}] (some? (:rf/time-ms cofx)))}}
                 :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
                 :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
                           :b    {:on {:inner {:target :done :guard :needs-time?}}}
                           :done {}}})
              (assoc :rf/cofx {} :rf/cofx-mint-policy :strict))]
    (is (= :rf.error/missing-required-cofx
           (error-id #(rf.machines/machine-transition m {:state :a :data {}} [:go]))))))

(deftest compound-on-done-raised-signal-ensures-cofx
  ;; reaching :work's :final? child raises [:rf.machine/done [:work]]; the
  ;; :on-done guard's fact is not in [:fin]'s ensure-set
  (reg-roll!)
  (rf/reg-machine :raise/compound-done
    {:initial :work
     :guards  {:rolled-six? rolled-six}
     :states  {:work {:initial :step
                      :states  {:step {:on {:fin :fin}} :fin {:final? true}}
                      :on-done {:target :done :guard :rolled-six?}}
               :done {}}})
  (rf/dispatch-sync [:raise/compound-done [:fin]])
  (is (= [:done] (machine-state :raise/compound-done))))

(deftest parallel-region-raise-guard-fact-ensured
  ;; :left raises :inner; the parent re-broadcasts it, and :right's guard needs the fact
  (reg-roll!)
  (rf/reg-machine :raise/parallel-region
    {:type    :parallel
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
