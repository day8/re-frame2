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
            [re-frame.adapter.reagent :as reagent-adapter]
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
      ;; having to seed the snapshot manually. Per rf2-0z73, the
      ;; initial-state cascade ALSO fires every state's :entry action
      ;; on first-event bootstrap (shallowest-first along the initial
      ;; chain) — so the log accumulates :enter-auth + :enter-dash
      ;; BEFORE the sibling-leaf transition kicks in.
      (reset! log [])
      ;; Sibling-leaf transition. LCA is :authenticated; only the leaf
      ;; exit/entry hooks fire for the transition itself — the parent's
      ;; :enter-auth runs ONCE during the bootstrap cascade and does
      ;; NOT re-fire on the sibling transition (LCA is :authenticated).
      (rf/dispatch-sync [:auth/flow [:open-settings]])
      (is (= [:authenticated :settings] (:state (snapshot :auth/flow)))
          "snapshot moved to the sibling leaf")
      (is (= [:enter-auth :enter-dash :exit-dash :enter-set] @log)
          "initial-cascade :entry actions fired on bootstrap (:enter-auth + :enter-dash) followed by the sibling-leaf exit/entry (:exit-dash + :enter-set). The LCA parent's :enter-auth fires ONCE on bootstrap, not on the LCA-bounded sibling transition.")))

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

;; ---- (1b) wildcard precedence in hierarchical machines (rf2-fhb9) ---------
;; Per Spec 005 §Wildcard transitions and §Transition resolution: at each
;; level, explicit-event match beats `:*`; only if neither matches does the
;; runtime walk up to the parent. So a leaf's `:*` SHADOWS a parent's
;; explicit handler for the same event — wildcard wins at the deeper level
;; before parent fallthrough kicks in. This is the divergence point that
;; the §Wildcard transitions list at L213-218 used to muddle (rf2-fhb9).
(deftest machine-hierarchical-wildcard-precedence-cljs
  (testing "leaf :* shadows parent's explicit handler for the same event"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-explicit (tag :parent-explicit)
                     :leaf-wildcard   (tag :leaf-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-explicit}}    ;; explicit at parent
             :states
             {:dashboard
              {:on {:* {:action :leaf-wildcard}}}}}}}]      ;; :* at leaf
      (rf/reg-machine :rf2-fhb9/precedence machine)
      (reset! log [])
      (rf/dispatch-sync [:rf2-fhb9/precedence [:help]])
      ;; At leaf [:authenticated :dashboard]: explicit miss → :* hit. The
      ;; runtime stops walking; parent's explicit :help is shadowed.
      (is (= [:leaf-wildcard] @log)
          "leaf-level :* fired, parent's explicit :help shadowed (per-level rule)")
      (is (not (some #{:parent-explicit} @log))
          "parent's explicit handler must NOT fire when leaf :* matched")))

  (testing "parent fallthrough still works when leaf has neither explicit nor :*"
    (let [log (atom [])
          tag (fn [k] (fn [_ _] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:parent-explicit (tag :parent-explicit)
                     :parent-wildcard (tag :parent-wildcard)}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:help {:action :parent-explicit}
                       :*    {:action :parent-wildcard}}    ;; both at parent
             :states
             {:dashboard {}}}}}]                            ;; leaf has no :on
      (rf/reg-machine :rf2-fhb9/parent-walk machine)
      (reset! log [])
      ;; :help at parent — explicit beats parent's own :*.
      (rf/dispatch-sync [:rf2-fhb9/parent-walk [:help]])
      (is (= [:parent-explicit] @log)
          "explicit at the matching level beats :* at the same level")
      ;; :unknown at parent — falls through to parent's :*.
      (reset! log [])
      (rf/dispatch-sync [:rf2-fhb9/parent-walk [:unknown]])
      (is (= [:parent-wildcard] @log)
          "no explicit anywhere — parent's :* fires"))))

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

;; ---- (3a) :after multi-stage + guard suppression -------------------------

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

;; ---- (3b) subscription-vector :after delay (dynamic) ---------------------

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

