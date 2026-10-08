(ns re-frame.machine-cofx-lifecycle-test
  "A named `:entry` / `:exit` action's `:rf.cofx/requires` is ensured before
  the cascade runs it: on a transition, at birth, and through every synthetic
  slot."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.cofx-attach :as rf.machines.cofx-attach]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- stamp
  "A named action requiring `cofx-id` that folds the fact into `:data` under `k`."
  [k cofx-id]
  {:rf.cofx/requires [cofx-id]
   :fn (fn [{:keys [data] cofx :rf.cofx}] {:data (assoc data k (cofx-id cofx))})})

(deftest entry-and-exit-actions-read-generated-facts
  (rf/reg-cofx :test/exit-gen {:recordable? true} (fn [] 7))
  (rf/reg-cofx :test/entry-gen {:recordable? true} (fn [] 6))
  (rf/reg-machine :lifecycle/entry-exit
    {:initial :idle
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
    {:initial :outer
     :data    {}
     :actions {:descent-stamp (stamp :d :test/descent-gen)}
     :states  {:outer {:initial :inner
                       :states  {:inner {:entry :descent-stamp}}}}})
  (rf/dispatch-sync [:lifecycle/descent-gen [:rf.machine/start]])
  (is (= {:d 11} (rf.machines.test-support/machine-data :lifecycle/descent-gen))))

;; Each synthetic slot hands its target to the same closure the :on walk uses,
;; so a named :entry on the target is ensured, not only its :always.
(deftest every-synthetic-slot-ensures-its-target-entry
  (rf/reg-cofx :audit/token {:recordable? true} (fn [] 42))
  (let [capture  {:capture (stamp :token :audit/token)}
        region-b {:initial :x :states {:x {}}}]
    (are [m snap ev] (= #{:audit/token}
                        (set (map :id (rf.machines.cofx-attach/ensure-set-for
                                        (rf.machines.cofx-attach/index-ensure-sets m) snap ev))))
      ;; :spawn :on-error into a compound — the :initial descent's :entry
      {:initial :working :actions capture
       :states  {:working {:spawn {:machine-id :x/c :on-error :errored}}
                 :errored {:initial :inner :states {:inner {:entry :capture}}}}}
      {:state :working}
      [:rf.machine.spawn/error [:working] {:boom 1}]
      ;; a compound's :on-done
      {:initial :flow :actions capture
       :states  {:flow  {:initial :s1 :on-done :after
                         :states  {:s1 {} :fin {:final? true}}}
                 :after {:entry :capture}}}
      {:state [:flow :fin]}
      [:rf.machine/done [:flow]]
      ;; a state :after
      {:initial :waiting :actions capture
       :states  {:waiting {:after {5000 :late}} :late {:entry :capture}}}
      {:state :waiting}
      [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]
      ;; a parallel root :on
      {:type    :parallel :actions capture
       :on      {:go {:target [:a :done]}}
       :regions {:a {:initial :idle :states {:idle {} :done {:entry :capture}}}
                 :b region-b}}
      {:state {:a :idle :b :x}}
      [:go]
      ;; a parallel root :after
      {:type    :parallel :actions capture
       :after   {1000 {:target [:a :done]}}
       :regions {:a {:initial :idle :states {:idle {} :done {:entry :capture}}}
                 :b region-b}}
      {:state {:a :idle :b :x}}
      [:rf.machine.timer/after-elapsed 1000 1 []])))
