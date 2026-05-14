(ns re-frame.reduce-regions-test
  "Pure-fn unit test for `re-frame.machines.parallel/reduce-regions` —
  the broadcast invariant for parallel-region machines (rf2-vqubp).

  Asserts the four threading properties the helper encodes:
   1. Regions iterate in declaration order (the order `:regions` was
      authored).
   2. A later region's step sees earlier regions' `:data` writes.
   3. The shared `:rf/spawn-counter` (rf2-gr8q) threads in/out across
      regions — bumps in one region are visible to the next.
   4. Per-region fx is prefixed via `prefix-region-invoke-id` so the
      `[:rf/spawned ...]` slot key stays unique per region.

  And the contract:
   5. A `result/fail` from any region short-circuits the reduce — later
      regions don't run and the failure propagates verbatim.

  The helper is a `defn-` so the test reaches in via the var to invoke
  it directly. This isolates the reduce-regions semantics from the
  bootstrap-step / machine-transition step-fns the two production
  callers supply."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]))

(def ^:private reduce-regions
  ;; Reach in to the private helper. Per rf2-vqubp this test isolates
  ;; the broadcast invariant from the two production step-fns.
  #'parallel/reduce-regions)

(defn- two-region-spec
  "Synthetic parallel-region spec — :a authored before :b."
  []
  {:type    :parallel
   :regions {:a {:initial :a/idle
                 :states  {:a/idle {}}}
             :b {:initial :b/idle
                 :states  {:b/idle {}}}}})

(defn- snapshot
  ([state]      (snapshot state {} nil))
  ([state data] (snapshot state data nil))
  ([state data counter]
   (cond-> {:state state :data data}
     (some? counter) (assoc :rf/spawn-counter counter))))

;; ---- (1) declaration order -------------------------------------------------

(deftest iterates-regions-in-declaration-order
  (testing "step-fn receives regions in `:regions` declaration order"
    (let [machine (two-region-spec)
          calls   (atom [])
          step    (fn [region-spec region-snap]
                    (swap! calls conj (:rf/region region-spec))
                    (result/ok region-snap []))
          snap    (snapshot {:a :a/idle :b :b/idle})
          r       (reduce-regions machine snap step)]
      (is (result/ok? r))
      (is (= [:a :b] @calls)
          "region :a fires before region :b — declaration order"))))

;; ---- (2) :data threads forward --------------------------------------------

(deftest later-region-sees-earlier-region-data-writes
  (testing "region :b's step sees the `:data` value :a's step wrote"
    (let [machine (two-region-spec)
          seen-data-by-b (atom nil)
          step    (fn [region-spec region-snap]
                    (case (:rf/region region-spec)
                      :a (result/ok (assoc-in region-snap [:data :written-by] :a) [])
                      :b (do (reset! seen-data-by-b (:data region-snap))
                             (result/ok region-snap []))))
          snap    (snapshot {:a :a/idle :b :b/idle} {:seed 1})
          r       (reduce-regions machine snap step)]
      (is (result/ok? r))
      (is (= {:seed 1 :written-by :a} @seen-data-by-b)
          ":b's step sees :a's :data write merged into the shared :data slot")
      (is (= {:seed 1 :written-by :a} (:data (result/snap r)))
          "merged snapshot carries the final :data after all regions ran"))))

;; ---- (3) :rf/spawn-counter threads in/out ----------------------------------

(deftest spawn-counter-threads-across-regions
  (testing ":rf/spawn-counter bump in :a is visible to :b and carries through"
    (let [machine (two-region-spec)
          seen-counter-by-b (atom nil)
          step    (fn [region-spec region-snap]
                    (case (:rf/region region-spec)
                      :a (result/ok (assoc region-snap :rf/spawn-counter {:ix 5}) [])
                      :b (do (reset! seen-counter-by-b (:rf/spawn-counter region-snap))
                             (result/ok (assoc region-snap :rf/spawn-counter {:ix 5 :iy 7}) []))))
          snap    (snapshot {:a :a/idle :b :b/idle} {} {})
          r       (reduce-regions machine snap step)]
      (is (result/ok? r))
      (is (= {:ix 5} @seen-counter-by-b)
          ":b's region-snap carries :a's bumped counter")
      (is (= {:ix 5 :iy 7} (:rf/spawn-counter (result/snap r)))
          "merged snapshot carries the final counter after all regions ran")))

  (testing "absent :rf/spawn-counter stays absent (no defensive seeding)"
    (let [machine (two-region-spec)
          step    (fn [_ region-snap] (result/ok region-snap []))
          snap    (snapshot {:a :a/idle :b :b/idle})            ;; no :rf/spawn-counter
          r       (reduce-regions machine snap step)]
      (is (result/ok? r))
      (is (not (contains? (result/snap r) :rf/spawn-counter))
          "no :rf/spawn-counter slot is fabricated when the input snapshot had none"))))

;; ---- (4) per-region fx prefix ---------------------------------------------

(deftest per-region-fx-is-prefixed-with-region-name
  (testing ":rf/invoke-id on emitted fx is prepended with the region name"
    (let [machine (two-region-spec)
          step    (fn [region-spec region-snap]
                    (let [rn (:rf/region region-spec)]
                      (result/ok region-snap
                                 [[:rf.machine/spawn {:rf/invoke-id [rn :child]}]])))
          snap    (snapshot {:a :a/idle :b :b/idle})
          r       (reduce-regions machine snap step)]
      (is (result/ok? r))
      ;; Each region's step emits an :invoke-id starting with its own
      ;; region name; reduce-regions prepends the region name AGAIN
      ;; (the standard prefix-region-invoke-id rule) so the final fx
      ;; carries `[rn rn :child]`.
      (is (= [[:rf.machine/spawn {:rf/invoke-id [:a :a :child]}]
              [:rf.machine/spawn {:rf/invoke-id [:b :b :child]}]]
             (result/fx r))
          "per-region fx is prefixed with that region's name"))))

;; ---- (5) short-circuit on failure -----------------------------------------

(deftest result-fail-short-circuits
  (testing "if region :a's step fails, region :b's step does NOT run"
    (let [machine (two-region-spec)
          b-ran?  (atom false)
          fail-r  (result/fail {:reason :test-failure})
          step    (fn [region-spec _region-snap]
                    (case (:rf/region region-spec)
                      :a fail-r
                      :b (do (reset! b-ran? true)
                             (result/ok {:state :b/idle :data {}} []))))
          snap    (snapshot {:a :a/idle :b :b/idle})
          r       (reduce-regions machine snap step)]
      (is (result/fail? r))
      (is (false? @b-ran?)
          "later region's step never runs when an earlier region fails")
      (is (= :test-failure (-> (result/info r) :reason))
          "the failure propagates verbatim — caller sees the offending region's diagnostic"))))
