(ns day8.re-frame2-causa.panels-e2e.trace-e2e-cljs-test
  "Multi-frame e2e coverage for the Trace panel (rf2-7icrs, spec/017
  — Trace row).

  The Trace panel projects the raw trace buffer through 13 filter
  axes (per `re-frame.trace.tooling/trace-buffer` opts). At the e2e
  level we assert:

    1. The buffer mirrors host emissions (count > 0 after one host
       dispatch — proves the trace cb is wired).
    2. The buffer carries an `:event/dispatched` event with the
       expected `:tags :event-id`.
    3. The `:rf.causa/trace-feed` composite sub returns a map shape
       (composes through the projection layer cleanly)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-causa.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest causa-trace-buffer-mirrors-host-emissions
  (testing "host dispatch produces trace events in Causa's buffer"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (let [before (count (e2e/sub-causa [:rf.causa/trace-buffer]))]
          (e2e/dispatch-host [:counter/inc])
          (let [after (count (e2e/sub-causa [:rf.causa/trace-buffer]))]
            (is (< before after)
                "trace-buffer did not grow after host dispatch — trace cb not wired")))))))

(deftest causa-trace-buffer-carries-event-dispatched
  (testing ":event/dispatched event with host's event vector appears in buffer"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [buffer      (e2e/sub-causa [:rf.causa/trace-buffer])
              dispatched  (filter #(= :event/dispatched (:operation %)) buffer)
              ;; Per Spec 009 §Event envelope: :event/dispatched carries
              ;; the full event vector under :tags :event (not :event-id —
              ;; that lands on later op-types like :event with :phase
              ;; :run-start).
              counter-inc (filter #(= [:counter/inc] (get-in % [:tags :event])) dispatched)]
          (is (pos? (count counter-inc))
              "no :event/dispatched trace event carries [:counter/inc]"))))))

(deftest causa-trace-feed-resolves-shape
  (testing ":rf.causa/trace-feed composite returns map shape"
    (e2e/with-host-and-causa-frames
      {:install-host counter/install-and-init!}
      (fn []
        (e2e/dispatch-host [:counter/inc])
        (let [feed (e2e/sub-causa [:rf.causa/trace-feed])]
          (is (map? feed)
              ":rf.causa/trace-feed did not return a map"))))))
