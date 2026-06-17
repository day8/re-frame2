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

    | head           | meaning                                     |
    |----------------|---------------------------------------------|
    | :<>            | React Fragment: emit children only          |
    | :>             | React component interop                     |
    | :r>            | raw React.createElement passthrough         |
    | :f>            | function-component dispatch                 |
    | DOM tag        | parse-tag + DOM element                     |
    | reagent class  | Form-3: render :reagent-render via fn path  |
    | user fn        | invoke fn-with-args (Form-1/Form-2); recurse|

  React-component heads (`:>`, `:r>`, `:f>`) emit a placeholder HTML
  comment (`<!--reagent-react-component-->`) and don't walk into the
  component. This matches `react-dom/server.renderToStaticMarkup`'s
  behaviour for non-static content (per §8.1 — `react-dom/server`
  treats foreign-component subtrees as opaque under static markup).
  Stage 4 picks the user-fn-call path for plain-fn heads to match
  stock Reagent's `render-to-static-markup` behaviour and preserve
  Dash8/rf8 HTML-export compatibility.

  COMPONENT-SHAPE PARITY (rf2-o3hqr). The static path must render the
  same Form-1/Form-2/Form-3 view shapes the live renderer does — a
  view that renders correctly in the browser must render to HTML, not
  throw. The previous user-fn path called the head once and recursed
  on the result, which is correct ONLY for Form-1 (the body IS hiccup).
  A Form-2 head `(fn [x] (fn [x] [:li x]))` returns its inner render
  closure; recursing on that bare fn hit `emit-element` and threw
  `:rf.error/static-markup-bad-element`. The fix mirrors the live
  `reagent2.impl.component/wrap-render` Form-1/Form-2 detection: invoke
  the head; if it returns a fn, that is the Form-2 inner render closure —
  call it with the SAME args and recurse on its hiccup. Form-3
  (`create-class`) heads are React classes carrying their user
  `:reagent-render` fn under `.-cljsReagentRender`; we render that fn
  through the same Form-1/Form-2 path. A generic React class (no
  reagent render fn) stays opaque — emitting the placeholder comment,
  matching `:>`.

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
            [reagent2.impl.component :as component]
            [reagent2.impl.diag :as diag]
            [reagent2.impl.template :as template]))

;; ---------------------------------------------------------------------------
;; HTML escaping
;;
;; Lifted from re-frame.ssr.html-helpers/escape-html. The text-content
;; path escapes the full 5-char set (`& < > " '`); the attribute path
;; escapes only `&` and `"` (double-quoted values). React's
;; renderToStaticMarkup uses the same split. Per §8.2.
;; ---------------------------------------------------------------------------

