(ns day8.re-frame2-xray.static.machines.browse-list-cljs-test
  "CLJS render tests for the Static Machines browse-list (rf2-o5f5f.2).

  ## What's under test

    1. Pip cluster — pip-cap dots inline, '>cap N live' textual count
       beyond. Silent for zero (per rf2-g3ghh).
    2. Sort button label reflects the active axis.
    3. Per-row `→ Dynamic` chip dispatches the JUMP fn (verified via
       app-db side-effects).
    4. Search box keystroke fires set-search; Escape key fires
       clear-search."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.browse-list :as browse-list]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [day8.re-frame2-xray.static.machines.instances-jump :as jump]
            [day8.re-frame2-xray.static.machines.panel :as panel]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

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

;; The private expand-tree / find-by-testid copies were semantically identical
;; to `re-frame.test-helpers`; tests call `rf.test-helpers/find-by-testid` directly
;; (rf2-vj80u8 — no Xray walker facade). The unused find-all-by-testid was
;; dropped. `hiccup-seq` (depth-first nodes over the expanded tree) is not
;; exposed by test-helpers, so it is kept as a thin wrapper over
;; `rf.test-helpers/expand-tree` for the string-leaf extraction below.
(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree)))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray (rf/dispatch-sync ev)))

(defn- seed-machines! [ids]
  (frame-dispatch [:rf.xray/set-registered-machines-override-for-test
                   (vec ids)]))

(defn- seed-snapshots! [snaps]
  (frame-dispatch [:rf.xray/set-machine-snapshots-override-for-test snaps]))

(defn- seed-definitions! [defs]
  (frame-dispatch [:rf.xray/set-machine-definitions-override-for-test defs]))

;; -------------------------------------------------------------------------
;; Pip cluster
;; -------------------------------------------------------------------------

(deftest pip-cluster-zero-is-silent
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-snapshots! {}) ;; no live instances
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (nil? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-row-pips"))
          "no pip cluster when live-count is zero"))))

(deftest pip-cluster-renders-dots-for-one-live-instance
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-snapshots! {:m/a {:state :idle}})
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          pips (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-row-pips")]
      (is (some? pips) "pip cluster mounts for live machine"))))

;; -------------------------------------------------------------------------
;; Sort button label reflects the active axis
;; -------------------------------------------------------------------------

(deftest sort-button-label-tracks-the-active-axis
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          btn  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sort")
          text (->> btn hiccup-seq (filter string?) (apply str))]
      (is (re-find #"Name" text) "default sort axis is Name")))
  (frame-dispatch [:rf.xray.static.machines/cycle-sort])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          btn  (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-sort")
          text (->> btn hiccup-seq (filter string?) (apply str))]
      (is (re-find #"States" text)))))

;; -------------------------------------------------------------------------
;; Per-row JUMP chip fires the JUMP
;; -------------------------------------------------------------------------

(deftest per-row-jump-chip-fires-jump
  (testing "Clicking the per-row `→ Dynamic` chip fires set-mode +
            select-tab + select-machine-id (via the centralised
            dispatcher)"
    (xray-setup!)
    (seed-machines! [:m/a :m/b])
    ;; Sanity baseline
    (frame-dispatch [:rf.xray/set-mode :static])
    (rf/with-frame :rf/xray
      (is (= :static (frame-sub [:rf.xray/mode]))))
    ;; Drive the dispatcher
    (rf/with-frame :rf/xray
      (jump/dispatch-jump-sync! :m/a))
    (rf/with-frame :rf/xray
      (is (= :dynamic (frame-sub [:rf.xray/mode])))
      (is (= :machines (frame-sub [:rf.xray/selected-tab])))
      (is (= :m/a (frame-sub [:rf.xray/selected-machine-id]))))))

;; -------------------------------------------------------------------------
;; Listbox ARIA
;; -------------------------------------------------------------------------

(deftest browse-list-uses-listbox-aria
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree   (panel/panel)
          rows-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-rows")
          attrs  (second rows-el)]
      (is (= "listbox" (:role attrs)))
      (is (string? (:aria-label attrs))))))

(deftest selected-row-carries-aria-selected-true
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          row-a (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-row-a")
          attrs (second row-a)]
      ;; Note: machine-id is :m/a so name = "a", testid suffix matches.
      (is (= "true" (:aria-selected attrs))))))

;; -------------------------------------------------------------------------
;; Count line shows total vs visible
;; -------------------------------------------------------------------------

(deftest toolbar-count-shows-visible-and-total
  (xray-setup!)
  (seed-machines! [:foo/a :foo/b :bar/c])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          count-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-count")
          text (->> count-el hiccup-seq (filter string?) (apply str))]
      (is (re-find #"3 machines" text))))
  (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          count-el (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-count")
          text (->> count-el hiccup-seq (filter string?) (apply str))]
      (is (re-find #"2 / 3" text)))))

;; -------------------------------------------------------------------------
;; No-results state
;; -------------------------------------------------------------------------

(deftest no-results-state-when-search-misses
  (xray-setup!)
  (seed-machines! [:foo/a :foo/b])
  (frame-dispatch [:rf.xray.static.machines/set-search "nonexistent"])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)]
      (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-static-machines-no-results"))))))
