(ns re-frame.schemas.storage
  "Per-frame storage + registration surface for app-db schemas.

  Per Spec 010 §Per-frame schemas. The registry shape is
    {frame-id {path schema-meta}}
  mirroring `re-frame.flows`'s frame-scoping (rf2-lvwr). The per-frame
  atom is the **single source of truth** for `app-db` schemas — there is
  no registrar `:app-schema` slot (resolved rf2-0frdi). Source-coords /
  hot-reload / pair-tool introspection reads through `app-schema-meta-at`,
  which returns the per-frame metadata map (including the registration's
  `:ns` / `:line` / `:file` source-coords).

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
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.schemas.validator :as validator]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "frame-id → path → schema-meta. Per-frame so a story or test
          fixture's reg-app-schema does not bleed into the default
          frame's contract."}
  schemas-by-frame
  (atom {}))

(defn resolve-frame
  "Pluck :frame from the opts map; default to (frame/current-frame)."
  [opts]
  (or (:frame opts) (frame/current-frame)))

(defn coerce-opts
  "Permit the keyword-only sugar `(app-schemas frame-id)` and the
  opts-map form `(app-schemas {:frame frame-id})`. Every zero-arity
  caller (`app-schema-at`, `app-schema-meta-at`, `app-schemas`,
  `app-schemas-digest`) supplies `{}` explicitly — `nil` is not a
  permitted argument."
  [opts-or-frame-id]
  (cond
    (keyword? opts-or-frame-id) {:frame opts-or-frame-id}
    (map?     opts-or-frame-id) opts-or-frame-id
    :else
    (throw (ex-info ":rf.error/bad-app-schemas-arg"
                    {:received opts-or-frame-id
                     :expected "keyword frame-id or opts map"}))))

;; ---- validator-unavailable warning (rf2-fq7d2) ----------------------------
;;
;; Per Spec 010 §Recommended soft-pass, the schemas artefact ships with a
;; Malli-delegating default validator that returns true ("pass") when the
;; `:schemas/malli-validate` late-bind hook is unbound — i.e. when the
;; `re-frame.schemas.malli` adapter ns hasn't been required at app boot.
;; This is intentional (apps that swap in a non-Malli validator must work),
;; but it has a footgun: a `reg-app-schema` call WITH the default validator
;; AND no Malli adapter loaded validates nothing. Boundary-validated
;; handlers silently accept untrusted input.
;;
;; The warning fires once per process from `reg-app-schema` /
;; `reg-app-schemas` when:
;;   1. `:schemas/malli-validate` late-bind hook is unbound, AND
;;   2. `validator-fn` is still the framework default.
;;
;; Apps that registered a non-default validator (a Zod port, clojure.spec
;; bridge, etc.) opted out of Malli explicitly — no warning.

