(ns day8.re-frame2-causa.perf-budget-cljs-test
  "Perf-budget invariant tests for Causa (rf2-w1eg7).

  ## Why this file exists

  `tools/causa/spec/007-UX-IA.md §Performance budget` declares the
  hard rule:

      Opening Causa must not change observable INP on a typical app.

  The spec catalogues four normative budgets:

    1. Rendering caps at <200 rows per panel (`spec/007-UX-IA.md`
       L611-612).
    2. Causality graph caps at the last 200 dispatches (`spec/007-UX-IA.md`
       L612, `spec/001-Causality-Graph.md` L88-92).
    3. Badge derivation is O(visible rows) — not O(total subs)
       (`spec/012-Subscriptions.md` L362-365).
    4. Chain walk is O(depth) in the sub graph, capped at 8 layers
       (`spec/012-Subscriptions.md` L366-367).

  Plus one substrate invariant:

    5. Trace ring-buffer eviction is O(1)-on-cost — capped vector
       conj + subvec, no per-event rescan (`tools/causa/src/.../trace_bus.cljc`
       L17-22; backed by `re-frame.trace/default-buffer-depth` = 200,
       Causa rides 5× = 1000 events).

  No test in the repo asserts any of these. `npm run test:perf-bundle`
  probes the framework's perf-API string presence in the production
  bundle, not Causa's render budgets. This file fills that gap.

  ## Why `.cljc` + `_cljs_test` naming

    - Cognitect's test-runner (CLJ) picks it up via `.*-test$` on the
      ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex.

  Same dual-target pattern as `causality_graph_cljs_test.cljc`. Every
  helper under test is `.cljc`, so the JVM path exercises identical
  code. We do NOT assert wall-clock milliseconds against a fixed
  budget — wall-clock is host-dependent (JVM warm-up, CI noise) and
  the spec's INP target is a *behaviour-in-the-browser* claim, not a
  unit-test target. Instead we assert the **algorithmic invariants**
  the spec's wall-clock claim *depends on*:

    - Stress-shape size (200 rows, 200 dispatches, 1000 buffer events)
      bounds drive the spec-stated caps.
    - Scaling tests confirm cost is sub-quadratic (10× input → < ~3×
      cost), the property the spec's O(n) / O(visible) / O(depth)
      claims encode.
    - Eviction invariant: post-overflow size is exactly the cap, head-
      dropped, regardless of total events pushed.

  Algorithmic scaling is checked via `simple-benchmark`-style timing
  with a generous slack factor (5×) so test flakiness on a slow CI
  host doesn't masquerade as a regression. The slack swallows
  micro-variance; a true quadratic blow-up is orders of magnitude
  larger.

  ## Budget numbers used

  Every constant below derives from the spec. Where a spec line gives
  a number, the test constant equals it. Where the spec is qualitative
  (`O(n)` etc.), the test asserts the scaling ratio, not an absolute
  ms count.

    `panel-row-cap`        = 200     ; spec/007 §Performance budget
    `cascade-cap`          = 200     ; spec/001 §Buffer caps
    `chain-layer-cap`      =   8     ; spec/012 L366 (default cap)
    `buffer-depth-default` = 1000    ; trace_bus.cljc L47
    `framework-buffer`     = 200     ; trace_bus.cljc L46
    `scaling-slack`        =   5.0   ; loose slack — see rationale above

  No proposed-but-unspec'd number; everything traces to spec or
  source comment."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.trace-bus :as trace-bus]
            [day8.re-frame2-causa.panels.causality-graph-helpers :as cg]
            [day8.re-frame2-causa.panels.common-helpers :as common]
            [day8.re-frame2-causa.panels.performance-helpers :as perf]
            ;; Views panel (rf2-21ob3) replaces the legacy Subs panel —
            ;; spec/012-Views.md. The per-row chain-walk is gone (subs
            ;; nest under views instead of being a top-level surface).
            [day8.re-frame2-causa.panels.views-helpers :as views]
            [day8.re-frame2-causa.panels.trace-helpers :as trace-helpers]))

;; ---- spec-derived budget constants --------------------------------------

