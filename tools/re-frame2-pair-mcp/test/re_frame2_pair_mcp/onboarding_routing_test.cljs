(ns re-frame2-pair-mcp.onboarding-routing-test
  "Phantom-name guard for the onboarding routing rules: every tool the
  `## Routing rules` section of `instructions-text` names MUST be
  registered, or the agent follows the rule and gets `:unknown-tool`.
  The reverse is not asserted: the rules name some tools by design, not
  all of them.

  Parse contract: inside that section a backticked lowercase-kebab token
  (`^[a-z][a-z0-9-]*$`) is a tool name and nothing else, so keywords,
  slashed names and capitalised words stay free-form. `instructions-text`
  states the same contract where its authors read it."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [clojure.set :as set]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.get-re-frame2-pair-instructions :as instr]))

(defn- routed-tool-names
  "Backticked tool-name tokens in the lines under `## Routing rules`, up
  to the next `## ` heading."
  [text]
  (->> (str/split-lines text)
       (drop-while #(not= "## Routing rules" (str/trim %)))
       (rest)
       (take-while #(not (str/starts-with? % "## ")))
       (mapcat #(re-seq #"`([^`]+)`" %))
       (keep (fn [[_ tok]] (when (re-matches #"[a-z][a-z0-9-]*" tok) tok)))
       (into #{})))

(deftest routing-rules-name-only-registered-tools
  (let [routed (routed-tool-names instr/instructions-text)]
    ;; Non-vacuity: a renamed heading or prose that stopped backticking
    ;; would parse to nothing and pass the phantom check on an empty set.
    (is (>= (count routed) 10)
        (str "the `## Routing rules` section yielded only " (count routed)
             " tool names; the heading or the backtick convention changed"))
    (is (empty? (set/difference routed (set registry/tool-names)))
        "the `## Routing rules` section names tools that are not registered")))
