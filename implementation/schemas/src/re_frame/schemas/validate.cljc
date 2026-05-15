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
  keyword per Spec 009 §Privacy). The trace event's `:tags`
  map is stamped with `:sensitive? true` so consumers can route on it
  (until rf2-isdwf's top-level hoisting lands in core).

  The value-bearing slots are redacted (`:value`, `:received`,
  `:explain`, plus `:fx-args` on `:where :fx-args` emissions and
  `:query-v` on `:where :sub-return` emissions — see `redact-tags`);
  the structural / categorical slots (`:path`, `:failing-id`,
  `:spec-id`, `:reason`) are kept — consumers need them to locate
  the broken slot without leaking user data.

  Per rf2-4fbsd the emit-sites carry two slots for the failing value
  (`:value` and `:received`, per Spec 010 §`:sensitive?`) and one slot
  for the registered explainer's output (`:explain`). The earlier
  `:event` (duplicate of `:received`) and `:malli-error` (duplicate of
  `:explain`) tags have been dropped — consumers reach for `:received`
  / `:value` / `:explain`."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private redacted-sentinel
  "The `:rf/redacted` privacy sentinel emitted by validation traces
  for slots matching the `:sensitive?` predicate. Per Spec 009
  §Privacy — the framework-reserved keyword that cannot
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
  correctly. Idempotent — safe to call on an already-redacted map.

  The five redacted slots (`:value`, `:received`, `:explain`,
  `:fx-args`, `:query-v`) are the canonical set per the Spec 010
  §`:sensitive?` redaction-shape list. Two of them are per-surface
  doubled-id names carried only on a single emit-site (`:fx-args` on
  `:where :fx-args`; `:query-v` on `:where :sub-return`); the
  `contains?` guards make those clauses no-ops on the other surfaces
  whose tag maps don't carry the slot. Per rf2-nijom this replaces the
  previous fx-only `extra-redact` lambda — the redaction is now
  symmetric across every value-bearing slot, and the schema lists
  them canonically.

  Per rf2-adtp2 / rf2-p2adl Q2 — `:query-v` (the caller-supplied
  subscription query vector on `:where :sub-return` emissions) is
  the lookup key, not just an id, and on `:sensitive?`-marked subs
  typically carries the same secret material the registered schema
  is gating (user ids, auth tokens, document ids). Without
  redaction the failure trace re-leaks it alongside the failing
  return value the other clauses just scrubbed."
  [tags]
  (cond-> tags
    (contains? tags :value)    (assoc :value     redacted-sentinel)
    (contains? tags :received) (assoc :received  redacted-sentinel)
    (contains? tags :explain)  (assoc :explain   redacted-sentinel)
    (contains? tags :fx-args)  (assoc :fx-args   redacted-sentinel)
    (contains? tags :query-v)  (assoc :query-v   redacted-sentinel)
    true                       (assoc :sensitive? true)))

(defn- common-prefix
  "Return the longest common prefix of two sequential collections (as a
  vector). Element-wise comparison via `=`. Helper for narrowing
  multi-error explainer outputs to a single most-specific `:in` path."
  [a b]
  (loop [a (seq a) b (seq b) acc (transient [])]
    (if (and a b (= (first a) (first b)))
      (recur (next a) (next b) (conj! acc (first a)))
      (persistent! acc))))

