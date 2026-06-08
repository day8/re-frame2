(ns day8.re-frame2-machines-viz.context-shape
  "Pure derivation of a machine's static Context SHAPE — the
  `{key → type-caption}` map the chart's root Context band renders so the
  operator sees the machine's `:data` keys + their type shape even with no
  live snapshot in hand (`chart.cljs` `:machine-data`).

  ## Declared over inferred (rf2-3q4k5b · EP-0005)

  Two tiers, declared wins:

    1. **Declared (authoritative).** When the machine spec carries a
       `:data-schema` (a Malli `[:map [k schema] …]`, EP-0005 / rf2-rcim4m),
       the shape is read straight off the schema's `:map` entries. This is
       AUTHORITATIVE — the author declared the context shape — so the chart
       drops the `inferred from :data` badge for that machine (rf2-5tz9p's
       badge becomes conditional on schema-absence rather than always-on).
    2. **Inferred (one-sample).** When there is no `:data-schema`, fall back
       to the pre-existing behaviour (rf2-vcnvj): derive `{key → type}` from
       ONE sample of the definition's initial `:data`, which the chart badges
       `inferred from :data` because a partial initial `:data` can mislead.

  `static-context-shape` returns `{:shape {k caption} :inferred? bool}` — the
  shape plus the flag the host threads into the chart's `:machine-data` +
  `:machine-data-inferred?` props. Returns nil when the machine declares
  neither a `:data-schema` nor a map `:data`, so the panel stays hidden.

  ## Dependency-free, JVM-portable

  This walks the Malli `:data-schema` as PLAIN DATA — a vector whose head is
  the schema-type keyword and whose tail is entries / children. It does NOT
  `:require` Malli (machines-viz carries no Malli dep — it is a viz tool, not
  a validator). The schema is the framework's already-validated artefact; the
  viz only needs its surface shape, which the vector form exposes directly.
  Pure `.cljc` → the JVM test corpus pins it without a browser."
  (:require [clojure.string :as str]))

;; ---- inferred (one-sample, from initial :data) -------------------------

(defn- value-type-caption
  "The type caption for a sampled `:data` VALUE — the inferred path. Mirrors
  the original `topology-view/static-context-shape` ladder (rf2-vcnvj) so the
  inferred shape is unchanged by the declared-over-inferred split."
  [v]
  (cond
    (nil? v)        "nil"
    (boolean? v)    "boolean"
    (number? v)     "number"
    (string? v)     "string"
    (keyword? v)    "keyword"
    (map? v)        "map"
    (vector? v)     "vector"
    (set? v)        "set"
    (sequential? v) "seq"
    :else           "value"))

(defn infer-shape
  "rf2-vcnvj — derive `{key → type-caption}` from one sample of the
  definition's initial `:data`. Returns nil when `:data` is not a map (so the
  panel stays hidden). Pure."
  [definition]
  (let [data (:data definition)]
    (when (map? data)
      (into {} (map (fn [[k v]] [k (value-type-caption v)])) data))))

;; ---- declared (authoritative, from :data-schema) -----------------------

