(ns day8.re-frame2-xray.panels.machine-canvas-cljs-test
  "CLJS-side wiring tests for the Machines canvas adapter.

  xyflow owns zoom/pan/fit internally; the Xray side owns the `Chart`
  hiccup wrapper. There is no Canvas/List view-mode toggle.

  Covers the `Chart` view's after-rings mount and the extent of the
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

(deftest chart-mounts-the-after-rings-bridge-rf2-k97c-3
  (testing "`machine-after-rings/AfterRingsOverlay` is an
            `rf.fresco/defview`, i.e. a real React component, while `Chart`
            here is a `reg-view`, i.e. a Reagent tree. So `Chart` must
            mount the `as-component` BRIDGE and not the boundary: handing
            Reagent a React component where it expects a render fn is
            exactly what `defview`'s contract forbids, and it would paint
            nothing. The boundary's own end-to-end DOM evidence lives in
            `machine_after_rings_fresco_boundary_dom_cljs_test`, which
            mounts this same public var — this row is what says CHART is
            the caller holding it, so the two cannot drift apart silently.
            `Chart` mounting the boundary directly type-checks fine and
            fails only at first paint; it drops the bridge, which this row
            reads."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (let [heads (fn [props]
                    (into #{}
                          (comp (filter vector?) (map first))
                          (hiccup-seq (mc/Chart props))))]
        (is (contains? (heads {:definition fixture-definition :machine-id :m})
                       after-rings/AfterRingsOverlay-bridge)
            "Chart mounts AfterRingsOverlay-bridge (:show-after-rings? defaults true)")
        (is (not (contains? (heads {:definition        fixture-definition
                                    :machine-id        :m
                                    :show-after-rings? false})
                            after-rings/AfterRingsOverlay-bridge))
            "the Static call sites pass :show-after-rings? false, which drops
             the bridge altogether — the non-vacuity control for the row above")))))

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
             which is how the two heads mount DIFFERENT overlay heads (the
             bridge, or the boundary) through one body")))))

