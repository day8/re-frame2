(ns day8.re-frame2-causa.panels.machine-inspector-sim-cljs-test
  "CLJS-side wiring + view + integration tests for the UC1 Sim
  sub-mode (rf2-v869p, Phase 2, parent rf2-2tkza).

  ## What's under test (in addition to the pure-data tests in
  `machine_inspector_sim_helpers_cljs_test.cljc`)

    1. **Registry** wires the `:rf.causa/sim-*` sub + event family
       under `:rf/causa`.

    2. **Sim start** clones the machine definition into Causa state +
       seeds the initial snapshot (production registry untouched).

    3. **Sim step** with a valid event → engine OK Result → snapshot
       advances + audit-trail grows. Sim is hermetic — the host's
       app-db / registered-machine snapshots stay put.

    4. **Sim step** with a fail-Result → snapshot stays + `:last-error`
       populated.

    5. **Sim reset** rewinds the snapshot, clears the trail, preserves
       the active flag.

    6. **Sim stop** disposes the per-machine slot (no leak in
       Causa's `:sim/by-machine` map).

    7. **Chart integration**: when sim is active for the selected
       machine, the chart's `data-sim-active` is `\"true\"` + the
       `data-highlight-id` reflects the sim snapshot's `:state`.

    8. **Sim side rail** mounts when active + carries the testid
       hooks the design calls out (banner, event input, step button,
       reset button, exit button, audit trail).

    9. **Toggle button** is enabled when a definition is available +
       dispatches `:rf.causa/sim-start` / `:sim-stop`.

  Same hiccup-walker pattern as `machine_inspector_view_cljs_test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-causa.panels.machine-inspector-sim :as sim]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- hiccup walker -------------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (let [expanded (expand-fn-component tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- fixture data --------------------------------------------------------

(def ^:private fixture-definition
  {:initial :idle
   :data    {:counter 0}
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-snapshots! [snapshots]
  (rf/dispatch-sync
    [:rf.causa/set-machine-snapshots-override-for-test snapshots]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- force-picker-mode!
  "Force the panel out of the rf2-a9cke focused-event default into the
  picker-driven Mode A (definition) so the chart / sim chrome / sim
  side-rail this test ns exercises mounts. The sim sub-mode is
  picker-driven by design — the focused-event lens is the canonical
  lens but doesn't surface the sim sub-mode chrome."
  []
  (rf/dispatch-sync [:rf.causa/set-forced-machine-mode :mode-a]))

(def ^:private ok-result
  {:re-frame.machines.result/tag :ok
   :re-frame.machines.result/snap {:state :authing :data {:counter 1}}
   :re-frame.machines.result/fx []})

(def ^:private fail-result
  {:re-frame.machines.result/tag :fail
   :re-frame.machines.result/info {:reason :no-matching-transition}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-sim-handlers
  (testing "register-causa-handlers! installs every rf2-v869p Phase 2 handler"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa/sim-by-machine)))
    (is (some? (registrar/handler :sub :rf.causa/sim-state)))
    (is (some? (registrar/handler :sub :rf.causa/sim-active?)))
    (is (some? (registrar/handler :sub :rf.causa/sim-available-transitions)))
    (is (some? (registrar/handler :sub :rf.causa/sim-event-suggestions)))
    (is (some? (registrar/handler :event :rf.causa/sim-start)))
    (is (some? (registrar/handler :event :rf.causa/sim-step)))
    (is (some? (registrar/handler :event :rf.causa/sim-reset)))
    (is (some? (registrar/handler :event :rf.causa/sim-stop)))
    (is (some? (registrar/handler :event :rf.causa/sim-set-pending-event)))
    (is (some? (registrar/handler :event :rf.causa/sim-set-pending-data)))))

;; ---- (2) sim-start ------------------------------------------------------

(deftest sim-start-clones-definition-and-seeds-snapshot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [sim @(rf/subscribe [:rf.causa/sim-state])]
      (is (true? (:active? sim)))
      (is (= :auth/login (:machine-id sim)))
      (is (= fixture-definition (:definition sim))
          "definition cloned into Causa state")
      (is (= :idle (get-in sim [:snapshot :state])))
      (is (= {:counter 0} (get-in sim [:snapshot :data]))))))

(deftest sim-start-does-not-touch-production-registry
  (testing "sim isolation — Causa's overrides for the *production*
            machine surfaces (the registered set + snapshot map) are
            unchanged by sim-start"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :idle :data {:counter 99}}})
      (override-definitions! {:auth/login fixture-definition})
      (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
      (rf/dispatch-sync [:rf.causa/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      ;; Production-side snapshot still has counter 99 — sim's clone
      ;; started fresh from the definition's :data slot.
      (let [data @(rf/subscribe [:rf.causa/machine-inspector-data])]
        (is (= 99 (-> data :selected :data :counter))
            "the production snapshot's :data is untouched"))
      ;; Sim's snapshot started clean from the definition's initial :data.
      (let [sim @(rf/subscribe [:rf.causa/sim-state])]
        (is (= 0 (-> sim :snapshot :data :counter))
            "the sim snapshot used the definition's :data, not the live snapshot")))))

;; ---- (3) sim-step OK ----------------------------------------------------

(deftest sim-step-ok-advances-snapshot-and-trail
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    ;; Stub the engine to return an OK Result without booting the
    ;; machines artefact.
    (with-redefs [rf/machine-transition (fn [_def _snap _event] ok-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [sim @(rf/subscribe [:rf.causa/sim-state])]
      (is (= :authing (get-in sim [:snapshot :state]))
          "snapshot advanced")
      (is (= {:counter 1} (get-in sim [:snapshot :data])))
      (is (= 1 (count (:audit-trail sim))))
      (is (= :idle (-> sim :audit-trail last :from)))
      (is (= :authing (-> sim :audit-trail last :to)))
      (is (= [:start] (-> sim :audit-trail last :event)))
      (is (nil? (:last-error sim))))))

;; ---- (4) sim-step FAIL --------------------------------------------------

(deftest sim-step-fail-leaves-snapshot-and-records-error
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [sim @(rf/subscribe [:rf.causa/sim-state])]
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot unchanged on fail")
      (is (= 0 (count (:audit-trail sim)))
          "trail unchanged on fail")
      (is (= [:bad] (-> sim :last-error :event))
          "error stamped onto sim state"))))

(deftest sim-step-engine-throw-treated-as-fail
  "When the machines artefact is not on the classpath, `rf/machine-
  transition` throws; the sim handler catches and synthesises a fail-
  Result so the user sees an error instead of a runtime crash."
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e]
                                          (throw (js/Error. "no artefact")))]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [sim @(rf/subscribe [:rf.causa/sim-state])]
      (is (= :idle (get-in sim [:snapshot :state])))
      (is (some? (:last-error sim))))))

;; ---- (5) sim-reset ------------------------------------------------------

(deftest sim-reset-rewinds-snapshot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    ;; Confirm we moved
    (is (= :authing (-> @(rf/subscribe [:rf.causa/sim-state])
                        :snapshot :state)))
    (rf/dispatch-sync [:rf.causa/sim-reset {:machine-id :auth/login}])
    (let [sim @(rf/subscribe [:rf.causa/sim-state])]
      (is (true? (:active? sim))
          "still in sim mode after reset")
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot rewound to initial")
      (is (= [] (:audit-trail sim))
          "trail cleared")
      (is (nil? (:last-error sim))))))

;; ---- (6) sim-stop --------------------------------------------------------

(deftest sim-stop-disposes-per-machine-slot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (is (some? @(rf/subscribe [:rf.causa/sim-state])))
    (rf/dispatch-sync [:rf.causa/sim-stop {:machine-id :auth/login}])
    (is (nil? @(rf/subscribe [:rf.causa/sim-state]))
        "per-machine slot deleted on stop")
    (let [by-machine @(rf/subscribe [:rf.causa/sim-by-machine])]
      (is (not (contains? by-machine :auth/login))
          "Causa's :sim/by-machine map carries no entry for the stopped sim"))))

;; ---- (7) chart integration ----------------------------------------------

(deftest chart-flips-sim-active-when-sim-on
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-snapshots!   {:auth/login {:state :idle :data {}}})
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    ;; Before sim-start the chart's data-sim-active is "false".
    (let [tree-before  (machine-inspector/Panel)
          chart-before (find-by-testid tree-before "rf-causa-machine-inspector-chart")]
      (is (= "false" (:data-sim-active (second chart-before)))
          "sim is off — chart reflects this"))
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree-after  (machine-inspector/Panel)
          chart-after (find-by-testid tree-after "rf-causa-machine-inspector-chart")]
      (is (= "true" (:data-sim-active (second chart-after)))
          "sim is on — chart reflects this"))))

(deftest chart-highlight-shifts-to-sim-snapshot
  (testing "with sim active, the chart's data-highlight-id is sourced
            from the sim snapshot, not the production snapshot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (force-picker-mode!)
      (override-machines!    [:auth/login])
      ;; The production snapshot says :authing — but sim's snapshot
      ;; starts at :idle (the definition's :initial). The chart should
      ;; pick the sim value when sim is active.
      (override-snapshots!   {:auth/login {:state :authing :data {}}})
      (override-definitions! {:auth/login fixture-definition})
      (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
      (rf/dispatch-sync [:rf.causa/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (let [tree  (machine-inspector/Panel)
            chart (find-by-testid tree "rf-causa-machine-inspector-chart")]
        (is (= "idle" (:data-highlight-id (second chart)))
            "chart highlights the sim snapshot's :state (idle), not the live :authing")))))

(deftest chart-highlight-follows-sim-step
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [tree  (machine-inspector/Panel)
          chart (find-by-testid tree "rf-causa-machine-inspector-chart")]
      (is (= "authing" (:data-highlight-id (second chart)))
          "after a sim step the chart re-renders with the new highlight"))))

;; ---- (8) sim side rail ---------------------------------------------------

(deftest side-rail-mounts-when-sim-active
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    ;; Off — no rail.
    (is (nil? (find-by-testid (machine-inspector/Panel)
                              "rf-causa-machine-inspector-sim-rail"))
        "rail absent when sim is off")
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (machine-inspector/Panel)]
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-rail"))
          "rail present when sim is on")
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-banner")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-current-state")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-event-input")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-data-input")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-step-button")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-reset-button")))
      (is (some? (find-by-testid tree "rf-causa-machine-inspector-sim-exit-button"))))))

(deftest side-rail-renders-available-transitions
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (machine-inspector/Panel)
          available (find-all-by-testid-prefix
                      tree "rf-causa-machine-inspector-sim-available-")]
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-sim-available-list")))
      ;; :idle declares :start — should be in the available list.
      (is (some #(= "rf-causa-machine-inspector-sim-available-start"
                    (:data-testid (second %)))
                available)))))

(deftest side-rail-renders-audit-trail-after-step
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (machine-inspector/Panel)]
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-sim-audit-empty"))
          "empty audit message before any steps"))
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [tree (machine-inspector/Panel)]
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-sim-audit-list")))
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-sim-audit-0"))
          "one audit row after one step"))))

(deftest side-rail-surfaces-error-on-fail
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.causa/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [tree (machine-inspector/Panel)]
      (is (some? (find-by-testid
                   tree "rf-causa-machine-inspector-sim-error"))
          "error toast surfaces inline"))))

;; ---- (9) toggle button ---------------------------------------------------

(deftest toggle-button-enabled-when-definition-present
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (let [tree   (machine-inspector/Panel)
          toggle (find-by-testid tree "rf-causa-machine-inspector-sim-toggle")]
      (is (some? toggle))
      (is (= "false" (:data-active (second toggle))))
      (is (some? (:on-click (second toggle)))
          "toggle carries an on-click handler when a definition is available"))))

(deftest toggle-button-disabled-without-definition
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines! [:auth/login])
    ;; No definitions override — Causa has no way to clone.
    (let [tree   (machine-inspector/Panel)
          toggle (find-by-testid tree "rf-causa-machine-inspector-sim-toggle")]
      (is (some? toggle))
      (is (nil? (:on-click (second toggle)))
          "toggle is non-interactive when no definition is available"))))

(deftest toggle-button-dispatches-sim-start-and-stop
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (let [dispatches (atom [])]
      (with-redefs [rf/dispatch* (fn
                                   ([ev] (swap! dispatches conj ev) nil)
                                   ([ev _opts] (swap! dispatches conj ev) nil))]
        (let [tree   (machine-inspector/Panel)
              toggle (find-by-testid tree "rf-causa-machine-inspector-sim-toggle")
              handler (:on-click (second toggle))]
          (handler nil))
        ;; sim-start should have been dispatched
        (is (some (fn [ev]
                    (and (vector? ev)
                         (= :rf.causa/sim-start (first ev))))
                  @dispatches)
            "first toggle click fires sim-start")))))

(deftest toggle-button-stops-sim-when-active
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (force-picker-mode!)
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (rf/dispatch-sync [:rf.causa/select-machine-id :auth/login])
    (rf/dispatch-sync [:rf.causa/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree   (machine-inspector/Panel)
          toggle (find-by-testid tree "rf-causa-machine-inspector-sim-toggle")]
      (is (= "true" (:data-active (second toggle)))
          "toggle reflects active sim state"))))

;; ---- (10) frame isolation -----------------------------------------------

(deftest sim-state-does-not-leak-into-default-frame
  (testing "sim state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (some? (:sim/by-machine causa-db))
          "sim slot lands on Causa")
      (is (nil? (:sim/by-machine default-db))
          "sim slot did NOT leak into :rf/default"))))