(defonce ^:private validator-unavailable-warned
  ;; Process-lifecycle one-shot. Reset by `clear-validator-unavailable-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-validator-unavailable-warned!
  "Reset the one-shot `:rf.warning/schema-validator-unavailable` cache.
  Used by test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! validator-unavailable-warned false))

;; ---- walker-opaque warning (rf2-jsokn / rf2-ycqtv finding #12) ------------
;;
;; Per Spec 010 §The `:spec` value is opaque to re-frame, the framework's
;; schema walker (`re-frame.schemas.walker`) is pure data — it handles
;; vector-form Malli EDN and treats compiled `m/schema` values, registry
;; refs, and anything-else-non-vector as opaque leaves. A user that
;; registers a registry-ref schema (`(rf/reg-app-schema [:user]
;; :my/user-schema)`) and puts `:sensitive?` / `:large?` per-slot flags
;; inside the registry definition will see the walker **silently skip**
;; them — the validation-failure trace won't redact the sensitive slot
;; and the size-elision walker won't see the `:large?` declarations.
;;
;; This warning fires once per process from `reg-app-schema` /
;; `reg-app-schemas` when the registered schema is not a vector form
;; (i.e. the walker cannot introspect it). Symmetric with the
;; `:rf.warning/schema-validator-unavailable` warn-once-per-process
;; pattern above. Cost is one boot-time predicate per `reg-app-schema`
;; call; the warning is the discoverability nudge for the two workable
;; fallbacks (vector form, or registration-meta `:sensitive?`).

(defonce ^:private walker-opaque-warned
  ;; Process-lifecycle one-shot. Reset by `clear-walker-opaque-warned!`
  ;; (used by the schemas test-fixture's `reset-runtime`).
  (atom false))

(defn clear-walker-opaque-warned!
  "Reset the one-shot `:rf.warning/schema-walker-opaque` cache. Used by
  test fixtures so each case starts from a clean diagnostic slate."
  []
  (reset! walker-opaque-warned false))

(defn- maybe-warn-walker-opaque!
  "Emit `:rf.warning/schema-walker-opaque` once per process when
  `reg-app-schema` / `reg-app-schemas` is invoked with a schema value
  that is NOT a vector form (the walker can only introspect Malli EDN
  vector forms — `m/schema` compiled values and registry-ref keywords
  are treated as opaque leaves).

  Callers MUST wrap invocations in `(when interop/debug-enabled? ...)`
  so the production bundle DCEs the consult+emit branch (Spec 009
  §Production builds)."
  [schema path]
  (when (and (not (vector? schema))
             (not @walker-opaque-warned))
    (when (compare-and-set! walker-opaque-warned false true)
      (when-let [emit! (late-bind/get-fn :trace/emit!)]
        (emit! :warning :rf.warning/schema-walker-opaque
               {:path path
                :schema-kind (cond
                               (keyword? schema) :registry-ref
                               (map?     schema) :compiled-schema-object
                               :else             :unknown)
                :reason
                (str "reg-app-schema was called with a non-vector schema"
                     " form (registry ref / compiled m/schema / other"
                     " opaque value). The schema-walker (used for"
                     " per-slot `:sensitive?` / `:large?` extraction)"
                     " can only introspect vector-form Malli EDN —"
                     " per-slot flags inside an opaque value are"
                     " silently skipped. Two workable shapes: (1)"
                     " register the vector form directly so the walker"
                     " can introspect it; (2) use registration-level"
                     " `:sensitive?` metadata on the consuming"
                     " `reg-event-*` for coarse-grained honour. Per"
                     " Spec 010 §The `:spec` value is opaque to"
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

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure.

  Per Spec 010 §Per-frame schemas this registration is frame-scoped.
  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless the
  caller is inside a (with-frame ...) wrapper or a frame-provider.

  Per rf2-0frdi the schemas artefact owns its own per-frame side-table
  (`schemas-by-frame`) — there is no registrar `:app-schema` slot. The
  authoritative store is keyed by `(frame-id, path)` so that registrations
  against frame A and frame B against the same path are independent
  entries. Pair-tools and source-coord tests read via `app-schema-meta-at`."
  ([path schema] (reg-app-schema path schema {}))
  ([path schema opts]
   (let [frame-id (resolve-frame opts)
         meta     (source-coords/merge-coords
                    {:schema schema :path path :frame frame-id})]
     (swap! schemas-by-frame assoc-in [frame-id path] meta)
     ;; Per rf2-fq7d2: dev-time nudge when the Malli adapter is unloaded
     ;; AND the framework-default validator is still installed — the
     ;; default soft-passes per Spec 010 §Recommended soft-pass, so a
     ;; reg-app-schema with no validator wired up validates nothing.
     ;; Production elides via the outer `interop/debug-enabled?` gate
     ;; (Spec 009 §Production builds).
     (when interop/debug-enabled?
       (maybe-warn-validator-unavailable!)
       ;; Per rf2-jsokn: dev-time nudge when the registered schema is
       ;; a non-vector form (registry-ref keyword, compiled m/schema
       ;; object, etc.) — the schema walker can only introspect vector
       ;; Malli EDN, so per-slot `:sensitive?` / `:large?` flags inside
       ;; an opaque value are silently skipped. Production elides via
       ;; the outer `interop/debug-enabled?` gate.
       (maybe-warn-walker-opaque! schema path))
     path)))

(defn reg-app-schemas
  "Bulk-register a map of `{path -> schema}` against the active frame
  (or the frame named in `opts`). Per rf2-jzs9 — the plural form of
  `reg-app-schema`, designed for feature-modular apps (per Conventions
  §Feature-modularity prefix convention) where a single feature module
  registers 5–20 schemas under a common path prefix like `[:cart …]` or
  `[:auth …]`.

  Shape:

    (rf/reg-app-schemas {[:auth]                AuthSlice
                         [:auth :login-form]    FormSlice
                         [:cart]                CartSlice
                         [:cart :items]         [:vector CartItem]})

    (rf/reg-app-schemas {[:foo] FooSchema} {:frame :tenant/a})

  Per Spec 010 §Per-frame schemas registration is frame-scoped; the
  `:frame` opt overrides the default `(frame/current-frame)` resolution
  for every entry in the map (you cannot mix frames in a single call).
  The singular form `reg-app-schema` remains available and is used
  internally for each entry — every entry stamps its own per-frame side-
  table entry with source-coords captured from this call site.

  Returns the vector of paths registered, in iteration order. Last-
  write-wins on duplicate paths inside the same map (map iteration
  order in Clojure is small-map literal-order, large-map hash order;
  callers that rely on deterministic order should use a singular
  `reg-app-schema` chain instead)."
  ([path->schema] (reg-app-schemas path->schema {}))
  ([path->schema opts]
   (mapv (fn [[path schema]] (reg-app-schema path schema opts))
         path->schema)))

(defn app-schema-at
  "Look up the registered schema for a path in a frame, or nil.

  Arities:
    (app-schema-at path)         ;; current frame (or :rf/default)
    (app-schema-at path opts)    ;; opts map; :frame names the frame
                                 ;; (keyword sugar also accepted)

  Per Spec 010 §Schemas as a tooling and agent surface."
  ([path] (app-schema-at path {}))
  ([path opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
     (when-let [m (get-in @schemas-by-frame [frame-id path])]
       (:schema m)))))

(defn app-schema-meta-at
  "Return the registration metadata map for a path in a frame, or nil.

  Unlike `app-schema-at` (which returns just the `:schema` value), this
  returns the full meta map stamped at `reg-app-schema` — including
  source-coords (`:ns` / `:line` / `:file`), `:path`, `:schema`, and
  `:frame`. Used by pair-tools, 10x panels, and source-coord tests that
  need to introspect where a schema was registered.

  Per rf2-0frdi this is the canonical replacement for the legacy
  `(rf/handler-meta :app-schema path)` query — the registrar `:app-schema`
  slot is no longer populated; the per-frame side-table is the single
  source of truth.

  Arities:
    (app-schema-meta-at path)         ;; current frame (or :rf/default)
    (app-schema-meta-at path opts)    ;; opts map; :frame names the frame
                                      ;; (keyword sugar also accepted)"
  ([path] (app-schema-meta-at path {}))
  ([path opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
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
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
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
;; Per rf2-0frdi the schemas artefact no longer participates in the
;; registrar's replacement-hook stream — `reg-app-schema` writes only to
;; `schemas-by-frame`. Re-registering a `(frame-id, path)` entry is an
;; ordinary `swap!`: the new meta replaces the prior entry atomically, and
;; the validation hot path (`frame-schema-entries`) picks up the new
;; schema on its next read. There is nothing to invalidate elsewhere —
;; the per-frame map IS the cache.

;; ---- test-support snapshot / restore -------------------------------------
;;
;; Consumed by re-frame.test-support's reset-runtime-fixture-factory via the
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
  and by `reset-runtime-fixture-factory`'s `:clear-kinds [:app-schema]` path."
  []
  (reset! schemas-by-frame {}))

(defn on-frame-destroyed!
  "Per Spec 002 §Destroy: drop every schema registered against the
  destroyed frame so a subsequent `reg-frame` of the same id starts
  with a clean schema slate. Called from `frame/destroy-frame!`
  through the `:schemas/on-frame-destroyed!` late-bind hook
  (mirrors the `:machines/on-frame-destroyed!` /
  `:ssr/on-frame-destroyed` cleanup contract).

  Without this cleanup, schemas registered against a destroyed
  frame would persist and re-fire when the frame is re-registered
  — under the rf2-wkxng / rf2-6m0se rollback contract that
  manifests as spurious rollbacks against orphan paths the new
  frame's :on-create never wrote. Idempotent — a missing frame
  entry is a no-op `dissoc`."
  [frame-id]
  (swap! schemas-by-frame dissoc frame-id))
