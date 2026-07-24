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
;; A projection here always answers a map, a string, a keyword or a
;; boolean, never nil, so a miss and a remembered nil are not confusable.

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

(defn select-value
  "One authored `<select>` `value` in semantic space, or `::reject` when it
  is outside the grammar — the ONE attribute whose value may be
  COLLECTION-shaped.

  A native `<select multiple>`'s value is not a scalar: what is selected
  is the LIST of chosen option values, and the host contract says so. So
  a SEQUENTIAL value converts member by member through [[attr-value]] —
  `[:a \"b\" 3]` is `[\"a\" \"b\" \"3\"]`, one grammar, no second value
  table — and anything else takes [[attr-value]] whole, which is what
  keeps an ordinary single select exactly as it was.

  SEQUENTIAL and not merely collection-shaped. A set is the tempting
  spelling for a selection and it is refused, because the tree is ONE
  value on two hosts: a set has no order, so the vector read out of one
  can differ between the JVM and ClojureScript, and a declaration would
  answer two trees. A map is refused for having no members to convert."
  [v]
  (if (sequential? v)
    (reduce (fn [acc x]
              (let [s (attr-value x)]
                (if (= ::reject s) (reduced ::reject) (conj acc s))))
            []
            v)
    (attr-value v)))

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

(def ^:private by-emitted-name
  "The clause both refusals end on — one sentence, written once, because the
  two refusals state ONE rule about how a key is read."
  (str "The refusal reads the prop name the emitters will write, so a namespaced "
       "keyword, a string or a symbol spelling the same name is refused with it."))

(defn- attr-key-refusal* [k]
  (let [slot (rules/caller-key-slot k)]
    (cond
      (and (= rules/reserved-key-slot slot) (not= :key k))
      (str "It projects onto React's key, and :key is that slot's one spelling. A key is "
           "not a prop at all — the reconciler consumes it and it never reaches the DOM — "
           "so an alias routed into it would not misspell an attribute, it would change "
           "which element React considers the SAME element across renders: preserved DOM "
           "state landing on the wrong row, or a remount where none was intended. Write "
           ":key. " by-emitted-name)

      (= rules/reserved-ref-slot slot)
      (str "It projects onto React's reserved ref prop, and the interpreted walk has no "
           "ref machinery — React would consume it as a reserved prop while the structural "
           "tree carried it as an ordinary attribute, which is two answers for one "
           "declaration. Refs land with the host-lifecycle slice. " by-emitted-name)

      :else
      (if-some [replacement (rules/rejected-slot-replacement slot)]
        (str "It is not a prop — one spelling per name, ambiguities removed. Use "
             replacement ". " by-emitted-name)
        ::no-refusal))))

(def ^:private refusal-cache (atom {}))

(defn attr-key-refusal
  "Why the interpreted walk refuses attribute key `k`, as the sentence a
  diagnostic states — or `nil` when the key is an ordinary attribute.

  The key is read as the emitters will read it. Both walks classify and
  project an attribute key by its NAME — the namespace is dropped on the
  way to the DOM — so the refusal asks
  [[re-frame.freehand.rules/caller-key-slot]] for the prop slot the key is
  about to be written into and judges THAT, exactly as the runtime spread
  deny does. Comparing the raw key instead left every other representation
  of one emitted prop outside the guard: `:x/children`, `\"children\"` and
  `'children` all reach React's `children` slot, so one declaration
  rendered content the structural tree did not carry — the cross-mode
  divergence this guard exists to rule out (rf2-2bg4t). One canonical
  slot, and refusal and emission agree by construction rather than by
  coincidence.

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
    host-lifecycle slice.
  - React's `key` is refused for a third reason, and it is the reason the
    two ACCEPTED structural slots are not (see [[attr-key]]). `key` is not
    a prop: React consumes it and it never reaches the DOM, so an alias
    routed into it changes RECONCILIATION IDENTITY rather than an
    attribute. That is the `:children` hazard class — a structural
    divergence with no visible spelling — not the misspelled-attribute
    class, so `:key` keeps its single spelling and every alias of it is
    refused (rf2-drpa3.93).

  A key that is not nameable at all has no slot and no refusal here; the
  walks report a malformed key through their own channel.

  Remembered per key — see §Remembered projections above. The verdict is a
  pure function of the key, and this is the one table read on EVERY
  attribute of every element, so the slot projection is paid once per
  distinct authored key rather than once per attribute per render."
  [k]
  (let [answer (remembered refusal-cache k attr-key-refusal*)]
    (when-not (= ::no-refusal answer) answer)))

;; ---------------------------------------------------------------------------
;; Attribute keys the grammar canonicalizes
;; ---------------------------------------------------------------------------

(def ^:private attr-key-cache (atom {}))

