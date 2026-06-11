(ns re-frame.resources.scope-registry
  "Named resource-scope resolvers — `reg-resource-scope` /
  `clear-resource-scope` / the pure `resolve-resource-scope` helper, plus
  the registry-side introspection accessors and the `{:from-db <id>}`
  reference resolver. Per Spec 016 §Named resource-scope resolvers
  (`reg-resource-scope`) and EP-0016 Decision 3 (slice 2).

  A resource-scope resolver is a registrar entry under the `:resource-scope`
  kind (the third resources-artefact kind, alongside `:resource` and
  `:mutation`). It derives a CACHE SCOPE from declared db inputs — the one
  scope-resolution currency the same named resolver carries to resource
  registration, route resources, event-side ensure, subscriptions,
  invalidation descriptors, populate/patch/remove targets, and `clear-scope`.

  ## The `{:inputs … :resolve …}` grammar (the primary form)

      (rf/reg-resource-scope :realworld/session
        {:inputs  {:username [:db [:auth :user :username]]}
         :resolve (fn [{:keys [username]} _ctx]
                    (when username [:rf.scope/session {:username username}]))})

  - `:inputs` — a map `{name source-descriptor}`. The ONLY shipped source
    descriptor is `[:db <rf-path>]`, where `<rf-path>` is an EP-0012
    concrete `:rf/path` (the path algebra in `re-frame.path`). `[:runtime
    <path>]` is RESERVED, not shipped — declaring one is a loud
    registration error (route-derived scope un-defers with the
    tenant-switcher consumer, per Spec 016 §Route-derived scope is reserved).
  - `:resolve` — `(fn [inputs ctx] -> scope | nil)`. PURE. It derives a
    scope from the resolved input map; it MUST NOT fetch, dispatch, mutate
    state, read ambient host state, or perform transport work. The `ctx`
    arg is RESERVED and is invoked as literal `nil` in this slice — a
    resolver MUST derive scope from its declared `:inputs`, not from `ctx`.
    A `nil` result is FAIL-CLOSED at every scope-requiring site (never an
    implicit global read).

  ## Whole-db function sugar (explicit-cost)

      (rf/reg-resource-scope :realworld/session
        (fn [db _ctx] (when-let [u (get-in db [:auth :user :username])]
                        [:rf.scope/session {:username u}])))

  A bare fn lowers to an EXPLICIT whole-db dependency (the canonical stored
  shape carries `:whole-db? true` and a synthetic `:inputs {:db [:db []]}`
  so tooling marks the whole-db cost on both axes — narrow re-resolution
  AND sensitivity-inheritance precision, EP-0015 disposition 8). The
  declared-inputs form is the recommended path; the sugar is a marked
  convenience, not a peer.

  ## The pure `resolve-resource-scope` helper

  `resolve-resource-scope` resolves a named scope against a SUPPLIED db
  value — a plain function over the registry, no effect-API surface and no
  resolution-timing ambiguity. Its canonical use is the logout idiom:
  resolve the concrete old scope from the handler's coeffect db (the
  pre-transition causal input) and pass it to `:rf.resource/clear-scope`
  concretely. Per Spec 016 §`clear-scope` resolves the concrete scope from
  the coeffect db (EP-0016 issue 7)."
  (:require [re-frame.path :as path]
            [re-frame.registrar :as registrar]
            [re-frame.resources.state :as state]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :resource-scope registrar kind ----------------------------------

(def scope-kind
  "The registrar kind for named resource-scope resolvers
  (`:resource-scope`). Per Spec 016 §Named resource-scope resolvers / the
  kind taxonomy. Added to the core registrar's closed kind set (a Spec
  change); resolvers register their canonical spec under this kind."
  :resource-scope)

;; ---- input source descriptors --------------------------------------------

(def shipped-input-sources
  "The CLOSED set of input source-descriptor heads shipped in this slice.
  Only `:db` ships — `[:db <rf-path>]` reads the path off the frame app-db.
  `:runtime` is RESERVED (`[:runtime <path>]`, route-derived scope) and is
  rejected fail-closed at registration until its consumer un-defers it. Per
  Spec 016 §The `{:inputs … :resolve …}` grammar / §Route-derived scope is
  reserved."
  #{:db})

(def reserved-input-sources
  "Input source-descriptor heads that are RESERVED (named in the spec /
  EP-0014 input vocabulary) but NOT shipped in this slice — declaring one
  is a loud registration error that names the reservation, so a typo is not
  mistaken for an unshipped feature. `:runtime` is the route-derived source
  (`[:runtime <path>]`, Spec 016 §Route-derived scope is reserved)."
  #{:runtime})

;; ---- registration-error shape --------------------------------------------

(defn- registration-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) for a `reg-resource-scope` validation failure."
  [error-id where reason extra]
  (ex-info (str error-id)
           (merge {:rf.error/id error-id
                   :where       where
                   :recovery    :fix-registration
                   :reason      reason}
                  extra)))

