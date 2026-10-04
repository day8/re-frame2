(ns day8.re-frame2-xray.panels.reactivity.event-filter-pill-reactivity-cljs-test
  "Sub-reactivity guard for the IN/OUT filter-pill mechanism
  (a per-control-action test).

  This file is NOT
  about event-id muting — that distinct mechanism
  (`:rf.xray/mute-event-id` → the `:rf.xray/muted-event-ids` set)
  is owned by `control-axes-e2e/event-id-mute-e2e-cljs-test`. Here we
  cover the pattern-based pill filter: `:rf.xray/add-filter :out
  <pill>` adds an `:out`-bucket pill to `:rf.xray/active-filters`.
  The recomposed event list is `filters/right-click-integration-cljs-test`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

(deftest active-filters-sub-tracks-add-filter
  (testing "`:rf.xray/add-filter` adds a pill to the
            active-filters slot; the `:rf.xray/active-filters` sub
            re-fires with the new bucket contents."
    (h/setup-xray-frame!)
    (let [filters-0 (h/read-sub :rf.xray/active-filters)]
      (is (= {:in [] :out []} filters-0)
          "default state — both buckets empty")
      (h/dispatch-xray!
        [:rf.xray/add-filter :out
         {:pattern :evt/inc}])
      (let [filters-1 (h/read-sub :rf.xray/active-filters)]
        (is (= 1 (count (:out filters-1)))
            "one pill added to :out bucket")
        (is (= :evt/inc (-> filters-1 :out first :pattern))
            "pill carries the filter pattern")
        (is (not= filters-0 filters-1)
            "active-filters sub re-fired on add-filter")))))

