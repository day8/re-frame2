(ns re-frame.ui.rules
  "THE one DOM prop-conversion rule table (Spec 004 rewrite §Template
  grammar; owning contract: jvm-tree-and-conversion-contract.md §The DOM
  conversion table).

  One table, three consumers:
    - the compile-time analyzers/emitters (static prop conversion for both
      hosts — names baked into emitted code);
    - the emitted CLJS runtime paths for DYNAMIC values (`ui/spread`, the
      single sanctioned runtime conversion; dynamic attr/style values);
    - the JVM tree builder (`re-frame.ui.tree`) for semantic attr-value
      normalization.

  ## Provenance ([S1-CONFIRM] discharge)

  The `standard-names` vocabulary mirrors react-dom 19.2.0's
  `possibleStandardNames` (dev source, extracted 2026-07-12; identity
  entries elided — a lookup miss falls through to verbatim, which is the
  same rule React 16+ applies to unrecognized names). `unitless-css`
  mirrors react-dom 19.2.0's `unitlessNumbers` set verbatim, converted to
  CSS kebab space (the space our authors write). `void-tags` and
  `boolean-attrs` were probed against react-dom/server 19.2.0 (renders +
  throw behaviour); see the S1b PR body for the row-by-row outcomes."
  (:require [clojure.string :as str]
            [re-frame.error :as error]))

;; ---------------------------------------------------------------------------
;; Shared vocabulary
;; ---------------------------------------------------------------------------

