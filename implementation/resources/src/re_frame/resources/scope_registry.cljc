(ns re-frame.resources.scope-registry
  "Named resource-scope resolvers: `reg-resource-scope`,
  `clear-resource-scope`, the pure `resolve-resource-scope` helper, and
  `{:from-db <id>}` reference resolution. A resolver derives a cache scope
  from declared db inputs, and every scope-taking site resolves it the same
  way.

      (rf/reg-resource-scope :realworld/session
        {:inputs {:username [:db [:auth :user :username]]}}
        (fn [{:keys [username]} _ctx]
          (when username [:rf.scope/session {:username username}])))

  Spec 016 §Named resource-scope resolvers (`reg-resource-scope`)"
  (:require [re-frame.error :as rf.error]
            [re-frame.path :as rf.path]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :resource-scope registrar kind ----------------------------------

(def scope-kind
  "The registrar kind for named resource-scope resolvers."
  :resource-scope)

;; ---- input source descriptors --------------------------------------------

(def shipped-input-sources
  "The closed set of shipped input source heads: only `:db`.
  Spec 016 §The `{:inputs …}` metadata + resolver-fn grammar"
  #{:db})

(def reserved-input-sources
  "Source heads that are named but not shipped. Declaring one is a loud,
  named error, so a typo is not mistaken for an unshipped feature.
  Spec 016 §Route-derived scope is reserved (`[:runtime path]`, not shipped)"
  #{:runtime})

;; ---- registration-error shape --------------------------------------------

(defn- registration-error
  "The thrown-error shape for a `reg-resource-scope` validation failure."
  [error-id where reason extra]
  (rf.error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

;; ---- validation ----------------------------------------------------------

(defn- validate-input-descriptor!
  "Returns the canonical `[:db <vector-path>]` for one `:inputs` descriptor.
  Throws `:rf.error/resource-scope-source-reserved` for a reserved head and
  `:rf.error/invalid-resource-scope-spec` for any other bad shape."
  [scope-id input-name descriptor]
  (when-not (and (vector? descriptor) (= 2 (count descriptor)))
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id " input " input-name
                  " has an invalid source descriptor " (pr-str descriptor)
                  ". Each input source is a 2-vector [:db <rf-path>] — the "
                  "only shipped source. Per Spec 016 §The :inputs grammar.")
             {:scope-id scope-id :input input-name :descriptor descriptor})))
  (let [[head raw-path] descriptor]
    (cond
      (contains? reserved-input-sources head)
      (throw (registration-error
               :rf.error/resource-scope-source-reserved
               'rf/reg-resource-scope
               (str "resource-scope " scope-id " input " input-name
                    " declares the RESERVED source " (pr-str head)
                    " — it is named in the input vocabulary but NOT "
                    "shipped. Route-derived scope (`[:runtime <path>]`) "
                    "un-defers only for a consumer that carries a principal "
                    "in a path segment and needs named-resolver scope at any "
                    "site, a route entry included. Use a `[:db <rf-path>]` source: viewer "
                    "identity that is app state is db-derived. Per Spec 016 "
                    "§Route-derived scope is reserved.")
               {:scope-id scope-id :input input-name :source head}))

      (not (contains? shipped-input-sources head))
      (throw (registration-error
               :rf.error/invalid-resource-scope-spec
               'rf/reg-resource-scope
               (str "resource-scope " scope-id " input " input-name
                    " declares an unknown source " (pr-str head) ". The only "
                    "shipped source is `:db` (`[:db <rf-path>]`). Per Spec "
                    "016 §The :inputs grammar.")
               {:scope-id scope-id :input input-name :source head}))

      ;; nil is not the root path: the root is the explicit `[]`, so a nil
      ;; fails closed instead of silently reading the whole db.
      (not (sequential? raw-path))
      (throw (registration-error
               :rf.error/invalid-resource-scope-spec
               'rf/reg-resource-scope
               (str "resource-scope " scope-id " input " input-name
                    " has a non-path `:db` target " (pr-str raw-path)
                    ". `[:db <rf-path>]` takes an EP-0012 concrete :rf/path "
                    "(a sequential collection of segments; the root is the "
                    "explicit [], not nil). Per Spec 016 §The :inputs grammar "
                    "/ Conventions §The :rf/path algebra.")
               {:scope-id scope-id :input input-name :descriptor descriptor}))

      ;; A host/opaque or template segment fails closed in normalize-concrete.
      :else
      (try
        [:db (rf.path/normalize-concrete raw-path)]
        (catch #?(:clj Exception :cljs :default) e
          (throw (registration-error
                   :rf.error/invalid-resource-scope-spec
                   'rf/reg-resource-scope
                   (str "resource-scope " scope-id " input " input-name
                        " `:db` path carries a malformed segment: "
                        #?(:clj (.getMessage e) :cljs (ex-message e))
                        " Per Conventions §Segment domain.")
                   {:scope-id scope-id :input input-name :descriptor descriptor})))))))

;; A resolver's `:rf.egress/output-sensitivity` key is silently ignored, not
;; validated: there is no sensitivity propagation from its inputs.
;; Spec 016 §No derived-sensitivity propagation

(defn- canonical-spec
  "Returns the stored spec `{:inputs :resolve :whole-db? :doc}` for the 3-slot
  registration. `metadata` must be a map with an `:inputs` map; `resolve-fn`
  is the third slot, and a `:resolve` inside `metadata` is refused as
  mislocated. Throws `:rf.error/invalid-resource-scope-spec`.
  `:whole-db?` is derived: true iff some input targets the root path `[]`.
  Spec 016 §The `{:inputs …}` metadata + resolver-fn grammar"
  [scope-id metadata resolve-fn]
  (when-not (map? metadata)
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id "'s metadata (the MIDDLE slot) must "
                  "be a map, got " (pr-str (type metadata)) ". The grammar is "
                  "(reg-resource-scope " scope-id " {…} resolve-fn): "
                  "the :inputs / :doc reflection-config metadata map is the "
                  "SECOND slot, the :resolve fn is the THIRD.")
             {:scope-id scope-id :value metadata})))
  (when (contains? metadata :resolve)
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id " declares :resolve inside its "
                  "metadata map — the resolver fn is the THIRD "
                  "slot: (reg-resource-scope " scope-id " {…} resolve-fn). Move "
                  "the resolver out of the metadata map into the value slot.")
             {:scope-id scope-id :value (:resolve metadata)})))
  (when-not (fn? resolve-fn)
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id "'s resolver (the THIRD slot) must "
                  "be a fn, got " (pr-str (type resolve-fn)) ". The grammar is "
                  "(reg-resource-scope " scope-id " {:inputs {name [:db path]}} "
                  "(fn [inputs ctx] -> scope|nil)). Per Spec 016 §The "
                  ":inputs grammar.")
             {:scope-id scope-id :value resolve-fn})))
  (when-not (contains? metadata :inputs)
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id "'s metadata (the MIDDLE slot) "
                  "declares no `:inputs`. `:inputs` is REQUIRED: the grammar is "
                  "(reg-resource-scope " scope-id " {:inputs {name [:db path]}} "
                  "(fn [inputs ctx] -> scope|nil)), and the resolver's first "
                  "arg is ALWAYS the resolved inputs map. To read the whole db, "
                  "declare it as an input on the root path: "
                  "{:inputs {:db [:db []]}}. Per Spec 016 §The :inputs grammar.")
             {:scope-id scope-id :metadata metadata})))
  (let [{:keys [inputs doc]} metadata]
    (when-not (map? inputs)
      (throw (registration-error
               :rf.error/invalid-resource-scope-spec
               'rf/reg-resource-scope
               (str "resource-scope " scope-id " has a non-map `:inputs` "
                    (pr-str inputs) ". `:inputs` is a map {name [:db path]}. "
                    "Per Spec 016 §The :inputs grammar.")
               {:scope-id scope-id :inputs inputs})))
    (let [canonical (reduce-kv
                      (fn [acc input-name descriptor]
                        (assoc acc input-name
                               (validate-input-descriptor! scope-id input-name descriptor)))
                      {} inputs)]
      {:inputs    canonical
       :resolve   resolve-fn
       :whole-db? (boolean (some (fn [[_ path]] (= [] (vec path)))
                                 (vals canonical)))
       :doc       doc})))