(defn- escape-text
  "Full text-node HTML escape: `&`, `<`, `>`, `\"`, `'`. Byte-equal to
  re-frame.ssr.html-helpers/escape-html and React 19's
  escapeTextForBrowser (the 5-char text-content set)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

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
;;
;; **Lockstep**: the void-tag set above (in template.cljs) duplicates
;; `re-frame.ssr.emit/void-elements` (keyword form vs string form,
;; same membership). If HTML5 extends the void-element list, both
;; copies update. Boolean-attrs is local to this artefact (re-frame.ssr
;; takes value-driven booleans through `html-helpers/attr-string`
;; instead of carrying its own name set).
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
;;   :tab-index       → "tabindex" (kebab→camel via cached-prop-name,
;;                                  then React lowercases for HTML5)
;;   :data-foo        → "data-foo"  (data-* untouched)
;;   :aria-label      → "aria-label" (aria-* untouched)
;;
;; CASE-SENSITIVE SVG ATTRIBUTES (rf2-ygknv finding 3). The previous
;; rule blanket-lowercased every camelCase token, turning case-SENSITIVE
;; SVG attribute names into broken markup:
;;
;;   :viewBox     → "viewbox"     (WRONG — must stay "viewBox")
;;   :clipPath    → "clippath"    (WRONG — React emits "clip-path")
;;   :strokeWidth → "strokewidth" (WRONG — React emits "stroke-width")
;;
;; `react-dom/server.renderToStaticMarkup` does NOT lowercase blindly —
;; it consults a fixed attribute-name table. Three observed shapes for a
;; camelCase hiccup name (the React-camelCase form `cached-prop-name`
;; produces):
;;
;;   1. PRESERVED camelCase — `viewBox`, `preserveAspectRatio`,
;;      `gradientUnits`, `stdDeviation`, `colSpan`, `readOnly`, …
;;   2. DASHERIZED — `clipPath`→`clip-path`, `strokeWidth`→`stroke-width`,
;;      `fillOpacity`→`fill-opacity`, `stopColor`→`stop-color`,
;;      `httpEquiv`→`http-equiv`, `acceptCharset`→`accept-charset`, …
;;   3. LOWERCASED — plain HTML camelCase like `tabIndex`→`tabindex`.
;;
;; `react-attr-name-overrides` below pins shapes 1 + 2 exactly to
;; React 19's emitted output (extracted from `renderToStaticMarkup` —
;; see `parity_cljs_test.cljs` for the round-trip pin). Any camelCase
;; token NOT in the map falls through to the lowercase rule (shape 3),
;; which is correct for the residual HTML attributes. This restores
;; SVG parity with the React-element path (React itself remaps SVG
;; names at the DOM layer, so the live path was already correct; only
;; this pure serializer diverged).
;; ---------------------------------------------------------------------------