(def placeholders
  "Closed v1 event-vector placeholder vocabulary (02 §3; scalars only).
  Placeholders splice at TOP-LEVEL positions of a literal event vector at
  dispatch time; they are compiled, so they are recognized in literal
  vectors only."
  #{:rf.ui/value :rf.ui/checked :rf.ui/key})

(def handler-option-keys
  "Closed v1 handler-map option vocabulary (02 §2). `:passive`/`:once`
  are grammar-legal but their emission lands with the committed-handler
  slice (S3) — the S1 emitters reject them with a didactic error."
  #{:event :prevent-default :stop-propagation :capture :passive :once})

;; ---------------------------------------------------------------------------
;; Name conversion — author kebab keyword name -> React prop name
;; ---------------------------------------------------------------------------

(def standard-names
  "react-dom 19.2.0 `possibleStandardNames`, reduced to non-identity
  entries and keyed by both the kebab and the hyphen-collapsed lowercase
  form. Lookup rule (`react-prop-name`): data-*/aria-* verbatim; exact
  key hit; collapsed-key hit; else verbatim (React's unrecognized-name
  pass-through)."
  {"accent-height" "accentHeight"
   "accentheight" "accentHeight"
   "accept-charset" "acceptCharset"
   "acceptcharset" "acceptCharset"
   "accesskey" "accessKey"
   "alignment-baseline" "alignmentBaseline"
   "alignmentbaseline" "alignmentBaseline"
   "allowfullscreen" "allowFullScreen"
   "allowreorder" "allowReorder"
   "arabic-form" "arabicForm"
   "arabicform" "arabicForm"
   "attributename" "attributeName"
   "attributetype" "attributeType"
   "autocapitalize" "autoCapitalize"
   "autocomplete" "autoComplete"
   "autocorrect" "autoCorrect"
   "autofocus" "autoFocus"
   "autoplay" "autoPlay"
   "autoreverse" "autoReverse"
   "autosave" "autoSave"
   "basefrequency" "baseFrequency"
   "baseline-shift" "baselineShift"
   "baselineshift" "baselineShift"
   "baseprofile" "baseProfile"
   "calcmode" "calcMode"
   "cap-height" "capHeight"
   "capheight" "capHeight"
   "cellpadding" "cellPadding"
   "cellspacing" "cellSpacing"
   "charset" "charSet"
   "class" "className"
   "classid" "classID"
   "clip-path" "clipPath"
   "clip-rule" "clipRule"
   "clippath" "clipPath"
   "clippathunits" "clipPathUnits"
   "cliprule" "clipRule"
   "color-interpolation" "colorInterpolation"
   "color-interpolation-filters" "colorInterpolationFilters"
   "color-profile" "colorProfile"
   "color-rendering" "colorRendering"
   "colorinterpolation" "colorInterpolation"
   "colorinterpolationfilters" "colorInterpolationFilters"
   "colorprofile" "colorProfile"
   "colorrendering" "colorRendering"
   "colspan" "colSpan"
   "contenteditable" "contentEditable"
   "contentscripttype" "contentScriptType"
   "contentstyletype" "contentStyleType"
   "contextmenu" "contextMenu"
   "controlslist" "controlsList"
   "crossorigin" "crossOrigin"
   "datetime" "dateTime"
   "defaultchecked" "defaultChecked"
   "defaultvalue" "defaultValue"
   "diffuseconstant" "diffuseConstant"
   "disablepictureinpicture" "disablePictureInPicture"
   "disableremoteplayback" "disableRemotePlayback"
   "dominant-baseline" "dominantBaseline"
   "dominantbaseline" "dominantBaseline"
   "edgemode" "edgeMode"
   "enable-background" "enableBackground"
   "enablebackground" "enableBackground"
   "enctype" "encType"
   "enterkeyhint" "enterKeyHint"
   "externalresourcesrequired" "externalResourcesRequired"
   "fetchpriority" "fetchPriority"
   "fill-opacity" "fillOpacity"
   "fill-rule" "fillRule"
   "fillopacity" "fillOpacity"
   "fillrule" "fillRule"
   "filterres" "filterRes"
   "filterunits" "filterUnits"
   "flood-color" "floodColor"
   "flood-opacity" "floodOpacity"
   "floodcolor" "floodColor"
   "floodopacity" "floodOpacity"
   "font-family" "fontFamily"
   "font-size" "fontSize"
   "font-size-adjust" "fontSizeAdjust"
   "font-stretch" "fontStretch"
   "font-style" "fontStyle"
   "font-variant" "fontVariant"
   "font-weight" "fontWeight"
   "fontfamily" "fontFamily"
   "fontsize" "fontSize"
   "fontsizeadjust" "fontSizeAdjust"
   "fontstretch" "fontStretch"
   "fontstyle" "fontStyle"
   "fontvariant" "fontVariant"
   "fontweight" "fontWeight"
   "for" "htmlFor"
   "formaction" "formAction"
   "formenctype" "formEncType"
   "formmethod" "formMethod"
   "formnovalidate" "formNoValidate"
   "formtarget" "formTarget"
   "frameborder" "frameBorder"
   "glyph-name" "glyphName"
   "glyph-orientation-horizontal" "glyphOrientationHorizontal"
   "glyph-orientation-vertical" "glyphOrientationVertical"
   "glyphname" "glyphName"
   "glyphorientationhorizontal" "glyphOrientationHorizontal"
   "glyphorientationvertical" "glyphOrientationVertical"
   "glyphref" "glyphRef"
   "gradienttransform" "gradientTransform"
   "gradientunits" "gradientUnits"
   "horiz-adv-x" "horizAdvX"
   "horiz-origin-x" "horizOriginX"
   "horizadvx" "horizAdvX"
   "horizoriginx" "horizOriginX"
   "hreflang" "hrefLang"
   "http-equiv" "httpEquiv"
   "httpequiv" "httpEquiv"
   "image-rendering" "imageRendering"
   "imagerendering" "imageRendering"
   "imagesizes" "imageSizes"
   "imagesrcset" "imageSrcSet"
   "inputmode" "inputMode"
   "itemid" "itemID"
   "itemprop" "itemProp"
   "itemref" "itemRef"
   "itemscope" "itemScope"
   "itemtype" "itemType"
   "kernelmatrix" "kernelMatrix"
   "kernelunitlength" "kernelUnitLength"
   "keyparams" "keyParams"
   "keypoints" "keyPoints"
   "keysplines" "keySplines"
   "keytimes" "keyTimes"
   "keytype" "keyType"
   "lengthadjust" "lengthAdjust"
   "letter-spacing" "letterSpacing"
   "letterspacing" "letterSpacing"
   "lighting-color" "lightingColor"
   "lightingcolor" "lightingColor"
   "limitingconeangle" "limitingConeAngle"
   "marginheight" "marginHeight"
   "marginwidth" "marginWidth"
   "marker-end" "markerEnd"
   "marker-mid" "markerMid"
   "marker-start" "markerStart"
   "markerend" "markerEnd"
   "markerheight" "markerHeight"
   "markermid" "markerMid"
   "markerstart" "markerStart"
   "markerunits" "markerUnits"
   "markerwidth" "markerWidth"
   "maskcontentunits" "maskContentUnits"
   "maskunits" "maskUnits"
   "maxlength" "maxLength"
   "mediagroup" "mediaGroup"
   "minlength" "minLength"
   "nomodule" "noModule"
   "novalidate" "noValidate"
   "numoctaves" "numOctaves"
   "overline-position" "overlinePosition"
   "overline-thickness" "overlineThickness"
   "overlineposition" "overlinePosition"
   "overlinethickness" "overlineThickness"
   "paint-order" "paintOrder"
   "paintorder" "paintOrder"
   "panose-1" "panose1"
   "pathlength" "pathLength"
   "patterncontentunits" "patternContentUnits"
   "patterntransform" "patternTransform"
   "patternunits" "patternUnits"
   "playsinline" "playsInline"
   "pointer-events" "pointerEvents"
   "pointerevents" "pointerEvents"
   "pointsatx" "pointsAtX"
   "pointsaty" "pointsAtY"
   "pointsatz" "pointsAtZ"
   "popovertarget" "popoverTarget"
   "popovertargetaction" "popoverTargetAction"
   "preservealpha" "preserveAlpha"
   "preserveaspectratio" "preserveAspectRatio"
   "primitiveunits" "primitiveUnits"
   "radiogroup" "radioGroup"
   "readonly" "readOnly"
   "referrerpolicy" "referrerPolicy"
   "refx" "refX"
   "refy" "refY"
   "rendering-intent" "renderingIntent"
   "renderingintent" "renderingIntent"
   "repeatcount" "repeatCount"
   "repeatdur" "repeatDur"
   "requiredextensions" "requiredExtensions"
   "requiredfeatures" "requiredFeatures"
   "rowspan" "rowSpan"
   "shape-rendering" "shapeRendering"
   "shaperendering" "shapeRendering"
   "specularconstant" "specularConstant"
   "specularexponent" "specularExponent"
   "spellcheck" "spellCheck"
   "spreadmethod" "spreadMethod"
   "srcdoc" "srcDoc"
   "srclang" "srcLang"
   "srcset" "srcSet"
   "startoffset" "startOffset"
   "stddeviation" "stdDeviation"
   "stitchtiles" "stitchTiles"
   "stop-color" "stopColor"
   "stop-opacity" "stopOpacity"
   "stopcolor" "stopColor"
   "stopopacity" "stopOpacity"
   "strikethrough-position" "strikethroughPosition"
   "strikethrough-thickness" "strikethroughThickness"
   "strikethroughposition" "strikethroughPosition"
   "strikethroughthickness" "strikethroughThickness"
   "stroke-dasharray" "strokeDasharray"
   "stroke-dashoffset" "strokeDashoffset"
   "stroke-linecap" "strokeLinecap"
   "stroke-linejoin" "strokeLinejoin"
   "stroke-miterlimit" "strokeMiterlimit"
   "stroke-opacity" "strokeOpacity"
   "stroke-width" "strokeWidth"
   "strokedasharray" "strokeDasharray"
   "strokedashoffset" "strokeDashoffset"
   "strokelinecap" "strokeLinecap"
   "strokelinejoin" "strokeLinejoin"
   "strokemiterlimit" "strokeMiterlimit"
   "strokeopacity" "strokeOpacity"
   "strokewidth" "strokeWidth"
   "suppresscontenteditablewarning" "suppressContentEditableWarning"
   "suppresshydrationwarning" "suppressHydrationWarning"
   "surfacescale" "surfaceScale"
   "systemlanguage" "systemLanguage"
   "tabindex" "tabIndex"
   "tablevalues" "tableValues"
   "targetx" "targetX"
   "targety" "targetY"
   "text-anchor" "textAnchor"
   "text-decoration" "textDecoration"
   "text-rendering" "textRendering"
   "textanchor" "textAnchor"
   "textdecoration" "textDecoration"
   "textlength" "textLength"
   "textrendering" "textRendering"
   "transform-origin" "transformOrigin"
   "transformorigin" "transformOrigin"
   "underline-position" "underlinePosition"
   "underline-thickness" "underlineThickness"
   "underlineposition" "underlinePosition"
   "underlinethickness" "underlineThickness"
   "unicode-bidi" "unicodeBidi"
   "unicode-range" "unicodeRange"
   "unicodebidi" "unicodeBidi"
   "unicoderange" "unicodeRange"
   "units-per-em" "unitsPerEm"
   "unitsperem" "unitsPerEm"
   "usemap" "useMap"
   "v-alphabetic" "vAlphabetic"
   "v-hanging" "vHanging"
   "v-ideographic" "vIdeographic"
   "v-mathematical" "vMathematical"
   "valphabetic" "vAlphabetic"
   "vector-effect" "vectorEffect"
   "vectoreffect" "vectorEffect"
   "vert-adv-y" "vertAdvY"
   "vert-origin-x" "vertOriginX"
   "vert-origin-y" "vertOriginY"
   "vertadvy" "vertAdvY"
   "vertoriginx" "vertOriginX"
   "vertoriginy" "vertOriginY"
   "vhanging" "vHanging"
   "videographic" "vIdeographic"
   "viewbox" "viewBox"
   "viewtarget" "viewTarget"
   "vmathematical" "vMathematical"
   "word-spacing" "wordSpacing"
   "wordspacing" "wordSpacing"
   "writing-mode" "writingMode"
   "writingmode" "writingMode"
   "x-height" "xHeight"
   "xchannelselector" "xChannelSelector"
   "xheight" "xHeight"
   "xlinkactuate" "xlinkActuate"
   "xlinkarcrole" "xlinkArcrole"
   "xlinkhref" "xlinkHref"
   "xlinkrole" "xlinkRole"
   "xlinkshow" "xlinkShow"
   "xlinktitle" "xlinkTitle"
   "xlinktype" "xlinkType"
   "xmlbase" "xmlBase"
   "xmllang" "xmlLang"
   "xmlnsxlink" "xmlnsXlink"
   "xmlspace" "xmlSpace"
   "ychannelselector" "yChannelSelector"
   "zoomandpan" "zoomAndPan"})

(def rejected-prop-spellings
  "One spelling per name — ambiguities removed at compile time (conversion
  table §Attribute names; template grammar). The value is the didactic
  replacement the compile error names."
  {:class-name                  ":class"
   :html-for                    ":for"
   :dangerously-set-inner-html  "(ui/html ...)"
   :dangerouslySetInnerHTML     "(ui/html ...)"
   :inner-html                  "(ui/html ...)"
   :children                    "positional children"})

(defn- collapsed [n] (str/replace n "-" ""))

(defn react-prop-name
  "Author kebab prop NAME (string, no namespace) -> React prop name.
  data-*/aria-* verbatim; recognized names via the react-dom vocabulary
  (kebab or hyphen-collapsed hit); unrecognized names verbatim (React 16+
  pass-through)."
  [n]
  (cond
    (or (str/starts-with? n "data-") (str/starts-with? n "aria-"))
    n

    :else
    (or (get standard-names n)
        (get standard-names (collapsed n))
        n)))

(defn camelize
  "kebab -> camelCase: 'on-click' -> 'onClick', 'my-event' -> 'myEvent'."
  [s]
  (let [[h & t] (str/split s #"-")]
    (apply str h (map str/capitalize t))))

(defn react-event-name
  "Handler-position name ('on-click') -> React handler prop ('onClick');
  `capture?` appends React's Capture suffix."
  ([n] (react-event-name n false))
  ([n capture?]
   (cond-> (camelize n)
     capture? (str "Capture"))))

(defn custom-element-property-name
  "RULED custom-element grammar: a declared `:properties` name compiles to
  the camelCase JS property (`:help-text` -> `helpText`)."
  [n]
  (camelize n))

(def dom-attr-aliases
  "React prop name -> the DOM attribute name react-dom/server writes when
  the two differ — the serialiser half of the conversion table (consumed
  by normalization N and, at S5, by the SSR serialiser). Everything NOT
  in this map serialises as the prop name verbatim (react-dom 19.2.0
  emits e.g. `readOnly` verbatim — HTML attribute names are ASCII
  case-insensitive; probed, see the S1b/S1f PR bodies).

  Derived from `standard-names`' hyphenated author spellings (the SVG
  kebab attrs + the hyphenated HTML attrs `accept-charset`/`http-equiv`
  — react-dom's alias set is the same DOM metadata), plus the `xlink:`/
  `xml:` colon families and the probed HTML specials (`class`, `for`,
  `tabindex`)."
  (merge (into {}
               (keep (fn [[k v]] (when (str/includes? k "-") [v k])))
               standard-names)
         {"className"    "class"
          "htmlFor"      "for"
          "tabIndex"     "tabindex"
          "xlinkActuate" "xlink:actuate"
          "xlinkArcrole" "xlink:arcrole"
          "xlinkHref"    "xlink:href"
          "xlinkRole"    "xlink:role"
          "xlinkShow"    "xlink:show"
          "xlinkTitle"   "xlink:title"
          "xlinkType"    "xlink:type"
          "xmlBase"      "xml:base"
          "xmlLang"      "xml:lang"
          "xmlSpace"     "xml:space"
          "xmlnsXlink"   "xmlns:xlink"}))

(defn dom-attr-name
  "Author kebab attr NAME (string, no namespace) -> the serialised DOM
  attribute name (the serialiser/normalization half of the table; the
  client-emitter half is `react-prop-name`). data-*/aria-* verbatim;
  otherwise the React prop name mapped through `dom-attr-aliases`,
  falling back to the prop name verbatim."
  [n]
  (if (or (str/starts-with? n "data-") (str/starts-with? n "aria-"))
    n
    (let [p (react-prop-name n)]
      (get dom-attr-aliases p p))))

;; ---------------------------------------------------------------------------
;; :style
;; ---------------------------------------------------------------------------

(def unitless-css
  "CSS properties whose numeric values do NOT gain 'px'. Mirrors react-dom
  19.2.0 `unitlessNumbers` verbatim (converted to CSS kebab space,
  vendor prefixes included). Version-pinned; the parity corpus detects
  drift ([S1-CONFIRM] row 9)."
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

(defn custom-property? [n] (str/starts-with? n "--"))

(defn- upper-first [s]
  (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s))

(defn react-style-name
  "CSS kebab style name -> React style-object key. Custom properties
  (`--x`) verbatim, no case mapping. Vendor prefixes follow React's
  spelling: -webkit- -> Webkit…, -moz- -> Moz…, -ms- -> ms…."
  [n]
  (cond
    (custom-property? n) n
    (str/starts-with? n "-webkit-") (upper-first (camelize (subs n 1)))
    (str/starts-with? n "-moz-")    (upper-first (camelize (subs n 1)))
    (str/starts-with? n "-ms-")     (camelize (subs n 1))
    :else (camelize n)))

#?(:clj
   (defn- js-double-str
     "ECMA-262 `Number::toString(10)` for a FINITE, NON-INTEGRAL double.
  Built from the JVM's shortest round-trip decimal (`Double/toString` —
  Ryu shortest digits, the same digit selection JS engines use), then
  re-laid-out per the ECMA rules: plain notation while the decimal
  exponent n is in (-6, 21], exponential (`1e+21`, `1.23e-7`) outside.
  Fixes the previous host-format fallback, which leaked Java notation
  (`0.0001` -> \"1.0E-4\", `1e21` -> \"1.0E21\") — parity-corpus row 10
  residual, discharged at S1f."
     [^double d]
     (let [neg?   (neg? d)
           s      (Double/toString (Math/abs d))
           ;; -> [digits n] with value = 0.d1d2... * 10^n
           [ds n] (if-let [ei (str/index-of s "E")]
                    (let [mant   (subs s 0 ei)
                          ex     (Long/parseLong (subs s (inc ei)))
                          digits (str/replace mant "." "")]
                      ;; Java E-notation mantissa is d.ddd (one leading digit)
                      [digits (inc ex)])
                    (let [di   (str/index-of s ".")
                          i    (subs s 0 di)
                          f    (subs s (inc di))]
                      (if (= i "0")
                        (let [z (count (take-while #(= \0 %) f))]
                          [(subs f z) (- z)])
                        [(str i f) (count i)])))
           ds     (let [t (str/replace ds #"0+$" "")]
                    (if (= t "") "0" t))
           k      (count ds)
           body   (cond
                    ;; 0 handled by the integral path upstream
                    (and (<= k n) (<= n 21))
                    (str ds (apply str (repeat (- n k) "0")))

                    (and (< 0 n) (<= n 21))
                    (str (subs ds 0 n) "." (subs ds n))

                    (and (< -6 n) (<= n 0))
                    (str "0." (apply str (repeat (- n) "0")) ds)

                    :else
                    (str (subs ds 0 1)
                         (when (> k 1) (str "." (subs ds 1)))
                         "e" (when (pos? (dec n)) "+") (dec n)))]
       (str (when neg? "-") body))))

(defn js-number-str
  "JS `ToString` semantics for numbers, on both hosts. The pinned case is
  integral doubles rendering WITHOUT a trailing `.0` ([S1-CONFIRM] row
  10); non-integral doubles follow the full ECMA layout via
  `js-double-str` (plain notation in the (-6, 21] decimal-exponent
  window, `e`-notation outside — the parity corpus pins both)."
  [x]
  #?(:cljs (str x)
     :clj  (cond
             (integer? x) (str x)
             (or (double? x) (float? x))
             (let [d (double x)]
               (cond
                 (Double/isNaN d)                     "NaN"
                 (= d Double/POSITIVE_INFINITY)       "Infinity"
                 (= d Double/NEGATIVE_INFINITY)       "-Infinity"
                 (and (== d (Math/rint d))
                      (< (Math/abs d) 1.0E21))
                 (str (.toBigInteger (java.math.BigDecimal. d)))
                 :else (js-double-str d)))
             (ratio? x) (js-number-str (double x))
             :else (str x))))

(defn js-string-coerce
  "React-style key coercion space: numbers via JS ToString, everything
  else via `str` — the collision domain for duplicate-key diagnosis
  ([S1-CONFIRM] row 11)."
  [v]
  (if (number? v) (js-number-str v) (str v)))

(defn css-val->str
  "Style value -> canonical CSS value string (tree semantic space +
  serialiser space; replicates React's dangerousStyleValue): numbers gain
  'px' unless the property is unitless, a custom property, or the value
  is 0; keywords stringify via `name`; strings pass trimmed."
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
;; :class — composition + deterministic order
;; ---------------------------------------------------------------------------

(defn- class-part->str [p]
  (cond
    (nil? p)     nil
    (false? p)   nil
    (true? p)    nil
    (keyword? p) (name p)
    :else        (let [s (str p)] (when-not (str/blank? s) s))))

(defn classes-str
  "Join class parts (strings/keywords; nil/false/blank dropped) with
  single spaces; nil when empty. Order is the caller's — the deterministic
  ordering rules (vector order; flag maps lexicographic; sugar first) are
  applied by the compiler/`class-val` before this join."
  [parts]
  (let [ps (into [] (keep class-part->str) parts)]
    (when (seq ps) (str/join " " ps))))

(defn flag-map-classes
  "Flag map -> class parts in LEXICOGRAPHIC class-name order (one
  deterministic rule for literal and runtime maps alike — map iteration
  order is never trusted; conversion table §:class)."
  [m]
  (->> m
       (keep (fn [[k v]] (when v (class-part->str k))))
       sort
       vec))

(defn class-val
  "Runtime conversion for a DYNAMIC `:class` value: string/keyword
  verbatim; vector in vector order (nils dropped); flag map lexicographic;
  nil -> nil."
  [v]
  (cond
    (nil? v)     nil
    (string? v)  (when-not (str/blank? v) v)
    (keyword? v) (name v)
    (map? v)     (classes-str (flag-map-classes v))
    (vector? v)  (classes-str v)
    :else        (class-part->str v)))

;; ---------------------------------------------------------------------------
;; Serialiser-half data rows (owned by the conversion table; consumed by
;; the SSR serialiser at S5 and by normalization N — recorded here so the
;; table is one artefact; probed against react-dom/server 19.2.0)
;; ---------------------------------------------------------------------------

(def void-tags
  "Tags that self-close and MUST NOT have children (compile error).
  Probe outcome ([S1-CONFIRM] row 12): react-dom/server 19.2.0 throws for
  children on all of these INCLUDING param and keygen (absent from the
  contract draft's 13 — corrected here); menuitem also rejects children
  but is not self-closing, so it lives in `children-rejected-tags` only."
  #{:area :base :br :col :embed :hr :img :input :link :meta :param
    :keygen :source :track :wbr})

(def children-rejected-tags
  "Void set plus menuitem (React 19.2.0: \"menuitems cannot have
  `children`...\" — rejected, though rendered non-self-closing)."
  (conj void-tags :menuitem))

(def boolean-attrs
  "HTML boolean attributes: true -> presence (attr=\"\"), false/absent ->
  omitted. Probed row-by-row against react-dom/server 19.2.0
  ([S1-CONFIRM] row 4). Note `ismap` (React 19 dropped the camel `isMap`
  prop; the author spelling is `:ismap`). `hidden`: React 19.2.0 treats
  it as PURE boolean — the string \"until-found\" also renders bare
  presence, so the contract draft's enumerated exception is corrected to
  boolean-only for this React pin (row 4/probe)."
  #{"allowfullscreen" "async" "autofocus" "autoplay" "checked" "controls"
    "default" "defer" "disabled" "formnovalidate" "hidden" "inert" "ismap"
    "itemscope" "loop" "multiple" "muted" "nomodule" "novalidate" "open"
    "playsinline" "readonly" "required" "reversed" "selected"})

(def booleanish-attrs
  "true/false -> \"true\"/\"false\", never omitted (probed: contentEditable,
  draggable, spellCheck — [S1-CONFIRM] row 5)."
  #{"contenteditable" "draggable" "spellcheck"})

(def overloaded-boolean-attrs
  "true -> bare presence, false -> omitted, other values stringify
  (probed: download, capture — [S1-CONFIRM] row 6)."
  #{"download" "capture"})

(def property-only-attrs
  "Names React never serialises to markup on NON-custom elements. Probe
  outcome ([S1-CONFIRM] row 7): react-dom/server 19.2.0 DOES serialise
  `muted` on <video> (muted=\"\"), so the contract draft's `:muted`
  citizen is corrected — no S1 row member remains; the set is kept (empty)
  as the named home for any future member the parity corpus finds."
  #{})

(defn escape-html
  "Full 5-char escaping (& < > \" ') for text and attribute values —
  `ui/html` is the single bypass (conversion table §Children, text, and
  escaping; matches React's escapeTextForBrowser)."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

;; ---------------------------------------------------------------------------
;; Namespace context (SVG / MathML) — conversion table §Namespaces
;; ---------------------------------------------------------------------------

(defn child-ns-context
  "Namespace context the CHILDREN of `tag` build under, given the current
  context (nil = HTML). Rows per the conversion table §Namespaces
  ([S1-CONFIRM] row 2): :svg enters svg; :foreignObject's children revert
  to HTML; :math enters mathml; :annotation-xml with an :encoding attr of
  text/html or application/xhtml+xml reverts to HTML."
  [ctx tag attrs]
  (case tag
    :svg  :svg
    :math :mathml
    :foreignObject (when-not (= ctx :svg) ctx)
    :annotation-xml (if (and (= ctx :mathml)
                             (contains? #{"text/html" "application/xhtml+xml"}
                                        (some-> (get attrs :encoding) str/lower-case)))
                      nil
                      ctx)
    ctx))

(defn element-ns
  "The `:ns` field value for an element built in context `ctx` (nil for
  HTML — canonical form never writes `:ns :html`)."
  [ctx]
  (when (contains? #{:svg :mathml} ctx) ctx))

;; ---------------------------------------------------------------------------
;; Custom-element declarations (RULED grammar) — runtime reconciliation registry
;;
;; The RUNTIME arm of the compile-side build-scoped model (rf2-vxgfnd.16 AC5;
;; rf2-vxgfnd.48 shipped the first cut; rf2-vxgfnd.67 makes it a full
;; reconciliation protocol). `ui/spread` and the JVM tree builder classify a
;; custom element's `:properties` by reading the LIVE aggregate
;; `custom-elements` (tag -> decl). That aggregate must be a PURE FUNCTION of
;; the CURRENT set of live sources — never of process history — exactly like
;; the compile-side build registries (`re-frame.ui.compiler.build`).
;;
;; .48 evicted a source's contribution ONLY when it re-registered — i.e. it used
;; a SURVIVING declaration as the signal that the source was rebuilt. That left
;; three holes: a source that deletes its LAST declaration (or whose file is
;; deleted) never re-registers, so its stale rows lingered until a full page
;; reload; a reload that threw mid-way left the live registry half-mutated; and
;; two builds sharing one runtime atom cross-contaminated. .67 replaces
;; registration-triggered eviction with an explicit reload-cycle protocol driven
;; from the reload BOUNDARY, not from re-registration:
;;
;;   (notify-reload! [build?])    ^:dev/before-load — OPEN a reload cycle. From
;;                                here re-registrations STAGE; the live aggregate
;;                                stays at its last-known-good until commit.
;;   register-custom-element!     a declaration re-runs — STAGES into the open
;;                                cycle (or, at initial load / in production where
;;                                no cycle is open, writes through directly).
;;   (note-reloaded-sources! ..)  the SHADOW_NS_RESET producer (rf2-vxgfnd.77;
;;                                see below) records WHICH sources re-ran this
;;                                cycle — the signal a zero-declaration source
;;                                cannot emit for itself (absence cannot
;;                                self-report). Optional: absent, only the
;;                                staged sources are reconciled.
;;   (commit-reload! [build?])    ^:dev/after-load — COMMIT atomically: every
;;                                staged source REPLACES its whole prior
;;                                contribution, every reloaded-but-unstaged /
;;                                removed source is EVICTED, untouched sources are
;;                                kept, and the live aggregate is rebuilt from the
;;                                reconciled ledger in ONE swap. shadow-cljs runs
;;                                after-load only on a SUCCESSFUL reload, so a
;;                                thrown / aborted reload never commits — the last
;;                                known-good manifest survives.
;;
;; Ownership is [build-id ns-sym] so two builds sharing one atom (a shared JVM /
;; a test) reconcile independently — evicting one build's source can never drop
;; another's rows. The emitted 3-arg registration uses ns-only ownership under
;; the sole ::default build (one compiled bundle = one build, its own atoms); the
;; explicit build-scoped arities exist for multi-build reconciliation and tests.
;;
;; Production never opens a cycle (`notify-reload!` is a dev-only hook), so every
;; registration writes through directly and classification stays a plain lookup —
;; all reconciliation machinery is reload-only.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "tag keyword -> {:properties #{kebab-kw}}. The LIVE aggregate
  `ui/spread` and the JVM tree builder read (unchanged; undeclared elements need
  no declaration — all-attributes default). A pure function of the current live
  sources: rebuilt from the ledger on every reload commit."}
  custom-elements
  (atom {}))

(defonce ^{:private true
           :doc "[build-id ns-sym] -> {tag decl} — the per-source committed
  contribution ledger (the runtime mirror of the compile-side build ledger). The
  live aggregate is the merge of every source's map, so a source whose file is
  deleted, or whose last declaration is dropped, vanishes the moment its ledger
  row is reconciled away — no surviving declaration required."}
  element-sources
  (atom {}))

(defonce ^{:private true
           :doc "build-id -> {:staged {[build ns] {tag decl}} :touched #{[build ns]}}
  for each build whose reload cycle is IN FLIGHT (absent = no cycle → direct
  write-through). `:staged` accumulates this cycle's re-registrations, held back
  from the live aggregate until commit; `:touched` is the reloaded/removed source
  set `note-reloaded-sources!` records — the sources to reconcile even when they
  staged nothing (zero-declaration / deletion)."}
  reload-cycles
  (atom {}))

(def ^:private default-build ::default)

(defn- rebuild-live!
  "Rebuild the live aggregate as the merge of every ledger source's map — the
  live registry is a PURE FUNCTION of the ledger. One `reset!` (sources merged in
  a stable order), so consumers never observe a partial manifest."
  []
  (reset! custom-elements
          (reduce (fn [agg src] (merge agg (get @element-sources src)))
                  {}
                  (sort-by str (keys @element-sources))))
  nil)

(defn register-custom-element!
  "Register `tag`'s runtime property classification under its declaring source.
  Emitted top-level by `ui/custom-element`, so it re-runs on every ns hot-reload.
  While a reload cycle is open for the build it STAGES (the live aggregate stays
  last-known-good until `commit-reload!`); otherwise — initial load, production —
  it writes through directly. Ownership is [build-id ns-sym]; the 3-arg emit uses
  the sole ::default build."
  ([tag decl ns-sym] (register-custom-element! tag decl ns-sym default-build))
  ([tag decl ns-sym build-id]
   (let [source [build-id ns-sym]]
     (if (contains? @reload-cycles build-id)
       (swap! reload-cycles update-in [build-id :staged source]
              (fnil assoc {}) tag decl)
       (do (swap! element-sources update source (fnil assoc {}) tag decl)
           (swap! custom-elements assoc tag decl))))
   tag))

(defn ^:dev/before-load notify-reload!
  "shadow-cljs hot-reload hook (dev only): OPEN a reload cycle for `build-id`
  (the runtime analogue of the compile-side `begin-build!`). Re-registrations
  from here stage instead of mutating the live aggregate; opening a fresh cycle
  discards any staging left by a previously ABORTED reload. Production never hot-
  reloads, so this never fires there."
  ([] (notify-reload! default-build))
  ([build-id]
   (swap! reload-cycles assoc build-id {:staged {} :touched #{}})
   nil))

(defn note-reloaded-sources!
  "Record the sources that re-ran (or were removed) in the open reload cycle —
  the signal a zero-declaration source cannot emit for itself, fed in the shipped
  browser reload path by `reload-source-reset!` (the SHADOW_NS_RESET producer;
  rf2-vxgfnd.77). `sources` is a coll of ns-syms (paired with `build-id`) or
  ready-made [build ns] pairs. A no-op when no cycle is open; unions into the
  cycle so it can be called repeatedly."
  ([sources] (note-reloaded-sources! sources default-build))
  ([sources build-id]
   (when (contains? @reload-cycles build-id)
     (let [pairs (map (fn [s] (if (vector? s) s [build-id s])) sources)]
       (swap! reload-cycles update-in [build-id :touched] (fnil into #{}) pairs)))
   nil))

(defn ^:dev/after-load commit-reload!
  "shadow-cljs hot-reload hook (dev only): COMMIT the open reload cycle for
  `build-id` atomically. Every STAGED source replaces its whole prior
  contribution; every source flagged by `note-reloaded-sources!` that staged
  NOTHING (a zero-declaration edit or a deleted file) is evicted; untouched
  sources are kept; then the live aggregate is rebuilt from the reconciled ledger
  in one swap. shadow-cljs runs after-load only on a SUCCESSFUL reload, so an
  aborted reload never reaches here and the last known-good manifest survives.
  A no-op when no cycle is open."
  ([] (commit-reload! default-build))
  ([build-id]
   (when-let [{:keys [staged touched]} (get @reload-cycles build-id)]
     (let [sources (into (set (keys staged)) touched)]
       (swap! element-sources
              (fn [ledger]
                (reduce (fn [l src]
                          (if-let [decls (get staged src)]
                            (assoc l src decls)   ; re-declared: replace whole contribution
                            (dissoc l src)))      ; zero-declaration / removed: evict
                        ledger
                        sources)))
       (rebuild-live!)
       (swap! reload-cycles dissoc build-id)))
   nil))

;; ---------------------------------------------------------------------------
;; The production producer: shadow-cljs's real reload machinery feeds
;; `note-reloaded-sources!` (rf2-vxgfnd.77)
;;
;; The ledger above evicts a source that dropped its last declaration ONLY when
;; `note-reloaded-sources!` is told the source re-ran. But a source that re-runs
;; declaring NOTHING emits no code to announce itself — absence cannot
;; self-report — so the signal cannot come from the emitted
;; `register-custom-element!` calls (a zero-declaration source emits none). It
;; must come from the reload machinery, which knows which namespaces re-ran
;; regardless of what they declared.
;;
;; shadow-cljs exposes exactly that seam: its `before-load-src` calls every fn on
;; `js/goog.global.SHADOW_NS_RESET` with the ns symbol of each source it reloads,
;; DURING the code-load phase — after the `^:dev/before-load` `notify-reload!`
;; opened the cycle and before the `^:dev/after-load` `commit-reload!` closes it.
;; `reload-source-reset!` rides that seam: for every reloaded ns it stages a
;; `:touched` entry, so `commit-reload!` reconciles the sources that ACTUALLY
;; re-ran and evicts the stale rows of one that now declares nothing — the exact
;; runtime analogue of the compile side, where `build/commit-slice` evicts a
;; source that recompiled contributing nothing.
;;
;; Bounded case (matches rf2-df9873's ruling AND the compile side): a source FILE
;; deleted with no reloading dependent never re-runs, so shadow never reports it
;; and its runtime rows persist until the next full page load — "no design can
;; observe an unsaved deletion." The compile-side `finish-build!` evicts the
;; deleted source from the COMPILE registry via `:build-sources` membership; the
;; runtime arm converges on the next full reload. `note-reloaded-sources!` stays
;; the seam an authoritative removed-source manifest would feed if one is wired.
;;
;; Dev-only: the whole install is `js/goog.DEBUG`-gated, so `:advanced`
;; production carries no producer and never references SHADOW_NS_RESET.
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn reload-source-reset!
     "The per-source action shadow-cljs's reload machinery drives (through
     `js/goog.global.SHADOW_NS_RESET`) for every namespace it reloads this cycle:
     record `ns` as a source that re-ran, so `commit-reload!` evicts it when it
     staged no declaration this cycle. A no-op when no reload cycle is open
     (initial load / production). `ns` is a CLJS ns symbol; a string is coerced."
     [ns]
     (note-reloaded-sources! [(cond-> ns (string? ns) symbol)])
     nil))

#?(:cljs
   (defn install-reload-source-producer!
     "Wire the runtime reload ledger to shadow-cljs's real reload machinery — the
     PRODUCER that feeds `note-reloaded-sources!` in the shipped browser reload
     path (rf2-vxgfnd.77). Pushes `reload-source-reset!` onto
     `js/goog.global.SHADOW_NS_RESET` (ensuring the array `||`-style, so it
     neither clobbers nor is clobbered by shadow's own init), so shadow calls it
     with each reloaded source's ns from `before-load-src`. Dev-only (gated on
     `js/goog.DEBUG`, elided from `:advanced` production) and installed exactly
     once via the `defonce` below (a hot-reload of THIS ns never double-registers)."
     []
     (when ^boolean js/goog.DEBUG
       (let [arr (or js/goog.global.SHADOW_NS_RESET #js [])]
         (set! (.-SHADOW_NS_RESET js/goog.global) arr)
         (.push arr reload-source-reset!)
         true))))

#?(:cljs
   (defonce ^:private _reload-source-producer
     ;; Install at client load so the shipped reload path has a producer the
     ;; moment this ns is present; `defonce` survives a hot-reload of rules.cljc
     ;; itself, so the callback is registered exactly once.
     (install-reload-source-producer!)))

(defn reset-custom-elements!
  "Test support: clear the runtime custom-element registry, the per-source
  ledger, and any in-flight reload cycles."
  []
  (reset! custom-elements {})
  (reset! element-sources {})
  (reset! reload-cycles {})
  nil)

(defn custom-element-properties [tag]
  (get-in @custom-elements [tag :properties] #{}))

;; ---------------------------------------------------------------------------
;; Semantic attr-value normalization (tree space) — shared with ui/spread
;; ---------------------------------------------------------------------------

(defn attr-val-semantic
  "Normalize a DYNAMIC attr value into the tree's semantic space
  (jvm-tree contract §Attr value normalization): keywords/symbols ->
  `name` (namespace ignored); numbers -> JS ToString; booleans stay
  booleans; nil -> nil (caller drops); strings verbatim; collections ->
  typed error (React would render garbage)."
  [attr-kw v]
  (cond
    (nil? v)      nil
    (string? v)   v
    (boolean? v)  v
    (number? v)   (js-number-str v)
    (keyword? v)  (name v)
    (symbol? v)   (name v)
    (coll? v)     (error/throw-error!
                   :rf.error/ui-tree-malformed 're-frame.ui/render
                   (str "collection value for attribute " attr-kw
                        " — collections are only meaningful for :class/:style"
                        " (React would render \"[object Object]\" garbage)")
                   {:extra {:attr attr-kw :value v}})
    :else         (str v)))
