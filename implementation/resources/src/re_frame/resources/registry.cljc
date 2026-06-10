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

  SKELETON slice (rf2-p10npe): registration validation + the registry
  write/read are real (so an app can register a resource and Xray can
  enumerate the static registry); the per-frame runtime disposal
  `clear-resource` performs (owner-index release, host-handle cancel,
  in-flight abort, late-reply suppression, tag-index prune, trace) lands
  with the runtime slices (rf2-afpdkn / rf2-pbxj48) — until then it
  clears the registrar entry only."
  (:require [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]))

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

(defn- valid-scope-policy?
  "A scope policy is one of the reserved enum keywords
  (`:rf.scope/global`, `:rf.scope/from-caller`) or a resolver (a fn, a
  pure data value, or a fn-of-nothing). Per Spec 016 §Scope resolution.
  `nil` / missing is NOT valid — it is a loud registration error."
  [scope]
  (and (some? scope)
       (or (= scope global-scope-policy)
           (= scope from-caller-scope-policy)
           ;; A resolver — a fn (route/spec resolver) or a data value /
           ;; fn-of-nothing (sub-resolvable). The runtime slice
           ;; distinguishes route-ctx resolvers from sub-resolvable ones;
           ;; at registration any non-nil value other than an unknown
           ;; bare `:rf.scope/*` keyword is accepted as a resolver.
           (fn? scope)
           (not (keyword? scope))
           ;; A namespaced keyword resolver alias is permitted; only a
           ;; bare `nil` (handled above) fails.
           (keyword? scope))))

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
  (registrar/register!
    resource-kind
    resource-id
    (source-coords/merge-coords
      (merge {:doc (:doc resource-spec)}
             {:rf/resource resource-spec
              :handler-fn  (:request resource-spec)})))
  resource-id)

(defn clear-resource
  "Remove a registered resource (registration-lifecycle, NOT data
  invalidation). Per Spec 016 §Public API §Registration.

  SKELETON slice (rf2-p10npe): clears the registrar entry. The full
  contract — also dispose resource-runtime state for the id in each
  affected frame (release owner indexes, cancel timers / host handles,
  abort in-flight where possible, suppress late replies by generation,
  remove tag-index rows, emit a trace) — lands with the runtime slices
  (rf2-afpdkn / rf2-pbxj48). Application data-lifecycle work uses
  `:rf.resource/invalidate-tags` / `:rf.resource/remove` /
  `:rf.resource/clear-scope` instead.

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
  half of `resources` (the runtime-side per-frame instance table lands
  with the runtime slice). Per Spec 016 §Xray and AI tooling (the static
  resource registry)."
  []
  (vec (registrar/ids resource-kind)))
