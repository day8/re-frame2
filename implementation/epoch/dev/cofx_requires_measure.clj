(ns cofx-requires-measure
  "Measure the per-(state x event) ensure-set design against one machine-level
  :rf.cofx/requires, under :live and :strict, through birth, guards, raises
  and completion. Run once per design — `entry` at this branch's base commit,
  `machine` at its tip — from implementation/epoch:

    clojure -Sdeps '{:aliases {:meas {:extra-paths [\"dev\"] :main-opts [\"-m\" \"cofx-requires-measure\"]}}}' -M:test:meas machine"
  (:require [clojure.pprint :as pp]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.machines]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def design (atom :entry))
(def gen-calls (atom 0))
(def sub-calls (atom 0))
(def traces (atom []))

(defn attach
  "Declare `decls` ({[slot id] requires}) the way the design under test reads them."
  [m decls]
  (case @design
    :entry   (reduce-kv (fn [m [slot id] reqs]
                          (update-in m [slot id] (fn [f] {:rf.cofx/requires reqs :fn f})))
                        m decls)
    :machine (assoc m :rf.cofx/requires (vec (distinct (mapcat val decls))))))

(defn reg-facts! []
  (reset! gen-calls 0)
  (reset! sub-calls 0)
  (rf/reg-cofx :meas/gen {:recordable? true} (fn [] (swap! gen-calls inc)))
  (rf/reg-sub :meas/cfg (fn [db _] (swap! sub-calls inc) (get db :cfg 250))))

