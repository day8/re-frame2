(ns re-frame.join-work-identity-cljs-test
  "A `:spawn-all` child's canonical work id: a fixed-actor-id child's generation is
  the runtime join attempt — never its keyword spelling, a carried value or the
  live successor spec — and teardown never gives a closed attempt a second,
  contradictory terminal."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private child
  {:initial :running
   :states  {:running {:on {:go :done}}
             :done    {:final? true}}})

(defn- register-fixed-parent!
  "Register and start a parent whose `:spawn-all` pairs fixed-id children `:a` /
  `:b`. Resolution events are unhandled, so the join record survives for late
  probes; `:destroy-a` imperatively destroys `fixed-a` without leaving the state."
  [parent-id child-type fixed-a fixed-b join]
  (rf/reg-machine child-type child)
  (rf/reg-machine parent-id
    {:initial :idle
     :actions {:destroy-a (fn [_] {:fx [[:rf.machine/destroy fixed-a]]})}
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all {:children         [{:id :a :machine-id child-type :fixed-actor-id fixed-a}
                                                       {:id :b :machine-id child-type :fixed-actor-id fixed-b}]
                                    :join             join
                                    :on-all-complete  [:join/all]
                                    :on-some-complete [:join/some]}
                        :on {:destroy-a {:action :destroy-a}
                             :abort     :idle}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- join-attempt
  "A live child's `:rf/join-child` membership record — the coordinate its carrier carries."
  [actor-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :snapshots actor-id :data :rf/join-child]))

(defn- events-of [op]
  (rf.machines.test-support/events-of op))

(defn- first-work-id [op]
  (:rf.reply/work-id (:tags (first (events-of op)))))

(defn- terminal-statuses-for [work-id]
  (into []
        (comp (map :tags)
              (filter #(= work-id (:rf.reply/work-id %)))
              (keep :rf.reply/work-status)
              (filter #{:completed :failed :cancelled}))
        (rf.machines.test-support/captured-events)))

(defn- destroy-reasons [actor-id]
  (into [] (comp (filter #(= actor-id (get-in % [:tags :actor-id])))
                 (map #(get-in % [:tags :reason])))
        (events-of :rf.machine/destroyed)))

(defn- forged-completion!
  "Hand-deliver a completion carrier bearing `auth` (a `:rf/join-child` record read
  off a live child) as its coordinate — a late re-delivery the runtime never mints."
  [parent-id auth]
  (rf/dispatch-sync
    [parent-id [:rf.machine.spawn/done (:invoke-id auth)
                (assoc (select-keys auth [:parent-id :invoke-id :child-id
                                          :spawned-id :attempt :work-generation])
                       :result (:child-id auth)
                       :error? false)]]))

(deftest teardown-after-a-fold-adds-no-terminal-to-the-folded-child
  (register-fixed-parent! :jwi/td-parent :jwi/td-child :jwi/td-a#7 :jwi/td-b :all)
  (let [attempt (:rf/attempt (join-state :jwi/td-parent))
        work    #(vector :rf.work/machine % [:racing] attempt)]
    (rf/dispatch-sync [:jwi/td-a#7 [:go]])          ;; non-decisive fold; A closes itself at finality
    (rf/dispatch-sync [:jwi/td-parent [:destroy-a]]) ;; names A's dead address
    (rf/dispatch-sync [:jwi/td-parent [:abort]])     ;; parent exit before B reports
    (is (= [[:completed :completed] [:rf.machine/finished] [:cancelled]]
           [(terminal-statuses-for (work :jwi/td-a#7))
            (destroy-reasons :jwi/td-a#7)
            (terminal-statuses-for (work :jwi/td-b))])
        "A keeps its two agreeing :completed rows and its own finality teardown; only B is cancelled")))

(deftest late-carrier-after-explicit-cancellation-cannot-fold
  (register-fixed-parent! :jwi/lc-parent :jwi/lc-child :jwi/lc-a#7 :jwi/lc-b :all)
  (let [attempt (:rf/attempt (join-state :jwi/lc-parent))
        auth-a  (join-attempt :jwi/lc-a#7)]          ;; formed while A is live: exact-current
    (rf/dispatch-sync [:jwi/lc-parent [:destroy-a]])  ;; closes A's attempt as a cancellation
    (forged-completion! :jwi/lc-parent auth-a)
    (let [j (join-state :jwi/lc-parent)]
      (is (= [#{} #{:a} [:cancelled] [:rf.machine.spawn-all/duplicate-completion]]
             [(:done j)
              (:cancelled j)
              (terminal-statuses-for [:rf.work/machine :jwi/lc-a#7 [:racing] attempt])
              (mapv (comp :rf.reply/stale-reason :tags)
                    (events-of :rf.machine.spawn-all/stale-completion))])
          "the tombstone refuses the carrier; cancellation stays the attempt's sole terminal"))
    (rf/dispatch-sync [:jwi/lc-parent [:abort]])
    (rf/dispatch-sync [:jwi/lc-parent [:start]])
    (is (= #{} (:cancelled (join-state :jwi/lc-parent))) "a new attempt starts with no tombstones")))

(deftest fixed-id-join-attempt-authority-is-the-machine-work-generation
  (testing "accepted, late, superseded and tampered evidence carry the runtime
            attempt, never the #7 spelling, a carried value or the successor spec"
    (register-fixed-parent! :jwi/parent :jwi/child :jwi/fixed-a#7 :jwi/fixed-b :all)
    (let [a1   [:rf.work/machine :jwi/fixed-a#7 [:racing] (:rf/attempt (join-state :jwi/parent))]
          auth (join-attempt :jwi/fixed-a#7)]
      (rf/dispatch-sync [:jwi/fixed-a#7 [:go]])
      (rf/dispatch-sync [:jwi/fixed-b [:go]])
      (forged-completion! :jwi/parent auth)          ;; exact-current, after resolution
      (is (= [a1 a1] [(first-work-id :rf.machine.spawn-all/child-completed)
                      (first-work-id :rf.machine.spawn-all/late-completion)]))
      ;; Re-enter: the fixed addresses repeat, only the attempt tells A1 from A2.
      (rf/dispatch-sync [:jwi/parent [:abort]])
      (rf/dispatch-sync [:jwi/parent [:start]])
      (let [attempt-2 (:rf/attempt (join-state :jwi/parent))]
        (rf.machines.test-support/reset-captured!)
        (forged-completion! :jwi/parent auth)        ;; attempt 1's carrier: superseded
        (forged-completion! :jwi/parent (assoc (join-attempt :jwi/fixed-a#7)
                                               :work-generation (if (= 7 attempt-2) 8 7)))
        (is (= [a1 [:rf.work/machine :jwi/fixed-a#7 [:racing] attempt-2]]
               [(first-work-id :rf.machine.spawn-all/stale-completion)
                (first-work-id :rf.machine.spawn-all/child-completed)])))
      ;; Re-registration (HMR) turns :a generated; the live successor spec cannot
      ;; reclassify attempt 1's evidence.
      (rf/dispatch-sync [:jwi/parent [:abort]])
      (rf/reg-machine :jwi/parent
        {:initial :idle
         :states  {:idle   {:on {:start :racing}}
                   :racing {:spawn-all {:children        [{:id :a :machine-id :jwi/child}
                                                          {:id :b :machine-id :jwi/child
                                                           :fixed-actor-id :jwi/fixed-b}]
                                        :join            :all
                                        :on-all-complete [:join/all]}
                            :on {:abort :idle}}}})
      (rf/dispatch-sync [:jwi/parent [:start]])
      (rf.machines.test-support/reset-captured!)
      (forged-completion! :jwi/parent auth)
      (is (= a1 (first-work-id :rf.machine.spawn-all/stale-completion)))))

  (testing "a survivor's join-resolution cancellation carries the fixed-id attempt"
    (rf.machines.test-support/reset-captured!)
    (register-fixed-parent! :jwi/some-parent :jwi/some-child :jwi/fixed-c :jwi/fixed-d :any)
    (let [expected [:rf.work/machine :jwi/fixed-d [:racing] (:rf/attempt (join-state :jwi/some-parent))]]
      (rf/dispatch-sync [:jwi/fixed-c [:go]])
      (is (= [expected expected]
             [(first-work-id :rf.machine.spawn/cancelled-on-join-resolution)
              (->> (events-of :rf.machine/destroyed)
                   (filter #(= [:jwi/fixed-d :explicit] ((juxt :actor-id :reason) (:tags %))))
                   first :tags :rf.reply/work-id)]))))

  (testing "a fixed join child's own final-leaf reply and its fold share one work id"
    (rf.machines.test-support/reset-captured!)
    (rf/reg-machine :jwi/final-child child)
    (rf/reg-machine :jwi/final-parent
      {:initial :idle
       :states  {:idle   {:on {:start :racing}}
                 :racing {:spawn-all {:children        [{:id :only :machine-id :jwi/final-child
                                                         :fixed-actor-id :jwi/fixed-final}]
                                      :join            :all
                                      :on-all-complete [:join/done]}}}})
    (rf/dispatch-sync [:jwi/final-parent [:start]])
    (let [expected [:rf.work/machine :jwi/fixed-final [:racing] (:rf/attempt (join-state :jwi/final-parent))]]
      (rf/dispatch-sync [:jwi/fixed-final [:go]])
      (is (= [expected expected]
             [(first-work-id :rf.machine/done)
              (first-work-id :rf.machine.spawn-all/all-completed)])))))
