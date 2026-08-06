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
            [re-frame.error :as error]
            [re-frame.ui.eq :as eq]))

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

(def raw-text-tags
  "The HTML RAW-TEXT elements — `<script>`/`<style>`. React 19.2 takes a
  SINGLE text body (one string) for these: multiple children reach React as an
  array that warns and loses the body, and a structural (element/list) child is
  dropped or stringified. The compiler holds them to a single text-producing
  child, a sole `(ui/html …)` trusted-markup body, or no body (rf2-ib4fd). The
  compile-tier KEYWORD counterpart of the SSR serialiser's string set
  `re-frame.ssr.ui-tree/raw-text-tags` (`#{\"script\" \"style\"}`). `:title`
  and `:textarea` are escapable RCDATA, NOT raw text, and are absent here."
  #{:script :style})

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
;; two holes: a source that deletes its LAST declaration (or whose file is
;; deleted) never re-registers, so its stale rows lingered until a full page
;; reload; and a reload that threw mid-way left the live registry half-mutated.
;; .67 replaces registration-triggered eviction with an explicit reload-cycle
;; protocol driven from the reload BOUNDARY, not from re-registration:
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
;;   (commit-reload! [build?])    ^:dev/after-load — COMMIT: reconcile the ledger
;;                                (every staged source REPLACES its whole prior
;;                                contribution, every reloaded-but-unstaged /
;;                                removed source is EVICTED, untouched sources are
;;                                kept) AND close the cycle in ONE atomic swap,
;;                                then publish the aggregate. shadow-cljs runs
;;                                after-load only on a SUCCESSFUL reload, so a
;;                                thrown / aborted reload never commits — the last
;;                                known-good manifest survives.
;;
;; The whole protocol reads and writes ONE authoritative value (`ledger-state`:
;; live `::sources` + in-flight `::cycles`), so the reload cycle is a
;; LINEARIZABLE state transition (rf2-vxgfnd.144). The public `custom-elements`
;; aggregate is a pure projection published AFTER each transition, never interleaved with it —
;; so a watch that reentrantly registers during publication observes an already-
;; closed cycle and write-through's into the next generation instead of staging
;; into a cycle the commit is about to discard.
;;
;; A ledger row is keyed [build-id ns-sym]. The `ns-sym` is what partitions a real
;; host's sources; the `build-id` is the INTERNAL PRODUCER SEAM — the identity the
;; reset producer stamps and every arity default resolves through — and, in tests,
;; a STATE-PARTITION TOKEN that lets one process drive several independent ledgers
;; without them colliding. It is NOT a claim that two dev builds can share one copy
;; of this namespace and reconcile independently: they cannot, and nothing shadow
;; exposes could tell them apart. See §THE ISOLATION UNIT below for the contract
;; that bounds this.
;;
;; The key is only worth its weight if `build-id` is COHERENT — every participant
;; in a cycle must agree on it (rf2-vxgfnd.142). It used to be the placeholder
;; `::default` on every real path — emitted registrations, both lifecycle hooks,
;; the reset producer — with a second identity reachable only from a test-only
;; arity, so an emitted registration and the cycle meant to reconcile it could key
;; a row differently and strand it. Now `current-build-id` is the ONE rule every
;; arity default resolves through, so they cannot disagree. The explicit
;; build-scoped arities remain the seam the producer routes through and the token
;; tests partition state with.
;;
;; ## Production carries none of this (rf2-k9yuy)
;;
;; Production never opens a cycle: `notify-reload!` is a `^:dev/before-load` hook
;; shadow does not install in a release build. So a production registration was
;; always a plain write-through and classification always a plain lookup — but
;; until rf2-k9yuy the ledger atom was still ALLOCATED and every production
;; registration still `swap-vals!`'d against it, shipping unused bookkeeping to
;; consumers. The `reload-ledger?` compile-time gate now folds the whole ledger
;; out of `:advanced` + `goog.DEBUG=false`: no ledger atom, no staging branch, no
;; reload-hook body, and `register-custom-element!` emits the ONE direct
;; `custom-elements` update it actually needs — still once per declaration at
;; load, never per render. Dev and the JVM keep the full protocol unchanged.
;;
;; The elision is pinned BOTH ways. LEXICALLY: the ledger's namespace-qualified
;; root keys (`::sources` / `::cycles`) are minted nowhere else, so
;; `scripts/check-ui-mounted-prod-elision.cjs` greps the real `:advanced` browser
;; bundle for them — present ⇒ the ledger compiled in. CAUSALLY:
;; `custom_element_reload_elision_prod_test.cljs` runs IN that bundle and asserts
;; the private `ledger-state` var holds no atom while registration still
;; classifies. The dev side is the positive control — `rules_cljs_test` (node,
;; `goog.DEBUG=true`) stages, reconciles and commits real cycles, so removing the
;; dev branch reddens there rather than silently satisfying the bundle grep.
;;
;; ## THE ISOLATION UNIT — one dev build per CLJS root (rf2-4vm19, NORMATIVE)
;;
;; THE CONTRACT: ONE Shadow dev build per CLJS global namespace root containing
;; `re-frame.ui.rules`. That is the unit this ledger isolates. It is a stated
;; boundary, not an aspiration — everything below follows from what shadow's
;; runtime seam does and does not convey.
;;
;; The unit is the CLJS ROOT — not the page, and not the output file. Separate
;; output files are not automatically separate runtime copies: standard shadow dev
;; output shares the global root, and single-module release output is wrapped by
;; default.
;;
;; SUPPORTED, and isolated by construction:
;;   - one app plus its `:preloads` tools — that is ONE build;
;;   - iframes and workers — separate JS realms, so separate roots;
;;   - independently compiled release bundles — output-wrapped, own copy, and no
;;     ledger at all (see §Production carries none of this);
;;   - separate pages.
;;
;; UNSUPPORTED, and UNOBSERVABLE from inside: two dev builds fused onto ONE shared
;; root. `before-load-src` calls each SHADOW_NS_RESET callback with the reloaded ns
;; and NOTHING else, and lifecycle hooks are invoked with no arguments after being
;; resolved by name off the SHARED `$CLJS` object. So a callback learns which build
;; is reloading only from the identity BAKED INTO ITS OWN BUNDLE — which is exactly
;; what `current-build-id` reads. Two bundles that each carry their OWN copy of
;; this namespace are therefore isolated: each copy resolves its own build's name
;; and reconciles only its own rows. But two builds sharing ONE copy (one `$CLJS`,
;; one set of atoms, one `defonce`d producer) share one set of hooks — a single
;; shared singleton — and cannot be told apart by anything shadow exposes.
;;
;; There is deliberately NO runtime detection, warning, or error for the fused
;; case. From inside the shared copy the condition is unobservable, so any check
;; would be heuristic or tautological — the same "agreeing with itself by
;; construction" defect this namespace rejects for cycle tokens below. The
;; boundary is held by construction and stated here, never policed at runtime.
;;
;; ## Overlapping cycles are unsupported, and no token can change that (rf2-84173)
;;
;; A build's cycle is identified by `build-id` alone. That is not an omission
;; awaiting a generation token — it is the most any token could achieve, because
;; of two facts about shadow's loader (read off the pinned shadow-cljs client:
;; `shadow.cljs.devtools.client.{browser,shared,env}`):
;;
;;   1. NO CONVEYANCE. Lifecycle hooks are resolved by name off `$CLJS` and
;;      invoked with NO arguments. A `^:dev/after-load` invocation is therefore
;;      ANONYMOUS: it cannot present a claim minted by the `^:dev/before-load`
;;      that opened its cycle. A token would have to be minted AND checked
;;      entirely inside this ledger, where `commit-reload!` could only compare it
;;      against the live slot it already reads — agreeing with itself by
;;      construction. That is a tautology wearing an identity check's clothes,
;;      which is precisely why this ledger has none.
;;
;;   2. NO PAIRING. `do-js-reload*` walks a task chain and ABANDONS the remainder
;;      when a task throws, so a failed load never runs `^:dev/after-load` at all.
;;      before-load and after-load are therefore not a balanced pair, which also
;;      rules out every depth/refcount scheme: one aborted reload would leave the
;;      count permanently above zero and hot reload would silently stop
;;      committing, forever.
;;
;; Shadow adds no single-flight guard of its own — no pending flag, no queue, no
;; dedup — so back-to-back `build-complete` messages each run a full independent
;; reload chain. What actually serializes them is JavaScript: with only
;; SYNCHRONOUS lifecycle hooks the whole before -> load -> after chain runs inside
;; one event-loop turn, so cycle N's after-load always precedes cycle N+1's
;; before-load and cycles cannot overlap. An `^:dev/before-load-async` /
;; `^:dev/after-load-async` hook ANYWHERE in the build parks that chain and lets a
;; second before-load land mid-cycle.
;;
;; So the honest boundary is: exactly ONE live cycle per build, overlapping
;; same-build cycles unsupported, and the supersession made LOUD instead of silent
;; (`notify-reload!`). The degradation under a genuine overlap is bounded and
;; self-healing — stale classification rows until the next full page load, the
;; same bound `note-reloaded-sources!` already documents for a deleted file.
;;
;; NOTE the word "generation" elsewhere in this namespace means something
;; narrower and real: the open-vs-closed cycle state a registration observes
;; ATOMICALLY (rf2-vxgfnd.144), which decides staging vs write-through. That is a
;; property of the ledger transition, NOT an identity for a cycle.
;; ---------------------------------------------------------------------------

