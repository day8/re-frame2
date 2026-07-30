(ns re-frame.bench.p0-app
  "EP-0038 P0 — the `:advanced` browser entry. One bundle, two modes.

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

  `?mode=clock` runs the mount and bulk rows to completion in the page and
  parks the records on `window.P0_RESULTS`.

  `?mode=heap` installs the retention instrument on `window.P0H` and then
  does nothing at all. Heap readings belong to the DRIVER, which owns the
  collector: the page cannot force a garbage collection, and a page that
  decided when a heap reading was taken would be reading whatever the
  collector happened to have done.

  Built and driven by `p0_run.cjs` beside this file.

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4."
  (:require [re-frame.bench.order-guard :as guard]
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
  (or (.get (js/URLSearchParams. (.-search js/location)) "mode") "clock"))

(defn- fail! [why]
  (set! (.-P0_ERROR js/window) (str why))
  (js/console.error (str ";; P0 FAILED — " why)))

(defn- record! [k v]
  (let [acc (or (.-P0_RESULTS js/window) #js {})]
    (aset acc (name k) (pr-str v))
    (set! (.-P0_RESULTS js/window) acc)
    v))

(defn- segment-order
  "Segment order for round `r`: forward on even rounds, REVERSED on odd.

  Two segments admit exactly two orders, and running both is what makes
  the cross-segment figure a both-orders result rather than a single-order
  one. A single-order result has not been checked and may not be
  reported — that is the arm-order guard's rule, applied at the seam it
  cannot see."
  [r]
  (if (even? r) arms/segments (vec (rseq (vec arms/segments)))))

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

;; ---------------------------------------------------------------------------
;; The mount row
;; ---------------------------------------------------------------------------

(defn- run-mount-rows!
  "Every mount witness, every segment, `rounds` rounds, segment order
  alternating with the round. Answers
  `{witness-id {segment-id {:per-round-ratio [...] :p50 [...] :bad :total}}}`."
  [rounds sampling warmups]
  (let [acc (atom {})
        pos (atom 0)]
    (dotimes [r rounds]
      (doseq [{:keys [id] :as segment} (segment-order r)]
        (arms/enter-segment! segment)
        (doseq [{:keys [elements arms-for] :as w} arms/mount-witnesses]
          (let [as (arms-for id)]
            (h/warm! as warmups)
            (let [{:keys [readings order bad total position]}
                  (h/mount-round! as sampling elements @pos)
                  norm (h/normalise readings :floor)]
              (reset! pos position)
              (swap! acc update-in [(:id w) id]
                     (fn [m]
                       (-> (or m {:p50 [] :ratio [] :order [] :bad 0 :total 0})
                           (update :p50 conj (:p50 norm))
                           (update :ratio conj (:ratio norm))
                           (update :order into order)
                           (update :bad + bad)
                           (update :total + total)))))))))
    @acc))

;; ---------------------------------------------------------------------------
;; The positive control
;; ---------------------------------------------------------------------------

(defn- run-control!
  "The clock's positive control, run in the same page and the same rounds
  as the arms, reported as PREDICTED against MEASURED.

  `control-2x` is the floor's W1 page with twice the rows and nothing else
  changed, so its ratio to `control-1x` is fixed by element arithmetic at
  2403/1203 = 1.997 before anything is measured. A run whose control
  misses has not earned the right to publish the arms beside it."
  [rounds sampling warmups]
  (let [as  (arms/control-arms)
        pos (atom 100000)
        rs  (atom [])]
    (h/warm! as warmups)
    (dotimes [_ rounds]
      (let [{:keys [readings position]} (h/mount-round! as sampling nil @pos)
            norm (h/normalise readings :control-1x)]
        (reset! pos position)
        (swap! rs conj (get-in norm [:ratio :control-2x]))))
    ;; `mount-round!` was handed `nil` as the expectation, so its own
    ;; verification counter is meaningless here and is not reported: the
    ;; two control arms build DIFFERENT pages by construction, which is
    ;; the entire point of the control and the one place the element gate
    ;; cannot apply.
    (let [vs @rs]
      {:predicted    arms/control-predicted
       :measured     {:mean (/ (reduce + 0.0 vs) (count vs))
                      :min  (apply min vs)
                      :max  (apply max vs)}
       :per-round    vs
       :rounds       rounds
       :note         (str "control-2x / control-1x. Predicted from element arithmetic: "
                          "w1-elements(2n)/w1-elements(n) = "
                          (fx/w1-elements (* 2 fx/w1-rows)) "/"
                          (fx/w1-elements fx/w1-rows)
                          ". Both arms are FLOOR arms — no substrate, no boundary, no "
                          "subscription — so the control prices the instrument and not "
                          "a candidate.")})))

;; ---------------------------------------------------------------------------
;; The bulk rows
;; ---------------------------------------------------------------------------

(defn- bulk-segment!
  "One segment of one bulk round, for one `kind`. Answers a promise of
  `{:p50 … :ratio … :legs … :order … :bad … :total …}`."
  [segment kind sampling warmups pos]
  (arms/enter-segment! segment)
  (let [mounts (arms/mount-bulk-arms! (arms/bulk-arms (:id segment)))]
    (-> (arms/warm-bulk! mounts kind warmups)
        (.then (fn [_] (arms/bulk-round! mounts kind sampling @pos)))
        (.then (fn [{:keys [readings legs order bad total position]}]
                 (reset! pos position)
                 (arms/release-bulk-arms! mounts)
                 (let [norm (h/normalise readings :floor)]
                   {:p50   (:p50 norm)
                    :ratio (:ratio norm)
                    :legs  (arms/leg-summary legs)
                    :order order
                    :bad   bad
                    :total total})))
        (.catch (fn [e]
                  (arms/release-bulk-arms! mounts)
                  (throw e))))))

(defn- run-bulk-row!
  "`rounds` rounds of one bulk `kind`, segment order alternating with the
  round. Answers a promise of `{segment-id {:p50 [...] :ratio [...] …}}`."
  [kind rounds sampling warmups]
  (let [acc (atom {})
        pos (atom 200000)]
    (-> (arms/chain nil
                    (for [r (range rounds) s (segment-order r)] s)
                    (fn [_ segment]
                      (-> (bulk-segment! segment kind sampling warmups pos)
                          (.then (fn [res]
                                   (swap! acc update (:id segment)
                                          (fn [m]
                                            (-> (or m {:p50 [] :ratio [] :legs []
                                                       :order [] :bad 0 :total 0})
                                                (update :p50 conj (:p50 res))
                                                (update :ratio conj (:ratio res))
                                                (update :legs conj (:legs res))
                                                (update :order into (:order res))
                                                (update :bad + (:bad res))
                                                (update :total + (:total res)))))
                                   nil)))))
        (.then (fn [_] @acc)))))

;; ---------------------------------------------------------------------------
;; Assembling a published row
;; ---------------------------------------------------------------------------

(defn- frontier-row
  "Turn one witness family's two segments into the published record —
  including the RED-ZONE figure, which is UIx's floor-normalised ratio
  over Reagent's, per round, as a range.

  `:red-zone-clock` is labelled here rather than in prose downstream
  because the delegated ruling on rf2-2rtt6.1 makes this number the
  threshold itself: a later candidate row worse than this on the clock is
  RED and needs an operator waiver naming the dogfood benefit."
  [witness-id doc by-segment]
  (let [rg      (get by-segment :reagent-subs)
        ux      (get by-segment :uix-subs)
        rg-r    (mapv #(get % :reagent-subs) (:ratio rg))
        ux-r    (mapv #(get % :uix-subs) (:ratio ux))
        floor-r (mapv (fn [a b] (/ (:floor a) (:floor b))) (:p50 ux) (:p50 rg))]
    {:witness              witness-id
     :doc                  doc
     :arms                 [:floor :reagent-subs :uix-subs]
     :ratio-to-floor       {:reagent-subs (h/across-rounds (:ratio rg))
                            :uix-subs     (h/across-rounds (:ratio ux))}
     :per-round            {:reagent-subs {:p50 (:p50 rg) :ratio (:ratio rg)}
                            :uix-subs     {:p50 (:p50 ux) :ratio (:ratio ux)}}
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
     :order-verdict        {:reagent-subs (guard/verdict (:order rg)
                                                         {:tolerance order-tolerance})
                            :uix-subs     (guard/verdict (:order ux)
                                                         {:tolerance order-tolerance})}
     :verification         {:unverified (+ (:bad rg) (:bad ux))
                            :of         (+ (:total rg) (:total ux))}}))

(defn- refused?
  "Did the arm-order guard refuse any segment of `row`? A refusal is about
  what may be QUOTED, not about throwing the run away — the data stands as
  raw data and nothing in it may be published as measured. The driver
  turns this into exit 2, and the repair is to the ARM, never to the
  guard."
  [row]
  (boolean (some :refuse? (vals (:order-verdict row)))))

;; ---------------------------------------------------------------------------
;; The clock run
;; ---------------------------------------------------------------------------

(defn- run-clock!
  []
  (let [rounds   (q "rounds" 6)
        sampling {:samples (q "samples" 12)}
        warmups  (q "warmups" 3)
        parities (reduce (fn [acc {:keys [id] :as segment}]
                           (arms/enter-segment! segment)
                           (assoc acc id (parity-of-segment! id)))
                         {}
                         arms/segments)
        problems (into []
                       (for [[seg ws] parities
                             [w {:keys [problems]}] ws
                             p problems]
                         (assoc p :segment seg :witness w)))
        ;; Cross-SEGMENT parity: the floor is in both segments and is the
        ;; reference in both, so if each segment agrees internally and the
        ;; floors agree with each other, all four arms built one page.
        cross    (into []
                       (for [{:keys [id]} arms/mount-witnesses
                             :let [a (get-in parities [:reagent-subs id :canon :floor])
                                   b (get-in parities [:uix-subs id :canon :floor])]
                             :when (not= a b)]
                         {:problem :cross-segment-floor-disagreement :witness id}))]
    (record! :parity {:problems (into problems cross)
                      :ok?      (and (empty? problems) (empty? cross))
                      :counts   (into {} (for [[seg ws] parities]
                                           [seg (into {} (for [[w v] ws]
                                                           [w (:counts v)]))]))})
    (if (or (seq problems) (seq cross))
      (do (fail! (str "the arms do not build the same page under :advanced — "
                      (pr-str (into problems cross))))
          (js/Promise.resolve nil))
      (let [refusals (atom [])
            keep!    (fn [k row]
                       (when (refused? row) (swap! refusals conj k))
                       (record! k row)
                       row)]
        (record! :control (run-control! rounds sampling warmups))
        (let [mount-rows (run-mount-rows! rounds sampling warmups)]
          (record! :mount
                   (into {}
                         (map (fn [{:keys [id doc]}]
                                (let [row (frontier-row id doc (get mount-rows id))]
                                  (when (refused? row) (swap! refusals conj id))
                                  [id row])))
                         arms/mount-witnesses))
          (-> (run-bulk-row! :broad rounds sampling warmups)
              (.then (fn [by-seg]
                       (keep! :bulk-broad
                              (frontier-row :U-broad
                                            (str "one write that all " fx/cells-n
                                                 " subscribed cells read — re-render "
                                                 "THROUGHPUT, where fine grain buys "
                                                 "nothing because all the work is "
                                                 "genuinely required")
                                            by-seg))
                       (run-bulk-row! :narrow rounds sampling warmups)))
              (.then (fn [by-seg]
                       (keep! :bulk-narrow
                              (frontier-row :U-narrow
                                            (str "one write exactly ONE of " fx/cells-n
                                                 " subscribed cells reads — "
                                                 "LOCALISATION, where fine grain either "
                                                 "shows up or does not")
                                            by-seg))
                       (record! :refused (vec @refusals))
                       (set! (.-P0_REFUSED js/window) (clj->js (mapv name @refusals)))
                       nil)))))))))

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
    ;; is wasteful. THE GUARD EXITS ON REFUSAL; the repair is to the arm.
    (let [st (guard/self-test)]
      (record! :order-guard-self-test st)
      (doseq [c (:checks st)]
        (js/console.log (str ";; P0 order-guard " (if (:ok c) "ok  " "FAIL") " " (:name c)
                             (when (:detail c) (str "  — " (:detail c))))))
      (when-not (:ok? st)
        (fail! "the arm-order guard's self-test FAILED — nothing may be measured")))
    (case (mode)
      "heap" (do (heap/install!)
                 (js/console.log ";; P0 heap instrument installed")
                 (set! (.-P0_READY js/window) true))
      (-> (run-clock!)
          (.then (fn [_]
                   (set! (.-P0_DONE js/window) true)
                   (js/console.log ";; P0 CLOCK DONE")
                   nil))
          (.catch (fn [e]
                    (fail! (str "clock run rejected: " e))
                    (set! (.-P0_DONE js/window) true)))))
    (catch :default e
      (fail! (str "p0 run threw: " e))
      (set! (.-P0_DONE js/window) true)
      (set! (.-P0_READY js/window) true))))
