(ns re-frame.story-mcp.descriptor-budget-test
  "Budget guard for the tool descriptions `tools/list` ships. Every session
  that loads the catalogue pays for every description before its first
  call, so each one stays the tool's call contract and the long-form
  guidance lives in the spec (spec/002-Tool-Registry.md §A tool's
  `:description` is its call contract)."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.story-mcp.tools.registry :as rf.story-mcp.tools.registry]))

(def ^:private max-description-chars 2000)

(def ^:private max-total-description-chars 12000)

(deftest every-description-fits-its-budget
  (is (= [] (keep (fn [{:keys [name description]}]
                    (when (< max-description-chars (count description))
                      [name (count description)]))
                  rf.story-mcp.tools.registry/tool-registry))
      (str "tool descriptions over the " max-description-chars "-character budget")))

(deftest the-whole-catalogue-fits-its-budget
  (let [total (reduce + (map (comp count :description) rf.story-mcp.tools.registry/tool-registry))]
    (is (<= total max-total-description-chars)
        (str "the registry's descriptions total " total " characters, over the "
             max-total-description-chars "-character budget"))))
