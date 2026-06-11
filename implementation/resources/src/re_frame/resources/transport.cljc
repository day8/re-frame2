(ns re-frame.resources.transport
  "Transport-neutral seam between the resource runtime and the concrete
  read transport. Per Spec 016 §Transport.

  The initial scope ships a SINGLE built-in transport — `:rf.http/managed`
  (Spec 014). The resource lifecycle, cache identity, owner model,
  stale/fresh policy, invalidation, SSR hydration, and Xray surfaces are
  nonetheless transport-NEUTRAL: the core assumes no URL, HTTP method,
  status code, or body, so the deferred GraphQL transport (and any later
  transport) can plug in without weakening the core semantics. This
  namespace holds that neutral seam; the HTTP lowering itself lives in the
  sibling `re-frame.resources.transport.http`.

  The transport dispatch lives here; the runtime's ensure/refetch →
  work-ledger-record → managed-HTTP lowering routes through `lower-ensure`
  into the sibling `re-frame.resources.transport.http`."
  (:require [re-frame.resources.transport.http :as http]))

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

(defn lower-ensure
  "Lower an ensure/refetch into the resource's declared transport — the
  transport-neutral entry point the runtime calls after it has created or
  joined a work-ledger record. Dispatches on `transport`; the only
  initial-scope target is `:rf.http/managed` (delegates to
  `re-frame.resources.transport.http/lower`).

  Per Spec 016 §Transport: the runtime owns reply addressing and request
  correlation; the internal reply payloads stamp the qualified
  `:rf.frame/id`, `:work/id`, `:resource-key`, `:scope`, and
  `:generation`, and success/failure handlers MUST verify frame + work-id
  + generation before writing (cancellation is an optimization; stale
  suppression is the correctness boundary). The verification work identity
  is `:work/id` (EP-0007 — the qualified spelling the ledger / entry / reply
  envelope share).

  The runtime supplies the live ensure-context; this fn delegates to the
  transport's lowering."
  [transport ensure-ctx]
  (let [transport (or transport default-transport)]
    (if (= transport managed-http-transport)
      (http/lower ensure-ctx)
      (throw (ex-info ":rf.error/resource-unknown-transport"
                      {:rf.error/id :rf.error/resource-unknown-transport
                       :where       're-frame.resources.transport/lower-ensure
                       :recovery    :fix-registration
                       :reason      (str "unknown resource transport " transport
                                         " — the only initial-scope transport is "
                                         managed-http-transport
                                         " (Spec 016 §Transport).")
                       :transport   transport})))))
