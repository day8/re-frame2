(ns re-frame.spec
  "Schema-related interceptors. Per Spec 010 §Production builds (rf2-r2uh).

  > The ns name is preserved from v2's early phase (`re-frame.spec`),
  > but the canonical vocabulary is `:schema` everywhere else after
  > rf2-ieu0i — the interceptor `:id` is `:rf.schema/at-boundary`,
  > the handler-metadata key is `:schema` (the bare `:spec` key is
  > accepted as a deprecated alias for one cycle), and the hot-reload
  > trace category is `:rf.schema/violation`. The namespace alias
  > remains available for back-compat; new code should reach the
  > interceptor through `re-frame.core/at-boundary`.

  The headline export is `at-boundary` — the production-side
  validation interceptor users attach to event handlers that ingest
  data from untrusted sources (HTTP responses, websocket messages,
  postMessage, query-string values). Per Spec 010 §Production builds
  the canonical CLJS reference elides every dev-time `validate-*!`
  call site at `:advanced` + `goog.DEBUG=false`; system-boundary
  handlers that still want shape enforcement opt back in by adding
  `at-boundary` to their interceptor chain.

  Usage:

  ```clojure
  (ns my-app.api
    (:require [re-frame.core :as rf]))

  (rf/reg-event-fx :api/response-received
    {:schema ApiResponseSchema}
    [rf/at-boundary]
    (fn [_ [_ payload]] ...))
  ```

  The interceptor reuses the handler's existing `:schema` metadata —
  it does NOT introduce a parallel schema. The deprecated `:spec`
  alias is also recognised (one-cycle migration window per
  rf2-ieu0i). Per Spec 010 L143:

  - In **dev builds**, every event handler's `:schema` is checked anyway
    (per Spec 010 §Validation order step 1). The boundary interceptor
    is a no-op in this mode — it doesn't run validation a second time.
  - In **production builds**, `re-frame.interop/debug-enabled?` is
    `false` and step-1 validation is elided. The boundary interceptor
    runs the same `:schema` check inline, so handlers carrying it
    still validate at the boundary.
  - In **production builds with no `:schema`** on the handler, the
    boundary interceptor is a no-op (nothing to validate against) and
    emits `:rf.warning/boundary-without-spec` once per `(handler-id)`
    to flag the misconfiguration.

  Validation routes through the same registered validator the dev-time
  hot path uses (the `set-schema-validator!` seam) — a substituted
  validator covers both surfaces with one registration. When
  `set-schema-validator!` has been called with `nil` the boundary
  interceptor is also a no-op (validation disabled).

  This namespace stays decoupled from `re-frame.schemas` (an optional
  artefact) by reaching into it through the
  `:schemas/validate-with-registered-fn` and
  `:schemas/explain-with-registered-fn` late-bind hooks. When the
  schemas artefact is not on the classpath the hooks return nil and
  the interceptor falls through as a no-op."
  (:require [re-frame.error :as error]
            [re-frame.interceptor :as interceptor]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- warn-once cache (Spec 010 L147) -------------------------------------
;;
;; `:rf.warning/boundary-without-spec` fires at most once per handler-id —
;; the misconfiguration (boundary interceptor attached to a handler with
;; no `:schema`) is a steady-state condition. Without suppression every
;; dispatch of the offending event would emit the warning. Mirrors the
;; warn-once cache pattern from re-frame.views (rf2-d3k3).

(defonce ^:private boundary-warned-handler-ids
  (atom #{}))

(defn clear-boundary-warned-handler-ids!
  "Reset the warn-once cache. Tests use this between cases so each case
  starts from a clean slate."
  []
  (reset! boundary-warned-handler-ids #{})
  nil)

;; ---- dev / prod gate ------------------------------------------------------
;;
;; The boundary interceptor's first decision is "is this a dev build?" —
;; if so, no-op (Spec 010 L145: dev-mode step-1 has already run). The
;; canonical CLJS gate is `re-frame.interop/debug-enabled?` (alias of
;; `goog.DEBUG`); on the JVM it is hardcoded `true`.
;;
;; We wrap the read in a private fn so tests can rebind the boundary's
;; dev/prod decision INDEPENDENTLY of the trace surface's
;; `interop/debug-enabled?` read. This matters because the trace
;; surface (`emit!` / `emit-error!`) is itself gated on
;; `debug-enabled?` (Spec 009 §Production builds) — JVM tests that
;; want to exercise the boundary's prod branch AND observe the
;; emitted warning / error trace need to keep `debug-enabled?` true
;; (so traces fire) while taking the boundary's prod branch.
;;
;; Production-elision: `interop/debug-enabled?` is the closure-define
;; alias; under `:advanced` + `goog.DEBUG=false` it folds to `false`
;; and `dev-mode?` constant-folds with it (the fn body has a single
;; var read; Closure inlines and folds). In control builds it stays
;; `true` and the interceptor's outer `if` takes the dev (no-op) arm.

(defn- dev-mode?
  "Returns true in dev / JVM (where step-1 validation already runs in
  the router); false in `:advanced` + `goog.DEBUG=false` production
  (where the boundary interceptor takes its validation branch).

  Wraps `interop/debug-enabled?` in an indirection so tests can rebind
  the boundary's dev/prod decision without redefining the var the
  trace surface itself reads."
  []
  interop/debug-enabled?)

;; ---- :rf.schema/at-boundary ----------------------------------------------
;;
;; Per Spec 010 §Production builds. The interceptor runs in the
;; :before slot — pre-handler, alongside the dev-mode step-1
;; validation site. Failure recovery is identical to step-1: skip the
;; handler (set `:rf/skip-handler?` on the context); downstream queue
;; continues.
;;
;; The interceptor's `:id` is `:rf.schema/at-boundary` — renamed from
;; `:spec/at-boundary` at rf2-ieu0i as part of the framework-wide
;; `:spec` → `schema` vocabulary unification.

(def at-boundary
  "Production-side schema validation interceptor. Per Spec 010 §Production
  builds. Add to a `reg-event-*` handler's positional interceptor vector
  to force `:schema` validation against the dispatched event vector even
  in production builds where dev-time validation is elided.

  Re-uses the handler's existing `:schema` metadata (or the deprecated
  `:spec` alias — accepted for one cycle per rf2-ieu0i); does not
  introduce a parallel schema. No-op in dev builds (step-1 validation
  already fires); no-op when no validator is registered
  (`set-schema-validator!` was called with `nil`); no-op when the
  handler carries no `:schema` (and emits
  `:rf.warning/boundary-without-spec` once to flag the misconfiguration)."
  (interceptor/->interceptor
    :id :rf.schema/at-boundary
    :before
    (fn [ctx]
      ;; In dev builds, step-1 validation already ran in the router's
      ;; `validate-event!` call. The boundary interceptor is a no-op
      ;; here — running validation a second time would just duplicate
      ;; the trace.
      (if (dev-mode?)
        ctx
        ;; Production path. Reach validation through the late-bind
        ;; seam so this namespace stays decoupled from the optional
        ;; schemas artefact.
        (let [validate-fn (late-bind/get-fn :schemas/validate-with-registered-fn)
              explain-fn  (late-bind/get-fn :schemas/explain-with-registered-fn)]
          (if (nil? validate-fn)
            ;; Schemas artefact not on the classpath, or no validator
            ;; registered. Per Spec 010 §Non-Malli validators / nil
            ;; validator: nil = "every value passes"; the boundary
            ;; interceptor is a no-op.
            ctx
            (let [event       (interceptor/get-coeffect ctx :event)
                  event-id    (when (vector? event) (first event))
                  handler-meta (when event-id
                                 (registrar/lookup :event event-id))
                  ;; Accept :schema (canonical, rf2-ieu0i) or :spec
                  ;; (deprecated alias kept for one cycle).
                  schema      (or (:schema handler-meta) (:spec handler-meta))]
              (cond
                ;; No handler-id / no metadata — defensive; the runtime
                ;; should never call an interceptor without an event.
                ;; Treat as a no-op rather than emit a misleading warning.
                (nil? handler-meta)
                ctx

                ;; Per Spec 010 L147 — boundary attached to a handler with
                ;; no :schema. Warn once and no-op.
                (nil? schema)
                (do
                  (when-not (contains? @boundary-warned-handler-ids event-id)
                    (swap! boundary-warned-handler-ids conj event-id)
                    (trace/emit! :warning :rf.warning/boundary-without-spec
                                 {:event-id event-id
                                  :event    event
                                  :reason
                                  (str ":rf.schema/at-boundary is attached "
                                       "to event handler `" event-id "` but the "
                                       "handler carries no `:schema` metadata. The "
                                       "boundary interceptor cannot validate "
                                       "without a schema; this dispatch passes "
                                       "through unchecked. Either attach a `:schema` "
                                       "to the handler's metadata-map (recommended) "
                                       "or remove the boundary interceptor.")
                                  :recovery :no-recovery}))
                  ctx)

                :else
                (let [ok? (try (validate-fn schema event)
                               (catch #?(:clj Throwable :cljs :default) _ true))]
                  (if ok?
                    ctx
                    (let [explanation (when explain-fn
                                        (try (explain-fn schema event)
                                             (catch #?(:clj Throwable :cljs :default) _ nil)))]
                      ;; Emit the failure trace using the same shape as
                      ;; dev-mode step-1 (per Spec 010 L149). Per Spec
                      ;; 009 §Production builds `emit-error!` itself
                      ;; elides under `:advanced` + `goog.DEBUG=false`,
                      ;; so this body only fires under JVM / dev-CLJS
                      ;; with debug-enabled? flipped off — exactly the
                      ;; surface the rf2-r2uh tests exercise.
                      (trace/emit-error! :rf.error/schema-validation-failure
                                         {:where      :event
                                          :event-id   event-id
                                          :failing-id event-id
                                          :schema-id  event-id
                                          :received   event
                                          :value      event
                                          :explain    explanation
                                          :source     :boundary
                                          :reason     (str "Event " event-id
                                                           " payload failed boundary "
                                                           "schema " schema ", got "
                                                           (error/type-of-value event) ".")
                                          :recovery   :no-recovery})
                      ;; Per Spec 010 §Per-step recovery step 1: handler
                      ;; is not invoked. The handler-as-interceptor
                      ;; checks `:rf/skip-handler?` in its :before slot
                      ;; (see events.cljc).
                      (assoc ctx :rf/skip-handler? true))))))))))))
