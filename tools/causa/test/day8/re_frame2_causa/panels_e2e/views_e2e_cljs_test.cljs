(ns day8.re-frame2-causa.panels-e2e.views-e2e-cljs-test
  "Multi-frame e2e coverage for the Views panel (rf2-7icrs, spec/017
  — Views row).

  The Views panel projects `:renders` + `:sub-runs` of the focused
  cascade pair (current + prior epoch) into a three-group layout
  (mounted / re-rendered / unmounted). In a node-test runtime
  WITHOUT a substrate that mounts actual React components, the
  `:renders` slot of every epoch will be empty — but the composite's
  shape MUST still resolve through every layer, the `:frame` slot
  MUST carry the host frame, and `:has-cascade?` MUST flip on once
  an epoch lands.

  This is the multi-frame e2e Views row's minimum bar: we are NOT
  asserting actual render-tracker output (browser-level concern); we
  ARE asserting the sub-graph compiles, resolves, and inherits the
  host frame correctly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(deftest causa-views-data-resolves-shape
  (testing ":rf.causa/views-data composes through every :<- layer"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [data (e2e/sub-causa [:rf.causa/views-data])]
          (is (map? data)
              ":rf.causa/views-data did not return a map")
          (is (contains? data :has-cascade?)
              ":has-cascade? slot missing from views-data shape")
          (is (contains? data :group-by)
              ":group-by slot missing from views-data shape")
          (is (contains? data :frame)
              ":frame slot missing from views-data shape"))))))

(deftest causa-views-tracks-host-frame
  (testing "views-data :frame inherits from the spine's focused cascade"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [data (e2e/sub-causa [:rf.causa/views-data])]
          (is (= :rf/default (:frame data))
              "views-data :frame did not match the host frame — rf2-83d4x wrong-frame class"))))))

(deftest causa-views-group-by-defaults-to-component
  (testing "spec/012 — views-group-by defaults to :component"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (is (= :component (e2e/sub-causa [:rf.causa/views-group-by]))
            "default :views/group-by is not :component")))))
