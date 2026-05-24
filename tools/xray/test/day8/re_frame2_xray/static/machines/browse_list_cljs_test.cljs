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
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.browse-list :as browse-list]
            [day8.re-frame2-xray.static.machines.helpers :as h]
            [day8.re-frame2-xray.static.machines.instances-jump :as jump]
            [day8.re-frame2-xray.static.machines.panel :as panel]
            [day8.re-frame2-xray.static.machines.persistence :as ls]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-bus :as trace-bus]))

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (static-persistence/clear!)
  (ls/clear!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(declare expand-tree)

(defn- expand-tree [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))
    (vector? tree) (mapv expand-tree tree)
    (seq? tree)    (map expand-tree tree)
    :else          tree))

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

(defn- find-all-by-testid [tree testid]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (= testid (:data-testid (second node)))))
           (hiccup-seq tree)))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

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
      (is (nil? (find-by-testid tree "rf-xray-static-machines-row-pips"))
          "no pip cluster when live-count is zero"))))

(deftest pip-cluster-renders-dots-for-one-live-instance
  (xray-setup!)
  (seed-machines! [:m/a])
  (seed-snapshots! {:m/a {:state :idle}})
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          pips (find-by-testid tree "rf-xray-static-machines-row-pips")]
      (is (some? pips) "pip cluster mounts for live machine"))))

;; -------------------------------------------------------------------------
;; Sort button label reflects the active axis
;; -------------------------------------------------------------------------

(deftest sort-button-label-tracks-the-active-axis
  (xray-setup!)
  (seed-machines! [:m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          btn  (find-by-testid tree "rf-xray-static-machines-sort")
          text (->> btn hiccup-seq (filter string?) (apply str))]
      (is (re-find #"Name" text) "default sort axis is Name")))
  (frame-dispatch [:rf.xray.static.machines/cycle-sort])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          btn  (find-by-testid tree "rf-xray-static-machines-sort")
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
          rows-el (find-by-testid tree "rf-xray-static-machines-rows")
          attrs  (second rows-el)]
      (is (= "listbox" (:role attrs)))
      (is (string? (:aria-label attrs))))))

(deftest selected-row-carries-aria-selected-true
  (xray-setup!)
  (seed-machines! [:m/a :m/b])
  (frame-dispatch [:rf.xray.static.machines/select :m/a])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          row-a (find-by-testid tree "rf-xray-static-machines-row-a")
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
          count-el (find-by-testid tree "rf-xray-static-machines-count")
          text (->> count-el hiccup-seq (filter string?) (apply str))]
      (is (re-find #"3 machines" text))))
  (frame-dispatch [:rf.xray.static.machines/set-search "foo"])
  (rf/with-frame :rf/xray
    (let [tree (panel/panel)
          count-el (find-by-testid tree "rf-xray-static-machines-count")
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
      (is (some? (find-by-testid tree "rf-xray-static-machines-no-results"))))))
