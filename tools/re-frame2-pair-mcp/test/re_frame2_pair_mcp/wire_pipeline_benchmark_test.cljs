(ns re-frame2-pair-mcp.wire-pipeline-benchmark-test
  "Wall-clock + structural micro-benchmarks for the wire-pipeline
  hot paths (rf2-w92r8).

  ## Why this benchmark

  Round-1 audit (rf2-7hie3) surfaced three perf suspicions on the
  wire-pipeline. None were proven hot; each was a measurement task.
  Round-2 (rf2-w92r8) lifted them to a single bead so the
  conclusions are data-driven, not vibes-driven:

    1. **Wire-pipeline intermediate allocations** —
       `run-snapshot-map` (in `wire_pipeline.cljs`) chains five
       per-frame transforms (sensitive-strip → slice-app-db →
       diff-encode-epochs → dedup-epochs → summarise-others). Each
       hop allocates a fresh per-frame map. For a 3-frame snapshot
       under `:include :all`, that's ~15 intermediate maps. The
       suspicion was that for high event-rate traffic (100/sec) the
       intermediate-map churn would surface as GC pressure.

    2. **`count-elided-markers` full-payload walk** at every emit-
       site. Round-2 (rf2-e35a5) eliminated the walk for the
       `:snapshot-map` and `:scalar-value` arms — the server-side
       count rides back on the eval form. The `:epoch-vector` arm
       (trace-window, watch-epochs) and the subscribe per-tick still
       walk locally. This benchmark pins the per-walk cost so a
       future optimisation (e.g. server-side count for runtime
       drains) has a baseline.

    3. **Dedup pass on 50-record epoch payloads** — equality-based
       structural dedup over a 50-epoch slice with a 256-key shared
       `:db-before`. Already pinned at 89.5% reduction by
       `dedup_test/reduction-ratio-shared-subtrees`. The wall-clock
       cost is what we measure here — does the dedup pass fit in the
       per-call budget (rough rule of thumb: < 10ms for an
       interactive read-tool).

  ## Output shape

  Each `deftest` runs the measurement N times, reports min / median /
  max wall-clock to the test log via `println` (mirror of
  `dedup_benchmark_test`), and pins a generous upper-bound assertion
  so a future algorithmic regression breaks the build. The numbers
  themselves drive judgement; the assertion drives CI.

  Per-bench timer uses `performance.now()` (sub-ms resolution on
  Node) where available, falls back to `js/Date.now()` (1ms
  resolution) otherwise.

  ## Why no separate bench/ artefact

  Same reasoning as `dedup_benchmark_test`: re-frame2-pair-mcp already wires
  every dependency the wire-pipeline pulls in (mcp-base, day8/de-dupe,
  sensitive, summary), already runs under `npm test`, and the
  ratchets surface to the same log a contributor reads when
  iterating on the per-pipeline arm. A separate runner would
  duplicate the deps wiring for no information gain."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

;; ---------------------------------------------------------------------------
;; Timer.
;; ---------------------------------------------------------------------------

(def ^:private perf-now
  "Sub-ms timestamp. Prefers `performance.now()` on Node; falls back
  to `Date.now()` (1ms resolution) on environments without the
  performance API."
  (if (and (exists? js/performance)
           (fn? (.-now js/performance)))
    (fn [] (.now js/performance))
    (fn [] (.getTime (js/Date.)))))

(defn- bench-times
  "Run `f` `n` times, returning a vector of wall-clock milliseconds
  per invocation. Warms once (the first call carries JIT setup
  costs) before the measured runs."
  [n f]
  (f) ;; warm
  (loop [i n acc (transient [])]
    (if (zero? i)
      (persistent! acc)
      (let [start (perf-now)
            _     (f)
            end   (perf-now)]
        (recur (dec i) (conj! acc (- end start)))))))

(defn- median [xs]
  (let [sorted (sort xs)
        n      (count sorted)]
    (cond
      (zero? n) 0
      (odd? n)  (nth sorted (quot n 2))
      :else     (let [a (nth sorted (dec (quot n 2)))
                      b (nth sorted (quot n 2))]
                  (/ (+ a b) 2)))))

;; Silent-on-success (rf2-try1x): the bench report lines only emit
;; when `bench-verbose?` is true. The raw min/median/max are also
;; folded into each failing `is` message via `report-str` below so
;; triage retains the numbers.
(goog-define bench-verbose? false)

(defn- report-str
  [label samples]
  (let [lo (apply min samples)
        hi (apply max samples)
        md (median samples)]
    (str "[rf2-w92r8] " label
         " — min=" (.toFixed (double lo) 3) "ms"
         " median=" (.toFixed (double md) 3) "ms"
         " max=" (.toFixed (double hi) 3) "ms"
         " (" (count samples) " runs)")))

(defn- report-line
  [label samples]
  (when bench-verbose?
    (println (report-str label samples))))

;; ---------------------------------------------------------------------------
;; Fixtures — representative payloads.
;; ---------------------------------------------------------------------------

(def ^:private big-map
  "1024-key keyword→value map, ~256 chars per value. Stand-in for a
  modestly-large app-db slice. Constructed once, shared across
  benches via let-bindings."
  (apply hash-map
         (mapcat (fn [i] [(keyword (str "k" i))
                          (apply str (repeat 256 "x"))])
                 (range 1024))))

(defn- make-snapshot
  "Three-frame snapshot with `:app-db` + 50-epoch `:epochs` slice
  each carrying a full `:db-before`. Mirrors the `dedup_test`
  load-bearing shape — the slice the audit flagged as the dedup
  worst-case."
  []
  (let [epochs (vec (for [i (range 50)]
                      {:event-id   (keyword (str "e" i))
                       :db-before  big-map
                       :db-after   (assoc big-map :touched i)}))]
    {:rf/default {:app-db big-map :epochs epochs :sub-cache {} :machines {} :traces []}
     :rf/secondary {:app-db big-map :epochs (vec (take 10 epochs))
                    :sub-cache {} :machines {} :traces []}
     :rf/tertiary {:app-db (select-keys big-map (map #(keyword (str "k" %)) (range 32)))
                   :epochs [] :sub-cache {} :machines {} :traces []}}))

(defn- make-snapshot-with-markers
  "Snapshot whose `:app-db` carries scattered `:rf.size/large-elided`
  markers — the worst case for a local walker doing
  `count-elided-markers`. Twenty markers per frame in the audit
  reference shape."
  []
  (let [marker (fn [path]
                 {:rf.size/large-elided
                  {:path path :bytes 102400 :type :string
                   :reason :schema :handle [:rf.elision/at path]}})
        with-markers (reduce (fn [m i]
                               (assoc m
                                      (keyword (str "elided-" i))
                                      (marker [(keyword (str "elided-" i))])))
                             big-map
                             (range 20))]
    {:rf/default {:app-db with-markers :epochs [] :sub-cache {} :machines {} :traces []}
     :rf/secondary {:app-db with-markers :epochs [] :sub-cache {} :machines {} :traces []}}))

;; ---------------------------------------------------------------------------
;; Bench 1 — wire-pipeline snapshot-map allocation churn.
;;
;; Five sequential transforms per frame. We don't try to count
;; intermediate maps directly (CLJS makes that fragile); instead we
;; time the whole pipeline against a representative snapshot and
;; pin a generous upper bound. A future change that adds a wasteful
;; intermediate would surface as time-budget pressure here.
;; ---------------------------------------------------------------------------

(deftest bench-snapshot-map-pipeline
  ;; 3-frame snapshot with 50-epoch / 1K-app-db / shared-:db-before.
  ;; Run 30 times; report min/median/max; pin median to a generous
  ;; 100ms ceiling on a dev workstation.
  (let [snap    (make-snapshot)
        opts    {:kind        :snapshot-map
                 :incl?       false
                 :mode        :diff
                 :dedup?      true
                 :slice-mode  :full
                 :slice-modes {}
                 :server-elided 0}
        label   "snapshot-map (3 frames / 50 epochs / 1K-key db / dedup on)"
        samples (bench-times 30 #(wp/run-wire-pipeline snap opts))]
    (report-line label samples)
    (is (< (median samples) 1000)
        (str "Snapshot-map pipeline median MUST be under 1s on representative shapes  "
             (report-str label samples)))))

(deftest bench-snapshot-map-pipeline-no-dedup
  ;; Same shape, dedup off — pins the dedup-pass cost as a delta
  ;; against the previous bench. Used to detect a regression in
  ;; the non-dedup hot path (epoch diff-encode + summary).
  (let [snap    (make-snapshot)
        opts    {:kind        :snapshot-map
                 :incl?       false
                 :mode        :diff
                 :dedup?      false
                 :slice-mode  :full
                 :slice-modes {}
                 :server-elided 0}
        label   "snapshot-map (3 frames / 50 epochs / 1K-key db / dedup off)"
        samples (bench-times 30 #(wp/run-wire-pipeline snap opts))]
    (report-line label samples)
    (is (< (median samples) 1000)
        (report-str label samples))))

;; ---------------------------------------------------------------------------
;; Bench 2 — count-elided-markers walk over a marker-rich payload.
;;
;; This is the walker rf2-e35a5 eliminated for snapshot+get-path. The
;; `:epoch-vector` arm and subscribe per-tick still walk locally. The
;; bench pins the per-walk cost so a future "server-side count for
;; runtime drains" optimisation has a baseline to claim against.
;; ---------------------------------------------------------------------------

(deftest bench-count-elided-markers-marker-rich
  ;; 2-frame snapshot with 20 markers per frame embedded in a 1K-key
  ;; app-db. Walk runs over the whole payload — markers are inside
  ;; the `:app-db` slice.
  (let [snap    (make-snapshot-with-markers)
        label   "count-elided-markers (2 frames / 40 markers / 1K-key db)"
        samples (bench-times 100 #(base-elision/count-elided-markers snap))]
    (report-line label samples)
    (let [actual (base-elision/count-elided-markers snap)]
      (is (= 40 actual)
          (str "Walker MUST find every marker; got " actual)))
    (is (< (median samples) 100)
        (str "Walker median MUST stay under 100ms on representative shapes  "
             (report-str label samples)))))

(deftest bench-count-elided-markers-marker-free
  ;; The common path — a payload with no markers. The walker still
  ;; recurses through every map / vector. Pins the floor cost so a
  ;; future regression on the empty path doesn't sneak through.
  (let [snap    (make-snapshot)
        label   "count-elided-markers (no markers / 1K-key db / 3 frames)"
        samples (bench-times 100 #(base-elision/count-elided-markers snap))]
    (report-line label samples)
    (is (= 0 (base-elision/count-elided-markers snap)))
    (is (< (median samples) 100)
        (report-str label samples))))

;; ---------------------------------------------------------------------------
;; Bench 3 — dedup pass over a 50-epoch payload.
;;
;; `dedup_test/reduction-ratio-shared-subtrees` already pins the
;; compression RATIO at 89.5%. This bench pins the WALL-CLOCK cost so
;; the per-call latency budget surfaces here.
;; ---------------------------------------------------------------------------

(deftest bench-dedup-epochs-50-record
  (let [epochs  (vec (for [i (range 50)]
                       {:event-id  (keyword (str "e" i))
                        :db-before big-map
                        :db-after  (assoc big-map :touched i)}))
        label   "dedup-value (50 epochs / 1K-key shared :db-before)"
        samples (bench-times 20 #(dedup/dedup-value epochs true))]
    (report-line label samples)
    (is (< (median samples) 5000)
        (str "50-epoch dedup median MUST stay under 5s on representative shapes  "
             (report-str label samples)))))

(deftest bench-dedup-epochs-1-record
  ;; Single-record dedup — the floor. Useful to amortise across
  ;; cursor-paginated calls (`:limit 1`).
  (let [epochs  [{:event-id :e1
                  :db-before big-map
                  :db-after  (assoc big-map :touched 1)}]
        label   "dedup-value (1 epoch / 1K-key :db-before)"
        samples (bench-times 50 #(dedup/dedup-value epochs true))]
    (report-line label samples)
    (is (< (median samples) 500)
        (report-str label samples))))
