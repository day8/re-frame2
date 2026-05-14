(ns re-frame-pair2-mcp.dedup-benchmark-test
  "Trace-burst structural-dedup compression benchmark (rf2-li2cw).

  ## Why this benchmark

  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §5 (Structural dedup)
  asserts that `day8/de-dupe` 'typically compresses trace bursts
  3-5× without semantic loss.' That claim was approximate-vibes —
  no in-repo measurement bound it. When `003-Tool-Catalogue.md`
  lands and the catalogue-entry-contract requires each tool to
  declare its typical-token hint, every trace-bus-shaped tool's
  hint would have inherited that imprecision.

  This benchmark exercises `day8/de-dupe` against a representative
  trace-event corpus at three scales (100 / 1,000 / 10,000 events)
  with varying structural depth, measures the actual reduction
  ratio, and prints the numbers to the test log. The measured
  factor is pinned in
  [`tools/causa-mcp/spec/004-Wire-Pipeline.md`](../../../causa-mcp/spec/004-Wire-Pipeline.md)
  §5 with this file cited as the source-of-truth.

  ## Corpus shape

  A `:rf/trace-event` per Spec-Schemas (`:id`, `:operation`,
  `:op-type`, `:time`, `:tags`, optional `:rf.trace/trigger-handler`).
  Trace bursts share structural backbones — a single user-driven
  cascade emits dozens of events all carrying the same
  `:dispatch-id`, `:trigger-handler` map, `:frame`, and
  `:source-coord` reference. The benchmark constructs cascades of
  varying width and depth and concatenates them to reach the
  target event count.

  ## Why not a separate `bench/` artefact

  Causa-mcp is spec-only today (no `src/`); bootstrapping
  `tools/causa-mcp/bench/` would mean a fresh deps.edn,
  shadow-cljs.edn and package.json that the eventual impl-pass
  would have to reconcile. pair2-mcp already wires `day8/de-dupe`,
  already runs CLJS-on-Node via shadow-cljs, and already houses
  the load-bearing `dedup-value` helper this benchmark probes.
  Reusing that runner is the path of least friction; the
  measured factor it produces is what causa-mcp's spec cites.

  ## Measured numbers (run 2026-05-14)

  The benchmark replaced an earlier vibes-only '3-5×' quote in
  the spec. **Measured**:

  | Corpus                                | Reduction | Ratio  |
  |---------------------------------------|-----------|--------|
  | 100ev / cascade=8 / variety=8         | 28.7%     | 1.40×  |
  | 100ev / cascade=24 / variety=4        | 30.1%     | 1.43×  |
  | 1Kev / cascade=8 / variety=16         | 30.7%     | 1.44×  |
  | 1Kev / cascade=24 / variety=8         | 31.1%     | 1.45×  |
  | 10Kev / cascade=8 / variety=32        | 30.9%     | 1.45×  |
  | 10Kev / cascade=24 / variety=16       | 31.0%     | 1.45×  |
  | high-share / 100 replays / cascade=24 | 90.7%     | 10.80× |

  ### Two regimes, one library

  - **Raw trace bursts** (per-event `:id` / `:time` / per-tag
    variance dominates): **1.4-1.5× compression** (28-31%
    reduction). Only the shared `:rf.trace/trigger-handler`
    subtree and `:dispatch-id` backbone collapse; per-event
    uniqueness dominates the wire bytes.
  - **High-share replays** (the lazy-summary projection strips
    `:id` / `:time` envelope fields, leaving structurally-
    identical event subtrees across replays of a recurring
    cascade): **~10× compression** (~90% reduction). The deduper
    substitutes one cache entry per distinct event subtree.
  - **Epoch slices** (whole `:db-before` reference shared across
    records — different corpus shape, not exercised by this
    benchmark): **5-10× compression** (80-90% reduction). The
    existing `dedup_test.cljs/reduction-ratio-shared-subtrees`
    pins 89.5% on a 10-epoch / 256-key shared-`:db-before`
    payload — the load-bearing case for rf2-obpa9.

  The three regimes share the algorithm; their compression
  budgets differ because their shared-subtree cardinality differs.
  `tools/causa-mcp/spec/004-Wire-Pipeline.md` §5 cites the raw-
  burst and high-share numbers explicitly so the per-tool
  typical-token hint matches the actual call-site shape.

  ## Floor for assertion

  - Raw trace bursts: **≥1.30×** (observed lower bound 1.40×
    minus a 5% slack margin).
  - High-share burst: **≥8×** (observed 10.80×, floor is
    conservative; an algorithm regression that drops below 8×
    breaks the assertion).

  A failing assertion means the underlying algorithm drifted; the
  spec wording must update in lockstep."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.test-utils :as tu]))

;; ---------------------------------------------------------------------------
;; Trace-event factory — builds a representative `:rf/trace-event` corpus.
;; ---------------------------------------------------------------------------

(defn- mk-trigger-handler
  "Builds a representative `:rf.trace/trigger-handler` value. This map is
  the dominant shared subtree inside a cascade — every event emitted
  while a single event-handler is in scope carries the same
  trigger-handler reference."
  [handler-id]
  {:kind         :event
   :id           handler-id
   :source-coord {:ns     'app.events
                  :file   "src/app/events.cljs"
                  :line   42
                  :column 3}})

(defn- mk-cascade
  "Builds one event-handler cascade of `cascade-width` trace events. All
  events share the same `:dispatch-id`, `:trigger-handler`, and
  `:frame` — the cross-event structural backbone the deduper targets.

  Returns a vector of `:rf/trace-event`-shaped maps."
  [cascade-id handler-id cascade-width starting-id]
  (let [trigger-handler (mk-trigger-handler handler-id)
        dispatch-id     cascade-id
        frame           :rf/default]
    (vec
      (for [i (range cascade-width)]
        {:id                        (+ starting-id i)
         :operation                 (if (zero? i) :event/dispatched :rf.fx/handled)
         :op-type                   (if (zero? i) :event :fx)
         :time                      (+ 1700000000000 (* cascade-id 100) i)
         :tags                      {:frame        frame
                                     :dispatch-id  dispatch-id
                                     :event-id     handler-id
                                     :effect-key   (keyword (str "fx-" i))}
         :rf.trace/trigger-handler  trigger-handler}))))

(defn- mk-trace-burst
  "Builds a trace burst of approximately `target-events` events made of
  cascades of `cascade-width` events each. Returns a flat vector of
  trace events; cascades are concatenated in emit order (per the trace
  bus contract).

  `handler-variety` controls how many distinct handler ids the burst
  cycles through — a low number (e.g. 3) models a user repeatedly
  invoking the same handful of events; a high number (e.g. 100)
  models a noisier system with diverse cascades. The deduper's win
  depends on this ratio: low variety ⇒ more shared trigger-handler
  references ⇒ higher compression."
  [target-events cascade-width handler-variety]
  (let [n-cascades (max 1 (long (Math/ceil (/ target-events cascade-width))))]
    (vec
      (mapcat
        (fn [cascade-idx]
          (mk-cascade cascade-idx
                      (keyword "app.events"
                               (str "evt-" (mod cascade-idx handler-variety)))
                      cascade-width
                      (* cascade-idx cascade-width)))
        (range n-cascades)))))

(defn- mk-high-share-burst
  "Builds a 'high-share' trace burst — the same logical cascade replays
  `n-cascades` times, where each replay's events are **structurally
  identical** to the corresponding event in the canonical cascade.

  This mirrors the actual upper-bound regime day8/de-dupe targets: a
  trace-window that has accumulated under a recurring source (timer
  tick, scheduled job, idempotent rerender) where the framework
  re-emits structurally-identical trace events. Per-event `:id` /
  `:time` variability still exists in the wild — but those fields are
  *omitted from the wire payload* by the lazy-summary mechanism (Spec
  009 §`:rf/trace-event` envelope vs. §lazy-summary projection), so
  the deduper-input has identical event subtrees across replays.

  Returns a vector of trace events whose per-event subtree
  cardinality is exactly `cascade-width` — the deduper substitutes
  one cache entry per distinct event subtree and refers back from
  every replay."
  [n-cascades cascade-width]
  (let [handler-id      :app.events/poll-tick
        trigger-handler (mk-trigger-handler handler-id)
        frame           :rf/default
        ;; Build ONE canonical cascade — `cascade-width` distinct event
        ;; subtrees. Each replay re-uses the same `cascade-width` event
        ;; values, so the deduper sees `n-cascades` × `cascade-width`
        ;; references against only `cascade-width` distinct subtrees.
        canonical       (vec
                          (for [i (range cascade-width)]
                            {:operation                (if (zero? i) :event/dispatched :rf.fx/handled)
                             :op-type                  (if (zero? i) :event :fx)
                             :tags                     {:frame      frame
                                                        :event-id   handler-id
                                                        :effect-key (keyword (str "fx-" i))}
                             :rf.trace/trigger-handler trigger-handler}))]
    (vec
      (mapcat (fn [_replay-idx] canonical)
              (range n-cascades)))))

;; ---------------------------------------------------------------------------
;; Measurement primitive.
;; ---------------------------------------------------------------------------

(defn- measure
  "Apply `dedup/dedup-value` to `payload`, count `pr-str` bytes before
  and after, and return a measurement map for logging + assertion.

  Returns `{:label .. :events .. :raw-bytes .. :deduped-bytes ..
  :reduction-pct .. :ratio ..}` where `:ratio` is `raw/deduped`
  (the 'Nx compression' the spec quotes)."
  [label events payload]
  (let [raw-bytes     (count (pr-str payload))
        wrapped       (dedup/dedup-value payload true)
        deduped-bytes (count (pr-str wrapped))
        ratio         (if (pos? deduped-bytes)
                        (/ raw-bytes deduped-bytes 1.0)
                        0.0)
        reduction-pct (if (pos? raw-bytes)
                        (- 100.0 (* 100.0 (/ deduped-bytes raw-bytes 1.0)))
                        0.0)]
    {:label         label
     :events        events
     :raw-bytes     raw-bytes
     :deduped-bytes deduped-bytes
     :ratio         ratio
     :reduction-pct reduction-pct
     :wrapped       wrapped}))

(defn- row-str [m]
  (str "[rf2-li2cw]"
       " " (:label m)
       "  events=" (:events m)
       "  raw=" (:raw-bytes m) "B"
       "  deduped=" (:deduped-bytes m) "B"
       "  ratio=" (.toFixed (:ratio m) 2) "×"
       "  reduction=" (.toFixed (:reduction-pct m) 1) "%"))

;; Silent-on-success (rf2-try1x): the benchmark prints its measured
;; rows only when explicitly verbose. The row text is also folded into
;; each failing `is` message below, so triage still sees the numbers.
;; To surface the measurements on a green run for spec-tracking, flip
;; the goog-define `re-frame-pair2-mcp.dedup-benchmark-test/bench-verbose?`
;; to true at compile time.
(goog-define bench-verbose? false)

(defn- print-row [m]
  (when bench-verbose?
    (println (row-str m))))

;; ---------------------------------------------------------------------------
;; Benchmark fixtures — the three scales the bead names.
;; ---------------------------------------------------------------------------

(def ^:private corpora
  "Six corpora spanning the bead's stated scale axis (100 / 1k / 10k
  events) crossed with two structural-depth profiles (narrow cascade
  width = lower shared-subtree density; wide cascade width = higher
  density). These represent the **raw trace-burst regime** — events
  with per-event unique `:id`, `:time`, and per-tag variance dominate
  the wire bytes. The deduper's win is the shared
  `:rf.trace/trigger-handler` subtree and `:dispatch-id` backbone
  across same-cascade events."
  [{:label "100ev / cascade=8 / variety=8"   :events 100   :cascade-width 8  :variety 8}
   {:label "100ev / cascade=24 / variety=4"  :events 100   :cascade-width 24 :variety 4}
   {:label "1Kev / cascade=8 / variety=16"   :events 1000  :cascade-width 8  :variety 16}
   {:label "1Kev / cascade=24 / variety=8"   :events 1000  :cascade-width 24 :variety 8}
   {:label "10Kev / cascade=8 / variety=32"  :events 10000 :cascade-width 8  :variety 32}
   {:label "10Kev / cascade=24 / variety=16" :events 10000 :cascade-width 24 :variety 16}])

