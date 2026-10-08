(ns re-frame.trace-bus-elision-prod-test
  "Spec 009 §Production builds: under `:advanced` + `goog.DEBUG=false` the
  trace RING is inert — `trace-buffer` reads empty and the `:trace-buffer`
  configure key is a silent no-op. The listener half is
  `re-frame.trace-listener-elision-prod-test`.

  Built only by `:browser-test-prod-elision` (the `-elision-prod-test`
  suffix); under `goog.DEBUG=true` these assertions fail by design."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest dispatched-events-do-not-populate-trace-buffer-under-prod
  (testing "a dispatched handler runs, and nothing reaches the trace ring"
    (rf/reg-event :prod-bus/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-bus/inc])
    (is (or (nil? (rf.trace.tooling/trace-buffer :rf/default))
            (empty? (rf.trace.tooling/trace-buffer :rf/default))))
    (is (= 1 (:n (rf/app-db-value :rf/default))) "control: the handler ran")))

(deftest trace-configure-is-noop-under-prod
  (testing "booting through (configure! {:trace-buffer ...}) does not crash under :advanced"
    (is (nil? (rf/configure! {:trace-buffer {:events-retained 64}})))))
