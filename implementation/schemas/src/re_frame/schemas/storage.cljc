(ns re-frame.schemas.storage
  "Per-frame storage + registration surface for app-db schemas.

  The authoritative registry has shape `{frame-id {path schema-meta}}`.
  App-db schemas are not registrar entries; source coordinates, hot reload,
  and tools read the metadata stored here.

  Owns:
    - `schemas-by-frame` atom (the authoritative store).
    - `reg-app-schema` / `reg-app-schemas` registration entry points.
    - `app-schema-at` / `app-schemas` query entry points.
    - `app-schema-meta-at` — meta-introspection (source-coords, etc.)
      consumed by pair-tools and source-coord tests.
    - `frame-schema-entries` cross-artefact seam consumed by
      `re-frame.elision` / `re-frame.epoch` via the late-bind table.
    - `snapshot-schemas-by-frame` / `restore-schemas-by-frame!` /
      `clear-schemas-by-frame!` — test-support hooks consumed by
      `re-frame.test-support`'s reset-runtime fixture."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.path :as path]
            [re-frame.privacy :as privacy]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "frame-id → path → schema-meta. Per-frame so a story or test
          fixture's reg-app-schema does not bleed into the default
          frame's contract."}
  schemas-by-frame
  (atom {}))

(defn resolve-frame
  "Resolve a schema operation's frame id. An explicit `:frame` target wins;
  otherwise the carried frame scope is required. Frame values normalize to
  runnable ids, and the result must be a keyword so reads and registrations
  cannot create unreachable registry keys. `operation` labels context errors."
  ([opts] (resolve-frame opts :reg-app-schema))
  ([opts operation]
   (let [frame-target (:frame opts)
         frame-id     (if (some? frame-target)
                        (frame/frame-target->id frame-target)
                        (frame/require-current-frame!
                          operation {:where 'rf/reg-app-schema}))]
     (when-not (keyword? frame-id)
       (error/throw-error!
         :rf.error/app-schemas-bad-arg
         'rf/app-schemas
          (str "the :frame opt must be a frame-id keyword or a frame value "
              "(from rf/make-frame); got " (pr-str frame-target)
              ", which resolved to the non-keyword frame target "
              (pr-str frame-id) ". Pass a frame-id keyword or a frame value.")
         {:recovery :supply-a-frame-id-or-frame-value
          :extra    {:received frame-target
                     :resolved frame-id
                     :expected "keyword frame-id or frame value"}}))
     frame-id)))

(defn coerce-opts
  "Permit the keyword-only sugar `(app-schemas frame-id)` and the
  opts-map form `(app-schemas {:frame frame-id})`. Nil means no override.

  A frame value is itself a map, so it must be recognized before the generic
  opts-map branch or it would lose its target."
  [opts-or-frame-id]
  (cond
    (nil? opts-or-frame-id) {}
    ;; A frame value is a map; keep this branch before the generic map branch.
    (or (keyword? opts-or-frame-id)
        (frame/frame-value? opts-or-frame-id)) {:frame opts-or-frame-id}
    (map? opts-or-frame-id) opts-or-frame-id
    :else
    (error/throw-error!
      :rf.error/app-schemas-bad-arg
      'rf/app-schemas
      (str "app-schemas expects a keyword frame-id, a frame value, or an "
           "opts map; got " (pr-str opts-or-frame-id) ". Pass a frame-id "
           "keyword, a frame value (from rf/make-frame), or a "
           "{:frame <frame-id-or-value>} opts map.")
      {:recovery :supply-a-frame-id-or-opts-map
       :extra    {:received opts-or-frame-id
                  :expected "keyword frame-id, frame value, or opts map"}})))

(defn frame-target->opts
  "Lift registration metadata's `:frame` target into the opts shape consumed
  by `resolve-frame`. Wrapping matters for invalid map-shaped targets: they
  must be validated as targets, not mistaken for an opts map. Nil selects the
  carried frame scope."
  [frame-target]
  (if (some? frame-target)
    {:frame frame-target}
    {}))

(defn- best-effort-frame-id
  "Resolve the registration frame for an error payload without throwing:
  the explicit `:frame` opt if present, else the carried-invariant scope
  frame, else `nil` when no scope is established. Used only to enrich the
  `:rf.error/app-schema-runtime-path` payload. The path gate
  runs before the registration's own (throwing) frame resolution, so we
  must not let a missing scope (or a malformed opts shape) mask the
  runtime-path rejection.

  The argument is an OPTS map / opts-sugar (the bulk path's `opts-or-frame-id`,
  or the singular path's `(frame-target->opts (:frame metadata))` lift) — i.e.
  the same shape `resolve-frame` consumes — so this is the non-throwing twin of
  the registration's own resolution and they never disagree on which frame the
  error payload names."
  [opts]
  (try
    (resolve-frame (coerce-opts opts))
    (catch #?(:clj Exception :cljs :default) _ nil)))

(defn- normalize-app-schema-metadata
  "Validate + normalize the OPTIONAL middle registration-metadata map of the
  3-slot `(reg-app-schema path metadata schema)` grammar.

  The schema is the final positional value, matching other 3-slot `reg-*`
  surfaces:

      (reg-app-schema [:user] UserSchema)                ;; 2-slot
      (reg-app-schema [:user] {:frame :session} UserSchema) ;; 3-slot

  The 2-slot form supplies no metadata (`nil` here → `{}`). A non-nil, non-map
  middle arg is a caller bug — the common slip is passing the schema where the
  metadata map goes — and fails LOUDLY at the authoring boundary (dev AND prod)
  with `:rf.error/app-schema-bad-metadata`, naming the path and the value.

  Returns the metadata map (never nil)."
  [path metadata]
  (cond
    (nil? metadata) {}
    (map? metadata) metadata
    :else
    (error/throw-error!
      :rf.error/app-schema-bad-metadata
      'rf/reg-app-schema
      (str "reg-app-schema's middle arg must be a registration-metadata map — "
           "e.g. (reg-app-schema " (pr-str path) " {:frame :session} MySchema). "
           "Got " (pr-str metadata) ". The grammar is "
           "(reg-app-schema path schema) / (reg-app-schema path metadata schema); "
           "the schema is the positional value slot, not a :schema metadata key.")
      {:recovery :pass-metadata-map-then-schema
       :extra    {:path path :received metadata}})))

;; ---- registration-time path validation -----------------------------------

(defn valid-app-schema-path?
  "True when `path` is a valid `reg-app-schema` path: a sequential
  collection of CONCRETE path segments (a vector / seq), INCLUDING the
  empty vector `[]` for the whole-`app-db` root schema. Non-sequential
  scalars — a bare keyword, string, number, nil, map, set — are rejected:
  they break the `(get-in db path)` the validation hot path runs.

  `sequential?` (not `vector?`) is deliberate: APIs accept any
  sequential collection (Conventions §The
  `:rf/path` algebra §Path shape). The accepted seq is then NORMALIZED to
  its canonical vector form before it becomes a stored declaration / digest
  path key, so a list path and the equivalent vector path are ONE identity
  (EP-0012 §Path shape — \"all stored declarations MUST normalize to a
  vector\").

  Each segment must additionally be a concrete `:rf/path`
  segment (`re-frame.path/segment?` — a portable EDN identity value:
  keyword, string, symbol, safe-range integer, boolean, UUID, instant, or
  nil). Schema paths are full `:rf/path` citizens (Conventions §The
  `:rf/path` algebra cites schema paths), so a composite / function / host
  / float / unsafe-integer segment is rejected at registration rather than
  riding into a stored declaration and a digest path key — the same shared
  concrete boundary every other path consumer inherits, no private
  shape-only grammar. `segment?` shares the CEDN-1 safe-integer predicate,
  so an out-of-safe-range integer segment fails here AND would fail the
  canonical digest-key encoding."
  [path]
  (and (sequential? path)
       (every? path/segment? path)))

;; ---- runtime-db first-segment rejection -----------------------------------
;; App schemas validate app-db only. Runtime-db is framework-owned, so a
;; runtime path is rejected before registration rather than installing a
;; contract over a value this validator never reads.

(defn runtime-app-schema-path?
  "True when `path`'s FIRST segment reaches into the runtime-db partition
  — a keyword in the reserved `:rf.runtime/*` namespace (`:rf.runtime/
  machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, …), the
  runtime-db CONTAINER root `:rf.db/runtime` (the epoch / restore path
  prefix), OR the retired legacy app-db `:rf/runtime` root. Such a path is
  a category error for `reg-app-schema`, which validates only app-db.

  Mirrors the canonical runtime-path detection used across the core
  conformance reader (`(= \"rf.runtime\" (namespace (first path)))`) and
  also catches the `:rf.db/runtime` container root and the legacy
  `:rf/runtime` segment. A non-sequential or empty `path` is NOT runtime
  — `valid-app-schema-path?` owns the shape check and the empty `[]` root
  is a legitimate whole-app-db schema."
  [path]
  (boolean
    (when (and (sequential? path) (seq path))
      (let [head (first path)]
        (and (keyword? head)
             (or (= "rf.runtime" (namespace head))
                 (= :rf.db/runtime head)
                 (= :rf/runtime head)))))))

(defn assert-app-schema-path!
  "Throw a framework error (per Spec 009 error catalogue) when `path` is
  not a valid `reg-app-schema` path. Called by `reg-app-schema` /
  `reg-app-schemas` BEFORE the store mutation so a bad path never lands
  in `schemas-by-frame`. Two distinct rejections:

    - `:rf.error/app-schema-bad-path` — the SHAPE is wrong (a
      non-sequential scalar), which would poison the `validate-app-schema!`
      hot path's `(get-in db path)`.
    - `:rf.error/app-schema-runtime-path` — the shape is fine but the
      FIRST segment reaches into the runtime-db partition (`:rf.runtime/*`
      or the legacy `:rf/runtime` root). App schemas validate only app-db;
      the runtime-db partition is framework-owned (the framework validates
      it — machine `:snapshots` refined per-machine from each machine's
      `:data-schema`) and is NOT a user schema-registration surface, so the
      remedy is to drop the runtime path.

  `frame` (optional) names the resolved registration frame for the
  runtime-path error payload; callers pass the frame they resolved. It is
  best-effort context only — the gate runs before the registration's own
  frame resolution, so a `nil` frame still rejects loudly."
  ([path] (assert-app-schema-path! path nil))
  ([path frame]
   (when-not (valid-app-schema-path? path)
     (error/throw-error!
       :rf.error/app-schema-bad-path
       'rf/reg-app-schema
       (str "reg-app-schema path " (pr-str path) " is invalid; pass "
            "a sequential get-in path (vector/seq) "
            "of CONCRETE :rf/path segments (keyword, "
            "string, symbol, safe-range integer, "
            "boolean, UUID, instant, or nil), or [] "
            "for the app-db root — composite / "
            "function / host / float / unsafe-integer "
            "segments are rejected (EP-0012 §The "
            ":rf/path algebra).")
       {:recovery :supply-a-concrete-get-in-path
        :extra    {:received path}}))
   (when (runtime-app-schema-path? path)
     (error/throw-error!
       :rf.error/app-schema-runtime-path
       'rf/reg-app-schema
       (str "app schemas validate only app-db; a "
            "path whose first segment is a "
            ":rf.runtime/* keyword (or the legacy "
            ":rf/runtime root) reaches into the "
            "runtime-db partition. The runtime-db "
            "partition is framework-owned and "
            "validated by the framework (machine "
            ":snapshots refined per-machine from "
            "each machine's :data-schema); it is "
            "NOT a user schema-registration "
            "surface — drop the runtime path.")
       {:recovery :drop-the-runtime-path
        :extra    {:received path
                   :frame    frame}}))))

;; ---- bulk argument validation ---------------------------------------------

(defn valid-bulk-schemas-arg?
  "True when `path->schema` is an acceptable `reg-app-schemas` first
  argument: a map (INCLUDING the empty map `{}`, the documented no-op).
  nil, vectors, strings, seqs of pairs, and every other non-map shape are
  rejected — they would silently register nothing and hand back `[]`,
  masking a malformed-input bug."
  [path->schema]
  (map? path->schema))

(defn assert-bulk-schemas-arg!
  "Throw `:rf.error/app-schemas-bad-batch` when `reg-app-schemas`'
  first argument is not a `{path -> schema}` map. Called BEFORE any
  store mutation so a nil / non-map batch rejects atomically rather than
  silently no-op'ing to `[]`. `{}` is accepted (the
  documented empty no-op)."
  [path->schema]
  (when-not (valid-bulk-schemas-arg? path->schema)
    (error/throw-error!
      :rf.error/app-schemas-bad-batch
      'rf/reg-app-schemas
      (str "reg-app-schemas expects a {path -> schema} map (possibly empty); "
           "got " (pr-str path->schema) ". Pass a map of path -> schema.")
      {:recovery :supply-a-path-to-schema-map
       :extra    {:received path->schema
                  :expected "a {path -> schema} map (possibly empty)"}})))

(defn coerce->frame-id
  "Resolve a frame-id from the `opts-or-frame-id` argument the read
  surface accepts: coerce through `coerce-opts` (keyword sugar / opts
  map / throw on bad shape), then `resolve-frame` (`:frame` override or
  the carried-invariant scope frame; raises `:rf.error/no-frame-context`
  outside any scope per EP-0002). The query entry points (`app-schema-at` /
  `app-schema-meta-at` / `app-schemas` / `app-schemas-digest`) use the
  opts ONLY to name a frame, so they collapse the coerce+resolve pair
  through this helper. The `reg-*` entry points keep the two-step form so
  they can read further keys off the coerced opts map."
  [opts-or-frame-id]
  (resolve-frame (coerce-opts opts-or-frame-id)))

;; ---- validator-unavailable warning ----------------------------------------
;; The default validator deliberately soft-passes when its Malli hook is
;; absent. Loading the facade normally installs that hook, so warn only when
;; the default remains selected and the hook is nevertheless unavailable.
;; Custom validators are an explicit opt-out and do not warn.

(defonce ^:private validator-unavailable-warned
  ;; Process-lifecycle one-shot. Reset by `clear-validator-unavailable-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-validator-unavailable-warned!
  "Reset the one-shot `:rf.warning/schema-validator-unavailable` cache.
  Used by test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! validator-unavailable-warned false))

;; ---- walker-opaque warning -------------------------------------------------
;; The pure-data walker can inspect vector-form Malli EDN but not compiled
;; schema objects. Warn once when any opaque descendant could hide per-slot
;; flags. Bare keywords do not warn: primitive keywords cannot carry props,
;; and distinguishing them from registry references would require the
;; framework to interpret the validator's schema language.

(defonce ^:private walker-opaque-warned
  ;; Process-lifecycle one-shot. Reset by `clear-walker-opaque-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-walker-opaque-warned!
  "Reset the one-shot `:rf.warning/schema-walker-opaque` cache. Used by
  test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! walker-opaque-warned false))

(defn- walker-introspectable?
  "True when the schema value is a form the pure-data walker can fully
  introspect for per-slot `:sensitive?` / `:large?` flags — the root is
  vector-form Malli EDN or a bare keyword (primitive type or registry
  ref — a keyword cannot carry per-slot props, so the walker provably
  skips nothing and there is nothing to warn about) AND no
  NESTED child anywhere beneath it is itself opaque.

  A schema whose root is walkable vector-form EDN can still embed an opaque
  child at a deeper slot (e.g. `[:map [:token
  (m/schema [:string {:sensitive? true}])]]`) — a root-only check would
  call that schema introspectable even though the nested compiled
  value's per-slot flags are exactly as invisible to the walk as a
  top-level opaque schema's. `schema-has-opaque-child?` recurses the
  whole tree so this predicate is accurate at any depth."
  [schema]
  (not (walker/schema-has-opaque-child? schema)))

(defn- maybe-warn-walker-opaque!
  "Emit `:rf.warning/schema-walker-opaque` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked with a schema that is
  opaque — at its ROOT or at any NESTED child — to the pure-data walker
  (a compiled `m/schema` object / other non-vector, non-keyword value,
  anywhere in the tree). Vector forms with no opaque descendant and bare
  keywords do not warn — see `walker-introspectable?`.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [schema path]
  (when (and (not (walker-introspectable? schema))
             (not @walker-opaque-warned))
    (when (compare-and-set! walker-opaque-warned false true)
      (when-let [emit! (late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-walker-opaque
               {:path path
                :schema-kind (if (map? schema)
                               :compiled-schema-object
                               :unknown)
                :reason
                (str "reg-app-schema was called with a schema that is"
                     " opaque — either the registered value ITSELF is a"
                     " compiled / opaque form (a non-vector, non-keyword"
                     " value such as a compiled m/schema object), or a"
                     " vector-form schema embeds one as a NESTED child."
                     " The schema-walker (used for per-slot"
                     " `:sensitive?` / `:large?` extraction) can only"
                     " introspect vector-form Malli EDN — per-slot flags"
                     " inside an opaque value, at any depth, are"
                     " silently skipped. The workable shape: register"
                     " the vector form directly, all the way down, so"
                     " the walker can introspect every per-slot flag."
                     " (The handler/cofx/sub registration-meta"
                     " `:sensitive?` fallback has been removed —"
                     " sensitivity is path-targeted and the redactor"
                     " consults only per-slot schema declarations.) Per"
                     " Spec 010 §The `:schema` value is opaque to"
                     " re-frame.")})))))

(defn- maybe-warn-validator-unavailable!
  "Emit `:rf.warning/schema-validator-unavailable` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked AND the Malli adapter
  is unloaded AND the framework-default validator is still installed.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds). The keyword `:rf.warning/schema-validator-
  unavailable` is a literal arg at the call site — moving the gate
  inside this helper would leave the literal reachable from the
  unconditional helper call and defeat the elision sentinel."
  []
  (when (and (not @validator-unavailable-warned)
             (nil? (late-bind/get-fn :schemas/malli-validate))
             (validator/using-default-validator?))
    (when (compare-and-set! validator-unavailable-warned false true)
      (when-let [emit! (late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-validator-unavailable
               {:reason
                (str "reg-app-schema was called but :schemas/malli-validate"
                     " is unbound and the framework-default validator is"
                     " still installed — every validation site soft-passes."
                     " Require `re-frame.schemas.malli` at app boot to"
                     " activate Malli validation, or call"
                     " `set-schema-validator!` with a non-default fn"
                     " to suppress this warning.")})))))

;; ---- hot-reload :rf.schema/violation trace --------------------------------
;;
;; Per Spec 010 §Schema migration on hot-reload + Spec 009 error catalogue
;; row `:rf.schema/violation`: when a `(frame-id, path)` schema is
;; re-registered during dev (a file save re-evaluates `reg-app-schema`
;; with a DIFFERENT schema for the same path), the live `app-db` value at
;; that path may now violate the new schema. The runtime emits a
;; `:rf.schema/violation` trace (`:op-type :warning`, recovery
;; `:logged-and-skipped`) so dev panels highlight the stale slice — the
;; live app continues running; `app-db` is NOT auto-cleared or rewound.
;;
;; Distinct from `:rf.error/schema-validation-failure` (dispatch-time
;; boundary validation): this fires at the hot-reload edge against
;; PRE-EXISTING state, only when the schema actually changed AND the live
;; value fails the new schema. A no-op re-eval with the same schema does
;; nothing; a re-eval that the live value still satisfies does nothing.

;; The `:rf/redacted` privacy sentinel — Spec 009 §Privacy. Stamped in
;; place of `:mismatching-value` when the new schema declares the slot
;; sensitive, mirroring the `:rf.error/schema-validation-failure`
;; redaction posture so the hot-reload trace never re-leaks a credential.
;; The sensitivity decision also fails closed on an opaque schema the walker
;; cannot introspect: `schema-has-sensitive?` returns false on an opaque
;; value even though Malli may honour a `{:sensitive? true}` slot inside it for
;; the violation. Without the fail-closed arm a hot-reload violation against an
;; opaque schema carrying a sensitive slot leaked `:mismatching-value` verbatim
;; — the same asymmetry the validation-failure redactor closes
;; (`re-frame.schemas.validate`, the
;; `(or schema-has-sensitive? schema-opaque?)` posture). This is the redaction
;; half of the same fail-closed posture for the hot-reload egress edge.
(def ^:private redacted-sentinel privacy/redacted-sentinel)

(defn- maybe-emit-schema-violation!
  "Emit `:rf.schema/violation` when a re-registration changes the schema
  at `(frame-id, path)` AND the live `app-db` value at `path` fails the
  NEW schema. `prior-schema` is the schema the path carried before this
  registration (nil when first registration). No-op when there is no
  prior schema, the schema is unchanged, no validator is registered, or
  the live value still validates.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [frame-id path prior-schema new-schema]
  (when (and (some? prior-schema)
             (not= prior-schema new-schema))
    (when-let [validate-fn @validator/validator-fn]
      (let [app-db      (frame/frame-app-db-value frame-id)
            live-value (get-in app-db path)
            ;; A malformed new schema can make the validator throw
            ;; (e.g. Malli's `:malli.core/child-error` on a childless
            ;; `[:vector]`). A bad schema is not a hot-reload violation
            ;; and MUST NOT crash registration — treat a throwing
            ;; validator as "cannot determine a violation" (pass).
            passes?   (try (boolean (validate-fn new-schema live-value))
                           (catch #?(:clj Throwable :cljs :default) _ true))]
        (when-not passes?
          (when-let [emit! (late-bind/get-fn :trace/emit!)]
            ;; Fail closed on an opaque schema. The
            ;; pure-data walker cannot see a `{:sensitive? true}` slot inside a
            ;; compiled `m/schema` value, so `schema-has-sensitive?` alone would
            ;; return false and `:mismatching-value` would egress the live value
            ;; verbatim — the asymmetry the validate-*! redactor already closes
            ;; (`re-frame.schemas.validate` uses the same
            ;; `(or schema-has-sensitive? schema-has-opaque-child?)` posture). A
            ;; bare keyword is provably flag-free and is NOT opaque
            ;; (`schema-opaque?`). Use recursive `schema-has-opaque-child?`,
            ;; not the root-only
            ;; `schema-opaque?` — a vector-form `new-schema` can embed a nested
            ;; opaque child the root-only check would miss.
            (let [sensitive? (or (walker/schema-has-sensitive? new-schema)
                                 (walker/schema-has-opaque-child? new-schema))]
              (emit! :warning :rf.schema/violation
                     {:path               path
                      :pre-reload-schema  prior-schema
                      :post-reload-schema new-schema
                      :mismatching-value  (if sensitive?
                                            redacted-sentinel
                                            live-value)
                      :frame              frame-id
                      :recovery           :logged-and-skipped
                      :sensitive?         sensitive?}))))))))

;; Schemas describe shape and validation-failure egress. They do not populate
;; durable app-db classification, which is owned by commit-plane effects.
;; Schema props remain available to owner-local transient egress projectors.

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure and reject the whole candidate
  before it installs.

  DEVELOPMENT-BUILD ASSERTION. That rejection is dev-only. A production
  build (`:advanced` with `goog.DEBUG` false, or `-Dre-frame.debug=false`
  on the JVM) still performs this registration — `app-schema-at` and
  `app-schemas` keep answering, so tools and agents can introspect the
  shape — but the candidate validator is elided, so nothing checks it.
  A candidate that violates this schema installs silently: no rejection,
  no rollback, no trace. Your app-db schemas do not run in production
  builds. Keep the real invariant in the handler, and use the
  `:rf.schema/at-boundary` interceptor where untrusted input must be
  validated in production too. Per Spec 010 §Production builds.

  The schema is the positional value slot:

      (rf/reg-app-schema [:user] UserSchema)                   ;; 2-slot
      (rf/reg-app-schema [:user] {:frame :session} UserSchema) ;; 3-slot

  `reg-app-schema` is an ordinary member of the `reg-*` family: the path is
  the registration id (Conventions §reg-* return-value convention — the
  path-as-id asymmetry is defended in Spec 010), and the schema is the last
  POSITIONAL value slot, uniform with every other 3-slot `reg-*` surface
  (Spec 001 §The metadata map). The OPTIONAL middle arg is the standard
  registration-metadata map carrying the `:frame` target (and any open
  `:my/*` extension key). Omit it for the 2-slot form. A non-map middle arg
  throws `:rf.error/app-schema-bad-metadata` at the authoring boundary.

  Registration is frame-scoped. The `:frame` metadata value may be a frame-id
  keyword or frame value; without it the carried frame scope is required.
  The path must be a sequential collection of concrete path segments, with
  `[]` naming the whole app-db root. Path and frame validation happen before
  the registry mutation. Returns the canonical vector path."
  ([path schema] (reg-app-schema path nil schema))
  ([path metadata schema]
  (let [metadata (normalize-app-schema-metadata path metadata)
        ;; Metadata carries a frame target, not the whole opts map.
        frame-opts (frame-target->opts (:frame metadata))]
   ;; Reject malformed and runtime-db paths before the store mutation. Frame
   ;; context is best-effort here so a missing scope cannot mask a path error.
   (assert-app-schema-path! path (best-effort-frame-id frame-opts))
   (let [frame-id     (resolve-frame frame-opts)
         ;; Storage, lookup, and digesting share one canonical vector identity.
         path         (path/normalize-concrete path)
         ;; Preserve open registration metadata, but stamp the positional
         ;; schema and resolved path/frame over caller-supplied values.
         schema-meta  (source-coords/merge-coords
                        (assoc (dissoc metadata :schema :frame)
                               :schema schema :path path :frame frame-id))
         ;; Capture before replacement for the dev-only hot-reload check.
         prior-schema (get-in @schemas-by-frame [frame-id path :schema])]
     (swap! schemas-by-frame assoc-in [frame-id path] schema-meta)
     ;; Read-only diagnostics are absent from production builds.
     (when interop/debug-enabled?
       (maybe-warn-validator-unavailable!)
       (maybe-warn-walker-opaque! schema path)
       (maybe-emit-schema-violation! frame-id path prior-schema schema))
     path))))

(defn reg-app-schemas
  "Bulk-register a map of `{path -> schema}` against the active frame
  (or the frame named in `opts`). Designed for feature-modular apps (per Conventions
  §Feature-modularity prefix convention) where a single feature module
  registers 5–20 schemas under a common path prefix like `[:cart …]` or
  `[:auth …]`.

  Shape:

    (rf/reg-app-schemas {[:auth]                AuthSlice
                         [:auth :login-form]    FormSlice
                         [:cart]                CartSlice
                         [:cart :items]         [:vector CartItem]})

    (rf/reg-app-schemas {[:foo] FooSchema} {:frame :tenant/a})

  The `:frame` opt names
  the frame for every entry in the map (you cannot mix frames in a single
  call), else the carried-invariant scope chain resolves it. Registering
  under no scope and no explicit `:frame` raises
  `:rf.error/no-frame-context` (the up-front path-shape sweep still runs
  first).
  The singular form `reg-app-schema` remains available and is used
  internally for each entry — every entry stamps its own per-frame side-
  table entry with source-coords captured from this call site.

  DEVELOPMENT-BUILD ASSERTION, exactly as for the singular form: every
  entry in the batch registers in a production build but is never checked
  there, so a violating candidate installs silently. Your app-db schemas
  do not run in production builds. Per Spec 010 §Production builds.

  Returns the vector of canonical paths registered, in map iteration order."
  ([path->schema] (reg-app-schemas path->schema {}))
  ([path->schema opts-or-frame-id]
   ;; Reject a malformed batch before path iteration can silently no-op.
   (assert-bulk-schemas-arg! path->schema)
   ;; Validate every path before mutating so an invalid entry cannot leave a
   ;; partially registered batch.
   (let [batch-frame-id (best-effort-frame-id opts-or-frame-id)]
     (run! #(assert-app-schema-path! % batch-frame-id) (keys path->schema)))
   ;; Delegate each map value through the singular positional schema slot.
   (let [frame-target (:frame (coerce-opts opts-or-frame-id))]
     (mapv (fn [[path schema]]
             (if (some? frame-target)
               (reg-app-schema path {:frame frame-target} schema)
               (reg-app-schema path schema)))
           path->schema))))

(defn app-schema-at
  "Look up the registered schema for a path in a frame, or nil.

  Arities:
    (app-schema-at path)         ;; carried-invariant scope frame (EP-0002)
    (app-schema-at path opts)    ;; opts map; :frame names the frame
                                 ;; (keyword sugar also accepted)

  Per Spec 010 §Schemas as a tooling and agent surface."
  ([path] (app-schema-at path {}))
  ([path opts-or-frame-id]
   ;; Lookup uses the same canonical concrete path identity as registration.
   (let [frame-id (coerce->frame-id opts-or-frame-id)
         path     (path/normalize-concrete path)]
     (when-let [registration-metadata (get-in @schemas-by-frame [frame-id path])]
       (:schema registration-metadata)))))

(defn app-schema-meta-at
  "Return the registration metadata map for a path in a frame, or nil.

  Unlike `app-schema-at` (which returns just the `:schema` value), this
  returns the full meta map stamped at `reg-app-schema` — including
  source-coords (`:ns` / `:line` / `:file`), `:path`, `:schema`, and
  `:frame`. Used by pair-tools, 10x panels, and source-coord tests that
  need to introspect where a schema was registered.

  This is the canonical read surface for app-db schema metadata. App-db
  schemas are not a registrar kind;
  the per-frame side-table is the single source of truth.

  Arities:
    (app-schema-meta-at path)         ;; carried-invariant scope frame (EP-0002)
    (app-schema-meta-at path opts)    ;; opts map; :frame names the frame
                                      ;; (keyword sugar also accepted)"
  ([path] (app-schema-meta-at path {}))
  ([path opts-or-frame-id]
   ;; Lookup uses the same canonical concrete path identity as registration.
   (let [frame-id (coerce->frame-id opts-or-frame-id)
         path     (path/normalize-concrete path)]
     (get-in @schemas-by-frame [frame-id path]))))

(defn app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Pair tools and 10x panels read this to
  introspect what schemas apply in a given frame.

  Arities:

    (app-schemas)              ;; sugar for (app-schemas {})
    (app-schemas frame-id)     ;; sugar for (app-schemas {:frame frame-id})
    (app-schemas opts)         ;; opts is a map; supports {:frame ...}
                               ;; and is the place future opts will land

  Per Spec 010 §Per-frame schemas the result is the schema set
  registered against the named frame (active frame when none is
  given). Schemas registered against a different frame do not appear."
  ([] (app-schemas {}))
  ([opts-or-frame-id]
   (let [frame-id (coerce->frame-id opts-or-frame-id)]
     (reduce-kv (fn [acc path m] (assoc acc path (:schema m)))
                {}
                (get @schemas-by-frame frame-id {})))))

(defn frame-schema-entries
  "Cross-artefact seam — consumed by `re-frame.elision` and
  `re-frame.epoch` via the `:schemas/frame-schema-entries` late-bind
  hook. Returns the `{path → schema-meta}` map for a frame, or `{}`."
  [frame-id]
  (get @schemas-by-frame frame-id {}))

;; ---- hot-reload semantics ------------------------------------------------
;;
;; `reg-app-schema` writes only to `schemas-by-frame`. Re-registering a
;; `(frame-id, path)` entry is an
;; ordinary `swap!`: the new meta replaces the prior entry atomically, and
;; the validation hot path (`frame-schema-entries`) picks up the new
;; schema on its next read. The per-frame map IS the validation cache.
;;
;; One dev-only side effect accompanies a re-registration:
;;   `:rf.schema/violation` — emitted when the schema CHANGED and the live
;;   `app-db` value at the path fails the new shape (Spec 010 §Schema
;;   migration on hot-reload). See `maybe-emit-schema-violation!`. Gated on
;;   `interop/debug-enabled?` and DCE'd in production.
;;
;; Re-registration does not refresh durable app-db classification; schemas
;; do not own that registry.

;; ---- test-support snapshot / restore -------------------------------------
;;
;; Consumed by re-frame.test-support's make-reset-runtime-fixture via the
;; `:schemas/{snapshot-by-frame,restore-by-frame!,clear-by-frame!}`
;; late-bind hooks. The fixture captures and restores the per-frame
;; registry around each test; when the schemas artefact is not on the
;; classpath the hooks are nil and the fixture no-ops the schema steps.

(defn snapshot-schemas-by-frame
  "Return a snapshot value of the per-frame schema registry."
  []
  @schemas-by-frame)

(defn restore-schemas-by-frame!
  "Reset the per-frame schema registry to the supplied snapshot."
  [snap]
  (reset! schemas-by-frame snap))

(defn clear-schemas-by-frame!
  "Reset the per-frame schema registry to `{}`. Used by test fixtures
  and by `make-reset-runtime-fixture`'s `:clear-app-schemas? true`
  path. The per-frame registry is the artefact's only mutable registration
  state."
  []
  (reset! schemas-by-frame {}))

(defn on-frame-destroyed!
  "Per Spec 002 §Destroy: drop every schema registered against the
  destroyed frame so a subsequent construction of the same id starts
  with a clean schema slate. Called from `frame/destroy-frame!`
  through the `:schemas/on-frame-destroyed!` late-bind hook
  (mirrors the `:machines/on-frame-destroyed!` /
  `:ssr/on-frame-destroyed` cleanup contract).

  Without this cleanup, reusing a frame id would revive schemas from the
  destroyed frame and could cause rollback against orphan paths. Idempotent:
  a missing frame entry is a no-op `dissoc`."
  [frame-id]
  (swap! schemas-by-frame dissoc frame-id))
