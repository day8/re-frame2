(ns day8.re-frame2-causa.static.machines.panel-cljs-test
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

    6. Instances pill click dispatches three events: set-mode :runtime
       + select-tab :machines + select-machine-id.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's hiccup
  tree by data-testid rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.machines.instances-jump :as jump]
            [day8.re-frame2-causa.static.machines.panel :as panel]
            [day8.re-frame2-causa.static.machines.persistence :as ls]
            [day8.re-frame2-causa.static.persistence :as static-persistence]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (config/set-static-mode-enabled! nil)
  (static-persistence/clear!)
  (ls/clear!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker ------------------------------------------------------

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- text-nodes [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

;; ---- helpers ------------------------------------------------------------

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync ev)))

(defn- seed-machines!
  "Drive the `:rf.causa/registered-machines-override` test seam so the
  browse-all sub composes against a known set."
  [ids]
  (frame-dispatch [:rf.causa/set-registered-machines-override-for-test
                   (vec ids)]))

(defn- seed-definitions! [defs]
  (frame-dispatch [:rf.causa/set-machine-definitions-override-for-test defs]))

(defn- seed-snapshots! [snaps]
  (frame-dispatch [:rf.causa/set-machine-snapshots-override-for-test snaps]))

;; -------------------------------------------------------------------------
;; (1) Mount via the shell detail-panel
;; -------------------------------------------------------------------------

(deftest static-shell-mounts-machines-panel-on-machines-tab
  (testing "Selecting the :machines sub-tab mounts the Static Machines
            panel (replaces the placeholder from rf2-o5f5f.1)"
    (causa-setup!)
    (seed-machines! [:m/a :m/b])
    (rf/with-frame :rf/causa
      (let [tree (static-shell/surface)]
        (is (some? (find-by-testid tree "rf-causa-static-machines-panel"))
            "panel mounts on default :machines tab")
        ;; Placeholder card MUST be gone now that the panel is live.
        (is (nil? (find-by-testid tree "rf-causa-static-placeholder-machines"))
            "placeholder no longer mounts")))))

;; -------------------------------------------------------------------------
;; (2) Browse-list renders one row per registered machine
;; -------------------------------------------------------------------------

(deftest browse-list-renders-one-row-per-machine
  (testing "Each registered machine surfaces as a clickable row"
    (causa-setup!)
    (seed-machines! [:foo/login :foo/checkout :bar/upload])
    (seed-definitions! {:foo/login    {:states {:a {} :b {}}}
                        :foo/checkout {:states {:a {} :b {} :c {}}}
                        :bar/upload   {:states {:x {}}}})
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)
            rows (find-all-by-testid-prefix tree "rf-causa-static-machines-row-")
            ;; Filter rows-only — the row testid prefix matches the
            ;; per-row id chips too, so we keep only the outer row buttons
            ;; (they carry `:data-machine-id` on the attrs map).
            row-buttons (filter #(some? (:data-machine-id (second %))) rows)]
        (is (= 3 (count row-buttons)) "one row per machine")))))

(deftest browse-list-empty-state-when-no-machines
  (testing "Empty state renders when (rf/machines) returns nothing"
    (causa-setup!)
    (seed-machines! [])
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)]
        (is (some? (find-by-testid tree "rf-causa-static-machines-empty"))
            "empty-state card present")
        (is (re-find #"No machines registered"
                     (text-nodes (find-by-testid tree
                                                 "rf-causa-static-machines-empty"))))))))

;; -------------------------------------------------------------------------
;; (3) Search filters the rows
;; -------------------------------------------------------------------------

(deftest search-narrows-the-row-list
  (testing "set-search filters rows; clear-search restores them"
    (causa-setup!)
    (seed-machines! [:foo/login :foo/checkout :bar/upload])
    (frame-dispatch [:rf.causa.static.machines/set-search "foo"])
    (rf/with-frame :rf/causa
      (let [{:keys [visible total]} @(rf/subscribe [:rf.causa.static.machines/data])]
        (is (= 2 visible) "two foo/* machines match")
        (is (= 3 total))))
    (frame-dispatch [:rf.causa.static.machines/clear-search])
    (rf/with-frame :rf/causa
      (let [{:keys [visible]} @(rf/subscribe [:rf.causa.static.machines/data])]
        (is (= 3 visible) "clear-search restores all rows")))))

