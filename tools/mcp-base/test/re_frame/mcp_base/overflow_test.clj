(ns re-frame.mcp-base.overflow-test
  "Pins the documented default cap. The token-estimate rule and the
  marker shape are pinned through `apply-cap` in cap_test."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.mcp-base.overflow :as rf.mcp-base.overflow]))

(deftest default-max-tokens-pinned
  (is (= 5000 rf.mcp-base.overflow/default-max-tokens)))
