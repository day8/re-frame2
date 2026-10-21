(ns re-frame.schemas.validate
  "Validation entry points and failure-trace projection (Spec 010).

  Owns the four dev-time validate-*! fns the framework calls at the
  locked validation sites:

    - validate-event!        — pre-handler (event vector vs handler :schema)
    - validate-fx!           — pre-fx-handler (fx args vs fx :schema)
    - validate-app-schema!   — pre-install (candidate app-db vs frame's app-schemas)
    - validate-sub!          — post-sub-recompute (return value vs sub :schema)

  Event, effect, and subscription validation go through `check!`, the one
  validation seam: it validates, isolates a validator throw as
  `:rf.error/malformed-schema`, and builds the redacted failure tags. Every
  validating surface outside this artefact (the `:boundary? true` check,
  recordable coeffect values, sub overrides, machine `:data` and output,
  route and resource params, epoch restore, Story) reaches the same fn
  through the `:schemas/check!` late-bind hook, so a failure carries one
  shape whichever surface found it (Spec 010 §The validation seam).
  App-db validation iterates every schema the frame resolves, keeps
  per-entry verdicts isolated, and narrows `:value` to the failing leaf, so
  it builds its tags from the same primitives rather than through `check!`.

  Per Spec 009 §Production builds every dev-time validate-*! body lives
  inside an `(if interop/debug-enabled? ...)` gate as the OUTERMOST
  form so :advanced+goog.DEBUG=false DCE-elides every reason string,
  keyword, validator deref, and trace call. `check!` itself runs in
  production for the surfaces that validate there, and gates its
  humanizer and reason on the same flag.

  Validator and explainer functions are pluggable. An absent validator means
  pass without inspection; an absent explainer means no diagnostic value.

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces. The emit-sites redact the failing value before
  stamping a trace event when the schema marks any slot
  `:sensitive? true` in its Malli props, or hides a slot the walker
  cannot see. Sensitivity is path-marked at the schema slot and decided
  schema-wide.
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

  On a sensitive app-db failure `:path` keeps the registered path and the
  leading `:in` segments that are keys the schema declares on a `:map`
  (`walker/declared-key-prefix`), and every later segment is `:rf/redacted`:
  a `:set` element, a `:map-of` key or a closed map's extra key is the
  caller's data. `:reason` is built from the same path, so neither leaks.

  A structurally malformed registered schema (childless
  `[:vector]`, unknown op) makes the registered validator THROW at
  validate-time (Malli validates schema forms lazily). `validate-app-schema!`
  isolates that throw PER-ENTRY, emits the distinct
  `:rf.error/malformed-schema` category, fails CLOSED (in-band `false` →
  the router rejects the candidate, which never installs), and keeps
  validating the frame's sibling schemas so one bad registration cannot
  disable the frame's pre-install app-db validation."
  (:require [re-frame.elision :as rf.elision]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.schemas.storage :as rf.schemas.storage]
            [re-frame.schemas.validator :as rf.schemas.validator]
            [re-frame.schemas.walker :as rf.schemas.walker]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Canonical privacy sentinel for value-bearing trace slots.
(def ^:private redacted-sentinel rf.privacy/redacted-sentinel)

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

;; The value-bearing slots that carry the CHECKED value, so the `:large?`
;; size marker may stand in for them. `:rf.sub/query-v` is value-bearing for
;; privacy (it is the lookup key) but it is not the checked value: a marker
;; there would replace the query vector Xray matches a sub-return violation
;; by with a description of a different value.
(def ^:private size-elided-slots
  (filterv #(not= :rf.sub/query-v %) value-bearing-slots))

;; ---- :large? size-elision of validation-failure value slots ---------------
;;
;; A `:large?`-flagged slot inside the checked value ships the whole blob into
;; the validation-failure trace's value-bearing slots unless the emit-site
;; elides it. Per Spec 010 §`:large?` (the validation size-safety arm) and
;; §Composition with `:large?` (sensitive wins), the emit-site substitutes the
;; `:rf.size/large-elided` marker for each value-bearing slot that carries the
;; checked value when the schema declares any `:large?` slot and the slot's
;; own `:sensitive?` decision did not redact it — a redacted slot already
;; scrubs to `:rf/redacted`, and a sensitive marker would itself leak the
;; secret's `:path` / `:bytes` size signature. The canonical
;; `re-frame.elision/->marker` owns the wire shape.

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

  `:path []` — the marker substitutes the WHOLE slot it stands in for (a
  single marker, not a path-walk), so its path is relative to that slot's
  value: the whole checked value, or the failing leaf on the app-db
  surface's narrowed `:value`."
  [v]
  (rf.elision/->marker v [] {:reason :effect :hint nil}))

(defn- elide-large-slots
  "Substitute the `:rf.size/large-elided` marker for `v` (the value those
  slots carry) into each of `slots` present in `tags` — every
  `size-elided-slots` entry by default — and stamp `:large? true`.
  `contains?`-guarded so a slot a surface doesn't carry is a no-op. Per
  Spec 010 §`:large?` validation size-safety arm.

  Called only when the sensitive decision redacted nothing (sensitive wins —
  see `redact-tags`): a slot already scrubbed to
  `:rf/redacted` must never be replaced by a size marker that re-leaks the
  secret's `:bytes` signature."
  ([tags v] (elide-large-slots tags v size-elided-slots))
  ([tags v slots]
   (let [marker (large-marker v)]
     (-> (reduce (fn [t slot]
                   (cond-> t (contains? t slot) (assoc slot marker)))
                 tags
                 slots)
         (assoc :large? true)))))

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
  under the schema-wide decision, stamping `:sensitive? true`. Per
  Spec 010 §`:sensitive?` — privacy in schema-validation error traces.
  Idempotent — safe to call on an already-redacted map.

  Every surface redacts through this one fn under that decision: `check!`
  through `redact-validation-tags`, and the app-db hot path, whose narrowed
  `:value` follows the same rule as the whole-payload slots beside it.

  The six candidate slots (`:value`, `:received`, `:explain`,
  `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`) are the canonical set
  per the Spec 010 §`:sensitive?` redaction-shape list. Three are per-surface /
  conditional names carried only on a subset of emit-sites (`:rf.fx/args` on
  `:where :fx-args`; `:rf.sub/query-v` on `:where :sub-return`;
  `:explain-humanized` only when a humanizer is installed); the `contains?` guards make those clauses no-ops on the surfaces
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

(def ^:private app-db-whole-slots
  "The `:app-db` failure slots that re-ship the whole registered value."
  [:explain :explain-humanized])

(defn- project-through-registry
  "Project an `:app-db` failure's value-bearing slots through `frame-id`'s
  elision registry — the one classification `:rf.sub/run` and
  `:rf.flow/computed` apply at emit — so a path classified there (a
  commit-plane effect, a flow's own output marks, a subsystem declaration)
  needs no second `:sensitive?` on its schema. Runs after the schema-driven
  redaction, which stays an additional way to mark a slot.

    - `:value`, narrowed to the failing leaf, is walked at the leaf's own
      path (`leaf-path`), so it is path-precise like every wire slot. A slot
      the schema already redacted or size-elided is left alone.
    - `:explain` / `:explain-humanized` re-ship the whole registered value
      and are not path-anchored, so they cannot be walked like `:value`.
      When the registry classifies anything in that value sensitive they are
      redacted whole and the trace is stamped `:sensitive? true`; otherwise,
      when it classifies anything large, a size marker stands in for them.

  The registry walked is the one the candidate transition would install,
  `runtime-db-after`'s (nil when the transition leaves runtime-db alone, so the
  committed registry stands). The failure is projected before that commit, and
  a classification effect returned by the event that wrote the failing value
  must already govern it, though the rejection installs nothing.

  The walker fails closed: a frame it cannot resolve redacts the whole value,
  which reads here as sensitive."
  [tags frame-id runtime-db-after registered-path registered-value leaf-path leaf-value]
  (let [walk       (fn [v path extra]
                     (rf.elision/elide-candidate-value
                       v (merge {:frame frame-id :path path} extra) runtime-db-after))
        sensitive? (not= registered-value
                         (walk registered-value registered-path {:rf.egress/include-large? true}))
        large?     (and (not sensitive?)
                        (boolean (some rf.elision/marker?
                                       (tree-seq coll? seq (walk registered-value registered-path nil)))))]
    (cond-> tags
      (and (contains? tags :value) (= leaf-value (:value tags)))
      (assoc :value (walk leaf-value leaf-path nil))

      sensitive?
      (-> (scrub-slots app-db-whole-slots) (assoc :sensitive? true))

      large?
      (elide-large-slots registered-value
                         (remove #(= redacted-sentinel (get tags %)) app-db-whole-slots)))))

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

(defn- failing-leaf-value
  "The failing datum at `in-path` (as `failing-in-path` derived it).

  When every error shares that one `:in` (the single-error case among them),
  the first error's own `:value` is the failing datum. That is not always
  the value AT `:in`: a `:map-of` KEY failure reports the key under the same
  `:in [k]` as the entry, so `get-in` would hand back the entry's VALUE —
  possibly valid — as the thing that failed. When the
  errors diverge, `in-path` is their common ancestor and no single error
  owns it, so the value is read from `registered-value`. An error without a
  `:value` key (a non-Malli explainer) falls back to `get-in` too."
  [explanation in-path registered-value]
  (if (nil? in-path)
    registered-value
    (let [errors (seq (:errors explanation))
          error  (first errors)]
      (if (and (contains? error :value)
               (every? #(= in-path (:in %)) errors))
        (:value error)
        (get-in registered-value in-path)))))

(defn- reason-string
  "Build the human-readable reason for a validation failure. Each caller
  supplies a distinctive literal tail so the production-elision gate can
  prove every dev-only surface absent from optimized bundles."
  [subject id-or-path slot-tail schema value]
  (str subject id-or-path slot-tail (pr-str schema)
       ", got " (rf.error/type-of-value value) "."))

(defn- humanize-explain
  "Compute the operator-readable `:explain-humanized` payload from a
  raw explainer output (per Spec 010 §Humanize-hook).
  Returns the humanized shape when the installed port carries a
  `:humanize` fn and `explanation` is non-nil; nil otherwise (no
  humanizer — a port without one, or a production build — or the
  explainer produced nothing).

  Call this before privacy redaction so sensitive failures retain a present
  but redacted `:explain-humanized` slot rather than losing the slot entirely.

  Failures inside the humanizer degrade silently (humanize is a
  cosmetic enrichment; a thrown humanizer can't suppress the
  failure trace itself)."
  [explanation]
  (when (some? explanation)
    (try (rf.schemas.validator/run-humanizer explanation)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- emit-validation-failure!
  "Single emit point for `:rf.error/schema-validation-failure` traces. A
  thin wrapper over `trace/emit-error!`: the tags arrive already built and
  redacted, `:explain-humanized` included, so the redaction can scrub the
  humanized slot symmetrically with `:explain`."
  [tags]
  (rf.trace/emit-error! :rf.error/schema-validation-failure tags))

(defn- record-type-tag
  "TOTAL, CLOSED type tag for the always-on `:errors` record's `:reason`.
  Returns one of nine string literals and nothing else, ever.

  ## Why this exists rather than `error/type-of-value`

  `type-of-value` is the right helper for the DEV TRACE, whose reason is
  already unbounded (it interpolates `(pr-str schema)`) and which no
  application listener receives. It is the wrong one HERE, and the reason is
  a disclosure rather than a size: its fallback arm is `(str (type v))`, and
  on ClojureScript `type` IS `(.-constructor x)` — `cljs/core.cljs` defines it
  as exactly that, docstring \"Return x's constructor\".

  `constructor` is an ORDINARY, WRITABLE property name. A foreign JS value
  carrying its own `constructor` field returns that field's text verbatim, so
  the supposed type tag is PAYLOAD wearing the costume of trusted runtime
  structure. A `js-obj` with `constructor` set to a sentinel string returns
  the sentinel, which `emit-app-db-rejection-record!` would then concatenate
  after `\"got \"` and publish unchanged to the corpus-wide `:errors`
  listener registry — defeating this record's whole closed-shape/no-payload
  guarantee, the one thing that lets a dev-only check ride the always-on
  stream at all.

  ## Why not a host classification either

  The obvious repairs are both value-controlled too, which is why the
  fallback here is a CONSTANT rather than a cleverer lookup:

    - `Object.prototype.toString.call(v)` is steered by `Symbol.toStringTag`,
      an ordinary own property — it returns `[object <attacker text>]`.
    - `(.-name (.-constructor v))` re-reads the same overridable slot.

  So the only arms permitted here are predicates that read the VALUE'S SHAPE
  through the runtime rather than through a property the value chooses:
  `typeof`, `instanceof` (which consults the CONSTRUCTOR's
  `Symbol.hasInstance`, never the value's), and CLJS protocol dispatch. The
  cheap, non-property-reading predicates are ordered FIRST so a foreign value
  reaches the protocol arms only after the primitives have declined it.

  A value that masquerades — a JS object with a planted `cljs$core$IMap$`
  slot, say — is classified `\"map\"`. That is a WRONG tag but not a leak,
  which is the property that matters: every arm yields a framework literal,
  so no path returns caller text.

  ## Total, including against hostile code

  `map?` / `vector?` are protocol lookups, i.e. property GETs, and a Proxy
  can install a `get` trap that throws (reading `.constructor` through such
  a proxy raises). A diagnostic must not explode while explaining a
  rejection, so the whole cond is wrapped and a throwing value classifies
  as `\"object\"`.

  The eight named tags are `error/type-of-value`'s documented vocabulary,
  reused deliberately: this record's reason is a SUBSET of the framework's
  reason-string vocabulary, so no consumer learns a new word. Only the
  unbounded host-class fallback differs: here it is `\"object\"`."
  [v]
  (try
    (cond
      (nil? v)     "nil"
      (string? v)  "string"
      (integer? v) "integer"
      (number? v)  "number"
      (boolean? v) "boolean"
      (keyword? v) "keyword"
      (map? v)     "map"
      (vector? v)  "vector"
      :else        "object")
    (catch #?(:clj Throwable :cljs :default) _ "object")))

(defn- emit-app-db-rejection-record!
  "Fan ONE structural-only `:rf.error/schema-validation-failure` record onto
  the always-on `:errors` stream for a rejected `app-db` candidate — the
  `:where :app-db`, `:rollback? true` arm.

  ## Why a dev-only check reports on the always-on stream

  The promotion criterion (Spec 009 §The promotion criterion) says which
  categories MUST ride the always-on axis in EVERY build. It does not forbid
  a check that is compiled out of production from reporting, in the build
  where it runs, that it discarded a whole transaction. `always-on` describes
  the STREAM's survival, not a promise that every producer exists in every
  build.

  And without it the silence would be total. A rejected candidate leaves the
  page rendering its pre-event state with nothing thrown, nothing logged, no
  failed request, and — but for this record — nothing on the stream an
  application registers to hear about its own errors. The dev TRACE carries
  the whole story and has no default listener, so unless the developer has
  Xray open and knows to look, an application-wide permanent rollback loop
  simply renders empty.

  ## Production is untouched BY CONSTRUCTION

  This is called from inside `validate-app-schema!`'s outermost
  `(if interop/debug-enabled? …)` gate — the same gate as the check itself —
  so `:advanced` + `goog.DEBUG=false` (and `-Dre-frame.debug=false` on the
  JVM) carries neither the call nor the reason string's literals. The elision
  probe pins the reason's distinctive tail ABSENT from the release bundle;
  `clojure -M:test:prod-gate` in core pins zero records under the JVM gate.

  ## The record is BUILT FROM structural inputs, never filtered from `tags`

  Same discipline as the `:boundary? true` arm's
  `emit-boundary-rejection-record!` (router.cljc), and for the same reason:
  a validation failure's natural detail is the value that failed. So the key
  set is CLOSED and every member is composed here from a framework keyword,
  the developer's own registration root, or an event / frame id.

  Deliberately OMITTED, each for a reason:

    - `:path` — the trace's `:path` is the registered root conj'd with the
      failing leaf's Malli `:in`, and `:in` segments are not all structural:
      a `:set` failure's segment IS the failing element value and a `:map-of`
      key rides as the key. The trace redacts those only when the schema is
      sensitive, so a set element under a schema declaring nothing
      sensitive would ship verbatim. A structural-path projection would have
      to drop those segment kinds first.
    - `:value` / `:received` / `:explain` / `:explain-humanized` / `:schema` —
      the payload itself, and the schema FORM (unbounded `pr-str`).

  All of them stay on the dev trace, which this record does not alter: Xray,
  Story and the epoch recorder read the trace.

  ## The reason is composed, not copied

  `reason-string` interpolates `(pr-str schema)` — unbounded — so it cannot
  ride here. This sentence is built from the registered path and
  `record-type-tag` alone — a CLOSED nine-literal vocabulary, never
  `error/type-of-value`, whose host-class fallback `(str (type v))` reads the
  value's own overridable `constructor` on CLJS and so would return caller
  text through this slot (see that fn's docstring for why the obvious
  host-side repairs are value-controlled too). The tag is what the
  unowned-error console fallback prints when
  nothing owns the `:errors` stream: a red line naming a registered path and
  ending `got string` says which slice a handler wrote with the wrong type
  without printing the value.

  Reached through the `:error-emit/dispatch-error-record` late-bind hook, the
  general non-event union-record chokepoint — never a static require, which
  would close a load cycle from the schemas artefact into core's error-emit
  namespace. A nil hook (error-emit not loaded) is a silent skip.

  Returns nil."
  [registered-path event-id frame-id leaf-value]
  (when-let [dispatch-error-record! (rf.late-bind/get-fn
                                      :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:rf.error/id     :rf.error/schema-validation-failure
       :where           :app-db
       :registered-path registered-path
       :event-id        event-id
       :failing-id      event-id
       :frame           frame-id
       :rollback?       true
       :recovery        :no-recovery
       ;; The tail is ONE string literal on purpose: `str` compiles to a
       ;; runtime concat, so a tail split across two literals would never
       ;; appear contiguously in a compiled bundle and `check-elision.cjs`
       ;; could not prove it absent.
       :reason          (str "App-db candidate at registered path "
                             registered-path " failed its schema (got "
                             (record-type-tag leaf-value)
                             "); the candidate transition was rejected and nothing installed.")
       :time            (rf.interop/now-ms)}))
  nil)

(defn- emit-malformed-schema-rejection-record!
  "Fan ONE structural-only `:rf.error/malformed-schema` record onto the
  always-on `:errors` stream for a candidate transition REJECTED because a
  registered `app-db` schema is itself malformed.

  Sibling of `emit-app-db-rejection-record!` above, for the same reason it is
  a sibling rather than a shared seam: the union record is BUILT FROM this
  arm's own structural inputs, so no `:where` switch exists for a future arm
  to fall through by default.

  ## Why this arm belongs on the stream with the other one

  It is the SAME consequence — `:rollback? true`, the whole candidate
  discarded, nothing installed — reached through the schema FORM rather than
  the value. Leaving it trace-only would split one error class in two: a
  developer whose non-nilable schema rejects every commit would see red
  console lines, while one whose childless `[:vector]` rejects every commit
  would see an app that silently stops updating. Both are the
  same programming error class, and both are dev-only, so both report from
  inside `validate-app-schema!`'s own `debug-enabled?` gate.

  ## The `:reason` is a CONSTANT sentence, and that is the point

  The dev trace's reason for this arm interpolates the THROWING VALIDATOR'S
  MESSAGE. That message is unbounded and author-controlled — a user-supplied
  `set-schema-fns!` validator may say anything, and Malli's own form errors
  `pr-str` the offending schema — so it cannot ride a record that fans out to
  corpus listeners and a frame's `:observability :errors` sink. This sentence
  is composed from the registered path alone (the literal vector the
  application author wrote in their own `reg-app-schema` call) plus framework
  prose. The validator's message stays on the DCE'd dev trace.

  `:schema` (the malformed registration form), `:path`, and every
  value-bearing slot are omitted for the reasons `emit-app-db-rejection-
  record!` sets out — and this arm carries no value at all: the validator
  threw, so it never proved the slot's sensitivity, and omitting the value
  is the category's fail-closed posture.

  Reached through the `:error-emit/dispatch-error-record` late-bind hook,
  never a static require into core's error-emit namespace. A nil hook is a
  silent skip. Returns nil."
  [registered-path event-id frame-id]
  (when-let [dispatch-error-record! (rf.late-bind/get-fn
                                      :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:rf.error/id     :rf.error/malformed-schema
       :where           :app-db
       :registered-path registered-path
       :event-id        event-id
       :failing-id      event-id
       :frame           frame-id
       :rollback?       true
       :recovery        :no-recovery
       ;; ONE string literal for the tail — `str` compiles to a runtime
       ;; concat, so a tail split across two literals could never be proven
       ;; absent from a release bundle by `check-elision.cjs`.
       :reason          (str "Registered app-db schema at path " registered-path
                             " is malformed and could not be evaluated; the candidate transition was rejected fail-closed and nothing installed.")
       :time            (rf.interop/now-ms)}))
  nil)

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
  surfaces carry structural locator tags but never include a value-bearing
  slot because the validator did not establish sensitivity."
  [tags]
  (rf.trace/emit-error! :rf.error/malformed-schema tags))

;; ---- the artefact's validation primitives ---------------------------------
;;
;; `check!` composes these. They stay public (implementation tier) for the
;; artefact's own tests; every surface outside the artefact reaches
;; validation through the `:schemas/check!` hook instead.

(defn validate-with-registered-fn
  "Apply the registered validator to `(schema, value)` and answer the
  verdict alone: true on conform, and when no validator is installed (a nil
  `:validate` disables validation); false on a failure. A validator that
  THROWS on a malformed schema answers false, so a verdict-only caller never
  reads a broken schema as a pass. `check!` is the seam that tells the two
  apart and reports them.

  Does not consult `interop/debug-enabled?`."
  [schema value]
  (try
    (boolean (rf.schemas.validator/run-validator schema value))
    (catch #?(:clj Throwable :cljs :default) _ false)))

(defn explain-with-registered-fn
  "Apply the registered explainer to `(schema, value)`: its explanation, or
  nil when no explainer is installed. A throwing explainer degrades to nil,
  because a diagnostic must never change the verdict it explains."
  [schema value]
  (try
    (rf.schemas.validator/run-explainer schema value)
    (catch #?(:clj Throwable :cljs :default) _ nil)))

(defn redact-validation-tags
  "Redact a validation failure's `tags` against the `schema` its value was
  checked against. Per Spec 010 §`:sensitive?` — privacy in
  schema-validation error traces, and §`:large?`.

    - When the schema declares any slot `:sensitive?`, or the walker cannot
      see all of it (an opaque root, or an opaque descendant inside a
      walkable vector form, where Malli may honour a `:sensitive?` slot the
      walker never sees), every value-bearing slot (`:value`, `:received`,
      `:explain`, `:explain-humanized`, `:rf.fx/args`, `:rf.sub/query-v`)
      scrubs to `:rf/redacted` and `:sensitive? true` is stamped.
    - Otherwise, when it declares any slot `:large?`, the slots carrying the
      checked value take the `:rf.size/large-elided` marker built from
      `(:value tags)`, and `:large? true` is stamped. Sensitive wins, because
      a size marker would carry the secret's size.

  The decision is whole-schema because every slot it governs carries the
  whole checked value, so a conforming sensitive sibling of the slot that
  failed rides inside it. Idempotent."
  [schema tags]
  (let [sensitive? (or (rf.schemas.walker/schema-has-sensitive? schema)
                       (rf.schemas.walker/schema-has-opaque-child? schema))
        large?     (and (not sensitive?)
                        (rf.schemas.walker/schema-has-large? schema))]
    (cond-> tags
      sensitive? redact-tags
      large?     (elide-large-slots (:value tags)))))

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

;; ---- `check!` — the one validation seam -----------------------------------

(defn- failure-tags
  "The failure tags for `value` failing `schema`, redacted and size-elided.

  Every value-bearing slot carries the WHOLE checked value, so the
  redaction is the whole-schema `redact-validation-tags` decision: a
  conforming `:sensitive?` sibling of the slot that failed rides inside the
  payload. `:explain-humanized` is built from the raw explanation BEFORE
  redaction, so a sensitive failure carries the slot as `:rf/redacted`
  rather than losing it, and only in development builds: the humanizer is a
  development-only port (Spec 010 §Humanize-hook). `reason` is a thunk,
  called only in development, so a surface's reason prose never reaches a
  release bundle through a check that runs there."
  [{:keys [where id schema value frame recovery extra-tags reason]}]
  (let [explanation (explain-with-registered-fn schema value)
        humanized   (when rf.interop/debug-enabled? (humanize-explain explanation))]
    (redact-validation-tags
      schema
      (cond-> (merge {:where    where
                      :value    value
                      :received value
                      :explain  explanation
                      :recovery recovery}
                     extra-tags)
        (some? id)                          (assoc :failing-id id)
        (some? frame)                       (assoc :frame frame)
        (some? humanized)                   (assoc :explain-humanized humanized)
        (and rf.interop/debug-enabled? reason) (assoc :reason (reason))))))

(defn- malformed-tags
  "The `:rf.error/malformed-schema` tags for a validator that threw `ex` on
  `schema`: the surface's structural slots and the schema form to fix, with
  every value-bearing slot omitted. The validator never decided the value,
  so nothing proved it non-sensitive, and omitting it is the fail-closed
  choice."
  [{:keys [where id schema frame recovery extra-tags]} ex]
  (cond-> (merge {:where where :schema schema :recovery recovery}
                 (apply dissoc extra-tags value-bearing-slots))
    (some? id)                (assoc :failing-id id)
    (some? frame)             (assoc :frame frame)
    rf.interop/debug-enabled? (assoc :reason (str "Registered schema " (pr-str schema)
                                                  " is malformed and could not be "
                                                  "evaluated: " (ex-message ex)))))

(defn check!
  "Validate `value` against `schema` with the installed validator and, on a
  failure, build the tags every validating surface reports it with. The one
  seam behind `validate-event!`, `validate-fx!` and `validate-sub!`, and the
  `:schemas/check!` late-bind hook every validating surface outside this
  artefact calls. Spec 010 §The validation seam.

  `request` is a map:

    :where       the surface (`:event`, `:sub-return`, `:machine-data`, …)
    :id          the failing id, stamped as `:failing-id`
    :schema      the declared schema, delegated to the validator verbatim
    :value       the checked value
    :frame       the frame the failure belongs to, stamped as `:frame`
    :recovery    the recovery the caller applies; default `:no-recovery`
    :extra-tags  the surface's own structural tags (`:event-id`,
                 `:machine-id`, `:phase`, `:rollback?`, `:source`, …)
    :reason      `(fn [] reason-string)`, called in development only
    :tags-only?  true to receive the tags without the seam emitting them

  Returns nil when the value conforms, or no validator is installed (a nil
  `:validate` disables validation). Otherwise returns `{:category :tags}`:

    - `:category` is `:rf.error/schema-validation-failure`, or
      `:rf.error/malformed-schema` when the validator THREW — a malformed
      schema never reads as a pass or as an ordinary failure.
    - `:tags` are the redacted, size-elided failure tags: `:where`,
      `:failing-id`, `:frame`, `:value` / `:received` / `:explain`, in
      development `:explain-humanized` and `:reason`, `:sensitive? true` or
      `:large? true` when the schema says so, and the caller's extra tags.
      A malformed schema's tags carry `:schema` and no value-bearing slot.

  Unless `:tags-only?`, the seam emits the trace under `:category` itself.
  The caller then applies its own recovery: abort, skip, observe or throw.

  Production builds pay only for what they report. With
  `interop/debug-enabled?` false an emitting check builds no tags, because
  the trace it would carry is elided; a tags-only check builds them, for the
  caller's always-on error or thrown ex-data, without the development-only
  humanizer and reason."
  [{:keys [schema value tags-only?] :as request}]
  (when-let [validate-fn @rf.schemas.validator/validator-fn]
    (let [verdict (validate-entry-result validate-fn schema value)]
      (when-not (true? verdict)
        (let [request (update request :recovery #(or % :no-recovery))
              thrown  (when (vector? verdict) (second verdict))]
          (if (or tags-only? rf.interop/debug-enabled?)
            (let [tags (if thrown (malformed-tags request thrown) (failure-tags request))]
              (when-not tags-only?
                (if thrown (emit-malformed-schema! tags) (emit-validation-failure! tags)))
              {:category (if thrown :rf.error/malformed-schema :rf.error/schema-validation-failure)
               :tags     tags})
            {:category (if thrown :rf.error/malformed-schema :rf.error/schema-validation-failure)}))))))

(defn- run-validation
  "The meta-bearing surfaces' (event / fx / sub) call into `check!`.
  `reg-meta`'s `:schema` is the declaration when its KEY is present: a
  present nil / false token is delegated to the validator verbatim, the way
  `validate-app-schema!` passes every registration through, and only an
  absent key means nothing to check. Returns true on pass, no declaration or
  no validator, false on a reported failure.

  Every call site sits inside its public wrapper's outermost
  `(if interop/debug-enabled? ...)` gate, so under `:advanced` +
  `goog.DEBUG=false` the request maps and their reason literals DCE."
  [reg-meta request]
  (or (not (contains? reg-meta :schema))
      (nil? (check! (assoc request :schema (:schema reg-meta))))))

(defn- unchecked-slice?
  "True when the commit has nothing at `path` to check: the candidate lacks
  the path, or (given `db-before`) holds the same value there as the
  installed db, present in each and `identical?`. A path schema constrains a
  value only where its path exists — whether a key must exist is said by an
  enclosing schema, usually the root `[]`, with required map entries. A
  present `nil` is a value and is checked."
  [db-before db-after path]
  (let [after (get-in db-after path ::absent)]
    (or (= ::absent after)
        (and (some? db-before)
             (identical? after (get-in db-before path ::absent))))))

(defn validate-app-schema!
  "Before a handler's :db installs, walk the named frame's registered
  app-schemas and validate the candidate. Failures trace as
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
  `:boundary? true` step-1 arm and the other callers of the `check!` seam,
  none of which is gated this way. Per Spec 010 §Production builds.

  The schemas walked are the ones the named frame RESOLVES (Spec 010
  §Schemas resolve through the frame's images): every registration its
  images, or the live store, select — a schema is not owned by a frame.

  Validation routes through the registered validator/explainer functions.
  When `set-schema-fns!` has installed a `nil` `:validate`
  this fn is a hard no-op for every schema in the frame.

  Arities:
    (validate-app-schema! db)                            ;; current frame
    (validate-app-schema! db event-id)                   ;; current frame, named handler
    (validate-app-schema! db event-id frame-id)          ;; explicit frame
    (validate-app-schema! db event-id frame-id db-before)
    (validate-app-schema! db event-id frame-id db-before runtime-db-after)

  event-id (optional) names the handler whose commit prompted the
  failure — surfaced as :failing-id in the error tags.

  A registered path the candidate lacks is skipped in every arity: a path
  schema constrains a value only where its path exists, and a key's
  required presence is declared by an enclosing schema (usually the root
  `[]`) with required map entries. A present value, `nil` included, is
  checked.

  db-before (the 4-arity) is the app-db the candidate replaces. A registered path
  whose slice the candidate leaves `identical?` to it is skipped, because
  this commit does not write it: a registration the live slice does not
  satisfy is named by `:rf.schema/violation` when it happens, and the
  slice is checked again when an event writes it. A parent path is still
  checked when a write lands beneath it, and `[]` whenever the db changed.
  Without db-before every present entry is checked.

  runtime-db-after (the 5-arity, and the shape the router's late-bind call
  uses — it invokes the hook as
  `(validate db-after event-id frame db-before runtime-db-after)`) is the
  candidate runtime-db the same transition would install, nil when it leaves
  runtime-db alone. A failure projects through ITS elision registry, so a
  classification effect returned by the event that wrote the failing value
  governs the failure trace. Without it the frame's committed registry is
  walked.

  Returns:
    true   — every registered schema conformed (or no validator /
             no schemas resolved for the frame / debug elided).
    false  — at least one schema failed; a trace event was emitted
             for every failing entry. The router consumes this signal
             to reject the candidate, leaving the pre-handler value live.

  Structurally distinct from the three meta-bearing validate-*! fns
  (event / fx / sub): walks N schemas in a loop, has no
  single `:schema`-on-meta lookup, and emits a trace per failure (rather
  than at-most-one). Returns a single boolean conjoining every entry's
  result so the caller can decide rollback deterministically — but
  every failing schema is still surfaced as its own trace so consumers
  see the full set."
  ([db] (validate-app-schema! db nil (rf.frame/current-frame)))
  ([db event-id] (validate-app-schema! db event-id (rf.frame/current-frame)))
  ([db event-id frame-id]
   (validate-app-schema! db event-id frame-id nil nil))
  ([db event-id frame-id db-before]
   (validate-app-schema! db event-id frame-id db-before nil))
  ([db event-id frame-id db-before runtime-db-after]
   ;; Per Spec 009 §Production builds the entire body lives inside a
   ;; `(if interop/debug-enabled? ... true)` gate as the OUTERMOST form
   ;; so :advanced + goog.DEBUG=false DCE-elides every reason string,
   ;; keyword, validator deref, and trace call. Production builds
   ;; return `true` unconditionally — candidate rejection is dev-only
   ;; (pre-install validation is gated by debug-enabled?, so no
   ;; failure is observable to reject on). The `@validator-fn`
   ;; check is a runtime atom deref and must NOT be combined into the
   ;; gate predicate (the deref defeats Closure's reachability proof).
   ;;
   ;; Dereference the validator once outside the per-schema loop.
   (if rf.interop/debug-enabled?
     (if-let [validate-fn @rf.schemas.validator/validator-fn]
       ;; A loop rather than doseq, so we can emit
       ;; a trace per failure (full surface for consumers) AND return
       ;; a single conjoined boolean (single signal for the rollback
       ;; gate). Pass-state stays `true` only when every entry passed.
        (loop [entries (let [{:keys [entries ambiguous]}
                             (rf.schemas.storage/frame-schema-resolution frame-id)]
                         ;; A path more than one namespace registers fails
                         ;; loudly the moment a candidate puts a value there to
                         ;; check: the frame refuses it, and validating without
                         ;; it would drop the schema silently.
                         (doseq [path ambiguous]
                           (when-not (unchecked-slice? db-before db path)
                             (rf.schemas.storage/throw-ambiguous-schema! frame-id path)))
                         (seq (remove #(unchecked-slice? db-before db (key %)) entries)))
               ok?     true]
          (if-let [[registered-path registration-metadata] (first entries)]
            (let [registered-value  (get-in db registered-path)
                  schema            (:schema registration-metadata)
                  ;; A malformed schema cannot abort sibling validation or
                  ;; escape to a caller that treats defensive throws as pass.
                  validation-result (validate-entry-result validate-fn schema registered-value)]
              (cond
               ;; Conformed — carry the running pass-state forward.
               (true? validation-result)
               (recur (next entries) ok?)

               ;; Malformed registered schema (validator threw). Surface a
               ;; DISTINCT `:rf.error/malformed-schema` trace and fail
               ;; CLOSED (`ok? false` → the router rejects the candidate; it
               ;; never installs blind). Continue to the sibling entries so one
               ;; bad schema does not disable validation frame-wide.
               (and (vector? validation-result)
                    (= :malformed (first validation-result)))
               (let [validator-error (second validation-result)
                     reason          #?(:clj  (.getMessage ^Throwable validator-error)
                                        :cljs (ex-message validator-error))]
                 ;; The always-on `:errors` record FIRST, then the
                 ;; dev trace — `emit-error-both!`'s axis-1-then-axis-2
                 ;; ordering, so the JVM SSR listener's last-write-wins buffer
                 ;; keeps the richer trace as its final input. Both sit inside
                 ;; `validate-app-schema!`'s outermost `debug-enabled?` gate.
                 (emit-malformed-schema-rejection-record!
                   registered-path event-id frame-id)
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
                 (recur (next entries) false))

               ;; Legitimate validation failure.
               ;;
               ;; The registered explainer runs once; its output feeds the
               ;; trace's `:explain` slot and the failing-leaf extraction.
               ;; `failing-in-path` is the value-navigation path Malli reports
               ;; as `:in` (not the schema-walk `:path`, which carries `:multi`
               ;; / `:orn` dispatch values), so the trace's `:path` — the
               ;; registered root conj'd with it — is a slot a consumer can
               ;; `get-in` on a non-failed copy of app-db. With no extractable
               ;; `:in` (a non-Malli explainer, no explanation) `:path` is the
               ;; registered root; `:registered-path` always is.
               ;;
               ;; One redaction decision governs every value-bearing slot, the
               ;; narrowed `:value` included: the schema marks a slot
               ;; `:sensitive?`, or hides one from the walker (Spec 010
               ;; §`:sensitive?`). Sensitive wins over large.
               :else
               (let [explanation   (explain-with-registered-fn schema registered-value)
                     in-path       (failing-in-path explanation)
                     ;; Usually the value at `in-path`, but the key itself on a
                     ;; `:map-of` key failure (see `failing-leaf-value`).
                     leaf-value    (failing-leaf-value explanation in-path registered-value)
                     sensitive?    (or (rf.schemas.walker/schema-has-sensitive? schema)
                                       (rf.schemas.walker/schema-has-opaque-child? schema))
                     large?        (and (not sensitive?)
                                        (rf.schemas.walker/schema-has-large? schema))
                     ;; A sensitive failure's `:path` keeps the declared `:map`
                     ;; keys and redacts every later segment, which can be
                     ;; data. `:reason` is built from the same path.
                     trace-in-path (if (and in-path sensitive?)
                                     (let [kept (rf.schemas.walker/declared-key-prefix schema in-path)]
                                       (into kept (repeat (- (count in-path) (count kept))
                                                          redacted-sentinel)))
                                     in-path)
                     trace-path    (if trace-in-path
                                     (into (vec registered-path) trace-in-path)
                                     registered-path)
                     ;; Humanized before redaction, so `:explain-humanized`
                     ;; scrubs symmetrically with `:explain` (sentinel present,
                     ;; not omitted).
                     humanized     (humanize-explain explanation)
                     base-tags     (cond-> {:where           :app-db
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
                     ;; Each size marker describes the value its slot carries:
                     ;; the leaf for `:value`, the whole registered value for
                     ;; `:explain` / `:explain-humanized`.
                     tags          (-> (cond-> base-tags
                                         sensitive? redact-tags
                                         large?     (-> (elide-large-slots registered-value
                                                                           app-db-whole-slots)
                                                        (elide-large-slots leaf-value [:value])))
                                       ;; Then the frame's elision registry, at
                                       ;; the unredacted leaf path.
                                       (project-through-registry
                                         frame-id runtime-db-after
                                         registered-path registered-value
                                         (if in-path
                                           (into (vec registered-path) in-path)
                                           registered-path)
                                         leaf-value))]
                 ;; The always-on `:errors` record FIRST, then the dev trace —
                 ;; `emit-error-both!`'s axis-1-then-axis-2 ordering
                 ;; (error_emit.cljc), so the JVM SSR error-listener's
                 ;; last-write-wins buffer keeps the RICHER trace as its final
                 ;; input.
                 (emit-app-db-rejection-record!
                   registered-path event-id frame-id leaf-value)
                 (emit-validation-failure! tags)
                 (recur (next entries) false))))
            ok?))
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
  anywhere in the event schema (incl. container-level props) drives
  redaction; non-sensitive event failures ride verbatim (the walker
  reports nothing → no redaction)."
  ([event-id event handler-meta]
   (validate-event! event-id event handler-meta nil))
  ([event-id event handler-meta frame]
   (if rf.interop/debug-enabled?
     (run-validation
       handler-meta
       {:where      :event
        :id         event-id
        :value      event
        :frame      frame
        :extra-tags {:event-id event-id :schema-id event-id}
        :reason     #(reason-string "Event " event-id " payload failed schema "
                                    (:schema handler-meta) event)})
     true)))

(defn validate-sub!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :schema on the sub's metadata.
  Failures emit `:rf.error/schema-validation-failure :where
  :sub-return` with `:recovery :no-recovery`; the caller still delivers
  the computed value. Returns true/false per the `run-validation`
  contract.

  The optional frame is stamped on the trace so runtime calls join the
  in-flight epoch. Direct callers may omit it."
  ([sub-id query-v value sub-meta]
   (validate-sub! sub-id query-v value sub-meta nil))
  ([sub-id query-v value sub-meta frame]
   (if rf.interop/debug-enabled?
     (run-validation
       sub-meta
       {:where      :sub-return
        :id         sub-id
        :value      value
        :frame      frame
        :extra-tags {:rf.sub/id sub-id :schema-id sub-id :rf.sub/query-v query-v}
        :reason     #(reason-string "Subscription " sub-id " return value failed schema "
                                    (:schema sub-meta) value)})
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
   (validate-fx! fx-id event-id args fx-meta nil))
  ([fx-id event-id args fx-meta frame]
   (if rf.interop/debug-enabled?
     (run-validation
       fx-meta
       {:where      :fx-args
        :id         fx-id
        :value      args
        :frame      frame
        :recovery   :skipped
        :extra-tags (cond-> {:rf.fx/id fx-id :rf.fx/args args :schema-id fx-id}
                      event-id (assoc :event-id event-id))
        :reason     #(reason-string "Effect " fx-id " args failed schema "
                                    (:schema fx-meta) args)})
     true)))