;; -------------------------------------------------------------------------
;; (4) Sort cycle
;; -------------------------------------------------------------------------

(deftest sort-cycles-through-three-axes
  (testing "cycle-sort walks :name → :states → :live → :name"
    (causa-setup!)
    (is (= :name (frame-sub [:rf.causa.static.machines/sort-key]))
        "default :name")
    (frame-dispatch [:rf.causa.static.machines/cycle-sort])
    (is (= :states (frame-sub [:rf.causa.static.machines/sort-key])))
    (frame-dispatch [:rf.causa.static.machines/cycle-sort])
    (is (= :live (frame-sub [:rf.causa.static.machines/sort-key])))
    (frame-dispatch [:rf.causa.static.machines/cycle-sort])
    (is (= :name (frame-sub [:rf.causa.static.machines/sort-key])))))

;; -------------------------------------------------------------------------
;; (5) Selection lifecycle
;; -------------------------------------------------------------------------

(deftest selection-defaults-to-first-row
  (causa-setup!)
  (seed-machines! [:m/a :m/b :m/c])
  (rf/with-frame :rf/causa
    (let [{:keys [selected-id]} @(rf/subscribe [:rf.causa.static.machines/data])]
      ;; Sort default is :name; the first sorted row is :m/a.
      (is (= :m/a selected-id)
          "default selection is the first sorted row"))))

(deftest select-event-flips-the-slot
  (causa-setup!)
  (seed-machines! [:m/a :m/b :m/c])
  (frame-dispatch [:rf.causa.static.machines/select :m/c])
  (rf/with-frame :rf/causa
    (let [{:keys [selected-id]} @(rf/subscribe [:rf.causa.static.machines/data])]
      (is (= :m/c selected-id)))))

;; -------------------------------------------------------------------------
;; (6) Detail header
;; -------------------------------------------------------------------------

(deftest detail-header-renders-canonical-shape
  (causa-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {} :b {} :c {}}
                            :source-coord {:file "src/a.cljs" :line 7}}})
  (seed-snapshots! {:m/a {:state :a}})
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)]
      (is (some? (find-by-testid tree "rf-causa-static-machines-detail-header")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-detail-title")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-detail-source-coord")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-detail-state-count")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-detail-live-count")))
      (let [text (text-nodes (find-by-testid tree "rf-causa-static-machines-detail-state-count"))]
        (is (re-find #"3 states" text)))
      (let [text (text-nodes (find-by-testid tree "rf-causa-static-machines-detail-live-count"))]
        (is (re-find #"1 live" text))))))

(deftest detail-header-degrades-when-source-coord-missing
  (causa-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {}}}}) ;; no :source-coord
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)]
      (is (nil? (find-by-testid tree "rf-causa-static-machines-detail-source-coord"))
          "source-coord chip is suppressed when the slot is missing"))))

;; -------------------------------------------------------------------------
;; (7) 4-mode sub-strip
;; -------------------------------------------------------------------------

(deftest sub-strip-renders-four-pills
  (causa-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)]
      (is (some? (find-by-testid tree "rf-causa-static-machines-pill-topology")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-pill-sim")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-pill-instances")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-pill-cascade"))))))

(deftest sub-strip-default-is-topology
  (causa-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)
          pill (find-by-testid tree "rf-causa-static-machines-pill-topology")]
      (is (= "true" (:aria-selected (second pill)))
          "Topology is the default active pill"))))

(deftest sub-strip-set-sub-mode-flips-the-active-pill
  (causa-setup!)
  (seed-machines! [:m/a])
  (frame-dispatch [:rf.causa.static.machines/set-sub-mode :m/a :sim])
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)
          sim  (find-by-testid tree "rf-causa-static-machines-pill-sim")
          topo (find-by-testid tree "rf-causa-static-machines-pill-topology")]
      (is (= "true"  (:aria-selected (second sim))))
      (is (= "false" (:aria-selected (second topo)))))))

;; -------------------------------------------------------------------------
;; (8) Cascade dimmed
;; -------------------------------------------------------------------------

(deftest cascade-pill-is-disabled
  (causa-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)
          pill (find-by-testid tree "rf-causa-static-machines-pill-cascade")
          attrs (second pill)]
      (is (= true (:disabled attrs))
          "Cascade button is disabled")
      (is (= "true" (:aria-disabled attrs))
          "aria-disabled=true for screen readers")
      (is (re-find #"Runtime-only" (or (:title attrs) ""))
          "tooltip surfaces 'Runtime-only' message"))))

