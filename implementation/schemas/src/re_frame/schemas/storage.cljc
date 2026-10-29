(ns re-frame.schemas.storage
  "Registration and resolution of app-db schemas.

  An app-db schema is an ordinary registrar kind, `:app-schema`, whose id is
  the canonical path vector. `reg-app-schema` is a frame-agnostic program
  registration, like `reg-event`: it never reads a frame, and every frame
  validates against the schemas it RESOLVES — live for a frame made without
  `:images`, through the sealed generation for a frame made with them, and
  each path's latest registration for a frame that carries neither. A Story
  variant or a test narrows a schema by shadowing it in a later image.

  Owns:
    - `reg-app-schema` / `reg-app-schemas` registration entry points.
    - `app-schemas` / `app-schema-meta` query entry points — one opts MAP
      with a REQUIRED `:frame`, answering the registrations that frame
      resolves (source-coords, `:path`, `:schema`).
    - `frame-schema-entries` cross-artefact seam consumed by the validation
      hot path, `re-frame.epoch` and Story via the late-bind table.
    - `classification-sensitive?`, the classification half of an app-db
      validation trace's sensitivity decision, shared with the hot path."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.path :as rf.path]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas.validator :as rf.schemas.validator]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- a frame is never a registration input ---------------------------------
;;
;; Registration never reads a frame. A schema that should differ between
;; frames comes from the images those frames resolve, so naming a frame at
;; registration is refused rather than ignored: ignoring it would install a
;; program-wide schema where the author asked for a frame-local one.

(defn- refuse-frame-target!
  "Throw `:rf.error/app-schema-frame-target`: `received` named a frame on a
  schema registration. `where` is the entry point the caller used."
  [where path received]
  (rf.error/throw-error!
    :rf.error/app-schema-frame-target
    where
    (str where " was given a frame (" (pr-str received) "). App-db schemas "
         "are program registrations, like events: a schema registers once, at "
         "the top level, and applies to every frame that resolves it. A schema "
         "that differs per frame comes from images: register it in a namespace "
         "a later image selects, and that image's frames resolve it instead.")
    {:recovery :register-at-top-level-and-select-variants-by-image
     :extra    (cond-> {:received received}
                 (some? path) (assoc :path path))}))

(defn- normalize-app-schema-metadata
  "Validate + normalize the OPTIONAL middle registration-metadata map of the
  3-slot `(reg-app-schema path metadata schema)` grammar.

  The schema is the final positional value, matching other 3-slot `reg-*`
  surfaces:

      (reg-app-schema [:user] UserSchema)                       ;; 2-slot
      (reg-app-schema [:user] {:doc \"The user.\"} UserSchema)  ;; 3-slot

  The 2-slot form supplies no metadata (`nil` here → `{}`). A frame — a
  `:frame` key, or a bare frame value in the slot — throws
  `:rf.error/app-schema-frame-target`. A frame value is itself a map, so it is
  recognized before the generic map branch. A non-nil, non-map middle arg is a
  caller bug — the common slip is passing the schema where the metadata map
  goes — and fails LOUDLY at the authoring boundary (dev AND prod) with
  `:rf.error/app-schema-bad-metadata`, naming the path and the value.

  Returns the metadata map (never nil)."
  [path metadata]
  (cond
    (nil? metadata) {}
    ;; A frame value is a map; keep this branch before the generic map branch.
    (rf.frame/frame-value? metadata)
    (refuse-frame-target! 'rf/reg-app-schema path metadata)
    (map? metadata)
    (if (contains? metadata :frame)
      (refuse-frame-target! 'rf/reg-app-schema path (:frame metadata))
      metadata)
    :else
    (rf.error/throw-error!
      :rf.error/app-schema-bad-metadata
      'rf/reg-app-schema
      (str "reg-app-schema's middle arg must be a registration-metadata map — "
           "e.g. (reg-app-schema " (pr-str path) " {:doc \"…\"} MySchema). "
           "Got " (pr-str metadata) ". The grammar is "
           "(reg-app-schema path schema) / (reg-app-schema path metadata schema); "
           "the schema is the positional value slot, not a :schema metadata key.")
      {:recovery :pass-metadata-map-then-schema
       :extra    {:path path :received metadata}})))

(defn- assert-bulk-opts!
  "Validate `reg-app-schemas`' optional second argument. `nil` and a map
  without `:frame` are accepted. A frame — a frame-id keyword, a frame value,
  or a `:frame` key — throws `:rf.error/app-schema-frame-target`; any other
  value throws `:rf.error/app-schemas-bad-arg`."
  [opts]
  (cond
    (nil? opts) nil
    (or (keyword? opts) (rf.frame/frame-value? opts))
    (refuse-frame-target! 'rf/reg-app-schemas nil opts)
    (map? opts)
    (when (contains? opts :frame)
      (refuse-frame-target! 'rf/reg-app-schemas nil (:frame opts)))
    :else
    (rf.error/throw-error!
      :rf.error/app-schemas-bad-arg
      'rf/reg-app-schemas
      (str "reg-app-schemas' optional second argument is an opts map; got "
           (pr-str opts) ". Register the batch with (reg-app-schemas m).")
      {:recovery :drop-the-second-argument
       :extra    {:received opts
                  :expected "an opts map, or no second argument"}})))

;; ---- resolution through a frame ----------------------------------------------

(defn- in-frame-resolution
  "Run `thunk` under `frame-target`'s registration resolution — its sealed
  generation, the live store for a frame made without `:images`, or each id's
  latest registration for a frame carrying neither (an unknown id included).
  Reached through the late-bound `:live-frame/call-with-frame-resolution`
  seam; with it absent, `thunk` runs against each id's latest registration."
  [frame-target thunk]
  (if-let [with-frame (rf.late-bind/get-fn :live-frame/call-with-frame-resolution)]
    (with-frame frame-target thunk)
    (thunk)))

(defn- resolved-registrations
  "The `{path → registration}` map of the `:app-schema` ids the bound
  resolution runs. A path more than one namespace registers is never left out
  of it: a frame made without `:images` refuses such a path, so this throws
  the `:rf.error/ambiguous-registration` the frame raises where it uses the
  id, rather than answering without the schema."
  []
  (when-let [[path _] (first (rf.registrar/ambiguous-registrations :app-schema))]
    (rf.registrar/lookup :app-schema path))
  (rf.registrar/registrations :app-schema))

(defn- resolution-with-ambiguity
  "`{:entries {path → registration} :ambiguous #{path}}` for the bound
  resolution: the paths it runs, and the paths it refuses because more than
  one namespace registers them."
  []
  {:entries   (rf.registrar/registrations :app-schema)
   :ambiguous (set (keys (rf.registrar/ambiguous-registrations :app-schema)))})

(defn- frame-resolves-schema?
  "True when frame `frame-id` resolves `path` to a registration carrying
  `schema`. A path the frame cannot resolve because more than one namespace
  registers it answers false: this read only selects frames for a dev
  diagnostic."
  [frame-id path schema]
  (try
    (let [resolved (in-frame-resolution
                     frame-id #(rf.registrar/lookup :app-schema path))]
      (and (some? resolved) (= schema (:schema resolved))))
    (catch #?(:clj Throwable :cljs :default) e
      (if (rf.registrar/ambiguous-registration? e)
        false
        (throw e)))))

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
       (every? rf.path/segment? path)))

;; ---- runtime-db first-segment rejection -----------------------------------
;; App schemas validate app-db only. Runtime-db is framework-owned, so a
;; runtime path is rejected before registration rather than installing a
;; contract over a value this validator never reads.

(defn runtime-app-schema-path?
  "True when `path`'s FIRST segment reaches into the runtime-db partition
  — a keyword in the reserved `:rf.runtime/*` namespace (`:rf.runtime/
  machines`, `:rf.runtime/routing`, …) or the
  runtime-db CONTAINER root `:rf.db/runtime` (the epoch / restore path
  prefix). Such a path is a category error for `reg-app-schema`, which
  validates only app-db. A `:rf/runtime` key is ordinary app data, so a
  path rooted there is an app-db path like any other.

  Mirrors the canonical runtime-path detection used across the core
  conformance reader (`(= \"rf.runtime\" (namespace (first path)))`) and
  also catches the `:rf.db/runtime` container root. A non-sequential or
  empty `path` is NOT runtime — `valid-app-schema-path?` owns the shape check and the empty `[]` root
  is a legitimate whole-app-db schema."
  [path]
  (boolean
    (when (and (sequential? path) (seq path))
      (let [head (first path)]
        (and (keyword? head)
             (or (= "rf.runtime" (namespace head))
                 (= :rf.db/runtime head)))))))

(defn assert-app-schema-path!
  "Throw a framework error (per Spec 009 error catalogue) when `path` is
  not a valid `reg-app-schema` path. Called by `reg-app-schema` /
  `reg-app-schemas` BEFORE anything is registered, so a bad path never lands.
  Two distinct rejections:

    - `:rf.error/app-schema-bad-path` — the SHAPE is wrong (a
      non-sequential scalar), which would poison the `validate-app-schema!`
      hot path's `(get-in db path)`.
    - `:rf.error/app-schema-runtime-path` — the shape is fine but the
      FIRST segment reaches into the runtime-db partition (`:rf.runtime/*`
      or the `:rf.db/runtime` root). App schemas validate only app-db;
      the runtime-db partition is framework-owned (the framework validates
      it — machine `:snapshots` refined per-machine from each machine's
      `[:schemas :data]` schema) and is NOT a user schema-registration
      surface, so the
      remedy is to drop the runtime path."
  [path]
  (when-not (valid-app-schema-path? path)
    (rf.error/throw-error!
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
    (rf.error/throw-error!
      :rf.error/app-schema-runtime-path
      'rf/reg-app-schema
      (str "app schemas validate only app-db; a "
           "path whose first segment is a "
           ":rf.runtime/* keyword (or the "
           ":rf.db/runtime root) reaches into the "
           "runtime-db partition. The runtime-db "
           "partition is framework-owned and "
           "validated by the framework (machine "
           ":snapshots refined per-machine from "
           "each machine's [:schemas :data] "
           "schema); it is "
           "NOT a user schema-registration "
           "surface — drop the runtime path.")
      {:recovery :drop-the-runtime-path
       :extra    {:received path}})))

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
    (rf.error/throw-error!
      :rf.error/app-schemas-bad-batch
      'rf/reg-app-schemas
      (str "reg-app-schemas expects a {path -> schema} map (possibly empty); "
           "got " (pr-str path->schema) ". Pass a map of path -> schema.")
      {:recovery :supply-a-path-to-schema-map
       :extra    {:received path->schema
                  :expected "a {path -> schema} map (possibly empty)"}})))

(defn read-frame-id
  "Resolve the REQUIRED `:frame` target of a schema READ's opts map to a
  frame-id keyword.

  ONE FRAME SPELLING. Every schema read entry point
  (`app-schemas` / `app-schema-meta` / `app-schemas-digest`) takes a single
  opts MAP whose `:frame` names the frame, accepting the same target shapes
  `rf/registrations` accepts — a frame-id keyword or a frame value. There is
  deliberately NO ambient default and no trailing frame-target sniffing: a
  live frame value is itself a map, so a type-sniffing trailing argument
  cannot be read locally (Spec Principles §Name over place). A frameless or
  non-map call raises the catalogued `:rf.error/no-frame-context`; a `:frame`
  that names no frame id raises `:rf.error/app-schemas-bad-arg`.

  The read then answers from that frame's resolution. An id naming no live
  frame resolves each path's latest registration rather than throwing: Story
  computes a variant frame's identity BEFORE that frame is allocated."
  [opts where-sym]
  (let [target (when (map? opts) (:frame opts))]
    (if (some? target)
      (let [frame-id (rf.frame/frame-value->id target)]
        (when-not (keyword? frame-id)
          (rf.error/throw-error!
            :rf.error/app-schemas-bad-arg
            where-sym
            (str "the :frame opt must be a frame-id keyword or a frame value "
                 "(from rf/make-frame); got " (pr-str target)
                 ", which resolved to the non-keyword frame target "
                 (pr-str frame-id) ". Pass a frame-id keyword or a frame value.")
            {:recovery :supply-a-frame-id-or-frame-value
             :extra    {:received target
                        :resolved frame-id
                        :expected "keyword frame-id or frame value"}}))
        frame-id)
      (let [payload (rf.frame/no-frame-context-payload
                      :app-schema-introspection
                      {:where    where-sym
                       :reason   (str where-sym " requires an explicit frame: pass "
                                      "{:frame <frame-id-or-frame-value>}. Schema "
                                      "reads never fall back to an ambient frame, and "
                                      "the frame target is never a bare positional "
                                      "argument. Got " (pr-str opts) ".")
                       :recovery :supply-frame})]
        (rf.frame/emit-no-frame-context! payload)
        (throw (rf.error/ex-info-from-data payload))))))

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
  (not (rf.schemas.walker/schema-has-opaque-child? schema)))

(defn- maybe-warn-walker-opaque!
  "Emit `:rf.warning/schema-walker-opaque` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked with a schema that is
  opaque — at its ROOT or at any NESTED child — to the pure-data walker
  (a compiled `m/schema` object / other non-vector, non-keyword value,
  anywhere in the tree; a local `{:registry ...}`; or an explicit
  `[:ref ...]` reference form). Vector forms with no opaque descendant and
  bare keywords do not warn — see `walker-introspectable?`.

  `:schema-kind` has no value of its own for the reference form: a root
  `[:ref ...]` reports `:unknown`, exactly as a nested opaque value does.
  The `:reason` string is what names the shape.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [schema path]
  (when (and (not (walker-introspectable? schema))
             (not @walker-opaque-warned))
    (when (compare-and-set! walker-opaque-warned false true)
      (when-let [emit! (rf.late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-walker-opaque
               {:path path
                ;; Root-shape classification. An opaque ROOT — a compiled
                ;; `m/schema` value, or any other non-vector, non-keyword
                ;; value — is `:compiled-schema-object`. The walker's own
                ;; predicate decides, because a compiled schema is not a map
                ;; and storage never asks Malli what a schema is. A NESTED
                ;; opaque value or a nested local registry leaves the root a
                ;; plain vector form and so reports `:unknown`.
                :schema-kind (cond
                               (rf.schemas.walker/schema-opaque? schema)
                               :compiled-schema-object
                               (rf.schemas.walker/schema-local-registry? schema)
                               :local-registry
                               :else :unknown)
                :reason
                (str "reg-app-schema was called with a schema that is"
                     " opaque — either the registered value ITSELF is a"
                     " compiled / opaque form (a non-vector, non-keyword"
                     " value such as a compiled m/schema object), or a"
                     " vector-form schema embeds one as a NESTED child,"
                     " or it carries a local `{:registry ...}` whose"
                     " referenced shapes the walk resolves nowhere, or it"
                     " is (or embeds) an explicit `[:ref ...]` reference"
                     " form, whose referenced shape the walk likewise"
                     " resolves nowhere."
                     " The schema-walker introspects only vector-form Malli"
                     " EDN, so it cannot prove such a schema carries no"
                     " `:sensitive?` slot, and every validation failure"
                     " against it redacts, even when nothing in it is"
                     " sensitive. Register the vector form directly, all"
                     " the way down, to keep those failures readable. Per"
                     " Spec 010 §The `:schema` value is opaque to"
                     " re-frame.")})))))

;; ---- registration-time :rf.schema/violation trace -------------------------
;;
;; Per Spec 010 §Schema migration on hot-reload + Spec 009 error catalogue
;; row `:rf.schema/violation`: when a `(frame-id, path)` schema is
;; registered for the first time, or re-registered during dev with a
;; DIFFERENT schema (a file save re-evaluates `reg-app-schema`), the live
;; `app-db` value present at that path may violate it. The runtime emits a
;; `:rf.schema/violation` trace (`:op-type :warning`, recovery
;; `:logged-and-skipped`) so dev panels highlight the stale slice — the
;; live app continues running; `app-db` is NOT auto-cleared or rewound.
;;
;; Distinct from `:rf.error/schema-validation-failure` (dispatch-time
;; candidate validation): this fires at the registration edge against
;; PRE-EXISTING state, only when the declaration is new or changed AND a
;; live value is present at the path AND it fails the schema. An absent
;; path, a re-eval with the same schema, and a live value that satisfies
;; the schema all do nothing.

;; `:mismatching-value` is the live value, so it is redacted the way a
;; dispatch-time failure's `:value` is (Spec 010 §`:sensitive?`). When the new
;; schema marks a slot sensitive, or hides one from the walker, it is the
;; `:rf/redacted` sentinel: the `(or schema-has-sensitive?
;; schema-has-opaque-child?)` posture `re-frame.schemas.validate` takes,
;; because Malli may honour a `:sensitive?` slot inside an opaque value the
;; walker cannot see. Otherwise it is the live value walked through the frame's
;; classification at `path`, which redacts a classified sensitive slot and
;; size-elides a large one.
(def ^:private redacted-sentinel rf.privacy/redacted-sentinel)

(defn classification-sensitive?
  "True when `frame-id`'s classification redacts anything in `v`, the value at
  app-db `path`: a `reg-classification`, a flow's output marks or a subsystem
  declaration makes a path at, above or beneath `path` sensitive. A
  schema-validation trace about `v` is then sensitive whatever its schema
  says, because an app-db path is declared sensitive once, by classification
  (Spec 010 §`:sensitive?`). A large declaration does not count."
  [frame-id path v]
  (not= v (rf.elision/elide-wire-value
            v {:frame frame-id :path path :rf.egress/include-large? true})))

(defn- maybe-emit-schema-violation!
  "Emit `:rf.schema/violation` when the live `app-db` value present at `path`
  in frame `frame-id` fails `new-schema`. No-op when the path is absent from
  the live `app-db` (or the frame has no `app-db` yet), no validator is
  registered, or the live value validates.

  `prior-registered?` is whether the registrar held an ENTRY for `path`
  before this registration, and `prior-schema` is the exact token that entry
  stored. The two are separate arguments on purpose: a schema value is opaque
  to re-frame, so `nil` is a legitimate token a custom validator may
  interpret, and Spec 010 §The `:schema` value is opaque makes \"registered\"
  a question about DECLARATION PRESENCE rather than token truthiness.

  `:pre-reload-schema` names the prior declaration's token, so the trace
  carries it only on a re-registration: on a first registration there is
  no prior declaration and the key is absent (a `nil` there would read as
  a registered `nil` token).

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [frame-id path prior-registered? prior-schema new-schema]
  (when-let [validate-fn (rf.schemas.validator/validator)]
    (let [app-db     (rf.frame/frame-app-db-value frame-id)
          live-value (if (nil? app-db) ::absent (get-in app-db path ::absent))]
      (when-not (or (= ::absent live-value)
                    ;; A malformed new schema can make the validator throw
                    ;; (e.g. Malli's `:malli.core/child-error` on a childless
                    ;; `[:vector]`). A bad schema is not a violation of the
                    ;; live value and MUST NOT crash registration — treat a
                    ;; throwing validator as "cannot determine a violation"
                    ;; (pass).
                    (try (boolean (validate-fn new-schema live-value))
                         (catch #?(:clj Throwable :cljs :default) _ true)))
        (when-let [emit! (rf.late-bind/get-fn :trace/emit!)]
          ;; The recursive `schema-has-opaque-child?`, not the root-only
          ;; `schema-opaque?`: a vector-form schema can embed an opaque child.
          (let [schema-sensitive? (or (rf.schemas.walker/schema-has-sensitive? new-schema)
                                      (rf.schemas.walker/schema-has-opaque-child? new-schema))]
            (emit! :warning :rf.schema/violation
                   (cond-> {:path               path
                            :post-reload-schema new-schema
                            :mismatching-value  (if schema-sensitive?
                                                  redacted-sentinel
                                                  (rf.elision/elide-wire-value
                                                    live-value {:frame frame-id :path path}))
                            :frame              frame-id
                            :recovery           :logged-and-skipped
                            :sensitive?         (or schema-sensitive?
                                                    (classification-sensitive?
                                                      frame-id path live-value))}
                     prior-registered? (assoc :pre-reload-schema prior-schema)))))))))

(defn- maybe-emit-schema-violations!
  "Run the registration-time `:rf.schema/violation` check in every live frame
  that resolves `path` to the registration just made. A first registration
  checks; a re-registration checks only when the schema changed, so a re-eval
  with the same schema does nothing.

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  (Spec 009 §Production builds)."
  [path prior-registered? prior-schema new-schema]
  (when (or (not prior-registered?)
            (not= prior-schema new-schema))
    (doseq [frame-id (rf.frame/frame-ids)]
      (when (frame-resolves-schema? frame-id path new-schema)
        (maybe-emit-schema-violation! frame-id path prior-registered?
                                      prior-schema new-schema)))))

;; Schemas describe shape and validation-failure egress. They do not populate
;; durable app-db classification, which `reg-classification` owns.
;; Schema props are available to owner-local transient egress projectors.

;; ---- app-db schema registration -------------------------------------------

(def ^:private ^:dynamic *bulk-entry?*
  "True while `reg-app-schemas` registers its entries. A bulk entry is a
  `path -> schema` pair with no slot for a per-path `:doc`, so it is exempt
  from `:rf.warning/missing-doc`, as a programmatic registration is."
  false)

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure and reject the whole candidate
  before it installs. The schema constrains the value only where `path`
  exists: an absent path is not checked, a present `nil` is. A key that
  must exist is declared by an enclosing schema, usually the root `[]`:
  `(reg-app-schema [] [:map [:cart CartSchema]])`. Registering also checks
  the live value already present at `path` in each frame that resolves the
  schema and, when it fails, emits the dev-only `:rf.schema/violation`
  warning.

  DEVELOPMENT-BUILD ASSERTION. That rejection is dev-only. A production
  build (`:advanced` with `goog.DEBUG` false, or `-Dre-frame.debug=false`
  on the JVM) still performs this registration — `app-schemas` and
  `app-schema-meta` keep answering, so tools and agents can introspect the
  shape — but the candidate validator is elided, so nothing checks it.
  A candidate that violates this schema installs silently: no rejection,
  no rollback, no trace. Your app-db schemas do not run in production
  builds. Keep the real invariant in the handler, and set `:boundary? true`
  on the ingress event handler where untrusted input must be
  validated in production too. Per Spec 010 §Production builds.

  The schema is the positional value slot:

      (rf/reg-app-schema [:user] UserSchema)                       ;; 2-slot
      (rf/reg-app-schema [:user] {:doc \"The user.\"} UserSchema)  ;; 3-slot

  `reg-app-schema` is an ordinary member of the `reg-*` family: it registers
  the registrar kind `:app-schema`, the canonical path is the registration id
  (Conventions §reg-* return-value convention — the path-as-id asymmetry is
  defended in Spec 010), and the schema is the last POSITIONAL value slot,
  uniform with every other 3-slot `reg-*` surface (Spec 001 §The metadata
  map). The OPTIONAL middle arg is the standard registration-metadata map
  (`:doc`, open `:my/*` extension keys). A non-map middle arg throws
  `:rf.error/app-schema-bad-metadata` at the authoring boundary.

  Registration is FRAME-AGNOSTIC: a top-level program registration that never
  reads a frame. Every frame validates against the schemas it resolves — live
  for a frame made without `:images`, through the sealed generation for a
  frame made with them — so a schema applies to every frame that resolves it,
  and a frame that should validate differently selects a later image that
  shadows the path. A `:frame` in the metadata, or a frame value in its slot,
  throws `:rf.error/app-schema-frame-target`.

  The path must be a sequential collection of concrete path segments, with
  `[]` naming the whole app-db root. Path validation happens before anything
  is registered. So, in development builds, does compiling the schema
  through the validator port's `:compile`: a malformed schema such as a
  childless `[:vector]` throws here and registers nothing. Returns the
  canonical vector path."
  ([path schema] (reg-app-schema path nil schema))
  ([path metadata schema]
   (let [metadata (normalize-app-schema-metadata path metadata)]
     ;; Reject malformed and runtime-db paths before registering.
     (assert-app-schema-path! path)
     ;; A malformed schema throws here, at the call that wrote it.
     (when rf.interop/debug-enabled?
       (rf.schemas.validator/run-compiler schema))
     (let [;; Registration, resolution and digesting share one canonical
           ;; vector identity.
           path        (rf.path/normalize-concrete path)
           ;; Preserve open registration metadata, but stamp the positional
           ;; schema and canonical path over caller-supplied values.
           schema-meta (rf.source-coords/merge-coords
                         (assoc (dissoc metadata :schema)
                                :schema schema :path path))
           ;; `:was` is the path's latest registration before this one. Its
           ;; presence — not the truthiness of its schema token — is what
           ;; makes this a re-registration: a schema value is opaque to
           ;; re-frame, so a registered nil is a real declaration a custom
           ;; validator may interpret (Spec 010 §The `:schema` value is
           ;; opaque).
           {:keys [was]} (binding [rf.registrar/*missing-doc-exempt?* *bulk-entry?*]
                           (rf.registrar/register! :app-schema path schema-meta))]
       ;; Read-only diagnostics are absent from production builds.
       (when rf.interop/debug-enabled?
         (maybe-warn-walker-opaque! schema path)
         (maybe-emit-schema-violations! path (some? was) (:schema was) schema))
       path))))

(defn reg-app-schemas
  "Bulk-register a map of `{path -> schema}`. Designed for feature-modular
  apps (per Conventions §Feature-modularity prefix convention) where a single
  feature module registers 5–20 schemas under a common path prefix like
  `[:cart …]` or `[:auth …]`.

  Shape:

    (rf/reg-app-schemas {[:auth]                AuthSlice
                         [:auth :login-form]    FormSlice
                         [:cart]                CartSlice
                         [:cart :items]         [:vector CartItem]})

  Each entry registers through the singular `reg-app-schema`, so every
  entry is its own `:app-schema` registration, frame-agnostic, carrying
  source-coords captured from this call site. A frame — a frame-id or frame
  value as the second argument, or a `:frame` key in it — throws
  `:rf.error/app-schema-frame-target`, as the singular form does. The whole
  batch is validated (its shape, every path, the second argument, and in
  development builds every schema, through the port's `:compile`) before any
  entry registers.

  A bulk entry has no slot for a per-path `:doc`, so this form never emits
  `:rf.warning/missing-doc`. To document a path, register it with
  `reg-app-schema` and a `:doc` in its metadata map.

  DEVELOPMENT-BUILD ASSERTION, exactly as for the singular form: every
  entry in the batch registers in a production build but is never checked
  there, so a violating candidate installs silently. Your app-db schemas
  do not run in production builds. Per Spec 010 §Production builds.

  Returns the vector of canonical paths registered, in map iteration order."
  ([path->schema] (reg-app-schemas path->schema nil))
  ([path->schema opts]
   ;; Reject a malformed batch before path iteration can silently no-op.
   (assert-bulk-schemas-arg! path->schema)
   (assert-bulk-opts! opts)
   ;; Validate every path before registering so an invalid entry cannot leave
   ;; a partially registered batch.
   (run! assert-app-schema-path! (keys path->schema))
   (when rf.interop/debug-enabled?
     (run! rf.schemas.validator/run-compiler (vals path->schema)))
   (binding [*bulk-entry?* true]
     (mapv (fn [[path schema]] (reg-app-schema path schema))
           path->schema))))

(defn ^:no-doc frame-schema-entries
  "Cross-artefact seam — consumed by `re-frame.epoch` and Story via the
  `:schemas/frame-schema-entries` late-bind hook, and the read behind
  `app-schemas` and the digest. Returns
  the `{path → registration}` map of the app-db schemas frame `frame-target`
  RESOLVES, or `{}`: its sealed generation's, the live store's for a frame
  made without `:images`, and each path's latest registration for a frame
  carrying neither. A path more than one namespace registers throws
  `:rf.error/ambiguous-registration` in a frame made without `:images`, never
  validating without it.

  Takes a frame target (an id or a frame value), not an opts map: it is the
  private twin of the public `app-schemas`, which reads the same fact through
  the one `{:frame f}` spelling. There is no public var of this name."
  [frame-target]
  (in-frame-resolution frame-target resolved-registrations))

(defn ^:no-doc frame-schema-resolution
  "The validation hot path's read of frame `frame-target`'s schemas:
  `{:entries {path → registration} :ambiguous #{path}}`. `:ambiguous` names
  each path the frame refuses because more than one namespace registers it, so
  validation can fail loudly on such a path exactly when a candidate puts a
  value there to check ([[throw-ambiguous-schema!]]) — never validating that
  value without its schema, and never refusing a commit over a path it leaves
  absent."
  [frame-target]
  (in-frame-resolution frame-target resolution-with-ambiguity))

(defn ^:no-doc throw-ambiguous-schema!
  "Throw the `:rf.error/ambiguous-registration` frame `frame-target` raises
  for `path`, one of the `:ambiguous` paths of [[frame-schema-resolution]]."
  [frame-target path]
  (in-frame-resolution frame-target #(rf.registrar/lookup :app-schema path)))

(defn app-schemas
  "Return the app-db schema registrations frame `f` resolves, as a
  `{path → registration-metadata}` map, or `{}`.

  `(app-schemas {:frame f})`. `:frame` is REQUIRED and accepts a frame-id
  keyword or a frame value (`rf/make-frame`'s return token); a frameless or
  non-map call raises `:rf.error/no-frame-context`.

  Each value is the registration's metadata — `:path`, `:schema`, and the
  source-coords `:ns` / `:line` / `:file` — the same `{id → meta}` shape the
  registrar grammar's `rf/registrations` answers, because
  `(rf/registrations {:frame f :kind :app-schema})` reads the same
  resolution. Project `:schema` when only the schema values are wanted:
  `(update-vals (app-schemas {:frame f}) :schema)`.

  Per Spec 010 §Schemas resolve through the frame's images the result is the
  frame's own resolution: every schema it inherits from the registrations it
  resolves, and a later image's schema in place of the one it shadows. A path
  more than one namespace registers throws `:rf.error/ambiguous-registration`
  for a frame made without `:images`."
  [opts]
  (frame-schema-entries (read-frame-id opts 're-frame.schemas/app-schemas)))

(defn app-schema-meta
  "Return the registration metadata of the schema one path resolves to in a
  frame, or nil.

  `(app-schema-meta {:frame f :path p})`. Both keys are REQUIRED; `:frame`
  accepts a frame-id keyword or a frame value. The value is the
  registration's metadata — source-coords (`:ns` / `:line` / `:file`),
  `:path` and `:schema` — so the resolved schema itself is
  `(:schema (app-schema-meta {:frame f :path p}))`.

  This is the canonical single-path read surface for app-db schema metadata:
  pair tools, 10x panels and source-coord tests use it to find where the
  schema a frame runs was registered. Per Spec 010 §Schemas as a tooling and
  agent surface."
  [{:keys [path] :as opts}]
  (let [frame-id (read-frame-id opts 're-frame.schemas/app-schema-meta)
        ;; Lookup uses the same canonical concrete path identity as
        ;; registration.
        path     (rf.path/normalize-concrete path)]
    (in-frame-resolution frame-id #(rf.registrar/lookup :app-schema path))))

;; ---- hot-reload semantics ------------------------------------------------
;;
;; `reg-app-schema` is a `register!` like any other: re-registering a path from
;; the same namespace replaces that namespace's slot, the hot-reload sweep
;; (`re-frame.registrar/sweep-reload-cycle!`) forgets a schema a reloaded
;; namespace no longer registers, and `(rf/clear :app-schema path)` removes
;; one. A frame made without `:images` reads the change at its next
;; validation; a frame made with them re-resolves its generation.
;;
;; One dev-only side effect accompanies a registration:
;;   `:rf.schema/violation` — emitted in each live frame that resolves the
;;   path when the schema is new or CHANGED and that frame's live `app-db`
;;   value at the path fails it (Spec 010 §Schema migration on hot-reload).
;;   See `maybe-emit-schema-violations!`. Gated on `interop/debug-enabled?`
;;   and DCE'd in production.
;;
;; Re-registration does not refresh durable app-db classification; schemas
;; do not own that registry.
