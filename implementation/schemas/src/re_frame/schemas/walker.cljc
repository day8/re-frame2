(ns re-frame.schemas.walker
  "Pure-data extractor for per-slot flags in Malli EDN schemas.

  `:large?` and `:sensitive?` slot props are projected to
  `{path declaration}` maps with `:source :schema`. Owner-local consumers use
  these declarations for validation-failure and transient egress projection.
  Schemas do not populate durable app-db classification; commit-plane effects
  own that policy.

  The walker does not depend on Malli and does not ask the registered
  validator to interpret a schema. It recognizes vector forms shaped as
  `[op props? children...]`. Compiled schema values and registry references
  are opaque, so their internal flags cannot be extracted. Registration warns
  once for compiled or nested opaque values. Keyword registry references stay
  silent because the framework cannot distinguish them from primitive keyword
  schemas without violating the opaque-schema boundary.

  Register vector-form EDN when per-slot flags must be visible. Compiled and
  nested opaque schemas fail closed in validation redaction; keyword registry
  references remain flag-invisible and therefore require the vector form for
  precise privacy declarations.
  The traversal is parameterized by flag key so both supported flags share the
  same operator and path semantics."
  (:require [re-frame.schemas.cache :as rf.schemas.cache]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private name-bearing-ops
  "Schema ops whose children carry name slots (the first element of a
  child entry is the slot's app-db key)."
  #{:map})

(def ^:private dispatch-bearing-ops
  "Schema ops whose children carry dispatch-value branches (the first
  element of a child entry is a dispatch value, not an app-db path
  segment).

  `:catn` is not here: although its children carry name
  slots like `:orn`/`:altn`, Malli reports a `:catn` failure's `:in`
  segment as the element's INTEGER POSITION (`[1 :age]`), not the name —
  identical to `:cat`. So `:catn` is position-bearing (see
  `position-bearing-ops`), and its name slot is decorative for the
  declaration-path coordinate system."
  #{:multi :orn :altn})

(def ^:private position-bearing-ops
  "Schema ops whose children are POSITION-bearing — each element `i` has
  its OWN heterogeneous schema and Malli reports a failure's `:in` segment
  as the integer index `i`. The walker descends element `i` at
  `(conj base-path i)` (the positional analogue of a `:map` key), giving
  per-element sibling precision: a per-position flag claims that exact
  index, not the shared base-path.

    - `:tuple` — element `i` is `children[i]`, a bare schema.
    - `:cat` / `:catn` — regex sequence combinators that
      root event schemas (`[:cat [:= :id] PayloadSchema]`). `:cat`
      elements are bare schemas (`children[i]`); `:catn` elements are
      NAME-bearing entries (`[name props? schema]`) but Malli still
      reports the integer position in `:in`, so the name is decorative and
      the position drives the coordinate. Both descend the element schema
      at `(conj base-path i)`.

  The position-pinned declaration path is matching-safe against the core-elision
  coordinate system: the runtime elision walk descends a tuple / event-
  vector value (a vector) through its literal-index fork (`fork-index-paths`
  — `(conj c i)` in `re-frame.elision`), which matches a position-pinned
  declaration exactly. No elision-side change is required — the index fork
  is schema-agnostic (it walks the runtime value), so `:cat`/`:catn`/`:tuple`
  all align through the same generic path."
  #{:tuple :cat :catn})

(defn- schema-properties
  "Return the per-slot props map of a Malli vector form, or nil. Convention:
  `[op {props} children...]` carries the map at position 1."
  [schema-form]
  (let [properties (when (and (vector? schema-form) (>= (count schema-form) 2))
                     (nth schema-form 1))]
    (when (map? properties) properties)))

(defn- schema-children
  "Return the children portion of a Malli vector form — drop the op
  keyword and the optional props map."
  [schema-form]
  (let [schema-tail (subvec schema-form 1)]
    (if (and (seq schema-tail) (map? (first schema-tail)))
      (subvec schema-tail 1)
      schema-tail)))

(defn- declaration-from-properties
  "Build a declaration map `{flag-key true :source :schema}` (plus
  optional `:hint` propagated verbatim) from a slot's per-slot props,
  or nil when `flag-key` is not set to `true`. Per Spec 009 §Size
  elision in traces and Spec 010 §`:sensitive?` — `:hint` is optional
  and omitted when absent so the marker shape stays minimal. The
  `:source :schema` slot records schema provenance for the owner-local
  consumer (machine / resource `:data-schema`, HTTP body-privacy,
  story-mcp) that reads the extracted map, not the durable app-db
  classification registry."
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

    - `:tuple` / `:cat` / `:catn` children are position-bearing: each element
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
      the coordinate. The position-pinned declaration path is matching-safe
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
           acc'     (if-let [declaration (declaration-from-properties
                                           flag-key (schema-properties schema))]
                      (assoc acc base-path declaration)
                      acc)
           children (schema-children schema)]
       (cond
         (contains? name-bearing-ops op)
         (reduce
           (fn [acc child]
             (if-not (and (vector? child) (>= (count child) 2))
               acc
               (let [slot-key             (nth child 0)
                     schema-or-properties (nth child 1)
                     properties?          (map? schema-or-properties)
                     slot-path            (conj base-path slot-key)
                     child-schema         (if properties?
                                            (when (>= (count child) 3) (nth child 2))
                                            schema-or-properties)
                     acc                  (if-let [declaration
                                                   (and properties?
                                                        (declaration-from-properties
                                                          flag-key schema-or-properties))]
                                            (assoc acc slot-path declaration)
                                            acc)]
                 (if (some? child-schema)
                   (walk-flagged-schema flag-key child-schema slot-path acc)
                   acc))))
           acc'
           children)

         (contains? dispatch-bearing-ops op)
         (reduce
           (fn [acc child]
             (if-not (vector? child)
               acc
               (let [schema-or-properties (when (>= (count child) 2) (nth child 1))
                     properties?          (map? schema-or-properties)
                     schema-index         (if properties? 2 1)
                     child-schema         (when (> (count child) schema-index)
                                            (nth child schema-index))
                     acc                  (if-let [declaration
                                                   (and properties?
                                                        (declaration-from-properties
                                                          flag-key schema-or-properties))]
                                            (assoc acc base-path declaration)
                                            acc)]
                 (if (some? child-schema)
                   (walk-flagged-schema flag-key child-schema base-path acc)
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
                     [element-schema entry-declaration]
                     (if (= op :catn)
                       (if (and (vector? child) (>= (count child) 2))
                         (let [schema-or-properties (nth child 1)
                               properties?          (map? schema-or-properties)
                               schema                (if properties?
                                                       (when (>= (count child) 3)
                                                         (nth child 2))
                                                       schema-or-properties)]
                           [schema (and properties?
                                        (declaration-from-properties
                                          flag-key schema-or-properties))])
                         [nil nil])
                       [child nil])
                     acc (if entry-declaration
                           (assoc acc slot-path entry-declaration)
                           acc)]
                 [(if (some? element-schema)
                    (walk-flagged-schema flag-key element-schema slot-path acc)
                    acc)
                  (inc i)]))
             [acc' 0]
             children))

         :else
         (reduce (fn [acc child]
                   (walk-flagged-schema flag-key child base-path acc))
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
  provenance for its wire-boundary elision. This map does not feed durable
  app-db classification."
  [schema base-path]
  (walk-flagged-schema :large? schema base-path {}))

;; The sensitive-path walk is memoized for boot-time schema reuse and
;; clearable by fixtures that generate many distinct schemas.
(let [[memo clear!]
      (rf.schemas.cache/clearable-memo
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

            Memoised by `(schema, base-path)`: the failure branch
            (`schema-sensitive-at?`) re-walks the same
            registered schema on every consecutive failure, and the walk
            is pure over immutable schema values. The cache is bounded by
            the (registered-schema, base-path) cardinality — schemas are
            registered once at app-boot, so steady-state cache size equals
            the registry size.

            The memo is clearable for test isolation via
            `clear-sensitive-paths-cache!`."
      :arglists '([schema base-path])}
    extract-sensitive-paths-from-schema memo)

  (def
    ^{:doc "Reset the `extract-sensitive-paths-from-schema` memo cache.
            The walker memo is process-
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

  Whole-payload trace slots can contain a conforming sensitive sibling, so
  their redaction uses this schema-wide predicate. The app-db leaf value uses
  the narrower `schema-sensitive-at?` decision.

  Returns boolean. Pure; same input always produces the same output."
  [schema]
  (-> (extract-sensitive-paths-from-schema schema [])
      seq
      boolean))

(defn schema-has-large?
  "True when the registered schema declares ANY slot `:large? true` —
  either the schema's container-level props, or a nested slot anywhere
  inside the schema. Per Spec 010 §`:large?` — schema-driven
  size-elision nomination and validation-failure size-safety arm.

  The mirror of `schema-has-sensitive?` for size classification. Validation
  traces include whole-payload slots, so a nested large value could make the
  failure payload itself large. When this
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
  leaf and its internal per-slot flags are invisible to the walk.

  A bare KEYWORD (`:int` / `:string` / a registry ref) is NOT opaque
  here: a primitive keyword provably carries no per-slot props, so the
  walker provably skips nothing — treating every keyword failure as
  fail-closed-sensitive would over-redact every plain scalar failure for
  the rare registry-ref true positive. Only genuinely opaque compiled values
  (which Malli DOES honour `:sensitive?` on for validation, but the
  walker cannot see) fail closed.

  Used by the validation-failure redaction path: an opaque schema's
  validation failure redacts FAIL-CLOSED (the walker cannot prove the
  value is non-sensitive, and an opaque compiled schema may carry a
  `{:sensitive? true}` slot Malli honoured for the failure). The supported
  route to make per-slot flags visible (and avoid
  the coarse whole-value fail-closed redaction) is registering the vector
  form, per the `:rf.warning/schema-walker-opaque` nudge.

  Returns boolean. Pure."
  [schema]
  (not (or (vector? schema) (keyword? schema))))

;; ---- opacity-recursion operator classification ----------------------------
;;
;; The opacity walk (`schema-has-opaque-child?` / `opaque-nested-tail?`)
;; recurses a vector-form schema's REAL child-schema positions to detect a
;; nested opaque (compiled) value whose per-slot `:sensitive?` flag the
;; pure-data walker cannot see. A Malli vector form is `[op props? & tail]`,
;; but the tail is a child SCHEMA only for STRUCTURAL ops. For LITERAL / config
;; ops the tail is DATA — `[:= 42]` holds the value `42`, `[:enum 1 2]` the
;; members, `[:> 10]` a comparator bound, `[:re "x"]` a pattern, `[:ref ::k]`
;; a reference — and the scalar primitives (`:int`, `:string`, …) carry no
;; child schema at all. Recursing into those data operands is the rf2-3fc89f.12
;; bug: an ordinary literal (`42`, `"x"`) reaches the opaque `:else` and the
;; whole schema is false-flagged as carrying an opaque child. So the walk
;; projects the true child schemas per operator instead of treating every tail
;; element as a child. Classification is structural (no Malli/validator
;; introspection) and covers the shipped Malli 0.20.1 default registry.

;; Ops whose children are `[head props? child-schema]` ENTRIES — the head is a
;; map key / dispatch value / branch name (data); only the entry tail is a real
;; child position. Reuses the walker's `name-bearing-ops` (`:map`) and
;; `dispatch-bearing-ops` (`:multi` / `:orn` / `:altn`) categories and adds the
;; named sequence combinators (`:catn` / `:andn`). (`:catn` is entry-shaped for
;; opacity even though the declaration-path walk treats it as position-bearing — here
;; only the child-schema position matters, which is the entry tail.)
(def ^:private opacity-entry-ops
  (into (into name-bearing-ops dispatch-bearing-ops) #{:catn :andn}))

;; Ops whose children are BARE child schemas: homogeneous container element
;; schemas (`:vector` `:set` `:sequential` `:seqable` `:every`); tuple / cat /
;; alt / regex-quantifier elements (`:tuple` `:cat` `:alt` `:*` `:+` `:?`
;; `:repeat`); transparent / combinator children (`:and` `:or` `:not` `:maybe`
;; `:schema` `:malli.core/schema`); `:map-of` key + value schemas; and function
;; schemas (`:->` `:=>` `:function`). Every tail element is a real schema
;; position, so the walk descends each directly.
(def ^:private opacity-bare-ops
  #{:and :or :not :maybe
    :vector :sequential :set :seqable :every
    :tuple :cat :alt :* :+ :? :repeat
    :schema :malli.core/schema :map-of
    :-> :=> :function})

;; Ops whose vector tail is DATA (a comparator bound, `:=` / `:enum` members,
;; a regex pattern, a predicate fn, a registry reference) or which carry no
;; child schema at all (scalar primitives). The opacity walk MUST NOT descend
;; into these — their tails are operands, not child schemas.
(def ^:private opacity-literal-ops
  #{:= :not= :enum :< :<= :> :>= :re :fn :ref
    :any :boolean :double :float :int :keyword :nil
    :qualified-keyword :qualified-symbol :some :string :symbol :uuid})

(defn- entry-child-schema
  "Return the child SCHEMA of a Malli entry `[head props? schema]` (a `:map`
  key entry or a `:multi` / `:orn` / `:altn` / `:andn` / `:catn` branch). The
  head is data (key / dispatch value / name), so only the tail is a real child
  position. Returns nil for a tail-less entry (nothing to walk), or a non-entry
  value unchanged so a malformed opaque child is still inspected (fail-closed)."
  [entry]
  (if (and (vector? entry) (>= (count entry) 2))
    (if (map? (nth entry 1))
      (nth entry 2 nil)
      (nth entry 1))
    entry))

(defn- opacity-child-schemas
  "Operator-aware projection of the child SCHEMAS a vector-form schema exposes
  to the opacity walk (see the operator-classification note above). Returns a
  sequence of child schemas for a known structural op, an empty sequence for a
  known literal / scalar op (its tail is data), or `::opaque` for an
  unclassified op — the walk cannot prove that op's tail is not a
  schema-bearing position, so it fails closed."
  [schema]
  (let [op (nth schema 0)]
    (cond
      (contains? opacity-literal-ops op) []
      (contains? opacity-bare-ops op)    (schema-children schema)
      (contains? opacity-entry-ops op)
      (into [] (comp (map entry-child-schema) (remove nil?))
            (schema-children schema))
      :else ::opaque)))

(defn- opaque-nested-tail?
  "Private recursive helper for `schema-has-opaque-child?`. True when `schema`
  — a value reached by descending into a real child-schema position — is an
  opaque compiled value, OR a vector-form schema that (operator-awarely)
  contains an opaque descendant, OR an unclassified operator shape (fail
  closed). A bare keyword / fn / symbol reached here is NOT opaque: it is a
  primitive schema or a predicate shorthand that provably carries no per-slot
  props (the enclosing entry is the only place a flag could live, and the walk
  inspects that entry before its tail). Literal / config operands (`:=` value,
  `:enum` members, comparator bounds, `:re` pattern, `:ref` target) are DATA,
  not child schemas, so the projection never descends into them. Only ever
  called on a descended value, never on the caller's original root argument —
  see `schema-has-opaque-child?` for the root/nested split."
  [schema]
  (cond
    (keyword? schema) false
    (fn? schema) false
    (symbol? schema) false
    (and (vector? schema) (pos? (count schema)))
    (let [children (opacity-child-schemas schema)]
      (if (= ::opaque children)
        true
        (boolean (some opaque-nested-tail? children))))
    :else true))

(defn schema-has-opaque-child?
  "True when the root is opaque, or a vector-form schema contains an opaque
  descendant in a real child-schema position at any depth, or the root is an
  unclassified operator shape. Redaction and registration warnings use this
  recursive predicate so a compiled child cannot hide inside a walkable root.

  The recursion is OPERATOR-AWARE (rf2-3fc89f.12): it descends only the true
  child-schema positions of each Malli operator. Literal / config operands
  (`:=` value, `:enum` members, `:re` pattern, comparator bounds, `:ref`
  target, scalar primitives) are DATA, not child schemas, so they are NOT
  recursed into — an ordinary `[:= 42]` / `[:enum 1 2]` is fully walkable and
  NOT opaque. An actual compiled value in a real schema position (a `:map`
  slot's tail, a container element, a `:map-of` key/value, a `:cat`/`:tuple`
  element, a `:multi`/`:orn` branch, …) still fails closed, as does a genuinely
  unknown operator shape.

  Root functions and symbols are opaque and fail closed (via `schema-opaque?`).
  The same values used as nested schema tails are considered flag-free because
  their enclosing entry is the only place slot props can occur, and the walker
  inspects that entry before its tail.

  Returns boolean. Pure."
  [schema]
  (or (schema-opaque? schema)
      (and (vector? schema) (pos? (count schema))
           (opaque-nested-tail? schema))))

(defn- prefix?
  "True when `prefix` is a prefix of `path` (or equal). Both are
  indexed vectors compared element-wise. Single-pass with no lazy-seq
  allocation."
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
;; positional ops descend into their single element schema. Without this
;; alignment a sensitive slot nested in a collection would not match.
;;
;; `:tuple` / `:cat` / `:catn` are membership outliers: they are kept here so
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

  `schema-sensitive-at?` compares `:in` against the walker's declaration
  paths via `prefix?`, but a collection index segment (`1` in
  `[1 :token]`, `\"a\"` in `[\"a\" :secret]`) blocks the element-wise
  match because the walker never emits index segments. Dropping each
  index-bearing op's segment while descending re-aligns the two.

  Returns `[:ok aligned-path leaf-schema]` when the whole path resolves
  against recognised ops, or `[:fallback subschema aligned-prefix]` when
  an unrecognised / opaque op is hit with path remaining — the caller
  then redacts fail-SAFE iff the leftover `subschema` OR the
  **already-aligned prefix** carries any sensitive declaration, OR
  either subtree is opaque. `:map` segments are kept (real app-db
  keys); index-bearing-op segments are dropped.

  Per rf2-ss06u.2 the fallback MUST carry `aligned-prefix` (the segments
  consumed so far) alongside the leftover subschema: a `:sensitive?`
  declaration on an ANCESTOR that align-in-path already consumed and
  discarded (e.g. `[:s]` marked sensitive, the failing leaf `[:s :k]`
  living under a transparent `:and` / `:multi` / `:orn` wrapper) is NOT
  visible in the leftover subtree. Without the prefix the caller's
  `schema-has-sensitive?` on the leftover returns false and the failing
  value LEAKS verbatim — the exact data the `:sensitive?` feature exists
  to protect. Returning the prefix lets `schema-sensitive-at?` test the
  consumed-ancestor sensitivity (`prefix? declaration-path aligned-prefix`)
  too, so a descendant failure under a sensitive ancestor stays
  redacted + stamped.

  Per rf2-hi0tf8 the `:ok` outcome ALSO carries the `leaf-schema` the
  walk arrived at (the schema at `in-path`'s terminus), not just the
  aligned path: a path can resolve cleanly through vector-form `:map` /
  `:tuple` / … structure right up to a NESTED opaque child (a compiled
  `m/schema` value used as a map slot's tail, e.g. `[:map [:token
  (m/schema [:string {:sensitive? true}])]]`) — the walk successfully
  \"arrives\" at that slot (the path is fully consumed), but the slot
  itself is unintrospectable. Without `leaf-schema` the caller has no
  way to notice the arrival point is opaque; `schema-sensitive-at?`
  consults it via `schema-has-opaque-child?` to fail closed."
  [schema in-path]
  (loop [schema         schema
         remaining-path (vec in-path)
         aligned-path   []]
    (if (empty? remaining-path)
      [:ok aligned-path schema]
      (if-not (and (vector? schema) (pos? (count schema)))
        ;; Path remains but the schema is a bare keyword / opaque leaf —
        ;; cannot descend further; hand the leaf to the conservative
        ;; fallback (carrying the consumed prefix for ancestor-sensitivity).
        [:fallback schema aligned-path]
        (let [op       (nth schema 0)
              children (schema-children schema)
              segment  (nth remaining-path 0)]
          (cond
            ;; `:map` — the `:in` segment is a real app-db key. Keep it
            ;; and descend into the named child's tail schema.
            (contains? name-bearing-ops op)
            (if-let [child (some (fn [candidate]
                                   (when (and (vector? candidate)
                                              (>= (count candidate) 2)
                                              (= (nth candidate 0) segment))
                                     candidate))
                                 children)]
              (let [properties? (and (>= (count child) 2)
                                     (map? (nth child 1)))
                    child-schema (if properties?
                                   (when (>= (count child) 3) (nth child 2))
                                   (nth child 1))]
                (recur child-schema
                       (subvec remaining-path 1)
                       (conj aligned-path segment)))
              ;; Key not found in the schema (shape drift) — fail-SAFE.
              [:fallback schema aligned-path])

            ;; `:tuple` / `:cat` / `:catn` — POSITION-bearing (`:tuple`
            ;; rf2-ss06u.4; `:cat`/`:catn` rf2-4q681i). Unlike the
            ;; homogeneous index-bearing ops below, each element has its OWN
            ;; schema, so the integer index IS a discriminating segment (the
            ;; positional analogue of a `:map` key). Malli reports the
            ;; integer position in `:in` for ALL THREE (a `:catn` failure
            ;; reports `[1 :age]`, the position not the name). KEEP the index
            ;; in `aligned-path` — the walker emits per-position declaration paths
            ;; (`(conj base i)`), so a failure at element `i` aligns to that
            ;; same `[… i]` and prefix-matches ONLY that position's
            ;; declaration. Dropping it (the prior `:cat` behaviour, which
            ;; collapsed every element onto the shared base-path) made a
            ;; sensitive sibling over-redact an unrelated non-sensitive
            ;; failure — the rf2-4q681i fix. `:tuple`/`:cat` element `segment` is
            ;; `children[segment]` (bare schema); `:catn` element `segment` is a
            ;; NAME-bearing entry (`[name props? schema]`) so we descend into
            ;; its schema part.
            (contains? position-bearing-ops op)
            (if-let [child (when (and (int? segment)
                                      (< segment (count children)))
                             (nth children segment))]
              (let [element-schema (if (= op :catn)
                                     (if (and (vector? child) (>= (count child) 2))
                                       (if (map? (nth child 1))
                                         (when (>= (count child) 3) (nth child 2))
                                         (nth child 1))
                                       nil)
                                     child)]
                (if (some? element-schema)
                  (recur element-schema
                         (subvec remaining-path 1)
                         (conj aligned-path segment))
                  [:fallback schema aligned-path]))
              [:fallback schema aligned-path])

            ;; `:map-of` — the `:in` KEY segment is normally dropped (like
            ;; the homogeneous index-bearing ops below) and the walk descends
            ;; into the VALUE schema (child 1), because a `:map-of` key is a
            ;; navigable locator, not a declarable app-db slot. BUT when the
            ;; KEY SCHEMA (child 0) is itself sensitive-or-opaque
            ;; (rf2-6ijdgh) the key IS the secret, and that sensitivity is
            ;; INVISIBLE once we descend into the value: an opaque / nested-
            ;; opaque key contributes NO walker declaration (the pure-data
            ;; walker's `:else` bailout silently skips a compiled `m/schema`
            ;; key), so the `:ok` outcome below yields `leaf-sensitive? =
            ;; false` and `sanitize-sensitive-path` never runs — the secret
            ;; key ships VERBATIM in `:path` / `:reason` while `:value` /
            ;; `:explain` are (correctly, via `schema-has-opaque-child?`)
            ;; redacted: a fail-OPEN leak. Fail-SAFE instead: hand the whole
            ;; `:map-of` node (carrying the consumed prefix) to the fallback
            ;; so `schema-sensitive-at?` detects the sensitive/opaque key
            ;; (`schema-has-sensitive?` / `opaque-nested-tail?` on the
            ;; leftover) → `leaf-sensitive? = true` → the sanitiser runs and
            ;; scrubs the secret key. Mirrors the fail-closed
            ;; sensitive-or-opaque posture used everywhere else in this file.
            ;; (For a VECTOR-form sensitive key the fallback is a no-op
            ;; equivalence — the key already claims the base-path decl so the
            ;; leaf resolves sensitive; forcing fallback keeps the two key
            ;; shapes on one uniform path.)
            (= op :map-of)
            (let [key-schema (nth children 0 nil)]
              (if (or (schema-has-sensitive? key-schema)
                      (schema-has-opaque-child? key-schema))
                [:fallback schema aligned-path]
                (let [child (nth children 1 nil)]
                  (if (some? child)
                    (recur child (subvec remaining-path 1) aligned-path)
                    [:fallback schema aligned-path]))))

            ;; Homogeneous index-bearing container — drop the index
            ;; segment and descend into the element schema.
            ;; `:vector`/`:sequential`/`:set` have one shared element
            ;; schema. The index is not a declarable slot for these, so it
            ;; is dropped to align with the walker's index-free declaration paths.
            (contains? index-bearing-ops op)
            (let [child (nth children 0 nil)]
              (if (some? child)
                (recur child (subvec remaining-path 1) aligned-path)
                [:fallback schema aligned-path]))

            ;; Transparent wrappers contribute NO `:in` segment — descend
            ;; into the (single) inner schema without consuming a segment.
            (#{:maybe} op)
            (if-let [child (first children)]
              (recur child remaining-path aligned-path)
              [:fallback schema aligned-path])

            ;; Any other op (`:and`/`:or`/`:multi`/`:orn`/registry refs/
            ;; opaque values) — we can't reliably resolve the segment;
            ;; redact fail-SAFE iff the leftover subschema OR the consumed
            ;; ancestor prefix declares anything sensitive (rf2-ss06u.2 —
            ;; the consumed ancestor's `:sensitive?` is invisible in the
            ;; leftover, so the prefix MUST ride along).
            :else
            [:fallback schema aligned-path]))))))

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
    - DECLARED `:map` keys, `:vector` / `:sequential` / `:tuple` / `:cat` /
      `:catn` integer indices — navigable scalar locators; KEEP them so
      `:path` stays a useful `get-in` locator for those shapes (the bead's
      regression requirement). A `:map` segment that is NOT a declared child
      is different (rf2-j538f7.13): a `[:map {:closed true} …]` extra-key
      failure reports the CALLER-SUPPLIED extra key itself as the segment —
      user data, possibly a credential — so the key-not-found branch fails
      closed INCLUDING the current segment.
    - Transparent wrappers contribute NO `:in` segment — descend without
      consuming. `:maybe` is genuinely single-child (`[:maybe inner]`) so
      the lockstep walk continues precisely. `:and` / `:or` are MULTI-child
      (rf2-jqx2at): with more than one child the branch that produced the
      failing `:in` cannot be identified from the path alone (an `:or` value
      matched some ONE branch; an `:and` value is constrained by ALL), so
      following only the first child can mis-classify a LATER branch's
      value-bearing segment — e.g. a `:sensitive?` `:map-of` key, or a `:set`
      element — as a navigable locator and ship it VERBATIM in `:path` /
      `:reason` (the earlier draft treated `:and` / `:or` as single-child
      transparent — the leak this closes). They descend ONLY in the
      degenerate single-child case (unambiguous); otherwise, like the
      multi-branch wrappers (`:multi` / `:orn`) and any other / opaque op,
      the branch is ambiguous and the walk drops to the FAIL-CLOSED tail.

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
  (letfn [(fail-closed-tail [sanitized-path remaining-path]
            ;; Cannot resolve the schema further — scrub EVERY remaining
            ;; segment (rf2-612mri). A tail scalar cannot be proven a
            ;; structural locator past an unresolvable op (it may be a
            ;; value-bearing `:set` element), so keeping it would
            ;; under-redact; the resolvable navigable shapes keep their
            ;; locators on their own branches above and never reach here.
            (into sanitized-path
                  (map (constantly path-redacted-sentinel))
                  remaining-path))]
    (loop [schema         schema
           remaining-path (vec in-path)
           sanitized-path []]
      (if (empty? remaining-path)
        sanitized-path
        (if-not (and (vector? schema) (pos? (count schema)))
          (fail-closed-tail sanitized-path remaining-path)
          (let [op       (nth schema 0)
                children (schema-children schema)
                segment  (nth remaining-path 0)]
            (cond
              ;; `:map` — a DECLARED key is a real app-db locator; keep it
              ;; and descend into the named child's tail schema.
              (contains? name-bearing-ops op)
              (if-let [child (some (fn [candidate]
                                     (when (and (vector? candidate)
                                                (>= (count candidate) 2)
                                                (= (nth candidate 0) segment))
                                       candidate))
                                   children)]
                (let [properties? (and (>= (count child) 2)
                                       (map? (nth child 1)))
                      child-schema (if properties?
                                     (when (>= (count child) 3) (nth child 2))
                                     (nth child 1))]
                  (recur child-schema
                         (subvec remaining-path 1)
                         (conj sanitized-path segment)))
                ;; Key not found — the segment is NOT a declared slot, so it
                ;; cannot be proven a structural locator (rf2-j538f7.13). For
                ;; a `[:map {:closed true} …]` extra-key failure Malli reports
                ;; the CALLER-SUPPLIED EXTRA KEY VALUE itself as the `:in`
                ;; segment — arbitrary user data (decoded JSON keys, headers,
                ;; hostile input) that may itself be a credential. Keeping it
                ;; (the earlier `(conj sanitized-path segment)` shape) shipped the secret
                ;; VERBATIM through `:path` / `:reason` despite `:value` /
                ;; `:explain` being redacted. Fail closed INCLUDING the
                ;; current segment; declared keys keep the precise branch
                ;; above (an OPEN map never emits an extra-key error, so only
                ;; undeclared / shape-drift segments land here).
                (fail-closed-tail sanitized-path remaining-path))

              ;; `:set` — the segment is the failing ELEMENT VALUE. ALWAYS
              ;; scrub (unnavigable + value-bearing) and descend into the
              ;; element schema.
              (= op :set)
              (recur (nth children 0 nil)
                     (subvec remaining-path 1)
                     (conj sanitized-path path-redacted-sentinel))

              ;; `:map-of` — the segment is the failing entry's KEY (Malli
              ;; reports the key VALUE verbatim, e.g. `["secret-token-123"
              ;; :age]`). A `:map-of` key is normally a navigable locator and
              ;; is KEPT so `:path` stays a `get-in` locator — BUT when the
              ;; KEY SCHEMA (child 0) is sensitive-or-opaque the key IS the
              ;; secret and must be scrubbed, else it ships verbatim in
              ;; `:path` / `:reason` despite `:value` / `:explain` being
              ;; redacted. Two shapes:
              ;;   - a VECTOR-form `:sensitive?` key (rf2-612mri) —
              ;;     `schema-has-sensitive?` sees it directly; and
              ;;   - a COMPILED / nested-opaque key (rf2-6ijdgh),
              ;;     `(m/schema [:string {:sensitive? true}])` or
              ;;     `[:and (m/schema …)]` — invisible to
              ;;     `schema-has-sensitive?` (the pure-data walker cannot
              ;;     introspect the compiled value), so we ALSO OR in
              ;;     `schema-has-opaque-child?`, the recursive opaque-aware
              ;;     predicate, matching the fail-closed posture the value /
              ;;     explain redaction already takes on an opaque key. Descend
              ;;     into the VALUE schema (child 1) either way.
              (= op :map-of)
              (let [key-schema        (nth children 0 nil)
                    value-schema      (nth children 1 nil)
                    sanitized-segment (if (or (schema-has-sensitive? key-schema)
                                              (schema-has-opaque-child? key-schema))
                                        path-redacted-sentinel
                                        segment)]
                (recur value-schema
                       (subvec remaining-path 1)
                       (conj sanitized-path sanitized-segment)))

              ;; Other index-bearing ops — the segment is a navigable index
              ;; (`:vector` / `:sequential` / `:tuple` / `:cat` / `:catn`);
              ;; keep it and descend into the element schema. The
              ;; per-position ops (`:tuple` / `:cat` / `:catn`, rf2-ss06u.4 /
              ;; rf2-4q681i) index `children[segment]`; `:catn` then strips the
              ;; decorative name (`[name props? schema]`) to reach the
              ;; element schema. The homogeneous ones (`:vector` /
              ;; `:sequential`) share one element schema (child 0).
              (contains? index-bearing-ops op)
              (let [child (cond
                            (#{:tuple :cat} op)
                            (when (and (int? segment)
                                       (< segment (count children)))
                              (nth children segment))

                            (= op :catn)
                            (when (and (int? segment)
                                       (< segment (count children)))
                              (let [entry (nth children segment)]
                                (when (and (vector? entry) (>= (count entry) 2))
                                  (if (map? (nth entry 1))
                                    (when (>= (count entry) 3) (nth entry 2))
                                    (nth entry 1)))))

                            :else
                            (nth children 0 nil))]
                (recur child
                       (subvec remaining-path 1)
                       (conj sanitized-path segment)))

              ;; `:maybe` — genuinely single-child transparent
              ;; (`[:maybe inner]`); descend into the unambiguous inner
              ;; schema without consuming a segment.
              (= op :maybe)
              (if-let [child (first children)]
                (recur child remaining-path sanitized-path)
                (fail-closed-tail sanitized-path remaining-path))

              ;; `:and` / `:or` — MULTI-child wrappers (rf2-jqx2at). With
              ;; more than one child the branch that produced the failing
              ;; `:in` cannot be identified from the path alone (an `:or`
              ;; value matched some ONE branch; an `:and` value is
              ;; constrained by ALL), so following only the first child can
              ;; mis-classify a LATER branch's value-bearing segment — a
              ;; `:sensitive?` `:map-of` key, a `:set` element — as a
              ;; navigable locator and ship it verbatim in `:path` /
              ;; `:reason`. Descend ONLY the degenerate single-child case
              ;; (unambiguous); otherwise fail CLOSED on the remaining tail,
              ;; exactly like `:multi` / `:orn` below.
              (#{:and :or} op)
              (if (= (count children) 1)
                (recur (first children) remaining-path sanitized-path)
                (fail-closed-tail sanitized-path remaining-path))

              ;; Multi-branch / opaque op — the branch is ambiguous, so the
              ;; lockstep walk cannot continue safely. Fail CLOSED.
              :else
              (fail-closed-tail sanitized-path remaining-path))))))))

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
  `(or (schema-has-sensitive? schema) (schema-has-opaque-child? schema))`
  (the failing slot IS the whole registered schema, so any sensitive
  declaration OR any unintrospectable nested child anywhere counts).

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
  sensitive OR is itself opaque / hides a nested opaque child (descendant
  under the unresolved op) OR a sensitive declaration is an ANCESTOR of
  the already-consumed prefix (rf2-ss06u.2 — a `:sensitive?` container
  the alignment already descended through and discarded, e.g. `[:s]`
  sensitive with the leaf `[:s :k]` under a transparent `:and` /
  `:multi` / `:orn` wrapper).

  Per rf2-hi0tf8: even when the path DOES fully align (every segment
  resolves through recognised vector-form ops), the walk can arrive
  exactly AT a nested opaque child — e.g. `[:map [:token (m/schema
  [:string {:sensitive? true}])]]` resolving `[:token]` lands cleanly on
  the compiled leaf. `align-in-path` returns that arrival schema so this
  check can fail closed on it too (`schema-has-opaque-child?`), the same
  way a top-level opaque schema already fails closed via `schema-opaque?`
  — a nested opaque leaf's invisible `:sensitive?` flag is exactly as
  dangerous as a root one's.

  Returns boolean. Pure; same `(schema, in-path)` always produces the
  same output."
  [schema in-path]
  (if (or (nil? in-path) (empty? in-path))
    (or (schema-has-sensitive? schema) (schema-has-opaque-child? schema))
    (let [[outcome :as alignment] (align-in-path schema in-path)]
      (if (= outcome :fallback)
        ;; Couldn't fully resolve the path. Redact fail-SAFE iff EITHER:
        ;;   - the leftover subschema carries any sensitive declaration
        ;;     (a descendant under the unresolved op), OR
        ;;   - the leftover subschema is itself opaque or hides a nested
        ;;     opaque child (rf2-hi0tf8 — the unresolved op MAY be an
        ;;     opaque compiled value directly, or a resolvable wrapper
        ;;     that embeds one further down), OR
        ;;   - a sensitive declaration is an ancestor of (or equal to) the
        ;;     prefix align-in-path already consumed (rf2-ss06u.2 — the
        ;;     consumed-ancestor `:sensitive?` is invisible in the leftover
        ;;     subtree; without this the failing value under a sensitive
        ;;     ancestor wrapped by :and/:multi/:orn LEAKS verbatim).
        ;; The ancestor check is `prefix? declaration-path aligned-prefix` only —
        ;; a sensitive SIBLING outside the consumed prefix must NOT taint
        ;; the failing slot (preserves the precise-narrowing win). Here
        ;; `aligned-prefix` is the third value in the `:fallback` shape.
        ;;
        ;; `opaque-nested-tail?`, not `schema-has-opaque-child?`:
        ;; `remaining-schema` was reached by DESCENDING from the root (exactly the
        ;; nested position `schema-has-opaque-child?`'s docstring
        ;; describes), so a bare fn/symbol found here is provably
        ;; flag-free the same way a nested `pos-int?` tail is — it is
        ;; NOT the whole-registration root case `schema-opaque?` fails
        ;; closed on.
        (let [[_ remaining-schema aligned-prefix] alignment]
          (or (schema-has-sensitive? remaining-schema)
              (opaque-nested-tail? remaining-schema)
              (let [declarations (extract-sensitive-paths-from-schema schema [])]
                (boolean
                  (some (fn [declaration-path]
                          (prefix? declaration-path aligned-prefix))
                        (keys declarations))))))
        ;; Fully aligned (`:ok`). `aligned-path` is the declaration path;
        ;; `leaf-schema` is the schema the walk arrived at (the
        ;; `:ok` shape). Per rf2-hi0tf8 that leaf may itself be a nested
        ;; opaque child the path resolution walked straight into — check
        ;; it alongside the existing ancestor/descendant sensitive-decl
        ;; scan. `opaque-nested-tail?` (not `schema-has-opaque-child?`) for
        ;; the same reason as the `:fallback` branch above — `leaf-schema` was
        ;; reached by descent, so a bare fn/symbol there is flag-free.
        (let [[_ aligned-path leaf-schema] alignment
              declarations (extract-sensitive-paths-from-schema schema [])]
          (or (opaque-nested-tail? leaf-schema)
              (boolean
                (some (fn [declaration-path]
                        (or (prefix? declaration-path aligned-path)   ;; ancestor sensitive
                            (prefix? aligned-path declaration-path))) ;; descendant sensitive
                      (keys declarations)))))))))
