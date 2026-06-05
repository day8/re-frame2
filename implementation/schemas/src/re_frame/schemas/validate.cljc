(ns re-frame.schemas.validate
  "Validation entry points (Spec 010 §Validation order steps 1-6).

  Owns the five dev-time validate-*! fns the framework calls at the
  locked validation sites:

    - validate-event!        — pre-handler (event vector vs handler :schema)
    - validate-cofx!         — post-injection (cofx value vs cofx :schema)
    - validate-fx!           — pre-fx-handler (fx args vs fx :schema)
    - validate-app-schema!   — post-handler-commit (frame's app-schemas)
    - validate-sub!          — post-sub-recompute (return value vs sub :schema)

  The metadata key is `:schema` (canonical per rf2-ieu0i).

  Per rf2-s2jgz (audit-of-audits #20) the family is named on the
  kind axis — validate-event!, validate-cofx!, validate-fx!,
  validate-sub! and validate-app-schema!. The earlier
  validate-app-db! / validate-sub-return! names were renamed for
  symmetry with their siblings.

  Also owns the production-side boundary-validation seam
  (`validate-with-registered-fn` / `explain-with-registered-fn`) that
  the boundary-validation interceptor (`re-frame.spec`, rf2-r2uh)
  reaches via the schemas-side late-bind hook.

  Per rf2-s7s6j the four meta-bearing validate-*! fns (event / cofx /
  fx / sub) share a single core via the private
  `run-validation` primitive — each public fn is a thin wrapper that
  contributes only its registration-meta source, its checked value,
  its sensitivity-source check, its tag shape (`:where`,
  `:reason`, etc.), and any fx-specific post-redaction step.
  `validate-app-schema!` stays a sibling of the four; it walks N schemas
  via doseq (no single :schema lookup, no true/false return contract)
  and so doesn't share the wrapper's shape.

  Per Spec 009 §Production builds every dev-time validate-*! body lives
  inside an `(if interop/debug-enabled? ...)` gate as the OUTERMOST
  form so :advanced+goog.DEBUG=false DCE-elides every reason string,
  keyword, validator deref, and trace call. The private
  `run-validation` primitive is reachable only from those gated arms
  — when every call-site is dead, Closure's reachability proof DCEs
  the primitive itself, along with every literal reason string passed
  through it.

  Per Spec 010 §Non-Malli validators the validator/explainer
  are pluggable via the registered atoms in `re-frame.schemas.validator`;
  when none is registered every fn here returns true (pass) without
  inspecting the schema.

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces. The emit-sites redact the failing value before
  stamping a trace event when the schema slot at the failing path
  (or a containing slot) carries `:sensitive? true` in its Malli
  props. Sensitivity is path-marked at the schema slot only — the
  removed handler-meta `:sensitive?` registration-metadata fallback
  no longer applies (see the NOTE below).
  The substitution sentinel is `:rf/redacted` (the framework-reserved
  keyword per Spec 009 §Privacy). The trace event's `:tags`
  map is stamped with `:sensitive? true` so consumers can route on it
  (until rf2-isdwf's top-level hoisting lands in core).

  The value-bearing slots are redacted (`:value`, `:received`,
  `:explain`, `:explain-humanized`, plus `:rf.fx/args` on
  `:where :fx-args` emissions and `:rf.sub/query-v` on
  `:where :sub-return` emissions — see `redact-tags`); the structural /
  categorical slots (`:path`, `:failing-id`, `:schema-id`, `:reason`)
  are kept — consumers need them to locate the broken slot without
  leaking user data. Per rf2-qhq3f `:explain-humanized` (the
  operator-readable decomposition of the explainer's output) is
  value-bearing too — it carries the failing value verbatim under
  Malli's path-shaped humanize output — so it redacts symmetrically
  with `:explain` (present as the `:rf/redacted` sentinel on sensitive
  failures, not omitted).

  Per rf2-ss06u.1 the `:path` tag's normally-structural segments have one
  value-bearing carve-out: a `:set` failure's segment is the failing
  ELEMENT VALUE itself (Malli has no positional index for a set). On a
  sensitive `:set`-nested failure `validate-app-schema!` scrubs that
  segment to `:rf/redacted` via `walker/sanitize-sensitive-path` so the
  sensitive element (and any sibling secrets in it) never ships verbatim
  in `:path` — navigable `:vector` / `:map-of` / `:tuple` / map segments
  stay intact so `:path` remains a `get-in` locator for those shapes.

  Per rf2-ss06u.3 a structurally MALFORMED registered schema (childless
  `[:vector]`, unknown op) makes the registered validator THROW at
  validate-time (Malli validates schema forms lazily). `validate-app-schema!`
  isolates that throw PER-ENTRY, emits the distinct
  `:rf.error/malformed-schema` category, fails CLOSED (in-band `false` →
  the router rolls back; it does NOT install the unvalidated commit), and
  keeps validating the frame's sibling schemas — so one bad schema can
  never masquerade as a clean validate (the prior router `(catch … true)`
  swallow) nor disable post-commit validation frame-wide.

  Per rf2-4fbsd the emit-sites carry two slots for the failing value
  (`:value` and `:received`, per Spec 010 §`:sensitive?`) and one slot
  for the registered explainer's output (`:explain`). The earlier
  `:event` (duplicate of `:received`) and `:malli-error` (duplicate of
  `:explain`) tags have been dropped — consumers reach for `:received`
  / `:value` / `:explain`."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator]
            [re-frame.schemas.walker :as walker]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; The `:rf/redacted` privacy sentinel emitted by validation traces for
;; slots matching the `:sensitive?` predicate. Per Spec 009 §Privacy —
;; the framework-reserved keyword that cannot collide with an app-defined
;; value. rf2-qe237 — refer to the canonical core def rather than a local
;; copy so the keyword can never drift across artefacts.
(def ^:private redacted-sentinel privacy/redacted-sentinel)

;; NOTE: the handler-meta `:sensitive?` annotation has been removed.
;; Sensitivity is now path-marked at the schema slot — `walk-schema?`
;; consults `walker/schema-has-sensitive?` to drive the failure-trace
;; redaction.

;; The canonical value-bearing tag slots — the ones that carry the failing
;; value (or a value-bearing lookup key) verbatim and so must be scrubbed
;; on a `:sensitive?` failure (`redact-tags`) AND dropped entirely from a
;; `:rf.error/malformed-schema` trace (where the validator never proved
;; sensitivity, so we cannot redact path-targeted — omitting the value is
;; fail-closed, mirroring `validate-app-schema!`'s malformed-schema emit).
;; Per Spec 010 §`:sensitive?` redaction-shape list (rf2-nijom / rf2-adtp2
;; / rf2-qhq3f). Three are per-surface conditional names carried only on a
;; subset of emit-sites (`:rf.fx/args` on `:where :fx-args`;
;; `:rf.sub/query-v` on `:where :sub-return`; `:explain-humanized` only when
;; the humanize hook is installed); `contains?` guards keep the clauses
;; no-ops on the surfaces whose tag maps don't carry the slot.
(def ^:private value-bearing-slots
  [:value :received :explain :explain-humanized :rf.fx/args :rf.sub/query-v])

(defn- redact-tags
  "Replace value-bearing slots in a tags map with the `:rf/redacted`
  sentinel. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces. Stamps `:sensitive? true` so consumers filter
  correctly. Idempotent — safe to call on an already-redacted map.

  The six redacted slots (`:value`, `:received`, `:explain`,
  `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`) are the
  canonical set per the Spec 010 §`:sensitive?` redaction-shape list.
  Three of them are per-surface / conditional names carried only on a
  subset of emit-sites (`:rf.fx/args` on `:where :fx-args`;
  `:rf.sub/query-v` on `:where :sub-return`; `:explain-humanized` only
  when the `:schemas/humanize-explain!` hook is installed); the
  `contains?` guards make those clauses no-ops on the surfaces whose
  tag maps don't carry the slot.
  Per rf2-nijom this replaces the previous fx-only `extra-redact`
  lambda — the redaction is now symmetric across every value-bearing
  slot, and the schema lists them canonically.

  Per rf2-adtp2 / rf2-p2adl Q2 — `:rf.sub/query-v` (the caller-supplied
  subscription query vector on `:where :sub-return` emissions) is
  the lookup key, not just an id, and on `:sensitive?`-marked subs
  typically carries the same secret material the registered schema
  is gating (user ids, auth tokens, document ids). Without
  redaction the failure trace re-leaks it alongside the failing
  return value the other clauses just scrubbed.

  Per rf2-qhq3f — `:explain-humanized` (the operator-readable
  decomposition of the explainer's output, per Spec 010 §Humanize-hook)
  is itself value-bearing: Malli's `malli.error/humanize` carries the
  failing value verbatim under its path-shaped output. Spec 010
  §Humanize-hook §Composition with `:sensitive?` requires BOTH
  `:explain` AND `:explain-humanized` to redact symmetrically — a
  redacted-raw / leaked-humanized split would re-leak the value the
  `:explain` clause just scrubbed. The slot is built from the
  pre-redaction explanation at the emit-sites (so it carries the real
  humanized payload on non-sensitive failures) and this clause scrubs
  it to the sentinel on sensitive failures — the sentinel is present
  (not omitted), so the trace shape is symmetric across sensitive and
  non-sensitive surfaces and Xray's violation block, which prefers
  `:explain-humanized`, reads `:rf/redacted` rather than falling through
  to a missing slot."
  [tags]
  (-> (reduce (fn [t slot]
                (cond-> t (contains? t slot) (assoc slot redacted-sentinel)))
              tags
              value-bearing-slots)
      (assoc :sensitive? true)))

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

(defn- humanize-explain
  "Compute the operator-readable `:explain-humanized` payload from a
  RAW explainer output (per Spec 010 §Humanize-hook, rf2-2ek7t).
  Returns the humanized shape when the `:schemas/humanize-explain!`
  late-bind hook is installed and `explanation` is non-nil; nil
  otherwise (no hook installed — non-Malli validator / adapter ns not
  required — or the explainer produced nothing).

  Per rf2-qhq3f this MUST be called on the explainer's output BEFORE
  any privacy redaction is applied to the tag map. The earlier design
  humanized inside the central emit seam, AFTER `redact-tags` had
  already overwritten `:explain` with `:rf/redacted`; on a sensitive
  failure the humanizer was then handed the sentinel keyword, returned
  nil, and `:explain-humanized` was silently OMITTED — a shape drift
  against Spec 010 §Humanize-hook §Composition with `:sensitive?`,
  which requires the slot present and redacted to the sentinel
  (symmetric with `:explain`). Computing here, from the raw
  explanation, lets the emit-sites stamp the slot into base-tags so
  the shared `redact-tags` cond-> scrubs it on sensitive surfaces and
  leaves the real payload on non-sensitive ones.

  Failures inside the humanizer degrade silently (humanize is a
  cosmetic enrichment; a thrown humanizer can't suppress the
  failure trace itself)."
  [explanation]
  (when (some? explanation)
    (when-let [hum-fn (late-bind/get-fn :schemas/humanize-explain!)]
      (try (hum-fn explanation) (catch #?(:clj Throwable :cljs :default) _ nil)))))

(defn- emit-validation-failure!
  "Single emit seam for `:rf.error/schema-validation-failure` traces.
  A thin wrapper over `trace/emit-error!` — the `:explain-humanized`
  augmentation now happens at the call-sites (via `humanize-explain`
  folded into base-tags BEFORE redaction, per rf2-qhq3f) so the
  privacy redaction in `redact-tags` can scrub the humanized slot
  symmetrically with `:explain`. Centralising the bare emit keeps the
  category keyword in one place; future cross-cutting tag shaping
  lands here."
  [tags]
  (trace/emit-error! :rf.error/schema-validation-failure tags))

(defn- emit-malformed-schema!
  "Single emit seam for `:rf.error/malformed-schema` traces (rf2-ss06u.3).

  A malformed REGISTERED schema (a childless `[:vector]`, an unknown op,
  etc.) registers without error — Malli validates schema FORMS lazily, at
  validate-time — then makes the registered validator THROW on the first
  post-commit validation. Before this fix the throw aborted the whole
  `validate-app-schema!` loop and was swallowed by the router's defensive
  `(catch ... true)` as a validation PASS: the offending commit installed
  unvalidated with no trace and no rollback, AND every OTHER registered
  schema for the frame went unvalidated for as long as the bad schema
  stayed registered (the privacy-bearing redaction traces never fired
  frame-wide). Same fail-open class as the rf2-sk0ql path bypass, via the
  SCHEMA vector instead of the PATH vector.

  This emit surfaces the structural error as its OWN distinct category so
  it can never masquerade as a clean validate. `:path` / `:registered-path`
  / `:frame` are structural locator slots (no user value). The schema FORM
  itself rides under `:schema` (the offending registration the developer
  must fix); the throwing-validator message rides under `:reason`. The
  app-db value is NOT included — a malformed schema is a programming
  error, and including the value would re-open the very leak the per-entry
  isolation closes (the validator never proved sensitivity, so we cannot
  redact path-targeted; omitting the value is fail-closed).

  Per rf2-a5kzs the SAME emit now serves the four meta-bearing surfaces
  (event / cofx / fx / sub) via `run-validation`: a malformed `:schema`
  on a handler / cofx / fx / sub registration makes the validator throw
  identically, and the runtime call-sites (`router` / `cofx` / `fx` /
  `subs`) coerce that throw to a validation PASS via their defensive
  `(catch … true)` — the same fail-open class. Those surfaces stamp the
  structural slots they carry (`:where`, the surface id, `:frame`) plus
  `:schema` / `:reason`, never a value-bearing slot."
  [tags]
  (trace/emit-error! :rf.error/malformed-schema tags))

(defn- safe-explain
  "Per rf2-a5kzs (finding 3) — invoke the registered explainer inside a
  try/catch so a throwing custom explainer (or an explainer that itself
  fails on a structurally degenerate schema) can never abort failure-trace
  construction. A propagated explainer throw would unwind PAST the
  legitimate `false` return, and the runtime call-sites
  (`router` / `cofx` / `fx` / `subs`, and `validate-app-schema!`'s router
  caller) coerce that throw to a validation PASS via their defensive
  `(catch … true)` — turning a real validation FAILURE into a silent
  catch-as-pass (for app-db: the swallowed-backstop returns true / no
  rollback, so the invalid commit installs).

  The explainer is diagnostic-only enrichment for the `:explain` slot;
  its failure must NOT change the validation verdict. On a throw this
  degrades to nil (the failure trace then omits / nil-fills `:explain`,
  exactly as when no explainer is registered) and the caller continues
  the original `false`."
  [schema value]
  (try
    (validator/run-explainer schema value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn- validate-entry-result
  "Run the registered validator for one `(schema, value)` entry, isolating
  a malformed-schema throw (rf2-ss06u.3). Returns:
    - `true`               — the value conformed.
    - `false`              — a legitimate validation failure.
    - `[:malformed ex]`    — the validator THREW (a malformed registered
                             schema: childless `[:vector]`, unknown op, …).

  Per rf2-ss06u.3 the throw MUST be caught HERE (per-entry) rather than
  propagate to the router's `(catch ... true)` — otherwise one malformed
  schema aborts the whole frame's validation loop and is swallowed as a
  silent commit-PASS. Catching per-entry lets the caller emit a distinct
  `:rf.error/malformed-schema` trace, fail CLOSED (roll back — do not
  install blind), AND continue validating the frame's sibling schemas."
  [vf schema value]
  (try
    (boolean (vf schema value))
    (catch #?(:clj Throwable :cljs :default) ex
      [:malformed ex])))

(defn- run-validation
  "Shared core of the four meta-bearing validate-*! fns (event / cofx /
  fx / sub). Performs the registered-validator deref, the
  `:schema`-on-meta lookup, the validate / explain calls, the
  sensitivity decision, and the trace emit. Returns true on pass / no
  schema / no validator; false on a logged failure.

  Parameters:
    - `reg-meta`     the registration metadata (handler / cofx / sub /
                     fx) — its `:schema` slot, if any, is the schema.
    - `value`        the value being checked (event vector, cofx
                     value, sub return value, fx args).
    - `walk-schema?` boolean — when true AND the validator fails,
                     consult the schema's per-slot `:sensitive?` walker
                     before emitting. Event vectors are not schema-walked
                     (event vectors aren't `:map`-shaped, so per-slot
                     `:sensitive?` props don't apply) so wrappers
                     pass `false`; cofx / fx / sub-return pass `true`.
    - `build-base-tags`  `(fn [schema explanation] -> map)` — produces
                     the per-fn tag map (`:where`, `:reason`, etc.)
                     EXCLUDING any sensitivity stamping. Also the source
                     of the structural locator slots for the malformed-
                     schema trace (rf2-a5kzs): called with a nil
                     explanation, then stripped of the value-bearing slots,
                     so the malformed-schema trace reuses the surface's own
                     `:where` / id / `:frame` shape without duplicating it.

  Per rf2-a5kzs (findings 2 + 3) this primitive isolates BOTH failure
  modes that previously fell open through the runtime call-sites'
  defensive `(catch … true)`:

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

  Per rf2-1o6ax the registered validator-fn is deref'd ONCE at the
  gate and invoked directly — `validator/run-validator` would deref
  the same atom a second time on every pass, which is wasted work
  on a path that runs per-event / per-cofx / per-fx / per-sub-return.

  Per rf2-nijom this primitive no longer carries an `extra-redact`
  escape hatch — the canonical redacted slots
  (`:value`, `:received`, `:explain`, `:explain-humanized`,
  `:rf.fx/args`, `:rf.sub/query-v`) all live on the central
  `redact-tags` cond->. The per-surface clauses are no-ops on the
  surfaces whose base-tags don't contain the slot, so a single
  redactor covers every meta-bearing emit site. Per rf2-qhq3f the
  `:explain-humanized` slot is folded into base-tags here (from the
  RAW explanation via `humanize-explain`, before redaction) so the
  redactor scrubs it symmetrically with `:explain` on sensitive
  failures rather than the humanizer being handed an already-redacted
  `:explain` and silently dropping the slot."
  [reg-meta value walk-schema? build-base-tags]
  (if-let [vf @validator/validator-fn]
    (if-let [schema (:schema reg-meta)]
      ;; Per rf2-a5kzs (finding 2) — isolate a malformed-schema throw HERE
      ;; rather than let it propagate to the call-site `(catch … true)`.
      (let [result (validate-entry-result vf schema value)]
        (cond
          ;; Conformed.
          (true? result)
          true

          ;; Malformed registered schema (validator threw). Surface a
          ;; DISTINCT `:rf.error/malformed-schema` trace built from the
          ;; surface's own structural slots (`:where` / id / `:frame`),
          ;; stripped of the value-bearing slots (the validator never
          ;; proved sensitivity — omitting the value is fail-closed,
          ;; mirroring `validate-app-schema!`). Return false so the caller
          ;; runs its normal recovery instead of the swallowed pass.
          (and (vector? result) (= :malformed (first result)))
          (let [ex     (second result)
                reason #?(:clj  (.getMessage ^Throwable ex)
                          :cljs (ex-message ex))
                ;; build-base-tags called with a nil explanation, then
                ;; stripped of value-bearing slots — no value leaks into a
                ;; malformed-schema trace.
                base   (apply dissoc (build-base-tags schema nil)
                              value-bearing-slots)]
            (emit-malformed-schema!
              (assoc base
                     :schema    schema
                     :reason    (str "Registered schema " (pr-str schema)
                                     " is malformed and could not be "
                                     "evaluated: " reason)
                     :recovery  :no-recovery))
            false)

          ;; Legitimate validation failure — the existing emit path.
          :else
          (let [;; Per rf2-a5kzs (finding 3) — explainer through
                ;; `safe-explain` so a throwing explainer can't unwind past
                ;; this false and become a catch-as-pass at the call-site.
                explanation (safe-explain schema value)
                sensitive?  (and walk-schema?
                                 (walker/schema-has-sensitive? schema))
                ;; Per rf2-qhq3f — humanize from the RAW explanation here,
                ;; before redaction, and fold the slot into base-tags so
                ;; `redact-tags` scrubs it symmetrically with `:explain`
                ;; on sensitive failures (sentinel present, not omitted).
                humanized   (humanize-explain explanation)
                base-tags   (cond-> (build-base-tags schema explanation)
                              (some? humanized) (assoc :explain-humanized humanized))
                tags        (cond-> base-tags sensitive? redact-tags)]
            (emit-validation-failure! tags)
            false)))
      true)
    true))

(defn validate-app-schema!
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
    (validate-app-schema! db)                       ;; current frame
    (validate-app-schema! db event-id)              ;; current frame, named handler
    (validate-app-schema! db event-id frame-id)     ;; explicit frame

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
  (event / cofx / fx / sub): walks N schemas via doseq, has no
  single `:schema`-on-meta lookup, and emits a trace per failure (rather
  than at-most-one). Returns a single boolean conjoining every entry's
  result so the caller can decide rollback deterministically — but
  every failing schema is still surfaced as its own trace so consumers
  see the full set."
  ([db] (validate-app-schema! db nil (frame/current-frame)))
  ([db event-id] (validate-app-schema! db event-id (frame/current-frame)))
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
         (if-let [[reg-path schema-meta] (first entries)]
           (let [reg-slice (get-in db reg-path)
                 schema    (:schema schema-meta)
                 ;; Per rf2-ss06u.3 — isolate a malformed-schema throw per
                 ;; entry so it can never abort the loop (which would skip
                 ;; every sibling schema) NOR propagate to the router's
                 ;; `(catch ... true)` (which swallows a throw as a silent
                 ;; validation PASS — installing an unvalidated commit).
                 result    (validate-entry-result vf schema reg-slice)]
             (cond
               ;; Conformed — carry the running pass-state forward.
               (true? result)
               (recur (next entries) ok?)

               ;; Malformed registered schema (validator threw). Surface a
               ;; DISTINCT `:rf.error/malformed-schema` trace and fail
               ;; CLOSED (`ok? false` → the router rolls back; we do NOT
               ;; install blind). Continue to the sibling entries so one
               ;; bad schema does not disable validation frame-wide.
               (and (vector? result) (= :malformed (first result)))
               (let [ex     (second result)
                     reason #?(:clj  (.getMessage ^Throwable ex)
                               :cljs (ex-message ex))]
                 (emit-malformed-schema!
                   (cond-> {:where           :app-db
                            :path            reg-path
                            :registered-path reg-path
                            :schema          schema
                            :frame           frame-id
                            :reason          (str "Registered app-db schema at path "
                                                  reg-path " is malformed and could "
                                                  "not be evaluated: " reason)
                            :rollback?       true
                            :recovery        :no-recovery}
                     event-id (assoc :failing-id event-id)))
                 (recur (next entries) false))

               ;; Legitimate validation failure — the existing emit path.
               :else
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
                 ;; Per rf2-a5kzs (finding 3) — the explainer is invoked
                 ;; through `safe-explain` so a throwing custom explainer
                 ;; (or an explainer that fails on a degenerate schema)
                 ;; cannot abort this branch and unwind past the `false`
                 ;; this entry contributes to the loop's conjoined result.
                 ;; A propagated throw would reach the router's
                 ;; swallowed-backstop `(catch … true)` and the invalid
                 ;; commit would install with no rollback. The explainer is
                 ;; diagnostic-only; on a throw `:explain` degrades to nil
                 ;; and the failure verdict is preserved.
                 (let [explanation (safe-explain schema reg-slice)
                       in-path     (failing-in-path explanation)
                       leaf-value  (if in-path
                                     (get-in reg-slice in-path)
                                     reg-slice)
                       sensitive?  (if in-path
                                     (walker/schema-sensitive-at? schema in-path)
                                     (walker/schema-has-sensitive? schema))
                       ;; Per rf2-ss06u.1 — a `:set` failure's `:in`
                       ;; segment is the failing ELEMENT VALUE itself
                       ;; (Malli has no positional index for a set), so
                       ;; concatenating the raw `:in` into the structural
                       ;; `:path` tag ships the entire sensitive element
                       ;; map (sibling secrets included) VERBATIM —
                       ;; defeating the redaction the `:value` / `:explain`
                       ;; slots apply. When the slot is sensitive, scrub the
                       ;; `:set`-element value segments out of the `:path`
                       ;; (navigable `:vector` / `:map-of` / `:tuple` / map
                       ;; segments are kept so `:path` stays a useful
                       ;; locator for those shapes — the bead regression).
                       path-in     (if (and in-path sensitive?)
                                     (walker/sanitize-sensitive-path schema in-path)
                                     in-path)
                       leaf-path   (if path-in
                                     (vec (concat reg-path path-in))
                                     reg-path)
                       ;; Per rf2-qhq3f — humanize from the RAW
                       ;; explanation, before redaction, so the
                       ;; `:explain-humanized` slot is scrubbed
                       ;; symmetrically with `:explain` on sensitive
                       ;; app-db failures (sentinel present, not omitted).
                       humanized   (humanize-explain explanation)
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
                                     event-id          (assoc :failing-id event-id)
                                     (some? humanized) (assoc :explain-humanized humanized))
                       tags        (if sensitive? (redact-tags base-tags) base-tags)]
                   (emit-validation-failure! tags)
                   (recur (next entries) false)))))
           ok?))
       true)
     true)))

(defn validate-event!
  "Per Spec 010 §Validation order step 1 — before an event handler runs,
  validate the event vector against any :schema on the handler's
  metadata. Failures emit `:rf.error/schema-validation-failure :where
  :event`; the caller skips the handler (recovery: `:no-recovery`).
  Returns true/false per the `run-validation` contract.

  The optional 4-arity `frame` (rf2-lo28u) stamps a `:frame` tag onto
  the failure trace. Without it the trace carries no `:frame`, so
  `re-frame.epoch.capture/capture-event!` — which buffers a trace into
  the in-flight cascade ONLY when the trace's tags carry the cascade's
  `:frame` — silently DROPS the violation from the epoch's
  `:trace-events`. The violation still reaches the global trace stream
  (so `register-listener!` consumers see it) but never lands in the
  per-frame epoch record, so the Xray Issues / Schema-timeline lens
  (which reads off `:trace-events`) shows nothing. This is the
  asymmetry against `validate-app-schema!`, which always tags `:frame`
  and so is correctly captured. The router passes the in-flight frame
  so the `:where :event` trace is captured in the same epoch as the
  dispatch that triggered it, exactly like the `:where :app-db` trace.
  The 3-arity stays for direct (non-router) callers (the elision probe,
  unit tests) where there is no in-flight cascade to attribute to.

  Per rf2-a5kzs (finding 1) — event validation DOES consult the schema's
  per-slot `:sensitive?` walker (`walk-schema? true`). An event schema is
  not itself `:map`-shaped, but its payload commonly IS: a login schema
  `[:cat [:= :auth/login] [:map [:password {:sensitive? true} :string]]]`
  marks the password slot sensitive. The previous `false` ignored those
  annotations, so a failing sensitive event payload leaked verbatim via
  `:received` / `:value` / `:explain` to trace listeners / off-box
  consumers — contradicting Spec 010's redaction contract. `walker/
  schema-has-sensitive?` walks the WHOLE schema form (the `:cat` container
  descends into its `:map` payload child), so a per-slot `:sensitive?`
  anywhere in the event schema (incl. container-level props) now drives
  redaction; non-sensitive event failures still ride verbatim (the walker
  reports nothing → no redaction)."
  ([event-id event handler-meta] (validate-event! event-id event handler-meta nil))
  ([event-id event handler-meta frame]
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
           frame (assoc :frame frame))))
     true)))

(defn validate-sub!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :schema on the sub's metadata.
  Failures emit `:rf.error/schema-validation-failure :where
  :sub-return`; the caller replaces the value with the default (nil)
  per the `:replaced-with-default` recovery. Returns true/false per
  the `run-validation` contract.

  The optional 5-arity `frame` (rf2-9cm27) stamps a `:frame` tag onto
  the failure trace — the reaction's frame. Without it the trace carries
  no `:frame`, so `re-frame.epoch.capture/capture-event!` — which buffers
  a trace into the in-flight cascade ONLY when the trace's tags carry the
  cascade's `:frame` — silently DROPS the violation from the epoch's
  `:trace-events`, leaving the Xray Issues / Schema-timeline lens (which
  reads off `:trace-events`) blind to it. Mirrors `validate-event!`'s
  `:frame` fix (rf2-lo28u) so every per-step validation trace is captured
  uniformly. The 4-arity stays for direct (non-runtime) callers (the
  elision probe, unit tests) where there is no reaction frame to
  attribute to."
  ([sub-id query-v value sub-meta] (validate-sub! sub-id query-v value sub-meta nil))
  ([sub-id query-v value sub-meta frame]
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
           frame (assoc :frame frame))))
     true)))

(defn validate-cofx!
  "Per Spec 010 §Validation order step 2 — after a cofx injects its
  value into the merged context, validate that value against any
  :schema on the cofx's metadata. Failures emit
  `:rf.error/schema-validation-failure :where :cofx`; the caller
  skips the handler (recovery: `:no-recovery`). Returns true/false
  per the `run-validation` contract.

  The optional 5-arity `frame` (rf2-9cm27) stamps a `:frame` tag onto
  the failure trace — the in-flight cascade's frame. Without it the
  trace carries no `:frame`, so `re-frame.epoch.capture/capture-event!`
  silently DROPS the violation from the epoch's `:trace-events` (it
  buffers a trace into the in-flight cascade ONLY when the trace's tags
  carry the cascade's `:frame`), leaving the Xray Schema-timeline lens
  blind to it. Mirrors `validate-event!`'s `:frame` fix (rf2-lo28u). The
  4-arity stays for direct (non-runtime) callers (the elision probe,
  unit tests)."
  ([cofx-id event-id value cofx-meta] (validate-cofx! cofx-id event-id value cofx-meta nil))
  ([cofx-id event-id value cofx-meta frame]
   (if interop/debug-enabled?
     (run-validation
       cofx-meta
       value
       true   ;; consult schema's per-slot `:sensitive?` walker on fail
       (fn [schema explanation]
         (cond-> {:where      :cofx
                  :rf.cofx/id cofx-id
                  :event-id   event-id
                  :failing-id event-id
                  :schema-id  cofx-id
                  :received   value
                  :value      value
                  :explain    explanation
                  :reason     (reason-string "Coeffect " cofx-id
                                             " injected value failed schema "
                                             schema value)
                  :recovery   :no-recovery}
           frame (assoc :frame frame))))
     true)))

(defn validate-fx!
  "Per Spec 010 §Validation order step 5 — before an fx handler runs,
  validate its args against any :schema on the fx's metadata. Failures
  emit `:rf.error/schema-validation-failure :where :fx-args`; per
  Spec 010 §Per-step recovery row 5 the caller skips the offending fx
  only (recovery: `:skipped`) — sibling fx in the same `:fx` vector
  continue to run, and downstream queued events still drain. Returns
  true/false per the `run-validation` contract.

  Per rf2-nijom the per-surface `:rf.fx/args` slot is redacted by the
  central `redact-tags` cond->; the lambda escape hatch that used to
  do this here is gone, and Spec 010 §`:sensitive?` now lists
  `:rf.fx/args` alongside `:value` / `:received` / `:explain` as the
  canonical redacted slots (and `:rf.sub/query-v` on the sub-return
  surface, per rf2-adtp2).

  The optional 5-arity `frame` (rf2-9cm27) stamps a `:frame` tag onto
  the failure trace — the in-flight cascade's frame. Without it the
  trace carries no `:frame`, so `re-frame.epoch.capture/capture-event!`
  silently DROPS the violation from the epoch's `:trace-events` (it
  buffers a trace into the in-flight cascade ONLY when the trace's tags
  carry the cascade's `:frame`), leaving the Xray Schema-timeline lens
  blind to it. Mirrors `validate-event!`'s `:frame` fix (rf2-lo28u). The
  4-arity stays for direct (non-runtime) callers (the elision probe,
  unit tests)."
  ([fx-id event-id args fx-meta] (validate-fx! fx-id event-id args fx-meta nil))
  ([fx-id event-id args fx-meta frame]
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
           frame    (assoc :frame frame))))
     true)))

;; ---- public boundary-validation entry point (rf2-r2uh integration) -------
;;
;; The boundary-validation interceptor (`re-frame.spec/validate-at-boundary-interceptor`,
;; interceptor id `:rf.schema/at-boundary` per rf2-ieu0i; rf2-r2uh)
;; runs `:schema` validation on a handler at production-build time —
;; outside the `interop/debug-enabled?` gate that guards the
;; hot-path validate-*! fns above. Per Spec 010 §Production builds the
;; boundary interceptor MUST route through the same registered validator
;; the dev-mode hot path uses (so a substituted validator covers both
;; surfaces). This namespace publishes `validate-with-registered-fn` as
;; the call the interceptor reaches for via the
;; `:schemas/validate-with-registered-fn` late-bind hook (the schemas
;; artefact is optional per rf2-p7va so the interceptor cannot
;; statically `:require [re-frame.schemas]`).
;;
;; Contract: returns true on conform; false on fail (incl. a malformed
;; schema — fail CLOSED); true (pass) when no validator is registered.
;; Does NOT emit a trace — the boundary interceptor is responsible for
;; emitting :rf.error/schema-validation-failure :where :event with the
;; appropriate envelope. Pure check surface.

(defn validate-with-registered-fn
  "Apply the registered validator to `(schema, value)`. Public seam for
  the boundary-validation interceptor (rf2-r2uh). Returns true on
  conform; false on fail; true when no validator is registered (the
  call-site treats no-validator as no-validation, mirroring the hot
  path).

  Per rf2-a5kzs (finding 2, boundary seam) — a structurally MALFORMED
  registered schema makes the validator THROW. This seam isolates that
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
  interceptor (rf2-r2uh). Returns the explanation map / data on fail;
  nil when the schema conforms or no explainer is registered.

  Per rf2-a5kzs (finding 3, boundary seam) — a throwing explainer
  degrades to nil here rather than propagating; the boundary interceptor
  already wraps the call in its own try/catch, so this is belt-and-braces
  symmetry with the dev-time `safe-explain` (the explainer is diagnostic-
  only and must never change the verdict)."
  [schema value]
  (try
    (validator/run-explainer schema value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn redact-event-tags
  "Redaction seam for the production boundary-validation interceptor
  (`re-frame.spec`, rf2-a5kzs finding 1). Given the registered event
  `schema` and the failure trace `tags` the interceptor built, return the
  tags with the value-bearing slots (`:received` / `:value` / `:explain` /
  …) scrubbed to `:rf/redacted` and `:sensitive? true` stamped WHEN the
  event schema declares any slot `:sensitive?` (e.g. a `:cat` payload map
  `[:map [:password {:sensitive? true} :string]]`); otherwise the tags ride
  back verbatim.

  The dev-time step-1 path (`validate-event!`) consults the same
  `walker/schema-has-sensitive?` predicate via `run-validation`'s
  `walk-schema?`; this seam gives the production boundary path the SAME
  redaction without the optional schemas artefact and the (core) `re-frame.spec`
  interceptor coupling — the interceptor reaches it through the
  `:schemas/redact-event-tags` late-bind hook and falls through verbatim
  when the hook is unbound (schemas artefact absent).

  Pure; `redact-tags` is idempotent so a double-call is safe."
  [schema tags]
  (cond-> tags
    (walker/schema-has-sensitive? schema) redact-tags))
