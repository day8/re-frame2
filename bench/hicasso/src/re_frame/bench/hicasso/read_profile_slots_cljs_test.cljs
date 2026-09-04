(ns re-frame.bench.hicasso.read-profile-slots-cljs-test
  "PHASE B'S NULLS SIT AT THREE DIFFERENT SLOTS, AND THE SLOTS ARE THE
  POINT — pinned (rf2-lo7uy).

  ## What this exists to catch

  rf2-3l6hf's window measured a quantity whose true value is exactly zero
  — `c-local - c-null`, the same `C-FULL` mode differenced against itself
  under a second id — and read `+0.0234 / +0.0219 / +0.0234 ms/commit`,
  three runs inside one grid step. Every phase-B term is a `c-local - X`
  delta on that instrument, and most are only a few multiples of that
  offset, so its CAUSE matters and one null cannot supply it: one null is
  one pair of slots.

  `read_profile_app` therefore carries three nulls, and the whole of their
  value is WHERE THEY SIT. `rounds-async!` visits arms in
  `rf.bench.hicasso.lane/slot-order`'s reflecting rotation, indexed by the SAMPLE and not
  the round, so every arm occupies a fixed multiset of sweep positions —
  the same multiset in every round of every run. The layout was chosen so
  that

  - `c-null-twin` occupies `c-local`'s footprint EXACTLY, making any
    position-driven cost cancel term by term between them;
  - `c-null-curve` occupies a different footprint with `c-local`'s MEAN
    position, so a linear drift cancels and a curved one does not;
  - `c-null` stays where the published window had it, displaced in both.

  **A null at the wrong slot still compiles, still runs, still reports a
  number, and answers a different question from the one the transcript
  says it answers.** Nothing else in the tree would notice: the arms are
  built from one `mk-local`, so every null is byte-identical work, and the
  only thing distinguishing them is a vector index. That is what these
  rows hold.

  ## What is deliberately NOT here

  No clock, no frame, no window, no measurement. The claims are the
  schedule's arithmetic and the roster's shape, and both are decidable
  without running a bench. Whether the offset IS positional is a question
  for the window this layout was built to make askable; this namespace
  only refuses to let the instrument drift out from under it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.read-profile-app :as rf.bench.hicasso.read-profile-app]))

(defn- slot-of [id]
  (first (keep-indexed (fn [i x] (when (= x id) i)) rf.bench.hicasso.read-profile-app/phase-b-arm-ids)))

