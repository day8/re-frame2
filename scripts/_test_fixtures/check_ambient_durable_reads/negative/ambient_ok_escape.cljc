(ns fixtures.ambient-ok-escape
  "NEGATIVE fixture: the conscious-allowlist escape. A genuinely-diagnostic
  ambient read in a durable-write namespace is opted out with the reviewed
  `#_:rf.world/ambient-ok` reader-discard marker in the same window. This is
  the documented way to keep a deliberate diagnostic read without silently
  losing the gate's protection on the rest of the file. Must stay GREEN
  (0 findings)."
  (:require [re-frame.interop :as interop]))

(defn install-entry
  [entry]
  #_:rf.world/ambient-ok   ;; reviewed: diagnostic-only stamp, not consulted for durable writes
  (assoc entry :created-at (interop/now-ms)))
