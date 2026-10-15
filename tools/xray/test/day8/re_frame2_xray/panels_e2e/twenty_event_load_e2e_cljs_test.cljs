(ns day8.re-frame2-xray.panels-e2e.twenty-event-load-e2e-cljs-test
  "Repeated event vectors retain distinct cascades and epochs in Xray."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
