(ns day8.re-frame2-causa.panels.reactivity.event-filter-pill-reactivity-cljs-test
  "Sub-reactivity guard for the IN/OUT filter-pill mechanism
  (rf2-dhoc9 per-control-action test).

  Renamed from `event-mute-reactivity` (rf2-dkmnm): this file is NOT
  about event-id muting — that distinct mechanism
  (`:rf.causa/mute-event-id` → the `:rf.causa/muted-event-ids` set)
  is owned by `control-axes-e2e/event-id-mute-e2e-cljs-test`. Here we
  cover the pattern-based pill filter: `:rf.causa/add-filter :out
  <pill>` adds an `:out`-bucket pill to `:rf.causa/active-filters`;
  `:rf.causa/filtered-cascades` recomposes and the L2 event list
  re-renders without the filtered event. This is the unit-level
  mirror of rf2-rwhat Phase 3's right-click flow."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-causa.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(def cascades
  ;; NOTE: `seed-cascades!` derives each cascade's `:event` slot from
  ;; its `:dispatch-id` as `[(keyword "evt" <name>)]`, so the matcher
  ;; sees event-id `:evt/inc` / `:evt/cart` rather than the raw
  ;; dispatch-id keyword. We filter on the derived event-id to mirror
  ;; the production matcher's input.
  [(h/cascade :counter/inc :rf/default)
   (h/cascade :nav/cart :rf/default)])

(deftest active-filters-sub-tracks-add-filter
  (testing "rf2-dhoc9 — `:rf.causa/add-filter` adds a pill to the
            active-filters slot; the `:rf.causa/active-filters` sub
            re-fires with the new bucket contents."
    (h/setup-causa-frame!)
    (let [filters-0 (h/read-sub :rf.causa/active-filters)]
      (is (= {:in [] :out []} filters-0)
          "default state — both buckets empty")
      (h/dispatch-causa!
        [:rf.causa/add-filter :out
         {:pattern :evt/inc :scope #{:event-id}}])
      (let [filters-1 (h/read-sub :rf.causa/active-filters)]
        (is (= 1 (count (:out filters-1)))
            "one pill added to :out bucket")
        (is (= :evt/inc (-> filters-1 :out first :pattern))
            "pill carries the filter pattern")
        (is (not= filters-0 filters-1)
            "active-filters sub re-fired on add-filter")))))

(deftest filtered-cascades-sub-tracks-out-pill
  (testing "rf2-dhoc9 — `:rf.causa/filtered-cascades` recomposes when
            an `:out` pill is added. The filtered event-id's cascade
            falls out of the projected list."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (let [cascades-before (h/read-sub :rf.causa/filtered-cascades)]
      (is (= 2 (count cascades-before))
          "both cascades present before filter")
      (h/dispatch-causa!
        [:rf.causa/add-filter :out
         {:pattern :evt/inc :scope #{:event-id}}])
      (let [cascades-after (h/read-sub :rf.causa/filtered-cascades)]
        (is (= 1 (count cascades-after))
            "filtered cascade dropped from list")
        (is (not= cascades-before cascades-after)
            "filtered-cascades sub re-fired on filter")))))

(deftest remove-filter-restores-cascade
  (testing "rf2-dhoc9 — `:rf.causa/remove-filter` deletes a pill; the
            previously-filtered cascade returns to `:rf.causa/filtered-
            cascades`. Closes the round-trip filter → unfilter
            reactivity contract."
    (h/setup-causa-frame!)
    (h/seed-cascades! cascades)
    (h/dispatch-causa!
      [:rf.causa/add-filter :out
       {:pattern :evt/inc :scope #{:event-id}}])
    (is (= 1 (count (h/read-sub :rf.causa/filtered-cascades)))
        "filter is active")
    (h/dispatch-causa! [:rf.causa/remove-filter :out 0])
    (is (= 2 (count (h/read-sub :rf.causa/filtered-cascades)))
        "unfilter → cascade returns to the filtered list")))
