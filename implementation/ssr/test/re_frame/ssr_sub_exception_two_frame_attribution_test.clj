(ns re-frame.ssr-sub-exception-two-frame-attribution-test
  "A reactive sub that throws under production hardening fails closed to 500
  on its OWN frame's response, through the always-on error axis, with a
  sibling server frame live and untouched.

  This drives the sub before settling the response, the inverse of the Ring
  handler's order, so it proves the attribution layer and not the wire; the
  wire contract is `re-frame.ssr.ring-rendertime-sub-failclosed-test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- server-frame [frame-id]
  (rf/make-frame {:id frame-id :platform :server
                  :ssr {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}})
  frame-id)

(deftest two-server-frames-sub-exception-fails-closed-on-emitting-frame-only
  (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
  (rf/reg-sub :clean-sub (fn [_db _] :ok))
  (let [fa (server-frame :ssr/sub-req-a)
        fb (server-frame :ssr/sub-req-b)]
    (with-redefs [rf.interop/debug-enabled? false]
      (rf/subscribe-once [:throwing-sub] {:frame fa})
      (rf/subscribe-once [:clean-sub] {:frame fb})
      (is (= [500 200] [(:status (:response (rf.ssr/flush-response-result! fa)))
                        (:status (:response (rf.ssr/flush-response-result! fb)))])))))
