(ns day8.re-frame2-xray.static.machines.panel-cljs-test
  "CLJS wiring + render tests for the Static Machines sub-tab panel
  (rf2-o5f5f.2).

  ## What's under test

    1. The panel mounts as the L4 detail panel for the `:machines`
       sub-tab — previously a placeholder card, now the real master-
       detail surface.

    2. Browse-list renders one row per registered machine; search
       filters incrementally; sort cycles through Name/States/Live.

    3. Detail header renders the canonical 4-cell shape: machine-id ·
       source-coord ↗ · N states · M live.

    4. 4-mode sub-strip pills render and dispatch the right
       events (Topology / Sim / Instances / Cascade).

    5. Cascade pill is dimmed (disabled attribute set + dashed border
       in style); clicking it does nothing.

    6. Instances pill click dispatches three events: set-mode :dynamic
       + select-tab :machines + select-machine-id.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's hiccup
  tree by data-testid rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.instances-jump :as jump]
            [day8.re-frame2-xray.static.machines.panel :as panel]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier, which already resets the trace-collector rings the old
  ;; init reset a SECOND time) into one owner; `:post-reset` carries the
  ;; suppressed-count + static-persistence + machines-localStorage slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!)
                   (ls/clear!))}))

;; ---- hiccup walker ------------------------------------------------------
;; The private expand-tree / hiccup-seq / find-by-testid / find-all-by-testid-
;; prefix / text-nodes copies were semantically identical to
;; `re-frame.test-helpers`; tests call `rf.test-helpers/find-by-testid`,
;; `rf.test-helpers/find-by-testid-prefix` and `rf.test-helpers/text-content` directly (rf2-vj80u8 — no
;; Xray walker facade).

;; ---- helpers ------------------------------------------------------------

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- seed-machines!
  "Drive the `:rf.xray/registered-machines-override` test seam so the
  browse-all sub composes against a known set."
  [ids]
  (frame-dispatch [:rf.xray/set-registered-machines-override-for-test
                   (vec ids)]))

(defn- seed-definitions! [defs]
  (frame-dispatch [:rf.xray/set-machine-definitions-override-for-test defs]))

(defn- seed-snapshots! [snaps]
  (frame-dispatch [:rf.xray/set-machine-snapshots-override-for-test snaps]))

;; -------------------------------------------------------------------------
;; (1) Mount via the shell detail-panel
;; -------------------------------------------------------------------------

(deftest static-shell-mounts-machines-panel-on-machines-tab
  (testing "Selecting the :machines sub-tab mounts the Static Machines
            panel (replaces the placeholder from rf2-o5f5f.1)"
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    (rf/with-frame :rf/xray
      (let [tree (static-shell/surface)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-panel"))
            "panel mounts on default :machines tab")
        ;; Placeholder card MUST be gone now that the panel is live.
        (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-placeholder-machines"))
            "placeholder no longer mounts")))))

;; -------------------------------------------------------------------------
;; (2) Browse-list renders one row per registered machine
;; -------------------------------------------------------------------------

(deftest browse-list-renders-one-row-per-machine
  (testing "Each registered machine surfaces as a clickable row"
    (xray-setup!)
    (seed-machines! [:foo/login :foo/checkout :bar/upload])
    (seed-definitions! {:foo/login    {:states {:a {} :b {}}}
                        :foo/checkout {:states {:a {} :b {} :c {}}}
                        :bar/upload   {:states {:x {}}}})
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)
            rows (rf.test-helpers/find-by-testid-prefix tree "rf-xray-static-machines-row-")
            ;; Filter rows-only — the row testid prefix matches the
            ;; per-row id chips too, so we keep only the outer row buttons
            ;; (they carry `:data-machine-id` on the attrs map).
            row-buttons (filter #(some? (:data-machine-id (second %))) rows)]
        (is (= 3 (count row-buttons)) "one row per machine")))))

(deftest browse-list-empty-state-when-no-machines
  (testing "Empty state renders when (rf.machines/machines) returns nothing"
    (xray-setup!)
    (seed-machines! [])
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-empty"))
            "empty-state card present")
        (is (re-find #"No machines registered"
                     (rf.test-helpers/text-content (rf.test-helpers/find-by-testid tree
                                                 "rf-xray-static-machines-empty"))))))))

