(ns fixtures.now-ms-into-loaded-at
  "POSITIVE fixture: a resource reply handler reads `interop/now-ms` while
  writing the durable `:loaded-at` field. EP-0010 §The Boundary Is Already
  Visible names this exact anti-pattern. Must FLAG (1 finding)."
  (:require [re-frame.interop :as interop]))

(defn install-loaded-entry
  [entry value]
  (assoc entry
         :value     value
         :loaded-at (interop/now-ms)))   ;; FLAGGED: ambient clock into durable field
