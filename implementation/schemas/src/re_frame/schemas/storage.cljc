(ns re-frame.schemas.storage
  "Per-frame storage + registration surface for app-db schemas.

  Per Spec 010 §Per-frame schemas. The registry shape is
    {frame-id {path schema-meta}}
  mirroring `re-frame.flows`'s frame-scoping (rf2-lvwr). The registrar
  (`:app-schema` kind) still receives a slot-per-path entry so source-
  coords / hot-reload tooling can introspect a path's most-recent
  registration; the per-frame map is the authoritative store the
  validation hot path reads.

  Owns:
    - `schemas-by-frame` atom (the authoritative store).
    - `reg-app-schema` / `reg-app-schemas` registration entry points.
    - `app-schema-at` / `app-schemas` query entry points.
    - `frame-schema-entries` cross-artefact seam consumed by
      `re-frame.elision` / `re-frame.epoch` via the late-bind table.
    - `snapshot-schemas-by-frame` / `restore-schemas-by-frame!` /
      `clear-schemas-by-frame!` — test-support hooks consumed by
      `re-frame.test-support`'s reset-runtime fixture.
    - The hot-reload replacement-hook seam (`on-app-schema-registry-event!`)."
  (:require [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]))

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
  opts-map form `(app-schemas {:frame frame-id})`."
  [opts-or-frame-id]
  (cond
    (keyword? opts-or-frame-id) {:frame opts-or-frame-id}
    (map?     opts-or-frame-id) opts-or-frame-id
    (nil?     opts-or-frame-id) {}
    :else
    (throw (ex-info ":rf.error/bad-app-schemas-arg"
                    {:received opts-or-frame-id
                     :expected "keyword frame-id or opts map"}))))

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure.

  Per Spec 010 §Per-frame schemas this registration is frame-scoped.
  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless the
  caller is inside a (with-frame ...) wrapper or a frame-provider."
  ([path schema] (reg-app-schema path schema {}))
  ([path schema opts]
   (let [frame-id (resolve-frame opts)
         meta     (source-coords/merge-coords
                    {:schema schema :path path :frame frame-id})]
     ;; Stamp the registrar slot so source-coords / handler-meta /
     ;; hot-reload tooling continue to see :app-schema entries. The
     ;; registrar is per-process; the per-frame map is the
     ;; authoritative store for validation.
     (registrar/register! :app-schema path meta)
     (swap! schemas-by-frame assoc-in [frame-id path] meta)
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
  internally for each entry — every entry stamps its own registrar slot
  with source-coords captured from this call site.

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

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; When a frame is destroyed (or a schema is explicitly cleared via the
;; registrar), drop its per-frame entry so the validation hot path
;; doesn't keep walking dead schemas. Per Spec 001 §Hot-reload semantics
;; the registrar's :app-schema slot is shared (path-keyed) across
;; frames; replacement-hook fires on EVERY re-register, not only when
;; we want to invalidate. We only act on :rf.registry/handler-cleared-
;; equivalent paths (kind = :app-schema, no `:now` means cleared).

(defn- on-app-schema-registry-event!
  [{:keys [kind id was now]}]
  (when (= kind :app-schema)
    ;; A `register!` always supplies `now`; `unregister!` / `clear-kind!`
    ;; produce no replacement-hook callback (they don't fire hooks). The
    ;; registrar today only invokes hooks on replacement, so this hook
    ;; sees re-registrations against the same path. Re-registering with
    ;; an explicit :frame opt MAY be against a different frame — in
    ;; which case we leave the prior frame's entry alone (it was
    ;; registered separately) and the new frame's entry is updated by
    ;; reg-app-schema directly. Nothing to do here today; the hook is
    ;; published as the seam future invalidation work plugs into.
    (let [_ was _ now])))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! on-app-schema-registry-event!)
      :installed))

;; ---- test-support snapshot / restore -------------------------------------
;;
;; Consumed by re-frame.test-support's reset-runtime-fixture via the
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
  and by `reset-runtime-fixture`'s `:clear-kinds [:app-schema]` path."
  []
  (reset! schemas-by-frame {}))
