(ns re-frame.bench.fresco.read-profile-slots-cljs-test
  "PHASE B'S NULLS SIT AT THREE DIFFERENT SLOTS, AND THE SLOTS ARE THE POINT.

  `rounds-async!` visits arms in `slot-order`'s reflecting rotation,
  indexed by the sample, so every arm occupies a fixed multiset of sweep
  positions. `read_profile_app` carries three nulls of identical work, and
  the only thing distinguishing them is a vector index:

  - `c-null-twin` on `c-local`'s footprint exactly, so any position-driven
    cost cancels term by term;
  - `c-null-curve` on `c-local`'s mean position but a different footprint,
    so a linear drift cancels and a curved one does not;
  - `c-null` on the published window's slot, displaced in both.

  A null at the wrong slot still runs and reports a number that answers a
  different question, and nothing else in the tree would notice."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.bench.fresco.read-profile-app :as rf.bench.fresco.read-profile-app]))

(defn- footprint [id]
  (first (filter (comp #{id} :id) (rf.bench.fresco.read-profile-app/phase-b-slot-plan))))

(deftest the-built-roster-is-the-declared-roster
  ;; The vector index IS the slot, and `arm-rows` keys by id, so a duplicate
  ;; would pool two arms into one row.
  (let [ids rf.bench.fresco.read-profile-app/phase-b-arm-ids]
    (is (= [ids (count ids) true 3]
           [(mapv :id (rf.bench.fresco.read-profile-app/phase-b-arms {} {} {} nil))
            (count (set ids))
            (every? (set ids) rf.bench.fresco.read-profile-app/null-arm-ids)
            (count rf.bench.fresco.read-profile-app/null-arm-ids)]))))

(deftest the-nulls-were-appended-and-nothing-was-reordered
  ;; Published deltas are quoted slot-against-slot, so the published nine
  ;; arms keep their slots and the two further nulls come after them.
  (is (= [:commit :c-local :c-null :c-noactivate :c-nowatch
          :c-nosub :c-noreaders :c-nomap :b-build]
         (subvec rf.bench.fresco.read-profile-app/phase-b-arm-ids 0 9))))

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
