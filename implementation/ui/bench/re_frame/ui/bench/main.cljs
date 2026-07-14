(ns re-frame.ui.bench.main
  "G-1 direct-render parity gate (07 §5, rf2-vxgfnd.6): compiled views
  must render within the budget of hand-written jsx-runtime CLJS on
  p50 AND p95, per component, under the REVISED noise-robust estimator.

  ## The estimator (07 §5 — supersedes the S-1 spike's best-round/min)

  Alternating INTERLEAVED rounds, PAIRED sample ratios, MEDIAN-of-rounds:
  within each round every sample is one local order-balanced stratum: four
  timed pairs alternate AB/BA (A=compiled, B=hand), so both
  implementations occupy first and second position exactly twice. The
  highest and lowest observation on each side are discarded and the
  middle pair is averaged LOCALLY, then that sample's compiled/hand ratio
  is formed before any cross-sample aggregation. The larger timed batch
  keeps each observation out of the sub-millisecond scheduler-noise
  regime; the local trim prevents one GC/scheduler interruption from
  dominating ratio p95. Each sample slot is repeated across the seven
  rounds; its final paired ratio is the median of those seven local ratios
  (an odd count keeps it a real observation). Gate p50 + p95 are then
  computed across the 40 stabilized paired samples. Raw implementation
  timings and round summaries are retained as evidence, but the gate never
  divides independently aggregated timings: preserving local covariance
  is what makes the relative gate robust to shared CI-runner noise.

  ## The precheck

  Before any timing, compiled HTML must equal hand-written HTML
  byte-for-byte per fixture — the two sides must be doing identical
  work or the ratio is meaningless (and byte-equality has caught real
  conversion bugs — S-1 §Surprises 5).

  Exit 0 = every component within budget; exit 1 otherwise.
  Full data lands in out/ui-bench.json."
  (:require ["react-dom/server" :as rds]
            ["fs" :as fs]
            ["os" :as os]
            [re-frame.ui.bench.hand :as hand]
            [re-frame.ui.bench.views :as v]
            [re-frame.ui.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Fixtures (the S-1 set — numbers comparable across reports)
;; ---------------------------------------------------------------------------

(def todos-20
  (vec (for [i (range 20)]
         {:id i
          :label (str "Todo item " i)
          :done? (zero? (mod i 3))
          :priority (case (mod i 4) 0 :low 1 :med 2 :high 3 :low)})))

(def cases
  "Every gated workload names a memo-matched hand baseline (`:gate-hand`) that
  carries the SAME React.memo boundary + an equivalent per-slot rf= comparator
  the compiled `defview` emits (emit-cljs `comparator-form`), so each ratio
  isolates the compiler's lowering overhead rather than the cost of the
  always-memo policy. `:hand` is the unwrapped baseline (kept for byte-equality);
  where `:policy-hand` names it the unwrapped comparison is reported separately
  as explicit NON-GATING product-policy evidence."
  [{:id "static-tree"  :compiled v/static-tree  :hand hand/static-tree*
    :gate-hand hand/static-tree*-memo
    :props {}}
   {:id "counter-42"   :compiled v/counter      :hand hand/counter*
    :gate-hand hand/counter*-memo
    :props {:n 42 :step 10 :locked? false}}
   {:id "todos-20"     :compiled v/todo-list    :hand hand/todo-list*
    :gate-hand hand/todo-list*-memo
    :policy-hand hand/todo-list*
    :props {:title "Twenty" :todos todos-20}}
   {:id "status-error" :compiled v/status-panel :hand hand/status-panel*
    :gate-hand hand/status-panel*-memo
    :props {:state :error :message "boom & <bust> \"quoted\"" :retries 2}}])

(def budget
  "07 §5 G-1: within 10% of hand-written JSX CLJS on p50 and p95."
  {:p50 1.10 :p95 1.10})

(def opts {:batch 400 :samples 40 :rounds 7 :warmup 2000})

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- ->props [m]
  (reduce-kv (fn [o k val]
               (unchecked-set o (subs (str k) 1) val)
               o)
             #js {} m))

(defn- render-html [view-fn props-js]
  (rds/renderToStaticMarkup (rt/jsx2 view-fn props-js)))

(defn- sample-us
  "Time one batch of f; per-render microseconds."
  [f batch]
  (let [t0 (js/process.hrtime.bigint)]
    (dotimes [_ batch] (f))
    (let [t1 (js/process.hrtime.bigint)]
      (/ (js/Number (js* "~{} - ~{}" t1 t0)) (* batch 1000.0)))))

(defn- percentile [xs p]
  (let [sorted (vec (sort xs))]
    (nth sorted (js/Math.floor (* p (dec (count sorted)))))))

(defn- median [xs] (percentile xs 0.5))

(defn- distribution-stats [xs]
  {:p50 (median xs) :p95 (percentile xs 0.95)})

(defn- middle-mean4 [xs]
  (let [v (vec (sort xs))]
    (/ (+ (nth v 1) (nth v 2)) 2.0)))

(defn- bench-round
  "One interleaved round. Each sample contains four AB/BA-alternating
  pairs. Each side's middle two observations are averaged locally, then
  the paired ratio is formed before any percentile is computed."
  [cf hf {:keys [batch samples]} flip?]
  (let [ca (js/Array. samples)
        ha (js/Array. samples)
        ra (js/Array. samples)]
    (dotimes [s samples]
      (let [cs (js/Array.)
            hs (js/Array.)]
        (dotimes [pair-idx 4]
          (if (= flip? (odd? (+ s pair-idx)))
            (do (.push cs (sample-us cf batch))
                (.push hs (sample-us hf batch)))
            (do (.push hs (sample-us hf batch))
                (.push cs (sample-us cf batch)))))
        (let [c (middle-mean4 cs)
              h (middle-mean4 hs)]
          (aset ca s c)
          (aset ha s h)
          (aset ra s (/ c h)))))
    {:c (distribution-stats (vec ca))
     :h (distribution-stats (vec ha))
     :ratio (distribution-stats (vec ra))
     :ratio-samples (vec ra)}))

(defn- bench-pair
  "Alternating interleaved rounds. Each sample slot is stabilized by the
  median of its per-round paired ratios; gate p50/p95 are computed across
  only those stabilized paired ratios."
  [cf hf {:keys [rounds warmup samples] :as o}]
  (dotimes [_ warmup] (cf) (hf))
  (let [rs (mapv #(bench-round cf hf o (odd? %)) (range rounds))
        agg (fn [k stat] (median (mapv #(get-in % [k stat]) rs)))
        paired-ratios
        (mapv (fn [sample-idx]
                (median (mapv #(nth (:ratio-samples %) sample-idx) rs)))
              (range samples))]
    {:compiled {:p50 (agg :c :p50) :p95 (agg :c :p95)}
     :hand     {:p50 (agg :h :p50) :p95 (agg :h :p95)}
     :ratio    (distribution-stats paired-ratios)
     :paired-ratio-samples paired-ratios
     :rounds   (mapv (fn [r] (select-keys r [:c :h :ratio])) rs)}))

(defn- round2 [x] (/ (js/Math.round (* 100 x)) 100))
(defn- round4 [x] (/ (js/Math.round (* 10000 x)) 10000))
(defn- fixed4 [x] (.toFixed x 4))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn- byte-equality! []
  (let [fails (atom 0)
        check! (fn [id label c h]
                 (when (not= c h)
                   (swap! fails inc)
                   (println "  BYTE-DIFF" id "against" label)
                   (println "    compiled:" c)
                   (println "    hand:    " h)))]
    (doseq [{:keys [id compiled hand gate-hand props]} cases]
      (let [pj (->props props)
            c  (render-html compiled pj)
            baselines (cond-> [["unwrapped hand" hand]]
                        gate-hand (conj ["memo-matched hand" gate-hand]))]
        (doseq [[label baseline] baselines]
          (check! id label c (render-html baseline pj)))))
    ;; The lowering-regression control candidate must render byte-identically to
    ;; the todos-20 baselines — the mutation is ONLY the class-property lowering,
    ;; so a byte diff would mean the control measures a different tree.
    (let [{:keys [gate-hand hand props]}
          (some #(when (= "todos-20" (:id %)) %) cases)
          pj (->props props)
          g  (render-html v/todo-list-generic-class pj)]
      (check! "todos-20-generic-class-control" "memo-matched hand"
              g (render-html gate-hand pj))
      (check! "todos-20-generic-class-control" "unwrapped hand"
              g (render-html hand pj)))
    (if (zero? @fails)
      (println "precheck: compiled HTML == every measured hand baseline byte-for-byte")
      (do (println "precheck FAILED:" @fails "baseline comparisons differ — ratios would be meaningless")
          (js/process.exit 1)))))

(defn- within-budget? [{:keys [p50 p95]}]
  (and (<= p50 (:p50 budget))
       (<= p95 (:p95 budget))))

(defn- lowering-regression-control
  "Faithful G-1 red control (rf2-vxgfnd.206): bench the REAL compiled todos-20
  list whose row class-property is lowered through the generic classes-str path
  (`v/todo-list-generic-class`) against the SAME memo-matched hand baseline the
  todos-20 gate uses. The candidate differs from the gated `v/todo-list` at ONE
  place — the class-property call site (generic `classes-str`/`class-val` join
  instead of the single-flag straight-line specialization) — with byte-identical
  output. So the ratio reddens for a genuine class-property lowering regression,
  not for an injected side workload: there is no second traversal, no duplicate
  specialized work, and no volatile timing sink. Swapping the candidate back to
  the specialized `v/todo-list` drops this ratio under budget and fails the
  `:detected?` assertion in `-main`."
  []
  (let [{:keys [gate-hand props]}
        (some #(when (= "todos-20" (:id %)) %) cases)
        pj (->props props)
        r  (bench-pair #(render-html v/todo-list-generic-class pj)
                       #(render-html gate-hand pj)
                       opts)
        detected? (not (within-budget? (:ratio r)))]
    (println
     (str (if detected? "  CONTROL PASS " "  CONTROL FAIL ")
          "todos-20 real generic classes-str class-property lowering [must exceed budget]"
          "  ratio p50=" (fixed4 (get-in r [:ratio :p50]))
          " p95=" (fixed4 (get-in r [:ratio :p95]))
          "  limits p50<=" (fixed4 (:p50 budget))
          " p95<=" (fixed4 (:p95 budget))))
    {:id "todos-20-generic-classes-str-class-property-lowering"
     :candidate "compiled todo-list, row class-property lowered via generic classes-str"
     :baseline "memo-matched hand JSX"
     :expected "budget breach"
     :detected? detected?
     :ratio {:p50 (round4 (get-in r [:ratio :p50]))
             :p95 (round4 (get-in r [:ratio :p95]))}
     :paired-ratio-samples (mapv round4 (:paired-ratio-samples r))
     :rounds (:rounds r)}))

(defn -main [& _args]
  (println "G-1 direct-render parity gate — revised estimator"
           (pr-str opts)
           "budget p50<=" (fixed4 (:p50 budget))
           "p95<=" (fixed4 (:p95 budget)))
  (when (not= "production" (unchecked-get js/process.env "NODE_ENV"))
    (println "FATAL: NODE_ENV must be 'production' (react dev-build costs would pollute the ratio)")
    (js/process.exit 1))
  (byte-equality!)
  (let [results
        (vec
         (for [{:keys [id compiled hand gate-hand policy-hand props]} cases]
           (let [pj (->props props)
                 cf #(render-html compiled pj)
                 gate-hf #(render-html (or gate-hand hand) pj)
                 r  (bench-pair cf gate-hf opts)
                 c  (:compiled r) h (:hand r)
                 r50 (get-in r [:ratio :p50])
                 r95 (get-in r [:ratio :p95])
                 ok? (within-budget? (:ratio r))
                 ;; NON-GATING policy evidence: the compiled/UNWRAPPED-hand ratio,
                 ;; reported only where `:policy-hand` names an unwrapped baseline
                 ;; (todos-20 — the keyed list where the always-memo policy cost
                 ;; is meaningful). It never feeds the gate decision.
                 policy-r (when policy-hand
                            (bench-pair cf #(render-html policy-hand pj) opts))
                 policy-c (:compiled policy-r)
                 policy-h (:hand policy-r)
                 policy-r50 (get-in policy-r [:ratio :p50])
                 policy-r95 (get-in policy-r [:ratio :p95])]
             (println (str (if ok? "  OK   " "  FAIL ") id
                           (when gate-hand " [memo-matched hand baseline]")
                           "  compiled p50=" (round2 (:p50 c)) "us p95=" (round2 (:p95 c)) "us"
                           "  hand p50=" (round2 (:p50 h)) "us p95=" (round2 (:p95 h)) "us"
                           "  ratio p50=" (fixed4 r50) " p95=" (fixed4 r95)
                           "  limits p50<=" (fixed4 (:p50 budget))
                           " p95<=" (fixed4 (:p95 budget))))
             (when policy-r
               (println
                (str "  INFO " id
                     " always-memo SSR policy cost [non-gating]"
                     "  compiled p50=" (round2 (:p50 policy-c)) "us p95=" (round2 (:p95 policy-c)) "us"
                     "  unwrapped-hand p50=" (round2 (:p50 policy-h)) "us p95=" (round2 (:p95 policy-h)) "us"
                     "  ratio p50=" (fixed4 policy-r50) " p95=" (fixed4 policy-r95))))
             (cond->
              {:id id
               :baseline (if gate-hand "memo-matched hand JSX" "hand JSX")
               :compiled {:p50-us (round2 (:p50 c)) :p95-us (round2 (:p95 c))}
               :hand     {:p50-us (round2 (:p50 h)) :p95-us (round2 (:p95 h))}
               :ratio    {:p50 (round4 r50) :p95 (round4 r95)}
               :ok?      ok?
               :paired-ratio-samples (mapv round4 (:paired-ratio-samples r))
               :rounds   (:rounds r)}
               policy-r
               (assoc :always-memo-ssr-policy-cost
                      {:label "always-memo SSR policy cost"
                       :gating? false
                       :hand-baseline "unwrapped hand JSX"
                       :compiled {:p50-us (round2 (:p50 policy-c))
                                  :p95-us (round2 (:p95 policy-c))}
                       :hand {:p50-us (round2 (:p50 policy-h))
                              :p95-us (round2 (:p95 policy-h))}
                       :ratio {:p50 (round4 policy-r50)
                               :p95 (round4 policy-r95)}
                       :paired-ratio-samples
                       (mapv round4 (:paired-ratio-samples policy-r))})))))
        lowering-control (lowering-regression-control)]
    (when-not (fs/existsSync "out") (fs/mkdirSync "out"))
    (fs/writeFileSync
     "out/ui-bench.json"
     (js/JSON.stringify
      (clj->js {:gate "G-1"
                :estimator "order-balanced locally trimmed samples, per-sample paired ratios stabilized across rounds"
                :opts opts
                :budget budget
                :cpu (.-model (aget (os/cpus) 0))
                :node (.-version js/process)
                :react "19.2.0"
                :optimizations "advanced"
                :per-render-unit "microseconds"
                :results results
                :lowering-regression-control lowering-control})
      nil 2))
    (println "wrote out/ui-bench.json")
    (when-let [summary-path (unchecked-get js/process.env "GITHUB_STEP_SUMMARY")]
      (when-let [policy (some :always-memo-ssr-policy-cost results)]
        (fs/appendFileSync
         summary-path
         (str "\n### G-1 policy evidence (non-gating)\n\n"
              "- always-memo SSR policy cost: p50 `"
              (fixed4 (get-in policy [:ratio :p50]))
              "`, p95 `" (fixed4 (get-in policy [:ratio :p95]))
              "` (compiled / unwrapped hand JSX; non-gating)\n"))))
    (if (and (every? :ok? results)
             (:detected? lowering-control))
      (println "G-1: PASS — every component within"
               (str "p50<=" (fixed4 (:p50 budget))
                    ", p95<=" (fixed4 (:p95 budget))))
      (do (println "G-1: FAIL — a component exceeded the budget or the lowering-regression control escaped detection")
          (js/process.exit 1)))))
