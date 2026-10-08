(ns day8.re-frame2-xray.static.machines.panel-cljs-test
  "CLJS wiring + render tests for the Static Machines sub-tab panel.

  ## What's under test

    1. The shell mounts the registry's `:machines` panel in its L4 slot.

    2. Browse-list empty state; search filters and clears; sort cycles
       through Name/States/Live.

    3. Detail header: source-coord chip (or its absence), N states,
       M live.

    4. The sub-strip's active pill follows the per-machine sub-mode.

    5. The Sim and Topology bodies, including their no-definition hints.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's hiccup
  tree by data-testid rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-helpers.static-shell-tree
             :as static-shell-tree]
            [day8.re-frame2-xray.test-helpers.static-machines-tree
             :as machines-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` owns the reset (plain-atom + `:all` tier,
  ;; which resets the trace-collector rings too); `:post-reset` carries the
  ;; suppressed-count + static-persistence + machines-localStorage slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (config/reset-suppressed-count!)
                   (static-persistence/clear!)
                   (ls/clear!))}))

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
  (testing "The row asserts ONE LEVEL UP from the panel's own testid: the
            panel is a Fresco boundary behind an `as-component` bridge, so
            the hiccup walk stops at the bridge's `[:>]` head. The shell
            owes that the `:machines` slot mounts THE REGISTRY'S `:panel`;
            that the boundary behind it paints is W1's subject in
            `panel_fresco_boundary_dom_cljs_test`."
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    (rf/with-frame :rf/xray
      (let [slot  (rf.test-helpers/find-by-testid
                    (static-shell-tree/surface-tree)
                    "rf-xray-static-detail-panel-machines")
            mount ((:panel (panel-registry/tab-by-id :static :machines)))]
        (is (some? slot)
            "the :machines L4 slot renders on the default Static tab")
        (is (= (last slot) mount)
            "the slot mounts exactly the registry's :panel value")))))

;; -------------------------------------------------------------------------
;; (2) Browse list
;; -------------------------------------------------------------------------

(deftest browse-list-empty-state-when-no-machines
  (xray-setup!)
  (seed-machines! [])
  (rf/with-frame :rf/xray
    (is (some? (rf.test-helpers/find-by-testid (machines-tree/panel-tree)
                                               "rf-xray-static-machines-empty")))))

(deftest search-narrows-the-row-list
  (xray-setup!)
  (seed-machines! [:foo/login :foo/checkout :bar/upload])
  (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
  (is (= [2 3] ((juxt :visible :total) (frame-sub [:rf.xray.static.machines/data])))
      "set-search filters to the two foo/* machines")
  (frame-dispatch [:rf.xray.static.machines/clear-search])
  (is (= 3 (:visible (frame-sub [:rf.xray.static.machines/data])))
      "clear-search restores all rows"))

(deftest sort-cycles-through-three-axes
  (xray-setup!)
  (is (= [:name :states :live :name]
         (mapv (fn [_]
                 (let [k (frame-sub [:rf.xray.static.machines/sort-key])]
                   (frame-dispatch [:rf.xray.static.machines/cycle-sort])
                   k))
               (range 4)))))

;; -------------------------------------------------------------------------
;; (3) Detail header
;; -------------------------------------------------------------------------

(deftest detail-header-renders-canonical-shape
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {} :b {} :c {}}
                            :source-coord {:file "src/a.cljs" :line 7}}})
  (seed-snapshots! {:m/a {:state :a}})
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          text #(rf.test-helpers/text-content (rf.test-helpers/find-by-testid tree %))]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-source-coord")))
      (is (= ["3 states" "1 live"]
             (mapv text ["rf-xray-static-machines-detail-state-count"
                         "rf-xray-static-machines-detail-live-count"]))))))

(deftest detail-header-degrades-when-source-coord-missing
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:states {:a {}}}}) ;; no :source-coord
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-source-coord"))
          "source-coord chip is suppressed when the slot is missing"))))

;; -------------------------------------------------------------------------
;; (4) Sub-strip
;; -------------------------------------------------------------------------

(deftest sub-strip-set-sub-mode-flips-the-active-pill
  (xray-setup!)
  (seed-machines! [:m/a])
  (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (= ["true" "false"]
             (mapv #(:aria-selected (second (rf.test-helpers/find-by-testid tree %)))
                   ["rf-xray-static-machines-pill-sim"
                    "rf-xray-static-machines-pill-topology"]))))))

;; -------------------------------------------------------------------------
;; (5) Sim body
;; -------------------------------------------------------------------------

(deftest sim-mode-renders-real-sim-body-with-no-definition-hint
  (xray-setup!)
  (seed-machines! [:m/a])
  (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
  (rf/with-frame :rf/xray
    (is (some? (rf.test-helpers/find-by-testid
                 (machines-tree/panel-tree)
                 "rf-xray-static-machines-sim-no-definition")))))

(deftest sim-mode-mounts-the-sim-body-and-rail-once-sim-has-started
  (testing "With :sim selected and the hermetic sim started, the panel
            mounts the Sim body and its rail — the rail mounts only when
            the threaded sim-state is populated. The body's own auto-start
            dispatch is `sim_cljs_test`'s
            `body-auto-starts-sim-when-definition-present`."
    (let [definition {:initial :idle
                      :data    {:counter 0}
                      :states  {:idle {:on {:start :running}}
                                :running {}}}]
      (xray-setup!)
      (seed-machines! [:m/a])
      (seed-definitions! {:m/a definition})
      ;; The sim-state sub reads the raw selected-id slot, so select
      ;; explicitly as a row click would.
      (frame-dispatch [:rf.xray.static.machines/select :m/a])
      (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
      (frame-dispatch [:rf.xray.static.machines/sim-start
                       {:machine-id :m/a :definition definition}])
      (rf/with-frame :rf/xray
        (is (some? (rf.test-helpers/find-by-testid (machines-tree/panel-tree)
                                                   "rf-xray-static-machines-sim-rail"))
            "Sim rail mounts when sim-state is populated")))))

;; -------------------------------------------------------------------------
;; (6) Topology body
;; -------------------------------------------------------------------------

(deftest topology-mode-mounts-chart-when-definition-present
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {:m/a {:initial :idle
                            :states  {:idle {} :done {}}}})
  (rf/with-frame :rf/xray
    (is (some? (rf.test-helpers/find-by-testid (machines-tree/panel-tree)
                                               "rf-xray-machine-canvas-host"))
        "the default Topology body wraps the chart in the interactive
         canvas-host (zoom / pan / fit)")))

(deftest topology-mode-shows-no-definition-hint-when-missing
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {})
  (rf/with-frame :rf/xray
    (is (some? (rf.test-helpers/find-by-testid (machines-tree/panel-tree)
                                               "rf-xray-static-machines-topology-no-definition")))))

;; Static tab inventory shape is covered by `static-tab-inventory-shape` in
;; the shell test.
