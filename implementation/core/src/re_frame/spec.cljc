(ns re-frame.spec
  "Schema-related interceptors. Per Spec 010 §Production builds (rf2-r2uh).

  > The ns name is preserved from v2's early phase (`re-frame.spec`),
  > but the canonical vocabulary is `:schema` everywhere else after
  > rf2-ieu0i — the interceptor `:id` is `:rf.schema/at-boundary`,
  > the handler-metadata key is `:schema`, and the hot-reload trace
  > category is `:rf.schema/violation`. The namespace alias remains
  > available for back-compat; new code should reach the interceptor
  > through `re-frame.core/validate-at-boundary-interceptor`.

  The headline export is `validate-at-boundary-interceptor` — the production-side
  validation interceptor users attach to event handlers that ingest
  data from untrusted sources (HTTP responses, websocket messages,
  postMessage, query-string values). Per Spec 010 §Production builds
  the canonical CLJS reference elides every dev-time `validate-*!`
  call site at `:advanced` + `goog.DEBUG=false`; system-boundary
  handlers that still want shape enforcement opt back in by adding
  `validate-at-boundary-interceptor` to their interceptor chain.

  Usage:

  ```clojure
  (ns my-app.api
    (:require [re-frame.core :as rf]))

  (rf/reg-event-fx :api/response-received
    {:schema ApiResponseSchema}
    [rf/validate-at-boundary-interceptor]
    (fn [_ [_ payload]] ...))
  ```

  The interceptor reuses the handler's existing `:schema` metadata —
  it does NOT introduce a parallel schema. Per Spec 010 L143:

  - In **dev builds**, every event handler's `:schema` is checked anyway
    (per Spec 010 §Validation order step 1). The boundary interceptor
    is a no-op in this mode — it doesn't run validation a second time.
  - In **production builds**, `re-frame.interop/debug-enabled?` is
    `false` and step-1 validation is elided. The boundary interceptor
    runs the same `:schema` check inline, so handlers carrying it
    still validate at the boundary.
  - **Registration without `:schema`** is rejected at registration
    time with `:rf.error/at-boundary-missing-schema` (per
    [Spec 010 §Production builds] and rf2-iftj4). The boundary
    interceptor is structurally meaningless without a schema, so
    `re-frame.events` raises an ex-info from `reg-event-*` rather
    than waiting for the first dispatch to surface the
    misconfiguration. There is no warn-and-accept fallback.

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
;; `:spec/at-boundary` at rf2-ieu0i and finalised under the schema-
;; vocabulary strip in rf2-9brg7 (audit-of-audits schemas #6).

(def validate-at-boundary-interceptor
  "Production-side schema validation interceptor. Per Spec 010 §Production
  builds. Add to a `reg-event-*` handler's positional interceptor vector
  to force `:schema` validation against the dispatched event vector even
  in production builds where dev-time validation is elided.

  Re-uses the handler's existing `:schema` metadata; does not introduce
  a parallel schema. No-op in dev builds (step-1 validation already
  fires); no-op when no validator is registered (`set-schema-validator!`
  was called with `nil`).

  Per rf2-iftj4, registering a handler that attaches `validate-at-boundary-interceptor` but
  carries no `:schema` is rejected at registration time with
  `:rf.error/at-boundary-missing-schema`; the runtime can therefore
  assume `:schema` is present whenever this interceptor's `:before`
  slot fires."
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
                  schema      (:schema handler-meta)]
              (cond
                ;; No handler-id / no metadata — defensive; the runtime
                ;; should never call an interceptor without an event.
                (nil? handler-meta)
                ctx

                ;; Per rf2-iftj4, registration would have rejected an
                ;; validate-at-boundary-interceptor attachment without `:schema`. A nil
                ;; schema here can only happen if a caller mutated the
                ;; registry metadata after registration; fall through
                ;; as a no-op (defensive — never expected in practice).
                (nil? schema)
                ctx

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