(def ^:private react-attr-name-overrides
  "React's canonical HTML/SVG output name for each camelCase hiccup
  attribute whose serialized form is NOT a plain `lower-case` of the
  name. Keyed on the React-camelCase token `cached-prop-name` produces;
  value is the exact string `react-dom/server.renderToStaticMarkup`
  emits (React 19). Covers the case-sensitive SVG attributes (`viewBox`,
  `preserveAspectRatio`, …), the dasherized SVG presentation attributes
  (`clipPath`→`clip-path`, `strokeWidth`→`stroke-width`, …), and the
  hyphenated HTML specials (`httpEquiv`→`http-equiv`). Pinned against
  the live React reference in `parity_cljs_test.cljs` per §8.7."
  {"accentHeight" "accent-height"
   "acceptCharset" "accept-charset"
   "accessKey" "accessKey"
   "alignmentBaseline" "alignment-baseline"
   "allowReorder" "allowReorder"
   "arabicForm" "arabic-form"
   "attributeName" "attributeName"
   "attributeType" "attributeType"
   "autoComplete" "autoComplete"
   "autoPlay" "autoPlay"
   "autoReverse" "autoReverse"
   "baseFrequency" "baseFrequency"
   "baseProfile" "baseProfile"
   "baselineShift" "baseline-shift"
   "calcMode" "calcMode"
   "capHeight" "cap-height"
   "cellPadding" "cellPadding"
   "cellSpacing" "cellSpacing"
   "clipPath" "clip-path"
   "clipPathUnits" "clipPathUnits"
   "clipRule" "clip-rule"
   "colSpan" "colSpan"
   "colorInterpolation" "color-interpolation"
   "colorInterpolationFilters" "color-interpolation-filters"
   "colorProfile" "color-profile"
   "colorRendering" "color-rendering"
   "contentEditable" "contentEditable"
   "contentScriptType" "contentScriptType"
   "contentStyleType" "contentStyleType"
   "dateTime" "dateTime"
   "diffuseConstant" "diffuseConstant"
   "dominantBaseline" "dominant-baseline"
   "edgeMode" "edgeMode"
   "enableBackground" "enable-background"
   "encType" "encType"
   "externalResourcesRequired" "externalResourcesRequired"
   "fillOpacity" "fill-opacity"
   "fillRule" "fill-rule"
   "filterRes" "filterRes"
   "filterUnits" "filterUnits"
   "floodColor" "flood-color"
   "floodOpacity" "flood-opacity"
   "fontFamily" "font-family"
   "fontSize" "font-size"
   "fontSizeAdjust" "font-size-adjust"
   "fontStretch" "font-stretch"
   "fontStyle" "font-style"
   "fontVariant" "font-variant"
   "fontWeight" "font-weight"
   "formAction" "formAction"
   "formMethod" "formMethod"
   "glyphName" "glyph-name"
   "glyphOrientationHorizontal" "glyph-orientation-horizontal"
   "glyphOrientationVertical" "glyph-orientation-vertical"
   "glyphRef" "glyphRef"
   "gradientTransform" "gradientTransform"
   "gradientUnits" "gradientUnits"
   "horizAdvX" "horiz-adv-x"
   "horizOriginX" "horiz-origin-x"
   "httpEquiv" "http-equiv"
   "imageRendering" "image-rendering"
   "kernelMatrix" "kernelMatrix"
   "kernelUnitLength" "kernelUnitLength"
   "keyPoints" "keyPoints"
   "keySplines" "keySplines"
   "keyTimes" "keyTimes"
   "lengthAdjust" "lengthAdjust"
   "letterSpacing" "letter-spacing"
   "lightingColor" "lighting-color"
   "limitingConeAngle" "limitingConeAngle"
   "markerEnd" "marker-end"
   "markerHeight" "markerHeight"
   "markerMid" "marker-mid"
   "markerStart" "marker-start"
   "markerUnits" "markerUnits"
   "markerWidth" "markerWidth"
   "maskContentUnits" "maskContentUnits"
   "maskUnits" "maskUnits"
   "maxLength" "maxLength"
   "minLength" "minLength"
   "noValidate" "noValidate"
   "numOctaves" "numOctaves"
   "overlinePosition" "overline-position"
   "overlineThickness" "overline-thickness"
   "paintOrder" "paint-order"
   "pathLength" "pathLength"
   "patternContentUnits" "patternContentUnits"
   "patternTransform" "patternTransform"
   "patternUnits" "patternUnits"
   "pointerEvents" "pointer-events"
   "pointsAtX" "pointsAtX"
   "pointsAtY" "pointsAtY"
   "pointsAtZ" "pointsAtZ"
   "preserveAlpha" "preserveAlpha"
   "preserveAspectRatio" "preserveAspectRatio"
   "primitiveUnits" "primitiveUnits"
   "readOnly" "readOnly"
   "refX" "refX"
   "refY" "refY"
   "renderingIntent" "rendering-intent"
   "repeatCount" "repeatCount"
   "repeatDur" "repeatDur"
   "requiredExtensions" "requiredExtensions"
   "requiredFeatures" "requiredFeatures"
   "shapeRendering" "shape-rendering"
   "specularConstant" "specularConstant"
   "specularExponent" "specularExponent"
   "spellCheck" "spellCheck"
   "spreadMethod" "spreadMethod"
   "srcSet" "srcSet"
   "startOffset" "startOffset"
   "stdDeviation" "stdDeviation"
   "stitchTiles" "stitchTiles"
   "stopColor" "stop-color"
   "stopOpacity" "stop-opacity"
   "strikethroughPosition" "strikethrough-position"
   "strikethroughThickness" "strikethrough-thickness"
   "strokeDasharray" "stroke-dasharray"
   "strokeDashoffset" "stroke-dashoffset"
   "strokeLinecap" "stroke-linecap"
   "strokeLinejoin" "stroke-linejoin"
   "strokeMiterlimit" "stroke-miterlimit"
   "strokeOpacity" "stroke-opacity"
   "strokeWidth" "stroke-width"
   "surfaceScale" "surfaceScale"
   "systemLanguage" "systemLanguage"
   "tableValues" "tableValues"
   "targetX" "targetX"
   "targetY" "targetY"
   "textAnchor" "text-anchor"
   "textDecoration" "text-decoration"
   "textLength" "textLength"
   "textRendering" "text-rendering"
   "underlinePosition" "underline-position"
   "underlineThickness" "underline-thickness"
   "unicodeBidi" "unicode-bidi"
   "unicodeRange" "unicode-range"
   "unitsPerEm" "units-per-em"
   "useMap" "useMap"
   "vAlphabetic" "v-alphabetic"
   "vHanging" "v-hanging"
   "vIdeographic" "v-ideographic"
   "vMathematical" "v-mathematical"
   "vectorEffect" "vector-effect"
   "vertAdvY" "vert-adv-y"
   "vertOriginX" "vert-origin-x"
   "vertOriginY" "vert-origin-y"
   "viewBox" "viewBox"
   "viewTarget" "viewTarget"
   "wordSpacing" "word-spacing"
   "writingMode" "writing-mode"
   "xChannelSelector" "xChannelSelector"
   "xHeight" "x-height"
   "yChannelSelector" "yChannelSelector"
   "zoomAndPan" "zoomAndPan"})

