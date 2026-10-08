(ns re-frame2-pair-mcp.registry-test
  "The descriptor list and the handler lookup are both derived from
  `registry/tools`. A descriptor whose name disagrees with its entry, or a
  duplicated name, would otherwise ship green and surface only as an
  `:unknown-tool` (or a silently shadowed handler) at the stdio layer."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(deftest handler-for-keys-match-descriptor-names
  (is (= (set (map :name registry/tool-descriptors))
         (set (keys registry/handler-for)))))

(deftest registry-names-are-unique
  (is (apply distinct? (map :name registry/tools))))