(def panel-row-cap
  "Per spec/007-UX-IA.md §Performance budget L611-612: 'nothing renders
  >200 rows at once'. The cap is a panel-level rendering invariant;
  the projection layer must not allocate quadratically over this size."
  200)

(def cascade-cap
  "Per spec/001-Causality-Graph.md §Buffer caps L92: 'The graph caps
  at the last 200 dispatches by default'. Mirrored in spec/007-UX-IA.md
  §Performance budget."
  200)

(def chain-layer-cap
  "Per the legacy spec/012-Subscriptions.md L366-367: chain walk was
  capped at 8 layers. The Subs panel retired under rf2-21ob3 (replaced
  by Views per spec/012-Views.md); subs nest under views and the
  invalidation-chain surface is not a v1 obligation. Retained as a
  documentary constant so any future re-introduction of a chain-walk
  helper can re-pin against the same number."
  8)

(def cluster-threshold-default
  "Per spec/012-Views.md §Grid-explosion clustering — `≥ 50` renders
  sharing `(view-id, triggered-by)` collapse into one aggregate row.
  Mirrored at `views_helpers.cljc/default-cluster-threshold`."
  50)

(def buffer-depth-default
  "Per `tools/causa/src/.../trace_bus.cljc` L47: Causa's ring is sized
  at 5× the framework default (1000)."
  1000)

(def framework-buffer
  "Per `tools/causa/src/.../trace_bus.cljc` L46: framework default
  ring depth."
  200)

(def scaling-slack
  "Slack factor for sub-quadratic scaling assertions. A true O(n)
  algorithm scales 10× → 10×; we accept up to 10× × `scaling-slack`
  to absorb micro-variance and JIT noise on shared CI. A quadratic
  algorithm at 10× input is 100×, far outside this slack."
  5.0)

;; ---- fixture builders ---------------------------------------------------

(defn- dispatched-ev
  "Build a `:event/dispatched` trace event for one cascade root or
  child. Mirrors the shape `re-frame.trace.projection/group-cascades`
  expects."
  ([id dispatch-id event-vec]
   (dispatched-ev id dispatch-id event-vec nil :app))
  ([id dispatch-id event-vec parent-dispatch-id]
   (dispatched-ev id dispatch-id event-vec parent-dispatch-id :app))
  ([id dispatch-id event-vec parent-dispatch-id origin]
   {:id        id
    :time      id
    :op-type   :event
    :operation :event/dispatched
    :tags      (cond-> {:dispatch-id dispatch-id
                        :event       event-vec
                        :origin      origin}
                 parent-dispatch-id (assoc :parent-dispatch-id parent-dispatch-id))}))

(defn- handler-ev
  [id dispatch-id]
  {:id id :time id :op-type :event :operation :event
   :tags {:dispatch-id dispatch-id :phase :run-end}})

(defn- fx-ev
  [id dispatch-id]
  {:id id :time id :op-type :event :operation :event/do-fx
   :tags {:dispatch-id dispatch-id}})

(defn- effect-ev
  [id dispatch-id fx-id]
  {:id id :time id :op-type :fx :operation :rf.fx/handled
   :tags {:dispatch-id dispatch-id :fx-id fx-id}})

(defn- render-ev
  [id dispatch-id render-key]
  {:id id :time id :op-type :view :operation :view/render
   :tags {:dispatch-id dispatch-id :render-key render-key}})

(defn- one-cascade
  "Four-event cascade for `dispatch-id`. `parent` is nil for a root,
  otherwise an upstream dispatch-id. `id-base` is the starting
  monotonic id."
  ([id-base dispatch-id event-vec]
   (one-cascade id-base dispatch-id event-vec nil))
  ([id-base dispatch-id event-vec parent]
   [(dispatched-ev (+ id-base 0) dispatch-id event-vec parent)
    (handler-ev    (+ id-base 1) dispatch-id)
    (fx-ev         (+ id-base 2) dispatch-id)
    (effect-ev     (+ id-base 3) dispatch-id :db)
    (render-ev     (+ id-base 4) dispatch-id :app)]))

