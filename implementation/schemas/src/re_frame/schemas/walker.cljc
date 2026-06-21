(ns re-frame.schemas.walker
  "Per-slot flag walker for Malli EDN schemas (rf2-nwv63 / rf2-kj51z / rf2-oghml).

  Per Spec 009 §Size elision in traces and Spec 010 §`:sensitive?`, the
  schema-driven nomination path: any Malli slot carrying a per-slot flag
  (`:large? true` or `:sensitive? true`) in its per-slot properties
  (per Spec-Schemas §`:rf/app-schema-meta` — the per-slot metadata
  vocabulary) is walked to a `{path declaration}` map with `:source
  :schema`. Flags compose orthogonally — a slot may carry either or both,
  and the consumer resolves the conflict at use time.

  This file owns the **walker** that maps a registered schema's EDN form
  to a `{path declaration}` map. It is a **pure-data extractor**; the
  walker itself performs no runtime-db write.

  ## EP-0015 §8 (rf2-d2r3um) — schema props do NOT feed app-db egress

  These extractors NO LONGER populate the frame's app-db egress registry
  under `[:rf.runtime/elision :declarations]` /
  `[:rf.runtime/elision :sensitive-declarations]`. App-db egress
  classification is declared by the **EP-0025 commit-plane classification
  effects** — a `reg-event` returns `:sensitive` / `:large` alongside `:db`,
  written `:source :effect` (Spec 015 §Data classification). The former
  durable `:sensitive` / `:large {:app-db …}` *frame annotation* and the
  public `add-marks` / `set-marks` app-db path-mark API are both REMOVED
  (Spec 015 §3 / Privacy.md §Removed surfaces). Schemas describe **shape**,
  not durable app-db egress policy, and the old
  `re-frame.elision/populate-{,sensitive-}from-schemas!` bridge + its
  `:elision/populate-from-schemas!` late-bind seam are REMOVED (see
  `re-frame.schemas` §schema-walker hooks; Spec-Schemas §`:rf/runtime-db`
  `:rf.runtime/elision`).

  The extractors survive for their **owner-local** schema-prop consumers,
  which read the `{path declaration}` map directly (NOT via the elision
  runtime-db registry):

    - the resource `:data-schema` classification
      (`re-frame.resources.classification`, EP-0015 §6);
    - the HTTP body-privacy projector
      (`re-frame.http.privacy-body`);
    - story-mcp's tool-egress projector;
    - the schema-validation-FAILURE-trace redactor
      (`re-frame.schemas.validate`, via `schema-sensitive-at?` /
      `schema-has-sensitive?` / `schema-opaque?` / `sanitize-sensitive-path`)
      — `:sensitive?` redacts the failing value before the
      `:rf.error/schema-validation-failure` trace ships.

  EP-0025 (rf2-398kql) — the machine `:data-schema`→marks redaction bridge
  (EP-0005) is GONE. A machine's `:sensitive?` / `:large?` `:data`-slot props
  no longer classify the machine's durable `:data` for trace / SSR egress;
  durable machine `:data` classification rides the commit-plane classification
  effects like every other app-db path (a `reg-event` returns `:sensitive` /
  `:large` alongside `:db`, EP-0025, the sole durable app-db mechanism). The
  `:data-schema` still VALIDATES, and its props
  still drive the machine-data validation-FAILURE-trace redactor via the
  `:where :machine-data` validation path — only the schema→MARKS classification
  bridge is reversed.

  The surviving consumers call the per-flag late-bind hooks
  (`:schemas/extract-large-paths-from-schema` /
  `:schemas/extract-sensitive-paths-from-schema`) registered by the outer
  façade and apply the result within their own owner-local scope.

  The walker is **pure data** — it doesn't import malli.core. Malli EDN
  forms are vectors of the shape `[op props? children...]`; we pattern-
  match on shape. Per Spec 010 §The `:schema` value is opaque to re-frame,
  the framework MUST NOT call into the registered validator to introspect
  schema structure — we walk the EDN ourselves. This means the walker
  handles the **vector form** (`[:map [:k :string]]`) — the form
  `(rf/reg-app-schema ...)` users write. Non-vector Malli forms (schema
  objects, registry refs) are treated as opaque leaves; their internal
  per-slot declarations are invisible to the walker (the same caveat
  applies to Malli's own schema-walking when introspection goes through
  `m/schema` ↔ raw EDN — round-tripping a registry ref loses the slot
  metadata).

  ## Discoverability caveat — non-vector forms (rf2-yaioz / rf2-mxs7a)

  A user that registers a schema via a compiled `m/schema` value or a
  registry reference and adds per-slot `:sensitive?` / `:large?` flags
  inside that opaque value will see the walker **silently skip** them —
  the validation-failure trace will not redact the sensitive slot. The
  two opaque shapes differ in diagnostics:

    - **Compiled / non-keyword opaque values** (a compiled `m/schema`
      object, a map, any non-vector non-keyword form) **warn once per
      process at registration time.** `reg-app-schema` /
      `reg-app-schemas` emit `:rf.warning/schema-walker-opaque` (the
      one-shot warn-once nudge owned by `re-frame.schemas.storage`,
      pinned by `schemas_walker_opaque_warning_test`) naming the two
      workable shapes below.

    - **Keyword registry refs** (`:my/user-schema`) stay **silent by
      design.** A bare keyword is a valid Malli schema in two flavours
      — a primitive (`:int` / `:string`) and a registry ref — and the
      predicate cannot cheaply tell them apart without a registry
      consult, which Spec 010 §The `:schema` value is opaque to
      re-frame forbids. A primitive keyword provably carries no
      per-slot flags, so warning on every keyword would be a frequent
      false positive to catch a rare registry-ref true positive
      (rf2-ee38b.6); the keyword case is suppressed entirely.

  The workable shape when per-slot flags need to apply:

    1. **Register the vector form, not the compiled one.** Pass the
       raw EDN `[op props? children...]` to `reg-app-schema` — the
       walker can introspect the per-slot `:sensitive?` / `:large?`
       flags directly.

  > **No registration-meta fallback.** Earlier drafts named a
  > handler/cofx/sub registration-level `:sensitive?` annotation as a
  > coarse fallback for opaque schemas. That annotation has been REMOVED
  > (per Spec 010 §`:sensitive?` and Spec 009 §`:sensitive?` registration
  > metadata key, rf2-k0ew8n): sensitivity is path-targeted — a property
  > of the data value at a path, not of the handler that touched it — and
  > the validation redaction now consults ONLY the per-slot schema
  > declaration. Registering an opaque value and adding handler-meta
  > `:sensitive?` does NOT redact; the supported route is registering the
  > vector form (or, for durable app-db egress, the EP-0025 commit-plane
  > `:sensitive` / `:large` classification effects — a `reg-event` returns
  > them alongside `:db`).

  Example — vector form vs registry ref:

    ;; Vector form — walker sees :sensitive? per-slot.
    (rf/reg-app-schema [:user]
      [:map
       [:id    :int]
       [:token {:sensitive? true} :string]])

    ;; Registry ref — walker treats the schema as an opaque leaf;
    ;; the per-slot :sensitive? inside `:my/user-schema` is invisible.
    ;; The supported route is registering the vector form so the walker
    ;; can introspect it (handler-meta :sensitive? is NOT a fallback).
    (rf/reg-app-schema [:user] {:schema :my/user-schema})

  The walker is parameterised on the per-slot flag key (`:large?` /
  `:sensitive?`); both flags share identical structural recognition
  (`:map` name-bearing, `:multi`/`:orn`/`:catn`/`:altn` dispatch-bearing,
  positional combinators descend at the same base-path) so a single
  traversal serves every per-slot annotation under
  `:rf/app-schema-meta`. Future per-slot annotations (Spec-Schemas
  reserves additional slots) compose as one-line registrations."
  (:require [re-frame.schemas.cache :as cache]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private name-bearing-ops
  "Schema ops whose children carry name slots (the first element of a
  child entry is the slot's app-db key)."
  #{:map})

(def ^:private dispatch-bearing-ops
  "Schema ops whose children carry dispatch-value branches (the first
  element of a child entry is a dispatch value, not an app-db path
  segment).

  `:catn` is NOT here (rf2-4q681i): although its children carry NAME
  slots like `:orn`/`:altn`, Malli reports a `:catn` failure's `:in`
  segment as the element's INTEGER POSITION (`[1 :age]`), not the name —
  identical to `:cat`. So `:catn` is position-bearing (see
  `position-bearing-ops`), and its name slot is decorative for the
  decl-path coordinate system."
  #{:multi :orn :altn})

(def ^:private position-bearing-ops
  "Schema ops whose children are POSITION-bearing — each element `i` has
  its OWN heterogeneous schema and Malli reports a failure's `:in` segment
  as the integer index `i`. The walker descends element `i` at
  `(conj base-path i)` (the positional analogue of a `:map` key), giving
  per-element sibling precision: a per-position flag claims that exact
  index, not the shared base-path.

    - `:tuple` (rf2-ss06u.4) — element `i` is `children[i]`, a bare schema.
    - `:cat` / `:catn` (rf2-4q681i) — the regex sequence combinators that
      root event schemas (`[:cat [:= :id] PayloadSchema]`). `:cat`
      elements are bare schemas (`children[i]`); `:catn` elements are
      NAME-bearing entries (`[name props? schema]`) but Malli still
      reports the integer position in `:in`, so the name is decorative and
      the position drives the coordinate. Both descend the element schema
      at `(conj base-path i)`.

  The position-pinned decl-path is matching-safe against the core-elision
  coordinate system: the runtime elision walk descends a tuple / event-
  vector value (a vector) through its literal-index fork (`fork-index-paths`
  — `(conj c i)` in `re-frame.elision`), which matches a position-pinned
  declaration exactly. No elision-side change is required — the index fork
  is schema-agnostic (it walks the runtime value), so `:cat`/`:catn`/`:tuple`
  all align through the same generic path."
  #{:tuple :cat :catn})

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
  `:source :schema` slot records schema provenance for the owner-local
  consumer (machine / resource `:data-schema`, HTTP body-privacy,
  story-mcp) that reads the extracted map — NOT the (removed, EP-0015 §8)
  app-db egress registry."
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

    - `:multi` / `:orn` / `:altn` children are dispatch-bearing —
      `[v schema]` / `[v {props} schema]` — and the flag on a branch's
      slot props claims the parent path (the op's `base-path`), not a
      child path; dispatch values aren't path segments.

    - `:tuple` / `:cat` / `:catn` children are POSITION-bearing
      (`:tuple` rf2-ss06u.4; `:cat`/`:catn` rf2-4q681i) — each element
      has its OWN heterogeneous schema, so element `i` descends at
      `(conj base-path i)`, the integer index being the discriminating
      segment (the positional analogue of a `:map` key). A `:sensitive?`
      flag on element 0 of `[:tuple [:string {:sensitive?}] :int]` claims
      `(conj base 0)`, NOT `base` — so it does not taint the sibling at
      `(conj base 1)`. This is the index-bearing element-precision the
      `:vector` / `:map-of` paths already have via their map-key
      discriminator; for these ops the discriminator IS the index.
      `:cat` elements are bare schemas (`children[i]`); `:catn` elements
      are NAME-bearing entries (`[name props? schema]`) — the flag may
      live in the entry's own props OR the element schema's props, both
      claiming `(conj base i)` — but Malli reports the integer POSITION
      (not the name) in a `:catn` failure's `:in`, so the position drives
      the coordinate. The position-pinned decl-path is matching-safe
      against the core-elision coordinate system: the runtime elision walk
      descends a tuple / event-vector value (a vector) through its
      literal-index fork (`fork-index-paths` — `(conj c i)`), which matches
      a position-pinned declaration exactly.

    - Other positional / nameless container ops (`:vector`, `:set`,
      `:sequential`, `:maybe`, `:and`, `:or`, `:not`, …) descend into each
      child at the SAME `base-path` — these ops are homogeneous (one
      shared element schema) or their index is not a declarable app-db
      slot, so they don't introduce a new path segment.

    - Container-level props on the schema itself (the schema's OWN props,
      not a parent slot's) claim `base-path`. Covers
      `(rf/reg-app-schema [:user :pdf] {:schema [:string {:large? true}]})` — the
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

         ;; `:tuple` / `:cat` / `:catn` — position-bearing (`:tuple`
         ;; rf2-ss06u.4; `:cat`/`:catn` rf2-4q681i). Each element has its
         ;; OWN schema; element `i` descends at `(conj base-path i)` so a
         ;; per-position `:sensitive?` / `:large?` flag claims that exact
         ;; index, NOT the shared base-path. Mirrors the `:map` name-bearing
         ;; descent with integer position keys, giving the sibling precision
         ;; the index-free `:else` descent destroys.
         ;;
         ;; `:tuple` / `:cat` elements are bare schemas (`children[i]`);
         ;; `:catn` elements are NAME-bearing entries (`[name props? schema]`)
         ;; — the flag may live in the entry's own props OR the element
         ;; schema's props, both claiming `(conj base i)`. Either shape
         ;; descends the element schema at the position-pinned path.
         (contains? position-bearing-ops op)
         (first
           (reduce
             (fn [[acc i] child]
               (let [slot-path (conj base-path i)
                     ;; `:catn` entry `[name props? schema]` — strip the
                     ;; decorative name (and optional props) to reach the
                     ;; element schema; an entry-level flag claims slot-path.
                     ;; `:tuple`/`:cat` entry is the bare schema itself.
                     [elem-schema entry-decl]
                     (if (= op :catn)
                       (if (and (vector? child) (>= (count child) 2))
                         (let [maybe-prop (nth child 1)
                               has-prop?  (map? maybe-prop)
                               schema     (if has-prop?
                                            (when (>= (count child) 3) (nth child 2))
                                            maybe-prop)]
                           [schema (and has-prop?
                                        (decl-from-props flag-key maybe-prop))])
                         [nil nil])
                       [child nil])
                     acc (if entry-decl (assoc acc slot-path entry-decl) acc)]
                 [(if (some? elem-schema)
                    (walk-flagged-schema flag-key elem-schema slot-path acc)
                    acc)
                  (inc i)]))
             [acc' 0]
             children))

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

  Returned declarations carry `:source :schema` per Spec 009 so the
  owner-local consumer (machine / resource `:data-schema`, HTTP
  body-privacy, story-mcp) that reads this map can report schema
  provenance for its wire-boundary elision. EP-0015 §8: this map does
  NOT feed the app-db egress registry under `[:rf.runtime/elision
  :declarations]` — app-db egress is frame-owned."
  [schema base-path]
  (walk-flagged-schema :large? schema base-path {}))

;; `extract-sensitive-paths-from-schema` is the clearable-memo wrapper
;; over the `:sensitive?` walk (rf2-17sqc shape; the factory lives in
;; `re-frame.schemas.cache`, consolidated rf2-mse54). The cache is
;; *clearable* (`clojure.core/memoize` exposes no clear hook) so a test
;; that registers many distinct fresh schemas (the concurrency-stress
;; harness) can reset the process-lifetime cache in fixture teardown.
;; Behaviour for callers is byte-identical to the prior `memoize` form.
;; See `re-frame.schemas.cache` for the boot-once invariant.
(let [[memo clear!]
      (cache/clearable-memo
        (fn [schema base-path]
          (walk-flagged-schema :sensitive? schema base-path {})))]

  (def
    ^{:doc "Walk a registered Malli schema form at `base-path` and return
            a `{path {:sensitive? true ...}}` map for every `:sensitive?
            true` slot found. Per Spec 010 §`:sensitive?` — privacy in
            schema-validation error traces.

            Used by the validation emit-sites (`validate-app-schema!` and
            the per-step `validate-event!` / `validate-fx!` /
            `validate-sub!` helpers, plus the EP-0017 recordable-cofx
            `:rf.error/cofx-value-invalid` path via
            `redact-validation-tags`) to decide whether the failing slot's
            value MUST be redacted before the trace event ships.

            Memoised by `(schema, base-path)` (rf2-y29nf): the failure-
            branch call site (`schema-sensitive-at?`) re-walks the same
            registered schema on every consecutive failure, and the walk
            is pure over immutable schema values. The cache is bounded by
            the (registered-schema, base-path) cardinality — schemas are
            registered once at app-boot, so steady-state cache size equals
            the registry size.

            The memo is **clearable** for test isolation via
            `clear-sensitive-paths-cache!` (rf2-17sqc)."
      :arglists '([schema base-path])}
    extract-sensitive-paths-from-schema memo)

  (def
    ^{:doc "Reset the `extract-sensitive-paths-from-schema` memo cache
            (rf2-17sqc). Test-support hook: the walker memo is process-
            lifetime and bounded by the (registered-schema, base-path)
            cardinality in real apps (schemas register once at boot), so
            production never needs this — but a test that registers many
            distinct fresh schemas (`schemas_concurrency_stress_test`)
            calls it in fixture teardown so the cache doesn't grow
            unbounded across the suite. Returns nil."
      :arglists '([])}
    clear-sensitive-paths-cache! clear!))

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

(defn schema-has-large?
  "True when the registered schema declares ANY slot `:large? true` —
  either the schema's container-level props, or a nested slot anywhere
  inside the schema. Per Spec 010 §`:large?` — schema-driven
  size-elision nomination, the validation-failure size-safety arm
  (rf2-vmhu4i).

  The mirror of `schema-has-sensitive?` on the OTHER per-slot flag. The
  schema-validation emit-sites ship the WHOLE registered slot's value
  verbatim in the value-bearing trace slots; a `:large?`-flagged slot
  inside that value would ride the whole blob into a validation-failure
  trace (an egress surface) unless the emit-site elides it. When this
  returns true the emit-site substitutes the `:rf.size/large-elided`
  size marker for the value-bearing slots — UNLESS the slot is ALSO
  sensitive, in which case sensitive wins (Spec 010 §Composition with
  `:large?` — the marker's `:path` / `:bytes` themselves must not leak a
  secret's size signature).

  Returns boolean. Pure; same input always produces the same output."
  [schema]
  (-> (extract-large-paths-from-schema schema [])
      seq
      boolean))

(defn schema-opaque?
  "True when `schema` is a COMPILED / OPAQUE value the pure-data walker
  cannot introspect for per-slot flags — a non-vector, non-keyword form
  (a compiled `malli.core/schema` object, a map, a fn, …). Per Spec 010
  §The `:schema` value is opaque to re-frame the walker MUST NOT call
  into the registered validator to introspect structure, so it walks the
  vector-form Malli EDN itself; a compiled `m/schema` value is an opaque
  leaf and its internal per-slot `:sensitive?` / `:large?` flags are
  invisible to the walk (rf2-u9bjgr).

  A bare KEYWORD (`:int` / `:string` / a registry ref) is NOT opaque
  here: a primitive keyword provably carries no per-slot props, so the
  walker provably skips nothing — treating every keyword failure as
  fail-closed-sensitive would over-redact every plain scalar failure for
  the rare registry-ref true positive (the same rf2-ee38b.6 false-positive
  tradeoff the `:rf.warning/schema-walker-opaque` nudge already makes by
  staying silent on keywords). Only genuinely opaque compiled values
  (which Malli DOES honour `:sensitive?` on for validation, but the
  walker cannot see) fail closed.

  Used by the validation-failure redaction path: an opaque schema's
  validation failure redacts FAIL-CLOSED (the walker cannot prove the
  value is non-sensitive, and an opaque compiled schema may carry a
  `{:sensitive? true}` slot Malli honoured for the failure), per EP-0015's
  central projection / fail-closed expectation for schema-validation
  egress. The supported route to make per-slot flags VISIBLE (and avoid
  the coarse whole-value fail-closed redaction) is registering the vector
  form, per the `:rf.warning/schema-walker-opaque` nudge.

  Returns boolean. Pure."
  [schema]
  (not (or (vector? schema) (keyword? schema))))

(defn- prefix?
  "True when `prefix` is a prefix of `path` (or equal). Both are
  indexed vectors compared element-wise. Single-pass: counts each
  input once, no lazy-seq allocation (rf2-ikxb5 — call-site is the
  hot `schema-sensitive-at?` `some` over `(keys decls)`)."
  [prefix path]
  (let [pn (count prefix)]
    (and (<= pn (count path))
         (loop [i 0]
           (cond
             (== i pn)                    true
             (= (nth prefix i) (nth path i)) (recur (inc i))
             :else                         false)))))

;; Schema ops whose `:in` segment is a COLLECTION INDEX / KEY, not an
;; app-db path segment. Malli's explainer reports a value-relative `:in`
;; path that descends INTO collection elements (`[1 :token]` for a
;; vector-of-maps, `["a" :secret]` for a map-of), but the walker builds
;; its `{path declaration}` map at INDEX-FREE base-paths — homogeneous
;; positional / keyed containers descend at the same base-path (walker
;; comment lines ~145-148) because the element index is not a declarable
;; app-db slot. Aligning the two coordinate systems means dropping the
;; collection-navigation segment that these ops contribute. `:map-of`
;; descends into its VALUE schema (child index 1); the homogeneous
;; positional ops descend into their single element schema. Per rf2-g5auo
;; — without this alignment a `:sensitive?` slot nested in a collection
;; leaks verbatim.
;;
;; `:tuple` / `:cat` / `:catn` are the membership outliers (`:tuple`
;; rf2-ss06u.4; `:cat`/`:catn` rf2-4q681i): they are kept in this set so
;; `sanitize-sensitive-path` treats their integer index as a navigable
;; locator (KEEP, not scrub), but `align-in-path` handles them in their
;; OWN position-KEEPING branch ABOVE the generic index-drop branch — each
;; element is heterogeneous (per-position schema), so the index IS a
;; discriminating segment and must NOT be dropped, else sibling positions
;; collapse and over-redact. (`align-in-path` never reaches the generic
;; index-drop branch below for these three; their `position-bearing-ops`
;; branch fires first.)
(def ^:private index-bearing-ops
  #{:vector :sequential :set :tuple :cat :catn :map-of})

(defn- align-in-path
  "Translate Malli's value-relative `:in` path (carrying collection
  indices / map-of keys) into the walker's index-free declaration-path
  coordinate system, walking `schema` in lockstep with `in-path`.

  Per rf2-g5auo: the `:sensitive?` redaction lookup
  (`schema-sensitive-at?`) compares `:in` against the walker's decl
  paths via `prefix?`, but a collection index segment (`1` in
  `[1 :token]`, `\"a\"` in `[\"a\" :secret]`) blocks the element-wise
  match because the walker never emits index segments. Dropping each
  index-bearing op's segment while descending re-aligns the two.

  Returns `[:ok aligned-path]` when the whole path resolves against
  recognised ops, or `[:fallback subschema aligned-prefix]` when an
  unrecognised / opaque op is hit with path remaining — the caller then
  redacts fail-SAFE iff the leftover `subschema` OR the **already-aligned
  prefix** carries any sensitive declaration. `:map` segments are kept
  (real app-db keys); index-bearing-op segments are dropped.

  Per rf2-ss06u.2 the fallback MUST carry `aligned-prefix` (the segments
  consumed so far) alongside the leftover subschema: a `:sensitive?`
  declaration on an ANCESTOR that align-in-path already consumed and
  discarded (e.g. `[:s]` marked sensitive, the failing leaf `[:s :k]`
  living under a transparent `:and` / `:multi` / `:orn` wrapper) is NOT
  visible in the leftover subtree. Without the prefix the caller's
  `schema-has-sensitive?` on the leftover returns false and the failing
  value LEAKS verbatim — the exact data the `:sensitive?` feature exists
  to protect. Returning the prefix lets `schema-sensitive-at?` test the
  consumed-ancestor sensitivity (`prefix? decl-path aligned-prefix`)
  too, so a descendant failure under a sensitive ancestor stays
  redacted + stamped."
  [schema in-path]
  (loop [schema  schema
         in      (vec in-path)
         aligned []]
    (if (empty? in)
      [:ok aligned]
      (if-not (and (vector? schema) (pos? (count schema)))
        ;; Path remains but the schema is a bare keyword / opaque leaf —
        ;; cannot descend further; hand the leaf to the conservative
        ;; fallback (carrying the consumed prefix for ancestor-sensitivity).
        [:fallback schema aligned]
        (let [op       (nth schema 0)
              children (children-of schema)
              seg      (nth in 0)]
          (cond
            ;; `:map` — the `:in` segment is a real app-db key. Keep it
            ;; and descend into the named child's tail schema.
            (contains? name-bearing-ops op)
            (if-let [child (some (fn [c]
                                   (when (and (vector? c) (>= (count c) 2)
                                              (= (nth c 0) seg))
                                     c))
                                 children)]
              (let [has-prop? (and (>= (count child) 2) (map? (nth child 1)))
                    tail      (if has-prop?
                                (when (>= (count child) 3) (nth child 2))
                                (nth child 1))]
                (recur tail (subvec in 1) (conj aligned seg)))
              ;; Key not found in the schema (shape drift) — fail-SAFE.
              [:fallback schema aligned])

            ;; `:tuple` / `:cat` / `:catn` — POSITION-bearing (`:tuple`
            ;; rf2-ss06u.4; `:cat`/`:catn` rf2-4q681i). Unlike the
            ;; homogeneous index-bearing ops below, each element has its OWN
            ;; schema, so the integer index IS a discriminating segment (the
            ;; positional analogue of a `:map` key). Malli reports the
            ;; integer position in `:in` for ALL THREE (a `:catn` failure
            ;; reports `[1 :age]`, the position not the name). KEEP the index
            ;; in `aligned` — the walker emits per-position decl-paths
            ;; (`(conj base i)`), so a failure at element `i` aligns to that
            ;; same `[… i]` and prefix-matches ONLY that position's
            ;; declaration. Dropping it (the prior `:cat` behaviour, which
            ;; collapsed every element onto the shared base-path) made a
            ;; sensitive sibling over-redact an unrelated non-sensitive
            ;; failure — the rf2-4q681i fix. `:tuple`/`:cat` element `seg` is
            ;; `children[seg]` (bare schema); `:catn` element `seg` is a
            ;; NAME-bearing entry (`[name props? schema]`) so we descend into
            ;; its schema part.
            (contains? position-bearing-ops op)
            (if-let [child (when (and (int? seg) (< seg (count children)))
                             (nth children seg))]
              (let [elem-schema (if (= op :catn)
                                  (if (and (vector? child) (>= (count child) 2))
                                    (if (map? (nth child 1))
                                      (when (>= (count child) 3) (nth child 2))
                                      (nth child 1))
                                    nil)
                                  child)]
                (if (some? elem-schema)
                  (recur elem-schema (subvec in 1) (conj aligned seg))
                  [:fallback schema aligned]))
              [:fallback schema aligned])

            ;; Homogeneous index-bearing container — drop the index/key
            ;; segment and descend into the element (or `:map-of` value)
            ;; schema. `:vector`/`:sequential`/`:set` have one shared
            ;; element schema; `:map-of`'s value schema is child 1. The
            ;; index/key is not a declarable slot for these, so it is
            ;; dropped to align with the walker's index-free decl-paths.
            (contains? index-bearing-ops op)
            (let [child (nth children (if (= op :map-of) 1 0) nil)]
              (if (some? child)
                (recur child (subvec in 1) aligned)
                [:fallback schema aligned]))

            ;; Transparent wrappers contribute NO `:in` segment — descend
            ;; into the (single) inner schema without consuming a segment.
            (#{:maybe} op)
            (if-let [child (first children)]
              (recur child in aligned)
              [:fallback schema aligned])

            ;; Any other op (`:and`/`:or`/`:multi`/`:orn`/registry refs/
            ;; opaque values) — we can't reliably resolve the segment;
            ;; redact fail-SAFE iff the leftover subschema OR the consumed
            ;; ancestor prefix declares anything sensitive (rf2-ss06u.2 —
            ;; the consumed ancestor's `:sensitive?` is invisible in the
            ;; leftover, so the prefix MUST ride along).
            :else
            [:fallback schema aligned]))))))

;; The privacy sentinel substituted for a value-bearing path segment.
;; Spec 009 §Privacy — the framework-reserved keyword. Kept as a local
;; literal here (the walker is pure-data and does not require core's
;; privacy ns) — `validate.cljc` already imports the canonical
;; `privacy/redacted-sentinel`; the two agree by definition.
(def ^:private path-redacted-sentinel :rf/redacted)

(defn sanitize-sensitive-path
  "Return `in-path` with every VALUE-BEARING segment replaced by the
  `:rf/redacted` sentinel, walking `schema` in lockstep with the raw
  `in-path`. Per rf2-ss06u.1 — privacy in the `:path` trace tag.

  Malli reports a `:set` failure's `:in` segment as the failing element
  VALUE itself (not an index — sets have no positional index), e.g.
  `:in = ({:token 99 :ssn \"...\"} :token)`. `validate-app-schema!`
  concats the raw `:in` into the structural `:path` tag, which Spec 010
  declares unredacted (`:path` is categorical / locator data) — so for a
  `:set` the entire failing element map (including any sibling secrets in
  it) ships VERBATIM in `:path`, defeating the `:sensitive?` redaction
  the `:value` / `:explain` slots already apply.

  Scrubbing rules:

    - `:set` — the segment is the failing ELEMENT VALUE; ALWAYS scrub it
      (it is both unnavigable AND value-bearing), even when scalar.
    - `:map-of` keys — navigable locators by default (KEEP them so `:path`
      stays a useful `get-in` locator), UNLESS the key SCHEMA itself is
      declared `:sensitive?` (rf2-612mri). A `[:map-of [:string
      {:sensitive? true}] …]` uses the secret AS the key; Malli reports
      that secret verbatim as the `:in` key segment, so a failing value
      under a sensitive key would otherwise ship the secret in `:path` /
      `:reason` despite `:value` / `:explain` being redacted. When the key
      schema declares sensitivity, the key segment is scrubbed.
    - `:map` keys, `:vector` / `:sequential` / `:tuple` / `:cat` / `:catn`
      integer indices — navigable scalar locators; KEEP them so `:path`
      stays a useful `get-in` locator for those shapes (the bead's regression
      requirement).
    - Transparent wrappers (`:maybe` / `:and` / `:or` / `:multi` / `:orn`)
      contribute NO `:in` segment — descend without consuming. For the
      single-child wrappers (`:maybe` / `:and` / `:or`) the inner schema is
      unambiguous so the lockstep walk continues precisely. For the
      multi-branch wrappers (`:multi` / `:orn`) and any other / opaque op
      the branch is ambiguous, so the walk drops to a FAIL-CLOSED tail.

  FAIL-CLOSED tail (rf2-ss06u.1 / rf2-ss06u.2 / rf2-612mri): once the
  lockstep walk cannot confidently continue (a multi-branch / opaque op,
  or the schema bottoms out before the path does), EVERY remaining segment
  is scrubbed — scalars included. Past an unresolvable point the sanitizer
  cannot PROVE a segment is a structural locator: a scalar in the tail may
  be a navigable index/key OR a value-bearing `:set` scalar element (the
  rf2-612mri leak shape — `[:orn [:tokens [:set [:string {:sensitive?
  true}]]]]` reports `:in = [123456789]`, the secret element itself, and
  the prior scalar-keep pass leaked it into `:path` / `:reason`), and a
  non-scalar can only be a value-bearing collection. Since the sanitizer
  runs ONLY on slots already proven `:sensitive?`, fail-closed scrubbing of
  the unresolvable tail can never lose navigability that matters (the
  resolvable `:map` / `:vector` / `:tuple` / `:map-of` shapes keep their
  locators on their OWN branches above and never reach the tail) — and
  keeping any tail scalar would under-redact. Per the bead: over-redaction
  on these wrapper shapes is acceptable; the value-protection direction
  must never under-redact. This closes both the deep nesting the
  adversarial generator surfaced (`{:a #{{:auth #{{:secret …}}}}}` under an
  `:orn`) and the scalar set-element leak (rf2-612mri).

  Pure; same `(schema, in-path)` always produces the same output.
  Returns a vector."
  [schema in-path]
  (letfn [(fail-closed-tail [out in]
            ;; Cannot resolve the schema further — scrub EVERY remaining
            ;; segment (rf2-612mri). A tail scalar cannot be proven a
            ;; structural locator past an unresolvable op (it may be a
            ;; value-bearing `:set` element), so keeping it would
            ;; under-redact; the resolvable navigable shapes keep their
            ;; locators on their own branches above and never reach here.
            (into out (map (constantly path-redacted-sentinel)) in))]
    (loop [schema schema
           in     (vec in-path)
           out    []]
      (if (empty? in)
        out
        (if-not (and (vector? schema) (pos? (count schema)))
          (fail-closed-tail out in)
          (let [op       (nth schema 0)
                children (children-of schema)
                seg      (nth in 0)]
            (cond
              ;; `:map` — real app-db key; keep it and descend into the
              ;; named child's tail schema.
              (contains? name-bearing-ops op)
              (if-let [child (some (fn [c]
                                     (when (and (vector? c) (>= (count c) 2)
                                                (= (nth c 0) seg))
                                       c))
                                   children)]
                (let [has-prop? (and (>= (count child) 2) (map? (nth child 1)))
                      tail      (if has-prop?
                                  (when (>= (count child) 3) (nth child 2))
                                  (nth child 1))]
                  (recur tail (subvec in 1) (conj out seg)))
                ;; Key not found (shape drift) — fail-closed on the rest.
                (fail-closed-tail (conj out seg) (subvec in 1)))

              ;; `:set` — the segment is the failing ELEMENT VALUE. ALWAYS
              ;; scrub (unnavigable + value-bearing) and descend into the
              ;; element schema.
              (= op :set)
              (recur (nth children 0 nil) (subvec in 1) (conj out path-redacted-sentinel))

              ;; `:map-of` — the segment is the failing entry's KEY (Malli
              ;; reports the key VALUE verbatim, e.g. `["secret-token-123"
              ;; :age]`). A `:map-of` key is normally a navigable locator and
              ;; is KEPT so `:path` stays a `get-in` locator — BUT when the
              ;; KEY SCHEMA (child 0) itself declares `:sensitive?`
              ;; (rf2-612mri), the key IS the secret and must be scrubbed,
              ;; else it ships verbatim in `:path` / `:reason` despite
              ;; `:value` / `:explain` being redacted. Descend into the
              ;; VALUE schema (child 1) either way.
              (= op :map-of)
              (let [key-schema (nth children 0 nil)
                    val-schema (nth children 1 nil)
                    seg-out    (if (schema-has-sensitive? key-schema)
                                 path-redacted-sentinel
                                 seg)]
                (recur val-schema (subvec in 1) (conj out seg-out)))

              ;; Other index-bearing ops — the segment is a navigable index
              ;; (`:vector` / `:sequential` / `:tuple` / `:cat` / `:catn`);
              ;; keep it and descend into the element schema. The
              ;; per-position ops (`:tuple` / `:cat` / `:catn`, rf2-ss06u.4 /
              ;; rf2-4q681i) index `children[seg]`; `:catn` then strips the
              ;; decorative name (`[name props? schema]`) to reach the
              ;; element schema. The homogeneous ones (`:vector` /
              ;; `:sequential`) share one element schema (child 0).
              (contains? index-bearing-ops op)
              (let [child (cond
                            (#{:tuple :cat} op)
                            (when (and (int? seg) (< seg (count children)))
                              (nth children seg))

                            (= op :catn)
                            (when (and (int? seg) (< seg (count children)))
                              (let [entry (nth children seg)]
                                (when (and (vector? entry) (>= (count entry) 2))
                                  (if (map? (nth entry 1))
                                    (when (>= (count entry) 3) (nth entry 2))
                                    (nth entry 1)))))

                            :else
                            (nth children 0 nil))]
                (recur child (subvec in 1) (conj out seg)))

              ;; Single-child transparent wrappers — no `:in` segment
              ;; consumed; descend into the (unambiguous) inner schema.
              (#{:maybe :and :or} op)
              (if-let [child (first children)]
                (recur child in out)
                (fail-closed-tail out in))

              ;; Multi-branch / opaque op — the branch is ambiguous, so the
              ;; lockstep walk cannot continue safely. Fail CLOSED.
              :else
              (fail-closed-tail out in))))))))

(defn schema-sensitive-at?
  "Path-targeted sensitivity check (rf2-oh4se). Returns true when the
  slot at `in-path` inside `schema` is sensitive under Spec 010
  §`:sensitive?`. `in-path` is the navigation path relative to the
  schema's root (the value path Malli reports as `:in` in its explain
  output, NOT the `:path` slot which encodes branch dispatch values).

  A slot at `in-path` is sensitive when EITHER:

    - An **ancestor** along the path is `:sensitive?` — the failing
      slot sits underneath a sensitive container, so its value is part
      of a sensitive subtree (e.g. `[:auth]` is sensitive, the failing
      slot is `[:auth :token]`).

    - A **descendant** of the slot is `:sensitive?` — the failing
      slot's value contains a sensitive child (e.g. `[:user]` is the
      failing slot, `[:user :password]` is declared sensitive; the
      value at `[:user]` carries the password verbatim and would
      re-leak it if shipped).

  Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces; replaces the coarse whole-schema `schema-has-sensitive?`
  check at the `validate-app-schema!` emit-site when a leaf path is
  extractable from the explainer output.

  When `in-path` is nil or empty the check is equivalent to
  `schema-has-sensitive?` (the failing slot IS the whole registered
  schema, so any sensitive declaration anywhere counts).

  Malli's `:in` carries collection indices / `:map-of` keys
  (`[1 :token]`, `[\"a\" :secret]`) whereas the walker's decl paths are
  index-free (`[:token]`, `[:secret]`) — positional/keyed containers
  descend at the same base-path. Per rf2-g5auo the raw `:in` is first
  aligned to the walker's coordinate system (`align-in-path`) before the
  prefix match, so a `:sensitive?` slot nested inside a `:vector` /
  `:sequential` / `:set` / `:tuple` / `:map-of` is matched (and
  redacted) just like a top-level one. When the path cannot be fully
  aligned (opaque / unrecognised op with path remaining) the check is
  fail-SAFE: it redacts iff the leftover subschema declares anything
  sensitive (descendant under the unresolved op) OR a sensitive
  declaration is an ANCESTOR of the already-consumed prefix (rf2-ss06u.2
  — a `:sensitive?` container the alignment already descended through and
  discarded, e.g. `[:s]` sensitive with the leaf `[:s :k]` under a
  transparent `:and` / `:multi` / `:orn` wrapper).

  Returns boolean. Pure; same `(schema, in-path)` always produces the
  same output."
  [schema in-path]
  (if (or (nil? in-path) (empty? in-path))
    (schema-has-sensitive? schema)
    (let [[outcome aligned-or-sub aligned-prefix] (align-in-path schema in-path)]
      (if (= outcome :fallback)
        ;; Couldn't fully resolve the path. Redact fail-SAFE iff EITHER:
        ;;   - the leftover subschema carries any sensitive declaration
        ;;     (a descendant under the unresolved op), OR
        ;;   - a sensitive declaration is an ancestor of (or equal to) the
        ;;     prefix align-in-path already consumed (rf2-ss06u.2 — the
        ;;     consumed-ancestor `:sensitive?` is invisible in the leftover
        ;;     subtree; without this the failing value under a sensitive
        ;;     ancestor wrapped by :and/:multi/:orn LEAKS verbatim).
        ;; The ancestor check is `prefix? decl-path aligned-prefix` only —
        ;; a sensitive SIBLING outside the consumed prefix must NOT taint
        ;; the failing slot (preserves the precise-narrowing win).
        (or (schema-has-sensitive? aligned-or-sub)
            (let [decls (extract-sensitive-paths-from-schema schema [])]
              (boolean
                (some (fn [decl-path] (prefix? decl-path aligned-prefix))
                      (keys decls)))))
        (let [decls   (extract-sensitive-paths-from-schema schema [])
              in-v    aligned-or-sub]
          (boolean
            (some (fn [decl-path]
                    (or (prefix? decl-path in-v)   ;; ancestor sensitive
                        (prefix? in-v decl-path))) ;; descendant sensitive
                  (keys decls))))))))
