(ns re-frame.freehand.conversion
  "The DOM conversion table's code half — the pure, host-neutral rules
  BOTH Freehand emitters read.

  [Spec 004B](../../../../../spec/004B-UI-Tree-and-Conversion.md) owns the
  table normatively; this namespace is its executable copy. Nothing here
  walks a form, builds a node, or touches React: it is a table of pure
  functions over one attribute, one class value, one style entry, one tag
  keyword.

  That separation is EP-0036 governing law 7 taken literally. The React
  emitter and the structural emitter are **separate walks** — they are
  not one implementation wearing two hats — but they are allowed to share
  normalizers, and this is the shared set. A rule that lived in only one
  walk could drift between them silently; a rule that lives here cannot,
  because there is one definition and the conformance corpus reads its
  output on both hosts.

  Two halves of the table are represented:

    - the **semantic** half (`class-parts`, `css-value`, `attr-value`,
      `js-number-str`, the namespace context rows) — author space, the
      values the structural tree carries;
    - the **client** half (`react-prop-name`, `react-event-name`,
      `react-style-name`) — the final React prop spelling.

  What is deliberately ABSENT is the serialisation half — final markup
  names, boolean emission classes, escaping, the form-control special
  forms. That half belongs to the JVM serialiser and lands with its own
  slice; a copy here would be a second, unexercised owner.

  Where this table and the compiled tier's [[re-frame.freehand.rules]]
  state the SAME rule — the number grammar, the React prop vocabulary,
  the rejected spellings — this namespace DELEGATES rather than restates.
  Two tables that must agree and are written twice do not agree for long,
  and they part company in the places hardest to notice: the last digit
  of a number that looks right, the casing of a prop React quietly
  declines."
  (:require [clojure.string :as str]
            [re-frame.freehand.rules :as rules]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Remembered projections
;; ---------------------------------------------------------------------------
;;
;; Three of the rules below are pure projections of ONE authored keyword —
;; the `.class#id` parse, the React prop name, the React handler name — and
;; every one of them is asked the same question on every render. An
;; author's markup site spells its tag and its attribute keys as lexical
;; CONSTANTS, so a template of a thousand elements re-derives a thousand
;; answers it already had. Measured on B1 and on the React emitter's own
;; leaf calls, that re-derivation was the largest single cost in the
;; interpreted walk.
;;
;; The TECHNIQUE is `reagent2.impl.template`'s, which has cached both its
;; tag parse and its prop-name projection for years and is the reason a
;; runtime Hiccup interpreter can be fast at all. It is adopted here as a
;; technique and not as a dependency: that namespace is ClojureScript-only
;; (`#js {}` / `aget`), and these rules are `.cljc` because the structural
;; walk runs them on the JVM, so there is no shared artefact to import even
;; if Freehand were willing to take a classpath edge onto an adapter. Two
;; differences from the donor are deliberate:
;;
;;   - it is keyed on the KEYWORD, not on the keyword's `name`. A cache
;;     keyed by name aliases `:svg/text` onto `:text` — one entry serving
;;     two heads that parse differently — and an aliased parse cache fails
;;     in the way that is hardest to see, because the wrong answer is a
;;     perfectly well-formed one. (The donor had to patch that class of
;;     collision twice.) A keyword is its own total key.
;;   - it is BOUNDED, because the key space belongs to the author. Tags
;;     and attribute keys are lexical constants in ordinary markup, so a
;;     live set is a few dozen; a caller that MINTS keywords per render
;;     would otherwise grow these maps without limit. Past the limit a
;;     cache stops accepting entries and the projection costs what it
;;     always cost — a bounded loss, never a leak.
;;
;; A projection here always answers a map or a string, never nil, so a
;; miss and a remembered nil are not confusable.

(def ^:private cache-limit
  "The most entries one remembered projection will hold."
  4096)

(defn- remembered
  "`(f k)`, answered from `cache` when it has been asked before."
  [cache k f]
  (if-some [hit (get @cache k)]
    hit
    (let [v (f k)]
      (swap! cache (fn [m] (if (< (count m) cache-limit) (assoc m k v) m)))
      v)))

;; ---------------------------------------------------------------------------
;; Numbers — JS `ToString`, on both hosts
;; ---------------------------------------------------------------------------
;;
;; Spec 004B §Attr value normalization and §Children, text, and escaping:
;; numbers render with JS `ToString` semantics in BOTH the tree and the
;; markup. On ClojureScript that is free. On the JVM it is not: `(str 1.0)`
;; is "1.0" and `(str 1.0E-4)` is "1.0E-4", where JavaScript answers "1" and
;; "0.0001". A `.cljc` walk that used the host's `str` would produce two
;; different trees for one declaration and the cross-host corpus would be
;; asserting nothing.
;;
;; The rule is [[re-frame.freehand.rules/js-number-str]], not a copy of it.
;; A number grammar reimplemented per emitter is a grammar that drifts per
;; emitter, and the two would drift where it is hardest to notice — in the
;; last digit of a value that looks right.

(def js-number-str
  "`x` rendered with JavaScript `ToString` semantics, identically on both
  hosts — the ONE owned conversion, shared with the compiled tier rather
  than reimplemented beside it. See
  [[re-frame.freehand.rules/js-number-str]] for the rule and for the JVM
  integer domain beyond JavaScript's exactly representable range."
  rules/js-number-str)

;; ---------------------------------------------------------------------------
;; `.class#id` sugar
;; ---------------------------------------------------------------------------

(def ^:private sugar-re #"([.#])([^.#]+)")

(defn- parse-tag*
  [tag-kw]
  (let [s    (name tag-kw)
        cuts (keep identity [(str/index-of s ".") (str/index-of s "#")])
        end  (if (seq cuts) (apply min cuts) (count s))
        toks (re-seq sugar-re (subs s end))]
    {:tag     (keyword (subs s 0 end))
     :classes (into [] (keep (fn [[_ mark nm]] (when (= "." mark) nm))) toks)
     :id      (some (fn [[_ mark nm]] (when (= "#" mark) nm)) toks)}))

(def ^:private tag-cache (atom {}))

(defn parse-tag
  "Split an authored element keyword into its tag and its `.class#id`
  sugar: `:section.panel.open#main` becomes

      {:tag :section :classes [\"panel\" \"open\"] :id \"main\"}

  The tag survives VERBATIM — there is no case folding anywhere, so the
  camelCase SVG tags (`:clipPath`, `:feGaussianBlur`, `:foreignObject`)
  pass through unchanged (Spec 004B §Element fields, pinned).

  Remembered per keyword — see §Remembered projections above."
  [tag-kw]
  (remembered tag-cache tag-kw parse-tag*))

;; ---------------------------------------------------------------------------
;; `:class` — composition and deterministic order
;; ---------------------------------------------------------------------------

(defn- class-name-str [x]
  (cond
    (keyword? x) (name x)
    (string? x)  (when-not (str/blank? x) x)
    :else        ::reject))

(defn class-parts
  "One authored `:class` value expanded into an ordered vector of class-name
  strings, or `::reject` when the value is outside the grammar.

  Spec 004B §`:class`: a string is verbatim, a vector keeps **vector
  order** with nils dropped, and a flag map renders its truthy entries in
  **lexicographic class-name order** — one deterministic rule for literal
  and runtime maps alike, because map iteration order is never trusted."
  [v]
  (cond
    (nil? v)        []
    (false? v)      []
    (string? v)     (if (str/blank? v) [] [v])
    (keyword? v)    [(name v)]
    (map? v)        (let [ns' (keep (fn [[k flag]] (when flag (class-name-str k))) v)]
                      (if (some #(= ::reject %) ns') ::reject (vec (sort ns'))))
    (sequential? v) (reduce (fn [acc x]
                              (let [parts (class-parts x)]
                                (if (= ::reject parts) (reduced ::reject) (into acc parts))))
                            [] v)
    :else           ::reject))

(defn class-string
  "Join class-name parts with single spaces; nil when there are none.
  Duplicates are deliberately NOT removed — class order and duplication
  have no CSS meaning, and the pinned order exists for fingerprints and
  exact-string tests (Spec 004B §`:class`).

  A single part joins to itself, so the one-class case — `.panel`, by far
  the most common element in real markup — answers the string it was
  given instead of building an equal one."
  [parts]
  (case (count parts)
    0 nil
    1 (nth parts 0)
    (str/join " " parts)))

;; ---------------------------------------------------------------------------
;; `:style`
;; ---------------------------------------------------------------------------

(def unitless-css
  "CSS properties whose numeric values do NOT gain `px`. React's published
  `unitlessNumbers`, version-pinned to react-dom 19.2.0 and written in CSS
  kebab space (Spec 004B §`:style`)."
  #{"-moz-animation-iteration-count" "-moz-box-flex" "-moz-box-flex-group"
    "-moz-line-clamp" "-ms-animation-iteration-count" "-ms-flex"
    "-ms-flex-grow" "-ms-flex-negative" "-ms-flex-order" "-ms-flex-positive"
    "-ms-flex-shrink" "-ms-grid-column" "-ms-grid-column-span" "-ms-grid-row"
    "-ms-grid-row-span" "-ms-zoom" "-webkit-animation-iteration-count"
    "-webkit-box-flex" "-webkit-box-flex-group" "-webkit-box-ordinal-group"
    "-webkit-column-count" "-webkit-columns" "-webkit-flex"
    "-webkit-flex-grow" "-webkit-flex-positive" "-webkit-flex-shrink"
    "-webkit-line-clamp" "animation-iteration-count" "aspect-ratio"
    "border-image-outset" "border-image-slice" "border-image-width"
    "box-flex" "box-flex-group" "box-ordinal-group" "column-count" "columns"
    "fill-opacity" "flex" "flex-grow" "flex-negative" "flex-order"
    "flex-positive" "flex-shrink" "flood-opacity" "font-weight" "grid-area"
    "grid-column" "grid-column-end" "grid-column-span" "grid-column-start"
    "grid-row" "grid-row-end" "grid-row-span" "grid-row-start" "line-clamp"
    "line-height" "opacity" "order" "orphans" "scale" "stop-opacity"
    "stroke-dasharray" "stroke-dashoffset" "stroke-miterlimit"
    "stroke-opacity" "stroke-width" "tab-size" "widows" "z-index" "zoom"})

(defn custom-property?
  "Is this CSS name a custom property (`--brand-accent`)? Custom properties
  take no px rule and no case mapping."
  [css-name]
  (str/starts-with? css-name "--"))

(defn css-value
  "One style value rendered as its canonical CSS value string: numbers gain
  `px` unless the property is unitless, a custom property, or the value is
  `0`; keywords stringify through `name`; strings pass trimmed
  (Spec 004B §`:style`)."
  [css-name v]
  (cond
    (keyword? v) (name v)
    (number? v)  (if (or (zero? v)
                         (custom-property? css-name)
                         (contains? unitless-css css-name))
                   (js-number-str v)
                   (str (js-number-str v) "px"))
    :else        (str/trim (str v))))

;; ---------------------------------------------------------------------------
;; Attribute values — the semantic space the tree carries
;; ---------------------------------------------------------------------------

(defn attr-value
  "One authored attribute value in semantic space, or `::reject` when it is
  outside the closed value grammar (Spec 004B §Attr value normalization).

  Keywords and symbols stringify through `name` — a namespaced keyword's
  namespace is silently dropped, which is the shipped rule. Numbers take JS
  `ToString`. Booleans stay booleans, so `{:disabled false}` is
  present-false and distinguishable from absent. Everything else — a
  collection, a function, a host object — is rejected: React would render
  `\"[object Object]\"` garbage, and a host object has no cross-host
  spelling."
  [v]
  (cond
    (string? v)  v
    (keyword? v) (name v)
    (symbol? v)  (name v)
    (number? v)  (js-number-str v)
    (boolean? v) v
    :else        ::reject))

(defn handler-key?
  "Is `k` a handler-position key? The grammar is the `on-` prefix followed
  by a hyphen, so `:on-click` is a handler site and `:online` is an
  ordinary attribute."
  [k]
  (str/starts-with? (name k) "on-"))

;; ---------------------------------------------------------------------------
;; Forwarded children — a RUN, not markup
;; ---------------------------------------------------------------------------
;;
;; A view forwards the children it was given by writing the value into its
;; markup:
;;
;;     (v/defview panel [{:keys [title children]}]
;;       [:section.panel [:h2 title] children])
;;
;; `children` is a VECTOR, because that is what the props contract pins. In
;; child position a vector is otherwise markup, and vector-head
;; classification is TOTAL — there is no heuristic arm that could decide
;; between the two by inspecting the value. So the emitter that PUT the
;; value there marks it: a forwarded children vector carries a run marker,
;; and an emitter splices a run in document order exactly as it splices a
;; seq.
;;
;; The marker is metadata, so a marked run is still `=` to the plain vector
;; the props contract pins — the props map a test compares and the value a
;; body splices are one value, not two.

(def ^:private child-run-key :re-frame.freehand/children-run)

(defn mark-child-run
  "Mark a forwarded `:children` vector as a spliceable run."
  [children]
  (vary-meta children assoc child-run-key true))

(defn child-run?
  "Is `x` a forwarded children run — a vector to splice rather than
  markup to walk?"
  [x]
  (and (vector? x) (true? (child-run-key (meta x)))))

(defn forward-children
  "The props map a view body receives, with its forwarded `:children`
  marked as a run. Identical in both emitters, and `=` to the props map
  the call normalized."
  [props]
  (cond-> props
    (contains? props :children) (update :children mark-child-run)))

;; ---------------------------------------------------------------------------
;; Namespaces — SVG and MathML context
;; ---------------------------------------------------------------------------

(defn element-ns
  "The `:ns` field for an element built in context `ctx`. HTML writes no
  `:ns` at all — the canonical form has exactly one representation per
  node, so `:ns :html` is never emitted (Spec 004B §Element fields)."
  [ctx]
  (when (contains? #{:svg :mathml} ctx) ctx))

(defn enter-ns
  "The context an element with tag `tag` is itself built in, given its
  parent's context: `<svg>` and `<math>` are themselves the integration
  points, so they carry the namespace they open."
  [ctx tag]
  (case tag
    :svg  :svg
    :math :mathml
    ctx))

(declare attr-value)

(defn child-ns
  "The context this element's CHILDREN are built in (Spec 004B
  §Namespaces): `<foreignObject>` reverts its children to HTML, and
  `<annotation-xml>` reverts when its `:encoding` names an HTML island.

  `attrs` may be the authored map or the normalized one — the encoding is
  read through the same value normalization either way, so both emitters
  answer the same context for the same element."
  [ctx tag attrs]
  (case tag
    :foreignObject  nil
    :annotation-xml (let [enc (some-> (get attrs :encoding) attr-value)]
                      (if (and (string? enc)
                               (contains? #{"text/html" "application/xhtml+xml"}
                                          (str/lower-case enc)))
                        nil
                        ctx))
    ctx))

;; ---------------------------------------------------------------------------
;; Void elements
;; ---------------------------------------------------------------------------

(def void-tags
  "Tags that self-close and reject children. Fifteen, per the react-dom/server
  19.2.0 probe recorded in Spec 004B §Children, text, and escaping."
  #{:area :base :br :col :embed :hr :img :input :keygen :link :meta :param
    :source :track :wbr})

(def children-rejected-tags
  "The void set plus `:menuitem`, which rejects children without being
  self-closing (Spec 004B §Children, text, and escaping)."
  (conj void-tags :menuitem))

;; ---------------------------------------------------------------------------
;; The client half — final React prop spellings
;; ---------------------------------------------------------------------------

(defn- camelize [s]
  (str/replace s #"-(\w)" (fn [[_ c]] (str/upper-case c))))

(defn- upper-first [s]
  (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s))

(defn react-style-name
  "A CSS kebab property name as React spells it in a style object. Custom
  properties pass verbatim (React routes them through `setProperty`);
  vendor prefixes follow React's own casing."
  [css-name]
  (cond
    (custom-property? css-name)             css-name
    (str/starts-with? css-name "-webkit-")  (upper-first (camelize (subs css-name 1)))
    (str/starts-with? css-name "-moz-")     (upper-first (camelize (subs css-name 1)))
    (str/starts-with? css-name "-ms-")      (camelize (subs css-name 1))
    :else                                   (camelize css-name)))

(defn- react-prop-name* [k] (rules/react-prop-name (name k)))

(def ^:private prop-name-cache (atom {}))

(defn react-prop-name
  "The author attribute keyword as the React emitter spells it — React's
  own CANONICAL prop name (Spec 004B §Attribute names).

  React does not take DOM attribute spellings. `contentEditable`,
  `acceptCharset`, `charSet`, `tabIndex` and `strokeWidth` are props;
  their lowercase or hyphen-collapsed forms are unrecognized names React
  warns about and handles differently. So the projection reads the
  react-dom `possibleStandardNames` vocabulary in
  [[re-frame.freehand.rules/react-prop-name]] — the same table the
  compiled tier bakes into emitted code, not a second one — with
  `data-*`/`aria-*` verbatim and an unrecognized name verbatim, which is
  React 16+'s own pass-through rule.

  It takes NO namespace context, and that is the point rather than an
  omission. A canonical prop name is canonical everywhere: `:stroke-width`
  is `strokeWidth` whether it sits under `<svg>` directly or under a
  declared view that React renders as its own component. The context-
  sensitive rule this replaced could only be right where the walk happened
  to know the context, so inserting a view boundary silently changed which
  attribute reached the DOM.

  Remembered per keyword — see §Remembered projections above."
  [k]
  (remembered prop-name-cache k react-prop-name*))

;; ---------------------------------------------------------------------------
;; Attribute keys the grammar refuses
;; ---------------------------------------------------------------------------

(defn attr-key-refusal
  "Why the interpreted walk refuses attribute key `k`, as the sentence a
  diagnostic states — or `nil` when the key is an ordinary attribute.

  Two sources, one answer, and both are answered BEFORE either emitter
  writes anything:

  - [[re-frame.freehand.rules/rejected-prop-spellings]] is the grammar's
    own \"one spelling per name\" roster, which the compiled analyzer
    enforces at compile time. The interpreted walk must enforce the same
    keys with the same replacements, or a declaration that the compiler
    rejects would interpret happily — and `:children` in particular is a
    prop React RESERVES, so an attribute-path `:children` would put
    content in the DOM that the structural tree does not carry, and would
    make a structurally legal void element throw in React alone.
  - `:ref` is refused for a different reason. It is legal in the grammar,
    but it is a commit-phase host hook and the interpreted tier has no ref
    machinery; React consumes it as a reserved prop while the structural
    tree carries it as an ordinary attribute. Honouring it half-way is
    exactly the divergence two emitters over one semantic value exist to
    rule out, so the interpreted walk says so instead. Refs land with the
    host-lifecycle slice."
  [k]
  (if (= :ref k)
    (str ":ref is a commit-phase host hook, and the interpreted walk has no ref machinery "
         "— React would consume it as a reserved prop while the structural tree carried it "
         "as an ordinary attribute, which is two answers for one declaration. Refs land "
         "with the host-lifecycle slice.")
    (when-some [replacement (get rules/rejected-prop-spellings k)]
      (str k " is not a prop — one spelling per name, ambiguities removed. Use "
           replacement "."))))

(defn- react-event-name* [k]
  (str "on" (upper-first (camelize (subs (name k) 3)))))

(def ^:private event-name-cache (atom {}))

(defn react-event-name
  "The handler-position key as React spells the prop: `:on-click` becomes
  `onClick`, `:on-double-click` becomes `onDoubleClick`.

  Remembered per keyword — see §Remembered projections above. This is the
  most expensive projection in the table per call (a regex camelize, a
  case fold and three string builds) and the React emitter runs it on
  every handler attribute of every element it emits."
  [k]
  (remembered event-name-cache k react-event-name*))