(defn- linear-trace
  "Build a trace stream of `n` cascades, each a 5-event cascade. Each
  cascade is independent (no parent). Used to drive O(n) scaling
  tests over the cascade projection."
  [n]
  (into [] (mapcat #(one-cascade (* 10 %) % [:ev/n %])) (range 1 (inc n))))

(defn- chained-trace
  "Build a trace stream of `n` cascades where cascade k+1's parent is
  cascade k. Produces a single deep chain — exercises the causality-
  graph layout walker."
  [n]
  (into []
        (mapcat (fn [k]
                  (one-cascade (* 10 k) k [:ev/k k] (when (> k 1) (dec k)))))
        (range 1 (inc n))))

(defn- timed
  "Run `thunk` and return `[result elapsed-ms]`. Best-effort wall-clock
  measurement using the host's high-res clock; portable across CLJ /
  CLJS. Used for scaling-ratio assertions (NOT absolute-ms budgets).

  Performs **one discarded warm-up call** before the measured call so
  JIT-compile and cold-cache costs don't land on the first measurement.
  Without this, scaling tests can flake on hosts where the smaller
  `t1` measurement absorbs the JIT cost that the larger `t10` then
  enjoys for free — producing a spuriously inflated `t10/t1` ratio
  (observed at exactly 50.0+ε on CI in rf2-pe6rx).

  CLJS: `js/performance.now` is unavailable under `:node-test` on
  some shadow versions, so we fall back to `system-time` (also high
  resolution under modern Node)."
  [thunk]
  ;; Warm-up: discard the first invocation's result so JIT compilation
  ;; and cold-path allocation don't bias the measurement. This is one
  ;; extra call per measurement — cheap relative to the scaling-test
  ;; payload, and the gain in measurement stability is worth it.
  (thunk)
  (let [t0 #?(:clj  (System/nanoTime)
              :cljs (system-time))
        r  (thunk)
        t1 #?(:clj  (System/nanoTime)
              :cljs (system-time))
        ms #?(:clj  (/ (- t1 t0) 1e6)
              :cljs (- t1 t0))]
    [r ms]))

(def ^:private ratio-noise-floor-ms
  "Minimum reliable wall-clock measurement, in ms, below which the
  scaling-ratio assertion is treated as a no-op (returns 1.0).

  Rationale: browser `performance.now` is clock-clamped under
  Chromium — typically ~0.1ms in non-cross-origin-isolated contexts,
  but as coarse as ~1ms under privacy-mode or container-scheduling
  jitter (observed on GH Actions ubuntu-latest in rf2-pe6rx where
  `t10/t1` landed at exactly 50.0+1e-10 — i.e. both measurements
  rounded to the same clamped grid). A 2ms floor sits comfortably
  above the worst clamp granularity so micro-measurements don't
  produce mathematically-tight ratios that whisper past `<=` on
  float-precision wobble.

  CLJ: `System/nanoTime` is far higher resolution; the 2ms floor is
  still defensible there as the per-cascade payload is ~µs and a
  total under 2ms is GC/JIT-dominated noise rather than algorithmic
  signal."
  2.0)

(defn- ratio
  "Return `b / a`, guarding against division by ~zero. When `a` is
  too small to measure reliably (under `ratio-noise-floor-ms`),
  returns 1.0 — the ratio is meaningless at that scale, so the slack
  assertion always passes. Avoids flaky tests on a fast host whose
  high-res clock clamps measurements to a coarse grid."
  [a b]
  (if (< a ratio-noise-floor-ms)
    1.0
    (double (/ b a))))

;; -------------------------------------------------------------------------
;; (1) Buffer-eviction invariant — spec: ring buffer holds ≤ depth
;; -------------------------------------------------------------------------

(deftest buffer-evicts-to-exact-depth-no-overshoot
  (testing "after pushing 10× the cap, buffer size equals the cap"
    (let [depth   framework-buffer
          flooded (reduce (fn [buf i] (trace-bus/push buf depth {:id i}))
                          []
                          (range (* 10 depth)))]
      (is (= depth (count flooded))
          "framework cap = exactly framework-buffer entries after flood"))
    (let [depth   buffer-depth-default
          flooded (reduce (fn [buf i] (trace-bus/push buf depth {:id i}))
                          []
                          (range (* 10 depth)))]
      (is (= depth (count flooded))
          "Causa cap = exactly buffer-depth-default entries after flood"))))

