(ns re-frame.bench.fresco.lane-resolution-cljs-test
  "[[re-frame.bench.fresco.lane/resolution]] answers what size of difference
  in the arms' OWN work a run could have seen, which `:straddles-1?` cannot:
  a paint-bounded pair can clear the floor every round, read `1.00x`, and
  still be blind to a `1.5x` difference, because the frame grid sits in
  both arms. The fixture is the `:locale` pair's first evidence run. Every
  answer is a figure and never a verdict — `budgets.md` §7 forbids a lane
  threshold — so the key set is pinned and carries no boolean."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]))

(def ^:private summary
  {:idle-frame   {:p50 16.30}
   :donor-locale {:p50 17.05}
   :locale       {:p50 17.05}})

(def ^:private round-ratios
  "Arm-to-floor ratios: `:donor-locale` at `17.05 / 16.30`, `:locale` that
  times 1.0000, 1.0375 and 1.0200 — a 3.75 point spread straddling 1.0."
  [{:locale 1.046    :donor-locale 1.046}
   {:locale 1.085225 :donor-locale 1.046}
   {:locale 1.06692  :donor-locale 1.046}])

(defn- pair []
  (rf.bench.fresco.lane/ratio-between round-ratios :locale :donor-locale))

(deftest resolves-at-is-the-difference-whose-displacement-equals-the-spread
  ;; 1 + spread / own-work-share: 0.0375 / (0.75 / 17.05) = 0.8525.
  (is (= {:denominator     :donor-locale
          :floor-p50       16.3
          :denominator-p50 17.05
          :own-work        0.75
          :own-work-share  0.044
          :spread          0.0375
          :resolves-at     1.8525}
         (rf.bench.fresco.lane/resolution (pair) summary :idle-frame))))

(deftest the-pair-clears-the-floor-and-still-cannot-resolve-the-line
  ;; Both arms separate from an empty frame in every round and the pair
  ;; reads ~1.00x — while the figure above says it could not have seen 1.5x.
  (let [over-floor (rf.bench.fresco.lane/across-rounds round-ratios)]
    (is (= [false false true]
           [(:straddles-1? (:locale over-floor))
            (:straddles-1? (:donor-locale over-floor))
            (:straddles-1? (pair))]))))

(deftest an-arm-at-or-below-the-floor-resolves-nothing-at-any-size
  ;; `nil` rather than a large number that looks like an answer, and the
  ;; negative excess is published rather than clamped away.
  (is (= [[0 nil] [-0.3 nil]]
         (mapv (fn [d]
                 ((juxt :own-work :resolves-at)
                  (rf.bench.fresco.lane/resolution (pair)
                                                   {:idle-frame {:p50 16.30} :donor-locale {:p50 d}}
                                                   :idle-frame)))
               [16.30 16.00]))))
