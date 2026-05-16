(ns day8.re-frame2-causa.panels.ai-co-pilot-views-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-views` (rf2-nb8if).

  Renders each plain Reagent fn (`ai-co-pilot-rail`,
  `ai-co-pilot-cue`, `ai-co-pilot-view` — the leaf delegate behind
  the facade `Panel`) once and asserts the load-
  bearing data-testid hook is present. The leaf is plain-fn-only
  per the canonical convention — the public `reg-view` lives in
  the facade `ai-co-pilot.cljs`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.ai-co-pilot-subs :as subs]
            [day8.re-frame2-causa.panels.ai-co-pilot-views :as views]))

(defn- expand-fn [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
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

(deftest rail-renders-with-testid
  (subs/install!)
  (frame/reg-frame :rf/causa {})
  (is (has-testid? (views/ai-co-pilot-rail) "rf-causa-copilot-rail")))

(deftest cue-renders-with-testid
  (subs/install!)
  (frame/reg-frame :rf/causa {})
  (is (has-testid? (views/ai-co-pilot-cue) "rf-causa-copilot-cue")))

(deftest view-renders-with-testid
  (subs/install!)
  (frame/reg-frame :rf/causa {})
  (is (has-testid? (views/ai-co-pilot-view) "rf-causa-copilot-panel")))
