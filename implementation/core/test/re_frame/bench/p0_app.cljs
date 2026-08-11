(ns re-frame.bench.p0-app
  "EP-0038 P0 — the `:advanced` browser entry. One bundle, three modes.

  **All bar numbers are browser numbers** (HD-012 / validation.md): a real
  browser, `:advanced`, `goog.DEBUG false`. JVM and Node figures are
  diagnostic only and are never quotable against the bar. Two independent
  reasons force a plain `:browser` entry rather than a test target:

  1. Spec 009 instrumentation, schema validation and trace emission are
     all `goog.DEBUG`-gated and all sit on the subscription and render
     paths these rows measure. A development build would publish a cost no
     user pays.
  2. `:advanced` cannot compile shadow's `:browser-test` target —
     `cljs-test-display`'s `goog.define`s collide under Closure.

  ## ONE ROUND PER PAGE, and the measurement that forced it

  `?round=N` runs exactly ONE round and parks its raw record on
  `window.P0_ROUND`; the driver reloads the page for the next one. That is
  not fastidiousness. Run as a single page, this instrument's own probe
  measured `usedJSHeapSize` climbing 34 -> 46 -> 55 -> 63 -> 75 -> 87 MB
  across six segment entries — about 12 MB each, with `body-children`
  pinned at 2 the whole way, so nothing was leaking into the document —
  and on that heap the FLOOR arm, a hand-written `createElement` walk
  that cannot change, drifted 3.4 -> 5.8 -> 7.0 ms. The arm-order guard
  refused on phase, correctly.

  Three repairs were tried against it and are recorded because each one
  narrows what the cause can be: a forced major collection between every
  sample (no effect), hoisting each arm so every round calls the identical
  closures (no effect), and unwrapping the `flushSync` around every
  unmount (no effect on the climb, though it is the right shape and stays
  — see `p0-harness`). What did not drift, in the same page and the same
  run, was the POSITIVE CONTROL: two floor arms, no adapter installed and
  destroyed between its rounds, sitting at 1.9167 / 1.9574 / 1.9574. The
  accumulation belongs to the substrate arms and their adapter segments,
  not to the harness around them.

  Rather than publish a figure against a page that is getting slower, the
  round is made the unit of a page. A fresh document cannot inherit the
  previous round's heap, so the drift cannot exist across rounds by
  construction, and the guard's phase factor is then asking a question
  about the machine rather than about a leak this arm already knows it has.
  **The accumulation itself is a finding and is filed, not swept up.**

  `?mode=heap` installs the retention instrument on `window.P0H` and does
  nothing else — heap readings belong to the driver, which owns the
  collector.

  `?mode=aggregate` installs `window.P0A`, which folds the driver's
  collected per-round EDN into the published records: ranges across
  rounds, the red-zone ratios, and the arm-order verdict. It answers the
  row's GATES beside the record — the summed `N unverified of M` and the
  positive control's verdict — because a figure the driver prints and
  never exits on is decoration (rf2-95s5b).

  Built and driven by `p0_run.cjs` beside this file.

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.4."
  (:require [cljs.reader :as reader]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.order-guard :as guard]
            [re-frame.bench.p0-arms :as arms]
            [re-frame.bench.p0-fixture :as fx]
            [re-frame.bench.p0-harness :as h]
            [re-frame.bench.p0-heap :as heap]))

(def order-tolerance
  "A relative difference of medians for the arm-order guard. A browser
  mount window moves several percent between rounds where a JVM allocation
  counter does not, so this sits above this instrument's noise and far
  below the 2.01x the recorded fault made."
  0.25)

(defn- q [k default]
  (if-some [v (.get (js/URLSearchParams. (.-search js/location)) k)]
    (js/parseInt v 10)
    default))

(defn- mode []
  (or (.get (js/URLSearchParams. (.-search js/location)) "mode") "round"))

(defn- fail! [why]
  (set! (.-P0_ERROR js/window) (str why))
  (js/console.error (str ";; P0 FAILED — " why)))

