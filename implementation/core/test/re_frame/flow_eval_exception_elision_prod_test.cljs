(ns re-frame.flow-eval-exception-elision-prod-test
  "`:rf.error/flow-eval-exception` rides the always-on error-emit substrate,
  so a registered error listener still fires when a flow's `:derive` throws
  under `:advanced` + `goog.DEBUG=false`, where the trace surface is elided
  (Spec 013 §Failure semantics rule 4). Built only by
  `:browser-test-prod-elision`, through `re-frame.prod-elision-runner`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            ;; Publishes the flow late-bind hooks the router reaches at dispatch.
            [re-frame.flows]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

(deftest error-emit-listener-fires-under-prod-on-flow-eval-throw
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener!
      :prod/flow-recorder
      (fn [record] (swap! seen conj record)))
    (rf/reg-event :prod/flow-throw
                  (fn [_ _] {:db {:token "string-value"}}))
    (rf/reg-flow :str-len {:inputs [[:token]] :output-path [:str-len]}
                 (fn [t]
                   (when (string? t)
                     (throw (ex-info "no strings allowed" {})))
                   (count t)))
    (rf/dispatch-sync [:prod/flow-throw])
    (is (= 1 (count @seen)) "the listener fires exactly once")
    (let [r (first @seen)]
      (is (= {:error    :rf.error/flow-eval-exception
              :event    [:prod/flow-throw]
              :event-id :prod/flow-throw
              :frame    :rf/default}
             (select-keys r [:error :event :event-id :frame])))
      (is (number? (:time r)))
      (is (integer? (:elapsed-ms r))
          "the substrate boundary rounds the float performance.now() value"))))