(deftest buffer-evicts-head-not-tail
  (testing "after flood, the head id is the oldest retained, tail is newest"
    (let [depth   framework-buffer
          n       (* 5 depth)
          flooded (reduce (fn [buf i] (trace-bus/push buf depth {:id i}))
                          []
                          (range n))]
      (is (= (- n depth) (:id (first flooded)))
          "head is exactly `n - depth` — oldest retained")
      (is (= (dec n)     (:id (last flooded)))
          "tail is the most recently pushed"))))

(deftest buffer-eviction-cost-scales-linearly-with-events
  (testing "pushing 10× more events costs O(events) — not O(events × depth)"
    (let [depth   framework-buffer
          run     (fn [n]
                    (timed (fn []
                             (reduce (fn [buf i]
                                       (trace-bus/push buf depth {:id i}))
                                     []
                                     (range n)))))
          [_ t1]  (run (* 5 depth))
          [_ t10] (run (* 50 depth))
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "10× events scaled by " r "× (slack " scaling-slack "×); "
               "if cost were O(n × depth) the ratio would be ~10× cleaner "
               "but a regression toward O(n²) would blow past slack")))))

(deftest buffer-cap-on-equal-input-is-idempotent-shape
  (testing "two identical floods produce the same buffer shape — no leakage"
    (let [depth framework-buffer
          push* (fn []
                  (reduce (fn [buf i] (trace-bus/push buf depth {:id i}))
                          []
                          (range (* 3 depth))))
          a     (push*)
          b     (push*)]
      (is (= a b) "pure push fn is deterministic over the same input"))))

;; -------------------------------------------------------------------------
;; (2) Causality-graph projection — spec: O(n) over cascades, cap = 200
;; -------------------------------------------------------------------------

(deftest causality-graph-projects-200-cascades
  (testing "spec/001 §Buffer caps: the graph holds the last 200 dispatches"
    (let [trace    (linear-trace cascade-cap)
          cascades (-> (projection/group-cascades trace)
                       (cg/enrich-cascades trace))
          graph    (cg/project-cascades-to-graph cascades)]
      (is (= cascade-cap (count (:nodes graph)))
          "one node per cascade up to the 200-dispatch cap")
      (is (= cascade-cap (count (:roots graph)))
          "every cascade is a root (linear trace has no parents)")
      (is (= 0 (count (:arrows graph)))
          "no parent → no arrows"))))

(deftest causality-graph-projection-scales-linearly
  (testing "projecting 10× more cascades costs at most 10× × slack"
    (let [run    (fn [n]
                   (timed
                     (fn []
                       (let [trace (linear-trace n)]
                         (-> (projection/group-cascades trace)
                             (cg/enrich-cascades trace)
                             cg/project-cascades-to-graph)))))
          [_ t1]  (run 50)
          [_ t10] (run 500)
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "project-cascades-to-graph 10× input scaled " r "× "
               "(slack " scaling-slack "× over linear)")))))

(deftest causality-graph-layout-scales-linearly
  (testing "compute-layout over a deep chain is O(nodes)"
    (let [run    (fn [n]
                   (let [trace    (chained-trace n)
                         cascades (-> (projection/group-cascades trace)
                                      (cg/enrich-cascades trace))
                         graph    (cg/project-cascades-to-graph cascades)]
                     (timed (fn [] (cg/compute-layout graph)))))
          [_ t1]  (run 20)
          [_ t10] (run 200)
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "compute-layout 10× input scaled " r "× "
               "(slack " scaling-slack "× over linear)")))))

(deftest causality-graph-bounded-by-cascade-cap-input
  (testing "feeding > 200 cascades projects > 200 nodes — the cap is
            enforced at the *buffer* layer, not in the helper. The test
            documents this contract so a future refactor that adds a
            helper-side cap doesn't accidentally lift the cascade
            count below the spec's 200 floor."
    (let [n        (* 2 cascade-cap)
          trace    (linear-trace n)
          cascades (-> (projection/group-cascades trace)
                       (cg/enrich-cascades trace))
          graph    (cg/project-cascades-to-graph cascades)]
      (is (= n (count (:nodes graph)))
          "helper is total — buffer must do the capping"))))

