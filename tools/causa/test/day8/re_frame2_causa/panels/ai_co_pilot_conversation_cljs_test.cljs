(ns day8.re-frame2-causa.panels.ai-co-pilot-conversation-cljs-test
  "Per-leaf smoke test for `ai-co-pilot-conversation` (rf2-nb8if).

  Renders the public `conversation-view` fn over a couple of turns
  and asserts the question / answer data-testid hooks are present."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-causa.panels.ai-co-pilot-conversation :as conversation]
            [day8.re-frame2-causa.panels.ai-co-pilot-helpers :as h]))

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

(deftest conversation-view-renders-empty-tree
  (is (vector? (conversation/conversation-view []))))

(deftest conversation-view-renders-question-and-answer-turns
  (let [conv (-> (h/empty-conversation)
                 (h/append-question "what's up?")
                 (h/start-answer)
                 (h/append-token "all good")
                 (h/end-answer))
        tree (conversation/conversation-view conv)]
    (is (has-testid? tree "rf-causa-copilot-turn-question"))
    (is (has-testid? tree "rf-causa-copilot-turn-answer"))))
