(ns re-frame.resources.state
  "Resource runtime-db paths, the durable entry and work-row shapes, and the
  pure transitions over them. The runtime swaps over these paths live in the
  sibling namespaces; the paths and shapes are pinned here so every sibling
  agrees on one home. The cache lives at `:rf.runtime/resources` and the
  work ledger at `:rf.runtime/work-ledger`, both reserved runtime-db keys,
  never app-db. Spec 016 §Cache home and write authority."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db paths ----------------------------------------------
;;
;; Paths here are relative to runtime-db; in a full frame-state projection the
;; subtree sits under `:rf.db/runtime`.

(def resources-key
  "The reserved runtime-db key of the resource cache.
  Spec 016 §Cache home and write authority."
  :rf.runtime/resources)

(def work-ledger-key
  "The reserved runtime-db key of the frame work ledger.
  Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(defn key-id
  "The map key for a scoped resource key: its canonical-bytes string. The
  `:entries` map, the reverse indexes and the work ledger are keyed on it
  rather than the vector, because Clojure `=` collapses a vector and a list
  of the same elements while their canonical identities differ. The string
  is plain EDN, so it rides every wire with no custom handler. Total on an
  already-canonical key. Conventions §Canonical EDN identity."
  [scoped-key]
  (rf.identity/canonical-bytes scoped-key))

(defn entries-path
  "Path to the entries map `{<key-id> <entry>}`.
  Spec 016 §Cache home and write authority."
  []
  [resources-key :entries])

(defn tag-index-path
  "Path to the reverse tag index `{<tag> #{<key-id> …}}`, rebuilt on restore
  and never trusted from a snapshot.
  Spec 016 §5. Transient side tables and indexes are recomputed or cleared on install"
  []
  [resources-key :tag-index])

(defn owner-index-path
  "Path to the reverse owner index `{<owner> #{<key-id> …}}`, rebuilt on
  restore. Spec 016 §5. Transient side tables and indexes are recomputed or cleared on install"
  []
  [resources-key :owner-index])

;; ---- tag-set input normalization ------------------------------------------------

(defn lone-tag?
  "True iff `raw` is one tag written directly: a non-empty vector whose head
  is not a collection. Spec 016 §Invalidation."
  [raw]
  (and (vector? raw)
       (seq raw)
       (not (coll? (first raw)))))

(defn normalize-tag-set
  "Lowers a bare tag-set input to `#{<tag> …}`. A lone tag vector becomes the
  one-tag set; a naive `(set raw)` would split it into scalars that match
  nothing. Both tag-set input surfaces route through here.
  Spec 016 §Invalidation."
  [raw]
  (if (lone-tag? raw)
    #{raw}
    (set raw)))

(defn entry-path
  "Path to the entry for the scoped key vector, keyed on its `key-id`.
  Spec 016 §Resource identity."
  [scoped-resource-key]
  [resources-key :entries (key-id scoped-resource-key)])

(defn entry-path-by-id
  "Path to the entry for an already-computed `key-id`, such as a reverse-index
  member, which must not be re-transformed."
  [k-id]
  [resources-key :entries k-id])

;; ---- framework-write authority ----------------------------------------------

(def framework-authority-meta
  "Registration meta stamped on every resource event handler, so its
  runtime-db effect reads as a framework write rather than tripping the
  `:rf.warning/app-handler-runtime-effect` diagnostic.
  Spec 016 §Write authority."
  {:rf/framework-authority? true})

;; ---- durable shapes ---------------------------------------------------------------

(def terminal-work-statuses
  "The terminal work-ledger statuses.
  Spec 016 §Ledger row retention and identity."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn- empty-entry*
  "The base `:idle` entry for `resource-id`, `:resource/key` nil."
  [resource-id]
  {:resource/id    resource-id
   :resource/key   nil
   :status         :idle
   :data           nil
   :error          nil
   :refresh-error  nil
   :loaded-at      nil
   :stale-at       nil
   :invalidated-at nil
   :attempt        0
   :generation     0
   ;; The per-entry write identity the optimistic settle compares, bumped on
   ;; every authoritative write. Distinct from `:generation`, which bumps at
   ;; load START and would false-conflict on every in-flight refetch.
   ;; EP-0019 §Decision 2: recording — snapshot inverse + per-entry revision token.
   :revision       0
   :request-id     nil
   :current-work   nil
   :tags           #{}
   :active-owners  #{}
   ;; A projection pointer to the prior key whose data shows while this key
   ;; first-loads under `:keep-previous?`; never copied into `:data`.
   :previous-key   nil})

(defn empty-entry
  "An empty `:idle` entry for `resource-id`. Entries store facts, never the
  derived booleans. The 2-arity stamps the kind-preserving scoped key vector
  as `:resource/key`, since the map key is the opaque `key-id`.
  Spec 016 §Status semantics."
  ([resource-id] (empty-entry* resource-id))
  ([resource-id scoped-key]
   (cond-> (empty-entry* resource-id)
     scoped-key (assoc :resource/key scoped-key))))

;; ---- canonicalization ---------------------------------------------------------------
;;
;; Resource params and scopes are canonical-EDN identities through the shared
;; `re-frame.identity` algebra; there is no resource-local dialect. These
;; wrappers keep the public resource error categories.
;; Conventions §Canonical EDN identity.
;;
;; A payload `:params` slot is a value, an explicit nil, or absent, and the
;; boundary must not fold them: the `:params-schema` decides whether nil
;; conforms. Absent defaults to `{}` only at the payload boundary, so a handler
;; threads presence explicitly as `missing-params`.

(def missing-params
  "The sentinel a payload boundary threads for an absent `:params` slot,
  distinct from an explicit nil. Spec 016 §Resource identity."
  ::missing-params)

(defn params-present?
  "`missing-params` when `payload` has no `:params` key, else its value
  (possibly nil)."
  [payload]
  (if (contains? payload :params)
    (:params payload)
    missing-params))

(defn default-omitted-params
  "Lowers `missing-params` to `{}`; any present value, explicit nil
  included, passes through for the schema to judge.
  Spec 016 §Resource identity."
  [params]
  (if (= params missing-params) {} params))

(defn serializable-edn?
  "True iff `x` is a portable canonical-EDN value. Host values and
  non-portable numbers are rejected. Conventions §Canonical EDN identity."
  [x]
  (try
    (rf.identity/canonical-bytes x)
    true
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn canonicalize
  "The canonical EDN value of `x` for a cache key. Throws
  `:rf.error/non-edn-identity` for an out-of-domain value or duplicate
  canonical map keys; callers wanting the public resource error use
  `canonicalize-or-rethrow`. Spec 016 §Canonicalization rule."
  [x]
  (rf.identity/canonical x))

(defn- throw-non-edn!
  "Throws `:rf.error/resource-non-edn-params` for `value`; the one home of
  that error shape."
  [value where kind resource-id]
  (rf.error/throw-error!
    :rf.error/resource-non-edn-params
    where
    (str "resource " resource-id " " (name kind)
         " is not a portable CEDN-1 EDN identity — "
         "host / opaque values (functions, promises, "
         "DOM nodes, AbortControllers, JS objects) and "
         "non-portable numbers (floats, ratios, decimals, "
         "NaN/infinities, integers outside the safe range) "
         "are rejected at the cache-key boundary; instants "
         "are accepted. Put every value that affects remote "
         "identity in params as plain portable EDN, and encode "
         "a float as a fixed-point integer or a string. Per Spec 016 §Resource "
         "identity / Conventions §Canonical EDN identity.")
    {:recovery :fix-params
     :extra    {:resource-id resource-id
                :kind        kind
                :value       (pr-str value)}}))

(defn reject-non-edn!
  "Returns `value` when it is portable canonical EDN, else throws
  `:rf.error/resource-non-edn-params`. `kind` is `:params` or `:scope`.
  Validate-only: for the canonical value use `canonicalize-or-rethrow`,
  which validates in the same walk. Spec 016 §Resource identity."
  [value where kind resource-id]
  (when-not (serializable-edn? value)
    (throw-non-edn! value where kind resource-id))
  value)

(defn canonicalize-or-rethrow
  "The canonical value of `value` in one walk, rethrowing a domain failure as
  `:rf.error/resource-non-edn-params`. Equivalent to `reject-non-edn!` then
  `canonicalize`, which would walk twice. Spec 016 §Canonicalization rule."
  [value where kind resource-id]
  (try
    (canonicalize value)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (if (= :rf.error/non-edn-identity (:rf.error/id (ex-data e)))
        (throw-non-edn! value where kind resource-id)
        (throw e)))))

;; ---- concrete-scope validation ------------------------------------------------------

(def reserved-scope-ns
  "The reserved scope namespace. A bare keyword in it is a closed enum; a
  scope tuple such as `[:rf.scope/session {…}]` is not governed by it."
  "rf.scope")

(def reserved-concrete-scopes
  "The bare `:rf.scope/*` keywords valid as a concrete scope.
  Spec 016 §Scope resolution."
  #{:rf.scope/global})

(defn reserved-scope-typo?
  "True when `scope` is a bare `:rf.scope/*` keyword outside
  `reserved-concrete-scopes`, such as `:rf.scope/glabal`."
  [scope]
  (and (keyword? scope)
       (= reserved-scope-ns (namespace scope))
       (not (contains? reserved-concrete-scopes scope))))

(defn reject-wrapped-reserved-scope!
  "Returns `scope`, or throws `:rf.error/resource-invalid-scope` for the
  singleton `[:rf.scope/global]` spelling: the global scope is the bare
  keyword. Spec 016 §Scope resolution."
  [scope where resource-id]
  (when (and (vector? scope)
             (contains? reserved-concrete-scopes (first scope)))
    (rf.error/throw-error!
      :rf.error/resource-invalid-scope
      where
      (str "resource " resource-id " was reached with a "
           "scope " (pr-str scope) " — a reserved "
           ":rf.scope/* concrete scope wrapped in a "
           "vector. The global scope IS the bare keyword "
           ":rf.scope/global; supply it bare, not as the "
           "singleton [:rf.scope/global]. Per Spec 016 "
           "§Scope resolution.")
      {:recovery :fix-scope
       :extra    {:resource-id resource-id
                  :scope       scope}}))
  scope)

(defn reject-reserved-scope-typo!
  "Returns `scope`, or throws `:rf.error/resource-invalid-scope` for a
  reserved-namespace typo, which would otherwise resolve to a silent wrong
  cache scope. Spec 016 §Scope resolution."
  [scope where resource-id]
  (when (reserved-scope-typo? scope)
    (rf.error/throw-error!
      :rf.error/resource-invalid-scope
      where
      (str "resource " resource-id " was reached with a "
           "scope " (pr-str scope) " in the framework-"
           "reserved :rf.scope/* namespace that is not a "
           "valid concrete scope. The only concrete "
           "reserved scope is :rf.scope/global; any "
           "other :rf.scope/* keyword is a typo. A "
           "framework-namespace typo MUST fail closed "
           "rather than become a silent wrong cache "
           "scope. Per Spec 016 §Scope resolution.")
      {:recovery :fix-scope
       :extra    {:resource-id resource-id
                  :scope       scope}}))
  scope)

(defn from-db-reference-scope?
  "True iff `scope` is a `{:from-db <resolver-id>}` reference. It lives at
  the concrete-scope boundary, where a reference is refused;
  `scope-registry/from-db-reference?` delegates here because the dependency
  runs this way. Spec 016 §Resolver references — `{:from-db <id>}`"
  [scope]
  (and (map? scope) (contains? scope :from-db)))

(defn reject-from-db-reference-scope!
  "Returns `scope`, or throws `:rf.error/resource-invalid-scope` for a
  `{:from-db …}` reference reaching a concrete-scope boundary. A map is a
  valid literal scope, so without this a reference would key nothing and
  silently no-op. Spec 016 §Scope resolution."
  [scope where resource-id]
  (when (from-db-reference-scope? scope)
    (rf.error/throw-error!
      :rf.error/resource-invalid-scope
      where
      (str "resource " resource-id " was reached with a "
           "scope " (pr-str scope) " — a {:from-db …} named-"
           "resolver REFERENCE where a CONCRETE scope is "
           "required. A reference is a policy spelling, not "
           "a cache scope: accepting it here would key the "
           "cache on the literal reference map, match "
           "nothing, and silently do nothing. Resolve it "
           "first against the db you mean — "
           "(rf/resolve-resource-scope db "
           (pr-str (:from-db scope)) ") — and pass the "
           "concrete scope it returns. Per Spec 016 "
           "§Scope resolution.")
      {:recovery :fix-scope
       :extra    {:resource-id resource-id
                  :scope       scope}}))
  scope)

(defn canonicalize-scope
  "The one validation and canonicalization path for a concrete scope:
  rejects a reserved typo, a wrapped global, a `{:from-db …}` reference and a
  host value, then returns the canonical scope. Every scope-bearing operation
  routes through it. Spec 016 §Scope resolution."
  [scope where resource-id]
  (reject-reserved-scope-typo! scope where resource-id)
  (reject-wrapped-reserved-scope! scope where resource-id)
  (reject-from-db-reference-scope! scope where resource-id)
  (canonicalize-or-rethrow scope where :scope resource-id))

;; ---- scoped resource key ----------------------------------------------------------

(defn scoped-resource-key*
  "Assembles `[scope resource-id params]` from ALREADY-canonical scope and
  params without re-canonicalizing; for the resolution hot paths, where a
  sub re-runs it on every frame-state change. Raw callers use
  `scoped-resource-key`. Spec 016 §Canonicalization rule."
  [scope resource-id params]
  [scope resource-id params])

(defn scoped-resource-key
  "The canonical scoped key `[canonical-scope resource-id canonical-params]`,
  canonicalizing both. The vector is kind-preserving and travels as the
  entry's `:resource/key`, in the work-id and on the wire; maps key on its
  `key-id`. Spec 016 §Resource identity."
  [scope resource-id params]
  (scoped-resource-key* (canonicalize scope) resource-id (canonicalize params)))

(defn prior-loaded-sibling-key
  "The scoped key of the most recently loaded entry with the same scope and
  resource id as `new-key` but different params and usable data, or nil.
  Reads each entry's `:resource/key`, since `entries` keys are opaque.
  Spec 016 §Paginated and previous data."
  [entries new-key]
  (let [[scope rid params] new-key]
    (->> entries
         (keep (fn [[_k-id entry]]
                 (let [[s r p] (:resource/key entry)]
                   (when (and (= s scope) (= r rid) (not= p params)
                              (some? (:data entry)))
                     [(:resource/key entry) (:loaded-at entry)]))))
         (sort-by (fn [[_ loaded-at]] (or loaded-at 0)) >)
         ffirst)))

;; ---- compact lifecycle FSM --------------------------------------------------------

(defn next-status
  "The entry status after `signal` (`:start-load`, `:success`, `:failure`)
  from `status`. A start-load is `:loading` without data and `:fetching`
  with it; a failure from `:fetching` returns to `:loaded`, else `:error`.
  The one home of entry status semantics. Spec 016 §Lifecycle is an FSM."
  [status signal has-data?]
  (case signal
    :start-load (if has-data? :fetching :loading)
    :success    :loaded
    :failure    (if (= :fetching status) :loaded :error)
    status))

(declare infinite-entry?)

(defn has-data?
  "True iff the entry has usable `:data`. An infinite feed's `:data` is its
  page vector, seeded `[]`, so it has data only once a page has loaded.
  Spec 016 §Status semantics."
  [entry]
  (let [data (:data entry)]
    (if (infinite-entry? entry)
      (boolean (seq data))
      (some? data))))

(defn entry-stale?
  "True iff `entry` is invalidated or its `:stale-at` has passed at
  `clock-ms`. Computed from durable timestamps, never from a timer, and
  orthogonal to load status. Spec 016 §Freshness clock contract."
  [entry clock-ms]
  (boolean
    (and entry
         (or (some? (:invalidated-at entry))
             (when-let [sa (:stale-at entry)] (>= clock-ms sa))))))

;; ---- shared stale / timer helpers -------------------------------------------------
;;
;; Shared by the read path and the mutation-success path, so a patched entry
;; ages exactly as a fetched one.

(defn stale-at-for
  "`loaded-at` plus the spec's `:stale-after-ms`, or nil without a policy.
  Spec 016 §Stale and GC scheduling."
  [spec loaded-at]
  (when-let [ms (:stale-after-ms spec)]
    (+ loaded-at ms)))

(defn positive-or-nil
  "`ms` when positive, else nil, so an elapsed or skewed deadline never arms
  a negative delay."
  [ms]
  (when (and (number? ms) (pos? ms)) ms))

(defn stale-wake-delay-ms
  "The delay from `now-ms` to `entry`'s `:stale-at`, or nil. A stale timer
  armed outside a load settle uses the remaining window so it wakes at the
  deadline. Spec 016 §Stale and GC scheduling."
  [entry now-ms]
  (positive-or-nil (some-> (:stale-at entry) (- now-ms))))

(defn server-frame?
  "True iff `frame-id`'s own `:platform` is `:server`. It ignores the
  host-wide default, which is `:server` on the JVM, so a JVM client-mode test
  still arms timers. Spec 016 §Stale and GC scheduling."
  [frame-id]
  (= :server (:platform (rf.frame/frame-meta frame-id))))

;; ---- per-entry revision --------------------------------------------------------
;;
;; The optimistic settle compares recorded and current `:revision` to ask
;; whether an authoritative write landed since its apply; if one did, a blind
;; restore would clobber newer truth. So bump on EVERY authoritative write a
;; rollback could clobber, never gated on `(= old new)` of `:data`: success,
;; patch and populate re-stamp freshness and tags even when data is shared. A
;; false conflict costs one refetch; a missed one is silent corruption.
;; EP-0019 §Decision 2: recording — snapshot inverse + per-entry revision token.

(defn bump-revision
  "Increments `:revision` (absent reads as 0); a nil entry is returned as is.
  The one home of the bump."
  [entry]
  (if entry
    (update entry :revision (fnil inc 0))
    entry))

(defn entry-revision
  "The entry's `:revision`, 0 for a nil entry or none recorded."
  [entry]
  (if entry (:revision entry 0) 0))

(defn entry-invalidate
  "Marks `entry` stale at `invalidated-at` and bumps `:revision`; nil stays
  nil. Records the in-flight attempt as `:invalidated-during`, since that
  attempt's request predates the mark and its success must not clear it
  (judged by attempt identity, never milliseconds, which tie). Drops any
  sweep's `:sweep-covers-invalidation?` claim, since the sweep began before
  this mark. Spec 016 §Invalidation."
  [entry invalidated-at]
  (if entry
    (bump-revision
      (let [marked (-> entry
                       (assoc :invalidated-at invalidated-at)
                       (dissoc :sweep-covers-invalidation?))]
        (if-let [w (:current-work entry)]
          (assoc marked :invalidated-during w)
          (dissoc marked :invalidated-during))))
    entry))

(defn invalidation-kept-by-settle
  "The `:invalidated-at` a success of `entry`'s current attempt leaves: the
  mark when it was written during that attempt, else nil. Read against the
  pre-settle entry. Spec 016 §Race and in-flight semantics."
  [entry]
  (let [w (:current-work entry)]
    (when (and (some? w) (= w (:invalidated-during entry)))
      (:invalidated-at entry))))

;; ---- owner-liveness entry writes ------------------------------------------------
;;
;; A no-conflict optimistic rollback restores the `:before` snapshot whole,
;; `:active-owners` included, so an owner change between apply and settle must
;; bump `:revision`. Otherwise a restore resurrects a released owner (the entry
;; never GCs and its poll re-arms forever) or drops a new one (premature GC).
;; The bump is gated on the set actually changing; callers maintain the
;; derived `:owner-index`.

(defn attach-owner
  "Adds `owner` to `:active-owners`, bumping `:revision` only when it was
  new; a nil owner is a no-op. Spec 016 §Active owners and causes."
  [entry owner]
  (if (and (some? owner) (not (contains? (:active-owners entry) owner)))
    (-> entry
        (update :active-owners (fnil conj #{}) owner)
        bump-revision)
    entry))

(defn detach-owner
  "Removes `owner` from `:active-owners`, bumping `:revision` only when it
  was present; a nil entry is returned as is.
  Spec 016 §Active owners and causes."
  [entry owner]
  (if (and entry (contains? (:active-owners entry) owner))
    (-> entry
        (update :active-owners disj owner)
        bump-revision)
    entry))

;; ---- entry transitions ---------------------------------------------------------

(defn entry-start-load
  "Moves an entry in flight (`:loading` or `:fetching`), sets `:generation`,
  `:current-work` and `:request-id`, increments `:attempt` and attaches
  `owner`. It leaves `:invalidated-at` alone: only a settle that produced
  data clears it, and clearing it here would let a failed or aborted attempt
  leave the entry reading fresh over pre-mutation data. `:stale?` reading
  true while the refetch runs is stale-while-revalidate, and the ensure gate
  and focus scan both skip in-flight entries. Spec 016 §Status semantics."
  [entry {:keys [generation work-id request-id owner]}]
  (let [had-data? (has-data? entry)]
    (cond-> (assoc entry
                   :status       (next-status (:status entry) :start-load had-data?)
                   :generation   generation
                   :attempt      (inc (:attempt entry 0))
                   :current-work work-id
                   :request-id   request-id
                   :error          (if had-data? (:error entry) nil))
      owner (update :active-owners (fnil conj #{}) owner))))

(defn entry-succeeded
  "Settles an entry `:loaded`: keeps the old `:data` when the new value is
  `=` (structural sharing keeps subs quiet), stamps `:loaded-at`,
  `:stale-at` and `:tags`, clears errors, `:current-work` and
  `:previous-key`, and bumps `:revision` unconditionally.
  Spec 016 §Structural sharing."
  [entry {:keys [data loaded-at stale-at tags]}]
  (let [prev      (:data entry)
        shared    (if (and (some? prev) (= prev data)) prev data)]
    (-> entry
        (assoc
          :status        :loaded
          :data          shared
          :error         nil
          :refresh-error nil
          :loaded-at     loaded-at
          :stale-at      stale-at
          :invalidated-at (invalidation-kept-by-settle entry)
          :current-work  nil
          :previous-key  nil
          :tags          (or tags (:tags entry) #{}))
        (dissoc :invalidated-during)
        bump-revision)))

(defn entry-failed
  "Settles a failed load: a first-load failure goes `:error` with no data;
  a refresh failure returns to `:loaded`, keeps `:data` and records
  `:refresh-error`. Clears `:current-work` and bumps `:revision`, so a
  rollback cannot resurrect the in-flight state. Spec 016 §Status semantics."
  [entry {:keys [error]}]
  (let [had-data?   (has-data? entry)
        next        (next-status (:status entry) :failure had-data?)]
    (bump-revision
      (if (= :loaded next)
        (assoc entry
               :status        :loaded
               :refresh-error error
               :current-work  nil)
        (assoc entry
               :status        :error
               :error         error
               :data          nil
               :current-work  nil)))))

;; ---- infinite-feed entry refinement and transitions ----------------------------
;;
;; An infinite feed is the same entry with `:infinite? true`, `:data` as the
;; ordered page vector, and `:page-params`, `:next-page-param`,
;; `:prev-page-param` and `:page-error` beside it. There is no sixth status: a
;; load-more reuses `:fetching`. Everything else is derived in subs.
;; Spec 016 §Durable cache shape (R1).

(defn infinite-entry?
  "True iff `entry` is an infinite feed. Spec 016 §Durable cache shape (R1)."
  [entry]
  (true? (:infinite? entry)))

(def initial-page-param
  "The default page-0 param, nil; a resource may declare
  `:initial-page-param`. Spec 016 §Registration — `:infinite`"
  nil)

(defn page-param-for-spec
  "The page-0 param for `spec`. Spec 016 §Registration — `:infinite`"
  [spec]
  (get spec :initial-page-param initial-page-param))

(defn empty-infinite-entry
  "An empty `:idle` feed entry, with the page vector and page params seeded
  `[]` so the transitions reason about a vector from the start.
  Spec 016 §Durable cache shape (R1)."
  ([resource-id] (empty-infinite-entry resource-id nil))
  ([resource-id scoped-key]
   (assoc (empty-entry resource-id scoped-key)
          :infinite?       true
          :data            []
          :page-params     []
          :next-page-param nil
          :prev-page-param nil
          :page-error      nil)))

(defn next-param-for
  "The next page param from `(next-page-param-fn last-page pages)`, or nil,
  the one terminal. Nil for no pages. Spec 016 §Registration — `:infinite`"
  [next-page-param-fn pages]
  (when (and next-page-param-fn (seq pages))
    (next-page-param-fn (peek pages) pages)))

(defn prev-param-for
  "The prev page param from `(prev-page-param-fn first-page pages)`, or nil.
  Observable as `:has-prev-page?` though no prepend event exists.
  Spec 016 §Registration — `:infinite`"
  [prev-page-param-fn pages]
  (when (and prev-page-param-fn (seq pages))
    (prev-page-param-fn (first pages) pages)))

(defn terminal?
  "True iff `next-param` is the terminal nil. Spec 016 §Registration — `:infinite`"
  [next-param]
  (nil? next-param))

(defn page-count
  "The number of accumulated pages; 0 for an ordinary entry."
  [entry]
  (count (:data entry)))

(defn- settle-page-invalidation
  "Writes onto `settled` the stale mark a page success at `page-index`
  leaves. A page covers less than a scalar reply, so: a mark written during
  this attempt survives; page 0 covers an older mark at once with no sweep
  following, otherwise the sweep carries it (`:sweep-covers-invalidation?`);
  an appended page keeps it; a sweep leg clears a carried mark only when it
  is the last leg. `entry` is the pre-settle entry.
  Spec 016 §Refetch and invalidation of an infinite feed."
  [settled entry page-index]
  (let [mark      (:invalidated-at entry)
        sweeping? (boolean (seq (:refetch-sweep entry)))
        carried?  (true? (:sweep-covers-invalidation? entry))
        [mark' carry?]
        (cond
          (nil? mark)                         [nil false]
          (invalidation-kept-by-settle entry) [mark false]
          (zero? page-index)                  [(when sweeping? mark) sweeping?]
          (>= page-index (page-count entry))  [mark carried?]
          (and carried? (not sweeping?))      [nil false]
          :else                               [mark carried?])]
    (-> settled
        (assoc :invalidated-at mark')
        (dissoc :invalidated-during :sweep-covers-invalidation?)
        (cond-> carry? (assoc :sweep-covers-invalidation? true)))))

(defn entry-append-page
  "Appends `:page` and `:page-param`, recomputes both cursors, clears
  `:page-error`, re-stamps freshness, returns to `:loaded` and bumps
  `:revision`. Prior pages stay identical. Nil stays nil.
  Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  [entry {:keys [page next-page-param-fn prev-page-param-fn page-param loaded-at stale-at]}]
  (if entry
    (let [pages' (conj (or (:data entry) []) page)]
      (-> entry
          (assoc :status          :loaded
                 :data            pages'
                 :page-params     (conj (or (:page-params entry) []) page-param)
                 :next-page-param (next-param-for next-page-param-fn pages')
                 :prev-page-param (prev-param-for prev-page-param-fn pages')
                 :page-error      nil
                 :refresh-error   nil
                 :loaded-at       loaded-at
                 :stale-at        stale-at
                 :current-work    nil)
          (settle-page-invalidation entry (page-count entry))
          bump-revision))
    entry))

(defn entry-page-failed
  "A load-more failure, the third error channel: returns to `:loaded`, keeps
  every page and records `:page-error`. Clears `:current-work` and bumps
  `:revision`. Nil stays nil.
  Spec 016 §Causal event — `:rf.resource/load-more` (R2)"
  [entry {:keys [error]}]
  (if entry
    (bump-revision
      (assoc entry
             :status       :loaded
             :page-error   error
             :current-work nil))
    entry))

(defn entry-replace-page
  "Replaces the page at `:page-index` in place without growing the feed (a
  window-preserving refetch), otherwise settling like `entry-append-page`.
  An index at or past the tail delegates to `entry-append-page`. Nil stays
  nil. Spec 016 §Refetch and invalidation of an infinite feed."
  [entry {:keys [page next-page-param-fn prev-page-param-fn page-param page-index
                 loaded-at stale-at] :as opts}]
  (if entry
    (let [pages (or (:data entry) [])]
      (if (>= page-index (count pages))
        (entry-append-page entry opts)
        (let [prev-page (nth pages page-index)
              shared    (if (and (some? prev-page) (= prev-page page)) prev-page page)
              pages'    (assoc pages page-index shared)
              params'   (assoc (or (:page-params entry) []) page-index page-param)]
          (-> entry
              (assoc :status          :loaded
                     :data            pages'
                     :page-params     params'
                     :next-page-param (next-param-for next-page-param-fn pages')
                     :prev-page-param (prev-param-for prev-page-param-fn pages')
                     :page-error      nil
                     :refresh-error   nil
                     :loaded-at       loaded-at
                     :stale-at        stale-at
                     :current-work    nil)
              (settle-page-invalidation entry page-index)
              bump-revision))))
    entry))

(defn refetch-window-count
  "How many leading pages a refetch refreshes: 1 by default,
  `page-count` under `:refetch-all-pages?`, `:refetch-window` clamped to
  `[1, page-count]`, and 0 for an empty feed. The accumulation is never
  truncated. Spec 016 §Refetch and invalidation of an infinite feed."
  [refetch-policy page-count]
  (let [{:keys [refetch-all-pages? refetch-window]} refetch-policy]
    (cond
      (zero? page-count)          0
      refetch-all-pages?          page-count
      (integer? refetch-window)   (max 1 (min refetch-window page-count))
      :else                       1)))

(defn refetch-sweep-tail
  "The ordered `[page-param page-index]` legs a multi-page refetch re-fetches
  after the issue-time page-0 replacement, one at a time; `[]` by default or
  for a non-feed or empty entry. Each leg reuses its page's recorded param.
  Spec 016 §Refetch and invalidation of an infinite feed."
  [entry refetch-policy]
  (if (and entry (infinite-entry? entry) (seq (:data entry)))
    (let [page-count   (count (:data entry))
          window       (refetch-window-count refetch-policy page-count)
          page-params  (or (:page-params entry) [])]
      (mapv (fn [i] [(get page-params i) i]) (range 1 window)))
    []))

(defn entry-begin-refetch-sweep
  "Records the sweep tail under `:refetch-sweep`; an empty tail writes
  nothing. Leaves status and revision to `entry-start-load`.
  Spec 016 §Refetch and invalidation of an infinite feed."
  [entry refetch-policy]
  (let [tail (refetch-sweep-tail entry refetch-policy)]
    (if (seq tail)
      (assoc entry :refetch-sweep (vec tail))
      entry)))

(defn next-refetch-sweep-leg
  "The next sweep leg, or nil."
  [entry]
  (first (:refetch-sweep entry)))

(defn entry-advance-refetch-sweep
  "Pops the issued leg, removing `:refetch-sweep` when it empties."
  [entry]
  (let [rest- (vec (rest (:refetch-sweep entry)))]
    (if (seq rest-)
      (assoc entry :refetch-sweep rest-)
      (dissoc entry :refetch-sweep))))

(defn clear-refetch-sweep
  "Drops the sweep cursor and its stale-mark claim; a failure or abort stops
  the sweep."
  [entry]
  (dissoc entry :refetch-sweep :sweep-covers-invalidation?))

(defn resolve-page->items
  "Lifts a `:page->items` keyword or fn to a fn, or nil when absent.
  Spec 016 §Subscription contract: the merged list and page metadata (R3)"
  [page->items]
  (cond
    (nil? page->items)     nil
    (keyword? page->items) #(get % page->items)
    (fn? page->items)      page->items
    :else                  nil))

(defn merge-pages->items
  "Flattens `pages` into one item vector. A vector page is its items; any
  other page needs the resolved accessor, else
  `:rf.error/infinite-missing-page-accessor`: the framework never guesses,
  and only the merge sees a concrete page shape.
  Spec 016 §Subscription contract: the merged list and page metadata (R3)"
  [pages resolved-accessor resource-id where]
  (into []
        (mapcat
          (fn [page]
            (cond
              (vector? page)         page
              (some? resolved-accessor) (resolved-accessor page)
              :else
              (rf.error/throw-error!
                :rf.error/infinite-missing-page-accessor
                where
                (str "infinite resource " resource-id " accumulated a non-vector "
                     "page but declares no :page->items accessor — the merged "
                     ":rf.resource/items list cannot flatten it. Declare "
                     ":page->items (a keyword key or a (fn [page] → items)) on the "
                     "reg-resource; the framework does NOT guess :items / :data. "
                     "Per Spec 016 §Subscription contract (R3).")
                {:recovery :fix-registration
                 :extra    {:resource-id resource-id
                            :page-shape  (cond (map? page) :map
                                               (seq? page) :seq
                                               :else       (type page))}}))))
        (or pages [])))

;; ---- reverse indexes ------------------------------------------------------------

(defn recompute-indexes
  "Rebuilds `:tag-index` and `:owner-index` from `:entries`, with `key-id`
  members. The trust-rebuild for restore, hydration and teardown.
  Spec 016 §5. Transient side tables and indexes are recomputed or cleared on install"
  [resources-subtree]
  (let [entries (:entries resources-subtree)]
    (reduce-kv
      (fn [acc k-id entry]
        (-> acc
            (update :tag-index
                    (fn [ti] (reduce (fn [ti tag] (update ti tag (fnil conj #{}) k-id))
                                     ti (:tags entry))))
            (update :owner-index
                    (fn [oi] (reduce (fn [oi owner] (update oi owner (fnil conj #{}) k-id))
                                     oi (:active-owners entry))))))
      (assoc resources-subtree :tag-index {} :owner-index {})
      entries)))

(defn- index-without-key
  "Removes `k-id` under each of `member-keys`, dropping emptied sets to match
  the `recompute-indexes` shape."
  [index member-keys k-id]
  (reduce (fn [idx mk]
            (let [s (disj (get idx mk #{}) k-id)]
              (if (seq s)
                (assoc idx mk s)
                (dissoc idx mk))))
          index
          member-keys))

(defn- index-with-key
  "Adds `k-id` under each of `member-keys`."
  [index member-keys k-id]
  (reduce (fn [idx mk] (update idx mk (fnil conj #{}) k-id))
          index
          member-keys))

(defn reindex-keys
  "Reconciles both indexes for just `changed-key-ids`, from `old-entries` to
  the subtree's current `:entries`, so a settle costs O(touched) rather than
  a full rebuild. When every key whose tags or owners changed is listed, the
  result is `=` to `recompute-indexes`."
  [resources-subtree old-entries changed-key-ids]
  (let [new-entries (:entries resources-subtree)]
    (reduce
      (fn [subtree k-id]
        (let [old-entry (get old-entries k-id)
              new-entry (get new-entries k-id)]
          (-> subtree
              (update :tag-index
                      (fn [ti]
                        (-> ti
                            (index-without-key (:tags old-entry) k-id)
                            (index-with-key (:tags new-entry) k-id))))
              (update :owner-index
                      (fn [oi]
                        (-> oi
                            (index-without-key (:active-owners old-entry) k-id)
                            (index-with-key (:active-owners new-entry) k-id)))))))
      resources-subtree
      changed-key-ids)))

;; ---- host-side generation allocator ------------------------------------------------
;;
;; One process-global, monotone high-water mark that restore never rewinds, so
;; a pre-restore reply can never match a post-restore entry. Process-global
;; rather than per-frame because a reply addresses its frame by keyword: a
;; per-frame mark dropped on destroy would restart a same-id successor at 1 and
;; re-mint its predecessor's work-ids. Handlers stay pure: the recordable
;; `:rf.resource/generation-allocation` cofx mints the value at
;; processing-start, the handler writes it, and `:rf.resource/commit-generation`
;; advances the mark with `max`.
;; Spec 016 §1. The generation allocator is monotonic and host-side; it does not rewind

(defonce
  ^{:doc "The process-global generation high-water mark. Host state, so
   restore cannot rewind it, and not keyed by frame, so a same-id successor
   cannot restart it."}
  generation-cache
  (atom 0))

(defn generation-snapshot
  "The current high-water mark, 0 before any allocation."
  []
  @generation-cache)

(defn next-generation
  "`(inc snapshot)`, treating nil as 0. Pure."
  [snapshot]
  (inc (or snapshot 0)))

(defn commit-generation!
  "Raises the high-water mark to `n` with `max`, so a reordered or replayed
  commit never rewinds it. Returns nil."
  [n]
  (swap! generation-cache max n)
  nil)

(defn reset-cache!
  "Resets the high-water mark to 0, for test isolation; the runtime / frames
  reset does not clear this host state."
  []
  (reset! generation-cache 0)
  nil)

;; ---- generation-allocation cofx and commit-generation fx -------------------------
;;
;; The generation is a durable join key, so its value is recorded even though
;; its allocator is host-transient: a generator-backed recordable cofx, which
;; strict replay refuses with `:rf.error/missing-required-cofx` rather than
;; re-mint. `:counter` mirrors `:generation`, matching the routing allocation
;; shape. Spec 002 §Durable join keys are recordable, even when their allocator is host-transient

(def generation-allocation-cofx-meta
  "Registration metadata for the `:rf.resource/generation-allocation` cofx.
  It declares no `:schema`, so resources run without the schemas artefact;
  `allocated-generation` checks the shape instead. EP-0017 §5. Satisfaction algorithm"
  {:doc "The recordable generation allocation for the active frame's resource
/mutation work: `{:generation N :counter N}`, minted at processing-start from
the host-side monotone high-water allocator (`generation-cache`) and recorded
on the causal token. `:generation` is the durable join key written onto the
entry / instance and stamped on the reply token (the stale-suppression
correlation, EP-0011); `:counter` mirrors it (the resource allocator's
generation IS its counter) so a restore can re-establish the host high-water.
The ensure/refetch/mutation-execute handlers declare
`:rf.cofx/requires [:rf.resource/generation-allocation]`, read `:generation`
flat, and write only that value durably; the host allocator advances with
`max` via the `:rf.resource/commit-generation` fx so replay/restore cannot
rewind it. Recordable so replay reproduces an identical generation (and
therefore identical `:work/id` / `:instance/id`, which derive from it). Per
Spec 016 §Restore and replay + 002 §Durable join keys are recordable."})

(defn generation-allocation-cofx
  "The generator: `{:generation N :counter N}` with N the next generation.
  It only reads the mark; the allocating handler emits the commit fx. Under
  `:strict` it does not run. Tests supply a deterministic allocation on the
  dispatch token's `:rf.cofx`."
  []
  (let [n (next-generation (generation-snapshot))]
    {:generation n :counter n}))

(defn- generation-allocation?
  [v]
  (and (map? v) (int? (:generation v)) (int? (:counter v))))

(defn allocated-generation
  "The `:generation` of an allocation, after an always-on shape check that
  throws `:rf.error/cofx-value-invalid`. A supplied or replayed allocation
  arrives as recorded, and a nil generation would silently become the join
  key and no-op the high-water bump. A predicate rather than a cofx
  `:schema`, so it runs without the schemas artefact."
  [allocation]
  (when-not (generation-allocation? allocation)
    (rf.error/throw-error!
      :rf.error/cofx-value-invalid 'rf/reg-cofx
      (str "Recordable coeffect `:rf.resource/generation-allocation` must be "
           "`{:generation int :counter int}`. A malformed supplied or replayed "
           "allocation is refused before it folds into resource state.")
      {:extra {:rf.cofx/id :rf.resource/generation-allocation
               :value      allocation}}))
  (:generation allocation))

(def commit-generation-meta
  "Registration metadata for the `:rf.resource/commit-generation` fx, on
  every platform."
  {:doc "Advance the host-side `re-frame.resources.state` generation
high-water mark with `max` (monotone — never rewinds). Args: `{:value N}` (the
allocated `:generation` / `:counter` from the recordable
`:rf.resource/generation-allocation` cofx). Emitted by the
ensure/refetch/mutation-execute handlers on the branch that allocates a
generation; advancing with `max` is what makes replay/restore unable to
rewind the allocator and recycle a generation. Per Spec 016 §Restore and
replay."})

(defn commit-generation-handler
  "`:rf.resource/commit-generation` fx handler: advances the mark with `max`.
  A nil `:frame` stamp throws `:rf.error/no-frame-context`."
  [{:keys [frame]} {:keys [value]}]
  (rf.frame/require-frame-stamp!
    frame :rf.resource/commit-generation
    {:where 'rf.resource/commit-generation-handler})
  (when (number? value)
    (commit-generation! value))
  nil)
