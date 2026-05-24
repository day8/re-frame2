(ns day8.re-frame2-xray.static.machines.sim-cljs-test
  "CLJS-side wiring + view + integration tests for the Static Machines
  Sim sub-mode (rf2-r4nao rehost; engine originally rf2-v869p Phase 2,
  parent rf2-2tkza).

  ## What's under test (in addition to the pure-data tests in
  `sim_helpers_cljs_test.cljc`)

    1. **Registry** wires the `:rf.xray.static.machines/sim-*` sub +
       event family under `:rf/xray`.

    2. **Sim start** clones the machine definition into Xray state +
       seeds the initial snapshot (production registry untouched).

    3. **Sim step** with a valid event → engine OK Result → snapshot
       advances + audit-trail grows. Sim is hermetic — the host's
       app-db / registered-machine snapshots stay put.

    4. **Sim step** with a fail-Result → snapshot stays + `:last-error`
       populated.

    5. **Sim reset** rewinds the snapshot, clears the trail, preserves
       the active flag.

    6. **Sim stop** disposes the per-machine slot (no leak in Xray's
       `:rf.xray.static.machines/sim-by-machine` map).

    7. **Sim rail** mounts when active + carries the testid hooks the
       design calls out (banner, event input, step button, reset button,
       exit button, audit trail).

    8. **Body auto-start** — `sim/body` dispatches `:sim-start` when no
       sim-state exists yet for the selected machine + definition.

    9. **Frame isolation** — sim state stays on `:rf/xray`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.sim :as sim]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

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

;; rf2-u422r — a RAW walker that does NOT invoke fn components, so a
;; `[machine-canvas/Chart {...}]` child survives as data and its props
;; are assertable (the expanding `hiccup-seq` would replace it with the
;; component's render output).

(defn- raw-hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

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
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-snapshots! [snapshots]
  (rf/dispatch-sync
    [:rf.xray/set-machine-snapshots-override-for-test snapshots]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- select-static-machine! [machine-id]
  (rf/dispatch-sync [:rf.xray.static.machines/select machine-id]))

(def ^:private ok-result
  {:re-frame.machines.result/tag :ok
   :re-frame.machines.result/snap {:state :authing :data {:counter 1}}
   :re-frame.machines.result/fx []})

(def ^:private fail-result
  {:re-frame.machines.result/tag :fail
   :re-frame.machines.result/info {:reason :no-matching-transition}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-sim-handlers
  (testing "register-xray-handlers! installs every rf2-r4nao Sim handler"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-by-machine)))
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-state)))
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-active?)))
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-available-transitions)))
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-event-suggestions)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-start)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-step)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-reset)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-stop)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-set-pending-event)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-set-pending-data)))))

;; ---- (2) sim-start ------------------------------------------------------

(deftest sim-start-clones-definition-and-seeds-snapshot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
      (is (true? (:active? sim)))
      (is (= :auth/login (:machine-id sim)))
      (is (= fixture-definition (:definition sim))
          "definition cloned into Xray state")
      (is (= :idle (get-in sim [:snapshot :state])))
      (is (= {:counter 0} (get-in sim [:snapshot :data]))))))

(deftest sim-start-does-not-touch-production-registry
  (testing "sim isolation — Xray's overrides for the *production*
            machine surfaces (the registered set + snapshot map) are
            unchanged by sim-start"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :idle :data {:counter 99}}})
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      ;; Production-side snapshot still has counter 99 — sim's clone
      ;; started fresh from the definition's :data slot.
      (let [snaps @(rf/subscribe [:rf.xray/machine-snapshots-override])]
        (is (= 99 (get-in snaps [:auth/login :data :counter]))
            "the production snapshot's :data is untouched"))
      ;; Sim's snapshot started clean from the definition's initial :data.
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (= 0 (-> sim :snapshot :data :counter))
            "the sim snapshot used the definition's :data, not the live snapshot")))))

;; ---- (3) sim-step OK ----------------------------------------------------

(deftest sim-step-ok-advances-snapshot-and-trail
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    ;; Stub the engine to return an OK Result without booting the
    ;; machines artefact.
    (with-redefs [rf/machine-transition (fn [_def _snap _event] ok-result)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
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
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot unchanged on fail")
      (is (= 0 (count (:audit-trail sim)))
          "trail unchanged on fail")
      (is (= [:bad] (-> sim :last-error :event))
          "error stamped onto sim state"))))

