(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :spec metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  Per Spec 010 §Per-frame schemas, `app-db` schemas are **frame-scoped**:
  registered against the active frame at registration time and looked
  up per frame at validation time. Stories, multi-instance widgets,
  per-test fixtures need shape-flexibility — a stripped-down schema
  registered against `:story.foo/empty` does NOT bleed into the
  `:rf/default` frame's contract. `(reg-app-schema path schema)` uses
  the active frame (`(frame/current-frame)`); `(reg-app-schema path
  schema {:frame frame-id})` registers explicitly.

  Per Spec 010 §Non-Malli validators (rf2-froe) the validator and
  explainer fns are pluggable via `set-schema-validator!` /
  `set-schema-explainer!`. The default validator delegates to Malli
  (`(resolve 'malli.core/validate)`); apps that want to opt out — to
  drop the ~24 KB gzipped Malli surface measured in
  findings/malli-bundle-cost-audit.md — register a different fn (or
  `nil` for a hard no-op). The `:spec` value remains opaque to the
  framework; only the registered validator interprets it."
  (:require #?(:cljs [goog.crypt :as gcrypt])
            #?(:cljs [goog.crypt.Sha256])
            [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace])
  #?(:clj (:import [java.security MessageDigest]
                   [java.nio.charset StandardCharsets])))

