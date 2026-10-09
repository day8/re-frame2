(ns day8.re-frame2-machines-viz.chart.stale-settle-cljs-test
  "A stale async ELK settle must not overwrite the current topology.

  `MachineChart` launches a new ELK pass whenever its layout key changes and
  keeps the previous layout visible while the pass is in flight. Each pass
  callback closes over the key it was launched for, and only a completion
  whose key is still the CURRENT one may commit layout state or schedule a
  fit — for the initial pass and the measured relayout alike. Otherwise a
  slow result for topology A, landing after B has settled, would overwrite
  B's positions and routes and collapse B's nodes onto the origin.

  `chart/compute-layout!` is stubbed to capture each pass's done-fn, so a
  test settles passes in whatever order its scenario needs — no Promise,
  timer or rAF. The committed layout is read at the projection boundary
  (`projection/xyflow-graph` records what each render feeds it), fits through
  `chart/invoke-fit-view!`. The same seam idioms as `parse-cache-cljs-test`
  and `auto-fit-view-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.chart.projection :as projection]))

;; ---- fixtures -----------------------------------------------------------

(def ^:private machine-a
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done}}
             :done    {:final? true}}})

(def ^:private machine-b
  {:initial :off
   :states  {:off {:on {:flip :on}}
             :on  {:on {:flip :off}}}})

(def ^:private props-a {:machine-id :m :definition machine-a})
(def ^:private props-b {:machine-id :m :definition machine-b})

(def ^:private parsed-a
  "Node ids disjoint from `parsed-b`'s, so a cross-topology commit is
  unambiguous."
  {:nodes [{:id "a-idle"} {:id "a-loading"}] :edges [] :initial-path [:a-idle]})

(def ^:private parsed-b
  {:nodes [{:id "b-off"} {:id "b-on"}] :edges [] :initial-path [:b-off]})

(def ^:private a-result
  {:positions   {"a-idle"    {:x 900 :y 901 :width 100 :height 50}
                 "a-loading" {:x 902 :y 903 :width 100 :height 50}}
   :edge-points {"a-edge__out" [{:x 9 :y 9} {:x 10 :y 10}]}
   :edge-labels {"a-edge__out" {:x 11 :y 11}}})

(def ^:private a-measured-result
  "Distinct from `a-result`, so a leaked measured-relayout commit is told
  apart from a leaked initial one."
  {:positions   {"a-idle"    {:x 700 :y 701 :width 120 :height 60}
                 "a-loading" {:x 702 :y 703 :width 140 :height 60}}
   :edge-points {"a-edge__out" [{:x 7 :y 7} {:x 8 :y 8}]}
   :edge-labels {"a-edge__out" {:x 6 :y 6}}})

(def ^:private a-error-result
  {:positions   {}
   :edge-points {}
   :edge-labels {}
   :layout-error {:error {:message "stale boom"} :input-summary {}}})

(def ^:private b-result
  {:positions   {"b-off" {:x 111 :y 222 :width 100 :height 50}
                 "b-on"  {:x 333 :y 444 :width 100 :height 50}}
   :edge-points {"b-edge__out" [{:x 1 :y 2} {:x 3 :y 4}]}
   :edge-labels {"b-edge__out" {:x 5 :y 6}}})

(def ^:private fake-instance
  "Every call the chart makes on the xyflow instance is behind a stubbed
  seam, so an opaque object will do."
  #js {:__name "fake-xyflow-instance"})

;; ---- harness ------------------------------------------------------------

(defn- find-prop
  "The value of key `k` on the first hiccup map carrying it."
  [hiccup k]
  (->> (tree-seq sequential? seq hiccup)
       (filter map?)
       (some #(when (contains? % k) (get % k)))))

(defn- with-stale-seams
  "Run `(f captured)` with the chart's seams stubbed, restoring them after.
  `captured` holds atoms:

    :passes      — `{:parsed p :measured-dims md :done done-fn}` per
                   `compute-layout!` call; elk never runs.
    :projections — `{:positions :edge-points :edge-labels}` per
                   `projection/xyflow-graph` call: the layout each render
                   committed to.
    :fit-calls   — one entry per `invoke-fit-view!` call.
    :measured    — what `read-measured-dims` answers.

  The `compute-layout!` stub is multi-arity like the real fn: shadow compiles
  the render's call to a direct `arity$8` dispatch."
  [f]
  (let [passes       (atom [])
        projections  (atom [])
        fit-calls    (atom [])
        measured     (atom {})
        capture!     (fn [parsed measured-dims done]
                       (swap! passes conj {:parsed parsed
                                           :measured-dims measured-dims
                                           :done done})
                       nil)
        orig-parse   chart/invoke-project-definition!
        orig-project projection/xyflow-graph
        orig-layout  chart/compute-layout!
        orig-fit     chart/invoke-fit-view!
        orig-dims    chart/read-measured-dims]
    (set! chart/invoke-project-definition!
          (fn [definition]
            (if (= definition machine-a) parsed-a parsed-b)))
    (set! projection/xyflow-graph
          (fn [_parsed positions opts]
            (swap! projections conj {:positions   positions
                                     :edge-points (:edge-points opts)
                                     :edge-labels (:edge-labels opts)})
            {:nodes [] :edges []}))
    (set! chart/compute-layout!
          (fn
            ([p done] (capture! p nil done))
            ([p _d _lo done] (capture! p nil done))
            ([p _d _lo _mid done] (capture! p nil done))
            ([p _d _lo _mid md done] (capture! p md done))
            ([p _d _lo _mid md _cv done] (capture! p md done))
            ([p _d _lo _mid md _cv _cr done] (capture! p md done))))
    (set! chart/invoke-fit-view!
          (fn [instance opts] (swap! fit-calls conj [instance opts])))
    (set! chart/read-measured-dims
          (fn [_instance] @measured))
    (try
      (f {:passes passes :projections projections
          :fit-calls fit-calls :measured measured})
      (finally
        (set! chart/invoke-project-definition! orig-parse)
        (set! projection/xyflow-graph orig-project)
        (set! chart/compute-layout! orig-layout)
        (set! chart/invoke-fit-view! orig-fit)
        (set! chart/read-measured-dims orig-dims)))))

(defn- settle! [passes i result]
  ((:done (nth @passes i)) result))

;; ---- tests ----------------------------------------------------------------

(deftest stale-initial-settle-is-dropped
  (testing "A's slow initial settle landing after B has settled is dropped
            whole: B's positions, routes and edge-labels survive and no fit
            is scheduled for A"
    (with-stale-seams
      (fn [{:keys [passes projections fit-calls]}]
        (let [rfn (chart/MachineChart props-a)]
          (rfn props-a)
          (rfn props-b)
          (is (= [parsed-a parsed-b] (mapv :parsed @passes)))
          ((find-prop (rfn props-b) :onInit) fake-instance)
          (settle! passes 1 b-result)
          (rfn props-b)
          (let [fits (count @fit-calls)]
            (is (pos? fits) "non-vacuity: the current settle does fit")
            (settle! passes 0 a-result)
            (rfn props-b)
            (is (= fits (count @fit-calls)) "the stale settle schedules no fit")
            (is (= b-result (last @projections)))))))))

(deftest stale-measured-relayout-settle-is-dropped
  (testing "A's measured relayout closes over A's key too, so it is dropped
            when it lands after B has settled; and while B's pass is in
            flight the chart keeps A's last layout rather than flashing an
            empty graph"
    (with-stale-seams
      (fn [{:keys [passes projections fit-calls measured]}]
        (let [rfn (chart/MachineChart props-a)]
          (rfn props-a)
          (settle! passes 0 a-result)
          (reset! measured {"a-idle"    {:width 120 :height 60}
                            "a-loading" {:width 140 :height 60}})
          ((find-prop (rfn props-a) :onInit) fake-instance)
          (is (= @measured (:measured-dims (nth @passes 1)))
              "non-vacuity: pass 2 is A's measured relayout")
          (rfn props-b)
          (is (= (:positions a-result) (:positions (last @projections)))
              "in flight, B renders A's last committed layout")
          (settle! passes 2 b-result)
          (rfn props-b)
          (let [fits (count @fit-calls)]
            (settle! passes 1 a-measured-result)
            (rfn props-b)
            (is (= fits (count @fit-calls)) "the stale relayout schedules no fit")
            (is (= b-result (last @projections)))))))))

(deftest stale-error-settle-does-not-surface
  (testing "a stale pass failing with a layout error neither replaces B's
            layout with the empty error shape nor paints the error banner"
    (with-stale-seams
      (fn [{:keys [passes projections]}]
        (let [rfn (chart/MachineChart props-a)]
          (rfn props-a)
          (rfn props-b)
          (settle! passes 1 b-result)
          (settle! passes 0 a-error-result)
          (let [hiccup (rfn props-b)]
            (is (= b-result (last @projections)))
            (is (= "false" (find-prop hiccup :data-layout-error)))))))))
