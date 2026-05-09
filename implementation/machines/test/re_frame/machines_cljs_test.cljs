(ns re-frame.machines-cljs-test
  "CLJS-side coverage for hierarchical / always / after / invoke state-machine
  features under the Reagent reactive substrate. Per rf2-pc82.

  These tests mirror the conformance fixtures in
  ../spec/conformance/fixtures/{hierarchical-*,always-*,
  after-*,invoke-*}.edn but exercise the runtime through reg-machine /
  dispatch-sync — the same surface real apps use. The flat-machine
  case is already covered by `machine-transition-cljs` in
  runtime_cljs_test.cljs; this file completes the matrix on CLJS."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; Snapshot/restore the registrar around each test (rf2-am9d). We do NOT
;; call (registrar/clear-all!): CLJS has no runtime (require :reload), so
;; wiping the registrar would permanently lose routing's framework events
;; and machines.cljc's :rf/machine sub, which were registered at ns-load
;; time. The reset-runtime-fixture also resets the machines spawn-counter
;; and clears trace listeners, both of which these tests need.
(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

(defn- seed-snapshot!
  "Force the snapshot for `machine-id` to a known value via an event-db.
  Used to *reposition* a machine to a non-initial state mid-test (e.g. to
  exercise a different leaf without rebuilding the whole machine).
  The first-dispatch path no longer needs this: `create-machine-handler`
  cascades the declared `:initial` to a leaf path via `initial-cascade`,
  so a fresh `(rf/reg-machine ...)` followed by a real event dispatches
  against the correct compound leaf — see rf2-m1tv."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (namespace machine-id) "-" (name machine-id)))]
    (rf/reg-event-db seed-id
      (fn [db _] (assoc-in db [:rf/machines machine-id] snap)))
    (rf/dispatch-sync [seed-id])))

;; ---- (0) initial-cascade on first dispatch (rf2-m1tv) ---------------------
;; Per Spec 005 §Initial-state cascading: when a machine is first
;; instantiated and its declared :initial lands on a compound state, the
;; runtime descends the :initial chain to a leaf path. Without this, the
;; first event resolves against the wrong state-node level.

(deftest machine-initial-cascade-on-first-dispatch
  (testing "compound :initial chain descends to a leaf on first-dispatch snapshot synthesis (rf2-m1tv)"
    (let [machine
          {:initial :foo
           :data    {}
           :states
           {:foo {:initial :bar
                  :states
                  {:bar {:on {:go :baz}}
                   :baz {}}}}}]
      (rf/reg-machine :rf2-m1tv/flow machine)
      ;; First event: the runtime synthesises the initial snapshot, which
      ;; MUST cascade :foo → :bar to a leaf path. The :go transition is
      ;; declared on :bar, so resolving against the synthesised initial
      ;; snapshot only fires if :state is [:foo :bar].
      (rf/dispatch-sync [:rf2-m1tv/flow [:go]])
      (is (= [:foo :baz] (:state (snapshot :rf2-m1tv/flow)))
          "first-dispatch initial-cascade landed at the leaf and the :go transition fired")))

  (testing "deeper compound :initial chain (three levels) cascades to leaf"
    (let [machine
          {:initial :a
           :data    {}
           :states
           {:a {:initial :b
                :states
                {:b {:initial :c
                     :states
                     {:c {:on {:next :d}}
                      :d {}}}}}}}]
      (rf/reg-machine :rf2-m1tv/deep machine)
      (rf/dispatch-sync [:rf2-m1tv/deep [:next]])
      (is (= [:a :b :d] (:state (snapshot :rf2-m1tv/deep)))
          ":initial chain a→b→c cascaded to leaf; :next transition resolved at :c"))))

;; ---- (1) hierarchical -----------------------------------------------------
;; Mirrors hierarchical-compound-transition.edn (sibling-leaf transition,
;; LCA cascade) and hierarchical-parent-fallthrough.edn (deepest-wins).

(deftest machine-hierarchical-cljs
  (testing "compound state — sibling-leaf transition fires only the leaf exit/entry"
    ;; Tracks which entry/exit hooks fired so we can assert the LCA cascade.
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:enter-auth (tag :enter-auth)
                     :exit-auth  (tag :exit-auth)
                     :enter-dash (tag :enter-dash)
                     :exit-dash  (tag :exit-dash)
                     :enter-set  (tag :enter-set)
                     :exit-set   (tag :exit-set)}
           :states
           {:authenticated
            {:initial :dashboard
             :entry   :enter-auth
             :exit    :exit-auth
             :states
             {:dashboard {:entry :enter-dash
                          :exit  :exit-dash
                          :on    {:open-settings :settings}}
              :settings  {:entry :enter-set
                          :exit  :exit-set
                          :on    {:close :dashboard}}}}}}]
      (rf/reg-machine :auth/flow machine)
      ;; The runtime cascades the declared :initial through compound
      ;; :initial chains on first-snapshot synthesis (rf2-m1tv), so the
      ;; first event dispatches against the deepest leaf without us
      ;; having to seed the snapshot manually.
      (reset! log [])
      ;; Sibling-leaf transition. LCA is :authenticated; only the leaf
      ;; exit/entry hooks fire — the parent's :enter-auth must NOT re-fire.
      (rf/dispatch-sync [:auth/flow [:open-settings]])
      (is (= [:authenticated :settings] (:state (snapshot :auth/flow)))
          "snapshot moved to the sibling leaf")
      (is (= [:exit-dash :enter-set] @log)
          "only leaf-level exit/entry fired — LCA parent did NOT re-enter")))

  (testing "deepest-wins — leaf overrides parent for same event id"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-help    (tag :parent)
                     :leaf-help      (tag :leaf)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-help}}      ;; internal — no :target
             :states
             {:dashboard {}                                ;; no :help handler
              :settings  {:on {:help {:action :leaf-help}}}}}}}]
      (rf/reg-machine :auth2/flow machine)
      ;; Initial cascade lands at [:authenticated :dashboard] without seeding.
      ;; Case 1: leaf has no :help — parent fallthrough.
      (reset! log [])
      (rf/dispatch-sync [:auth2/flow [:help]])
      (is (= [:authenticated :dashboard] (:state (snapshot :auth2/flow)))
          "internal transition — snapshot unchanged")
      (is (= [:parent] @log) "parent's :help action fired (fallthrough)")
      ;; Reposition to the :settings leaf for case 2.
      (seed-snapshot! :auth2/flow {:state [:authenticated :settings] :data {}})
      ;; Case 2: leaf handles :help — leaf wins, parent SHADOWED.
      (reset! log [])
      (rf/dispatch-sync [:auth2/flow [:help]])
      (is (= [:authenticated :settings] (:state (snapshot :auth2/flow)))
          "internal transition — snapshot unchanged")
      (is (= [:leaf] @log) "leaf's :help action fired; parent shadowed"))))

