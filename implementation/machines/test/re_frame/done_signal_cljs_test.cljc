(ns re-frame.done-signal-cljs-test
  "Per rf2-bnjb3 (parallel onDone) + rf2-zlmz7 (compound onDone). Verifies the
  TRANSITIONABLE done-state / `:on-done` completion signal at the LIVE handler
  boundary (not just the pure engine — the SCXML-conformance corpus covers the
  pure surface). XState v5 `onDone` / SCXML §3.7 `done.state.<id>`.

  The headline correctness property: a `:final?` leaf EMBEDDED inside a
  compound (or a region's compound) signals `done.state.<compound>` — an
  in-machine completion event an enclosing `:on-done` / `:on` takes IN THE
  SAME MACROSTEP — WITHOUT tearing the machine down. The D7 reconciliation:
  a TOP-LEVEL `:final?` leaf (direct child of the machine root) STILL
  auto-destroys (the actor-done case); an embedded final signals-not-destroys.

  The former substitute (`:raise` from the final leaf's `:entry`, which
  collided with `:final?`-auto-destroys — the rf2-zlmz7 footgun) is gone: you
  mark the sub-flow's terminal leaf `:final?` and declare `:on-done` on the
  enclosing compound, and the machine keeps running.

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
   [re-frame.trace.tooling :as trace-tooling]
   [re-frame.registrar :as registrar]
   [re-frame.test-support :as test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

(defn- snapshot [machine-id]
  (get-in (rf/app-db-value :rf/default) [:rf/runtime :machines :snapshots machine-id]))

(defn- record-traces! [k]
  (let [a (atom [])]
    (trace-tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- traces-for [traces operation]
  (filter #(= operation (:operation %)) @traces))

;; ===========================================================================
;; rf2-zlmz7 — COMPOUND onDone (embedded :final? signals, does NOT destroy)
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
;; rf2-bnjb3 — PARALLEL onDone (all-regions-final fires root :on-done)
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
      (rf/reg-event-db :rf2-bnjb3/coordinator
        (fn [db _] (reset! continued true) db))
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