(defonce ^{:doc "tag keyword -> {:properties #{kebab-kw}}. The LIVE aggregate
  `ui/spread` and the JVM tree builder read (unchanged; undeclared elements need
  no declaration — all-attributes default). A pure function of the current live
  sources: rebuilt from the ledger on every reload commit."}
  custom-elements
  (atom {}))

(def ^:private ^boolean reload-ledger?
  "COMPILE-TIME gate for the ENTIRE hot-reload ledger (rf2-k9yuy).

  Only a host that can OPEN a reload cycle needs the source/cycle ledger below.
  Development (`goog.DEBUG`) can; `:advanced` production cannot — `notify-reload!`
  is a `^:dev/before-load` hook shadow never installs in a release build — so in
  production every registration was already a plain write-through and the whole
  ledger was unused bookkeeping a consumer shipped for nothing.

  Under `:advanced` + `goog.DEBUG=false` Closure constant-folds this to `false`,
  which DCEs the ledger atom's construction, every staging/reconcile branch and
  every reload-hook body — so no source/cycle state is allocated, rooted, read or
  mutated in production. What survives is the ONE direct `custom-elements` update
  a production registration actually needs.

  The JVM arm is unconditionally `true` (NOT `interop/debug-enabled?`, which
  `-Dre-frame.debug=false` can flip): the compiler and the JVM tree-builder
  fixtures drive the reload protocol headless, so the ledger must always be
  present there."
  #?(:cljs ^boolean js/goog.DEBUG
     :clj  true))

(def ^:private empty-ledger
  "The quiescent ledger value — no committed sources, no in-flight cycles."
  {::sources {} ::cycles {}})

(defonce ^{:private true
           :doc "The ONE authoritative ledger value (rf2-vxgfnd.144), or `nil`
  in `:advanced` production (rf2-k9yuy — see `reload-ledger?`; a host that can
  never open a reload cycle allocates no ledger). A single atom so cycle
  open/close, staged-row ownership and the ledger transition are a LINEARIZABLE
  state change — check-then-act on the reload cycle can never be split across two
  atoms and interleaved:

    {::sources {[build-id ns-sym] {tag decl}}   ; the per-source committed ledger
     ::cycles  {build-id {:staged {[b ns] {tag decl}} :touched #{[b ns]}}}}

  `::sources` is the runtime mirror of the compile-side build ledger — a source
  whose file is deleted, or whose last declaration is dropped, vanishes the
  moment its row is reconciled away, no surviving declaration required.
  `::cycles` holds each build's IN-FLIGHT reload (absent = no cycle → direct
  write-through): `:staged` accumulates the cycle's re-registrations, held back
  from the live aggregate until commit; `:touched` is the reloaded/removed source
  set `note-reloaded-sources!` records — the sources to reconcile even when they
  staged nothing (zero-declaration / deletion).

  Both keys are NAMESPACE-QUALIFIED (`::sources` / `::cycles`) so the advanced-
  bundle elision scan has an unambiguous lexical sentinel — this ledger is their
  only minter, whereas the bare-keyword spellings collide with unrelated corpus
  keywords and would make the grep meaningless. The scan itself lives in
  scripts/check-ui-mounted-prod-elision.cjs; it deliberately owns the fully-
  qualified grep strings, because the elision companion reifies THIS var and so
  carries this doc into the very bundle being scanned.

  The public `custom-elements` aggregate is a pure PROJECTION of `::sources`,
  published (one `reset!`) AFTER each authoritative transition completes — never
  interleaved with it."}
  ledger-state
  (when reload-ledger? (atom empty-ledger)))

(def ^:private default-build
  "The owner token for a source registering outside any real Shadow build: the
  JVM tree builder, a plain-JVM/REPL host, a `:node-test` bundle, and every
  `:advanced` production bundle (which never reloads and — rf2-k9yuy — carries no
  ledger at all, so this token names only the write-through owner)."
  ::default)

#?(:cljs
   (defn- shadow-build-id
     "The REAL build identity of the bundle this code is running in, or nil when
     there is no Shadow dev build (production, a bare node bundle, a test host).

     shadow-cljs compiles `(name build-id)` into every devtools-enabled build as
     the `shadow.cljs.devtools.client.env/build-id` closure-define, and PREPENDS
     that namespace to the module entries — so it is already loaded and defined
     before any app code (and therefore before any emitted
     `register-custom-element!`) runs. It is a STABLE identity: the build's own
     name, not the content/build digest, so it does not change when sources do.

     Read by NAME off `$CLJS`, the way shadow reads its own reload lifecycle fns,
     rather than by requiring the devtools client — the devtools client must never
     be reachable from library code that ships to production. `js/goog.DEBUG` gates
     the sole call site, so `:advanced` constant-folds this away entirely."
     []
     (let [root (or (unchecked-get js/goog.global "$CLJS") js/goog.global)
           v    (js/goog.getObjectByName "shadow.cljs.devtools.client.env.build_id" root)]
       (when (and (string? v) (seq v))
         (keyword v)))))

(defn current-build-id
  "The build identity the ledger keys this host's sources under — ONE rule, shared
  by every arity default in the reload protocol (`register-custom-element!`,
  `notify-reload!`, `note-reloaded-sources!`, `commit-reload!`, and the installed
  `reload-source-reset!` producer). Because they all resolve it the same way, an
  emitted registration and the reload cycle that reconciles it can never disagree
  about who owns a row (rf2-vxgfnd.142).

  In a real Shadow dev build this is that build's OWN name (`:app`); everywhere
  else — production, JVM, a bare node bundle — there is no Shadow build to name and
  it is `::default`. Resolved per call rather than cached: it is dev-only, called
  once per declaration at load and once per reload hook (never per render), and a
  cache would only create a window in which an early miss became permanent."
  []
  #?(:cljs (if ^boolean js/goog.DEBUG
             (or (shadow-build-id) default-build)
             default-build)
     :clj  default-build))

