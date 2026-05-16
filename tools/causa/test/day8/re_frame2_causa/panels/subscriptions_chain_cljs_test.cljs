(ns day8.re-frame2-causa.panels.subscriptions-chain-cljs-test
  "Per-leaf smoke test for `subscriptions-chain` (rf2-nb8if).

  Renders `chain-view` for the missing and present cases and
  asserts the data-testid hooks the parent panel relies on."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.subscriptions-chain :as chain]))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest chain-view-renders-with-testid-when-missing
  (let [tree (chain/chain-view {:missing? true :focused nil
                                :inputs [] :app-db-paths []})]
    (is (has-testid? tree "rf-causa-subscriptions-chain"))
    (is (has-testid? tree "rf-causa-subscriptions-chain-missing"))))

(deftest chain-view-renders-focused-when-present
  (let [tree (chain/chain-view
               {:missing? false
                :focused {:query-v [:cart/total]
                          :sub-id :cart/total :status :fresh
                          :layer 2 :ref-count 1
                          :input-subs [] :recomputed? false :error nil}
                :inputs []
                :app-db-paths []})]
    (is (has-testid? tree "rf-causa-subscriptions-chain-focused"))))
