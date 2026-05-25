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

;; ---- 4. SnapshotDrillIn view (rf2-lxvn6 · spec/021 §10) --------------

(deftest snapshot-drill-in-mounts-data-display-widget
  (testing "the canvas-side SnapshotDrillIn view mounts the first-class
            data-display widget against the supplied snapshot. The host
            carries per-machine + per-phase data attributes so panel-
            level tests can target the surface without reaching into the
            inner widget."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [snapshot {:state :authing :data {:user "alice" :retries 1}}
            tree     (mc/SnapshotDrillIn {:machine-id :auth/login
                                          :snapshot   snapshot})
            host     (find-by-testid tree "rf-xray-machine-canvas-snapshot-drill-in")]
        (is (some? host) "drill-in host mounts")
        (is (= ":auth/login" (:data-machine-id (second host))))
        (is (= "current" (:data-phase (second host)))
            "defaults to :current phase")
        (is (= "true" (:data-has-snapshot (second host))))))))

(deftest snapshot-drill-in-supports-phase-arg
  (testing "the :phase arg lets callers scope the per-machine `:panel-id`
            qualifier — `:before` / `:after` mounts on the same machine
            stay independent, the rule per spec/021 §10.0.2 property 5
            (per-call-site isolation)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [snapshot {:state :idle :data {}}
            before-tree (mc/SnapshotDrillIn {:machine-id :auth/login
                                             :snapshot   snapshot
                                             :phase      :before})
            after-tree  (mc/SnapshotDrillIn {:machine-id :auth/login
                                             :snapshot   snapshot
                                             :phase      :after})
            before-host (find-by-testid before-tree
                                        "rf-xray-machine-canvas-snapshot-drill-in")
            after-host  (find-by-testid after-tree
                                        "rf-xray-machine-canvas-snapshot-drill-in")]
        (is (= "before" (:data-phase (second before-host))))
        (is (= "after"  (:data-phase (second after-host))))))))

(deftest snapshot-drill-in-renders-empty-state-when-snapshot-nil
  (testing "callers can pass nil — the surface still mounts but the body
            renders the uninitialised-machine empty-state chip rather
            than the widget. Keeps callers from gating the mount
            themselves."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (mc/SnapshotDrillIn {:machine-id :auth/login
                                      :snapshot   nil})
            host (find-by-testid tree "rf-xray-machine-canvas-snapshot-drill-in")
            empty (find-by-testid tree
                    "rf-xray-machine-canvas-snapshot-drill-in-empty")]
        (is (some? host))
        (is (= "false" (:data-has-snapshot (second host))))
        (is (some? empty)
            "empty-state chip mounts when snapshot is nil")))))

;; ---- (5) popup affordance (rf2-l4625) ----------------------------------
;;
;; Machine snapshots routinely carry deeply-nested `:data` maps; the
;; popup gives the operator a full-modal inspection surface alongside
;; the per-machine drill-in. The canvas-side SnapshotDrillIn mount
;; passes `:popup-affordance? true` through to `[dd/data-display]`.

(defn- find-data-display-mounts
  "Walk hiccup, collect every `[fn ...]` mount whose 3rd-arg looks like
  a data-display opts map. Returns `{:value :opts}` maps."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [n]
              (cond
                (vector? n)
                (do (when (and (fn? (first n)) (>= (count n) 3)
                               (map? (nth n 2))
                               (contains? (nth n 2) :panel-id))
                      (swap! out conj {:value (nth n 1) :opts (nth n 2)}))
                    (doseq [c (rest n)] (walk c)))
                (seq? n) (doseq [c n] (walk c))))]
      (walk tree))
    @out))

(deftest snapshot-drill-in-data-display-carries-popup-affordance
  (testing "rf2-l4625 — the canvas-side SnapshotDrillIn passes
            `:popup-affordance? true` to the embedded data-display so
            the operator can pop the snapshot into the popup overlay"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [snapshot {:state :authing :data {:user "alice" :retries 1}}
            tree     (mc/SnapshotDrillIn {:machine-id :auth/login
                                          :snapshot   snapshot})
            mounts   (find-data-display-mounts tree)]
        (is (seq mounts) "data-display widget mounted")
        (is (every? #(true? (:popup-affordance? (:opts %))) mounts)
            "every mount opts in to the popup affordance")))))
