(ns re-frame.ssr-request-durable-fact-test
  "The ambient `:rf.server/request` read is unrecorded, so a durable
  request-derived fact must arrive as event payload or a provided recordable
  `:rf.cofx` leaf (Spec 011 §Durable request-derived facts) — and the raw
  request never rides the causal token."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(deftest ambient-request-read-is-not-recorded-on-the-token
  (let [server-frame (rf.frame/make-anon-frame-record! {:platform :server})
        seen-cofx    (atom ::unset)]
    (rf.ssr/set-request! server-frame {:request-method :get :uri "/x"
                                       :headers {"cookie" "session=raw-secret"}})
    (rf/reg-event :req/inspect-record
      {:platforms        #{:server}
       :rf.cofx/requires [:rf.server/request]}
      (fn [ctx _]
        (reset! seen-cofx (:rf.cofx ctx))
        {}))
    (rf/dispatch-sync [:req/inspect-record] {:frame server-frame})
    (is (= [true false]
           [(map? @seen-cofx) (str/includes? (pr-str @seen-cofx) "raw-secret")])
        "[a :rf.cofx record exists, the request rides it]")))
