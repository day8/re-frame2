(ns re-frame.schemas.walker
  "Per-slot flag walker for Malli EDN schemas (rf2-nwv63 / rf2-kj51z / rf2-oghml).

  Per Spec 009 §Size elision in traces and Spec 010 §`:sensitive?`, the
  schema-driven nomination path: any Malli slot carrying a per-slot flag
  (`:large? true` or `:sensitive? true`) in its per-slot properties
  (per Spec-Schemas §`:rf/app-schema-meta` — the per-slot metadata
  vocabulary) is registered into the frame's
  `[:rf/runtime :elision :declarations]` (`:large?`) or
  `[:rf/runtime :elision :sensitive-declarations]` (`:sensitive?`) slot
  with `:source :schema`. Both registries are sibling slots under the
  shared `[:rf/runtime :elision]` sub-container; flags compose
  orthogonally — a slot may carry either or both, and the validation
  walker resolves the conflict at emit time. Storing them separately
  keeps the per-flag query
  (`(get-in db [:rf/runtime :elision :sensitive-declarations <path>])`)
  O(1) without value-shape inspection.

  This file owns the **walker** that maps a registered schema's EDN form
  to a `{path declaration}` map; the actual app-db write lives in
  `re-frame.elision` (rf2-v9tw2) so that file owns the unified registry
  surface for schema-owned declarations. The seam between the two is
  the per-flag late-bind hook registered by the outer façade.

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

  ## Discoverability caveat — non-vector forms (rf2-yaioz)

  A user that registers a schema via a compiled `m/schema` value or a
  registry reference and adds per-slot `:sensitive?` / `:large?` flags
  inside that opaque value will see the walker **silently skip** them.
  No warning fires; the validation-failure trace will not redact the
  sensitive slot.

  Two workable shapes when per-slot flags need to apply:

    1. **Register the vector form, not the compiled one.** Pass the
       raw EDN `[op props? children...]` to `reg-app-schema` — the
       walker can introspect it.

    2. **Use registration-level metadata for coarse honour.** A
       handler's `:sensitive?` registration meta still applies to the
       whole event-handler-shipped value (per Spec 010 §`:sensitive?`)
       even when the walker cannot reach per-slot flags. This is the
       fallback when the schema MUST stay opaque (third-party
       compiled schemas, registry refs that require lazy resolution).

  Example — vector form vs registry ref:

    ;; Vector form — walker sees :sensitive? per-slot.
    (rf/reg-app-schema [:user]
      [:map
       [:id    :int]
       [:token {:sensitive? true} :string]])

    ;; Registry ref — walker treats the schema as an opaque leaf;
    ;; the per-slot :sensitive? inside `:my/user-schema` is invisible.
    ;; Coarse honour via `:sensitive?` on the consuming reg-event-* is
    ;; still available.
    (rf/reg-app-schema [:user] :my/user-schema)

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
  `:source` slot records schema provenance for the unified registry."
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
  introspection (`(get-in db [:rf/runtime :elision :declarations <path>])`)
  reports schema provenance for wire-boundary elision."
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
            the per-step `validate-event!` / `validate-cofx!` /
            `validate-sub!` helpers) to decide whether the failing slot's
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
;; its `{path declaration}` map at INDEX-FREE base-paths — positional /
;; keyed containers descend at the same base-path (walker comment lines
;; ~144-147) because the element index is not a declarable app-db slot.
;; Aligning the two coordinate systems means dropping the collection-
;; navigation segment that these ops contribute. `:map-of` descends into
;; its VALUE schema (child index 1); the positional ops descend into
;; their element/Nth-element schema. Per rf2-g5auo — without this
;; alignment a `:sensitive?` slot nested in a collection leaks verbatim.
(def ^:private index-bearing-ops
  #{:vector :sequential :set :tuple :map-of})

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

            ;; Index-bearing container — drop the index/key segment and
            ;; descend into the element (or `:map-of` value) schema.
            (contains? index-bearing-ops op)
            (let [child (if (= op :tuple)
                          (when (and (int? seg) (< seg (count children)))
                            (nth children seg))
                          ;; :vector/:sequential/:set have one element
                          ;; schema; :map-of's value schema is child 1.
                          (nth children (if (= op :map-of) 1 0) nil))]
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

(defn- scalar-locator?
  "True when `x` is a scalar that can serve as a `get-in` path segment — a
  keyword / string / number / symbol / boolean. A composite (map / set /
  vector / seq) can NEVER be a navigable locator AND is exactly the shape
  Malli reports as a `:set`-element value — so a non-scalar segment in a
  failing `:in` path is always value-bearing, never a locator."
  [x]
  (or (keyword? x) (string? x) (number? x) (symbol? x) (boolean? x)))

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
    - `:map` keys, `:vector` / `:sequential` / `:tuple` integer indices,
      `:map-of` keys — navigable scalar locators; KEEP them so `:path`
      stays a useful `get-in` locator for those shapes (the bead's
      regression requirement).
    - Transparent wrappers (`:maybe` / `:and` / `:or` / `:multi` / `:orn`)
      contribute NO `:in` segment — descend without consuming. For the
      single-child wrappers (`:maybe` / `:and` / `:or`) the inner schema is
      unambiguous so the lockstep walk continues precisely. For the
      multi-branch wrappers (`:multi` / `:orn`) and any other / opaque op
      the branch is ambiguous, so the walk drops to a FAIL-CLOSED tail.

  FAIL-CLOSED tail (rf2-ss06u.1 / rf2-ss06u.2): once the lockstep walk
  cannot confidently continue (a multi-branch / opaque op, or the schema
  bottoms out before the path does), every remaining segment is scrubbed
  UNLESS it is a scalar locator (`scalar-locator?`). A non-scalar segment
  past an unresolvable point can only be a value-bearing collection (a
  `:set` element map/set/vector, the exact leak shape) — never a locator —
  so scrubbing it can never lose navigability, and keeping it would
  under-redact. Per the bead: over-redaction on these wrapper shapes is
  acceptable; the value-protection direction must never under-redact. This
  closes the deep nesting the adversarial generator surfaced
  (`{:a #{{:auth #{{:secret …}}}}}` under an `:orn`) where the prior
  pass-through-verbatim fallback leaked the set-element maps into `:path`.

  Pure; same `(schema, in-path)` always produces the same output.
  Returns a vector."
  [schema in-path]
  (letfn [(fail-closed-tail [out in]
            ;; Cannot resolve the schema further — scrub every remaining
            ;; non-scalar segment; keep scalar locators.
            (into out (map (fn [seg]
                             (if (scalar-locator? seg) seg path-redacted-sentinel)))
                  in))]
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

              ;; Other index-bearing ops — the segment is a navigable index
              ;; (`:vector` / `:sequential` / `:tuple`) or key (`:map-of`);
              ;; keep it and descend into the element / value schema.
              (contains? index-bearing-ops op)
              (let [child (if (= op :tuple)
                            (when (and (int? seg) (< seg (count children)))
                              (nth children seg))
                            (nth children (if (= op :map-of) 1 0) nil))]
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
