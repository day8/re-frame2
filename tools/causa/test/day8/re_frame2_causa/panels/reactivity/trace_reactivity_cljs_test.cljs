(ns day8.re-frame2-causa.panels.reactivity.trace-reactivity-cljs-test
  "Sub-reactivity guard for the Trace panel's primary composite
  (rf2-dhoc9).

  Per the rf2-70tkv affected-panels matrix Trace tracked LIVE
  correctly pre-fix — it pivots on the spine's `:dispatch-id` axis
  through `:rf.causa/trace-feed`'s third input. This test pins that
  cascade-scope rebind so a regression in the trace-feed → focus
  reactive chain would surface in millis."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(deftest trace-feed-tracks-focus-flip
  (testing "rf2-dhoc9 — the trace-feed projection is cascade-scoped
            (rf2-ycoct): only trace rows whose `:dispatch-id` matches
            the spine's focus pass through. Flipping focus between
            cascades must change the projected feed."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [feed-1 (h/read-sub :rf.causa/trace-feed)]
      ;; Sanity — feed is shaped; the cascade-scope filter is applied.
      (is (map? feed-1) "trace-feed returns the projected shape")
      (h/focus-cascade! :c2)
      (let [feed-2 (h/read-sub :rf.causa/trace-feed)]
        (is (not= feed-1 feed-2)
            "trace-feed re-fired on focus flip — the cascade-scope
             dispatch-id axis changed, so the projected rows
             window changed")))))

(deftest trace-feed-cascade-scope-is-the-focused-dispatch-id
  (testing "rf2-dhoc9 — the projection's `:cascade-dispatch-id` option
            is the focused cascade's id. Verify by inspecting the
            projected rows themselves: each cascade's seed event
            carries a distinct `:dispatch-id` tag, so the feed's row
            set differs between focuses."
    (h/setup-causa-frame!)
    ;; Cascade :c1 has its own seed event; cascade :c2 has its own.
    (h/seed-cascades! cascades)
    (h/focus-cascade! :c1)
    (let [feed-c1 (h/read-sub :rf.causa/trace-feed)
          rows-c1 (:rows feed-c1)]
      (h/focus-cascade! :c2)
      (let [feed-c2 (h/read-sub :rf.causa/trace-feed)
            rows-c2 (:rows feed-c2)]
        ;; The feed rows for each cascade should reference the
        ;; cascade's own seed event. The two projections cover
        ;; different events, so the row shapes differ.
        (is (not= rows-c1 rows-c2)
            "projected rows differ across focuses — cascade-scope
             dispatch-id changed with the focus flip")))))