;; ---- validation ----------------------------------------------------------

(defn- validate-input-descriptor!
  "Validate ONE `:inputs` source descriptor `[source-head rf-path]` for
  resolver `scope-id` input `input-name`. The only shipped head is `:db`
  with a concrete `:rf/path` (an EP-0012 sequential path). A `:runtime`
  head is RESERVED (loud, named reservation). Any other shape is a loud
  `:rf.error/invalid-resource-scope-spec`. Returns the canonical
  descriptor `[:db <vector-path>]`."
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
                    " — it is named in the input vocabulary but NOT shipped "
                    "in this slice. Route-derived scope (`[:runtime <path>]`) "
                    "un-defers with the tenant-switcher consumer. Use a "
                    "`[:db <rf-path>]` source: viewer identity that is app "
                    "state is db-derived. Per Spec 016 §Route-derived scope "
                    "is reserved.")
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

      ;; A `:db` target must be a CONCRETE sequential `:rf/path`. nil is NOT
      ;; the root path — the root is the explicit `[]` (the whole-db sugar
      ;; lowers to `[:db []]`). A nil here is an accidental absent path and
      ;; fails closed rather than silently targeting the whole db (EP-0012
      ;; rf2-w9x5fv item 1: explicit root, no silent nil→root).
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

      ;; Route through the VALIDATED concrete boundary (rf2-w9x5fv item 2):
      ;; a host/opaque or template segment in a `:db` path fails closed here.
      :else
      (try
        [:db (path/normalize-concrete raw-path)]
        (catch #?(:clj Exception :cljs :default) e
          (throw (registration-error
                   :rf.error/invalid-resource-scope-spec
                   'rf/reg-resource-scope
                   (str "resource-scope " scope-id " input " input-name
                        " `:db` path carries a malformed segment: "
                        #?(:clj (.getMessage e) :cljs (ex-message e))
                        " Per Conventions §Segment domain.")
                   {:scope-id scope-id :input input-name :descriptor descriptor})))))))

(defn- canonical-spec
  "Normalize a resolver registration argument into the canonical STORED
  spec map. Per Spec 016 §The `{:inputs … :resolve …}` grammar +
  §Whole-db function sugar.

  - The map form `{:inputs {name [:db path]} :resolve (fn [inputs ctx] …)}`
    validates every declared input descriptor, requires a fn `:resolve`,
    and stores `{:inputs <canonical> :resolve <fn> :whole-db? false :doc …}`.
  - The bare-fn sugar `(fn [db ctx] …)` lowers to an explicit whole-db
    dependency: a synthetic `:inputs {:db [:db []]}` (the root path),
    `:whole-db? true`, and the fn wrapped so it is called `(f db ctx)` —
    the resolver sees the whole db as its single input. Tooling reads
    `:whole-db?` to mark the cost on both axes (EP-0015 disposition 8).

  Returns the canonical stored spec; throws a loud
  `:rf.error/invalid-resource-scope-spec` on a malformed argument."
  [scope-id resolver]
  (cond
    ;; whole-db fn sugar — lower to an explicit whole-db input
    (fn? resolver)
    {:inputs    {:db [:db []]}
     :resolve   (fn [{:keys [db]} ctx] (resolver db ctx))
     :whole-db? true
     :doc       nil}

    (map? resolver)
    (let [{:keys [inputs resolve doc]} resolver]
      (when-not (fn? resolve)
        (throw (registration-error
                 :rf.error/invalid-resource-scope-spec
                 'rf/reg-resource-scope
                 (str "resource-scope " scope-id " declares no fn `:resolve`. "
                      "The primary form is {:inputs {name [:db path]} :resolve "
                      "(fn [inputs ctx] -> scope|nil)}. Per Spec 016 §The "
                      ":inputs grammar.")
                 {:scope-id scope-id :resolve resolve})))
      (when (and (some? inputs) (not (map? inputs)))
        (throw (registration-error
                 :rf.error/invalid-resource-scope-spec
                 'rf/reg-resource-scope
                 (str "resource-scope " scope-id " has a non-map `:inputs` "
                      (pr-str inputs) ". `:inputs` is a map {name [:db path]}. "
                      "Per Spec 016 §The :inputs grammar.")
                 {:scope-id scope-id :inputs inputs})))
      {:inputs    (reduce-kv
                    (fn [acc input-name descriptor]
                      (assoc acc input-name
                             (validate-input-descriptor! scope-id input-name descriptor)))
                    {} (or inputs {}))
       :resolve   resolve
       :whole-db? false
       :doc       doc})

    :else
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id "'s resolver must be either a "
                  "{:inputs … :resolve …} map or a (fn [db ctx] …) whole-db "
                  "sugar, got " (pr-str (type resolver)) ". Per Spec 016 §The "
                  ":inputs grammar / §Whole-db function sugar.")
             {:scope-id scope-id :value resolver}))))

