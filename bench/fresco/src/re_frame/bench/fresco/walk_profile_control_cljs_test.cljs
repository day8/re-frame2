(ns re-frame.bench.fresco.walk-profile-control-cljs-test
  "THE WALK PROFILE'S POSITIVE CONTROL MUST REFUSE.

  `walk_profile_app`'s control sets `window.FRESCO_CONTROL_FAILED`, which
  makes `run.cjs`'s control exit path live for this arm. A browser proof of
  that costs an `:advanced` build and a Chromium run, so the RULE is pinned
  here over synthetic readings.

  [[strict-rule-beats-overlap]] carries the design: the control asks
  whether EVERY ROUND clears the bar, where `lane/control-verdict` asks
  whether the range OVERLAPS it (`lane_control_strict_cljs_test` states
  which instruments take which rule). The other rows pin the mode no
  mutation of the measured arm can reach: the bar is
  `n-tags x (fresh - hit)` less slack, so converged or inverted primitives
  put it at or below zero, where every reading clears it — and the tag
  cache having stopped mattering is exactly the case the row exists for."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.bench.fresco.walk-profile-app :as rf.bench.fresco.walk-profile-app]))

(def ^:private walks-per-sample
  "`timed-walks` measures K walks per sample and the control divides by K;
  every exact `:worst` below moves if this drifts from the app's K."
  8)

(defn- round
  "One round's readings from `{arm ms-per-walk}`: three identical samples,
  so each arm's p50 is the number asked for."
  [per-walk]
  (into {} (map (fn [[id ms]] [id (vec (repeat 3 (* ms walks-per-sample)))])) per-walk))

(def ^:private micro
  "100 ns of fresh-minus-cached over 1,000 tags: a floor of 0.1 ms/walk and
  a bar, at 25% slack, of 0.075."
  [[:cached-parse-hit 50.0] [:parse-tag-fresh 150.0]])

(def ^:private census {:native 1000})
(def ^:private roster (make-array 1000))

(defn- healthy
  "`parse-raw` `delta` above `local`, and `ship-lazy` above `ship`."
  [delta]
  (round {:local 0.60 :parse-raw (+ 0.60 delta) :ship 0.50 :ship-lazy 1.50}))

(defn- floor-row [readings micro]
  (rf.bench.fresco.walk-profile-app/tag-cache-floor-row readings census roster micro))

(deftest an-instrument-with-signal-passes
  (is (= {:ok? true :worst 0.18}
         (select-keys (floor-row [(healthy 0.20) (healthy 0.25) (healthy 0.18)] micro) [:ok? :worst]))))

(deftest strict-rule-beats-overlap
  ;; One dataset, two rules: the overlap rule lets a good round vouch for a
  ;; bad one, and the every-round rule refuses it.
  (let [strict  (floor-row [(healthy 0.20) (healthy 0.20) (healthy 0.03)] micro)
        overlap (rf.bench.fresco.lane/control-verdict 0.1 {:min 0.03 :max 0.20 :mean 0.1433} 0.25)]
    (is (= [true false 0.03] [(:ok? overlap) (:ok? strict) (:worst strict)]))))

(deftest the-audits-exact-converged-case
  ;; Cached 50 ns, fresh 50 ns, observed delta 0: an unguarded `worst >= bar`
  ;; reports ok.
  (is (= {:predicted 0 :bar 0 :worst 0 :ok? false}
         (select-keys (floor-row [(healthy 0.0)] [[:cached-parse-hit 50.0] [:parse-tag-fresh 50.0]])
                      [:predicted :bar :worst :ok?]))))

(deftest the-audits-exact-inverted-case
  ;; A cache hit priced above a fresh parse: a NEGATIVE floor puts the bar
  ;; below every real measurement.
  (is (= {:predicted -0.1 :bar -0.075 :ok? false :stated? false}
         (select-keys (floor-row [(healthy 0.0)] [[:cached-parse-hit 150.0] [:parse-tag-fresh 50.0]])
                      [:predicted :bar :ok? :stated?]))))

(deftest an-absent-prediction-is-reported-DIFFERENTLY-from-a-missed-bar
  ;; Two refusals, two repairs: an operator told only FAILED goes looking at
  ;; the arms, where nothing is wrong.
  (is (= ["REFUSED — no prediction" "FAILED" "ok"]
         (mapv rf.bench.fresco.walk-profile-app/control-status
               [(floor-row [(healthy 0.20)] [[:cached-parse-hit 50.0] [:parse-tag-fresh 50.0]])
                (floor-row [(healthy 0.01)] micro)
                (floor-row [(healthy 0.20)] micro)]))))

(deftest a-roster-that-is-not-the-walks-parse-population-refuses
  ;; The prediction is per-tag over the micro roster, so a roster that is
  ;; not what the walk parses prices the wrong thing.
  (let [r (rf.bench.fresco.walk-profile-app/tag-cache-floor-row [(healthy 0.20) (healthy 0.20)]
                                                                {:native 999} roster micro)]
    (is (= [false {:micro-roster 1000 :walk-parses 999}] [(:ok? r) (:population r)]))))

(deftest the-lazy-arm-must-read-above-the-eager-one-in-every-round
  ;; A single inverted round refuses beside two good ones: an inversion
  ;; means the window is not pricing the walk.
  (let [inverted (rf.bench.fresco.walk-profile-app/lazy-tail-direction-row
                   [(healthy 0.2) (round {:local 0.6 :parse-raw 0.8 :ship 1.50 :ship-lazy 1.40}) (healthy 0.2)])]
    (is (= [true false -0.1]
           [(:ok? (rf.bench.fresco.walk-profile-app/lazy-tail-direction-row [(healthy 0.2) (healthy 0.2)]))
            (:ok? inverted)
            (:worst inverted)]))))
