(ns re-frame.ssr.emit
  "Pure hiccup → HTML emitter. Per Spec 011 §The render-tree → HTML emitter.

  HTML5: void elements self-close bare, doctype prefix on demand, full
  attr/text escaping, `:tag#id.cls` keyword parsing, callable-head
  (fn / Var) component resolution. `render-to-string` returns ONE
  shape: an HTML STRING — the structural hash and HTTP response triple
  live in sibling namespaces (`re-frame.ssr.hash` /
  `re-frame.ssr.response`).

  ## One head grammar

  A KEYWORD head is a DOM / custom element. Always, on every host.
  Probing `(registrar/lookup :view head)` first and resolving a
  registered view would make `[:dashboard/card 7]` mean \"registered
  view\" here and `<card>` on every client substrate (Reagent's
  `parse-tag` runs `(name tag)`; UIx is not hiccup at all).
  A `.cljc` app sharing views across both — the point of the SSR story —
  could then not write a keyword head that meant one thing, and
  because the SERVER would render it correctly the mistake would survive
  every server-side test.

  Views are referenced by a CALLABLE head: the Var `reg-view` defs, or
  `(rf/view :id)`. Conventions §Render-tree shape vs runtime lookup owns
  this grammar.

  HTML escape helpers (`escape-html`, `escape-attr`, `attr-string`) live
  in `re-frame.ssr.html-helpers`, shared with the head/meta emitter.
  `attr-string` is re-exported below so consumers who
  `:require [re-frame.ssr.emit :as emit]` see it at
  `emit/attr-string`; the emitter calls `html/escape-html` directly."
  ;; There is no `re-frame.registrar` or `re-frame.interop` require: the
  ;; emitter is a pure hiccup → HTML function with no registry dependency
  ;; at all, because resolving a name is not the emitter's job.
  (:require [clojure.string]
            [re-frame.error :as rf.error]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr.hash :as rf.ssr.hash]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            ;; `dom-attr-aliases`, react-dom's prop →
            ;; attribute table, is read by the hiccup attribute conversion
            ;; below. `ui-tree` requires nothing from this ns, so no cycle.
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]
            #?(:cljs [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])))

;; ---- shared HTML helpers --------------------------------------------------
;;
;; Re-export `attr-string` so callers that `:require [re-frame.ssr.emit :as
;; emit]` resolve `emit/attr-string`. The producing ns is
;; `re-frame.ssr.html-helpers` (shared with `re-frame.ssr.head.emit`); the
;; entity-escape rules live there once. `escape-html` / `escape-attr` are
;; consumed directly via the `html/` alias — they have no `emit/`-qualified
;; consumers.

(def attr-string rf.ssr.html-helpers/attr-string)

