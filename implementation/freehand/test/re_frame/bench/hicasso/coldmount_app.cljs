(ns re-frame.bench.hicasso.coldmount-app
  "THE COLD-MOUNT DOUBLE BUILD, PRICED AGAINST THE MOUNT RED-ZONE — the
  run (rf2-2rtt6.15; decision input for the rf2-2rtt6.14 ruling).

  ## The question, and the two numbers it needs from ONE run

  rf2-2rtt6.12 proved by counting that every cold subscription read on the
  React-hook spine constructs TWO reactions (`bodyRuns = 2.00N` against
  Reagent's `1.00N`) and priced the RETENTION of the first. The CLOCK of
  the second construction was left unmeasured, and the rf2-2rtt6.14
  decision rule consumes it as a FRACTION OF THE MOUNT RED-ZONE — the
  UIx-minus-Reagent mount-clock excess — measured in the SAME runs (M1's
  published red-zone magnitude is withdrawn, so the excess is re-derived
  from this run's own rounds and no published number is quoted into the
  denominator).

  So every row here carries, per round, in one page:

      numerator    xcript − handoff          (UIx segment; the single-
                                              variable ablation of the
                                              build/dispose/rebuild churn)
      denominator  uix-subs − reagent-subs   (the mount red-zone excess,
                                              cross-seam, same round)

  and the published figure is their ratio, per round, with ranges — raw
  and seam-adjusted (the UIx-segment terms divided by that round's
  floor-vs-floor seam ratio) as two estimators that must agree.

  ## The rows

  One row per page (the converged arm's repair, kept):

      M1L1  the converged M1 mount (901 el / 300 boundaries), layer-1 —
            the PRIMARY witness, rf2-2rtt6.2's page exactly
      M1L2  the same page reading through one `:<-` hop
      M1L3  the same page reading through two `:<-` hops
      M2L1  the converged M2 form (51 el / 12 fields) — DIAGNOSTIC,
            per rf2-2rtt6.2's own grading (clamp-quantised)
      M2L2  the form through one `:<-` hop — DIAGNOSTIC

  The `:<-` input-chain re-deref lives at layer 2+, so the layer ladder is
  the split that attributes the double-build clock between the reaction
  allocation itself (visible at layer 1, where there is no chain) and the
  per-hop input rebuild + re-deref (the L2−L1 and L3−L2 increments).

  ## Method, inherited rather than restated

  The converged arm's method, on its witnesses, on its lane: three-plus
  arms a segment interleaved under `lane/slot-order` (rotating AND
  reflecting), two segments a round with the floor in both, segment order
  alternating with the round, canonical-DOM parity before any clock,
  every mount read back at the arm's own arithmetic, `:ctl-2x` predicted
  before the run, the arm-order guard owning exit 2, and the
  segment-order verdict on every cross-seam figure
  (`p0-converge-app/segment-order-verdict`, required from the converged
  arm rather than copied — a second copy of an adjudication rule is a
  second authority).

  TWO deliberate differences from the converged mount rows, both stated:

    * ROUNDS = 4, EVEN. The converged page once reported a systematic
      segment-order effect (11 of 12 row-runs read higher Reagent-first,
      p = 0.0032) and named an even round count as the arm-level repair.
      THAT FINDING IS WITHDRAWN — the twelve were four correlated rows
      inside each of only three runs, not twelve trials, and the fixed
      start confounded order with time; the counterbalanced ten-run
      ensemble does not establish the effect on any row (rf2-6i0i2).
      THE REPAIR STANDS ON ITS OWN AND IS WHY THIS ENTRY KEEPS IT: a 3:2
      design over-weights one order, while a balanced 2:2 makes the raw
      mean and the order-balanced mean coincide BY CONSTRUCTION, which is
      a design property and needs no effect to justify it. This entry
      re-derives its own denominator, so no published row moves.
    * A RESIDUE GATE per segment-round (`lane/residue` back to baseline
      after a settle). The `handoff` arm holds a provisional +1 between
      render and commit; a plan in which every render commits must drive
      the census back to zero, and a leak here would silently convert
      later samples into warm-cache mounts. Equality, no tolerance.

  ## WHICH SCHEDULE THESE ROWS SPEAK FOR — read this before quoting one

  Every mount in this instrument — timed and counted alike — runs inside
  `react-dom/flushSync` (`coldmount_views.cljs`). That forces React's
  passive `useSyncExternalStore` subscribe to run before control returns,
  and that IS the ordering the rf2-2rtt6.25 hand-off needs in order to
  win its race with its own reaper. **The shipping client mount path does
  not force it**: `re-frame.substrate.adapter/render` is a bare
  `createRoot(…).render(…)`, and when these rows were taken (2026-07-31,
  reap horizon `setTimeout 0`) the reaper fired first there, so the
  escrowed reference was released, the commit missed, and the mount
  rebuilt — `bodyRuns` 2.00N, measured at N = 1 and N = 300
  (rf2-2rtt6.25, merged-PR audit of #7305; the browser assertion is
  `use-subscribe-public-mount-schedule-rebuilds`).

  So the `shipped` arm here is **the forced-synchronous MECHANISM arm**,
  not an acceptance witness for shipped performance. Its rows say what
  the hand-off costs and saves WHEN IT RUNS TO COMPLETION. They are not a
  statement about any mount a consumer performs, and the `:schedule` key
  on every record emitted below says so in the data as well as in this
  docstring. The instruments themselves — the counting witness, the
  fidelity control, the residue gate, the segment-order control — are
  unaffected; what changed is which schedule they speak for. Nothing here
  is unsound: Spec 006 §Render-phase provisional acquisition and commit
  adoption requires that correctness never depend on the reaper losing
  the race, and the lost race costs a construction and nothing else.

  UPDATE, 2026-08-03 (rf2-2rtt6.71): the reap horizon has since been
  RULED out to `setTimeout 4` — the shortest probed delay reading 1.00N
  at N = 1 and N = 300 on the ruling's own swap-the-primitive probe — by
  a measured margin and not by any React guarantee. **That changes
  nothing on this page.** No measurement window here was altered and no
  row was re-taken, so every figure below is still a forced-synchronous
  mechanism figure measured under the old horizon. Nor do the browser
  assertions witness the win: that runner's render-to-passive-flush gap
  measures >128 ms, so they read two builds at any shippable horizon.

  ## What fails the run

  The counting witness adjudicated per page BEFORE any clock (predicted
  commits / rebuilt / per-layer bodyRuns, exact — including the SHIPPED
  arm, which under THIS harness's forced-synchronous schedule reads the
  `handoff` row); the clock fidelity control (`uix-subs` vs `handoff` —
  ranges overlap or medians within the pre-declared 3% band, else every
  delta is void; re-pointed from `xcript` by rf2-2rtt6.25, because on
  this schedule the shipped hook runs the hand-off to completion and
  `xcript` is the double build it transcribes); the positive controls;
  the DOM read-backs; the residue gate; the arm-order guard (exit 2); the
  segment-order direction refusal. The records are written to the console
  before any refusal, so the evidence survives it.

  Driven by `coldmount_run.cjs` on the `:hicasso-bench` build id through
  `--config-merge` — no new build id, no `implementation/shadow-cljs.edn`
  edit."
  (:require ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.coldmount-views :as cmv]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.p0-converge-app :as converge]
            [re-frame.bench.hicasso.p0-reagent-views :as v]
            [re-frame.bench.order-guard :as guard]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [reagent.dom.client :as rdc]
            [uix.dom :as uix-dom]))

