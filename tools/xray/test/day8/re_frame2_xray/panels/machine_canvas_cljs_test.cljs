(ns day8.re-frame2-xray.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the Machines canvas adapter.

  rf2-gpzb4 (2026-05-21 xyflow migration) — the previous test corpus
  was dominated by the viewport-reducer + drag-state machinery the
  SVG renderer needed. Post-migration xyflow owns zoom/pan/fit
  internally; the `Chart` hiccup wrapper + the chart-collapsed slot
  survive on the Xray side. (rf2-48fwsi retired the dead Canvas/List
  view-mode toggle + its slot/events/fx.)

  Covers:

    1. Registry wires the surviving subs + events + fx.
    2. The chart-collapsed slot mutates + persists per machine.
    3. The `Chart` view returns hiccup carrying the canvas-host
       data-testid."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.machine-canvas :as mc]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; (core `make-reset-runtime-fixture` + Xray `reset-all!`) into one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- 1. Registry wires the surviving canvas surface -------------------

(deftest registry-wires-canvas-subs
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (testing "every machine-canvas sub resolves through rf/subscribe"
      (doseq [q-v [[:rf.xray.machine-canvas/chart-collapsed-for :m]
                   [:rf.xray.machine-canvas/chart-collapsed-by-id]]]
        (is (some? (rf/subscribe q-v))
            (str q-v " must resolve through rf/subscribe"))))))

;; ---- 2. Chart-collapsed slot (rf2-3d987 issue #4) ---------------------

(deftest chart-collapsed-defaults-to-false
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [collapsed? @(rf/subscribe
                        [:rf.xray.machine-canvas/chart-collapsed-for :m])]
      (is (= false collapsed?)
          "rf2-3d987 issue #4 — unset slot defaults to false (expanded)"))))

(deftest chart-collapsed-set-collapsed-and-toggle
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :collapsed}])
    (is (= true @(rf/subscribe
                   [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :collapsed flips the slot to true")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :toggle}])
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :toggle inverts the slot")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :toggle}])
    (is (= true @(rf/subscribe
                   [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        "second :toggle inverts again")
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :m :mode :expanded}])
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :m]))
        ":mode :expanded flips the slot to false")))

(deftest chart-collapsed-is-per-machine
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync
      [:rf.xray.machine-canvas/set-chart-collapsed
       {:machine-id :auth/login :mode :collapsed}])
    (is (= true  @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :auth/login])))
    (is (= false @(rf/subscribe
                    [:rf.xray.machine-canvas/chart-collapsed-for :checkout/flow]))
        "rf2-3d987 issue #4 — per-machine slot, one machine's collapse
         does not affect another's")))

(deftest persist-chart-collapsed-fx-registered
  (setup-xray-frame!)
  (is (some?
        (registrar/handler
          :fx :rf.xray.machine-canvas/persist-chart-collapsed))
      "persist-chart-collapsed fx is in the registrar"))

(deftest persist-chart-collapsed-fx-actually-fires-rf2-04tx
  (testing "rf2-04tx — the set-chart-collapsed handler must REACH the
            persist fx, not merely have one registered. The handler used
            to return the fx-id as a TOP-LEVEL effect key beside `:db`;
            the effect map is closed, so the runtime policed the key as
            `:rf.error/effect-map-shape` and dropped it — the `:db` write
            landed, the toggle looked like it worked, and the operator's
            choice never reached localStorage. `persist-chart-collapsed-
            fx-registered` above cannot see that: a registered fx nobody
            routes to satisfies it perfectly. This one observes the do-fx
            plane, which is the only place the drop is visible."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [persisted (atom [])]
        (rf/dispatch-sync
          [:rf.xray.machine-canvas/set-chart-collapsed
           {:machine-id :auth/login :mode :collapsed}]
          {:fx-overrides
           {:rf.xray.machine-canvas/persist-chart-collapsed
            (fn [_ctx by-id] (swap! persisted conj by-id))}})
        (is (= 1 (count @persisted))
            "the persist fx ran exactly once — it reached the :fx walk")
        (is (= {:auth/login true} (first @persisted))
            "and carried the POST-mutation chart-collapsed map")
        (rf/dispatch-sync
          [:rf.xray.machine-canvas/set-chart-collapsed
           {:machine-id :checkout/flow :mode :collapsed}]
          {:fx-overrides
           {:rf.xray.machine-canvas/persist-chart-collapsed
            (fn [_ctx by-id] (swap! persisted conj by-id))}})
        (is (= {:auth/login true :checkout/flow true} (second @persisted))
            "a second toggle persists the WHOLE by-id map, not just the
             machine that moved — the reload-restore contract")))))

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

(deftest chart-view-never-emits-view-mode-toggle-rf2-48fwsi
  (testing "rf2-48fwsi — the vestigial Canvas/List view-mode toggle is
            removed; the Chart never renders it (it was dead after the
            rf2-g2axio events-as-nodes redesign — no view branched on
            the persisted mode)."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [tree (mc/Chart {:definition fixture-definition :machine-id :m})]
        (is (some? (find-by-testid tree "rf-xray-machine-canvas-host"))
            "canvas host mounts")
        (is (nil? (find-by-testid tree "rf-xray-machine-canvas-view-mode-toggle"))
            "the retired view-mode toggle never renders")))))

;; ---- 4. SnapshotDrillIn view (rf2-lxvn6 · spec/021 §10) --------------

(deftest snapshot-drill-in-mounts-edn-inspector-widget
  (testing "the canvas-side SnapshotDrillIn view mounts the first-class
            edn-inspector widget against the supplied snapshot. The host
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
;; passes `:popup-affordance? true` through to `[ei/edn-inspector]`.

(defn- find-edn-inspector-mounts
  "Walk hiccup, collect every `[fn ...]` mount whose 3rd-arg looks like
  a edn-inspector opts map. Returns `{:value :opts}` maps."
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

(deftest snapshot-drill-in-edn-inspector-carries-popup-affordance
  (testing "rf2-l4625 — the canvas-side SnapshotDrillIn passes
            `:popup-affordance? true` to the embedded edn-inspector so
            the operator can pop the snapshot into the popup overlay"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [snapshot {:state :authing :data {:user "alice" :retries 1}}
            tree     (mc/SnapshotDrillIn {:machine-id :auth/login
                                          :snapshot   snapshot})
            mounts   (find-edn-inspector-mounts tree)]
        (is (seq mounts) "edn-inspector widget mounted")
        (is (every? #(true? (:popup-affordance? (:opts %))) mounts)
            "every mount opts in to the popup affordance")))))
