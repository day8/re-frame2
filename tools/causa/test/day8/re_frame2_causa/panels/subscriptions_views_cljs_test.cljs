(ns day8.re-frame2-causa.panels.subscriptions-views-cljs-test
  "Per-leaf smoke test for `subscriptions-views` (rf2-nb8if).

  Renders `subscriptions-panel` (a plain Reagent fn per the
  canonical facade convention — the public `reg-view` lives in the
  facade) and asserts the top-level data-testid hook."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.subscriptions-subs :as subs]
            [day8.re-frame2-causa.panels.subscriptions-views :as views]))

(defn- expand-fn [node]
  (if (and (vector? node) (fn? (first node)))
    (try (apply (first node) (rest node))
         (catch :default _ node))
    node))

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (->> (tree-seq (some-fn vector? seq?) seq (expand-fn tree))
             (map expand-fn))))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest subscriptions-panel-renders-with-testid
  (subs/install!)
  (frame/reg-frame :rf/causa {})
  (is (has-testid? (views/subscriptions-panel)
                   "rf-causa-subscriptions")
      "the root :section data-testid is present"))
