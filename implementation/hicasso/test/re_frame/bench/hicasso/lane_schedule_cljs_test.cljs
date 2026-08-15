(ns re-frame.bench.hicasso.lane-schedule-cljs-test
  "WHAT THE GUARD IS TOLD RAN BEFORE WHAT (rf2-6ta5r, rf2-h904p).

  [[re-frame.bench.hicasso.lane/rounds!]] runs warm-up samples and throws
  their VALUES away. It must not throw away the fact that they RAN: a
  warm-up sample is what the next measured sample actually followed, and
  `order-guard`'s `:predecessor` factor strata every banked sample by
  exactly that.

  It did throw it away. [[lane/collect!]] carried `:prev` forward from the
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

  4, 5, 7 and 8 — `direct_return_clock`'s, `amp_merge_clock`'s before and
  after `rf2-z143r`'s ladder, and the eighth arm `rf2-v5oto` wants. The
  fault is invariant to all of them, which is the same arithmetic that
  settles `rf2-6ta5r`'s arm-count question: the schedule length does not
  move it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as lane]))

;; ---------------------------------------------------------------------------
;; The replay
;; ---------------------------------------------------------------------------

(def ^:private arm-counts
  "Every arm count this lane's page-mount harnesses run at, plus the one
  `rf2-v5oto` proposes."
  [4 5 7 8])

(defn- replay
  "Run `lane/rounds!` over `n` arms with a stub that records the true
  execution order and answers each call's index in it.

  Answers `{:samples … :readings … :truth […]}`, where `truth` is EVERY
  execution in order — warm-up and measured alike."
  [n sampling rounds]
  (let [truth (atom [])
        arms  (mapv (fn [i] {:id (keyword (str "arm-" i))}) (range n))
        out   (lane/rounds! arms sampling rounds
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