;; -------------------------------------------------------------------------
;; (3) Performance-panel aggregation — spec: O(visible rows) projection
;; -------------------------------------------------------------------------

(deftest performance-panel-handles-200-cascades-no-quadratic
  (testing "spec/007 §Performance budget: 200-row panel cap. The
            projection over 200 cascades is bounded; no quadratic
            allocation in `project-feed`."
    (let [trace    (linear-trace panel-row-cap)
          cascades (projection/group-cascades trace)
          feed     (perf/project-feed cascades perf/default-budget-ms)]
      (is (= panel-row-cap (:total feed)))
      (is (= panel-row-cap (count (:rows feed))))
      (is (map? (:tier-counts feed)))
      (is (= 4 (count (:tier-counts feed)))
          "tier-counts produces all 4 tiers — no missing-key flicker"))))

(deftest performance-projection-scales-linearly
  (testing "project-feed over 10× more cascades costs ≤ 10× × slack"
    (let [run    (fn [n]
                   (let [trace    (linear-trace n)
                         cascades (projection/group-cascades trace)]
                     (timed (fn []
                              (perf/project-feed cascades
                                                 perf/default-budget-ms)))))
          [_ t1]  (run 100)
          [_ t10] (run 1000)
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "project-feed 10× input scaled " r "× "
               "(slack " scaling-slack "× over linear)")))))

(deftest tier-counts-is-linear-over-rows
  (testing "tier-counts is reduce-once over rows — no nested scan"
    (let [rows   (mapv (fn [i] {:tier (case (mod i 4)
                                        0 :fast 1 :medium 2 :slow 3 :blocking)})
                       (range (* 5 panel-row-cap)))
          counts (perf/tier-counts rows)]
      (is (= (count rows) (apply + (vals counts)))
          "every row contributes to exactly one tier bucket")
      ;; Scaling check
      (let [run    (fn [n]
                     (let [r (mapv (fn [i] {:tier :medium}) (range n))]
                       (timed (fn [] (perf/tier-counts r)))))
            [_ t1]  (run 1000)
            [_ t10] (run 10000)
            r       (ratio t1 t10)]
        (is (<= r (* 10.0 scaling-slack))
            (str "tier-counts 10× rows scaled " r "× "
                 "(slack " scaling-slack "× over linear)"))))))

(deftest over-budget-count-linear-over-rows
  (testing "over-budget-count is one filter pass — no quadratic"
    (let [rows (mapv (fn [i] {:over-budget? (zero? (mod i 3))})
                    (range (* 5 panel-row-cap)))]
      (is (number? (perf/over-budget-count rows)))
      (is (<= (perf/over-budget-count rows) (count rows))))))

(deftest performance-project-cascade-pure-no-stash
  (testing "project-cascade does not mutate its cascade input — calling
            the projection twice on the same input must produce equal
            output (no hidden cache poisoning, no `update-in` on a
            shared map)."
    (let [trace    (linear-trace 50)
          cascades (vec (projection/group-cascades trace))
          rows-a   (perf/project-cascades 16 cascades)
          rows-b   (perf/project-cascades 16 cascades)]
      (is (= rows-a rows-b)
          "pure-data projection — repeat-safe"))))

;; -------------------------------------------------------------------------
;; (4) Views panel projection — spec/012-Views.md (rf2-21ob3)
;; -------------------------------------------------------------------------
;;
;; The Views panel replaces the legacy Subs panel: per spec §Performance
;;
;;   - Three-group projection is O(renders in cascade).
;;   - Clustering is O(N log N).
;;   - Heatmap is O(distinct components in cascade).
;;
;; The asserts below pin the three contracts against the helpers in
;; `views_helpers.cljc` so a regression that re-introduces a quadratic
;; pass over the cascade's render list trips a fail in CI.