;; ---- (4) declarative :invoke ----------------------------------------------
;; Mirrors invoke-spawn-on-entry-destroy-on-exit.edn — entering a state with
;; :invoke emits a :rf.machine/spawn fx (observable as :rf.machine/spawned
;; trace); exiting emits :rf.machine/destroy (observable as
;; :rf.machine/destroyed trace).
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
      ;; Entering :authenticating: :rf.machine/spawn fx fires
      ;; (→ :rf.machine/spawned trace), :on-spawn callback records the
      ;; deterministic actor id into :data.:pending.
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
          "expected :rf.machine/spawned trace from the :rf.machine/spawn fx")
      ;; Exiting :authenticating via :auth/failed: :rf.machine/destroy fx
      ;; fires targeting the recorded actor id.
      (reset! traces [])
      (rf/dispatch-sync [:auth3/flow [:auth/failed]])
      (rf/remove-trace-cb! ::inv)
      (is (= :idle (:state (snapshot :auth3/flow))))
      (is (some (fn [ev]
                  (and (= :rf.machine/destroyed (:operation ev))
                       (= :http/post#1 (:actor-id (:tags ev)))))
                @traces)
          "expected :rf.machine/destroyed trace targeting :http/post#1"))))

;; ---- (4a') :invoke :data fn-form materialised at spawn (rf2-h131) ---------
;; Per Spec 005 §Spec-spec keys (line 1503/1511): `:data` admits a function
;; form `(fn [snap ev] data)` so the spawned child's initial data can be
;; derived from the parent's post-action snapshot + the triggering event.
;; The runtime materialises the fn before passing the value to the
;; spawn-fx (which expects a literal map).

(deftest machine-invoke-data-fn-form-cljs
  (testing "fn-form `:data` is materialised — spawned child receives the result map, NOT the fn"
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {:endpoint "/api/login"}
                   :states
                   {:idle    {:on {:start :working}}
                    :working {:invoke {:machine-id :h131/worker
                                       :data       (fn [snap _]
                                                     {:url    (-> snap :data :endpoint)
                                                      :method :post})}}}}]
      (rf/reg-machine :h131/worker child)
      (rf/reg-machine :h131/sup parent)
      (rf/dispatch-sync [:h131/sup [:start]])
      (let [child-data (:data (snapshot :h131/worker#1))]
        (is (map? child-data)
            "spawned child's :data is a literal map, not the fn")
        (is (= "/api/login" (:url child-data))
            "fn-form derived :url from the parent's :data.:endpoint")
        (is (= :post (:method child-data))
            "fn-form-derived :method survived the spawn"))))
  (testing "fn-form `:data` sees the post-action snapshot (Spec 005:1511)"
    (let [child   {:initial :running :data {} :states {:running {}}}
          parent  {:initial :idle
                   :data    {:base "https://api.example.com"}
                   :actions {:assemble (fn [data _]
                                         {:data (assoc data :endpoint
                                                       (str (:base data) "/v1/me"))})}
                   :states
                   {:idle    {:on {:go {:target :working :action :assemble}}}
                    :working {:invoke {:machine-id :h131b/worker
                                       :data       (fn [snap _]
                                                     {:url (-> snap :data :endpoint)})}}}}]
      (rf/reg-machine :h131b/worker child)
      (rf/reg-machine :h131b/sup parent)
      (rf/dispatch-sync [:h131b/sup [:go]])
      (is (= "https://api.example.com/v1/me"
             (:url (:data (snapshot :h131b/worker#1))))
          "fn-form saw the :data writes the transition's :action made"))))

;; ---- (4b) state-level :after on :invoke-bearing state (rf2-3y3y) ----------
;; Per Spec 005 §Wall-clock timeouts on :invoke — use parent state's :after.
;; The pre-rf2-3y3y :timeout-ms slot on :invoke / :invoke-all is dropped;
;; wall-clock guards are expressed via :after on the :invoke-bearing state
;; itself. When :after fires, the standard exit cascade tears down the
;; spawned child via :rf.machine/destroy.

(deftest machine-after-on-invoke-cljs
  (testing ":after on an :invoke-bearing state — synthetic timer-elapsed cancels child + transitions"
    (let [child  {:initial :running
                  :states  {:running {:on {:never-fires :done}}
                            :done    {}}}
          parent {:initial :idle
                  :data    {:rf/after-epoch 0}
                  :on-spawn-actions
                  {:record (fn [data id] (assoc data :pending id))}
                  :states
                  {:idle {:on {:go :authenticating}}
                   :authenticating
                   {:invoke {:machine-id :child/auth-after
                             :on-spawn   :record}
                    :after  {30000 :timed-out}
                    :on    {:auth/succeeded :authenticated}}
                   :authenticated {}
                   :timed-out     {}}}
          traces (atom [])]
      (rf/reg-machine :child/auth-after child)
      (rf/reg-machine :sup/auth-after  parent)
      (rf/register-trace-cb! ::ato (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:sup/auth-after [:go]])
      (is (= :authenticating (:state (snapshot :sup/auth-after)))
          "parent transitioned :idle → :authenticating")
      (is (some (fn [ev]
                  (and (= :rf.machine.timer/scheduled (:operation ev))
                       (= 30000   (:delay (:tags ev)))
                       (= :literal (:delay-source (:tags ev)))))
                @traces)
          "expected :rf.machine.timer/scheduled with :delay-source :literal")
      (let [child-id (get-in (rf/get-frame-db :rf/default)
                             [:rf/spawned :sup/auth-after [:authenticating]])
            epoch    (get-in (snapshot :sup/auth-after) [:data :rf/after-epoch])]
        (is (some? child-id) "spawn slot bound to the spawned child id")
        (reset! traces [])
        ;; Synthetically dispatch the :after-elapsed timer event with the
        ;; current epoch — mirrors the wall-clock setTimeout firing.
        (rf/dispatch-sync [:sup/auth-after [:rf.machine.timer/after-elapsed 30000 epoch]])
        (is (= :timed-out (:state (snapshot :sup/auth-after)))
            "parent transitioned :authenticating → :timed-out via :after firing")
        (is (nil? (get-in (rf/get-frame-db :rf/default)
                          [:rf/machines child-id]))
            "child machine snapshot torn down by the standard exit cascade"))
      (rf/remove-trace-cb! ::ato))))

;; ---- (4c) :timeout-ms on :invoke / :invoke-all is rejected (rf2-3y3y) ----

(deftest machine-invoke-timeout-ms-removed-cljs
  (testing ":timeout-ms on :invoke is rejected with :rf.error/invoke-timeout-ms-removed"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:invoke {:machine-id :stub
                                            :timeout-ms 1000
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke bad)))))
  (testing ":on-timeout alone on :invoke is also rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :running}}
                         :running {:invoke {:machine-id :stub
                                            :on-timeout [:never]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-on-to bad)))))
  (testing ":timeout-ms on :invoke-all is rejected"
    (let [bad {:initial :idle
               :states  {:idle {:on {:go :h}}
                         :h    {:invoke-all
                                {:children
                                 [{:id :a :machine-id :stub}]
                                 :join             :all
                                 :on-child-done    :done
                                 :on-child-error   :failed
                                 :on-all-complete  [:done!]
                                 :timeout-ms       5000
                                 :on-timeout       [:to]}}}}]
      (is (thrown-with-msg? js/Error
                            #"invoke-timeout-ms-removed"
                            (rf/reg-machine :rmv/bad-invoke-all bad))))))

