(ns re-frame.root-spawn-test
  "A machine root's `:spawn` is a child that lives as long as the machine, on
  flat and parallel roots alike (Spec 005 §The machine root)."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timeout :as rf.machines.timeout]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- slot [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- reg-kid!
  "A child resting in `:w`. `:ping` counts; `:ok <v>` finishes it with result
  `v`; `:fail <v>` finishes it on an `:error?` leaf with payload `v`. Its `:w`
  `:exit` appends `[id :exit]` to `log`."
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

(defn- start!
  "Register `kid` and a `shape` parent (`flat-parent` / `parallel-parent`) at
  `p`, start it, and return the exit log they share."
  [shape p kid extra]
  (let [log (atom [])]
    (reg-kid! kid log)
    (rf/reg-machine p (shape kid log extra))
    (rf/dispatch-sync [p [:rf.machine/start]])
    log))

(defn- fold-got [{:keys [data result]}]
  (assoc data :got result))

(defn- record-error [{d :data ev :event}]
  {:data (assoc d :err (nth ev 2))})

;; ---- birth -----------------------------------------------------------------

(deftest flat-root-spawns-its-child-at-birth
  (start! flat-parent :rsf/p :rsf/kid {})
  (is (= [:rsf/kid#1 :rsf/kid#1 []]
         [(slot :rsf/p [])
          (get-in (snapshot :rsf/p) [:data :rf/spawned []])
          (get-in (snapshot :rsf/kid#1) [:data :rf/invoke-id])])))

(deftest parallel-root-spawns-its-child-at-birth
  (start! parallel-parent :rsp/p :rsp/kid {})
  (is (= [:rsp/kid#1 :rsp/kid#1 true]
         [(slot :rsp/p [])
          (get-in (snapshot :rsp/p) [:data :rf/spawned []])
          (some? (snapshot :rsp/kid#1))])))

(deftest root-spawn-follows-root-entry-and-precedes-the-descent
  (reg-kid! :rso/kid (atom []))
  (rf/reg-machine :rso/p
    {:initial :a
     :data    {:trail []}
     :entry   (fn [{d :data}] {:data (update d :trail conj :root)})
     :spawn   {:machine-id :rso/kid
               :data       (fn [{:keys [snapshot]}] {:seen (get-in snapshot [:data :trail])})}
     :states  {:a {:entry (fn [{d :data}] {:data (update d :trail conj :a)})}}})
  (rf/dispatch-sync [:rso/p [:rf.machine/start]])
  (is (= [[:root] [:root :a]]
         [(get-in (snapshot :rso/kid#1) [:data :seen])
          (get-in (snapshot :rso/p) [:data :trail])])))

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
    ;; Control: the state-level child at `:a` ended on exit and respawned on re-entry.
    (is (= [1 :rss/kid#1 nil true [[:rss/leaf-kid :exit]]]
           [(get-in (snapshot :rss/kid#1) [:data :pings])
            (slot :rss/p [])
            (snapshot :rss/leaf-kid#1)
            (some? (snapshot :rss/leaf-kid#2))
            @log]))))

(deftest parallel-root-child-survives-transitions
  (start! parallel-parent :rpt/p :rpt/kid {})
  (rf/dispatch-sync [:rpt/kid#1 [:ping]])
  (rf/dispatch-sync [:rpt/p [:hop]])
  (rf/dispatch-sync [:rpt/p [:back]])
  (is (= 1 (get-in (snapshot :rpt/kid#1) [:data :pings]))))

;; ---- completion and failure ------------------------------------------------

(deftest flat-root-on-done-folds-the-result
  (start! flat-parent :rfd/p :rfd/kid {:spawn {:machine-id :rfd/kid :on-done fold-got}})
  (rf/dispatch-sync [:rfd/kid#1 [:ok 42]])
  (is (= [nil 42 nil nil]
         [(snapshot :rfd/kid#1)
          (get-in (snapshot :rfd/p) [:data :got])
          (slot :rfd/p [])
          (get-in (snapshot :rfd/p) [:data :rf/spawned])])))

(deftest flat-root-transition-on-done-targets-a-top-level-state
  (start! flat-parent :rft/p :rft/kid {:spawn {:machine-id :rft/kid :on-done :b}})
  (rf/dispatch-sync [:rft/kid#1 [:ok 1]])
  (is (= :b (:state (snapshot :rft/p)))))

(deftest flat-root-on-error-takes-its-transition
  (start! flat-parent :rfe/p :rfe/kid
          {:actions {:record record-error}
           :spawn   {:machine-id :rfe/kid :on-error {:target :b :action :record}}})
  (rf/dispatch-sync [:rfe/kid#1 [:fail :boom]])
  (let [p (snapshot :rfe/p)]
    (is (= [:b :boom] [(:state p) (get-in p [:data :err])]))))

(deftest parallel-root-on-done-folds-the-result
  (start! parallel-parent :rpd/p :rpd/kid {:spawn {:machine-id :rpd/kid :on-done fold-got}})
  (rf/dispatch-sync [:rpd/kid#1 [:ok 42]])
  (is (= [nil 42] [(snapshot :rpd/kid#1) (get-in (snapshot :rpd/p) [:data :got])])))

(deftest parallel-root-transition-on-done-moves-a-region
  (start! parallel-parent :rpm/p :rpm/kid {:spawn {:machine-id :rpm/kid :on-done {:target [:x :x2]}}})
  (rf/dispatch-sync [:rpm/kid#1 [:ok 1]])
  (is (= {:x :x2 :y :y1} (:state (snapshot :rpm/p)))))

(deftest parallel-root-on-error-takes-its-transition
  (start! parallel-parent :rpe/p :rpe/kid
          {:actions {:record record-error}
           :spawn   {:machine-id :rpe/kid :on-error {:target [:x :x2] :action :record}}})
  (rf/dispatch-sync [:rpe/kid#1 [:fail :boom]])
  (let [p (snapshot :rpe/p)]
    (is (= [{:x :x2 :y :y1} :boom] [(:state p) (get-in p [:data :err])]))))

(deftest a-root-carrier-from-a-superseded-attempt-is-dropped
  (are [reason snap invoke-id] (= reason (rf.machines.transition/spawn-carrier-stale-reason snap invoke-id 1))
    :rf.machine.spawn/attempt-superseded {:state {:x :x1 :y :y1} :rf/spawn-attempts {[] 2}}      []
    ;; control: a region child's carrier still goes stale when its state exits
    :rf.machine.spawn/state-exited       {:state {:x :x1 :y :y1} :rf/spawn-attempts {[:x :x2] 1}} [:x :x2]))

;; ---- teardown --------------------------------------------------------------

(deftest flat-root-child-ends-at-finality-after-root-exit
  (let [log (start! flat-parent :rff/p :rff/kid {})]
    (rf/dispatch-sync [:rff/p [:go]])
    (is (= [nil nil nil [[:parent :root-exit] [:rff/kid :exit]]]
           [(snapshot :rff/p)
            (snapshot :rff/kid#1)
            (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned :rff/p])
            @log])
        "the root :exit reads a live child; the child's :exit runs after it")))

(deftest flat-root-child-ends-on-destroy-after-root-exit
  (let [log (start! flat-parent :rfx/p :rfx/kid {})]
    (rf/reg-event ::kill (fn [_ _] {:fx [[:rf.machine/destroy :rfx/p]]}))
    (rf/dispatch-sync [::kill])
    (is (= [nil nil [[:parent :root-exit] [:rfx/kid :exit]] {:parent-id :rfx/p :invoke-id []}]
           [(snapshot :rfx/p)
            (snapshot :rfx/kid#1)
            @log
            (->> (rf.machines.test-support/events-of :rf.machine/destroyed)
                 (map :tags)
                 (filter #(= :rfx/kid#1 (:actor-id %)))
                 first
                 (#(select-keys % [:parent-id :invoke-id])))]))))

;; ---- registration ----------------------------------------------------------

(defn- refusal-id
  "The `:rf.error/id` `validate-machine!` refuses `machine` with, or nil."
  [machine]
  (try (rf.machines/validate-machine! machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(def ^:private flat {:initial :a :states {:a {} :b {}}})
(def ^:private par  {:type :parallel :regions {:x {:initial :x1 :states {:x1 {} :x2 {}}}}})

(deftest root-spawn-is-held-to-the-spawn-grammar
  (are [id m] (= id (refusal-id m))
    :rf.error/machine-spawn-bad-shape             (assoc flat :spawn {})
    :rf.error/machine-unknown-spawn-key           (assoc flat :spawn {:machine-id :k :bogus 1})
    :rf.error/machine-bad-on-error-clause         (assoc flat :spawn {:machine-id :k :on-error 42})
    :rf.error/machine-bad-on-done-clause          (assoc par :spawn {:machine-id :k :on-done 42})
    ;; a flat root's targets name top-level states
    :rf.error/machine-unresolved-target           (assoc flat :spawn {:machine-id :k :on-error :nowhere})
    :rf.error/machine-unresolved-target           (assoc flat :spawn {:machine-id :k :on-done {:target [:nowhere]}})
    ;; a parallel root's targets are region-qualified
    :rf.error/machine-parallel-root-on-bad-target (assoc par :spawn {:machine-id :k :on-error :x2})
    :rf.error/machine-parallel-root-on-bad-target (assoc par :spawn {:machine-id :k :on-done {:target [:nowhere :x2]}})
    :rf.error/machine-unresolved-guard            (assoc flat :spawn {:machine-id :k :on-error {:target :b :guard :nope?}})
    :rf.error/machine-unresolved-action           (assoc par :spawn {:machine-id :k :on-done {:action :nope}})))

(deftest root-spawn-timeout-lowers-onto-the-root-after
  (let [m   (assoc par :spawn {:machine-id :k :timeout 1000 :on-timeout {:target [:x :x2]}})
        out (rf.machines.timeout/desugar-timeouts m)]
    (is (= [{:machine-id :k} {1000 {:target [:x :x2]}} nil]
           [(:spawn out) (:after out) (refusal-id m)])))
  (is (= :rf.error/machine-non-parallel-root-after-not-supported
         (refusal-id (assoc flat :spawn {:machine-id :k :timeout 1000 :on-timeout :b})))))
