(ns re-frame.event-emit-elision-prod-test
  "The always-on event-emit substrate keeps firing under `:advanced` +
  `goog.DEBUG=false`, where the trace surface is elided: a production
  forwarder registered here still sees every processed event.

  Built only by `:browser-test-prod-elision` (runner
  `re-frame.prod-elision-runner`); the `-elision-prod-test` suffix keeps it out
  of the default runners. The record's outcomes run under the JVM production
  gate in `re-frame.event-emit-cljs-test`, and listener-throw isolation under
  `:advanced` is the shared registry's, pinned by
  `re-frame.on-error-elision-prod-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest event-emit-fires-under-prod
  (let [seen (atom [])]
    (rf.event-emit/register-event-listener! :prod/recorder #(swap! seen conj %))
    (rf/reg-event :prod/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod/inc "payload"])
    (is (= [{:event [:prod/inc "payload"] :event-id :prod/inc :frame :rf/default :outcome :ok}]
           (mapv #(dissoc % :time :elapsed-ms) @seen)))
    (is (number? (:time (first @seen))))
    (is (integer? (:elapsed-ms (first @seen))))))
