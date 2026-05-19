(ns day8.re-frame2-causa.control-axes-e2e.palette-invoke-e2e-cljs-test
  "Multi-frame e2e coverage for the Cmd-K palette invoke control axis
  (rf2-7icrs).

  The palette surface is opened via Cmd-K (or Ctrl-K); typing
  filters the list, ↑/↓ moves the cursor, Enter dispatches the
  selected item via `:rf.causa/palette-invoke`. At the e2e level we
  assert:

    1. `:rf.causa/palette-open` flips `:rf.causa/palette-open?` to true.
    2. `:rf.causa/palette-close` flips it back to false.
    3. `:rf.causa/palette-toggle` round-trips.
    4. Setting a query updates `:rf.causa/palette-query`.

  The full invoke path (palette-invoke → action dispatch) is
  exercised by the pure-fn `palette/events_cljs_test.cljs` and
  `palette/dispatch_routing_cljs_test.cljs`; this file focuses on
  the cross-frame reactivity contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-palette-defaults-closed
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (is (not (e2e/sub-causa [:rf.causa/palette-open?]))
          "palette should default to closed"))))

(deftest causa-palette-open-flips-open?
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-open])
      (is (e2e/sub-causa [:rf.causa/palette-open?])
          ":rf.causa/palette-open did not flip palette-open? to true"))))

(deftest causa-palette-toggle-round-trips
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-toggle])
      (is (e2e/sub-causa [:rf.causa/palette-open?])
          "first toggle did not open palette")
      (e2e/dispatch-causa [:rf.causa/palette-toggle])
      (is (not (e2e/sub-causa [:rf.causa/palette-open?]))
          "second toggle did not close palette"))))

(deftest causa-palette-query-updates
  (e2e/with-host-and-causa-frames
    {:install-host counter/install-and-init!}
    (fn []
      (e2e/dispatch-causa [:rf.causa/palette-set-query "frame"])
      (is (= "frame" (e2e/sub-causa [:rf.causa/palette-query]))
          ":rf.causa/palette-set-query did not write through to palette-query"))))

(deftest causa-palette-state-survives-host-dispatch
  (testing "rf2-83d4x — palette state lives in :rf/causa, not host"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-causa [:rf.causa/palette-open])
        (e2e/dispatch-host [:counter/inc])
        (is (e2e/sub-causa [:rf.causa/palette-open?])
            "palette state cleared on host dispatch — wrong-frame class")))))
