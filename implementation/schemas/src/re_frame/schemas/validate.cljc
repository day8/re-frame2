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

  Per rf2-s7s6j the four meta-bearing validate-*! fns (event / cofx /
  fx / sub-return) share a single core via the private
  `run-validation` primitive — each public fn is a thin wrapper that
  contributes only its registration-meta source, its checked value,
  its sensitivity-source check, its tag shape (`:where`,
  `:reason`, etc.), and any fx-specific post-redaction step.
  `validate-app-db!` stays a sibling of the four; it walks N schemas
  via doseq (no single :spec lookup, no true/false return contract)
  and so doesn't share the wrapper's shape.

  Per Spec 009 §Production builds every dev-time validate-*! body lives
  inside an `(if interop/debug-enabled? ...)` gate as the OUTERMOST
  form so :advanced+goog.DEBUG=false DCE-elides every reason string,
  keyword, validator deref, and trace call. The private
  `run-validation` primitive is reachable only from those gated arms
  — when every call-site is dead, Closure's reachability proof DCEs
  the primitive itself, along with every literal reason string passed
  through it.

  Per Spec 010 §Non-Malli validators (rf2-froe) the validator/explainer
  are pluggable via the registered atoms in `re-frame.schemas.validator`;
  when none is registered every fn here returns true (pass) without
  inspecting the schema.

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
  without leaking user data.

  Per rf2-4fbsd the emit-sites carry two slots for the failing value
  (`:value` and `:received`, per Spec 010 §`:sensitive?`) and one slot
  for the registered explainer's output (`:explain`). The earlier
  `:event` (duplicate of `:received`) and `:malli-error` (duplicate of
  `:explain`) tags have been dropped — consumers reach for `:received`
  / `:value` / `:explain`."
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

(defn- sensitive-by-meta?
  "Sensitivity check used by validate-event!. Only consults the
  registration meta (event vectors aren't walked for per-slot
  `:sensitive?` schema props per Spec 010)."
  [m _schema]
  (meta-sensitive? m))

(defn- sensitive-by-meta-or-schema?
  "Sensitivity check used by validate-cofx! / validate-fx! /
  validate-sub-return!. Either the registration meta OR a per-slot
  `:sensitive?` prop anywhere in the schema tree triggers redaction."
  [m schema]
  (or (meta-sensitive? m)
      (walker/schema-has-sensitive? schema)))

(defn- redact-tags
  "Replace value-bearing slots in a tags map with the `:rf/redacted`
  sentinel. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces. Stamps `:sensitive? true` so consumers filter
  correctly. Idempotent — safe to call on an already-redacted map."
  [tags]
  (cond-> tags
    (contains? tags :value)    (assoc :value     redacted-sentinel)
    (contains? tags :received) (assoc :received  redacted-sentinel)
    (contains? tags :explain)  (assoc :explain   redacted-sentinel)
    true                       (assoc :sensitive? true)))

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

(defn- run-validation
  "Shared core of the four meta-bearing validate-*! fns (event / cofx /
  fx / sub-return). Performs the registered-validator deref, the
  `:spec`-on-meta lookup, the validate / explain calls, the
  sensitivity decision, and the trace emit. Returns true on pass / no
  schema / no validator; false on a logged failure.

  Parameters:
    - `meta`         the registration metadata (handler / cofx / sub /
                     fx) — its `:spec` slot, if any, is the schema.
    - `value`        the value being checked (event vector, cofx
                     value, sub return, fx args).
    - `sensitive?-fn`  `(fn [meta schema] -> boolean)` — combines the
                     meta-level `:sensitive?` flag with any
                     schema-level `:sensitive?` props per Spec 010.
    - `build-base-tags`  `(fn [schema explanation] -> map)` — produces
                     the per-fn tag map (`:where`, `:reason`, etc.)
                     EXCLUDING any sensitivity stamping.
    - `extra-redact` `(fn [tags] -> tags)` or `nil` — additional
                     redaction step applied AFTER `redact-tags` when
                     `sensitive?` is true. Used by validate-fx! to
                     scrub the doubled `:fx-args` slot.

  Reachability: every call-site lives inside the outermost
  `(if interop/debug-enabled? ...)` gate of its public wrapper.
  Closure's reachability proof under :advanced + goog.DEBUG=false
  finds every call-site dead and DCEs this fn — along with every
  literal reason string and tag keyword passed through it."
  [meta value sensitive?-fn build-base-tags extra-redact]
  (if @validator/validator-fn
    (if-let [schema (:spec meta)]
      (if (validator/run-validator schema value)
        true
        (let [explanation (validator/run-explainer schema value)
              sensitive?  (sensitive?-fn meta schema)
              base-tags   (build-base-tags schema explanation)
              tags        (cond-> base-tags
                            sensitive?                  redact-tags
                            (and sensitive? extra-redact) extra-redact)]
          (trace/emit-error! :rf.error/schema-validation-failure tags)
          false))
      true)
    true))

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
  failure — surfaced as :failing-id in the error tags.

  Structurally distinct from the four meta-bearing validate-*! fns
  (event / cofx / fx / sub-return): walks N schemas via doseq, has
  no single `:spec`-on-meta lookup, and emits per failure without
  returning a true/false pass flag. Does not share the
  `run-validation` shape."
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
  Failures emit `:rf.error/schema-validation-failure :where :event`; the
  caller skips the handler (recovery: `:no-recovery`). Returns
  true/false per the `run-validation` contract."
  [event-id event handler-meta]
  (if interop/debug-enabled?
    (run-validation
      handler-meta
      event
      sensitive-by-meta?
      (fn [schema explanation]
        {:where      :event
         :event-id   event-id
         :failing-id event-id
         :spec-id    event-id
         :received   event
         :value      event
         :explain    explanation
         :reason     (str "Event " event-id
                          " payload failed schema "
                          schema ", got "
                          (type-of-value event) ".")
         :recovery   :no-recovery})
      nil)
    true))

