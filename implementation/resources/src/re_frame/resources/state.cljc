(ns re-frame.resources.state
  "Resource runtime-db paths + the durable entry / work-record shapes.
  Per Spec 016 §Cache home and write authority and §Frame work ledger.

  This namespace fixes the reserved runtime-db key paths and the canonical
  durable shapes the runtime reads/writes, plus the framework-write-
  authority registration-meta stamp every resource event handler carries.
  The runtime swaps over these paths (entry transition function, work-
  ledger join/dedupe, host side-table bookkeeping) live in the sibling
  runtime / work-ledger namespaces; the paths and shapes are pinned here
  so every sibling agrees on one home.

  Cache lives ONLY at `:rf.runtime/resources` inside the runtime-db
  partition (`:rf.db/runtime`); the work ledger lives at
  `:rf.runtime/work-ledger`. Both are reserved runtime-db keys (per
  [Conventions §Reserved runtime-db keys]) — allocated lazily, per-frame
  isolated, never an app-db location."
  (:require [re-frame.frame :as frame]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db paths -------------------------------------------
;;
;; Inside runtime-db itself, framework code reads/writes the bare
;; `[:rf.runtime/resources …]` paths; inside a full frame-state
;; projection the resource subtree is at
;; `[:rf.db/runtime :rf.runtime/resources …]`. Per Spec 016 §Cache home.

(def resources-key
  "The reserved runtime-db key for the resource cache subtree
  (`:rf.runtime/resources`). Per Spec 016 §Cache home and write authority."
  :rf.runtime/resources)

(def work-ledger-key
  "The reserved runtime-db key for the frame work ledger subtree
  (`:rf.runtime/work-ledger`). Named neutrally — resources are its first
  writer, later slices extend it to timers / streams / route loaders /
  spawned actors / machine async work. Per Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(defn entries-path
  "Runtime-db-relative path to the cache entries map
  `{<scoped-resource-key> <entry>}`. Per Spec 016 §Cache home."
  []
  [resources-key :entries])

(defn tag-index-path
  "Runtime-db-relative path to the reverse tag index
  `{<tag> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`
  (rebuilt on restore/hydration, never trusted from the snapshot). Per
  Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :tag-index])

(defn owner-index-path
  "Runtime-db-relative path to the reverse owner index
  `{<owner> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`.
  Per Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :owner-index])

(defn entry-path
  "Runtime-db-relative path to a single cache entry by its scoped
  resource key `[cache-scope resource-id canonical-params]`. Per Spec 016
  §Resource identity."
  [scoped-resource-key]
  [resources-key :entries scoped-resource-key])

(defn work-record-path
  "Runtime-db-relative path to a single work record by its `:work/id`.
  Per Spec 016 §Frame work ledger."
  [work-id]
  [work-ledger-key work-id])

;; ---- framework-write authority -------------------------------------------
;;
;; `:rf.runtime/resources` and `:rf.runtime/work-ledger` are framework-
;; owned runtime-db children, so resource writes MUST mint framework-write
;; authority — ordinary app authority is not enough. Every resource
;; `reg-event-fx` registration site stamps this reserved registration-meta
;; key so the runtime recognises a returned `:rf.db/runtime` effect from a
;; resource handler as in-bounds (it governs only the
;; `:rf.warning/app-handler-runtime-effect` ownership diagnostic — a
;; convention, not a capability gate; Spec 002 Mike ruling #4). Mirrors
;; routing's `framework-authority-meta`. Per Spec 016 §Write authority.

(def framework-authority-meta
  "Reserved registration-meta map (`{:rf/framework-authority? true}`)
  stamped on every resource event handler so a returned runtime-db effect
  is recognised as a framework write. Per Spec 016 §Write authority."
  {:rf/framework-authority? true})

;; ---- durable shapes (documentation-grade defaults) -----------------------
;;
;; These constructors fix the canonical durable shapes the runtime fills
;; in. They allocate plain EDN — no host handles, which live OUTSIDE
;; durable frame-state in side tables keyed by `[frame-id work-id]`.

(def lifecycle-states
  "The five resource lifecycle FSM states (cache-entry status). The
  transition function over these states lives in the runtime; the closed
  set is pinned here so siblings agree on it. Per
  Spec 016 §Lifecycle is an FSM."
  #{:idle :loading :fetching :loaded :error})

(def terminal-work-statuses
  "The terminal work-ledger statuses an attempt may reach. Terminal rows
  are pruned on the linked entry's next successful transition (a small
  bounded per-resource-key tail is retained for Xray). Per Spec 016
  §Ledger row retention and identity."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn empty-entry
  "Construct an empty `:idle` durable cache entry for `resource-id`. The
  durable entry stores FACTS, not derived booleans (`:stale?` /
  `:loading?` / `:has-data?` are public derived sub values, computed in
  the subs layer, never stored). Per Spec 016 §Status semantics.

  The runtime populates / transitions this shape; this constructor pins
  the canonical key set so an entry written by one sibling reads correctly
  in another."
  [resource-id]
  {:resource/id    resource-id
   :status         :idle
   :data           nil
   :error          nil
   :refresh-error  nil
   :loaded-at      nil
   :stale-at       nil
   :invalidated-at nil
   :attempt        0
   :generation     0
   :request-id     nil
   :current-work   nil
   :tags           #{}
   :active-owners  #{}
   ;; `:previous-key` is the prior scoped key whose data is PROJECTED while
   ;; this (new) key first-loads under `:keep-previous?` (Spec 016
   ;; §Paginated and previous data). It is a PROJECTION POINTER only — the
   ;; previous key's data is never inserted into THIS entry's `:data` and
   ;; never provides THIS key's `:tags`; the sub layer reads it to project
   ;; `:previous?` / `:previous-key` / `:previous-data`. Cleared once this
   ;; key becomes `:loaded` (it then has its own data). nil when this entry
   ;; does not keep previous data.
   :previous-key   nil})

;; ---- canonicalization (Spec 016 §Canonicalization rule) -------------------
;;
;; Canonicalization is a pure function over EDN: a map is normalized so
;; member order is irrelevant to identity and equality; nested maps recurse;
;; sets and vectors keep their value semantics. The SAME rule applies to
;; params maps and scope maps, so a scope and a param map spelled two ways
;; collapse to one cache identity. Host values (functions, promises, dates,
;; DOM nodes, AbortControllers, JS objects) are rejected loudly here — the
;; cache key MUST be serializable EDN.

(defn serializable-edn?
  "True iff `x` is serializable EDN data the cache key may carry — a
  keyword / symbol / string / number / boolean / nil, or a collection
  (map / vector / list / set) recursively built from such. Host / opaque
  values (functions, dates, promises, DOM nodes, AbortControllers, raw JS
  objects, atoms) are rejected. Per Spec 016 §Resource identity (\"Host
  values … are rejected\")."
  [x]
  (cond
    (nil? x)     true
    (keyword? x) true
    (symbol? x)  true
    (string? x)  true
    (boolean? x) true
    (number? x)  true
    (map? x)     (every? (fn [[k v]] (and (serializable-edn? k)
                                          (serializable-edn? v))) x)
    (set? x)     (every? serializable-edn? x)
    ;; vectors, lists, seqs
    (sequential? x) (every? serializable-edn? x)
    :else        false))

(defn- type-rank
  "Total type ordering used by `total-edn-compare` so a map with MIXED key
  types (e.g. a keyword key and a string key) sorts deterministically rather
  than throwing a raw `ClassCastException` (rf2-ptz7z8). The rank groups
  values by their EDN kind; WITHIN a kind the natural / string ordering
  breaks ties. Covers exactly the serializable-EDN kinds the cache key may
  carry (`serializable-edn?`)."
  [x]
  (cond
    (nil? x)        0
    (boolean? x)    1
    (number? x)     2
    (string? x)     3
    (keyword? x)    4
    (symbol? x)     5
    :else           6))

(defn- total-edn-compare
  "A TOTAL comparator over the serializable-EDN key shapes a canonical map
  may carry (rf2-ptz7z8). Orders first by `type-rank` (so mixed
  keyword/string/number/nil keys are orderable instead of throwing), then
  WITHIN a kind by the kind's natural order — numbers numerically, strings /
  keywords / symbols by their printed form. A deterministic total order is
  all canonicalization needs: it only has to be stable + order-independent,
  not semantically meaningful across kinds. Returns a negative / zero /
  positive int."
  [a b]
  (let [ra (type-rank a) rb (type-rank b)]
    (if (not= ra rb)
      (compare ra rb)
      ;; same kind — compare within it. numbers compare numerically; every
      ;; other comparable kind compares by its printed form (keywords /
      ;; symbols carry namespace + name; strings are themselves), which is a
      ;; total order within the kind.
      (if (number? a)
        (compare a b)
        (compare (str a) (str b))))))

(defn canonicalize
  "Pure canonicalization of an EDN value for use in a cache key (Spec 016
  §Canonicalization rule). A map is normalized into a sorted-by-key map under
  a TOTAL comparator so two spellings (key order) collapse to one identity
  and `=`; nested maps recurse; sets and vectors keep value semantics (their
  elements recurse). Reject non-EDN / host values loudly via `reject-non-edn!`
  upstream — this fn assumes its input is already serializable EDN.

  The sorted-map normalization makes member ORDER irrelevant to BOTH
  equality and hashing, so `{:a 1 :b 2}` and `{:b 2 :a 1}` produce the
  identical canonical value (and therefore the identical scoped resource
  key). The comparator is TOTAL over the accepted EDN key shapes
  (rf2-ptz7z8): a map mixing keyword and string keys canonicalizes
  deterministically (ordered by `total-edn-compare`) instead of throwing a
  raw `ClassCastException` from the default sorted-map comparator. Returns
  the canonical value."
  [x]
  (cond
    (map? x)        (into (sorted-map-by total-edn-compare)
                          (map (fn [[k v]] [k (canonicalize v)]))
                          x)
    (set? x)        (into #{} (map canonicalize) x)
    (vector? x)     (mapv canonicalize x)
    (sequential? x) (mapv canonicalize x)
    :else           x))

(defn reject-non-edn!
  "Throw `:rf.error/resource-non-edn-params` when `value` (a params or
  scope map) is not serializable EDN — a host / opaque value (fn, promise,
  date, DOM node, AbortController, JS object) reached the cache-key
  boundary. Per Spec 016 §Resource identity (host values are rejected) /
  §Canonicalization rule. `where` / `kind` (`:params` | `:scope`) name the
  offending boundary. Returns `value` unchanged when it conforms."
  [value where kind resource-id]
  (when-not (serializable-edn? value)
    (throw (ex-info ":rf.error/resource-non-edn-params"
                    {:rf.error/id :rf.error/resource-non-edn-params
                     :where       where
                     :recovery    :fix-params
                     :reason      (str "resource " resource-id " " (name kind)
                                       " is not serializable EDN — host / opaque "
                                       "values (functions, promises, dates, DOM "
                                       "nodes, AbortControllers, JS objects) are "
                                       "rejected at the cache-key boundary. Put "
                                       "every value that affects remote identity "
                                       "in params as plain EDN. Per Spec 016 "
                                       "§Resource identity.")
                     :resource-id resource-id
                     :kind        kind
                     :value       (pr-str value)})))
  value)

;; ---- concrete-scope validation (Spec 016 §Scope resolution) ---------------
;;
;; The SINGLE shared validation path for a CONCRETE scope value — the value
;; a resolved scope actually carries into the cache key (a payload `:scope`,
;; a route-resolver result, a fn-of-nothing result, a pure-data policy, or a
;; mutation invalidation default). Distinct from the registration-time scope
;; POLICY gate (`registry/valid-scope-policy?`), which validates the
;; declared policy slot. Every scope-bearing operation — event resolution,
;; sub resolution, route planning, mutation invalidation default — routes
;; its concrete scope through `canonicalize-scope` so the same three
;; guarantees hold everywhere (rf2-lzv9xc):
;;
;;   1. host / opaque scope values are rejected (`reject-non-edn!`);
;;   2. a BARE unknown `:rf.scope/*` keyword (a reserved-namespace typo such
;;      as `:rf.scope/glabal`) is rejected fail-closed (rf2-pd7akw) — it can
;;      NEVER become a silent wrong cache scope;
;;   3. the singleton-vector `[:rf.scope/global]` global-scope spelling is
;;      normalized to the canonical bare `:rf.scope/global` (rf2-vv87xz) so a
;;      payload copied from historical prose cannot create a SECOND, distinct
;;      global cache key.

(def reserved-scope-ns
  "The framework-reserved scope namespace (`:rf.scope/*`, per Conventions
  §Reserved namespaces / the `:rf.<spec-area>/*` scheme). A *bare keyword*
  in this namespace is a CLOSED reserved enum (`#{:rf.scope/global
  :rf.scope/from-caller}`); any other `:rf.scope/*` bare keyword is a typo,
  NOT a literal scope. Note a scope VALUE like `[:rf.scope/session {…}]` is a
  vector tuple, not a bare keyword — the reserved namespace governs only the
  bare-keyword slot."
  "rf.scope")

(def reserved-concrete-scopes
  "The closed set of bare `:rf.scope/*` keywords that are VALID as a concrete
  resolved scope. Only `:rf.scope/global` is a concrete cache scope —
  `:rf.scope/from-caller` is a registration POLICY (it never resolves to a
  concrete value; the use site supplies one) and so is NOT a valid concrete
  scope. Any other `:rf.scope/*` bare keyword is a typo. Per Spec 016 §Scope
  resolution."
  #{:rf.scope/global})

(defn reserved-scope-typo?
  "True when `scope` is a BARE keyword in the framework-reserved
  `:rf.scope/*` namespace that is NOT a valid concrete scope (rf2-pd7akw) —
  i.e. a misspelled reserved scope like `:rf.scope/glabal`, or the
  policy-only `:rf.scope/from-caller` reaching a concrete boundary. A
  non-keyword scope (a `[:rf.scope/session …]` tuple, a map, a string) is
  NOT in the bare-keyword reserved slot and is never a typo here."
  [scope]
  (and (keyword? scope)
       (= reserved-scope-ns (namespace scope))
       (not (contains? reserved-concrete-scopes scope))))

(def ^:private global-scope
  "The canonical CONCRETE global cache scope — the bare keyword the
  implementation stores as the first element of a scoped key for an explicit
  global resource (rf2-vv87xz). The historical singleton-vector spelling
  `[:rf.scope/global]` normalizes to this."
  :rf.scope/global)

(defn- normalize-global-scope
  "Normalize the historical singleton-vector global spelling
  `[:rf.scope/global]` to the canonical concrete `:rf.scope/global` bare
  keyword (rf2-vv87xz), so a payload that copied the singleton-vector form
  from older prose collapses to the SAME global cache key the implementation
  resolves an explicit-global policy to — it can never silently create a
  second, distinct global key. Every other value passes through unchanged."
  [scope]
  (if (= scope [global-scope]) global-scope scope))

(defn reject-reserved-scope-typo!
  "Throw `:rf.error/resource-invalid-scope` when `scope` is a reserved-
  namespace typo (`reserved-scope-typo?`) reaching a CONCRETE scope boundary
  (rf2-pd7akw). A bare `:rf.scope/*` keyword outside the concrete enum is a
  framework-namespace typo, never a literal app scope — accepting it would
  resolve to a silent WRONG cache scope (a tenant / user / permission leak),
  exactly the failure the fail-closed scope contract exists to prevent.
  `where` / `resource-id` name the offending boundary. Returns `scope`
  unchanged when it conforms."
  [scope where resource-id]
  (when (reserved-scope-typo? scope)
    (throw (ex-info ":rf.error/resource-invalid-scope"
                    {:rf.error/id :rf.error/resource-invalid-scope
                     :where       where
                     :recovery    :fix-scope
                     :reason      (str "resource " resource-id " was reached with a "
                                       "scope " (pr-str scope) " in the framework-"
                                       "reserved :rf.scope/* namespace that is not a "
                                       "valid concrete scope. The only concrete "
                                       "reserved scope is :rf.scope/global; "
                                       ":rf.scope/from-caller is a registration "
                                       "policy (the use site supplies the concrete "
                                       "scope), and any other :rf.scope/* keyword is "
                                       "a typo. A framework-namespace typo MUST fail "
                                       "closed rather than become a silent wrong "
                                       "cache scope. Per Spec 016 §Scope resolution.")
                     :resource-id resource-id
                     :scope       scope})))
  scope)

(defn canonicalize-scope
  "The SINGLE shared concrete-scope validation + canonicalization path
  (rf2-lzv9xc). Given a CONCRETE resolved scope value, in order:

    1. reject a reserved-namespace typo fail-closed
       (`reject-reserved-scope-typo!`, rf2-pd7akw);
    2. reject a host / opaque value (`reject-non-edn!`);
    3. normalize the historical `[:rf.scope/global]` singleton-vector
       spelling to the canonical bare `:rf.scope/global` (rf2-vv87xz);
    4. canonicalize the EDN (`canonicalize`).

  Every scope-bearing operation routes its concrete scope through this fn so
  the typo / host / global-spelling guarantees hold uniformly across event
  resolution, sub resolution, route planning, and mutation invalidation
  defaults. `where` / `resource-id` name the boundary for the structured
  errors. Returns the canonical scope."
  [scope where resource-id]
  (reject-reserved-scope-typo! scope where resource-id)
  (reject-non-edn! scope where :scope resource-id)
  (canonicalize (normalize-global-scope scope)))

;; ---- scoped resource key (Spec 016 §Resource identity) --------------------

(defn scoped-resource-key
  "Build the canonical scoped resource key
  `[canonical-scope resource-id canonical-params]` — the cache key, the
  request-correlation token payload, and the unit Xray / SSR enumerate.

  Both the scope and the params are canonicalized under the SAME rule
  (`canonicalize`), so key order in either map never affects identity, and
  the scope is part of the key (the same params in different scopes can't
  supersede each other). Per Spec 016 §Resource identity. Assumes scope +
  params are already validated serializable EDN (`reject-non-edn!`)."
  [scope resource-id params]
  [(canonicalize scope) resource-id (canonicalize params)])

(defn prior-loaded-sibling-key
  "Find the prior loaded SIBLING key to project under `:keep-previous?`
  (Spec 016 §Paginated and previous data): among the cache `entries`, the
  key with the SAME `[scope resource-id]` as `new-key` but DIFFERENT
  params, that currently has usable `:data`, picking the most recently
  loaded (`:loaded-at`). Returns the sibling scoped key, or nil when there
  is no sibling to project (the new key first-loads with no placeholder).
  A pure selection — the projection pointer it returns never inserts data
  into the new entry."
  [entries new-key]
  (let [[scope rid params] new-key]
    (->> entries
         (keep (fn [[k entry]]
                 (let [[s r p] k]
                   (when (and (= s scope) (= r rid) (not= p params)
                              (some? (:data entry)))
                     [k (:loaded-at entry)]))))
         (sort-by (fn [[_ loaded-at]] (or loaded-at 0)) >)
         ffirst)))

;; ---- compact lifecycle FSM (Spec 016 §Lifecycle is an FSM) ----------------
;;
;; A PURE transition function over the cache entry, NOT a spawned machine
;; per entry (Spec 016 §Lifecycle is an FSM: spawning a full machine per
;; ordinary resource entry is prohibited in v1). The transition function
;; over the five states answers \"given the current status and an event,
;; what is the next status?\" — it describes CACHE-ENTRY status, distinct
;; from the work-ledger attempt lifecycle (rf2-afpdkn).
;;
;;   :idle    + :start-load (no data)        -> :loading
;;   :loading + :success                     -> :loaded
;;   :loading + :failure                     -> :error
;;   :loaded  + :start-refresh               -> :fetching
;;   :fetching+ :success                     -> :loaded
;;   :fetching+ :failure                     -> :loaded   (:refresh-error; data kept)
;;   :error   + :start-load                  -> :loading
;;   <any>    + :start-load (has data)       -> :fetching (refresh, not first load)

(defn next-status
  "Pure status transition (Spec 016 §Lifecycle is an FSM). Given the
  current `status`, a transition `signal`
  (`:start-load` / `:success` / `:failure`), and whether the entry
  currently `has-data?`, return the next status.

  - `:start-load` with NO usable data -> `:loading` (first load); with
    usable data -> `:fetching` (refresh / stale-while-revalidate);
  - `:success` -> `:loaded`;
  - `:failure` from `:loading` (or `:idle`/`:error` first load) -> `:error`
    (no usable data because the first load failed);
  - `:failure` from `:fetching` -> `:loaded` (background-refresh failure:
    return to `:loaded`, keep prior `:data`, record `:refresh-error`).

  This is the SINGLE home for the cache-entry status semantics; the event
  handlers and the reply handlers both transition through it so the five
  states never drift between writers."
  [status signal has-data?]
  (case signal
    :start-load (if has-data? :fetching :loading)
    :success    :loaded
    :failure    (if (= :fetching status) :loaded :error)
    ;; unknown signal — no transition (defensive; callers pass the closed set)
    status))

(defn has-data?
  "True iff the entry currently has usable last-known-good `:data`. The
  fact `:loading?` / `:fetching?` / `:has-data?` derive from. Spec 016
  §Status semantics — durable entries store facts, derived booleans are
  computed (here + in subs), never stored."
  [entry]
  (some? (:data entry)))

(defn entry-stale?
  "Derived freshness fact: true iff `entry` is stale against `clock-ms` —
  it has been explicitly invalidated (`:invalidated-at` set) OR its
  `:stale-after-ms` window has elapsed (`:stale-at` set and
  `clock-ms >= :stale-at`). Freshness is computed from the DURABLE absolute
  timestamps, NOT from trusting a timer fired on time, and is ORTHOGONAL to
  load status (a `:loaded` entry may be stale). The SINGLE home for the
  staleness derivation so the subs projection, the SSR projection, and the
  stale-timer re-check never drift. Per Spec 016 §Status semantics / §Stale
  and GC scheduling. A computed value, never a stored fact."
  [entry clock-ms]
  (boolean
    (and entry
         (or (some? (:invalidated-at entry))
             (when-let [sa (:stale-at entry)] (>= clock-ms sa))))))

;; ---- entry transitions (Spec 016 §Status semantics / §Structural sharing) -
;;
;; Pure functions `(entry, …) -> entry`. They transition through
;; `next-status` so the five-state semantics stay in one place, write the
;; durable FACTS (status / data / errors / timestamps / generation /
;; current-work / tags), and NEVER store the derived booleans. Structural
;; sharing preserves the old `:data` value when the newly-decoded value
;; equals the previous (identity-preserving — downstream subs stay quiet on
;; a background refresh that returns identical EDN).

(defn entry-start-load
  "Transition an entry to its in-flight status for a fresh load attempt:
  `:loading` when it has no usable data (first load), `:fetching` when it
  does (refresh / stale-while-revalidate). Bumps `:generation` and
  `:attempt`, records the `:current-work` pointer + `:request-id`, and
  attaches `owner` to `:active-owners`. Clears `:invalidated-at` (the load
  satisfies any pending invalidation). Per Spec 016 §Status semantics /
  §Lifecycle is an FSM / §Frame work ledger."
  [entry {:keys [generation work-id request-id owner]}]
  (let [had-data? (has-data? entry)]
    (cond-> (assoc entry
                   :status       (next-status (:status entry) :start-load had-data?)
                   :generation   generation
                   :attempt      (inc (:attempt entry 0))
                   :current-work work-id
                   :request-id   request-id
                   ;; a fresh first load clears a prior first-load error; a
                   ;; refresh keeps prior data + clears stale refresh-error
                   ;; lazily on success (Spec 016 §Status semantics)
                   :error          (if had-data? (:error entry) nil)
                   :invalidated-at nil)
      owner (update :active-owners (fnil conj #{}) owner))))

(defn entry-succeeded
  "Transition an entry to `:loaded` on a successful load/refresh. Applies
  STRUCTURAL SHARING: preserves the old `:data` value (identity) when the
  newly-decoded `new-data` is `=` to the previous data, so downstream subs
  stay quiet. Sets `:loaded-at` / `:stale-at` from the supplied clock +
  stale policy, clears `:error` / `:refresh-error` / `:current-work`, and
  records the produced `:tags`. Per Spec 016 §Status semantics /
  §Structural sharing."
  [entry {:keys [data loaded-at stale-at tags]}]
  (let [prev      (:data entry)
        ;; Structural sharing: keep the OLD value when the decoded value is
        ;; equal, so `(identical? old new-data)` holds for quiet downstream
        ;; reactions. Per Spec 016 §Structural sharing.
        shared    (if (and (some? prev) (= prev data)) prev data)]
    (assoc entry
           :status        :loaded
           :data          shared
           :error         nil
           :refresh-error nil
           :loaded-at     loaded-at
           :stale-at      stale-at
           :invalidated-at nil
           :current-work  nil
           ;; the new key now has its OWN data — drop the previous-key
           ;; projection pointer (Spec 016 §Paginated and previous data).
           :previous-key  nil
           :tags          (or tags (:tags entry) #{}))))

(defn entry-failed
  "Transition an entry on a failed load/refresh (Spec 016 §Status
  semantics). A FIRST-load failure (no usable data) settles `:error` with
  the failure envelope and no data. A BACKGROUND-refresh failure (entry was
  `:fetching`, prior data present) returns to `:loaded`, PRESERVES the
  prior `:data`, and records `:refresh-error`. `next-status` decides which.
  Clears `:current-work`."
  [entry {:keys [error]}]
  (let [had-data?   (has-data? entry)
        next        (next-status (:status entry) :failure had-data?)]
    (if (= :loaded next)
      ;; background-refresh failure — keep data, record refresh-error
      (assoc entry
             :status        :loaded
             :refresh-error error
             :current-work  nil)
      ;; first-load failure — no usable data
      (assoc entry
             :status        :error
             :error         error
             :data          nil
             :current-work  nil))))

;; ---- reverse-index recompute (Spec 016 §Restore and replay part 5) --------
;;
;; `:tag-index` and `:owner-index` are DERIVED projections of the entries'
;; `:tags` and `:active-owners`. They are recomputable-from-`:entries`: on
;; restore / SSR-hydration they are rebuilt from the installed `:entries`
;; rather than trusted from the snapshot, so a stale or partial index can
;; never outlive the entries it describes. The runtime keeps them in step
;; incrementally, but `recompute-indexes` is the single authoritative
;; rebuild both restore and an in-cascade index repair use.

(defn recompute-indexes
  "Rebuild `:tag-index` (`{<tag> #{<scoped-key> …}}`) and `:owner-index`
  (`{<owner> #{<scoped-key> …}}`) from the resource subtree's `:entries`.
  Returns the resource subtree with both indexes replaced. Per Spec 016
  §Restore and replay part 5 / §Cache home."
  [resources-subtree]
  (let [entries (:entries resources-subtree)]
    (reduce-kv
      (fn [acc k entry]
        (-> acc
            (update :tag-index
                    (fn [ti] (reduce (fn [ti tag] (update ti tag (fnil conj #{}) k))
                                     ti (:tags entry))))
            (update :owner-index
                    (fn [oi] (reduce (fn [oi owner] (update oi owner (fnil conj #{}) k))
                                     oi (:active-owners entry))))))
      (assoc resources-subtree :tag-index {} :owner-index {})
      entries)))

;; ---- host-side transient generation allocator -----------------------------
;;
;; Per Spec 016 §Restore and replay part 1: the generation allocator is a
;; per-frame, HOST-SIDE monotonic high-water mark — never rewound by epoch
;; restore, so a pre-restore in-flight reply's generation can never match a
;; post-restore live entry (stale-suppression is structurally safe). This
;; is deliberately the OPPOSITE discipline from machine spawn-ids (which
;; never escape the frame and so may be snapshot-local).
;;
;; The PURE SEAM (handlers stay pure), mirroring routing's nav-counters
;; (rf2-oosjmh): READ via the `:rf.resource/generation` cofx (injects the
;; active frame's high-water snapshot); the handler mints the next
;; generation purely from the snapshot; WRITE via the
;; `:rf.resource/commit-generation` fx (records the new high-water mark,
;; monotone). A frame's entry is released on frame destroy.

(defonce
  ^{:doc "Per-frame host-side generation high-water marks
   `{<frame-id> <int>}`. Host-side transient state (NOT runtime-db), so an
   epoch restore cannot rewind it and recycle a generation — the
   anti-recycling correctness boundary (Spec 016 §Restore and replay part
   1). Read via the `:rf.resource/generation` cofx, bumped via the
   `:rf.resource/commit-generation` fx (both monotone)."}
  generation-cache
  (atom {}))

(defn generation-snapshot
  "Read `frame-id`'s current generation high-water mark from the host
  `generation-cache` (0 when none). The value the
  `:rf.resource/generation` cofx threads into the pure resource handlers."
  [frame-id]
  (get @generation-cache frame-id 0))

(defn next-generation
  "Pure: given a high-water `snapshot` int (or nil), return the next
  monotone generation `(inc snapshot)`. Does NOT mutate — the handler uses
  the value and emits a `:rf.resource/commit-generation` fx carrying it."
  [snapshot]
  (inc (or snapshot 0)))

(defn commit-generation!
  "Record `n` as `frame-id`'s generation high-water mark in the host
  `generation-cache`. MONOTONE — never lowers an existing value (a `max`
  install), so a reordered / replayed commit can never rewind the allocator
  and recycle a generation. Per Spec 016 §Restore and replay part 1.
  Returns nil."
  [frame-id n]
  (swap! generation-cache update frame-id (fn [cur] (max (or cur 0) n)))
  nil)

(defn release-frame!
  "Drop the destroyed frame's host-side generation high-water mark.
  Invoked by the resources frame-destroy teardown hook. Per Spec 016
  §Stale and GC scheduling (frame destroy cancels all resource timers /
  clears host handles for that frame) and §Restore and replay part 5."
  [frame-id]
  (swap! generation-cache dissoc frame-id)
  nil)

(defn reset-cache!
  "Drop EVERY frame's host-side generation high-water mark (test
  isolation). Published as a reset hook so the shared CLJS
  `make-reset-runtime-fixture` reset-hooks table clears it per test (it is
  host-side transient state, not cleared by the runtime/frames reset)."
  []
  (reset! generation-cache {})
  nil)

;; ---- the :rf.resource/generation cofx + :rf.resource/commit-generation fx -
;;
;; The pure read/write seam over the host-side allocator (mirrors routing's
;; :rf.route/nav-counters cofx + :rf.route/commit-nav-counter fx). The
;; resource event handlers that mint a generation (ensure / refetch) inject
;; the cofx; the WRITE half rides the fx, emitted only on the branch that
;; actually allocates a generation.

(def generation-cofx-meta
  "Metadata for the `:rf.resource/generation` cofx registration. Injects
  the active frame's host-side generation high-water mark so the resource
  handlers mint the next monotone generation purely."
  {:doc "The active frame's host-side resource-generation high-water mark
(an int), read from the `re-frame.resources.state` host cache and injected
under `:coeffects :rf.resource/generation`. The ensure/refetch handlers read
it to mint the next monotone generation without reaching the host atom
(handlers stay pure); the actual high-water bump rides the
`:rf.resource/commit-generation` fx. Per Spec 016 §Restore and replay."})

(defn generation-cofx
  "Handler fn for the `:rf.resource/generation` cofx. Reads the in-flight
  cascade's frame from the `:rf.frame/id` coeffect and injects the frame's
  host-side generation snapshot under `:coeffects :rf.resource/generation`.
  Pure with respect to the handler — it only reads the host cache (the
  write is a separate fx). 2-arity accepts an explicit snapshot override
  for tests."
  ([ctx]
   (let [frame-id (get-in ctx [:coeffects :rf.frame/id])]
     (assoc-in ctx [:coeffects :rf.resource/generation]
               (generation-snapshot frame-id))))
  ([ctx snapshot]
   (assoc-in ctx [:coeffects :rf.resource/generation] snapshot)))

(def commit-generation-meta
  "Metadata for the `:rf.resource/commit-generation` fx registration. The
  WRITE half of the host-side generation seam: records a new monotone
  high-water mark into the host `generation-cache`. Universal platform —
  the allocator is host-side on both client and server."
  {:doc "Record a new monotone generation high-water mark into the host-side
`re-frame.resources.state` cache. Args: `{:value N}`. Emitted by the
ensure/refetch handlers on the branch that allocates a generation; the WRITE
counterpart to the `:rf.resource/generation` cofx read. Per Spec 016
§Restore and replay."})

(defn commit-generation-handler
  "`:rf.resource/commit-generation` fx handler. Registered by the façade so
  a `:reload` re-wires it on a fresh registrar. Writes the new monotone
  high-water mark under the cascade-envelope frame into the host
  `generation-cache`. The carried-frame invariant (EP-0002): the fx context
  carries the cascade frame as `:frame`; a nil stamp is an invariant
  failure (`:rf.error/no-frame-context`), never a synthesised default."
  [{:keys [frame]} {:keys [value]}]
  (let [frame-id (frame/require-frame-stamp!
                   frame :rf.resource/commit-generation
                   {:where 'rf.resource/commit-generation-handler})]
    (when (number? value)
      (commit-generation! frame-id value))
    nil))
