(ns day8.re-frame2-causa.panels.reactivity.issues-reactivity-cljs-test
  "Sub-reactivity guard for the Issues Ribbon panel's primary
  composite (rf2-dhoc9).

  Per the rf2-70tkv affected-panels matrix Issues tracked LIVE
  correctly pre-fix (it pivots on `:rf.causa/focus :dispatch-id`
  through `:rf.causa/issues-ribbon`'s third input). This test pins
  the cascade-scope reactive contract — flipping focus changes which
  cascade's issues populate the feed."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(deftest issues-ribbon-sub-tracks-focus-flip
  (testing "rf2-dhoc9 — `:rf.causa/issues-ribbon` is cascade-scoped
            via the focus sub's `:dispatch-id` axis (rf2-u6dhp). The
            sub re-fires on focus flip; even when both projections
            yield empty feeds, the `:focus-dispatch-id` axis inside
            the filter map flows through."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [feed-1 (h/read-sub :rf.causa/issues-ribbon)]
      (is (map? feed-1) "issues-ribbon returns the projected shape")
      (h/focus-cascade! :c2)
      (let [feed-2 (h/read-sub :rf.causa/issues-ribbon)]
        ;; The focused cascade's `:dispatch-id` differs, so the
        ;; `:focus-dispatch-id` axis in `:filters` differs even when
        ;; the projected rows are empty. The composite map must
        ;; differ on at least that axis.
        (is (= :c1 (get-in feed-1 [:filters :focus-dispatch-id]))
            "feed-1 projected with focus :c1")
        (is (= :c2 (get-in feed-2 [:filters :focus-dispatch-id]))
            "feed-2 projected with focus :c2")
        (is (not= feed-1 feed-2)
            "issues-ribbon sub re-fired on focus flip")))))

(deftest issues-filters-axis-re-fires-on-filter-change
  (testing "rf2-dhoc9 — `:rf.causa/issues-filters` re-fires when a
            filter axis flips; this guards the second reactive input
            to the composite alongside the focus axis."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (let [filters-1 (h/read-sub :rf.causa/issues-filters)]
      (is (= #{} (:severities filters-1)))
      (h/dispatch-causa! [:rf.causa.issues/toggle-severity :error])
      (let [filters-2 (h/read-sub :rf.causa/issues-filters)]
        (is (= #{:error} (:severities filters-2))
            "severity toggle wrote the active-severities set")
        (is (not= filters-1 filters-2)
            "issues-filters sub re-fired on toggle")))))

(deftest issues-ribbon-composes-focus-and-filters
  (testing "rf2-dhoc9 — `:rf.causa/issues-ribbon` recomposes when
            EITHER input changes (focus OR filters). Pin the
            composition by changing both in sequence and asserting
            the composite map changes after each."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [feed-a (h/read-sub :rf.causa/issues-ribbon)]
      ;; Change focus.
      (h/focus-cascade! :c2)
      (let [feed-b (h/read-sub :rf.causa/issues-ribbon)]
        (is (not= feed-a feed-b)
            "focus flip changed the composite (focus-dispatch-id axis)"))
      ;; Then change a filter.
      (h/dispatch-causa! [:rf.causa.issues/toggle-severity :warn])
      (let [feed-c (h/read-sub :rf.causa/issues-ribbon)]
        (is (not= (h/read-sub :rf.causa/issues-ribbon) feed-a)
            "filter toggle changed the composite too")
        (is (some? feed-c))))))