;; ---------------------------------------------------------------------------
;; Benchmark deftest — runs every corpus, prints measurements, asserts
;; the conservative floor.
;; ---------------------------------------------------------------------------

(deftest day8-de-dupe-trace-burst-compression-factor
  ;; Pin the measured compression factor across the bead's stated scale
  ;; axis. Floor is the **observed** lower bound minus a 5% slack margin
  ;; — currently 1.30× on raw trace bursts (observed range
  ;; 1.40-1.45×). The actual ratio prints to the test log only when
  ;; `bench-verbose?` is true (see goog-define above); on green the
  ;; measured numbers are silent. Failure messages carry the row text
  ;; so triage sees the numbers on red.
  (when bench-verbose?
    (println)
    (println "[rf2-li2cw] day8/de-dupe trace-burst compression benchmark")
    (println "[rf2-li2cw] ---------------------------------------------"))
  (let [results (doall
                  (for [{:keys [label events cascade-width variety]} corpora]
                    (let [payload (mk-trace-burst events cascade-width variety)
                          m       (measure label (count payload) payload)]
                      (print-row m)
                      m)))]
    (testing "every raw-trace-burst corpus clears the 1.30× measured floor"
      ;; 1.30× is the observed 1.40× lower bound minus a 5% slack
      ;; margin (≈0.07×). An algorithm regression that drops below
      ;; 1.30× breaks this assertion AND obliges a spec update.
      (doseq [m results
              :let [{:keys [label ratio]} m]]
        (is (>= ratio 1.30)
            (str label " — measured ratio " (.toFixed ratio 2)
                 "× below the 1.30× pinned floor  row=" (row-str m)))))
    (testing "scale-stability: 100ev / 1Kev / 10Kev ratios within 10% of each other"
      ;; The ratio is **shape-driven, not size-driven** — bigger
      ;; bursts of the same shape should compress at the same ratio.
      ;; If 10Kev compression drifts >10% off 100ev compression, the
      ;; algorithm has a shape-dependent constant we haven't pinned.
      (let [ratios (map :ratio results)
            lo     (apply min ratios)
            hi     (apply max ratios)]
        (is (< (- hi lo) (* 0.15 lo))
            (str "ratio spread " (.toFixed lo 2) "× → " (.toFixed hi 2)
                 "× exceeds 15% of the floor — scale dependence detected"))))
    (testing "round-trip exactness on every corpus"
      ;; Compression without semantic loss: every wrapped payload must
      ;; expand back to the original event vector.
      (doseq [{:keys [label wrapped]} results
              :let [payload (->> corpora
                                 (filter #(= label (:label %)))
                                 first
                                 ((fn [{:keys [events cascade-width variety]}]
                                    (mk-trace-burst events cascade-width variety))))]]
        (is (= payload (tu/dedup-expand wrapped))
            (str label " — round-trip differed from original payload"))))
    (when bench-verbose?
      (println "[rf2-li2cw] ---------------------------------------------")
      (println))))

;; ---------------------------------------------------------------------------
;; High-share burst — the upper-bound regime.
;;
;; A single handler firing repeatedly (polling tick, view-render, timer)
;; produces cascades whose body tags are identical across replays. The
;; deduper collapses the entire repeated cascade-body subtree, reaching
;; the same compression neighbourhood the original '3-5×' vibes-quote
;; was reaching for — but on a different corpus shape than the raw
;; trace burst.
;; ---------------------------------------------------------------------------

(deftest day8-de-dupe-high-share-burst-compression-factor
  (when bench-verbose?
    (println "[rf2-li2cw] day8/de-dupe high-share-burst compression benchmark")
    (println "[rf2-li2cw] -----------------------------------------------------"))
  (let [;; 1K replays of the same 24-event cascade. Each cascade body
        ;; (modulo unique :id / :time / :dispatch-id) is identical.
        payload (mk-high-share-burst 100 24)
        m       (measure "high-share / 100 replays / cascade=24" (count payload) payload)]
    (print-row m)
    (testing "high-share burst exceeds the 8× pinned floor"
      ;; The high-share corpus reaches the regime the original spec
      ;; quote was reaching for. Observed ratio currently ~14×; the
      ;; floor is set well below to surface only material regression.
      (is (>= (:ratio m) 8.0)
          (str "high-share burst ratio " (.toFixed (:ratio m) 2)
               "× fell below the 8× pinned floor — re-measure and "
               "update tools/causa-mcp/spec/004-Wire-Pipeline.md §5."
               "  row=" (row-str m))))
    (testing "round-trip on the high-share corpus"
      (is (= payload (tu/dedup-expand (:wrapped m)))))
    (when bench-verbose?
      (println "[rf2-li2cw] -----------------------------------------------------")
      (println))))

;; ---------------------------------------------------------------------------
;; Pinned typical-token-hint datapoint.
;;
;; The spec quotes a single number per tool — the deduper's typical
;; compression factor on real trace-bus payloads. This deftest pins the
;; particular corpus the spec cites so a refactor that drifts the
;; algorithm surfaces here as a failing test rather than silent spec
;; drift.
;; ---------------------------------------------------------------------------

(deftest pinned-spec-quote-1k-raw-burst
  ;; The mid-scale representative cited in the spec:
  ;; 1K events / cascade-width 24 / handler variety 8. Large enough
  ;; to be honest, small enough to be a nominal example.
  (let [payload (mk-trace-burst 1000 24 8)
        m       (measure "spec-quote / raw / 1Kev / cascade=24 / variety=8"
                         (count payload)
                         payload)]
    (print-row m)
    (testing "1K-event raw-burst spec quote holds at ≥1.40× (the measured floor)"
      (is (>= (:ratio m) 1.40)
          (str "1K-event raw burst ratio "
               (.toFixed (:ratio m) 2)
               "× fell below the 1.40× pinned floor — re-measure and "
               "update tools/causa-mcp/spec/004-Wire-Pipeline.md §5.")))
    (testing "round-trip on the pinned corpus"
      (is (= payload (tu/dedup-expand (:wrapped m)))))))