;; -------------------------------------------------------------------------
;; (3) Search filters the rows
;; -------------------------------------------------------------------------

(deftest search-narrows-the-row-list
  (testing "set-search filters rows; clear-search restores them"
    (xray-setup!)
    (seed-machines! [:foo/login :foo/checkout :bar/upload])
    (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
    (rf/with-frame :rf/xray
      (let [{:keys [visible total]} @(rf/subscribe [:rf.xray.static.machines/data])]
        (is (= 2 visible) "two foo/* machines match")
        (is (= 3 total))))
    (frame-dispatch [:rf.xray.static.machines/clear-search])
    (rf/with-frame :rf/xray
      (let [{:keys [visible]} @(rf/subscribe [:rf.xray.static.machines/data])]
        (is (= 3 visible) "clear-search restores all rows")))))

;; -------------------------------------------------------------------------
;; (4) Sort cycle
;; -------------------------------------------------------------------------

(deftest sort-cycles-through-three-axes
  (testing "cycle-sort walks :name → :states → :live → :name"
    (xray-setup!)
    (is (= :name (frame-sub [:rf.xray.static.machines/sort-key]))
        "default :name")
    (frame-dispatch [:rf.xray.static.machines/cycle-sort])
    (is (= :states (frame-sub [:rf.xray.static.machines/sort-key])))
    (frame-dispatch [:rf.xray.static.machines/cycle-sort])
    (is (= :live (frame-sub [:rf.xray.static.machines/sort-key])))
    (frame-dispatch [:rf.xray.static.machines/cycle-sort])
    (is (= :name (frame-sub [:rf.xray.static.machines/sort-key])))))

;; -------------------------------------------------------------------------
;; (5) Selection lifecycle
;; -------------------------------------------------------------------------

(deftest selection-defaults-to-first-row
  (xray-setup!)
  (seed-machines! [:m/a :m/b :m/c])
  (rf/with-frame :rf/xray
    (let [{:keys [selected-id]} @(rf/subscribe [:rf.xray.static.machines/data])]
      ;; Sort default is :name; the first sorted row is :m/a.
      (is (= :m/a selected-id)
          "default selection is the first sorted row"))))

(deftest select-event-flips-the-slot
  (xray-setup!)
  (seed-machines! [:m/a :m/b :m/c])
  (frame-dispatch [:rf.xray.static.machines/select :m/c])
  (rf/with-frame :rf/xray
    (let [{:keys [selected-id]} @(rf/subscribe [:rf.xray.static.machines/data])]
      (is (= :m/c selected-id)))))

;; -------------------------------------------------------------------------
;; (6) Detail header
;; -------------------------------------------------------------------------

(deftest detail-header-renders-canonical-shape
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {} :b {} :c {}}
                            :source-coord {:file "src/a.cljs" :line 7}}})
  (seed-snapshots! {:m/a {:state :a}})
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-header")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-title")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-source-coord")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-state-count")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-live-count")))
      (let [text (rf.test-helpers/text-content (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-state-count"))]
        (is (re-find #"3 states" text)))
      (let [text (rf.test-helpers/text-content (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-live-count"))]
        (is (re-find #"1 live" text))))))

(deftest detail-header-degrades-when-source-coord-missing
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {}}}}) ;; no :source-coord
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-source-coord"))
          "source-coord chip is suppressed when the slot is missing"))))

;; -------------------------------------------------------------------------
;; (7) 4-mode sub-strip
;; -------------------------------------------------------------------------

(deftest sub-strip-renders-four-pills
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-topology")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-sim")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-instances")))
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-cascade"))))))

(deftest sub-strip-default-is-topology
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          pill (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-topology")]
      (is (= "true" (:aria-selected (second pill)))
          "Topology is the default active pill"))))

(deftest sub-strip-set-sub-mode-flips-the-active-pill
  (xray-setup!)
  (seed-machines! [:m/a])
  (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          sim  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-sim")
          topo (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-topology")]
      (is (= "true"  (:aria-selected (second sim))))
      (is (= "false" (:aria-selected (second topo)))))))

;; -------------------------------------------------------------------------
;; (8) Cascade dimmed
;; -------------------------------------------------------------------------

(deftest cascade-pill-is-disabled
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          pill (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-pill-cascade")
          attrs (second pill)]
      (is (= true (:disabled attrs))
          "Cascade button is disabled")
      (is (= "true" (:aria-disabled attrs))
          "aria-disabled=true for screen readers")
      (is (re-find #"Dynamic-only" (or (:title attrs) ""))
          "tooltip surfaces 'Dynamic-only' message"))))

;; -------------------------------------------------------------------------
;; (9) Sim body (rf2-r4nao — rehosted Sim machinery replaces the
;;    rf2-o5f5f.2 placeholder)
;; -------------------------------------------------------------------------

(deftest sim-mode-renders-real-sim-body-with-no-definition-hint
  (testing "When the selected machine has no introspectable definition,
            the Sim body renders the no-definition hint rather than the
            old `rf2-r4nao will fill this` placeholder."
    (xray-setup!)
    (seed-machines! [:m/a])
    (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        ;; The old placeholder is gone.
        (is (nil? (rf.test-helpers/find-by-testid
                    tree "rf-xray-static-machines-sim-placeholder"))
            "old placeholder card no longer mounts")
        ;; The real Sim body is mounted; no-definition variant since
        ;; the test fixture seeds no :states map.
        (is (some? (rf.test-helpers/find-by-testid
                     tree "rf-xray-static-machines-sim-no-definition"))
            "real Sim body's no-definition hint mounts in :sim mode")))))

(deftest sim-mode-auto-starts-sim-when-definition-present
  (testing "Selecting :sim mode for a machine with a definition auto-
            starts the hermetic sim; the rail mounts in the body."
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :data    {:counter 0}
                              :states  {:idle {:on {:start :running}}
                                        :running {}}}})
    ;; Explicit select — in production the click on a row dispatches
    ;; :select; in this test we mirror that so the sim-state sub (which
    ;; reads the raw selected-id slot) targets :m/a.
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
    ;; Drive the sim-start the body's auto-start would dispatch async,
    ;; via dispatch-sync so the slot lands before the assertions read
    ;; back the rendered tree. The test asserts the *contract* (when
    ;; sim-state is populated, the body wraps in the rail mount) — the
    ;; body's own dispatch is exercised by the unit-level test in
    ;; `sim_cljs_test.cljs` (`body-auto-starts-sim-when-definition-
    ;; present`).
    (rf/with-frame :rf/xray
      (frame-dispatch [:rf.xray.static.machines/sim-start
                       {:machine-id :m/a
                        :definition {:initial :idle
                                     :data    {:counter 0}
                                     :states  {:idle {:on {:start :running}}
                                               :running {}}}}])
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sim-body"))
            "real Sim body wrapper mounts")
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sim-rail"))
            "Sim rail mounts when sim-state is populated")))))