(defn validate-sub-return!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :spec on the sub's metadata.
  Failures emit `:rf.error/schema-validation-failure :where
  :sub-return`; the caller replaces the value with the default (nil)
  per the `:replaced-with-default` recovery. Returns true/false per
  the `run-validation` contract."
  [sub-id query-v value sub-meta]
  (if interop/debug-enabled?
    (run-validation
      sub-meta
      value
      sensitive-by-meta-or-schema?
      (fn [schema explanation]
        {:where      :sub-return
         :sub-id     sub-id
         :failing-id sub-id
         :spec-id    sub-id
         :query-v    query-v
         :received   value
         :value      value
         :explain    explanation
         :reason     (str "Subscription " sub-id
                          " return value failed schema "
                          schema ", got "
                          (type-of-value value) ".")
         :recovery   :replaced-with-default})
      nil)
    true))

(defn validate-cofx!
  "Per Spec 010 §Validation order step 2 — after a cofx injects its
  value into the merged context, validate that value against any
  :spec on the cofx's metadata. Failures emit
  `:rf.error/schema-validation-failure :where :cofx`; the caller
  skips the handler (recovery: `:no-recovery`). Returns true/false
  per the `run-validation` contract."
  [cofx-id event-id value cofx-meta]
  (if interop/debug-enabled?
    (run-validation
      cofx-meta
      value
      sensitive-by-meta-or-schema?
      (fn [schema explanation]
        {:where      :cofx
         :cofx-id    cofx-id
         :event-id   event-id
         :failing-id event-id
         :spec-id    cofx-id
         :received   value
         :value      value
         :explain    explanation
         :reason     (str "Coeffect " cofx-id
                          " injected value failed schema "
                          schema ", got "
                          (type-of-value value) ".")
         :recovery   :no-recovery})
      nil)
    true))

(defn validate-fx!
  "Per Spec 010 §Validation order step 5 — before an fx handler runs,
  validate its args against any :spec on the fx's metadata. Failures
  emit `:rf.error/schema-validation-failure :where :fx-args`; per
  Spec 010 §Per-step recovery row 5 the caller skips the offending fx
  only (recovery: `:skipped`) — sibling fx in the same `:fx` vector
  continue to run, and downstream queued events still drain. Returns
  true/false per the `run-validation` contract.

  The doubled `:fx-args` slot in the emitted tags is scrubbed via
  `run-validation`'s extra-redact step so the redaction is symmetric
  with the cofx/event surfaces (which don't carry a doubled-id slot)."
  [fx-id event-id args fx-meta]
  (if interop/debug-enabled?
    (run-validation
      fx-meta
      args
      sensitive-by-meta-or-schema?
      (fn [schema explanation]
        (cond-> {:where      :fx-args
                 :fx-id      fx-id
                 :fx-args    args
                 :failing-id fx-id
                 :spec-id    fx-id
                 :received   args
                 :value      args
                 :explain    explanation
                 :reason     (str "Effect " fx-id
                                  " args failed schema "
                                  schema ", got "
                                  (type-of-value args) ".")
                 :recovery   :skipped}
          event-id (assoc :event-id event-id)))
      ;; Extra-redact: the doubled `:fx-args` slot isn't covered by
      ;; the generic `redact-tags` (which only knows `:value` /
      ;; `:received` / `:explain` — the value-bearing slots Spec 010
      ;; §`:sensitive?` blesses). Scrub `:fx-args` here so the
      ;; redaction is symmetric across all four meta-bearing
      ;; validate-*! surfaces.
      (fn [tags] (assoc tags :fx-args redacted-sentinel)))
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