;; ---- (2) :always microsteps -----------------------------------------------
;; Mirrors always-single-microstep.edn (single guarded fire, atomic commit)
;; and always-depth-exceeded.edn (cycle hits the bounded depth limit).

(deftest machine-always-cljs
  (testing ":always fires once after the resolving event under a true guard"
    (let [machine
          {:initial :asking
           :data    {:correct-count 9}
           :guards  {:enough? (fn [data _]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [data _]
                                {:data {:correct-count
                                        (inc (:correct-count data))}})}
           :states
           {:asking {:always [{:guard :enough? :target :winner}]
                     :on     {:answer-correct {:action :count}}}
            :winner {}
            :loser  {}}}]
      (rf/reg-machine :quiz/flow machine)
      ;; Initial state :asking with :data {:correct-count 9} synthesised
      ;; on first dispatch — no seed needed.
      ;; Pre-condition: count is 9; one :answer-correct ticks to 10; :always
      ;; guard becomes true; microstep transitions to :winner.
      (rf/dispatch-sync [:quiz/flow [:answer-correct]])
      (let [s (snapshot :quiz/flow)]
        (is (= :winner (:state s))
            "external observer sees only the macrostep — landed at :winner")
        (is (= 10 (get-in s [:data :correct-count]))
            "the action's data update is committed alongside the :always target"))))

  (testing ":always doesn't fire when the guard is false"
    (let [machine
          {:initial :asking
           :data    {:correct-count 5}
           :guards  {:enough? (fn [data _]
                                (>= (:correct-count data) 10))}
           :actions {:count   (fn [data _]
                                {:data {:correct-count
                                        (inc (:correct-count data))}})}
           :states
           {:asking {:always [{:guard :enough? :target :winner}]
                     :on     {:answer-correct {:action :count}}}
            :winner {}}}]
      (rf/reg-machine :quiz2/flow machine)
      (rf/dispatch-sync [:quiz2/flow [:answer-correct]])
      (let [s (snapshot :quiz2/flow)]
        (is (= :asking (:state s))
            "guard false — microstep loop terminates with zero microsteps")
        (is (= 6 (get-in s [:data :correct-count]))
            "action ran; data updated; state unchanged"))))

  (testing ":always cycle hits depth limit — snapshot rolls back atomically"
    ;; Two states ping-pong via :always with always-true guards. The
    ;; microstep loop trips the depth limit; per Spec 005 §Bounded depth
    ;; the snapshot rolls back to the input.
    (let [machine
          {:initial :start
           :data    {}
           :always-depth-limit 5
           :guards  {:p? (fn [_ _] true)}
           :states
           {:start {:on {:go {:target :a}}}
            :a     {:always [{:guard :p? :target :b}]}
            :b     {:always [{:guard :p? :target :a}]}}}
          traces (atom [])]
      (rf/reg-machine :osc/flow machine)
      (rf/register-trace-cb! ::osc (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:osc/flow [:go]])
      (rf/remove-trace-cb! ::osc)
      ;; Atomic rollback: external snapshot stays at :start.
      (is (= :start (:state (snapshot :osc/flow)))
          "macrostep is atomic; cycle aborts; snapshot rolls back to input")
      (is (some (fn [ev]
                  (and (= :rf.error/machine-always-depth-exceeded
                          (:operation ev))
                       (= :error (:op-type ev))
                       (= :no-recovery (:recovery ev))))
                @traces)
          "expected :rf.error/machine-always-depth-exceeded trace"))))

