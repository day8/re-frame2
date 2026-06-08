(ns day8.re-frame2-machines-viz.chart.parse-cache-cljs-test
  "rf2-jl72i — per-chart parsed-topology cache regression.

  ## What this pins

  `MachineChart` is a Reagent Form-2 component: calling
  `(chart/MachineChart props)` returns the inner render fn, which closes
  over the per-chart state atoms — including the rf2-jl72i `parse-cache`.
  Calling that render fn repeatedly with new prop maps simulates the
  re-render sequence a host drives (a `:current-state` highlight change,
  an overlay `:tick` bump, a `:fit-signal` bump, a bare parent re-render,
  a `:density` switch, a NEW `:definition`).

  The topology / runtime-highlight plane separation
  (`spec/API.md` §Topology props and runtime-highlight props MUST be
  strictly separate, §Highlight / overlay prop changes MUST change
  attrs/classes only) requires that a DECORATION-ONLY render NOT walk the
  definition through the parser (`layout/project-definition`, routed via
  the `chart/invoke-project-definition!` seam) nor re-run the downstream
  projection (`projection/xyflow-graph`) or layout (`compute-layout!`)
  pipelines. Only a NEW `:definition` may reparse — exactly once — and it
  busts the downstream caches. Density / direction / layout-options
  changes relayout but MUST NOT reparse (the parse is keyed only on
  `:definition`).

  ## Why this is a `-cljs-test` (node), not a DOM test

  Building the chart's hiccup tree is pure CLJS data — it does NOT render
  React (`[:> ReactFlow …]` is a hiccup tag, not an invocation). The only
  eager work the render body does is the topology parse (now cached) and
  the rf2-dnmbs `project+convert!` thunk; both reach the framework only
  through `set!`-able seams we stub here (same idiom as
  `auto-fit-view-cljs-test`). So the cache contract is fully Node-runnable
  without a real xyflow instance or DOM — it rides the always-on
  `npm run test:cljs` gate."
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
  "Run `(f counts)` with all four framework seams the parse-cache
  regression cares about rebound via `set!` to count their invocations,
  then restore the originals. `counts` is an atom holding
  `{:parse N :project N :layout N :clj->js N}`.

  - `chart/invoke-project-definition!` — the topology parser seam. Returns
    a stub parsed graph (small, fixed shape) so the downstream `clj->js`
    in `project+convert!` is trivial.
  - `projection/xyflow-graph` — the parsed→xyflow projection. Returns an
    empty `{:nodes [] :edges []}` so `clj->js` of the result is trivial.
  - `chart/compute-layout!` — the elkjs layout pass. No-op (never call the
    real async elk).
  - `chart/invoke-fit-view!` — the `.fitView` seam. No-op.
  - `goog.global` `clj->js`-adjacent: we count `clj->js` indirectly via the
    project seam, since `project+convert!` only `clj->js`-es on a graph-
    cache MISS (which a re-projection implies). A `:project` increment
    therefore stands in for the `clj->js` cost the bead names."
  [f]
  (let [counts        (atom {:parse 0 :project 0 :layout 0})
        orig-parse    chart/invoke-project-definition!
        orig-project  projection/xyflow-graph
        orig-layout   chart/compute-layout!
        orig-fit      chart/invoke-fit-view!]
    (set! chart/invoke-project-definition!
          (fn [_definition]
            (swap! counts update :parse inc)
            ;; Minimal parsed shape the render body reads: a single leaf
            ;; node + no edges. `:region?` / `:compound?` absent so the
            ;; count + measurable-id derivations are total.
            {:nodes [{:id "idle"}] :edges [] :initial-path [:idle]}))
    (set! projection/xyflow-graph
          (fn [_parsed _positions _opts]
            (swap! counts update :project inc)
            {:nodes [] :edges []}))
    ;; `compute-layout!` is a fixed-MULTI-arity `defn`; the render calls
    ;; the arity-8 form (`parsed direction layout-options machine-id
    ;; measured-dims chart-vc context-rows done-fn` — rf2-8z1rca added the
    ;; `context-rows` param). shadow compiles that call to the direct
    ;; `.cljs$core$IFn$_invoke$arity$8` dispatch, so the stub must itself be
    ;; a MULTI-arity fn (a single fixed-arity `fn` exposes only the generic
    ;; `call`, not `arity$8`). We mirror the real fn's arity shape and
    ;; increment on whichever the render hits. No-op: the real elk pass
    ;; never runs.
    (set! chart/compute-layout!
          (fn
            ([_p _done] (swap! counts update :layout inc) nil)
            ([_p _d _lo _done] (swap! counts update :layout inc) nil)
            ([_p _d _lo _mid _done] (swap! counts update :layout inc) nil)
            ([_p _d _lo _mid _md _done] (swap! counts update :layout inc) nil)
            ([_p _d _lo _mid _md _cv _done]
             (swap! counts update :layout inc) nil)
            ([_p _d _lo _mid _md _cv _cr _done]
             (swap! counts update :layout inc) nil)))
    (set! chart/invoke-fit-view!
          (fn [& _args] nil))
    (try
      (f counts)
      (finally
        (set! chart/invoke-project-definition! orig-parse)
        (set! projection/xyflow-graph orig-project)
        (set! chart/compute-layout! orig-layout)
        (set! chart/invoke-fit-view! orig-fit)))))