;; -------------------------------------------------------------------------
;; (10) Instances JUMP — verify dispatches land
;; -------------------------------------------------------------------------

(deftest instances-jump-flips-mode-tab-and-selection
  (testing "Calling the JUMP fn dispatches the three events. Verifies
            the post-dispatch state in app-db."
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    ;; Start from a known state — :static + :events + nothing selected.
    (frame-dispatch [:rf.xray/set-mode :static])
    (frame-dispatch [:rf.xray/select-tab :events])
    (rf/with-frame :rf/xray
      (is (= :static (frame-sub [:rf.xray/mode])))
      (is (= :events (frame-sub [:rf.xray/selected-tab]))))
    ;; Fire the JUMP via the dispatcher helper. Three dispatches land.
    ;; Use the sync variant so post-dispatch assertions can read the
    ;; new slots without an event-queue flush.
    (rf/with-frame :rf/xray
      (jump/dispatch-jump-sync! :m/b))
    (rf/with-frame :rf/xray
      (is (= :dynamic (frame-sub [:rf.xray/mode]))
          ":rf.xray/set-mode :dynamic fired")
      (is (= :machines (frame-sub [:rf.xray/selected-tab]))
          ":rf.xray/select-tab :machines fired")
      (is (= :m/b (frame-sub [:rf.xray/selected-machine-id]))
          ":rf.xray/select-machine-id <mid> fired"))))