(defn- segment-order
  "Segment order for round `r`: forward on even rounds, REVERSED on odd.

  Two segments admit exactly two orders, and running both is what makes
  the cross-segment figure a both-orders result rather than a single-order
  one. A single-order result has not been checked and may not be
  reported — that is the arm-order guard's rule, applied at the seam it
  cannot see."
  [r]
  (if (even? r) (vec arms/segments) (vec (rseq (vec arms/segments)))))

(defn- witness-order
  "Witness order for round `r`, alternating exactly as the segments do.

  The witnesses are a nuisance factor in their own right and this was
  measured, not anticipated. With W1 always first and W3 always second,
  W3 ran its samples on a page that W1's 36 mounts had already loaded, and
  the arm-order guard reported W3's UIx segment as phase-contaminated —
  LAST-THIRD 1.4815x FIRST-THIRD, ranges disjoint — while W1, which always
  ran first, came out clean. A fixed witness order makes 'second' a
  permanent property of one witness, which is the same trap as a fixed arm
  order one level up."
  [r]
  (if (even? r) (vec arms/mount-witnesses) (vec (rseq (vec arms/mount-witnesses)))))

;; ---------------------------------------------------------------------------
;; The parity gate — run before any clock is read, in EVERY segment
;; ---------------------------------------------------------------------------