(defn rt [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn snap [id] (get-in (rt) [:rf.runtime/machines :snapshots id]))
(defn child-of [parent invoke-id] (get-in (rt) [:rf.runtime/machines :spawned parent invoke-id]))
(defn records [] (rf.epoch/epoch-history :rf/default))
(defn record-for [ev] (some #(when (= ev (:trigger-event %)) %) (reverse (records))))
(defn errors [id] (count (filter #(= id (:operation %)) @traces)))
(defn last-epoch-id [] (:epoch-id (last (records))))

(def fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.epoch/clear-history!) (rf.epoch/clear-epoch-listeners!))}))

(defn in-fixture [f]
  (let [r (atom nil)]
    (fixture (fn []
               (reset! traces [])
               (rf.trace.tooling/register-listener! ::meas (fn [ev] (swap! traces conj ev)))
               (try (reset! r (f))
                    (finally (rf.trace.tooling/unregister-listener! ::meas)))))
    @r))

(def stamp-gen
  (fn [{:keys [data] cofx :rf.cofx}] {:data (assoc data :g (:meas/gen cofx))}))

;; ---- A: over-minting under :live -------------------------------------------

(defn scenario-a []
  (reg-facts!)
  (rf/reg-machine :meas/a
    (attach {:initial :idle
             :data    {:n 0}
             :actions {:count (fn [{:keys [data]}] {:data (update data :n inc)})
                       :stamp (fn [{:keys [data] cofx :rf.cofx}]
                                {:data (assoc data :g (:meas/gen cofx) :c (:meas/cfg-v cofx))})}
             :states  {:idle {:on {:tick {:action :count}
                                   :go   {:target :done :action :stamp}}}
                       :done {}}}
            {[:actions :stamp] [:meas/gen {:rf/sub [:meas/cfg] :as :meas/cfg-v}]}))
  (rf/dispatch-sync [:meas/a [:rf.machine/start]])
  (dotimes [_ 9] (rf/dispatch-sync [:meas/a [:tick]]))
  (rf/dispatch-sync [:meas/a [:go]])
  (let [recs (filter #(= :meas/a (first (:trigger-event %))) (records))]
    {:events-to-machine     11
     :generator-calls       @gen-calls
     :sub-evaluations       @sub-calls
     :records               (count recs)
     :records-carrying-gen  (count (filter #(contains? (:rf.cofx %) :meas/gen) recs))
     :records-carrying-sub  (count (filter #(contains? (:rf.cofx %) :meas/cfg-v) recs))
     :token-chars-total     (reduce + (map #(count (pr-str (:rf.cofx %))) recs))
     :final                 (select-keys (snap :meas/a) [:state :data])}))

;; ---- B: one recorded event replayed under :strict --------------------------

(defn replay-strict
  "Record `ev` live (after `setup!`), rewind to before it, replay it under
  :strict from its recorded token. Report live vs replayed snapshot of `mid`."
  [setup! ev mid]
  (setup!)
  (let [before (last-epoch-id)
        _      (rf/dispatch-sync ev {:rf.cofx {:rf/time-ms 1}})
        live   (select-keys (snap mid) [:state :data])
        rec    (record-for ev)
        token  (:rf.cofx rec)
        calls  @gen-calls
        _      (rf.epoch/restore-epoch! :rf/default before)
        _      (reset! traces [])
        _      (rf/dispatch-sync ev {:rf.cofx token :rf.cofx/mint-policy :strict})
        replay (select-keys (snap mid) [:state :data])]
    {:live live :replay replay :same? (= live replay)
     :live-generator-calls calls :replay-generator-calls (- @gen-calls calls)
     :recorded-token token
     :missing-required (errors :rf.error/missing-required-cofx)}))

(defn scenario-b []
  {:birth
   (in-fixture
     (fn []
       (replay-strict
         (fn []
           (reg-facts!)
           (rf/reg-machine :meas/birth
             (attach {:initial :booting :data {}
                      :actions {:stamp-birth stamp-gen}
                      :states  {:booting {:entry :stamp-birth}}}
                     {[:actions :stamp-birth] [:meas/gen]})))
         [:meas/birth [:rf.machine/start]] :meas/birth)))
   :guard
   (in-fixture
     (fn []
       (replay-strict
         (fn []
           (reg-facts!)
           (rf/reg-machine :meas/guard
             (attach {:initial :idle :data {}
                      :guards  {:odd? (fn [{cofx :rf.cofx}] (odd? (:meas/gen cofx)))}
                      :states  {:idle {:on {:go [{:target :odd :guard :odd?} {:target :even}]}}
                                :odd {} :even {}}}
                     {[:guards :odd?] [:meas/gen]}))
           (rf/dispatch-sync [:meas/guard [:rf.machine/start]]))
         [:meas/guard [:go]] :meas/guard)))
   :raise
   (in-fixture
     (fn []
       (replay-strict
         (fn []
           (reg-facts!)
           (rf/reg-machine :meas/raise
             (attach {:initial :a :data {}
                      :guards  {:odd? (fn [{cofx :rf.cofx}] (odd? (:meas/gen cofx)))}
                      :actions {:raise-inner (fn [_] {:fx [[:raise [:inner]]]})}
                      :states  {:a    {:on {:go {:target :b :action :raise-inner}}}
                                :b    {:on {:inner [{:target :odd :guard :odd?} {:target :even}]}}
                                :odd  {} :even {}}}
                     {[:guards :odd?] [:meas/gen]}))
           (rf/dispatch-sync [:meas/raise [:rf.machine/start]]))
         [:meas/raise [:go]] :meas/raise)))})

(defn scenario-b-sub []
  (in-fixture
    (fn []
      (replay-strict
        (fn []
          (reg-facts!)
          (rf/reg-machine :meas/sub
            (attach {:initial :idle :data {}
                     :actions {:sample (fn [{:keys [data] cofx :rf.cofx}] {:data (assoc data :c (:meas/cfg-v cofx))})}
                     :states  {:idle {:on {:go {:target :done :action :sample}}} :done {}}}
                    {[:actions :sample] [{:rf/sub [:meas/cfg] :as :meas/cfg-v}]}))
          (rf/dispatch-sync [:meas/sub [:rf.machine/start]]))
        [:meas/sub [:go]] :meas/sub))))

;; Replay-strict above needs the child id before building the event; resolve lazily.
(defn scenario-b-completion []
  (in-fixture
    (fn []
      (reg-facts!)
      (rf/reg-machine :meas/b-child
        (attach {:initial :running :data {}
                 :actions {:stamp stamp-gen}
                 :states  {:running {:on {:go {:target :done :action :stamp}}}
                           :done    {:final? true :output-key :g}}}
                {[:actions :stamp] [:meas/gen]}))
      (rf/reg-machine :meas/b-parent
        {:initial :idle :data {}
         :actions {:store (fn [{:keys [data] ev :event}] {:data (assoc data :g (:result (nth ev 2)))})}
         :states  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :meas/b-child
                                     :on-done    {:target :loaded :action :store}}}
                   :loaded  {}}})
      (rf/dispatch-sync [:meas/b-parent [:start]])
      (let [child (child-of :meas/b-parent [:working])]
        (replay-strict (fn []) [child [:go]] :meas/b-parent)))))

;; ---- C: :strict through completion, the parent declaring an unrelated fact --

(defn scenario-c []
  (in-fixture
    (fn []
      (reg-facts!)
      (rf/reg-machine :meas/c-child
        {:initial :running :data {}
         :states  {:running {:on {:go :done}}
                   :done    {:final? true}}})
      (rf/reg-machine :meas/c-parent
        (attach {:initial :idle :data {}
                 :actions {:refresh stamp-gen}
                 :states  {:idle    {:on {:start :working}}
                           :working {:spawn {:machine-id :meas/c-child
                                             :on-done    {:target :loaded}}
                                     :on    {:refresh {:action :refresh}}}
                           :loaded  {}}}
                {[:actions :refresh] [:meas/gen]}))
      (rf/dispatch-sync [:meas/c-parent [:start]])
      (let [child (child-of :meas/c-parent [:working])]
        (replay-strict (fn []) [child [:go]] :meas/c-parent)))))

;; ---- D: :strict birth of a spawned child declaring a later-used fact --------

(defn scenario-d []
  (in-fixture
    (fn []
      (reg-facts!)
      (rf/reg-machine :meas/d-child
        (attach {:initial :running :data {}
                 :actions {:later stamp-gen}
                 :states  {:running {:on {:finish {:target :done :action :later}}}
                           :done    {}}}
                {[:actions :later] [:meas/gen]}))
      (rf/reg-machine :meas/d-parent
        {:initial :idle :data {}
         :states  {:idle    {:on {:go :working}}
                   :working {:spawn {:machine-id :meas/d-child}}}})
      (rf/dispatch-sync [:meas/d-parent [:go]] {:rf.cofx {:rf/time-ms 1} :rf.cofx/mint-policy :strict})
      (let [child (child-of :meas/d-parent [:working])]
        {:parent-state     (:state (snap :meas/d-parent))
         :child            child
         :child-booted?    (and (some? child) (not (get-in (snap child) [:rf/runtime :bootstrap-pending?])))
         :child-snapshot   (select-keys (snap child) [:state :rf/runtime])
         :generator-calls  @gen-calls
         :missing-required (errors :rf.error/missing-required-cofx)}))))

;; ---- E: a :strict (`:test` preset) timer, the machine declaring an unrelated fact

(defn scenario-e []
  (in-fixture
    (fn []
      (reg-facts!)
      (rf/reg-machine :meas/e
        (attach {:initial :idle :data {}
                 :actions {:stamp stamp-gen}
                 :states  {:idle      {:on {:go :waiting :stamp-it {:action :stamp}}}
                           :waiting   {:after {100 :timed-out}}
                           :timed-out {}}}
                {[:actions :stamp] [:meas/gen]}))
      ;; the test supplies the fact on the event it dispatches itself
      (rf/dispatch-sync [:meas/e [:go]] {:rf.cofx {:rf/time-ms 1 :meas/gen 5} :rf.cofx/mint-policy :strict})
      (let [epoch (get-in (snap :meas/e) [:rf/runtime :after-epoch [:waiting]])]
        ;; the timer event arrives with a fresh token, as dispatch-later sends it
        (rf/dispatch-sync [:meas/e [:rf.machine.timer/after-elapsed 100 epoch [:waiting]]]
                          {:rf.cofx/mint-policy :strict}))
      {:state            (:state (snap :meas/e))
       :generator-calls  @gen-calls
       :missing-required (errors :rf.error/missing-required-cofx)})))

(defn -main [& [d]]
  (reset! design (keyword (or d "entry")))
  (let [b (assoc (scenario-b) :completion (scenario-b-completion) :sub-source (scenario-b-sub))
        result {:design @design
                :A-live-over-minting (in-fixture scenario-a)
                :B-strict-replay     (update-vals b #(dissoc % :recorded-token))
                :B-recorded-tokens   (update-vals b :recorded-token)
                :C-strict-completion-unrelated-parent-fact (in-fixture scenario-c)
                :D-strict-spawned-birth (in-fixture scenario-d)
                :E-strict-timer         (in-fixture scenario-e)}]
    (pp/pprint result)
    (shutdown-agents)
    (System/exit 0)))
