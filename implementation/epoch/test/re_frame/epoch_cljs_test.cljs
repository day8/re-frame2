(ns re-frame.epoch-cljs-test
  "CLJS coverage for epoch behaviour no JVM suite reaches. The epoch runtime is
  `.cljc` and the JVM suites drive it; what lives here is the CLJS-lane path
  from a live `reg-event :schema` violation into the epoch record.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Publishes the epoch late-bind hooks this test reads through.
            [re-frame.epoch]
            ;; Schemas + the Malli adapter so a `:schema`-bearing reg-event's
            ;; `:where :event` violation actually fires (without the adapter the
            ;; default validator soft-passes).
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Mirrors the live wiring of the standard_epochs example: an app-db schema for
;; the frame PLUS a reg-event carrying an inline `:schema`. Capture drops any
;; trace whose tags lack `:frame`, so a `:where :event` violation emitted
;; without its frame reaches the global trace stream yet never lands in the
;; epoch record Xray's Issues / Schema-timeline lens reads.
(deftest event-args-violation-captured-in-epoch-trace-events-cljs
  (testing "a plain reg-event :schema violation lands in the triggering
            epoch's :trace-events"
    (rf/reg-app-schema [:auth] [:map [:token :string]])
    (rf/reg-event :lo28u/bad-event-args
      {:schema [:cat [:= :lo28u/bad-event-args] pos-int?]}
      (fn [{:keys [db]} _ev] {:db (assoc db :baseline 1)}))
    (rf/dispatch-sync [:lo28u/bad-event-args "not-a-number"])
    (is (= 1 (count (filter #(and (= :rf.error/schema-validation-failure
                                     (:operation %))
                                  (= :event (-> % :tags :where)))
                            (:trace-events (last (rf/epoch-history :rf/default))))))
        "the :where :event violation is captured in THIS epoch's :trace-events")))
