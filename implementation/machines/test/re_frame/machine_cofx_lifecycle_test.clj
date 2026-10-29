(ns re-frame.machine-cofx-lifecycle-test
  "A named `:entry` / `:exit` action's `:rf.cofx/requires` is ensured before
  the cascade runs it: on a transition, at birth, and through every synthetic
  slot."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
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

;; The root's own `:entry`, a parallel root's `:entry` and a region body's
;; `:entry` all run at birth, so the birth ensure-set covers them as it covers
;; the initial descent.
(deftest root-and-region-body-entry-actions-read-generated-facts
  (rf/reg-cofx :test/root-gen {:recordable? true} (fn [] 13))
  (let [acts   {:stamp (stamp :v :test/root-gen)}
        region {:initial :x :states {:x {}}}]
    (rf/reg-machine :lifecycle/root-entry
      {:initial :idle :data {} :actions acts :entry :stamp :states {:idle {}}})
    (rf/reg-machine :lifecycle/parallel-root-entry
      {:type :parallel :data {} :actions acts :entry :stamp
       :regions {:a region :b region}})
    (rf/reg-machine :lifecycle/region-body-entry
      {:type :parallel :data {} :actions acts
       :regions {:a (assoc region :entry :stamp) :b region}})
    (doseq [id [:lifecycle/root-entry :lifecycle/parallel-root-entry :lifecycle/region-body-entry]]
      (rf/dispatch-sync [id [:rf.machine/start]])
      (is (= {:v 13} (rf.machines.test-support/machine-data id)) (str id)))))

;; A parallel root's `:on-done` action runs when the last region settles final,
;; inside the event that finished it.
(deftest parallel-root-on-done-action-reads-generated-fact
  (rf/reg-cofx :test/done-gen {:recordable? true} (fn [] 17))
  (let [region {:initial :x :states {:x {:on {:finish :f}} :f {:final? true}}}]
    (rf/reg-machine :lifecycle/parallel-on-done
      {:type :parallel :data {} :actions {:stamp (stamp :v :test/done-gen)}
       :on-done {:action :stamp}
       :regions {:a region :b region}})
    (rf/dispatch-sync [:lifecycle/parallel-on-done [:rf.machine/start]])
    (rf/dispatch-sync [:lifecycle/parallel-on-done [:finish]])
    (is (= {:v 17} (rf.machines.test-support/machine-data :lifecycle/parallel-on-done)))))

;; A transition into a `:type :history` pseudo-state enters the leaf the
;; history restores, so that leaf's `:entry` is ensured.
(deftest history-restore-ensures-the-restored-leaf-entry
  (rf/reg-cofx :test/history-gen {:recordable? true} (fn [] 19))
  (rf/reg-machine :lifecycle/history
    {:initial :c
     :data    {}
     :actions {:stamp (stamp :v :test/history-gen)
               :clear (fn [{:keys [data]}] {:data (dissoc data :v)})}
     :states  {:c   {:initial :a
                     :on      {:leave {:target :out :action :clear}}
                     :states  {:a {:on {:next :b}}
                               :b {:entry :stamp}
                               :h {:type :history}}}
               :out {:on {:resume [:c :h]}}}})
  (doseq [ev [[:next] [:leave] [:resume]]]
    (rf/dispatch-sync [:lifecycle/history ev]))
  (is (= {:state [:c :b] :data {:v 19}}
         (select-keys (rf.machines.test-support/snapshot :lifecycle/history) [:state :data]))))

;; Every leaf a history pseudo-state can restore, and its `:default-target`,
;; joins the ensure-set — whichever route reaches the pseudo-state.
(deftest every-history-route-ensures-the-restorable-entries
  (rf/reg-cofx :audit/hist {:recordable? true} (fn [] 1))
  (let [acts {:capture (stamp :t :audit/hist)}
        ids  (fn [m snap ev]
               (set (map :id (rf.machines.cofx-attach/ensure-set-for
                               (rf.machines.cofx-attach/index-ensure-sets m) snap ev))))]
    (are [m snap ev] (= #{:audit/hist} (ids m snap ev))
      ;; shallow: a direct child's initial descent
      {:initial :out :actions acts
       :states  {:c   {:initial :a :states {:a {} :b {:initial :b1 :states {:b1 {:entry :capture}}}
                                            :h {:type :history}}}
                 :out {:on {:resume [:c :h]}}}}
      {:state :out} [:resume]
      ;; deep: any descendant
      {:initial :out :actions acts
       :states  {:c   {:initial :a :states {:a {} :b {:initial :b1 :states {:b1 {} :b2 {:entry :capture}}}
                                            :h {:type :history :deep? true}}}
                 :out {:on {:resume [:c :h]}}}}
      {:state :out} [:resume]
      ;; an `:always` hop into the pseudo-state
      {:initial :out :actions acts
       :states  {:c   {:initial :a :states {:a {} :b {:entry :capture} :h {:type :history}}}
                 :out {:on {:go :mid}}
                 :mid {:always {:target [:c :h]}}}}
      {:state :out} [:go])
    (testing "a compound whose :initial names its history enters the :default-target at birth"
      (is (= #{:audit/hist}
             (set (map :id (rf.machines.cofx-attach/bootstrap-ensure-set-for
                             (rf.machines.cofx-attach/index-ensure-sets
                               {:initial :c :actions acts
                                :states  {:c {:initial :h
                                              :states  {:h {:type :history :default-target :a}
                                                        :a {:entry :capture}}}}})))))))))

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

;; An `:always` hop runs the lifecycle cascade as a transition does, so a named
;; `:entry` on the state it reaches and a named `:exit` on the state it leaves
;; are ensured with the hop's guard and action.
(deftest an-always-hop-ensures-the-entry-it-reaches-and-the-exit-it-leaves
  (rf/reg-cofx :test/hop-gen {:recordable? true} (fn [] 42))
  (let [stamp* {:stamp (stamp :v :test/hop-gen)}]
    (rf/reg-machine :lifecycle/hop-entry
      {:initial :a :data {} :actions stamp*
       :states  {:a {:on {:go :b}}
                 :b {:always {:target :c}}
                 :c {:entry :stamp}}})
    (rf/reg-machine :lifecycle/hop-exit
      {:initial :a :data {} :actions stamp*
       :states  {:a {:on {:go :b}}
                 :b {:exit :stamp :always {:target :c}}
                 :c {}}})
    (rf/dispatch-sync [:lifecycle/hop-entry [:go]])
    (rf/dispatch-sync [:lifecycle/hop-exit [:go]])
    (is (= [{:v 42} {:v 42}]
           (mapv rf.machines.test-support/machine-data [:lifecycle/hop-entry :lifecycle/hop-exit])))))
