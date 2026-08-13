(ns re-frame.bench.hicasso.topo.control-app
  "**THE TOURNAMENT'S CLOCK CONTROL** — the changed-set doubling
  `rf2-7iqb5` prescribed and nobody built (rf2-hic-036).

  Driven by the lane's generic driver, so it adds no driver of its own:

      HICASSO_INIT_FN=re-frame.bench.hicasso.topo.control-app/-main \\
      HICASSO_OUT_DIR=out/topo-control \\
      HICASSO_PORT=8147 \\
      node implementation/freehand/test/re_frame/bench/hicasso/run.cjs

  ## What this file decides, and why it runs BEFORE any cell

  The topology tournament's four operations are all WRITE rows, and the
  write rows of this lane's existing clock driver are refused by
  construction:

  > bulk-class rows cannot hold a difference-statistic control at the
  > ~3.5% floor a magnitude needs (rf2-7iqb5, 28–48% within-block IQR),
  > and the narrow class sits on the clock clamp (rf2-d2tzk)
  > — `shapes/census_clock_run.cjs`

  So the question this window exists to answer is not *what are the
  numbers* but *can any number here be trusted*. That question is settled
  by a control, and the control therefore runs first and alone. If it
  refuses, the tournament's clock half refuses with it and the cells are
  never taken — which is a result, not a failure.

  ## The control, and why it is not the one this lane already had

  Every earlier positive control for an update row **scaled the page**.
  `rf2-7iqb5` records precisely why that cannot certify one: on an update
  row the work does not scale with the page, so even a perfect instrument
  reads below the predicted factor — and its own run failed high at
  13.696 / 13.583 / 13.112x against a registered 8–13x band.

  Its prescribed repair is this one: **hold page size fixed and double
  the CHANGED SET.** Element count, layout and paint are then constant
  between the two halves, and only commit-side work moves.

  ## The predicted factor is DERIVED, never assumed to be 2.00

  This is the half a flat prediction gets wrong, and this lane already
  knows it: `census_clock_arms/ctl-predicted` computes 1.9759 / 1.9944 /
  1.7255 from element arithmetic rather than printing 2.00, because the
  page chrome does not double.

  Here the same care lands somewhere sharper. The predicted factor is the
  ratio of **rows of markup built**, which the deterministic census
  measures as exact integers, and it is *arm-specific*:

  | arm | markup at stride 10 | at stride 5 | predicted |
  |---|---|---|---|
  | `fine` | `B/10` | `B/5` | **2.00** |
  | `coarse` | `B` | `B` | **1.00 — degenerate** |
  | `chunked` (k=25) | `B` | `B` | **1.00 — degenerate** |

  `coarse` and `chunked` rebuild every row whichever stride runs, so
  their changed-set control **cannot discriminate at all**. That is a
  fact about those topologies, not about the instrument, and it is why
  this file certifies on `fine` alone and says so rather than quietly
  averaging a null control into a pass.

  ## The sign rule, which is the fix that landed in PR #7634

  A band alone admits a control certifying that MORE DIRTY WORK READS
  FASTER: `rf2-7iqb5` proved it live, capturing `num=-1.194 den=-0.594
  measured=2.0101x band=[1.5076,2.5126] ok=TRUE` against a decreasing
  fixture. [[verdict]] therefore requires the measured difference to be
  positive as well as in band, and refuses on the sign."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.topo.arms :as arms]
            [re-frame.bench.hicasso.topo.model :as m]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Pre-registered, and matching `topology-tournament.md` §1.5 exactly
;; ---------------------------------------------------------------------------

(def band
  "Registered before the run: predicted 2.00x, admitted in [1.60, 2.50]."
  {:lo 1.60 :hi 2.50 :predicted 2.00})

(def sampling {:warmup 3 :samples 12})
(def rounds 5)

(def batch-k
  "Operations under ONE clock.

  Chrome clamps `performance.now()` to 100 µs, and `rf2-d2tzk` records
  what that does to a narrow row: HD-008's bulk floor read a p50 of about
  0.1 ms — ONE quantum — and a yield correction over it consumed the
  whole window. `rf2-9zysg`'s repair for the narrow row was to batch, and
  this is that repair, not a new idea: `k` commits share one clock, which
  lifts the window clear of the clamp and is NOT the same as summing `k`
  separately-clamped readings."
  20)

(def row-count
  "The control runs at the tournament's largest size, because that is
  where a difference statistic has the most room and where a refusal is
  therefore most informative."
  1000)

;; ---------------------------------------------------------------------------
;; The arms — two strides on ONE page size, interleaved
;; ---------------------------------------------------------------------------

(def ^:private frame-id ::control)

(defn- window!
  "`batch-k` commits under one clock. The clock stops after the last
  drain; the read-back happens afterwards, which is the batched shape
  `lane/verified-writes!` documents."
  [stride]
  (let [t0 (lane/now-ms)]
    (dotimes [_ batch-k]
      (rt/dispatch! frame-id [:topo/bump-stride stride])
      (mount/settle!))
    (- (lane/now-ms) t0)))

(def arms
  "Two arms, one page. `:d10` moves every 10th row, `:d5` every 5th — so
  the changed set doubles and nothing else does."
  [{:id :d10 :stride 10}
   {:id :d5  :stride 5}])

;; ---------------------------------------------------------------------------
;; The verdict
;; ---------------------------------------------------------------------------

(defn verdict
  "Adjudicate the control. STRICT: in band AND positive in sign, on the
  ROUND-WISE ratios rather than on a pooled mean, so one round outside
  the band refuses the control rather than being averaged away.

  Pure, and exported for its own exit-path test — the gate arithmetic has
  to be checkable without a headless Chromium."
  [round-ratios]
  (let [rs      (vec round-ratios)
        every?* (fn [p] (and (seq rs) (every? p rs)))
        in-band (every?* (fn [r] (and (>= r (:lo band)) (<= r (:hi band)))))
        signed  (every?* (fn [r] (> r 1.0)))]
    {:rounds    rs
     :predicted (:predicted band)
     :band      [(:lo band) (:hi band)]
     :in-band?  in-band
     :positive? signed
     :ok?       (boolean (and in-band signed))
     :why       (cond
                  (empty? rs)   "no rounds — a control that measured nothing cannot certify anything"
                  (not signed)  "REFUSED ON THE SIGN — a round read the doubled changed set as no slower, which is a control certifying that more dirty work costs less"
                  (not in-band) "REFUSED ON THE BAND — a round fell outside [1.60, 2.50] around the derived 2.00"
                  :else         "in band and positive in every round")}))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- round-ratio [reading]
  (let [p (fn [id] (:p50 (lane/summarise (get reading id))))]
    (/ (p :d5) (p :d10))))