;; ---- pluggable validator / explainer (rf2-froe) ---------------------------
;;
;; Per Spec 010 §Non-Malli validators — the validator-fn extension point.
;; The framework never inspects the value stored in `:spec` directly;
;; every validation site routes through the registered validator fn.
;; This is the seam Malli plugs into by default; apps that want to drop
;; the ~24 KB gzipped Malli surface (rf2-qnxf) call
;; `(rf/set-schema-validator! some-other-fn)` (or `nil` for no-op) at
;; boot before any reg-app-schema / :spec metadata lands.
;;
;; Two fns are registered separately so the validate hot path stays
;; cheap (validate returns truthy/falsey; explain is only invoked on
;; the failure branch to populate the trace's `:explain` key):
;;
;;   :validate (fn [schema value] truthy?)
;;             — same shape as Malli's `validate`. nil disables; the
;;             call site treats nil as "pass everything".
;;
;;   :explain  (fn [schema value] explanation)
;;             — same shape as Malli's `explain`. nil = no explanation
;;             attached to the failure trace.
;;
;; A combined `(set-schema-validator! {:validate ... :explain ...})`
;; arity exists for callers who want to install both atomically.

;; Per rf2-t0hq the CLJS default validator used to reach Malli through
;; runtime `resolve` — but CLJS has no runtime resolve (the symbol is
;; a compile-time analyzer affordance only). The catch silently
;; swallowed the failure and the validator returned true for every
;; value, so Malli was never consulted on CLJS unless the user
;; explicitly called `(rf/set-schema-validator! malli.core/validate)`.
;;
;; The fix is the rf2-froe / rf2-p7va substitute-validator pattern:
;; the `re-frame.schemas.malli` adapter namespace (this artefact)
;; publishes Malli's `validate` and `explain` into the late-bind hook
;; table on ns-load. The default fns below consult the table on every
;; call. Apps opt in to Malli validation on CLJS by requiring
;; `re-frame.schemas.malli` at app boot.
;;
;; On the JVM `requiring-resolve` still works as a no-require fallback
;; so existing JVM tests / apps keep working without an explicit
;; require of the adapter ns; the late-bind hook takes precedence when
;; the adapter ns IS loaded.

(defn- default-malli-validate
  "The default validator — delegates to malli.core/validate via the
  late-bind hook published by `re-frame.schemas.malli` (rf2-t0hq).

  Lookup order:
    1. Late-bind hook `:schemas/malli-validate` (published by
       `re-frame.schemas.malli` when that namespace is loaded).
    2. On the JVM only, fall back to `(requiring-resolve
       'malli.core/validate)` so the JVM artefact tests / apps that
       have Malli on the classpath but don't `:require
       [re-frame.schemas.malli]` keep working.
    3. Soft-pass — return true (per Spec 010 §Recommended soft-pass).

  Apps that want Malli-absent behaviour to be a hard fail register
  a stricter validator via `set-schema-validator!`."
  [schema value]
  (if-let [v (late-bind/get-fn :schemas/malli-validate)]
    (v schema value)
    #?(:clj  (try
               (if-let [v (requiring-resolve 'malli.core/validate)]
                 (v schema value)
                 true)
               (catch Throwable _ true))
       :cljs true)))

(defn- default-malli-explain
  "The default explainer — delegates to malli.core/explain via the
  late-bind hook published by `re-frame.schemas.malli` (rf2-t0hq).
  Same lookup order as `default-malli-validate`. Returns the Malli
  explanation map on fail; nil on conform / when Malli is not on the
  classpath / when the adapter ns is not loaded."
  [schema value]
  (if-let [e (late-bind/get-fn :schemas/malli-explain)]
    (e schema value)
    #?(:clj  (try
               (when-let [e (requiring-resolve 'malli.core/explain)]
                 (e schema value))
               (catch Throwable _ nil))
       :cljs nil)))

(defonce
  ^{:doc "The currently-registered validator fn — `(fn [schema value]
          truthy?)`. Default delegates to Malli; apps swap via
          `set-schema-validator!`. Setting the atom to `nil` disables
          validation everywhere (every `validate-*!` call returns
          true without inspecting the schema)."}
  validator-fn
  (atom default-malli-validate))

(defonce
  ^{:doc "The currently-registered explainer fn — `(fn [schema value]
          explanation)`. Populates the failure trace's `:explain`
          key. Default delegates to Malli's `explain`; nil means no
          explanation is attached."}
  explainer-fn
  (atom default-malli-explain))

(defn set-schema-validator!
  "Register the validator fn that every dev-time validation site routes
  through. Per Spec 010 §Non-Malli validators (rf2-froe) the seam is
  the substitute-Malli extension point — apps that want to drop Malli
  (the ~24 KB gzipped surface measured in the rf2-qnxf bundle audit)
  swap in their own validator at boot, before the first `reg-app-schema`
  or `:spec`-bearing `reg-*` lands.

  Argument shapes:

    (set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely
      Same signature as `malli.core/validate` — truthy on conform,
      falsey on fail. The explainer is left untouched (apps that want
      to also swap explanations call `set-schema-explainer!`).

    (set-schema-validator! {:validate validate-fn :explain explain-fn})
      Atomic swap of both fns at once. Either key may be `nil` to
      disable the corresponding hot path. Keys not supplied leave the
      existing registration in place.

  Per Spec 010 §Non-Malli validators the validator-fn must be pure
  (same `(schema, value)` returns the same result) and must be
  production-elidable alongside `re-frame.interop/debug-enabled?` —
  every call site is already gated on `debug-enabled?`, so the
  validator's body is unreachable in `:advanced` + `goog.DEBUG=false`
  builds.

  Last-write-wins on re-registration. Returns the validator that was
  installed (may be nil)."
  [validate-fn-or-map]
  (cond
    (map? validate-fn-or-map)
    (let [{:keys [validate explain]
           :or   {validate ::unset
                  explain  ::unset}} validate-fn-or-map]
      (when-not (= ::unset validate) (reset! validator-fn validate))
      (when-not (= ::unset explain)  (reset! explainer-fn  explain))
      @validator-fn)

    :else
    (do (reset! validator-fn validate-fn-or-map)
        @validator-fn)))

(defn set-schema-explainer!
  "Register the explainer fn — `(fn [schema value] explanation)` — that
  every failure-trace site calls to enrich the trace's `:explain` key.
  See `set-schema-validator!`. Setting `nil` disables explanations
  (the failure trace simply omits the `:explain` data).

  Last-write-wins. Returns the explainer that was installed (may be
  nil)."
  [explain-fn]
  (reset! explainer-fn explain-fn)
  @explainer-fn)

(defn reset-schema-validator!
  "Reset the validator and explainer atoms back to the default Malli-
  delegating fns. Test-support helper — restores the framework default
  after a test that called `set-schema-validator!` / `set-schema-
  explainer!`."
  []
  (reset! validator-fn default-malli-validate)
  (reset! explainer-fn default-malli-explain))

(defn- run-validator
  "Hot-path entry — invoke the registered validator fn against a
  `(schema, value)` pair. Returns true (pass) when no validator is
  registered (nil) so the call sites treat 'no validator' as 'no
  validation' rather than 'every value fails'."
  [schema value]
  (if-let [f @validator-fn]
    (f schema value)
    true))

(defn- run-explainer
  "Hot-path entry — invoke the registered explainer fn against a
  `(schema, value)` pair. Returns nil when no explainer is registered
  (nil); call sites then omit the `:explain` key from the failure
  trace."
  [schema value]
  (when-let [f @explainer-fn]
    (f schema value)))

;; ---- per-frame storage ----------------------------------------------------
;;
;; Per Spec 010 §Per-frame schemas. The registry shape is
;;   {frame-id {path schema-meta}}
;; mirroring `re-frame.flows`'s frame-scoping (rf2-lvwr). The registrar
;; (`:app-schema` kind) still receives a slot-per-path entry so source-
;; coords / hot-reload tooling can introspect a path's most-recent
;; registration; the per-frame map is the authoritative store the
;; validation hot path reads.

(defonce
  ^{:doc "frame-id → path → schema-meta. Per-frame so a story or test
          fixture's reg-app-schema does not bleed into the default
          frame's contract."}
  schemas-by-frame
  (atom {}))

(defn- resolve-frame
  "Pluck :frame from the opts map; default to (frame/current-frame)."
  [opts]
  (or (:frame opts) (frame/current-frame)))

(defn- coerce-opts
  "Permit the keyword-only sugar `(app-schemas frame-id)` and the
  opts-map form `(app-schemas {:frame frame-id})`."
  [opts-or-frame-id]
  (cond
    (keyword? opts-or-frame-id) {:frame opts-or-frame-id}
    (map?     opts-or-frame-id) opts-or-frame-id
    (nil?     opts-or-frame-id) {}
    :else
    (throw (ex-info ":rf.error/bad-app-schemas-arg"
                    {:received opts-or-frame-id
                     :expected "keyword frame-id or opts map"}))))

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure.

  Per Spec 010 §Per-frame schemas this registration is frame-scoped.
  The frame to register against comes from the optional :frame opt;
  default is (frame/current-frame) — usually :rf/default unless the
  caller is inside a (with-frame ...) wrapper or a frame-provider."
  ([path schema] (reg-app-schema path schema {}))
  ([path schema opts]
   (let [frame-id (resolve-frame opts)
         meta     (source-coords/merge-coords
                    {:schema schema :path path :frame frame-id})]
     ;; Stamp the registrar slot so source-coords / handler-meta /
     ;; hot-reload tooling continue to see :app-schema entries. The
     ;; registrar is per-process; the per-frame map is the
     ;; authoritative store for validation.
     (registrar/register! :app-schema path meta)
     (swap! schemas-by-frame assoc-in [frame-id path] meta)
     path)))

(defn reg-app-schemas
  "Bulk-register a map of `{path -> schema}` against the active frame
  (or the frame named in `opts`). Per rf2-jzs9 — the plural form of
  `reg-app-schema`, designed for feature-modular apps (per Conventions
  §Feature-modularity prefix convention) where a single feature module
  registers 5–20 schemas under a common path prefix like `[:cart …]` or
  `[:auth …]`.

  Shape:

    (rf/reg-app-schemas {[:auth]                AuthSlice
                         [:auth :login-form]    FormSlice
                         [:cart]                CartSlice
                         [:cart :items]         [:vector CartItem]})

    (rf/reg-app-schemas {[:foo] FooSchema} {:frame :tenant/a})

  Per Spec 010 §Per-frame schemas registration is frame-scoped; the
  `:frame` opt overrides the default `(frame/current-frame)` resolution
  for every entry in the map (you cannot mix frames in a single call).
  The singular form `reg-app-schema` remains available and is used
  internally for each entry — every entry stamps its own registrar slot
  with source-coords captured from this call site.

  Returns the vector of paths registered, in iteration order. Last-
  write-wins on duplicate paths inside the same map (map iteration
  order in Clojure is small-map literal-order, large-map hash order;
  callers that rely on deterministic order should use a singular
  `reg-app-schema` chain instead)."
  ([path->schema] (reg-app-schemas path->schema {}))
  ([path->schema opts]
   (mapv (fn [[path schema]] (reg-app-schema path schema opts))
         path->schema)))

(defn app-schema-at
  "Look up the registered schema for a path in a frame, or nil.

  Arities:
    (app-schema-at path)         ;; current frame (or :rf/default)
    (app-schema-at path opts)    ;; opts map; :frame names the frame
                                 ;; (keyword sugar also accepted)

  Per Spec 010 §Schemas as a tooling and agent surface."
  ([path] (app-schema-at path {}))
  ([path opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
     (when-let [m (get-in @schemas-by-frame [frame-id path])]
       (:schema m)))))

(defn app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Pair tools and 10x panels read this to
  introspect what schemas apply in a given frame.

  Arities:

    (app-schemas)              ;; sugar for (app-schemas {})
    (app-schemas frame-id)     ;; sugar for (app-schemas {:frame frame-id})
    (app-schemas opts)         ;; opts is a map; supports {:frame ...}
                               ;; and is the place future opts will land

  Per Spec 010 §Per-frame schemas the result is the schema set
  registered against the named frame (active frame when none is
  given). Schemas registered against a different frame do not appear."
  ([] (app-schemas {}))
  ([opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
     (reduce-kv (fn [acc path m] (assoc acc path (:schema m)))
                {}
                (get @schemas-by-frame frame-id {})))))

(defn frame-schema-entries
  "Internal: return the {path → schema-meta} map for a frame, or {}.
  Used by the validation hot path and the epoch-restore predicates so
  they can read the same authoritative store reg-app-schema writes."
  [frame-id]
  (get @schemas-by-frame frame-id {}))

;; ---- per-slot flag walker (rf2-nwv63 / rf2-kj51z / rf2-oghml) ------------
;;
;; Per Spec 009 §Size elision in traces and Spec 010 §`:sensitive?`, the
;; schema-driven nomination path: any Malli slot carrying a per-slot flag
;; (`:large? true` or `:sensitive? true`) in its per-slot properties
;; (per Spec-Schemas §`:rf/app-schema-meta` — the per-slot metadata
;; vocabulary) is registered into the frame's
;; `[:rf/elision :declarations]` (`:large?`) or
;; `[:rf/elision :sensitive-declarations]` (`:sensitive?`) slot with
;; `:source :schema`. Both registries are sibling slots under the shared
;; `[:rf/elision]` reserved root; flags compose orthogonally — a slot may
;; carry either or both, and the validation walker resolves the conflict
;; at emit time. Storing them separately keeps the per-flag query
;; (`(get-in db [:rf/elision :sensitive-declarations <path>])`) O(1)
;; without value-shape inspection.
;;
;; This file owns the **walker** that maps a registered schema's EDN form
;; to a `{path declaration}` map; the actual app-db write lives in
;; `re-frame.elision` (rf2-v9tw2) so that file owns the unified registry
;; surface across the schema / declared / runtime-flagged sources. The
;; seam between the two is the per-flag late-bind hook registered at the
;; bottom of this file.
;;
;; The walker is **pure data** — it doesn't import malli.core. Malli EDN
;; forms are vectors of the shape `[op props? children...]`; we pattern-
;; match on shape. Per Spec 010 §The `:spec` value is opaque to re-frame,
;; the framework MUST NOT call into the registered validator to introspect
;; schema structure — we walk the EDN ourselves. This means the walker
;; handles the **vector form** (`[:map [:k :string]]`) — the form
;; `(rf/reg-app-schema ...)` users write. Non-vector Malli forms (schema
;; objects, registry refs) are treated as opaque leaves; their internal
;; per-slot declarations are invisible to the walker (the same caveat
;; applies to Malli's own schema-walking when introspection goes through
;; `m/schema` ↔ raw EDN — round-tripping a registry ref loses the slot
;; metadata).
;;
;; The walker is parameterised on the per-slot flag key (`:large?` /
;; `:sensitive?`); both flags share identical structural recognition
;; (`:map` name-bearing, `:multi`/`:orn`/`:catn`/`:altn` dispatch-bearing,
;; positional combinators descend at the same base-path) so a single
;; traversal serves every per-slot annotation under
;; `:rf/app-schema-meta`. Future per-slot annotations (Spec-Schemas
;; reserves additional slots) compose as one-line registrations.

(def ^:private name-bearing-ops
  "Schema ops whose children carry name slots (the first element of a
  child entry is the slot's app-db key)."
  #{:map})

(def ^:private dispatch-bearing-ops
  "Schema ops whose children carry dispatch-value branches (the first
  element of a child entry is a dispatch value, not an app-db path
  segment)."
  #{:multi :orn :catn :altn})

(defn- props-of
  "Return the per-slot props map of a Malli vector form, or nil. Convention:
  `[op {props} children...]` carries the map at position 1."
  [v]
  (let [p (when (and (vector? v) (>= (count v) 2)) (nth v 1))]
    (when (map? p) p)))

(defn- children-of
  "Return the children portion of a Malli vector form — drop the op
  keyword and the optional props map."
  [v]
  (let [tail (subvec v 1)]
    (if (and (seq tail) (map? (first tail)))
      (subvec tail 1)
      tail)))

(defn- decl-from-props
  "Build a declaration map `{flag-key true :source :schema}` (plus
  optional `:hint` propagated verbatim) from a slot's per-slot props,
  or nil when `flag-key` is not set to `true`. Per Spec 009 §Size
  elision in traces and Spec 010 §`:sensitive?` — `:hint` is optional
  and omitted when absent so the marker shape stays minimal. The
  `:source` slot records provenance so the unified registry merger
  (`populate-*-declarations`) can apply the conflict-resolution rule:
  app-declared beats schema beats runtime-flagged."
  [flag-key props]
  (when (true? (get props flag-key))
    (cond-> {flag-key true
             :source  :schema}
      (some? (:hint props)) (assoc :hint (:hint props)))))

(defn walk-flagged-schema
  "Walk a Malli EDN schema form at `base-path`, populating `acc` with
  `{path declaration}` entries for every slot whose per-slot props
  carry `(flag-key → true)`. Pure; same input always produces the same
  output. Used by the per-flag entry points
  (`extract-large-paths-from-schema`, `extract-sensitive-paths-from-schema`).

  `flag-key` is the per-slot annotation key (`:large?` or `:sensitive?`).

  Structural rules:

    - `:map` children are name-bearing — `[k schema]` / `[k {props} schema]`
      — and the flag may live in the slot's own props (claims `(conj base k)`)
      OR the child schema's own props (also claims `(conj base k)`, since
      the path is the same).

    - `:multi` / `:orn` / `:catn` / `:altn` children are dispatch-bearing —
      `[v schema]` / `[v {props} schema]` — and the flag on a branch's
      slot props claims the parent path (the op's `base-path`), not a
      child path; dispatch values aren't path segments.

    - Positional / nameless container ops (`:vector`, `:set`, `:sequential`,
      `:maybe`, `:and`, `:or`, `:not`, `:tuple`, `:cat`, …) descend into
      each child at the SAME `base-path` — these ops don't introduce a
      new app-db path segment.

    - Container-level props on the schema itself (the schema's OWN props,
      not a parent slot's) claim `base-path`. Covers
      `(rf/reg-app-schema [:user :pdf] [:string {:large? true}])` — the
      reg-app-schema path IS where the marker fires.

  Returns the accumulator map."
  ([flag-key schema base-path]
   (walk-flagged-schema flag-key schema base-path {}))
  ([flag-key schema base-path acc]
   (cond
     ;; Keyword schema (`:string`, `:int`, `:any`, registry-name kw, …)
     ;; — no slot props on a bare keyword; nothing to nominate.
     (keyword? schema) acc

     ;; Vector form `[op props? children...]` — the structural case.
     (and (vector? schema) (pos? (count schema)))
     (let [op       (nth schema 0)
           acc'     (if-let [decl (decl-from-props flag-key (props-of schema))]
                      (assoc acc base-path decl)
                      acc)
           children (children-of schema)]
       (cond
         (contains? name-bearing-ops op)
         (reduce
           (fn [acc child]
             (if-not (and (vector? child) (>= (count child) 2))
               acc
               (let [k          (nth child 0)
                     maybe-prop (nth child 1)
                     has-prop?  (map? maybe-prop)
                     slot-path  (conj base-path k)
                     tail       (if has-prop?
                                  (when (>= (count child) 3) (nth child 2))
                                  maybe-prop)
                     acc        (if-let [d (and has-prop?
                                                (decl-from-props flag-key maybe-prop))]
                                  (assoc acc slot-path d)
                                  acc)]
                 (if (some? tail)
                   (walk-flagged-schema flag-key tail slot-path acc)
                   acc))))
           acc'
           children)

         (contains? dispatch-bearing-ops op)
         (reduce
           (fn [acc child]
             (if-not (vector? child)
               acc
               (let [maybe-prop (when (>= (count child) 2) (nth child 1))
                     has-prop?  (map? maybe-prop)
                     tail-idx   (if has-prop? 2 1)
                     tail       (when (> (count child) tail-idx) (nth child tail-idx))
                     acc        (if-let [d (and has-prop?
                                                (decl-from-props flag-key maybe-prop))]
                                  (assoc acc base-path d)
                                  acc)]
                 (if (some? tail)
                   (walk-flagged-schema flag-key tail base-path acc)
                   acc))))
           acc'
           children)

         :else
         (reduce (fn [acc c] (walk-flagged-schema flag-key c base-path acc))
                 acc'
                 children)))

     ;; Anything else (schema object, fn schema, opaque leaf) —
     ;; not introspectable as data; skip.
     :else acc)))

(defn extract-large-paths-from-schema
  "Walk a registered Malli schema form at `base-path` and return a
  `{path declaration}` map for every `:large? true` slot found. Per
  Spec 009 §Size elision in traces — the schema-driven nomination path.

  Returned declarations carry `:source :schema` per Spec 009 —
  introspection (`(get-in db [:rf/elision :declarations <path>])`)
  reports provenance so consumers know whether the elision claim
  came from the schema layer, an app fx, or the runtime auto-detect
  heuristic."
  [schema base-path]
  (walk-flagged-schema :large? schema base-path {}))

(defn extract-sensitive-paths-from-schema
  "Walk a registered Malli schema form at `base-path` and return a
  `{path {:sensitive? true ...}}` map for every `:sensitive? true` slot
  found. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces.

  Used by the validation emit-sites (`validate-app-db!` and the
  per-step `validate-event!` / `validate-cofx!` / `validate-sub-return!`
  helpers) to decide whether the failing slot's value MUST be redacted
  before the trace event ships."
  [schema base-path]
  (walk-flagged-schema :sensitive? schema base-path {}))

(defn schema-has-sensitive?
  "True when the registered schema declares ANY slot sensitive —
  either the schema's container-level props carry `:sensitive? true`,
  or any nested `:sensitive? true` slot lives anywhere inside the
  schema. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces.

  The schema-validation emit-sites ship the WHOLE registered slot's
  value (not just a failing leaf) in the trace's `:value` / `:received`
  / `:explain` slots; a sensitive child slot still leaks if the value
  rides verbatim. Conservative redaction — when any slot in the schema
  is sensitive, the whole trace's value-bearing slots are redacted.

  Returns boolean. Pure; same input always produces the same output."
  [schema]
  (-> (extract-sensitive-paths-from-schema schema [])
      seq
      boolean))

;; ---- per-flag registry feeders (rf2-v9tw2 / rf2-c1l4d) -------------------
;;
;; Each `:rf/app-schema-meta` flag (`:large?`, `:sensitive?`) hydrates a
;; sibling slot under `[:rf/elision]`:
;;
;;   :large?      → [:rf/elision :declarations]
;;   :sensitive?  → [:rf/elision :sensitive-declarations]
;;
;; The `frame-*-declarations` fns aggregate per-flag declarations across
;; every schema registered against a frame. The `populate-*` fns
;; idempotently fold those declarations into a frame's app-db with the
;; Spec 009 conflict-resolution rule applied: existing entries with
;; `:source :declared` are preserved (declared beats schema); entries
;; with `:source :schema` from a prior call are overwritten (hot-reload
;; picks up `:hint` / flag flips); entries no longer claimed by any
;; registered schema are NOT removed (a stale `:source :schema` entry
;; persists until the next explicit clear).
;;
;; Both pairs are published via per-flag late-bind hooks at the bottom
;; of this file so re-frame.core can call them without statically
;; requiring the schemas artefact (per rf2-p7va — schemas is optional).

(defn- frame-declarations
  "Aggregate `extract-fn` across every schema registered against
  `frame-id`, returning a `{path declaration}` map."
  [extract-fn frame-id]
  (reduce-kv
    (fn [acc path m] (merge acc (extract-fn (:schema m) path)))
    {}
    (frame-schema-entries frame-id)))

(defn- populate-declarations
  "Idempotent — fold `frame-id`'s schema-derived declarations into `db`
  at `registry-path`. Preserves `:source :declared` entries; overwrites
  `:source :schema` entries. Returns `db` unchanged when the frame
  carries no schema-derived declarations."
  [db frame-id extract-fn registry-path]
  (let [schema-decls (frame-declarations extract-fn frame-id)]
    (if (empty? schema-decls)
      db
      (update-in db registry-path
                 (fn [existing]
                   (reduce-kv
                     (fn [acc path decl]
                       (if (= :declared (some-> (get acc path) :source))
                         acc
                         (assoc acc path decl)))
                     (or existing {})
                     schema-decls))))))

(defn frame-elision-declarations
  "Return the merged `{path declaration}` map for every `:large? true`
  slot in every app-schema registered against `frame-id`. Composes
  `extract-large-paths-from-schema` across the frame's schema set.

  Arities:
    (frame-elision-declarations)              ;; current frame
    (frame-elision-declarations frame-id)     ;; explicit frame"
  ([] (frame-elision-declarations (frame/current-frame)))
  ([frame-id] (frame-declarations extract-large-paths-from-schema frame-id)))

(defn populate-elision-declarations
  "Idempotent — fold the frame's schema-derived `:large?` declarations
  into `db` at `[:rf/elision :declarations]`. See the per-flag registry-
  feeder header above for the full conflict-resolution rule."
  [db frame-id]
  (populate-declarations db frame-id
                         extract-large-paths-from-schema
                         [:rf/elision :declarations]))

(defn frame-sensitive-declarations
  "Return the merged `{path declaration}` map for every `:sensitive? true`
  slot in every app-schema registered against `frame-id`. Composes
  `extract-sensitive-paths-from-schema` across the frame's schema set.

  Arities:
    (frame-sensitive-declarations)              ;; current frame
    (frame-sensitive-declarations frame-id)     ;; explicit frame"
  ([] (frame-sensitive-declarations (frame/current-frame)))
  ([frame-id] (frame-declarations extract-sensitive-paths-from-schema frame-id)))

(defn populate-sensitive-declarations
  "Idempotent — fold the frame's schema-derived `:sensitive?` declarations
  into `db` at `[:rf/elision :sensitive-declarations]`. See the per-flag
  registry-feeder header above for the full conflict-resolution rule."
  [db frame-id]
  (populate-declarations db frame-id
                         extract-sensitive-paths-from-schema
                         [:rf/elision :sensitive-declarations]))

;; ---- schema digest --------------------------------------------------------
;;
;; Per Spec 010 §Digest algorithm — a stable, cross-runtime hash over a
;; frame's registered `app-db` schema set. The wire form is
;; `"sha256:" + first-16-hex-chars`. Two frames produce equal digests iff
;; their `{path → schema-value}` maps serialise byte-for-byte identically.
;;
;; Used by:
;;   - The epoch-restore schema-mismatch trace (epoch.cljc) — pinpoints
;;     when a frame's schema set drifted between record and restore.
;;   - SSR hydration (Spec 011) — server stamps its digest on the payload;
;;     the client compares its own digest at hydrate time.
;;   - Pair tools — flag attached REPL sessions whose runtime schemas
;;     have shifted under them.

(defn- canonicalise-schema-form
  "Normalise a Malli EDN schema form for stable byte serialisation:
  metadata is stripped, and map-keys (the Malli `props` map's contents
  in `[:map {k v ...} & children]`) are emitted in (compare a b) order
  via `sort-by str`. The result is fed through `pr-str` to produce the
  canonical UTF-8 byte sequence Spec 010 hashes."
  [form]
  (letfn [(canon [x]
            (cond
              (map? x)
              ;; Sort keys lexicographically by their printed form so two
              ;; equivalent maps with different insertion order serialise
              ;; identically. Using a sorted-map preserves the order
              ;; through pr-str.
              (into (sorted-map-by (fn [a b]
                                     (compare (pr-str a) (pr-str b))))
                    (map (fn [[k v]] [(canon k) (canon v)]))
                    x)
              (vector? x) (mapv canon x)
              (set? x)    (into (sorted-set-by (fn [a b]
                                                 (compare (pr-str a) (pr-str b))))
                                (map canon)
                                x)
              (seq? x)    (doall (map canon x))
              :else       x))]
    (canon form)))

(defn- schema-print
  "Serialise a schema value to a stable UTF-8 byte-source string. Per
  Spec 010 §Digest algorithm step 1, the CLJS reference uses `pr-str`
  over the Malli EDN form with map-key ordering normalised and metadata
  stripped."
  [schema-value]
  (binding [*print-meta*       false
            *print-readably*   true
            *print-dup*        false
            *print-namespace-maps* false]
    (pr-str (canonicalise-schema-form schema-value))))

(defn- utf8-bytes
  "Encode a string as UTF-8 bytes."
  [s]
  #?(:clj  (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs (gcrypt/stringToUtf8ByteArray s)))

(defn- bytes->hex
  "Lowercase hex encoding of a byte sequence. Cross-runtime byte-stable."
  [bs]
  #?(:clj  (let [sb (StringBuilder.)]
             (doseq [b bs]
               (let [u (bit-and (long b) 0xff)]
                 (when (< u 0x10) (.append sb \0))
                 (.append sb (Long/toString u 16))))
             (.toString sb))
     :cljs (gcrypt/byteArrayToHex bs)))

(defn- sha256-hex
  "Return the lowercase 64-char hex SHA-256 of a UTF-8 string. Uses
  the platform's native SHA-256 (java.security.MessageDigest on JVM,
  goog.crypt.Sha256 on CLJS) — both are FIPS 180-3 compliant and
  produce byte-identical output for the same input."
  [s]
  #?(:clj
     (let [md (MessageDigest/getInstance "SHA-256")]
       (.update md ^bytes (utf8-bytes s))
       (bytes->hex (.digest md)))
     :cljs
     (let [h (goog.crypt.Sha256.)]
       (.update h (utf8-bytes s))
       (bytes->hex (.digest h)))))

(defn- digest-line
  "Per Spec 010 §Digest algorithm step 3 — emit one line per
  `(path, schema-value)` of the form `<path-string> <hex-of-sha256>\\n`."
  [path schema-value]
  (str (pr-str path)
       " "
       (sha256-hex (schema-print schema-value))
       "\n"))

(defn- compute-digest
  "Run the Spec 010 §Digest algorithm against the supplied
  `{path → schema-value}` map. Returns the canonical wire form
  `\"sha256:\" + first-16-hex-chars`."
  [path->schema]
  (let [lines       (mapv (fn [[path schema]] (digest-line path schema))
                          path->schema)
        sorted      (sort lines)            ;; lexicographic byte order
        concatted   (apply str sorted)
        full-hex    (sha256-hex concatted)]
    (str "sha256:" (subs full-hex 0 16))))

(defn app-schemas-digest
  "Return a stable digest of the frame's registered `app-db` schema set
  — the same shape `app-schemas` would return — as the canonical wire
  form `\"sha256:\" + 16 lowercase-hex-chars`.

  Per Spec 010 §Digest algorithm the procedure is byte-deterministic
  and cross-runtime: a CLJS server and a CLJS client running the same
  schema set produce byte-identical digests. The empty schema set
  produces a stable, well-defined digest (the SHA-256 of the empty
  string — the lines list collapses to a single empty concatenation).

  Arities:
    (app-schemas-digest)              ;; sugar for (app-schemas-digest {})
    (app-schemas-digest frame-id)     ;; keyword sugar
    (app-schemas-digest opts)         ;; opts map; supports {:frame ...}

  Uses include the SSR hydration handshake (Spec 011 §The :rf/hydrate
  event), the epoch-restore schema-mismatch trace (Tool-Pair §Time-
  travel), and pair-tool drift detection."
  ([] (app-schemas-digest {}))
  ([opts-or-frame-id]
   (let [opts     (coerce-opts opts-or-frame-id)
         frame-id (resolve-frame opts)]
     (compute-digest (app-schemas {:frame frame-id})))))

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; When a frame is destroyed (or a schema is explicitly cleared via the
;; registrar), drop its per-frame entry so the validation hot path
;; doesn't keep walking dead schemas. Per Spec 001 §Hot-reload semantics
;; the registrar's :app-schema slot is shared (path-keyed) across
;; frames; replacement-hook fires on EVERY re-register, not only when
;; we want to invalidate. We only act on :rf.registry/handler-cleared-
;; equivalent paths (kind = :app-schema, no `:now` means cleared).

(defn- on-app-schema-registry-event!
  [{:keys [kind id was now]}]
  (when (= kind :app-schema)
    ;; A `register!` always supplies `now`; `unregister!` / `clear-kind!`
    ;; produce no replacement-hook callback (they don't fire hooks). The
    ;; registrar today only invokes hooks on replacement, so this hook
    ;; sees re-registrations against the same path. Re-registering with
    ;; an explicit :frame opt MAY be against a different frame — in
    ;; which case we leave the prior frame's entry alone (it was
    ;; registered separately) and the new frame's entry is updated by
    ;; reg-app-schema directly. Nothing to do here today; the hook is
    ;; published as the seam future invalidation work plugs into.
    (let [_ was _ now])))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! on-app-schema-registry-event!)
      :installed))