(def ^:private rounds
  "EVEN, deliberately — see the namespace docstring. 4 rounds = 2 rounds
  per segment order, so the raw mean over rounds IS the order-balanced
  mean."
  4)

(def ^:private mount-sampling {:warmup 8 :samples 12})

(def ^:private control-slack 0.25)

(def ^:private fidelity-band
  "The clock fidelity control passes when the shipped arm's and the
  transcription's per-round p50 RANGES overlap, or — declared here,
  before the run — their medians are within 3%. The second clause exists
  because ranges at low round counts can be narrow; 3% is the same band
  rf2-2rtt6.12 declared on the heap axis, and it sits well below the
  effects the ablation resolves."
  0.03)

(def ^:private witness-boundaries
  "Boundaries per counting-witness mount; 3 reads each, so N = 300 reads
  per call — the same N as a timed M1 mount."
  100)

(def ^:private schedule-provenance
  "STAMPED ON EVERY EMITTED RECORD (rf2-2rtt6.25, merged-PR audit of
  #7326). A row that travels without its schedule is a row that will be
  quoted as a shipped-performance claim, which is exactly what happened
  to this instrument's first `shipped` table. So the schedule rides in
  the data, not only in the docstring.

  See the namespace docstring's \"Which schedule these rows speak for\"."
  {:mount-schedule :forced-synchronous
   :how            "react-dom/flushSync around every mount — the timed ones in lane/mount-arm! and lane/mount-batch!, the counted ones in coldmount-views/witness!"
   :arm            "the shipped hook's hand-off MECHANISM, measured where it is allowed to run to completion"
   :not            (str "NOT a shipped-mount-performance claim, and still not one after rf2-2rtt6.71. "
                        "Every mount here is forced with flushSync, and every published row was taken "
                        "2026-07-31 while the reap horizon was setTimeout 0 — on which the public client "
                        "mount path (re-frame.substrate.adapter/render, a bare createRoot().render()) "
                        "reaped the escrowed reference before React's passive useSyncExternalStore "
                        "subscribe, so the commit missed and rebuilt: bodyRuns 2.00N at N = 1 and N = 300 "
                        "(rf2-2rtt6.25, merged-PR audit of #7305). Neither fact is repaired by re-pricing.")
   :correctness    (str "unaffected either way — Spec 006 §Render-phase provisional acquisition and "
                        "commit adoption requires that correctness never depend on the reaper losing "
                        "the race; the lost race costs a construction and the steady state is one "
                        "durable reference.")
   :horizon        (str "RULED setTimeout 4 (rf2-2rtt6.71, 2026-08-03) — the shortest probed delay "
                        "reading 1.00N at N = 1 and N = 300, on the ruling's own swap-the-primitive "
                        "probe. It is a MARGIN, not a React guarantee, and nothing on this page "
                        "witnesses it: no measurement window here was altered and no row was re-taken, "
                        "so these rows remain forced-synchronous mechanism rows. The browser assertions "
                        "listed below do not witness it either — the test runner's render-to-flush gap "
                        "measures >128 ms, so they still read two builds at any shippable horizon.")
   :assertions     ["use-subscribe-public-mount-schedule-rebuilds"
                    "use-subscribe-escrow-leg-answers-on-the-public-mount-schedule"
                    "use-subscribe-reaped-provisional-is-never-adopted-by-a-later-mount"]})

