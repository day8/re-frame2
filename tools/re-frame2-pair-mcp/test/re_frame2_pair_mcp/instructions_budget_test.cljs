(ns re-frame2-pair-mcp.instructions-budget-test
  "Budget guard for the onboarding prose (`instructions-text` in
  `tools/get_re_frame2_pair_instructions.cljs`). The response egresses
  through the wire cap like any other, so once it exceeds
  `default-max-tokens` an agent's first call returns an overflow marker
  and no onboarding text. Other suites would go red too, but none of them
  names the cause; this one names the budget, the usage and the margin.

  Both checks measure the real handler result with the production
  `cap/sum-payload-tokens` and `cap/apply-cap`, so there is no second copy
  of the token arithmetic to drift. The prose rides the wire twice (the
  EDN text slot and the structuredContent JSON), so one character of
  prose costs ~0.5 tokens."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private tool-name "get-re-frame2-pair-instructions")

;; A var rather than an inline `(registry/handler-for ...)` call: inside
;; an `async` body that call compiles to an awaited IIFE, which hands
;; `.then` the resolved value instead of the promise.
(def ^:private instructions-handler (registry/handler-for tool-name))

(def ^:private hint-tolerance-fraction 0.10)

(deftest instructions-response-matches-its-size-hint-and-budget
  (async done
    (-> (instructions-handler nil nil nil)
        (.then (fn [result]
                 (let [tokens (cap/sum-payload-tokens result)
                       hint   (or (some #(when (= tool-name (:name %)) (:typicalTokens %))
                                        registry/tool-descriptors)
                                  0)
                       budget cap/default-max-tokens]
                   (is (<= (js/Math.abs (- hint tokens))
                           (* hint-tolerance-fraction tokens))
                       (str "advertised " hint " tokens; measured " tokens))
                   (is (identical? result (cap/apply-cap result {:tool tool-name :cap budget}))
                       (str "instructions exceed the " budget "-token wire budget")))))
        (.catch (fn [e] (is false (str "instructions handler rejected: " (.-message e)))))
        (.then (fn [_] (done))))))
