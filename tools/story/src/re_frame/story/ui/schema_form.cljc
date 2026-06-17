(ns re-frame.story.ui.schema-form
  "Schema-generated input forms for view-state values (rf2-xon7j).

  ## What it is

  When a `:sub-overrides` target sub declares an output `:schema`, the
  designer should fill in a TYPED FORM (text / number / checkbox / select
  / flat fieldset) rather than hand-write the override's raw EDN. A schema
  good enough to VALIDATE an override (the rf2-7pgiz `:where :sub-override`
  seam — core validates a HIT against the sub's declared `:schema`) is good
  enough to GENERATE its input. This namespace is the pure projection that
  turns a registered schema into a flat field-shape, builds default values,
  coerces typed input back out, and encodes the filled form into the EDN
  `:sub-overrides` entry the View-State section emits as a copy-paste
  scaffold (`re-frame.story.ui.view-state`).

  ## SETTLE-FIRST — introspection is achieved with the EXISTING surface

  Generating a form requires INTROSPECTING a schema into a field-shape
  (key → widget-type). The mayor flagged that `re-frame.schemas` is a
  pluggable-validator façade with only a FLAGGED-PATH walker
  (`walk-flagged-schema` — it walks for `:sensitive?` / `:large?` flags,
  not a general shape enumerator) and NO general introspection API; the
  validator is pluggable, so we MUST NOT assume Malli is the installed
  validator nor call into it to introspect structure (Spec 010 §The
  `:schema` value is opaque to re-frame — the framework MUST NOT call the
  registered validator to introspect schema structure).

  We need no new artefact API. The re-frame2 schema REPRESENTATION is the
  Malli EDN VECTOR FORM — `[op props? children...]` — the same data the
  schemas-artefact walker pattern-matches on WITHOUT importing
  `malli.core`, and the same shape `re-frame.story.malli-schema` already
  decodes (`schema-op` / `schema-children` / `map-entry-key` /
  `map-entry-schema`) for the controls-panel argtype derivation and the
  schema-validation panel's args walk. We read the registered schema's EDN
  form as data — exactly as the established Story surface does — and never
  consult the validator. So introspection is a tiny localized READ over an
  already-shared pure leaf, not a new general-introspection contract.

  ## Flat-shapes-first + the raw-EDN escape hatch (Mike ruled, Option A)

  The first cut renders the FLAT / common shapes only:

  - `:string`        → text input
  - `:int` / `:double` / `:number` → number input
  - `:boolean`       → checkbox
  - keyword-enum (`[:enum :a :b …]`) → select
  - a FLAT `[:map …]` of the above → a fieldset of those widgets

  Anything else — a nested / recursive map, a `:vector` / `:set` / `:tuple`
  collection, an opaque predicate (`[:fn …]`), a registry ref, a compiled
  schema value, or simply NO declared schema — is NOT form-renderable here.
  For those the View-State section ALWAYS offers the raw-EDN escape hatch:
  the designer types the value as EDN. The generated form NEVER blocks a
  value — it is a convenience over the EDN entry that is always present
  (spec/019 §4 — 'sub override without output schema' is a handled empty
  state).

  ## Pure / host-free

  This whole namespace is pure data → data (`.cljc`, `clojure.core` +
  the pure `malli-schema` leaf only — no Reagent, no DOM, no validator).
  The JVM corpus pins every projection; the CLJS form render in
  `re-frame.story.ui.view-state` paints the field-shape this ns derives."
  (:require [clojure.string :as str]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [re-frame.story.malli-schema :as msu]
            [re-frame.story.predicates :as pred]))

;; ===========================================================================
;; PURE: scalar-schema → widget descriptor (flat shapes only)
;; ===========================================================================
;;
;; The widget vocabulary mirrors the controls-panel scalar set
;; (`re-frame.story.ui.controls/scalar-renderers`) so the View-State form
;; reuses the SAME widget tags + renderers (`:text` / `:number` /
;; `:boolean` / `:select`) — no second widget taxonomy. We deliberately
;; recognise ONLY the flat scalar shapes here; collections / nested maps /
;; opaque predicates are NOT form-renderable and fall to the EDN escape
;; hatch (`scalar-widget` returns nil for them).

(defn scalar-widget
  "Given a scalar Malli schema fragment, return its flat widget descriptor
  map, or nil when the fragment is not a renderable flat scalar. Pure data
  → data; JVM-testable.

  Renderable flat scalars:

  - `:string`              → `{:widget :text}`
  - `:int` / `:double` / `:number` → `{:widget :number}`
  - `:boolean`             → `{:widget :boolean}`
  - `:keyword` / `:symbol` → `{:widget :text}` (coerced on entry)
  - `[:enum a b c]` whose members are all keywords
                           → `{:widget :select :options [a b c]}`

  Everything else (a `:map` / `:vector` / `:set` / `:tuple` collection, a
  mixed-type `:enum`, an `[:fn …]` predicate, a registry-name keyword, a
  compiled schema value) returns nil — NOT a flat scalar, so the caller
  drops to the EDN escape hatch."
  [schema]
  (cond
    (= :string schema)  {:widget :text}
    (= :int schema)     {:widget :number :integer? true}
    (= :double schema)  {:widget :number}
    (= :number schema)  {:widget :number}
    (= :boolean schema) {:widget :boolean}
    (= :keyword schema) {:widget :text :coerce :keyword}
    (= :symbol schema)  {:widget :text :coerce :symbol}

    (and (vector? schema) (= :enum (msu/schema-op schema)))
    (let [options (vec (msu/schema-children schema))]
      ;; A keyword-enum is a clean select; a mixed / non-keyword enum is
      ;; NOT a flat select (the option encoding would be ambiguous), so it
      ;; falls to the EDN escape hatch.
      (when (and (seq options) (every? keyword? options))
        {:widget :select :options options}))

    :else nil))

;; ===========================================================================
;; PURE: schema → flat field-shape
;; ===========================================================================

(defn field-shape
  "Project a sub's output `schema` into a flat FIELD-SHAPE the View-State
  form renders, or a non-renderable descriptor that routes to the EDN
  escape hatch. Pure data → data; JVM-testable.

  Returns one of:

      ;; A single flat scalar (the whole override value is one widget):
      {:renderable? true
       :kind        :scalar
       :widget      <widget-descriptor>}

      ;; A FLAT map of flat scalars (one widget per declared key):
      {:renderable? true
       :kind        :map
       :fields      [{:key k :widget <widget-descriptor>} …]}

      ;; Not form-renderable — the designer uses the raw-EDN escape hatch:
      {:renderable? false
       :kind        :edn
       :reason      <human string — why no form>}

  A `[:map …]` is form-renderable ONLY when EVERY declared entry is itself
  a flat scalar (`scalar-widget` non-nil). A single non-flat entry (a
  nested map, a collection, an opaque predicate) makes the whole map
  non-renderable — flat-shapes-first; the escape hatch covers the rest. An
  optional-entry marker (`[k {:optional true} child]`) is fine as long as
  the child is a flat scalar.

  `nil` / a non-vector-non-keyword schema (a compiled schema value, a
  registry ref) is non-renderable — `:reason` names why so the section can
  show an honest note (spec/019 §4 control empty states)."
  [schema]
  (cond
    (nil? schema)
    {:renderable? false :kind :edn :reason "no output schema declared"}

    ;; A top-level flat scalar — the whole override value is one widget.
    (when-let [w (scalar-widget schema)] w)
    {:renderable? true :kind :scalar :widget (scalar-widget schema)}

    ;; A vector form — only a FLAT `[:map …]` is renderable.
    (and (vector? schema) (= :map (msu/schema-op schema)))
    (let [entries (mapv (fn [entry]
                          {:key    (msu/map-entry-key entry)
                           :widget (scalar-widget (msu/map-entry-schema entry))})
                        (msu/schema-children schema))]
      (cond
        (empty? entries)
        {:renderable? false :kind :edn
         :reason "empty map schema — nothing to generate"}

        (every? (comp some? :widget) entries)
        {:renderable? true :kind :map :fields entries}

        :else
        {:renderable? false :kind :edn
         :reason (str "map has non-flat field(s): "
                      (->> entries
                           (remove (comp some? :widget))
                           (map (comp pr-str :key))
                           (str/join ", ")))}))

    ;; Any other vector form (`:vector` / `:set` / `:tuple` / `:and` /
    ;; `:or` / `:maybe` / `:multi` / `[:fn …]` / a non-keyword enum) is
    ;; not flat-renderable.
    (vector? schema)
    {:renderable? false :kind :edn
     :reason (str "non-flat schema form " (pr-str (msu/schema-op schema)))}

    ;; A bare keyword that is not a recognised flat scalar = a registry
    ;; name (`:my/user`) — opaque to a data walk.
    (keyword? schema)
    {:renderable? false :kind :edn
     :reason (str "schema is a registry ref " (pr-str schema))}

    ;; A compiled schema object / fn schema / anything non-data.
    :else
    {:renderable? false :kind :edn
     :reason "schema is not introspectable as EDN data"}))

;; ===========================================================================
;; PURE: default values + input coercion
;; ===========================================================================

(defn widget-default
  "A sensible empty/initial value for a flat `widget` descriptor. Pure data
  → data. Mirrors the controls panel's `default-element-value` for the
  scalar tags this ns supports.

  - `:text`    → `\"\"`
  - `:number`  → `nil` (an empty number input — coerced on entry)
  - `:boolean` → `false`
  - `:select`  → the first option (a select always has a value)"
  [{:keys [widget options]}]
  (case widget
    :text    ""
    :number  nil
    :boolean false
    :select  (first options)
    nil))

(defn coerce-input
  "Coerce a raw input value for `widget` into the typed value the override
  should carry. Pure data → data.

  - `:text` with `:coerce :keyword` → a keyword (`\"loading\"` → `:loading`;
    a leading `:` is tolerated). `:coerce :symbol` → a symbol. Plain text →
    the string unchanged.
  - `:number` → a long when `:integer?`, else a double; nil/blank → nil;
    an unparseable string is returned unchanged so the form does not
    silently swallow a typo (the live override validation surfaces it).
  - `:boolean` → the raw value coerced to a boolean.
  - `:select`  → the raw value passed through (the renderer hands back the
    chosen keyword option)."
  [{:keys [widget integer? coerce]} raw]
  (case widget
    :text    (cond
               (not (string? raw)) raw
               (= :keyword coerce)
               (let [s (cond-> raw (str/starts-with? raw ":") (subs 1))]
                 (when (seq s) (keyword s)))
               (= :symbol coerce)
               (when (seq raw) (symbol raw))
               :else raw)
    :number  (cond
               (number? raw) raw
               (and (string? raw) (seq (str/trim raw)))
               (let [t (str/trim raw)]
                 #?(:clj  (try (if integer? (Long/parseLong t) (Double/parseDouble t))
                               (catch Exception _ raw))
                    :cljs (let [n (if integer? (js/parseInt t 10) (js/parseFloat t))]
                            (if (js/isNaN n) raw n))))
               :else nil)
    :boolean (boolean raw)
    :select  raw
    raw))

