(ns re-frame.join-work-identity-cljs-test
  "Cross-platform counterfixtures for `:spawn-all` canonical work identity.

  A fixed actor address is reusable after teardown, so its name cannot identify
  a join attempt. These tests drive real CLJ/CLJS machine cascades and require
  every reply-bearing path to reuse the runtime-minted `:rf/attempt` authority
  as the fixed actor's existing machine-work-id generation slot. Generated
  `<type>#<n>` actors keep their actor-name generation, and a normal fixed-id
  single spawn keeps generation 1."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.reply :as m-reply]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

(defn- dispatching-child [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:remember-id (fn [{data :data event :event}]
                            {:data (assoc data :id (second event))})
             :complete    (fn [{data :data}]
                            {:fx [[:dispatch
                                   [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :remember-id}
                            :go     {:target :done :action :complete}}}
             :done    {}}})

(defn- register-fixed-parent!
  [parent-id child-a-type child-b-type fixed-a fixed-b join]
  (rf/reg-machine child-a-type (dispatching-child parent-id))
  (rf/reg-machine child-b-type (dispatching-child parent-id))
  (rf/reg-machine
    parent-id
    {:initial :idle
     :states
     {:idle   {:on {:start :racing}}
      :racing {:spawn-all
               {:children [{:id :a :machine-id child-a-type
                            :fixed-actor-id fixed-a :start [:set-id :a]}
                           {:id :b :machine-id child-b-type
                            :fixed-actor-id fixed-b :start [:set-id :b]}]
                :join join
                :on-child-done :child/done
                :on-child-error :child/error
                :on-all-complete [:join/all]
                :on-some-complete [:join/some]}
               ;; Deliberately leave resolution events unhandled so the frozen
               ;; join record remains available for exact-current late probes.
               :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- join-state [parent-id]
  (get-in (mtest/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- join-auth [actor-id]
  (get-in (mtest/runtime-db)
          [:rf.runtime/machines :snapshots actor-id :data :rf/join-child]))

(defn- events-of [op]
  (mtest/events-of op))

(defn- work-id-of [event]
  (:rf.reply/work-id (:tags event)))

(defn- forged-completion! [parent-id auth]
  (rf/dispatch-sync
    [parent-id (with-meta [:child/done (:child-id auth)]
                 {:rf/join-auth auth})]))

(deftest fixed-id-join-attempt-authority-is-the-machine-work-generation
  (testing "sequential fixed-id attempts stay distinct across accepted, late,
            superseded, and cancellation evidence"
    (register-fixed-parent! :jwi/parent :jwi/a-type :jwi/b-type
                            :jwi/fixed-a :jwi/fixed-b :all)
    (let [attempt-1 (:rf/attempt (join-state :jwi/parent))
          auth-a-1 (join-auth :jwi/fixed-a)]
      ;; A is non-decisive; B is decisive. Both are accepted terminals from
      ;; the same attempt, but each has its own actor-specific work id.
      (rf/dispatch-sync [:jwi/fixed-a [:go]])
      (let [a1-terminal (work-id-of (first (events-of
                                             :rf.machine.spawn-all/child-completed)))]
        (rf/dispatch-sync [:jwi/fixed-b [:go]])
        (let [b1-terminal (work-id-of (first (events-of
                                               :rf.machine.spawn-all/all-completed)))]
          ;; An exact-current post-resolution arrival is stale on the SAME
          ;; attempt arc; it must not mint or borrow another identity.
          (forged-completion! :jwi/parent auth-a-1)
          (let [a1-late (work-id-of (first (events-of
                                            :rf.machine.spawn-all/late-completion)))]
            (is (= [:rf.work/machine :jwi/fixed-a [:racing] attempt-1]
                   a1-terminal))
            (is (= [:rf.work/machine :jwi/fixed-b [:racing] attempt-1]
                   b1-terminal))
            (is (= a1-terminal a1-late)
                "late evidence retains the exact current attempt identity"))

          ;; Re-enter the same parent/invoke path. The fixed actor addresses
          ;; are intentionally identical; only the carried authority can
          ;; distinguish A from B.
          (rf/dispatch-sync [:jwi/parent [:abort]])
          (rf/dispatch-sync [:jwi/parent [:start]])
          (let [attempt-2 (:rf/attempt (join-state :jwi/parent))]
            (is (not= attempt-1 attempt-2))
            (is (= :jwi/fixed-a (get-in (join-state :jwi/parent)
                                        [:children :a])))
            (mtest/reset-captured!)
            (forged-completion! :jwi/parent auth-a-1)
            (let [old-stale (work-id-of (first (events-of
                                                 :rf.machine.spawn-all/stale-completion)))]
              (rf/dispatch-sync [:jwi/fixed-a [:go]])
              (let [a2-terminal (work-id-of (first (events-of
                                                     :rf.machine.spawn-all/child-completed)))]
                (is (= a1-terminal old-stale)
                    "the stale carrier uses attempt A's carried authority")
                (is (= [:rf.work/machine :jwi/fixed-a [:racing] attempt-2]
                       a2-terminal))
                (is (not= old-stale a2-terminal)
                    "attempt A suppression cannot land on attempt B's arc"))))))))

  (testing "join resolution cancellation uses the same exact fixed-id attempt"
    (mtest/reset-captured!)
    (register-fixed-parent! :jwi/some-parent :jwi/c-type :jwi/d-type
                            :jwi/fixed-c :jwi/fixed-d :any)
    (let [attempt (:rf/attempt (join-state :jwi/some-parent))]
      (rf/dispatch-sync [:jwi/fixed-c [:go]])
      (let [expected  [:rf.work/machine :jwi/fixed-d [:racing] attempt]
            cancelled (first (events-of
                               :rf.machine.spawn/cancelled-on-join-resolution))
            destroyed (first (filter #(and (= :jwi/fixed-d
                                              (get-in % [:tags :actor-id]))
                                           (= :explicit
                                              (get-in % [:tags :reason])))
                                     (events-of :rf.machine/destroyed)))]
        (is (= expected (work-id-of cancelled)))
        (is (= expected (work-id-of destroyed))
            "the destroy-side cancellation reuses the join attempt identity"))))

  (testing "a fixed join child final leaf and its accepted fold share one id"
    (mtest/reset-captured!)
    (rf/reg-machine
      :jwi/final-child-type
      {:initial :running
       :actions {:complete (fn [_]
                             {:fx [[:dispatch
                                    [:jwi/final-parent [:child/done :only]]]]})}
       :states {:running {:on {:go {:target :done :action :complete}}}
                :done {:final? true}}})
    (rf/reg-machine
      :jwi/final-parent
      {:initial :idle
       :states {:idle {:on {:start :racing}}
                :racing
                {:spawn-all
                 {:children [{:id :only :machine-id :jwi/final-child-type
                              :fixed-actor-id :jwi/fixed-final}]
                  :join :all
                  :on-child-done :child/done
                  :on-child-error :child/error
                  :on-all-complete [:join/done]}}}})
    (rf/dispatch-sync [:jwi/final-parent [:start]])
    (let [attempt  (:rf/attempt (join-state :jwi/final-parent))
          expected [:rf.work/machine :jwi/fixed-final [:racing] attempt]]
      (rf/dispatch-sync [:jwi/fixed-final [:go]])
      (is (= expected (work-id-of (first (events-of :rf.machine/done))))
          "the final-leaf actor terminal uses its private join membership")
      (is (= expected
             (work-id-of (first (events-of
                                  :rf.machine.spawn-all/all-completed))))
          "the join fold reuses that same canonical work identity")))

  (testing "generated actor and ordinary single-spawn identities are unchanged"
    (is (= [:rf.work/machine :jwi/generated#7 [:racing] 7]
           (:rf.reply/work-id
             (m-reply/join-child-reply
               {:parent-id :jwi/parent :invoke-id [:racing]
                :child-id :a :spawned-id :jwi/generated#7 :attempt 999}
               :done [])))
        "a generated actor keeps its exact #n generation")
    (is (= [:rf.work/machine :jwi/single-fixed [:working] 1]
           (:rf.reply/work-id
             (m-reply/success-reply
               {:actor-id :jwi/single-fixed :work-bearing-path [:working]}
               :ok)))
        "normal fixed-id single spawn remains generation 1")))
