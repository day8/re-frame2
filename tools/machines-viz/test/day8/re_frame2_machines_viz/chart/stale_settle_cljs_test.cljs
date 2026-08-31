(ns day8.re-frame2-machines-viz.chart.stale-settle-cljs-test
  "rf2-x19xi — a stale async ELK settle must not overwrite the current
  topology.

  ## What this pins

  `MachineChart` launches a new async ELK pass whenever the layout key
  (definition / direction / layout-options / density / context-rows /
  adaptive-mode tuple) changes, and keeps the PREVIOUS committed layout
  visible while the pass is in flight. Each pass callback closes over
  the `this-key` it was launched for. Before rf2-x19xi the callback
  committed its result UNCONDITIONALLY: a slow result for topology A
  resolving after topology B had already settled would overwrite B's
  positions / routed edge points / edge-label positions / layout-error
  state and schedule a fit for A — B node ids absent from A's result
  fall back to the `{x 0 y 0}` origin, so the visible current machine
  collapses at the origin or carries another topology's routes.

  The invariant (the rf2-x19xi stale-settle guard): only the completion
  whose closed-over key equals the chart instance's CURRENT
  `layout-key` may mutate visible layout state or trigger post-settle
  fitting. Completion order must not change the final visualization —
  for the initial pass and the measured-relayout pass alike.

  ## How the settle order is controlled (deterministic, no sleeps)

  `chart/compute-layout!` is rebound via `set!` to a stub that CAPTURES
  each pass's `done-fn` (plus its parsed graph and `measured-dims`)
  instead of running elkjs. The test then invokes the captured
  callbacks directly, in whatever order the scenario needs — the
  degenerate, fully deterministic form of \"control the Promise
  resolution order\". No Promise, no timer, no rAF is involved
  anywhere (in this Node runtime `schedule-fit!` takes its synchronous
  no-rAF fallback, so fit scheduling is observable immediately).

  The committed layout is observed at the projection boundary:
  `projection/xyflow-graph` is rebound to record the `positions` /
  `:edge-points` / `:edge-labels` each render feeds it — exactly the
  maps a stale commit corrupts. The fit side is observed through the
  `chart/invoke-fit-view!` seam, and the measured-relayout pass is
  driven through the `:onInit` prop (extracted from the returned
  hiccup) with `chart/read-measured-dims` stubbed — the same seam
  idioms `parse-cache-cljs-test` and `auto-fit-view-cljs-test` use.

  ## Why this is a `-cljs-test` (node), not a DOM test

  Same reasoning as `parse-cache-cljs-test`: calling the Form-2 inner
  render fn repeatedly with new prop maps IS the host-driven re-render
  sequence, building the hiccup tree is pure CLJS data, and every
  framework-reaching call goes through a `set!`-able seam. The whole
  stale-settle contract is therefore Node-runnable and rides the
  always-on `npm run test:cljs` gate plus the artefact's own
  `npm run test:tools-machines-viz` lane."
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

(def ^:private parsed-a
  "Stubbed parse of `machine-a` — node ids DISJOINT from `parsed-b`'s so
  a cross-topology commit is unambiguous (a B render reading A's
  positions map finds NONE of its ids and falls back to the origin)."
  {:nodes [{:id "a-idle"} {:id "a-loading"}] :edges [] :initial-path [:a-idle]})

(def ^:private parsed-b
  {:nodes [{:id "b-off"} {:id "b-on"}] :edges [] :initial-path [:b-off]})

(def ^:private a-result
  "A-only sentinel settle: positions, routed edge points and edge-label
  positions that exist for NO b-* id."
  {:positions   {"a-idle"    {:x 900 :y 901 :width 100 :height 50}
                 "a-loading" {:x 902 :y 903 :width 100 :height 50}}
   :edge-points {"a-edge__out" [{:x 9 :y 9} {:x 10 :y 10}]}
   :edge-labels {"a-edge__out" {:x 11 :y 11}}})

(def ^:private a-measured-result
  "A-only sentinels DISTINCT from `a-result`'s, so a leaked measured-
  relayout commit is distinguishable from a leaked initial commit."
  {:positions   {"a-idle"    {:x 700 :y 701 :width 120 :height 60}
                 "a-loading" {:x 702 :y 703 :width 140 :height 60}}
   :edge-points {"a-edge__out" [{:x 7 :y 7} {:x 8 :y 8}]}
   :edge-labels {"a-edge__out" {:x 6 :y 6}}})

(def ^:private a-error-result
  "The layout-error result shape `compute-layout!`'s failure path
  produces (empty maps + `:layout-error`)."
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
  "Stand-in for the xyflow ReactFlowInstance (same idiom as
  `auto-fit-view-cljs-test`). Everything the chart calls on it —
  `.fitView` and the measured-box read — is behind a stubbed seam, so
  an opaque pointer is all the call sites need."
  #js {:__name "fake-xyflow-instance"})

;; ---- harness ------------------------------------------------------------

(defn- find-prop
  "Walk a hiccup tree for the first map carrying key `k` and return that
  key's value (e.g. the ReactFlow `:onInit` handler, or the root div's
  `:data-layout-error` attr)."
  [hiccup k]
  (->> (tree-seq sequential? seq hiccup)
       (filter map?)
       (some #(when (contains? % k) (get % k)))))

(defn- with-stale-seams
  "Run `(f captured)` with the five framework seams the stale-settle
  regression needs rebound via `set!`, then restore the originals.
  `captured` is a map of atoms:

    :passes      — one entry per `compute-layout!` call:
                   `{:parsed p :measured-dims md :done done-fn}`. The
                   real elk pass NEVER runs; the test invokes each
                   captured `done-fn` directly to control settle order.
    :projections — one entry per `projection/xyflow-graph` call:
                   `{:positions … :edge-points … :edge-labels …}` — the
                   layout maps the render actually fed the projector.
    :fit-calls   — one entry per `invoke-fit-view!` call.
    :measured    — the map `read-measured-dims` returns (the test loads
                   it to arm the measure-then-relayout pass).

  `invoke-project-definition!` is stubbed to the fixed parses above (the
  parse itself is `parse-cache-cljs-test`'s concern, not this suite's).
  The `compute-layout!` stub mirrors the real fn's MULTI-arity shape:
  shadow compiles the render's call to the direct `arity$8` dispatch, so
  a single fixed-arity `fn` would not be reached."
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

;; ---- 1. stale INITIAL settle is dropped (layout state + fit) -----------

(deftest stale-initial-settle-is-dropped
  (testing "rf2-x19xi — definition A's slow initial settle resolving
            AFTER definition B has settled must be dropped whole: B's
            committed positions/routes/edge-labels survive, and the
            stale completion neither updates :fit-key nor calls
            invoke-fit-view!."
    (with-stale-seams
      (fn [{:keys [passes projections fit-calls]}]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          ;; Render A, then B, WITHOUT completing either pass.
          (rfn {:machine-id :m :definition machine-a})
          (rfn {:machine-id :m :definition machine-b})
          (is (= 2 (count @passes))
              "exactly two layout passes captured (A's then B's)")
          (is (= [parsed-a parsed-b] (mapv :parsed @passes))
              "pass 1 is A's, pass 2 is B's")
          ;; Non-vacuity: before any settle, every projection saw the
          ;; empty pre-layout fallback.
          (is (and (seq @projections)
                   (every? #(empty? (:positions %)) @projections))
              "pre-settle renders project the empty pre-layout fallback")
          ;; Capture a fit instance through the :onInit hiccup seam (no
          ;; positions yet, so onInit itself schedules no fit).
          (let [on-init (find-prop (rfn {:machine-id :m :definition machine-b})
                                   :onInit)]
            (is (fn? on-init) "hiccup exposes the :onInit seam")
            (on-init fake-instance))
          (is (zero? (count @fit-calls))
              "no fit before any settle (onInit found no positions)")
          ;; B (the CURRENT key) settles first.
          ((:done (nth @passes 1)) b-result)
          (is (= 1 (count @fit-calls))
              "the current B settle schedules exactly the existing one
               post-settle fit")
          (rfn {:machine-id :m :definition machine-b})
          (is (= (:positions b-result) (:positions (last @projections)))
              "non-vacuity: B's settle visibly moves the projection off
               the pre-layout fallback before the stale result arrives")
          ;; This render also fires the ORTHOGONAL first-observed
          ;; :fit-signal entry fit (fit-sig ::unfit → nil) — existing
          ;; behavior, not under test; pin it so the later \"no stale
          ;; fit\" delta is exact.
          (is (= 2 (count @fit-calls))
              "the first post-settle render adds only the orthogonal
               entry fit")
          ;; A's pass (the STALE key) resolves late.
          ((:done (nth @passes 0)) a-result)
          (is (= 2 (count @fit-calls))
              "a stale settle must NOT schedule a fit (no :fit-key swap,
               no invoke-fit-view!)")
          (rfn {:machine-id :m :definition machine-b})
          (let [final (last @projections)]
            (is (= (:positions b-result) (:positions final))
                "B's committed positions survive the stale A settle —
                 the stale result is dropped, not committed")
            (is (= (:edge-points b-result) (:edge-points final))
                "B's routed edge points survive the stale A settle")
            (is (= (:edge-labels b-result) (:edge-labels final))
                "B's edge-label positions survive the stale A settle"))
          (is (= 2 (count @fit-calls))
              "re-rendering B after the stale settle schedules nothing
               further (fit state undisturbed)"))))))

;; ---- 2. stale MEASURED-RELAYOUT settle is dropped ----------------------

(deftest stale-measured-relayout-settle-is-dropped
  (testing "rf2-x19xi — the measure-then-relayout SECOND pass closes
            over the same key as its initial pass, so A's measured
            relayout resolving after B has settled is equally stale and
            equally dropped. Also pins the in-flight behavior: after B
            launches and before B settles, A's last committed layout
            stays visible (no empty-graph flash)."
    (with-stale-seams
      (fn [{:keys [passes projections fit-calls measured]}]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          ;; Render A and let its initial pass settle — A is current.
          (rfn {:machine-id :m :definition machine-a})
          (is (= 1 (count @passes)) "A's initial pass captured")
          ((:done (nth @passes 0)) a-result)
          ;; Arm the measured boxes for A's measurable ids and drive the
          ;; measure seam (:onInit → maybe-relayout!) — this launches
          ;; A's MEASURED relayout pass.
          (reset! measured {"a-idle"    {:width 120 :height 60}
                            "a-loading" {:width 140 :height 60}})
          (let [on-init (find-prop (rfn {:machine-id :m :definition machine-a})
                                   :onInit)]
            (is (fn? on-init) "hiccup exposes the :onInit seam")
            (on-init fake-instance))
          (is (= 2 (count @passes))
              "the measure seam launched A's measured relayout pass")
          (is (= {"a-idle"    {:width 120 :height 60}
                  "a-loading" {:width 140 :height 60}}
                 (:measured-dims (nth @passes 1)))
              "non-vacuity: pass 2 IS the measured relayout (non-nil
               measured dims fed back to ELK)")
          ;; Switch to B BEFORE the measured relayout settles.
          (rfn {:machine-id :m :definition machine-b})
          (is (= 3 (count @passes)) "B's initial pass captured")
          (is (= (:positions a-result)
                 (:positions (last @projections)))
              "in-flight behavior preserved: while B's pass is pending,
               the B render still projects A's last committed layout
               rather than flashing an empty graph")
          ;; B settles — B is current.
          ((:done (nth @passes 2)) b-result)
          (rfn {:machine-id :m :definition machine-b})
          (is (= (:positions b-result) (:positions (last @projections)))
              "B's settle commits (B is the current key)")
          (let [fits-after-b (count @fit-calls)]
            ;; A's measured relayout (the STALE key) resolves last.
            ((:done (nth @passes 1)) a-measured-result)
            (is (= fits-after-b (count @fit-calls))
                "a stale measured-relayout settle must NOT schedule a
                 fit")
            (rfn {:machine-id :m :definition machine-b})
            (let [final (last @projections)]
              (is (= (:positions b-result) (:positions final))
                  "B's committed positions survive the stale measured-
                   relayout settle")
              (is (= (:edge-points b-result) (:edge-points final))
                  "B's routed edge points survive the stale measured-
                   relayout settle"))
            (is (= fits-after-b (count @fit-calls))
                "fit state stays undisturbed after the stale measured-
                 relayout settle")))))))

;; ---- 3. stale ERROR settle does not surface ----------------------------

(deftest stale-error-settle-does-not-surface
  (testing "rf2-x19xi — a stale pass resolving with a LAYOUT-ERROR
            result must not replace B's committed layout with the empty
            error shape nor paint the layout-error banner over the
            healthy current topology."
    (with-stale-seams
      (fn [{:keys [passes projections]}]
        (let [rfn (chart/MachineChart {:machine-id :m :definition machine-a})]
          (rfn {:machine-id :m :definition machine-a})
          (rfn {:machine-id :m :definition machine-b})
          (is (= 2 (count @passes))
              "exactly two layout passes captured (A's then B's)")
          ;; B settles — current, healthy.
          ((:done (nth @passes 1)) b-result)
          (rfn {:machine-id :m :definition machine-b})
          (is (= (:positions b-result) (:positions (last @projections)))
              "B's settle commits")
          ;; A's pass fails LATE with the error result shape.
          ((:done (nth @passes 0)) a-error-result)
          (let [hiccup (rfn {:machine-id :m :definition machine-b})
                final  (last @projections)]
            (is (= (:positions b-result) (:positions final))
                "B's committed positions survive a stale ERROR settle
                 (the empty error shape is not committed)")
            (is (= "false" (find-prop hiccup :data-layout-error))
                "the healthy current topology does not surface the
                 stale pass's layout-error banner")))))))
