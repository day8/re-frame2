(ns re-frame-pair2-mcp.typical-tokens-test
  "Sanity check for rf2-6sddv — every tool descriptor in pair2-mcp's
  `tool-descriptors` carries a positive-integer `:typicalTokens` hint
  and that hint survives the `clj->js` projection in
  `tool-descriptors-js` (so `tools/list` consumers see it on the wire
  as `typicalTokens`).

  `:typicalTokens` is informational only — a ballpark of the
  response-payload size in tokens that AI clients use to budget calls
  and pick size-conscious args (`max-tokens`, `cache`, `cursor`)
  without trial-and-error. Not a cap; real budgets are enforced
  elsewhere."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame-pair2-mcp.tools :as tools]))

(deftest every-descriptor-carries-positive-integer-typical-tokens
  (testing "tool-descriptors: every entry has a positive-integer :typicalTokens"
    (doseq [d tools/tool-descriptors]
      (is (integer? (:typicalTokens d))
          (str "missing :typicalTokens on " (:name d)))
      (is (pos? (:typicalTokens d))
          (str "non-positive :typicalTokens on " (:name d))))))

(deftest typical-tokens-survives-js-projection
  (testing "tool-descriptors-js surfaces typicalTokens to the wire"
    (let [js-arr (tools/tool-descriptors-js)
          n      (alength js-arr)]
      (is (pos? n) "at least one descriptor exists")
      (doseq [i (range n)]
        (let [desc (aget js-arr i)
              tt   (j/get desc :typicalTokens)
              name (j/get desc :name)]
          (is (number? tt)
              (str "missing typicalTokens on tool " name))
          (is (and (integer? tt) (pos? tt))
              (str "non-positive-integer typicalTokens on tool " name)))))))
