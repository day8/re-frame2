(ns re-frame.resources.transport
  "The transport-neutral seam between the resource runtime and its single
  built-in transport, `:rf.http/managed`. The HTTP lowering lives in
  `re-frame.resources.transport.http`. There is no dispatch table: with one
  transport it would have one live arm. Spec 016 §Transport."
  (:require [re-frame.error :as rf.error]))

#?(:clj (set! *warn-on-reflection* true))

(def managed-http-transport
  "The only built-in transport id. Spec 016 §Transport."
  :rf.http/managed)

(def default-transport
  "The `:transport` of a resource that declares none.
  Spec 016 §Resource registration spec."
  managed-http-transport)

(defn assert-managed-transport!
  "Returns `transport` when it is `:rf.http/managed`; otherwise throws
  `:rf.error/resource-unknown-transport` (`:recovery :fix-registration`).
  The runtime call sites run it before lowering into `transport.http/lower`.
  Spec 016 §Transport."
  [transport where]
  (when-not (= transport managed-http-transport)
    (rf.error/throw-error!
      :rf.error/resource-unknown-transport (or where 're-frame.resources.transport/assert-managed-transport!)
      (str "unknown resource transport " transport
           " — the only built-in transport is "
           managed-http-transport
           " (Spec 016 §Transport).")
      {:recovery :fix-registration
       :extra    {:transport transport}}))
  transport)
