(ns day8.re-frame2-machines-viz.chart.parse-cache-cljs-test
  "Per-chart parsed-topology cache.

  `spec/API.md` keeps the topology and runtime-highlight planes strictly
  separate: a decoration-only render must not walk the definition through the
  parser (`chart/invoke-project-definition!`) nor re-run ELK
  (`compute-layout!`). Only a NEW `:definition` reparses, exactly once.
  Density / direction / layout-options changes relayout but never reparse.

  `(chart/MachineChart props)` returns the Form-2 render fn closing over the
  cache; calling it with new props IS the host-driven re-render sequence.
  Building its hiccup is pure CLJS data, and every framework-reaching call
  goes through a `set!`-able seam, so this runs on Node (the same idiom as
  `auto-fit-view-cljs-test`)."
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

;; ---- spy harness --------------------------------------------------------

(defn- with-seam-spies
  "Run `(f counts)` with the parser, projection, layout and fit seams
  stubbed, restoring them after. `counts` is an atom of
  `{:parse N :project N :layout N}` invocation counts. The parser answers a
  one-leaf graph; the real elk pass never runs."
  [f]
  (let [counts        (atom {:parse 0 :project 0 :layout 0})
        orig-parse    chart/invoke-project-definition!
        orig-project  projection/xyflow-graph
        orig-layout   chart/compute-layout!
        orig-fit      chart/invoke-fit-view!
        layout!       (fn [] (swap! counts update :layout inc) nil)]
    (set! chart/invoke-project-definition!
          (fn [_definition]
            (swap! counts update :parse inc)
            {:nodes [{:id "idle"}] :edges [] :initial-path [:idle]}))
    (set! projection/xyflow-graph
          (fn [_parsed _positions _opts]
            (swap! counts update :project inc)
            {:nodes [] :edges []}))
    ;; shadow compiles the render's 8-arity call to a direct `arity$8`
    ;; dispatch, so the stub must be multi-arity like the real fn.
    (set! chart/compute-layout!
          (fn
            ([_p _done] (layout!))
            ([_p _d _lo _done] (layout!))
            ([_p _d _lo _mid _done] (layout!))
            ([_p _d _lo _mid _md _done] (layout!))
            ([_p _d _lo _mid _md _cv _done] (layout!))
            ([_p _d _lo _mid _md _cv _cr _done] (layout!))))
    (set! chart/invoke-fit-view! (fn [& _args] nil))
    (try
      (f counts)
      (finally
        (set! chart/invoke-project-definition! orig-parse)
        (set! projection/xyflow-graph orig-project)
        (set! chart/compute-layout! orig-layout)
        (set! chart/invoke-fit-view! orig-fit)))))

;; ---- tests --------------------------------------------------------------

(deftest decoration-only-renders-do-not-reparse-or-relayout
  (testing "with the SAME `:definition`, highlight deltas (`:current-state`,
            `:from-highlight` / `:to-highlight`, `:fired-edge-ids` — what
            Xray's Prev/Next feeds a kept chart instance), overlay `:tick`
            bumps, a `:fit-signal` bump and a bare re-render never reparse
            nor re-run ELK, so the positions stay put. Highlight deltas DO
            re-project (the cheap re-tint); the others hit the projection
            cache too."
    (with-seam-spies
      (fn [counts]
        (let [props {:machine-id :m :definition machine-a}
              rfn   (chart/MachineChart props)]
          (rfn props)
          (let [mount-projections (:project @counts)]
            (rfn (assoc props :current-state :loading))
            (rfn (assoc props :from-highlight :idle :to-highlight :loading
                        :fired-edge-ids ["idle->loading"]))
            (rfn (assoc props :from-highlight :loading :to-highlight :done
                        :fired-edge-ids ["loading->done"]))
            (rfn (assoc props :overlays [{:id :ring :tick 1}]))
            (let [after-highlights (:project @counts)]
              (is (> after-highlights mount-projections) "highlight deltas re-project")
              (rfn (assoc props :overlays [{:id :ring :tick 2}]))
              (rfn (assoc props :fit-signal 7))
              (rfn props)
              (is (= {:parse 1 :layout 1 :project after-highlights} @counts)))))))))

(deftest new-definition-reparses-once-and-busts-downstream-caches
  (testing "a CHANGED `:definition` reparses once and re-runs layout; an
            unchanged one does neither; switching back reparses again (the
            cache holds the LAST definition only)"
    (with-seam-spies
      (fn [counts]
        (let [rfn     (chart/MachineChart {:machine-id :m :definition machine-a})
              render! (fn [definition & {:as more}]
                        (rfn (merge {:machine-id :m :definition definition} more))
                        ((juxt :parse :layout) @counts))]
          (is (= [1 1] (render! machine-a)))
          (is (= [2 2] (render! machine-b)) "a new definition reparses and relayouts")
          (is (= [2 2] (render! machine-b :current-state :on)) "an unchanged one does neither")
          (is (= [3 3] (render! machine-a)) "switching back reparses (single-slot memo)"))))))

(deftest density-direction-layout-options-changes-do-not-reparse
  (testing "density / direction / layout-options re-run ELK but never
            reparse: the parse is keyed ONLY on `:definition`"
    (with-seam-spies
      (fn [counts]
        (let [props {:machine-id :m :definition machine-a}
              rfn   (chart/MachineChart props)]
          (rfn props)
          (let [layouts-after-mount (:layout @counts)]
            (rfn (assoc props :density :compact))
            (rfn (assoc props :density :cosy))
            (rfn (assoc props :direction :lr))
            (rfn (assoc props :layout-options {"elk.spacing.nodeNode" "80"}))
            (is (= 1 (:parse @counts)) "no reparse")
            (is (> (:layout @counts) layouts-after-mount) "but layout re-runs")))))))