;; ---- reg-resource-scope / clear-resource-scope ---------------------------

(defn reg-resource-scope
  "Registers resolver `resolve-fn`, `(fn [inputs ctx] -> scope | nil)`, under
  `scope-id` with `metadata` `{:inputs {name [:db path]} :doc …}`, and returns
  `scope-id`. Refuses what `canonical-spec` refuses. Emits
  `:rf.resource/registered` on first registration.
  Spec 016 §Named resource-scope resolvers (`reg-resource-scope`)"
  [scope-id metadata resolve-fn]
  (let [spec     (canonical-spec scope-id metadata resolve-fn)
        previous (rf.registrar/lookup scope-kind scope-id)]
    (rf.registrar/register!
      scope-kind
      scope-id
      (rf.source-coords/merge-coords
        (merge {:doc (:doc spec)}
               ;; Select the image-selection stamp from `metadata`: the
               ;; canonical spec never carries it.
               (select-keys metadata [:ns :rf.provenance/ns])
               {:rf/resource-scope spec
                :handler-fn        (:resolve spec)})))
    (when (nil? previous)
      (rf.trace/emit! :rf.event :rf.resource/registered
                   {:resource-id scope-id
                    :kind        :resource-scope
                    :inputs      (vec (keys (:inputs spec)))
                    :whole-db?   (:whole-db? spec)}))
    scope-id))