(defn ^:export -main []
  (rf/init! uix-adapter/adapter)
  (lane/leave-act-environment!)
  (lane/self-test!)
  (try
    (m/make-frame! frame-id row-count)
    (m/reseed! frame-id row-count)
    (arms/reset-counters!)
    (let [handle (mount/root! (mount/fresh-container!) frame-id [(arms/view-of :fine) {}])
          struct {:boundaries (:boundaries (rt/stats))
                  :edges      (:edges (rt/stats))
                  :elements   (lane/element-count (:container handle))}
          want   (select-keys (arms/expected :fine row-count) [:boundaries :edges :elements])]
      (lane/record! :page (assoc struct :expected want))
      (if (not= want struct)
        (do (set! (.-HICASSO_CONTROL_FAILED js/window) true)
            (lane/fail! (str "the control's page is not the arm it claims: expected "
                             (pr-str want) ", mounted " (pr-str struct))))
        (let [{:keys [readings samples]}
              (lane/rounds! arms sampling rounds (fn [{:keys [stride]}] (window! stride)))
              ratios (mapv round-ratio readings)
              v      (verdict ratios)
              gv     (lane/guard! samples "topo changed-set control (fine, B=1000)")]
          (lane/record! :runtime (lane/runtime-label))
          (lane/record! :batch-k batch-k)
          (lane/record! :per-round (mapv (fn [r] {:d10 (lane/summarise (get r :d10))
                                                  :d5  (lane/summarise (get r :d5))}) readings))
          (lane/record! :control v)
          (when (:refuse? gv) (set! (.-HICASSO_GUARD_REFUSED js/window) true))
          (when-not (:ok? v)
            (set! (.-HICASSO_CONTROL_FAILED js/window) true)
            (js/console.error (str ";; CONTROL REFUSED — " (:why v))))
          (mount/release! handle))))
    (catch :default e
      (lane/fail! (str "topo control threw: " (.-message e)))))
  (lane/done!))
