(ns day8.re-frame2-xray.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the Machines canvas adapter.

  rf2-gpzb4 (2026-05-21 xyflow migration) — the previous test corpus
  was dominated by the viewport-reducer + drag-state machinery the
  SVG renderer needed. Post-migration xyflow owns zoom/pan/fit
  internally; only the view-mode toggle slot + the `Chart` hiccup
  wrapper survive on the Xray side.

  Covers:

    1. Registry wires the surviving subs + events + fx.
    2. `:set-view-mode` updates the per-machine slot and fires the
       persist-view-mode fx with the latest map.
    3. The `Chart` view returns hiccup carrying the canvas-host
       data-testid + the view-mode toggle (when enabled)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.machine-canvas :as mc]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

;; ---- 1. Registry wires the surviving canvas surface -------------------

(deftest registry-wires-canvas-subs
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (testing "every machine-canvas sub resolves through rf/subscribe"
      (doseq [q-v [[:rf.xray.machine-canvas/view-mode-for :m]
                   [:rf.xray.machine-canvas/view-mode-by-id]]]
        (is (some? (rf/subscribe q-v))
            (str q-v " must resolve through rf/subscribe"))))))

;; ---- 2. View-mode toggle + persistence fx -----------------------------

(deftest set-view-mode-mutates-slot
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-view-mode
       {:machine-id :m :mode :list}])
    (let [mode @(rf/subscribe [:rf.xray.machine-canvas/view-mode-for :m])
          by-id @(rf/subscribe [:rf.xray.machine-canvas/view-mode-by-id])]
      (is (= :list mode))
      (is (= {:m :list} by-id)))))

(deftest set-view-mode-defaults-to-canvas
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [mode @(rf/subscribe [:rf.xray.machine-canvas/view-mode-for :m])]
      (is (= :canvas mode)
          "Unset slot defaults to :canvas — the dominant surface"))))

(deftest set-view-mode-normalises-bad-input
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-view-mode
       {:machine-id :m :mode :nonsense}])
    (let [mode @(rf/subscribe [:rf.xray.machine-canvas/view-mode-for :m])]
      (is (= :canvas mode)
          "Unknown modes fall back to :canvas"))))

(deftest persist-view-mode-fx-registered
  (setup-xray-frame!)
  (is (some?
        (registrar/handler
          :fx :rf.xray.machine-canvas/persist-view-mode))
      "persist-view-mode fx is in the registrar"))

;; ---- 3. Chart view hiccup shape ---------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle {:on {:start :loading}}
             :loading {}}})

(deftest chart-view-emits-canvas-host
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (mc/Chart {:definition fixture-definition :machine-id :m})]
      (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
          "canvas host wrapper present"))))

(deftest chart-view-emits-view-mode-toggle
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (mc/Chart {:definition fixture-definition :machine-id :m})]
      (is (some? (find-by-testid tree "rf-xray-machine-canvas-view-mode-toggle"))))))

(deftest chart-view-omits-view-mode-toggle-when-knob-false
  (testing "rf2-md9oz — Static Topology consumer passes
            :show-view-mode-toggle? false to suppress the Canvas/List
            pill (Static panel owns sub-mode at L3)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (mc/Chart {:definition fixture-definition
                            :machine-id :m
                            :show-view-mode-toggle? false})]
        (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
            "canvas host still mounts")
        (is (nil? (find-by-testid tree "rf-xray-machine-canvas-view-mode-toggle"))
            "view-mode toggle is suppressed")))))
