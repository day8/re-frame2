(ns re-frame.schemas.validate
  "Validation entry points and failure-trace projection (Spec 010).

  Owns the four dev-time validate-*! fns the framework calls at the
  locked validation sites:

    - validate-event!        — pre-handler (event vector vs handler :schema)
    - validate-fx!           — pre-fx-handler (fx args vs fx :schema)
    - validate-app-schema!   — post-handler-commit (frame's app-schemas)
    - validate-sub!          — post-sub-recompute (return value vs sub :schema)

  Event, effect, and subscription validation share `run-validation`.
  App-db validation iterates every schema registered for a frame and keeps
  per-entry verdicts isolated. Recordable cofx validation belongs to
  `re-frame.cofx` because it is an always-on production contract.

  This namespace also owns the production boundary seams reached through
  late binding by `re-frame.spec`.

  Per Spec 009 §Production builds every dev-time validate-*! body lives
  inside an `(if interop/debug-enabled? ...)` gate as the OUTERMOST
  form so :advanced+goog.DEBUG=false DCE-elides every reason string,
  keyword, validator deref, and trace call. The private
  `run-validation` primitive is reachable only from those gated arms
  — when every call-site is dead, Closure's reachability proof DCEs
  the primitive itself, along with every literal reason string passed
  through it.

  Validator and explainer functions are pluggable. An absent validator means
  pass without inspection; an absent explainer means no diagnostic value.

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces. The emit-sites redact the failing value before
  stamping a trace event when the schema slot at the failing path
  (or a containing slot) carries `:sensitive? true` in its Malli
  props. Sensitivity is path-marked at the schema slot.
  The substitution sentinel is `:rf/redacted` (the framework-reserved
  keyword per Spec 009 §Privacy). The trace event's `:tags`
  map is stamped with `:sensitive? true` so consumers can route on it.

  The value-bearing slots are redacted (`:value`, `:received`,
  `:explain`, `:explain-humanized`, plus `:rf.fx/args` on
  `:where :fx-args` emissions and `:rf.sub/query-v` on
  `:where :sub-return` emissions — see `redact-tags`); the structural /
  categorical slots (`:path`, `:failing-id`, `:schema-id`, `:reason`)
  are kept — consumers need them to locate the broken slot without
  leaking user data. `:explain-humanized` (the
  operator-readable decomposition of the explainer's output) is
  value-bearing too — it carries the failing value verbatim under
  Malli's path-shaped humanize output — so it redacts symmetrically
  with `:explain` (present as the `:rf/redacted` sentinel on sensitive
  failures, not omitted).

  A `:path` tag's normally structural segments have several value-bearing
  cases that
  `walker/sanitize-sensitive-path` scrubs to `:rf/redacted` on a sensitive
  failure (the same scrubbed path also feeds `:reason`, so neither slot
  leaks): a `:set` failure's segment is the failing ELEMENT VALUE itself
  (Malli has no positional index for a set); a `:map-of` key whose KEY
  SCHEMA declares `:sensitive?` is the secret used AS the key
  and any scalar in the fail-closed tail past an ambiguous wrapper
  (`:orn` / `:multi`) cannot be proven a locator and may be a value-bearing
  `:set` scalar element. Navigable `:vector` /
  `:tuple` / `:map` segments and NON-sensitive `:map-of` keys stay intact
  so `:path` remains a `get-in` locator for those shapes.

  A structurally malformed registered schema (childless
  `[:vector]`, unknown op) makes the registered validator THROW at
  validate-time (Malli validates schema forms lazily). `validate-app-schema!`
  isolates that throw PER-ENTRY, emits the distinct
  `:rf.error/malformed-schema` category, fails CLOSED (in-band `false` →
  the router rolls back; it does NOT install the unvalidated commit), and
  keeps validating the frame's sibling schemas so one bad registration
  cannot disable frame-wide post-commit validation."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Canonical privacy sentinel for value-bearing trace slots.
(def ^:private redacted-sentinel privacy/redacted-sentinel)

;; The canonical value-bearing tag slots — the ones that carry the failing
;; value (or a value-bearing lookup key) verbatim and so must be scrubbed
;; on a `:sensitive?` failure (`redact-tags`) AND dropped entirely from a
;; `:rf.error/malformed-schema` trace (where the validator never proved
;; sensitivity, so we cannot redact path-targeted — omitting the value is
;; fail-closed, mirroring `validate-app-schema!`'s malformed-schema emit).
;; Three are per-surface conditional names carried only on a
;; subset of emit-sites (`:rf.fx/args` on `:where :fx-args`;
;; `:rf.sub/query-v` on `:where :sub-return`; `:explain-humanized` only when
;; the humanize hook is installed); `contains?` guards keep the clauses
;; no-ops on the surfaces whose tag maps don't carry the slot.
(def ^:private value-bearing-slots
  [:value :received :explain :explain-humanized :rf.fx/args :rf.sub/query-v])

;; ---- :large? size-elision of validation-failure value slots ---------------
;;
;; A `:large?`-flagged slot inside the checked value ships the whole blob into
;; the validation-failure trace's value-bearing slots unless the emit-site
;; elides it. Per Spec 010 §`:large?` (the validation size-safety arm) and
;; §Composition with `:large?` (sensitive wins), the emit-site substitutes the
;; `:rf.size/large-elided` marker for the whole value-bearing slots when the
;; schema declares any `:large?` slot and NO `:sensitive?` slot governs the
;; redaction — a sensitive failure already scrubs to `:rf/redacted`, and a
;; sensitive marker would itself leak the secret's `:path` / `:bytes` size
;; signature. The canonical `re-frame.elision/->marker` owns the wire shape.

(defn- large-marker
  "Build the `:rf.size/large-elided` marker for the whole failing value `v`.
  Conforms to Spec-Schemas §`:rf/elision-marker` / Spec 009 §Wire marker by
  delegating to the canonical `re-frame.elision/->marker` — the marker shape
  (`:path`, `:bytes`, `:type`, `:reason`, `:hint`, `:handle`) is therefore
  identical to every other framework emission.

  `:reason :effect` is the canonical classification provenance. The enclosing
  trace already identifies the validation-failure source.

  `:hint nil` — the slot is REQUIRED by the contract (Spec 009 §Wire marker
  permits a nil value); the whole-value substitution has no human-facing
  re-fetch hint distinct from the trace envelope.

  `:path []` — the marker substitutes the WHOLE value-bearing slot, matching
  the whole-payload nature of these slots (a single marker, not a path-walk;
  the value-bearing slots carry the whole checked value, not a leaf)."
  [v]
  (elision/->marker v [] {:reason :effect :hint nil}))

(defn- elide-large-slots
  "Substitute the `:rf.size/large-elided` marker (for `v`, the whole checked
  value) into each `value-bearing-slots` entry present in `tags`, and stamp
  `:large? true`. `contains?`-guarded so a slot a surface doesn't carry is a
  no-op. Per Spec 010 §`:large?` validation size-safety arm.

  Called ONLY on the non-sensitive branch (sensitive wins — see
  `redact-tags` / `redact-tags-per-slot`): a slot already scrubbed to
  `:rf/redacted` must never be replaced by a size marker that re-leaks the
  secret's `:bytes` signature."
  [tags v]
  (let [marker (large-marker v)]
    (-> (reduce (fn [t slot]
                  (cond-> t (contains? t slot) (assoc slot marker)))
                tags
                value-bearing-slots)
        (assoc :large? true))))

;; ---- per-slot decision scope ----------------------------------------------
;; A redaction decision must match the value a tag carries. App-db `:value`
;; is narrowed to the failing leaf and can use `schema-sensitive-at?`. Whole
;; payload slots can still contain sensitive siblings, so they must use the
;; broader `schema-has-sensitive?` decision.
(def ^:private app-db-narrowed-slots
  "The value-bearing slots the app-db hot path NARROWS to the failing leaf
  (`:value` = `(get-in registered-value in-path)`). These — and ONLY these — use the
  leaf-precise `schema-sensitive-at?` decision; every other value-bearing slot
  on that surface carries the whole registered value and uses the root check."
  #{:value})

(defn- scrub-slots
  "Replace each slot of `slots` present in `tags` with the `:rf/redacted`
  sentinel. `contains?`-guarded so a slot a surface doesn't carry is a no-op."
  [tags slots]
  (reduce (fn [t slot]
            (cond-> t (contains? t slot) (assoc slot redacted-sentinel)))
          tags
          slots))

(defn- redact-tags
  "Scrub the value-bearing slots of a tags map to the `:rf/redacted` sentinel
  under the WHOLE-PAYLOAD (root) decision, stamping `:sensitive? true`. Per
  Spec 010 §`:sensitive?` — privacy in schema-validation error traces.
  Idempotent — safe to call on an already-redacted map.

  This is the WHOLE-PAYLOAD redactor: it treats EVERY value-bearing slot as
  carrying the whole checked value, so it is the correct shape for the
  meta-bearing surfaces (event / fx / sub via `run-validation`, where
  no slot is leaf-narrowed) and for the off-namespace seam
  `redact-validation-tags` (coarse and root-checked). The
  app-db hot path, which DOES narrow `:value` to the failing leaf, uses
  `redact-tags-per-slot` below to apply the leaf-precise decision to that one
  slot while keeping the root decision on the whole-payload slots.

  The six candidate slots (`:value`, `:received`, `:explain`,
  `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`) are the canonical set
  per the Spec 010 §`:sensitive?` redaction-shape list. Three are per-surface /
  conditional names carried only on a subset of emit-sites (`:rf.fx/args` on
  `:where :fx-args`; `:rf.sub/query-v` on `:where :sub-return`;
  `:explain-humanized` only when the `:schemas/humanize-explain!` hook is
  installed); the `contains?` guards make those clauses no-ops on the surfaces
  whose tag maps don't carry the slot.

  `:rf.sub/query-v` (the caller-supplied
  subscription query vector on `:where :sub-return` emissions) is the lookup
  key, not just an id, and on `:sensitive?`-marked subs typically carries the
  same secret material the registered schema is gating (user ids, auth tokens,
  document ids). Without redaction the failure trace re-leaks it alongside the
  failing return value the other clauses just scrubbed.

  `:explain-humanized` (the operator-readable decomposition of
  the explainer's output, per Spec 010 §Humanize-hook) is itself value-bearing:
  Malli's `malli.error/humanize` carries the failing value verbatim under its
  path-shaped output. Spec 010 §Humanize-hook §Composition with `:sensitive?`
  requires BOTH `:explain` AND `:explain-humanized` to redact symmetrically — a
  redacted-raw / leaked-humanized split would re-leak the value the `:explain`
  clause just scrubbed. The slot is built from the pre-redaction explanation at
  the emit-sites (so it carries the real humanized payload on non-sensitive
  failures) and this clause scrubs it to the sentinel on sensitive failures —
  the sentinel is present (not omitted), so the trace shape is symmetric across
  sensitive and non-sensitive surfaces and Xray's violation block, which prefers
  `:explain-humanized`, reads `:rf/redacted` rather than falling through to a
  missing slot."
  [tags]
  (-> (scrub-slots tags value-bearing-slots)
      (assoc :sensitive? true)))

(defn- redact-tags-per-slot
  "Scrub value-bearing slots under two decisions matched to their value scope:

    - `whole-sensitive?`    the ROOT / whole-schema decision
                            (`walker/schema-has-sensitive?`) — governs every
                            WHOLE-PAYLOAD slot (every value-bearing slot NOT in
                            `narrowed-slots`) and the `:sensitive?` stamp.
    - `narrowed-sensitive?` the LEAF-PRECISE decision
                            (`walker/schema-sensitive-at?` on the failing leaf)
                            — governs ONLY the slots in `narrowed-slots`, which
                            the surface has narrowed to that exact leaf.

  A WHOLE-PAYLOAD slot carries every conforming sibling, so a conforming
  `:sensitive?` sibling rides inside it; gating it on the leaf-precise decision
  would leak the sibling. A leaf-narrowed slot carries only the failing leaf,
  so the leaf-precise decision is exact there.

  Because `schema-sensitive-at?` ⊆ `schema-has-sensitive?` (a sensitive leaf
  ancestor/descendant implies the schema declares something sensitive),
  `whole-sensitive?` is the broader condition: whenever a leaf-narrowed slot is
  scrubbed the whole-payload slots are too, so `:sensitive?` is stamped (under
  `whole-sensitive?`) and `:explain` / `:received` are never left verbatim
  beside a redacted `:value`. Idempotent.

  Only the app-db hot path uses this (with `narrowed-slots` =
  `app-db-narrowed-slots`); the meta-bearing surfaces narrow nothing and use
  the whole-only `redact-tags`."
  [tags whole-sensitive? narrowed-sensitive? narrowed-slots]
  (let [whole-slots (remove narrowed-slots value-bearing-slots)]
    (cond-> tags
      whole-sensitive?    (-> (scrub-slots whole-slots)
                              (assoc :sensitive? true))
      narrowed-sensitive? (scrub-slots narrowed-slots))))

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
  "Derive the failing leaf value path from the registered explainer's output.
  Returns a vector path (possibly empty when the
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
  "Build the human-readable reason for a validation failure. Each caller
  supplies a distinctive literal tail so the production-elision gate can
  prove every dev-only surface absent from optimized bundles."
  [subject id-or-path slot-tail schema value]
  (str subject id-or-path slot-tail (pr-str schema)
       ", got " (error/type-of-value value) "."))

(defn- humanize-explain
  "Compute the operator-readable `:explain-humanized` payload from a
  raw explainer output (per Spec 010 §Humanize-hook).
  Returns the humanized shape when the `:schemas/humanize-explain!`
  late-bind hook is installed and `explanation` is non-nil; nil
  otherwise (no hook installed — non-Malli validator / adapter ns not
  required — or the explainer produced nothing).

  Call this before privacy redaction so sensitive failures retain a present
  but redacted `:explain-humanized` slot rather than losing the slot entirely.

  Failures inside the humanizer degrade silently (humanize is a
  cosmetic enrichment; a thrown humanizer can't suppress the
  failure trace itself)."
  [explanation]
  (when (some? explanation)
    (when-let [humanize-fn (late-bind/get-fn :schemas/humanize-explain!)]
      (try (humanize-fn explanation)
           (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn- emit-validation-failure!
  "Single emit seam for `:rf.error/schema-validation-failure` traces.
  A thin wrapper over `trace/emit-error!` — the `:explain-humanized`
  augmentation now happens at the call-sites (via `humanize-explain`
  folded into base-tags before redaction) so the
  privacy redaction in `redact-tags` can scrub the humanized slot
  symmetrically with `:explain`. Centralising the bare emit keeps the
  category keyword in one place; future cross-cutting tag shaping
  lands here."
  [tags]
  (trace/emit-error! :rf.error/schema-validation-failure tags))

(defn- emit-malformed-schema!
  "Single emit seam for `:rf.error/malformed-schema` traces.

  A malformed REGISTERED schema (a childless `[:vector]`, an unknown op,
  etc.) registers without error — Malli validates schema FORMS lazily, at
  validate-time. The throw must not escape to a caller's defensive pass or
  abort validation of sibling registrations.

  This emit surfaces the structural error as its OWN distinct category so
  it can never masquerade as a clean validate. `:path` / `:registered-path`
  / `:frame` are structural locator slots (no user value). The schema FORM
  itself rides under `:schema` (the offending registration the developer
  must fix); the throwing-validator message rides under `:reason`. The
  app-db value is NOT included — a malformed schema is a programming
  error, and including the value would re-open the very leak the per-entry
  isolation closes (the validator never proved sensitivity, so we cannot
  redact path-targeted; omitting the value is fail-closed).

  The same emit serves event, effect, and subscription validation. Those
  surfaces retain structural locator tags but never include a value-bearing
  slot because the validator did not establish sensitivity."
  [tags]
  (trace/emit-error! :rf.error/malformed-schema tags))

(defn- safe-explain
  "Invoke the diagnostic-only explainer without allowing it to change the
  validation verdict. A throwing explainer degrades to nil."
  [schema value]
  (try
    (validator/run-explainer schema value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- validate-entry-result
  "Run the registered validator for one `(schema, value)` entry, isolating
  a malformed-schema throw. Returns:
    - `true`               — the value conformed.
    - `false`              — a legitimate validation failure.
    - `[:malformed ex]`    — the validator THREW (a malformed registered
                             schema: childless `[:vector]`, unknown op, …).

  Catching per entry lets callers emit `:rf.error/malformed-schema`, fail
  closed, and continue validating sibling registrations."
  [validate-fn schema value]
  (try
    (boolean (validate-fn schema value))
    (catch #?(:clj Throwable :cljs :default) ex
      [:malformed ex])))

(defn- run-validation
  "Shared core of the three meta-bearing validate-*! fns (event /
  fx / sub). Performs the registered-validator deref, the
  `:schema`-on-meta lookup, the validate / explain calls, the
  sensitivity decision, and the trace emit. Returns true on pass / no
  schema / no validator; false on a logged failure.

  Parameters:
    - `reg-meta`     the registration metadata (handler / sub /
                     fx) — its `:schema` entry, when PRESENT, is the
                     declaration. Presence is KEY-presence, never value
                     truthiness (rf2-6eh5h): a present nil / false is a
                     declaration whose exact token is delegated to the
                     registered validator (the value is opaque per Spec
                     010), mirroring `validate-app-schema!`'s
                     unconditional pass-through. Only an ABSENT key
                     means no declaration.
    - `value`        the value being checked (event vector, sub
                     return value, fx args).
    - `walk-schema?` boolean — when true and the validator fails,
                     consult the schema's per-slot `:sensitive?` walker
                     before emitting. An event vector
                     isn't itself `:map`-shaped but its `:cat`/`:catn` payload
                     commonly is (a login schema marks `:password` sensitive),
                     and fx / sub-return validate a map value directly.
                     The decision here is the whole-schema
                     `schema-has-sensitive?` (NOT the leaf-precise
                     `schema-sensitive-at?`): every value-bearing slot on these
                     surfaces carries the WHOLE checked value, so a conforming
                     sensitive sibling rides inside it and the redaction scope
                     must match the carried-value scope (see the `:else`
                     branch's PER-SLOT DECISION SCOPING note).
    - `build-base-tags`  `(fn [schema explanation] -> map)` — produces
                     the per-fn tag map (`:where`, `:reason`, etc.)
                     EXCLUDING any sensitivity stamping. Also the source
                     of the structural locator slots for the malformed-
                     schema trace: called with a nil
                     explanation, then stripped of the value-bearing slots,
                     so the malformed-schema trace reuses the surface's own
                     `:where` / id / `:frame` shape without duplicating it.

  This primitive isolates two diagnostic failure modes:

    - A malformed registered `:schema` (childless `[:vector]`, unknown op)
      makes the validator THROW. Caught per-call via `validate-entry-result`;
      a distinct `:rf.error/malformed-schema` trace fires and the fn returns
      `false`, so each caller applies its normal recovery (skip handler /
      skip fx / replace sub return) instead of the silent throw-as-pass.
    - A throwing explainer no longer aborts trace construction — the
      explainer is invoked through `safe-explain`, which degrades a throw
      to a nil explanation and preserves the `false` verdict.

  Reachability: every call-site lives inside the outermost
  `(if interop/debug-enabled? ...)` gate of its public wrapper.
  Closure's reachability proof under :advanced + goog.DEBUG=false
  finds every call-site dead and DCEs this fn — along with every
  literal reason string and tag keyword passed through it.

  The registered validator is dereferenced once at the gate. The canonical
  redacted slots
  (`:value`, `:received`, `:explain`, `:explain-humanized`,
  `:rf.fx/args`, `:rf.sub/query-v`) all live on the central
  `redact-tags` cond->. The per-surface clauses are no-ops on the
  surfaces whose base-tags don't contain the slot, so a single
  redactor covers every meta-bearing emit site. The
  `:explain-humanized` slot is folded into base-tags here (from the
  RAW explanation via `humanize-explain`, before redaction) so the
  redactor scrubs it symmetrically with `:explain` on sensitive
  failures rather than the humanizer being handed an already-redacted
  `:explain` and silently dropping the slot."
  [reg-meta value walk-schema? build-base-tags continue?]
  (if-not (continue?)
    :rf/stale-incarnation
    (if-let [validate-fn @validator/validator-fn]
      ;; KEY-presence, not value truthiness (rf2-6eh5h): a present nil /
      ;; false `:schema` is delegated verbatim — default Malli then throws
      ;; on the non-schema form and the malformed isolation below fails
      ;; CLOSED; a custom validator interprets its own token.
      (if (contains? reg-meta :schema)
        ;; Isolate malformed schemas before a caller can treat a throw as pass.
        (let [schema             (:schema reg-meta)
              validation-result (validate-entry-result validate-fn schema value)]
          ;; The registered validator is callback-bearing. A destroy+return or
          ;; destroy+throw makes its result inert before explanation, schema
          ;; walking, tag construction, or diagnostic delivery can begin.
          (if-not (continue?)
            :rf/stale-incarnation
            (cond
              ;; Conformed.
              (true? validation-result)
              true

              ;; Malformed registered schema (validator threw). Surface a
              ;; DISTINCT `:rf.error/malformed-schema` trace built from the
              ;; surface's own structural slots (`:where` / id / `:frame`),
              ;; stripped of the value-bearing slots (the validator never
              ;; proved sensitivity — omitting the value is fail-closed,
              ;; mirroring `validate-app-schema!`). Return false so the caller
              ;; runs its normal recovery instead of the swallowed pass.
              (and (vector? validation-result)
                   (= :malformed (first validation-result)))
              (let [validator-error (second validation-result)
                    reason          #?(:clj  (.getMessage ^Throwable validator-error)
                                       :cljs (ex-message validator-error))
                    ;; build-base-tags called with a nil explanation, then
                    ;; stripped of value-bearing slots — no value leaks into a
                    ;; malformed-schema trace.
                    base-tags       (apply dissoc (build-base-tags schema nil)
                                           value-bearing-slots)]
                (if-not (continue?)
                  :rf/stale-incarnation
                  (do
                    (emit-malformed-schema!
                      (assoc base-tags
                             :schema   schema
                             :reason   (str "Registered schema " (pr-str schema)
                                            " is malformed and could not be "
                                            "evaluated: " reason)
                             ;; Preserve the surface-specific recovery the
                             ;; caller supplied; default only when absent.
                             :recovery (get base-tags :recovery :no-recovery)))
                    (if (continue?) false :rf/stale-incarnation))))

              ;; Legitimate validation failure — the existing emit path.
              :else
              (let [;; A diagnostic explainer cannot change the false verdict.
                    explanation (safe-explain schema value)]
                (if-not (continue?)
                  :rf/stale-incarnation
                  (let [
                ;; Meta-bearing
                ;; surfaces (event / fx / sub) carry the WHOLE checked
                ;; value in EVERY value-bearing slot — `:value` / `:received` /
                ;; `:explain` / `:explain-humanized` / `:rf.fx/args` /
                ;; `:rf.sub/query-v` are all the whole event-vector /
                ;; fx-args / sub-return value (nothing here is narrowed to the
                ;; failing leaf the way the app-db path narrows `:value`). So
                ;; the redaction decision MUST be scoped to the WHOLE schema
                ;; (`schema-has-sensitive?`), NOT the leaf-precise
                ;; `schema-sensitive-at?`. A failing non-sensitive sibling (e.g.
                ;; `:age`) cleared redaction while a CONFORMING sensitive
                ;; sibling (e.g. `:password` / `:jwt`) rode unredacted inside
                ;; every whole-payload slot. Leaf precision does not apply here
                ;; because there is no narrowed slot:
                ;; the whole payload carries the sibling. (It DOES still apply
                ;; to the app-db `:value` slot, which is genuinely leaf-narrowed
                ;; — see `validate-app-schema!`.) `walk-schema?` stays the knob
                ;; for whether to consult the walker at all.
                ;; A compiled or opaque schema (a non-vector,
                ;; non-keyword `m/schema` object) cannot be walked, so the
                ;; per-slot `:sensitive?` flag Malli honoured for the failure
                ;; is INVISIBLE to the walker. Fail CLOSED: redact as if
                ;; sensitive. Otherwise an opaque schema carrying a
                ;; `{:sensitive? true}` slot leaks the failing value verbatim
                ;; while the equivalent vector form redacts. A bare keyword is
                ;; flag-free and not opaque. `schema-opaque?` only classifies
                ;; the root form; a
                ;; vector-form schema with a NESTED opaque child (e.g. a
                ;; `:cat`/`:map` element that is itself a compiled `m/schema`
                ;; value) is just as unintrospectable, so the whole-payload
                ;; check uses the recursive `schema-has-opaque-child?`.
                        sensitive?  (and walk-schema?
                                          (or (walker/schema-has-sensitive? schema)
                                              (walker/schema-has-opaque-child? schema)))
                ;; When the schema declares any `:large?`
                ;; slot AND the failure is not sensitive (sensitive wins),
                ;; elide the value-bearing slots to the `:rf.size/large-elided`
                ;; marker. An opaque schema is already handled fail-closed
                ;; SENSITIVE above (which subsumes large), so `:large?` is only
                ;; consulted for a walkable schema here.
                        large?      (and walk-schema?
                                          (not sensitive?)
                                          (walker/schema-has-large? schema))
                ;; Humanize from the raw explanation here,
                ;; before redaction, and fold the slot into base-tags so
                ;; `redact-tags` scrubs it symmetrically with `:explain`
                ;; on sensitive failures (sentinel present, not omitted).
                        humanized   (humanize-explain explanation)]
                    ;; The optional humanizer is callback-bearing too.
                    (if-not (continue?)
                      :rf/stale-incarnation
                      (let [base-tags (cond-> (build-base-tags schema explanation)
                                        (some? humanized)
                                        (assoc :explain-humanized humanized))
                            tags      (cond-> base-tags
                                        sensitive? redact-tags
                                        large?     (elide-large-slots value))]
                        (if-not (continue?)
                          :rf/stale-incarnation
                          (do
                            (emit-validation-failure! tags)
                            (if (continue?) false :rf/stale-incarnation)))))))))))
        true)
      true)))

(defn validate-app-schema!
  "After a handler commits :db, walk every registered app-schema for the
  named frame and validate the post-state. Failures trace as
  :rf.error/schema-validation-failure with the registered explainer's
  output attached.

  DEV-ONLY SURFACE — and this is the consumer-visible consequence, not
  just an internal note. The whole body sits inside a
  `(if interop/debug-enabled? ... true)` gate, so in a production build
  this fn returns `true` without walking anything. `reg-app-schema` still
  registers, but nothing checks the registered schemas, and the
  candidate-rejection contract of Spec 010 §Per-step recovery row 4 does
  not apply: a candidate that violates a registered app-db schema
  INSTALLS, silently. Production app-db integrity is the handler's job;
  the production-side validation surfaces are the
  `:rf.schema/at-boundary` interceptor and the
  `:schemas/validate-with-registered-fn` seam, neither of which is gated
  this way. Per Spec 010 §Production builds and the rf2-bkvu5 ruling.

  Per Spec 010 §Per-frame schemas only the named frame's schemas are
  walked — schemas registered against sibling frames are ignored.

  Validation routes through the registered validator/explainer functions.
  When `set-schema-validator!` has been called with `nil`
  this fn is a hard no-op for every schema in the frame.

  Arities:
    (validate-app-schema! db)                            ;; current frame
    (validate-app-schema! db event-id)                   ;; current frame, named handler
    (validate-app-schema! db event-id frame-id)          ;; explicit frame
    (validate-app-schema! db event-id frame-id continue?) ;; explicit owner fence

  event-id (optional) names the handler whose commit prompted the
  failure — surfaced as :failing-id in the error tags.

  continue? (the 4-arity, and the shape the router's late-bind call uses —
  it invokes the hook as `(validate db-after event-id frame continue?)`) is
  the exact-owner continuation predicate: between entries and around every
  authored validator callback it is consulted, and once it reports false the
  walk stops and returns :rf/stale-incarnation — the dequeued event lost its
  exact frame incarnation, so its validation verdict is inert. The 3-arity
  derives it from the current event-owner token (or `(constantly true)`
  outside any owned event).

  Returns:
    true   — every registered schema conformed (or no validator /
             no schemas registered for the frame / debug elided).
    false  — at least one schema failed; a trace event was emitted
             for every failing entry. The router consumes this signal
              to roll back the :db effect to the pre-handler value.

  Structurally distinct from the three meta-bearing validate-*! fns
  (event / fx / sub): walks N schemas via doseq, has no
  single `:schema`-on-meta lookup, and emits a trace per failure (rather
  than at-most-one). Returns a single boolean conjoining every entry's
  result so the caller can decide rollback deterministically — but
  every failing schema is still surfaced as its own trace so consumers
  see the full set."
  ([db] (validate-app-schema! db nil (frame/current-frame)))
  ([db event-id] (validate-app-schema! db event-id (frame/current-frame)))
  ([db event-id frame-id]
   (if-let [owner-token (frame/current-event-owner-token)]
     (validate-app-schema! db event-id frame-id
                           #(frame/event-continuation-live? frame-id owner-token))
     (validate-app-schema! db event-id frame-id (constantly true))))
  ([db event-id frame-id continue?]
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
   ;; Dereference the validator once outside the per-schema loop.
   (if interop/debug-enabled?
     (if-let [validate-fn @validator/validator-fn]
       ;; reduce + atomic short-circuit replaced doseq so we can emit
       ;; a trace per failure (full surface for consumers) AND return
       ;; a single conjoined boolean (single signal for the rollback
       ;; gate). Pass-state stays `true` only when every entry passed.
        (loop [entries (seq (storage/frame-schema-entries frame-id))
               ok?     true]
          (if-not (continue?)
            :rf/stale-incarnation
            (if-let [[registered-path registration-metadata] (first entries)]
            (let [registered-value  (get-in db registered-path)
                  schema            (:schema registration-metadata)
                  ;; A malformed schema cannot abort sibling validation or
                  ;; escape to a caller that treats defensive throws as pass.
                  validation-result (validate-entry-result validate-fn schema registered-value)]
              (cond
                ;; The validator callback may synchronously destroy the exact
                ;; owner. Its result is inert and no sibling/explainer/emit runs.
                (not (continue?))
                :rf/stale-incarnation

               ;; Conformed — carry the running pass-state forward.
               (true? validation-result)
               (recur (next entries) ok?)

               ;; Malformed registered schema (validator threw). Surface a
               ;; DISTINCT `:rf.error/malformed-schema` trace and fail
               ;; CLOSED (`ok? false` → the router rolls back; we do NOT
               ;; install blind). Continue to the sibling entries so one
               ;; bad schema does not disable validation frame-wide.
               (and (vector? validation-result)
                    (= :malformed (first validation-result)))
               (let [validator-error (second validation-result)
                     reason          #?(:clj  (.getMessage ^Throwable validator-error)
                                        :cljs (ex-message validator-error))]
                 (emit-malformed-schema!
                   (cond-> {:where           :app-db
                            :path            registered-path
                            :registered-path registered-path
                            :schema          schema
                            :frame           frame-id
                            :reason          (str "Registered app-db schema at path "
                                                  registered-path " is malformed and could "
                                                  "not be evaluated: " reason)
                            :rollback?       true
                            :recovery        :no-recovery}
                     event-id (assoc :failing-id event-id)))
                 (if (continue?)
                   (recur (next entries) false)
                   :rf/stale-incarnation))

               ;; Legitimate validation failure — the existing emit path.
               :else
               (do
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
                 ;; Conservative fallback: when no leaf path is extractable
                 ;; (non-Malli explainer or missing explanation), emit the
                 ;; registered root as `:path` and consult
                 ;; `schema-has-sensitive?` for the redaction decision.
                 ;; The `:registered-path` tag always carries the
                 ;; registration root so tooling can reach it
                 ;; regardless of whether path narrowing succeeded.
                 ;;
                 ;; Per Spec 010 §`:sensitive?`, the redaction decision is
                 ;; The redaction decision is PER-SLOT-SCOPED (see the
                 ;; `let` below): the path-targeted check
                 ;; (`schema-sensitive-at?`) governs only the slots this
                 ;; surface NARROWS to the failing leaf — `:value`, plus
                 ;; the `:path` / `:reason` leaf coordinates — so a
                 ;; failure at a non-sensitive leaf whose SIBLING is
                 ;; sensitive does not redact THOSE (the precise-narrowing
                 ;; win; ancestor- OR descendant-sensitive at the leaf
                 ;; counts). The WHOLE-PAYLOAD slots (`:explain` /
                 ;; `:explain-humanized`, which carry the whole registered value)
                 ;; stay under the coarse `schema-has-sensitive?` root
                 ;; check because a conforming sensitive sibling rides inside
                 ;; them.
                 ;;
                 ;; The trace carries `:rollback? true` (consistent with depth-exceeded;
                 ;; reuses the existing `:recovery :no-recovery`
                 ;; vocabulary rather than minting a new enum value).
                 ;; The router consumes the loop's final boolean to
                 ;; perform the actual container restoration.
                 ;; Explainer failure is diagnostic only and cannot change the
                 ;; false verdict contributed by this entry.
                  (let [explanation (safe-explain schema registered-value)]
                    (if-not (continue?)
                      :rf/stale-incarnation
                      (let [in-path     (failing-in-path explanation)
                             leaf-value (if in-path
                                          (get-in registered-value in-path)
                                          registered-value)
                       ;; The app-db
                       ;; hot path is the ONLY surface that NARROWS a slot:
                       ;; `:value` is `(get-in registered-value in-path)` — just the
                       ;; failing leaf. So it carries TWO redaction decisions
                       ;; scoped to the two value-scopes it ships:
                       ;;
                       ;;   - `leaf-sensitive?` — the LEAF-PRECISE check
                       ;;     (`schema-sensitive-at?` at the failing `:in`;
                       ;;     whole-schema fallback when no `:in` extractable).
                       ;;     A failure at a non-sensitive leaf whose sibling is sensitive is
                       ;;     NOT redacted — the leaf value genuinely doesn't
                       ;;     contain the sibling. This decision governs the
                       ;;     NARROWED `:value` slot AND the `:path` / `:reason`
                       ;;     sanitization (both keyed to the failing leaf).
                       ;;
                       ;;   - `whole-sensitive?` — the ROOT / whole-schema check
                       ;;     (`schema-has-sensitive?`). This governs the
                       ;;     WHOLE-PAYLOAD slots `:explain` /
                       ;;     `:explain-humanized`, which carry the WHOLE
                       ;;     `registered-value` verbatim (Malli's explanation root
                       ;;     `:value` is the whole input map / the humanized
                       ;;     decomposition is path-shaped over it). A
                       ;;     Conforming sensitive siblings such as a valid jwt
                       ;;     rides inside `:explain`; gating `:explain` on the
                       ;;     leaf-precise `[:name]` decision (sibling-blind)
                       ;;     would leak through a leaf-only decision. The root
                       ;;     check catches them. `whole-sensitive?` also stamps
                       ;;     the top-level `:sensitive?` (it is the broader
                       ;;     decision — `leaf-sensitive?` ⊆ `whole-sensitive?`).
                       ;; A compiled or opaque schema (a
                       ;; non-vector, non-keyword `m/schema` object) cannot be
                       ;; walked, so a per-slot `:sensitive?` Malli honoured is
                       ;; invisible. Fail CLOSED on BOTH the whole-payload and
                       ;; the leaf decision: redact every value-bearing slot as
                       ;; sensitive. (`reg-app-schema` also warns once via
                       ;; `:rf.warning/schema-walker-opaque` at registration;
                       ;; this is the redaction half of the same fail-closed
                       ;; posture.) A schema whose root is
                       ;; walkable vector-form EDN can still embed a NESTED
                       ;; opaque child (e.g. `[:map [:token (m/schema
                       ;; [:string {:sensitive? true}])]]`); `schema-opaque?`
                       ;; alone only sees the root, so both decisions now
                       ;; route through `schema-sensitive-at?`, which fails
                       ;; closed on a nested opaque node exactly where it
                       ;; sits — the failing LEAF's own opacity (or an
                       ;; opaque ancestor/descendant along its path) for
                       ;; `leaf-sensitive?`, and the WHOLE schema's opacity
                       ;; anywhere for `whole-sensitive?` (`nil` path — the
                       ;; same whole-schema scope `schema-has-sensitive?`
                       ;; already used). Scoping `leaf-sensitive?` to the
                       ;; failing path (rather than "opaque anywhere in the
                       ;; schema") preserves leaf precision: an opaque sibling must
                       ;; not taint an unrelated non-opaque leaf's `:value`.
                       leaf-sensitive?  (walker/schema-sensitive-at? schema in-path)
                       whole-sensitive? (walker/schema-sensitive-at? schema nil)
                       ;; A `:large?` non-sensitive schema
                       ;; elides the value-bearing slots to the size marker
                       ;; (sensitive wins; opaque is fail-closed sensitive,
                       ;; subsuming large). `whole-large?` governs every
                       ;; value-bearing slot uniformly here — the marker is the
                       ;; same shape on the narrowed `:value` (built from the
                       ;; failing leaf) and the whole-payload `:explain`.
                       whole-large?     (and (not whole-sensitive?)
                                             (walker/schema-has-large? schema))
                       ;; Some `:in`
                       ;; segments are value-bearing, not structural: a
                       ;; `:set` failure's segment is the failing ELEMENT
                       ;; VALUE (Malli has no positional index for a set), a
                       ;; sensitive `:map-of` KEY is the secret used as the
                       ;; key, and a scalar in the fail-closed tail past an
                       ;; ambiguous wrapper may be a `:set` scalar element.
                       ;; Concatenating the raw `:in` into the structural
                       ;; `:path` tag would ship those verbatim — defeating
                       ;; the redaction the `:value` / `:explain` slots
                       ;; apply, and re-leaking through `:reason` (built from
                       ;; the same path). The `:path` / `:reason` sanitization
                       ;; is keyed to the failing leaf, so it scopes to
                       ;; `leaf-sensitive?` (the path segments are the failing
                       ;; leaf's coordinates — a conforming sibling's secret
                       ;; never appears in THIS leaf's path). When the leaf is
                       ;; sensitive, scrub the value-bearing segments out of
                       ;; `:path` via `sanitize-sensitive-path` (navigable
                       ;; `:vector` / `:tuple` / `:map` segments + NON-sensitive
                       ;; `:map-of` keys are kept so `:path` stays a useful
                       ;; locator for those shapes).
                            trace-in-path   (if (and in-path leaf-sensitive?)
                                              (walker/sanitize-sensitive-path schema in-path)
                                              in-path)
                            trace-path      (if trace-in-path
                                              (vec (concat registered-path trace-in-path))
                                              registered-path)]
                       ;; Humanize from the raw
                       ;; explanation, before redaction, so the
                       ;; `:explain-humanized` slot is scrubbed
                       ;; symmetrically with `:explain` on sensitive
                       ;; app-db failures (sentinel present, not omitted).
                    (let [humanized (humanize-explain explanation)]
                      (if-not (continue?)
                        :rf/stale-incarnation
                        (let [base-tags   (cond-> {:where           :app-db
                                                   :path            trace-path
                                                   :registered-path registered-path
                                                   :value           leaf-value
                                                   :frame           frame-id
                                                   :explain         explanation
                                                   :reason          (reason-string
                                                                      "App-db at path "
                                                                      trace-path
                                                                      " failed schema "
                                                                      schema leaf-value)
                                                   :rollback?       true
                                                   :recovery        :no-recovery}
                                            event-id          (assoc :failing-id event-id)
                                            (some? humanized) (assoc :explain-humanized humanized))
                       ;; PER-SLOT DECISION SCOPING: `:value` (narrowed) under
                       ;; the leaf decision; `:explain` / `:explain-humanized`
                       ;; (whole registered value) under the root decision. When the
                       ;; schema is `:large?` and not
                       ;; sensitive) the value-bearing slots elide to the size
                       ;; marker (built from the whole `registered-value`) — sensitive
                       ;; wins, so this arm only fires when neither the leaf nor
                       ;; the whole-payload decision redacted.
                              tags        (cond-> (redact-tags-per-slot base-tags
                                                                        whole-sensitive?
                                                                        leaf-sensitive?
                                                                        app-db-narrowed-slots)
                                            whole-large? (elide-large-slots registered-value))]
                          (if-not (continue?)
                            :rf/stale-incarnation
                            (do
                              (emit-validation-failure! tags)
                              (if (continue?)
                                (recur (next entries) false)
                                :rf/stale-incarnation))))))))))))
            ok?)))
        true)
      true)))

(defn validate-event!
  "Per Spec 010 §Validation order step 1 — before an event handler runs,
  validate the event vector against any :schema on the handler's
  metadata. Failures emit `:rf.error/schema-validation-failure :where
  :event`; the caller skips the handler (recovery: `:no-recovery`).
  Returns true/false per the `run-validation` contract.

  The optional frame is stamped on the trace so runtime calls are captured in
  the same epoch as their dispatch. Direct callers may omit it.

  Event validation consults per-slot `:sensitive?` declarations. An event schema is
  not itself `:map`-shaped, but its payload commonly IS: a login schema
  `[:cat [:= :auth/login] [:map [:password {:sensitive? true} :string]]]`
  marks the password slot sensitive. `walker/schema-has-sensitive?` walks
  the whole schema form (the `:cat` container
  descends into its `:map` payload child), so a per-slot `:sensitive?`
  anywhere in the event schema (incl. container-level props) now drives
  redaction; non-sensitive event failures still ride verbatim (the walker
  reports nothing → no redaction)."
  ([event-id event handler-meta]
   (validate-event! event-id event handler-meta nil (constantly true)))
  ([event-id event handler-meta frame]
   (validate-event! event-id event handler-meta frame (constantly true)))
  ([event-id event handler-meta frame continue?]
   (if interop/debug-enabled?
     (run-validation
       handler-meta
       event
       true   ;; consult the event schema's per-slot `:sensitive?` walker
       (fn [schema explanation]
         (cond-> {:where      :event
                  :event-id   event-id
                  :failing-id event-id
                  :schema-id  event-id
                  :received   event
                  :value      event
                  :explain    explanation
                  :reason     (reason-string "Event " event-id
                                             " payload failed schema "
                                             schema event)
                  :recovery   :no-recovery}
            frame (assoc :frame frame)))
       continue?)
     true)))

(defn validate-sub!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :schema on the sub's metadata.
  Failures emit `:rf.error/schema-validation-failure :where
  :sub-return`; the caller replaces the value with the default (nil)
  per the `:replaced-with-default` recovery. Returns true/false per
  the `run-validation` contract.

  The optional frame is stamped on the trace so runtime calls join the
  in-flight epoch. Direct callers may omit it."
  ([sub-id query-v value sub-meta]
   (validate-sub! sub-id query-v value sub-meta nil (constantly true)))
  ([sub-id query-v value sub-meta frame]
   (validate-sub! sub-id query-v value sub-meta frame (constantly true)))
  ([sub-id query-v value sub-meta frame continue?]
   (if interop/debug-enabled?
     (run-validation
       sub-meta
       value
       true   ;; consult schema's per-slot `:sensitive?` walker on fail
       (fn [schema explanation]
         (cond-> {:where          :sub-return
                  :rf.sub/id      sub-id
                  :failing-id     sub-id
                  :schema-id      sub-id
                  :rf.sub/query-v query-v
                  :received       value
                  :value      value
                  :explain    explanation
                  :reason     (reason-string "Subscription " sub-id
                                             " return value failed schema "
                                             schema value)
                  :recovery   :replaced-with-default}
            frame (assoc :frame frame)))
       continue?)
     true)))

;; Recordable cofx values use the always-on contract in `re-frame.cofx`.

(defn validate-fx!
  "Per Spec 010 §Validation order step 5 — before an fx handler runs,
  validate its args against any :schema on the fx's metadata. Failures
  emit `:rf.error/schema-validation-failure :where :fx-args`; per
  Spec 010 §Per-step recovery row 5 the caller skips the offending fx
  only (recovery: `:skipped`) — sibling fx in the same `:fx` vector
  continue to run, and downstream queued events still drain. Returns
  true/false per the `run-validation` contract.

  The per-surface `:rf.fx/args` slot is redacted by the central
  `redact-tags` path. Spec 010 §`:sensitive?` lists
  `:rf.fx/args` alongside `:value` / `:received` / `:explain` as the
  canonical redacted slots (and `:rf.sub/query-v` on the sub-return
  surface).

  The optional frame is stamped on the trace so runtime calls join the
  in-flight epoch. Direct callers may omit it."
  ([fx-id event-id args fx-meta]
   (validate-fx! fx-id event-id args fx-meta nil (constantly true)))
  ([fx-id event-id args fx-meta frame]
   (validate-fx! fx-id event-id args fx-meta frame (constantly true)))
  ([fx-id event-id args fx-meta frame continue?]
   (if interop/debug-enabled?
     (run-validation
       fx-meta
       args
       true   ;; consult schema's per-slot `:sensitive?` walker on fail
       (fn [schema explanation]
         (cond-> {:where      :fx-args
                  :rf.fx/id   fx-id
                  :rf.fx/args args
                  :failing-id fx-id
                  :schema-id  fx-id
                  :received   args
                  :value      args
                  :explain    explanation
                  :reason     (reason-string "Effect " fx-id
                                             " args failed schema "
                                             schema args)
                  :recovery   :skipped}
           event-id (assoc :event-id event-id)
            frame    (assoc :frame frame)))
       continue?)
     true)))

;; ---- public boundary-validation entry points ------------------------------
;;
;; The boundary-validation interceptor (`re-frame.spec/validate-at-boundary-interceptor`,
;; interceptor id `:rf.schema/at-boundary`)
;; runs `:schema` validation on a handler at production-build time —
;; outside the `interop/debug-enabled?` gate that guards the
;; hot-path validate-*! fns above. Per Spec 010 §Production builds the
;; boundary interceptor MUST route through the same registered validator
;; the dev-mode hot path uses (so a substituted validator covers both
;; surfaces). This namespace publishes `validate-with-registered-fn` as
;; the call the interceptor reaches for via the
;; `:schemas/validate-with-registered-fn` late-bind hook (the schemas
;; artefact is optional, so the interceptor cannot
;; statically `:require [re-frame.schemas]`).
;;
;; Contract: returns true on conform; false on fail (incl. a malformed
;; schema — fail CLOSED); true (pass) when no validator is registered.
;; Does NOT emit a trace — the boundary interceptor is responsible for
;; emitting :rf.error/schema-validation-failure :where :event with the
;; appropriate envelope. Pure check surface.

(defn validate-with-registered-fn
  "Apply the registered validator to `(schema, value)`. Public seam for
  the boundary-validation interceptor. Returns true on
  conform; false on fail; true when no validator is registered (the
  call-site treats no-validator as no-validation, mirroring the hot
  path).

  A structurally malformed registered schema makes the validator throw.
  This seam isolates that
  throw and returns `false` (fail CLOSED — the boundary interceptor then
  skips the handler) rather than letting it propagate to the interceptor's
  defensive `(catch … true)`, which would coerce the throw to a validation
  PASS and run the handler on an unvalidated boundary payload. Mirrors the
  dev-time `run-validation` / `validate-app-schema!` per-entry isolation.
  A `nil` validator (validation disabled) still passes — `run-validator`
  returns true and never throws.

  Does NOT consult `interop/debug-enabled?` — the boundary interceptor
  runs in production by design."
  [schema value]
  (try
    (boolean (validator/run-validator schema value))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn explain-with-registered-fn
  "Apply the registered explainer to `(schema, value)`. Companion to
  `validate-with-registered-fn` for the boundary-validation
  interceptor. Returns the explanation map / data on fail;
  nil when the schema conforms or no explainer is registered.

  A throwing explainer degrades to nil because diagnostic enrichment must
  never change the verdict."
  [schema value]
  (try
    (validator/run-explainer schema value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn redact-validation-tags
  "Shared schema-aware redaction seam for validation-failure
  trace emitted outside this namespace. Given the registered
  `schema` the failing value was checked against and the failure-trace
  `tags` a caller built, return the tags with the value-bearing slots
  (`:value` / `:received` / `:explain` / `:explain-humanized` /
  `:rf.fx/args` / `:rf.sub/query-v` — the canonical `value-bearing-slots`)
  scrubbed to `:rf/redacted` and `:sensitive? true` stamped when the
  schema declares ANY slot `:sensitive?` (e.g. a payload map
  `[:map [:password {:sensitive? true} :string]]`); otherwise the tags
  ride back verbatim.

  Framework-side validation-failure emit sites route through this redactor so
  `:sensitive?` redaction logic lives in a single place rather than
  being re-derived ad-hoc per surface. The dev-time hot-path emits
  (`validate-event!` / `-fx!` / `-sub!` / `validate-app-schema!`)
  reach the same `redact-tags` core directly via `run-validation`; the
  off-namespace emit sites — the production boundary interceptor
  (`re-frame.spec`), machine `:data` validation
  (`re-frame.machines.data-validation`), the `:sub-override` validation
  path (`re-frame.subs`), flow-output validation (`re-frame.flows`), and
  the recordable-coeffect `:rf.error/cofx-value-invalid` emit
  (`re-frame.cofx`)
  — reach it through the `:schemas/redact-validation-tags` late-bind
  hook and fall through verbatim when the hook is unbound (schemas
  artefact absent → no schema to redact against).

  The decision fails closed on a compiled or opaque schema (a
  non-vector, non-keyword `m/schema` object the walker cannot introspect):
  it redacts as if sensitive, because Malli may have honoured a
  `{:sensitive? true}` slot the walker cannot see — without this an opaque
  schema's failure leaks verbatim while the vector form redacts. The same
  fail-closed posture applies when the opaque value is
  nested inside an otherwise-walkable vector-form schema (`schema-opaque?`
  alone only sees the root) — `schema-has-opaque-child?` catches both.

  A `:large?`-flagged, non-sensitive schema elides the
  value-bearing slots to the `:rf.size/large-elided` marker (sensitive wins;
  the opaque fail-closed branch is sensitive, which subsumes large). `tags`
  carries the whole checked value under `:value` (the shared off-box slot),
  so the marker is built from `(:value tags)`.

  Pure; `redact-tags` is idempotent so a double-call (or a call on a
  tags map a path-based pre-scrub already touched) is safe."
  [schema tags]
  (let [sensitive? (or (walker/schema-has-sensitive? schema)
                       (walker/schema-has-opaque-child? schema))
        large?     (and (not sensitive?)
                        (walker/schema-has-large? schema))]
    (cond-> tags
      sensitive? redact-tags
      large?     (elide-large-slots (:value tags)))))