;; -------------------------------------------------------------------------
;; (9) Sim body (rf2-r4nao — rehosted Sim machinery replaces the
;;    rf2-o5f5f.2 placeholder)
;; -------------------------------------------------------------------------

(deftest sim-mode-renders-real-sim-body-with-no-definition-hint
  (testing "When the selected machine has no introspectable definition,
            the Sim body renders the no-definition hint rather than the
            old `rf2-r4nao will fill this` placeholder."
    (causa-setup!)
    (seed-machines! [:m/a])
    (frame-dispatch [:rf.causa.static.machines/set-sub-mode :m/a :sim])
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)]
        ;; The old placeholder is gone.
        (is (nil? (find-by-testid
                    tree "rf-causa-static-machines-sim-placeholder"))
            "old placeholder card no longer mounts")
        ;; The real Sim body is mounted; no-definition variant since
        ;; the test fixture seeds no :states map.
        (is (some? (find-by-testid
                     tree "rf-causa-static-machines-sim-no-definition"))
            "real Sim body's no-definition hint mounts in :sim mode")))))

(deftest sim-mode-auto-starts-sim-when-definition-present
  (testing "Selecting :sim mode for a machine with a definition auto-
            starts the hermetic sim; the rail mounts in the body."
    (causa-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :data    {:counter 0}
                              :states  {:idle {:on {:start :running}}
                                        :running {}}}})
    ;; Explicit select — in production the click on a row dispatches
    ;; :select; in this test we mirror that so the sim-state sub (which
    ;; reads the raw selected-id slot) targets :m/a.
    (frame-dispatch [:rf.causa.static.machines/select :m/a])
    (frame-dispatch [:rf.causa.static.machines/set-sub-mode :m/a :sim])
    ;; Drive the sim-start the body's auto-start would dispatch async,
    ;; via dispatch-sync so the slot lands before the assertions read
    ;; back the rendered tree. The test asserts the *contract* (when
    ;; sim-state is populated, the body wraps in the rail mount) — the
    ;; body's own dispatch is exercised by the unit-level test in
    ;; `sim_cljs_test.cljs` (`body-auto-starts-sim-when-definition-
    ;; present`).
    (rf/with-frame :rf/causa
      (frame-dispatch [:rf.causa.static.machines/sim-start
                       {:machine-id :m/a
                        :definition {:initial :idle
                                     :data    {:counter 0}
                                     :states  {:idle {:on {:start :running}}
                                               :running {}}}}])
      (let [tree (panel/panel)]
        (is (some? (find-by-testid tree "rf-causa-static-machines-sim-body"))
            "real Sim body wrapper mounts")
        (is (some? (find-by-testid tree "rf-causa-static-machines-sim-rail"))
            "Sim rail mounts when sim-state is populated")))))

;; -------------------------------------------------------------------------
;; (10) Instances JUMP — verify dispatches land
;; -------------------------------------------------------------------------

(deftest instances-jump-flips-mode-tab-and-selection
  (testing "Calling the JUMP fn dispatches the three events. Verifies
            the post-dispatch state in app-db."
    (causa-setup!)
    (seed-machines! [:m/a :m/b])
    ;; Start from a known state — :static + :events + nothing selected.
    (frame-dispatch [:rf.causa/set-mode :static])
    (frame-dispatch [:rf.causa/select-tab :events])
    (rf/with-frame :rf/causa
      (is (= :static (frame-sub [:rf.causa/mode])))
      (is (= :events (frame-sub [:rf.causa/selected-tab]))))
    ;; Fire the JUMP via the dispatcher helper. Three dispatches land.
    ;; Use the sync variant so post-dispatch assertions can read the
    ;; new slots without an event-queue flush.
    (rf/with-frame :rf/causa
      (jump/dispatch-jump-sync! :m/b))
    (rf/with-frame :rf/causa
      (is (= :runtime (frame-sub [:rf.causa/mode]))
          ":rf.causa/set-mode :runtime fired")
      (is (= :machines (frame-sub [:rf.causa/selected-tab]))
          ":rf.causa/select-tab :machines fired")
      (is (= :m/b (frame-sub [:rf.causa/selected-machine-id]))
          ":rf.causa/select-machine-id <mid> fired"))))

