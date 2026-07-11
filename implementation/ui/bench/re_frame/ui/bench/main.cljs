(ns re-frame.ui.bench.main
  "G-1 direct-render parity gate (07 §5, rf2-vxgfnd.6): compiled views
  must render within the budget of hand-written jsx-runtime CLJS on
  p50 AND p95, per component, under the REVISED noise-robust estimator.

  ## The estimator (07 §5 — supersedes the S-1 spike's best-round/min)

  Alternating INTERLEAVED rounds, MEDIAN-of-rounds: within each round
  the two implementations alternate sample-by-sample (each sample = one
  timed batch), with the starting order flipped every round — thermal /
  GC / scheduler drift lands on both impls symmetrically. Per round and
  impl, sample p50 + p95 are computed; the final estimate per impl is
  the MEDIAN across rounds of the round-p50s and round-p95s (an odd
  round count keeps the median a real observation). Ratios are
  RELATIVE (same process, interleaved), so the gate is robust to
  absolute machine speed — the property CI runners violate.

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
  [{:id "static-tree"  :compiled v/static-tree  :hand hand/static-tree*
    :props {}}
   {:id "counter-42"   :compiled v/counter      :hand hand/counter*
    :props {:n 42 :step 10 :locked? false}}
   {:id "todos-20"     :compiled v/todo-list    :hand hand/todo-list*
    :props {:title "Twenty" :todos todos-20}}
   {:id "status-error" :compiled v/status-panel :hand hand/status-panel*
    :props {:state :error :message "boom & <bust> \"quoted\"" :retries 2}}])

(def budget
  "07 §5 G-1: within 10% of hand-written JSX CLJS on p50 and p95."
  {:p50 1.10 :p95 1.10})

(def opts {:batch 100 :samples 120 :rounds 7 :warmup 2000})

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

(defn- bench-round
  "One interleaved round: samples alternate compiled/hand (order per
  `flip?`). -> {:c {:p50 .. :p95 ..} :h {..}}"
  [cf hf {:keys [batch samples]} flip?]
  (let [ca (js/Array. samples)
        ha (js/Array. samples)]
    (dotimes [s samples]
      (if flip?
        (do (aset ha s (sample-us hf batch))
            (aset ca s (sample-us cf batch)))
        (do (aset ca s (sample-us cf batch))
            (aset ha s (sample-us hf batch)))))
    (let [stats (fn [arr]
                  (let [xs (vec arr)]
                    {:p50 (median xs) :p95 (percentile xs 0.95)}))]
      {:c (stats ca) :h (stats ha)})))

(defn- bench-pair
  "Alternating interleaved rounds; median-of-rounds per estimate."
  [cf hf {:keys [rounds warmup] :as o}]
  (dotimes [_ warmup] (cf) (hf))
  (let [rs   (mapv #(bench-round cf hf o (odd? %)) (range rounds))
        agg  (fn [k stat] (median (mapv #(get-in % [k stat]) rs)))]
    {:compiled {:p50 (agg :c :p50) :p95 (agg :c :p95)}
     :hand     {:p50 (agg :h :p50) :p95 (agg :h :p95)}
     :rounds   (mapv (fn [r] {:c (:c r) :h (:h r)}) rs)}))

(defn- round2 [x] (/ (js/Math.round (* 100 x)) 100))

;; ---------------------------------------------------------------------------
;; Main
;; ---------------------------------------------------------------------------

(defn- byte-equality! []
  (let [fails (atom 0)]
    (doseq [{:keys [id compiled hand props]} cases]
      (let [pj (->props props)
            c  (render-html compiled pj)
            h  (render-html hand pj)]
        (when (not= c h)
          (swap! fails inc)
          (println "  BYTE-DIFF" id)
          (println "    compiled:" c)
          (println "    hand:    " h))))
    (if (zero? @fails)
      (println "precheck: compiled HTML == hand HTML byte-for-byte on"
               (count cases) "fixtures")
      (do (println "precheck FAILED:" @fails "fixtures differ — ratios would be meaningless")
          (js/process.exit 1)))))

(defn -main [& _args]
  (println "G-1 direct-render parity gate — revised estimator"
           (pr-str opts) "budget" (pr-str budget))
  (when (not= "production" (unchecked-get js/process.env "NODE_ENV"))
    (println "FATAL: NODE_ENV must be 'production' (react dev-build costs would pollute the ratio)")
    (js/process.exit 1))
  (byte-equality!)
  (let [results
        (vec
         (for [{:keys [id compiled hand props]} cases]
           (let [pj (->props props)
                 cf #(render-html compiled pj)
                 hf #(render-html hand pj)
                 r  (bench-pair cf hf opts)
                 c  (:compiled r) h (:hand r)
                 r50 (/ (:p50 c) (:p50 h))
                 r95 (/ (:p95 c) (:p95 h))
                 ok? (and (<= r50 (:p50 budget)) (<= r95 (:p95 budget)))]
             (println (str (if ok? "  OK   " "  FAIL ") id
                           "  compiled p50=" (round2 (:p50 c)) "us p95=" (round2 (:p95 c)) "us"
                           "  hand p50=" (round2 (:p50 h)) "us p95=" (round2 (:p95 h)) "us"
                           "  ratio p50=" (round2 r50) " p95=" (round2 r95)))
             {:id id
              :compiled {:p50-us (round2 (:p50 c)) :p95-us (round2 (:p95 c))}
              :hand     {:p50-us (round2 (:p50 h)) :p95-us (round2 (:p95 h))}
              :ratio    {:p50 (round2 r50) :p95 (round2 r95)}
              :ok?      ok?
              :rounds   (:rounds r)})))]
    (when-not (fs/existsSync "out") (fs/mkdirSync "out"))
    (fs/writeFileSync
     "out/ui-bench.json"
     (js/JSON.stringify
      (clj->js {:gate "G-1"
                :estimator "alternating interleaved rounds, median-of-rounds"
                :opts opts
                :budget budget
                :cpu (.-model (aget (os/cpus) 0))
                :node (.-version js/process)
                :react "19.2.0"
                :optimizations "advanced"
                :per-render-unit "microseconds"
                :results results})
      nil 2))
    (println "wrote out/ui-bench.json")
    (if (every? :ok? results)
      (println "G-1: PASS — every component within" (pr-str budget))
      (do (println "G-1: FAIL — a component exceeded the budget")
          (js/process.exit 1)))))
