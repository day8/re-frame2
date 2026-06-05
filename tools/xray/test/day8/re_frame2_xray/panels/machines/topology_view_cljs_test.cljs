(ns day8.re-frame2-xray.panels.machines.topology-view-cljs-test
  "Pure-data tests for the topology-view composition helpers (rf2-vcnvj).
  Focuses on `static-context-shape` — the root-Context-chrome projection
  the Static-Machines topology path feeds the chart so the root context
  renders on the blank-state topology without a live snapshot.

  Also pins the `Topology` → `machine-canvas/Chart` prop boundary
  (rf2-xf5on): the view's `:trace-events` contract promises
  `fired-this-epoch` edge highlights, so the fired-edge ids it computes
  must reach the chart mount's `:fired-edge-ids` prop."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machines.topology-view :as tv]))

(defn- toy-definition
  "Minimal machine: :empty --:populate--> :populated --:submit-->
  :submitting (final). Mirrors the trace-state test's fixture so the
  fired-edge ids agree by construction with the live chart's projection."
  []
  {:initial :empty
   :states  {:empty      {:on {:populate :populated}}
             :populated  {:on {:submit :submitting}}
             :submitting {:final? true}}})

(defn- canonical-edge-id
  "The live-chart canonical edge id for `from→to via event`, projected
  through the SAME `chart.layout/parse-definition` the live MachineChart
  uses — so the test asserts the EXACT ids the chart paints, not a
  re-implemented scheme."
  [definition from-path to-path event]
  (->> (:edges (chart-layout/parse-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= to-path   (:to-path e))
                          (= event     (:event e)))
                 (:id e))))))

(defn- find-chart-props
  "Walk the hiccup `Topology` returns and return the props map of the
  `[machine-canvas/Chart {...}]` mount (or nil if absent). Depth-first;
  returns the first match."
  [hiccup]
  (cond
    (and (vector? hiccup)
         (= machine-canvas/Chart (first hiccup)))
    (second hiccup)

    (vector? hiccup)
    (some find-chart-props hiccup)

    (seq? hiccup)
    (some find-chart-props hiccup)

    :else nil))

(deftest static-context-shape-maps-keys-to-type-captions
  (testing "rf2-vcnvj — derives `(key → type-caption)` from the
            definition's declared `:data`, NOT the live values (the door
            machine's `{:opened-count 0 :held-open? false :trail []}`
            shape)."
    (let [def   {:initial :locked
                 :data    {:opened-count 0 :held-open? false :trail []
                           :note "x" :tag :a :nested {} :many #{}}
                 :states  {:locked {}}}
          shape (tv/static-context-shape def)]
      (is (= {:opened-count "number"
              :held-open?   "boolean"
              :trail        "vector"
              :note         "string"
              :tag          "keyword"
              :nested       "map"
              :many         "set"}
             shape)
          "each key maps to its value's type caption (shape, not value)"))))

(deftest static-context-shape-nil-when-no-data
  (testing "rf2-vcnvj — a machine with no `:data` yields nil so the root
            Context panel stays hidden."
    (is (nil? (tv/static-context-shape {:initial :a :states {:a {}}})))
    (is (nil? (tv/static-context-shape {:initial :a :data nil :states {:a {}}})))
    (is (nil? (tv/static-context-shape nil)))))

;; ---- Topology → Chart fired-edge prop boundary (rf2-xf5on) --------------

(deftest topology-forwards-fired-edge-ids-to-chart
  (testing "rf2-xf5on — the fired-this-epoch edge ids the view computes from
            `:trace-events` reach the `machine-canvas/Chart` mount's
            `:fired-edge-ids` prop (the docstring's contract). Before the
            fix the prop was absent and the fired treatment was silently
            dropped."
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          events      [{:operation :rf.machine/transition
                        :tags      {:machine-id :cart}
                        :from      [:empty] :to [:populated]
                        :event     :populate}]
          props       (find-chart-props
                        (tv/Topology {:machine-id   :cart
                                      :definition   def
                                      :trace-events events}))]
      (is (some? props)
          "the :else branch mounts machine-canvas/Chart")
      (is (string? populate-id))
      (is (contains? props :fired-edge-ids)
          "Chart mount carries the :fired-edge-ids prop")
      (is (= #{populate-id} (:fired-edge-ids props))
          "and it is exactly the canonical fired-edge id set the live
           chart paints"))))

(deftest topology-passes-empty-fired-edges-when-no-transition-this-epoch
  (testing "rf2-xf5on — case-B (no transition this epoch) forwards `#{}`,
            identical to passing no fired highlight."
    (let [def   (toy-definition)
          props (find-chart-props
                  (tv/Topology {:machine-id   :cart
                                :definition   def
                                :trace-events []}))]
      (is (some? props))
      (is (= #{} (:fired-edge-ids props))
          "empty fired set → no fired highlight on the blank-epoch chart"))))
