(ns re-frame.schemas.validate
  "Validation entry points (Spec 010 §Validation order steps 1-6).

  Owns the four dev-time validate-*! fns the framework calls at the
  locked validation sites:

    - validate-event!        — pre-handler (event vector vs handler :schema)
    - validate-fx!           — pre-fx-handler (fx args vs fx :schema)
    - validate-app-schema!   — post-handler-commit (frame's app-schemas)
    - validate-sub!          — post-sub-recompute (return value vs sub :schema)

  The metadata key is `:schema` (canonical per rf2-ieu0i).

  Per rf2-s2jgz (audit-of-audits #20) the family is named on the
  kind axis — validate-event!, validate-fx!,
  validate-sub! and validate-app-schema!. The earlier
  validate-app-db! / validate-sub-return! names were renamed for
  symmetry with their siblings. The cofx surface used to live here
  too (validate-cofx!) but was retired per rf2-nkf4l3 — EP-0017 made
  the live cofx schema check the recordable-value contract in
  `re-frame.cofx/validate-recordable-value!` (a production hard error
  emitting `:rf.error/cofx-value-invalid`), not a dev-only
  `:rf.error/schema-validation-failure :where :cofx` trace.

  Also owns the production-side boundary-validation seam
  (`validate-with-registered-fn` / `explain-with-registered-fn`) that
  the boundary-validation interceptor (`re-frame.spec`, rf2-r2uh)
  reaches via the schemas-side late-bind hook.

  Per rf2-s7s6j the three meta-bearing validate-*! fns (event /
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

  Per rf2-ss06u.1 / rf2-612mri the `:path` tag's normally-structural
  segments have several value-bearing carve-outs that
  `walker/sanitize-sensitive-path` scrubs to `:rf/redacted` on a sensitive
  failure (the same scrubbed path also feeds `:reason`, so neither slot
  leaks): a `:set` failure's segment is the failing ELEMENT VALUE itself
  (Malli has no positional index for a set); a `:map-of` key whose KEY
  SCHEMA declares `:sensitive?` is the secret used AS the key
  (rf2-612mri); and any scalar in the FAIL-CLOSED tail past an ambiguous
  wrapper (`:orn` / `:multi`) cannot be proven a locator and may be a
  value-bearing `:set` scalar element (rf2-612mri). Navigable `:vector` /
  `:tuple` / `:map` segments and NON-sensitive `:map-of` keys stay intact
  so `:path` remains a `get-in` locator for those shapes.

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

;; ---- :large? size-elision of validation-failure value slots (rf2-vmhu4i) --
;;
;; A `:large?`-flagged slot inside the checked value ships the whole blob into
;; the validation-failure trace's value-bearing slots unless the emit-site
;; elides it. Per Spec 010 §`:large?` (the validation size-safety arm) and
;; §Composition with `:large?` (sensitive wins), the emit-site substitutes the
;; `:rf.size/large-elided` marker for the whole value-bearing slots when the
;; schema declares any `:large?` slot and NO `:sensitive?` slot governs the
;; redaction — a sensitive failure already scrubs to `:rf/redacted`, and a
;; sensitive marker would itself leak the secret's `:path` / `:bytes` size
;; signature. The marker is built by the canonical `re-frame.elision/->marker`
;; (core, NOT the `marks` ns) so the shape physically cannot drift from the
;; `:rf/elision-marker` contract again (rf2-9wvwpa). `re-frame.elision` lives in
;; core — the artefact the schemas surface already depends on (cf.
;; `re-frame.frame` above) — and carries no concrete substrate-adapter
;; dependency (it `:require`s only the `re-frame.substrate.adapter` *contract*
;; ns, a sibling of `re-frame.frame`).

(defn- large-marker
  "Build the `:rf.size/large-elided` marker for the whole failing value `v`.
  Conforms to Spec-Schemas §`:rf/elision-marker` / Spec 009 §Wire marker by
  delegating to the canonical `re-frame.elision/->marker` — the marker shape
  (`:path`, `:bytes`, `:type`, `:reason`, `:hint`, `:handle`) is therefore
  identical to every other framework emission and cannot drift (rf2-9wvwpa).

  `:reason :frame` — post-EP-0015 §8 the egress `:reason` enum is
  `[:frame :marks]` (Spec-Schemas §`:rf/elision-marker`); `:frame` is the
  declarative/registration-time default in `->marker`. No imperative mark
  created this elision, and the validation-failure provenance is already on
  the enclosing trace envelope (`:operation
  :rf.error/schema-validation-failure` + `:where` + `:tags :large? true`), so
  the marker carries no `:reason :schema` carve-out.

  `:hint nil` — the slot is REQUIRED by the contract (Spec 009 §Wire marker
  permits a nil value); the whole-value substitution has no human-facing
  re-fetch hint distinct from the trace envelope.

  `:path []` — the marker substitutes the WHOLE value-bearing slot, matching
  the whole-payload nature of these slots (a single marker, not a path-walk;
  the value-bearing slots carry the whole checked value, not a leaf)."
  [v]
  (elision/->marker v [] {:reason :frame :hint nil}))

(defn- elide-large-slots
  "Substitute the `:rf.size/large-elided` marker (for `v`, the whole checked
  value) into each `value-bearing-slots` entry present in `tags`, and stamp
  `:large? true`. `contains?`-guarded so a slot a surface doesn't carry is a
  no-op. Per Spec 010 §`:large?` validation size-safety arm (rf2-vmhu4i).

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

;; ---- PER-SLOT DECISION SCOPING (rf2-3qam7b / the rf2-me69cb principle) ----
;;
;; THE PRINCIPLE: the SCOPE of a slot's `:sensitive?` redaction decision MUST
;; MATCH the SCOPE of the value that slot actually carries.
;;
;;   - A slot that carries a LEAF-NARROWED value (only the failing leaf — e.g.
;;     `:value` on the app-db path, which is `(get-in reg-slice in-path)`) may
;;     use the LEAF-PRECISE check `walker/schema-sensitive-at?`: a sensitive
;;     SIBLING of the failing leaf is genuinely not present in the carried
;;     value, so it must not force redaction (the rf2-oh4se / rf2-k0ew8n
;;     precise-narrowing win).
;;
;;   - A slot that carries the WHOLE PAYLOAD verbatim (`:explain` /
;;     `:explain-humanized` / `:received` / `:rf.fx/args` / `:rf.sub/query-v`,
;;     and `:value` on every surface EXCEPT the app-db leaf) MUST use the
;;     ROOT / whole-schema check `walker/schema-has-sensitive?`. The whole
;;     payload carries every CONFORMING sibling too, so a conforming
;;     `:sensitive?` sibling (e.g. a valid `:jwt` next to a failing `:name`)
;;     rides INSIDE that slot — a leaf-precise decision keyed on the failing
;;     `:name` would (wrongly) clear it and the jwt would egress to Xray /
;;     pair-MCP unredacted. This was the rf2-3qam7b leak: a leaf-precise
;;     decision was gating whole-payload slots (sibling-blind).
;;
;; `schema-sensitive-at?` is a SUBSET of `schema-has-sensitive?` — a sensitive
;; leaf ancestor/descendant implies the schema declares something sensitive —
;; so the whole-payload decision (`schema-has-sensitive?`) also governs the
;; `:sensitive?` top-level stamp (a redaction of ANY value-bearing slot stamps
;; the trace so Xray routing + the MCP egress gate see it).
;;
;; The app-db path is the ONLY surface that narrows a slot (`:value` to the
;; failing leaf); every meta-bearing surface (event / fx / sub) carries
;; the WHOLE checked value in EVERY value-bearing slot, so its whole decision
;; is the root check. The off-namespace seam `redact-validation-tags` is
;; already whole-only (the coarse root check; rf2-me69cb=(a)) and is unchanged.
(def ^:private app-db-narrowed-slots
  "The value-bearing slots the app-db hot path NARROWS to the failing leaf
  (`:value` = `(get-in reg-slice in-path)`). These — and ONLY these — use the
  leaf-precise `schema-sensitive-at?` decision; every other value-bearing slot
  on that surface carries the whole reg-slice and uses the root check."
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
  `redact-validation-tags` (already coarse + root-checked, rf2-me69cb=(a)). The
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

  Per rf2-adtp2 / rf2-p2adl Q2 — `:rf.sub/query-v` (the caller-supplied
  subscription query vector on `:where :sub-return` emissions) is the lookup
  key, not just an id, and on `:sensitive?`-marked subs typically carries the
  same secret material the registered schema is gating (user ids, auth tokens,
  document ids). Without redaction the failure trace re-leaks it alongside the
  failing return value the other clauses just scrubbed.

  Per rf2-qhq3f — `:explain-humanized` (the operator-readable decomposition of
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
  "PER-SLOT DECISION SCOPING (rf2-3qam7b). Scrub value-bearing slots under TWO
  decisions matched to the scope of the value each slot carries:

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
  (sibling-blind) leaked the sibling (rf2-3qam7b). A leaf-narrowed slot carries
  ONLY the failing leaf, so the leaf-precise decision is exact there — keeping
  the rf2-oh4se no-sibling-taint precision for that one slot.

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

  Per rf2-a5kzs the SAME emit now serves the three meta-bearing surfaces
  (event / fx / sub) via `run-validation`: a malformed `:schema`
  on a handler / fx / sub registration makes the validator throw
  identically, and the runtime call-sites (`router` / `fx` /
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
  (`router` / `fx` / `subs`, and `validate-app-schema!`'s router
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
  "Shared core of the three meta-bearing validate-*! fns (event /
  fx / sub). Performs the registered-validator deref, the
  `:schema`-on-meta lookup, the validate / explain calls, the
  sensitivity decision, and the trace emit. Returns true on pass / no
  schema / no validator; false on a logged failure.

  Parameters:
    - `reg-meta`     the registration metadata (handler / sub /
                     fx) — its `:schema` slot, if any, is the schema.
    - `value`        the value being checked (event vector, sub
                     return value, fx args).
    - `walk-schema?` boolean — when true AND the validator fails,
                     consult the schema's per-slot `:sensitive?` walker
                     before emitting. Per rf2-a5kzs (finding 1) all three
                     meta-bearing surfaces now pass `true`: an event vector
                     isn't itself `:map`-shaped but its `:cat`/`:catn` payload
                     commonly is (a login schema marks `:password` sensitive),
                     and fx / sub-return validate a map value directly.
                     Per rf2-3qam7b the decision here is the WHOLE-schema
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
  on a path that runs per-event / per-fx / per-sub-return.

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
                     :schema   schema
                     :reason   (str "Registered schema " (pr-str schema)
                                    " is malformed and could not be "
                                    "evaluated: " reason)
                     ;; Per rf2-mxs7a — preserve the surface-specific
                     ;; recovery the caller's build-base-tags supplied
                     ;; (validate-fx! → :skipped, validate-sub! →
                     ;; :replaced-with-default; event →
                     ;; :no-recovery). The runtime applies that local
                     ;; fallback even on a malformed schema (fx.cljc
                     ;; skips the offending fx; subs/memo.cljc returns
                     ;; the default), so the malformed-schema trace must
                     ;; report the SAME recovery the data plane actually
                     ;; took rather than unconditionally lying with
                     ;; :no-recovery. Default to :no-recovery only when
                     ;; the surface did not carry one.
                     :recovery (get base :recovery :no-recovery)))
            false)

          ;; Legitimate validation failure — the existing emit path.
          :else
          (let [;; Per rf2-a5kzs (finding 3) — explainer through
                ;; `safe-explain` so a throwing explainer can't unwind past
                ;; this false and become a catch-as-pass at the call-site.
                explanation (safe-explain schema value)
                ;; PER-SLOT DECISION SCOPING (rf2-3qam7b). The meta-bearing
                ;; surfaces (event / fx / sub) carry the WHOLE checked
                ;; value in EVERY value-bearing slot — `:value` / `:received` /
                ;; `:explain` / `:explain-humanized` / `:rf.fx/args` /
                ;; `:rf.sub/query-v` are all the whole event-vector /
                ;; fx-args / sub-return value (nothing here is narrowed to the
                ;; failing leaf the way the app-db path narrows `:value`). So
                ;; the redaction decision MUST be scoped to the WHOLE schema
                ;; (`schema-has-sensitive?`), NOT the leaf-precise
                ;; `schema-sensitive-at?`. A leaf-precise decision was the
                ;; rf2-3qam7b leak: a failing NON-sensitive sibling (e.g.
                ;; `:age`) cleared redaction while a CONFORMING sensitive
                ;; sibling (e.g. `:password` / `:jwt`) rode unredacted inside
                ;; every whole-payload slot, egressing to Xray + pair-MCP. The
                ;; earlier rf2-k0ew8n / rf2-4q681i "don't over-redact a
                ;; non-sensitive failing sibling" win does NOT apply here
                ;; precisely because there is no narrowed slot to apply it to —
                ;; the whole payload carries the sibling. (It DOES still apply
                ;; to the app-db `:value` slot, which is genuinely leaf-narrowed
                ;; — see `validate-app-schema!`.) `walk-schema?` stays the knob
                ;; for whether to consult the walker at all.
                ;; Per rf2-u9bjgr — a COMPILED / OPAQUE schema (a non-vector,
                ;; non-keyword `m/schema` object) cannot be walked, so the
                ;; per-slot `:sensitive?` flag Malli honoured for the failure
                ;; is INVISIBLE to the walker. Fail CLOSED: redact as if
                ;; sensitive. Otherwise an opaque schema carrying a
                ;; `{:sensitive? true}` slot leaks the failing value verbatim
                ;; while the equivalent vector form redacts (the bead's
                ;; asymmetry). A bare keyword is provably flag-free, so it is
                ;; NOT opaque here (`walker/schema-opaque?`).
                sensitive?  (and walk-schema?
                                 (or (walker/schema-has-sensitive? schema)
                                     (walker/schema-opaque? schema)))
                ;; Per rf2-vmhu4i — when the schema declares any `:large?`
                ;; slot AND the failure is not sensitive (sensitive wins),
                ;; elide the value-bearing slots to the `:rf.size/large-elided`
                ;; marker. An opaque schema is already handled fail-closed
                ;; SENSITIVE above (which subsumes large), so `:large?` is only
                ;; consulted for a walkable schema here.
                large?      (and walk-schema?
                                 (not sensitive?)
                                 (walker/schema-has-large? schema))
                ;; Per rf2-qhq3f — humanize from the RAW explanation here,
                ;; before redaction, and fold the slot into base-tags so
                ;; `redact-tags` scrubs it symmetrically with `:explain`
                ;; on sensitive failures (sentinel present, not omitted).
                humanized   (humanize-explain explanation)
                base-tags   (cond-> (build-base-tags schema explanation)
                              (some? humanized) (assoc :explain-humanized humanized))
                tags        (cond-> base-tags
                              sensitive? redact-tags
                              large?     (elide-large-slots value))]
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
                 ;; validation error traces (rf2-kj51z / rf2-3qam7b).
                 ;; The redaction decision is PER-SLOT-SCOPED (see the
                 ;; `let` below): the path-targeted check
                 ;; (`schema-sensitive-at?`) governs only the slots this
                 ;; surface NARROWS to the failing leaf — `:value`, plus
                 ;; the `:path` / `:reason` leaf coordinates — so a
                 ;; failure at a non-sensitive leaf whose SIBLING is
                 ;; sensitive does not redact THOSE (the precise-narrowing
                 ;; win; ancestor- OR descendant-sensitive at the leaf
                 ;; counts). The WHOLE-PAYLOAD slots (`:explain` /
                 ;; `:explain-humanized`, which carry the whole reg-slice)
                 ;; stay under the coarse `schema-has-sensitive?` root
                 ;; check, because a conforming sensitive sibling rides
                 ;; inside them — gating them on the leaf decision was the
                 ;; rf2-3qam7b leak.
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
                       ;; PER-SLOT DECISION SCOPING (rf2-3qam7b). The app-db
                       ;; hot path is the ONLY surface that NARROWS a slot:
                       ;; `:value` is `(get-in reg-slice in-path)` — just the
                       ;; failing leaf. So it carries TWO redaction decisions
                       ;; scoped to the two value-scopes it ships:
                       ;;
                       ;;   - `leaf-sensitive?` — the LEAF-PRECISE check
                       ;;     (`schema-sensitive-at?` at the failing `:in`;
                       ;;     whole-schema fallback when no `:in` extractable).
                       ;;     Per rf2-oh4se / rf2-kj51z a failure at a
                       ;;     non-sensitive leaf whose SIBLING is sensitive is
                       ;;     NOT redacted — the leaf value genuinely doesn't
                       ;;     contain the sibling. This decision governs the
                       ;;     NARROWED `:value` slot AND the `:path` / `:reason`
                       ;;     sanitization (both keyed to the failing leaf).
                       ;;
                       ;;   - `whole-sensitive?` — the ROOT / whole-schema check
                       ;;     (`schema-has-sensitive?`). This governs the
                       ;;     WHOLE-PAYLOAD slots `:explain` /
                       ;;     `:explain-humanized`, which carry the WHOLE
                       ;;     `reg-slice` verbatim (Malli's explanation root
                       ;;     `:value` is the whole input map / the humanized
                       ;;     decomposition is path-shaped over it). A
                       ;;     CONFORMING sensitive sibling (the rf2-3qam7b leak:
                       ;;     `[:name]` fails, `[:jwt {:sensitive?}]` conforms)
                       ;;     rides inside `:explain`; gating `:explain` on the
                       ;;     leaf-precise `[:name]` decision (sibling-blind)
                       ;;     leaked the live jwt to Xray + pair-MCP. The root
                       ;;     check catches it because the schema declares the
                       ;;     jwt slot sensitive. `whole-sensitive?` also stamps
                       ;;     the top-level `:sensitive?` (it is the broader
                       ;;     decision — `leaf-sensitive?` ⊆ `whole-sensitive?`).
                       ;; Per rf2-u9bjgr — a COMPILED / OPAQUE schema (a
                       ;; non-vector, non-keyword `m/schema` object) cannot be
                       ;; walked, so a per-slot `:sensitive?` Malli honoured is
                       ;; invisible. Fail CLOSED on BOTH the whole-payload and
                       ;; the leaf decision: redact every value-bearing slot as
                       ;; sensitive. (`reg-app-schema` also warns once via
                       ;; `:rf.warning/schema-walker-opaque` at registration;
                       ;; this is the redaction half of the same fail-closed
                       ;; posture.)
                       opaque?          (walker/schema-opaque? schema)
                       leaf-sensitive?  (or opaque?
                                            (if in-path
                                              (walker/schema-sensitive-at? schema in-path)
                                              (walker/schema-has-sensitive? schema)))
                       whole-sensitive? (or opaque?
                                            (walker/schema-has-sensitive? schema))
                       ;; Per rf2-vmhu4i — a `:large?` (non-sensitive) schema
                       ;; elides the value-bearing slots to the size marker
                       ;; (sensitive wins; opaque is fail-closed sensitive,
                       ;; subsuming large). `whole-large?` governs every
                       ;; value-bearing slot uniformly here — the marker is the
                       ;; same shape on the narrowed `:value` (built from the
                       ;; failing leaf) and the whole-payload `:explain`.
                       whole-large?     (and (not whole-sensitive?)
                                             (walker/schema-has-large? schema))
                       ;; Per rf2-ss06u.1 / rf2-612mri — some `:in`
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
                       ;; locator for those shapes — the bead regression).
                       path-in     (if (and in-path leaf-sensitive?)
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
                       ;; PER-SLOT DECISION SCOPING: `:value` (narrowed) under
                       ;; the leaf decision; `:explain` / `:explain-humanized`
                       ;; (whole reg-slice) under the root decision. Per
                       ;; rf2-vmhu4i, when the schema is `:large?` (and not
                       ;; sensitive) the value-bearing slots elide to the size
                       ;; marker (built from the whole `reg-slice`) — sensitive
                       ;; wins, so this arm only fires when neither the leaf nor
                       ;; the whole-payload decision redacted.
                       tags        (cond-> (redact-tags-per-slot base-tags
                                                                 whole-sensitive?
                                                                 leaf-sensitive?
                                                                 app-db-narrowed-slots)
                                     whole-large? (elide-large-slots reg-slice))]
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

;; NB the injection-time `validate-cofx!` fn was RETIRED per rf2-nkf4l3.
;; EP-0017 removed the ctx-mutating `inject-cofx` injection point this fn's
;; `:where :cofx` trace described; the live cofx schema contract is the
;; recordable-value check in `re-frame.cofx/validate-recordable-value!` — a
;; PRODUCTION hard error emitting `:rf.error/cofx-value-invalid` (and throwing),
;; not a dev-only `:rf.error/schema-validation-failure :where :cofx` trace. See
;; Spec 010 §Validation order step 2 and §Per-step recovery row 2.

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

(defn redact-validation-tags
  "THE shared schema-aware redaction seam for EVERY validation-failure
  trace emitted OUTSIDE this namespace (rf2-o69h5). Given the registered
  `schema` the failing value was checked against and the failure-trace
  `tags` a caller built, return the tags with the value-bearing slots
  (`:value` / `:received` / `:explain` / `:explain-humanized` /
  `:rf.fx/args` / `:rf.sub/query-v` — the canonical `value-bearing-slots`)
  scrubbed to `:rf/redacted` and `:sensitive? true` stamped WHEN the
  schema declares ANY slot `:sensitive?` (e.g. a payload map
  `[:map [:password {:sensitive? true} :string]]`); otherwise the tags
  ride back verbatim.

  This is the ONE redactor the class-sweep (rf2-o69h5) routes every
  framework-side validation-failure emit site through, so the
  `:sensitive?` redaction logic lives in a single place rather than
  being re-derived ad-hoc per surface. The dev-time hot-path emits
  (`validate-event!` / `-fx!` / `-sub!` / `validate-app-schema!`)
  reach the SAME `redact-tags` core directly via `run-validation`; the
  off-namespace emit sites — the production boundary interceptor
  (`re-frame.spec`), machine `:data` validation
  (`re-frame.machines.data-validation`), the `:sub-override` validation
  path (`re-frame.subs`), flow-output validation (`re-frame.flows`), and
  the EP-0017 recordable-coeffect `:rf.error/cofx-value-invalid` emit
  (`re-frame.cofx` — the live cofx schema surface; the injection-time
  `validate-cofx!` was retired per rf2-nkf4l3)
  — reach it through the `:schemas/redact-validation-tags` late-bind
  hook and fall through verbatim when the hook is unbound (schemas
  artefact absent → no schema to redact against).

  Per rf2-u9bjgr the decision FAILS CLOSED on a COMPILED / OPAQUE schema (a
  non-vector, non-keyword `m/schema` object the walker cannot introspect):
  it redacts as if sensitive, because Malli may have honoured a
  `{:sensitive? true}` slot the walker cannot see — without this an opaque
  schema's failure leaks verbatim while the vector form redacts.

  Per rf2-vmhu4i a `:large?`-flagged (and non-sensitive) schema elides the
  value-bearing slots to the `:rf.size/large-elided` marker (sensitive wins;
  the opaque fail-closed branch is sensitive, which subsumes large). `tags`
  carries the whole checked value under `:value` (the shared off-box slot),
  so the marker is built from `(:value tags)`.

  Pure; `redact-tags` is idempotent so a double-call (or a call on a
  tags map a path-based pre-scrub already touched) is safe."
  [schema tags]
  (let [sensitive? (or (walker/schema-has-sensitive? schema)
                       (walker/schema-opaque? schema))
        large?     (and (not sensitive?)
                        (walker/schema-has-large? schema))]
    (cond-> tags
      sensitive? redact-tags
      large?     (elide-large-slots (:value tags)))))