(defn- attr-name
  "Hiccup keyword/string → HTML attribute name string. Honours React
  aliases (`:class` → \"class\", `:for` → \"for\"). For camelCase
  tokens, consults `react-attr-name-overrides` to emit React 19's exact
  output (`viewBox` preserved, `clipPath`→`clip-path`, …); any camelCase
  token not in the table lowercases for HTML5 conformance
  (`tabIndex` → `tabindex`). Kebab-case `data-*` / `aria-*` pass through
  verbatim. Per rf2-ygknv finding 3."
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
      (or (get react-attr-name-overrides n)
          (if (or (str/starts-with? n "data-")
                  (str/starts-with? n "aria-")
                  ;; All-lowercase already.
                  (= n (str/lower-case n)))
            n
            (str/lower-case n))))))

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

;; ---------------------------------------------------------------------------
;; Inline-style serialisation (rf2-9nyg6)
;;
;; `react-dom/server.renderToStaticMarkup` serialises a `style` object via
;; its internal `pushStyleAttribute`. Two behaviours of that path are NOT
;; captured by a naive `(str k ":" v)` join, and the divergence produces
;; invalid CSS:
;;
;;   1. NUMERIC px SUFFIX. React appends `px` to a *numeric* value unless
;;      the value is `0` or the property is in its `unitlessNumber` set
;;      (`flex`, `z-index`, `opacity`, `line-height`, `font-weight`,
;;      `order`, …). So `{:width 10}` → `width:10px`, but `{:flex-grow 1}`
;;      → `flex-grow:1` (no `px`). A bare `width:10` is invalid/ignored CSS.
;;
;;   2. NIL / BOOLEAN / EMPTY ENTRIES OMITTED. React skips an entry whose
;;      value is `null`, a boolean, or `""`. The naive join instead emits
;;      an empty declaration `color:` for `{:color nil}`.
;;
;; The unitless-property set below mirrors React 19.2.0's `unitlessNumber`
;; Set *exactly* (vendor-prefixed entries included). React keys that set on
;; the *camelCase* style name (`flexGrow`, `zIndex`, `MozBoxFlex`, …) — the
;; same form the React-element path hands React via `kv-conv`/
;; `cached-prop-name`. So we camelCase the hiccup key through
;; `template/cached-prop-name` (the in-artefact transform that path uses)
;; before the unitless lookup, then convert that camelCase name to the
;; kebab CSS name with React's own rule (`([A-Z]) → -$1`, lower-case,
;; `^ms- → -ms-`). Custom properties (`--foo`) are passed through verbatim
;; with no `px` logic, matching React.
;; ---------------------------------------------------------------------------

