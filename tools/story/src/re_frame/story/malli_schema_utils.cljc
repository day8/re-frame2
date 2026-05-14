(ns re-frame.story.malli-schema-utils
  "Pure Malli-schema introspection helpers. Shared between the
  controls panel's widget-derivation (`re-frame.story.ui.controls`,
  CLJS-only) and the schema-validation panel's args-violation walk
  (`re-frame.story.ui.schema-validation`, `.cljc`).

  Used to live as private mirrors in both consumer namespaces — the
  schema-validation copy carried a comment acknowledging the dup. The
  shape is canonical Malli vector-schema decoding: optional properties
  map at index 1, then ordered child schemas. Lives at the leaf of the
  require graph (depends on `clojure.core` only) so any sibling
  namespace can `:require` it without cycle risk.

  See rf2-x77hd / audit rf2-cgqam round-2 P1 #2.")

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
