(ns day8.re-frame2-causa.panels.machines-canvas-view-cljs-test
  "View tests for the Machines Canvas L4 sub-domain tab (rf2-mkpnb).

  Asserts the panel's structural contract:

    - Empty state — no machines registered → picker shows the
      `picker-empty` testid + canvas pane shows the `no-selection`
      testid.
    - Populated — when machines are registered, the picker renders
      one row per machine + the canvas pane mounts the
      `:on-state-click`-aware Chart adapter for the picked machine.
    - Selection — `:rf.causa.machines-canvas/select` updates the
      slot + the next render picks up the new machine.
    - No-definition — when the picked machine has no introspectable
      definition the canvas pane falls back to the `no-definition`
      testid (chart cannot render)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.machines-canvas.panel
             :as machines-canvas-panel]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(def ^:private find-by-testid           th/find-by-testid)
(def ^:private find-all-by-testid-prefix th/find-by-testid-prefix)

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- minimal-definition
  "Tiny well-shaped machine def so `:states` is countable + so the
  ELK / layered layout can produce ≥ 1 node. The chart adapter handles
  the layout; we only assert the wrapper renders + the chart-host
  testid is reachable."
  []
  {:states {:on  {:transitions {:flip {:to :off}}}
            :off {:transitions {:flip {:to :on}}}}})

;; ---- 1. empty state ----------------------------------------------------

(deftest panel-renders-empty-state-when-no-machines-registered
  (testing "no registered machines → picker-empty + no-selection"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [])
      (let [tree (machines-canvas-panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machines-canvas"))
            "panel root present")
        (is (some? (find-by-testid tree "rf-causa-machines-canvas-picker-empty"))
            "picker shows empty state")
        (is (some? (find-by-testid tree "rf-causa-machines-canvas-no-selection"))
            "canvas pane shows no-selection state")
        (is (nil? (find-by-testid tree "rf-causa-machines-canvas-picker-rows"))
            "picker rows container NOT rendered when empty")
        ;; rf2-ezx8w · spec/021 §17.1.5 — per-panel header icon.
        (is (some? (find-by-testid tree "rf-causa-machines-canvas-panel-icon"))
            "panel header icon (◆ in :green) present")))))

;; ---- 2. populated state ------------------------------------------------

(deftest panel-renders-picker-rows-when-machines-registered
  (testing "machines registered → picker carries one row per machine +
            canvas pane mounts the Chart for the (default) first machine"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:traffic :door])
      (override-definitions! {:traffic (minimal-definition)
                              :door    (minimal-definition)})
      (let [tree (machines-canvas-panel/Panel)
            picker-rows (find-by-testid tree "rf-causa-machines-canvas-picker-rows")
            row-buttons (find-all-by-testid-prefix
                          tree "rf-causa-machines-canvas-picker-row-")]
        (is (some? picker-rows)
            "picker rows container rendered when machines are registered")
        (is (= 2 (count row-buttons))
            "one row per registered machine")
        (is (some? (find-by-testid tree "rf-causa-machines-canvas-body"))
            "canvas body wrapper rendered for the default-picked machine")
        (is (nil? (find-by-testid tree "rf-causa-machines-canvas-no-selection"))
            "no-selection stub gone once a machine is auto-picked")))))

;; ---- 3. selection updates the slot -------------------------------------

(deftest select-event-updates-selected-id-slot
  (testing ":rf.causa.machines-canvas/select writes the selected-id slot"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:traffic :door])
      (override-definitions! {:traffic (minimal-definition)
                              :door    (minimal-definition)})
      (rf/dispatch-sync [:rf.causa.machines-canvas/select :door])
      (is (= :door @(rf/subscribe [:rf.causa.machines-canvas/selected-id]))
          "selected-id sub reflects the dispatched selection")
      (let [tree (machines-canvas-panel/Panel)
            sec  (find-by-testid tree "rf-causa-machines-canvas")]
        (is (= ":door"
               (-> sec second :data-selected-machine-id))
            "panel root's data-selected-machine-id attribute reflects selection")))))

;; ---- 4. no-definition fallback -----------------------------------------

(deftest panel-renders-no-definition-state-when-machine-has-no-spec
  (testing "machine registered but `(rf/machine-meta id)` returns nil →
            canvas pane shows no-definition state, no Chart host"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines! [:headless])
      ;; Empty definitions override — :headless registered without a spec.
      (override-definitions! {})
      (let [tree (machines-canvas-panel/Panel)]
        (is (some? (find-by-testid tree "rf-causa-machines-canvas-no-definition"))
            "no-definition stub rendered")
        (is (nil? (find-by-testid tree "rf-causa-machine-canvas-host"))
            "Chart adapter host NOT rendered without a definition")))))
