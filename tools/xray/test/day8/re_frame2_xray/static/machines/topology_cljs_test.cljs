(ns day8.re-frame2-xray.static.machines.topology-cljs-test
  "rf2-eao0s0 — Static Machines Topology → machine-canvas/Chart prop
  boundary.

  The Dynamic topology + focused-event charts forward the machine's
  STATIC context shape into `machine-canvas/Chart` so the root Context
  band renders without a live snapshot. The Static Topology chart
  wrapper had been mounting only definition / machine-id, so the root
  Context band was missing there. These tests pin that the wrapper now
  forwards `:context-band` (the {key → type-caption} shape) +
  `:context-band-inferred?` for BOTH an inferred (`:data`-sample) and a
  declared (`[:schemas :data]`) definition.

  The chart wrapper is the private `topology/chart` fn; we invoke it via
  its var so the `[machine-canvas/Chart {...}]` mount survives as data
  (a raw tree-seq, no fn-component expansion, finds its props)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.static.machines.topology :as topology]))

;; ---- fixtures -----------------------------------------------------------

(def ^:private inferred-definition
  "No [:schemas :data] → the context shape is INFERRED from one sample of the
  initial :data (the chart keeps the `inferred from :data` badge)."
  {:initial :idle
   :data    {:opened-count 0 :held-open? false :trail []}
   :states  {:idle {:on {:open :opening}}
             :opening {:final? true}}})

(def ^:private declared-definition
  "Declares a [:schemas :data] schema → the context shape is AUTHORITATIVE off
  the schema (the chart drops the inferred badge)."
  {:initial :idle
   :data    {:opened-count 0 :held-open? false}
   :schemas {:data [:map [:opened-count :int] [:held-open? :boolean]]}
   :states  {:idle {:on {:open :opening}}
             :opening {:final? true}}})

(def ^:private no-data-definition
  "Neither :data nor [:schemas :data] → the shape is nil so the chart hides
  the Context panel."
  {:initial :idle
   :states  {:idle {} :opening {}}})

;; ---- raw walker ---------------------------------------------------------
;;
;; A RAW tree-seq that does NOT invoke fn components, so the
;; `[machine-canvas/Chart {...}]` child of the wrapper survives as data
;; and its props are assertable.

(defn- raw-hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- chart-props
  "Render the private `topology/chart` wrapper for `definition` and return
  the embedded machine-canvas/Chart props map (or nil if absent)."
  [definition]
  (let [tree       (#'topology/chart
                     identity
                     {:definition definition :machine-id :door/main})
        chart-node (some (fn [node]
                           (when (and (vector? node)
                                      (= machine-canvas/Chart (first node)))
                             node))
                         (raw-hiccup-seq tree))]
    (second chart-node)))

;; ---- tests --------------------------------------------------------------

(deftest topology-forwards-inferred-context-shape-to-chart
  (testing "rf2-eao0s0 — an inferred (:data, no schema) machine: the Static
            Topology chart forwards the {key → type-caption} shape with
            :context-band-inferred? TRUE."
    (let [props (chart-props inferred-definition)]
      (is (some? props) "the chart wrapper mounts machine-canvas/Chart")
      (is (= {:opened-count "number" :held-open? "boolean" :trail "vector"}
             (:context-band props))
          "the static context SHAPE reaches the chart's :context-band")
      (is (true? (:context-band-inferred? props))
          "inferred sample → :context-band-inferred? TRUE reaches the chart"))))

(deftest topology-forwards-declared-context-shape-to-chart
  (testing "rf2-eao0s0 — a declared ([:schemas :data]) machine: the Static
            Topology chart forwards the AUTHORITATIVE schema shape with
            :context-band-inferred? FALSE."
    (let [props (chart-props declared-definition)]
      (is (= {:opened-count "number" :held-open? "boolean"}
             (:context-band props))
          "the declared schema SHAPE reaches the chart's :context-band")
      (is (false? (:context-band-inferred? props))
          "declared schema → :context-band-inferred? FALSE reaches the chart"))))

(deftest topology-hides-context-panel-when-no-shape
  (testing "rf2-eao0s0 — a machine that declares neither :data nor a
            [:schemas :data] schema forwards a nil :context-band so the chart hides
            the Context panel (the existing chart contract)."
    (let [props (chart-props no-data-definition)]
      (is (some? props) "the chart wrapper still mounts machine-canvas/Chart")
      (is (nil? (:context-band props))
          "no shape → :context-band is nil (Context panel stays hidden)"))))

(deftest topology-toolbar-renders-no-inert-popout-affordance
  (testing "rf2-h6ooa — the toolbar's 'Pop out' button dispatched
            `:rf.xray.static.machines/open-chart-popout`, whose handler is a
            registered NO-OP (no pop-out window exists), so clicking a
            promised control silently did nothing. Hide it until it does
            something: the RENDERED toolbar carries no pop-out affordance,
            and no control in it dispatches the no-op event.

            `body` is rendered with a RECORDING dispatch; the toolbar mount
            it emits is taken off the RAW tree (so the chart's reg-view is
            never invoked) and then EXPANDED the way Reagent mounts it
            (every fn head invoked), so a control nested under a fn
            component is visible to the walk."
    (let [dispatched (atom [])
          body-tree  (topology/body #(swap! dispatched conj %)
                                    {:machine-id :door/main
                                     :definition inferred-definition})
          mount      (some (fn [node]
                             (when (and (vector? node)
                                        (= @#'topology/chart-toolbar
                                           (first node)))
                               node))
                           (raw-hiccup-seq body-tree))
          tree       (rf.test-helpers/expand-tree mount)]
      (is (some? (rf.test-helpers/find-by-testid
                   tree "rf-xray-static-machines-topology-toolbar"))
          "NON-VACUITY: the toolbar itself rendered, so the absence below is
           a statement about its contents")
      (is (nil? (rf.test-helpers/find-by-testid
                  tree "rf-xray-static-machines-topology-popout"))
          "the inert pop-out affordance is absent from the rendered toolbar")
      (doseq [node (tree-seq (some-fn vector? seq?) seq tree)
              :let [on-click (:on-click (rf.test-helpers/attrs node))]
              :when (fn? on-click)]
        (on-click nil))
      (is (not-any? #(= :rf.xray.static.machines/open-chart-popout (first %))
                    @dispatched)
          "clicking every rendered toolbar control dispatches nothing into
           the no-op pop-out handler"))))