(defn- render!
  "Realise one render of the Form-2 render fn `rfn` with `props`. The
  return value (a hiccup tree) is discarded — the test asserts on the
  seam-call counts, which are the observable side effects."
  [rfn props]
  (rfn props)
  nil)

;; ---- tests --------------------------------------------------------------

(deftest decoration-only-renders-do-not-reparse-or-reproject-or-relayout
  (testing "rf2-jl72i — with the SAME `:definition`, a `:current-state` /
            `:from-highlight` / `:to-highlight` change, an overlay `:tick`
            bump, a `:fit-signal` bump, and a bare parent re-render MUST
            NOT call the topology parser. They also MUST NOT re-run
            layout. (A highlight change DOES legitimately re-project — it
            re-tints nodes — but the parse, the O(topology) cost the bead
            targets, must be reused.)"
    (with-seam-spies
      (fn [counts]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          ;; First render: one parse, one layout pass (new layout-key),
          ;; one projection.
          (render! rfn {:machine-id :m :definition machine-a})
          (is (= 1 (:parse @counts)) "first render parses once")
          (is (= 1 (:layout @counts)) "first render lays out once")
          (let [after-mount (:project @counts)]
            ;; --- decoration-only re-renders, SAME definition ---
            (render! rfn {:machine-id :m :definition machine-a
                          :current-state :loading})
            (render! rfn {:machine-id :m :definition machine-a
                          :current-state :loading
                          :from-highlight :idle :to-highlight :loading})
            (render! rfn {:machine-id :m :definition machine-a
                          :overlays [{:id :ring :tick 1}]})
            (render! rfn {:machine-id :m :definition machine-a
                          :overlays [{:id :ring :tick 2}]})
            (render! rfn {:machine-id :m :definition machine-a
                          :fit-signal 7})
            ;; A bare parent re-render (identical props).
            (render! rfn {:machine-id :m :definition machine-a})
            (is (= 1 (:parse @counts))
                "NO decoration-only render re-walks the definition")
            (is (= 1 (:layout @counts))
                "NO decoration-only render re-runs ELK layout")
            (is (>= (:project @counts) after-mount)
                "highlight changes may re-project (decorative re-tint), but
                 never reparse/relayout")))))))

(deftest new-definition-reparses-once-and-busts-downstream-caches
  (testing "rf2-jl72i — a CHANGED `:definition` calls the parser exactly
            once for the new topology, re-runs layout (new layout-key), and
            re-projects. Switching back to the original definition reparses
            again (the cache holds the LAST definition only — a per-chart
            single-slot memo keyed on the current definition)."
    (with-seam-spies
      (fn [counts]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          (render! rfn {:machine-id :m :definition machine-a})
          (is (= 1 (:parse @counts)) "machine-a parses once")
          (is (= 1 (:layout @counts)) "machine-a lays out once")
          ;; Swap to a NEW definition.
          (render! rfn {:machine-id :m :definition machine-b})
          (is (= 2 (:parse @counts)) "a new definition reparses once")
          (is (= 2 (:layout @counts)) "a new definition re-runs layout")
          ;; Re-render machine-b unchanged → cache hit, no reparse/relayout.
          (render! rfn {:machine-id :m :definition machine-b
                        :current-state :on})
          (is (= 2 (:parse @counts)) "unchanged definition does NOT reparse")
          (is (= 2 (:layout @counts)) "unchanged definition does NOT relayout")
          ;; Swap back to machine-a → reparse (single-slot cache).
          (render! rfn {:machine-id :m :definition machine-a})
          (is (= 3 (:parse @counts)) "swapping back reparses (single-slot memo)")
          (is (= 3 (:layout @counts)) "swapping back re-runs layout"))))))

(deftest density-direction-layout-options-changes-do-not-reparse
  (testing "rf2-jl72i — density / direction / layout-options are layout
            props (they re-run ELK), but they MUST NOT reparse: the parse
            is keyed ONLY on `:definition`. This pins the parse-cache key
            against a future regression that folds density/direction into
            the parse key."
    (with-seam-spies
      (fn [counts]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          (render! rfn {:machine-id :m :definition machine-a})
          (is (= 1 (:parse @counts)) "first render parses once")
          (let [layouts-after-mount (:layout @counts)]
            ;; --- density switch (structural for layout, rf2-8q5pt) ---
            (render! rfn {:machine-id :m :definition machine-a :density :compact})
            (render! rfn {:machine-id :m :definition machine-a :density :cosy})
            ;; --- direction switch ---
            (render! rfn {:machine-id :m :definition machine-a :direction :lr})
            ;; --- layout-options change ---
            (render! rfn {:machine-id :m :definition machine-a
                          :layout-options {"elk.spacing.nodeNode" "80"}})
            (is (= 1 (:parse @counts))
                "density / direction / layout-options changes do NOT reparse")
            (is (> (:layout @counts) layouts-after-mount)
                "but they DO re-run layout (the layout-key includes them)")))))))

(deftest prev-next-highlight-deltas-do-not-rerun-elk-rf2-un3gfo
  (testing "rf2-un3gfo — the chart end of the no-flicker contract. When the
            Xray Machine panel's section key is STABLE across Prev/Next (the
            panel-side fix, pinned in
            `machine-inspector-helpers-cljs-test/section-key-is-stable-…`),
            the SAME MachineChart instance receives the per-epoch highlight
            deltas as prop changes — `:from-highlight` / `:to-highlight` /
            `:current-state` / `:fired-edge-ids` — with the `:definition`
            (hence the layout-key) UNCHANGED. Those deltas MUST NOT re-run
            ELK: the topology positions stay put and only the highlights
            re-paint. (Before the key fix, each Prev/Next REMOUNTED the
            chart, so ELK re-ran from scratch every navigation → the
            flicker.) This simulates the exact prop sequence the preserved
            instance now sees."
    (with-seam-spies
      (fn [counts]
        ;; Mount on the focused machine's first transition (idle → loading).
        (let [rfn (chart/MachineChart
                    {:machine-id     :m :definition machine-a
                     :from-highlight :idle :to-highlight :loading
                     :fired-edge-ids ["idle->loading"]})]
          (render! rfn {:machine-id     :m :definition machine-a
                        :from-highlight :idle :to-highlight :loading
                        :fired-edge-ids ["idle->loading"]})
          (is (= 1 (:parse @counts)) "mount parses once")
          (is (= 1 (:layout @counts)) "mount runs ELK once (new layout-key)")
          ;; --- Next → the loading → done transition ---
          (render! rfn {:machine-id     :m :definition machine-a
                        :from-highlight :loading :to-highlight :done
                        :fired-edge-ids ["loading->done"]})
          ;; --- Next again → a no-op resting in :done (current-state grammar) ---
          (render! rfn {:machine-id    :m :definition machine-a
                        :current-state :done})
          ;; --- Prev → back to loading → done ---
          (render! rfn {:machine-id     :m :definition machine-a
                        :from-highlight :loading :to-highlight :done
                        :fired-edge-ids ["loading->done"]})
          (is (= 1 (:parse @counts))
              "Prev/Next highlight deltas (same definition) NEVER reparse")
          (is (= 1 (:layout @counts))
              "Prev/Next highlight deltas NEVER re-run ELK — positions stay
               put, only highlights re-paint (the no-flicker guarantee)")
          (is (>= (:project @counts) 1)
              "highlight deltas DO re-project (decorative re-tint) — that is
               the cheap repaint, not a relayout"))))))
