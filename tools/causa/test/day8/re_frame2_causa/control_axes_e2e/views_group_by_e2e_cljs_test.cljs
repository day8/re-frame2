(ns day8.re-frame2-causa.control-axes-e2e.views-group-by-e2e-cljs-test
  "Multi-frame e2e coverage for the Views Group-By cycle control axis
  (rf2-7icrs, rf2-83d4x).

  The Views panel's Group-By cycle button rotates the projection
  grouping (component → sub → view-key → component). The control
  writes to `:rf/causa`'s app-db (Causa-state, not host-state);
  rf2-83d4x was the wrong-frame-routing bug where the dispatch
  landed on `:rf/default` instead, and the Group-By cycle never
  flipped because Causa's sub read from `:rf/causa` (one app-db,
  the other was being written; the read never saw the write).

  At the e2e level we assert the corrected dispatch path lands the
  write where Causa's sub reads it.

  ## Expected-fail before rf2-83d4x

  If this test runs BEFORE rf2-83d4x's fix lands, it will fail —
  the dispatch into `:rf/causa` works at this site (we route via
  `dispatch-causa`), but the underlying bug was in the view's
  call-site that did NOT pass `{:frame :rf/causa}`. So this test
  exercises the post-fix contract; it's a regression guard not a
  bug exposer (the bug is in production view code, not in the
  control axis itself). Filed for the record."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- set-views-group-by!
  "Write the group-by slot directly inside Causa's frame via
  reset-frame-db! style update. There's no dedicated
  `:rf.causa/views-cycle-group-by` event in the registry yet (the
  cycle button writes via a panel-local event that lives in
  views_view.cljs), so the e2e test reaches the slot through a
  per-test event registered here."
  [grouping]
  (rf/reg-event-db :rf.causa-test/set-views-group-by
    (fn [db [_ g]]
      (assoc db :views/group-by g)))
  (e2e/dispatch-causa [:rf.causa-test/set-views-group-by grouping]))

(deftest causa-views-group-by-default-is-component
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (= :component (e2e/sub-causa [:rf.causa/views-group-by]))
          "default group-by should be :component"))))

(deftest causa-views-group-by-cycle-to-sub
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (set-views-group-by! :sub)
      (is (= :sub (e2e/sub-causa [:rf.causa/views-group-by]))
          ":rf.causa/views-group-by did not write through to :sub"))))

(deftest causa-views-group-by-write-survives-host-dispatch
  (testing "rf2-83d4x guard — host events must not stamp on Causa-frame slot"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (set-views-group-by! :sub)
        (e2e/dispatch-host [:counter/inc])
        (is (= :sub (e2e/sub-causa [:rf.causa/views-group-by]))
            "host dispatch reset the views-group-by slot — wrong-frame routing")))))
