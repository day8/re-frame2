(ns re-frame.done-signal-cljs-test
  "Parallel onDone + compound onDone. Verifies the
  TRANSITIONABLE done-state / `:on-done` completion signal at the LIVE handler
  boundary (not just the pure engine — the SCXML-conformance corpus covers the
  pure surface). XState v5 `onDone` / SCXML §3.7 `done.state.<id>`.

  The headline correctness property: a `:final?` leaf EMBEDDED inside a
  compound (or a region's compound) signals `done.state.<compound>` — an
  in-machine completion event an enclosing `:on-done` / `:on` takes IN THE
  SAME MACROSTEP — WITHOUT tearing the machine down. The D7 reconciliation:
  a TOP-LEVEL `:final?` leaf (direct child of the machine root) STILL
  auto-destroys (the actor-done case); an embedded final signals-not-destroys.

  To advance a completed sub-flow you mark the sub-flow's terminal leaf
  `:final?` and declare `:on-done` on the enclosing compound, and the machine
  keeps running.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and shadow-cljs
  (CLJS) discover it — the engine + lifecycle are identical across runtimes."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; requiring `re-frame.machines` wires the machines artefact into the
   ;; late-bind registry so `rf/reg-machine` resolves (mirrors
   ;; `final_state_cljs_test`).
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.registrar :as registrar]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- record-traces! [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- traces-for [traces operation]
  (filter #(= operation (:operation %)) @traces))

;; ===========================================================================
;; COMPOUND onDone (embedded :final? signals, does NOT destroy)
;; ===========================================================================

(deftest compound-done-advances-and-machine-survives
  (testing "an embedded compound reaching its :final? child fires the
            compound's :on-done IN-MACHINE (advancing the outer flow) and the
            machine SURVIVES — no auto-destroy"
    (rf/reg-machine :rf2-zlmz7/flow
      {:initial :flow
       :data    {}
       :states
       {:flow {:initial :step
               :on-done :next        ;; sibling of :flow
               :states  {:step       {:on {:finish :inner-done}}
                         :inner-done {:final? true}}}
        :next {}}})
    (rf/dispatch-sync [:rf2-zlmz7/flow [:finish]])
    (is (= :next (:state (snapshot :rf2-zlmz7/flow)))
        "the compound :flow reached its final child; :on-done advanced to :next")
    (is (some? (snapshot :rf2-zlmz7/flow))
        "the machine snapshot is INTACT — embedded final did NOT auto-destroy")
    (is (some? (registrar/lookup :event :rf2-zlmz7/flow))
        "the handler is still registered — the machine keeps running")))

(deftest compound-done-no-on-done-rests-without-destroy
  (testing "an embedded :final? leaf with NO enclosing :on-done is a benign
            done no-op — the machine RESTS in the final config rather than
            auto-destroying (the rf2-zlmz7 footgun is gone)"
    (let [traces (record-traces! ::no-handler)]
      (rf/reg-machine :rf2-zlmz7/rest
        {:initial :flow
         :data    {}
         :states
         {:flow {:initial :step
                 :states  {:step       {:on {:finish :inner-done}}
                           :inner-done {:final? true}}}}})
      (rf/dispatch-sync [:rf2-zlmz7/rest [:finish]])
      (is (= [:flow :inner-done] (:state (snapshot :rf2-zlmz7/rest)))
          "machine RESTS at the embedded final leaf — no teardown")
      (is (some? (registrar/lookup :event :rf2-zlmz7/rest))
          "handler still live — the embedded final did not auto-destroy")
      (is (empty? (traces-for traces :rf.machine/done))
          "no whole-machine :rf.machine/done fired (this is an in-machine
           signal, not actor finality)"))))

(deftest compound-done-runs-on-done-action
  (testing "the compound :on-done transition's :action runs as it advances"
    (rf/reg-machine :rf2-zlmz7/act
      {:initial :flow
       :data    {:hits 0}
       :actions {:bump (fn [{d :data}] {:data (update d :hits inc)})}
       :states
       {:flow {:initial :step
               :on-done {:target :next :action :bump}
               :states  {:step       {:on {:finish :inner-done}}
                         :inner-done {:final? true}}}
        :next {}}})
    (rf/dispatch-sync [:rf2-zlmz7/act [:finish]])
    (is (= :next (:state (snapshot :rf2-zlmz7/act))))
    (is (= 1 (get-in (snapshot :rf2-zlmz7/act) [:data :hits]))
        ":on-done action ran once in the same macrostep")))

;; ===========================================================================
;; D7 reconciliation — TOP-LEVEL :final? STILL auto-destroys (regression)
;; ===========================================================================

(deftest top-level-final-still-auto-destroys
  (testing "D7 regression: a TOP-LEVEL :final? leaf (direct child of the root)
            STILL auto-destroys — the embedded-vs-top-level split preserves the
            actor-done case"
    (let [traces (record-traces! ::top-level)]
      (rf/reg-machine :rf2-bnjb3/top
        {:initial :running
         :states  {:running {:on {:end :done}}
                   :done    {:final? true}}})
      (rf/dispatch-sync [:rf2-bnjb3/top [:end]])
      (is (nil? (snapshot :rf2-bnjb3/top))
          "top-level final auto-destroyed (snapshot cleared)")
      (is (nil? (registrar/lookup :event :rf2-bnjb3/top))
          "handler unregistered on top-level final")
      (is (= 1 (count (traces-for traces :rf.machine/done)))
          "the whole-machine :rf.machine/done fired (actor finality)"))))

;; ===========================================================================
;; PARALLEL onDone (all-regions-final fires root :on-done)
;; ===========================================================================

(deftest parallel-done-fires-root-on-done-and-survives
  (testing "all regions reach :final? → the parallel ROOT's :on-done fires
            (action) and the machine SURVIVES — the 'do these in parallel,
            then continue' pattern"
    (rf/reg-machine :rf2-bnjb3/par
      {:type    :parallel
       :data    {:completions 0}
       :actions {:complete (fn [{d :data}] {:data (update d :completions inc)})}
       ;; onDone on the parallel ROOT — action-only (no in-machine target).
       :on-done {:action :complete}
       :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                 :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
    (rf/dispatch-sync [:rf2-bnjb3/par [:fin]])
    (is (= {:left :done :right :done} (:state (snapshot :rf2-bnjb3/par)))
        "both regions reached :final?")
    (is (= 1 (get-in (snapshot :rf2-bnjb3/par) [:data :completions]))
        "the parallel root :on-done action fired once on all-regions-final")
    (is (some? (registrar/lookup :event :rf2-bnjb3/par))
        "the machine SURVIVES (no auto-destroy) — :on-done is the
         transitionable signal, distinct from actor teardown")))

(deftest parallel-done-action-emits-continue-fx
  (testing "the parallel root :on-done's action :fx (a dispatch to a
            coordinator) runs — the 'then continue' continuation"
    (let [continued (atom false)]
      (rf/reg-event :rf2-bnjb3/coordinator
        (fn [{:keys [db]} _] (reset! continued true) {:db db}))
      (rf/reg-machine :rf2-bnjb3/par-fx
        {:type    :parallel
         :data    {}
         :actions {:announce (fn [{d :data}]
                               {:data d
                                :fx   [[:dispatch [:rf2-bnjb3/coordinator]]]})}
         :on-done {:action :announce}
         :regions {:a {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                   :b {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
      (rf/dispatch-sync [:rf2-bnjb3/par-fx [:fin]])
      (is (true? @continued)
          "the parallel :on-done action's :fx dispatched the coordinator event"))))

(deftest parallel-on-done-fires-once-not-on-every-resting-macrostep
  (testing "(h3wca.1) the parallel root :on-done fires EXACTLY ONCE — on
            ENTERING the all-regions-final config — and does NOT re-fire on a
            later event delivered while the machine rests all-final (XState v5
            `onDone` / SCXML `done.state.<id>` fire once on entry; re-frame2
            must not re-run the :action / re-emit the :fx every macrostep)"
    (let [continued (atom 0)]
      (rf/reg-event :rf2-h3wca/coordinator
        (fn [{:keys [db]} _] (swap! continued inc) {:db db}))
      (rf/reg-machine :rf2-h3wca/once
        {:type    :parallel
         :data    {:completions 0}
         :actions {:complete (fn [{d :data}]
                               {:data (update d :completions inc)
                                :fx   [[:dispatch [:rf2-h3wca/coordinator]]]})}
         :on-done {:action :complete}
         ;; Both regions reach :final? on a single :fin. :done is a final
         ;; LEAF with no `:on`, so any later event the regions all decline
         ;; leaves the machine resting all-final.
         :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                   :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
      ;; Macrostep 1: ENTER the done config — :on-done fires once.
      (rf/dispatch-sync [:rf2-h3wca/once [:fin]])
      (is (= {:left :done :right :done} (:state (snapshot :rf2-h3wca/once)))
          "both regions final after :fin")
      (is (= 1 (get-in (snapshot :rf2-h3wca/once) [:data :completions]))
          ":on-done action ran once on entering the done config")
      (is (= 1 @continued) ":on-done :fx dispatched the coordinator once")
      ;; Macrosteps 2-4: deliver events the regions DECLINE while resting
      ;; all-final. The done config is NOT newly reached → :on-done must NOT
      ;; re-fire (no :data drift, no re-dispatch).
      (rf/dispatch-sync [:rf2-h3wca/once [:noop]])
      (rf/dispatch-sync [:rf2-h3wca/once [:fin]])   ; declined (already :done)
      (rf/dispatch-sync [:rf2-h3wca/once [:another-noop]])
      (is (= 1 (get-in (snapshot :rf2-h3wca/once) [:data :completions]))
          ":completions did NOT drift — :on-done did not re-fire on resting macrosteps")
      (is (= 1 @continued)
          "the coordinator was NOT re-dispatched on later resting events")
      (is (some? (registrar/lookup :event :rf2-h3wca/once))
          "machine still alive (no-op events don't tear it down)"))))

(deftest parallel-no-on-done-still-auto-destroys
  (testing "D7 regression: a parallel machine with NO root :on-done reaching
            all-regions-final STILL auto-destroys (the actor-done default)"
    (rf/reg-machine :rf2-bnjb3/par-destroy
      {:type    :parallel
       :regions {:a {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                 :b {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
    (rf/dispatch-sync [:rf2-bnjb3/par-destroy [:fin]])
    (is (nil? (snapshot :rf2-bnjb3/par-destroy))
        "no :on-done ⇒ all-regions-final auto-destroys (snapshot cleared)")
    (is (nil? (registrar/lookup :event :rf2-bnjb3/par-destroy))
        "handler unregistered")))

(deftest parallel-one-region-pending-no-on-done-no-destroy
  (testing "negative: with one region still non-final, the parallel :on-done
            does NOT fire and the machine is NOT destroyed"
    (rf/reg-machine :rf2-bnjb3/par-partial
      {:type    :parallel
       :data    {:completions 0}
       :actions {:complete (fn [{d :data}] {:data (update d :completions inc)})}
       :on-done {:action :complete}
       :regions {:a {:initial :run :states {:run {:on {:fin-a :done}} :done {:final? true}}}
                 :b {:initial :run :states {:run {:on {:fin-b :done}} :done {:final? true}}}}})
    (rf/dispatch-sync [:rf2-bnjb3/par-partial [:fin-a]])
    (is (= {:a :done :b :run} (:state (snapshot :rf2-bnjb3/par-partial)))
        "only :a reached final")
    (is (= 0 (get-in (snapshot :rf2-bnjb3/par-partial) [:data :completions]))
        ":on-done did NOT fire (not all-regions-final)")
    (is (some? (registrar/lookup :event :rf2-bnjb3/par-partial))
        "machine alive — :b still pending")))

;; ===========================================================================
;; parallel-root :on-done honours the :db hard-disallow + phase
;; ===========================================================================

(deftest parallel-on-done-action-returning-db-emits-error-and-drops-db
  (testing "rf2-z522n: a parallel-root :on-done action that wrongly returns
            :db emits :rf.error/machine-action-wrote-db (the same uniform
            hard-disallow every other phase enforces) and DROPS the :db key —
            its :data still flows. Previously the parallel-root path bypassed
            the validation and silently ignored the :db write."
    (let [traces (record-traces! ::db-disallow)]
      (rf/reg-machine :rf2-z522n/par-on-done-db
        {:type    :parallel
         :data    {:completions 0}
         ;; :on-done action wrongly returns :db AND a legit :data write.
         :actions {:complete (fn [{d :data}]
                               {:db   {:hacked true}
                                :data (update d :completions inc)})}
         :on-done {:action :complete}
         :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                   :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
      (rf/dispatch-sync [:rf2-z522n/par-on-done-db [:fin]])
      (let [errs (traces-for traces :rf.error/machine-action-wrote-db)]
        (is (= 1 (count errs))
            "exactly one wrote-db hard-disallow error from the parallel :on-done action")
        (is (= :complete (-> errs first :tags :action-id))
            "the diagnostic names the offending :on-done action")
        ;; `:offending-value` (the whole app-db the :on-done action
        ;; wrongly returned) is summarized to `:rf/redacted` at the trace egress
        ;; chokepoint so it never leaks raw; `:action-id` still locates it.
        (is (= :rf/redacted (-> errs first :tags :offending-value))
            "the offending app-db value is redacted at egress (rf2-x9haxl)"))
      ;; The :data write STILL flowed through (the :db key was stripped, not
      ;; the whole effects map).
      (is (= 1 (get-in (snapshot :rf2-z522n/par-on-done-db) [:data :completions]))
          ":on-done :data write flowed through; only :db was dropped")
      ;; The snapshot was NOT clobbered with the offending :db key.
      (is (not (contains? (snapshot :rf2-z522n/par-on-done-db) :db))
          "no :db key leaked onto the snapshot"))))

(deftest parallel-on-done-action-ran-stamps-transition-phase
  (testing "rf2-z522n: the parallel-root :on-done action's :rf.machine/action-ran
            trace carries phase :transition — consistent with an embedded
            compound :on-done (which runs through apply-transition-once) and
            within the documented closed MachineActionRanTags phase enum (NO
            undocumented :on-done phase)."
    (let [traces (record-traces! ::phase)]
      (rf/reg-machine :rf2-z522n/par-on-done-phase
        {:type    :parallel
         :data    {:completions 0}
         :actions {:complete (fn [{d :data}] {:data (update d :completions inc)})}
         :on-done {:action :complete}
         :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                   :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
      (rf/dispatch-sync [:rf2-z522n/par-on-done-phase [:fin]])
      (let [runs (->> (traces-for traces :rf.machine/action-ran)
                      (filter #(= :complete (-> % :tags :action-id))))]
        (is (= 1 (count runs))
            "the parallel :on-done action ran exactly once")
        (is (= :transition (-> runs first :tags :phase))
            "phase is :transition — the documented phase, not an undocumented :on-done")
        (is (contains? #{:exit :transition :entry :always
                         :after-action :initial-entry :destroy-exit}
                       (-> runs first :tags :phase))
            "phase is a member of the closed MachineActionRanTags enum")))))

;; ===========================================================================
;; Composed — compound-inside-parallel-region done propagation
;; ===========================================================================

(deftest compound-region-done-advances-region-and-machine-survives
  (testing "a COMPOUND region reaching its final child fires the region's
            :on-done (advancing that region) while siblings continue and the
            whole machine survives"
    (rf/reg-machine :rf2-bnjb3/par-compound
      {:type    :parallel
       :data    {}
       :regions
       {:work   {:initial :flow
                 :states {:flow {:initial :step
                                 :on-done :work-done
                                 :states {:step       {:on {:finish :inner-done}}
                                          :inner-done {:final? true}}}
                          :work-done {}}}
        :status {:initial :idle :states {:idle {}}}}})
    (rf/dispatch-sync [:rf2-bnjb3/par-compound [:finish]])
    (is (= {:work :work-done :status :idle}
           (:state (snapshot :rf2-bnjb3/par-compound)))
        ":work's compound advanced via its :on-done; :status untouched")
    (is (some? (registrar/lookup :event :rf2-bnjb3/par-compound))
        "the parallel machine survives — only one region's compound is done")))

;; ===========================================================================
;; registration-time validation
;; ===========================================================================

(deftest parallel-on-done-target-rejected
  (testing "a parallel root's :on-done declaring an in-machine :target is
            rejected at registration (root-only parallel has no flat sibling)"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-on-done-target"
          (rf/reg-machine :rf2-bnjb3/bad-target
            {:type    :parallel
             :on-done {:target :somewhere}
             :regions {:a {:initial :run :states {:run {} }}}})))))

;; EVERY target-bearing :on-done value-form is rejected LOUDLY at registration
;; (not just the map form). A bare-keyword target and a vector-path target must
;; not slip past validation: were one to reach runtime it would SILENTLY STALL —
;; apply-on-done-action would normalise it to a target-only / action-less
;; candidate, run no action, mark the parallel done-signal handled (suppressing
;; auto-destroy), and move nowhere. Registration rejects them up front.
(deftest parallel-on-done-bare-keyword-target-rejected
  (testing "rf2-6srk5: a parallel root's :on-done declaring a BARE-KEYWORD
            target (:on-done :next) is rejected at registration — it would
            otherwise silently stall in the all-final config"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-on-done-target"
          (rf/reg-machine :rf2-6srk5/bad-kw-target
            {:type    :parallel
             :on-done :next
             :regions {:a {:initial :run :states {:run {}}}}})))))

(deftest parallel-on-done-vector-path-target-rejected
  (testing "rf2-6srk5: a parallel root's :on-done declaring a VECTOR-PATH
            target (:on-done [:next]) is rejected at registration"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-on-done-target"
          (rf/reg-machine :rf2-6srk5/bad-vec-target
            {:type    :parallel
             :on-done [:next]
             :regions {:a {:initial :run :states {:run {}}}}})))))

(deftest parallel-on-done-candidate-vector-target-rejected
  (testing "rf2-6srk5: a parallel root's :on-done CANDIDATE VECTOR containing
            a target-bearing map is rejected at registration"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-parallel-on-done-target"
          (rf/reg-machine :rf2-6srk5/bad-cand-vec-target
            {:type    :parallel
             :on-done [{:guard :g :target :next} {:action :a}]
             :guards  {:g (constantly true)}
             :actions {:a (fn [{d :data}] {:data d})}
             :regions {:a {:initial :run :states {:run {}}}}})))))

(deftest parallel-on-done-action-fx-only-accepted
  (testing "rf2-6srk5: an :action / :fx-only parallel root :on-done (NO
            :target) stays ACCEPTED at registration and fires exactly once"
    (let [ran (atom 0)]
      (rf/reg-machine :rf2-6srk5/ok-action-only
        {:type    :parallel
         :data    {:n 0}
         :actions {:bump (fn [{d :data}] (swap! ran inc) {:data (update d :n inc)})}
         :on-done {:action :bump}
         :regions {:left  {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}
                   :right {:initial :run :states {:run {:on {:fin :done}} :done {:final? true}}}}})
      (rf/dispatch-sync [:rf2-6srk5/ok-action-only [:fin]])
      (is (= {:left :done :right :done} (:state (snapshot :rf2-6srk5/ok-action-only)))
          "both regions reached final — action-only :on-done was accepted")
      (is (= 1 @ran) "the action-only :on-done fired exactly once"))))

(deftest compound-on-done-unresolved-action-rejected
  (testing "a compound :on-done referencing an unregistered action keyword is
            rejected at registration (fail-fast ref resolution)"
    (is (thrown-with-msg?
          #?(:clj Exception :cljs js/Error)
          #":rf.error/machine-unresolved-action"
          (rf/reg-machine :rf2-zlmz7/bad-ref
            {:initial :flow
             :states  {:flow {:initial :step
                              :on-done {:target :next :action :nope}
                              :states  {:step       {:on {:finish :inner-done}}
                                        :inner-done {:final? true}}}
                       :next {}}})))))
