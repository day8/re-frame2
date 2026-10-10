(ns re-frame.bench.fresco.read-profile-grid-cljs-test
  "PHASE B'S PRINTED GRID IS DERIVED FROM THE KEPT COUNT'S PARITY.

  `rf.bench.fresco.lane/now-ms` is clamped to 100 µs, `summarise`'s p50 is
  the mean of the two middle readings at an EVEN kept count (a half-clamp
  grid) and a single reading at an ODD one, and the row divides by the
  frame count. `b-rounds` and `b-sampling` are independent vars, so an odd
  product doubles the real grid — and a design line printing
  `0.05 / frames` as a constant would go on advertising the halved one.
  The odd-parity line below is the row a literal cannot survive. The
  parity mechanism itself is pinned beside `summarise`, in
  `lane_quantile_cljs_test`'s `quantile-at-one-half-is-summarise-s-p50`."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [re-frame.bench.fresco.read-profile-app :as rf.bench.fresco.read-profile-app]))

(deftest the-derived-grid-follows-the-kept-counts-parity
  ;; Even kept totals (64, 24) land on the half clamp, odd ones (63, 1) on
  ;; the full clamp; frames divide either.
  (is (= [(/ 0.05 32) (/ 0.05 4) (/ 0.1 32) (/ 0.1 4)]
         (mapv (fn [[r s f]] (rf.bench.fresco.read-profile-app/phase-b-grid-ms r {:warmup 2 :samples s} f))
               [[8 8 32] [4 6 4] [9 7 32] [1 1 4]]))))

(deftest the-design-line-prints-the-derived-grid-at-both-parities
  (doseq [[r s expected] [[8 8 ["kept = 64 samples/arm (EVEN" "grid = 0.001563 ms/commit"]]
                          [9 7 ["kept = 63 samples/arm (ODD" "grid = 0.003125 ms/commit"]]]]
    (let [line (rf.bench.fresco.read-profile-app/phase-b-design-line r {:warmup 2 :samples s} 32)]
      (is (every? #(str/includes? line %) expected) line))))

(deftest the-live-shape-is-recorded-with-its-parity
  ;; The shipped window's own shape, read off `phase-b-shape`, prints the
  ;; parity its kept count has and the grid that shape derives.
  (let [{:keys [rounds sampling frames]} (rf.bench.fresco.read-profile-app/phase-b-shape)
        kept (* rounds (:samples sampling))
        line (rf.bench.fresco.read-profile-app/phase-b-design-line rounds sampling frames)]
    (is (every? #(str/includes? line %)
                [(str "kept = " kept " samples/arm (" (if (even? kept) "EVEN" "ODD"))
                 (str "grid = " (.toFixed (rf.bench.fresco.read-profile-app/phase-b-grid-ms rounds sampling frames) 6)
                      " ms/commit")])
        line)))