;; ---------------------------------------------------------------------------
;; The ONE cross-source declaration law (rf2-vxgfnd.143)
;;
;; Delegated ruling 2026-07-15, Option A — FAIL ATOMICALLY, never a merge or a
;; winner law. For a given tag the effective declaration is the unique value
;; contributed by every live source across the one JS realm IFF every
;; cross-source pair is `rf=`-equal. The runtime aggregate is keyed by TAG alone
;; — one shared `custom-elements` registry per realm — so `[build-id ns-sym]` is
;; DECLARER PROVENANCE (the anchor a contradiction names), never a partition key:
;; two sources with different build-ids that classify one tag differently
;; contradict, and the runtime cross-build test pins exactly that. (The COMPILE
;; arm's per-build registry legitimately permits different manifests in different
;; builds — separate builds are separate compilation units, not one realm.) Any
;; non-`rf=`-equal same-tag declaration from a DIFFERENT source fails atomically,
;; carrying a sorted vector of [build-id ns-sym] anchors — every equal incumbent
;; declarer plus the arrival — and leaves the last-known-good aggregate unchanged.
;;
;; ## Where the law lives, and why it is not where it was first ruled
;;
;; The 2026-07-15 ruling placed the runtime arm on the ledger paths below
;; (`register-custom-element!`'s pre-write check, the `commit-reload!` fold).
;; All of those sit behind `reload-ledger?`, which Closure constant-folds to
;; false and DCEs under `:advanced` + `goog.DEBUG=false` (rf2-k9yuy) — so
;; implemented as first written the law would not have existed in the shipped
;; artefact at all. That is the exact shape of two defects that DID ship in the
;; same week (rf2-2hkfy #6376, an "always-on" rejection — enforcement meant to
;; survive every build, NOT the always-on error-emit channel — that was actually
;; goog.DEBUG-gated; rf2-5pr75 #6352, a live XSS closed only by making the check
;; fire on every build). The 2026-07-19 placement amendment gives the law a
;; residence on the ONE registration that survives a release build:
;;
;;   `write-element!` — the direct `custom-elements` barrier the ADVANCED-
;;   PRODUCTION path performs (`reload-ledger?` false, no ledger). On dev/JVM
;;   hosts (`reload-ledger?` true) the same declaration is instead admitted
;;   against `ledger-state` — `write-through-verdict` for a direct registration,
;;   `commit-reload!` for a staged one — and the live `custom-elements` aggregate
;;   is a published PROJECTION of the ledger, never a `write-element!` call.
;;
;; So `ledger-state` is the dev/JVM admission authority and `write-element!` is
;; the advanced-production barrier; both apply the SAME pure `admit`, and the
;; `aggregate` re-derivation over `::sources` is defence in depth, not a second
;; winner rule. `custom_element_reload_elision_prod_test.cljs` runs INSIDE the
;; `:advanced` bundle and pins the production enforcement so it cannot silently
;; rot back to dev-only.
;;
;; The compile arm (PR #6006) already draws the same two-tier shape:
;; `build/contribute-element-checked!` is the write BARRIER and
;; `build/elements-conflict` the re-derived detector. `admit` below is the
;; runtime barrier's law, and `aggregate` re-derives the same verdict over the
;; committed ledger — one law, applied twice, unable to drift because both call
;; the same pure function.
;; ---------------------------------------------------------------------------

(def ^:private owners-key
  "Owner provenance slot on a live aggregate entry: the SET of `[build-id ns-sym]`
  sources whose `rf=`-equal declarations are live for that tag.

  A SET, not a single canonical owner (rf2-7uyl9): several sources may
  legitimately state the same fact, and a later replacement from one of them must
  be able to name EVERY other source still contributing it — otherwise it could
  silently overwrite a manifest another source still declares, and the conflict
  evidence could not name every declarer. Written in the SAME swap as the
  declaration itself, because the law's evidence needs every anchor and production
  carries no ledger to look incumbents up in (rf2-k9yuy). Readers are unaffected —
  `custom-element-properties` reads `:properties` off the entry — and there is
  deliberately NO second atom and no parallel index that could drift from it."
  ::owners)

(defn- declaration
  "The DECLARATION half of a live entry — the entry minus its provenance. What
  the equal declarers all agree on, `rf=`, for their declarations to co-exist."
  [entry]
  (dissoc entry owners-key))

(defn- conflict-row
  "One anchor of a contradiction, in the ruled evidence shape (mirrors the
  compile arm's `build/element-conflict-row`)."
  [[build-id ns-sym] decl]
  {:build build-id :ns ns-sym :properties (:properties decl #{})})

(defn- conflict-evidence
  "Every anchor of a contradiction, SORTED. `incumbent-owners` are all the
  sources whose `rf=`-equal `incumbent-decl` is live for `tag`; `source`/`decl`
  is the arriving contradiction. Naming EVERY equal declarer — not one canonical
  owner (rf2-7uyl9) — is what stops a replacement silently overwriting a manifest
  another source still declares, and makes the anchors complete. Sorting by
  `[build ns]` keeps the evidence byte-identical under every permutation of
  source names, evaluation order and build ids."
  [tag incumbent-owners incumbent-decl source decl]
  {:tag tag
   :declarations (->> (conj (mapv #(conflict-row % incumbent-decl) incumbent-owners)
                            (conflict-row source decl))
                      (sort-by (juxt (comp str :build) (comp str :ns)))
                      vec)})

(defn- admit
  "THE law, as one pure step: fold `source`'s declaration of `tag` into
  aggregate `agg`.

  Returns `{:aggregate agg'}` when the declaration is admitted, or
  `{:conflict evidence}` — naming EVERY equal live declarer plus `source`
  (rf2-7uyl9) — when it contradicts a live declaration from a DIFFERENT source,
  in which case NOTHING is written and `agg` remains the last-known-good
  aggregate.

  Each entry records the SET of sources whose `rf=`-equal declarations are live
  (`owners-key`). Four cases:

    - no live declaration for `tag`  -> admitted; `source` is its sole declarer.
    - the arriving decl is `rf=`-EQUAL to the live one -> `source` JOINS the
                                        declarer set (idempotent if already in);
                                        duplicates co-exist, and every one is now
                                        named in any future contradiction.
    - not equal, but `source` is the SOLE live declarer -> REPLACED. A source
                                        never conflicts with itself: re-declaration
                                        is what a reload or a REPL re-eval does.
    - not equal, and OTHER sources still declare the live value -> CONFLICT,
                                        naming every remaining declarer. Never a
                                        merge, never a winner by evaluation order.

  Pure and total, so the production write barrier (`write-element!`) and the
  dev-side ledger projection (`aggregate`) apply literally the same law and
  cannot drift from one another."
  [agg tag decl source]
  (let [entry  (get agg tag)
        owners (get entry owners-key)]
    (cond
      (nil? owners)
      {:aggregate (assoc agg tag (assoc decl owners-key #{source}))}

      (eq/rf= (declaration entry) decl)
      {:aggregate (assoc agg tag (assoc decl owners-key (conj owners source)))}

      (= owners #{source})
      {:aggregate (assoc agg tag (assoc decl owners-key #{source}))}

      :else
      {:conflict (conflict-evidence tag (disj owners source)
                                    (declaration entry) source decl)})))

(defn- throw-element-conflict!
  "Raise the ruled runtime conflict as an UNCONDITIONAL throw — never a
  `trace/`-gated path. The law this reports holds in `:advanced` production, so
  the throw has to fire there too; gating it behind `goog.DEBUG` would re-create
  the very dev-only-law defect the placement amendment exists to prevent. That is
  production-surviving ENFORCEMENT — a DISTINCT axis from the always-on
  error-emit listener CHANNEL. This is a pure `error/throw-error!` that never
  fans a record to the `:errors` listener, so Spec 009 catalogues
  `:rf.error/custom-element-conflict` on the DIAGNOSTIC channel (the
  thrown-ex-info-is-diagnostic rule) even though the enforcement itself is
  unconditional and survives a release build."
  [{:keys [tag declarations] :as evidence}]
  (error/throw-error!
   :rf.error/custom-element-conflict 're-frame.ui/custom-element
   (str "conflicting (ui/custom-element " tag " ...) declarations — "
        (str/join " vs "
                  (map (fn [{:keys [build ns properties]}]
                         (str ns " (build " (pr-str build) ") declares :properties "
                              (pr-str (vec (sort properties)))))
                       declarations))
        ". One tag has ONE property manifest: delete the duplicate declaration, "
        "or make all declaring sources declare an IDENTICAL :properties set (identical "
        "declarations may co-exist). re-frame.ui will not pick a winner by "
        "evaluation or reload order — the live manifest is UNCHANGED")
   {:recovery :align-custom-element-declarations
    :extra evidence}))

(defn- write-element!
  "THE advanced-production residence of the law (placement amendment 2026-07-19):
  the direct `custom-elements` swap the PRODUCTION registration path performs.

  This is the only registration code that survives `:advanced` +
  `goog.DEBUG=false`; everything ledger-shaped folds away with `reload-ledger?`,
  so putting the check anywhere else would make the law dev-only. On dev/JVM
  hosts the declaration is admitted against `ledger-state` instead (see
  `register-custom-element!`), never here.

  The verdict is decided INSIDE the one atomic swap, so no interleaved
  registration is admitted against a value this one has already rejected. The
  post-swap re-derivation reads the same `old` the WINNING attempt saw, so the
  evidence reported is exactly the verdict that was applied — never a second,
  differently-timed opinion."
  [tag decl source]
  (let [[old _new] (swap-vals! custom-elements
                               (fn [agg]
                                 (:aggregate (admit agg tag decl source) agg)))]
    (when-let [evidence (:conflict (admit old tag decl source))]
      (throw-element-conflict! evidence)))
  nil)

(defn- aggregate
  "The public aggregate as the conflict-CHECKED fold of every ledger source's
  map — the live registry is a PURE FUNCTION of `::sources`, computed by the
  SAME `admit` law the production barrier applies.

  Returns `{:aggregate m}` or `{:conflict evidence}`. Sources are folded in a
  stable sorted order, so the projection and the anchors of any contradiction it
  finds are deterministic regardless of ledger insertion order. That order
  decides only which of two `rf=`-EQUAL declarations is folded first; it can
  never pick a winner between contradictory ones, which is what makes this a
  DEFENCE-IN-DEPTH re-derivation rather than a second, sort-order law of the
  kind the ruling forbids."
  [sources]
  (reduce
   (fn [acc src]
     (let [step (reduce-kv (fn [acc tag decl]
                             (let [r (admit (:aggregate acc) tag decl src)]
                               (if (:conflict r) (reduced r) r)))
                           acc
                           (get sources src))]
       (if (:conflict step) (reduced step) step)))
   {:aggregate {}}
   (sort-by str (keys sources))))

(defn- report-publication-escape!
  "Surface the contained observer failure from ONE reload publication (rf2-za45g).

  Bounded to a single diagnostic per publication and itself contained: the
  machinery that reports a failed observer must never become a second way for a
  committed reload to fail. Dev-only by construction — the only caller sits
  inside `reload-ledger?`, so `:advanced` production carries neither.

  CLJS-only, mirroring the analogous descriptor-HMR reporter in
  `re-frame.ui.reactive` (rf2-vxgfnd.215): shadow's `^:dev/after-load` hook — the
  thing an escaping observer would falsify — exists only on CLJS. The JVM arm of
  this ledger drives the protocol headless from compiler / tree-builder fixtures,
  where there is no reload outcome to misreport and no console to report it to."
  [build-id tag-count error]
  #?(:cljs
     (try
       (when (exists? js/console)
         (.warn js/console
                (str "[re-frame.ui] a custom-element publication observer threw "
                     "while publishing build " (pr-str build-id)
                     "'s reload commit (" tag-count
                     " declared tags live) — the throw was CONTAINED and the "
                     "reload REMAINS SUCCESSFUL: the ledger was already "
                     "reconciled and the cycle closed before publication began. "
                     "Cause: "
                     (if (nil? error)
                       "nil"
                       (error/ex-message-safe error)))))
       (catch :default _ nil))
     :clj (do build-id tag-count error nil)))

(defn- publish!
  "Republish the public aggregate as a pure projection of the CURRENT ledger
  `::sources`. Called AFTER an authoritative `ledger-state` transition, never
  interleaved with it, so a reentrant `register-custom-element!` a watch fires
  during this publish sees the already-transitioned ledger.

  Uses `swap!` reading the LIVE ledger (not a captured snapshot) so that under JVM
  contention the projection retries and recomputes from the latest `::sources` —
  it can never overwrite a concurrent write-through's row with a stale aggregate.
  On CLJS it is a single uncontended recompute.

  ## Publication is DELIVERY, not authority (rf2-za45g)

  By the time this runs the reload is already committed: `commit-reload!`
  reconciled `::sources` and closed the cycle in ONE atomic swap, and only then
  called here. Publication is the DELIVERY of that outcome, so it needs delivery's
  failure semantics — the same split rf2-vxgfnd.215 drew for descriptor HMR.

  An observer of `custom-elements` is arbitrary code. `swap!` notifies watches
  SYNCHRONOUSLY and with no containment, so a throwing observer used to escape
  here, out of `commit-reload!`, and into shadow's `^:dev/after-load` hook — which
  reports the whole reload FAILED even though the framework had committed it. That
  is a lie about authority told by a listener, so the throw is contained and
  reported instead.

  Containment is safe because BOTH hosts publish the atom's new value BEFORE
  notifying watches (CLJS `-reset!` sets state then `-notify-watches`; the JVM
  `Atom.swap` CAS's then `notifyWatches`). A contained observer throw therefore
  leaves the live aggregate, the projected ledger and the cycle state exactly as a
  clean publication would — quiescent and coherent — never half-applied.

  What containment does NOT buy: SIBLING delivery. Watch fan-out belongs to the
  host's `IAtom`, which abandons the remaining watches when one throws, and
  replacing that would mean shipping a listener framework this ledger has no
  consumer for — there are no framework-owned observers of `custom-elements` at
  all (classification reads `custom-element-properties`, never a watch). So
  arbitrary watches are explicitly UNSUPPORTED observers: they are contained and
  diagnosed, but their mutual delivery order and completeness are the host's
  semantics, not a framework guarantee."
  [build-id]
  (let [failure (try
                  ;; A conflicted projection RETAINS the live aggregate rather
                  ;; than publishing a partial one. Unreachable in practice —
                  ;; `commit-reload!` rejects a conflicting cycle before it can
                  ;; reach here, and the barrier keeps contradictory rows out of
                  ;; `::sources` in the first place — but publication is DELIVERY
                  ;; (rf2-za45g), so its failure mode is "deliver nothing new",
                  ;; never "deliver something half-resolved".
                  (swap! custom-elements
                         (fn [live]
                           (:aggregate (aggregate (::sources @ledger-state)) live)))
                  {:failed? false :error nil}
                  ;; The explicit `:failed?` bit — never the truthiness of
                  ;; `:error` — preserves failure identity when CLJS throws a
                  ;; falsey value (`(throw nil)`, `(throw false)` are both legal).
                  (catch #?(:clj Throwable :cljs :default) e
                    {:failed? true :error e}))]
    (when (:failed? failure)
      (report-publication-escape! build-id (count @custom-elements) (:error failure))))
  nil)

(defn- reconcile-sources
  "Fold one build's committed cycle into `::sources`: every STAGED source replaces
  its whole prior contribution; every reconciled source that staged NOTHING (a
  zero-declaration edit or a removed/deleted file flagged via `:touched`) is
  evicted; untouched sources are kept."
  [sources staged touched]
  (reduce (fn [l src]
            (if-let [decls (get staged src)]
              (assoc l src decls)     ; re-declared: replace whole contribution
              (dissoc l src)))        ; zero-declaration / removed: evict
          sources
          (into (set (keys staged)) touched)))

(defn- commit-outcome
  "Pure: what committing `build-id`'s open cycle in ledger value `st` WOULD do.

  `nil` when no cycle is open; `{:sources reconciled}` when the reconciled
  ledger projects cleanly; `{:conflict evidence}` when a staged source
  introduces a cross-source contradiction.

  The verdict is taken on the POST-reconciliation value, which is the only
  honest place to take it: a staged source REPLACES its whole prior
  contribution, so a row that looks contradictory mid-cycle may be the very row
  being retired. Dev-side defence in depth — a staged registration never reaches
  the production barrier, so this is where the law is enforced for it."
  [st build-id]
  (when-let [{:keys [staged touched]} (get (::cycles st) build-id)]
    (let [reconciled (reconcile-sources (::sources st) staged touched)
          {:keys [conflict]} (aggregate reconciled)]
      (if conflict {:conflict conflict} {:sources reconciled}))))

(defn- write-through-verdict
  "Pure: the verdict of admitting `source`'s declaration of `tag` into ledger
  value `st` on the WRITE-THROUGH path (no cycle open for the build).

  Decided against the ledger's OWN projection (`aggregate` over `::sources`), so
  the whole admission is ONE decision on the single `ledger-state` atom
  (rf2-u25hr) — never a check against a snapshot of `@custom-elements`, which a
  concurrent, differently-timed live write can leave stale. That split is exactly
  the defect: two write-throughs each prechecked the still-empty live aggregate,
  both entered `::sources`, and only one live write won — stranding the loser's
  row in the ledger while the projection went nil. Deciding against `::sources`
  instead serialises the two on this one atom: the second sees the first's row
  and is rejected before it can enter.

  Returns `{:sources st'}` with the row recorded in `::sources` when admitted, or
  `{:conflict evidence}` when it contradicts a live source, leaving `::sources`
  untouched. `::sources` is conflict-free by invariant (a rejected row never
  enters it), so its projection is always a clean aggregate."
  [st source tag decl]
  (let [live (:aggregate (aggregate (::sources st)))]
    (if-let [conflict (:conflict (admit live tag decl source))]
      {:conflict conflict}
      {:sources (update-in st [::sources source] (fnil assoc {}) tag decl)})))

(defn register-custom-element!
  "Register `tag`'s runtime property classification under its declaring source.
  Emitted top-level by `ui/custom-element`, so it re-runs on every ns hot-reload.
  While a reload cycle is open for the build it STAGES (the live aggregate stays
  last-known-good until `commit-reload!`); otherwise — initial load, production —
  it writes through directly. Ownership is [build-id ns-sym]; the 3-arg emit
  resolves the ambient build via `current-build-id`.

  The stage-vs-write-through decision and the ledger write are ONE atomic
  transition on `ledger-state` (rf2-vxgfnd.144): a registration can never read
  'cycle open' and then have its stage recreate a cycle a concurrent commit just
  removed. A write-through additionally publishes its one row; because it decided
  against staging atomically, a commit that closed the cycle first cannot delete
  this row (the closed cycle no longer names it), and a commit still open cannot
  see it as staged (it went to `::sources`, not `:staged`) — the generation rule
  the reentrancy fix relies on.

  In `:advanced` production (`reload-ledger?` false) there is no ledger and no
  cycle can ever be open, so the whole stage-vs-write-through transition folds
  away and what remains is the single direct aggregate update — the same row the
  dev write-through publishes, reached without touching any ledger state
  (rf2-k9yuy).

  Every path applies the SAME pure `admit` law (rf2-vxgfnd.143), so a
  cross-source contradiction is caught on EVERY host and build mode — advanced
  production through the direct `write-element!` barrier, dev/JVM through the
  `ledger-state` admission (`write-through-verdict` here, `commit-reload!` for a
  staged reload). A contradictory same-tag declaration from a DIFFERENT source is
  rejected without writing and raises `:rf.error/custom-element-conflict` whose
  `:declarations` is a sorted vector naming every equal incumbent declarer plus
  the arriving source, leaving the live manifest exactly as it was. `rf=`-equal
  duplicates co-exist, and a source re-declaring its own tag replaces its row only
  while it is the SOLE declarer — while another source still co-declares the live
  value, a non-`rf=`-equal re-declaration is a contradiction."
  ([tag decl ns-sym] (register-custom-element! tag decl ns-sym (current-build-id)))
  ([tag decl ns-sym build-id]
   (let [source [build-id ns-sym]]
     (if reload-ledger?
       (let [[old _new]
             (swap-vals! ledger-state
                         (fn [st]
                           (if (contains? (::cycles st) build-id)
                             (update-in st [::cycles build-id :staged source]
                                        (fnil assoc {}) tag decl)
                             ;; WRITE-THROUGH. The admission verdict is decided
                             ;; INSIDE this one `ledger-state` swap, against the
                             ;; ledger's OWN projection (`write-through-verdict`),
                             ;; never against a snapshot of `@custom-elements` a
                             ;; concurrent live write can leave stale (rf2-u25hr).
                             ;; Two write-throughs for one tag therefore serialise
                             ;; on this atom: the second sees the first's row in
                             ;; `::sources` and is rejected, so a rejected row can
                             ;; never remain in `::sources` while the live
                             ;; aggregate holds the winner. A rejected write-through
                             ;; leaves `::sources` untouched here and (below)
                             ;; publishes nothing — NEITHER atom moves off
                             ;; last-known-good.
                             ;;
                             ;; A STAGED row is deliberately NOT checked here: it
                             ;; is not live yet, and the rows it would be checked
                             ;; against are the very ones this cycle is about to
                             ;; replace. `commit-reload!` is the staged path's
                             ;; check, on the POST-reconciliation value.
                             (:sources (write-through-verdict st source tag decl)
                                       st))))]
         ;; The winning transition saw `old`; re-derive its verdict from that same
         ;; `old` to PUBLISH or to RAISE. `::sources` already carries the admitted
         ;; row (written in the swap above), so `publish!` — a pure projection of
         ;; the LIVE ledger — republishes it without a second, differently-timed
         ;; opinion and cannot lose it to a concurrent commit; a rejected
         ;; write-through raises without either atom having moved. (Staged
         ;; registrations stay invisible until commit.)
         (when-not (contains? (::cycles old) build-id)
           (let [{:keys [conflict]} (write-through-verdict old source tag decl)]
             (if conflict
               (throw-element-conflict! conflict)
               (publish! build-id)))))
       (write-element! tag decl source)))
   tag))

(defn- report-unpaired-cycle!
  "Surface an UNPAIRED `^:dev/before-load` — one that found a cycle still open,
  carrying evidence, and superseded it (rf2-84173).

  Bounded to one diagnostic per supersession and itself contained: a diagnostic
  must never become a way for opening a cycle to fail. Dev-only by construction
  (the only caller sits inside `reload-ledger?`).

  Reports the DISCARD because the discard is real and lossy — it is simply the
  lesser loss (see `notify-reload!`). Silence was the actual defect: the same
  blind clobber served the routine aborted-reload recovery and the pathological
  overlapping-cycle case, and nothing told them apart or told anyone."
  [build-id staged touched]
  #?(:cljs
     (try
       (when (exists? js/console)
         (.warn js/console
                (str "[re-frame.ui] build " (pr-str build-id)
                     " opened a custom-element reload cycle while one was STILL "
                     "OPEN; the previous cycle's evidence was discarded ("
                     (reduce + 0 (map count (vals staged))) " staged declaration(s) across "
                     (count staged) " source(s), " (count touched)
                     " touched source(s)). EXPECTED after a reload that threw — "
                     "shadow-cljs skips ^:dev/after-load entirely when a load "
                     "task fails, so before/after are not a balanced pair, and "
                     "discarding is how last-known-good is recovered. UNEXPECTED "
                     "otherwise: overlapping same-build reload cycles are NOT "
                     "supported, because shadow passes no cycle identity to "
                     "^:dev/after-load. If you use ^:dev/before-load-async or "
                     "^:dev/after-load-async, expect custom-element "
                     "classification to need a full page reload to converge.")))
       (catch :default _ nil))
     :clj (do build-id staged touched nil)))

(defn ^:dev/before-load notify-reload!
  "shadow-cljs hot-reload hook (dev only): OPEN a reload cycle for `build-id`
  (the runtime analogue of the compile-side `begin-build!`). Re-registrations
  from here stage instead of mutating the live aggregate. Production never hot-
  reloads (shadow installs no `^:dev/before-load` hook in a release build), so
  this never fires there and its body folds away with the ledger (rf2-k9yuy).

  ## Exactly ONE live cycle per build, and why the supersession is loud

  Opening a fresh cycle REPLACES whatever cycle was open for the build, so a
  build has exactly one live cycle — the generation every registration and every
  `:touched` note of that reload belongs to. Replacing is deliberate and is the
  ABORTED-RELOAD RECOVERY: shadow's `do-js-reload*` abandons the remaining task
  chain when a task throws, so a failed load never reaches `^:dev/after-load` and
  leaves its cycle open forever. The next before-load must clear it, or the first
  successful commit would apply a failed reload's partial staging.

  Replacing is also LOSSY, and until rf2-84173 it was silent. The `:touched`
  evidence in particular cannot be recovered: it is reported once, during the
  load phase, and `note-reloaded-sources!` no-ops once no cycle is open. So the
  supersession now reports what it discarded.

  Discarding is the RULED reading of the ambiguity, not an oversight. A second
  before-load has two possible causes and the ledger cannot tell them apart:
  the common one is an aborted predecessor (discarding is correct); the rare one
  is a genuinely overlapping activation (discarding loses a live cycle). Keeping
  the evidence instead would trade the rare loss for a common one — a source that
  re-ran in the aborted cycle sits in `:touched` with its staging thrown away, so
  the next commit would EVICT declarations that still exist in the new code and
  will not re-register. Losing live rows on every failed reload is strictly worse
  than degrading an overlap that requires an async lifecycle hook to occur at all.

  See `## Overlapping cycles are unsupported` above for why no token can do
  better here."
  ([] (notify-reload! (current-build-id)))
  ([build-id]
   (when reload-ledger?
     (let [[old _new] (swap-vals! ledger-state
                                  assoc-in [::cycles build-id]
                                  {:staged {} :touched #{}})]
       ;; Only the transition that actually superseded an EVIDENCE-BEARING cycle
       ;; reports. A cycle opened twice with nothing staged and nothing touched
       ;; discarded nothing, so there is nothing to warn about — this tests what
       ;; was LOST, not merely whether a key was present.
       (when-let [{:keys [staged touched]} (get (::cycles old) build-id)]
         (when (or (seq staged) (seq touched))
           (report-unpaired-cycle! build-id staged touched)))))
   nil))

(defn note-reloaded-sources!
  "Record the sources that re-ran (or were removed) in the open reload cycle —
  the signal a zero-declaration source cannot emit for itself, fed in the shipped
  browser reload path by `reload-source-reset!` (the SHADOW_NS_RESET producer;
  rf2-vxgfnd.77). `sources` is a coll of ns-syms, or of [build ns] pairs whose
  ns is taken and whose build is IGNORED. A no-op when no cycle is open (and, in
  `:advanced` production, when there is no ledger at all — rf2-k9yuy); unions
  into the cycle so it can be called repeatedly.

  OWNERSHIP TRAVELS WITH THE ADDRESSED CYCLE, NEVER WITH THE PAYLOAD
  (rf2-4vm19). Every source is normalized to `[build-id ns]` for the build this
  call addresses, so the reconciliation a cycle performs is total over its OWN
  rows and can never reach another build's. A ready-made pair used to be taken
  verbatim, which let evidence handed to build A's open cycle nominate build B
  as owner — `commit-reload!` then evicted B's row under a cycle B never took
  part in. Normalizing rather than rejecting keeps the ergonomic ns-only form
  the only shape anyone needs while making the pair form merely a more verbose
  spelling of it: one ownership rule, no second path to reason about, and no
  caller-facing failure mode for a datum whose ns is perfectly usable."
  ([sources] (note-reloaded-sources! sources (current-build-id)))
  ([sources build-id]
   (when reload-ledger?
     (swap! ledger-state
            (fn [st]
              (if (contains? (::cycles st) build-id)
                (let [pairs (map (fn [s] [build-id (if (vector? s) (second s) s)])
                                 sources)]
                  (update-in st [::cycles build-id :touched] (fnil into #{}) pairs))
                st))))
   nil))

(defn ^:dev/after-load commit-reload!
  "shadow-cljs hot-reload hook (dev only): COMMIT the open reload cycle for
  `build-id`. The reconciliation AND the cycle close are ONE atomic transition on
  `ledger-state`: `::sources` is reconciled (staged sources replace their whole
  contribution; touched-but-unstaged sources are evicted; untouched sources are
  kept) and the cycle is removed in the SAME swap. Only then is the aggregate
  published.

  This linearizes the commit against a reentrant registration (rf2-vxgfnd.144).
  Publishing can synchronously fire a watch on `custom-elements` that calls
  `register-custom-element!`; by then the cycle is ALREADY gone from
  `ledger-state`, so that registration write-through's into a new generation and
  survives, instead of staging into a cycle this commit then discards. The old
  snapshot-then-`dissoc` shape published while the cycle was still open, so such a
  registration staged and was dropped by the following `dissoc`.

  shadow-cljs runs after-load only on a SUCCESSFUL reload, so an aborted reload
  never reaches here and the last known-good manifest survives. A no-op when no
  cycle is open.

  Because this IS the `^:dev/after-load` hook, whatever escapes it is what shadow
  reports as the reload's outcome. The authority transition above is already
  complete when `publish!` runs, so a throwing publication observer must not be
  able to escape and recast a committed reload as a failed one — `publish!`
  contains it and reports it instead (rf2-za45g)."
  ([] (commit-reload! (current-build-id)))
  ([build-id]
   (when reload-ledger?
     (let [[old _new]
           (swap-vals! ledger-state
                       (fn [st]
                         (let [{:keys [sources]} (commit-outcome st build-id)]
                           (cond
                             ;; Clean commit: reconcile AND close in one swap.
                             sources
                             (-> st
                                 (assoc ::sources sources)
                                 (update ::cycles dissoc build-id))

                             ;; CONFLICTING cycle — reject ATOMICALLY. `::sources`
                             ;; is untouched, so the last-known-good manifest
                             ;; survives and nothing is partially published; the
                             ;; cycle is closed and its staging DISCARDED, exactly
                             ;; as an aborted reload's would be. Leaving it open
                             ;; instead would wedge the session — every later
                             ;; registration would stage into a cycle no commit
                             ;; could ever close.
                             (contains? (::cycles st) build-id)
                             (update st ::cycles dissoc build-id)

                             :else st))))]
       ;; Publish only when THIS commit closed a cycle (the winning transition saw
       ;; one in `old`). The cycle is gone from the ledger, so a reentrant
       ;; registration during the publish takes the write-through path — it cannot
       ;; be recaptured or dropped by this commit. `publish!` recomputes from the
       ;; LIVE ledger, so a concurrent write-through's row is never clobbered.
       ;;
       ;; The outcome is re-derived from the same `old` the winning transition
       ;; saw, so the raised evidence is the verdict that was actually applied.
       ;; A rejected commit RAISES: this is the `^:dev/after-load` hook, and the
       ;; reload genuinely did not commit, so reporting it as failed is the truth
       ;; (rf2-za45g contains a throwing publication OBSERVER precisely because
       ;; that case is the opposite — a committed reload being recast as failed).
       (let [{:keys [conflict]} (commit-outcome old build-id)]
         (cond
           conflict (throw-element-conflict! conflict)
           (contains? (::cycles old) build-id) (publish! build-id)))))
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
;; Bounded case of THIS producer — and the second producer that closes it: a
;; source FILE deleted (or renamed away) never re-runs, so `before-load-src`
;; never announces it and no SHADOW_NS_RESET call can ever carry its ns. The
;; compile side evicts the deleted source from the COMPILE registry via
;; authoritative `:build-sources` membership; the runtime arm used to converge
;; only on the next full page load. `note-reloaded-sources!` was always the seam
;; an authoritative removed-source manifest would feed if one were wired — and
;; one now is: `shadow-build-notify` below (rf2-4vm19) receives shadow's own
;; `:build-complete` broadcast of that same `:build-sources` membership and
;; reconciles the REMOVED delta through this exact seam. What remains genuinely
;; unobservable is only the UNSAVED bare REPL deletion (rf2-df9873): no saved
;; graph, no successful build, nothing to project.
;;
;; Dev-only: the whole install is `js/goog.DEBUG`-gated, so `:advanced`
;; production carries no producer and never references SHADOW_NS_RESET.
;;
;; ## Why the installed callback is a TRAMPOLINE (rf2-vxgfnd.145)
;;
;; `SHADOW_NS_RESET` holds CALLBACK OBJECTS, and the install is `defonce`d (it
;; must be: re-running it on every reload of this ns would append a duplicate).
;; Pushing the `reload-source-reset!` fn OBJECT therefore pinned the object
;; realized at FIRST load forever: hot-reloading rules.cljc rebinds the var to a
;; new object, but the array kept calling the old one. That stayed invisible only
;; while the old object delegated to other rebound vars — any fix to the
;; callback's OWN coercion, build routing or source filtering silently needed a
;; full page reload to take effect.
;;
;; So what is installed is one stable TRAMPOLINE that resolves the CURRENT
;; `reload-source-reset!` on every call (the same late-lookup discipline shadow
;; itself applies to lifecycle fns — it re-resolves `fn-str` off `$CLJS` per
;; reload "in case it gets reloaded"). The trampoline object is stable, so
;; `defonce` still guarantees exactly one entry, while the BEHAVIOUR it runs is
;; always the freshly loaded code.
;;
;; The trampoline carries its owning build identity as a TAG, and the install
;; REPLACES the entry it already owns instead of appending — but what identifies
;; that entry is OBJECT IDENTITY, never the tag (rf2-ymfix); the tag is provenance
;; a reader can inspect, not an ownership lever. An entry owned by shadow or
;; another library is never touched at all. The tag names a build because
;; `SHADOW_NS_RESET` lives on `goog.global`, which is SHARED across a whole JS
;; realm: two bundles that each carry their OWN copy of this namespace (the
;; supported multi-copy topology — see §THE ISOLATION UNIT) put two producers on
;; that one array, so they must coexist rather than evict each other.
;;
;; ## And the INSTALL is self-upgrading too (rf2-mp6fz)
;;
;; A trampoline keeps the entry's BEHAVIOUR current. It cannot keep the ENTRY
;; current — the object's own construction, its captured owner, and the installer
;; that placed it are all fixed at the moment of installation. While the install
;; was `defonce`d that moment was the page's first load, forever: a live page
;; kept whatever entry it had, and the only way to adopt a change to this
;; machinery was a full reload. The concrete failure that names: an entry
;; installed before shadow's build-id carrier was consulted captured `::default`,
;; and the page went on routing every reset to the placeholder owner even once
;; the real build identity became resolvable.
;;
;; So the install runs on EVERY load and retires what this copy already owns
;; (`owned-producer`). Ownership is OBJECT IDENTITY, never the tag: the tag says
;; which build an entry claims, identity says this copy put it there, and the two
;; diverge in exactly the cases that matter — our own entry whose captured tag
;; has gone stale, and a co-loaded bundle's entry carrying the tag we are leaving
;; behind. `defonce` moved from the install (where it froze the machinery) to the
;; ownership record (where surviving the reload is the whole point).
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn reload-source-reset!
     "The per-source action shadow-cljs's reload machinery drives (through
     `js/goog.global.SHADOW_NS_RESET`) for every namespace it reloads this cycle:
     record `ns` as a source that re-ran, so `commit-reload!` evicts it when it
     staged no declaration this cycle. A no-op when no reload cycle is open
     (initial load / production). `ns` is a CLJS ns symbol; a string is coerced.
     `build-id` is the build whose producer is calling (the installed trampoline
     passes its own owner); the 1-arg arity uses the ambient build."
     ([ns] (reload-source-reset! ns (current-build-id)))
     ([ns build-id]
      (note-reloaded-sources! [(cond-> ns (string? ns) symbol)] build-id)
      nil)))

#?(:cljs
   (def ^:private producer-tag
     "The JS property re-frame.ui stamps on its own `SHADOW_NS_RESET` entry,
     carrying the owning build id as PROVENANCE: it marks an entry as re-frame.ui's
     and names the build that installed it, which diagnostics and tests read to
     tell this framework's producers from shadow's and other libraries'. It is NOT
     the ownership lever — a re-install finds its own entry by OBJECT IDENTITY,
     never by tag, because the same tag can appear on a co-loaded bundle's entry
     that only identity can distinguish from ours (rf2-ymfix)."
     "reFrameUiReloadSourceReset"))

#?(:cljs
   (defonce ^{:private true
              :doc "The array entry THIS copy of the namespace currently owns, or
  nil before its first install (and always in `:advanced` production, which
  installs no producer at all — the atom is gated exactly like `ledger-state`, so
  no cell is allocated there either).

  `defonce` because it is the one thing that must SURVIVE a hot-reload of this
  namespace while the install itself deliberately does not (rf2-mp6fz): it is how
  the freshly loaded installer learns which entry the PREVIOUS generation of this
  code put on the shared array, so it can retire it instead of appending beside
  it.

  Identity, not tag, is the ownership proof. A tag says which BUILD an entry
  claims; only object identity says that THIS copy installed it. The two diverge
  exactly when migration matters — an entry whose captured tag is now stale is
  still ours, and a co-loaded bundle's entry carrying the very same tag is not.
  Retiring by tag would get both of those backwards."}
     owned-producer
     (when ^boolean js/goog.DEBUG (atom nil))))

#?(:cljs
   (defn- make-reload-source-producer
     "One stable callback object owned by `build-id` that resolves the CURRENT
     `reload-source-reset!` on every call. `reload-source-reset!` compiles to a
     namespace-global read, so a hot-reload of rules.cljc — which re-assigns that
     global — is picked up here without a page refresh."
     [build-id]
     (let [f (fn [ns] (reload-source-reset! ns build-id))]
       (unchecked-set f producer-tag (str build-id))
       f)))

#?(:cljs
   (defn install-reload-source-producer!
     "Wire the runtime reload ledger to shadow-cljs's real reload machinery — the
     PRODUCER that feeds `note-reloaded-sources!` in the shipped browser reload
     path (rf2-vxgfnd.77). Installs this build's tagged trampoline on
     `js/goog.global.SHADOW_NS_RESET` (ensuring the array `||`-style, so it
     neither clobbers nor is clobbered by shadow's own init), so shadow calls it
     with each reloaded source's ns from `before-load-src`.

     IDEMPOTENT, and SELF-UPGRADING across a hot reload (rf2-mp6fz). Ownership is
     OBJECT IDENTITY, with NO tag fallback (rf2-ymfix): it reuses the exact entry
     this copy already installed — found by identity on the shared array, so the
     slot is reused even when the build tag that entry captured has since gone
     stale — and REPLACES it in place. A copy that owns nothing there — a first
     install, or one whose remembered entry has gone — APPENDS its own; it never
     adopts an entry by tag, because a same-tagged callback may belong to a
     co-loaded bundle and only identity can tell the two apart. So repeated
     installs can neither duplicate this copy's producer nor strand a predecessor
     still routing to an identity this build no longer resolves, and an entry owned
     by shadow, another library, or another bundle is never replaced, moved,
     reordered, or invoked. Dev-only (gated on `js/goog.DEBUG`, elided from
     `:advanced` production)."
     []
     (when ^boolean js/goog.DEBUG
       (let [arr      (or js/goog.global.SHADOW_NS_RESET #js [])
             build-id (current-build-id)]
         (set! (.-SHADOW_NS_RESET js/goog.global) arr)
         (let [prev @owned-producer
               ;; OWNERSHIP IS OBJECT IDENTITY, with no tag fallback (rf2-ymfix):
               ;; locate only the exact entry this copy installed. A same-tagged
               ;; entry we did not install may belong to a co-loaded bundle, and
               ;; only identity tells the two apart — so an absent predecessor is
               ;; NOT authority to replace one.
               i    (if prev (.indexOf arr prev) -1)
               f    (make-reload-source-producer build-id)]
           (if (neg? i)
             (.push arr f)    ; own nothing on the array → APPEND; never adopt by tag
             (aset arr i f))  ; replace exactly this copy's own entry, in place
           (reset! owned-producer f))
         true))))

;; Install at client load so the shipped reload path has a producer the moment
;; this ns is present — and re-install on EVERY load, which is what makes the
;; producer self-upgrading (rf2-mp6fz).
;;
;; This deliberately is NOT `defonce`d. The `defonce` existed to stop the
;; original `.push` from appending a duplicate on each reload; once .145 gave the
;; entry an owner tag and a replace-in-place install, that job belonged to the
;; install itself — and the `defonce` had become the thing PINNING the installer,
;; so no change to it, to the trampoline's construction, or to the owner tag
;; could reach a page without a full reload. That is the same staleness .145 fixed
;; one level down, at the level above it: .145 made the entry run current code,
;; this makes the ENTRY ITSELF current. `owned-producer` carries the ownership
;; record across the reload boundary that this form no longer needs to.
#?(:cljs (install-reload-source-producer!))

;; ---------------------------------------------------------------------------
;; The removed-source producer: shadow's build-lifecycle broadcast feeds the
;; delta SHADOW_NS_RESET cannot report (rf2-4vm19)
;;
;; `reload-source-reset!` above records the sources that RE-RAN. A source whose
;; file is deleted or renamed away never re-runs — shadow's loader has nothing
;; to load for it — so no SHADOW_NS_RESET call fires, no `:touched` entry is
;; recorded, and its committed rows used to survive every hot reload until the
;; next full page load. The compile side has no such blind spot: its registries
;; fold over the authoritative `:build-sources` membership at finish, so the
;; deleted source falls out of the COMPILE registry on the very pass that
;; removed it. What was missing was the projection of that same authority into
;; the runtime cycle.
;;
;; Shadow itself already delivers it. Every successful watch build broadcasts
;; `:build-complete` to each connected runtime carrying `:info :sources` — the
;; per-source image of exactly the `:build-sources` graph the compile hook folds
;; (`shadow.build/extract-build-info`, sent by the worker on success only). The
;; pinned client exposes ONE seam for that message: the `:build-notify` receiver
;; (`shadow.cljs.devtools.client.env/run-custom-notify!` resolves the configured
;; fn by munged name off `$CLJS` — the same late-lookup discipline shadow applies
;; to its lifecycle fns). `shadow-build-notify` is that receiver: it diffs THIS
;; build's committed ledger rows against the broadcast membership and reconciles
;; the REMOVED namespaces through the existing cycle protocol —
;; `notify-reload!` -> `note-reloaded-sources!` -> `commit-reload!`, one bounded
;; removal cycle reusing the same eviction law as every other reload (a touched
;; source that stages nothing is evicted). No second ledger, no parallel
;; protocol, no new commit path.
;;
;; The wiring is automatic: the re-frame.ui compile hook injects the
;; `custom-notify-fn` closure-define pointing here whenever the WATCHED dev
;; build did not claim shadow's one `:build-notify` seam itself (see
;; `re-frame.ui.compiler.build-hook/wire-removed-source-notify`). A build that
;; DOES claim it keeps it — shadow has exactly one receiver slot — and
;; removed-source eviction degrades to the pre-existing bound (stale rows until
;; the next full page load) unless the app's own receiver delegates:
;; `(re-frame.ui.rules/shadow-build-notify msg)`.
;;
;; Host ordering is deliberately immaterial. The browser client runs the
;; receiver BEFORE the reload chain settles (source fetches are async); the
;; node client runs it AFTER the chain committed (imports are synchronous). Both
;; are the same case here: no cycle is open when the receiver runs, so the
;; removal cycle opens and commits on its own and shadow's own chain is
;; untouched. A cycle found OPEN here is a predecessor an aborted load stranded
;; (shadow skips `^:dev/after-load` when a load task throws); opening the
;; removal cycle performs exactly the supersession the next before-load would,
;; with the same loud report (`report-unpaired-cycle!`). Under the unsupported
;; async-lifecycle overlap (§Overlapping cycles above) the degradation is the
;; documented one — stale classification until the next full reload.
;;
;; Dev-only: the define is injected only into a watched dev build, and the body
;; folds away with the ledger under `:advanced` (rf2-k9yuy).
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn shadow-build-notify
     "shadow-cljs `:build-notify` receiver (dev only): reconcile this build's
     committed ledger rows against the successful build's authoritative
     `:build-sources` membership (`:info :sources` on the `:build-complete`
     broadcast), evicting the rows of every namespace the saved graph REMOVED.
     A deleted or renamed-away source never re-runs, so the SHADOW_NS_RESET
     producer can never report it — this broadcast is the only authority that
     can, and it is the same membership the compile-side registries fold at
     finish (rf2-4vm19).

     Wired automatically by the re-frame.ui compile hook unless the build
     claims `:devtools {:build-notify …}` itself; an app's own receiver can
     delegate by calling this with the same `msg`.

     Only `:build-complete` carries an accepted graph; every other notify type
     (`:build-failure`, `:build-start`, `:build-init`) is ignored, so a failed
     compile/evaluation leaves the last-known-good ledger untouched and the
     successful retry's `:build-complete` converges it."
     [{:keys [type info] :as _msg}]
     (when reload-ledger?
       (when (= :build-complete type)
         (let [build-id (current-build-id)
               member?  (into #{}
                              (mapcat (fn [{:keys [ns provides]}]
                                        (or (seq provides) (when ns [ns]))))
                              (:sources info))
               removed  (into []
                              (keep (fn [[b n]]
                                      (when (and (= b build-id)
                                                 (not (member? n)))
                                        n)))
                              (keys (::sources @ledger-state)))]
           (when (seq removed)
             ;; ONE bounded removal cycle through the existing seam. The removed
             ;; sources by definition cannot re-register during it, so the
             ;; commit evicts exactly their rows and touches nothing else; a
             ;; removal-only reconciliation can never introduce a cross-source
             ;; contradiction, so this commit never raises.
             (notify-reload! build-id)
             (note-reloaded-sources! removed build-id)
             (commit-reload! build-id)))))
     nil))

(defn reset-custom-elements!
  "Test support: clear the runtime custom-element registry, the per-source
  ledger, and any in-flight reload cycles. In `:advanced` production there is no
  ledger to clear, so only the aggregate is reset (rf2-k9yuy)."
  []
  (reset! custom-elements {})
  (when reload-ledger?
    (reset! ledger-state empty-ledger))
  nil)

(defn in-flight-cycles
  "Test support: the set of build-ids with a reload cycle currently OPEN. A
  quiescent ledger has none; a lingering entry after a commit settles is an
  orphan cycle. Always empty where there is no ledger (`:advanced` production
  can never open a cycle). Observability only — not a consumer API
  (classification reads `custom-element-properties`)."
  []
  (if reload-ledger?
    (set (keys (::cycles @ledger-state)))
    #{}))

(defn ledger-sources
  "Test support: the runtime SOURCE-MEMBERSHIP graph — the set of `[build-id
  ns-sym]` the ledger currently holds a committed contribution for. This is the
  runtime mirror of the compile side's `:build-sources` membership (see
  `ledger-state`): a saved source that is removed or renamed away drops out of
  this set the moment its reload cycle is reconciled (`reconcile-sources`
  evicts a touched-but-unstaged row), a rename swaps the old ns key for the new,
  and a same-namespace file move keeps its key. Always empty where there is no
  ledger (`:advanced` production allocates none). Observability only — not a
  consumer API (classification reads `custom-element-properties`)."
  []
  (if reload-ledger?
    (set (keys (::sources @ledger-state)))
    #{}))

(defn projected-aggregate
  "Test support: the aggregate the public `custom-elements` atom MUST equal — the
  pure projection of the current ledger `::sources`. A settled ledger's published
  aggregate always equals this; a divergence is a torn or clobbered projection.
  Where there is no ledger (`:advanced` production) registrations write straight
  through, so the live aggregate IS the projection. Observability only.

  A ledger holding a contradiction projects to `nil` — a loud divergence from
  any live aggregate, which is the intended reading: there IS no valid manifest
  for such a ledger, and the law is that no path invents one."
  []
  (if reload-ledger?
    (:aggregate (aggregate (::sources @ledger-state)))
    @custom-elements))

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

;; ---------------------------------------------------------------------------
;; ui/spread-safe — the literal safe-spread policy (owned-key deny, EVERY build)
;;
;; A component library forwards a consumer's runtime attr map onto an internal
;; element through `(ui/spread-safe owned caller)`. `owned` is the component's
;; LITERAL props (the compiler proves the controlled site and keeps the sync
;; door); `caller` is the forwarded runtime map. The structural/controlled/
;; identity keys below, PLUS the component's owned `:on-*` handler keys, are
;; denied to `caller` in every build — a runtime offender throws here, never
;; goog.DEBUG-gated, so the guarantee survives an advanced build.
;; ---------------------------------------------------------------------------

(def spread-safe-denied-structural
  "The structural / controlled / identity keys a `ui/spread-safe` CALLER map
  may never carry. `:key`/`:ref` are structural identity slots (keys are
  literal at the site, refs are a reserved React slot); `:value`/`:checked`
  are the controlled-input contract the sync-door proof depends on. A caller
  that could set them would clobber owned state, so they are denied in every
  build (not dev-only). The component's own `:on-*` handler keys JOIN this set
  per call."
  #{:key :ref :value :checked})

(defn caller-key-name
  "The CANONICAL author NAME of a `ui/spread-safe` caller key — `(name k)`,
  the SAME canonicalization both host converters apply (namespace dropped, so
  `:caller/ref` -> \"ref\", `\"ref\"` -> \"ref\", `'ref` -> \"ref\"). Returns
  nil when `k` is NOT a nameable attribute key (a keyword, string, or symbol);
  a non-nameable key is malformed — rejected by `assert-safe-caller!` — not
  merely 'not denied'. The author NAME drives BOTH the emitted-slot the deny law
  compares (`caller-key-slot`) and the canonical keyword an accepted key is
  rewritten to (`(keyword (name k))`), so denial and conversion never diverge."
  [k]
  (when (or (keyword? k) (string? k) (symbol? k))
    (name k)))

(defn caller-key-slot
  "The React/DOM prop-name SLOT the host converters actually emit a
  `ui/spread-safe` caller key into — the ONE canonicalization the owned-key deny
  law compares (against the structural slots and the owned handler families),
  driven by `(name k)` EXACTLY as the converters classify: an author `on-*` name
  camelizes to its React handler slot (`:on-change` / `\"on-change\"` ->
  `onChange`); everything else — already-camel handler spellings like
  `\"onChange\"` / `\"onChangeCapture\"` included — resolves through the
  react-dom attr table (`react-prop-name`, so `:class` -> `className`). Returns
  nil for a NON-nameable key (rejected as malformed upstream), never nil for a
  nameable one. NOT the property-camel slot for a custom-element `:properties`
  name (that camelization is tag-scoped and irrelevant to the deny, which never
  targets properties) — this is the deny/structural/handler classification."
  [k]
  (when-some [n (caller-key-name k)]
    (if (str/starts-with? n "on-")
      (react-event-name n false)
      (react-prop-name n))))

(def ^:private spread-safe-denied-structural-slots
  "The canonical emitted SLOTS of the denied structural/controlled keys — the
  form both host converters reduce a caller key to via `caller-key-slot`. The
  deny law compares canonical slots, so `:key`/`:ref`/`:value`/`:checked` AND
  every alternate spelling that resolves to one of those slots (`:caller/ref`,
  `\"ref\"`, `'ref`) are denied uniformly (rf2-izep3/rf2-xdvob)."
  (into #{} (keep caller-key-slot) spread-safe-denied-structural))

(defn- owned-handler-slots
  "The React handler SLOTS an owned `:on-*` family occupies — BOTH the bubble
  (`onChange`) and capture (`onChangeCapture`) phases, whichever phase the owned
  handler itself uses. A caller may install NEITHER phase on an owned event, so
  the deny law reduces every owned handler key to its two-slot family: an
  already-camel `\"onChange\"`, a capture `\"onChangeCapture\"`, a kebab
  `on-change-capture`, and every namespaced/string/symbol alias all resolve to a
  slot in this set (rf2-xdvob)."
  [owned-handler-keys]
  (into #{}
        (mapcat (fn [h]
                  (when-some [n (caller-key-name h)]
                    (when (str/starts-with? n "on-")
                      [(react-event-name n false)
                       (react-event-name n true)]))))
        owned-handler-keys))

(defn spread-safe-denied-key?
  "Is caller key `k` denied to a `ui/spread-safe` caller map, given the
  component's `owned-handler-keys` (the literal `:on-*` keys of its owned map)?
  Compares the caller key's canonical emitted SLOT (`caller-key-slot`) against
  the denied structural/controlled slots and the owned handler FAMILY slots
  (bubble + capture), so an alternate spelling — kebab, already-camel
  (`\"onChange\"` / `\"onChangeCapture\"`), string, symbol, or namespaced — that
  emits into an owned/structural slot cannot bypass the law (rf2-xdvob). A
  non-nameable key is not 'denied' here (it is rejected as malformed by
  `assert-safe-caller!`); returns false so the literal/compile-time path reports
  it through its own channel."
  [k owned-handler-keys]
  (boolean
   (when-some [slot (caller-key-slot k)]
     (or (contains? spread-safe-denied-structural-slots slot)
         (contains? (owned-handler-slots owned-handler-keys) slot)))))

(defn- spread-safe-denial-reason [slot owned-slots]
  (cond
    (= "key" slot)                        "a structural identity slot (keys are literal at the site)"
    (= "ref" slot)                        "a reserved React ref slot"
    (contains? #{"value" "checked"} slot) "the controlled-input contract the sync door proves"
    (contains? owned-slots slot)          "an owned event handler the component controls"
    :else                                 "owned by the component"))

(defn assert-safe-caller!
  "EVERY-BUILD guard + canonicalizer for `(ui/spread-safe owned caller)`. The
  `caller` must be an author-space attr MAP (or nil = empty); each key must be a
  nameable attribute key; then its canonical emitted SLOT (`caller-key-slot`) is
  compared against the denied structural/controlled slots and the owned handler
  FAMILY slots (bubble + capture). Alternate spellings — kebab, already-camel
  (`\"onChange\"` / `\"onChangeCapture\"`), a namespaced keyword, string, or
  symbol — that resolve to an owned/structural slot are therefore denied
  uniformly, and a non-map caller (e.g. a sequence of pairs) or a non-nameable
  key can no longer slip through. A non-map caller, a non-nameable key, or a
  denied key throws `:rf.error/ui-tree-malformed`, consistently on BOTH hosts.
  NOT `goog.DEBUG`-gated — the denial is a production invariant a component
  library relies on, so it survives an advanced build.

  Returns the CANONICALIZED caller map: every ACCEPTED key is rewritten to its
  author-canonical keyword (`(keyword (name k))`), so an alternate spelling lands
  in the SAME emitted slot as the literal author keyword and the emitted prop set
  matches the classification on BOTH hosts — a caller `:ns/class`/`\"class\"`
  routes through `:class` composition, and a caller ordinary key dedupes against
  an owned collision by the same identity (rf2-xdvob). A nil caller returns nil."
  [caller owned-handler-keys]
  (if (nil? caller)
    nil
    (do
      (when-not (map? caller)
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/spread-safe
         (str "(ui/spread-safe owned caller) — the caller must be an author-space "
              "attr MAP (or nil), not a " (name (:type (error/diag-value-summary caller)))
              ". A non-map caller (e.g. a sequence of pairs) cannot be canonicalized "
              "and checked against the owned-key deny law; pass a map literal or a "
              "map-valued expression")
         {:extra {:caller (error/diag-value-summary caller)}}))
      (let [owned-slots (owned-handler-slots owned-handler-keys)]
        (reduce-kv
         (fn [acc k v]
           (let [slot (caller-key-slot k)]
             (cond
               (nil? slot)
               (error/throw-error!
                :rf.error/ui-tree-malformed 're-frame.ui/spread-safe
                ;; `error/pr-form` / `error/safe-form`, not bare `pr-str`: this is
                ;; the ONE branch of this guard reached with a value that is not
                ;; provably nameable — `caller-key-slot` returned nil, so `k` is a
                ;; foreign object used as a map key, and a cyclic one (a React
                ;; context) would make `pr-str` blow the stack instead of
                ;; producing this error (rf2-30dc7). The two sibling arms below
                ;; are gated on a NON-nil slot, so their `k` provably IS nameable
                ;; and stays a bare `pr-str`.
                (str "(ui/spread-safe owned caller) — the caller attr key " (error/pr-form k)
                     " is not a nameable attribute key (a keyword, string, or symbol), "
                     "so it has no canonical DOM/React name to check against the "
                     "owned-key deny law. Use a keyword attr key")
                {:extra {:key (error/safe-form k)}})

               (or (contains? spread-safe-denied-structural-slots slot)
                   (contains? owned-slots slot))
               (error/throw-error!
                :rf.error/ui-tree-malformed 're-frame.ui/spread-safe
                (str "(ui/spread-safe owned caller) — the caller attr map may not "
                     "carry " (pr-str k) ": it is "
                     (spread-safe-denial-reason slot owned-slots)
                     ", denied in every build so it can never clobber an owned prop "
                     "(an alternate spelling — kebab, already-camel, a namespaced "
                     "keyword, string, or symbol — canonicalizes to the same slot and "
                     "is denied too). Forward it through the visible-cost (ui/spread "
                     "base overrides) instead, or drop it from the caller map")
                {:extra {:key k}})

               :else
               (assoc acc (keyword (caller-key-name k)) v))))
         {} caller)))))

;; ---------------------------------------------------------------------------
;; ui/spread + ui/spread-safe — the RUNTIME rejected-spelling deny (EVERY build)
;;
;; `rejected-prop-spellings` ("one spelling per name, and it is a node variant")
;; is enforced on LITERAL props by the analyzer (`check-rejected-spelling!`),
;; which cannot see a prop map assembled at RUNTIME. A `(ui/spread base
;; overrides)` map therefore reached the host converters unchecked, and
;; `react-prop-name` passes an unrecognized name VERBATIM — so
;; `{:dangerouslySetInnerHTML #js {:__html s}}` landed in React's raw-markup
;; slot with NO visible `(ui/html …)` trust assertion anywhere in the source
;; (rf2-5pr75). The deny below closes that at the ONE runtime seam both hosts
;; fold a spread map through (`runtime/convert-prop-map!` on CLJS,
;; `tree/fold-dyn-entry` on the JVM), so `ui/spread` and the `ui/spread-safe`
;; caller map are covered together.
;; ---------------------------------------------------------------------------

(def ^:private rejected-prop-slots
  "`rejected-prop-spellings` reduced to the canonical emitted SLOTS the host
  converters actually write into (`caller-key-slot` — the same rf2-xdvob
  canonicalization the spread-safe owned-key deny compares), each mapped to the
  didactic replacement its compile error names.

  Comparing SLOTS rather than literal keywords is what makes the deny TOTAL for
  the one spelling that carries a trust boundary: every alias of React's
  raw-markup prop — `\"dangerouslySetInnerHTML\"`, `'dangerouslySetInnerHTML`,
  the namespaced `:x/dangerouslySetInnerHTML` — reduces to the SAME slot and is
  denied, while a spelling that reduces to any OTHER slot (`:dangerouslysetinnerhtml`)
  cannot reach React's slot at all and needs no deny. No legitimate prop
  collides: `:class` slots to `className` and `:for` to `htmlFor`, distinct from
  the rejected `:class-name` / `:html-for`."
  (into {}
        (keep (fn [[k replacement]]
                (when-some [slot (caller-key-slot k)] [slot replacement])))
        rejected-prop-spellings))

(defn spread-rejected-key?
  "Is runtime prop key `k` a rejected spelling — the runtime twin of the
  analyzer's literal `check-rejected-spelling!`? Compares `k`'s canonical
  emitted slot, so every alias reaches the same verdict on both hosts. A
  non-nameable key has no slot and is not 'rejected' here (the converters
  report it through their own channel)."
  [k]
  (contains? rejected-prop-slots (caller-key-slot k)))

(defn assert-spread-prop-key!
  "EVERY-BUILD deny for ONE prop key arriving through a RUNTIME prop map — a
  `(ui/spread base overrides)` map or a `(ui/spread-safe owned caller)` caller
  map. A rejected spelling throws `:rf.error/ui-tree-malformed`, NOT
  `goog.DEBUG`-gated, so an `:advanced` production build is exactly as safe as
  dev — the same production-reachability the sibling `assert-safe-caller!`
  owned-key deny has, and the only defensible choice for a key whose whole
  meaning is 'bypass HTML escaping'.

  The key is denied on PRESENCE, value irrelevant: `rejected-prop-spellings`
  rejects the NAME, so a nil value is still an illegal spelling, and a
  value-dependent guard would be a trust check with a hole in it.

  DENY, not silently drop. Dropping would be fail-safe against injection but
  invisible twice over: an author who MEANT raw markup would ship a silently
  empty element, and an author who did NOT would never learn their forwarded
  prop map carries a smuggled key. The message names the escape — `(ui/html …)`,
  the visible trust assertion — exactly as the compile error does.

  `re-frame.ui` sanitises NOTHING: the fix is not to clean the markup but to
  require it be asserted at a visible site."
  [k]
  (when-some [replacement (get rejected-prop-slots (caller-key-slot k))]
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/spread
     (str "a runtime spread prop map may not carry " (pr-str k)
          ": it is not a prop — one spelling per name, ambiguities removed — and "
          "the rule holds for a map assembled at RUNTIME exactly as it does for "
          "literal props, which the compiler checks and this map it cannot see. "
          "Use " replacement ". An alternate spelling (kebab, already-camel, a "
          "namespaced keyword, string, or symbol) canonicalizes to the same "
          "emitted slot and is denied too, so raw markup can never reach the DOM "
          "without the visible (ui/html ...) trust assertion at the site")
     {:extra {:key k}})))
