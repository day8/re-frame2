(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :spec metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  Per Spec 010 §Per-frame schemas, `app-db` schemas are **frame-scoped**:
  registered against the active frame at registration time and looked
  up per frame at validation time. Stories, multi-instance widgets,
  per-test fixtures need shape-flexibility — a stripped-down schema
  registered against `:story.foo/empty` does NOT bleed into the
  `:rf/default` frame's contract. `(reg-app-schema path schema)` uses
  the active frame (`(frame/current-frame)`); `(reg-app-schema path
  schema {:frame frame-id})` registers explicitly.

  This first-pass implementation uses Malli when available; if the user
  hasn't pulled Malli into their project, schemas are silently ignored."
  (:require #?(:cljs [goog.crypt :as gcrypt])
            #?(:cljs [goog.crypt.Sha256])
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace])
  #?(:clj (:import [java.security MessageDigest]
                   [java.nio.charset StandardCharsets])))

(defn- malli-validate*
  [schema value]
  ;; Defensive: if Malli isn't loaded, treat as 'pass'. Real builds will
  ;; have Malli on the path. Uses requiring-resolve on JVM (lazy load);
  ;; on CLJS we require malli.core directly via :require so the var is
  ;; reachable through resolve.
  #?(:clj
     (try
       (let [validate (requiring-resolve 'malli.core/validate)]
         (validate schema value))
       (catch Throwable _ true))
     :cljs
     (try
       (let [validate (resolve 'malli.core/validate)]
         (if validate (validate schema value) true))
       (catch :default _ true))))

(defn- malli-explain*
  [schema value]
  #?(:clj
     (try
       (let [explain (requiring-resolve 'malli.core/explain)]
         (explain schema value))
       (catch Throwable _ nil))
     :cljs
     (try
       (let [explain (resolve 'malli.core/explain)]
         (when explain (explain schema value)))
       (catch :default _ nil))))

;; ---- per-frame storage ----------------------------------------------------
;;
;; Per Spec 010 §Per-frame schemas. The registry shape is
;;   {frame-id {path schema-meta}}
;; mirroring `re-frame.flows`'s frame-scoping (rf2-lvwr). The registrar
;; (`:app-schema` kind) still receives a slot-per-path entry so source-
;; coords / hot-reload tooling can introspect a path's most-recent
;; registration; the per-frame map is the authoritative store the
;; validation hot path reads.

(defonce
  ^{:doc "frame-id → path → schema-meta. Per-frame so a story or test
          fixture's reg-app-schema does not bleed into the default
          frame's contract."}
  schemas-by-frame
  (atom {}))

(defn- resolve-frame
  "Pluck :frame from the opts map; default to (frame/current-frame)."
  [opts]
  (or (:frame opts) (frame/current-frame)))

(defn- coerce-opts
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
  "Internal: return the {path → schema-meta} map for a frame, or {}.
  Used by the validation hot path and the epoch-restore predicates so
  they can read the same authoritative store reg-app-schema writes."
  [frame-id]
  (get @schemas-by-frame frame-id {}))

;; ---- schema digest --------------------------------------------------------
;;
;; Per Spec 010 §Digest algorithm — a stable, cross-runtime hash over a
;; frame's registered `app-db` schema set. The wire form is
;; `"sha256:" + first-16-hex-chars`. Two frames produce equal digests iff
;; their `{path → schema-value}` maps serialise byte-for-byte identically.
;;
;; Used by:
;;   - The epoch-restore schema-mismatch trace (epoch.cljc) — pinpoints
;;     when a frame's schema set drifted between record and restore.
;;   - SSR hydration (Spec 011) — server stamps its digest on the payload;
;;     the client compares its own digest at hydrate time.
;;   - Pair tools — flag attached REPL sessions whose runtime schemas
;;     have shifted under them.

(defn- canonicalise-schema-form
  "Normalise a Malli EDN schema form for stable byte serialisation:
  metadata is stripped, and map-keys (the Malli `props` map's contents
  in `[:map {k v ...} & children]`) are emitted in (compare a b) order
  via `sort-by str`. The result is fed through `pr-str` to produce the
  canonical UTF-8 byte sequence Spec 010 hashes."
  [form]
  (letfn [(canon [x]
            (cond
              (map? x)
              ;; Sort keys lexicographically by their printed form so two
              ;; equivalent maps with different insertion order serialise
              ;; identically. Using a sorted-map preserves the order
              ;; through pr-str.
              (into (sorted-map-by (fn [a b]
                                     (compare (pr-str a) (pr-str b))))
                    (map (fn [[k v]] [(canon k) (canon v)]))
                    x)
              (vector? x) (mapv canon x)
              (set? x)    (into (sorted-set-by (fn [a b]
                                                 (compare (pr-str a) (pr-str b))))
                                (map canon)
                                x)
              (seq? x)    (doall (map canon x))
              :else       x))]
    (canon form)))

(defn- schema-print
  "Serialise a schema value to a stable UTF-8 byte-source string. Per
  Spec 010 §Digest algorithm step 1, the CLJS reference uses `pr-str`
  over the Malli EDN form with map-key ordering normalised and metadata
  stripped."
  [schema-value]
  (binding [*print-meta*       false
            *print-readably*   true
            *print-dup*        false
            *print-namespace-maps* false]
    (pr-str (canonicalise-schema-form schema-value))))

(defn- utf8-bytes
  "Encode a string as UTF-8 bytes."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (gcrypt/stringToUtf8ByteArray s)))

(defn- bytes->hex
  "Lowercase hex encoding of a byte sequence. Cross-runtime byte-stable."
  [bs]
  #?(:clj  (let [sb (StringBuilder.)]
             (doseq [b bs]
               (let [u (bit-and (long b) 0xff)]
                 (when (< u 0x10) (.append sb \0))
                 (.append sb (Long/toString u 16))))
             (.toString sb))
     :cljs (gcrypt/byteArrayToHex bs)))

(defn- sha256-hex
  "Return the lowercase 64-char hex SHA-256 of a UTF-8 string. Uses
  the platform's native SHA-256 (java.security.MessageDigest on JVM,
  goog.crypt.Sha256 on CLJS) — both are FIPS 180-3 compliant and
  produce byte-identical output for the same input."
  [s]
  #?(:clj
     (let [md (MessageDigest/getInstance "SHA-256")]
       (.update md ^bytes (utf8-bytes s))
       (bytes->hex (.digest md)))
     :cljs
     (let [h (goog.crypt.Sha256.)]
       (.update h (utf8-bytes s))
       (bytes->hex (.digest h)))))

(defn- digest-line
  "Per Spec 010 §Digest algorithm step 3 — emit one line per
  `(path, schema-value)` of the form `<path-string> <hex-of-sha256>\\n`."
  [path schema-value]
  (str (pr-str path)
       " "
       (sha256-hex (schema-print schema-value))
       "\n"))

(defn- compute-digest
  "Run the Spec 010 §Digest algorithm against the supplied
  `{path → schema-value}` map. Returns the canonical wire form
  `\"sha256:\" + first-16-hex-chars`."
  [path->schema]
  (let [lines       (mapv (fn [[path schema]] (digest-line path schema))
                          path->schema)
        sorted      (sort lines)            ;; lexicographic byte order
        concatted   (apply str sorted)
        full-hex    (sha256-hex concatted)]
    (str "sha256:" (subs full-hex 0 16))))

(defn app-schemas-digest
  "Return a stable digest of the frame's registered `app-db` schema set
  — the same shape `app-schemas` would return — as the canonical wire
  form `\"sha256:\" + 16 lowercase-hex-chars`.

  Per Spec 010 §Digest algorithm the procedure is byte-deterministic
  and cross-runtime: a CLJS server and a CLJS client running the same
  schema set produce byte-identical digests. The empty schema set
  produces a stable, well-defined digest (the SHA-256 of the empty
  string — the lines list collapses to a single empty concatenation).

  Arities:
    (app-schemas-digest)              ;; sugar for (app-schemas-digest {})
    (app-schemas-digest frame-id)     ;; keyword sugar
    (app-schemas-digest opts)         ;; opts map; supports {:frame ...}

  Uses include the SSR hydration handshake (Spec 011 §The :rf/hydrate
  event), the epoch-restore schema-mismatch trace (Tool-Pair §Time-
  travel), and pair-tool drift detection."
  ([] (app-schemas-digest {}))
  ([opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
     (compute-digest (app-schemas {:frame frame-id})))))

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

;; ---- validation entry points ----------------------------------------------

(defn- type-of-value [v]
  (cond
    (string? v)  "string"
    (integer? v) "integer"
    (number? v)  "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (map? v)     "map"
    (vector? v)  "vector"
    (nil? v)     "nil"
    :else        (str (type v))))

(defn validate-app-db!
  "After a handler commits :db, walk every registered app-schema for the
  named frame and validate the post-state. Failures trace as
  :rf.error/schema-validation-failure with a Malli explanation.

  Per Spec 010 §Per-frame schemas only the named frame's schemas are
  walked — schemas registered against sibling frames are ignored.

  Arities:
    (validate-app-db! db)                       ;; current frame
    (validate-app-db! db event-id)              ;; current frame, named handler
    (validate-app-db! db event-id frame-id)     ;; explicit frame

  event-id (optional) names the handler whose commit prompted the
  failure — surfaced as :failing-id in the error tags."
  ([db] (validate-app-db! db nil (frame/current-frame)))
  ([db event-id] (validate-app-db! db event-id (frame/current-frame)))
  ([db event-id frame-id]
   (when interop/debug-enabled?
     (doseq [[path m] (frame-schema-entries frame-id)]
       (let [val-at (get-in db path)
             schema (:schema m)]
         (when-not (malli-validate* schema val-at)
           (trace/emit-error! :rf.error/schema-validation-failure
                              (cond-> {:where    :app-db
                                       :path     path
                                       :value    val-at
                                       :frame    frame-id
                                       :explain  (malli-explain* schema val-at)
                                       :reason   (str "App-db at path " path
                                                      " failed schema " schema
                                                      ": expected "
                                                      (cond
                                                        (and (vector? schema)
                                                             (= 1 (count schema))
                                                             (keyword? (first schema)))
                                                        (first schema)
                                                        :else schema)
                                                      ", got " (type-of-value val-at) ".")
                                       :recovery :no-recovery}
                                event-id (assoc :failing-id event-id)))))))))

(defn validate-event!
  "Per Spec 010 §Validation order step 1 — before an event handler runs,
  validate the event vector against any :spec on the handler's metadata.

  Returns true on pass (or when validation is elided / no schema is
  attached), false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :event; the caller
  is responsible for skipping the handler (recovery: :no-recovery).

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so :advanced+goog.DEBUG=false
  DCE-elides every reason string, every keyword, and every malli call."
  [event-id event handler-meta]
  (if interop/debug-enabled?
    (if-let [schema (:spec handler-meta)]
      (if (malli-validate* schema event)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :event
                              :event-id    event-id
                              :failing-id  event-id
                              :spec-id     event-id
                              :received    event
                              :event       event
                              :malli-error (malli-explain* schema event)
                              :explain     (malli-explain* schema event)
                              :reason      (str "Event " event-id
                                                " payload failed schema "
                                                schema ", got "
                                                (type-of-value event) ".")
                              :recovery    :no-recovery})
          false))
      true)
    true))

