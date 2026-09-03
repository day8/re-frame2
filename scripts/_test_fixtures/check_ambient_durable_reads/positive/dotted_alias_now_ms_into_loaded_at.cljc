(ns fixtures.dotted-alias-now-ms-into-loaded-at
  "POSITIVE fixture: the SAME anti-pattern as `now_ms_into_loaded_at.cljc`,
  written in the canonical dotted require-alias dialect
  (spec/Conventions.md §Require-alias dialect) that migrated artefacts use.
  A pattern that knew only the bare leaf `interop/` read this as clean and
  waved the durable write through — fail-open, since this gate forbids a
  shape rather than requiring one. Must FLAG (1 finding)."
  (:require [re-frame.interop :as rf.interop]))

(defn install-loaded-entry
  [entry value]
  (assoc entry
         :value     value
         :loaded-at (rf.interop/now-ms)))   ;; FLAGGED: ambient clock into durable field
