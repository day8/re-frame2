(ns day8.re-frame2-xray.static.machines.panel-cljs-test
  "CLJS wiring + render tests for the Static Machines sub-tab panel.

  ## What's under test

    1. Detail header: source-coord chip, N states, M live.

    2. The sub-strip's active pill and the body follow the per-machine
       sub-mode, including the no-definition hints.

    3. The Sim and Topology bodies.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's hiccup
  tree by data-testid rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
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
;; (1) Detail header
;; -------------------------------------------------------------------------

(deftest detail-header-renders-canonical-shape
  ;; A real registration: the source chip reads the coord `reg-machine`
  ;; records on the registration metadata.
  (xray-setup!)
  (rf/reg-machine :m/a {:initial :a :states {:a {} :b {} :c {}}})
  (seed-machines! [:m/a])
  (seed-snapshots! {:m/a {:state :a}})
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)
          text #(rf.test-helpers/text-content (rf.test-helpers/find-by-testid tree %))]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-detail-source-coord")))
      (is (= ["3 states" "1 live"]
             (mapv text ["rf-xray-static-machines-detail-state-count"
                         "rf-xray-static-machines-detail-live-count"]))))))

;; -------------------------------------------------------------------------
;; (2) Sub-strip
;; -------------------------------------------------------------------------

(deftest sub-strip-set-sub-mode-flips-the-active-pill-and-the-body
  ;; With no definition registered, each body shows its own hint.
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-definitions! {})
  (rf/with-frame :rf/xray
    (is (some? (rf.test-helpers/find-by-testid
                 (machines-tree/panel-tree)
                 "rf-xray-static-machines-topology-no-definition"))))
  (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
  (rf/with-frame :rf/xray
    (let [tree (machines-tree/panel-tree)]
      (is (= ["true" "false"]
             (mapv #(:aria-selected (second (rf.test-helpers/find-by-testid tree %)))
                   ["rf-xray-static-machines-pill-sim"
                    "rf-xray-static-machines-pill-topology"])))
      (is (some? (rf.test-helpers/find-by-testid
                   tree "rf-xray-static-machines-sim-no-definition"))))))

;; -------------------------------------------------------------------------
;; (3) Sim body
;; -------------------------------------------------------------------------

(deftest sim-mode-mounts-the-sim-body-and-rail-once-sim-has-started
  ;; The rail mounts only once the sim has started; the body's own
  ;; auto-start is `sim_cljs_test`'s `body-auto-starts-sim-when-definition-present`.
  (let [definition {:initial :idle
                    :states  {:idle {:on {:start :running}}
                              :running {}}}]
    (xray-setup!)
    (seed-machines! [:m/a])
    (seed-definitions! {:m/a definition})
    ;; The sim-state sub reads the raw selected-id slot.
    (frame-dispatch [:rf.xray.static.machines/select :m/a])
    (frame-dispatch [:rf.xray.static.machines/set-sub-mode :m/a :sim])
    (frame-dispatch [:rf.xray.static.machines/sim-start
                     {:machine-id :m/a :definition definition}])
    (rf/with-frame :rf/xray
      (let [tree (machines-tree/panel-tree)]
        (is (= [] (remove #(rf.test-helpers/find-by-testid tree %)
                          ["rf-xray-static-machines-sim-body"
                           "rf-xray-static-machines-sim-chart"
                           "rf-xray-static-machines-sim-rail"]))
            "the testids missing from the Sim body")))))

;; -------------------------------------------------------------------------
;; (4) Topology body
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
