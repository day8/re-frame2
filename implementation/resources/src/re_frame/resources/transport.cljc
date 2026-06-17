(ns re-frame.resources.transport
  "Transport-neutral seam between the resource runtime and the concrete
  read transport. Per Spec 016 §Transport.

  The initial scope ships a SINGLE built-in transport — `:rf.http/managed`
  (Spec 014). The resource lifecycle, cache identity, owner model,
  stale/fresh policy, invalidation, SSR hydration, and Xray surfaces are
  nonetheless transport-NEUTRAL: the core assumes no URL, HTTP method,
  status code, or body, so the deferred GraphQL transport (and any later
  transport) can plug in without weakening the core semantics. This
  namespace owns the neutral surface the runtime depends on: the
  `default-transport` constant the registration path defaults to, and the
  `assert-managed-transport!` registration-time misconfig guard. The HTTP
  lowering itself lives in the sibling `re-frame.resources.transport.http`,
  which the runtime call sites lower into directly after the guard.

  There is intentionally NO dispatch table here: with a single built-in
  transport a dispatch indirection has exactly one live arm, so the runtime
  guards the declared transport at the call site and lowers into
  `transport.http/lower`. A real dispatch table is reintroduced only when a
  second transport (the deferred GraphQL one) lands — at which point the
  guard becomes the dispatch."
  (:require [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

(def managed-http-transport
  "The only initial-scope transport id (`:rf.http/managed`, Spec 014).
  Per Spec 016 §Transport."
  :rf.http/managed)

(def default-transport
  "The default `:transport` for a resource that does not declare one —
  managed HTTP. Per Spec 016 §Resource registration spec (optional
  `:transport`, initial scope: `:rf.http/managed`, the only built-in)."
  managed-http-transport)

(defn assert-managed-transport!
  "Registration-time misconfig guard: the runtime call sites
  (`events`/`mutation-events`) invoke this with a resource/mutation's
  declared `:transport` (defaulted to `default-transport`) BEFORE lowering
  into `transport.http/lower`. The only initial-scope transport is
  `:rf.http/managed`; any other declared transport is a loud
  `:rf.error/resource-unknown-transport` misconfig (`:recovery
  :fix-registration`). Returns `transport` on success.

  Per Spec 016 §Transport: the runtime owns reply addressing and request
  correlation; the internal reply payloads stamp the qualified
  `:rf.frame/id`, `:work/id`, `:resource/key`, `:scope`, and
  `:generation`, and success/failure handlers MUST verify frame + work-id
  + generation before writing (cancellation is an optimization; stale
  suppression is the correctness boundary). When a second transport lands
  this guard becomes a real dispatch over the declared transport."
  [transport where]
  (when-not (= transport managed-http-transport)
    (error/throw-error!
      :rf.error/resource-unknown-transport (or where 're-frame.resources.transport/assert-managed-transport!)
      (str "unknown resource transport " transport
           " — the only initial-scope transport is "
           managed-http-transport
           " (Spec 016 §Transport).")
      {:recovery :fix-registration
       :extra    {:transport transport}}))
  transport)