(defn validate-sub-return!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :spec on the sub's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :sub-return; the
  caller is responsible for replacing the value with the
  default (nil) per the :replaced-with-default recovery.

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly."
  [sub-id query-v value sub-meta]
  (if interop/debug-enabled?
    (if-let [schema (:spec sub-meta)]
      (if (malli-validate* schema value)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :sub-return
                              :sub-id      sub-id
                              :failing-id  sub-id
                              :spec-id     sub-id
                              :query-v     query-v
                              :received    value
                              :value       value
                              :malli-error (malli-explain* schema value)
                              :explain     (malli-explain* schema value)
                              :reason      (str "Subscription " sub-id
                                                " return value failed schema "
                                                schema ", got "
                                                (type-of-value value) ".")
                              :recovery    :replaced-with-default})
          false))
      true)
    true))

(defn validate-cofx!
  "Per Spec 010 §Validation order step 2 — after a cofx injects its value
  into the merged context, validate that value against any :spec on the
  cofx's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :cofx; the caller is
  responsible for skipping the handler (recovery: :no-recovery).

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly."
  [cofx-id event-id value cofx-meta]
  (if interop/debug-enabled?
    (if-let [schema (:spec cofx-meta)]
      (if (malli-validate* schema value)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :cofx
                              :cofx-id     cofx-id
                              :event-id    event-id
                              :failing-id  event-id
                              :spec-id     cofx-id
                              :received    value
                              :value       value
                              :malli-error (malli-explain* schema value)
                              :explain     (malli-explain* schema value)
                              :reason      (str "Coeffect " cofx-id
                                                " injected value failed schema "
                                                schema ", got "
                                                (type-of-value value) ".")
                              :recovery    :no-recovery})
          false))
      true)
    true))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.router, re-frame.cofx, and re-frame.subs need to call into
