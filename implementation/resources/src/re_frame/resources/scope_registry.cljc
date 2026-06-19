(ns re-frame.resources.scope-registry
  "Named resource-scope resolvers — `reg-resource-scope` /
  `clear-resource-scope` / the `resolve-resource-scope` resolver helper, plus
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

  ## Derived-sensitivity propagation (EP-0016 wave, rf2-fi6tda.1)

  The automatic-inheritance propagation arm IS wired (EP-0015 disposition 8's
  fourth framework-known graph): a derived scope is treated as sensitive when
  a declared `:db` input reads a frame-sensitive app-db path, EVEN when the
  owning resource was not declared `:sensitive?` — defence-in-depth automatic
  inheritance, consistent with the subs/flows `:rf.egress/output-sensitivity`
  model (EP-0015 issue 9). The stored `:inputs` shape is the dependency graph
  the propagation pass reads (`re-frame.resources.classification/`
  `resolver-derived-sensitive?`); the CONSUMPTION side reads it at scoped-key /
  entry classification (`whole-entry-disposition-for` → `:redact`). A resolver
  declares its output's classification with the closed
  `:rf.egress/output-sensitivity` enum (the SAME claim subs/flows honour —
  `:rf.egress/inherit` default propagates, `:rf.egress/sensitive` force-marks,
  `:rf.egress/public` declassifies). The primary scope boundary holds
  INDEPENDENTLY of this arm via the resource-owned `:sensitive?` claim +
  scoped-key redaction (Spec 016); the arm only ADDS the precision case where
  the owning resource was not itself declared `:sensitive?`.

  ## The `resolve-resource-scope` resolver helper

  `resolve-resource-scope` resolves a named scope against a SUPPLIED db
  value — a plain function over the registry, no effect-API surface and no
  resolution-timing ambiguity. It is NOT an effect and has no app-state /
  dispatch side effects, and (rf2-ru73k6 F3) it is a PURE data helper: it
  routes through the trace-free `resolve-scope*-pure` evaluator, so a passive
  read advertised as pure does NOT emit `:rf.resource/scope-resolved` into the
  trace bus. The CAUSAL resolution boundaries that DO carry that trace evidence
  (the inputs/resolved-scope/`:resolved-nil?` row tooling reads — a resource
  event's `{:from-db …}` scope, route entry, mutation settle) run the traced
  `resolve-scope*` wrapper instead.
  Its canonical use is the logout idiom: resolve the concrete old scope from
  the handler's coeffect db (the pre-transition causal input) and pass it to
  `:rf.resource/clear-scope` concretely. Per Spec 016 §`clear-scope` resolves
  the concrete scope from the coeffect db (EP-0016 issue 7)."
  (:require [re-frame.error :as error]
            [re-frame.marks :as marks]
            [re-frame.path :as path]
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
  (error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

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

(defn- coerce-resolver-output-sensitivity
  "Validate a resolver's `:rf.egress/output-sensitivity` derived-output
  declassification claim against the closed enum (the SAME claim subs/flows
  honour — `re-frame.marks/output-sensitivity-values`, EP-0015 issue 9). Returns
  the validated value, or `:rf.egress/inherit` (the default) when the key is
  absent. FAIL-CLOSED: an unknown value THROWS
  `:rf.error/invalid-resource-scope-spec` (the enum is closed — a typo is a loud
  registration error, never a silent permissive fall-through). Per Spec 015
  §Derived sensitivity / §Declassifying a derived output."
  [scope-id v]
  (cond
    (nil? v)                                       :rf.egress/inherit
    (contains? marks/output-sensitivity-values v)  v
    :else
    (throw (registration-error
             :rf.error/invalid-resource-scope-spec
             'rf/reg-resource-scope
             (str "resource-scope " scope-id " declares an invalid "
                  ":rf.egress/output-sensitivity " (pr-str v) " — it must be one "
                  "of " (pr-str marks/output-sensitivity-values) ": "
                  ":rf.egress/inherit (default — inherit from sensitive :db "
                  "inputs), :rf.egress/sensitive (force-mark the derived scope "
                  "sensitive), :rf.egress/public (declassify). Per Spec 015 "
                  "§Derived sensitivity.")
             {:scope-id scope-id :rf.egress/output-sensitivity v
              :valid    marks/output-sensitivity-values}))))

(defn- canonical-spec
  "Normalize a resolver registration argument into the canonical STORED
  spec map. Per Spec 016 §The `{:inputs … :resolve …}` grammar +
  §Whole-db function sugar.

  - The map form `{:inputs {name [:db path]} :resolve (fn [inputs ctx] …)}`
    validates every declared input descriptor, requires a fn `:resolve`,
    and stores `{:inputs <canonical> :resolve <fn> :whole-db? false
    :output-sensitivity <enum> :doc …}`.
  - The bare-fn sugar `(fn [db ctx] …)` lowers to an explicit whole-db
    dependency: a synthetic `:inputs {:db [:db []]}` (the root path),
    `:whole-db? true`, and the fn wrapped so it is called `(f db ctx)` —
    the resolver sees the whole db as its single input. Tooling reads
    `:whole-db?` to mark the cost on both axes (EP-0015 disposition 8).

  Derived-sensitivity (EP-0016 wave, rf2-fi6tda.1): a resolver MAY declare
  `:rf.egress/output-sensitivity` (the SAME closed enum subs/flows honour) to
  classify its DERIVED scope — `:rf.egress/inherit` (default, propagate from
  sensitive `:db` inputs), `:rf.egress/sensitive` (force-mark), or
  `:rf.egress/public` (declassify). Validated fail-closed; stored verbatim as
  `:output-sensitivity` so the consumption-side propagation pass
  (`re-frame.resources.classification/resolver-derived-sensitive?`) reads it.

  Returns the canonical stored spec; throws a loud
  `:rf.error/invalid-resource-scope-spec` on a malformed argument."
  [scope-id resolver]
  (cond
    ;; whole-db fn sugar — lower to an explicit whole-db input
    (fn? resolver)
    {:inputs             {:db [:db []]}
     :resolve            (fn [{:keys [db]} ctx] (resolver db ctx))
     :whole-db?          true
     :output-sensitivity :rf.egress/inherit
     :doc                nil}

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
      {:inputs             (reduce-kv
                             (fn [acc input-name descriptor]
                               (assoc acc input-name
                                      (validate-input-descriptor! scope-id input-name descriptor)))
                             {} (or inputs {}))
       :resolve            resolve
       :whole-db?          false
       :output-sensitivity (coerce-resolver-output-sensitivity
                             scope-id (:rf.egress/output-sensitivity resolver))
       :doc                doc})

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

(defn input-db-paths
  "Return the vector of CONCRETE app-db paths a resolver's declared `:inputs`
  read off the frame app-db — the `<rf-path>` of each `[:db <rf-path>]` source
  (the only shipped source). This is the dependency graph the derived-
  sensitivity propagation pass reads (EP-0016 wave, rf2-fi6tda.1): a path here
  that overlaps a frame-sensitive declaration inherits sensitivity to the
  derived scope. The whole-db fn sugar's synthetic `[:db []]` returns `[[]]`
  (the root path overlaps every sensitive declaration — the documented whole-db
  cost on the sensitivity axis). Pure; nil/empty `:inputs` → `[]`. Per Spec 015
  §Derived sensitivity / Spec 016 §The `:inputs` grammar."
  [inputs]
  (into []
        (keep (fn [[_input-name [_source rf-path]]] rf-path))
        (or inputs {})))

;; ---- off-box trace egress projection of scope-resolution rows (rf2-84l82t) -
;;
;; The `:rf.resource/scope-resolved` trace row carries the resolver's resolved
;; `:input-values` (the concrete db reads — e.g. `{:username "jake"}`) and the
;; derived `:scope` (e.g. `[:rf.scope/session {:username "jake"}]`). These are
;; owner-local, identity-bearing values the CENTRAL trace egress pipeline only
;; knows as generic tag slots — a value-path walk cannot classify a resolver's
;; `:input-values` once they have been copied into trace tags (EP-0015; the
;; resource trace family is an egress record set). So the resource family owns
;; an egress projector for the row, the SSR/tool-projection analogue, consumed
;; off-box through a late-bound hook (epoch tool-pair); on-box listeners keep
;; the raw evidence (the row is dev observability — the leak is at off-box /
;; epoch / MCP egress, not the local listener).

(defn scope-resolver-egress-sensitive?
  "FAIL-CLOSED off-box egress classification for a `:rf.resource/scope-resolved`
  row's resolver `scope-id` (rf2-84l82t). A db-reading resolver MAY read a
  frame-sensitive path the off-box walk cannot prove safe (the row carries the
  resolved VALUES, not the path provenance), so the conservative posture
  mirrors `re-frame.resources.classification/resolver-derived-sensitive?`'s
  fail-closed footing: the resolved `:input-values` / `:scope` are egress-
  sensitive UNLESS the resolver explicitly DECLASSIFIES via
  `:output-sensitivity :rf.egress/public` (the standing audit surface — the
  resolver asserts its derived scope is safe to surface raw). A resolver that
  is not registered (no spec to read) is sensitive (fail-closed). Pure."
  [scope-id]
  (let [spec (scope-resolver-meta scope-id)]
    (not (= :rf.egress/public (:output-sensitivity spec)))))

(defn project-scope-resolved-egress
  "Project a `:rf.resource/scope-resolved` trace row's `tags` for OFF-BOX
  egress (rf2-84l82t). When the row's resolver
  (`scope-resolver-egress-sensitive?`) is egress-sensitive (the fail-closed
  default for any db-reading resolver not explicitly declassified), the
  resolved `:input-values` (the raw db reads) and `:scope` (the derived
  identity tuple, which embeds those reads) are replaced by the `:rf/redacted`
  sentinel and the row is stamped `:sensitive? true`. The STRUCTURAL slots —
  `:resource-id` (the resolver id), `:kind`, the declared input NAMES
  (`:inputs`), `:whole-db?`, `:resolved-nil?` — ride verbatim (they carry no
  user value and a tool needs them to attribute the resolution). An explicitly
  declassified (`:rf.egress/public`) resolver rides verbatim. Idempotent
  (`:rf/redacted` re-redacts to itself); a non-map `tags` rides unchanged.
  Pure. The on-box listener path never calls this — the raw evidence stays for
  dev tooling; this is the OFF-BOX egress projector."
  [tags]
  (if-not (map? tags)
    tags
    (if (scope-resolver-egress-sensitive? (:resource-id tags))
      (-> tags
          (assoc :input-values :rf/redacted
                 :scope        :rf/redacted
                 :sensitive?   true))
      tags)))

;; ---- the pure resolve helper (Spec 016 §`clear-scope` … coeffect db) ------

(defn resolve-scope*-pure
  "PURE: resolve resolver `spec` against `db`, returning a canonical scope
  value or nil — WITHOUT emitting any observability state (rf2-ru73k6 F3).
  Evaluates the declared `:inputs` off `db`, calls `:resolve` with
  `(inputs nil)` (the `ctx` arg is reserved, literal nil in this slice), then
  routes a non-nil result through the SHARED concrete-scope canonicalization
  path (`state/canonicalize-scope` — rejects a misspelled `:rf.scope/*`
  keyword fail-closed, rejects a host/opaque value, rejects the global scope
  wrapped as the singleton `[:rf.scope/global]` in favour of the canonical
  bare keyword). A nil result passes through as nil (the fail-closed
  unresolved condition the use site interprets).

  This is the resolver EVALUATOR. Passive reads advertised as pure — the
  `resolve-resource-scope` helper and subscription key resolution — call this
  directly so a read does not mutate the trace bus. The traced
  `resolve-scope*` wrapper layers the `:rf.resource/scope-resolved` evidence
  on for CAUSAL boundaries (events / routes / mutations)."
  [scope-id spec db where]
  (let [in-vals (eval-inputs (:inputs spec) db)
        raw     ((:resolve spec) in-vals nil)]
    (when (some? raw)
      (state/canonicalize-scope raw where scope-id))))

(defn resolve-scope*
  "Resolve resolver `spec` against `db` AND emit the dev-time
  `:rf.resource/scope-resolved` trace evidence — the TRACED wrapper over the
  pure `resolve-scope*-pure` evaluator, for use at CAUSAL resolution
  boundaries (resource events, route entry, mutation settle). Returns the same
  canonical scope value (or nil) the pure evaluator does.

  Emits `:rf.resource/scope-resolved` carrying the resolver id, the declared
  input NAMES, the resolved input VALUES, and the resolved scope (or
  `:resolved-nil? true`). The resolved `:input-values` / `:scope` are
  owner-local identity-bearing values the generic value-path trace egress walk
  cannot classify, so the resource family owns their OFF-BOX egress projector
  (`project-scope-resolved-egress`, published as
  `:resources/project-scope-resolved-egress` and consulted by the epoch
  tool-pair): fail-closed redaction for any db-reading resolver not explicitly
  declassified (rf2-84l82t / EP-0015). The on-box listener keeps the raw
  evidence (the leak is at off-box / epoch / MCP egress, not the local
  listener). Tooling reads the declared inputs to avoid unnecessary whole-db
  re-resolution and to mark the whole-db-sugar cost (EP-0015 disposition 8).
  PASSIVE reads
  advertised as pure (`resolve-resource-scope`, subscription resolution) MUST
  call `resolve-scope*-pure` instead — they do not emit observability state
  during a read (rf2-ru73k6 F3)."
  [scope-id spec db where]
  (let [inputs   (:inputs spec)
        in-vals  (eval-inputs inputs db)
        resolved (resolve-scope*-pure scope-id spec db where)]
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
  "Resolver helper: resolve the named resource-scope resolver `scope-id`
  against the supplied `db` value, returning a canonical concrete scope or
  nil. A PURE function over the resolver registry — NOT an effect, no
  resolution-timing ambiguity, no app-state / dispatch side effects, and
  (rf2-ru73k6 F3) NO observability side effect either: it routes through the
  trace-free `resolve-scope*-pure` evaluator, so a passive read advertised as
  pure does NOT emit `:rf.resource/scope-resolved` into the trace bus. The
  CAUSAL resolution boundaries that DO carry trace evidence (a resource
  event's `{:from-db …}` scope, route entry, mutation settle) run the traced
  `resolve-scope*` wrapper instead. Per Spec 016 §`clear-scope` resolves the
  concrete scope from the coeffect db (EP-0016 issue 7).

  Canonical use is the logout/account-switch idiom: resolve the concrete old
  scope from the handler's COEFFECT db (pre-transition by definition — the
  EP-0010-coherent causal input) and pass it to `:rf.resource/clear-scope`
  concretely:

      (rf/reg-event :auth/logout
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
    (resolve-scope*-pure scope-id spec db 'rf/resolve-resource-scope)))

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

(defn resolve-from-db-reference-pure
  "PURE: resolve a `{:from-db <resolver-id>}` reference against `db` WITHOUT
  emitting `:rf.resource/scope-resolved` trace evidence — the trace-free
  counterpart of `resolve-from-db-reference`, for PASSIVE reads advertised as
  pure (rf2-ru73k6 F3): subscription key resolution re-keys a sub on every
  frame-state change, so a traced resolve would flood the trace bus with a row
  per re-render of a passive read. Routes through `resolve-scope*-pure`. The
  CAUSAL `{:from-db …}` resolution sites (resource events, route entry,
  mutation settle) call the traced `resolve-from-db-reference` instead — they
  are the inspectable causal evidence. Same return contract: the resolved
  canonical scope, or nil (the fail-closed unresolved condition)."
  [reference db where]
  (let [scope-id (:from-db reference)
        spec     (require-scope-resolver! scope-id where)]
    (resolve-scope*-pure scope-id spec db where)))