(defn clear-resource-scope
  "Removes the resolver registered under `scope-id` and returns `scope-id`; a
  no-op when none is. A resolver holds no per-frame state, so nothing else is
  disposed."
  [scope-id]
  (rf.registrar/unregister! scope-kind scope-id)
  scope-id)

;; ---- registry lookup -----------------------------------------------------

(defn- not-registered-error
  "The `:rf.error/resource-scope-not-registered` thrown for `scope-id`;
  `when-clause` says when the lookup found nothing."
  [scope-id where when-clause]
  (registration-error
    :rf.error/resource-scope-not-registered
    where
    (str "no resource-scope resolver is registered under " scope-id when-clause
         " — call rf/reg-resource-scope before referencing it via "
         "{:from-db " scope-id "}. Per Spec 016 §Named "
         "resource-scope resolvers.")
    {:scope-id scope-id}))

(defn require-scope-resolver!
  "Returns the stored spec for `scope-id`, or throws
  `:rf.error/resource-scope-not-registered` naming `where`."
  [scope-id where]
  (or (:rf/resource-scope (rf.registrar/lookup scope-kind scope-id))
      (throw (not-registered-error scope-id where ""))))

;; ---- input evaluation ----------------------------------------------------

(defn eval-inputs
  "Returns `{input-name value}`, reading each `[:db rf-path]` off `db` (nil
  when missing). Pure."
  [inputs db]
  (reduce-kv
    (fn [acc input-name [_source rf-path]]
      (assoc acc input-name (rf.path/get db rf-path)))
    {} (or inputs {})))

;; ---- off-box trace egress ------------------------------------------------

(defn project-scope-resolved-egress
  "Projects a `:rf.resource/scope-resolved` row's `tags` for off-box egress:
  `:input-values` and `:scope` become `:rf/redacted` and the row is marked
  `:sensitive? true`. Fail-closed, because the row carries resolved values
  without their path provenance; the structural slots ride verbatim. A
  non-map passes through. Pure and idempotent; on-box listeners never call it."
  [tags]
  (if-not (map? tags)
    tags
    (-> tags
        (assoc :input-values :rf/redacted
               :scope        :rf/redacted
               :sensitive?   true))))

;; ---- resolver evaluation -------------------------------------------------

(defn- resolve-scope-from-inputs
  "Calls `:resolve` with `(in-vals nil)` and canonicalizes a non-nil result.
  Shared so a traced resolution evaluates its inputs once."
  [scope-id spec in-vals where]
  (let [raw ((:resolve spec) in-vals nil)]
    (when (some? raw)
      (rf.resources.state/canonicalize-scope raw where scope-id))))

(defn resolve-scope*-pure
  "Resolves `spec` against `db` to a canonical scope, or nil when the resolver
  returns nil. Emits no trace, so passive reads use it."
  [scope-id spec db where]
  (resolve-scope-from-inputs scope-id spec (eval-inputs (:inputs spec) db) where))

