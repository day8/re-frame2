(ns re-frame.bench.calibration-cljs-test
  "The calibration refusal, adjudicated on every `npm run test:cljs`: the
  allocation harnesses that run `self-test` are `:advanced` release builds
  driven by hand, so no gate reaches it there."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.calibration :as rf.bench.calibration]))

(defn- pair [d smi dbl] {:d d :smi smi :dbl dbl})

(deftest self-test-passes
  (let [st (rf.bench.calibration/self-test)]
    (doseq [c (:checks st)]
      (is (:ok c) (str (:name c) " — " (:detail c))))
    (is (:ok? st))))

(deftest the-recorded-fault-refuses
  ;; The polymorphic-`.slice()` run: 16.11 B/slot against a tagged slot's 8.
  (is (seq (rf.bench.calibration/report-lines
             (rf.bench.calibration/verdict [(pair 100 1681.7 848.0) (pair 200 3293.5 1648.0)] 16.1146)))
      "a refusal produces the lines the harness prints before exiting 2"))

(deftest the-fixed-harness-is-reportable
  (is (empty? (rf.bench.calibration/report-lines
                (rf.bench.calibration/verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] 8.0027)))
      "a sound control says nothing"))

(deftest the-band-edges-are-where-they-are-documented
  (doseq [[ratio regime] [[0.96 :off] [1.04 :off] [0.95 :neither] [1.05 :neither]
                          [0.5 :on] [0.45 :neither] [0.55 :neither]]]
    (is (= regime (rf.bench.calibration/regime-of ratio)) (str "ratio " ratio))))

(deftest the-slope-is-checked-against-the-width-the-ratios-selected
  (testing "compression OFF selects a width of 8"
    (doseq [[slope refuse?] [[9.9 false] [10.1 true] [4.0 true] [js/NaN true]]]
      (let [v (rf.bench.calibration/verdict [(pair 100 849.1 848.0) (pair 200 1651.8 1648.0)] slope)]
        (is (= refuse? (:refuse? v)) (str "slope " slope " against a width of 8")))))
  (testing "compression ON selects a width of 4"
    (doseq [[slope refuse?] [[4.9 false] [5.1 true]]]
      (let [v (rf.bench.calibration/verdict [(pair 100 448.0 848.0) (pair 200 848.0 1648.0)] slope)]
        (is (= refuse? (:refuse? v)) (str "slope " slope " against a width of 4"))))))