(def ^:private unitless-style-props
  "camelCase style-property names that take a bare numeric value (no
  `px` suffix). Mirrors React 19.2.0's `unitlessNumber` Set verbatim,
  including vendor-prefixed entries."
  #{"animationIterationCount" "aspectRatio" "borderImageOutset"
    "borderImageSlice" "borderImageWidth" "boxFlex" "boxFlexGroup"
    "boxOrdinalGroup" "columnCount" "columns" "flex" "flexGrow"
    "flexPositive" "flexShrink" "flexNegative" "flexOrder" "gridArea"
    "gridRow" "gridRowEnd" "gridRowSpan" "gridRowStart" "gridColumn"
    "gridColumnEnd" "gridColumnSpan" "gridColumnStart" "fontWeight"
    "lineClamp" "lineHeight" "opacity" "order" "orphans" "scale"
    "tabSize" "widows" "zIndex" "zoom" "fillOpacity" "floodOpacity"
    "stopOpacity" "strokeDasharray" "strokeDashoffset" "strokeMiterlimit"
    "strokeOpacity" "strokeWidth" "MozAnimationIterationCount" "MozBoxFlex"
    "MozBoxFlexGroup" "MozLineClamp" "msAnimationIterationCount" "msFlex"
    "msZoom" "msFlexGrow" "msFlexNegative" "msFlexOrder" "msFlexPositive"
    "msFlexShrink" "msGridColumn" "msGridColumnSpan" "msGridRow"
    "msGridRowSpan" "WebkitAnimationIterationCount" "WebkitBoxFlex"
    ;; NOTE: `WebKitBoxFlexGroup` keeps React 19.2.0's capital-K typo
    ;; VERBATIM (rf2-4dlxga). React's `unitlessNumber` Set really does
    ;; spell it with a capital K, while the camelCase prop token this
    ;; serializer (and React itself) computes is `WebkitBoxFlexGroup`
    ;; (lowercase k). So the lookup MISSES in React too — React emits
    ;; `-webkit-box-flex-group:2px`, and mirroring the typo is what
    ;; preserves byte-parity. Lowercasing the k here would make us emit
    ;; the unitless `:2`, diverging from React. Confirmed empirically
    ;; against react-dom/server 19.2.0; see parity-style-webkit-box-flex-group.
    "WebKitBoxFlexGroup" "WebkitBoxOrdinalGroup" "WebkitColumnCount"
    "WebkitColumns" "WebkitFlex" "WebkitFlexGrow" "WebkitFlexPositive"
    "WebkitFlexShrink" "WebkitLineClamp"})

(def ^:private camel-boundary
  "Match an upper-case char to insert a `-` before — React's
  `uppercasePattern` (`/([A-Z])/g`)."
  (js/RegExp. "([A-Z])" "g"))

