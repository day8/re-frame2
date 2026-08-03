(ns re-frame.bench.calibration
  "rf2-l3jv4 — the SMI/DBL control's verdict, made to FAIL CLOSED.

  Both allocation harnesses in this directory carry the same pair of
  `.slice()` controls: a PACKED_DOUBLE_ELEMENTS array whose copy has an
  asserted layout, and a PACKED_SMI_ELEMENTS array whose copy has not.
  Together they answer one question — is this instrument measuring a
  tagged-slot copy at all? — and the answer is what licenses every absolute
  byte figure the run goes on to print.

  ## Why the answer had to grow teeth

  The pair already printed its own disagreement. When the SMI/DBL ratio sat
  outside both bands the harness printed

      *** NEITHER — the SMI arm is not measuring a tagged-slot copy ***

  and then carried on, exited 0, and printed `VERDICT: reportable` from the
  arm-order guard beside it. That is the exact recurrence this owner exists
  to catch: `arm-ctl` was once ONE function body closed over both kinds of
  template, so the harness had ONE `.slice()` call site that saw both
  elements kinds, and at that polymorphic site the SMI receiver lost
  `.slice()`'s clone fast path and allocated its elements store TWICE — the
  arm read 16.11 B/slot against a tagged slot's 8. Splitting the site per
  elements kind returned it to 8.0. Nothing stops a future edit sharing that
  site again, and until this namespace existed nothing would have stopped the
  run REPORTING afterwards.

  So the evidence the harnesses already computed is turned into a boolean
  here, and that boolean joins the arm-order refusal in deciding the exit
  code. A run whose control says it is not measuring its stated control is
  not reportable, by the same rule that an order-contaminated run is not.

  ## What is checked, and what deliberately is NOT

  Three things, all of them measurement-against-measurement:

    1. **Every SMI/DBL ratio names a regime.** At the same D the two copies
       have identical layout with pointer compression OFF (ratio 1.00) and
       every slot and both headers halve with it ON (ratio ~0.50). A ratio in
       NEITHER band means the SMI arm is not copying tagged slots — refuse.
       Nothing is asserted about a header anywhere; this is one measurement
       divided by another from the same window of the same process.

    2. **The sizes agree with each other.** One D answering ON while another
       answers OFF is a single instrument giving two answers about one
       machine. Refuse — regardless of how confidently either lands in its
       band.

    3. **The SMI slope sits at the exact width of the regime the ratios
       selected.** The regime is READ off this pair, so predicting the slope
       from an assumed width would be circular; but once the RATIOS have
       named the regime, its width is 8 bytes or 4 and the slope may be
       checked against it non-circularly. A tagged slot is never 16.1.

  NOT checked: the DBL LARGE pair's documented ~+9% deviation. It is V8's
  page-tail filler on an 87 KB object, it is understood, and every arm these
  harnesses measure allocates small objects — gating it would refuse healthy
  runs for a known large-object effect. It stays printed and open, exactly as
  the harness docstrings say.

  ## Pure, so the failure path is adjudicated rather than hoped for

  Everything below takes numbers and returns a map. [[self-test]] injects
  ratios — including the recorded 16.11 — and asserts the verdict each one
  earns, so the refusal branch is exercised on every run of the harness and
  on every run of `re-frame.bench.calibration-cljs-test`, which is what makes
  it a checked behaviour rather than an unvisited branch.

  `.cljc` because `re-frame.bench.read-attribution` is JVM Clojure and the
  other two harnesses are ClojureScript; unlike
  `re-frame.bench.order-guard`'s rule this one has a single expression, since
  nothing outside `implementation/core` needs it."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The bands.

(def ^:const off-lo
  "Below this the SMI/DBL ratio is not compression-OFF." 0.95)
(def ^:const off-hi
  "Above this the SMI/DBL ratio is not compression-OFF." 1.05)
(def ^:const on-lo
  "Below this the SMI/DBL ratio is not compression-ON." 0.45)
(def ^:const on-hi
  "Above this the SMI/DBL ratio is not compression-ON." 0.55)

(def ^:const slope-tolerance
  "How far the SMI slope may sit from the exact slot width of the regime the
  RATIOS selected, as a fraction. Deliberately loose: the widths themselves
  are 8 and 4, a full 100%/50% apart, and the fault this exists to catch read
  2x. The tightest legitimate deviation on record on this surface is the DBL
  small pair's +0.5%, so 25% admits every honest reading of either width
  while leaving no room for a doubled one."
  0.25)

(defn- abs* [x] #?(:clj (Math/abs (double x)) :cljs (js/Math.abs x)))

(defn- finite? [x]
  (and (number? x)
       #?(:clj  (and (not (Double/isNaN (double x))) (not (Double/isInfinite (double x))))
          :cljs (js/isFinite x))))

(defn regime-of
  "The regime one SMI/DBL ratio names: `:off` (8 B/slot), `:on` (4 B/slot) or
  `:neither`. A non-finite ratio — a zero DBL reading divides to infinity —
  is `:neither`, which is the honest answer and not an excuse."
  [ratio]
  (cond
    (not (finite? ratio))            :neither
    (< off-lo ratio off-hi)          :off
    (< on-lo ratio on-hi)            :on
    :else                            :neither))

(def ^:private width-of {:off 8.0 :on 4.0})

(defn verdict
  "Adjudicate the SMI/DBL control pair.

  `pairs` is a seq of `{:d D :smi B :dbl B}` — one entry per control size,
  each carrying the measured bytes per copy of the two arms at that D.
  `slope` is the measured SMI slope in B/slot across the small pair.

  Answers

      {:refuse?  true/false
       :regime   :off | :on | :neither
       :pairs    [{:d D :smi B :dbl B :ratio R :regime :off|:on|:neither} ...]
       :slope    S
       :width    8.0 | 4.0 | nil     ; the width the RATIOS selected
       :off-by   fraction | nil      ; how far the slope sits from it
       :why      one line}

  Refusal is the disjunction of the three checks in the namespace docstring.
  When the ratios cannot name a regime the slope check has no width to run
  against and is skipped — the run is already refused."
  [pairs slope]
  (let [rows    (mapv (fn [{:keys [d smi dbl]}]
                        (let [r (if (and (finite? smi) (finite? dbl) (not (zero? dbl)))
                                  (/ smi dbl)
                                  #?(:clj Double/NaN :cljs js/NaN))]
                          {:d d :smi smi :dbl dbl :ratio r :regime (regime-of r)}))
                      pairs)
        named   (into #{} (map :regime) rows)
        bad     (filterv #(= :neither (:regime %)) rows)
        regime  (cond
                  (empty? rows)          :neither
                  (seq bad)              :neither
                  (= 1 (count named))    (first named)
                  :else                  :neither)
        width   (width-of regime)
        off-by  (when (and width (finite? slope))
                  (/ (- slope width) width))
        slope-bad? (or (and width (not (finite? slope)))
                       (and off-by (> (abs* off-by) slope-tolerance)))
        why     (cond
                  (empty? rows)
                  "no control pair was measured — the instrument has no calibration at all"

                  (seq bad)
                  (str "SMI/DBL ratio names NEITHER regime at D="
                       (str/join "," (map :d bad))
                       " — the SMI arm is not measuring a tagged-slot copy")

                  (< 1 (count named))
                  (str "the sizes disagree: "
                       (str/join ", " (map #(str "D=" (:d %) " says " (name (:regime %))) rows))
                       " — one instrument, two answers about one machine")

                  slope-bad?
                  (str "the ratios read pointer compression "
                       (str/upper-case (name regime))
                       " (" (long width) " B/slot) but the slope is " slope
                       " B/slot — a tagged slot is 8 bytes or 4, never that")

                  :else
                  (str "SMI/DBL ratio names pointer compression "
                       (str/upper-case (name regime))
                       " at every size, and the slope sits at that width"))]
    {:refuse? (boolean (or (= :neither regime) slope-bad?))
     :regime  regime
     :pairs   rows
     :slope   slope
     :width   width
     :off-by  off-by
     :why     why}))

(defn report-lines
  "The refusal, as harness-comment lines. Empty when the control is sound —
  the harnesses print the pair's own numbers either way, and this speaks only
  when they must not be quoted."
  [v]
  (when (:refuse? v)
    [";;"
     ";; ==== CONTROL CALIBRATION: THESE FIGURES ARE NOT REPORTABLE ===="
     (str ";;   " (:why v))
     ";;   every absolute byte figure above rests on the SMI/DBL pair agreeing"
     ";;   about what a tagged slot costs on this build (rf2-l3jv4). It does not."
     ";;   The table stands as raw data; nothing in it may be quoted."]))

;; ---------------------------------------------------------------------------
;; The self-test — injected ratios, so the REFUSAL branch is adjudicated on
;; every run rather than only ever reached by a broken machine.

(defn- pair [d smi dbl] {:d d :smi smi :dbl dbl})

(defn self-test []
  (let [;; 1. THE RECORDED FAULT, replayed from this bead's own measurement:
        ;;    the polymorphic `.slice()` site, SMI D=100 at 1681.7 B against
        ;;    the DBL D=100's 848, slope 16.1146 B/slot. This is the run that
        ;;    exited 0 and printed `VERDICT: reportable`.
        broken   (verdict [(pair 100 1681.7 848.0) (pair 200 3293.5 1648.0)] 16.1146)

        ;; 2. THE SAME HARNESS AFTER THE FIX, from the numbers the docstring
        ;;    of `write-attribution` quotes. It must pass, or the guard is
        ;;    useless.
        healthy  (verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] 8.0027)

        ;; 3. A CHROME-LIKE BUILD. Pointer compression ON halves every slot
        ;;    and both headers, so the ratio is ~0.5 and the slope ~4. The
        ;;    harnesses are run on node, but a guard that refused a
        ;;    compressed build would be asserting the regime it claims to
        ;;    read.
        compressed (verdict [(pair 100 448.0 848.0) (pair 200 848.0 1648.0)] 4.0)

        ;; 4. THE SIZES DISAGREEING. Each ratio lands cleanly in a band and
        ;;    they are DIFFERENT bands — no single reading is suspicious, and
        ;;    the pair is still nonsense.
        split    (verdict [(pair 100 849.1 848.0) (pair 200 824.0 1648.0)] 8.0)

        ;; 5. A SOUND RATIO WITH A BROKEN SLOPE. The absolutes agree at both
        ;;    sizes, so check 1 passes; only the size-to-size STEP is wrong,
        ;;    which is what a constant added to both copies looks like.
        slope-bad (verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] 12.5)

        ;; 6. A DEAD DBL ARM. Dividing by zero must refuse, not produce an
        ;;    infinity that slips through a range test.
        zero-dbl (verdict [(pair 100 849.1 0.0)] 8.0)

        ;; 7. NO CONTROL AT ALL. A plan that dropped its control arms has no
        ;;    calibration, and "nothing to check" is not "checked".
        empty-v  (verdict [] 8.0)

        ;; 8. THE DOCUMENTED DBL DEVIATION IS NOT GATED. Both DBL arms read
        ;;    9% over their own asserted layout — the page-tail effect — and
        ;;    the SMI arms track them. Only the RATIO is adjudicated here, so
        ;;    this must pass: turning a known large-object effect into a
        ;;    refusal was ruled out by name.
        ;;    Its own implied slope is 8.72 — the same +9% — and the 25%
        ;;    tolerance must admit it.
        tail     (verdict [(pair 100 924.3 924.3) (pair 200 1796.3 1796.3)] 8.72)

        checks
        [{:name "the recorded 16.11 B/slot polymorphic-site run is REFUSED"
          :ok   (and (:refuse? broken)
                     (= :neither (:regime broken))
                     (every? #(= :neither (:regime %)) (:pairs broken)))
          :detail (:why broken)}
         {:name "the same harness after the fix is reportable"
          :ok   (and (not (:refuse? healthy))
                     (= :off (:regime healthy))
                     (< (abs* (:off-by healthy)) 0.01))
          :detail (:why healthy)}
         {:name "a pointer-COMPRESSED build reads 4 B/slot and is reportable"
          :ok   (and (not (:refuse? compressed))
                     (= :on (:regime compressed)))
          :detail (:why compressed)}
         {:name "two sizes naming DIFFERENT regimes is refused, though each lands in a band"
          :ok   (and (:refuse? split)
                     (= :neither (:regime split))
                     (= [:off :on] (mapv :regime (:pairs split))))
          :detail (:why split)}
         {:name "sound ratios with a slope off the selected width are refused"
          :ok   (and (:refuse? slope-bad)
                     (= :off (:regime slope-bad)))
          :detail (:why slope-bad)}
         {:name "a zero DBL reading refuses instead of dividing to infinity"
          :ok   (and (:refuse? zero-dbl) (= :neither (:regime zero-dbl)))
          :detail (:why zero-dbl)}
         {:name "a plan with no control arms is refused, not silently excused"
          :ok   (and (:refuse? empty-v) (= :neither (:regime empty-v)))
          :detail (:why empty-v)}
         {:name "a DBL arm 9% over its own layout prediction does NOT refuse — only the ratio is adjudicated"
          :ok   (and (not (:refuse? tail)) (= :off (:regime tail)))
          :detail (:why tail)}]]
    {:ok?    (every? :ok checks)
     :checks checks}))

(defn print-self-test!
  "Run [[self-test]], print each check, and answer whether it passed. A
  harness that gets `false` must measure nothing."
  []
  (let [st (self-test)]
    (doseq [c (:checks st)]
      (println (str ";; calibration " (if (:ok c) "ok  " "FAIL") " " (:name c)
                    (when (:detail c) (str "  — " (:detail c))))))
    (:ok? st)))
