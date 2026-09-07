(ns re-frame.spec
  "Boundary schema validation — the ALWAYS-ON half of Spec 010 step-1 event
  validation. Per Spec 010 §Production builds (rf2-r2uh, rf2-kuky.40).

  > The ns name is preserved from v2's early phase (`re-frame.spec`), but the
  > canonical vocabulary is `:schema` everywhere else after rf2-ieu0i — the
  > registration keys are `:schema` and `:boundary?`, and the hot-reload trace
  > category is `:rf.schema/violation`.

  A handler opts into production-side enforcement of its OWN `:schema` by
  setting `:boundary? true` on the registration. System-boundary handlers
  (HTTP responses, websocket messages, postMessage, query-string values) are
  what the flag is for:

  ```clojure
  (ns my-app.api
    (:require [re-frame.core :as rf]))

  (rf/reg-event :api/response-received
    {:schema    ApiResponseSchema
     :boundary? true}
    (fn [_ [_ payload]] ...))
  ```

  Per Spec 010 §Production builds the canonical CLJS release elides every
  dev-time `validate-*!` call site at `:advanced` + `goog.DEBUG=false`.
  `:boundary? true` keeps the check alive there — at the SAME step-1 site, on
  the SAME value dev checks (the ORIGINAL dispatched event vector, before the
  interceptor chain runs), against the SAME already-resolved handler
  `:schema`. Dev and production therefore never disagree about what was
  validated, and no interceptor-chain rewrite can move the check or feed it a
  transformed event.

  - In **dev builds** every handler's `:schema` is checked anyway (Spec 010
    §Validation order step 1), so `validate-at-boundary!` is never reached and
    `:boundary?` changes nothing.
  - In **production builds** `re-frame.interop/debug-enabled?` is `false` and
    step-1 validation is elided — except for `:boundary? true` handlers, which
    take this arm.
  - **`:boundary? true` without `:schema`** is rejected at registration time
    with `:rf.error/at-boundary-missing-schema` (Spec 010 §Production builds,
    rf2-iftj4). The flag is structurally meaningless without a schema, so
    `re-frame.events` raises from `reg-event` rather than waiting for the first
    dispatch to surface the misconfiguration. There is no warn-and-accept
    fallback.

  Validation routes through the same registered validator the dev-time hot
  path uses (the `set-schema-fns!` seam) — a substituted validator covers both
  surfaces with one registration. When `set-schema-fns!` has installed a `nil`
  `:validate`, this arm is a no-op (validation disabled).

  This namespace stays decoupled from `re-frame.schemas` (an optional
  artefact) by reaching into it through the
  `:schemas/validate-with-registered-fn` and
  `:schemas/explain-with-registered-fn` late-bind hooks. When the schemas
  artefact is not on the classpath the hooks return nil and the check falls
  through as a no-op."
  (:require [re-frame.error :as rf.error]
            [re-frame.events :as rf.events]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- dev / prod gate ------------------------------------------------------
;;
;; The router's step-1 site asks "is this a dev build?" — if so the ordinary
;; dev-time `validate-event!` seam runs and covers EVERY handler; if not,
;; `validate-at-boundary!` runs and covers the `:boundary? true` handlers only.
;;
;; The canonical CLJS gate is `re-frame.interop/debug-enabled?` (alias of
;; `goog.DEBUG`); on the JVM it is hardcoded `true`. We wrap the read in a fn
;; so tests can rebind the boundary's dev/prod decision INDEPENDENTLY of the
;; trace surface's `rf.interop/debug-enabled?` read. This matters because the
;; trace surface (`emit!` / `emit-error!`) is itself gated on `debug-enabled?`
;; (Spec 009 §Production builds): a JVM test that wants the production branch
;; AND wants to observe the emitted error trace needs `debug-enabled?` to stay
;; true (so traces fire) while `dev-mode?` reads false.
;;
;; Production-elision: `rf.interop/debug-enabled?` is the closure-define
;; alias; under `:advanced` + `goog.DEBUG=false` it folds to `false` and
;; `dev-mode?` constant-folds with it (the fn body is a single var read;
;; Closure inlines and folds). The router's dev arm — the late-bind lookup,
;; the try/catch frame and the `:schemas/validate-event!` interned slot —
;; therefore DCEs exactly as it did when that gate was written inline.

(defn dev-mode?
  "Returns true in dev / JVM (where step-1 validation already covers every
  handler); false in `:advanced` + `goog.DEBUG=false` production (where only
  `:boundary? true` handlers are validated, by `validate-at-boundary!`).

  Wraps `rf.interop/debug-enabled?` in an indirection so tests can rebind the
  boundary's dev/prod decision without redefining the var the trace surface
  itself reads."
  []
  rf.interop/debug-enabled?)

