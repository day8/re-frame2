(ns day8.re-frame2-xray.panels-e2e.twenty-event-load-e2e-cljs-test
  "Multi-frame e2e port of the Xray feature-matrix Playwright scenarios
  `20-event feature/load re-check` and `20-event launch-mode shared
  runtime re-check` (rf2-rviu8).

  The Playwright originals (`scenarios.cjs::runTwentyEventLoad`,
  `runLaunchModesTwentyEventLoad`) drove 20 host `+/-` button clicks on
  the counter testbed, opened the Xray shell, and asserted:

    1. The trace count grew (proves dispatch reached the trace bus).
    2. The focused-epoch panel default-focused the head cascade after
       load (proves the spine pipeline emitted routable cascades).
       Originally the Event/Handler panel; rf2-5gl5r migrated the
       surface to the Epoch panel.
    3. The focused-event-bundle overlay state synchronises across launch
       modes (overlay vs popout).

  At the data layer — which is what the bug class actually probes —
  none of that needs a browser. With the rf2-7icrs multi-frame harness
  the same chain runs in <10 ms per assertion:

    host counter/inc x20  →  trace-bus mirror  →  Xray
      :rf.xray/event-bundles count grows
      :rf.xray/focus auto-follows head
      :rf.xray/epoch-history grows by ~20

  ## Why we still keep DOM coverage out of scope here

  The popout-launch-mode (window.open) and the L4 overlay surface are
  DOM-level surfaces — those stay covered by the cross-site Playwright
  smokes (story_play_scripts.cjs + the launch-mode chrome scenario)
  which exercise the actual window.open + overlay tree. This file
  covers the data invariants that broke in rf2-70tkv (panel frozen
  after head-event-bundle flip) and rf2-hwuki (`:frame` tag dropped)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- dispatch-counter-20 []
  (dotimes [i 20]
    (e2e/dispatch-host (if (even? i) [:counter/inc] [:counter/dec]))))

(deftest xray-trace-grows-under-20-dispatch-load
  (testing ":rf.xray/trace-buffer grows by >= 20 after 20 host dispatches"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-xray [:rf.xray/trace-buffer]))]
          (dispatch-counter-20)
          (let [after (count (e2e/sub-xray [:rf.xray/trace-buffer]))]
            (is (<= (+ before 20) after)
                "trace-buffer did not grow by at least 20 events after 20 dispatches — trace cb dropped events")))))))

(deftest xray-cascades-grow-under-20-dispatch-load
  (testing ":rf.xray/event-bundles produces 20 host-frame cascades after 20 dispatches"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (count (e2e/host-cascades-only))]
          (dispatch-counter-20)
          (let [after (count (e2e/host-cascades-only))]
            ;; Each dispatch lands one host-frame cascade (counter/inc
            ;; or counter/dec). The :rf.xray/event-bundles sub filters
            ;; Xray-internal cascades out.
            (is (= (+ before 20) after)
                ":rf.xray/event-bundles did not surface every host dispatch — spine ingestion lost events")))))))

(deftest xray-focus-tracks-final-head-after-load
  (testing "spine focus is on the final dispatch after 20 events (rf2-70tkv panel-frozen regression class)"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (dispatch-counter-20)
        ;; The 20th iteration (i=19) is :counter/dec.
        (let [focused-event (e2e/xray-focused-event)]
          (is (= [:counter/dec] focused-event)
              "spine focus did not advance to the final dispatch — auto-follow broke under load")))
      )))

(deftest xray-epoch-history-grows-under-20-dispatch-load
  (testing ":rf.xray/epoch-history records every dispatched epoch"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-xray [:rf.xray/epoch-history]))]
          (dispatch-counter-20)
          (let [after (count (e2e/sub-xray [:rf.xray/epoch-history]))]
            ;; Each host dispatch lands one epoch. Allow >= so a future
            ;; rev that adds extra recording epochs (Xray-internal) does
            ;; not break the test.
            (is (>= after (+ before 20))
                "epoch-history did not record every dispatched epoch — :frame tag drop or epoch-cb wiring broke")))))))

(deftest xray-app-db-mirrors-after-20-dispatch-load
  (testing ":rf.xray/target-frame-db reflects net counter value after 20 alternating inc/dec"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (dispatch-counter-20)
        ;; Init at 5; 10 inc + 10 dec → net 5.
        (is (= 5 (:counter/value (e2e/sub-xray [:rf.xray/target-frame-db])))
            ":rf.xray/target-frame-db did not see the host's final :counter/value")))))
