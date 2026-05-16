(ns day8.re-frame2-causa.panels.subscriptions-chain-model-cljs-test
  "Per-leaf smoke test for `subscriptions-chain-model` (rf2-nb8if).

  Pure CLJC; JVM-runnable. Existing aggregate coverage lives in
  `subscriptions_helpers_cljs_test.cljc` against the helpers
  facade; this file pins the chain-model leaf as an independently
  callable unit."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [day8.re-frame2-causa.panels.subscriptions-chain-model
             :as chain-model]))

(deftest compute-chain-marks-missing-when-cache-empty
  (let [chain (chain-model/compute-chain
                [:cart/total] {} [] {} #{})]
    (is (true? (:missing? chain)))
    (is (nil? (:focused chain)))))

(deftest compute-chain-projects-focused-row-when-present
  (let [cache {[:cart/total] {:layer 2 :ref-count 1 :input-subs []}}
        chain (chain-model/compute-chain
                [:cart/total] cache [] {} #{})]
    (is (false? (:missing? chain)))
    (is (= :cart/total (:sub-id (:focused chain))))
    (is (= 2 (:layer (:focused chain))))))