;; ---------------------------------------------------------------------------
;; Segments
;; ---------------------------------------------------------------------------

(def ^:private segments
  [{:id :reagent-subs :adapter reagent-adapter/adapter :name "Reagent-on-subs"}
   {:id :uix-subs     :adapter uix-adapter/adapter     :name "UIx-on-subs"}])

(defn- segment-order [r]
  (if (even? r) (vec segments) (vec (rseq (vec segments)))))

(def ^:private start-segment
  "Which segment leads round 0, and therefore how `segment-order-verdict`
  labels the even and odd strata. This entry's [[segment-order]] takes no
  counterbalanced start — round 0 is even, so Reagent leads it in every
  coldmount run — and the value is derived from the schedule rather than
  restated beside it, so the label and the order cannot drift apart."
  (:id (first (segment-order 0))))

(defn- enter-segment!
  "The converged arm's seam, unchanged: tear down, install this segment's
  adapter, re-register the sub graph (`cmv/register!` — the converged
  layer-1 sub plus the `:<-` chain and the witness chain) and stand the
  frame back up seeded. All outside every measured window; a teardown
  that throws is recorded and adjudicated, never swallowed."
  [{:keys [adapter]}]
  (try (rf/destroy-frame! v/subs-frame)
       (catch :default e (lane/teardown-failure! "enter-segment! destroy-frame!" e)))
  (when (rf/current-adapter)
    (try (rf/destroy-adapter!)
         (catch :default e (lane/teardown-failure! "enter-segment! destroy-adapter!" e))))
  (rf/init! adapter)
  (cmv/register!)
  (rf/make-frame {:id v/subs-frame})
  (frame/replace-app-db! v/subs-frame (v/seed-cells v/cells-n 0))
  (lane/leave-act-environment!)
  nil)

;; ---------------------------------------------------------------------------
;; Mount arms — the converged arm's constructors
;; ---------------------------------------------------------------------------

(defn- zeros [n] (vec (repeat n 0)))

(defn- floor-mount-arm
  [id element-of cells-of & [scale]]
  (let [scale (or scale 1)]
    {:id             id
     :scale          scale
     :parity-exempt? (not= 1 scale)
     :mount          (fn [container props _n]
                       (let [root (react-dom-client/createRoot container)]
                         (.render root (element-of (cells-of props)))
                         root))
     :unmount        (fn [root] (.unmount root))}))

(defn- reagent-mount-arm
  [id form-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (rdc/create-root container)]
                (rdc/render root (form-of props))
                root))
   :unmount (fn [root] (rdc/unmount root))})

(defn- uix-mount-arm
  [id element-of]
  {:id      id
   :mount   (fn [container props _n]
              (let [root (uix-dom/create-root container)]
                (uix-dom/render-root (element-of props) root)
                root))
   :unmount (fn [root] (uix-dom/unmount-root root))})

(defn- input-value [container sel]
  (some-> (.querySelector container sel) (.-value)))

(defn- verify-m1 [n]
  (fn [container]
    (and (= "0" (lane/text-at container 0))
         (= "0" (lane/text-at container (dec n))))))

(defn- verify-m2 [n]
  (fn [container]
    (and (= (v/field-value 0 0) (input-value container "#f0"))
         (= (v/field-value (dec n) 0)
            (input-value container (str "#f" (dec n)))))))

;; ---------------------------------------------------------------------------
;; The rows
;; ---------------------------------------------------------------------------

(def ^:private shapes
  {:M1 {:elements-of v/m1-elements
        :verify-of   verify-m1
        :props       {:n v/cells-n}
        :grid        cmv/m1-grid
        :uix-cells   cmv/uix-m1-cells
        :reagent     cmv/reagent-m1-views
        :floor       v/m1-floor
        :control     {:predicted (/ (double (v/m1-elements (* 2 v/cells-n)))
                                    (double (v/m1-elements v/cells-n)))
                      :basis     (str "element count: " (v/m1-elements (* 2 v/cells-n))
                                      " / " (v/m1-elements v/cells-n))}}
   :M2 {:elements-of v/m2-elements
        :verify-of   verify-m2
        :props       {:n v/fields-n}
        :grid        cmv/m2-grid
        :uix-cells   cmv/uix-m2-cells
        :reagent     cmv/reagent-m2-views
        :floor       v/m2-floor
        :control     {:predicted (/ (double (v/m2-elements (* 2 v/fields-n)))
                                    (double (v/m2-elements v/fields-n)))
                      :basis     (str "element count: " (v/m2-elements (* 2 v/fields-n))
                                      " / " (v/m2-elements v/fields-n))}}})