(defn- camel->css-name
  "camelCase style-property name → kebab CSS name, matching React's
  `pushStyleAttribute` conversion: insert `-` before each upper-case
  char, lower-case, then `^ms- → -ms-` (so `msFlex` → `-ms-flex`)."
  [camel]
  (-> camel
      (str/replace camel-boundary "-$1")
      (str/lower-case)
      (str/replace #"^ms-" "-ms-")))

(defn- style-value->str
  "Serialise one style value to its CSS string, applying React's `px`
  rule for numbers. `camel` is the camelCase property name (the
  unitless-lookup key). Numeric values get a `px` suffix unless the
  value is `0` or the property is unitless."
  [camel v]
  (if (number? v)
    (if (or (zero? v) (contains? unitless-style-props camel))
      (str v)
      (str v "px"))
    (named->str v)))

(defn- style-string
  "Serialise a style map to an HTML inline-style string, matching
  `react-dom/server.renderToStaticMarkup`'s `pushStyleAttribute`:
  numeric values of non-unitless properties get a `px` suffix; entries
  whose value is nil / boolean / empty-string are omitted; custom
  properties (`--foo`) pass through verbatim."
  [m]
  (->> m
       (keep (fn [[k v]]
               (when (and (some? v)
                          (not (boolean? v))
                          (not= "" v))
                 (let [raw (named->str k)]
                   (if (str/starts-with? raw "--")
                     ;; CSS custom property: name + value verbatim, no px.
                     (str raw ":" (named->str v))
                     (let [camel    (template/cached-prop-name k)
                           css-name (camel->css-name camel)]
                       (str css-name ":" (style-value->str camel v))))))))
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

;; ---------------------------------------------------------------------------
;; React-internal / non-HTML attribute filtering (rf2-dwds9 HIGH)
;;
;; React event-handler props (`onClick`, `onChange`, …) MUST NOT appear
;; in the static HTML output:
;;
;;   - They are not HTML attributes — `react-dom/server.renderToStaticMarkup`
;;     omits them entirely.
;;   - Serialising `{:on-click handler-string}` as `onclick="..."` would
;;     turn a server-side render of user-supplied data into an XSS
;;     vector (an `:on-click` whose value is a JavaScript expression
;;     would execute on every click of the rendered HTML).
;;   - Function-valued props (event handlers, ref callbacks) coerced
;;     through `(str f)` leak `function (...) { ... }` source into the
;;     attribute, which is both useless and potentially sensitive.
;;
;; Strategy: drop `on*`-prefixed names AND drop any fn-valued prop value
;; regardless of name. The two checks are complementary — `on*` covers
;; the case where the value is a string template (XSS shape); the
;; fn-value check covers any non-event prop that happens to carry a
;; callback (`:on-frame`, `:loader`, `:ref-fn`, …).
;;
;; Also covered: React-internal props that have no HTML meaning
;; (`:key`, `:ref`, `:dangerouslySetInnerHTML` — handled by caller).
;; ---------------------------------------------------------------------------

(defn- ^boolean event-handler-prop?
  "True when `k`'s name looks like a React event-handler prop
  (`onClick`, `onChange`, `on-click`, …). Matches both kebab- and
  camelCase forms before `attr-name` lowercasing."
  [k]
  (let [n (cond
            (keyword? k) (name k)
            (symbol? k)  (name k)
            (string? k)  k
            :else        nil)]
    (and (some? n)
         (>= (count n) 3)
         ;; Match `on-` (kebab) and `on[A-Z]` (camel).
         (or (and (str/starts-with? n "on-") (> (count n) 3))
             (and (str/starts-with? n "on")
                  (let [ch (.charAt n 2)]
                    (and (>= (.charCodeAt ch 0) 65)   ; 'A'
                         (<= (.charCodeAt ch 0) 90)))))))) ; 'Z'

(defn- emit-attr
  "Emit one [k v] attribute pair to the StringBuilder. Skips nil and
  false values; emits boolean-attribute short form for true.

  Drops React-internal / non-HTML props (rf2-dwds9 HIGH):
    - `:key` / `:ref` — React-internal.
    - `:dangerouslySetInnerHTML` — handled by caller (children path).
    - Event-handler props (`on*`) — not HTML attrs; emitting them as
      `onclick=\"...\"` would be an XSS surface and diverges from
      `react-dom/server.renderToStaticMarkup`.
    - Fn-valued props of any name — `(str f)` leaks `function () {…}`
      source into the attribute. React-DOM-server elides these too."
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

    ;; rf2-dwds9 HIGH: drop React event-handler props from static
    ;; markup. Matches `react-dom/server.renderToStaticMarkup`'s
    ;; behaviour and closes the XSS surface where a stringy `on*` value
    ;; would be emitted as an inline event handler.
    (event-handler-prop? k) nil

    ;; rf2-dwds9 HIGH: drop fn-valued props (event handlers, ref
    ;; callbacks, custom callback props). Stringifying a fn would leak
    ;; source text into the attribute and serves no HTML purpose.
    (fn? v) nil

    (= :style k)
    (when (and (map? v) (seq v))
      (.append sb " style=\"")
      (.append sb (escape-attr (style-string v)))
      (.append sb "\""))

    (true? v)
    (let [n      (attr-name k)
          ;; HTML5 boolean attributes are lowercase (`readonly`,
          ;; `disabled`, …); `attr-name` may preserve React's casing
          ;; (`readOnly`), so the boolean-attr membership test + the
          ;; emitted short-form use the lowercased name (rf2-ygknv
          ;; finding 3 follow-on: the case-preserving override must not
          ;; defeat the lowercase boolean-attr set).
          bool-n (str/lower-case n)]
      (when (contains? boolean-attrs bool-n)
        (.append sb " ")
        (.append sb bool-n)))

    :else
    (let [n      (attr-name k)
          bool-n (str/lower-case n)]
      (if (contains? boolean-attrs bool-n)
        ;; Boolean attribute with non-true truthy value: emit the
        ;; (lowercase HTML5) name.
        (do (.append sb " ")
            (.append sb bool-n))
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
  (let [id              (.-id parsed)
        shorthand-class (.-className parsed)
        user-class      (or (:class user-attrs) (:className user-attrs))
        merged-class    (template/class-names shorthand-class user-class)
        base            (cond-> (or user-attrs {})
                          ;; Drop :className duplicate; we collapse into :class.
                          (contains? user-attrs :className) (dissoc :className))
        with-id         (if (and id (not (:id base)))
                          (assoc base :id id)
                          base)]
    (cond-> with-id
      merged-class (assoc :class merged-class))))

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

(defn- emit-render-fn
  "Invoke a Form-1/Form-2 render fn `f` with `args` and emit the
  resulting hiccup. Mirrors the live `reagent2.impl.component/wrap-render`
  Form-1/Form-2 detection (rf2-o3hqr):

    - Form-1: `f` returns hiccup directly → recurse on it.
    - Form-2: `f` returns a fn (the inner render closure produced by the
      one-shot setup call) → call that inner fn with the SAME args and
      recurse on its hiccup.

  A static render has no instance, so there is no inner-fn cache (the
  live path caches on `.-cljsRenderFn`); each static render re-runs the
  setup. That is correct for static markup — output is a function of the
  args only."
  [^StringBuffer sb f args]
  (let [out (apply f args)]
    (if (fn? out)
      ;; Form-2: `out` is the inner render closure. Recall with the same
      ;; args (matches wrap-render's `(apply inner args)`).
      (emit-element sb (apply out args))
      (emit-element sb out))))

(defn- emit-user-fn
  "Emit a plain user-fn head (Form-1 or Form-2). Per §8.1 — Stage 4
  follows stock Reagent's function-call path so apps using
  `render-to-static-markup` for HTML export get the same shape. Form-2
  detection (rf2-o3hqr) is delegated to `emit-render-fn`."
  [^StringBuffer sb f argv]
  (emit-render-fn sb f (rest argv)))

(defn- emit-reagent-class
  "Emit a Form-3 (`create-class`) head (rf2-o3hqr). A reagent-slim class
  carries its user `:reagent-render` fn under `.-cljsReagentRender` (set
  by `create-class*`). Static markup has no React lifecycle, so we render
  the `:reagent-render` fn directly through the Form-1/Form-2 path — the
  cap's lifecycle methods (`:component-did-mount`, error boundaries, …)
  have no static-HTML meaning and are skipped, matching `react-dom/
  server.renderToStaticMarkup` (lifecycle hooks don't fire under static
  markup). Reagent's `:reagent-render` is itself a Form-1/Form-2 render
  fn, so the same setup/inner-fn detection applies."
  [^StringBuffer sb klass argv]
  (emit-render-fn sb (.-cljsReagentRender ^js klass) (rest argv)))

(defn- emit-vector
  [^StringBuffer sb argv]
  (when (zero? (count argv))
    ;; Canonical thrown-error shape per Spec 009 §The thrown-error shape
    ;; (rf2-vvixub) replicated INLINE — reagent-slim is bundle-isolated and
    ;; MUST NOT `:require` re-frame.* (the slim bundle-isolation gate), so
    ;; the central `re-frame.error` builder is unavailable here. Human
    ;; message + trailing [:rf.error/<id>] token; `:rf.error/id` the sole
    ;; machine discriminator.
    (throw (ex-info (str "Hiccup vector cannot be empty; give the vector a "
                         "head tag (e.g. [:div …]). "
                         "[:rf.error/static-markup-empty-vector]")
                    {:rf.error/id :rf.error/static-markup-empty-vector
                     :where       'reagent2.dom.server/render-to-static-markup
                     :reason      (str "Hiccup vector cannot be empty; give the "
                                       "vector a head tag (e.g. [:div …]).")
                     :recovery    :supply-a-head-tag})))
  (let [head (nth argv 0 nil)]
    (cond
      (= :<> head)                (emit-fragment sb argv)
      (or (= :> head)
          (= :r> head)
          (= :f> head))           (react-component-comment sb)
      (or (keyword? head)
          (symbol? head)
          (string? head))         (emit-dom-vector sb argv)
      ;; Form-3 head: a reagent-slim class made by `create-class*`. It
      ;; is a JS fn (so `fn?` would be true), but calling it would invoke
      ;; the class constructor, not render — so detect it BEFORE the
      ;; plain-fn path and render its `:reagent-render` fn (rf2-o3hqr).
      (component/reagent-class? head) (emit-reagent-class sb head argv)
      ;; A generic React class (not made by `create-class*`) has no
      ;; CLJS render fn to walk — treat it as opaque foreign content,
      ;; same as `:>` (rf2-o3hqr).
      (component/react-class? head)   (react-component-comment sb)
      ;; Plain user fn head — Form-1 or Form-2 (rf2-o3hqr).
      (fn? head)                  (emit-user-fn sb head argv)
      :else
      ;; Canonical shape replicated inline (bundle isolation — see above).
      ;; EP-0015 (rf2-uwqale): summarise head + argv — a static-markup
      ;; throw is captured by SSR/static-export error handlers and host
      ;; logs before the projector can classify it, and argv carries
      ;; app-owned hiccup children that can hold sensitive/large values.
      (throw (ex-info (str "Hiccup head " (pr-str (diag/value-summary head))
                           " is not a valid element head; use a keyword "
                           "(DOM tag or :>/:<>/:r>/:f>), a Reagent component "
                           "class, a React component class, or a fn. "
                           "[:rf.error/static-markup-bad-tag]")
                      {:rf.error/id   :rf.error/static-markup-bad-tag
                       :where         'reagent2.dom.server/render-to-static-markup
                       :reason        (str "Hiccup head must be a keyword (DOM tag "
                                           "or :>/:<>/:r>/:f>), a Reagent component "
                                           "class, a React component class, or a fn.")
                       :recovery      :supply-a-valid-hiccup-head
                       :tag/summary   (diag/value-summary head)
                       :argv/summary  (diag/value-summary argv)})))))

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
    ;; Canonical shape replicated inline (bundle isolation — see above).
    ;; EP-0015 (rf2-uwqale): summarise the offending child — never the
    ;; raw value. The bad-element throw is captured off-box (SSR error
    ;; handlers, host logs) and the child can carry app-owned data.
    (throw (ex-info (str "Cannot render " (pr-str (diag/value-summary x))
                         " as static markup; a hiccup child must be a string, "
                         "number, keyword, symbol, hiccup vector, or a "
                         "sequential of those. [:rf.error/static-markup-bad-element]")
                    {:rf.error/id  :rf.error/static-markup-bad-element
                     :where        'reagent2.dom.server/render-to-static-markup
                     :reason       (str "A hiccup child must be a string, number, "
                                        "keyword, symbol, hiccup vector, or a "
                                        "sequential of those.")
                     :recovery     :supply-a-renderable-child
                     :got/summary  (diag/value-summary x)}))))

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