;; ---- validation entry points ----------------------------------------------

;; Per Spec 010 §`:sensitive?` — privacy in schema-validation error
;; traces (rf2-kj51z). The validation emit-sites redact the failing
;; value before stamping a trace event when either:
;;   1. The schema slot at the failing path (or a containing slot)
;;      carries `:sensitive? true` in its Malli props.
;;   2. The surrounding registration metadata (handler-meta / cofx-meta /
;;      sub-meta) carries `:sensitive? true` — applies to every
;;      validation failure in that handler's scope as a coarse fallback.
;; The substitution sentinel is `:rf/redacted` (the framework-reserved
;; keyword per Spec 009 §`with-redacted`). The trace event's `:tags`
;; map is stamped with `:sensitive? true` so consumers can route on it
;; (until rf2-isdwf's top-level hoisting lands in core).
;;
;; The `:value`, `:received`, and `:explain` slots are redacted; the
;; structural / categorical slots (`:path`, `:failing-id`, `:spec-id`,
;; `:reason`) are kept — consumers need them to locate the broken slot
;; without leaking user data.

(def ^:private redacted-sentinel
  "The `:rf/redacted` privacy sentinel emitted by validation traces
  for slots matching the `:sensitive?` predicate. Per Spec 009
  §`with-redacted` — the framework-reserved keyword that cannot
  collide with an app-defined value."
  :rf/redacted)

(defn- meta-sensitive?
  "True when the registration metadata (handler / cofx / sub) carries
  `:sensitive? true`. Per Spec 009 §`:sensitive?` registration
  metadata key — the coarse, handler-level signal."
  [m]
  (true? (:sensitive? m)))

(defn- redact-tags
  "Replace value-bearing slots in a tags map with the `:rf/redacted`
  sentinel. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces. Stamps `:sensitive? true` so consumers filter
  correctly. Idempotent — safe to call on an already-redacted map."
  [tags]
  (cond-> tags
    (contains? tags :value)       (assoc :value       redacted-sentinel)
    (contains? tags :received)    (assoc :received    redacted-sentinel)
    (contains? tags :explain)     (assoc :explain     redacted-sentinel)
    (contains? tags :malli-error) (assoc :malli-error redacted-sentinel)
    (contains? tags :event)       (assoc :event       redacted-sentinel)
    true                          (assoc :sensitive?  true)))

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
  failure — surfaced as :failing-id in the error tags."
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
     (when @validator-fn
       (doseq [[path m] (frame-schema-entries frame-id)]
         (let [val-at (get-in db path)
               schema (:schema m)]
           (when-not (run-validator schema val-at)
             ;; Per Spec 010 §`:sensitive?` — privacy in schema-
             ;; validation error traces (rf2-kj51z). Consult the
             ;; schema's per-slot `:sensitive?` props before
             ;; including the failing value. The failing path is
             ;; the reg-app-schema path itself (per-path validation
             ;; only flags the whole registered slot); the walker
             ;; checks for container-level OR any nested slot the
             ;; failure path crosses.
             (let [sensitive? (schema-has-sensitive? schema)
                   base-tags  (cond-> {:where    :app-db
                                       :path     path
                                       :value    val-at
                                       :frame    frame-id
                                       :explain  (run-explainer schema val-at)
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

  Returns true on pass (or when validation is elided / no schema is
  attached), false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :event; the caller
  is responsible for skipping the handler (recovery: :no-recovery).

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so :advanced+goog.DEBUG=false
  DCE-elides every reason string, every keyword, and every validator
  call. Per Spec 010 §Non-Malli validators (rf2-froe) the validator
  is pluggable; when none is registered this fn returns true (pass)
  without inspecting the schema."
  [event-id event handler-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator-fn
      (if-let [schema (:spec handler-meta)]
        (if (run-validator schema event)
          true
          (let [explanation (run-explainer schema event)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Consult the handler's registration meta —
                ;; `:sensitive? true` on the reg-event-* declares
                ;; the whole event vector sensitive.
                sensitive? (meta-sensitive? handler-meta)
                base-tags  {:where       :event
                            :event-id    event-id
                            :failing-id  event-id
                            :spec-id     event-id
                            :received    event
                            :event       event
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Event " event-id
                                              " payload failed schema "
                                              schema ", got "
                                              (type-of-value event) ".")
                            :recovery    :no-recovery}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
      true)
    true))

(defn validate-sub-return!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :spec on the sub's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :sub-return; the
  caller is responsible for replacing the value with the
  default (nil) per the :replaced-with-default recovery.

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly.
  Per Spec 010 §Non-Malli validators (rf2-froe) the validator is
  pluggable; when none is registered this fn returns true."
  [sub-id query-v value sub-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator-fn
      (if-let [schema (:spec sub-meta)]
        (if (run-validator schema value)
          true
          (let [explanation (run-explainer schema value)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Two sources: the sub's registration meta
                ;; (`:sensitive?` on reg-sub) and the schema's own
                ;; per-slot `:sensitive?` (a container-level flag
                ;; on the spec covers every failing return).
                sensitive? (or (meta-sensitive? sub-meta)
                               (schema-has-sensitive? schema))
                base-tags  {:where       :sub-return
                            :sub-id      sub-id
                            :failing-id  sub-id
                            :spec-id     sub-id
                            :query-v     query-v
                            :received    value
                            :value       value
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Subscription " sub-id
                                              " return value failed schema "
                                              schema ", got "
                                              (type-of-value value) ".")
                            :recovery    :replaced-with-default}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
      true)
    true))

(defn validate-cofx!
  "Per Spec 010 §Validation order step 2 — after a cofx injects its value
  into the merged context, validate that value against any :spec on the
  cofx's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :cofx; the caller is
  responsible for skipping the handler (recovery: :no-recovery).

  Per Spec 009 §Production builds the entire body lives inside a
  `(when interop/debug-enabled? ...)` gate so DCE elides it cleanly.
  Per Spec 010 §Non-Malli validators (rf2-froe) the validator is
  pluggable; when none is registered this fn returns true."
  [cofx-id event-id value cofx-meta]
  ;; Outermost `interop/debug-enabled?` gate so :advanced + goog.DEBUG=false
  ;; DCE-elides the entire body. The `@validator-fn` deref must live
  ;; INSIDE the gate (atom deref is not a compile-time constant).
  (if interop/debug-enabled?
    (if @validator-fn
      (if-let [schema (:spec cofx-meta)]
        (if (run-validator schema value)
          true
          (let [explanation (run-explainer schema value)
                ;; Per Spec 010 §`:sensitive?` — privacy in
                ;; schema-validation error traces (rf2-kj51z).
                ;; Cofx-meta or container-level schema-prop both
                ;; trigger redaction.
                sensitive? (or (meta-sensitive? cofx-meta)
                               (schema-has-sensitive? schema))
                base-tags  {:where       :cofx
                            :cofx-id     cofx-id
                            :event-id    event-id
                            :failing-id  event-id
                            :spec-id     cofx-id
                            :received    value
                            :value       value
                            :malli-error explanation
                            :explain     explanation
                            :reason      (str "Coeffect " cofx-id
                                              " injected value failed schema "
                                              schema ", got "
                                              (type-of-value value) ".")
                            :recovery    :no-recovery}
                tags       (if sensitive? (redact-tags base-tags) base-tags)]
            (trace/emit-error! :rf.error/schema-validation-failure tags)
            false))
        true)
      true)
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
  (run-validator schema value))

(defn explain-with-registered-fn
  "Apply the registered explainer to `(schema, value)`. Companion to
  `validate-with-registered-fn` for the boundary-validation
  interceptor (rf2-r2uh). Returns the explanation map / data on fail;
  nil when the schema conforms or no explainer is registered."
  [schema value]
  (run-explainer schema value))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.router, re-frame.cofx, and re-frame.subs need to call into
;; schema validation but cannot `:require` this namespace without a
;; cyclic load order. Publish entry points through the late-bind hook
;; registry. See re-frame.late-bind.
;;
;; Per rf2-p7va (the schemas artefact split), `re-frame.core` and
;; `re-frame.test-support` MUST NOT `:require [re-frame.schemas]` either
;; — the schemas artefact is an optional dep, and a static require would
;; force every consumer of the core artefact to drag its symbols (and
;; the Malli dep) onto the classpath. The public-API re-exports
;; (`reg-app-schema`, `app-schema-at`, `app-schemas`) and the
;; test-support reset / snapshot helpers are published through the same
;; late-bind table; consumers without the schemas artefact see the
;; hooks unregistered and the surface no-ops cleanly.

;; Validation hot-path hooks (consumed by router / cofx / subs / epoch).
(late-bind/set-fn! :schemas/validate-app-db!     validate-app-db!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub-return! validate-sub-return!)
(late-bind/set-fn! :schemas/validate-cofx!       validate-cofx!)
(late-bind/set-fn! :schemas/frame-schema-entries frame-schema-entries)

;; Boundary-validation seam (rf2-r2uh integration).
(late-bind/set-fn! :schemas/validate-with-registered-fn validate-with-registered-fn)
(late-bind/set-fn! :schemas/explain-with-registered-fn  explain-with-registered-fn)

;; Public-API re-export hooks (consumed by re-frame.core).
(late-bind/set-fn! :schemas/reg-app-schema        reg-app-schema)
(late-bind/set-fn! :schemas/reg-app-schemas       reg-app-schemas)
(late-bind/set-fn! :schemas/app-schema-at         app-schema-at)
(late-bind/set-fn! :schemas/app-schemas           app-schemas)
(late-bind/set-fn! :schemas/app-schemas-digest    app-schemas-digest)
(late-bind/set-fn! :schemas/set-schema-validator! set-schema-validator!)
(late-bind/set-fn! :schemas/set-schema-explainer! set-schema-explainer!)

;; Elision-walker hooks (rf2-nwv63) — published so re-frame.core's
;; downstream registry-population code (rf2-v9tw2) can hydrate
;; `[:rf/elision :declarations]` at boot / on reg-app-schema without
;; statically depending on the schemas artefact.
(late-bind/set-fn! :schemas/extract-large-paths-from-schema
                   extract-large-paths-from-schema)
(late-bind/set-fn! :schemas/frame-elision-declarations
                   frame-elision-declarations)
(late-bind/set-fn! :schemas/populate-elision-declarations
                   populate-elision-declarations)

;; Sensitive-paths walker (rf2-kj51z + rf2-c1l4d) — published so
;; re-frame.core can extend the unified elision registry to recognise
;; schema `:sensitive?` slots without statically requiring the schemas
;; artefact. The pair mirrors the `:large?` walker (rf2-nwv63) — same
;; shape, same idempotency, same conflict-resolution semantics on a
;; sibling registry slot.
(late-bind/set-fn! :schemas/extract-sensitive-paths-from-schema
                   extract-sensitive-paths-from-schema)
(late-bind/set-fn! :schemas/schema-has-sensitive?
                   schema-has-sensitive?)
(late-bind/set-fn! :schemas/frame-sensitive-declarations
                   frame-sensitive-declarations)
(late-bind/set-fn! :schemas/populate-sensitive-declarations
                   populate-sensitive-declarations)

;; Test-support hooks (consumed by re-frame.test-support's
;; reset-runtime-fixture). The fixture wants to capture and restore
;; the `schemas-by-frame` atom around each test, so it asks for a
;; snapshot before the test, a clear in the middle, and a restore
;; afterwards. When the schemas artefact is not on the classpath
;; these hooks are nil and the fixture no-ops the schema steps —
;; correct, because there is no schema state to preserve.

(defn snapshot-schemas-by-frame
  "Return a snapshot value of the per-frame schema registry."
  []
  @schemas-by-frame)

(defn restore-schemas-by-frame!
  "Reset the per-frame schema registry to the supplied snapshot."
  [snap]
  (reset! schemas-by-frame snap))

(defn clear-schemas-by-frame!
  "Reset the per-frame schema registry to `{}`. Used by test fixtures
  and by `reset-runtime-fixture`'s `:clear-kinds [:app-schema]` path."
  []
  (reset! schemas-by-frame {}))

(late-bind/set-fn! :schemas/snapshot-by-frame    snapshot-schemas-by-frame)
(late-bind/set-fn! :schemas/restore-by-frame!    restore-schemas-by-frame!)
(late-bind/set-fn! :schemas/clear-by-frame!      clear-schemas-by-frame!)