(defn- arms-for [shape layer segment-id]
  (let [{:keys [floor grid uix-cells reagent]} (get shapes shape)]
    (-> [(floor-mount-arm :floor floor (fn [{:keys [n]}] (zeros n)))]
        (into (case segment-id
                :reagent-subs
                [(reagent-mount-arm :reagent-subs
                   (fn [{:keys [n]}] [v/subs-root (get reagent layer) n]))]
                :uix-subs
                [(uix-mount-arm :uix-subs
                   (fn [{:keys [n]}]
                     (cmv/uix-root grid (get-in uix-cells [:uix-subs layer]) n)))
                 (uix-mount-arm :uix-xcript
                   (fn [{:keys [n]}]
                     (cmv/uix-root grid (get-in uix-cells [:uix-xcript layer]) n)))
                 (uix-mount-arm :uix-handoff
                   (fn [{:keys [n]}]
                     (cmv/uix-root grid (get-in uix-cells [:uix-handoff layer]) n)))]))
        (conj (floor-mount-arm :ctl-2x floor
                               (fn [{:keys [n]}] (zeros (* 2 n))) 2)))))

(def ^:private row-specs
  {:M1L1 {:shape :M1 :layer 1 :grade :primary
          :doc "the converged M1 mount (901 el / 300 boundaries), layer-1 subs — the primary witness"}
   :M1L2 {:shape :M1 :layer 2 :grade :primary
          :doc "the M1 mount through one :<- hop ([:cm/l2 i] :<- [:p0/cell i])"}
   :M1L3 {:shape :M1 :layer 3 :grade :primary
          :doc "the M1 mount through two :<- hops ([:cm/l3 i] :<- [:cm/l2 i] :<- [:p0/cell i])"}
   :M2L1 {:shape :M2 :layer 1 :grade :diagnostic
          :doc "the converged M2 form (51 el / 12 fields), layer-1 subs — DIAGNOSTIC, clamp-quantised per rf2-2rtt6.2"}
   :M2L2 {:shape :M2 :layer 2 :grade :diagnostic
          :doc "the M2 form through one :<- hop — DIAGNOSTIC"}})

(defn- row-spec [row-key]
  (let [{:keys [shape layer] :as spec} (get row-specs row-key)
        sh (get shapes shape)]
    (merge sh spec
           {:row-key  row-key
            :arms-for (fn [segment-id] (arms-for shape layer segment-id))})))

(defn- query-row []
  (let [s (or (some-> js/window .-location .-search) "")]
    (or (some (fn [k] (when (re-find (re-pattern (str "row=" (name k) "(&|$)")) s) k))
              (keys row-specs))
        :M1L1)))

;; ---------------------------------------------------------------------------
;; Expectation and parity — the converged arm's, over these arms
;; ---------------------------------------------------------------------------

(defn- expectation
  [{:keys [elements-of verify-of props]} arm]
  (let [n (* (or (:scale arm) 1) (:n props))]
    {:elements (elements-of n)
     :verify   (verify-of n)}))

(defn- base-elements [{:keys [elements-of props]}] (elements-of (:n props)))

(defn- parity-of-segment!
  [{:keys [row-key props arms-for] :as spec} segment-id]
  (let [{:keys [mounts agree? disagree reference]}
        (lane/parity (arms-for segment-id) props)
        wrong (into {}
                    (comp (map (fn [{:keys [arm container]}]
                                 [(:id arm)
                                  {:expected (:elements (expectation spec arm))
                                   :got      (lane/element-count container)}]))
                          (remove (fn [[_ {:keys [expected got]}]] (= expected got))))
                    mounts)]
    (try
      {:reference reference
       :problems  (cond-> []
                    (not agree?)
                    (conj {:segment segment-id :row row-key
                           :problem :canonical-dom-disagreement :arms disagree})

                    (seq wrong)
                    (conj {:segment segment-id :row row-key
                           :problem :element-count :arms wrong}))}
      (finally (doseq [m mounts] (lane/release! m))))))

(defn- parity! [spec]
  (let [by-seg (reduce (fn [acc {:keys [id] :as segment}]
                         (enter-segment! segment)
                         (assoc acc id (parity-of-segment! spec id)))
                       {}
                       segments)
        cross  (when (not= (get-in by-seg [:reagent-subs :reference])
                           (get-in by-seg [:uix-subs :reference]))
                 [{:problem :cross-segment-disagreement :row (:row-key spec)}])]
    {:problems (into (vec (mapcat (comp :problems val) by-seg)) cross)}))

;; ---------------------------------------------------------------------------
;; THE COUNTING WITNESS — adjudicated before any clock
;; ---------------------------------------------------------------------------

