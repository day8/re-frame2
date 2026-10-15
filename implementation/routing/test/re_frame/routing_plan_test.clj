(ns re-frame.routing-plan-test
  "Fragment-only classification and frame attribution of fallback telemetry."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.routing.plan :as rf.routing.plan]))

;; ---- fragment-only navigation (Spec 012 §Fragments rules 3-4) ------------

(deftest fragment-only-detects-same-page-anchor-change
  (let [slice {:route-id :route/docs :params {:p 1} :query {:q "a"} :fragment "intro"}]
    (are [id query fragment expected]
         (= expected (rf.routing.plan/fragment-only? slice id {:p 1} query fragment))
      :route/docs {:q "a"} "details" true
      ;; an identical fragment is the complete no-op, not fragment-only
      :route/docs {:q "a"} "intro"   false
      :route/cart {:q "a"} "details" false
      :route/docs {:q "b"} "details" false)))

;; ---- fail-closed telemetry intents ---------------------------------------

(deftest fallback-telemetry-intents-threads-frame-onto-every-tag
  (testing "when :frame is present it lands on every emitted tag map (epoch/Xray attribution)"
    (is (= [[:emit :warning :rf.warning/malformed-url
             {:url "/x" :reason :match-error :frame :worker}]
            [:emit :warning :rf.warning/no-not-found-route
             {:url "/x" :frame :worker}]]
           (rf.routing.plan/fallback-telemetry-intents
             {:throw-reason :match-error :malformed? false :no-not-found? true
              :url "/x" :frame :worker})))))
