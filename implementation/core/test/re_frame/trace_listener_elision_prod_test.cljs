(ns re-frame.trace-listener-elision-prod-test
  "Spec 009 §Production builds: under `:advanced` + `goog.DEBUG=false` the
  `re-frame.interop/debug-enabled?` gate is constant-folded, so `emit!` is a
  no-op and a registered listener never fires, while dispatch itself still
  runs. The ring half is `re-frame.trace-bus-elision-prod-test`.

  Built only by `:browser-test-prod-elision` (the `-elision-prod-test`
  suffix); `:browser-test` and `:node-test` select other suffixes."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(deftest registered-listener-does-not-fire-under-prod
  (testing "dispatch runs the handler; the listener observes nothing"
    (let [seen (atom [])]
      (rf.trace.tooling/register-listener! ::prod-no-trace (fn [ev] (swap! seen conj ev)))
      (rf/reg-event :prod/ping (fn [{:keys [db]} _] {:db (assoc db :pinged? true)}))
      (rf/dispatch-sync [:prod/ping])
      (is (= true (:pinged? (rf/app-db-value :rf/default))) "control: the handler ran")
      (is (empty? @seen))
      (rf.trace.tooling/unregister-listener! ::prod-no-trace))))

(deftest emit-direct-call-is-noop-under-prod
  (testing "the gate sits inside emit! itself, so a direct call is elided too"
    (let [seen (atom [])]
      (rf.trace.tooling/register-listener! ::direct-emit (fn [ev] (swap! seen conj ev)))
      (rf.trace/emit! :info :rf.prod-test/direct-emit {:should "never appear"})
      (is (empty? @seen))
      (rf.trace.tooling/unregister-listener! ::direct-emit))))
