(ns re-frame.ssr.ring-rendertime-sub-failclosed-test
  "A reactive sub that throws during the render walk under production
  hardening recovers to nil instead of throwing, buffering a fail-closed 500
  AFTER `ssr-handler` read the response once. The handler re-flushes after the
  render, so the wire carries the projected error page, never a silent 200
  with the recovered-to-nil HTML (Spec 011 §View-time exceptions)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(deftest rendertime-sub-throw-fails-closed-to-500-on-the-wire
  (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
  (rf/reg-view* :pages/uses-throwing-sub
    (fn [] [:main "header that renders" (str @(rf/subscribe [:throwing-sub]))]))
  (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
  (with-redefs [rf.interop/debug-enabled? false]
    (let [{:keys [status body]} ((rf.ssr.ring/ssr-handler
                                   {:initial-events [[:init/ok]]
                                    :root-view      [(rf/view :pages/uses-throwing-sub)]
                                    :payload        :rf.ssr.payload/whole-app-db})
                                 {:uri "/uses-throwing-sub" :request-method :get})]
      (is (= 500 status))
      (is (str/includes? body "Something went wrong")))))