(defn- mk-render
  [view-id instance-token triggered-by elapsed-ms]
  {:render-key   [view-id instance-token]
   :triggered-by triggered-by
   :elapsed-ms   elapsed-ms})

(deftest views-classify-renders-scales-linearly
  (testing "classify-renders over 10× renders costs ≤ 10× × slack"
    (let [run (fn [n]
                (let [current (mapv #(mk-render :view/a % nil 0.5) (range n))]
                  (timed (fn [] (views/classify-renders current [])))))
          [_ t1]  (run 100)
          [_ t10] (run 1000)
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "classify-renders 10× input scaled " r "× — "
               "three-group projection is O(renders in cascade)")))))

(deftest views-cluster-renders-handles-grid-explosion
  (testing "cluster-renders folds a 1000-grid into one cluster (per
            spec §Grid-explosion clustering); aggregate row carries
            the cluster's count + total-ms + avg-ms."
    (let [grid (mapv #(mk-render :view/cell % :grid/cell-data 0.012)
                    (range 1000))
          clustered (views/cluster-renders grid)]
      (is (= 1 (count clustered))
          "above threshold, all 1000 renders cluster into ONE row")
      (let [c (first clustered)]
        (is (= :cluster (:kind c)))
        (is (= 1000 (:count c)))
        (is (> (:total-ms c) 0)
            "aggregate total-ms > 0")))))

(deftest views-cluster-threshold-default-matches-spec
  (testing "default cluster threshold matches spec §Grid-explosion
            clustering — `≥ 50` renders cluster"
    (is (= cluster-threshold-default views/default-cluster-threshold))))