(defn- failing-in-path
  "Per rf2-oh4se — derive the failing leaf value-path from the registered
  explainer's output. Returns a vector path (possibly empty when the
  failure is at the schema root) or nil when the explanation carries
  no extractable `:in` path (e.g. non-Malli explainer, malformed
  output, or no explainer registered).

  Malli's explanation shape is `{:schema ... :value ... :errors
  ({:path [...] :in [...] :schema ... :value ...} ...)}`. The `:in`
  slot is the navigation path through the failing VALUE (what we want
  for `(get-in db (concat registered-path :in))`); `:path` is the
  schema-walk path which encodes dispatch values (`:multi` / `:orn`
  branches) — wrong for value navigation.

  Multi-error explanations collapse to the common ancestor across
  every error's `:in` — narrowest path that contains every failure
  site. For a single error this is just that error's `:in`. When
  every error's `:in` agrees the result is exact; when they diverge
  (e.g. two separate slots in a map both fail) the common prefix is
  the parent slot that contains them.

  Pure; same explanation always produces the same output."
  [explanation]
  (when (map? explanation)
    (when-let [errors (seq (:errors explanation))]
      (let [paths (keep :in errors)]
        (when (seq paths)
          (reduce common-prefix (first paths) (rest paths)))))))

(defn- reason-string
  "Build the human-readable `:reason` slot for a schema-validation
  failure trace. Single template covering every emit-site:

    - `subject`: subject phrase up to (but not including) the id —
      \"Event \" / \"Coeffect \" / \"Subscription \" / \"Effect \" /
      \"App-db at path \". Built per-call-site as a string literal
      so the elision-probe grep (`scripts/check-elision.cjs`) can
      pin a distinctive substring per surface.
    - `id-or-path`: the id (event-id, sub-id, ...) or the app-db
      path; rendered via `str` so keywords print with the colon.
    - `slot-tail`: distinctive per-surface tail starting with
      \" failed schema \" — e.g. \" payload failed schema \" /
      \" injected value failed schema \" / \", failed schema \"
      (app-db has no slot label so the tail starts with \" failed
      schema \" directly). Pinned per-site so DCE analysis can see
      the literal substring inside each gated branch — the
      elision-probe asserts every surface's distinctive tail is
      ABSENT under :advanced + goog.DEBUG=false.
    - `schema`: the registered schema (Malli EDN form or other
      validator's schema value); rendered via `pr-str` (no special-
      casing of `[:int]` etc. — `pr-str` already reads fine).
    - `value`: the value that failed.

  Shape: \"<subject><id-or-path><slot-tail><pr-str schema>, got
  <error/type-of-value>.\""
  [subject id-or-path slot-tail schema value]
  (str subject id-or-path slot-tail (pr-str schema)
       ", got " (error/type-of-value value) "."))

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
    - `meta-sensitive?` boolean — the registration-meta `:sensitive?`
                     flag (the coarse, handler-level signal). Wrappers
                     pass `(meta-sensitive? meta)`. The schema-level
                     `:sensitive?` walker check is deferred to the
                     failure branch so the hot path doesn't pay for it
                     on every pass.
    - `walk-schema?` boolean — when true AND `meta-sensitive?` is
                     false AND the validator fails, consult the
                     schema's per-slot `:sensitive?` walker before
                     emitting. Event vectors are not schema-walked
                     (event vectors aren't `:map`-shaped, so per-slot
                     `:sensitive?` props don't apply) so wrappers
                     pass `false`; cofx / fx / sub-return pass `true`.
    - `build-base-tags`  `(fn [schema explanation] -> map)` — produces
                     the per-fn tag map (`:where`, `:reason`, etc.)
                     EXCLUDING any sensitivity stamping.

  Reachability: every call-site lives inside the outermost
  `(if interop/debug-enabled? ...)` gate of its public wrapper.
  Closure's reachability proof under :advanced + goog.DEBUG=false
  finds every call-site dead and DCEs this fn — along with every
  literal reason string and tag keyword passed through it.

  Per rf2-1o6ax the registered validator-fn is deref'd ONCE at the
  gate and invoked directly — `validator/run-validator` would deref
  the same atom a second time on every pass, which is wasted work
  on a path that runs per-event / per-cofx / per-fx / per-sub-return.

  Per rf2-nijom this primitive no longer carries an `extra-redact`
  escape hatch — the four canonical redacted slots
  (`:value`, `:received`, `:explain`, `:fx-args`) all live on the
  central `redact-tags` cond->. The fx-args clause is a no-op on
  the other three surfaces (their base-tags don't contain the
  slot), so a single redactor covers every meta-bearing emit site."
  [meta value meta-sensitive? walk-schema? build-base-tags]
  (if-let [vf @validator/validator-fn]
    (if-let [schema (:spec meta)]
      (if (vf schema value)
        true
        (let [explanation (validator/run-explainer schema value)
              sensitive?  (or meta-sensitive?
                              (and walk-schema?
                                   (walker/schema-has-sensitive? schema)))
              base-tags   (build-base-tags schema explanation)
              tags        (cond-> base-tags sensitive? redact-tags)]
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

  Returns:
    true   — every registered schema conformed (or no validator /
             no schemas registered for the frame / debug elided).
    false  — at least one schema failed; a trace event was emitted
             for every failing entry. The router consumes this signal
             to roll back the :db effect to the pre-handler value
             (per Spec 010 §Per-step recovery row 4 / rf2-wkxng /
             rf2-6m0se).

  Structurally distinct from the four meta-bearing validate-*! fns
  (event / cofx / fx / sub-return): walks N schemas via doseq, has no
  single `:spec`-on-meta lookup, and emits a trace per failure (rather
  than at-most-one). Returns a single boolean conjoining every entry's
  result so the caller can decide rollback deterministically — but
  every failing schema is still surfaced as its own trace so consumers
  see the full set."
  ([db] (validate-app-db! db nil (frame/current-frame)))
  ([db event-id] (validate-app-db! db event-id (frame/current-frame)))
  ([db event-id frame-id]
   ;; Per Spec 009 §Production builds the entire body lives inside a
   ;; `(if interop/debug-enabled? ... true)` gate as the OUTERMOST form
   ;; so :advanced + goog.DEBUG=false DCE-elides every reason string,
   ;; keyword, validator deref, and trace call. Production builds
   ;; return `true` unconditionally — the rollback path is dev-only
   ;; (post-commit validation is gated by debug-enabled?, so no
   ;; failure is observable to roll back against). The `@validator-fn`
   ;; check is a runtime atom deref and must NOT be combined into the
   ;; gate predicate (the deref defeats Closure's reachability proof).
   ;;
   ;; Per rf2-1o6ax the validator-fn is deref'd ONCE outside the doseq
   ;; and invoked directly per entry; `validator/run-validator` would
   ;; re-deref the atom on every iteration (2N derefs for N entries),
   ;; which is wasted work on the post-handler-commit hot path.
   (if interop/debug-enabled?
     (if-let [vf @validator/validator-fn]
       ;; reduce + atomic short-circuit replaced doseq so we can emit
       ;; a trace per failure (full surface for consumers) AND return
       ;; a single conjoined boolean (single signal for the rollback
       ;; gate). Pass-state stays `true` only when every entry passed.
       (loop [entries (seq (storage/frame-schema-entries frame-id))
              ok?     true]
         (if-let [[reg-path m] (first entries)]
           (let [val-at (get-in db reg-path)
                 schema (:schema m)]
             (if (vf schema val-at)
               (recur (next entries) ok?)
               (do
                 ;; Per rf2-oh4se — make the failure path precise and
                 ;; the sensitivity decision path-targeted.
                 ;;
                 ;; The registered explainer is invoked exactly once
                 ;; on the failure branch; its output feeds BOTH the
                 ;; trace's `:explain` slot (verbatim) and the
                 ;; failing-leaf path extraction. `failing-in-path`
                 ;; returns the navigation path through the value
                 ;; Malli reports under `:in` (the value-relative
                 ;; path, not the schema-walk path under `:path` which
                 ;; carries `:multi` / `:orn` dispatch values). The
                 ;; trace's `:path` is the registered root conj'd with
                 ;; the leaf — the slot a consumer can `get-in`
                 ;; against on a NON-failed copy of app-db.
                 ;;
                 ;; Conservative fallback: when no leaf path is
                 ;; extractable (non-Malli explainer, missing
                 ;; explanation), keep the old behaviour — emit the
                 ;; registered root as `:path` and consult
                 ;; `schema-has-sensitive?` for the redaction decision.
                 ;; The `:registered-path` tag always carries the
                 ;; registration root so tooling can reach it
                 ;; regardless of whether path narrowing succeeded.
                 ;;
                 ;; Per Spec 010 §`:sensitive?` — privacy in schema-
                 ;; validation error traces (rf2-kj51z). The path-
                 ;; targeted check (`schema-sensitive-at?`) replaces
                 ;; the coarse whole-schema check: a failure at a
                 ;; non-sensitive slot in a schema that also declares
                 ;; a sibling slot sensitive no longer suffers
                 ;; redaction. Conservative semantics preserved —
                 ;; ancestor-sensitive OR descendant-sensitive at the
                 ;; failing path counts.
                 ;;
                 ;; Per rf2-wkxng / rf2-6m0se the trace's tag carries
                 ;; `:rollback? true` (consistent with depth-exceeded;
                 ;; reuses the existing `:recovery :no-recovery`
                 ;; vocabulary rather than minting a new enum value).
                 ;; The router consumes the loop's final boolean to
                 ;; perform the actual container restoration.
                 (let [explanation (validator/run-explainer schema val-at)
                       in-path     (failing-in-path explanation)
                       leaf-path   (if in-path
                                     (vec (concat reg-path in-path))
                                     reg-path)
                       leaf-value  (if in-path
                                     (get-in val-at in-path)
                                     val-at)
                       sensitive?  (if in-path
                                     (walker/schema-sensitive-at? schema in-path)
                                     (walker/schema-has-sensitive? schema))
                       base-tags   (cond-> {:where           :app-db
                                            :path            leaf-path
                                            :registered-path reg-path
                                            :value           leaf-value
                                            :frame           frame-id
                                            :explain         explanation
                                            :reason          (reason-string
                                                               "App-db at path "
                                                               leaf-path
                                                               " failed schema "
                                                               schema leaf-value)
                                            :rollback?       true
                                            :recovery        :no-recovery}
                                     event-id (assoc :failing-id event-id))
                       tags        (if sensitive? (redact-tags base-tags) base-tags)]
                   (trace/emit-error! :rf.error/schema-validation-failure tags)
                   (recur (next entries) false)))))
           ok?))
       true)
     true)))

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
      (meta-sensitive? handler-meta)
      false  ;; event vectors aren't `:map`-shaped — no per-slot walk
      (fn [schema explanation]
        {:where      :event
         :event-id   event-id
         :failing-id event-id
         :spec-id    event-id
         :received   event
         :value      event
         :explain    explanation
         :reason     (reason-string "Event " event-id
                                    " payload failed schema "
                                    schema event)
         :recovery   :no-recovery}))
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
      (meta-sensitive? sub-meta)
      true   ;; consult schema's per-slot `:sensitive?` walker on fail
      (fn [schema explanation]
        {:where      :sub-return
         :sub-id     sub-id
         :failing-id sub-id
         :spec-id    sub-id
         :query-v    query-v
         :received   value
         :value      value
         :explain    explanation
         :reason     (reason-string "Subscription " sub-id
                                    " return value failed schema "
                                    schema value)
         :recovery   :replaced-with-default}))
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
      (meta-sensitive? cofx-meta)
      true   ;; consult schema's per-slot `:sensitive?` walker on fail
      (fn [schema explanation]
        {:where      :cofx
         :cofx-id    cofx-id
         :event-id   event-id
         :failing-id event-id
         :spec-id    cofx-id
         :received   value
         :value      value
         :explain    explanation
         :reason     (reason-string "Coeffect " cofx-id
                                    " injected value failed schema "
                                    schema value)
         :recovery   :no-recovery}))
    true))

(defn validate-fx!
  "Per Spec 010 §Validation order step 5 — before an fx handler runs,
  validate its args against any :spec on the fx's metadata. Failures
  emit `:rf.error/schema-validation-failure :where :fx-args`; per
  Spec 010 §Per-step recovery row 5 the caller skips the offending fx
  only (recovery: `:skipped`) — sibling fx in the same `:fx` vector
  continue to run, and downstream queued events still drain. Returns
  true/false per the `run-validation` contract.

  Per rf2-nijom the per-surface `:fx-args` slot is redacted by the
  central `redact-tags` cond->; the lambda escape hatch that used to
  do this here is gone, and Spec 010 §`:sensitive?` now lists
  `:fx-args` alongside `:value` / `:received` / `:explain` as the
  canonical redacted slots (and `:query-v` on the sub-return
  surface, per rf2-adtp2)."
  [fx-id event-id args fx-meta]
  (if interop/debug-enabled?
    (run-validation
      fx-meta
      args
      (meta-sensitive? fx-meta)
      true   ;; consult schema's per-slot `:sensitive?` walker on fail
      (fn [schema explanation]
        (cond-> {:where      :fx-args
                 :fx-id      fx-id
                 :fx-args    args
                 :failing-id fx-id
                 :spec-id    fx-id
                 :received   args
                 :value      args
                 :explain    explanation
                 :reason     (reason-string "Effect " fx-id
                                            " args failed schema "
                                            schema args)
                 :recovery   :skipped}
          event-id (assoc :event-id event-id))))
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
