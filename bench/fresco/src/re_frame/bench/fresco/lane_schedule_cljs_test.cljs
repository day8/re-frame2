(ns re-frame.bench.hicasso.lane-schedule-cljs-test
  "WHAT THE GUARD IS TOLD RAN BEFORE WHAT (rf2-6ta5r, rf2-h904p).

  [[re-frame.bench.hicasso.lane/rounds!]] runs warm-up samples and throws
  their VALUES away. It must not throw away the fact that they RAN: a
  warm-up sample is what the next measured sample actually followed, and
  `order-guard`'s `:predecessor` factor strata every banked sample by
  exactly that.

  It did throw it away. [[rf.bench.hicasso.lane/collect!]] carried `:prev` forward from the
  last sample it BANKED, so at each round's first measured sample the
  predecessor it recorded was the PREVIOUS ROUND'S last measured arm —
  never the warm-up sample that had just run. Replaying the schedule
  prices it: on every arm count this lane uses, exactly one arm carried 5
  of its 30 samples under an adjacency that did not happen. On the
  seven-arm `amp_merge_clock` schedule that arm is `:expanded-b`, THE NULL
  — 4 of its samples filed under `floor`, which never runs before it — and
  on the five-arm schedule those same 4 were filed under `expanded-b`
  ITSELF, a predecessor no schedule can produce.

  A guard that adjudicates a contrast which did not happen is the
  fail-open shape the lane exists to refuse, so this is a fault in the
  instrument and not in any arm.

  ## Why the stub returns the execution INDEX

  So the check needs no second model of the schedule. `measure-one!`
  answers the position of its own call in the TRUE execution sequence, so
  each banked sample carries, in its `:value`, the index at which it ran.
  The predecessor it SHOULD have recorded is then just the sequence's
  previous entry, read off the recording rather than re-derived from
  `slot-order` — a re-derivation would be a second copy of the rule under
  test and would agree with a broken one.

  ## Anti-vacuity

  [[the-schedule-really-does-bank-across-the-warm-up-gap]] runs FIRST and
  asserts the crossing exists: unless some banked sample really does
  follow a discarded one, the assertion above is true of an empty set and
  tests nothing. A `:warmup` of 0 — or a `rounds!` that stopped
  interleaving — would fail there rather than pass everything silently.

  ## Arm counts

  4, 5, 7, 8 and 9 — `direct_return_clock`'s, `amp_merge_clock`'s before
  `rf2-z143r`'s ladder and after it, the count `rf.bench.hicasso.lane/observe!`'s own
  docstring prices the fault at, and `amp_merge_clock`'s again once
  `rf2-v5oto`'s two clean-pair arms land. The fault is invariant to all
  of them, which is the same arithmetic that settles `rf2-6ta5r`'s
  arm-count question: the schedule length does not move it.

  ## The second thing this file replays: HOW WARM the arm was (rf2-ydqzt)

  `order-guard` strata a banked sample by its `:predecessor` and by its
  `:phase`, and the two need different facts about the same schedule. The
  deftests above are the predecessor's. The last two are the phase's:
  warm-up is charged PER ROUND while the ramp `:phase` exists to catch is
  RUN-LEVEL, and `rounds!`'s own docstring now quotes the prior-execution
  span that follows from it. A quoted number nothing checks is the class of
  claim this directory exists to refuse, so both samplings the lane's
  page-mount clocks have run are replayed and the span is asserted.

  Those two deftests are also what a future `:prewarm` has to walk past.
  The bead that priced this asymmetry declined to build one — the knob
  sufficed, and rf2-adld3's window on the warmed rig came back reportable
  on phase — so what is recorded here is a REFUSAL and its arithmetic. A
  change that moves the warm-up out of the round loop turns these red,
  which is the point: it should not be possible to land the mechanism and
  leave the reasoning that declined it standing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]))

;; ---------------------------------------------------------------------------
;; The replay
;; ---------------------------------------------------------------------------

(def ^:private arm-counts
  "Every arm count this lane's page-mount harnesses run at, including the
  nine `rf2-v5oto` takes `amp_merge_clock` to."
  [4 5 7 8 9])

(defn- replay
  "Run `rf.bench.hicasso.lane/rounds!` over `n` arms with a stub that records the true
  execution order and answers each call's index in it.

  Answers `{:samples … :readings … :truth […]}`, where `truth` is EVERY
  execution in order — warm-up and measured alike."
  [n sampling rounds]
  (let [truth (atom [])
        arms  (mapv (fn [i] {:id (keyword (str "arm-" i))}) (range n))
        out   (rf.bench.hicasso.lane/rounds! arms sampling rounds
                            (fn [arm]
                              (let [i (count @truth)]
                                (swap! truth conj (name (:id arm)))
                                i)))]
    (assoc out :truth @truth)))