;; -------------------------------------------------------------------------
;; (11) Topology mode — chart mounts when a definition is present
;; -------------------------------------------------------------------------

(deftest topology-mode-mounts-chart-when-definition-present
  (causa-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:initial :idle
                            :states  {:idle {} :done {}}}})
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)]
      (is (some? (find-by-testid tree "rf-causa-static-machines-topology"))
          "Topology mode mounts as the default body")
      (is (some? (find-by-testid tree "rf-causa-static-machines-topology-chart"))
          "chart wrapper mounts")
      ;; The SVG itself is rendered by chart-svg/render with the testid
      ;; we passed in.
      (is (some? (find-by-testid tree "rf-causa-static-machines-topology-svg"))
          "SVG primitive mounts"))))

(deftest topology-mode-shows-no-definition-hint-when-missing
  (causa-setup!)
  (seed-machines! [:m/a])
  ;; No definition seeded — machine-definitions sub returns {}
  (seed-definitions! {})
  (rf/with-frame :rf/causa
    (let [tree (panel/panel)]
      (is (some? (find-by-testid tree
                                 "rf-causa-static-machines-topology-no-definition"))))))

;; -------------------------------------------------------------------------
;; (11b) Topology mode — interactive canvas adapter (rf2-md9oz)
;; -------------------------------------------------------------------------

(deftest topology-mode-wraps-chart-in-canvas-host
  (testing "rf2-md9oz — Static Topology body delegates to
            machine-canvas/Chart so users get zoom / pan / fit."
    (causa-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}}})
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)]
        (is (some? (find-by-testid tree "rf-causa-machine-canvas-host"))
            "the chart is now wrapped in the interactive canvas-host")
        (is (some? (find-by-testid tree "rf-causa-machine-canvas-toolbar"))
            "controls toolbar (zoom / pan / fit) is present on static too")
        ;; The inner SVG testid still resolves so existing static-panel
        ;; selectors keep working.
        (is (some? (find-by-testid tree "rf-causa-static-machines-topology-svg"))
            ":testid forwards through Chart to the SVG primitive")))))

(deftest topology-mode-omits-view-mode-toggle-on-static
  (testing "rf2-md9oz — Static surface already owns the per-machine
            sub-mode pill strip at L3; the canvas's Canvas/List toggle
            is meaningless on static and must NOT mount."
    (causa-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}}})
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)]
        (is (nil? (find-by-testid tree
                                  "rf-causa-machine-canvas-view-mode-toggle"))
            "view-mode toggle is suppressed on static via
             :show-view-mode-toggle? false")))))

(deftest topology-mode-keeps-popout-and-source-coord-affordances
  (testing "The Static panel's existing 'open in popout' affordance +
            source-coord chip still live in the chart-toolbar ABOVE
            the canvas — they did not absorb into the canvas's own
            controls toolbar."
    (causa-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a {:initial :idle
                              :states  {:idle {} :done {}}
                              :source-coord {:file "src/m_a.cljs" :line 12}}})
    (rf/with-frame :rf/causa
      (let [tree (panel/panel)]
        (is (some? (find-by-testid tree
                                   "rf-causa-static-machines-topology-toolbar"))
            "static chart-toolbar still mounts above the canvas")
        (is (some? (find-by-testid tree
                                   "rf-causa-static-machines-topology-popout"))
            "Pop-out affordance still present")))))

;; -------------------------------------------------------------------------
;; (12) Public install — install-fx + hydrate
;; -------------------------------------------------------------------------

(deftest install-registers-subs-and-events
  (testing "install! is called transitively via register-causa-handlers!
            so the subs + events are registered after setup"
    (causa-setup!)
    ;; Every key sub resolves without throwing.
    (is (= "" (frame-sub [:rf.causa.static.machines/search])))
    (is (= :name (frame-sub [:rf.causa.static.machines/sort-key])))
    (is (= :topology (frame-sub [:rf.causa.static.machines/sub-mode :any/id])))))

;; -------------------------------------------------------------------------
;; (13) Tab inventory still names the bead
;; -------------------------------------------------------------------------

(deftest static-tab-inventory-machines-bead
  (testing "the :machines tab still carries its bead id even after the
            placeholder is replaced by the live panel"
    (is (= "rf2-o5f5f.2"
           (-> (some #(when (= :machines (:id %)) %)
                     (static-shell/tabs))
               :placeholder-bead)))))
