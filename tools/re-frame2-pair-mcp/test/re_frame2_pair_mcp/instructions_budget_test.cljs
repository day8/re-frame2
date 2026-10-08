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

(def ^:private hint-tolerance-fraction
  "How far the shipped `:typicalTokens` may sit from the measured
  response: wide enough for a round number, narrow enough to catch a
  hint a substantial prose edit left behind."
  0.10)

(deftest instructions-response-advertises-its-real-size
  ;; This response is a fixed string with no narrowing args, so its
  ;; `:typicalTokens` is knowable exactly; a LOW hint under-provisions
  ;; a client's budget for the first call of a session.
  (async done
    (-> (instructions-handler nil nil nil)
        (.then (fn [result]
                 (let [tokens (cap/sum-payload-tokens result)
                       hint   (or (some #(when (= tool-name (:name %)) (:typicalTokens %))
                                        registry/tool-descriptors)
                                  0)]
                   (is (<= (js/Math.abs (- hint tokens))
                           (* hint-tolerance-fraction tokens))
                       (str tool-name " advertises :typicalTokens " hint
                            " but its response measures " tokens " tokens. FIX: set "
                            ":typicalTokens in tools/descriptors_data.cljs to within "
                            (int (* 100 hint-tolerance-fraction)) "% of " tokens ".")))))
        (.catch (fn [e]
                  (is false (str tool-name " handler rejected: " (.-message e)))
                  nil))
        (.then (fn [_] (done))))))

(deftest instructions-response-fits-the-wire-token-budget
  (async done
    (-> (instructions-handler nil nil nil)
        (.then (fn [result]
                 (let [tokens    (cap/sum-payload-tokens result)
                       budget    cap/default-max-tokens
                       ;; Identity is the exact question "would the wire
                       ;; boundary have replaced this?", collapsed to a
                       ;; boolean so a failure prints the numbers rather
                       ;; than two ~10 KB envelopes.
                       replaced? (not (identical? result (cap/apply-cap result {:tool tool-name :cap budget})))]
                   (is (false? replaced?)
                       (str tool-name " is OVER its wire token budget.\n"
                            "  usage : " tokens " tokens\n"
                            "  budget: " budget " tokens\n"
                            "  margin: " (- budget tokens) " tokens\n"
                            "FIX: shorten `instructions-text` in "
                            "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/"
                            "get_re_frame2_pair_instructions.cljs. Raising "
                            "default-max-tokens, a cross-MCP constant, only defers "
                            "the failure.")))))
        (.catch (fn [e]
                  (is false (str tool-name " handler rejected: " (.-message e)))
                  nil))
        (.then (fn [_] (done))))))