(defn- witness-sweep!
  "Per segment: the counter-instrumented witness mounts, over disjoint
  cell ranges per call. Answers `{segment-id [result …]}`."
  [{:keys [layer]}]
  (reduce (fn [acc {:keys [id] :as segment}]
            (enter-segment! segment)
            (assoc acc id
                   (case id
                     :reagent-subs
                     [(cmv/witness! :reagent layer witness-boundaries 0)
                      (cmv/witness! :reagent layer witness-boundaries 900)]
                     :uix-subs
                     [(cmv/witness! :xcript  layer witness-boundaries 0)
                      (cmv/witness! :xcript  layer witness-boundaries 900)
                      (cmv/witness! :handoff layer witness-boundaries 1800)
                      (cmv/witness! :handoff layer witness-boundaries 2700)
                      ;; rf2-2rtt6.25 — the FORCED-SYNCHRONOUS MECHANISM arm.
                      ;; `witness!` mounts inside `flushSync`, and on THAT
                      ;; schedule the shipped hook runs the hand-off to
                      ;; completion and reads the `handoff` row. On the public
                      ;; `createRoot().render()` path it reads the `xcript` row
                      ;; instead — ns docstring, "Which schedule these rows
                      ;; speak for". Own disjoint cell ranges.
                      (cmv/witness! :shipped layer witness-boundaries 3600)
                      (cmv/witness! :shipped layer witness-boundaries 4500)])))
          {}
          segments))