;; ---- (5) :system-id named-machine addressing (rf2-suue / rf2-ecv4) -------
;;
;; Spec 005 §Named addressing via :system-id: a spawn whose args carry a
;; `:system-id` keyword binds that name in the per-frame `[:rf/system-ids]`
;; reverse index. `(rf/machine-by-system-id sid)` resolves the binding;
;; destroy clears it; collisions emit `:rf.error/system-id-collision` and
;; rebind (last-write-wins).
;;
;; These tests exercise the bundled live-handler wiring (rf2-suue
;; precondition): the spawn registers the child as a real event handler,
;; so dispatch-by-system-id reaches a running actor.

(deftest machine-system-id-cljs
  (testing "spawn-with-system-id binds the index, lookup resolves to spawned id, destroy clears"
    (let [;; A child machine registered ahead of the parent's spawn.
          child {:initial :running
                 :data    {:hits 0}
                 :actions {:bump (fn [data _] {:data {:hits (inc (:hits data))}})}
                 :states  {:running {:on {:ping {:action :bump}}}}}
          ;; A parent that spawns the child with a :system-id under :invoke.
          parent {:initial :idle
                  :on-spawn-actions
                  {:auth/record-actor (fn [data actor-id]
                                        (assoc data :pending actor-id))}
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:invoke {:machine-id :worker/proc
                                        :system-id  :worker
                                        :on-spawn   :auth/record-actor}
                               :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/flow parent)
      (rf/dispatch-sync [:sup/flow [:start]])
      ;; (1) Lookup-by-system-id returns the spawned id.
      (let [spawned (rf/machine-by-system-id :worker)]
        (is (= :worker/proc#1 spawned)
            ":system-id resolves to the spawned machine id")
        (is (= spawned (get-in (rf/get-frame-db :rf/default)
                               [:rf/system-ids :worker]))
            "[:rf/system-ids] reverse index records the binding")
        (is (some? (get-in (rf/get-frame-db :rf/default)
                           [:rf/machines spawned]))
            "snapshot initialised at [:rf/machines <spawned-id>]")
        ;; (2) Dispatch-by-system-id reaches the actor.
        (rf/dispatch-sync [spawned [:ping]])
        (is (= 1 (get-in (rf/get-frame-db :rf/default)
                         [:rf/machines spawned :data :hits]))
            "dispatch routed via system-id reached the live actor"))
      ;; (3) Destroy clears system-id binding and snapshot.
      (rf/dispatch-sync [:sup/flow [:done]])
      (is (nil? (rf/machine-by-system-id :worker))
          "post-destroy lookup returns nil")
      (is (nil? (get-in (rf/get-frame-db :rf/default)
                        [:rf/system-ids :worker]))
          "[:rf/system-ids] entry cleared on destroy")
      (is (nil? (get-in (rf/get-frame-db :rf/default)
                        [:rf/machines :worker/proc#1]))
          "snapshot cleared on destroy")))

  (testing "dispatch-to-system sugar resolves the system-id and routes the dispatch"
    (let [child  {:initial :running
                  :data    {:msgs []}
                  :actions {:record (fn [data [_ msg]]
                                      {:data {:msgs (conj (:msgs data) msg)}})}
                  :states  {:running {:on {:notify {:action :record}}}}}
          parent {:initial :idle
                  :on-spawn-actions
                  {:auth/record-actor (fn [data id] (assoc data :pending id))}
                  :states
                  {:idle    {:on {:go :running}}
                   :running {:invoke {:machine-id :notifier/proc
                                      :system-id  :notifier
                                      :on-spawn   :auth/record-actor}}}}]
      (rf/reg-machine :notifier/proc child)
      (rf/reg-machine :sup2/flow parent)
      (rf/dispatch-sync [:sup2/flow [:go]])
      (let [spawned (rf/machine-by-system-id :notifier)]
        (is (= :notifier/proc#1 spawned))
        ;; Verify the sugar dispatches via the system-id lookup. We use
        ;; dispatch-sync directly through the resolved id so the assert
        ;; reads post-drain state without depending on async timing —
        ;; dispatch-to-system itself wraps `dispatch` (queued); under a
        ;; non-Reagent test runner there's no render to trigger the
        ;; drain. The lookup-then-dispatch chain is what we're verifying.
        (rf/dispatch-sync [(rf/machine-by-system-id :notifier) [:notify "hello"]])
        (is (= ["hello"] (get-in (rf/get-frame-db :rf/default)
                                 [:rf/machines spawned :data :msgs]))
            "dispatch via system-id lookup reached the live actor")
        ;; And the sugar fn no-ops when the system-id is unbound.
        (is (nil? (rf/dispatch-to-system :no-such-system [:notify "x"]))
            "dispatch-to-system on an unbound system-id returns nil"))))

  (testing ":system-id collision emits :rf.error/system-id-collision and rebinds (last-write-wins)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          traces (atom [])]
      (rf/reg-machine :w/proc child)
      ;; First spawn under :primary
      (rf/reg-event-fx ::spawn1
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w/proc
                                    :id-prefix  :w/proc
                                    :system-id  :primary}]]}))
      (rf/reg-event-fx ::spawn2
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w/proc
                                    :id-prefix  :w/proc
                                    :system-id  :primary}]]}))
      (rf/register-trace-cb! ::col (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [::spawn1])
      (rf/dispatch-sync [::spawn2])
      (rf/remove-trace-cb! ::col)
      ;; Second spawn rebinds.
      (is (= :w/proc#2 (rf/machine-by-system-id :primary))
          "second spawn rebound :primary to the new actor")
      (is (some (fn [ev]
                  (and (= :rf.error/system-id-collision (:operation ev))
                       (= :primary (get-in ev [:tags :system-id]))
                       (= :w/proc#1 (get-in ev [:tags :existing-machine]))
                       (= :w/proc#2 (get-in ev [:tags :rebound-to]))))
                @traces)
          "expected :rf.error/system-id-collision trace")))

  )

(deftest machine-spawn-without-system-id-leaves-index-empty-cljs
  (testing "spawn-without-system-id leaves [:rf/system-ids] empty"
    (let [child {:initial :running :data {} :states {:running {}}}]
      (rf/reg-machine :w2/proc child)
      (rf/reg-event-fx ::spawn-anon
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w2/proc :id-prefix :w2/proc}]]}))
      (rf/dispatch-sync [::spawn-anon])
      (is (nil? (get-in (rf/get-frame-db :rf/default) [:rf/system-ids]))
          "[:rf/system-ids] not allocated when no spawns carry :system-id")
      (is (nil? (rf/machine-by-system-id :anything))
          "lookup against an unbound system-id returns nil"))))

