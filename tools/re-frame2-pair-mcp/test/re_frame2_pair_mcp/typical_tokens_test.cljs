(ns re-frame2-pair-mcp.typical-tokens-test
  "Every descriptor `tools/list` ships carries a positive-integer
  `typicalTokens` hint (spec/003-Tool-Catalogue.md §Universal:
  `:typicalTokens` on every tool descriptor)."
  (:require [cljs.test :refer-macros [deftest is]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]))

(deftest typical-tokens-survives-js-projection
  (let [descs (array-seq (tools/tool-descriptors-js))]
    (is (seq descs))
    (is (= [] (keep (fn [d]
                      (let [tt (j/get d :typicalTokens)]
                        (when-not (and (integer? tt) (pos? tt)) (j/get d :name))))
                    descs))
        "tools whose typicalTokens is missing or not a positive integer")))
