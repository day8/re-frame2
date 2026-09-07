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
  validation interceptor's value, registered under the framework id
  `:rf.schema/at-boundary`. System-boundary handlers (HTTP responses, websocket
  messages, postMessage, query-string values) opt back into shape enforcement
  by REFERENCING the registered interceptor by id in their chain. Per Spec 010
  §Production builds the canonical CLJS reference elides every dev-time
  `validate-*!` call site at `:advanced` + `goog.DEBUG=false`; this interceptor
  re-runs the handler's `:schema` check at the boundary in production.

  Usage — reference the registered interceptor by id (EP-0022: chains are
  reference-only; the `validate-at-boundary-interceptor` Var is the
  registration-boundary input, NOT a chain entry):

  ```clojure
  (ns my-app.api
    (:require [re-frame.core :as rf]))

  (rf/reg-event :api/response-received
    {:schema ApiResponseSchema
     :interceptors [:rf.schema/at-boundary]}
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
  hot path uses (the `set-schema-fns!` seam) — a substituted
  validator covers both surfaces with one registration. When
  `set-schema-fns!` has installed a `nil` `:validate` the boundary
  interceptor is also a no-op (validation disabled).

  This namespace stays decoupled from `re-frame.schemas` (an optional
  artefact) by reaching into it through the
  `:schemas/validate-with-registered-fn` and
  `:schemas/explain-with-registered-fn` late-bind hooks. When the
  schemas artefact is not on the classpath the hooks return nil and
  the interceptor falls through as a no-op."
  (:require [re-frame.error :as rf.error]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.interceptor-registry :as rf.interceptor-registry]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.trace :as rf.trace]))

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
;; `rf.interop/debug-enabled?` read. This matters because the trace
;; surface (`emit!` / `emit-error!`) is itself gated on
;; `debug-enabled?` (Spec 009 §Production builds) — JVM tests that
;; want to exercise the boundary's prod branch AND observe the
;; emitted warning / error trace need to keep `debug-enabled?` true
;; (so traces fire) while taking the boundary's prod branch.
;;
;; Production-elision: `rf.interop/debug-enabled?` is the closure-define
;; alias; under `:advanced` + `goog.DEBUG=false` it folds to `false`
;; and `dev-mode?` constant-folds with it (the fn body has a single
;; var read; Closure inlines and folds). In control builds it stays
;; `true` and the interceptor's outer `if` takes the dev (no-op) arm.

(defn- dev-mode?
  "Returns true in dev / JVM (where step-1 validation already runs in
  the router); false in `:advanced` + `goog.DEBUG=false` production
  (where the boundary interceptor takes its validation branch).

  Wraps `rf.interop/debug-enabled?` in an indirection so tests can rebind
  the boundary's dev/prod decision without redefining the var the
  trace surface itself reads."
  []
  rf.interop/debug-enabled?)

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
;;
;; rf2-mwv4e — the rejection ALSO stamps `:rf/boundary-rejected?` on the
;; context. That marker is the single internal fact the router's tail reads to
;; (a) fan ONE always-on structural record and (b) settle the dispatch
;; `:outcome :rejected` instead of the `:ok` a handler-less run would otherwise
;; report. The dev route reaches the SAME marker from `re-frame.router`'s
;; `run-chain` (step-1 refused an event whose handler references this
;; interceptor), so both enforcement routes converge on one marker and one emit
;; site. See `router/emit-boundary-rejection-record!`.

(def validate-at-boundary-interceptor
  "Production-side schema validation interceptor VALUE, registered under the
  framework id `:rf.schema/at-boundary` (see `register-schema-interceptors!`).
  Per Spec 010 §Production builds. Reference it by id from a `reg-event`
  handler's metadata `:interceptors` chain — `{:interceptors [:rf.schema/at-boundary]}`
  (EP-0022: chains are reference-only; this Var is the registration-boundary
  input, never a chain entry) — to force `:schema` validation against the
  dispatched event vector even in production builds where dev-time validation
  is elided.

  Re-uses the handler's existing `:schema` metadata; does not introduce
  a parallel schema. No-op in dev builds (step-1 validation already
  fires); no-op when no validator is registered (`set-schema-fns!`
  installed a `nil` `:validate`).

  A rejection is OBSERVABLE in a production build (rf2-mwv4e): besides the
  handler skip it stamps `:rf/boundary-rejected?` on the context, and the
  router's tail turns that into one always-on
  `:rf.error/schema-validation-failure` record (`:source :boundary`) plus an
  `:outcome :rejected` on the event-emit record. Both survive `:advanced` +
  `goog.DEBUG=false`; the rich `:value` / `:explain` diagnosis stays on the
  DCE'd dev trace below.

  Per rf2-iftj4, registering a handler that attaches `validate-at-boundary-interceptor` but
  carries no `:schema` is rejected at registration time with
  `:rf.error/at-boundary-missing-schema`; the runtime can therefore
  assume `:schema` is present whenever this interceptor's `:before`
  slot fires."
  (rf.interceptor/->interceptor*
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
        (let [validate-fn (rf.late-bind/get-fn-cached :schemas/validate-with-registered-fn)
              explain-fn  (rf.late-bind/get-fn-cached :schemas/explain-with-registered-fn)]
          (if (nil? validate-fn)
            ;; Schemas artefact not on the classpath, or no validator
            ;; registered. Per Spec 010 §Non-Malli validators / nil
            ;; validator: nil = "every value passes"; the boundary
            ;; interceptor is a no-op.
            ctx
            (let [event       (rf.interceptor/get-coeffect ctx :event)
                  event-id    (when (vector? event) (first event))
                  ;; rf2-7d30s — the in-flight cascade's frame, seeded as the
                  ;; `:rf.frame/id` coeffect (mirrors cofx.cljc / the dev-time
                  ;; `validate-event!` 4-arity). Stamped onto the failure
                  ;; trace below so `re-frame.epoch.capture/capture-event!`
                  ;; (which buffers only frame-tagged traces) attributes the
                  ;; boundary validation failure to the emitting frame's epoch
                  ;; — and so the SSR error-projection listener can route it
                  ;; per-frame under concurrent server frames without leaning
                  ;; on a single-active-frame guess.
                  frame       (rf.interceptor/get-coeffect ctx :rf.frame/id)
                  handler-meta (when event-id
                                 (rf.registrar/lookup :event event-id))
                  schema      (:schema handler-meta)]
              (cond
                ;; No handler-id / no metadata — defensive; the runtime
                ;; should never call an interceptor without an event.
                (nil? handler-meta)
                ctx

                ;; Declaration is KEY-presence, not value truthiness
                ;; (rf2-6eh5h). Per rf2-iftj4 registration rejects a
                ;; boundary attachment whose metadata lacks the `:schema`
                ;; KEY — but `{:schema nil}` registers (the registrar
                ;; checks `contains?`), so a present falsey value MUST be
                ;; delegated verbatim below rather than treated as
                ;; impossible: the old `(nil? schema)` no-op ran the
                ;; handler UNGUARDED on exactly the payloads this
                ;; interceptor exists to gate (a release-resident
                ;; fail-open). An ABSENT key can only mean the registry
                ;; metadata was mutated post-registration; no declaration,
                ;; fall through as a no-op (defensive).
                (not (contains? handler-meta :schema))
                ctx

                :else
                ;; Per rf2-a5kzs (finding 2, boundary seam) — the validate
                ;; seam now isolates a malformed-schema throw and returns
                ;; `false` (fail CLOSED). Per rf2-gro94 the defensive catch
                ;; here ALSO fails CLOSED (`false`, not `true`): the
                ;; boundary interceptor exists precisely to gate untrusted
                ;; system-boundary payloads (HTTP / websocket / postMessage
                ;; / query-string), so a validator that throws — through the
                ;; seam OR a non-schemas validator that escapes its
                ;; isolation — must SKIP the handler, never run it on an
                ;; unvalidated payload. Coercing the throw to a PASS was the
                ;; same fail-OPEN class the schemas / routing sweeps closed.
                ;; The skipped-handler recovery below already surfaces a
                ;; `:rf.error/schema-validation-failure` trace so the throw
                ;; is observable.
                (let [ok? (try (validate-fn schema event)
                               (catch #?(:clj Throwable :cljs :default) _ false))]
                  (if ok?
                    ctx
                    (let [explanation (when explain-fn
                                        (try (explain-fn schema event)
                                             (catch #?(:clj Throwable :cljs :default) _ nil)))
                          ;; Per rf2-a5kzs / rf2-o69h5 — route the failure
                          ;; tags through the SHARED schema-aware redaction
                          ;; seam so a sensitive event payload (a `:cat`
                          ;; payload map carrying `{:sensitive? true}`) is
                          ;; scrubbed at the boundary exactly as the dev-time
                          ;; step-1 `validate-event!` path scrubs it. The seam
                          ;; is the `:schemas/redact-validation-tags` late-bind
                          ;; hook — THE one redactor every off-schemas
                          ;; validation-failure emit site shares; when the
                          ;; schemas artefact is absent the hook is nil and the
                          ;; tags ride verbatim (no schema = nothing to redact
                          ;; against).
                          redact-fn   (rf.late-bind/get-fn-cached :schemas/redact-validation-tags)
                          base-tags   (cond-> {:where      :event
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
                                                                (rf.error/type-of-value event) ".")
                                               :recovery   :no-recovery}
                                        frame (assoc :frame frame))]
                      ;; Axis 2 — the RICH dev trace, using the same shape as
                      ;; dev-mode step-1 (per Spec 010 L149). Per Spec
                      ;; 009 §Production builds `emit-error!` itself
                      ;; elides under `:advanced` + `goog.DEBUG=false`,
                      ;; so this body only fires under JVM / dev-CLJS
                      ;; with debug-enabled? flipped off — exactly the
                      ;; surface the rf2-r2uh tests exercise. The
                      ;; payload-bearing slots (`:received` / `:value` /
                      ;; `:explain` / the interpolated `:reason`) ride HERE
                      ;; and ONLY here — see the always-on record's
                      ;; structural-only contract in
                      ;; `router/emit-boundary-rejection-record!`.
                      (rf.trace/emit-error! :rf.error/schema-validation-failure
                                         (cond-> base-tags
                                           redact-fn (->> (redact-fn schema))))
                      ;; Per Spec 010 §Per-step recovery step 1: handler
                      ;; is not invoked. The handler-as-interceptor
                      ;; checks `:rf/skip-handler?` in its :before slot
                      ;; (see events.cljc).
                      ;;
                      ;; rf2-mwv4e — `:rf/boundary-rejected?` is the second
                      ;; half: the router tail reads it to fan the always-on
                      ;; structural record (axis 1) and to settle the dispatch
                      ;; `:outcome :rejected`. Kept as a context marker rather
                      ;; than emitting here so the dev and production
                      ;; enforcement routes share ONE emit site and a rejection
                      ;; can never produce two records.
                      (assoc ctx :rf/skip-handler?      true
                                 :rf/boundary-rejected? true))))))))))))

;; ---- :rf.schema/at-boundary registration (EP-0022, rf2-i3uxo2) ------------
;;
;; Per EP-0022 chain grammar + API.md §`validate-at-boundary-interceptor`:
;; a public `:interceptors` chain carries REFERENCES, not inline values.
;; A handler opts into boundary validation by referencing the registered
;; `:rf.schema/at-boundary` interceptor by id — `{:interceptors [:rf.schema/at-boundary]}`
;; — NOT by dropping the `validate-at-boundary-interceptor` Var into the
;; chain. The Var stays the registration-boundary INPUT (the value this ns
;; registers); the chain references the registered interceptor.
;;
;; For the bare-keyword ref `[:rf.schema/at-boundary]` to resolve at chain
;; assembly (rather than failing `:rf.error/unregistered-interceptor`), the
;; interceptor must live under the `:interceptor` registrar kind. We register
;; it as a STATIC descriptor (`{:before …}`) — boundary validation runs
;; entirely in the `:before` slot. The descriptor reuses the SAME `:before`
;; fn as the `validate-at-boundary-interceptor` value Var, so the inline-value
;; (migration boundary) form and the by-ref form share one implementation.
;;
;; Mirrors `re-frame.std-interceptors/register-standard-interceptors!`: called
;; at namespace load so standalone require'rs (no `init!`) get the ref, AND
;; re-seeded from `re-frame.core/init!` so the ref survives a test fixture's
;; `rf.registrar/clear-all!` (which wipes the `:interceptor` kind).

(defn register-schema-interceptors!
  "Register the framework-standard `:rf.schema/at-boundary` interceptor into
  the active registrar so the EP-0022 ref form `[:rf.schema/at-boundary]`
  resolves at chain assembly (Spec 010 §Production builds + rf2-i3uxo2).

  Registered as a STATIC descriptor reusing the same `:before` slot as the
  `validate-at-boundary-interceptor` value Var — the inline-value (migration
  boundary) and by-ref forms therefore run identical boundary validation.

  Idempotent — called at namespace load AND from `re-frame.core/init!` so the
  ref survives a test fixture's `rf.registrar/clear-all!` (which wipes the
  `:interceptor` kind along with everything else). Mirrors how
  `re-frame.std-interceptors/register-standard-interceptors!` re-seeds the
  standard `:rf.interceptor/path` interceptor."
  []
  (rf.interceptor-registry/reg-interceptor*
    :rf.schema/at-boundary
    {:doc "Framework-standard production-boundary schema-validation interceptor
          (Spec 010 §Production builds). Referenced as `[:rf.schema/at-boundary]`
          from an event's metadata `:interceptors`; forces the handler's own
          `:schema` check at the boundary even in production builds where
          dev-time validation is elided. Registration without `:schema` is
          rejected at reg time (`:rf.error/at-boundary-missing-schema`)."}
    {:before (:before validate-at-boundary-interceptor)})
  nil)

;; Register at namespace load so standalone require'rs (no `init!`) get the
;; ref; `init!` re-registers (idempotent) for the post-clear-all! test path.
(register-schema-interceptors!)