(def ^:private sampling
  "The sampling both clocks ran when they failed, kept HERE rather than
  raised with them: this file is about a fault that is invariant to the
  numbers, and pinning the old ones keeps it able to see the fault at the
  size it was found."
  {:warmup 3 :samples 6})

(def ^:private rounds 5)

(defn- followed-a-discarded-execution
  "The banked samples whose immediate predecessor was a warm-up execution
  — the only place the fault can appear."
  [{:keys [samples]}]
  (let [banked (set (map :value samples))]
    (filterv (fn [{:keys [value]}]
               (and (pos? value) (not (contains? banked (dec value)))))
             samples)))

;; ---------------------------------------------------------------------------
;; Anti-vacuity, first
;; ---------------------------------------------------------------------------

(deftest the-schedule-really-does-bank-across-the-warm-up-gap
  (testing "**the anti-vacuity guard.** Every round runs its warm-up
           samples and then banks, so exactly one banked sample per round
           follows a DISCARDED execution — and those are the only samples
           at which the fault below can show. A schedule that stopped
           crossing the gap, or a `:warmup` of 0, would make the next
           deftest an assertion about an empty set."
    (doseq [n arm-counts]
      (let [r       (replay n sampling rounds)
            crossed (followed-a-discarded-execution r)]
        (is (= rounds (count crossed))
            (str n " arms: one banked sample per round should follow a warm-up sample"))
        (is (pos? (count crossed))
            (str n " arms: nothing crosses the warm-up gap — the fault below is untestable"))))))

;; ---------------------------------------------------------------------------
;; The fault
;; ---------------------------------------------------------------------------

(deftest every-recorded-predecessor-is-what-actually-ran
  (testing "The whole claim `collect!`'s docstring makes — *tagged with
           what ran immediately before it* — checked against the recording
           of what did run, on every banked sample of every arm count."
    (doseq [n arm-counts]
      (let [{:keys [samples truth]} (replay n sampling rounds)]
        (is (pos? (count samples)) (str n " arms: no samples were banked"))
        (doseq [{:keys [arm value predecessor]} samples]
          (let [ran-before (when (pos? value) (nth truth (dec value)))]
            (is (= ran-before predecessor)
                (str n " arms: the sample banked at execution " value
                     " for " arm " ran after " (pr-str ran-before)
                     " and was filed under " (pr-str predecessor)))))))))

(deftest no-sample-is-recorded-as-its-own-predecessor
  (testing "The five-arm `amp_merge_clock` schedule filed 4 of the null
           arm's samples under `expanded-b` — the null arm itself. No
           schedule can run an arm twice in a row: `slot-order` visits
           every arm exactly once per sample index, so an arm can repeat
           only across a sample-index boundary, and then only if it holds
           both the last slot of one order and the first of the next. This
           is that impossibility asserted directly, because an impossible
           reading is information about the INSTRUMENT."
    (doseq [n arm-counts]
      (let [{:keys [samples truth]} (replay n sampling rounds)]
        (doseq [{:keys [arm value predecessor]} samples]
          (when (and predecessor (= arm predecessor))
            ;; Only a genuine back-to-back execution may say so.
            (is (= arm (nth truth (dec value)))
                (str n " arms: " arm " filed as its own predecessor at execution "
                     value ", but " (pr-str (nth truth (dec value)))
                     " is what ran"))))))))

;; ---------------------------------------------------------------------------
;; The accounting the phase factor rests on
;; ---------------------------------------------------------------------------

(deftest warm-up-samples-run-and-are-discarded-and-positions-stay-contiguous
  (testing "`:position` is the index in the WHOLE RUN, which is what makes
           `order-guard`'s `:phase` factor a beginning-versus-end contrast
           rather than a within-round one. It must therefore count the
           BANKED samples densely — a discarded sample has no position —
           while the executions behind them include the warm-up."
    (doseq [n arm-counts]
      (let [{:keys [warmup samples]} sampling
            {samps :samples truth :truth readings :readings} (replay n sampling rounds)
            per-arm-banked (* samples rounds)
            per-arm-run    (* (+ warmup samples) rounds)]
        (is (= (* n per-arm-run) (count truth))
            (str n " arms: every arm runs warm-up + samples in every round"))
        (is (= (* n per-arm-banked) (count samps))
            (str n " arms: only the measured samples are banked"))
        (is (= (vec (range (count samps))) (mapv :position samps))
            (str n " arms: positions are dense and in banking order"))
        (is (= rounds (count readings))
            (str n " arms: one reading map per round"))
        (is (every? (fn [m] (every? (fn [[_ xs]] (= samples (count xs))) m)) readings)
            (str n " arms: every arm contributes `samples` readings to every round"))))))