;; ---- reg-resource-scope / clear-resource-scope ---------------------------

(defn reg-resource-scope
  "Register a named resource-scope resolver under `scope-id`. Per Spec 016
  §Named resource-scope resolvers (`reg-resource-scope`) / EP-0016 D3.

  `resolver` is either the primary declared-inputs map
  `{:inputs {name [:db <rf-path>]} :resolve (fn [inputs ctx] -> scope|nil)}`
  or the whole-db fn sugar `(fn [db ctx] -> scope|nil)`. Validates the
  declared inputs (only `[:db <rf-path>]` ships; `[:runtime …]` is reserved
  and rejected loudly), requires a fn `:resolve`, and writes a
  `:resource-scope`-kind registrar entry carrying the canonical spec plus
  captured source coords.

  Emits `:rf.resource/registered` (`:kind :resource-scope`) on first-time
  registration so the resolver appears in the Xray resource lifecycle
  timeline alongside resources. Returns `scope-id` per the `reg-*`
  return-value convention ([Conventions §reg-* return-value convention])."
  [scope-id resolver]
  (let [spec     (canonical-spec scope-id resolver)
        previous (registrar/lookup scope-kind scope-id)]
    (registrar/register!
      scope-kind
      scope-id
      (source-coords/merge-coords
        (merge {:doc (:doc spec)}
               {:rf/resource-scope spec
                :handler-fn        (:resolve spec)})))
    (when (nil? previous)
      (trace/emit! :rf.event :rf.resource/registered
                   {:resource-id scope-id
                    :kind        :resource-scope
                    :inputs      (vec (keys (:inputs spec)))
                    :whole-db?   (:whole-db? spec)}))
    scope-id))

(defn clear-resource-scope
  "Remove a registered resource-scope resolver (a registration-lifecycle
  removal — the `clear-` decrement counterpart of `reg-resource-scope`, per
  [Conventions §Tear-down verb axis]). A resolver holds no per-frame runtime
  state of its own (it is a pure derivation consulted at use time), so there
  is nothing to dispose beyond the registrar entry. No-op (returns
  `scope-id`) when the id is not registered. Per Spec 016 §Named
  resource-scope resolvers."
  [scope-id]
  (registrar/unregister! scope-kind scope-id)
  scope-id)

;; ---- registry-side introspection -----------------------------------------

(defn scope-resolver-meta
  "Return the registered resolver's canonical spec map (`:inputs`,
  `:resolve`, `:whole-db?`, `:doc`) for `scope-id`, or nil if none is
  registered. The introspection counterpart of `resource-meta` /
  `mutation-meta`. Per Spec 016 §Named resource-scope resolvers."
  [scope-id]
  (:rf/resource-scope (registrar/lookup scope-kind scope-id)))

(defn scope-resolver-ids
  "Return a vector of every registered resource-scope resolver id. Per
  Spec 016 §Named resource-scope resolvers (the static resolver registry —
  enumerable by tooling)."
  []
  (vec (registrar/ids scope-kind)))

(defn require-scope-resolver!
  "Look the resolver spec up by `scope-id`, throwing
  `:rf.error/resource-scope-not-registered` (the loud, fail-closed
  boundary) when no `:resource-scope`-kind registrar entry exists. `where`
  names the call-site public surface. Returns the canonical spec map. Per
  Spec 016 §Named resource-scope resolvers."
  [scope-id where]
  (or (scope-resolver-meta scope-id)
      (throw (registration-error
               :rf.error/resource-scope-not-registered
               where
               (str "no resource-scope resolver is registered under " scope-id
                    " — call rf/reg-resource-scope before referencing it via "
                    "{:from-db " scope-id "}. Per Spec 016 §Named "
                    "resource-scope resolvers.")
               {:scope-id scope-id}))))

;; ---- input evaluation (Spec 016 §The `:inputs` grammar — `[:db path]`) ----

(defn eval-inputs
  "Evaluate a resolver's declared `:inputs` against a db value, returning
  `{input-name resolved-value}`. The only shipped source is `[:db <rf-path>]`
  — the value at `<rf-path>` in `db` (via the EP-0012 `re-frame.path` get,
  nil when missing). Pure. Per Spec 016 §The `:inputs` grammar."
  [inputs db]
  (reduce-kv
    (fn [acc input-name [_source rf-path]]
      (assoc acc input-name (path/get db rf-path)))
    {} (or inputs {})))