(defn- witness-failures
  "Compare every witness result against the prediction, exactly. For N
  reads at page layer L:

      xcript    commits N   rebuilt N   bodyRuns 2N at every layer <= L
      handoff   commits N   rebuilt 0   bodyRuns  N at every layer <= L
      shipped   commits N   rebuilt 0   bodyRuns  N at every layer <= L
      reagent   commits 0   rebuilt 0   bodyRuns  N at every layer <= L

  Layers above L must read 0 — a non-zero there means the chain is not
  the chain this page claims to price.

  THE `shipped` ROW IS SCHEDULE-CONDITIONAL, and this is the harness's
  schedule, not the consumer's. `witness!` mounts inside `flushSync`, so
  React's passive subscribe runs before control returns and the hand-off
  reaches its adoption; that is what makes `rebuilt 0` predictable here.
  On the public `createRoot().render()` path the reaper wins first and
  the same hook reads the `xcript` row — `rebuilt N`, `bodyRuns 2N`
  (rf2-2rtt6.25, audit of #7305). So this prediction adjudicates that the
  MECHANISM runs to completion when it is allowed to; it is not an
  acceptance witness for shipped mount performance, and a failure here is
  a broken mechanism rather than a lost race. Its counters come from the
  sub-cache rather than from an instrumented `subscribe-fn`
  (`coldmount-views` namespace docstring), so `shipped` and `handoff`
  remain independent measurements of the same mechanism."
  [by-seg layer]
  (vec
    (for [[seg ws] by-seg
          {:keys [variant reads offset] :as w} ws
          :let [n      reads
                factor (case variant :xcript 2 1)
                exp    (merge
                         (case variant
                           :xcript  {:commits n :rebuilt n}
                           :handoff {:commits n :rebuilt 0}
                           :shipped {:commits n :rebuilt 0}
                           :reagent {:commits 0 :rebuilt 0})
                         {:body1 (if (<= 1 layer) (* factor n) 0)
                          :body2 (if (<= 2 layer) (* factor n) 0)
                          :body3 (if (<= 3 layer) (* factor n) 0)})
                bad    (into {}
                             (keep (fn [[k want]]
                                     (when (not= (get w k) want)
                                       [k {:predicted want :measured (get w k)}])))
                             exp)]
          :when (or (seq bad) (not (:ok? w)))]
      {:segment seg :variant variant :layer layer :offset offset :reads n
       :dom-ok? (:ok? w) :mismatches bad})))

;; ---------------------------------------------------------------------------
;; One round of this page's row in one segment
;; ---------------------------------------------------------------------------

(defn- mark-predecessor! [coll id]
  (swap! coll assoc :prev id)
  nil)

(defn- mount-round!
  [coll t {:keys [row-key props arms-for] :as spec} segment-id]
  (let [arms   (arms-for segment-id)
        n      (count arms)
        expect (into {} (map (juxt :id #(expectation spec %))) arms)
        acc    (atom (zipmap (map :id arms) (repeat [])))]
    (dotimes [s (+ (:warmup mount-sampling) (:samples mount-sampling))]
      (doseq [j (lane/slot-order n s)]
        (let [arm (nth arms j)
              {:keys [ms mounts]} (lane/mount-batch! arm props 1)
              {exp-elements :elements verify :verify} (get expect (:id arm))]
          (doseq [m mounts]
            (let [ok? (and (= exp-elements (lane/element-count (:container m)))
                           (verify (:container m)))]
              (swap! t (fn [{:keys [of bad]}]
                         {:of (inc of) :bad (if ok? bad (inc bad))}))))
          (doseq [m mounts] (lane/release! m))
          (let [label (str (name row-key) "/" (name segment-id) "/" (name (:id arm)))]
            (if (>= s (:warmup mount-sampling))
              (do (lane/collect! coll label ms)
                  (swap! acc update (:id arm) conj ms))
              (mark-predecessor! coll label))))))
    @acc))

(defn- run-round!
  "One round: both segments in this round's order, each with the residue
  gate around its mount round. Answers a promise of
  `{segment-id {arm-id [ms …]}}`."
  [spec r coll t]
  (lane/chain
    {}
    (segment-order r)
    (fn [acc {:keys [id] :as segment}]
      (enter-segment! segment)
      (lane/assert-teardown-clean! (str "entering segment " (name id)))
      (-> (lane/settle!)
          (.then
            (fn [_]
              (let [baseline (lane/residue v/subs-frame)
                    rd       (mount-round! coll t spec id)]
                (-> (lane/settle!)
                    (.then
                      (fn [_]
                        (lane/assert-residue!
                          baseline v/subs-frame
                          (str "mount round, row " (name (:row-key spec))
                               ", segment " (name id)))
                        (lane/assert-teardown-clean!
                          (str "mount round, segment " (name id)))
                        (assoc acc id rd)))))))))))

;; ---------------------------------------------------------------------------
;; Aggregation — the two numbers, per round, and their ratio
;; ---------------------------------------------------------------------------

(defn- p50-of [xs] (:p50 (lane/summarise xs)))

(defn- range-of [vs]
  {:mean (lane/round4 (/ (reduce + 0.0 vs) (count vs)))
   :min  (lane/round4 (apply min vs))
   :max  (lane/round4 (apply max vs))
   :per-round (mapv lane/round4 vs)})

(defn- strata-of
  "The per-round vector split by which segment ran first. Even rounds are
  Reagent-first (see [[segment-order]]); at 4 rounds the split is 2:2 and
  the design is balanced."
  [vs]
  (let [rf-v (vec (keep-indexed #(when (even? %1) %2) vs))
        uf-v (vec (keep-indexed #(when (odd? %1) %2) vs))]
    {:reagent-first (range-of rf-v)
     :uix-first     (range-of uf-v)
     :strata-overlap? (and (<= (apply min rf-v) (apply max uf-v))
                           (<= (apply min uf-v) (apply max rf-v)))}))

(defn- round-quantities
  "Everything one round yields, from its two segments' p50s."
  [slice]
  (let [rg      (get slice :reagent-subs)
        ux      (get slice :uix-subs)
        floor-r (p50-of (:floor rg))
        floor-u (p50-of (:floor ux))
        reagent (p50-of (:reagent-subs rg))
        uix     (p50-of (:uix-subs ux))
        xcript  (p50-of (:uix-xcript ux))
        handoff (p50-of (:uix-handoff ux))
        seam    (/ floor-u floor-r)
        delta   (- xcript handoff)
        excess  (- uix reagent)
        excess-seam (- (/ uix seam) reagent)]
    {:floor-reagent floor-r
     :floor-uix     floor-u
     :reagent       reagent
     :uix           uix
     :xcript        xcript
     :handoff       handoff
     :ctl-ratio-reagent (/ (p50-of (:ctl-2x rg)) floor-r)
     :ctl-ratio-uix     (/ (p50-of (:ctl-2x ux)) floor-u)
     :seam          seam
     :rz            (/ (/ uix floor-u) (/ reagent floor-r))
     :delta         delta
     :excess        excess
     :excess-seam   excess-seam
     :fraction      (when (pos? excess) (/ delta excess))
     :fraction-seam (when (pos? excess-seam) (/ (/ delta seam) excess-seam))}))

(defn- fidelity-of
  "The clock fidelity control: the transcription must reproduce the
  shipped hook. Both readers here are the same clock, so the legs are the
  per-round p50 ranges and the medians.

  RE-POINTED BY rf2-2rtt6.25, ON THIS HARNESS'S SCHEDULE. Before the
  hand-off landed, the shipped hook was the double build and `xcript` was
  its transcription, so `xcript` was the fidelity partner. Inside
  `flushSync` the shipped hook now runs the hand-off to completion, so
  the transcription that has to reproduce it is `handoff`, and this
  control is the clock half of the MECHANISM arm: the shipped arm must
  sit inside the single-build band on the schedule the harness forces.
  It says nothing about the public `createRoot().render()` path, where
  the reaper wins and the shipped clock is the double build's — see the
  ns docstring's schedule section. `xcript` stays in the run as the
  double-build reference; it is what the published delta is measured
  against, and on THIS schedule it is deliberately NOT expected to match
  the shipped clock."
  [uix-vec handoff-vec]
  (let [a   (range-of uix-vec)
        b   (range-of handoff-vec)
        ovl (and (<= (:min a) (:max b)) (<= (:min b) (:max a)))
        rel (js/Math.abs (dec (/ (p50-of handoff-vec) (p50-of uix-vec))))]
    {:shipped-uix    a
     :copy-handoff   b
     :ranges-overlap? ovl
     :medians-apart  (lane/round4 rel)
     :band           fidelity-band
     :ok?            (and (js/isFinite rel) (or ovl (<= rel fidelity-band)))}))

(defn- rule-verdict
  "The pre-registered rf2-2rtt6.14 decision rule, mechanised for ONE row.
  BOTH estimators (raw and seam-adjusted), ALL rounds:

    :below-20        every estimate resolves and sits under 0.20
    :at-or-above-20  every estimate resolves and sits at/over 0.20
    :unresolvable    a round's excess did not resolve above zero, or the
                     estimates straddle 0.20

  The rule itself lives on rf2-2rtt6.14 and is applied, not re-litigated:
  `< 20% or unresolvable` feeds the close; `>= 20%` must hold on
  representative layer-1 AND layer-2/3 mounts to feed the reopen."
  [fractions fractions-seam]
  (let [all (into (vec fractions) fractions-seam)]
    (cond
      (some nil? all)             :unresolvable
      (every? #(< % 0.20) all)    :below-20
      (every? #(>= % 0.20) all)   :at-or-above-20
      :else                       :unresolvable)))

;; ---------------------------------------------------------------------------
;; Publication
;; ---------------------------------------------------------------------------

(defn- publish!
  [{:keys [row-key grade layer doc control] :as _spec} slices t coll witness-results wfails]
  (let [qs        (mapv round-quantities slices)
        pick      (fn [k] (mapv k qs))
        fractions      (pick :fraction)
        fractions-seam (pick :fraction-seam)
        resolved? (fn [vs] (not-any? nil? vs))
        fid       (fidelity-of (pick :uix) (pick :handoff))
        rz        (pick :rz)
        order     (converge/segment-order-verdict rz rounds start-segment)
        c-rg      (lane/control-verdict (:predicted control)
                                        (select-keys (range-of (pick :ctl-ratio-reagent))
                                                     [:min :max :mean])
                                        control-slack)
        c-ux      (lane/control-verdict (:predicted control)
                                        (select-keys (range-of (pick :ctl-ratio-uix))
                                                     [:min :max :mean])
                                        control-slack)
        verdict   (rule-verdict fractions fractions-seam)]
    (lane/record! (name row-key)
                  {:benchmark   (keyword "hicasso.coldmount" (name row-key))
                   :bead        "rf2-2rtt6.15 (instrument); re-run for rf2-2rtt6.25 as the forced-synchronous MECHANISM arm"
                   :doc         doc
                   :grade       grade
                   :layer       layer
                   :witness-set "rf2-2rtt6.2 (converged; p0-converged-witness-set.md)"
                   :schedule    schedule-provenance
                   :spine       (str "the SHIPPED re-frame.substrate.spine/use-subscribe — "
                                     "rf2-2rtt6.13 (no retained dead handle) AND rf2-2rtt6.25 "
                                     "(the hook-scoped provisional hand-off) both landed. Under "
                                     "THIS harness's forced-synchronous schedule the cold read "
                                     "builds ONCE and the commit adopts it; on the public "
                                     "createRoot().render() path the reaper wins first and the "
                                     "same hook builds TWICE (rf2-2rtt6.25, audit of #7305). "
                                     "`xcript` is the double build, kept as the reference the "
                                     "delta is measured against")
                   :rounds      rounds
                   :balanced-design? (even? rounds)
                   :per-round   {:floor-reagent (mapv lane/round4 (pick :floor-reagent))
                                 :floor-uix     (mapv lane/round4 (pick :floor-uix))
                                 :reagent-subs  (mapv lane/round4 (pick :reagent))
                                 :uix-subs      (mapv lane/round4 (pick :uix))
                                 :uix-xcript    (mapv lane/round4 (pick :xcript))
                                 :uix-handoff   (mapv lane/round4 (pick :handoff))}
                   :double-build-ms {:doc "xcript − handoff, per round (ms, UIx segment): the build/dispose/rebuild churn of the cold-mount double build over the row's N cold reads"
                                     :summary (range-of (pick :delta))}
                   :red-zone-excess-ms {:doc "uix-subs − reagent-subs, per round (ms, cross-seam, same run): the mount red-zone excess this row's fraction is priced against"
                                        :raw (range-of (pick :excess))
                                        :seam-adjusted (range-of (pick :excess-seam))
                                        :resolved-above-zero? (and (resolved? fractions)
                                                                   (resolved? fractions-seam))}
                   :seam        (range-of (pick :seam))
                   :rz-ratio    (assoc (range-of rz)
                                       :numerator :uix-subs :denominator :reagent-subs
                                       :claim (if (:magnitude-resolved? order)
                                                :magnitude :direction-only))
                   :segment-order-control order
                   :status      :evidence})
    (lane/record! "coldmount-fraction"
                  {:row row-key
                   :layer layer
                   :grade grade
                   :schedule schedule-provenance
                   :fraction-of-red-zone
                   {:raw           (when (resolved? fractions) (range-of fractions))
                    :seam-adjusted (when (resolved? fractions-seam) (range-of fractions-seam))
                    :per-round-raw fractions
                    :per-round-seam-adjusted fractions-seam
                    :strata (when (resolved? fractions) (strata-of fractions))}
                   :rule (str "pre-registered on rf2-2rtt6.14: fraction < 20% of the mount "
                              "red-zone, or unresolvable from round-to-round spread -> close "
                              "as 'take rf2-2rtt6.13 alone; Spec 006 no-grace-period stands'; "
                              ">= 20% on representative layer-1 AND layer-2/3 mounts -> "
                              "reopen for a hook-scoped provisional hand-off design pass only")
                   :row-verdict verdict})
    (lane/record! "fidelity" (assoc fid :row row-key :schedule schedule-provenance))
    (lane/record! "witness-counts"
                  {:row row-key :layer layer
                   :schedule schedule-provenance
                   :predictions {:xcript  "commits N, rebuilt N, bodyRuns 2N at every layer <= L"
                                 :handoff "commits N, rebuilt 0, bodyRuns N at every layer <= L"
                                 :shipped "commits N, rebuilt 0, bodyRuns N at every layer <= L — SCHEDULE-CONDITIONAL: the shipped hook reads the handoff row only because this harness mounts inside flushSync. On the public createRoot().render() path it reads the xcript row (rf2-2rtt6.25, audit of #7305). Not an acceptance witness for shipped mount performance."
                                 :reagent "commits 0, rebuilt 0, bodyRuns N at every layer <= L"}
                   :results witness-results
                   :failures wfails})
    (lane/record! "positive-control"
                  {:row row-key
                   :predicted (:predicted control)
                   :basis     (:basis control)
                   :reagent-segment c-rg
                   :uix-segment     c-ux})
    (lane/record! "verification" {row-key (lane/tally-value t)})
    (let [vd (lane/guard! (:samples @coll)
                          (str "Hicasso cold-mount double build — " (name row-key)))]
      (lane/record! "arm-order-guard"
                    {:row row-key
                     :tolerance (:tolerance vd)
                     :contaminated? (:contaminated? vd)
                     :unchecked? (:unchecked? vd)
                     :refuse? (:refuse? vd)
                     :arms (:arms vd)})
      (when (:refuse? vd) (set! (.-HICASSO_GUARD_REFUSED js/window) true)))
    (when (some (complement :ok?) [c-rg c-ux])
      (set! (.-HICASSO_CONTROL_FAILED js/window) true))
    (when (:refuse? order)
      (set! (.-HICASSO_ORDER_REFUSED js/window) true))
    ;; The claims this table is published on: the counting witness and the
    ;; clock fidelity control. A mismatch or a non-fidelitous transcription
    ;; voids every delta; the records above are already on the console, and
    ;; the driver exits 4.
    (when (or (seq wfails) (not (:ok? fid)))
      (set! (.-HICASSO_CLAIM_FAILED js/window) true))
    (lane/assert-verified! t (str "row " (name row-key)))
    nil))

;; ---------------------------------------------------------------------------
;; The schedule's preconditions
;; ---------------------------------------------------------------------------

(defn- slot-order-varies-at?
  "The plan sizes this entry runs (3 arms Reagent-side, 5 UIx-side) plus
  the k=2 canary the converged arm keeps — each must emit more than one
  order across sample indices, or `both orders` is a claim the schedule
  cannot support."
  [k]
  (> (count (distinct (map #(guard/slot-order k %) (range 8)))) 1))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn ^:export -main
  []
  (try
    (lane/leave-act-environment!)
    (cond
      (not (lane/self-test!))
      (do (lane/fail! (str "the arm-order guard's SELF-TEST failed — this copy of the rule "
                           "no longer behaves like the one the .cjs drivers use, so nothing "
                           "was measured"))
          (lane/done!))

      (not (every? slot-order-varies-at? [2 3 5]))
      (do (lane/fail! (str "lane/slot-order emits ONE order at a plan size this entry runs "
                           "(or at the k=2 canary) — the rf2-ouwh8 degeneracy class. Nothing "
                           "is measured until the schedule is repaired; the repair belongs "
                           "to the schedule, never to the tolerance"))
          (lane/done!))

      :else
      (let [row-key (query-row)
            spec    (row-spec row-key)
            {:keys [problems]} (parity! spec)]
        (js/console.log (str ";; HICASSO coldmount row " (name row-key)))
        (lane/record! "parity"
                      {:row row-key :problems problems :ok? (empty? problems)
                       :note (str "canonical DOM with attribute names sorted, inside each "
                                  "segment AND across the seam, over the judged arms "
                                  "(floor, reagent-subs / uix-subs, uix-xcript, uix-handoff); "
                                  "element counts checked against written arithmetic — "
                                  (base-elements spec) " for " (name row-key)
                                  ", and the arm's OWN scale for the 2x control")})
        (if (seq problems)
          (do (lane/fail! (str "the arms do not build the same page under :advanced — "
                               (pr-str problems)))
              (lane/done!))
          (let [wit    (witness-sweep! spec)
                wfails (witness-failures wit (:layer spec))]
            (if (seq wfails)
              (do (lane/record! "witness-counts"
                                {:row row-key :layer (:layer spec)
                                 :results wit :failures wfails})
                  (set! (.-HICASSO_CLAIM_FAILED js/window) true)
                  (lane/fail! (str "THE COUNTING WITNESS FALSIFIED A PREDICTION — the "
                                   "construction counts this ablation is built on did not "
                                   "hold, so no clock taken over these arms is attributable: "
                                   (pr-str wfails)))
                  (lane/done!))
              (let [coll (lane/sample-collector)
                    t    (lane/tally)]
                (-> (lane/chain [] (range rounds)
                                (fn [acc r]
                                  (-> (run-round! spec r coll t)
                                      (.then (fn [slice] (conj acc slice))))))
                    (.then (fn [slices]
                             (publish! spec slices t coll wit wfails)
                             (lane/done!)
                             nil))
                    (.catch (fn [e]
                              (lane/fail! (str "the run rejected: " e))
                              (lane/done!))))))))))
    (catch :default e
      (lane/fail! (str "the run threw: " e))
      (lane/done!))))
