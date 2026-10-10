(ns re-frame.bench.fresco.lane-schedule-cljs-test
  "WHAT THE GUARD IS TOLD RAN BEFORE WHAT.

  [[re-frame.bench.fresco.lane/rounds!]] discards warm-up VALUES but must
  not forget that they RAN: `order-guard`'s `:predecessor` factor strata
  every banked sample by what ran immediately before it, and each round's
  first measured sample follows a warm-up execution. A collector that
  carried `:prev` from the last BANKED sample would file that sample under
  the previous round's last arm — an adjacency that never happened.

  The stub answers its own index in the TRUE execution sequence, so the
  predecessor a sample should carry is read off the recording rather than
  re-derived from `slot-order`, which would be a second copy of the rule
  under test.

  The last deftest holds the two prior-execution spans `rounds!`'s
  docstring quotes as its reason for having no run-level pre-warm: moving
  the warm-up out of the round loop turns it red."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(def ^:private arm-counts
  "Every arm count this lane's page-mount harnesses run at."
  [4 5 7 8 9])

(def ^:private sampling {:warmup 3 :samples 6})

(def ^:private rounds 5)

(defn- replay
  "`rounds!` over `n` arms with a stub answering each call's index in the
  true execution order; `:truth` is every execution, warm-up included."
  [n sampling rounds]
  (let [truth (atom [])
        arms  (mapv (fn [i] {:id (keyword (str "arm-" i))}) (range n))
        out   (rf.bench.fresco.lane/rounds! arms sampling rounds
                            (fn [arm]
                              (let [i (count @truth)]
                                (swap! truth conj (name (:id arm)))
                                i)))]
    (assoc out :truth @truth)))

(deftest every-recorded-predecessor-is-what-actually-ran
  (doseq [n arm-counts]
    (let [{:keys [samples truth]} (replay n sampling rounds)
          banked (set (map :value samples))]
      (is (= rounds (count (filter (fn [{:keys [value]}] (and (pos? value) (not (banked (dec value))))) samples)))
          (str n " arms: one banked sample per round follows a warm-up execution — "
               "the only place the fault can show, so the next assertion is not vacuous"))
      (is (= (mapv (fn [{:keys [value]}] (when (pos? value) (nth truth (dec value)))) samples)
             (mapv :predecessor samples))
          (str n " arms: every sample is filed under what actually ran before it")))))

(deftest warm-up-samples-run-and-are-discarded-and-positions-stay-contiguous
  ;; `:position` counts BANKED samples densely across the whole run — what
  ;; makes `:phase` a beginning-versus-end contrast — while the executions
  ;; behind them include every round's warm-up.
  (doseq [n arm-counts]
    (let [{:keys [warmup samples]} sampling
          {samps :samples truth :truth readings :readings} (replay n sampling rounds)]
      (is (= [(* n (+ warmup samples) rounds)
              (vec (range (* n samples rounds)))
              (vec (repeat rounds (zipmap (map #(keyword (str "arm-" %)) (range n)) (repeat samples))))]
             [(count truth)
              (mapv :position samps)
              (mapv (fn [m] (into {} (map (fn [[k xs]] [k (count xs)])) m)) readings)])
          (str n " arms")))))

(defn- prior-executions
  "Every banked sample with how many times ITS OWN ARM had already run,
  counted off the execution recording, warm-up included."
  [{:keys [samples truth]}]
  (let [before (:out (reduce (fn [{:keys [seen out]} nm]
                               {:seen (update seen nm (fnil inc 0))
                                :out  (conj out (get seen nm 0))})
                             {:seen {} :out []}
                             truth))]
    (mapv (fn [{:keys [value position]}]
            {:position position :prior (nth before value)})
          samples)))

(deftest the-ramp-the-phase-factor-catches-is-run-level-not-round-level
  ;; The run's first measured sample is warmed by round one's warm-up and
  ;; nothing else, while its last third sits an order of magnitude higher:
  ;; per-round warm-up restarts, and the ramp does not.
  (doseq [{:keys [sampling last-third]} [{:sampling {:warmup 3 :samples 6}  :last-third [32 44]}
                                         {:sampling {:warmup 8 :samples 12} :last-third [72 99]}]
          n                             arm-counts]
    (let [by-pos (vec (sort-by :position (prior-executions (replay n sampling rounds))))
          priors (mapv :prior (subvec by-pos (- (count by-pos) (quot (count by-pos) 3))))]
      (is (= [(:warmup sampling) last-third]
             [(:prior (first by-pos)) [(apply min priors) (apply max priors)]])
          (str n " arms " (pr-str sampling))))))
