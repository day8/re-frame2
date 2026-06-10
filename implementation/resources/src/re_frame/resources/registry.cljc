(ns re-frame.resources.registry
  "Resource registration — `reg-resource` / `clear-resource` and the
  registry-side introspection accessors. Per Spec 016 §Public API
  §Registration and §Resource registration spec.

  A resource is a registrar entry under the `:resource` kind (Spec 016
  §Registration: \"A `:resource` registrar kind is added; do NOT add a
  `:query` public kind\"). `reg-resource` validates the spec — crucially
  the REQUIRED, fail-closed `:scope` policy (Spec 016 §Scope resolution:
  no policy is a loud `:rf.error/resource-missing-scope-policy`) — and
  writes the entry; `clear-resource` is a registration-lifecycle removal
  (distinct from the data-lifecycle `:rf.resource/invalidate-tags` /
  `:rf.resource/remove` / `:rf.resource/clear-scope` events).

  This namespace owns the registration lifecycle only: validation + the
  registry write/read (so an app can register a resource and Xray can
  enumerate the static registry). `clear-resource` removes the registrar
  entry; the per-frame runtime disposal of cached data (owner-index
  release, host-handle cancel, in-flight abort, late-reply suppression,
  tag-index prune, trace) is the app's job via the data-lifecycle events
  (`:rf.resource/remove` / `:rf.resource/release-owner` /
  `:rf.resource/clear-scope`), not a side effect of `clear-resource`."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources.state :as state]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :resource registrar kind ----------------------------------------

(def resource-kind
  "The registrar kind for resources (`:resource`). Per Spec 016
  §Registration. Added to the core registrar's closed kind set (a Spec
  change); resources register their spec under this kind."
  :resource)

;; ---- scope-policy validation (fail-closed) -------------------------------

(def ^:private global-scope-policy
  "The explicit, auditable global-scope claim. Per Spec 016 §Scope
  resolution: `:scope :rf.scope/global` is a CLAIM (\"same params produce
  the same data for every user/tenant/permission/locale/impersonation\"),
  never a framework default."
  :rf.scope/global)

(def ^:private from-caller-scope-policy
  "Scope required from the use site. Per Spec 016 §Scope resolution:
  every ensure/refetch/state call MUST supply `:scope`, or a route-
  resource resolver MUST."
  :rf.scope/from-caller)

(def ^:private reserved-scope-ns
  "The framework-reserved scope namespace (`:rf.scope/*`, per Conventions
  §Reserved namespaces / the `:rf.<spec-area>/*` scheme). A *bare keyword*
  in this namespace is a CLOSED reserved enum (`#{:rf.scope/global
  :rf.scope/from-caller}`); any other `:rf.scope/*` bare keyword is a typo
  and a loud registration error, NOT a literal scope. Note a scope VALUE
  like `[:rf.scope/session {…}]` is a vector tuple, not a bare keyword —
  the reserved namespace governs only the bare-keyword *policy* slot."
  "rf.scope")

(defn- reserved-scope-namespace?
  "True when `scope` is a bare keyword in the framework-reserved
  `:rf.scope/*` namespace — i.e. a candidate for the closed scope-policy
  enum. A non-keyword (a `[:rf.scope/session …]` tuple, a map, a string)
  is NOT in the bare-keyword reserved slot."
  [scope]
  (and (keyword? scope) (= reserved-scope-ns (namespace scope))))

(defn- valid-scope-policy?
  "A scope policy is one of the reserved enum keywords
  (`:rf.scope/global`, `:rf.scope/from-caller`), a resolver (a fn), or a
  literal scope value (any non-keyword EDN data value, or an
  app-namespaced keyword). Per Spec 016 §Scope resolution. `nil` /
  missing is NOT valid — it is a loud registration error.

  FAIL-CLOSED reserved-namespace gate (rf2-y7lcqy): a bare keyword in the
  framework-reserved `:rf.scope/*` namespace that is NOT one of the closed
  enum is a TYPO (e.g. `:rf.scope/glabal`), and a typo in the framework
  namespace is a registration error — it MUST NOT be silently accepted as
  a literal scope (which would resolve to `[:rf.scope/glabal]`, a silent
  wrong scope). App-namespaced keywords (`:my.app/whatever`) and
  data-value scopes (`[:rf.scope/session {…}]`, maps, strings) remain
  valid literal scopes."
  [scope]
  (and (some? scope)
       (cond
         ;; the closed reserved enum
         (= scope global-scope-policy)      true
         (= scope from-caller-scope-policy) true
         ;; a bare `:rf.scope/*` keyword outside the enum is a typo —
         ;; reject (fail closed), do NOT accept as a literal scope.
         (reserved-scope-namespace? scope)  false
         ;; a fn resolver (route/spec/fn-of-nothing)
         (fn? scope)                        true
         ;; any other value — an app-namespaced keyword or a non-keyword
         ;; data value — is a legitimate literal scope / data-value
         ;; resolver.
         :else                              true)))

(defn- registration-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) for a `reg-resource` validation failure."
  [error-id where reason extra]
  (ex-info (str error-id)
           (merge {:rf.error/id error-id
                   :where       where
                   :recovery    :fix-registration
                   :reason      reason}
                  extra)))

