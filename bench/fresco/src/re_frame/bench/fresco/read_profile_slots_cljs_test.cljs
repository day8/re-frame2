(ns re-frame.bench.fresco.read-profile-slots-cljs-test
  "Published arms retain their slots while three nulls occupy the distinct footprints the comparison requires."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.read-profile-app :as rf.bench.fresco.read-profile-app]))

(defn- footprint [id]
  (first (filter (comp #{id} :id) (rf.bench.fresco.read-profile-app/phase-b-slot-plan))))

(deftest the-nulls-were-appended-and-nothing-was-reordered
  ;; Published deltas are quoted slot-against-slot, so the published nine
  ;; arms keep their slots and the two further nulls come after them. The
  ;; ids are distinct because `arm-rows` keys by id, so a duplicate would
  ;; pool two arms into one row.
  (let [ids rf.bench.fresco.read-profile-app/phase-b-arm-ids]
    (is (= [[:commit :c-local :c-null :c-noactivate :c-nowatch
             :c-nosub :c-noreaders :c-nomap :b-build]
            true]
           [(subvec ids 0 9) (apply distinct? ids)]))))

(deftest the-three-nulls-sit-on-three-different-footprints
  (let [base      (footprint :c-local)
        twin      (footprint :c-null-twin)
        curve     (footprint :c-null-curve)
        displaced (footprint :c-null)]
    (is (= {:twin-on-c-local-footprint?  true
            :twin-on-another-slot?       true
            :curve-on-c-local-mean?      true
            :curve-off-c-local-footprint? true
            :displaced-off-c-local-mean? true}
           {:twin-on-c-local-footprint?  (= (:positions base) (:positions twin))
            :twin-on-another-slot?       (not= (:slot base) (:slot twin))
            :curve-on-c-local-mean?      (= (:mean-position base) (:mean-position curve))
            :curve-off-c-local-footprint? (not= (:positions base) (:positions curve))
            :displaced-off-c-local-mean? (not= (:mean-position base) (:mean-position displaced))}))))

(deftest the-published-nine-arm-null-differenced-two-different-footprints
  ;; At nine arms and the sampling the published window ran, `c-local`
  ;; (slot 1) and `c-null` (slot 2) sit over different mean positions — the
  ;; confound the other two nulls exist to resolve. If the sampling moved,
  ;; re-derive this row rather than adjusting it.
  (let [sampling (:sampling (rf.bench.fresco.read-profile-app/phase-b-shape))]
    (is (= [{:warmup 2 :samples 8} 4.5 3.375]
           [sampling
            (:mean-position (rf.bench.fresco.read-profile-app/slot-footprint 9 1 sampling))
            (:mean-position (rf.bench.fresco.read-profile-app/slot-footprint 9 2 sampling))]))))
