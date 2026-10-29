(ns re-frame2-pair-mcp.descriptor-budget-test
  "Budget guard for the tool descriptions `tools/list` ships. Every session
  that loads the catalogue pays for every description before its first
  call, so each one stays the tool's call contract and argument detail goes
  on the argument's own `:description` (spec/003-Tool-Catalogue.md
  §Universal: a tool's `:description` is its call contract)."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private max-description-chars 2000)

(deftest every-description-fits-its-budget
  (is (= [] (keep (fn [{:keys [name description]}]
                    (when (< max-description-chars (count description))
                      [name (count description)]))
                  registry/tool-descriptors))
      (str "tool descriptions over the " max-description-chars "-character budget")))
