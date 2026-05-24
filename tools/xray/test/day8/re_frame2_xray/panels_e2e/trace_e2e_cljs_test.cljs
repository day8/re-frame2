(ns day8.re-frame2-xray.panels-e2e.trace-e2e-cljs-test
  "Multi-frame e2e coverage for the Trace panel (rf2-7icrs, spec/017
  — Trace row).

  The Trace panel projects the raw trace buffer through 13 filter
  axes (per `re-frame.trace.tooling/trace-buffer` opts). At the e2e
  level we assert:

    1. The buffer mirrors host emissions (count > 0 after one host
       dispatch — proves the trace cb is wired).
    2. The buffer carries an `:rf.event/dispatched` event with the
       expected `:tags :event-id`.
    3. The `:rf.xray/trace-feed` composite sub returns a map shape
       (composes through the projection layer cleanly)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest xray-trace-buffer-mirrors-host-emissions
  (testing "host dispatch produces trace events in Xray's buffer"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-xray [:rf.xray/trace-buffer]))]
          (e2e/dispatch-host [:counter/inc])
          (let [after (count (e2e/sub-xray [:rf.xray/trace-buffer]))]
            (is (< before after)
                "trace-buffer did not grow after host dispatch — trace cb not wired")))))))

(deftest xray-trace-buffer-carries-event-dispatched
  (testing ":rf.event/dispatched event with host's event vector appears in buffer"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [buffer      (e2e/sub-xray [:rf.xray/trace-buffer])
              dispatched  (filter #(= :rf.event/dispatched (:operation %)) buffer)
              ;; Per Spec 009 §Event envelope: :rf.event/dispatched carries
              ;; the full event vector under :tags :rf.event/v (not
              ;; :rf.trace/event-id — that lands on later operations like
              ;; :rf.event/run-start).
              counter-inc (filter #(= [:counter/inc] (get-in % [:tags :rf.event/v])) dispatched)]
          (is (pos? (count counter-inc))
              "no :rf.event/dispatched trace event carries [:counter/inc]"))))))

(deftest xray-trace-feed-resolves-shape
  (testing ":rf.xray/trace-feed composite returns map shape"
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [feed (e2e/sub-xray [:rf.xray/trace-feed])]
          (is (map? feed)
              ":rf.xray/trace-feed did not return a map"))))))
