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
    - Stale detection (rf2-7urp): real event beats timer; stale firing must
      not transition; `:rf.machine.timer/stale-after` trace emitted.
    - Multi-stage `:after` with guard suppression (sibling continues when
      one entry is guard-suppressed).
    - Subscription-vector dynamic delay (`:delay-source :sub` + `:sub-id`).

  Per the bead: prefer dispatch-sync of the synthetic
  `:rf.machine.timer/after-elapsed` event over wall-clock setTimeout waits,
  so the test is deterministic under Node.

  Split out of `machines_cljs_test.cljs` (rf2-3vps4)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(deftest machine-after-cljs
  (testing ":after schedules with current epoch on entry; fires on synthetic timer event"
    (let [machine
          {:initial :idle
           :data    {:rf/after-epoch 0}
           :states
           {:idle    {:on {:fetch :loading}}
            :loading {:after {5000 :timeout}
                      :on    {:loaded :ready}}
            :timeout {}
            :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :http/flow machine)
      (rf/register-trace-cb! ::after (fn [ev] (swap! traces conj ev)))
      ;; Step 1 — enter :loading; timer schedules at epoch 1.
      (rf/dispatch-sync [:http/flow [:fetch]])
      (let [s (snapshot :http/flow)]
        (is (= :loading (:state s)))
        (is (= 1 (get-in s [:data :rf/after-epoch]))
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
      (rf/dispatch-sync [:http/flow [:rf.machine.timer/after-elapsed 5000 1]])
      (let [s (snapshot :http/flow)]
        (is (= :timeout (:state s))
            "matching-epoch timer firing transitioned :loading → :timeout")
        (is (= 2 (get-in s [:data :rf/after-epoch]))
            "epoch advanced again on the timer-driven transition"))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/fired (:operation ev))
                       (true? (:fired? (:tags ev)))
                       (= 1    (:epoch  (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/fired trace with matching epoch")
      (rf/remove-trace-cb! ::after)))

  (testing ":after stale detection — real event beats timer; stale firing must not transition"
    (let [machine
          {:initial :idle
           :data    {:rf/after-epoch 0}
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
      (is (= 1 (get-in (snapshot :http2/flow) [:data :rf/after-epoch])))
      ;; Real :loaded event arrives BEFORE the timer would fire.
      ;; Snapshot moves to :ready; epoch advances to 2; the in-flight timer
      ;; (carrying epoch 1) is now stale.
      (rf/dispatch-sync [:http2/flow [:loaded]])
      (is (= :ready (:state (snapshot :http2/flow))))
      (is (= 2 (get-in (snapshot :http2/flow) [:data :rf/after-epoch])))
      ;; Now the stale timer fires. Per Spec 005 §Epoch-based stale
      ;; detection: (a) the stale firing MUST NOT cause a transition, and
      ;; (b) the runtime emits :rf.machine.timer/stale-after as the
      ;; canonical signal so observers can distinguish "suppressed stale
      ;; firing" from "no firing at all" (rf2-7urp).
      (rf/register-trace-cb! ::stale (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:http2/flow [:rf.machine.timer/after-elapsed 5000 1]])
      (rf/remove-trace-cb! ::stale)
      (is (= :ready (:state (snapshot :http2/flow)))
          "stale timer must not fire its transition")
      (is (= 2 (get-in (snapshot :http2/flow) [:data :rf/after-epoch]))
          "stale firing does not bump epoch")
      ;; Per rf2-7urp: the stale-after trace must emit even though the
      ;; current state (:ready) no longer carries an :after table.
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
             :data    {:rf/after-epoch 0 :slow? false}
             :guards  {:slow? (fn [data _] (:slow? data))}
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
      (rf/register-trace-cb! ::mg (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/multi-cljs [:fetch]])
      (let [epoch (get-in (snapshot :a/multi-cljs) [:data :rf/after-epoch])]
        ;; The 5s timer fires first; guard :slow? false → suppressed.
        (rf/dispatch-sync [:a/multi-cljs [:rf.machine.timer/after-elapsed 5000 epoch]])
        (is (= :loading (:state (snapshot :a/multi-cljs)))
            "guard-suppressed :after must not transition")
        (is (some (fn [ev]
                    (and (= :rf.machine.timer/fired (:operation ev))
                         (false? (:fired? (:tags ev)))))
                  @traces)
            ":fired? false trace emitted on guard suppression")
        ;; Sibling 30s still live (same epoch) — fire it, transition fires.
        (rf/dispatch-sync [:a/multi-cljs [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :timeout (:state (snapshot :a/multi-cljs)))
            "sibling timer transitions on its own")
        (rf/remove-trace-cb! ::mg)))))

;; ---- subscription-vector :after delay (dynamic) --------------------------

(deftest machine-after-subscription-delay-cljs
  (testing "subscription-vector delay: :scheduled trace carries :delay-source :sub + :sub-id"
    (rf/reg-event-db
      :a/sub-config-set
      (fn [db [_ ms]] (assoc db :timeout-config ms)))
    (rf/reg-sub
      :a/timeout-config
      (fn [db _] (:timeout-config db)))
    (rf/dispatch-sync [:a/sub-config-set 4000])
    (let [m {:initial :idle
             :data    {:rf/after-epoch 0}
             :states
             {:idle    {:on {:fetch :loading}}
              :loading {:after {[:a/timeout-config] :timeout}
                        :on    {:loaded :ready}}
              :timeout {}
              :ready   {}}}
          traces (atom [])]
      (rf/reg-machine :a/sub-cljs m)
      (rf/register-trace-cb! ::sub (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:a/sub-cljs [:fetch]])
      (is (= :loading (:state (snapshot :a/sub-cljs))))
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= :sub               (:delay-source (:tags ev)))
                       (= :a/timeout-config  (:sub-id (:tags ev)))))
                @traces)
          ":scheduled trace emitted with :delay-source :sub and :sub-id")
      (rf/remove-trace-cb! ::sub))))