(deftest sim-step-engine-throw-treated-as-fail
  (testing "When the machines artefact is not on the classpath,
            `rf/machine-transition` throws; the sim handler catches and
            synthesises a fail-Result so the user sees an error instead
            of a runtime crash."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e]
                                            (throw (js/Error. "no artefact")))]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                           {:machine-id :auth/login
                            :event [:start]}]))
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (= :idle (get-in sim [:snapshot :state])))
        (is (some? (:last-error sim)))))))

;; ---- (4b) on-chart edge click → step (rf2-u422r) ------------------------

(deftest registry-installs-on-chart-sim-handlers
  (testing "rf2-u422r — register-xray-handlers! installs the on-chart
            sub family + the edge-click step event"
    (registry/register-xray-handlers!)
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-current-state)))
    (is (some? (registrar/handler :sub :rf.xray.static.machines/sim-last-transition)))
    (is (some? (registrar/handler :event :rf.xray.static.machines/sim-chart-edge-clicked)))))

(deftest sim-chart-edge-clicked-steps-via-engine
  (testing "rf2-u422r — an on-chart edge click folds ONE step through the
            SAME engine path as the step-button: snapshot advances +
            audit-trail grows. No new transition logic."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                           {:machine-id :auth/login
                            :event-id   :start}]))
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (= :authing (get-in sim [:snapshot :state]))
            "snapshot advanced via the on-chart click")
        (is (= 1 (count (:audit-trail sim))))
        (is (= [:start] (-> sim :audit-trail last :event))
            "the clicked edge's event-id was coerced to the step vector")))))

(deftest sim-chart-edge-clicked-nil-event-is-noop
  (testing "rf2-u422r — clicking an inert (auto / non-fireable) edge with
            a nil event-id is a no-op: no step, no trail growth"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                         {:machine-id :auth/login
                          :event-id   nil}])
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (= :idle (get-in sim [:snapshot :state]))
            "snapshot unchanged on an inert-edge click")
        (is (= 0 (count (:audit-trail sim))))))))

(deftest sim-chart-edge-clicked-fail-surfaces-guard-error
  (testing "rf2-u422r — a failed-guard transition fired ON the chart
            surfaces the error exactly as the button does: snapshot stays
            put + :last-error stamped (rendered in the rail's error toast)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                           {:machine-id :auth/login
                            :event-id   :start}]))
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (= :idle (get-in sim [:snapshot :state]))
            "snapshot stays put on a failed on-chart step")
        (is (= [:start] (-> sim :last-error :event))
            "guard pass/fail surfaces via :last-error")))))

(deftest sim-current-state-and-last-transition-subs
  (testing "rf2-u422r — the chart-binding subs derive the active state +
            the taken transition off sim-state"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      ;; Before any step: current = initial, last-transition = nil.
      (is (= :idle @(rf/subscribe [:rf.xray.static.machines/sim-current-state])))
      (is (nil? @(rf/subscribe [:rf.xray.static.machines/sim-last-transition])))
      ;; After a step the chart subs reflect the advance.
      (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                           {:machine-id :auth/login :event-id :start}]))
      (is (= :authing @(rf/subscribe [:rf.xray.static.machines/sim-current-state])))
      (let [lt @(rf/subscribe [:rf.xray.static.machines/sim-last-transition])]
        (is (= :idle (:from lt)))
        (is (= :authing (:to lt)))
        (is (= [:start] (:event lt)))))))

;; ---- (5) sim-reset ------------------------------------------------------

(deftest sim-reset-rewinds-snapshot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    ;; Confirm we moved
    (is (= :authing (-> @(rf/subscribe [:rf.xray.static.machines/sim-state])
                        :snapshot :state)))
    (rf/dispatch-sync [:rf.xray.static.machines/sim-reset
                       {:machine-id :auth/login}])
    (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
      (is (true? (:active? sim))
          "still in sim mode after reset")
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot rewound to initial")
      (is (= [] (:audit-trail sim))
          "trail cleared")
      (is (nil? (:last-error sim))))))

;; ---- (6) sim-stop --------------------------------------------------------

(deftest sim-stop-disposes-per-machine-slot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (is (some? @(rf/subscribe [:rf.xray.static.machines/sim-state])))
    (rf/dispatch-sync [:rf.xray.static.machines/sim-stop
                       {:machine-id :auth/login}])
    (is (nil? @(rf/subscribe [:rf.xray.static.machines/sim-state]))
        "per-machine slot deleted on stop")
    (let [by-machine @(rf/subscribe [:rf.xray.static.machines/sim-by-machine])]
      (is (not (contains? by-machine :auth/login))
          "Xray's sim-by-machine map carries no entry for the stopped sim"))))

