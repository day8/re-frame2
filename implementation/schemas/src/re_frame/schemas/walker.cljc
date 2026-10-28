(ns re-frame.schemas.walker
  "Pure-data extractor for per-slot flags in Malli EDN schemas.

  `:large?` and `:sensitive?` slot props are projected to
  `{path declaration}` maps with `:source :schema`. Owner-local consumers use
  these declarations for validation-failure and transient egress projection.
  Schemas do not populate durable app-db classification; `reg-classification`
  owns that policy.

  The walker does not depend on Malli and does not ask the registered
  validator to interpret a schema. It recognizes vector forms shaped as
  `[op props? children...]`. Compiled schema values and registry references
  are opaque, so their internal flags cannot be extracted. Registration warns
  once for compiled or nested opaque values. Keyword registry references stay
  silent because the framework cannot distinguish them from primitive keyword
  schemas without violating the opaque-schema boundary.

  A LOCAL `{:registry ...}` on a schema's own props is the one registry shape
  the walk CAN see and therefore must fail closed on: the props map
  names the referenced shapes outright, so — unlike a bare keyword reference —
  there is no ambiguity with a primitive to trade against. Its values are still
  not walked; the form classifies opaque and registration nudges once.

  Register vector-form EDN when per-slot flags must be visible. Compiled,
  nested opaque and local-registry schemas fail closed in validation redaction;
  keyword registry references are flag-invisible, so their privacy
  declarations need the vector form.
  The traversal is parameterized by flag key so both supported flags share the
  same operator and path semantics.")

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
  declaration exactly. The elision side needs nothing position-specific —
  the index fork is schema-agnostic (it walks the runtime value), so
  `:cat`/`:catn`/`:tuple` all align through the same generic path.

  `:tuple` is always positional. A `:cat` / `:catn` is positional only when
  `fixed-width-sequence?` holds and it is not itself spliced into an
  enclosing regex op; otherwise its elements descend index-free."
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

(def ^:private regex-ops
  "Malli's sequence (regex) operators. As an element of an enclosing sequence
  one of these is SPLICED into it and consumes zero, one or many input values,
  so its input position cannot be read off its schema position."
  #{:cat :catn :alt :altn :? :* :+ :repeat})

(defn- sequence-element-schema
  "The element schema of a `:tuple` / `:cat` / `:catn` child: the bare child,
  or a `:catn` entry's `[name props? schema]` schema part (nil if malformed)."
  [op child]
  (if (= op :catn)
    (when (and (vector? child) (>= (count child) 2))
      (if (map? (nth child 1))
        (nth child 2 nil)
        (nth child 1)))
    child))

(defn- fixed-width-sequence?
  "True when input index `i` provably addresses element `i`: always for a
  `:tuple`, and for a `:cat` / `:catn` only when every element consumes
  exactly one value — a keyword, symbol, fn, or non-regex vector form. A
  regex, malformed or compiled element makes the width unknowable from the
  pure data, so the node falls back to a whole-node decision."
  [op children]
  (or (= op :tuple)
      (every? (fn [child]
                (let [element (sequence-element-schema op child)]
                  (or (keyword? element) (symbol? element) (fn? element)
                      (and (vector? element)
                           (pos? (count element))
                           (not (contains? regex-ops (nth element 0)))))))
              children)))

(defn- declaration-from-properties
  "Build a declaration map `{flag-key true :source :schema}` (plus
  optional `:hint` propagated verbatim) from a slot's per-slot props,
  or nil when `flag-key` is not set to `true`. Per Spec 009 §Size
  elision in traces and Spec 010 §`:sensitive?` — `:hint` is optional
  and omitted when absent so the marker shape stays minimal. The
  `:source :schema` slot records schema provenance for the owner-local
  consumer that reads the extracted map — the validation-failure-trace
  redactor (the ONLY consumer of a machine `[:schemas :data]` or a resource's
  `:params-schema` props), HTTP body-privacy (over the request's
  `:decode`), story-mcp — never the durable app-db classification registry,
  and never durable machine `:data` classification, which rides top-level
  `:sensitive` / `:large` on the `reg-machine` spec instead (Spec 015). A
  resource's OPTIONAL `:data-schema` is not on this list at all: it has no
  runtime validation consumer, only static reflection (Spec 016)."
  [flag-key props]
  (when (true? (get props flag-key))
    (cond-> {flag-key true
             :source  :schema}
      (some? (:hint props)) (assoc :hint (:hint props)))))

(declare ^:private walk-flags)

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

      That precision needs a FIXED-WIDTH sequence (`fixed-width-sequence?`)
      that is not itself spliced into an enclosing regex op. A `:cat` /
      `:catn` with a regex element (`[:cat [:* :int] Payload]`) cannot map an
      input index to an element, so it descends like `:sequential` — every
      element at the SAME `base-path`, `:catn` entry flags claiming
      `base-path`.

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
  [flag-key schema base-path acc]
  (walk-flags flag-key schema base-path acc false))