;; ---- (N) :fsm/tags — state tags (rf2-ee0d Nine States Stage 1) -----------
;; Per Spec 005 §State tags: a state-node body may declare `:tags
;; <set-of-keywords>`. The runtime maintains the union of every active
;; state's tag set at [:rf/machines <id> :tags] in the snapshot and ships
;; `:rf/machine-has-tag?` + the `rf/has-tag?` sugar to query it.

(deftest machine-tags-flat-active-state-cljs
  (testing "flat machine — snapshot's :tags is the active state's tag set"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:tags #{:idle/empty :active}
                                 :on   {:fetch :loading}}
                       :loading {:tags #{:loading :transient :active}
                                 :on   {:done :complete}}
                       :complete {:tags #{:done :terminal}}}}]
      (rf/reg-machine :tags/flat m)
      (rf/dispatch-sync [:tags/flat [:fetch]])
      (is (= #{:loading :transient :active}
             (:tags (snapshot :tags/flat))))
      (rf/dispatch-sync [:tags/flat [:done]])
      (is (= #{:done :terminal}
             (:tags (snapshot :tags/flat)))))))

(deftest machine-tags-compound-union-cljs
  (testing "compound machine — :tags is the union along the active path"
    (let [m {:initial :authed
             :data    {}
             :states
             {:authed
              {:tags    #{:auth :gated}
               :initial :dash
               :states  {:dash {:tags #{:home}
                                :on   {:nav-settings :settings}}
                         :settings {:tags #{:settings :writable}}}}}}]
      (rf/reg-machine :tags/compound m)
      (rf/dispatch-sync [:tags/compound [:nav-settings]])
      (is (= [:authed :settings] (:state (snapshot :tags/compound))))
      (is (= #{:auth :gated :settings :writable}
             (:tags (snapshot :tags/compound)))))))

(deftest machine-tags-no-declaration-elided-cljs
  (testing "no-tags machine — :tags slot is elided from the snapshot"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:on {:go :b}}
                       :b {}}}]
      (rf/reg-machine :tags/empty m)
      (rf/dispatch-sync [:tags/empty [:go]])
      (let [s (snapshot :tags/empty)]
        (is (= :b (:state s)))
        (is (not (contains? s :tags))
            "empty union elided from snapshot per Spec 005 §Snapshot shape change")))))

(deftest machine-tags-has-tag-sub-cljs
  (testing ":rf/machine-has-tag? returns true iff the snapshot's :tags contains tag"
    (let [m {:initial :loading
             :data    {}
             :states  {:loading {:tags #{:loading :transient}
                                 :on   {:done :resolved}}
                       :resolved {:tags #{:done}}}}]
      (rf/reg-machine :tags/sub m)
      (rf/dispatch-sync [:tags/sub [:no-op]])
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading])))
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :transient])))
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :done])))
      ;; rf/has-tag? sugar resolves through the framework sub.
      (is (= true  @(rf/has-tag? :tags/sub :loading)))
      (is (= false @(rf/has-tag? :tags/sub :done)))
      (rf/dispatch-sync [:tags/sub [:done]])
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading])))
      (is (= true  @(rf/has-tag? :tags/sub :done)))))

  (testing ":rf/machine-has-tag? returns false for an unknown machine"
    (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/unknown :anything])))))

(deftest machine-tags-recomputed-on-always-cljs
  (testing ":tags reflects the post-:always-microstep state, not the intermediate"
    (let [m {:initial :asking
             :data    {:n 0}
             :guards  {:enough? (fn [d _] (>= (:n d) 1))}
             :actions {:bump   (fn [d _] {:data (update d :n inc)})}
             :states  {:asking
                       {:tags   #{:active}
                        :always [{:guard :enough? :target :winner}]
                        :on     {:tick {:action :bump}}}
                       :winner {:tags #{:terminal :celebrate}}}}]
      (rf/reg-machine :tags/always m)
      (rf/dispatch-sync [:tags/always [:tick]])
      (is (= :winner (:state (snapshot :tags/always))))
      (is (= #{:terminal :celebrate}
             (:tags (snapshot :tags/always)))))))