;; ---- (7) sim rail (the in-body content rail) ----------------------------

(deftest rail-renders-nothing-when-sim-inactive
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (is (nil? (sim/SimRail))
        "rail returns nil when sim is inactive")))

(deftest rail-mounts-when-sim-active
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-rail"))
          "rail present when sim is on")
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-banner")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-current-state")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-event-input")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-data-input")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-step-button")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-reset-button")))
      (is (some? (find-by-testid tree "rf-xray-static-machines-sim-exit-button"))))))

(deftest rail-renders-available-transitions
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)
          available (find-all-by-testid-prefix
                      tree "rf-xray-static-machines-sim-available-")]
      (is (some? (find-by-testid
                   tree "rf-xray-static-machines-sim-available-list")))
      ;; :idle declares :start — should be in the available list.
      (is (some #(= "rf-xray-static-machines-sim-available-start"
                    (:data-testid (second %)))
                available)))))

(deftest rail-renders-audit-trail-after-step
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-xray-static-machines-sim-audit-empty"))
          "empty audit message before any steps"))
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-xray-static-machines-sim-audit-list")))
      (is (some? (find-by-testid
                   tree "rf-xray-static-machines-sim-audit-0"))
          "one audit row after one step"))))

(deftest rail-surfaces-error-on-fail
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-xray-static-machines-sim-error"))
          "error toast surfaces inline"))))

;; ---- (8) body auto-start -----------------------------------------------

(deftest body-auto-starts-sim-when-definition-present
  (testing "sim/body dispatches :sim-start when called without an
            existing sim-state — the auto-start lands the slot so the
            next subscribe re-fires with the rail populated"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      ;; Pre-condition: no sim-state for the machine.
      (is (nil? @(rf/subscribe [:rf.xray.static.machines/sim-state])))
      ;; Render the body — auto-start fires via rf/dispatch (async).
      (let [_tree (sim/body {:machine-id :auth/login
                             :definition fixture-definition})]
        ;; Drain the event queue so the dispatched :sim-start lands.
        (rf/dispatch-sync [:rf.xray.static.machines/sim-set-pending-data
                           {:machine-id :auth/login :text ""}]))
      ;; The auto-started slot should exist post-flush.
      (let [sim @(rf/subscribe [:rf.xray.static.machines/sim-state])]
        (is (some? sim) "sim-state landed via the body's auto-start")
        (is (= :idle (get-in sim [:snapshot :state])))))))

;; ---- (8b) on-chart sim surface (rf2-u422r) ------------------------------

(deftest sim-chart-returns-canvas-bound-to-sim
  (testing "rf2-u422r — SimChart returns the topology chart wrapper bound
            to the sim engine (the on-chart simulation surface)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (let [tree (sim/SimChart {:machine-id :auth/login
                                :definition fixture-definition})]
        (is (= "rf-xray-static-machines-sim-chart"
               (:data-testid (second tree)))
            "the on-chart sim wrapper mounts")
        ;; The wrapper carries the machine-canvas Chart hiccup as data.
        (is (some (fn [node]
                    (and (vector? node)
                         (= machine-canvas/Chart (first node))))
                  (raw-hiccup-seq tree))
            "the wrapper embeds machine-canvas/Chart")))))

(deftest sim-chart-passes-sim-bindings-to-canvas
  (testing "rf2-u422r — SimChart hands the canvas the amber sim palette,
            the current snapshot state, the focused-edge lens off the last
            transition, and an on-edge-click callback"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-chart-edge-clicked
                           {:machine-id :auth/login :event-id :start}]))
      (let [tree        (sim/SimChart {:machine-id :auth/login
                                       :definition fixture-definition})
            chart-node  (some (fn [node]
                                (when (and (vector? node)
                                           (= machine-canvas/Chart (first node)))
                                  node))
                              (raw-hiccup-seq tree))
            chart-props (second chart-node)]
        (is (true? (:sim? chart-props)) "amber sim palette is on")
        (is (= :authing (:current-state chart-props))
            "active-state highlight = the advanced snapshot state")
        (is (= :idle (:from-highlight chart-props))
            "focused-edge origin = last transition :from")
        (is (= :authing (:to-highlight chart-props))
            "focused-edge landing = last transition :to")
        (is (fn? (:on-edge-click chart-props))
            "the chart gets an on-edge-click callback")))))

