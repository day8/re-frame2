(ns re-frame.machine-cofx-lifecycle-test
  "A named `:entry` / `:exit` action reads the machine's ensured facts: on a
  transition and at birth."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- stamp
  "A named action that folds the fact `cofx-id` into `:data` under `k`."
  [k cofx-id]
  (fn [{:keys [data] cofx :rf.cofx}] {:data (assoc data k (cofx-id cofx))}))

(deftest entry-and-exit-actions-read-generated-facts
  (rf/reg-cofx :test/exit-gen {:recordable? true} (fn [] 7))
  (rf/reg-cofx :test/entry-gen {:recordable? true} (fn [] 6))
  (rf/reg-machine :lifecycle/entry-exit
    {:rf.cofx/requires [:test/exit-gen :test/entry-gen]
     :initial :idle
     :data    {}
     :actions {:stamp-exit  (stamp :exit-roll :test/exit-gen)
               :stamp-entry (stamp :roll :test/entry-gen)}
     :states  {:idle   {:exit :stamp-exit :on {:go :active}}
               :active {:entry :stamp-entry}}})
  (rf/dispatch-sync [:lifecycle/entry-exit [:go]])
  (is (= {:exit-roll 7 :roll 6} (rf.machines.test-support/machine-data :lifecycle/entry-exit))))

(deftest initial-descent-entry-action-reads-generated-fact
  (rf/reg-cofx :test/descent-gen {:recordable? true} (fn [] 11))
  (rf/reg-machine :lifecycle/descent-gen
    {:rf.cofx/requires [:test/descent-gen]
     :initial :outer
     :data    {}
     :actions {:descent-stamp (stamp :d :test/descent-gen)}
     :states  {:outer {:initial :inner
                       :states  {:inner {:entry :descent-stamp}}}}})
  (rf/dispatch-sync [:lifecycle/descent-gen [:rf.machine/start]])
  (is (= {:d 11} (rf.machines.test-support/machine-data :lifecycle/descent-gen))))