;; ---- `:boundary? true` — always-on step-1 validation ----------------------
;;
;; rf2-mwv4e — a refusal here does NOT emit the always-on structural record
;; itself. It returns `false`, and `re-frame.router/run-chain` stamps
;; `:rf/skip-handler?` plus `:rf/boundary-rejected?` on the context; the
;; router's tail reads that marker to fan ONE always-on structural record and
;; to settle the dispatch `:outcome :rejected`. The DEV route reaches the same
;; marker from the same place (step-1 refused an event whose handler carries
;; `:boundary? true`), so both enforcement routes converge on one marker and
;; one emit site, and a rejection can never produce two records. See
;; `router/emit-boundary-rejection-record!`.
;;
;; An ordinary dev-only `:schema` refusal on an UNFLAGGED handler is
;; deliberately NOT marked: that surface has no production counterpart
;; (Spec 010 §Production builds, rf2-bkvu5), so marking it would invent a
;; production signal that cannot exist.

(defn validate-at-boundary!
  "Production-side step-1 validation for a handler that declared
  `:boundary? true`. Called from `re-frame.router`'s step-1 site when
  `dev-mode?` is false, on the ORIGINAL dispatched event vector, BEFORE the
  interceptor chain runs and against the ALREADY-RESOLVED handler's own
  `:schema` — never a re-derived id, never a chain-transformed event.

  Returns truthy when the handler should run, `false` when it should be
  skipped. Falls through as a no-op (returns `true`) for a handler that did
  not declare `:boundary? true`, when no validator is registered
  (`set-schema-fns!` installed a `nil` `:validate`, or the schemas artefact is
  absent), and — defensively — when the registry metadata carries no `:schema`
  KEY at all.

  Declaration is KEY-presence, not value truthiness (rf2-6eh5h). Registration
  rejects `:boundary? true` whose metadata lacks the `:schema` KEY, but
  `{:schema nil :boundary? true}` registers (the registrar checks `contains?`),
  so a present falsey value is delegated to the backend VERBATIM as an opaque
  token rather than treated as impossible: reading `nil` as nothing-to-check
  would run the handler UNGUARDED on exactly the payloads the flag exists to
  gate — a release-resident fail-open.

  FAILS CLOSED on a validator that throws (per rf2-a5kzs finding 2 and
  rf2-gro94): the flag exists precisely to gate untrusted system-boundary
  payloads, so a throw — through the seam OR from a non-schemas validator that
  escapes its isolation — SKIPS the handler rather than running it on an
  unvalidated payload. A throwing EXPLAINER cannot reverse that verdict; it
  costs only the diagnosis. The router does not route this call through its
  dev-arm catch-and-pass, so neither throw can be coerced into a pass.

  `frame` is stamped on the failure trace so `re-frame.epoch.capture/
  capture-event!` (which buffers only frame-tagged traces) attributes the
  failure to the emitting frame's epoch, and so the SSR error-projection
  listener can route it per-frame under concurrent server frames."
  [event-id event handler-meta frame]
  (if-not (rf.events/boundary-guarded-handler? handler-meta)
    true
    (let [validate-fn (rf.late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
      (if (or (nil? validate-fn)
              ;; An ABSENT `:schema` key can only mean the registry metadata was
              ;; mutated post-registration; no declaration, no check.
              (not (contains? handler-meta :schema)))
        true
        (let [schema (:schema handler-meta)
              ok?    (try (validate-fn schema event)
                          (catch #?(:clj Throwable :cljs :default) _ false))]
          (if ok?
            true
            (let [explain-fn  (rf.late-bind/get-fn-cached :schemas/explain-with-registered-fn)
                  explanation (when explain-fn
                                (try (explain-fn schema event)
                                     (catch #?(:clj Throwable :cljs :default) _ nil)))
                  ;; Per rf2-a5kzs / rf2-o69h5 — route the failure tags through
                  ;; the SHARED schema-aware redaction seam so a sensitive
                  ;; payload (a `:cat` payload map carrying `{:sensitive? true}`)
                  ;; is scrubbed here exactly as the dev-time step-1 path scrubs
                  ;; it. When the schemas artefact is absent the hook is nil and
                  ;; the tags ride verbatim (no schema = nothing to redact
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
              ;; Axis 2 — the RICH dev trace, the same shape dev-mode step-1
              ;; emits (Spec 010 L149). Per Spec 009 §Production builds
              ;; `emit-error!` itself elides under `:advanced` +
              ;; `goog.DEBUG=false`, so this body only fires under JVM /
              ;; dev-CLJS with `dev-mode?` flipped off — exactly the surface the
              ;; rf2-r2uh tests exercise. The payload-bearing slots
              ;; (`:received` / `:value` / `:explain` / the interpolated
              ;; `:reason`) ride HERE and ONLY here — see the always-on record's
              ;; structural-only contract in
              ;; `router/emit-boundary-rejection-record!`.
              (rf.trace/emit-error! :rf.error/schema-validation-failure
                                    (cond-> base-tags
                                      redact-fn (->> (redact-fn schema))))
              ;; Per Spec 010 §Per-step recovery step 1 the handler is not
              ;; invoked and the downstream queue continues. `run-chain` turns
              ;; this `false` into `:rf/skip-handler?` + `:rf/boundary-rejected?`
              ;; and still executes the chain, so `:after` cleanup runs in full.
              false)))))))
