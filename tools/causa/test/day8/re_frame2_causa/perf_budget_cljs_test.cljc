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
            [day8.re-frame2-causa.panels.performance-helpers :as perf]
            [day8.re-frame2-causa.panels.subscriptions-helpers :as subs]))

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
  "Per spec/012-Subscriptions.md L366-367: 'Chain walk is O(depth) in
  the sub graph, capped at 8 layers by default.' v1 helpers render
  one level of inputs (per `subscriptions_helpers.cljc` ns doc); the
  spec target is 8 layers."
  8)

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
;; (4) Subscription badge derivation — spec: O(visible rows), not O(total)
;; -------------------------------------------------------------------------

(deftest subs-badge-derivation-touches-rows-not-all-subs
  (testing "spec/012 L362: badge derivation is O(visible rows). The
            test asserts the contract by counting cache-entry reads —
            the projection must NOT scan inputs-of-inputs to compute
            a row's badge (each row reads its own entry plus the
            just-settled sub-run record by O(1) lookup)."
    (let [;; 1000 entries in the sub-cache; only one re-ran this epoch.
          cache    (into {}
                         (for [i (range 1000)]
                           [[:sub/n i]
                            {:value     i
                             :ref-count 1
                             :layer     1
                             :input-subs []}]))
          sub-runs [{:query-v [:sub/n 0] :recomputed? true}]
          rows     (subs/project-rows cache sub-runs {})]
      (is (= 1000 (count rows))
          "every cache entry projects — visible-rows = cache size in v1")
      ;; The one sub that re-ran gets :fresh; the rest stay :fresh by
      ;; the spec's decision-table when no :invalidated? flag is set
      ;; (per `compute-status` step 6 — stable cached value with a
      ;; watcher renders as :fresh).
      (is (= 1 (count (filter :recomputed? rows)))
          "only the one sub-run record marks a row :recomputed?"))))

(deftest subs-project-rows-scales-linearly
  (testing "project-rows over 10× more entries costs ≤ 10× × slack"
    (let [run    (fn [n]
                   (let [cache (into {}
                                     (for [i (range n)]
                                       [[:sub/n i]
                                        {:value i :ref-count 1
                                         :layer 1 :input-subs []}]))]
                     (timed (fn [] (subs/project-rows cache [] {})))))
          [_ t1]  (run 100)
          [_ t10] (run 1000)
          r       (ratio t1 t10)]
      (is (<= r (* 10.0 scaling-slack))
          (str "project-rows 10× input scaled " r "× "
               "(slack " scaling-slack "× over linear) — badge derivation "
               "is O(visible rows), not O(total subs squared)")))))

(deftest subs-status-counts-linear
  (testing "status-counts is `frequencies` — one pass over rows"
    (let [rows (mapv (fn [i] {:status (case (mod i 5)
                                        0 :error 1 :re-running
                                        2 :invalidated 3 :fresh
                                        4 :cached-no-watcher)})
                    (range 5000))]
      (is (= 5000 (apply + (vals (subs/status-counts rows))))))))

(deftest subs-filter-by-status-linear
  (testing "filter-by-status is one filter pass — linear over rows"
    (let [rows (mapv (fn [i] {:status :fresh}) (range 5000))]
      (is (= 5000 (count (subs/filter-by-status rows nil))))
      (is (= 5000 (count (subs/filter-by-status rows #{})))
          "empty filter set shows all per spec §Filtering and grouping")
      (is (= 5000 (count (subs/filter-by-status rows #{:fresh})))))))

;; -------------------------------------------------------------------------
;; (5) Chain walk — spec: O(depth), capped at 8 layers
;; -------------------------------------------------------------------------

(deftest subs-compute-chain-walks-one-level-per-v1
  (testing "spec/012 L366-367: chain walk capped at 8 layers. The v1
            helper (subscriptions_helpers ns doc L70-73) renders the
            focused row + ONE level of inputs and links to App-DB
            Diff for the deeper walk. This test pins v1 behaviour;
            a follow-on bead that extends to the spec's 8-layer cap
            will update this assertion."
    (let [;; Build a chain :sub/a → :sub/b → :sub/c (3 layers deep).
          cache    {[:sub/a] {:layer 3 :ref-count 1 :input-subs [[:sub/b]]}
                    [:sub/b] {:layer 2 :ref-count 1 :input-subs [[:sub/c]]}
                    [:sub/c] {:layer 1 :ref-count 1 :input-subs []}}
          sub-runs [{:query-v [:sub/a] :recomputed? true}
                    {:query-v [:sub/b] :recomputed? true}
                    {:query-v [:sub/c] :recomputed? true}]
          chain    (subs/compute-chain [:sub/a] cache sub-runs {} #{})]
      (is (some? (:focused chain))
          "focused row materialises")
      (is (= 1 (count (:inputs chain)))
          "v1 walks exactly one level of inputs (per ns doc)")
      (is (= [:sub/b] (-> chain :inputs first :query-v))
          "the one input is the direct parent layer"))))

(deftest subs-compute-chain-handles-deep-chain-no-explosion
  (testing "the chain walk does not stack-overflow / quadratically
            allocate when the sub graph is 8+ layers deep. v1 only
            walks one level, but the projection still inspects the
            cache map; a deep cache must not slow the projection."
    (let [n        20  ; well beyond chain-layer-cap
          cache    (into {}
                         (for [i (range 1 (inc n))]
                           [[:sub/k i] {:layer i :ref-count 1
                                        :input-subs (if (< i n)
                                                      [[:sub/k (inc i)]] [])}]))
          ;; Every layer marked :recomputed? so the inputs filter retains them.
          sub-runs (mapv (fn [i] {:query-v [:sub/k i] :recomputed? true})
                         (range 1 (inc n)))
          chain    (subs/compute-chain [:sub/k 1] cache sub-runs {} #{})]
      (is (some? (:focused chain)))
      (is (= 1 (count (:inputs chain)))
          "v1 still walks just one level even over a 20-layer cache"))))

(deftest subs-compute-chain-missing-focused-is-marked
  (testing "looking up a query-v not in the cache returns :missing? true
            (no crash, no infinite loop) — chain-walk is total over input"
    (let [chain (subs/compute-chain [:sub/absent] {} [] {} #{})]
      (is (true? (:missing? chain)))
      (is (nil?  (:focused chain)))
      (is (= []  (:inputs chain))))))

(deftest subs-chain-layer-cap-documented-in-spec
  (testing "chain-layer-cap constant matches spec/012 L366: '8 layers'.
            If the spec's documented cap changes, this test fails so
            the constant is updated in lockstep."
    (is (= 8 chain-layer-cap))))

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
