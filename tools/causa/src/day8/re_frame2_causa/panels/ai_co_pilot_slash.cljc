(ns day8.re-frame2-causa.panels.ai-co-pilot-slash
  "Slash-command catalogue and parser for the AI Co-Pilot."
  (:require [clojure.string :as str]))

(def slash-commands
  "The 8 slash commands from spec/009-AI-CoPilot.md."
  [{:command :explain
    :usage   "/explain <event-id-or-epoch>"
    :doc     "Describe one epoch — the cause, the effect, the state delta."}
   {:command :diff
    :usage   "/diff <epoch-a> <epoch-b>"
    :doc     "What changed between two epochs?"}
   {:command :find
    :usage   "/find <pattern>"
    :doc     "Search the trace stream."}
   {:command :rewind
    :usage   "/rewind <event-or-epoch>"
    :doc     "Propose a rewind (executes only on user confirmation)."}
   {:command :state
    :usage   "/state <machine-id>"
    :doc     "Describe a machine's current state."}
   {:command :why
    :usage   "/why <epoch>"
    :doc     "Causal-ancestor walk."}
   {:command :whatif
    :usage   "/whatif <hypothetical>"
    :doc     "Speculative reasoning (the model labels the answer as reasoning)."}
   {:command :clear
    :usage   "/clear"
    :doc     "Clear conversation."}])

(def slash-command-set
  "Recognised slash-command keywords."
  (into #{} (map :command slash-commands)))

(defn parse-slash-command
  "Parse a known slash command into `{:command :args :raw}` or nil."
  [input]
  (when (and (string? input)
             (str/starts-with? (str/triml input) "/"))
    (let [trimmed (str/triml input)
          [head & rest-toks] (str/split trimmed #"\s+")
          command (some-> head (subs 1) not-empty keyword)]
      (when (contains? slash-command-set command)
        {:command command
         :args    (vec rest-toks)
         :raw     input}))))

(defn slash-popover-matches
  "Return slash command rows matching a partial slash input."
  [input]
  (when (and (string? input)
             (str/starts-with? (str/triml input) "/"))
    (let [trimmed (str/triml input)
          stem    (-> (subs trimmed 1)
                      (str/split #"\s+")
                      first
                      (or ""))]
      (if (= "" stem)
        slash-commands
        (filterv (fn [{:keys [command]}]
                   (str/starts-with? (name command) stem))
                 slash-commands)))))