;; -------------------------------------------------------------------------
;; (11) Topology mode — chart mounts when a definition is present
;; -------------------------------------------------------------------------

(deftest topology-mode-mounts-chart-when-definition-present
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:initial :idle
                            :states  {:idle {} :done {}}}})
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-topology"))
          "Topology mode mounts as the default body")
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-topology-chart"))
          "chart wrapper mounts")
      ;; The SVG itself is rendered by chart-svg/render with the testid
      ;; we passed in.
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-topology-svg"))
          "SVG primitive mounts"))))

(deftest topology-mode-shows-no-definition-hint-when-missing
  (xray-setup!)
  (seed-machines! [:m/a])
  ;; No definition seeded — machine-definitions sub returns {}
  (seed-definitions! {})
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (some? (rf.test-helpers/find-by-testid tree
                                 "rf-xray-static-machines-topology-no-definition"))))))

;; -------------------------------------------------------------------------
;; (11b) Topology mode — interactive canvas adapter (rf2-md9oz)
;; -------------------------------------------------------------------------

(deftest topology-mode-wraps-chart-in-canvas-host
  (testing "rf2-md9oz — Static Topology body delegates to
            machine-canvas/Chart so users get zoom / pan / fit."
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}}})
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-machine-canvas-host"))
            "the chart is now wrapped in the interactive canvas-host")
        ;; rf2-gpzb4 (xyflow migration): the host-side controls toolbar
        ;; is gone — xyflow renders its own `<Controls>` component
        ;; inside the chart. The inner-testid still threads through
        ;; via the `:inner-testid` prop so existing static-panel
        ;; selectors keep working.
        (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-topology-svg"))
            ":inner-testid forwards through Chart to the xyflow root")))))

(deftest topology-mode-omits-view-mode-toggle-on-static
  (testing "rf2-48fwsi — the vestigial Canvas/List view-mode toggle was
            removed framework-wide (it was dead after rf2-g2axio); it
            must NOT mount on the Static surface either. (Pre-rf2-48fwsi
            Static suppressed it via :show-view-mode-toggle? false; now
            no Chart caller can render it at all.)"
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}}})
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (nil? (rf.test-helpers/find-by-testid tree
                                  "rf-xray-machine-canvas-view-mode-toggle"))
            "the retired view-mode toggle never mounts on static")))))

(deftest topology-mode-keeps-popout-and-source-coord-affordances
  (testing "The Static panel's existing 'open in popout' affordance +
            source-coord chip still live in the chart-toolbar ABOVE
            the canvas — they did not absorb into the canvas's own
            controls toolbar."
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}
                              :source-coord {:file "src/m_a.cljs" :line 12}}})
    (rf/with-frame :rf/xray
      (let [tree (panel/panel)]
        (is (some? (rf.test-helpers/find-by-testid tree
                                   "rf-xray-static-machines-topology-toolbar"))
            "static chart-toolbar still mounts above the canvas")
        (is (some? (rf.test-helpers/find-by-testid tree
                                   "rf-xray-static-machines-topology-popout"))
            "Pop-out affordance still present")))))

;; -------------------------------------------------------------------------
;; (12) Public install — install-fx + hydrate
;; -------------------------------------------------------------------------

(deftest install-registers-subs-and-events
  (testing "install! is called transitively via register-xray-handlers!
            so the subs + events are registered after setup"
    (xray-setup!)
    ;; Every key sub resolves without throwing.
    (is (= "" (frame-sub [:rf.xray.static.machines/search])))
    (is (= :name (frame-sub [:rf.xray.static.machines/sort-key])))
    (is (= :topology (frame-sub [:rf.xray.static.machines/sub-mode :any/id])))))

;; rf2-sdqsla — the `static-tab-inventory-machines-bead` test was removed:
;; the `:placeholder-bead` slot was dropped once the rf2-o5f5f roll-out
;; completed (every Static tab ships a real panel). Inventory shape is now
;; covered by `static-tab-inventory-shape` in the shell test.