;; Per HTML5 spec, these elements are void — they self-close and have no
;; closing tag.
;;
;; Lockstep with `reagent2.impl.template/void-tags` (in the
;; `day8/reagent-slim-and-fast` artefact). Bundle isolation forbids
;; `:require` across artefacts (reagent-slim must not pull in
;; `re-frame.ssr` — that's the whole point of the slim artefact), so
;; the set is duplicated by intent. If HTML5 ever extends the void
;; element list (extraordinarily unlikely), update both copies. Per
;; reagent-slim IMPL-SPEC §14.3.
(def void-elements
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; ---- raw-text body tags ---------------------------------------------------
;;
;; `<script>` and `<style>` are HTML "raw text" elements: their content is
;; NOT parsed as markup, so routing it through `escape-html` (which rewrites
;; `<`→`&lt;`, `>`→`&gt;`, `&`→`&amp;`, `"`/`'` → entities) CORRUPTS it — the
;; HTML parser never decodes character references inside a raw-text element,
;; so `[:style "a > b {…}"]` would ship the literal DOM text `a &gt; b` and
;; `[:script "if (a<b){…}"]` would ship `if (a&lt;b)`.
;;
;; An ordinary inline `<script>`/`<style>`
;; with STRING content is AUTHOR CONTENT — trust the programmer. It is
;; emitted VERBATIM with only React's context-safe closing-sequence rewrite
;; (`html/escape-raw-text`), the ONE shared implementation this emitter, the
;; streaming walker, and the S5 serialiser (`re-frame.ssr.ui-tree`) all call,
;; so the same author content is byte-identical on every SSR path. This is
;; NOT sanitisation — the rewrite is the same breakout guard React applies,
;; aimed at attacker-supplied `</script>` DATA that must not terminate the
;; element early; residual JS/CSS-context safety is author-owned, as in
;; React. The DATA-payload channels keep their stricter
;; data-aware escapes: JSON-LD / structured `<head>` content via `reg-head`
;; (`re-frame.ssr.head.emit`, `<` → its JSON unicode escape) and the
;; trusted host-shell
;; `:head`/`:body-end` opts. A `<script>`/`<style>` with no string children
;; (element-only or empty) is structurally inert and takes the ordinary
;; per-child walk; the
;; raw-text set + escape live in `re-frame.ssr.html-helpers`
;; (`html/raw-text-tags` / `html/escape-raw-text`).

;; Tag-name injection gate. Gate the tag
;; component itself: HTML5 / SVG / MathML element names require an ASCII
;; letter start, then letters / digits / hyphens. Reject anything else.
;;
;; Fail fast (throw) rather than escape-and-emit. A tag-name
;; outside the grammar has no safe wire interpretation — escaping would
;; produce `<img&#x20;src=...>` which no browser parses as a tag, just a
;; visible glyph.
;;
;; `:<>` (React fragment) and `:>` (Reagent-native) are special heads
;; consumed by `emit-element` BEFORE this validator runs — they never
;; reach `parse-tag-name`. The grammar below applies only to actual DOM
;; tag emissions.
(def ^:private tag-name-re
  ;; HTML5 §element-name + SVG element-name + MathML element-name all
  ;; share the same conservative ASCII grammar: leading letter, then
  ;; letters / digits / hyphens. Custom elements (per HTML5 §custom-
  ;; element-name) require an ASCII-lower first letter + a `-`; the
  ;; conservative grammar admits both standard elements and well-formed
  ;; custom-element names.
  ;;
  ;; XML-namespaced SVG/MathML tags carry a single colon-
  ;; separated prefix (e.g. `:svg:rect`, `:xlink:href`-style elements).
  ;; Admit one optional `prefix:` segment where the prefix follows the
  ;; same element-name grammar. A single colon only — embedded `<`, `>`,
  ;; whitespace, `=` (the tag-injection vectors) remain rejected, and a
  ;; bare/leading/trailing/double colon (`:rect`, `svg:`, `a::b`) still
  ;; throws because each segment must be a well-formed element name.
  #"(?:[A-Za-z][A-Za-z0-9-]*:)?[A-Za-z][A-Za-z0-9-]*")

(defn- validate-tag-name!
  "Throw `:rf.error/invalid-tag-name` if `tag-name` does not match the
  HTML5 / SVG / MathML element-name grammar (`[A-Za-z][A-Za-z0-9-]*`,
  optionally prefixed by a single XML namespace segment `prefix:`)."
  [tag-name source-kw]
  (when-not (and (string? tag-name)
                 (re-matches tag-name-re tag-name))
    (rf.error/throw-error!
      :rf.error/invalid-tag-name
      're-frame.ssr.emit
      (str "tag-name " (pr-str tag-name)
           " (from hiccup head " (pr-str source-kw) ")"
           " does not match the HTML5/SVG/MathML"
           " element-name grammar"
           " ([A-Za-z][A-Za-z0-9-]*, optionally"
           " namespaced prefix:local) — DOM tag-name"
           " injection forbidden. Use a grammar-valid element name.")
      {:recovery :use-a-valid-element-name
       :extra    {:tag-name tag-name
                  :source   source-kw}}))
  tag-name)

;; Tag-name parsing for the :div#id.cls syntax (Reagent / Hiccup
;; convention). Memoised per-render by keyword identity: the result
;; depends solely on the keyword, and a single shell repeats `:div` /
;; `:span` / `:p` thousands of times. The memo (`*tag-name-cache*`) is
;; bound at the emit entry points and never outlives one render pass;
;; when unbound (cold callers — tests, custom consumers) the call falls
;; through to the uncached parse, so the public surface is the same either
;; way.

(defn- parse-tag-name*
  "Pure parse — the body the memo wraps."
  [tag-kw]
  (let [tag-expression (name tag-kw)
        ;; Match: tag-name optionally followed by #id and .class fragments.
        [_ tag id classes] (re-matches #"([^#.]+)(?:#([^.]+))?(.*)"
                                       tag-expression)
        class-list (when (and classes (seq classes))
                     (->> (clojure.string/split classes #"\.")
                          (remove empty?)
                          (clojure.string/join " ")))]
    (validate-tag-name! tag tag-kw)
    [tag
     (cond-> {}
       id         (assoc :id id)
       class-list (assoc :class class-list))]))

(def ^:dynamic *tag-name-cache*
  "Per-render volatile! holding `{tag-kw [tag-name tag-attrs]}`. Bound
  at the public emit entry points; nil outside a render pass (cold
  callers fall through to the uncached parse)."
  nil)

(defn parse-tag-name
  "Split a keyword like :div#main.col-12.bold into [:div {:id \"main\"
  :class \"col-12 bold\"}] components. Throws `:rf.error/invalid-tag-name`
  if the tag component is not a well-formed HTML5/SVG/MathML
  element name.

  When called inside a render pass (`*tag-name-cache*` bound), the
  result is memoised by keyword identity and reused on subsequent
  emissions of the same head — typical SSR shells repeat `:div`,
  `:span`, `:p` thousands of times."
  [tag-kw]
  (if-let [cache *tag-name-cache*]
    (or (get @cache tag-kw)
        (let [parsed (parse-tag-name* tag-kw)]
          (vswap! cache assoc tag-kw parsed)
          parsed))
    (parse-tag-name* tag-kw)))

(defn merge-class-attrs
  "Merge the class from the tag-name into the attrs map's :class.
  `merged` is the space-joined concatenation of whichever class strings
  exist, or nil when neither is present — nil is the documented
  \"omit :class\" signal the `cond->` below consumes."
  [tag-attrs user-attrs]
  (let [merged (some->> [(:class tag-attrs) (:class user-attrs)]
                        (remove nil?)
                        seq
                        (clojure.string/join " "))]
    (cond-> (merge tag-attrs (dissoc user-attrs :class))
      merged (assoc :class merged))))

(declare emit-element)

(defn- emit-children [children]
  (clojure.string/join (mapv emit-element children)))

(defn- emit-children-threading-root-attrs
  "Emit `children`, threading `root-attrs` onto the FIRST
  child only (nil to the rest), so the render-hash / source-coord lands
  exactly once on the first DOM-tag element reachable through a fragment
  root. A fragment whose first child is itself a fragment / fn-head /
  view-ref recurses — `emit-element` keeps threading the same root-attrs
  down its own root path until a DOM tag consumes it. When `root-attrs`
  is nil this is identical to `emit-children`. A plain `emit-children`
  would drop root-attrs, so a fragment-rooted SSR tree would lose the
  `data-rf-render-hash` marker the emitter docstring promises."
  [children root-attrs]
  (if (nil? root-attrs)
    (emit-children children)
    (let [child-vector (vec children)]
      (clojure.string/join
        (map-indexed (fn [child-index child]
                       (emit-element child (when (zero? child-index) root-attrs)))
                     child-vector)))))

;; ---- source-coord annotation: NOT the emitter's job ----------------------
;;
;; The two dev-mode view annotations — `data-rf2-source-coord` and
;; `data-rf-view` — are stamped at the reg-view REGISTRATION boundary on
;; every host, NOT by this emitter. The JVM half is a
;; debug-gated hiccup walk wrapping the stored `:handler-fn` in
;; `re-frame.core/reg-view*`'s `:clj` branch (see
;; `re-frame.views.jvm-source-coord-annotation`); the CLJS half rides the
;; substrate wrappers. So a registered view reached through its callable
;; head (`[(rf/view :id) …]` / a Var — the shape isomorphic pages use)
;; arrives here ALREADY annotated, and this emitter just stringifies it.
;;
;; Stamping the annotation inside the emitter would need a keyword-view
;; branch, and there is none (a keyword head is a DOM element on every
;; host); it would also never fire on the callable-head shape hydratable
;; pages actually contain. The emitter is a pure hiccup → HTML
;; function with no annotation logic.

;; ---- root-attrs injection ------------------------------------------------
;;
;; The render-hash (data-rf-render-hash) is stamped on the first DOM-tag
;; element of the rendered tree, by a structural injection on the hiccup
;; root before stringification rather than a regex replace over the output
;; string. The injection threads an optional `root-attrs` map down through
;; `emit-element` and consumes it on the first DOM-tag emission — past any
;; fragments (`:<>`), Reagent-native heads (`:>`), or fn-headed /
;; view-ref components on the root path. Non-DOM-rooted trees silently
;; no-op on the injection (the same exemption the registration-boundary
;; source-coord annotation takes for a non-DOM root).

(defn merge-root-attrs
  "Merge root-level injected attrs into the attrs map of
  a DOM tag. Existing attribute values win — the injected attr is only
  added when the key isn't already present, so a caller-supplied
  `data-rf-render-hash` on the root never gets overwritten."
  [attrs root-attrs]
  (reduce-kv (fn [merged-attrs attr-name attr-value]
               (if (contains? merged-attrs attr-name)
                 merged-attrs
                 (assoc merged-attrs attr-name attr-value)))
             attrs
             root-attrs))

;; ---- the hydrating adapter's prop conversion ------------------------------
;;
;; The markup these two hiccup body walkers paint is hydrated by a Reagent-tier
;; adapter (stock Reagent or reagent-slim), and what that client paints is
;; NOT the author's attribute map verbatim: the adapter converts each prop
;; first — a keyword name through Reagent's kebab → camel rule, a keyword
;; value through `name`, a class collection joined — and react-dom then writes
;; the DOM attribute for the prop (`htmlFor` → `for`, `tabIndex` → `tabindex`).
;; A walker writing the author's names and values verbatim would serve an
;; editable `<input read-only>` for `[:input {:read-only true}]`,
;; `type=":button"` for `[:button {:type :button}]` (an invalid type, which
;; the browser reads as SUBMIT) and a class vector's EDN print.
;; React neither patches nor reports an attribute-only divergence at
;; hydration, and the render-tree hash is taken over the tree, not the HTML,
;; so nothing would catch any of it (Spec 011 §The render-tree → HTML emitter).
;;
;; So both walkers convert the way the client does, through ONE function
;; (`dom-element-props`) so they cannot drift:
;;
;;   1. CLASS, on the author's attrs: a `:className` folds into `:class`, and
;;      the class value is joined the Reagent way (`class-names`).
;;   2. The tag shorthand class joins first (`merge-class-attrs`), then the
;;      root attrs (`merge-root-attrs`) — both on KEYWORD keys, before names
;;      are converted, so the root-attrs `contains?` precedence still compares
;;      like with like.
;;   3. NAMES and VALUES (`convert-dom-attrs`): on an ordinary element a
;;      keyword or symbol name takes Reagent's rule and then react-dom's alias
;;      table (`re-frame.ssr.ui-tree/dom-attr-aliases`); a string name takes
;;      the alias table only (Reagent hands a string key to React unchanged).
;;      A hyphenated (custom-element) tag keeps its author names verbatim — the
;;      two supported adapters disagree there, and web components
;;      conventionally read kebab attributes. A keyword or symbol VALUE is
;;      written with `name` on every DOM tag.
;;   4. The form-control special forms react-dom/server applies
;;      (`form-control-props`).
;;
;; `attr-string` judges what it is handed — stripping,
;; the boolean class and the attribute-name grammar all read the CONVERTED
;; name, which is the name that reaches the browser. That is load-bearing, not
;; incidental: `{:onc-lick "alert(1)"}` converts to `oncLick`, which the HTML
;; parser reads as `onclick`, and it is stripped only because the strip reads
;; the converted name. The head emitter and the Ring host shell attribute bags
;; call `attr-string` directly and are written verbatim — no adapter
;; re-renders them.

(defn- class-names
  "Reagent's `class-names`, shared by both supported adapters. A collection
  keeps its TRUTHY members (nil and false drop), a keyword or symbol member
  through `name`, joined with a space — nil when none survive, which omits
  the attribute. A keyword or symbol scalar goes through `name`; anything else
  is returned unchanged. The 2-arity joins two such values, as the adapters do
  when a `:className` rides beside a `:class`."
  ([class-value]
   (cond
     (coll? class-value)
     (let [classes (keep (fn [member]
                           (when member
                             (if (or (keyword? member) (symbol? member))
                               (name member)
                               member)))
                         class-value)]
       (when (seq classes)
         (clojure.string/join " " classes)))

     (or (keyword? class-value) (symbol? class-value))
     (name class-value)

     :else
     class-value))
  ([class-value extra-class-value]
   (let [parts (keep (fn [part] (when part (class-names part)))
                     [class-value extra-class-value])]
     (when (seq parts)
       (clojure.string/join " " parts)))))

(defn- normalise-class-attrs
  "Step 1 of the conversion, on the AUTHOR's attrs — reagent-slim's
  `collapse-class-keys` plus `class-names`. A `:className` folds into
  `:class` (joined with it when both are present), and the `:class` value is
  normalised by `class-names`."
  [user-attrs]
  (let [folded (if (contains? user-attrs :className)
                 (-> user-attrs
                     (assoc :class (class-names (:class user-attrs)
                                                (:className user-attrs)))
                     (dissoc :className))
                 user-attrs)]
    (if (contains? folded :class)
      (assoc folded :class (class-names (:class folded)))
      folded)))

(def ^:private reagent-prop-name-seeds
  "The three names Reagent's prop-name cache is seeded with, because its
  mechanical rule gets them wrong."
  {"class" "className" "for" "htmlFor" "charset" "charSet"})

(defn- capitalise-prop-segment
  "Reagent's `capitalize`: a one-character segment upper-cases whole."
  [segment]
  (if (< (count segment) 2)
    (clojure.string/upper-case segment)
    (str (clojure.string/upper-case (subs segment 0 1)) (subs segment 1))))

(defn- reagent-prop-name
  "The React prop name a Reagent-tier adapter hands React for the keyword
  name `name-str` — stock Reagent's `dash-to-prop-name` behind its seeded
  cache, which reagent-slim copies: split on `-`; a `data` or `aria` first
  segment keeps the name verbatim; otherwise the first segment plus each later
  one capitalised (`read-only` → `readOnly`, `foo-bar` → `fooBar`)."
  [name-str]
  (or (get reagent-prop-name-seeds name-str)
      (let [[start & more] (clojure.string/split name-str #"-")]
        (if (contains? #{"aria" "data"} start)
          name-str
          (apply str start (map capitalise-prop-segment more))))))

(defn- dom-attr-key
  "The attribute name the hydrating client paints for the author key
  `attr-key`. See the section comment above for the rule."
  [custom-element? attr-key]
  (cond
    (string? attr-key)
    (if custom-element?
      attr-key
      (get rf.ssr.ui-tree/dom-attr-aliases attr-key attr-key))

    (or (keyword? attr-key) (symbol? attr-key))
    (if custom-element?
      (name attr-key)
      (let [prop-name (reagent-prop-name (name attr-key))]
        (get rf.ssr.ui-tree/dom-attr-aliases prop-name prop-name)))

    ;; Not a name at all — left for `attr-string`, which refuses it.
    :else
    attr-key))

;; ---- javascript: URLs -------------------------------------------------------
;;
;; react-dom's `setProp` swaps a `javascript:` URL for a URL that throws before
;; it reaches `setAttribute`. It does so in five props on any element it does
;; not treat as custom (`href`, `src`, `action`, `formAction`, `xlinkHref`), and
;; in `data` on an `<object>`. The hydrating client paints through react-dom,
;; and React does not patch an attribute at hydration. So a walker writing
;; the value unchanged would leave a URL live on the hydrated page that the
;; client's own render blocks. The walkers paint what the client paints.
;; The regex, the substituted URL, the prop set and the custom-element test are
;; react-dom 19.3.0's, copied by intent;
;; `re-frame.ssr-javascript-url-react-parity-test` pins them against the
;; installed package.

(def ^:private javascript-url-re
  "react-dom 19.3.0's `isJavaScriptProtocol`, verbatim. It ignores case, skips
  leading C0 controls and spaces, and allows a tab, LF or CR between letters."
  #"(?i)^[\u0000-\u001F ]*j[\r\n\t]*a[\r\n\t]*v[\r\n\t]*a[\r\n\t]*s[\r\n\t]*c[\r\n\t]*r[\r\n\t]*i[\r\n\t]*p[\r\n\t]*t[\r\n\t]*:")

(def ^:private blocked-javascript-url
  "What react-dom 19.3.0 paints in place of a blocked `javascript:` URL."
  "javascript:throw new Error('React has blocked a javascript: URL as a security precaution.')")

(def ^:private javascript-url-props
  "The React props react-dom blocks a `javascript:` URL in, on any element it
  does not treat as custom. `data` joins them on an `<object>` only."
  #{"href" "src" "action" "formAction" "xlinkHref"})

(def ^:private react-non-custom-hyphenated-tags
  "The hyphenated tags react-dom does NOT treat as custom elements."
  #{"annotation-xml" "color-profile" "font-face" "font-face-src"
    "font-face-uri" "font-face-format" "font-face-name" "missing-glyph"})

(defn- block-javascript-url
  "`attr-value` as the hydrating client paints it for `attr-key` on `tag-name`.
  A `javascript:` URL in one of react-dom's URL props becomes the URL react-dom
  substitutes; everything else is returned unchanged. See the section comment
  above."
  [tag-name attr-key attr-value]
  (let [prop-name (cond
                    (string? attr-key) attr-key
                    (or (keyword? attr-key) (symbol? attr-key))
                    (reagent-prop-name (name attr-key)))]
    (if (and (string? attr-value)
             (or (not (clojure.string/includes? tag-name "-"))
                 (contains? react-non-custom-hyphenated-tags tag-name))
             (or (contains? javascript-url-props prop-name)
                 (and (= "data" prop-name) (= "object" tag-name)))
             (re-find javascript-url-re attr-value))
      blocked-javascript-url
      attr-value)))

(defn convert-dom-attrs
  "Convert a DOM element's merged attribute map the way the Reagent-tier
  client adapter plus react-dom does: every key becomes the attribute NAME the
  client paints (a string), and a keyword or symbol VALUE is written with
  `name`. `tag-name` is the element's parsed tag; a hyphenated (custom-element)
  tag keeps its author names verbatim. See the section comment above. A
  `javascript:` URL is blocked where react-dom blocks it
  (`block-javascript-url`)."
  [tag-name attrs]
  (let [custom-element? (clojure.string/includes? tag-name "-")]
    (reduce-kv (fn [converted attr-key attr-value]
                 (assoc converted
                        (dom-attr-key custom-element? attr-key)
                        (block-javascript-url
                         tag-name attr-key
                         (if (or (keyword? attr-value) (symbol? attr-value))
                           (name attr-value)
                           attr-value))))
               {}
               attrs)))

;; ---- form-control special forms --------------------------------------------
;;
;; react-dom/server does not write four form-control props as attributes;
;; writing them would show the wrong control state on first paint until
;; hydration repaired it — which is all a slow-JS, no-JS or crawler visitor
;; ever sees. Each form below is what react-dom 19.3.0's server renderer does
;; with the prop (`pushStartInstance`'s `input` / `textarea` / `select` /
;; `option` arms and `pushAttribute`), keyed on the CONVERTED name, since that
;; is the React prop name:
;;
;;   - `defaultValue` / `defaultChecked` on an `<input>` write `value` /
;;     `checked` when the controlled prop is absent;
;;   - `value` (else `defaultValue`) on a `<textarea>` is its TEXT BODY. When
;;     it is present an authored child is ignored, which is what the client
;;     paints (react-dom's client takes the value and never reads the child);
;;   - `value` (else `defaultValue`) on a `<select>` never writes an
;;     attribute; it marks `selected` on each descendant `<option>` whose
;;     value — its `value` prop, else its text — it names (a collection names
;;     several, for `multiple`). While a select carries one, an option's own
;;     `:selected` is ignored, as react-dom ignores it;
;;   - on any other element both default props are dropped.
;;
;; The select's value reaches its options through `*select-value*`, bound
;; around the select's children the way react-dom carries it in its format
;; context, so options produced by a `for`, a fragment or a component are
;; marked exactly like literal ones.

(def ^:dynamic *select-value*
  "The value of the `<select>` whose children are being emitted, or nil.
  Bound by `with-select-value`; read when an `<option>` is emitted."
  nil)

(defn- attr-value-text
  "JavaScript's `\"\" + v` for a converted attribute value — the string
  react-dom compares when it matches an option against its select."
  [v]
  (cond
    (string? v)                    v
    (number? v)                    (rf.ssr.hash/canonical-number v)
    (or (keyword? v) (symbol? v))  (name v)
    :else                          (str v)))

(defn- option-label
  "react-dom's `flattenOptionChildren`: an option's text children
  concatenated, seqs spliced and nil / boolean children skipped. nil when a
  child is an element, whose text react-dom could not infer either."
  [children]
  (loop [pending (seq children)
         label   ""]
    (if-let [[child & more] pending]
      (cond
        (or (nil? child) (boolean? child))
        (recur more label)

        (or (string? child) (number? child) (keyword? child) (symbol? child))
        (recur more (str label (attr-value-text child)))

        (and (sequential? child) (not (vector? child)))
        (recur (seq (concat child more)) label)

        :else
        nil)
      label)))

(defn- option-selected?
  [select-value option-attrs children]
  (let [option-value (if (some? (get option-attrs "value"))
                       (attr-value-text (get option-attrs "value"))
                       (option-label children))]
    (and (some? option-value)
         (if (or (sequential? select-value) (set? select-value))
           (boolean (some #(= option-value (attr-value-text %)) select-value))
           (= option-value (attr-value-text select-value))))))

(defn- promote-default
  "`default-prop` stands in for `prop` when `prop` is absent or nil, as
  react-dom/server's `<input>` arm does; `default-prop` itself never writes."
  [attrs prop default-prop]
  (if (contains? attrs default-prop)
    (let [default-value (get attrs default-prop)
          attrs         (dissoc attrs default-prop)]
      (if (and (nil? (get attrs prop)) (some? default-value))
        (assoc attrs prop default-value)
        attrs))
    attrs))

(defn- form-control-props
  "Apply the form-control special forms to a CONVERTED attribute map (see the
  section comment above). -> `{:attrs … :text … :select …}`, where `:text` is a
  textarea's text body and `:select` a select's value for its options."
  [normalised-tag-name attrs children]
  (let [controlled (fn [] (let [v (get attrs "value")]
                            (if (some? v) v (get attrs "defaultValue"))))
        drop-defaults #(dissoc % "defaultValue" "defaultChecked")]
    (case normalised-tag-name
      "input"
      {:attrs (-> attrs
                  (promote-default "value" "defaultValue")
                  (promote-default "checked" "defaultChecked"))}

      "textarea"
      {:attrs (drop-defaults (dissoc attrs "value"))
       :text  (some-> (controlled) attr-value-text)}

      "select"
      {:attrs  (drop-defaults (dissoc attrs "value"))
       :select (controlled)}

      "option"
      {:attrs (let [attrs (drop-defaults attrs)]
                (if (some? *select-value*)
                  (cond-> (dissoc attrs "selected")
                    (option-selected? *select-value* attrs children)
                    (assoc "selected" true))
                  attrs))}

      {:attrs (drop-defaults attrs)})))

(defn dom-element-props
  "Everything both hiccup body walkers need to open a DOM element, computed
  once here so the two cannot drift. Joins the
  class (the author's, normalised, after the tag shorthand's), merges
  `root-attrs` (nil on the streaming walker), converts names and values the
  way the hydrating client does, then applies the form-control special forms
  on an ordinary element. -> `{:attrs <string-keyed map for attr-string>
  :text <a textarea's text body, or nil> :select <a select's value for its
  options, or nil>}`."
  [tag-name normalised-tag-name tag-attrs user-attrs root-attrs children]
  (let [merged    (merge-class-attrs tag-attrs (normalise-class-attrs user-attrs))
        merged    (if root-attrs (merge-root-attrs merged root-attrs) merged)
        converted (convert-dom-attrs tag-name merged)]
    (if (clojure.string/includes? tag-name "-")
      {:attrs converted}
      (form-control-props normalised-tag-name converted children))))

(defn with-select-value
  "Call `emit-children-fn` with `*select-value*` bound for a `<select>`'s
  children. Every select rebinds it — to nil when it has no value — so an
  outer select never reaches the options of an inner one."
  [normalised-tag-name select-value emit-children-fn]
  (if (= "select" normalised-tag-name)
    (binding [*select-value* select-value]
      (emit-children-fn))
    (emit-children-fn)))

(defn reserved-rf-head?
  "True when `head` is a keyword in the framework-reserved `:rf/*` scheme
  — the bare `rf` namespace (`:rf/suspense-boundary`) or a dotted
  subsystem segment under it (`:rf.ssr/…`). Per Conventions §Reserved
  namespaces the whole scheme is framework-owned, so no author DOM element
  can legitimately live there.

  The discriminator for the reserved-head guard below. Callers
  consume the RECOGNISED reserved heads (`:>`, `:rf/suspense-boundary`)
  before consulting this, so a `true` here means \"reserved namespace, not
  a marker this emitter implements\"."
  [head]
  (when-let [ns* (and (keyword? head) (namespace head))]
    (or (= "rf" ns*)
        (clojure.string/starts-with? ns* "rf."))))

(defn reject-reserved-rf-hiccup-head!
  "Throw `:rf.error/invalid-hiccup-head` for an UNRECOGNISED head in the
  framework-reserved `:rf/*` scheme.

  With keyword heads uniformly DOM/custom elements (the `keyword?` arm of
  `emit-element`), a reserved-namespace head would otherwise sail
  through the element branch: `:rf/suspense-boundry` (typo) has a `name`
  that passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar, so the emitter
  would paint a phantom `<suspense-boundry>` and say nothing — the exact
  silent-phantom failure mode the one head grammar exists to prevent, one
  keystroke away. The `:rf/*` root is framework-owned, so there is no
  legitimate author element to preserve here and the guard costs one
  namespace test on a branch that already destructures the keyword.

  Reuses `:rf.error/invalid-hiccup-head` rather than minting a near-
  duplicate id: the head genuinely has no HTML interpretation, which is
  precisely what that id names. The message and `:recovery` distinguish
  the arm.

  `el` crosses `error/safe-form` FIRST — see
  `re-frame.error` §cycle-safe diagnostic printing. `head` needs no such
  crossing: this arm is
  reached only for a keyword in the reserved `:rf/*` namespace."
  [element head]
  (let [safe-element (rf.error/safe-form element)]
    (rf.error/throw-error!
      :rf.error/invalid-hiccup-head
      're-frame.ssr.emit
      (str "hiccup vector head " (pr-str head)
           " (in element " (pr-str safe-element) ") is in the framework-reserved"
           " :rf/* namespace but is not a hiccup head this emitter"
           " recognises. The recognised reserved heads are :<> (fragment),"
           " :> (Reagent-native interop) and :rf/suspense-boundary"
           " (streaming, shell walker only). The :rf/* scheme is framework-"
           "owned (Conventions §Reserved namespaces), so this cannot be an"
           " author DOM element — emitting it would paint a phantom <"
           (name head) "> element silently. Check the spelling, or use an"
           " unreserved keyword if you meant a custom element.")
      {:recovery :use-a-recognised-reserved-head-or-an-unreserved-keyword
       :extra    {:head    head
                  :element safe-element}})))

(defn reject-invalid-hiccup-head!
  "Throw `:rf.error/invalid-hiccup-head` for a hiccup vector whose head is
  neither a keyword (DOM tag / `:<>` / `:>` / `:rf/suspense-boundary`) nor
  a callable component (a fn or Var). A head that is a string / nil /
  number / boolean / collection has no HTML interpretation.

  A `(str el)` fallthrough would stringify the WHOLE hiccup vector RAW and
  UNESCAPED onto the wire, so a malformed-head vector carrying
  attacker-controlled child strings (`[nil \"<script>…\"]`,
  `[\"x\" \"<img … onerror=…>\"]`) would ship live `<script>` /
  `<img onerror>` markup — an XSS-class bypass of the escape-at-every-leaf-
  or-fail-loud invariant (Spec 011 §XSS at output boundaries). Fail loud instead,
  mirroring `validate-tag-name!` and the `:>` / `:rf/suspense-boundary`
  throws — never stringify an unescaped hiccup form to the wire. Shared by
  the sync emitter and the streaming shell walker so both paths reject the
  same malformed shape identically.

  THIS ARM IS WHERE A FOREIGN HEAD LANDS: a React context provider is
  neither `keyword?` nor `ifn?`, so `[ctx.Provider {…}]` falls here, and
  `pr-str` of a self-referential JS object would blow the stack —
  `RangeError` instead of the message this function exists to produce.
  `element` crosses
  `error/safe-form` first; `(first element)` is read from the crossed value,
  so the head is covered by the same one crossing."
  [element]
  (let [safe-element (rf.error/safe-form element)]
    (rf.error/throw-error!
      :rf.error/invalid-hiccup-head
      're-frame.ssr.emit
      (str "hiccup vector head " (pr-str (first safe-element))
           " (in element " (pr-str safe-element) ") is not a valid hiccup head — a head"
           " must be a keyword (DOM tag / :<> / :> /"
           " :rf/suspense-boundary) or a callable component (fn / Var). A"
           " string / nil / number / boolean / collection head has no HTML"
           " interpretation; emitting its EDN form raw would bypass output"
           " escaping (XSS). Produce a valid hiccup head.")
      {:recovery :use-a-keyword-or-callable-hiccup-head
       :extra    {:head    (first safe-element)
                  :element safe-element}})))

#?(:clj
   (defn- declared-fixed-arities
     "The set of FIXED arities the compiled fn `f` declares.

     Read off the class, not discovered by calling: a Clojure fn compiles to
     a class declaring one `invoke` method per fixed arity. A purely variadic
     `(fn [& xs] …)` declares NONE, so this returns `#{}` for it — the
     variadic tail is `variadic-required-arity`'s business, and the two must
     stay separate so \"accepts any arity\" is never read as \"accepts zero\".

     Only ever called on a `fn?` value (`resolve-component-head` guards),
     which matters: a Var is `ifn?` but not `fn?`, and `clojure.lang.Var`
     declares `invoke` for arities 0–21 regardless of what it holds, so
     inspecting one would answer yes to everything."
     [f]
     (into #{}
           (keep (fn [^java.lang.reflect.Method m]
                   (when (= "invoke" (.getName m))
                     (alength (.getParameterTypes m)))))
           (.getDeclaredMethods (class f)))))

#?(:clj
   (defn- variadic-required-arity
     "The number of params before the `&` when `f` is VARIADIC, else `nil`.
     A variadic fn extends `clojure.lang.RestFn`, whose `getRequiredArity`
     gives that count: `(fn [& xs] …)` reports 0, `(fn [a & xs] …)` reports 1."
     [f]
     (when (instance? clojure.lang.RestFn f)
       (.getRequiredArity ^clojure.lang.RestFn f))))

#?(:clj
   (defn- form-2-invocation-arity
     "How many of the component's `argument-count` args to hand
      `inner-render-fn` — modelling which arm the SAME function, compiled by
      ClojureScript, would SELECT — or `nil` when no declared arm fits, in
      which case the JVM passes all arguments and lets its own `ArityException`
      report the real call.

     `nil` covers two situations that are NOT the same, and only one of them
     is host agreement:

       • TOO MANY args for every arm of a multi-arm inner — the compiled
         CLJS dispatcher throws `Invalid arity: <count>` as well, so both
         hosts refuse and the shared component is rejected identically.
       • TOO FEW args — CLJS does NOT refuse. JavaScript binds the missing
         parameters to `undefined` and the render proceeds. The JVM refuses
         instead, DELIBERATELY. See `invoke-form-2-render-fn` for the
         supported contract and why this direction is not emulated.

     Walking every declared arity downward and taking the longest accepted
     prefix would NOT be what a compiled CLJS fn does. Only a fn with a
     SINGLE fixed arity and no variadic tail compiles
     to a bare JavaScript function, and only a bare JavaScript function drops
     extra arguments. Anything with more than one arm compiles to a dispatcher
     that switches on `arguments.length` and throws on an unsupported arity when no
     arm matches. On node, against `cljs.core/apply` — exactly what the
     `:cljs` branch of `invoke-form-2-render-fn` calls:

       (fn [x] …)                  at 3 args  → returns (extra args dropped)
       (fn ([x] …) ([x y] …))      at 3 args  → throws `Invalid arity: 3`
       (fn ([] …) ([x] …))         at 2 args  → throws `Invalid arity: 2`

     A prefix walk would select 2 and 1 for those last two and render
     happily, so a shared `.cljc` Form-2 component could render on the server
     and blow up on hydration — the precise parity this helper exists to hold.

     So the three selection rules mirror the three shapes the dispatcher has:

       1. an EXACT fixed arm for `argument-count`  → `argument-count`
       2. a variadic arm whose required count is
          satisfied by `argument-count`            → the whole arg list
       3. exactly ONE fixed arity, no variadic
          tail, shorter than `argument-count`      → that arity (truncate)

     Rule 3 is deliberately narrow, and widening it would be a DIFFERENT
     behaviour wearing this name. There is deliberately no fourth rule for
     TOO FEW args: nothing here ever SUPPLIES an argument the caller did not
     pass, so an inner that declares only larger arities falls through to
     `nil` and the JVM raises. That is the one place the hosts disagree, and
     `invoke-form-2-render-fn` states the contract and the reason."
     [inner-render-fn argument-count]
     (let [fixed-arities  (declared-fixed-arities inner-render-fn)
           required-arity (variadic-required-arity inner-render-fn)]
       (cond
         (contains? fixed-arities argument-count)
         argument-count

         (and required-arity (>= argument-count required-arity))
         argument-count

         (and (nil? required-arity)
              (= 1 (count fixed-arities))
              (< (first fixed-arities) argument-count))
         (first fixed-arities)

         :else nil))))

(defn- invoke-form-2-render-fn
  "Invoke a Form-2 inner render fn with `args`, on the JVM under the arity
  rules the compiled CLJS `inner` would follow for arm SELECTION and for
  EXCESS arguments — see THE SUPPORTED CONTRACT below for the one direction
  in which the JVM deliberately does NOT follow them. The idiomatic
  Reagent/UIx Form-2 inner either takes the SAME args as the outer
  (`(fn [x] …)`), ignores them and closes over the outer's (`(fn [] …)`), or
  — just as validly — takes a non-zero PREFIX of them (`(fn [kept] …)` under
  an `(fn [kept ignored] …)` outer). Those all compile to a bare JavaScript
  function, which drops extra arguments, so the client renders them; the JVM
  is strict, so this helper picks the call shape rather than being told it.
  What it must NOT do is be MORE permissive than the client, which is why
  `form-2-invocation-arity` models the compiled dispatcher rather than
  helpfully finding some arity that works.

  DISCOVERING the shape instead, with
  `(try (apply inner args) (catch ArityException _ (inner)))`, would be
  wrong twice over. The catch would enclose execution of programmer code, so
  an `ArityException` raised INSIDE a correctly-invoked render (an ordinary
  wrong-arity bug in a helper it calls) would be indistinguishable from an
  invocation mismatch: the render body would run a SECOND time, duplicating
  the effects of a non-pure render and reporting the retry's outcome instead
  of the original failure — or, for a variadic inner, succeeding at arity
  zero and silently shipping different HTML. And a retry at arity ZERO alone
  would reject a prefix-taking inner on the server while it renders fine in
  the browser.

  So: select the shape from the inner's DECLARED arities
  (`form-2-invocation-arity`), then invoke exactly once. No user code runs
  inside a catch here, and anything the render throws propagates unchanged.
  When no declared arm fits, `(apply inner args)` lets the inner's own
  `ArityException` report the real call rather than a fabricated retry.

  THE SUPPORTED CONTRACT — stated narrowly, because the wide version is
  false. This helper matches compiled
  CLJS on arity SELECTION (which arm of a multi-arm inner runs, and that a
  count no arm declares is refused on both hosts) and on EXCESS arguments (a
  single-fixed-arity inner compiles to a bare JS function, which drops them;
  the JVM truncates to match). It deliberately DIVERGES in one direction:

    MISSING arguments. Where the caller passes FEWER args than the inner's
    shortest arm requires, JavaScript binds the absent parameters to
    `undefined` and CLJS renders; the JVM raises `ArityException` and SSR
    fails. The server is therefore STRICTER than the client here — a legal
    shared `.cljc` Form-2 can render in the browser and fail during SSR.

  That is a choice, not an oversight, and the reason is one clause: CLJS
  supplying `undefined` is an accident of the JavaScript calling convention,
  and emulating it would ship an author's arity mistake as production HTML
  with nil props instead of failing where it can be seen. So do NOT \"fix\"
  this by filling the missing slots with nil. Both cases are pinned on both
  hosts, divergence included, by `re-frame.ssr.form2-arity-cljs-test`."
  [inner-render-fn args]
  #?(:clj  (let [invocation-arity (form-2-invocation-arity inner-render-fn
                                                            (count args))]
             (apply inner-render-fn
                    (if invocation-arity
                      (take invocation-arity args)
                      args)))
     :cljs (apply inner-render-fn args)))

(defn resolve-component-head
  "Resolve a callable-head hiccup component (`[component & args]`, where
  `component` is a fn or a Var — the idiomatic Reagent/UIx SSR shape,
  the same shape the render-tree hash walker supports) to renderable hiccup.

  A Form-1 component returns hiccup directly. A Form-2 component returns an
  INNER render fn; per Reagent/UIx Form-2 semantics it is invoked once
  more with the SAME args (at the arity its declared arms select, and
  tolerating EXCESS args only — see `invoke-form-2-render-fn` for the
  supported contract) to obtain the hiccup. Resolving Form-2 here
  does NOT perturb the structural hash — the hash walks the RAW tree
  (`[component …]`, a raw fn head serialising to one fixed token, hash.cljc),
  identical on server and client, so the resolved output cannot fire a
  spurious hydration mismatch.

  A bare `(apply head args)` would leave a Form-2 result (a fn) to fall
  through to `escape-html`, which would stringify the fn's `.toString`
  (`user$…fn__…@…`) as visible page text (plus a guaranteed downstream
  hydration mismatch). A result that is STILL a bare fn after the
  single Form-2 unwrap is not a valid component render — fail loud with
  `:rf.error/ssr-nonrenderable-component` rather than leak the fn text."
  [head args]
  (let [resolved (apply head args)]
    (if (fn? resolved)
      ;; Form-2 — the outer fn returned an inner render fn; invoke it once
      ;; with the same args (Form-2 semantics) to get the hiccup.
      (let [rendered (invoke-form-2-render-fn resolved args)]
        (if (fn? rendered)
          (rf.error/throw-error!
            :rf.error/ssr-nonrenderable-component
            're-frame.ssr.emit
            (str "a callable hiccup component resolved to a fn even after the"
                 " Form-2 unwrap (outer fn → inner render fn → still a fn). A"
                 " Form-2 component's inner render fn must return hiccup, not"
                 " another fn; SSR cannot render a bare fn — it would"
                 " stringify the fn's .toString as visible page text. Return"
                 " hiccup from the component's render fn.")
            {:recovery :return-hiccup-from-the-component-render-fn
             :extra    {:component head}})
          rendered))
      resolved)))

(defn emit-element
  "Emit a hiccup node as an HTML string. The optional `root-attrs` map
  carries attributes destined for the first DOM-tag
  element on the root path — view-refs, fragments, Reagent-native heads,
  and fn-headed components pass it through; the first DOM-tag emission
  merges and consumes it. Recursive calls into children always pass
  `nil` so the injection lands on the root only."
  ([el] (emit-element el nil))
  ([el root-attrs]
   (cond
     (nil? el)         ""
     (string? el)      (rf.ssr.html-helpers/escape-html el)
     ;; Canonicalise the numeric print form so the emitted HTML matches the
     ;; render-tree hash byte-for-byte across runtimes: a
     ;; whole-valued double renders `9` (not the JVM `9.0`), agreeing with
     ;; CLJS. `canonical-number` uses `pr-str`, which coincides with `str`
     ;; for numbers (no quoting), so ordinary integers/decimals are
     ;; unchanged.
     (number? el)      (rf.ssr.hash/canonical-number el)
     (boolean? el)     ""
     ;; A keyword or symbol CHILD is spelled by its `name` — no leading
     ;; colon, namespace dropped.
     ;;
     ;; Falling through to `escape-html`, whose `(str s)` keeps a
     ;; keyword's colon and a symbol's namespace, would emit
     ;; `<card>:revenue</card>` and `<div>a/b</div>` on the JVM where every
     ;; client substrate paints `<card>revenue</card>` and `<div>b</div>`.
     ;; On both hosts Reagent routes a `named?` child through
     ;; `(name x)` before handing it to React, which is why the namespace
     ;; disappears — `:a/b` and `'a/b` both paint `b`.
     ;;
     ;; The CHILD spelling of a keyword follows its HEAD meaning onto every
     ;; host. A text-node mismatch is not cosmetic,
     ;; because React hydration reconciles text nodes as well as element
     ;; structure, so a server ":revenue" could not hydrate cleanly
     ;; against the client's "revenue". Same rule, same reason: one
     ;; render tree means one thing on every host.
     ;;
     ;; `(or (keyword? el) (symbol? el))` rather than `named?` — the
     ;; latter is CLJS-only, and this is a `.cljc` emitter.
     (or (keyword? el) (symbol? el))
     (rf.ssr.html-helpers/escape-html (name el))
     (vector? el)
     (let [head (first el)]
       (cond
         ;; Fragment `:<>` — emits its rendered children with no wrapper.
         ;; Per Spec 011: source-coord annotation skips this head (the
         ;; fragment itself is not a DOM element), and the tag-name
         ;; validator does not apply. Handled ahead of the
         ;; general keyword branch so it never reaches `parse-tag-name`.
         ;;
         ;; Root-attrs (the render-hash
         ;; / `:render-hash` marker) MUST thread through a fragment root
         ;; onto the first DOM-tag child, exactly once. A plain
         ;; `emit-children` would drop them, so `[:<> [:div "x"]]` with
         ;; a supplied `:render-hash` would lose its `data-rf-render-hash`
         ;; even though the docstring promises threading "past … fragments".
         ;; Thread onto the first child only; nested fragments / fn-heads /
         ;; view-refs keep threading down their own root path.
         ;;
         ;; A FRAGMENT MAY CARRY A PROPS MAP AT SLOT 1, and it
         ;; is NOT a child. `[:<> {:key i} …]` inside a `for` is the
         ;; canonical fragment idiom, so this is ordinary application
         ;; markup, not a corner. A plain `(rest el)` would hand that
         ;; map to `emit-element` as the FIRST child, which would fall
         ;; through to `escape-html` and put the map's EDN in the response
         ;; bytes (`[:<> {:key "k"} [:div "x"]]` → `{:key &quot;k&quot;}<div>x
         ;; </div>`) — garbage text, and a guaranteed hydration mismatch
         ;; against a client render that emits none of it. It would ALSO
         ;; eat the root-attrs above: with the map as the first child, the
         ;; `data-rf-render-hash` marker would be threaded onto a value that
         ;; cannot carry an attribute and vanish, silently, on exactly
         ;; the keyed fragments applications write. Skipping the slot
         ;; avoids both. It is the failure the `:>` arm below states it
         ;; refuses to commit ("dump the props map as raw EDN into the
         ;; markup").
         ;;
         ;; The slot test is `(map? (second el))` — the same spelling the
         ;; DOM-tag branch below uses, and the same rule as the
         ;; React-side codec's `props-map?` (a map is props; a seq, string
         ;; or hiccup vector there is a child).
         ;;
         ;; WHAT HAPPENS TO THE ATTRIBUTES: they are DROPPED, silently and
         ;; whatever they are, `:key` included. A fragment is not an
         ;; element, so no attribute on one has a wire representation —
         ;; `:key` is reconciliation identity, which the client recomputes
         ;; from the same tree, and there is no tag to hang the rest on.
         ;; Dropping is what every sibling emitter in this repo
         ;; does (reagent-slim's static `emit-fragment` skips the whole
         ;; slot; the React-side codec reads `:key` off it and ignores the
         ;; remainder) and what React does at runtime — a stray prop on a
         ;; Fragment is a development warning, never an error. Refusing
         ;; here would make the SERVER stricter than the client for markup
         ;; that renders fine in a browser, i.e. a server-only failure on a
         ;; shared `.cljc` view — the one divergence direction this
         ;; artefact takes deliberately and narrowly elsewhere
         ;; (`invoke-form-2-render-fn` §THE SUPPORTED CONTRACT), and there
         ;; it buys a caught mistake. Here it would buy nothing: the arm
         ;; emits exactly what the client paints. The loud arms in
         ;; this emitter (`:>`, `:rf/suspense-boundary`, reserved `:rf/*`,
         ;; a malformed head) all guard shapes with NO safe wire meaning,
         ;; where the alternative is a phantom element or unescaped EDN.
         ;; A fragment attribute's safe wire meaning is nothing, and
         ;; nothing is what this emits.
         (= :<> head)
         (emit-children-threading-root-attrs
           (if (map? (second el)) (drop 2 el) (rest el))
           root-attrs)

         ;; Reagent-native interop head `:>` — `[:> Component {props} …]`
         ;; passes its children through to a React COMPONENT, not a DOM
         ;; tag. There is no React on the JVM, so `:>` cannot be statically
         ;; rendered server-side. Fail loud rather than
         ;; splice `(rest el)` through `emit-children`, which would stringify
         ;; the component ref and dump the props map as raw EDN into the
         ;; markup (garbage output, not a rendered component). The author
         ;; wraps the React component in a `reg-view` and references THAT
         ;; by its callable head — the Var `reg-view` defs, or
         ;; `(rf/view :id)`. The emitter is a pure hiccup → HTML function
         ;; with no registry lookup, so nothing resolves an id here; the
         ;; CALLABLE head is what the emitter invokes, which is why the
         ;; spelling has to be in the message.
         ;;
         ;; `el` crosses `error/safe-form` before it is
         ;; printed OR put in ex-data. This arm is the one where a foreign
         ;; JS value is not merely possible but EXPECTED: `[:> ctx.Provider
         ;; …]` is what `:>` interop is FOR, and a React 19 provider is a
         ;; cyclic object graph.
         (= :> head)
         (let [el (rf.error/safe-form el)]
           (rf.error/throw-error!
             :rf.error/ssr-reagent-native-head
             're-frame.ssr.emit
             (str "Reagent-native interop head `:>` "
                  "(element " (pr-str el) ") cannot be "
                  "rendered server-side — it targets a "
                  "React component and there is no React "
                  "on the JVM. Wrap the component in a "
                  "reg-view and reference that view by its "
                  "CALLABLE head — the Var reg-view defs "
                  "(`[my-view …]`) or `[(rf/view :my/id) …]` "
                  "— or render it client-only. A bare "
                  "keyword head is an HTML element, not a "
                  "view reference.")
             {:recovery :wrap-in-reg-view-or-render-client-only
              :extra    {:element el}}))

         ;; Reserved streaming marker `:rf/suspense-boundary` — recognised
         ;; ONLY by the streaming shell walker (`re-frame.ssr.streaming`).
         ;; The standard emitter must NOT treat it as a DOM tag: its
         ;; name passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar, so
         ;; without this guard `parse-tag-name` would emit a phantom
         ;; `<suspense-boundary>` element with the `{:id … :fallback …}`
         ;; attrs map serialised as bogus attributes. Fail
         ;; loud — parallel to the `:>` throw above — so a marker that
         ;; reaches a non-streaming render (e.g. `render-to-string` on a
         ;; streaming tree) surfaces a structured error rather than
         ;; silently producing malformed markup. Per Conventions §`:rf/*`
         ;; reserved hiccup heads + Spec 011 §Streaming SSR.
         ;; `el` crosses `error/safe-form` first: a
         ;; boundary's `:fallback` is ordinary hiccup and can carry a
         ;; foreign JS value anywhere inside it.
         (= :rf/suspense-boundary head)
         (let [el (rf.error/safe-form el)]
           (rf.error/throw-error!
             :rf.error/ssr-suspense-boundary-outside-stream
             're-frame.ssr.emit
             (str ":rf/suspense-boundary (element "
                  (pr-str el) ") is a streaming-only "
                  "marker recognised by the streaming "
                  "shell walker (re-frame.ssr.ring/"
                  "stream-handler), not the standard "
                  "emitter. It reached render-to-string "
                  "outside a stream — that path cannot "
                  "resolve the boundary's continuation, "
                  "so it would emit a phantom "
                  "<suspense-boundary> DOM element. Use "
                  "stream-handler to render trees "
                  "containing :rf/suspense-boundary.")
             {:recovery :render-via-stream-handler
              :extra    {:element el}}))

         ;; An unrecognised head in the framework-reserved `:rf/*` scheme.
         ;; The recognised reserved heads are consumed above (`:<>`, `:>`,
         ;; `:rf/suspense-boundary`); anything else under the reserved root
         ;; is a typo or a marker this emitter does not implement, and its
         ;; name passes the `[A-Za-z][A-Za-z0-9-]*` tag grammar — so
         ;; falling through to the element branch would paint a phantom
         ;; `<suspense-boundry>` / `<hydrate>` and say nothing. Per
         ;; Conventions §Reserved namespaces the `:rf/*` root is framework-
         ;; owned, so no author element can legitimately live there; fail
         ;; loud. Reuses `:rf.error/invalid-hiccup-head` —
         ;; the head genuinely has no HTML interpretation, which is exactly
         ;; what that id names — rather than minting a near-duplicate id.
         (reserved-rf-head? head)
         (reject-reserved-rf-hiccup-head! el head)

         (keyword? head)
         ;; ONE render-tree head grammar, corpus-wide: a
         ;; keyword head is a DOM / custom element on EVERY host. Probing
         ;; `(registrar/lookup :view head)` first and resolving a
         ;; registered view would make `[:dashboard/card 7]`
         ;; mean "registered view" here and "an HTML `<card>` element" on
         ;; every client substrate (Reagent's `parse-tag` runs `(name
         ;; tag)`; UIx are not hiccup at all). A `.cljc` app sharing
         ;; views across both — the whole point of the SSR story — could
         ;; not write a keyword head that meant one thing, and with the
         ;; server rendering it CORRECTLY while the client painted a
         ;; phantom, the mistake would survive every server-side test.
         ;;
         ;; Conventions §Render-tree shape vs runtime lookup owns the head
         ;; grammar: "keyword tags stay plain substrate-owned HTML
         ;; elements". Views are referenced by callable binding:
         ;; the Var `reg-view` defs, or `(rf/view :id)`.
         (let [[tag-name tag-attrs] (parse-tag-name head)
               [user-attrs children]
               (if (map? (second el))
                 [(second el) (drop 2 el)]
                 [{} (rest el)])
               ;; Void + raw-text classification
               ;; must be CASE-INSENSITIVE. `validate-tag-name!` admits
               ;; upper/mixed-case names (`[:BR]`, `[:SCRIPT …]`), but
               ;; `void-elements` / `raw-text-tags` are keyed lower-case,
               ;; so a raw lookup would emit a `[:BR]` as a non-void
               ;; open+close pair and classify a `[:SCRIPT "a<b"]` wrongly.
               ;; Normalise for classification while preserving the
               ;; author's emitted case.
               normalised-tag-name (clojure.string/lower-case tag-name)
               ;; Class join, root attrs, the
               ;; client's name/value conversion and the form-control special
               ;; forms, shared with the streaming walker.
               {attrs :attrs text :text select-value :select}
               (dom-element-props tag-name normalised-tag-name tag-attrs
                                  user-attrs root-attrs children)
               void?        (contains? void-elements (keyword normalised-tag-name))
               raw-text?    (contains? rf.ssr.html-helpers/raw-text-tags normalised-tag-name)]
           (cond
             void?     (str "<" tag-name (attr-string attrs) ">")
             ;; An ordinary inline <script>/<style> with STRING
             ;; content is author content: emit it VERBATIM with only the
             ;; shared closing-sequence rewrite (`html/escape-raw-text`),
             ;; byte-identical to the S5 serialiser and the streaming walker.
             ;; The `every? string?` gate mirrors the compiled path — an
             ;; all-string body is the real inline-script/style shape; any
             ;; structural child takes the ordinary per-child walk
             ;; (element children pass through inert; the hiccup emitter has
             ;; no compiled child-shape grammar).
             (and raw-text? (seq children) (every? string? children))
             (str "<" tag-name (attr-string attrs) ">"
                  (rf.ssr.html-helpers/escape-raw-text normalised-tag-name
                                        (clojure.string/join children))
                  "</" tag-name ">")
             ;; A `<textarea>`'s `:value` (else `:default-value`)
             ;; is its text body, as react-dom/server writes it, with the same
             ;; leading-LF compensation a string child gets.
             (some? text)
             (str "<" tag-name (attr-string attrs) ">"
                  (rf.ssr.html-helpers/leading-newline-compensation
                    normalised-tag-name text)
                  (rf.ssr.html-helpers/escape-html text)
                  "</" tag-name ">")
             ;; A `<pre>`/`<listing>`/`<textarea>` whose body is a
             ;; SINGLE string beginning with LF gets the one compensating LF
             ;; react-dom/server 19.2 emits, because the HTML parser eats the
             ;; first LF after those start tags. Without it `[:pre "\ncode"]`
             ;; would reach the DOM as "code" — one authored character lost, and a
             ;; text hydration mismatch against the client's rendering of the
             ;; same `.cljc` view. The roster and the rule are shared with the
             ;; streaming walker and the S5 serialiser
             ;; (`html/leading-newline-compensation`); `""` for every other
             ;; tag and body shape, so this is inert on the common path.
             :else
             (str "<" tag-name (attr-string attrs) ">"
                  (rf.ssr.html-helpers/leading-newline-compensation
                    normalised-tag-name
                    (rf.ssr.html-helpers/sole-string-child children))
                  (with-select-value normalised-tag-name select-value
                    #(emit-children children))
                  "</" tag-name ">")))

         ;; Callable component head — a plain fn OR a Var reference
         ;; (`[#'component & args]`). On the JVM a Var is `ifn?` but NOT
         ;; `fn?`, so a bare `(fn? head)` test would send a Var-headed
         ;; component to the malformed-head `:else` arm instead of
         ;; resolving it. `ifn?` covers both — keywords/`:<>`/`:>`/
         ;; `:rf/suspense-boundary` are all consumed by the branches above,
         ;; so the only callables reaching here are fns and Var references.
         ;; Pass root-attrs through this indirection too — structurally the
         ;; same kind of wrapping as a registered-view ref, so the root
         ;; hash / source-coord thread through the Var head onto the
         ;; resolved DOM root.
         ;;
         ;; `resolve-component-head` handles a Form-2 component
         ;; (an outer fn returning an inner render fn): the inner fn is
         ;; invoked once with the same args rather than left to fall through
         ;; to `escape-html`, which would stringify the fn's `.toString` as
         ;; visible page text.
         (ifn? head)
         (emit-element (resolve-component-head head (rest el)) root-attrs)

         ;; A vector whose head is not a keyword and not a
         ;; callable (string / nil / number / boolean / collection head) is
         ;; malformed. `(str el)` would ship its EDN form RAW and
         ;; UNESCAPED (XSS-class escape bypass); fail loud instead.
         :else (reject-invalid-hiccup-head! el)))

     ;; A non-vector sequential ROOT (a lazy-seq / list — e.g. the result of
     ;; `(map …)` or `(for …)` at the root). Per Spec 011 §Source-coord
     ;; annotation / §Hydration-mismatch detection a lazy-seq root is
     ;; "passed through the injection — the attribute lands on the eventual
     ;; DOM root." A plain `emit-children` would DROP
     ;; `root-attrs`, so a lazy-seq-rooted tree would silently lose its
     ;; `data-rf-render-hash` marker; thread it onto the first DOM child,
     ;; exactly like the `:<>` fragment root.
     (sequential? el) (emit-children-threading-root-attrs el root-attrs)
     :else (rf.ssr.html-helpers/escape-html el))))

(defn render-to-string
  "Pure hiccup → HTML string. Per Spec 011 §The render-tree → HTML
  emitter. Returns a STRING. The structural hash (`render-tree-hash`)
  and the HTTP response accumulator (`re-frame.ssr/get-response`, backed
  by the framework-private `response-slots` side-channel atom —
  Spec 011 §Response storage substrate) are separate surfaces.

  Implements HTML5 void elements, :tag#id.cls parsing, boolean attrs,
  text/attr escaping, callable-head (fn / Var) component
  resolution, :doctype? prefix, and :render-hash root-element hash
  injection for client-side mismatch detection.

  When `:render-hash` is supplied, `data-rf-render-hash`
  is threaded as `root-attrs` through `emit-element` and merged onto the
  first DOM-tag element of the rendered tree — past view-refs, fragments,
  Reagent-native heads, and fn-headed components on the root path. The
  injection is structural rather than a post-emit regex over the string,
  so it composes with the source-coord annotation and silently no-ops for
  non-DOM-rooted trees (matching the source-coord exemption).

  `:render-hash` is the ONE marker spelling.
  A caller that wants the marker computes the structural hash itself and
  passes it in — that single hash then drives BOTH the root-element
  `data-rf-render-hash` injection AND the caller's own payload slot
  (e.g. the ssr-ring pipeline's `:rf/render-hash`), so the tree is
  walked once rather than twice:

      (let [h (ssr/render-tree-hash tree)]
        {:html (ssr/render-to-string tree {:render-hash h})
         :rf/render-hash h})

  `:render-hash` is a pure pass-through to the root-attrs stamper, so
  Spec 011's hash/emit separation is preserved (no combined walker).
  The 1-arity is `(render-to-string tree {})` — no doctype, no marker.

  Unknown opts are ignored; the emitter does not validate its opts map."
  ([render-tree] (render-to-string render-tree nil))
  ([render-tree opts]
   ;; Bind the per-render parse-tag-name memo so
   ;; repeated heads (`:div`, `:span`, `:p`, …) parse once instead of
   ;; once per emission. Cache lives only for the duration of this
   ;; render call.
   (binding [*tag-name-cache* (volatile! {})]
     (let [supplied-hash (:render-hash opts)
           root-attrs    (when supplied-hash
                           {:data-rf-render-hash supplied-hash})
           body          (emit-element render-tree root-attrs)]
       (if (:doctype? opts)
         (str "<!DOCTYPE html>" body)
         body)))))

;; Wire render-to-string into the plain-atom adapter so callers using
;; ssr/render-to-string (delegating through the substrate adapter) get
;; this implementation. The Reagent adapter wires its own
;; set-hiccup-emitter! through `:reagent/set-hiccup-emitter!`; we
;; consume that hook below so ssr does not statically :require the
;; Reagent adapter ns.
#?(:clj
   (try
     (require 're-frame.substrate.plain-atom)
     ((requiring-resolve 're-frame.substrate.plain-atom/set-hiccup-emitter!)
      render-to-string)
     (catch Throwable _ nil)))

#?(:cljs
   (rf.substrate.plain-atom/set-hiccup-emitter! render-to-string))

;; Reagent adapter wiring (load-order-symmetric counterpart to the
;; plain-atom path above). No-op when the Reagent adapter isn't on the
;; classpath.
(when-let [reagent-set-emitter! (rf.late-bind/get-fn :reagent/set-hiccup-emitter!)]
  (reagent-set-emitter! render-to-string))

;; Retain the current SSR emitter durably so a substrate
;; adapter can RE-ARM its render-to-string slot at EVERY install, not only the
;; one-time ns-load publications above. The React-shaped adapter (UIx)
;; clears its per-generation `emitter-cell` on `dispose-adapter!`
;; (spine `dispose-active-roots-and-caches!`), so a public destroy → re-init
;; cycle — or an SSR-loaded-before-adapter load order, where the chain lookup
;; above finds no adapter yet — would otherwise leave `render-to-string`
;; unarmed (`:rf.error/no-hiccup-emitter-bound`). This slot is the single
;; authoritative source `re-frame.substrate.adapter/install-adapter!` replays
;; from at each install; disposal still clears each adapter's own slot, and the
;; emitter is never retained inside a disposed adapter. Host-neutral: the
;; JVM/plain-atom adapter retains its emitter across its no-op dispose, so its
;; install replay is a harmless idempotent re-apply. Load-order symmetric with
;; the publications above — whichever of ssr / adapter loads last, the durable
;; slot plus the install replay converge on the same armed state.
(rf.late-bind/set-fn! :ssr/current-hiccup-emitter render-to-string)
