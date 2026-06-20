(ns re-frame.story.malli-schema
  "Pure Malli-schema introspection helpers. Shared between the
  controls panel's widget-derivation (`re-frame.story.ui.controls`,
  CLJS-only) and the schema-validation panel's args-violation walk
  (`re-frame.story.ui.schema-validation`, `.cljc`).

  This is the single shared home for the introspection helpers both
  consumers use, so the two panels decode schemas through one
  implementation. The shape is canonical Malli vector-schema decoding:
  optional properties map at index 1, then ordered child schemas. Lives
  at the leaf of the require graph (depends on `clojure.core` only) so
  any sibling namespace can `:require` it without cycle risk.")

(defn properties?
  "Is `x` the optional Malli properties map at index 1 of a vector
  schema? Per Malli convention any map at that slot is properties;
  vector entries (child schemas for collections) are never maps."
  [x]
  (map? x))

(defn schema-op
  "Return the operator symbol of a vector schema (`:map`, `:vector`,
  `:tuple`, `:set`, `:enum`, ...) or nil if `s` is not a vector."
  [s]
  (when (vector? s) (first s)))

(defn schema-properties
  "Return the optional properties map from a vector schema, or nil."
  [s]
  (when (vector? s)
    (let [x (second s)]
      (when (properties? x) x))))

(defn schema-children
  "Return the child schemas of a vector schema, skipping the optional
  properties map. For `[:map [:a :string] [:b :int]]` returns
  `([:a :string] [:b :int])`."
  [s]
  (when (vector? s)
    (let [r (rest s)]
      (if (properties? (first r))
        (rest r)
        r))))

(defn map-entry-key
  "Map-entry tuples in Malli have shape `[k props? child]`. Return k."
  [entry]
  (first entry))

(defn map-entry-schema
  "Map-entry tuples in Malli have shape `[k props? child]`. Return
  child. Skip the optional properties map at index 1."
  [entry]
  (let [r (rest entry)]
    (if (properties? (first r))
      (second r)
      (first r))))

;; ---- view-args (props) schema key resolution -----------------------------
;;
;; The ONE canonical first-match order for a view's explicit-input (props)
;; schema, shared by the plan compiler (`re-frame.story.plan`, which writes
;; the resolved schema to `[:world :view-args-schema]`) and the
;; compiled-plan resolver (`re-frame.story.view-args`). Lives at this leaf
;; (alongside the Malli introspection helpers, no plan dependency) so both
;; consumers agree on the slots WITHOUT a require cycle (view-args requires
;; plan; plan must not require view-args).
;;
;; First match wins over `[:rf/props :schema]`: `:rf/props` is canonical
;; and wins over `:schema` (no composition). `:spec` is NOT a key here —
;; the framework reads `:schema` only on `reg-*` metadata; see
;; migration/from-re-frame-v1/README.md §M-54.

(def view-args-schema-keys
  "View-metadata keys carrying the explicit-input (props) schema, in
  resolution order (first present wins): `:rf/props` (canonical) then
  `:schema` (the alternative location). `:spec` is deliberately
  ABSENT — no framework reads it."
  [:rf/props :schema])

(defn view-args-schema
  "Return the explicit view-args/props schema from a `view-meta` map (the
  `:view` registrar slot), or nil when none is present. Picks the first
  of `view-args-schema-keys` that is set — `:rf/props` wins over
  `:schema`; no composition. This is the LOW-LEVEL key picker over an
  already-resolved view-meta map."
  [view-meta]
  (when (map? view-meta)
    (some (fn [k] (let [s (get view-meta k)] (when (some? s) s)))
          view-args-schema-keys)))
