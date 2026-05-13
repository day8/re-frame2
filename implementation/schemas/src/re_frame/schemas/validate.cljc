(ns re-frame.schemas.validate
  "Validation entry points (Spec 010 §Validation order steps 1-6).

  Owns the five dev-time validate-*! fns the framework calls at the
  locked validation sites:

    - validate-event!       — pre-handler (event vector vs handler :spec)
    - validate-cofx!        — post-injection (cofx value vs cofx :spec)
    - validate-fx!          — pre-fx-handler (fx args vs fx :spec)
    - validate-app-db!      — post-handler-commit (frame's app-schemas)
    - validate-sub-return!  — post-sub-recompute (return value vs sub :spec)

  Also owns the production-side boundary-validation seam
  (`validate-with-registered-fn` / `explain-with-registered-fn`) that
  the boundary-validation interceptor (`re-frame.spec`, rf2-r2uh)
  reaches via the schemas-side late-bind hook.

  Per rf2-zkca8 §Leaf-size discipline this file exceeds the 250-line
  target ceiling at ~415 LoC; the carve-out applies because the file is
  catalogue-shaped — five structurally-parallel validate-*! fns sharing
  the same `(if debug-enabled? (if @validator-fn (if-let [schema ...]
  (if (run-validator ...) true (let [explanation ...] ...)) true) true)
  true)` skeleton, differing only in (a) the meta key the schema lives
  under, (b) the `:where` tag, (c) the `:reason` template, and (d) the
  sensitivity-source check. The audit's Q5 (rf2-x8x4p findings) proposes
  collapsing all five into a single parameterised `run-validation`
  primitive; that landing is sequenced AFTER this split — splitting the
  five along sibling files first would only multiply file-handle overhead
  without reducing per-session tokens, since every reader who touches one
  validate-*! fn typically wants to see all five side-by-side.

  Per Spec 009 §Production builds every dev-time validate-*! body lives
  inside an `(if interop/debug-enabled? ...)` gate as the OUTERMOST
  form so :advanced+goog.DEBUG=false DCE-elides every reason string,
  keyword, validator deref, and trace call. Per Spec 010 §Non-Malli
  validators (rf2-froe) the validator/explainer are pluggable via the
  registered atoms in `re-frame.schemas.validator`; when none is
  registered every fn here returns true (pass) without inspecting the
  schema.

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces (rf2-kj51z). The emit-sites redact the failing value before
  stamping a trace event when either:
    1. The schema slot at the failing path (or a containing slot)
       carries `:sensitive? true` in its Malli props.
    2. The surrounding registration metadata (handler-meta / cofx-meta /
       sub-meta / fx-meta) carries `:sensitive? true` — applies to
       every validation failure in that handler's scope as a coarse
       fallback.
  The substitution sentinel is `:rf/redacted` (the framework-reserved
  keyword per Spec 009 §`with-redacted`). The trace event's `:tags`
  map is stamped with `:sensitive? true` so consumers can route on it
  (until rf2-isdwf's top-level hoisting lands in core).

  The `:value`, `:received`, and `:explain` slots are redacted; the
  structural / categorical slots (`:path`, `:failing-id`, `:spec-id`,
  `:reason`) are kept — consumers need them to locate the broken slot
  without leaking user data."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.trace :as trace]))

(def ^:private redacted-sentinel
  "The `:rf/redacted` privacy sentinel emitted by validation traces
  for slots matching the `:sensitive?` predicate. Per Spec 009
  §`with-redacted` — the framework-reserved keyword that cannot
  collide with an app-defined value."
  :rf/redacted)

(defn- meta-sensitive?
  "True when the registration metadata (handler / cofx / sub / fx)
  carries `:sensitive? true`. Per Spec 009 §`:sensitive?` registration
  metadata key — the coarse, handler-level signal."
  [m]
  (true? (:sensitive? m)))

(defn- redact-tags
  "Replace value-bearing slots in a tags map with the `:rf/redacted`
  sentinel. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces. Stamps `:sensitive? true` so consumers filter
  correctly. Idempotent — safe to call on an already-redacted map."
  [tags]
  (cond-> tags
    (contains? tags :value)       (assoc :value       redacted-sentinel)
    (contains? tags :received)    (assoc :received    redacted-sentinel)
    (contains? tags :explain)     (assoc :explain     redacted-sentinel)
    (contains? tags :malli-error) (assoc :malli-error redacted-sentinel)
    (contains? tags :event)       (assoc :event       redacted-sentinel)
    true                          (assoc :sensitive?  true)))

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
  :rf.error/schema-validation-failure with the registered explainer's
  output attached.

  Per Spec 010 §Per-frame schemas only the named frame's schemas are
  walked — schemas registered against sibling frames are ignored.

  Validation routes through the registered validator/explainer fns
  (rf2-froe). When `set-schema-validator!` has been called with `nil`
  this fn is a hard no-op for every schema in the frame.

  Arities:
    (validate-app-db! db)                       ;; current frame
    (validate-app-db! db event-id)              ;; current frame, named handler
    (validate-app-db! db event-id frame-id)     ;; explicit frame

  event-id (optional) names the handler whose commit prompted the
  failure — surfaced as :failing-id in the error tags."
  ([db] (validate-app-db! db nil (frame/current-frame)))
  ([db event-id] (validate-app-db! db event-id (frame/current-frame)))
  ([db event-id frame-id]
   ;; Per Spec 009 §Production builds the entire body lives inside a
   ;; `(when interop/debug-enabled? ...)` gate as the OUTERMOST form so
   ;; :advanced + goog.DEBUG=false DCE-elides every reason string,
   ;; keyword, validator deref, and trace call. The `@validator-fn`
   ;; check is a runtime atom deref and must NOT be combined into the
   ;; gate predicate (the deref defeats Closure's reachability proof).
   (when interop/debug-enabled?
     (when @validator/validator-fn
       (doseq [[path m] (storage/frame-schema-entries frame-id)]
         (let [val-at (get-in db path)
               schema (:schema m)]
           (when-not (validator/run-validator schema val-at)
             ;; Per Spec 010 §`:sensitive?` — privacy in schema-
             ;; validation error traces (rf2-kj51z). Consult the
             ;; schema's per-slot `:sensitive?` props before
             ;; including the failing value. The failing path is
             ;; the reg-app-schema path itself (per-path validation
             ;; only flags the whole registered slot); the walker
             ;; checks for container-level OR any nested slot the
             ;; failure path crosses.
             (let [sensitive? (walker/schema-has-sensitive? schema)
                   base-tags  (cond-> {:where    :app-db
                                       :path     path
                                       :value    val-at
                                       :frame    frame-id
                                       :explain  (validator/run-explainer schema val-at)
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
                                event-id (assoc :failing-id event-id))
                   tags       (if sensitive? (redact-tags base-tags) base-tags)]
               (trace/emit-error! :rf.error/schema-validation-failure tags)))))))))

(defn validate-event!
  "Per Spec 010 §Validation order step 1 — before an event handler runs,
  validate the event vector against any :spec on the handler's metadata.

  Returns true on pass (or when validation is elided / no schema is
  attached), false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :event; the caller
  is responsible for skipping the handler (recovery: :no-recovery).

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so :advanced+goog.DEBUG=false
  DCE-elides every reason string, every keyword, and every validator
  call. Per Spec 010 §Non-Malli validators (rf2-froe) the validator
  is pluggable; when none is registered this fn returns true (pass)
  without inspecting the schema."
  [event-id event handler-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator/validator-fn
      (if-let [schema (:spec handler-meta)]
        (if (validator/run-validator schema event)
          true
          (let [explanation (validator/run-explainer schema event)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Consult the handler's registration meta —
                ;; `:sensitive? true` on the reg-event-* declares
                ;; the whole event vector sensitive.
                sensitive? (meta-sensitive? handler-meta)
                base-tags  {:where       :event
                            :event-id    event-id
                            :failing-id  event-id
                            :spec-id     event-id
                            :received    event
                            :event       event
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Event " event-id
                                              " payload failed schema "
                                              schema ", got "
                                              (type-of-value event) ".")
                            :recovery    :no-recovery}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
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
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly.
  Per Spec 010 §Non-Malli validators (rf2-froe) the validator is
  pluggable; when none is registered this fn returns true."
  [sub-id query-v value sub-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator/validator-fn
      (if-let [schema (:spec sub-meta)]
        (if (validator/run-validator schema value)
          true
          (let [explanation (validator/run-explainer schema value)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Two sources: the sub's registration meta
                ;; (`:sensitive?` on reg-sub) and the schema's own
                ;; per-slot `:sensitive?` (a container-level flag
                ;; on the spec covers every failing return).
                sensitive? (or (meta-sensitive? sub-meta)
                               (walker/schema-has-sensitive? schema))
                base-tags  {:where       :sub-return
                            :sub-id      sub-id
                            :failing-id  sub-id
                            :spec-id     sub-id
                            :query-v     query-v
                            :received    value
                            :value       value
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Subscription " sub-id
                                              " return value failed schema "
                                              schema ", got "
                                              (type-of-value value) ".")
                            :recovery    :replaced-with-default}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
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
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly.
  Per Spec 010 §Non-Malli validators (rf2-froe) the validator is
  pluggable; when none is registered this fn returns true."
  [cofx-id event-id value cofx-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator/validator-fn
      (if-let [schema (:spec cofx-meta)]
        (if (validator/run-validator schema value)
          true
          (let [explanation (validator/run-explainer schema value)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Cofx-meta or container-level schema-prop both
                ;; trigger redaction.
                sensitive? (or (meta-sensitive? cofx-meta)
                               (walker/schema-has-sensitive? schema))
                base-tags  {:where       :cofx
                            :cofx-id     cofx-id
                            :event-id    event-id
                            :failing-id  event-id
                            :spec-id     cofx-id
                            :received    value
                            :value       value
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Coeffect " cofx-id
                                              " injected value failed schema "
                                              schema ", got "
                                              (type-of-value value) ".")
                            :recovery    :no-recovery}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
      true)
    true))

(defn validate-fx!
  "Per Spec 010 §Validation order step 5 — before an fx handler runs,
  validate its args against any :spec on the fx's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :fx-args; per Spec 010
  §Per-step recovery row 5 the caller is responsible for skipping the
  offending fx only (recovery: :skipped) — sibling fx in the same `:fx`
  vector continue to run, and downstream queued events still drain.

  Per Spec 010 §`:sensitive?` privacy: redaction triggers when either
  the fx's registration meta carries `:sensitive? true` or the schema
  itself declares a `:sensitive?` slot anywhere in its tree. The
  failing args value, the schema explanation, and the doubled `:value`
  / `:received` slots are all redacted in the emitted tags.

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so :advanced+goog.DEBUG=false
  DCE-elides every reason string, every keyword, and every validator
  call. Per Spec 010 §Non-Malli validators (rf2-froe) the validator is
  pluggable; when none is registered this fn returns true."
  [fx-id event-id args fx-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator/validator-fn
      (if-let [schema (:spec fx-meta)]
        (if (validator/run-validator schema args)
          true
          (let [explanation (validator/run-explainer schema args)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Fx-meta or container-level schema-prop both
                ;; trigger redaction.
                sensitive? (or (meta-sensitive? fx-meta)
                               (walker/schema-has-sensitive? schema))
                base-tags  (cond-> {:where       :fx-args
                                    :fx-id       fx-id
                                    :fx-args     args
                                    :failing-id  fx-id
                                    :spec-id     fx-id
                                    :received    args
                                    :value       args
                                    :malli-error explanation
                                    :explain     explanation
                                    :reason      (str "Effect " fx-id
                                                      " args failed schema "
                                                      schema ", got "
                                                      (type-of-value args) ".")
                                    :recovery    :skipped}
                             event-id (assoc :event-id event-id))
                ;; When the fx args themselves are redacted, the
                ;; doubled `:fx-args` slot must also be scrubbed —
                ;; redact-tags handles `:value`/`:received`/`:explain`/
                ;; `:malli-error`; explicitly clear `:fx-args` here so
                ;; the redaction is symmetric with the cofx/event
                ;; surfaces.
                tags       (if sensitive?
                             (assoc (redact-tags base-tags)
                                    :fx-args redacted-sentinel)
                             base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
      true)
    true))

;; ---- public boundary-validation entry point (rf2-r2uh integration) -------
;;
;; The boundary-validation interceptor (`re-frame.spec/validate-at-boundary`,
;; rf2-r2uh) runs `:spec` validation on a handler at production-build
;; time — outside the `interop/debug-enabled?` gate that guards the
;; hot-path validate-*! fns above. Per Spec 010 §Production builds the
;; boundary interceptor MUST route through the same registered validator
;; the dev-mode hot path uses (so a substituted validator covers both
;; surfaces). This namespace publishes `validate-with-registered-fn` as
;; the call the interceptor reaches for via the
;; `:schemas/validate-with-registered-fn` late-bind hook (the schemas
;; artefact is optional per rf2-p7va so the interceptor cannot
;; statically `:require [re-frame.schemas]`).
;;
;; Contract: returns true on conform; false on fail; true (pass) when
;; no validator is registered. Does NOT emit a trace — the boundary
;; interceptor is responsible for emitting :rf.error/schema-validation-
;; failure :where :event with the appropriate envelope. Pure check
;; surface.

(defn validate-with-registered-fn
  "Apply the registered validator to `(schema, value)`. Public seam for
  the boundary-validation interceptor (rf2-r2uh). Returns true on
  conform; false on fail; true when no validator is registered (the
  call-site treats no-validator as no-validation, mirroring the hot
  path).

  Does NOT consult `interop/debug-enabled?` — the boundary interceptor
  runs in production by design."
  [schema value]
  (validator/run-validator schema value))

(defn explain-with-registered-fn
  "Apply the registered explainer to `(schema, value)`. Companion to
  `validate-with-registered-fn` for the boundary-validation
  interceptor (rf2-r2uh). Returns the explanation map / data on fail;
  nil when the schema conforms or no explainer is registered."
  [schema value]
  (validator/run-explainer schema value))