(defn- parity-of-segment!
  "Mount every arm of every mount witness and answer
  `{witness-id {:canon {arm html} :counts {arm n} :problems [...]}}`.

  A canonical-DOM gate once caught an arm rendering an empty page, so this
  runs under `:advanced` too: a parity that held under `:none` and failed
  under `:advanced` would be a renaming bug silently deciding the
  comparison."
  [segment-id]
  (reduce
    (fn [acc {:keys [id elements arms-for]}]
      (let [{:keys [mounts agree? canon counts disagree]} (h/parity (arms-for segment-id))]
        (try
          (assoc acc id
                 {:canon    canon
                  :counts   counts
                  :problems (cond-> []
                              (not agree?)
                              (conj {:problem :canonical-dom-disagreement :arms disagree})
                              (not (every? #(= elements %) (vals counts)))
                              (conj {:problem  :element-count
                                     :expected elements
                                     :got      counts}))})
          (finally (doseq [m mounts] (h/release! m))))))
    {}
    arms/mount-witnesses))

(defn- parity!
  "Both segments' parity, plus the CROSS-segment check. The floor is in
  both segments and is the reference in both, so if each segment agrees
  internally and the two floors agree with each other, all four arms
  built one page."
  []
  (let [parities (reduce (fn [acc {:keys [id] :as segment}]
                           (arms/enter-segment! segment)
                           (assoc acc id (parity-of-segment! id)))
                         {}
                         arms/segments)
        problems (into []
                       (for [[seg ws] parities
                             [w {:keys [problems]}] ws
                             p problems]
                         (assoc p :segment seg :witness w)))
        cross    (into []
                       (for [{:keys [id]} arms/mount-witnesses
                             :let [a (get-in parities [:reagent-subs id :canon :floor])
                                   b (get-in parities [:uix-subs id :canon :floor])]
                             :when (not= a b)]
                         {:problem :cross-segment-floor-disagreement :witness id}))]
    {:problems (into problems cross)
     :ok?      (and (empty? problems) (empty? cross))
     :counts   (into {} (for [[seg ws] parities]
                          [seg (into {} (for [[w v] ws] [w (:counts v)]))]))}))

;; ---------------------------------------------------------------------------
;; One round
;; ---------------------------------------------------------------------------

(defn- mount-round-of-segments!
  "One round's mount rows over both segments, in this round's segment
  order. Answers `{witness-id {segment-id {:p50 :ratio :order :bad :total
  :schedule}}}` plus the heap/DOM probes taken at each segment entry."
  [r sampling arms-of]
  (let [acc    (atom {})
        pos    (atom 0)
        probes (atom [])]
    (doseq [{:keys [id] :as segment} (segment-order r)]
      (arms/enter-segment! segment)
      (swap! probes conj (assoc (h/probe) :round r :segment id))
      (doseq [{:keys [elements per-sample] :as w} (witness-order r)]
        (let [as (get arms-of [(:id w) id])
              {:keys [readings order bad total position schedule]}
              (h/mount-round! as sampling elements per-sample @pos)
              norm (h/normalise readings :floor)]
          (reset! pos position)
          (swap! acc assoc-in [(:id w) id]
                 {:p50 (:p50 norm) :ratio (:ratio norm) :order order
                  :bad bad :total total :schedule schedule}))))
    {:rows @acc :probes @probes}))

(defn- control-round!
  "The clock's positive control for this round, PREDICTED before it is
  measured: `control-2x` is the floor's W1 page with twice the rows and
  nothing else changed, so its ratio to `control-1x` is fixed by element
  arithmetic at 2403/1203 = 1.997. A run whose control misses has not
  earned the right to publish the arms beside it.

  Both arms are FLOOR arms — no substrate, no boundary, no subscription —
  so the control prices the INSTRUMENT and not a candidate, and reads the
  same in either segment.

  **The control gets its own, larger warm-up, and the reason is the
  control's own reading.** Run at the arms' warm-up on a page whose single
  round is the only thing it will ever do, it read 1.7727 / 1.7681 /
  1.8000 against a predicted 1.9975 — an 11% miss — where the same control
  in a page that ran three rounds read 1.9167 / 1.9574 / 1.9574, settling
  as the page warmed. A cold page's mount is dominated by first-call costs
  that do NOT scale with element count, and those costs pull a
  work-proportional ratio toward 1.0. That is a fact about cold pages
  rather than about the arms, and the control is the instrument that shows
  it."
  [sampling arms]
  (let [{:keys [readings]} (h/mount-round! arms
                                           (update sampling :warmup * 3)
                                           nil 1 100000)]
    ;; `nil` as the expectation: the two control arms build DIFFERENT
    ;; pages by construction, which is the whole point of the control and
    ;; the one place the element gate cannot apply.
    (get-in (h/normalise readings :control-1x) [:ratio :control-2x])))

(defn- bulk-round-of-segments!
  "One round of one bulk `kind` over both segments, in this round's
  segment order. Answers a promise of `{segment-id {...}}`."
  [r kind sampling]
  (let [acc (atom {})
        pos (atom 200000)]
    (-> (arms/chain nil (segment-order r)
                    (fn [_ segment]
                      (arms/enter-segment! segment)
                      (let [mounts (arms/mount-bulk-arms! (arms/bulk-arms (:id segment)))]
                        (-> (arms/bulk-round! mounts kind sampling @pos)
                            (.then (fn [{:keys [readings legs order bad total
                                                position schedule]}]
                                     (reset! pos position)
                                     (arms/release-bulk-arms! mounts)
                                     (let [norm (h/normalise readings :floor)]
                                       (swap! acc assoc (:id segment)
                                              {:p50   (:p50 norm)
                                               :ratio (:ratio norm)
                                               :legs  (arms/leg-summary legs)
                                               :order order
                                               :bad   bad
                                               :total total
                                               :schedule schedule})
                                       nil)))
                            (.catch (fn [e]
                                      (arms/release-bulk-arms! mounts)
                                      (throw e)))))))
        (.then (fn [_] @acc)))))

(defn- run-round!
  "Everything one round measures, in one fresh page."
  [r]
  (let [sampling {:warmup (q "warmup" 4) :samples (q "samples" 12)}
        ;; THE ARMS ARE BUILT ONCE PER PAGE. When they were rebuilt per
        ;; round inside one page, every round pushed a fresh closure
        ;; through one call site and the sites went megamorphic on nothing
        ;; the benchmark is trying to measure.
        arms-of  (into {}
                       (for [{:keys [id]} arms/segments
                             {w :id :keys [arms-for]} arms/mount-witnesses]
                         [[w id] (arms-for id)]))
        par      (parity! )]
    (if-not (:ok? par)
      (do (fail! (str "the arms do not build the same page under :advanced — "
                      (pr-str (:problems par))))
          (js/Promise.resolve nil))
      (let [control (control-round! sampling (arms/control-arms))
            {mount-rows :rows probes :probes}
            (mount-round-of-segments! r sampling arms-of)]
        (-> (bulk-round-of-segments! r :broad sampling)
            (.then (fn [broad]
                     (-> (bulk-round-of-segments! r :narrow sampling)
                         (.then (fn [narrow]
                                  {:round       r
                                   :parity      par
                                   :probes      probes
                                   :control     control
                                   :collector   @h/gc-available?
                                   :mount       mount-rows
                                   :bulk-broad  broad
                                   :bulk-narrow narrow}))))))))))

;; ---------------------------------------------------------------------------
;; Aggregation — ranges, red-zones, and the verdict
;; ---------------------------------------------------------------------------

(defn- reposition
  "Make one round's order samples orderable against another round's.

  Every round runs in its OWN page, so its positions restart at zero.
  Offsetting by the round index keeps the within-round order intact and
  puts the rounds in sequence, which is what lets the guard's `:phase`
  factor ask whether an arm reads differently early in the SESSION than
  late in it."
  [r samples]
  (mapv #(update % :position + (* 1000000 (inc r))) samples))

(defn- collect-segment
  "Fold one segment's per-round slices into the shape `frontier-row`
  consumes."
  [round-records path seg]
  (reduce (fn [acc {:keys [round] :as rec}]
            (let [s (get-in rec (conj path seg))]
              (-> acc
                  (update :p50 conj (:p50 s))
                  (update :ratio conj (:ratio s))
                  (update :order into (reposition round (:order s)))
                  (update :bad + (:bad s))
                  (update :total + (:total s))
                  (assoc :schedule (:schedule s))
                  (assoc :legs (conj (:legs acc) (:legs s))))))
          {:p50 [] :ratio [] :order [] :legs [] :bad 0 :total 0}
          round-records))

(defn- frontier-row
  "Turn one witness family's two segments into the published record —
  including the RED-ZONE figure, which is UIx's floor-normalised ratio
  over Reagent's, per round, as a range.

  `:red-zone-clock` is labelled here rather than in prose downstream
  because the delegated ruling on rf2-2rtt6.1 makes this number the
  threshold itself: a later candidate row worse than this on the clock is
  RED and needs an operator waiver naming the dogfood benefit."
  [witness-id doc rg ux]
  (let [rg-r    (mapv #(get % :reagent-subs) (:ratio rg))
        ux-r    (mapv #(get % :uix-subs) (:ratio ux))
        floor-r (mapv (fn [a b] (/ (:floor a) (:floor b))) (:p50 ux) (:p50 rg))]
    {:witness              witness-id
     :doc                  doc
     :arms                 [:floor :reagent-subs :uix-subs]
     :ratio-to-floor       {:reagent-subs (h/across-rounds (:ratio rg))
                            :uix-subs     (h/across-rounds (:ratio ux))}
     :per-round            {:reagent-subs {:p50 (:p50 rg) :ratio (:ratio rg)}
                            :uix-subs     {:p50 (:p50 ux) :ratio (:ratio ux)}}
     :legs                 {:reagent-subs (:legs rg) :uix-subs (:legs ux)}
     :red-zone-clock       (assoc (h/ratio-of-ratios ux-r rg-r)
                                  :axis :clock
                                  :numerator :uix-subs
                                  :denominator :reagent-subs
                                  :meaning
                                  (str "THE RED-ZONE THRESHOLD for " (name witness-id)
                                       " on the clock (delegated ruling, rf2-2rtt6.1). A "
                                       "later candidate row WORSE than this needs an "
                                       "explicit operator waiver naming the dogfood "
                                       "benefit. Ranges, not the mean: a range that "
                                       "straddles 1.0 means indistinguishable."))
     :segment-seam-control {:floor-uix-over-floor-reagent floor-r
                            :note
                            (str "the FLOOR's own p50 in the UIx segment over its p50 in "
                                 "the Reagent segment, same round. The floor is identical "
                                 "work in both and holds no re-frame state, so this is the "
                                 "seam's drift and nothing else. It is published rather "
                                 "than assumed to be 1.0.")}
     :schedule             {:reagent-subs (:schedule rg) :uix-subs (:schedule ux)}
     :order-verdict        {:reagent-subs (guard/verdict (:order rg)
                                                         {:tolerance order-tolerance})
                            :uix-subs     (guard/verdict (:order ux)
                                                         {:tolerance order-tolerance})}
     :verification         {:unverified (+ (:bad rg) (:bad ux))
                            :of         (+ (:total rg) (:total ux))}}))

(defn- refused?
  "Did the arm-order guard refuse either segment of `row`? A refusal is
  about what may be QUOTED, not about throwing the run away — the data
  stands as raw data and nothing in it may be published as measured. The
  driver turns this into exit 2, and the repair is to the ARM, never to
  the guard."
  [row]
  (boolean (some :refuse? (vals (:order-verdict row)))))

(defn aggregate
  "Fold the driver's per-round EDN into the published records."
  [round-edns]
  (let [recs     (mapv reader/read-string round-edns)
        controls (mapv :control recs)
        row      (fn [witness doc path]
                   (frontier-row witness doc
                                 (collect-segment recs path :reagent-subs)
                                 (collect-segment recs path :uix-subs)))
        mount    (into {}
                       (map (fn [{:keys [id doc]}]
                              [id (row id doc [:mount id])]))
                       arms/mount-witnesses)
        broad    (row :U-broad
                      (str "one write that all " fx/cells-n " subscribed cells read — "
                           "re-render THROUGHPUT, where fine grain buys nothing because "
                           "all the work is genuinely required")
                      [:bulk-broad])
        narrow   (row :U-narrow
                      (str "one write exactly ONE of " fx/cells-n " subscribed cells "
                           "reads — LOCALISATION, where fine grain either shows up or "
                           "does not")
                      [:bulk-narrow])
        all      (into mount {:bulk-broad broad :bulk-narrow narrow})]
    {:rounds    (count recs)
     :parity    (mapv :parity recs)
     :probes    (mapv :probes recs)
     :collector {:forced-between-samples? (every? true? (map :collector recs))
                 :note
                 (str "window.gc() between samples, never inside a window, and one "
                      "ROUND PER PAGE so no round inherits another's heap. false here "
                      "means the page was launched without --js-flags=--expose-gc and "
                      "this is a DIFFERENT instrument.")}
     :control   {:predicted arms/control-predicted
                 :per-round controls
                 :measured  {:mean (/ (reduce + 0.0 controls) (count controls))
                             :min  (apply min controls)
                             :max  (apply max controls)}
                 :note      (str "control-2x / control-1x, predicted from element "
                                 "arithmetic: w1-elements(2n)/w1-elements(n) = "
                                 (fx/w1-elements (* 2 fx/w1-rows)) "/"
                                 (fx/w1-elements fx/w1-rows)
                                 ". Both arms are FLOOR arms, so the control prices the "
                                 "instrument and not a candidate.")}
     :mount     mount
     :bulk      {:broad broad :narrow narrow}
     :refused   (into [] (comp (filter (fn [[_ v]] (refused? v))) (map key)) all)}))

;; ---------------------------------------------------------------------------
;; Adjudication — the clock row's own gates, answered where the fold is
;; ---------------------------------------------------------------------------

(defn- published-rows
  "Every row the fold publishes, keyed as the driver names them."
  [agg]
  (merge (:mount agg)
         {:bulk-broad (get-in agg [:bulk :broad])
          :bulk-narrow (get-in agg [:bulk :narrow])}))

(defn- verification-totals
  "Every published clock row's DOM read-back tally, summed — the
  `N unverified of M` the whole row stands or falls on.

  ## The positive control is NOT in this denominator, and that is the point

  `control-round!` mounts two arms that build DIFFERENT pages on purpose,
  so it hands `mount-round!` `nil` as the expectation and there is nothing
  to read back. An unverifiable window contributes to neither the
  numerator nor the denominator (see `p0-harness/mount-sample!`): counting
  it as verified would let the control dilute a real failure, and counting
  it as unverified would red every run for ever. The control is
  adjudicated by its own gate instead — [[lane/control-verdict]], below —
  and the driver reports it beside this figure rather than folding one
  into the other."
  [agg]
  (let [rows (published-rows agg)]
    {:unverified (reduce + 0 (map #(get-in % [:verification :unverified] 0) (vals rows)))
     :of         (reduce + 0 (map #(get-in % [:verification :of] 0) (vals rows)))
     :per-row    (into (sorted-map)
                       (map (fn [[id r]] [id (:verification r)]))
                       rows)}))

(defn adjudicate
  "Fold the per-round EDN into the published record AND answer the gates
  the driver has to exit on. **The only door onto the fold**, deliberately:
  a caller that could take the record without the verdicts is the fail-open
  this replaced (rf2-95s5b) — the clock row's `N unverified of M` was
  printed inside the record and nothing read it, and the positive control
  was published as a bare ratio nothing adjudicated.

  The adjudication is the LANE's, not a second copy: [[lane/control-verdict]]
  is what the freehand P0 arms already publish their controls under, and
  what it DECIDES is not this namespace's to change (rf2-egdaq, which is
  open, is the ruling on whether that decision tightens). Read its
  docstring before quoting `:ok?`: the rule is range OVERLAP, not
  every-round-inside.

  Answers a flat JS object because a driver reading `verify.ok?` off a
  `clj->js` map sees `undefined` — every gate green while every gate was
  unread. That happened on the predecessor's first run."
  [round-edns slack]
  (let [agg (aggregate (vec round-edns))
        vt  (verification-totals agg)
        ctl (lane/control-verdict (get-in agg [:control :predicted])
                                  (select-keys (get-in agg [:control :measured])
                                               [:min :max :mean])
                                  slack)
        rec (-> agg
                (assoc :verification vt)
                (assoc-in [:control :slack] slack)
                (assoc-in [:control :verdict] ctl))]
    #js {:edn        (pr-str rec)
         :unverified (:unverified vt)
         :of         (:of vt)
         :perRow     (pr-str (:per-row vt))
         :controlOk  (boolean (:ok? ctl))
         :controlWhy (:why ctl)}))

;; ---------------------------------------------------------------------------

(defn ^:export -main
  []
  (try
    (h/leave-act-environment!)
    ;; The arm-order guard's own self-test, BEFORE anything is measured.
    ;; Its checks are recorded fixtures replayed from the live study — the
    ;; 2.0125x, the 0.43% slope, the twelve-window warm-up sweep, the
    ;; rotation arithmetic — so this is deterministic. A broken guard makes
    ;; every figure below unpublishable, and finding that out after the run
    ;; is wasteful. THE GUARD REFUSES AND THE DRIVER EXITS 2; the repair is
    ;; to the arm.
    (let [st (guard/self-test)]
      (set! (.-P0_GUARD_SELF_TEST js/window) (pr-str st))
      (doseq [c (:checks st)]
        (js/console.log (str ";; P0 order-guard " (if (:ok c) "ok  " "FAIL") " " (:name c)
                             (when (:detail c) (str "  — " (:detail c))))))
      (when-not (:ok? st)
        (fail! "the arm-order guard's self-test FAILED — nothing may be measured")))
    (case (mode)
      "heap"
      (do (heap/install!)
          (js/console.log ";; P0 heap instrument installed")
          (set! (.-P0_READY js/window) true))

      "aggregate"
      (do (set! (.-P0A js/window)
                ;; ONE door, and it hands back the verdicts with the record.
                ;; A bare `aggregate` beside it would be a way to take the
                ;; figures without the gates, which is the defect this
                ;; namespace was carrying (rf2-95s5b).
                #js {:adjudicate (fn [edns slack] (adjudicate edns slack))})
          (js/console.log ";; P0 aggregator installed")
          (set! (.-P0_READY js/window) true))

      (-> (run-round! (q "round" 0))
          (.then (fn [rec]
                   (when rec (set! (.-P0_ROUND js/window) (pr-str rec)))
                   (set! (.-P0_DONE js/window) true)
                   (js/console.log (str ";; P0 ROUND " (q "round" 0) " DONE"))
                   nil))
          (.catch (fn [e]
                    (fail! (str "round rejected: " e))
                    (set! (.-P0_DONE js/window) true)))))
    (catch :default e
      (fail! (str "p0 run threw: " e))
      (set! (.-P0_DONE js/window) true)
      (set! (.-P0_READY js/window) true))))