;; schema validation but cannot `:require` this namespace without a
;; cyclic load order. Publish entry points through the late-bind hook
;; registry. See re-frame.late-bind.
;;
;; Per rf2-p7va (the schemas artefact split), `re-frame.core` and
;; `re-frame.test-support` MUST NOT `:require [re-frame.schemas]` either
;; — the schemas artefact is an optional dep, and a static require would
;; force every consumer of the core artefact to drag its symbols (and
;; the Malli dep) onto the classpath. The public-API re-exports
;; (`reg-app-schema`, `app-schema-at`, `app-schemas`) and the
;; test-support reset / snapshot helpers are published through the same
;; late-bind table; consumers without the schemas artefact see the
;; hooks unregistered and the surface no-ops cleanly.

;; Validation hot-path hooks (consumed by router / cofx / subs / epoch).
(late-bind/set-fn! :schemas/validate-app-db!     validate-app-db!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub-return! validate-sub-return!)
(late-bind/set-fn! :schemas/validate-cofx!       validate-cofx!)
(late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)

;; Public-API re-export hooks (consumed by re-frame.core).
(late-bind/set-fn! :schemas/reg-app-schema       reg-app-schema)
(late-bind/set-fn! :schemas/app-schema-at        app-schema-at)
(late-bind/set-fn! :schemas/app-schemas          app-schemas)
(late-bind/set-fn! :schemas/app-schemas-digest   app-schemas-digest)

;; Test-support hooks (consumed by re-frame.test-support's
;; reset-runtime-fixture). The fixture wants to capture and restore
;; the `schemas-by-frame` atom around each test, so it asks for a
;; snapshot before the test, a clear in the middle, and a restore
;; afterwards. When the schemas artefact is not on the classpath
;; these hooks are nil and the fixture no-ops the schema steps —
;; correct, because there is no schema state to preserve.

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

(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