;; ---------------------------------------------------------------------------
;; What the per-round warm-up charge actually buys (rf2-ydqzt)
;; ---------------------------------------------------------------------------

(def ^:private samplings
  "Both samplings the lane's page-mount clocks have run, each with the span
  of PRIOR EXECUTIONS OF ITS OWN ARM that the run's last third sits at.

  `rf.bench.hicasso.lane/rounds!`'s docstring quotes both spans as the reason a run-level
  pre-warm was declined; these are the same two numbers, checked. The
  file-level [[sampling]] above stays pinned at the pair the predecessor
  fault was found under — that fault is invariant to the numbers and this
  one is entirely about them."
  [{:sampling {:warmup 3 :samples 6}  :last-third [32 44]}
   {:sampling {:warmup 8 :samples 12} :last-third [72 99]}])

(defn- prior-executions
  "Every banked sample, tagged with how many times ITS OWN ARM had already
  run in this run.

  Counted off the true execution recording, warm-up included, and not
  re-derived from `slot-order` — the same reason [[replay]] gives: a
  re-derivation is a second copy of the rule under test and would agree
  with a broken one."
  [{:keys [samples truth]}]
  (let [before (:out (reduce (fn [{:keys [seen out]} nm]
                               {:seen (update seen nm (fnil inc 0))
                                :out  (conj out (get seen nm 0))})
                             {:seen {} :out []}
                             truth))]
    (mapv (fn [{:keys [arm value position]}]
            {:arm arm :position position :prior (nth before value)})
          samples)))

(deftest warm-up-is-charged-in-every-round-and-not-once-per-run
  (testing "The mechanism, asserted directly. `rounds!` spends `warmup`
           discarded executions per arm inside EVERY round, so at five
           rounds four fifths of the run's whole warm-up spend falls in
           rounds that were already warm. This is not a defect — it is the
           shape a run-level `:prewarm` would change — and it is asserted
           so that changing it cannot leave `rounds!`'s recorded reasoning
           standing."
    (doseq [{:keys [sampling]} samplings
            n                  arm-counts]
      (let [{:keys [warmup samples]} sampling
            {truth :truth samps :samples} (replay n sampling rounds)
            banked   (set (map :value samps))
            per-round (partition (* n (+ warmup samples)) (range (count truth)))]
        (is (= rounds (count per-round))
            (str n " arms " (pr-str sampling) ": the run is `rounds` blocks of executions"))
        (doseq [[r idxs] (map-indexed vector per-round)]
          (is (= (* n warmup) (count (remove banked idxs)))
              (str n " arms " (pr-str sampling) ": round " (inc r)
                   " discards `warmup` executions of every arm")))
        (is (= (* n warmup (dec rounds))
               (- (count (remove banked (range (count truth)))) (* n warmup)))
            (str n " arms " (pr-str sampling)
                 ": every round after the first pays a full warm-up block"))))))

(deftest the-ramp-the-phase-factor-catches-is-run-level-not-round-level
  (testing "The consequence, and the two numbers `rounds!` quotes. The
           FIRST measured sample of a run has had exactly `warmup` prior
           executions of its arm however many rounds follow — round one's
           warm-up is the only warm-up in front of it — while the run's
           last third sits an order of magnitude higher. Per-round warm-up
           does not close that gap and cannot: it restarts, and the ramp
           does not."
    (doseq [{:keys [sampling last-third]} samplings
            n                             arm-counts]
      (let [{:keys [warmup samples]} sampling
            by-pos (vec (sort-by :position (prior-executions (replay n sampling rounds))))
            total  (count by-pos)
            third  (quot total 3)
            priors (mapv :prior (subvec by-pos (- total third)))]
        (is (= (* n samples rounds) total)
            (str n " arms " (pr-str sampling) ": every banked sample is accounted for"))
        (is (= warmup (:prior (first by-pos)))
            (str n " arms " (pr-str sampling)
                 ": the run's first measured sample is warmed by round one's warm-up "
                 "and by nothing else"))
        (is (= last-third [(apply min priors) (apply max priors)])
            (str n " arms " (pr-str sampling)
                 ": the run's last third sits at " (pr-str last-third)
                 " prior executions of its own arm"))))))