(defn- validate-resource-spec!
  "Fail loudly at the authoring boundary when a resource spec omits the
  REQUIRED keys (Spec 016 §Resource registration spec). The fail-closed
  `:scope` policy is the load-bearing one: a `reg-resource` with no scope
  policy throws `:rf.error/resource-missing-scope-policy` so \"I forgot
  this read is user-scoped\" is unrepresentable at registration rather
  than an Xray heuristic. Fails in dev AND prod (caller bug)."
  [resource-id spec]
  (when-not (map? spec)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id "'s spec must be a map, got "
                  (pr-str (type spec)))
             {:resource-id resource-id :value spec})))
  ;; `:scope` is REQUIRED, fail-closed (rf2-6rrz53). No silent global default.
  (when-not (valid-scope-policy? (:scope spec))
    (throw (registration-error
             :rf.error/resource-missing-scope-policy
             'rf/reg-resource
             (str "resource " resource-id " declares no valid :scope policy. "
                  ":scope is REQUIRED (fail-closed) — one of :rf.scope/global "
                  "(an explicit, auditable global claim), a resolver, or "
                  ":rf.scope/from-caller. There is no implicit default; a "
                  "user-scoped read must say so. Per Spec 016 §Scope resolution.")
             {:resource-id resource-id :scope (:scope spec)})))
  ;; `:params-schema` is REQUIRED — validates + canonicalizes params.
  (when-not (contains? spec :params-schema)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id " declares no :params-schema. "
                  ":params-schema is REQUIRED — it validates and canonicalizes "
                  "params (the resource's identity). Per Spec 016 §Resource "
                  "registration spec.")
             {:resource-id resource-id})))
  ;; `:request` is REQUIRED for the only initial-scope transport.
  (when-not (contains? spec :request)
    (throw (registration-error
             :rf.error/invalid-resource-spec
             'rf/reg-resource
             (str "resource " resource-id " declares no :request. For "
                  ":transport :rf.http/managed (the only initial-scope "
                  "transport) :request returns a Spec 014 managed-HTTP args "
                  "map. Per Spec 016 §Resource registration spec.")
             {:resource-id resource-id})))
  nil)

;; ---- reg-resource / clear-resource ---------------------------------------

(defn reg-resource
  "Register a resource under `resource-id` with `resource-spec`. Per
  Spec 016 §Public API §Registration.

  Validates the spec (the REQUIRED, fail-closed `:scope` policy first;
  then `:params-schema` and `:request`) and writes a `:resource`-kind
  registrar entry carrying the spec plus any captured source coords.

  Returns `resource-id` per the `reg-*` return-value convention
  ([Conventions §reg-* return-value convention])."
  [resource-id resource-spec]
  (validate-resource-spec! resource-id resource-spec)
  (let [previous (registrar/lookup resource-kind resource-id)]
    (registrar/register!
      resource-kind
      resource-id
      (source-coords/merge-coords
        (merge {:doc (:doc resource-spec)}
               {:rf/resource resource-spec
                :handler-fn  (:request resource-spec)})))
    ;; `:rf.resource/registered` fires on FIRST-TIME registration so tools
    ;; subscribing to "all resource lifecycle events" (the Xray Resources
    ;; tab / lifecycle timeline) see one row per fresh resource — the
    ;; registration anchor of the `:rf.resource/*` trace family (Spec 016
    ;; §Xray and AI tooling). Re-registration rides the cross-kind
    ;; `:rf.registry/handler-replaced` trace (emitted by `registrar/register!`
    ;; per Spec 001 §Hot-reload trace surface); not re-emitted here. Mirrors
    ;; the `:rf.route/registered` / `:rf.flow/registered` symmetry. The row is
    ;; frame-agnostic (registration is a load-time act, not a per-frame event).
    (when (nil? previous)
      (trace/emit! :rf.event :rf.resource/registered
                   {:resource-id    resource-id
                    :scope-policy   (:scope resource-spec)
                    :transport      (:transport resource-spec)
                    :stale-after-ms (:stale-after-ms resource-spec)
                    :gc-after-ms    (:gc-after-ms resource-spec)})))
  resource-id)

