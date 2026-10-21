(ns re-frame.resources.transport
  "The transport-neutral seam between the resource runtime and its single
  built-in transport, `:rf.http/managed`. The HTTP lowering lives in
  `re-frame.resources.transport.http`. There is no dispatch table: with one
  transport it would have one live arm. Spec 016 §Transport.")

#?(:clj (set! *warn-on-reflection* true))

(def managed-http-transport
  "The only built-in transport id. Spec 016 §Transport."
  :rf.http/managed)
