(ns re-frame.bench.order-guard
  "rf2-88pie / rf2-om73r — the ARM-ORDER guard, for the harnesses that cannot
  reach the JavaScript one.

  No reported figure may depend on WHERE IN THE PLAN it was measured. That
  rule already exists, in
  `implementation/freehand/test/re_frame/freehand/bench/order_guard.cjs`,
  where it guards `b8_run.cjs`, `b7_run.cjs` and the B6 harness.

  ## Why the rule is expressed twice, and what stops the two drifting

  The `.cjs` module is a CommonJS file loaded by a Node driver that owns a
  Chromium page. Neither of the harnesses this namespace serves can consume
  it:

    * `re-frame.bench.read-attribution` is JVM Clojure. It measures with
      `com.sun.management.ThreadMXBean/getThreadAllocatedBytes` and there is
      no JavaScript runtime in the process at all.
    * `re-frame.bench.write-attribution` is ClojureScript, but it lives in
      `implementation/core`, and core may not `:require` out of
      `implementation/freehand`. A test harness that reached across that
      boundary would put the dependency arrow the wrong way round.

  So the honest shape is a SHARED RULE EXPRESSED TWICE, and the thing that
  stops the two copies drifting apart is that [[self-test]] replays the same
  RECORDED FIXTURES as the `.cjs` `selfTest` — the same 2.0125x, the same
  0.43% slope, the same twelve-window warm-up sweep, the same rotation
  arithmetic. A copy that drifts fails its own self-test on numbers taken
  from the live study, not on a restatement of itself. Both self-tests run
  before their harness measures anything.

  ## The property

  Three remedies were available and the `.cjs` header argues them out in
  full; this is the summary.

  **Randomise the order** — rejected: randomising spreads a contaminant
  across every arm instead of concentrating it in one, turning a visible 2x
  on one arm into an invisible smear on all of them.

  **Order the plan so no arm follows a much larger one** — rejected: it
  needs each arm's per-call allocation before measuring it, which is the
  thing being measured, and it is unfalsifiable, since a plan ordered by
  that rule produces no evidence the rule worked.

  **Stratify and REFUSE** — chosen:

    1. [[slot-order]] builds a run order in which every arm has at least two
       DISTINCT immediate predecessors across rounds.
    2. Every sample carries what ran immediately before it AND its position
       in the run.
    3. [[verdict]] partitions each arm's samples by EACH of those factors
       and applies the house rule: OVERLAPPING RANGES MEAN
       INDISTINGUISHABLE. A stratification is a finding only if the ranges
       are disjoint AND the medians differ by more than a stated tolerance.
    4. An arm with one stratum on a factor is `:unchecked`, and `:unchecked`
       refuses as loudly as `:contaminated` — a single-order result has not
       been checked and may not be reported.

  ## Cyclic rotation does not vary adjacency, and that is the trap

  `b8_run.cjs`, `b7_run.cjs`, `b6-harness/round!` and `b6-rows` all chose
  the arm for slot `j` of round `r` as `ARMS[(j + r) % n]`, and all of them
  published that as \"arm order rotating with the round\". A cyclic rotation
  changes which arm goes FIRST; it does not change which arm follows which.
  Arm `a` sits at slot `(a - r) mod n`, so its predecessor is `(a - 1) mod n`
  in every round, and the only sample with a different predecessor is the one
  at the round seam — exactly one of the arm's `R`.

  [[slot-order]] reflects the rotation on odd rounds, which replaces every
  `a -> a+1` adjacency with `a -> a-1`. [[self-test]] proves both halves of
  that arithmetically.

  ## What the `:predecessor` factor can and cannot attribute (rf2-om73r)

  Under [[slot-order]] an arm's predecessor is `(a - 1) mod n` on EVEN
  rounds and `(a + 1) mod n` on ODD ones, so with only two orders available
  the predecessor is a function of the round's PARITY. The two strata are
  therefore \"even rounds\" and \"odd rounds\" wearing predecessor labels,
  and a `:predecessor` finding says *this figure moves with the plan* —
  which is the whole property, and reason enough to refuse — but it does NOT
  establish that the immediate predecessor is the mechanism. Anything else
  that alternates with the round direction lands in the same stratum.

  Separating the two would need a third distinct order per arm, and
  randomising instead is worse for the reasons above. It is stated here so
  that no report quotes a `:predecessor` stratum as evidence about adjacency
  specifically.

  ## Position dominates adjacency

  The `.cjs` module's live reproduction measured, in plain node with nothing
  varying but how many times the site had run, the same control over sixteen
  consecutive windows:

      42.32 | 10.26 10.26 10.26 10.33 10.28 | 8.12 8.12 ... 8.12

  and, with position held fixed, an immediate predecessor worth 0.0–0.3%. So
  a plan run FORWARD and then REVERSED moves position and adjacency together
  and cannot say which of them it caught. Both factors are checked here, and
  a harness that records no positions is not thereby excused the predecessor
  question."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The schedule

(defn slot-order
  "Slot order for `n` arms in `round`: rotate forward by `round`, then REFLECT
  on odd rounds. The same arithmetic as `order_guard.cjs`'s `schedule` and
  `b6-harness/slot-order`."
  [n round]
  (let [xs (mapv #(mod (+ % round) n) (range n))]
    (if (odd? round) (vec (rseq xs)) xs)))

(defn rotation-only
  "The plain cyclic rotation, kept so [[self-test]] can price it."
  [n round]
  (mapv #(mod (+ % round) n) (range n)))

(defn adjacency
  "Flatten `rounds` rounds of `(order-fn n round)` into the sequence that
  actually runs, and answer, per arm, the predecessor strata it produces.

  `:seams?` decides whether the round seam counts. It does when the rounds
  run back to back in one process — the last arm of round `r` really does
  precede the first of round `r+1` — and pricing a schedule's WITHIN-ROUND
  variety means turning it off."
  ([n rounds order-fn] (adjacency n rounds order-fn {}))
  ([n rounds order-fn {:keys [seams?] :or {seams? true}}]
   (let [slots (mapcat (fn [r] (map-indexed vector (order-fn n r))) (range rounds))
         seq*  (vec (map second slots))
         first? (vec (map (fn [[k _]] (zero? k)) slots))
         preds (reduce
                 (fn [acc i]
                   (let [a (nth seq* i)
                         round-first? (nth first? i)]
                     (if (and (not seams?) round-first?)
                       acc
                       (let [p (if (zero? i) nil (nth seq* (dec i)))]
                         (update-in acc [a p] (fnil inc 0))))))
                 {}
                 (range (count seq*)))]
     {:sequence seq*
      :arms     (into {}
                      (map (fn [[a m]]
                             (let [counts (vals m)
                                   total  (reduce + counts)]
                               [a {:distinct    (count m)
                                   :samples     total
                                   :modal-share (/ (double (apply max counts))
                                                   (double total))}])))
                      preds)})))

;; ---------------------------------------------------------------------------
;; The verdict

(defn median
  [xs]
  (let [s (vec (sort xs))
        c (count s)
        m (quot c 2)]
    (if (odd? c) (nth s m) (/ (+ (nth s (dec m)) (nth s m)) 2.0))))

(defn- summarise [stratum values]
  {:stratum stratum
   :n       (count values)
   :min     (apply min values)
   :max     (apply max values)
   :p50     (median values)})

(defn- finite? [x]
  (and (number? x)
       #?(:clj  (Double/isFinite (double x))
          :cljs (js/isFinite x))))

(defn stratify
  "Partition one arm's samples on one nuisance factor.

  `:predecessor` is the bead's factor. `:phase` is the one the live
  reproduction says is larger: THIRDS, not halves, and the middle is
  discarded. A warm-up step lands somewhere inside the run; a median split
  can put the step inside the EARLY stratum, which then straddles it, and a
  straddling stratum's range overlaps everything. First third against last
  third is the beginning-versus-end contrast without that trap."
  [samples factor]
  (let [grouped
        (if (= factor :phase)
          (let [with-pos (vec (sort-by :position (filter #(finite? (:position %)) samples)))
                c        (count with-pos)]
            (if (< c 2)
              nil
              (let [t (max 1 (quot c 3))]
                [["FIRST-THIRD" (mapv :value (subvec with-pos 0 t))]
                 ["LAST-THIRD"  (mapv :value (subvec with-pos (- c t)))]])))
          (->> samples
               (reduce (fn [acc s]
                         (let [k (if (nil? (:predecessor s)) "<none>" (str (:predecessor s)))]
                           (update acc k (fnil conj []) (:value s))))
                       {})
               (into [])))]
    (->> grouped
         (map (fn [[k vs]] (summarise k vs)))
         (sort-by :p50)
         vec)))

(defn adjudicate
  "Adjudicate one arm's strata on one factor.

  Strata of a single sample carry no range, so they are adjudicated on the
  ratio alone — and only when there is no better-powered pair available,
  because a powered comparison should decide the question when one exists.
  `rf2-jr76s`'s recorded fault is exactly the unpowered case (one forward
  reading, one reversed) and must still fire."
  [strata tolerance]
  (if (< (count strata) 2)
    {:strata strata
     :status :unchecked
     :why    (str "only " (count strata) " stratum — the question was never asked")}
    (let [powered    (filterv #(>= (:n %) 2) strata)
          use        (if (>= (count powered) 2) powered strata)
          lo         (first use)
          hi         (peek use)
          ;; An arm that allocates NOTHING reads zero in every stratum, and
          ;; `0/0` is not an infinite separation — it is no separation at
          ;; all. Left as `Infinity` this manufactures contamination out of
          ;; two strata that agree exactly, which is the one thing the house
          ;; rule exists to prevent (`read-attribution`'s CACHEGET and NOOP
          ;; arms are both identically zero and both were flagged).
          ratio      (cond
                       (== (:p50 hi) (:p50 lo)) 1.0
                       (zero? (:p50 lo))        #?(:clj Double/POSITIVE_INFINITY
                                                   :cljs js/Infinity)
                       :else (/ (double (:p50 hi)) (double (:p50 lo))))
          disjoint?  (> (:min hi) (:max lo))
          degenerate (or (< (:n lo) 2) (< (:n hi) 2))
          beyond?    (> (#?(:clj Math/abs :cljs js/Math.abs) (- ratio 1.0)) tolerance)
          hit?       (and beyond? (or disjoint? degenerate))]
      {:strata       strata
       :worst        {:low (:stratum lo) :high (:stratum hi) :ratio ratio
                      :disjoint? disjoint? :degenerate? degenerate}
       :underpowered degenerate
       :status       (if hit? :contaminated :clean)
       :why          (cond
                       hit? (str (:stratum hi) " reads "
                                 #?(:clj (format "%.4f" ratio) :cljs (.toFixed ratio 4))
                                 "x " (:stratum lo)
                                 (if degenerate
                                   " (n=1 strata: a ratio, not a range)"
                                   ", ranges disjoint"))
                       beyond? (str "medians "
                                    #?(:clj (format "%.4f" ratio) :cljs (.toFixed ratio 4))
                                    "x apart but the ranges OVERLAP — indistinguishable")
                       :else (str "within "
                                  #?(:clj (format "%.0f" (* 100.0 tolerance))
                                     :cljs (.toFixed (* 100.0 tolerance) 0))
                                  "%"))})))

(defn verdict
  "Does any arm's figure depend on where in the plan it was measured?

  `samples` — a seq of `{:arm :value :predecessor :position}`. `tolerance` is
  a relative difference of medians and the caller must choose it knowing its
  own noise.

  Answers `{:tolerance :arms :contaminated? :unchecked? :refuse?}`.
  `:refuse?` is the one a driver acts on: a single-order result and a
  contaminated one are both unreportable."
  ([samples] (verdict samples {}))
  ([samples {:keys [tolerance factors] :or {tolerance 0.10
                                            factors   [:predecessor :phase]}}]
   (let [by-arm (reduce (fn [acc s]
                          (if (finite? (:value s))
                            (update acc (:arm s) (fnil conj []) s)
                            acc))
                        {}
                        samples)
         arms   (into {}
                      (map (fn [[arm ss]]
                             [arm {:n       (count ss)
                                   :factors (reduce
                                              (fn [acc f]
                                                (let [strata (stratify ss f)]
                                                  ;; `:phase` is only askable when
                                                  ;; positions were recorded; a
                                                  ;; harness that does not record
                                                  ;; them is not thereby excused
                                                  ;; the predecessor question, so
                                                  ;; an empty phase stratification
                                                  ;; is skipped rather than failed.
                                                  (if (and (= f :phase) (empty? strata))
                                                    acc
                                                    (assoc acc f (adjudicate strata tolerance)))))
                                              {}
                                              factors)}]))
                      by-arm)
         status (fn [k] (some (fn [[_ a]]
                                (some (fn [[_ r]] (= k (:status r))) (:factors a)))
                              arms))
         cont?  (boolean (status :contaminated))
         unch?  (boolean (status :unchecked))]
     {:tolerance     tolerance
      :arms          arms
      :contaminated? cont?
      :unchecked?    unch?
      :refuse?       (or cont? unch?)})))

;; ---------------------------------------------------------------------------
;; The report

(defn- pad-r [s w] (let [s (str s)] (str s (str/join (repeat (max 0 (- w (count s))) " ")))))
(defn- pad-l [s w] (let [s (str s)] (str (str/join (repeat (max 0 (- w (count s))) " ")) s)))

(defn- num* [x]
  (cond
    (not (finite? x)) (str x)
    (>= (#?(:clj Math/abs :cljs js/Math.abs) (double x)) 1000.0)
    (str (long #?(:clj (Math/round (double x)) :cljs (js/Math.round x))))
    :else #?(:clj (format "%.4f" (double x)) :cljs (.toFixed (double x) 4))))

(defn report-lines
  "The `;;`-prefixed report a harness prints beside its table."
  ([v] (report-lines v nil))
  ([v title]
   (concat
     [(str ";; ==== ARM-ORDER GUARD" (when title (str " — " title)) " ====")
      (str ";;   every arm partitioned by WHAT RAN BEFORE IT and by WHERE IN THE RUN it was "
           "measured; tolerance "
           #?(:clj (format "%.0f" (* 100.0 (:tolerance v)))
              :cljs (.toFixed (* 100.0 (:tolerance v)) 0))
           "%, overlapping ranges are indistinguishable")]
     (mapcat
       (fn [[arm a]]
         (concat
           [(str ";;   " arm "  (" (:n a) " samples)")]
           (mapcat
             (fn [[f r]]
               (concat
                 [(str ";;     ["
                       (case (:status r) :clean "ok  " :unchecked "UNCH" "FAIL")
                       "] by " (pad-r (name f) 12) " " (:why r))]
                 (map (fn [s]
                        (str ";;              " (pad-r (:stratum s) 24)
                             " n=" (pad-l (:n s) 2)
                             "  p50 " (pad-l (num* (:p50 s)) 12)
                             "  [" (num* (:min s)) "–" (num* (:max s)) "]"))
                      (:strata r))))
             (:factors a))))
       (sort-by key (:arms v)))
     [(if (:refuse? v)
        ";;   VERDICT: REFUSE — no figure above may be published as measured"
        ";;   VERDICT: reportable — no arm reads differently for its position in the plan")])))

;; ---------------------------------------------------------------------------
;; The self-test — deterministic, and it is the proof this copy of the rule
;; still behaves like the one in `order_guard.cjs`. Harnesses run it before
;; measuring, exactly as `b8_run.cjs` does.

(defn self-test []
  (let [one    (fn [v arm factor] (get-in v [:arms arm :factors factor]))

        ;; 1. THE RECORDED 2x, replayed from `rf2-jr76s`. A packed-SMI
        ;;    `.slice()` control, predicted 8.000 B/slot, read once in
        ;;    forward arm order and once in reversed. Both strata are n=1,
        ;;    which is the whole difficulty: a guard that insisted on ranges
        ;;    would have passed this.
        smi    [{:arm "CTL-SMI-slice" :predecessor "DBL-10000" :position 9 :value 16.1052}
                {:arm "CTL-SMI-slice" :predecessor "P-VALS"    :position 2 :value 8.0027}]
        v-smi  (verdict smi {:tolerance 0.10})

        ;; 2. THE UNCONTAMINATED ARM FROM THE SAME STUDY must pass. The
        ;;    per-subscription slope reproduced 2,830.7 forward against
        ;;    2,842.8 reversed — 0.43% — and a guard that failed this would
        ;;    be useless.
        slope  [{:arm "RFWRITE-slope" :predecessor "RFWRITE-150" :position 3 :value 2830.7}
                {:arm "RFWRITE-slope" :predecessor "RFWRITE-300" :position 8 :value 2842.8}]

        ;; 3. A SINGLE-ORDER RESULT IS REFUSED — the bead's first actionable:
        ;;    a study that ran one order has not checked this.
        one-order (mapv (fn [[p v]] {:arm "A" :predecessor "B" :position p :value v})
                        [[0 100] [1 101] [2 99]])
        v-one  (verdict one-order {:tolerance 0.10 :factors [:predecessor]})

        ;; 4. THE HOUSE RULE. Medians 20% apart with overlapping ranges is
        ;;    indistinguishable, not a finding.
        overlapping (mapv (fn [[p pred v]] {:arm "A" :predecessor pred :position p :value v})
                          [[0 "X" 100] [2 "X" 140] [4 "X" 120]
                           [1 "Y" 90]  [3 "Y" 135] [5 "Y" 100]])
        v-ov   (verdict overlapping {:tolerance 0.10})

        ;; 5. THE WARM-UP STEP, from the `.cjs` module's own measured sweep:
        ;;    the SAME control, nothing else running, consecutive windows.
        ;;    The predecessor is identical throughout, so ONLY the phase
        ;;    factor can catch it — and a guard built to the bead's literal
        ;;    proposal (reverse the plan, compare) would not have.
        warmup (map-indexed (fn [i v] {:arm "CTL-SMI-slice" :predecessor "CTL-SMI-slice"
                                       :position i :value v})
                            [10.3223 10.2627 10.2588 10.2588 10.334 10.2832
                             8.1221 8.1221 8.1221 8.1221 8.1221 8.1221])
        v-warm (verdict warmup {:tolerance 0.10})

        ;; 6. AN ARM THAT ALLOCATES NOTHING IS NOT INFINITELY CONTAMINATED.
        ;;    `read-attribution`'s CACHEGET reads exactly 0 B in every round,
        ;;    so both strata are zero; the pair is n=1 apiece here, which is
        ;;    the case adjudicated on the ratio alone and therefore the case
        ;;    that fires. It must not.
        zeros  [{:arm "CACHEGET" :predecessor "PORT"  :position 0 :value 0}
                {:arm "CACHEGET" :predecessor "DEREF" :position 1 :value 0}]
        v-zero (verdict zeros {:tolerance 0.10 :factors [:predecessor]})

        ;; 7. CYCLIC ROTATION DOES NOT VARY ADJACENCY. Every arm keeps one
        ;;    predecessor in every round but the seam, so the order question
        ;;    is asked with a single sample in R. The reflecting schedule
        ;;    splits it.
        r 6
        n 4
        rot   (adjacency n r rotation-only {:seams? false})
        refl  (adjacency n r slot-order    {:seams? false})
        rot-d (mapv :distinct (vals (:arms rot)))
        ref-d (mapv :distinct (vals (:arms refl)))
        ref-s (apply max (map :modal-share (vals (:arms refl))))

        checks
        [{:name "the recorded 2.01x is REFUSED"
          :ok   (and (:refuse? v-smi)
                     (= :contaminated (:status (one v-smi "CTL-SMI-slice" :predecessor)))
                     (< (#?(:clj Math/abs :cljs js/Math.abs)
                         (- (get-in (one v-smi "CTL-SMI-slice" :predecessor) [:worst :ratio])
                            2.0125))
                        0.001))
          :detail (str "ratio " (num* (get-in (one v-smi "CTL-SMI-slice" :predecessor)
                                              [:worst :ratio])))}
         {:name "the same study's uncontaminated slope passes"
          :ok   (not (:refuse? (verdict slope {:tolerance 0.10})))
          :detail "forward 2830.7 / reversed 2842.8"}
         {:name "a single-order result is refused as UNCHECKED"
          :ok   (and (:refuse? v-one) (:unchecked? v-one)
                     (= :unchecked (:status (one v-one "A" :predecessor))))
          :detail (:why (one v-one "A" :predecessor))}
         {:name "overlapping ranges are indistinguishable even 20% apart in median"
          :ok   (not (:refuse? v-ov))
          :detail (:why (one v-ov "A" :predecessor))}
         {:name "the measured warm-up step is caught by PHASE, which no reversal would show"
          :ok   (and (:refuse? v-warm)
                     (= :contaminated (:status (one v-warm "CTL-SMI-slice" :phase)))
                     (= :unchecked (:status (one v-warm "CTL-SMI-slice" :predecessor))))
          :detail (str "phase: " (:why (one v-warm "CTL-SMI-slice" :phase)))}
         {:name "an arm that reads exactly zero in both strata is indistinguishable, not infinite"
          :ok   (= :clean (:status (one v-zero "CACHEGET" :predecessor)))
          :detail (:why (one v-zero "CACHEGET" :predecessor))}
         {:name "cyclic rotation gives every arm exactly ONE within-round predecessor"
          :ok   (every? #(= 1 %) rot-d)
          :detail (str "rotation over " r " rounds: distinct predecessors per arm "
                       (str/join "," rot-d)
                       " (only the round seam ever differs, and that is one sample in R)")}
         {:name "the reflecting schedule gives every arm TWO, in balance"
          :ok   (and (every? #(>= % 2) ref-d) (<= ref-s 0.75))
          :detail (str "reflected: " (str/join "," ref-d)
                       " predecessors per arm, largest modal share "
                       #?(:clj (format "%.0f" (* 100.0 ref-s))
                          :cljs (.toFixed (* 100.0 ref-s) 0)) "%")}]]
    {:ok?     (every? :ok checks)
     :checks  checks}))

(defn print-self-test!
  "Run [[self-test]], print each check, and answer whether it passed. A
  harness that gets `false` must measure nothing."
  []
  (let [st (self-test)]
    (doseq [c (:checks st)]
      (println (str ";; order-guard " (if (:ok c) "ok  " "FAIL") " " (:name c)
                    (when (:detail c) (str "  — " (:detail c))))))
    (:ok? st)))
