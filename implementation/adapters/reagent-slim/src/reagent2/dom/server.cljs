(ns reagent2.dom.server
  "Pure-CLJS hiccup → HTML5 static-markup serializer for the
  day8/reagent-slim artefact (rf2-6hyy Stage 4-E).

  Per IMPL-SPEC §8 + Stage 2 §2.5 (S3-005). The whole point of this
  namespace is to ship `render-to-static-markup` WITHOUT a runtime
  dependency on `react-dom/server`. That's the biggest single bundle
  win for SSR-using apps (Stage 2 §3.5 estimated ~22-27 KB gzip).

  Public surface:

    render-to-static-markup [hiccup]   — hiccup → HTML string

  Pipeline (per §8.1):

      hiccup
        │
        ▼
      emit-element
        │
        ├── nil                      → nothing
        ├── string                   → escape-text
        ├── number                   → str
        ├── boolean                  → nothing (matches React)
        ├── keyword/symbol           → escape-text on (name x)
        ├── vector                   → emit-vector
        └── seq                      → recurse over each child

  emit-vector dispatches on the head:

    | head      | meaning                                |
    |-----------|----------------------------------------|
    | :<>       | React Fragment: emit children only     |
    | :>        | React component interop                |
    | :r>       | raw React.createElement passthrough    |
    | :f>       | function-component dispatch            |
    | DOM tag   | parse-tag + DOM element                |
    | user fn   | invoke fn-with-args; recurse on result |

  React-component heads (`:>`, `:r>`, `:f>`) emit a placeholder HTML
  comment (`<!--reagent-react-component-->`) and don't walk into the
  component. This matches `react-dom/server.renderToStaticMarkup`'s
  behaviour for non-static content (per §8.1 — `react-dom/server`
  treats foreign-component subtrees as opaque under static markup).
  Stage 4 picks the user-fn-call path for plain-fn heads to match
  stock Reagent's `render-to-static-markup` behaviour and preserve
  Dash8/rf8 HTML-export compatibility.

  HTML escaping (per §8.2): lifted by intent (not require — bundle
  isolation forbids `:require` between artefacts; per rf2-6phn the
  duplication is accepted because HTML5's escape rules are frozen)
  from `re-frame.ssr/escape-html`.

  Boolean attributes + void tags (per §8.4): lifted similarly from
  `re-frame.ssr/void-elements`. The HTML5 void-tag list is fixed.

  This namespace ships ~150-200 LoC per the §8.6 budget. No
  `react-dom/server` import. No `clj->js`. No React import.
  Production-elision-friendly: no `goog.DEBUG` branches; nothing to
  elide."
  (:require [clojure.string :as str]
            [goog.string :refer [StringBuffer]]
            [reagent2.impl.template :as template]))

;; ---------------------------------------------------------------------------
;; HTML escaping
;;
;; Lifted from re-frame.ssr/escape-html (ssr.cljc:51-57). The text-content
;; path doesn't escape `'` or `"`; the attribute path does. React's
;; renderToStaticMarkup uses the same split. Per §8.2.
;; ---------------------------------------------------------------------------

(defn- escape-text
  "Escape text content per HTML5: `&`, `<`, `>`. Quotes are not
  escaped in text-content positions. Matches react-dom/server."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- escape-attr
  "Escape attribute content per HTML5 with double-quoted values:
  `&` and `\"` only. Matches react-dom/server."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")))

;; ---------------------------------------------------------------------------
;; Void tags + boolean attributes (HTML5)
;;
;; Void-tag set is `reagent2.impl.template/void-tags` — shared with the
;; React-element path (both ship in the same artefact). Boolean-attr
;; set stays local; HTML5's list is fixed (no maintenance burden) and
;; SSR is the only consumer.
;;
;; Per §8.4 + rf2-6phn: bundle isolation forbids `:require` of the SSR
;; artefact (re-frame.ssr lives in day8/re-frame2-ssr), so we don't
;; share with that — only with the in-artefact template path.
;; ---------------------------------------------------------------------------

(def ^:private boolean-attrs
  "HTML5 boolean attributes (post-lowercase form): emit name only
  when truthy; omit when falsy. The set is the union of stock-
  Reagent and React's lists."
  #{"allowfullscreen" "async" "autofocus" "autoplay" "checked"
    "controls" "default" "defer" "disabled" "formnovalidate" "hidden"
    "loop" "multiple" "muted" "novalidate" "open" "playsinline"
    "readonly" "required" "reversed" "selected" "itemscope"})

;; ---------------------------------------------------------------------------
;; Attribute name conversion
;;
;; Hiccup convention is kebab-case with React aliases (`:class`,
;; `:for`). The HTML output side wants HTML-attribute names. We do
;; the minimum compatible mapping:
;;
;;   :class           → "class"
;;   :className       → "class"
;;   :for             → "for"
;;   :htmlFor         → "for"
;;   :tab-index       → "tabIndex"  (kebab→camel for React-style names)
;;   :data-foo        → "data-foo"  (data-* untouched)
;;   :aria-label      → "aria-label" (aria-* untouched)
;;
;; React-shape camelCased names (`tabIndex`, `colSpan`) reach the HTML
;; output already in their React form. `react-dom/server` lowercases
;; some (`tabindex`, `colspan`) but preserves SVG attrs (`viewBox`,
;; `clipPath`). For parity simplicity the rewrite emits the React name
;; as-is for camelCase tokens — the parity test allow-lists this in
;; the known-difference set per §8.7.
;; ---------------------------------------------------------------------------