(defn clear-resource
  "Remove a registered resource (registration-lifecycle, NOT data
  invalidation). Per Spec 016 §Public API §Registration.

  Clears the registrar entry. This is registration-lifecycle only: it does
  NOT dispose the per-frame resource-runtime state for the id (owner
  indexes, timers / host handles, in-flight requests, cached rows). Cached
  data is managed through the data-lifecycle events
  (`:rf.resource/invalidate-tags` / `:rf.resource/remove` /
  `:rf.resource/clear-scope`) instead.

  No-op (returns `resource-id`) when the id is not registered."
  [resource-id]
  (registrar/unregister! resource-kind resource-id)
  resource-id)

;; ---- registry-side introspection -----------------------------------------

(defn resource-meta
  "Return the registered resource's spec map (`:params-schema`,
  `:data-schema`, `:request`, `:scope`, `:transport`, `:stale-after-ms`,
  `:gc-after-ms`, `:tags`, `:doc`, source coords) for `resource-id`, or
  nil if no resource is registered under that id. Per Spec 016
  §Introspection."
  [resource-id]
  (:rf/resource (registrar/lookup resource-kind resource-id)))

(defn resource-ids
  "Return a vector of every registered resource id. The registry-side
  half of `resources` (the runtime-side per-frame instance table lives in
  the runtime). Per Spec 016 §Xray and AI tooling (the static
  resource registry)."
  []
  (vec (registrar/ids resource-kind)))

(defn require-resource-spec!
  "Look the resource spec up by `resource-id`, throwing
  `:rf.error/resource-not-registered` (the loud, fail-closed boundary) when
  no `:resource`-kind registrar entry exists. `where` names the call-site
  public surface. Returns the spec map. Per Spec 016 §Public API."
  [resource-id where]
  (or (resource-meta resource-id)
      (throw (registration-error
               :rf.error/resource-not-registered
               where
               (str "no resource is registered under " resource-id
                    " — call rf/reg-resource before ensuring / subscribing. "
                    "Per Spec 016 §Public API.")
               {:resource-id resource-id}))))

;; ---- params validation + canonicalization (Spec 016 §Resource identity) ---