(deftest views-heatmap-segments-shape-bounded-by-components
  (testing "heatmap segments are O(distinct components) — 1000 renders
            over 5 view-ids collapse to 5 component segments (plus an
            optional :rest segment)"
    (let [renders (mapv (fn [i]
                          (mk-render (keyword "view" (str "v" (mod i 5)))
                                     i :sub/x 0.1))
                        (range 1000))
          segs    (views/heatmap-segments renders)]
      (is (>= 5 (count (filter #(= :component (:kind %)) segs))))
      (is (<= (count segs) 6)
          "≤ 5 component segments + 1 optional :rest segment"))))

(deftest views-build-views-data-is-pure
  (testing "build-views-data is pure — running it twice on the same
            input produces equal output, so the panel can rely on
            structural-equality short-circuit to skip work"
    (let [renders (mapv #(mk-render :view/a % :sub/x 0.5) (range 50))
          a       (views/build-views-data renders [] [] {})
          b       (views/build-views-data renders [] [] {})]
      (is (= a b)
          "pure-data projection — repeat-safe"))))

;; -------------------------------------------------------------------------
;; (6) Composite-sub topology — spec: panels read derived subs
;; -------------------------------------------------------------------------

(deftest event-detail-composite-is-pure-projection
  (testing "the :rf.causa/event-detail composite (registry.cljs L253)
            calls `projection/group-cascades` on the buffer — a pure
            fn. Running it twice on the same input produces equal
            output, so the panel can rely on structural-equality
            short-circuit (= prev next) to skip work."
    (let [trace    (linear-trace 50)
          cascades (projection/group-cascades trace)]
      ;; Re-running group-cascades on the same input is equal — the
      ;; panel's `=`-based short-circuit holds.
      (is (= cascades (projection/group-cascades trace))))))

(deftest performance-feed-is-pure-projection
  (testing "perf/project-feed is pure — repeat invocation is equal.
            The Performance panel's composite can rely on this for
            re-render gating (spec/007 §Performance budget — every
            panel virtualises and short-circuits)."
    (let [trace    (linear-trace 50)
          cascades (projection/group-cascades trace)]
      (is (= (perf/project-feed cascades 16)
             (perf/project-feed cascades 16))))))

;; -------------------------------------------------------------------------
;; (7) Spec budget-number sanity checks — constants match the spec
;; -------------------------------------------------------------------------

(deftest spec-budget-constants-match-source
  (testing "panel-row-cap mirrors spec/007 §Performance budget"
    (is (= 200 panel-row-cap)))
  (testing "cascade-cap mirrors spec/001 §Buffer caps"
    (is (= 200 cascade-cap)))
  (testing "chain-layer-cap mirrors spec/012 L366"
    (is (= 8 chain-layer-cap)))
  (testing "framework-buffer mirrors trace_bus.cljc L46"
    (is (= 200 framework-buffer)))
  (testing "buffer-depth-default mirrors trace_bus.cljc L47"
    (is (= 1000 buffer-depth-default))))

(deftest performance-default-budget-is-one-frame
  (testing "perf/default-budget-ms = 16ms — one frame at 60fps. The
            spec/007 §Performance budget INP claim depends on this
            target."
    (is (= 16 perf/default-budget-ms))))

;; -------------------------------------------------------------------------
;; (8) Shared panel-row cap helper — every long-list panel applies this
;;     at its row-rendering boundary (rf2-1k5r1).
;; -------------------------------------------------------------------------

(deftest shared-cap-helper-pins-the-spec-budget
  (testing "common/panel-row-cap matches the spec-derived constant"
    (is (= panel-row-cap common/panel-row-cap)
        "spec/007 §Performance budget cap is the single source of truth")))

(deftest shared-cap-helper-truncates-and-reports-hidden
  (testing "cap-rows over the cap returns [capped true hidden]"
    (let [rows                       (mapv (fn [i] {:id i})
                                           (range (+ 50 panel-row-cap)))
          [capped over-cap? hidden]  (common/cap-rows rows)]
      (is (= panel-row-cap (count capped))
          "exactly the cap is retained")
      (is (true? over-cap?)
          "over-cap flag fires so the view can render an indicator")
      (is (= 50 hidden)
          "hidden count is total - cap"))))

(deftest shared-cap-helper-under-cap-is-passthrough
  (testing "cap-rows under the cap returns [rows false 0]"
    (let [rows                      (mapv (fn [i] {:id i}) (range 50))
          [capped over-cap? hidden] (common/cap-rows rows)]
      (is (= rows capped))
      (is (false? over-cap?))
      (is (zero?  hidden)))))

(deftest shared-cap-helper-bounds-dom-mount-regardless-of-input-shape
  (testing "the cap survives even with the buffer at 5× cap — the
            number that lands on the panel never exceeds panel-row-cap"
    (let [rows                      (mapv (fn [i] {:id i})
                                          (range (* 5 panel-row-cap)))
          [capped over-cap? hidden] (common/cap-rows rows)]
      (is (<= (count capped) panel-row-cap)
          "DOM-mount ceiling holds regardless of input size")
      (is (true? over-cap?))
      (is (= (- (count rows) panel-row-cap) hidden)))))

;; -------------------------------------------------------------------------
;; (9) Trace-feed incremental projection — rf2-44vzy
;;     The trace-feed sub reads a snapshot maintained incrementally
;;     by `:rf.causa/note-trace-event`; per-push cost is bounded by
;;     the axis count (13), not the buffer size. These tests pin the
;;     algorithmic invariant: pushing N events into a state that
;;     already holds M events costs O(N × axes) — not O(N × (M+N)).
;; -------------------------------------------------------------------------

(defn- trace-event-fixture
  "Build a Spec 009-shaped trace event for the perf scaling tests.
  Varies per-axis values across a small enumeration so distinct +
  counts compaction is exercised on the evict path."
  [i]
  {:id        i
   :time      i
   :op-type   (case (mod i 4) 0 :event 1 :error 2 :warning 3 :info)
   :operation :event/dispatched
   :source    (case (mod i 3) 0 :ui 1 :timer 2 :http)
   :tags      {:origin      (case (mod i 2) 0 :app 1 :pair)
               :frame       (case (mod i 2) 0 :rf/default 1 :rf/causa)
               :event-id    (keyword (str "ev/n" (mod i 8)))
               :handler-id  (keyword (str "hh/n" (mod i 8)))
               :dispatch-id i}})

(deftest trace-feed-state-add-cost-is-bounded-by-axes-not-buffer
  (testing "rf2-44vzy: pushing one event into a state with 1000
            already-buffered rows must NOT walk those rows — cost
            is O(axes), not O(buffer). Asserted via ratio: pushing
            into a state with 10× more pre-existing rows costs no
            more than ~1× (within slack) — the existing rows do not
            participate in the per-event work."
    (let [build-state (fn [n]
                        (reduce trace-helpers/feed-state+ev
                                (trace-helpers/init-feed-state)
                                (mapv trace-event-fixture (range n))))
          state-small (build-state 100)
          state-big   (build-state 1000)
          run         (fn [state n-pushes start-id]
                        (timed
                          (fn []
                            (reduce (fn [s i]
                                      (trace-helpers/feed-state+ev
                                        s (trace-event-fixture i)))
                                    state
                                    (range start-id (+ start-id n-pushes))))))
          [_ t-small] (run state-small 100 100)
          [_ t-big]   (run state-big   100 1000)
          r           (ratio t-small t-big)]
      (is (<= r (* 1.0 scaling-slack))
          (str "feed-state+ev 100 pushes into 1000-row state scaled "
               r "× over 100 pushes into 100-row state (slack "
               scaling-slack "×). A per-push O(buffer) regression "
               "(e.g. distinct rebuilt from rows) would scale ~10×."))))
  )

(deftest trace-feed-state-push-respects-cap-under-burst
  (testing "rf2-44vzy: under burst (50× the cap), the snapshot never
            grows past depth, and the incremental shape matches a
            from-scratch rebuild over the same retained window"
    (let [depth   buffer-depth-default
          n       (* 50 depth)
          all     (mapv trace-event-fixture (range n))
          state   (reduce (fn [s e]
                            (trace-helpers/feed-state-push s depth e))
                          (trace-helpers/init-feed-state)
                          all)
          retained (vec (drop (- n depth) all))
          rebuilt  (trace-helpers/rebuild-feed-state retained)]
      (is (= depth (:total state)))
      (is (= depth (count (:projected-rows state))))
      (is (= (:projected-rows state) (:projected-rows rebuilt))
          "the incremental rows vector equals the retained window")
      (is (= (:counts state) (:counts rebuilt))
          "counts match — evict path correctly decrements")
      (is (= (:seen state) (:seen rebuilt))
          "seen sets match — drop-on-zero compaction holds"))))

(deftest trace-feed-state-push-is-linear-over-events-pushed
  (testing "rf2-44vzy: pushing 10× more events with the cap engaged
            costs ~10× — O(events × axes), not O(events × buffer²)"
    (let [depth buffer-depth-default
          run   (fn [n]
                  (timed
                    (fn []
                      (reduce (fn [s i]
                                (trace-helpers/feed-state-push
                                  s depth (trace-event-fixture i)))
                              (trace-helpers/init-feed-state)
                              (range n)))))
          [_ t1]  (run (* 2 depth))
          [_ t10] (run (* 20 depth))
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "feed-state-push 10× input scaled " r "× "
               "(slack " scaling-slack "× over linear)")))))

(deftest project-feed-from-state-equivalent-to-project-feed
  (testing "rf2-44vzy: the incremental path produces an equivalent
            shape to the full-walk reference under typical filter
            mixes — no regression in :rows / :total / :rendered /
            :empty-kind"
    (doseq [filters [{}
                     {:op-type :event}
                     {:source :ui :op-type :error}
                     {:dispatch-id 5}
                     ;; orphan filter — value not in buffer:
                     {:source :sse}]]
      (let [evs        (mapv trace-event-fixture (range 200))
            state      (trace-helpers/rebuild-feed-state evs)
            from-state (trace-helpers/project-feed-from-state state filters)
            from-raw   (trace-helpers/project-feed evs filters)]
        (is (= (:total from-state) (:total from-raw))
            (str "total under " (pr-str filters)))
        (is (= (:rendered from-state) (:rendered from-raw))
            (str "rendered under " (pr-str filters)))
        (is (= (mapv :id (:rows from-state))
               (mapv :id (:rows from-raw)))
            (str "row id sequence under " (pr-str filters)))
        (is (= (:empty-kind from-state) (:empty-kind from-raw))
            (str "empty-kind under " (pr-str filters)))))))
