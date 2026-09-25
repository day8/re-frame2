(ns re-frame.root-spawn-test
  "A machine root's `:spawn` is a child that lives as long as the machine —
  XState's root `invoke` — per Spec 005 §The machine root.

  - It spawns once, at birth, under invoke-id `[]`: after the root's `:entry`
    and before the initial descent, on the eager and the lazy birth paths.
    The registry slot is `[:rf.runtime/machines :spawned <parent> []]` and the
    parent's mirror `[:data :rf/spawned []]`.
  - It survives every transition, because the root never exits on one.
  - Its completion carrier folds through the root `:spawn :on-done` fn or
    takes a transition-shaped `:on-done`; its failure carrier takes the root
    `:spawn :on-error`. A keyword target at `[]` names a top-level state; a
    `:type :parallel` root's target is region-qualified, as its `:on` is.
  - It is destroyed with its owner — at whole-machine finality and on destroy —
    after the root's `:exit` has run.

  Flat and parallel roots alike. Controls: a state-level `:spawn` in the same
  machine is still destroyed when its state exits, and a carrier from a
  superseded attempt is still dropped. Live runtime (plain-atom substrate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- slot
  "The parent's registry slot at `invoke-id`."
  [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- reg-kid!
  "A child resting in `:w`. `:ping` counts; `:ok <v>` finishes it on a plain
  `:final?` leaf with result `v`; `:fail <v>` finishes it on an `:error?` leaf
  with error payload `v`. Its `:w` `:exit` appends `[id :exit]` to `log`."
  [id log]
  (rf/reg-machine id
    {:initial :w
     :data    {}
     :states  {:w      {:exit (fn [_] (swap! log conj [id :exit]) nil)
                        :on   {:ping {:action (fn [{d :data}] {:data (update d :pings (fnil inc 0))})}
                               :ok   {:target :done
                                      :action (fn [{d :data ev :event}] {:data (assoc d :out (second ev))})}
                               :fail {:target :failed
                                      :action (fn [{d :data ev :event}] {:data (assoc d :out (second ev))})}}}
               :done   {:final? true :output-key :out}
               :failed {:final? true :error? true :output-key :out}}}))

(defn- flat-parent
  "A flat machine whose root spawns `kid`. `:d` is a top-level `:final?` leaf;
  the root `:exit` appends `[:parent :root-exit]` to `log`."
  [kid log extra]
  (merge {:initial :a
          :data    {}
          :exit    (fn [_] (swap! log conj [:parent :root-exit]) nil)
          :spawn   {:machine-id kid}
          :states  {:a {:on {:hop :b :go :d}}
                    :b {:on {:back :a}}
                    :d {:final? true}}}
         extra))

(defn- parallel-parent
  "A parallel machine whose root spawns `kid`. `:fin` makes both regions final;
  the root `:exit` appends `[:parent :root-exit]` to `log`."
  [kid log extra]
  (merge {:type    :parallel
          :data    {}
          :exit    (fn [_] (swap! log conj [:parent :root-exit]) nil)
          :spawn   {:machine-id kid}
          :regions {:x {:initial :x1
                        :states  {:x1 {:on {:hop :x2 :fin :xf}}
                                  :x2 {:on {:back :x1 :fin :xf}}
                                  :xf {:final? true}}}
                    :y {:initial :y1
                        :states  {:y1 {:on {:fin :yf}}
                                  :yf {:final? true}}}}}
         extra))

(defn- kill!
  "Destroy `actor-id` through the `:rf.machine/destroy` fx."
  [actor-id]
  (rf/reg-event ::kill (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
  (rf/dispatch-sync [::kill actor-id]))

(defn- record-error
  "A transition action storing the failure payload the carrier rides."
  [{d :data ev :event}]
  {:data (assoc d :err (nth ev 2))})

;; ---- birth -----------------------------------------------------------------

(deftest flat-root-spawns-its-child-at-birth
  (let [log (atom [])]
    (reg-kid! :rsf/kid log)
    (rf/reg-machine :rsf/p (flat-parent :rsf/kid log {}))
    (rf/dispatch-sync [:rsf/p [:rf.machine/start]])
    (is (some? (snapshot :rsf/kid#1)) "the root child is live after start")
    (is (= :rsf/kid#1 (slot :rsf/p [])) "the registry slot is the root's, at invoke-id []")
    (is (= :rsf/kid#1 (get-in (snapshot :rsf/p) [:data :rf/spawned []]))
        "the parent's :rf/spawned mirror names it at []")
    (is (= [] (get-in (snapshot :rsf/kid#1) [:data :rf/invoke-id])))))

(deftest flat-root-spawns-on-the-lazy-birth-path
  (let [log (atom [])]
    (reg-kid! :rsl/kid log)
    (rf/reg-machine :rsl/p (flat-parent :rsl/kid log {}))
    (rf/dispatch-sync [:rsl/p [:hop]])
    (is (= :b (:state (snapshot :rsl/p))))
    (is (some? (snapshot :rsl/kid#1)) "the first event's birth spawned the root child")))

(deftest parallel-root-spawns-its-child-at-birth
  (let [log (atom [])]
    (reg-kid! :rsp/kid log)
    (rf/reg-machine :rsp/p (parallel-parent :rsp/kid log {}))
    (rf/dispatch-sync [:rsp/p [:rf.machine/start]])
    (is (some? (snapshot :rsp/kid#1)))
    (is (= :rsp/kid#1 (slot :rsp/p [])))
    (is (= :rsp/kid#1 (get-in (snapshot :rsp/p) [:data :rf/spawned []])))))

(deftest root-spawn-follows-root-entry-and-precedes-the-descent
  (testing "the spawn's :data fn sees the root :entry's write and not the
            initial leaf's"
    (let [log (atom [])]
      (reg-kid! :rso/kid log)
      (rf/reg-machine :rso/p
        {:initial :a
         :data    {:trail []}
         :entry   (fn [{d :data}] {:data (update d :trail conj :root)})
         :spawn   {:machine-id :rso/kid
                   :data       (fn [{:keys [snapshot]}] {:seen (get-in snapshot [:data :trail])})}
         :states  {:a {:entry (fn [{d :data}] {:data (update d :trail conj :a)})}}})
      (rf/dispatch-sync [:rso/p [:rf.machine/start]])
      (is (= [:root] (get-in (snapshot :rso/kid#1) [:data :seen])))
      (is (= [:root :a] (get-in (snapshot :rso/p) [:data :trail]))))))

;; ---- lifetime --------------------------------------------------------------

(deftest flat-root-child-survives-transitions
  (let [log (atom [])]
    (reg-kid! :rss/kid log)
    (reg-kid! :rss/leaf-kid log)
    (rf/reg-machine :rss/p
      (flat-parent :rss/kid log
                   {:states {:a {:spawn {:machine-id :rss/leaf-kid} :on {:hop :b}}
                             :b {:on {:back :a}}}}))
    (rf/dispatch-sync [:rss/p [:rf.machine/start]])
    (rf/dispatch-sync [:rss/kid#1 [:ping]])
    (rf/dispatch-sync [:rss/p [:hop]])
    (rf/dispatch-sync [:rss/p [:back]])
    (is (= 1 (get-in (snapshot :rss/kid#1) [:data :pings]))
        "the same root child is still live, with its own state intact")
    (is (= :rss/kid#1 (slot :rss/p [])))
    (is (nil? (snapshot :rss/leaf-kid#1)) "control: :a's own child ended when :a exited")
    (is (some? (snapshot :rss/leaf-kid#2)) "control: re-entering :a spawned a fresh one")
    (is (= [[:rss/leaf-kid :exit]] @log))))

(deftest parallel-root-child-survives-transitions
  (let [log (atom [])]
    (reg-kid! :rpt/kid log)
    (rf/reg-machine :rpt/p (parallel-parent :rpt/kid log {}))
    (rf/dispatch-sync [:rpt/p [:rf.machine/start]])
    (rf/dispatch-sync [:rpt/kid#1 [:ping]])
    (rf/dispatch-sync [:rpt/p [:hop]])
    (rf/dispatch-sync [:rpt/p [:back]])
    (is (= 1 (get-in (snapshot :rpt/kid#1) [:data :pings])))
    (is (= [] @log))))

;; ---- completion and failure ------------------------------------------------

(deftest flat-root-on-done-folds-the-result
  (let [log (atom [])]
    (reg-kid! :rfd/kid log)
    (rf/reg-machine :rfd/p
      (flat-parent :rfd/kid log
                   {:spawn {:machine-id :rfd/kid
                            :on-done    (fn [{:keys [data result]}] (assoc data :got result))}}))
    (rf/dispatch-sync [:rfd/p [:rf.machine/start]])
    (rf/dispatch-sync [:rfd/kid#1 [:ok 42]])
    (is (nil? (snapshot :rfd/kid#1)) "the finished child is torn down")
    (is (= 42 (get-in (snapshot :rfd/p) [:data :got])) "its result folded into the root's :data")
    (is (nil? (slot :rfd/p [])))
    (is (nil? (get-in (snapshot :rfd/p) [:data :rf/spawned])))))

(deftest flat-root-transition-on-done-targets-a-top-level-state
  (let [log (atom [])]
    (reg-kid! :rft/kid log)
    (rf/reg-machine :rft/p (flat-parent :rft/kid log {:spawn {:machine-id :rft/kid :on-done :b}}))
    (rf/dispatch-sync [:rft/p [:rf.machine/start]])
    (rf/dispatch-sync [:rft/kid#1 [:ok 1]])
    (is (= :b (:state (snapshot :rft/p))))))

(deftest flat-root-on-error-takes-its-transition
  (let [log (atom [])]
    (reg-kid! :rfe/kid log)
    (rf/reg-machine :rfe/p
      (flat-parent :rfe/kid log
                   {:actions {:record record-error}
                    :spawn   {:machine-id :rfe/kid :on-error {:target :b :action :record}}}))
    (rf/dispatch-sync [:rfe/p [:rf.machine/start]])
    (rf/dispatch-sync [:rfe/kid#1 [:fail :boom]])
    (is (= :b (:state (snapshot :rfe/p))))
    (is (= :boom (get-in (snapshot :rfe/p) [:data :err])))))

(deftest parallel-root-on-done-folds-the-result
  (let [log (atom [])]
    (reg-kid! :rpd/kid log)
    (rf/reg-machine :rpd/p
      (parallel-parent :rpd/kid log
                       {:spawn {:machine-id :rpd/kid
                                :on-done    (fn [{:keys [data result]}] (assoc data :got result))}}))
    (rf/dispatch-sync [:rpd/p [:rf.machine/start]])
    (rf/dispatch-sync [:rpd/kid#1 [:ok 42]])
    (is (nil? (snapshot :rpd/kid#1)))
    (is (= 42 (get-in (snapshot :rpd/p) [:data :got])))))

(deftest parallel-root-transition-on-done-moves-a-region
  (let [log (atom [])]
    (reg-kid! :rpm/kid log)
    (rf/reg-machine :rpm/p
      (parallel-parent :rpm/kid log {:spawn {:machine-id :rpm/kid :on-done {:target [:x :x2]}}}))
    (rf/dispatch-sync [:rpm/p [:rf.machine/start]])
    (rf/dispatch-sync [:rpm/kid#1 [:ok 1]])
    (is (= {:x :x2 :y :y1} (:state (snapshot :rpm/p))))))

(deftest parallel-root-on-error-takes-its-transition
  (let [log (atom [])]
    (reg-kid! :rpe/kid log)
    (rf/reg-machine :rpe/p
      (parallel-parent :rpe/kid log
                       {:actions {:record record-error}
                        :spawn   {:machine-id :rpe/kid
                                  :on-error   {:target [:x :x2] :action :record}}}))
    (rf/dispatch-sync [:rpe/p [:rf.machine/start]])
    (rf/dispatch-sync [:rpe/kid#1 [:fail :boom]])
    (is (= {:x :x2 :y :y1} (:state (snapshot :rpe/p))))
    (is (= :boom (get-in (snapshot :rpe/p) [:data :err])))))

(deftest a-root-carrier-from-a-superseded-attempt-is-dropped
  (testing "the attempt check still applies to a root child, flat and parallel"
    (is (nil? (rf.machines.transition/spawn-carrier-stale-reason
                {:state :a :rf/spawn-attempts {[] 1}} [] 1)))
    (is (nil? (rf.machines.transition/spawn-carrier-stale-reason
                {:state {:x :x1 :y :y1} :rf/spawn-attempts {[] 1}} [] 1))
        "a parallel root carrier is current while the machine lives")
    (is (= :rf.machine.spawn/attempt-superseded
           (rf.machines.transition/spawn-carrier-stale-reason
             {:state {:x :x1 :y :y1} :rf/spawn-attempts {[] 2}} [] 1)))
    (is (= :rf.machine.spawn/state-exited
           (rf.machines.transition/spawn-carrier-stale-reason
             {:state {:x :x1 :y :y1} :rf/spawn-attempts {[:x :x2] 1}} [:x :x2] 1))
        "control: a region child's carrier still goes stale when its state exits")))

;; ---- teardown --------------------------------------------------------------

(deftest flat-root-child-ends-at-finality-after-root-exit
  (let [log (atom [])]
    (reg-kid! :rff/kid log)
    (rf/reg-machine :rff/p (flat-parent :rff/kid log {}))
    (rf/dispatch-sync [:rff/p [:rf.machine/start]])
    (rf/dispatch-sync [:rff/p [:go]])
    (is (nil? (snapshot :rff/p)) "the parent finished")
    (is (nil? (snapshot :rff/kid#1)) "its root child ended with it")
    (is (= [[:parent :root-exit] [:rff/kid :exit]] @log)
        "the root :exit reads a live child; the child's :exit runs after it")
    (is (nil? (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned :rff/p])))))

(deftest flat-root-child-ends-on-destroy-after-root-exit
  (let [log (atom [])]
    (reg-kid! :rfx/kid log)
    (rf/reg-machine :rfx/p (flat-parent :rfx/kid log {}))
    (rf/dispatch-sync [:rfx/p [:rf.machine/start]])
    (rf.machines.test-support/reset-captured!)
    (kill! :rfx/p)
    (is (nil? (snapshot :rfx/p)))
    (is (nil? (snapshot :rfx/kid#1)))
    (is (= [[:parent :root-exit] [:rfx/kid :exit]] @log))
    (let [kid (->> (rf.machines.test-support/events-of :rf.machine/destroyed)
                   (map :tags)
                   (filter #(= :rfx/kid#1 (:actor-id %)))
                   first)]
      (is (= :rfx/p (:parent-id kid)))
      (is (= [] (:invoke-id kid))))))

(deftest parallel-root-child-ends-at-finality-after-root-exit
  (let [log (atom [])]
    (reg-kid! :rpf/kid log)
    (rf/reg-machine :rpf/p (parallel-parent :rpf/kid log {}))
    (rf/dispatch-sync [:rpf/p [:rf.machine/start]])
    (rf/dispatch-sync [:rpf/p [:fin]])
    (is (nil? (snapshot :rpf/p)) "all regions final with no :on-done — the machine finishes")
    (is (nil? (snapshot :rpf/kid#1)))
    (is (= [[:parent :root-exit] [:rpf/kid :exit]] @log))))

(deftest parallel-root-child-ends-on-destroy-after-root-exit
  (let [log (atom [])]
    (reg-kid! :rpx/kid log)
    (rf/reg-machine :rpx/p (parallel-parent :rpx/kid log {}))
    (rf/dispatch-sync [:rpx/p [:rf.machine/start]])
    (kill! :rpx/p)
    (is (nil? (snapshot :rpx/p)))
    (is (nil? (snapshot :rpx/kid#1)))
    (is (= [[:parent :root-exit] [:rpx/kid :exit]] @log))))

;; ---- registration ----------------------------------------------------------

(defn- refusal-id
  "The `:rf.error/id` `validate-machine!` refuses `machine` with, or nil."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(def ^:private flat {:initial :a :states {:a {} :b {}}})
(def ^:private par  {:type :parallel :regions {:x {:initial :x1 :states {:x1 {} :x2 {}}}}})

(deftest root-spawn-registers-and-is-held-to-the-spawn-grammar
  (testing "a well-formed root :spawn registers, flat and parallel"
    (is (nil? (refusal-id (assoc flat :spawn {:machine-id :k :on-done :b :on-error [:b]}))))
    (is (nil? (refusal-id (assoc par :spawn {:machine-id :k :on-error {:target [:x :x2]}})))))
  (testing "the spawn-spec grammar a state's :spawn is held to"
    (is (= :rf.error/machine-spawn-bad-shape (refusal-id (assoc flat :spawn {}))))
    (is (= :rf.error/machine-spawn-bad-shape (refusal-id (assoc flat :spawn [{:machine-id :k}]))))
    (is (= :rf.error/machine-unknown-spawn-key (refusal-id (assoc flat :spawn {:machine-id :k :bogus 1}))))
    (is (= :rf.error/machine-bad-on-error-clause (refusal-id (assoc flat :spawn {:machine-id :k :on-error 42}))))
    (is (= :rf.error/machine-bad-on-done-clause (refusal-id (assoc par :spawn {:machine-id :k :on-done 42})))))
  (testing "a flat root's targets name top-level states"
    (is (= :rf.error/machine-unresolved-target
           (refusal-id (assoc flat :spawn {:machine-id :k :on-error :nowhere}))))
    (is (= :rf.error/machine-unresolved-target
           (refusal-id (assoc flat :spawn {:machine-id :k :on-done {:target [:nowhere]}})))))
  (testing "a parallel root's targets are region-qualified"
    (is (= :rf.error/machine-parallel-root-on-bad-target
           (refusal-id (assoc par :spawn {:machine-id :k :on-error :x2}))))
    (is (= :rf.error/machine-parallel-root-on-bad-target
           (refusal-id (assoc par :spawn {:machine-id :k :on-done {:target [:nowhere :x2]}})))))
  (testing "guard and action refs resolve"
    (is (= :rf.error/machine-unresolved-guard
           (refusal-id (assoc flat :spawn {:machine-id :k :on-error {:target :b :guard :nope?}}))))
    (is (= :rf.error/machine-unresolved-action
           (refusal-id (assoc par :spawn {:machine-id :k :on-done {:action :nope}}))))))
