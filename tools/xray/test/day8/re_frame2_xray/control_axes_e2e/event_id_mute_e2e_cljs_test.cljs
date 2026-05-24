(ns day8.re-frame2-xray.control-axes-e2e.event-id-mute-e2e-cljs-test
  "Multi-frame e2e coverage for the spine-filters event-id mute control
  axis (rf2-7icrs).

  Right-clicking an L2 row offers 'Mute this event-id' — the
  framework's `spine-filters` surface (rf2-ikuwt, lives at
  `tools/xray/src/day8/re_frame2_xray/spine_filters.cljs`)
  dispatches `:rf.xray/mute-event-id` which adds the event-id to a
  `#{}` of muted ids in `:rf/xray`'s app-db.

  The mute system is distinct from the IN/OUT filter pills:
    - Pills (`:rf.xray/active-filters`) are pattern-based and
      compose across multiple scopes.
    - Mutes (`:rf.xray/muted-event-ids`) are event-id-specific and
      drop cascades wholesale.

  At the e2e level we assert:

    1. `:rf.xray/mute-event-id` writes the event-id into
       `:rf.xray/muted-event-ids`.
    2. `:rf.xray/unmute-event-id` removes it.
    3. `:rf.xray/clear-muted-event-ids` clears the set.
    4. The slot state survives host dispatches (rf2-83d4x wrong-
       frame state class)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-muted-event-ids-defaults-empty
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          ":rf.xray/muted-event-ids should default to an empty set"))))

(deftest xray-mute-event-id-adds-to-set
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-host [:counter/inc])
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
      (let [muted (e2e/sub-xray [:rf.xray/muted-event-ids])]
        (is (contains? muted :counter/inc)
            ":counter/inc not added to :rf.xray/muted-event-ids")))))

(deftest xray-unmute-event-id-removes-from-set
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
      (e2e/dispatch-xray [:rf.xray/unmute-event-id :counter/inc])
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          "unmute did not remove :counter/inc"))))

(deftest xray-clear-muted-clears-set
  (e2e/with-host-and-xray-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
      (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/dec])
      (e2e/dispatch-xray [:rf.xray/clear-muted-event-ids])
      (is (empty? (e2e/sub-xray [:rf.xray/muted-event-ids]))
          "clear-muted did not clear the set"))))

(deftest xray-mute-state-survives-host-dispatch
  (testing "rf2-83d4x — muted-event-ids lives in :rf/xray, not host"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-xray [:rf.xray/mute-event-id :counter/inc])
        (e2e/dispatch-host [:counter/inc])
        (is (contains? (e2e/sub-xray [:rf.xray/muted-event-ids]) :counter/inc)
            "mute set cleared on host dispatch — wrong-frame state class")))))
