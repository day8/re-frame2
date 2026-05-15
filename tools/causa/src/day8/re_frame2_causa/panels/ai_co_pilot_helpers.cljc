(ns day8.re-frame2-causa.panels.ai-co-pilot-helpers
  "Stable pure-helper facade for the AI Co-Pilot panel.

  The implementation is split into focused leaves so CLJC helper code
  stays within the panel leaf budget while preserving the historical
  `ai-co-pilot-helpers` namespace used by tests and CLJS views."
  (:require [day8.re-frame2-causa.panels.ai-co-pilot-chips :as chips]
            [day8.re-frame2-causa.panels.ai-co-pilot-conversation-model :as conversation]
            [day8.re-frame2-causa.panels.ai-co-pilot-redaction :as redaction]
            [day8.re-frame2-causa.panels.ai-co-pilot-slash :as slash]))

(def slash-commands slash/slash-commands)
(def slash-command-set slash/slash-command-set)
(def parse-slash-command slash/parse-slash-command)
(def slash-popover-matches slash/slash-popover-matches)

(def chip-key-set chips/chip-key-set)
(def chip-glyphs chips/chip-glyphs)
(def chip-targets chips/chip-targets)
(def parse-streamed-answer chips/parse-streamed-answer)
(def resolve-chip chips/resolve-chip)

(def default-redaction-settings redaction/default-redaction-settings)
(def redact-trace-event redaction/redact-trace-event)
(def redact-app-db redaction/redact-app-db)
(def redact-payload redaction/redact-payload)

(def empty-conversation conversation/empty-conversation)
(def append-question conversation/append-question)
(def start-answer conversation/start-answer)
(def append-token conversation/append-token)
(def end-answer conversation/end-answer)