(defn resolve-scope*
  "As `resolve-scope*-pure`, and also emits `:rf.resource/scope-resolved` with
  the input names and values and the result. For causal boundaries only
  (events, route entry, a mutation's execution scope)."
  [scope-id spec db where]
  (let [inputs   (:inputs spec)
        in-vals  (eval-inputs inputs db)
        resolved (resolve-scope-from-inputs scope-id spec in-vals where)]
    (rf.trace/emit! :rf.event :rf.resource/scope-resolved
                 {:resource-id   scope-id
                  :kind          :resource-scope
                  :inputs        (vec (keys inputs))
                  :input-values  in-vals
                  :whole-db?     (:whole-db? spec)
                  :scope         resolved
                  :resolved-nil? (nil? resolved)})
    resolved))

(defn resolve-resource-scope
  "Resolves the named resolver `scope-id` against `db` to a canonical scope,
  or nil. Pure and trace-free. Throws `:rf.error/resource-scope-not-registered`
  for an unknown id. Its use is resolving the old scope from a handler's
  coeffect db before `:rf.resource/clear-scope`.
  Spec 016 §`clear-scope` resolves the concrete scope from the coeffect db (not a snapshot)"
  [db scope-id]
  (let [spec (require-scope-resolver! scope-id 'rf/resolve-resource-scope)]
    (resolve-scope*-pure scope-id spec db 'rf/resolve-resource-scope)))

;; ---- {:from-db <id>} reference resolution --------------------------------

(defn from-db-reference?
  "True iff `scope` is a `{:from-db <resolver-id>}` reference. Delegates to
  the state-side predicate so the two can never disagree."
  [scope]
  (rf.resources.state/from-db-reference-scope? scope))

(defn resolve-from-db-reference
  "Resolves `reference` against `db` at use time with trace evidence. Returns
  the canonical scope, or nil for the caller to interpret; throws for an
  unregistered resolver. Spec 016 §Resolver references — `{:from-db <id>}`"
  [reference db where]
  (let [scope-id (:from-db reference)
        spec     (require-scope-resolver! scope-id where)]
    (resolve-scope* scope-id spec db where)))

(defn resolve-from-db-reference-pure
  "As `resolve-from-db-reference` without the trace, because subscriptions
  re-key on every frame-state change and would flood the trace bus."
  [reference db where]
  (let [scope-id (:from-db reference)
        spec     (require-scope-resolver! scope-id where)]
    (resolve-scope*-pure scope-id spec db where)))

;; ---- a mutation's issuance context ---------------------------------------
;;
;; A mutation's consequences are `(fn [params result])`, so which resolvers
;; they name is known only once the reply arrives. The mutation therefore
;; resolves every registered resolver when it is issued, and each
;; `{:from-db …}` a consequence names reads that map, at execute or at settle.
;; Spec 016 §Resolver references — `{:from-db <id>}`

(defn resolve-all-pure
  "`{scope-id <canonical scope or nil>}` for every registered resolver,
  resolved against `db` without a trace. A resolver that throws throws here,
  before the mutation writes anything."
  [db where]
  (reduce-kv (fn [acc scope-id {spec :rf/resource-scope}]
               (cond-> acc
                 spec (assoc scope-id (resolve-scope*-pure scope-id spec db where))))
             {}
             (rf.registrar/registrations scope-kind)))

(defn resolve-from-db-scopes
  "Resolves `reference` from `from-db-scopes`, the `resolve-all-pure` map a
  mutation took when it was issued, and emits `:rf.resource/scope-resolved`
  for it, so only the resolvers a consequence names leave a row. The row has
  no `:input-values`: the map keeps each resolver's scope, not the db values it
  read. Returns the scope or nil; throws `:rf.error/resource-scope-not-registered`
  for a resolver that was not registered at issuance."
  [reference from-db-scopes where]
  (let [scope-id (:from-db reference)]
    (when-not (contains? from-db-scopes scope-id)
      (throw (not-registered-error scope-id where " when the mutation was issued")))
    (let [resolved (get from-db-scopes scope-id)
          spec     (:rf/resource-scope (rf.registrar/lookup scope-kind scope-id))]
      (rf.trace/emit! :rf.event :rf.resource/scope-resolved
                   (cond-> {:resource-id   scope-id
                            :kind          :resource-scope
                            :scope         resolved
                            :resolved-nil? (nil? resolved)}
                     spec (assoc :inputs    (vec (keys (:inputs spec)))
                                 :whole-db? (:whole-db? spec))))
      resolved)))

;; ---- ScopeInput resolution -----------------------------------------------

(defn resolve-scope-input
  "Resolves a ScopeInput (a concrete scope or a `{:from-db <id>}` reference)
  to a canonical scope at a causal boundary, so a reference resolves the same
  at every scope slot. A concrete scope is canonicalized once; a reference's
  result is already canonical. Returns nil only for a reference that resolved
  nil, and the call site applies its nil policy. `resource-id` scopes the
  validation error. Spec 016 §Resolver references — `{:from-db <id>}`"
  [scope db where resource-id]
  (if (from-db-reference? scope)
    (resolve-from-db-reference scope db where)
    (rf.resources.state/canonicalize-scope scope where resource-id)))
