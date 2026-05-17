(ns day8.re-frame2-causa.chart.timing-waterfall
  "Wire-timing waterfall — pure SVG hiccup primitive for the
  managed-fx wire-boundary diff panel (rf2-uyp86).

  ## Why a separate ns

  The waterfall renders per-phase timing for one managed-fx interaction:
  one horizontal row per phase, bar width proportional to the phase's
  share of total elapsed time, ms-label to the right. Five surfaces use
  it (HTTP / WebSocket / machine-invoke / SSR / flow); promoting it to
  a shared chart primitive keeps the panel template lean and the
  rendering testable from `clojure -M:test`.

  ## Shape

  Input is a `wire-timing` map per
  `panels/managed-fx-helpers/http-wire-timing`:

      {:phases   [[:dns     2]
                  [:connect 15]
                  [:ssl     22]
                  [:request 1]
                  [:ttfb    180]
                  [:download 30]]
       :total-ms 250
       :synthesised? false}

  Each phase is `[<phase-keyword> <duration-ms>]`. The renderer normalises
  bar widths against `:total-ms` (or against the sum of phase durations
  if `:total-ms` is absent / inconsistent).

  ## Reduced-motion

  Per spec/019 §1.1 the live arc animation flattens to a static
  bar + numeric percentage; the waterfall is already static (only the
  per-phase bar width carries meaning), so no extra reduced-motion
  handling is needed."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- visual constants --------------------------------------------------

(def ^:private row-height 16)
(def ^:private label-width 80)
(def ^:private value-width 60)
(def ^:private bar-padding 8)
(def ^:private font-stack
  "ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace")

;; ---- pure projection ---------------------------------------------------

(defn normalise-phases
  "Resolve the timing input to a vector of `{:phase :duration-ms :width-pct :offset-pct}`
  maps so the renderer is a pure positional walk.

  Pure fn — JVM-testable.

    - Drops phases with non-numeric / non-positive duration so a single
      bogus row doesn't break the layout.
    - `:width-pct` is `duration / total` ratio clamped to `[0, 1]`.
    - `:offset-pct` is the running sum of preceding widths — supports
      a layered (gantt-style) rendering where each phase starts after
      its predecessor ends. The default ASCII-spec style is a
      stack-of-bars (each row starts at 0); the panel can choose either
      mode by reading the same projection."
  [{:keys [phases total-ms]}]
  (let [clean    (filterv (fn [[_ ms]] (and (number? ms) (pos? ms))) (or phases []))
        sum      (reduce + 0 (map second clean))
        total    (or (when (and (number? total-ms) (pos? total-ms)) total-ms)
                     (when (pos? sum) sum)
                     0)]
    (if (or (zero? total) (empty? clean))
      []
      (loop [acc       []
             offset    0
             remaining clean]
        (if (empty? remaining)
          acc
          (let [[ph ms] (first remaining)
                width   (min 1.0 (max 0.0 (/ (double ms) (double total))))]
            (recur (conj acc {:phase      ph
                              :duration-ms ms
                              :width-pct  width
                              :offset-pct (min 1.0 (max 0.0 (/ (double offset) (double total))))})
                   (+ offset ms)
                   (rest remaining))))))))

(defn slowest-phase
  "Identify the phase that owns the largest share of total elapsed time.
  The panel marks this row with a `← slow` annotation. Pure fn."
  [{:keys [phases]}]
  (when (seq phases)
    (let [valid (filter (fn [[_ ms]] (and (number? ms) (pos? ms))) phases)]
      (when (seq valid)
        (first (apply max-key second valid))))))

;; ---- hiccup renderer ---------------------------------------------------

(defn- phase-label
  [{:keys [phase]}]
  (cond
    (keyword? phase) (name phase)
    (nil? phase)     "—"
    :else            (str phase)))

(defn- duration-label
  [{:keys [duration-ms]}]
  (cond
    (not (number? duration-ms)) "—"
    (< duration-ms 1000)        (str (long duration-ms) "ms")
    :else                       (str (Math/round (/ duration-ms 100.0)) "00ms")))

(defn- bar-fill
  [phase slowest?]
  (cond
    slowest?                  (:yellow tokens/tokens)
    (= phase :issued)         (:accent-violet tokens/tokens)
    (contains? #{:elapsed :ttfb :download :receive :compute}
               phase)         (:cyan tokens/tokens)
    :else                     (:cyan tokens/tokens)))

(defn render
  "Render a wire-timing map as a hiccup SVG waterfall.

  Options:

    :width           — viewport width in px (default 480)
    :slowest-marker? — when true, the longest phase gets a yellow fill
                       + `← slow` annotation (default true)
    :testid          — overrides the root SVG data-testid

  Returns nil when there are no phases to render so the panel can
  fall back to a `n/a` placeholder. Pure fn — JVM-runnable."
  ([wire] (render wire {}))
  ([wire {:keys [width slowest-marker? testid]
          :or   {width 480
                 slowest-marker? true
                 testid "rf-causa-waterfall"}}]
   (let [rows       (normalise-phases wire)
         slowest    (when slowest-marker? (slowest-phase wire))
         n-rows     (count rows)
         bar-area-w (max 40 (- width label-width value-width (* 2 bar-padding)))
         svg-h      (max row-height (* n-rows row-height))]
     (when (seq rows)
       [:svg {:data-testid testid
              :data-row-count (str n-rows)
              :width  width
              :height svg-h
              :viewBox (str "0 0 " width " " svg-h)
              :style {:display "block"
                      :font-family font-stack}}
        (into [:g {:data-testid "rf-causa-waterfall-rows"}]
              (for [[i row] (map-indexed vector rows)
                    :let [y         (* i row-height)
                          slow?     (= (:phase row) slowest)
                          bar-x     (+ label-width bar-padding)
                          bar-w-px  (max 1 (Math/round (* (:width-pct row) bar-area-w)))
                          fill      (bar-fill (:phase row) slow?)]]
                ^{:key (str (:phase row) "-" i)}
                [:g {:data-testid (str "rf-causa-waterfall-row-" (phase-label row))
                     :data-phase  (phase-label row)
                     :data-slow   (str slow?)}
                 ;; left label
                 [:text {:x 4
                         :y (+ y (- row-height 4))
                         :font-size 10
                         :fill (:text-secondary tokens/tokens)}
                  (phase-label row)]
                 ;; track background
                 [:rect {:x bar-x
                         :y (+ y 3)
                         :width bar-area-w
                         :height (- row-height 6)
                         :fill (:bg-3 tokens/tokens)
                         :rx 2}]
                 ;; bar
                 [:rect {:x bar-x
                         :y (+ y 3)
                         :width bar-w-px
                         :height (- row-height 6)
                         :fill fill
                         :rx 2}
                  [:title (str (phase-label row) " — " (duration-label row)
                               (when slow? " (slow)"))]]
                 ;; right value
                 [:text {:x (+ bar-x bar-area-w bar-padding)
                         :y (+ y (- row-height 4))
                         :font-size 10
                         :fill (if slow?
                                 (:yellow tokens/tokens)
                                 (:text-tertiary tokens/tokens))}
                  (str (duration-label row)
                       (when slow? " ← slow"))]]))]))))