(defn attr-key
  "The authored attribute key in the CANONICAL author spelling both walks
  discriminate on — the ACCEPTED-key twin of [[attr-key-refusal]].

  Two attribute keys own a SLOT rather than a place in the attribute map:
  `:class`, which composes with the `.class#id` sugar, and `:style`, which
  carries the CSS grammar. Every representation of those names — `:x/class`,
  `\"class\"`, `'style` — projects onto the SAME React prop the exact
  spelling does, so an alias is that key written differently and belongs in
  its slot:

      (attr-key :x/class)  ;=> :class
      (attr-key \"style\")   ;=> :style
      (attr-key :x/title)  ;=> :x/title

  Routing them rather than refusing them is the ruled answer, and the reason
  is that they are ordinary props. They reach the DOM, and
  `re-frame.freehand.rules/assert-safe-caller!` already accepts an aliased
  spelling of an accepted key and routes it to that key's slot — so refusing
  an alias on the DIRECT attribute path would make it stricter than the
  spread path for the same key, which an author would experience as
  arbitrary. React's `key` is the case that goes the other way, because it
  is not a prop at all; [[attr-key-refusal]] says why.

  Routing `:class` carries one obligation: the value COMPOSES into the class
  string beside the tag shorthand, exactly as the exact spelling does. An
  alias that assigned instead would silently drop `:div.a.b`'s classes,
  which is a worse bug than the one being fixed. Both walks compose because
  both route the canonical key through the one composition they already had.

  Only the slot-owning keys are canonicalized. An ordinary namespaced
  attribute is left in author space — `:x/title` is a `title` attribute in
  React either way, and the structural tree carries authored names, so
  rewriting it would edit the tree for no gain.

  Remembered per key — see §Remembered projections above. This is asked on
  every attribute of every element, so the slot projection is paid once per
  distinct authored key rather than once per attribute per render."
  [k]
  (remembered attr-key-cache k rules/canonical-attr-key))

(defn- id-slot-key?* [k]
  (= rules/sugar-id-slot (rules/caller-key-slot k)))

(def ^:private id-slot-cache (atom {}))

(defn id-slot-key?
  "Does attribute key `k` project onto the slot the tag parser's `#id`
  shorthand already occupies?

  The question both walks ask when — and only when — an element carries
  `#id` sugar, because two spellings of one id on one element is an
  ambiguity the grammar removes rather than ranks (Spec 004B). It is a
  question about the KEY alone, so it is asked of PRESENCE and never of
  the value: the compiled analyzer scans `(keys m)` and has no value to
  consult, and an authored id spelled `nil` is still the element's second
  id spelling. It is asked of the emitted SLOT, through
  [[re-frame.freehand.rules/caller-key-slot]], for the reason
  [[attr-key-refusal]] asks about the same projection: a key is classified
  by the name it is about to be written under, so `:x/id`, `\"id\"` and
  `'id` are `:id` written differently and reach React's `id` all the same.
  Comparing the raw key let an aliased spelling through, and the tree then
  carried the sugar id beside an authored one that overwrote it in the DOM.

  This does NOT canonicalize the key. An `:x/id` on an element with no
  sugar is an ordinary qualified attribute and keeps its authored name —
  the structural tree carries author space, and the ambiguity being ruled
  out is the one the sugar creates.

  Remembered per key — see §Remembered projections above."
  [k]
  (remembered id-slot-cache k id-slot-key?*))

(defn id-slot-keys
  "The keys of `ks` that project onto the emitted `#id` slot — every spelling
  of an element's id (`:id`, `:x/id`, \"id\", 'id). Its COUNT is how many
  times the element declares an id: PRESENCE decides, never value."
  [ks]
  (filter id-slot-key? ks))

(defn- id-spelling-label
  "How one authored id spelling names itself in the conflict sentence — a
  keyword or symbol verbatim, a string quoted so `\"id\"` cannot read as a
  bareword beside `:id`."
  [k]
  (if (string? k) (pr-str k) (str k)))

(defn- namespaced-name?
  "A namespaced keyword or symbol — a spelling whose namespace the host drops
  on the way to the DOM, which is why it lands in the same slot as `:id`."
  [k]
  (and (or (keyword? k) (symbol? k)) (some? (namespace k))))

(defn id-conflict
  "nil when the element declares its id AT MOST ONCE; otherwise a map
  `{:message <sentence> :keys [k …]}` naming the collision.

  `tag` is the element's tag (nil for a forwarded `v/spread` map that names
  no element); `sugar-id` is the tag's `#id` shorthand value (nil when the
  tag carries none) — the FIRST id spelling when present; `ks` are the
  authored attribute keys. `#id` sugar occupies the id slot once, and beyond
  that an attrs map may carry at most one id-slot key: sugar plus one, or two
  authored spellings, is the ambiguity.

  PRESENCE decides, never value — an id spelled nil is still a declaration,
  so `{:id nil :x/id \"b\"}` is two. This is the ONE reading of the id-slot
  cardinality law, shared verbatim by both interpreted walks, the compiled
  analyzer and the runtime `v/spread` seam, so `#id` sugar plus an alias, two
  authored id spellings, and a spread that merges to two all refuse
  identically (rf2-5r1af, rf2-drpa3.101)."
  [tag sugar-id ks]
  (let [id-ks (id-slot-keys ks)]
    (when-some [[a b offenders]
                (cond
                  (and sugar-id (seq id-ks))
                  [(str "#" sugar-id " sugar") (id-spelling-label (first id-ks))
                   [(first id-ks)]]
                  (and (nil? sugar-id) (next id-ks))
                  [(id-spelling-label (first id-ks)) (id-spelling-label (second id-ks))
                   [(first id-ks) (second id-ks)]]
                  :else nil)]
      (let [qk (first (filter namespaced-name? offenders))]
        {:offenders offenders
         :message
         (str (if tag (str "The element " tag) "A forwarded attribute map")
              " spells its id twice — once as " a " and once as " b
              ". Two id spellings on one element is an ambiguity; keep one."
              (when qk
                (str " " qk " is :id written differently: a namespace is dropped "
                     "on the way to the DOM, so both land in the same attribute.")))}))))

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
