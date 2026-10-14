(ns re-frame2-pair-mcp.registry-test
  "The descriptor list and the handler lookup are both derived from
  `registry/tools`. A descriptor whose name disagrees with its entry, or a
  duplicated name, would otherwise ship green and surface only as an
  `:unknown-tool` (or a silently shadowed handler) at the stdio layer."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(deftest handler-for-keys-match-descriptor-names
  ;; Sorted sequences rather than sets, so a duplicated name fails too.
  (is (= (sort (map :name registry/tool-descriptors))
         (sort (keys registry/handler-for)))))