(defn- attr-name
  "Hiccup keyword/string → HTML attribute name string. Honours React
  aliases (`:class` → \"class\", `:for` → \"for\"). Lowercases
  camelCased tokens for HTML5 conformance (`tabIndex` → `tabindex`,
  `colSpan` → `colspan`); preserves kebab-case `data-*` / `aria-*`
  verbatim."
  [k]
  (let [n (cond
            (keyword? k) (template/cached-prop-name k)
            (symbol? k)  (name k)
            :else        (str k))]
    (case n
      "className" "class"
      "htmlFor"   "for"
      ;; data-*/aria-* stay verbatim — cached-prop-name doesn't
      ;; camelCase them. Other non-camel tokens stay verbatim too.
      (if (or (str/starts-with? n "data-")
              (str/starts-with? n "aria-")
              ;; All-lowercase already.
              (= n (str/lower-case n)))
        n
        (str/lower-case n)))))

;; ---------------------------------------------------------------------------
;; Attribute value serialisation
;;
;; Per §8.3 / S3-005: every prop-name reaching this layer IS by
;; definition an HTML-attribute name (no React-component props here),
;; so keyword/symbol values stringify unconditionally — equivalent
;; to the narrowed convert-prop-value's html-attr-name? branch always
;; firing.
;; ---------------------------------------------------------------------------

(defn- named->str [x]
  (cond
    (nil? x)              nil
    (or (keyword? x)
        (symbol? x))      (name x)
    :else                 (str x)))

(defn- style-string
  "Serialise a style map to an HTML inline-style string."
  [m]
  (->> m
       (map (fn [[k v]] (str (named->str k) ":" (named->str v))))
       (str/join ";")))

(defn- attr-value-string
  "Convert a hiccup-shaped attribute value to its HTML-attribute
  string form. Keyword/symbol → name; collections → space-joined
  (for `:class [\"a\" \"b\"]`); other → str."
  [v]
  (cond
    (or (keyword? v) (symbol? v)) (name v)
    (coll? v) (->> v (keep named->str) (str/join " "))
    :else     (str v)))

(defn- emit-attr
  "Emit one [k v] attribute pair to the StringBuilder. Skips nil and
  false values; emits boolean-attribute short form for true."
  [^StringBuffer sb k v]
  (cond
    (nil? v)   nil
    (false? v) nil

    ;; The :key prop is React-internal — don't emit it.
    (= :key k) nil

    ;; :ref is a React-internal — don't emit it.
    (= :ref k) nil

    ;; :dangerouslySetInnerHTML is handled by the caller (children
    ;; emission); skip here.
    (= :dangerouslySetInnerHTML k) nil

    (= :style k)
    (when (and (map? v) (seq v))
      (.append sb " style=\"")
      (.append sb (escape-attr (style-string v)))
      (.append sb "\""))

    (true? v)
    (let [n (attr-name k)]
      (when (contains? boolean-attrs n)
        (.append sb " ")
        (.append sb n)))

    :else
    (let [n (attr-name k)]
      (if (contains? boolean-attrs n)
        ;; Boolean attribute with non-true truthy value: emit the name.
        (do (.append sb " ")
            (.append sb n))
        (do (.append sb " ")
            (.append sb n)
            (.append sb "=\"")
            (.append sb (escape-attr (attr-value-string v)))
            (.append sb "\""))))))

(defn- emit-attrs
  "Emit a hiccup attribute map. Iteration order = insertion order."
  [^StringBuffer sb attrs]
  (when (seq attrs)
    (doseq [[k v] attrs]
      (emit-attr sb k v))))

;; ---------------------------------------------------------------------------
;; Tag-shorthand merging — :div.foo#bar
;;
;; Per IMPL-SPEC §7.3 the rewrite parses `:div.cls#id` into the
;; HiccupTag. We reuse that parser via reagent2.impl.template/parse-tag
;; so the SSR walker sees the same parsed shape the React walker does.
;; ---------------------------------------------------------------------------

(defn- merge-shorthand
  "Merge HiccupTag's id/class shorthand into the user-supplied attrs.
  User :id wins over shorthand id; shorthand class is prepended to
  user :class (matches stock Reagent's behaviour). Returns nil when
  the result has no entries (suppresses the empty `attrs` doseq).

  Class merging uses `template/class-names` (the same coercion the
  React-element path uses) — both artefacts ship in the same bundle,
  so deduplicating avoids drift on the keyword/coll/string handling."
  [parsed user-attrs]
  (let [id      (.-id parsed)
        s-class (.-className parsed)
        u-class-raw (or (:class user-attrs) (:className user-attrs))
        merged  (template/class-names s-class u-class-raw)
        base    (cond-> (or user-attrs {})
                  ;; Drop :className duplicate; we collapse into :class.
                  (contains? user-attrs :className) (dissoc :className))
        with-id (if (and id (not (:id base)))
                  (assoc base :id id)
                  base)]
    (cond-> with-id
      merged (assoc :class merged))))

;; ---------------------------------------------------------------------------
;; Element emission
;; ---------------------------------------------------------------------------

(declare emit-element)

(defn- react-component-comment
  "Placeholder for foreign React-component subtrees — matches
  react-dom/server's opaque treatment under static markup."
  [^StringBuffer sb]
  (.append sb "<!--reagent-react-component-->"))

(defn- emit-children
  [^StringBuffer sb children]
  (doseq [c children]
    (emit-element sb c)))

(defn- emit-dom-vector
  "Emit a hiccup vector whose head is a DOM-tag keyword/symbol/string."
  [^StringBuffer sb argv]
  (let [head      (nth argv 0 nil)
        parsed    (template/parse-tag head)
        tag-str   (.-tag parsed)
        [first-arg has-props children-pos] (template/hiccup-shape argv 1)
        user-attrs (when (and has-props (some? first-arg)) first-arg)
        attrs      (merge-shorthand parsed user-attrs)
        n          (count argv)
        children     (when (< children-pos n)
                       (subvec argv children-pos))
        void?        (contains? template/void-tags tag-str)
        dangerous    (when (map? user-attrs)
                       (:dangerouslySetInnerHTML user-attrs))]
    (.append sb "<")
    (.append sb tag-str)
    (emit-attrs sb attrs)
    (cond
      void?
      ;; HTML5 void elements: bare `<br>`, no closing tag, no children.
      (.append sb ">")

      dangerous
      (do (.append sb ">")
          (when-let [html (:__html dangerous)]
            (.append sb (str html)))
          (.append sb "</")
          (.append sb tag-str)
          (.append sb ">"))

      :else
      (do (.append sb ">")
          (emit-children sb children)
          (.append sb "</")
          (.append sb tag-str)
          (.append sb ">")))))

(defn- emit-fragment
  "Emit a `:<>` fragment — children only, no surrounding markup."
  [^StringBuffer sb argv]
  (let [n         (count argv)
        [_head _has-props children-pos] (template/hiccup-shape argv 1)]
    (when (< children-pos n)
      (emit-children sb (subvec argv children-pos)))))

(defn- emit-user-fn
  "Invoke `f` with the rest of `argv` and recurse on the result. Per
  §8.1 — Stage 4 follows stock Reagent's function-call path so apps
  using `render-to-static-markup` for HTML export get the same shape."
  [^StringBuffer sb f argv]
  (emit-element sb (apply f (rest argv))))

(defn- emit-vector
  [^StringBuffer sb argv]
  (when (zero? (count argv))
    (throw (ex-info ":rf.error/static-markup-empty-vector"
                    {:type :rf.error/static-markup-empty-vector
                     :reason "Hiccup vector cannot be empty."})))
  (let [head (nth argv 0 nil)]
    (cond
      (= :<> head)                (emit-fragment sb argv)
      (or (= :> head)
          (= :r> head)
          (= :f> head))           (react-component-comment sb)
      (or (keyword? head)
          (symbol? head)
          (string? head))         (emit-dom-vector sb argv)
      (fn? head)                  (emit-user-fn sb head argv)
      :else
      (throw (ex-info ":rf.error/static-markup-bad-tag"
                      {:type :rf.error/static-markup-bad-tag
                       :tag  head
                       :argv argv})))))

(defn- emit-element
  "Recursive walker. Per §8.1."
  [^StringBuffer sb x]
  (cond
    (nil? x)        nil
    (boolean? x)    nil                  ;; React drops booleans
    (string? x)     (.append sb (escape-text x))
    (number? x)     (.append sb (str x))
    (or (keyword? x) (symbol? x))
    (.append sb (escape-text (name x)))
    (vector? x)     (emit-vector sb x)
    (sequential? x) (doseq [c x] (emit-element sb c))
    :else
    (throw (ex-info ":rf.error/static-markup-bad-element"
                    {:type :rf.error/static-markup-bad-element
                     :got  x}))))

;; ---------------------------------------------------------------------------
;; Public entry
;; ---------------------------------------------------------------------------

(defn render-to-static-markup
  "Hiccup → HTML5 static-markup string. Pure CLJS — no
  `react-dom/server` dependency. Per IMPL-SPEC §8.

  The output is suitable for HTML email, static-export, or as the
  initial seed for the SSR seam (`day8/re-frame2-ssr`'s
  `re-frame.ssr/render-to-string` is the richer path that includes
  hydration support per Spec 011)."
  [hiccup]
  (let [sb (StringBuffer.)]
    (emit-element sb hiccup)
    (.toString sb)))