;; ---- the pure resolve helper (Spec 016 §`clear-scope` … coeffect db) ------

(defn resolve-scope*
  "Internal: resolve resolver `spec` against `db`, returning a canonical
  scope value or nil. Evaluates the declared `:inputs` off `db`, calls
  `:resolve` with `(inputs nil)` (the `ctx` arg is reserved, literal nil in
  this slice), then routes a non-nil result through the SHARED concrete-scope
  canonicalization path (`state/canonicalize-scope` — rejects a misspelled
  `:rf.scope/*` keyword fail-closed, rejects a host/opaque value, normalizes
  the `[:rf.scope/global]` singleton spelling). A nil result passes through
  as nil (the fail-closed unresolved condition the use site interprets).

  Emits `:rf.resource/scope-resolved` carrying the resolver id, the declared
  input NAMES, the resolved input VALUES, and the resolved scope (or
  `:resolved-nil? true`) — the values flow through the trace pipeline's
  egress/marks projection (Spec 015 / EP-0015) for off-box safety. Tooling
  reads the declared inputs to avoid unnecessary whole-db re-resolution and
  to mark the whole-db-sugar cost (EP-0015 disposition 8)."
  [scope-id spec db where]
  (let [inputs   (:inputs spec)
        in-vals  (eval-inputs inputs db)
        raw      ((:resolve spec) in-vals nil)
        resolved (when (some? raw)
                   (state/canonicalize-scope raw where scope-id))]
    (trace/emit! :rf.event :rf.resource/scope-resolved
                 {:resource-id   scope-id
                  :kind          :resource-scope
                  :inputs        (vec (keys inputs))
                  :input-values  in-vals
                  :whole-db?     (:whole-db? spec)
                  :scope         resolved
                  :resolved-nil? (nil? resolved)})
    resolved))

(defn resolve-resource-scope
  "PURE helper: resolve the named resource-scope resolver `scope-id` against
  the supplied `db` value, returning a canonical concrete scope or nil. A
  plain function over the resolver registry — NOT an effect, no
  resolution-timing ambiguity. Per Spec 016 §`clear-scope` resolves the
  concrete scope from the coeffect db (EP-0016 issue 7).

  Canonical use is the logout/account-switch idiom: resolve the concrete old
  scope from the handler's COEFFECT db (pre-transition by definition — the
  EP-0010-coherent causal input) and pass it to `:rf.resource/clear-scope`
  concretely:

      (rf/reg-event-fx :auth/logout
        (fn [{:keys [db]} _]
          (let [old (rf/resolve-resource-scope db :realworld/session)]
            {:db (dissoc db :auth)
             :fx [[:dispatch [:rf.resource/clear-scope {:scope old :cause :logout}]]]})))

  Throws `:rf.error/resource-scope-not-registered` when no resolver is
  registered under `scope-id` (the loud fail-closed boundary — a typo'd
  reference is never a silent nil). A resolver that returns nil for the
  supplied db yields nil (the fail-closed unresolved condition; the use site
  interprets it — never an implicit global)."
  [db scope-id]
  (let [spec (require-scope-resolver! scope-id 'rf/resolve-resource-scope)]
    (resolve-scope* scope-id spec db 'rf/resolve-resource-scope)))

;; ---- {:from-db <id>} reference resolution --------------------------------

(defn from-db-reference?
  "True iff `scope` is a named-resolver REFERENCE `{:from-db <resolver-id>}`
  — the single derived-scope reference form (Spec 016 §Resolver references).
  A concrete scope value (a keyword, a `[:rf.scope/session …]` tuple, a
  plain map without `:from-db`) is NOT a reference."
  [scope]
  (and (map? scope) (contains? scope :from-db)))

(defn resolve-from-db-reference
  "Resolve a `{:from-db <resolver-id>}` reference against `db` at USE TIME —
  the single use-time resolution rule, uniform across every site that
  accepts a derived scope (resource registration, route resources, ensure /
  refetch payloads, subscriptions, invalidation descriptors, populate/patch
  targets, clear-scope). Returns the resolved canonical scope, or nil (the
  fail-closed unresolved condition — the caller interprets it; route
  planning MUST NOT substitute global, a sub is explainable as \"scope
  unresolved\", a clear-scope site emits a loud diagnostic). `where` names
  the call-site for the structured registry error. Per Spec 016 §Resolver
  references — `{:from-db <id>}`."
  [reference db where]
  (let [scope-id (:from-db reference)
        spec     (require-scope-resolver! scope-id where)]
    (resolve-scope* scope-id spec db where)))
