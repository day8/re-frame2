(ns re-frame.bench.calibration-cljs-test
  "rf2-l3jv4 — the calibration refusal, adjudicated by a GATED suite.

  `re-frame.bench.calibration/self-test` runs inside each harness before it
  measures anything, which is the right place for it but not a place CI ever
  reaches: the allocation harnesses are `:advanced` release builds driven by
  hand. The audit that reopened this owner said so plainly — every listed
  gate exercised values inside the expected bands, so the newly introduced
  failure branch was never adjudicated.

  This runs the same injected fixtures under `npm run test:cljs`, and adds
  the two properties the self-test cannot state about itself: that the
  refusal is REACHABLE from the numbers actually recorded against this bead,
  and that the healthy numbers actually recorded after the fix do NOT reach
  it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.calibration :as calib]))

(defn- pair [d smi dbl] {:d d :smi smi :dbl dbl})

(deftest self-test-passes
  (testing "every injected fixture earns the verdict it is asserted to earn"
    (let [st (calib/self-test)]
      (doseq [c (:checks st)]
        (is (:ok c) (str (:name c) " — " (:detail c))))
      (is (:ok? st)))))

(deftest the-recorded-fault-refuses
  (testing "the 16.11 B/slot polymorphic-.slice() run this bead was opened on"
    ;; The measured figures from the bead: SMI D=100 1681.7 B/copy against a
    ;; predicted (and DBL-measured) 848, SMI D=200 3293.5 against 1648,
    ;; printed slope 16.1146 B/slot. That run exited 0.
    (let [v (calib/verdict [(pair 100 1681.7 848.0) (pair 200 3293.5 1648.0)] 16.1146)]
      (is (:refuse? v))
      (is (= :neither (:regime v)))
      (is (= [:neither :neither] (mapv :regime (:pairs v))))
      (is (seq (calib/report-lines v))
          "a refusal must produce the lines the harness prints before exiting 2"))))

(deftest the-fixed-harness-is-reportable
  (testing "the same control after the per-elements-kind split"
    (let [v (calib/verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] 8.0027)]
      (is (not (:refuse? v)))
      (is (= :off (:regime v)))
      (is (= 8.0 (:width v)))
      (is (empty? (calib/report-lines v))
          "a sound control says nothing — the harness prints its numbers either way"))))

(deftest a-compressed-build-is-not-refused
  (testing "pointer compression ON is a regime to be READ, not a fault"
    (let [v (calib/verdict [(pair 100 448.0 848.0) (pair 200 848.0 1648.0)] 4.0)]
      (is (not (:refuse? v)))
      (is (= :on (:regime v)))
      (is (= 4.0 (:width v))))))

(deftest the-band-edges-are-where-they-are-documented
  (testing "regime-of, swept across the boundaries it is specified at"
    (is (= :off (calib/regime-of 1.0)))
    (is (= :off (calib/regime-of 0.96)))
    (is (= :off (calib/regime-of 1.04)))
    (is (= :neither (calib/regime-of 0.95)) "the band is open at its edge")
    (is (= :neither (calib/regime-of 1.05)) "the band is open at its edge")
    (is (= :on (calib/regime-of 0.5)))
    (is (= :neither (calib/regime-of 0.45)))
    (is (= :neither (calib/regime-of 0.55)))
    (is (= :neither (calib/regime-of 0.75)) "between the bands is not a regime")
    (is (= :neither (calib/regime-of 2.0)) "the recorded fault's ratio")
    (is (= :neither (calib/regime-of js/NaN)))
    (is (= :neither (calib/regime-of js/Infinity)))))

(deftest the-slope-is-checked-against-the-width-the-ratios-selected
  (testing "sound absolutes with a wrong step still refuse"
    ;; Both sizes name OFF, so the width is 8; only the size-to-size step is
    ;; wrong, which is what a constant added to both copies looks like.
    (doseq [[slope refuse?] [[8.0 false] [8.0027 false] [9.9 false]
                             [10.1 true] [12.5 true] [16.1146 true]
                             [4.0 true]]]
      (let [v (calib/verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] slope)]
        (is (= refuse? (:refuse? v)) (str "slope " slope " against a width of 8")))))
  (testing "the same question in the compressed regime, where the width is 4"
    (doseq [[slope refuse?] [[4.0 false] [4.9 false] [5.1 true] [8.0 true]]]
      (let [v (calib/verdict [(pair 100 448.0 848.0) (pair 200 848.0 1648.0)] slope)]
        (is (= refuse? (:refuse? v)) (str "slope " slope " against a width of 4"))))))

(deftest a-missing-or-dead-control-refuses
  (testing "nothing to check is not checked"
    (is (:refuse? (calib/verdict [] 8.0)))
    (is (= :neither (:regime (calib/verdict [] 8.0)))))
  (testing "a zero DBL reading refuses rather than dividing to infinity"
    (is (:refuse? (calib/verdict [(pair 100 849.1 0.0)] 8.0))))
  (testing "a non-finite slope refuses even where the ratios are sound"
    (is (:refuse? (calib/verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)]
                                 js/NaN)))))

(deftest the-sizes-must-agree-with-each-other
  (testing "one instrument giving two answers about one machine"
    (let [v (calib/verdict [(pair 100 849.1 848.0) (pair 200 824.0 1648.0)] 8.0)]
      (is (:refuse? v))
      (is (= :neither (:regime v)))
      (is (= [:off :on] (mapv :regime (:pairs v)))
          "each ratio lands cleanly in a band; it is the disagreement that refuses"))))

(deftest the-documented-large-object-deviation-is-not-gated
  (testing "V8's page-tail filler must not refuse a healthy run"
    ;; Both arms 9% over the asserted layout, tracking each other, with the
    ;; +9% step to match. The bead's bounded repair ruled this out by name.
    (let [v (calib/verdict [(pair 100 924.3 924.3) (pair 200 1796.3 1796.3)] 8.72)]
      (is (not (:refuse? v)))
      (is (= :off (:regime v))))))