(defn- walk-flags
  "The recursion behind `walk-flagged-schema`. `spliced?` is true when
  `schema` is an element of a regex op, where a `:cat` / `:catn` is spliced
  into the enclosing sequence and owns no input positions."
  ([flag-key schema base-path acc spliced?]
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
           children (schema-children schema)
           ;; A regex op's elements are spliced into its own sequence.
           splices? (contains? regex-ops op)
           positional? (and (contains? position-bearing-ops op)
                            (not (and spliced? splices?))
                            (fixed-width-sequence? op children))]
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
                   (walk-flags flag-key child-schema slot-path acc false)
                   acc))))
           acc'
           children)

         ;; A non-positional `:catn` is entry-shaped exactly like a
         ;; dispatch-bearing op: entry flags and element schemas both claim
         ;; the base-path.
         (or (contains? dispatch-bearing-ops op)
             (and (= op :catn) (not positional?)))
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
                   (walk-flags flag-key child-schema base-path acc splices?)
                   acc))))
           acc'
           children)

         ;; `:tuple` / `:cat` / `:catn` — position-bearing. Each element has
         ;; its OWN schema; element `i` descends at `(conj base-path i)` so a
         ;; per-position `:sensitive?` / `:large?` flag claims that exact
         ;; index, NOT the shared base-path. Mirrors the `:map` name-bearing
         ;; descent with integer position keys, giving the sibling precision
         ;; the index-free `:else` descent destroys.
         ;;
         ;; `:tuple` / `:cat` elements are bare schemas (`children[i]`);
         ;; `:catn` elements are NAME-bearing entries (`[name props? schema]`)
         ;; — the flag may live in the entry's own props OR the element
         ;; schema's props, both claiming `(conj base i)`. Either shape
         ;; descends the element schema at the position-pinned path. A
         ;; variable-width or spliced `:cat` is not `positional?` and takes
         ;; the index-free `:else` descent instead.
         positional?
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
                    (walk-flags flag-key element-schema slot-path acc splices?)
                    acc)
                  (inc i)]))
             [acc' 0]
             children))

         :else
         (reduce (fn [acc child]
                   (walk-flags flag-key child base-path acc splices?))
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
  owner-local consumer that reads this map can report schema provenance for
  its wire-boundary elision — the validation-failure-trace redactor (the ONLY
  consumer of a machine `[:schemas :data]` or a resource's
  `:params-schema` props), HTTP body-privacy (over the request's `:decode`),
  story-mcp. A resource's OPTIONAL `:data-schema` has no runtime validation
  consumer and is not one of them (Spec 016). This map does not feed
  durable app-db classification, nor durable machine `:data` classification —
  a machine declares that projection-relative at the top level of its
  `reg-machine` spec (Spec 015)."
  [schema base-path]
  (walk-flagged-schema :large? schema base-path {}))

(defn extract-sensitive-paths-from-schema
  "Walk a Malli schema form at `base-path` and return a
  `{path {:sensitive? true ...}}` map for every `:sensitive? true` slot
  found. Per Spec 010 §`:sensitive?` — privacy in schema-validation error
  traces.

  Used by the validation emit-sites (`validate-app-schema!` and the
  per-step `validate-event!` / `validate-fx!` / `validate-sub!` helpers,
  plus the EP-0017 recordable-cofx `:rf.error/cofx-value-invalid` path via
  `redact-validation-tags`) to decide whether the failing slot's value MUST
  be redacted before the trace event ships, and published to other
  artefacts as the `:schemas/extract-sensitive-paths-from-schema` late-bind
  hook. It walks afresh on every call and retains nothing, so a schema
  built per call (a managed-HTTP request's `:decode`) or replaced by a
  re-registration is not kept alive here."
  [schema base-path]
  (walk-flagged-schema :sensitive? schema base-path {}))

(defn schema-has-sensitive?
  "True when the registered schema declares ANY slot sensitive —
  either the schema's container-level props carry `:sensitive? true`,
  or any nested `:sensitive? true` slot lives anywhere inside the
  schema. Per Spec 010 §`:sensitive?` — privacy in schema-validation
  error traces.

  Validation-failure redaction decides on this schema-wide predicate, ORed
  with `schema-has-opaque-child?`, for every slot it governs: a whole-payload
  slot carries any conforming sensitive sibling, and the app-db surface's
  narrowed `:value` takes the same rule.

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
;; members, `[:> 10]` a comparator bound, `[:re "x"]` a pattern — and the
;; scalar primitives (`:int`, `:string`, …) carry no child schema at all.
;; Recursing into those data operands would be wrong: an ordinary literal
;; (`42`, `"x"`) would reach the opaque `:else` and false-flag the whole
;; schema as carrying an opaque child. So the walk
;; projects the true child schemas per operator instead of treating every tail
;; element as a child. Classification is structural (no Malli/validator
;; introspection) and covers the shipped Malli 0.20.1 default registry.
;;
;; `[:ref ::k]` is the one data tail that does NOT make its form walkable.
;; Its tail is data, so the walk cannot descend it — but the
;; shape it names holds the per-slot flags, so a clean walk here would report
;; flag-free on a schema whose flags simply live elsewhere. It fails closed,
;; on the opaque side, alongside a local `:registry`.

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
;; a regex pattern, a predicate fn) or which carry no child schema at all
;; (scalar primitives). The opacity walk MUST NOT descend into these — their
;; tails are operands, not child schemas.
;;
;; `:ref` is deliberately NOT a member. Its tail is data too, but
;; being un-descendable is not the same as being flag-free: the referenced
;; shape carries the per-slot flags and lives where the walk cannot reach it.
;; It gets its own fail-closed arm in `opacity-child-schemas` instead.
(def ^:private opacity-literal-ops
  #{:= :not= :enum :< :<= :> :>= :re :fn
    :any :boolean :double :float :int :keyword :nil
    :qualified-keyword :qualified-symbol :some :string :symbol :uuid})

(defn schema-local-registry?
  "True when a vector-form schema's own props carry a Malli LOCAL `:registry`
  (`[:schema {:registry {::user [...]}} ::user]`, or the same props map on any
  other op). Malli resolves the form's keyword references against that map, so
  the referenced shapes — and every per-slot `:sensitive?` / `:large?` flag
  inside them — live in a position the pure-data walk never descends: the props
  map is metadata to the walk, and the child is a bare keyword the walker
  deliberately treats as a flag-free primitive (see `schema-opaque?`).

  Registry VALUES are deliberately NOT walked. Walking them would
  mean resolving references, which is schema interpretation — the job Spec 010
  §The `:schema` value is opaque to re-frame reserves for the registered
  validator. Fail-closed over-redaction is the documented direction: the whole
  failure redacts and registration nudges once toward the walkable shape.

  The explicit `[:ref …]` form fails closed for the same reason and by the
  same route — a second spelling of the same unresolvable
  reference, classified by its own arm in `opacity-child-schemas`.

  Returns boolean. Pure."
  [schema]
  (contains? (schema-properties schema) :registry))

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
  known literal / scalar op (its tail is data), or `::opaque` for a schema
  carrying a local `:registry`, for an explicit `[:ref …]` reference form,
  or for an unclassified op — in every case the walk cannot
  prove the schema's flags are reachable, so it fails closed."
  [schema]
  (let [op (nth schema 0)]
    (cond
      ;; A local `:registry` puts the referenced shapes (and their
      ;; per-slot flags) behind keyword references the walk resolves nowhere.
      ;; Checked BEFORE the op dispatch: the props map is op-independent, and
      ;; the ops it lands on are the walkable ones (`:schema` / `:map` / …)
      ;; whose classification would otherwise report a clean, flag-free walk.
      (schema-local-registry? schema)     ::opaque
      ;; An explicit `[:ref ::k]` is a reference whose target the
      ;; pure-data walk resolves nowhere, so a `{:sensitive? true}` slot on the
      ;; referenced shape is honoured by Malli and invisible here. Fail closed,
      ;; like a local `:registry`. The BARE keyword (`::k`) stays walkable: a
      ;; registry reference cannot be told from a primitive (`:string`), while
      ;; `[:ref …]` can.
      (= :ref op)                        ::opaque
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
  `:enum` members, comparator bounds, `:re` pattern) are DATA, not child
  schemas, so the projection never descends into them. An explicit `[:ref …]`
  is data-tailed too, but classifies `::opaque` rather than walkable:
  the shape it names carries the per-slot flags, and the walk
  resolves it nowhere. Only ever called on a descended value, never on the
  caller's original root argument —
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

  The recursion is OPERATOR-AWARE: it descends only the true
  child-schema positions of each Malli operator. Literal / config operands
  (`:=` value, `:enum` members, `:re` pattern, comparator bounds, scalar
  primitives) are DATA, not child schemas, so they are NOT recursed into —
  an ordinary `[:= 42]` / `[:enum 1 2]` is fully walkable and
  NOT opaque. An explicit `[:ref …]` is the exception: its tail is
  data as well, but the shape it references holds the per-slot flags and the
  pure-data walk resolves it nowhere, so it fails CLOSED like a local
  `:registry`. An actual compiled value in a real schema position (a `:map`
  slot's tail, a container element, a `:map-of` key/value, a `:cat`/`:tuple`
  element, a `:multi`/`:orn` branch, …) still fails closed, as does a genuinely
  unknown operator shape, as does a local `:registry` at any depth
  (`schema-local-registry?`).

  Root functions and symbols are opaque and fail closed (via `schema-opaque?`).
  The same values used as nested schema tails are considered flag-free because
  their enclosing entry is the only place slot props can occur, and the walker
  inspects that entry before its tail.

  Returns boolean. Pure."
  [schema]
  (or (schema-opaque? schema)
      (and (vector? schema) (pos? (count schema))
           (opaque-nested-tail? schema))))

;; ---- qualified-keyword registry references --------------------------------
;;
;; A QUALIFIED keyword in a schema position is recognisably a registry
;; reference: Malli's default registry holds exactly one qualified key,
;; `:malli.core/schema`, and that one cannot stand as a bare child. Whether the
;; schema it names carries a `:sensitive?` / `:large?` mark is unknowable
;; without a registry consult, which Spec 010 reserves for the registered
;; validator, and a reference can just as well name a flag-free scalar. So the
;; opacity walk keeps it walkable, and this separate predicate answers the
;; narrower question an egress that must fail closed asks: does the form name
;; any registry schema by qualified keyword at all?

(defn- implicit-entry-ref?
  "True when `entry`, a child of a `:map`, names a registry schema by its KEY
  alone. Malli reads a tail-less entry — `:app/token`, `[:app/token]` or
  `[:app/token {:optional true}]` — as a reference to the registry schema of
  that name; an entry with an explicit child schema (`[:user/id :int]`) uses
  its key as data."
  [entry]
  (cond
    (keyword? entry) (qualified-keyword? entry)
    (vector? entry)  (and (qualified-keyword? (nth entry 0 nil))
                          (case (count entry)
                            1 true
                            2 (map? (nth entry 1))
                            false))
    :else            false))

(defn schema-has-qualified-ref?
  "True when `schema` names a registry schema by QUALIFIED keyword: at the
  root, at any real child-schema position of a vector form, or as a `:map`
  entry with no explicit child schema (Malli's implicit reference,
  `[:map :app/token]` / `[:map [:app/token {:optional true}]]`).

  The descent reuses the opacity walk's operator-aware projection, so map
  keys, `:enum` / `:=` operands, comparator bounds and `:multi` / `:orn`
  dispatch heads are data and never inspected — `[:map [:user/id :int]]` and
  `[:enum :status/a]` answer false. An UNQUALIFIED keyword answers false too:
  it cannot be told from a primitive. A form the opacity walk classifies
  opaque (`[:ref …]`, a local `:registry`, an unclassified op, a compiled
  value) answers false here, because `schema-has-opaque-child?` already owns
  that answer.

  The opacity walk does NOT consult this: validation surfaces keep a qualified
  reference walkable. The off-box HTTP export does, and fails closed on it.
  No registry consult, no Malli require, no namespace exemption.

  Returns boolean. Pure."
  [schema]
  (cond
    (qualified-keyword? schema) true
    (and (vector? schema) (pos? (count schema)))
    (let [children (opacity-child-schemas schema)]
      (and (not= ::opaque children)
           (boolean
             (or (and (contains? name-bearing-ops (nth schema 0))
                      (some implicit-entry-ref? (schema-children schema)))
                 (some schema-has-qualified-ref? children)))))
    :else false))

;; ---- marks below an unanchored `:map-of` ----------------------------------
;;
;; The walk writes a mark inside a `:map-of` value without the key it sits
;; under (`[:map-of :string [:map [:token {:sensitive? true} :string]]]` marks
;; `[:token]`), and a consumer matching the marks index-free lets a path ride a
;; map key only once the path's first named segment has matched
;; (`re-frame.classification/redact-with-paths`). Below a named slot or a
;; pinned position that holds; below a `:map-of` the walk reaches while its
;; base path is still empty, no extracted path reaches the mark. A mark the
;; walk writes AT that base path (`[]`) claims the whole value and is reached.

(defn- unanchored-map-of-mark?
  "The recursion behind `schema-has-unanchored-map-of-mark?`: descends only the
  positions `walk-flags` descends at an unchanged, empty base path. `spliced?`
  is as there."
  [schema spliced?]
  (if-not (and (vector? schema) (pos? (count schema)))
    false
    (let [op       (nth schema 0)
          children (schema-children schema)
          splices? (contains? regex-ops op)]
      (cond
        (= :map-of op)
        (boolean (some (fn [flag-key]
                         (some seq (keys (walk-flagged-schema flag-key schema [] {}))))
                       [:sensitive? :large?]))

        ;; A named slot or a pinned position anchors every mark below it.
        (or (contains? name-bearing-ops op)
            (and (contains? position-bearing-ops op)
                 (not (and spliced? splices?))
                 (fixed-width-sequence? op children)))
        false

        (or (contains? dispatch-bearing-ops op) (= :catn op))
        (boolean (some #(unanchored-map-of-mark? (entry-child-schema %) splices?) children))

        :else
        (boolean (some #(unanchored-map-of-mark? % splices?) children))))))

(defn schema-has-unanchored-map-of-mark?
  "True when `schema` carries a `:sensitive?` / `:large?` mark below a
  `:map-of` the walk reaches before any named slot: at the root, or behind a
  `:vector`, another `:map-of`, a transparent wrapper or a dispatch branch. The
  extracted path omits the key the mark sits under, and an index-free match
  rides a map key only after the path's first named segment, so no extracted
  path reaches that mark. A `:map` slot or a pinned `:tuple` / `:cat` position
  above the `:map-of` anchors it, and a mark the walk writes at the root path
  claims the whole value — both answer false.

  The opacity walk does not consult this: validation surfaces decide
  schema-wide, so every such mark counts there. The consumers that
  match the extracted marks index-free — the off-box HTTP body stamp and the
  invalid-params projection — read it through the
  `:schemas/schema-has-opaque-child?` hook and fail closed on it.

  Returns boolean. Pure."
  [schema]
  (unanchored-map-of-mark? schema false))

(defn declared-key-prefix
  "The longest prefix of `in-path` whose every segment is a key `schema`
  declares on a `:map`, looking through `:maybe`. `in-path` is Malli's `:in`
  for a failure against `schema`.

  A declared key is the developer's own vocabulary, so it locates a failure
  without carrying the failing data. The walk stops at the first segment that
  is not one: a `:set` element, a `:map-of` key and a closed map's extra key
  can be the caller's data, and an index or a branch tail sits where the
  grammar cannot name what follows. A sensitive app-db failure's `:path`
  keeps this prefix and redacts the rest (Spec 010 §`:sensitive?`).

  Returns a vector. Pure."
  [schema in-path]
  (loop [schema schema
         path   (seq in-path)
         prefix []]
    (if-not (and path (vector? schema) (pos? (count schema)))
      prefix
      (let [op (nth schema 0)]
        (cond
          (= :maybe op)
          (recur (first (schema-children schema)) path prefix)

          (contains? name-bearing-ops op)
          (let [segment (first path)
                entry   (some (fn [child]
                                (when (= segment (if (vector? child) (nth child 0 nil) child))
                                  child))
                              (schema-children schema))]
            (if (some? entry)
              (recur (entry-child-schema entry) (next path) (conj prefix segment))
              prefix))

          :else
          prefix)))))