(defn validate+canonicalize-params
  "Validate `params` against the resource's REQUIRED `:params-schema`
  (pluggable late-bound Malli validator, exactly as routing validates route
  params — `:schemas/validate-with-registered-fn`; no static schemas dep),
  reject non-EDN / host values loudly (`state/reject-non-edn!`), then return
  the canonical params (`state/canonicalize`). Throws
  `:rf.error/resource-invalid-params` on a schema-conformance failure.
  Per Spec 016 §Resource identity / §Canonicalization rule.

  `nil` vs missing is schema-defined: the `:params-schema` decides whether a
  key may be absent or nil — this fn does not impose a separate policy, it
  defers to the schema (Spec 016: \"nil vs missing MUST be schema-defined,
  not accidental\")."
  [resource-id spec params where]
  (let [params (or params {})
        schema (:params-schema spec)]
    ;; host / opaque values are rejected at the cache-key boundary
    (state/reject-non-edn! params where :params resource-id)
    ;; schema conformance (pluggable; no-op when no validator is registered)
    (when schema
      (let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (when (and validate (not (validate schema params)))
          (let [explain (late-bind/get-fn-cached :schemas/explain-with-registered-fn)]
            (throw (registration-error
                     :rf.error/resource-invalid-params
                     where
                     (str "resource " resource-id " params do not conform to "
                          ":params-schema. Per Spec 016 §Resource identity.")
                     {:resource-id resource-id
                      :params      params
                      :error       (when explain (explain schema params))}))))))
    (state/canonicalize params)))

;; ---- scope resolution (Spec 016 §Scope resolution) ------------------------
;;
;; Scope is the cache's tenant / user / permission / locale / impersonation
;; / SSR leak boundary, and a resolved scope can carry PII. A boundary that
;; critical MUST fail closed: it never silently defaults to \"shared\".
;; Resolution differs between EVENTS (which may run a (route, ctx) resolver
;; and have an event context) and SUBSCRIPTIONS (pure — no routing match,
;; no event context). There is NO `[:rf.scope/global]` fallthrough on
;; either path.

(defn- canonical-scope!
  "Reject a non-EDN scope value (host / opaque) and return the canonical
  scope. Per Spec 016 §Resource identity (a scope map gets the SAME
  canonicalization as params)."
  [resource-id scope where]
  (state/reject-non-edn! scope where :scope resource-id)
  (state/canonicalize scope))

(defn resolve-scope-for-event
  "Resolve the concrete cache scope for a resource EVENT, fail-closed, in
  Spec 016 §Resolution precedence order — NO `[:rf.scope/global]`
  fallthrough:

    1. `:scope` supplied on the event payload;
    2. (route-resource `:scope` resolver — supplied by the route slice,
       not this runtime slice; threaded in as `route-scope`);
    3. the resource-spec `:scope` resolver, but ONLY when it resolves to a
       concrete value without an event context — an explicit
       `:rf.scope/global` claim, or a pure-data / fn-of-nothing resolver.

  A `:rf.scope/global` policy resolves to `:rf.scope/global` ONLY because
  that is its declared explicit policy. A `:rf.scope/from-caller` resource
  reached with no payload `:scope` and no route resolver is a loud
  use-time error (`:rf.error/resource-scope-required-from-caller`). Returns
  the canonical scope."
  [resource-id spec {:keys [payload-scope route-scope]} where]
  (let [policy (:scope spec)]
    (cond
      ;; 1. payload scope (highest precedence)
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      ;; 2. route-resource resolver result (threaded in by the route slice)
      (some? route-scope)   (canonical-scope! resource-id route-scope where)
      ;; 3a. explicit global claim — the resource's declared policy
      (= policy global-scope-policy) global-scope-policy
      ;; 3b. from-caller with no payload/route scope — loud use-time error
      (= policy from-caller-scope-policy)
      (throw (registration-error
               :rf.error/resource-scope-required-from-caller
               where
               (str "resource " resource-id " declares :scope "
                    ":rf.scope/from-caller — every ensure / refetch / state "
                    "call MUST supply :scope on the payload (or a route "
                    "resolver must). There is no silent global read. Per Spec "
                    "016 §Scope resolution.")
               {:resource-id resource-id}))
      ;; 3c. a fn-of-nothing resolver (pure data resolvable without ctx)
      (fn? policy)
      (canonical-scope! resource-id (policy) where)
      ;; 3d. a pure data-value resolver (a concrete scope value declared
      ;; directly as the policy)
      :else (canonical-scope! resource-id policy where))))

(defn resolve-scope-for-sub
  "Resolve the cache scope for a resource SUBSCRIPTION, fail-closed (Spec
  016 §Subscription-side scope resolution). A sub is PURE — it cannot run a
  `(route, ctx)` resolver. Resolution order:

    1. `:scope` supplied on the subscription payload;
    2. the resource spec's `:scope` ONLY if a pure sub can evaluate it
       without an event context — an explicit `:rf.scope/global` claim, or
       a pure-data / fn-of-nothing resolver.

  A sub that CANNOT resolve a scope raises `:rf.error/resource-sub-
  unresolved-scope` (carrying the resource id + the unresolvable policy) —
  NEVER a silent `[:rf.scope/global]` read and NEVER a silent `:idle`. A
  `:rf.scope/from-caller` policy or a multi-arg `(route, ctx)` resolver is
  not sub-resolvable. Returns the canonical scope."
  [resource-id spec payload-scope where]
  (let [policy (:scope spec)]
    (cond
      (some? payload-scope) (canonical-scope! resource-id payload-scope where)
      (= policy global-scope-policy) global-scope-policy
      ;; a fn resolver is sub-resolvable ONLY when it is a fn-of-nothing
      ;; (a route (route, ctx) resolver needs an event context a pure sub
      ;; lacks). We treat a 0-arg-callable fn as sub-resolvable; a fn that
      ;; throws on 0-args is not sub-resolvable and falls to the loud error.
      (fn? policy)
      (let [resolved (try (policy) (catch #?(:clj Throwable :cljs :default) _ ::not-sub-resolvable))]
        (if (= resolved ::not-sub-resolvable)
          (throw (registration-error
                   :rf.error/resource-sub-unresolved-scope
                   where
                   (str "resource " resource-id " has a scope policy that a "
                        "pure subscription cannot resolve (a (route, ctx) "
                        "resolver or :rf.scope/from-caller). Pass :scope on the "
                        "subscription payload (the same scope the owning "
                        "route/event ensured under), or re-declare the resource "
                        "with a sub-resolvable scope policy. Per Spec 016 "
                        "§Subscription-side scope resolution.")
                   {:resource-id resource-id :policy :resolver}))
          (canonical-scope! resource-id resolved where)))
      (= policy from-caller-scope-policy)
      (throw (registration-error
               :rf.error/resource-sub-unresolved-scope
               where
               (str "resource " resource-id " declares :scope "
                    ":rf.scope/from-caller — a subscription MUST supply :scope "
                    "on its payload. Per Spec 016 §Subscription-side scope "
                    "resolution.")
               {:resource-id resource-id :policy from-caller-scope-policy}))
      ;; a pure data-value policy is sub-resolvable
      :else (canonical-scope! resource-id policy where))))