(defn- schema-type-caption
  "A short human caption for a Malli child schema, walking the PLAIN-DATA
  vector form (no Malli runtime). Handles the common scalar predicates +
  the structural collection heads; everything else falls back to a printed
  form so the operator still sees SOMETHING legible rather than a blank cell.

  Captions are aligned to the inferred ladder's vocabulary
  (`number`/`string`/`boolean`/`keyword`/`map`/`vector`/`set`) where the
  schema names a matching type, so a declared shape reads consistently with
  an inferred one — only the badge differs."
  [schema]
  (cond
    ;; A wrapped schema `[head props? & children]` (or `[head & children]`).
    (vector? schema)
    (let [head (first schema)]
      (case head
        (:map :map-of)            "map"
        (:vector :sequential)     "vector"
        (:set)                    "set"
        (:tuple)                  "vector"
        ;; `[:maybe X]` → `X?` (optional/nilable shape — e.g. `string?`).
        :maybe                    (str (schema-type-caption
                                         (some #(when-not (map? %) %)
                                               (rest schema)))
                                       "?")
        ;; `[:enum …]` → the keyword/value space reads as a keyword caption
        ;; when every member is a keyword, else a generic enum.
        :enum                     (if (every? keyword? (rest schema))
                                    "keyword"
                                    "enum")
        ;; `[:= v]` → the literal's own caption.
        :=                        (value-type-caption (second schema))
        ;; A `[:and …]` / `[:or …]` reads as its FIRST informative child.
        (:and :or)                (schema-type-caption
                                    (some #(when-not (map? %) %) (rest schema)))
        ;; Unknown head — print it.
        (str (symbol head))))

    ;; A bare keyword schema (`:int` / `:string` / `:boolean` / …) or a
    ;; predicate symbol (`int?` / `pos-int?` / `string?` / …).
    (keyword? schema)
    (case schema
      (:int :double :number)  "number"
      (:string)               "string"
      (:boolean)              "boolean"
      (:keyword :qualified-keyword :simple-keyword) "keyword"
      (:symbol :qualified-symbol :simple-symbol)    "symbol"
      (:uuid)                 "uuid"
      (:nil)                  "nil"
      (:any :some)            "value"
      (str (symbol schema)))

    (symbol? schema)
    (let [s (name schema)]
      (cond
        (str/includes? s "int")     "number"
        (str/includes? s "number")  "number"
        (str/includes? s "double")  "number"
        (str/includes? s "float")   "number"
        (str/includes? s "string")  "string"
        (str/includes? s "boolean") "boolean"
        (str/includes? s "keyword") "keyword"
        (str/includes? s "map")     "map"
        (str/includes? s "vector")  "vector"
        (str/includes? s "coll")    "vector"
        (str/includes? s "set")     "set"
        :else                       (str/replace s #"\?$" "")))

    :else "value"))

(defn declared-shape
  "rf2-3q4k5b — derive `{key → type-caption}` from a machine's declared
  `:data-schema` (a Malli `[:map …]`). Returns nil when the schema is absent
  or is not a `:map` form (only a `:map` declares per-KEY context shape; a
  non-map schema validates `:data` as a whole and carries no key→type table
  to render). Pure; walks the schema as plain data — no Malli runtime.

  Map-entry forms handled (Malli `:map`):
    `[k child]`            → key `k`, type from `child`
    `[k props child]`      → key `k`, props (e.g. `{:optional true}`) skipped
    `[k {:optional true} child]` → optional key rendered the same shape"
  [data-schema]
  (when (and (vector? data-schema)
             (= :map (first data-schema)))
    (let [entries (->> (rest data-schema)
                       ;; A `:map` may carry an opening props map
                       ;; (`[:map {:closed true} [k …] …]`); skip it. Every
                       ;; real entry is itself a vector `[k …]`.
                       (filter vector?))]
      (when (seq entries)
        (into {}
              (map (fn [entry]
                     (let [k        (first entry)
                           ;; Drop an optional per-entry props map; the last
                           ;; remaining child is the value schema.
                           children (->> (rest entry) (remove map?))
                           child    (last children)]
                       [k (schema-type-caption child)])))
              entries)))))

;; ---- declared-over-inferred top-level ----------------------------------

(defn static-context-shape
  "rf2-3q4k5b — the declared-over-inferred static Context shape for a machine
  DEFINITION. Returns `{:shape {key → type-caption} :inferred? bool}`:

    - When the definition declares a `:data-schema` (a Malli `[:map …]`), the
      shape is AUTHORITATIVE off the schema and `:inferred?` is FALSE (the
      chart drops the `inferred from :data` badge — EP-0005 option-A).
    - Otherwise the shape is INFERRED from one sample of the initial `:data`
      and `:inferred?` is TRUE (rf2-5tz9p's behaviour, unchanged).

  Returns nil when neither a `:map` `:data-schema` nor a map `:data` is
  present (the panel stays hidden). Pure — testable without a browser."
  [definition]
  (or (when-let [shape (declared-shape (:data-schema definition))]
        {:shape shape :inferred? false})
      (when-let [shape (infer-shape definition)]
        {:shape shape :inferred? true})))
