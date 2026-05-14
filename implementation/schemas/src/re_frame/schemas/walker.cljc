(ns re-frame.schemas.walker
  "Per-slot flag walker for Malli EDN schemas (rf2-nwv63 / rf2-kj51z / rf2-oghml).

  Per Spec 009 §Size elision in traces and Spec 010 §`:sensitive?`, the
  schema-driven nomination path: any Malli slot carrying a per-slot flag
  (`:large? true` or `:sensitive? true`) in its per-slot properties
  (per Spec-Schemas §`:rf/app-schema-meta` — the per-slot metadata
  vocabulary) is registered into the frame's
  `[:rf/elision :declarations]` (`:large?`) or
  `[:rf/elision :sensitive-declarations]` (`:sensitive?`) slot with
  `:source :schema`. Both registries are sibling slots under the shared
  `[:rf/elision]` reserved root; flags compose orthogonally — a slot may
  carry either or both, and the validation walker resolves the conflict
  at emit time. Storing them separately keeps the per-flag query
  (`(get-in db [:rf/elision :sensitive-declarations <path>])`) O(1)
  without value-shape inspection.

  This file owns the **walker** that maps a registered schema's EDN form
  to a `{path declaration}` map; the actual app-db write lives in
  `re-frame.elision` (rf2-v9tw2) so that file owns the unified registry
  surface across the schema / declared / runtime-flagged sources. The
  seam between the two is the per-flag late-bind hook registered by the
  outer façade.

  The walker is **pure data** — it doesn't import malli.core. Malli EDN
  forms are vectors of the shape `[op props? children...]`; we pattern-
  match on shape. Per Spec 010 §The `:spec` value is opaque to re-frame,
  the framework MUST NOT call into the registered validator to introspect
  schema structure — we walk the EDN ourselves. This means the walker
  handles the **vector form** (`[:map [:k :string]]`) — the form
  `(rf/reg-app-schema ...)` users write. Non-vector Malli forms (schema
  objects, registry refs) are treated as opaque leaves; their internal
  per-slot declarations are invisible to the walker (the same caveat
  applies to Malli's own schema-walking when introspection goes through
  `m/schema` ↔ raw EDN — round-tripping a registry ref loses the slot
  metadata).

  The walker is parameterised on the per-slot flag key (`:large?` /
  `:sensitive?`); both flags share identical structural recognition
  (`:map` name-bearing, `:multi`/`:orn`/`:catn`/`:altn` dispatch-bearing,
  positional combinators descend at the same base-path) so a single
  traversal serves every per-slot annotation under
  `:rf/app-schema-meta`. Future per-slot annotations (Spec-Schemas
  reserves additional slots) compose as one-line registrations.")

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
  check at the `validate-app-db!` emit-site when a leaf path is
  extractable from the explainer output.

  When `in-path` is nil or empty the check is equivalent to
  `schema-has-sensitive?` (the failing slot IS the whole registered
  schema, so any sensitive declaration anywhere counts).

  Returns boolean. Pure; same `(schema, in-path)` always produces the
  same output."
  [schema in-path]
  (if (or (nil? in-path) (empty? in-path))
    (schema-has-sensitive? schema)
    (let [decls (extract-sensitive-paths-from-schema schema [])
          in-v  (vec in-path)]
      (boolean
        (some (fn [decl-path]
                (or (prefix? decl-path in-v)   ;; ancestor sensitive
                    (prefix? in-v decl-path))) ;; descendant sensitive
              (keys decls))))))
