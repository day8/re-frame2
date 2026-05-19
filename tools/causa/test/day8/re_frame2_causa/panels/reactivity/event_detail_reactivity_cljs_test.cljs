(ns day8.re-frame2-causa.panels.reactivity.event-detail-reactivity-cljs-test
  "Sub-reactivity guard for the Event Detail panel's primary composite
  (rf2-dhoc9).

  Per the rf2-70tkv affected-panels matrix Event Detail tracked LIVE
  correctly pre-fix (it pivots on `:rf.causa/focus :dispatch-id`, which
  the spine's `compose-focus` already auto-derived to head). This test
  pins that pre-fix correctness AND guards against future regressions
  in the composite's `:dispatch-id` → cascade lookup."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)
   (h/cascade :c3 :rf/default)])

(deftest event-detail-sub-tracks-focus-flip
  (testing "rf2-dhoc9 — focus flips between three cascades; the
            Event Detail composite's `:selected-dispatch-id` slot
            matches the focused cascade's id at every step. The
            composite re-fires on the focus sub's `:dispatch-id`
            axis (no `:epoch-id` dependency)."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [data-1 (h/read-sub :rf.causa/event-detail)]
      (is (= :c1 (:selected-dispatch-id data-1)))
      (h/focus-cascade! :c2)
      (let [data-2 (h/read-sub :rf.causa/event-detail)]
        (is (= :c2 (:selected-dispatch-id data-2)))
        (is (not= data-1 data-2)
            "event-detail sub re-fired on focus :c1 → :c2 flip")
        (h/focus-cascade! :c3)
        (let [data-3 (h/read-sub :rf.causa/event-detail)]
          (is (= :c3 (:selected-dispatch-id data-3)))
          (is (not= data-2 data-3)
              "event-detail sub re-fired on focus :c2 → :c3 flip"))))))

(deftest event-detail-selected-cascade-is-the-focused-cascade
  (testing "rf2-dhoc9 — the composite's `:selected-cascade` is the
            focused cascade record (not the head fallback). The
            cascade's `:dispatch-id` matches the focused id."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [data (h/read-sub :rf.causa/event-detail)]
      (is (= :c1 (get-in data [:selected-cascade :dispatch-id]))
          "selected-cascade is :c1's record"))
    (h/focus-cascade! :c2)
    (let [data (h/read-sub :rf.causa/event-detail)]
      (is (= :c2 (get-in data [:selected-cascade :dispatch-id]))
          "selected-cascade rebinds to :c2's record"))))

(deftest event-detail-live-mode-tracks-head
  (testing "rf2-dhoc9 — in LIVE mode (no explicit pin) the composite
            tracks the head cascade. A new cascade arrival flips the
            selected-dispatch-id to the new head."
    (h/setup-causa-frame!)
    (h/seed-cascades! [(h/cascade :c1 :rf/default)])
    (let [data-1 (h/read-sub :rf.causa/event-detail)]
      (is (= :c1 (:selected-dispatch-id data-1))
          "head of 1-cascade buffer is :c1"))
    (h/seed-cascades! cascades)
    (let [data-2 (h/read-sub :rf.causa/event-detail)]
      (is (= :c3 (:selected-dispatch-id data-2))
          "rf2-s0s5x Phase A — LIVE auto-tracks the new head :c3"))))
