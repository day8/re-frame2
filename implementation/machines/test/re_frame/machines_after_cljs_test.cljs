(ns re-frame.machines-after-cljs-test
  "CLJS-side coverage for `:after` delayed transitions under the Reagent
  reactive substrate.

  Mirrors the conformance fixtures
  ../spec/conformance/fixtures/after-single-delay.edn (epoch + scheduled
  trace) and after-stale-detection.edn (real event beats timer; epoch
  mismatch).

  Concerns covered:
    - `:after` schedules with current epoch on entry; fires on synthetic
      timer event with matching epoch; epoch advances on entry.
    - Stale detection: real event beats timer; stale firing must
      not transition; `:rf.machine.timer/stale-after` trace emitted.
    - Multi-stage `:after` with guard suppression (sibling continues when
      one entry is guard-suppressed).
    - Subscription-vector dynamic delay (`:delay-source :sub` + `:rf.sub/id` + `:rf.sub/query-v`).

  Prefer dispatch-sync of the synthetic `:rf.machine.timer/after-elapsed`
  event over wall-clock setTimeout waits, so the test is deterministic under
  Node."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path. Inline trace
;; captures below keep their raw trace.tooling register/unregister.
(def ^:private snapshot mtest/snapshot)

(deftest machine-after-cljs
  (testing ":after schedules with current epoch on entry; fires on synthetic timer event"
    (let [machine
          {:initial :idle
           :data    {}
           :states
           {:idle    {:on {:fetch :loading}}
            :loading {:after {5000 :timeout}
                      :on    {:loaded :ready}}
            :timeout {}
            :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :http/flow machine)
      (trace-tooling/register-listener! ::after (fn [ev] (swap! traces conj ev)))
      ;; Step 1 — enter :loading; timer schedules at epoch 1.
      (rf/dispatch-sync [:http/flow [:fetch]])
      (let [s (snapshot :http/flow)]
        (is (= :loading (:state s)))
        ;; Per Spec 005 §Hierarchy interaction the epoch is per-decl-path.
        (is (= 1 (get-in s [:data :rf/after-epoch [:loading]]))
            "epoch advanced on entry to an :after-bearing state"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 5000     (:delay (:tags ev)))
                       (= 1        (:epoch (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled trace with :delay-source :literal")
      ;; Step 2 — fire the synthetic timer-elapsed event with matching epoch.
      (reset! traces [])
      (rf/dispatch-sync [:http/flow [:rf.machine.timer/after-elapsed 5000 1 [:loading]]])
      (let [s (snapshot :http/flow)]
        (is (= :timeout (:state s))
            "matching-epoch timer firing transitioned :loading → :timeout")
        (is (= 2 (get-in s [:data :rf/after-epoch [:loading]]))
            "the :loading node's per-path epoch advanced on the timer-driven exit"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/fired (:operation ev))
                       (true? (:fired? (:tags ev)))
                       (= 1    (:epoch  (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/fired trace with matching epoch")
      (trace-tooling/unregister-listener! ::after)))

  (testing ":after stale detection — real event beats timer; stale firing must not transition"
    (let [machine
          {:initial :idle
           :data    {}
           :states
           {:idle    {:on {:fetch :loading}}
            :loading {:after {5000 :timeout}
                      :on    {:loaded :ready}}
            :timeout {}
            :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :http2/flow machine)
      ;; Enter :loading — epoch advances to 1.
      (rf/dispatch-sync [:http2/flow [:fetch]])
      (is (= :loading (:state (snapshot :http2/flow))))
      (is (= 1 (get-in (snapshot :http2/flow) [:data :rf/after-epoch [:loading]])))
      ;; Real :loaded event arrives BEFORE the timer would fire.
      ;; Snapshot moves to :ready; the :loading node's per-path epoch
      ;; advances to 2; the in-flight timer (carrying epoch 1) is now stale.
      (rf/dispatch-sync [:http2/flow [:loaded]])
      (is (= :ready (:state (snapshot :http2/flow))))
      (is (= 2 (get-in (snapshot :http2/flow) [:data :rf/after-epoch [:loading]])))
      ;; Now the stale timer fires. Per Spec 005 §Epoch-based stale
      ;; detection: (a) the stale firing MUST NOT cause a transition, and
      ;; (b) the runtime emits :rf.machine.timer/stale-after as the
      ;; canonical signal so observers can distinguish "suppressed stale
      ;; firing" from "no firing at all".
      (trace-tooling/register-listener! ::stale (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:http2/flow [:rf.machine.timer/after-elapsed 5000 1 [:loading]]])
      (trace-tooling/unregister-listener! ::stale)
      (is (= :ready (:state (snapshot :http2/flow)))
          "stale timer must not fire its transition")
      (is (= 2 (get-in (snapshot :http2/flow) [:data :rf/after-epoch [:loading]]))
          "stale firing does not bump epoch")
      ;; The stale-after trace must emit even though the current state
      ;; (:ready) no longer carries an :after table.
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/stale-after (:operation ev))
                       (= 5000 (:delay (:tags ev)))
                       (= 1    (:scheduled-epoch (:tags ev)))
                       (= 2    (:current-epoch (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/stale-after trace on the stale firing")
      ;; Negative assertion: no machine-transition trace shows a state-change
      ;; from :loading on the stale firing.
      (is (not-any? (fn [ev]
                      (let [tags (:tags ev)
                            before-state (get-in tags [:before :state])
                            after-state  (get-in tags [:after :state])]
                        (and (= :rf.machine/transition (:operation ev))
                             (= :loading before-state)
                             (not= before-state after-state))))
                    @traces)
          "no real transition fired on the stale firing"))))

;; ---- :after multi-stage + guard suppression ------------------------------

(deftest machine-after-multi-stage-guard-cljs
  (testing "multiple :after entries; guard-false suppresses one, sibling continues"
    (let [m {:initial :idle
             :data    {:slow? false}
             :guards  {:slow? (fn [{data :data}] (:slow? data))}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {5000  {:guard :slow? :target :warn}
                                30000 :timeout}
                        :on    {:loaded :ready}}
              :warn    {}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/multi-cljs m)
      (trace-tooling/register-listener! ::mg (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/multi-cljs [:fetch]])
      (let [epoch (get-in (snapshot :a/multi-cljs) [:data :rf/after-epoch [:loading]])]
        ;; The 5s timer fires first; guard :slow? false → suppressed.
        (rf/dispatch-sync [:a/multi-cljs [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
        (is (= :loading (:state (snapshot :a/multi-cljs)))
            "guard-suppressed :after must not transition")
        (is (some (fn [ev]
                    (and (= :rf.machine.timer/fired (:operation ev))
                         (false? (:fired? (:tags ev)))))
                  @traces)
            ":fired? false trace emitted on guard suppression")
        ;; Sibling 30s still live (same epoch) — fire it, transition fires.
        (rf/dispatch-sync [:a/multi-cljs [:rf.machine.timer/after-elapsed 30000 epoch [:loading]]])
        (is (= :timeout (:state (snapshot :a/multi-cljs)))
            "sibling timer transitions on its own")
        (trace-tooling/unregister-listener! ::mg)))))

;; ---- guarded candidate-vector :after -------------------------------------
;;
;; Per Spec 005 §Delayed :after transitions §Transition spec: the :after
;; value admits the SAME guarded candidate-vector form as an :on clause —
;; [{:guard g :target s} {:target s2 :action a}] — first-guard-pass-wins.
;; This is the CLJS / reactive-substrate counterpart to the pure-engine sweep
;; + JVM integration tests.

(deftest machine-after-guarded-vector-cljs
  (testing "guarded candidate-vector :after under the reactive substrate —
            first guard passes → first target"
    (let [m {:initial :idle
             :data    {:handshake-ok? true}
             :guards  {:handshake-ok? (fn [{data :data}] (:handshake-ok? data))}
             :states
             {:idle           {:on {:go :authenticating}}
              :authenticating {:after {6000 [{:guard :handshake-ok? :target :connected}
                                             {:target :failed :action :record-error}]}}
              :connected      {}
              :failed         {}}
             :actions {:record-error (fn [{data :data}]
                                       {:data (assoc data :error :handshake)})}}]
      (rf/reg-machine :a/gv-pass-cljs m)
      (rf/dispatch-sync [:a/gv-pass-cljs [:go]])
      (is (= :authenticating (:state (snapshot :a/gv-pass-cljs))))
      (let [epoch (get-in (snapshot :a/gv-pass-cljs) [:data :rf/after-epoch [:authenticating]])]
        (rf/dispatch-sync [:a/gv-pass-cljs [:rf.machine.timer/after-elapsed 6000 epoch [:authenticating]]])
        (is (= :connected (:state (snapshot :a/gv-pass-cljs)))
            "first candidate's guard passes → :connected (NOT stranded)"))))

  (testing "guarded candidate-vector :after under the reactive substrate —
            first guard fails → unguarded fallback target + action"
    (let [m {:initial :idle
             :data    {:handshake-ok? false}
             :guards  {:handshake-ok? (fn [{data :data}] (:handshake-ok? data))}
             :states
             {:idle           {:on {:go :authenticating}}
              :authenticating {:after {6000 [{:guard :handshake-ok? :target :connected}
                                             {:target :failed :action :record-error}]}}
              :connected      {}
              :failed         {}}
             :actions {:record-error (fn [{data :data}]
                                       {:data (assoc data :error :handshake)})}}]
      (rf/reg-machine :a/gv-fallback-cljs m)
      (rf/dispatch-sync [:a/gv-fallback-cljs [:go]])
      (let [epoch (get-in (snapshot :a/gv-fallback-cljs) [:data :rf/after-epoch [:authenticating]])]
        (rf/dispatch-sync [:a/gv-fallback-cljs [:rf.machine.timer/after-elapsed 6000 epoch [:authenticating]]])
        (is (= :failed (:state (snapshot :a/gv-fallback-cljs)))
            "first guard fails → unguarded fallback :failed fires")
        (is (= :handshake (get-in (snapshot :a/gv-fallback-cljs) [:data :error]))
            "the fallback candidate's :action ran")))))

;; ---- subscription-vector :after delay (dynamic) --------------------------

(deftest machine-after-subscription-delay-cljs
  (testing "subscription-vector delay: :scheduled trace carries :delay-source :sub + :rf.sub/id + :rf.sub/query-v"
    (rf/reg-event
      :a/sub-config-set
      (fn [{:keys [db]} [_ ms]] {:db (assoc db :timeout-config ms)}))
    (rf/reg-sub
      :a/timeout-config
      (fn [db _] (:timeout-config db)))
    (rf/dispatch-sync [:a/sub-config-set 4000])
    (let [m {:initial :idle
             :data    {}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {[:a/timeout-config] :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/sub-cljs m)
      (trace-tooling/register-listener! ::sub (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/sub-cljs [:fetch]])
      (is (= :loading (:state (snapshot :a/sub-cljs))))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= :sub                (:delay-source (:tags ev)))
                       (= :a/timeout-config   (:rf.sub/id (:tags ev)))
                       (= [:a/timeout-config] (:rf.sub/query-v (:tags ev)))))
                @traces)
          ":scheduled trace emitted with :delay-source :sub + canonical :rf.sub/id + :rf.sub/query-v (rf2-1b6uh5)")
      (trace-tooling/unregister-listener! ::sub))))
