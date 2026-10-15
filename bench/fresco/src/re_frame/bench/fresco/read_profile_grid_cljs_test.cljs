(ns re-frame.bench.fresco.read-profile-grid-cljs-test
  "The printed phase-B grid follows kept-count parity and the requested frame divisor."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame.bench.fresco.read-profile-app :as rf.bench.fresco.read-profile-app]))

(deftest the-derived-grid-follows-the-kept-counts-parity
  ;; One even and one odd total, with different frame divisors.
  (is (= [(/ 0.05 32) (/ 0.1 4)]
         (mapv (fn [[r s f]] (rf.bench.fresco.read-profile-app/phase-b-grid-ms r {:warmup 2 :samples s} f))
               [[8 8 32] [9 7 4]]))))

(deftest the-design-line-prints-the-derived-grid-at-both-parities
  (doseq [[r s expected] [[8 8 ["kept = 64 samples/arm (EVEN" "grid = 0.001563 ms/commit"]]
                          [9 7 ["kept = 63 samples/arm (ODD" "grid = 0.003125 ms/commit"]]]]
    (let [line (rf.bench.fresco.read-profile-app/phase-b-design-line r {:warmup 2 :samples s} 32)]
      (is (every? #(str/includes? line %) expected) line))))