(defn default-form-value
  "Build the initial form-value for a `field-shape`. Pure data → data.

  - `:scalar` → the single widget's default value.
  - `:map`    → a `{key default}` map over the fields.
  - `:edn`    → nil (the EDN escape hatch starts from a hand-typed string)."
  [{:keys [kind widget fields]}]
  (case kind
    :scalar (widget-default widget)
    :map    (into {} (map (fn [{:keys [key widget]}]
                            [key (widget-default widget)]))
                  fields)
    nil))

;; ===========================================================================
;; PURE: the raw-EDN escape-hatch parse
;; ===========================================================================

(defn read-edn-value
  "Best-effort read of the raw-EDN escape-hatch input `s` into a value.
  Pure-ish (the only impurity is `read-string`, data → data here). Returns
  `[ok? value-or-error]`. A blank string reads as `[false nil]` so the
  dialog shows 'fill this value' rather than committing a nil override; a
  malformed form reports `[false <error-msg>]` rather than throwing. Mirrors
  `re-frame.story.author-expectations/read-edn` — one tolerant EDN reader
  shape across the Story escape-hatch surfaces."
  [s]
  (cond
    (nil? s)       [false nil]
    (str/blank? s) [false nil]
    :else
    (try
      [true (edn/read-string s)]
      (catch #?(:clj Exception :cljs :default) e
        [false #?(:clj (.getMessage ^Exception e) :cljs (str e))]))))

;; ===========================================================================
;; PURE: form-value → :sub-overrides EDN snippet
;; ===========================================================================
;;
;; The View-State form NEVER writes source directly — it emits a copy-paste
;; `:sub-overrides` scaffold the author pastes into the variant body, the
;; SAME idiom as `view-state/upgrade-snippet`, `save-variant/gen-variant-
;; snippet`, and `author-expectations` (source is never written directly;
;; spec/019 §3 + §5.1). The snippet is the single override entry — query
;; vector → filled value — pretty-printed so a multi-key map reads cleanly.

(defn- pp-value
  "Pretty-print an override `value` for the snippet. A flat map prints one
  key per line, aligned under the opening brace; everything else prints on
  one line via `pr-str`. Pure data → string."
  [value indent]
  (if (and (map? value) (seq value))
    (let [pad     (apply str (repeat indent \space))
          entries (->> value
                       (sort-by (comp str key))
                       (map (fn [[k v]] (str (pr-str k) " " (pr-str v)))))]
      (str "{" (str/join (str "\n " pad) entries) "}"))
    (pr-str value)))

(defn override-snippet
  "Build the copy-paste EDN scaffold that pins `query-v` to `value` as a
  `:sub-overrides` entry on a variant `:extends`-ing `source-variant-id`.
  Pure data → string.

  KEEPS the artifact a `reg-variant` (same artifact kind, the save-variant
  / upgrade-snippet idiom): the scaffold `:extends` the source so the
  component / decorators / args carry forward, and adds a `:sub-overrides`
  slot with the one filled entry for the author to paste + adjust. Source
  is never written directly.

  `value` is the typed value the form produced (or the parsed EDN from the
  escape hatch). When `from-edn?` is true a trailing comment notes the
  value came from the raw-EDN path (the form could not render the shape)."
  ([source-variant-id query-v value]
   (override-snippet source-variant-id query-v value false))
  ([source-variant-id query-v value from-edn?]
   (let [v-str    (pp-value value 20)
         pinned-id (keyword (namespace source-variant-id)
                            (str (name source-variant-id) "-pinned"))]
     (str (pred/reg-variant-envelope
            "story" pinned-id
            (str ":extends " (pr-str source-variant-id) "\n"
                 "   :sub-overrides {" (pr-str query-v) " " v-str "}"))
          "\n;; pins " (pr-str query-v)
          " for design exploration — lowest fidelity, never proof of sub logic."
          (when from-edn?
            "\n;; value entered as raw EDN (the output schema was not flat-renderable).")))))