(deftest sim-body-renders-chart-and-rail-panes
  (testing "rf2-u422r — the sim body is a two-pane split: the on-chart sim
            surface (primary) + the rail side column"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (let [tree (sim/body {:machine-id :auth/login
                            :definition fixture-definition})]
        (is (some? (find-by-testid tree "rf-xray-static-machines-sim-body")))
        (is (some? (find-by-testid tree "rf-xray-static-machines-sim-chart-pane"))
            "chart pane present")
        (is (some? (find-by-testid tree "rf-xray-static-machines-sim-rail-pane"))
            "rail pane present")
        (is (some? (find-by-testid tree "rf-xray-static-machines-sim-chart"))
            "on-chart sim surface present")
        (is (some? (find-by-testid tree "rf-xray-static-machines-sim-rail"))
            "side rail still present")))))

(deftest body-renders-no-definition-hint-when-missing
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (select-static-machine! :auth/login)
    (let [tree (sim/body {:machine-id :auth/login :definition nil})]
      (is (some? (find-by-testid tree
                                 "rf-xray-static-machines-sim-no-definition"))))))

(deftest body-renders-no-machine-hint-when-missing
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (sim/body {:machine-id nil :definition fixture-definition})]
      (is (some? (find-by-testid tree
                                 "rf-xray-static-machines-sim-no-machine"))))))

;; ---- (9) frame isolation -----------------------------------------------

(deftest sim-state-does-not-leak-into-default-frame
  (testing "sim state lives on :rf/xray, never :rf/default"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}]))
    (let [xray-db   (frame/frame-app-db-value :rf/xray)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (some? (:rf.xray.static.machines/sim-by-machine xray-db))
          "sim slot lands on Xray")
      (is (nil? (:rf.xray.static.machines/sim-by-machine default-db))
          "sim slot did NOT leak into :rf/default"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard (preserved from the
;; rf2-r4nao rehost source; see ns docstring in `static/machines/sim.cljs`).
;;
;; Two `for` loops in the Sim rail wrap function-call list forms — the
;; available-transition rows and audit-trail rows. Reagent's
;; `get-react-key` only reads `:key` from vector meta, so the keys must
;; land on the returned `[:li …]` vectors via `with-meta` rather than
;; reader-meta on the source list. This test asserts the meta is
;; preserved across the rehost.
;; ---------------------------------------------------------------------------

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest sim-available-transitions-carry-key-meta
  (testing "available-transition-row for-loop ships per-transition <li>
            children with :key meta on the returned vector (rf2-ppzid)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (let [tree      (sim/SimRail)
            available (raw-find-all-by-testid-prefix
                        tree "rf-xray-static-machines-sim-available-")
            ;; Drop the container <ul> (testid `…-available-list`); we
            ;; want only the per-row <li> children.
            li-rows   (remove
                        (fn [n]
                          (= "rf-xray-static-machines-sim-available-list"
                             (:data-testid (second n))))
                        available)]
        (is (>= (count li-rows) 1) "at least one available-transition row")
        (doseq [row li-rows]
          (is (vector? row) "available-transition row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "available-transition row carries :key meta — got "
                   (pr-str (meta row)))))))))

(deftest sim-audit-trail-rows-carry-key-meta
  (testing "audit-trail-row for-loop ships per-step <li> children with
            :key meta on the returned vector (rf2-ppzid)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.xray.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
        (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                           {:machine-id :auth/login :event [:start]}])
        (rf/dispatch-sync [:rf.xray.static.machines/sim-step
                           {:machine-id :auth/login :event [:ok]}]))
      (let [tree (sim/SimRail)
            rows (raw-find-all-by-testid-prefix
                   tree "rf-xray-static-machines-sim-audit-")
            ;; Drop the container <ol> (testid `…-audit-list`).
            li-rows (remove
                      (fn [n]
                        (or (= "rf-xray-static-machines-sim-audit-list"
                               (:data-testid (second n)))
                            (= "rf-xray-static-machines-sim-audit-empty"
                               (:data-testid (second n)))))
                      rows)]
        (is (>= (count li-rows) 2) "two audit rows after two steps")
        (doseq [row li-rows]
          (is (vector? row) "audit row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "audit row carries :key meta — got "
                   (pr-str (meta row)))))))))