;; ---- (3) :after delayed transitions ---------------------------------------
;; Mirrors after-single-delay.edn (epoch + scheduled trace) and
;; after-stale-detection.edn (real event beats timer; epoch mismatch).
;;
;; Per the bead: prefer dispatch-sync of the synthetic
;; :rf.machine.timer/after-elapsed event over wall-clock setTimeout waits,
;; so the test is deterministic under Node.

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
                       (= 5000 (:delay (:tags ev)))
                       (= 1    (:epoch (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled trace with epoch 1")
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

;; ---- (4) declarative :invoke ----------------------------------------------
;; Mirrors invoke-spawn-on-entry-destroy-on-exit.edn — entering a state with
;; :invoke emits a :spawn fx (observable as :rf.machine/spawned trace);
;; exiting emits :destroy-machine (observable as :rf.machine/destroyed trace).
;;
;; The on-spawn callback fires inline during apply-transition-once so the
;; child id can be recorded into the parent machine's :data — we assert via
;; the snapshot's :pending key.

(deftest machine-invoke-cljs
  (testing ":invoke spawns child on entry and destroys it on exit"
    (let [machine
          {:initial :idle
           :data    {:credentials {:user "alice" :pass "secret"}}
           :on-spawn-actions
           ;; Per Spec 005 §Declarative :invoke (rf2-een2 / rf2-smba):
           ;; on-spawn callback signature is (fn [data spawned-id] new-data).
           ;; The runtime patches the returned data back into the snapshot.
           {:auth/record-actor (fn [data actor-id]
                                 (assoc data :pending actor-id))}
           :states
           {:idle
            {:on {:submit :authenticating}}

            :authenticating
            {:invoke {:machine-id :http/post
                      :data       {:url "/api/login"
                                   :body {:user "alice" :pass "secret"}}
                      :on-spawn   :auth/record-actor
                      :start      [:begin]}
             :on    {:auth/succeeded :authenticated
                     :auth/failed    :idle}}

            :authenticated {}}}
          traces (atom [])]
      (rf/reg-machine :auth3/flow machine)
      ;; Initial state :idle with the credentials fixture data is
      ;; synthesised on first dispatch; no seed required.
      ;; Entering :authenticating: :spawn fx fires (→ :rf.machine/spawned
      ;; trace), :on-spawn callback records the deterministic actor id
      ;; into :data.:pending.
      (rf/register-trace-cb! ::inv (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:auth3/flow [:submit]])
      (let [s (snapshot :auth3/flow)]
        (is (= :authenticating (:state s)))
        (is (= :http/post#1 (get-in s [:data :pending]))
            "on-spawn callback recorded the deterministic actor id"))
      (is (some (fn [ev]
                  (and (= :rf.machine/spawned (:operation ev))
                       (= :http/post (:machine-id (:tags ev)))))
                @traces)
          "expected :rf.machine/spawned trace from the :spawn fx")
      ;; Exiting :authenticating via :auth/failed: :destroy-machine fx fires
      ;; targeting the recorded actor id.
      (reset! traces [])
      (rf/dispatch-sync [:auth3/flow [:auth/failed]])
      (rf/remove-trace-cb! ::inv)
      (is (= :idle (:state (snapshot :auth3/flow))))
      (is (some (fn [ev]
                  (and (= :rf.machine/destroyed (:operation ev))
                       (= :http/post#1 (:actor-id (:tags ev)))))
                @traces)
          "expected :rf.machine/destroyed trace targeting :http/post#1"))))