(defn- footprint [id]
  (first (filter (comp #{id} :id) (rf.bench.hicasso.read-profile-app/phase-b-slot-plan))))

;; ===========================================================================
;; 1 — the roster has ONE authority
;; ===========================================================================

(deftest the-built-roster-is-the-declared-roster
  (testing "`phase-b-arms` builds exactly the arms `phase-b-arm-ids`
           declares, in exactly that order — the vector index IS the slot,
           so a roster that disagreed would put every printed delta on a
           different pair of slots from the one its label names"
    (is (= rf.bench.hicasso.read-profile-app/phase-b-arm-ids
           (mapv :id (rf.bench.hicasso.read-profile-app/phase-b-arms {} {} {} nil)))))

  (testing "ids are distinct, because `arm-rows` keys by id and a duplicate
           would silently pool two arms into one row"
    (is (= (count rf.bench.hicasso.read-profile-app/phase-b-arm-ids)
           (count (set rf.bench.hicasso.read-profile-app/phase-b-arm-ids)))))

  (testing "every null is on the roster it is a null of"
    (is (every? (set rf.bench.hicasso.read-profile-app/phase-b-arm-ids) rf.bench.hicasso.read-profile-app/null-arm-ids))
    (is (= 3 (count rf.bench.hicasso.read-profile-app/null-arm-ids))
        "three, because one null cannot say whether what it reads is
         positional")))

;; ===========================================================================
;; 2 — the published slots did not move
;; ===========================================================================

(deftest the-nulls-were-appended-and-nothing-was-reordered
  (testing "the nine arms rf2-3l6hf published hold the slots it published
           them at. Their deltas are quoted as slot-1-against-slot-N, so
           moving one silently re-labels a number that is already in a
           committed transcript"
    (is (= [:commit :c-local :c-null :c-noactivate :c-nowatch
            :c-nosub :c-noreaders :c-nomap :b-build]
           (subvec rf.bench.hicasso.read-profile-app/phase-b-arm-ids 0 9))))

  (testing "and the two added nulls sit at HIGHER slots than the null they
           join, which is what let the roster grow without a reorder"
    (is (< (slot-of :c-null) (slot-of :c-null-twin)))
    (is (< (slot-of :c-null) (slot-of :c-null-curve)))))

;; ===========================================================================
;; 3 — the footprint is READ OUT of the schedule, not restated beside it
;; ===========================================================================

(deftest the-footprint-comes-from-the-schedule-itself
  (let [{:keys [sampling]} (rf.bench.hicasso.read-profile-app/phase-b-shape)
        {:keys [warmup samples]} sampling
        n (count rf.bench.hicasso.read-profile-app/phase-b-arm-ids)]

    (testing "an arm's position at a kept sample is its index in that
             sample's `rf.bench.hicasso.lane/slot-order`, arm for arm"
      (doseq [s (range warmup (+ warmup samples))]
        (let [order (rf.bench.hicasso.lane/slot-order n s)]
          (doseq [slot (range n)]
            (is (= (nth (rf.bench.hicasso.read-profile-app/slot-positions n slot sampling) (- s warmup))
                   (first (keep-indexed (fn [p j] (when (= j slot) p)) order)))
                (str "slot " slot " at sample " s))))))

    (testing "so at every kept sample the arms occupy positions 0..n-1
             exactly once between them — a permutation, which is a
             property of the plan and not of any transcription of it"
      (doseq [s (range warmup (+ warmup samples))]
        (is (= (set (range n))
               (into #{}
                     (map (fn [slot]
                            (nth (rf.bench.hicasso.read-profile-app/slot-positions n slot sampling) (- s warmup))))
                     (range n)))
            (str "sample " s))))

    (testing "every arm is sampled the same number of times, so no
             footprint is longer than another's"
      (is (= #{samples}
             (into #{} (map (fn [{:keys [positions]}] (count positions)))
                   (rf.bench.hicasso.read-profile-app/phase-b-slot-plan)))))))

;; ===========================================================================
;; 4 — THE LAYOUT: three nulls, three footprints, three questions
;; ===========================================================================

(deftest the-three-nulls-sit-on-three-different-footprints
  (let [base      (footprint :c-local)
        twin      (footprint :c-null-twin)
        curve     (footprint :c-null-curve)
        displaced (footprint :c-null)]

    (testing "THE TWIN IS c-local's FOOTPRINT EXACTLY — every position, not
             merely the mean. That is the whole of its value: a cost that
             is any function at all of sweep position cancels term by term
             between these two arms, so what the pair reads is error that
             position cannot explain"
      (is (= (:positions base) (:positions twin))
          "if these differ, `c-null-twin` is at the wrong slot and the
           transcript's claim about it is false")
      (is (= (:mean-position base) (:mean-position twin)))
      (is (not= (:slot base) (:slot twin))
          "and it is a genuinely different slot, not the same arm twice"))

    (testing "THE CURVE shares the mean position and not the footprint, so
             a linear within-sweep drift cancels here and a curved one
             survives — which is the only thing that separates those two
             once the twin has spoken"
      (is (= (:mean-position base) (:mean-position curve)))
      (is (not= (:positions base) (:positions curve))
          "equal footprints here would make this null a second twin and
           the curvature question unasked"))

    (testing "THE DISPLACED null — the pair rf2-3l6hf published +0.022 on
             — differs from c-local in both mean and shape, and is left
             exactly where it was so the two windows can be read against
             each other"
      (is (not= (:mean-position base) (:mean-position displaced)))
      (is (not= (:positions base) (:positions displaced))))

    (testing "so the three nulls are three questions rather than one asked
             three times"
      (is (= 3 (count (into #{} (map :positions) [displaced twin curve])))))))

;; ===========================================================================
;; 5 — why the question is worth asking: the published rig's null WAS
;;     positionally displaced
;; ===========================================================================

(deftest the-published-nine-arm-null-differenced-two-different-footprints
  (let [sampling (:sampling (rf.bench.hicasso.read-profile-app/phase-b-shape))]

    (testing "this row is about the NINE-arm rig rf2-3l6hf ran, and it is
             only about that rig while the sampling is the one it used"
      (is (= {:warmup 2 :samples 8} sampling)
          "if this fails the row below describes a rig that never ran, and
           it should be re-derived rather than adjusted"))

    (testing "at nine arms `c-local` sat on slot 1 and `c-null` on slot 2,
             and those two slots do NOT share a footprint — so the +0.022
             the window reported was measured across a positional
             difference, which is exactly the confound the new nulls exist
             to resolve"
      (let [a (rf.bench.hicasso.read-profile-app/slot-footprint 9 1 sampling)
            b (rf.bench.hicasso.read-profile-app/slot-footprint 9 2 sampling)]
        (is (not= (:positions a) (:positions b)))
        (is (= 4.5 (:mean-position a)))
        (is (= 3.375 (:mean-position b))
            "the null's arm sat over a mean position more than a slot
             earlier in the sweep than the arm it was differenced against,
             in every round of every run")))))
