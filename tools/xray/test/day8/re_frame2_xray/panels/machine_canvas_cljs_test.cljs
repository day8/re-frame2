(ns day8.re-frame2-xray.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the Machines canvas adapter.

  xyflow owns zoom/pan/fit internally; the Xray side owns the `Chart`
  hiccup wrapper. There is no Canvas/List view-mode toggle.

  Covers which head mounts the after-rings overlay and the extent of the
  `as-child` seam in `chart-tree`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-machines-viz.chart :as mv-chart]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.panels.machine-canvas :as mc]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` folds core `make-reset-runtime-fixture` +
  ;; Xray `reset-all!` into one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- Chart view hiccup shape ------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle {:on {:start :loading}}
             :loading {}}})

(deftest the-reagent-chart-head-draws-no-after-rings
  (testing "`Chart` serves only the Static surfaces, which have no live
            focused event to time, so it mounts no after-rings overlay —
            and in particular never the `rf.fresco/defview`, which a Reagent
            tree cannot head. `Chart-view` is the head that draws rings; its
            overlay is the value `chart-tree` is handed, graded below."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [heads (into #{}
                        (comp (filter vector?) (map first))
                        (hiccup-seq (mc/Chart {:definition fixture-definition
                                               :machine-id :m})))]
        (is (contains? heads mv-chart/MachineChart)
            "NON-VACUITY: the walk reaches the chart's own heads")
        (is (not (contains? heads after-rings/AfterRingsOverlay))
            "the Reagent head mounts no after-rings overlay")))))

;; ---- 3b. the one body behind the two heads ------------------------------
;;
;; Which head is a Fresco boundary is graded where ELEMENT 3 mounts it:
;; machine_inspector_view's element-3-heads-a-fresco-boundary-rf2-k97c-3.

(deftest chart-tree-wraps-only-the-machines-viz-mount-in-as-child
  (testing "`as-child` covers the machines-viz chart and NOTHING
            else. Driven with a marking wrapper rather than a real substrate
            walk, so the row reads the seam's EXTENT without depending on a
            React element being constructible in the node lane.

            The extent is the claim worth pinning: widen it and the Fresco
            lane would cross its own wrapper divs into Reagent, which paints
            the same and silently puts the panel's chrome under the
            installed adapter's renderer."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [crossed  (atom [])
            marking  (fn [v] (swap! crossed conj v) [:marked v])
            tree     (mc/chart-tree {:definition fixture-definition
                                     :machine-id :m}
                                    marking
                                    [::overlay-sentinel])
            heads    (into #{}
                           (comp (filter vector?) (map first))
                           (hiccup-seq tree))]
        (is (= 1 (count @crossed))
            (str "exactly one thing crosses. Crossed: " (pr-str @crossed)))
        (is (= mv-chart/MachineChart (ffirst @crossed))
            "and it is the machines-viz chart, the one head in this body that
             Fresco's codec would refuse")
        (is (contains? heads ::overlay-sentinel)
            "the overlay value the caller handed in is mounted verbatim —
             which is how `Chart-view` draws the rings and `Chart` draws
             none through one body")))))

