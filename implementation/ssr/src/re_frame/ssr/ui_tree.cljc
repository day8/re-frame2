(ns re-frame.ssr.ui-tree
  "The S5 structural-tree -> HTML serialiser (`re-frame.ssr/emit-ui-tree`).
  Owning contract: [Spec 004B §The SSR consumption boundary]
  (../../../spec/004B-UI-Tree-and-Conversion.md). Filed by rf2-3omxp
  (item 2, the code half); the spec half shipped as rf2-vxgfnd.97.2.

  ## What this is, and what it is NOT

  `emit-ui-tree` receives an ALREADY-RENDERED version-1 structural tree
  (the value a compiled-view substrate's JVM render produced on the
  JVM) and folds it to an HTML string. It calls NOTHING: no view is
  invoked, no subscription is resolved, no frame is bound. Every view has
  run and every dynamic value is already a literal in the tree — a view
  invoked here would be a SECOND render against a frame whose state has
  moved past the one the tree was built from (004B §What the seam
  consumes). It is a pure function of its arguments, JVM-runnable, and
  deterministic to the byte.

  It emits the markup for ONE root's tree only. Manifests, payloads,
  ledgers, containers, root identity, and the HTTP response live in the
  SSR artefact's other surfaces (004B §What the seam does not do); this
  seam neither writes nor reads them.

  ## The version gate — fail loud FIRST, before any emission

  The root `:rf.ui/tree-version` is validated BEFORE any traversal. A
  MISSING, NON-INTEGER, or UNSUPPORTED version throws
  `:rf.error/ssr-ui-tree-version-unsupported` with `{:got <received>
  :supported #{1}}` — an OPERATIONAL deploy-skew condition (the server is
  too old for the tree it was handed), deliberately DISTINCT from the
  code-bug `:rf.error/ui-tree-malformed` a malformed node past the gate
  throws. Two ids, one per failure class: version = the new id;
  structural = the shared tree-consumer id (004B §The SSR consumption
  boundary; ruling on rf2-vxgfnd.97, Fable 2026-07-19). A Tier-1 normalize
  gate REUSES the shared id deliberately — a different boundary, unchanged.

  ## One table, carried by the seam

  Emission applies the SERIALISATION HALF of [the DOM conversion table]
  (../../../spec/004B-UI-Tree-and-Conversion.md#the-dom-conversion-table--normative-rows)
  and adds nothing to it. The rows below were once a deliberate verbatim
  COPY of `re-frame.ui.rules` / `re-frame.ui.semantic`, carried because the
  Independence rule forbids `re-frame.ssr` requiring `re-frame.ui`, and
  pinned byte-for-byte against that source by a sibling test. **That
  substrate has been retired (rf2-0yp7w), so these tables are now the
  ORIGINALS and the pin is gone.** Nothing is duplicated any more and
  nothing upstream can drift from them.

  What keeps them honest without a second copy to compare against: the
  react-dom parity corpus (004B §Emission is pure), which tests the rows
  against react-dom's documented behaviour — the authority both copies
  were always transcribing — plus the row-level assertions in
  `re-frame.ssr.emit-ui-tree-cljs-test`. The react-dom version these rows
  track is named per-table below; that version string is the thing to
  check a row against, not another namespace.

  Scope note (rf2-3omxp): this seam is the tree->HTML CODE. The FULL
  react-dom byte-parity corpus (004B §Emission is pure) is a separate S5
  leaf; only the conversion-table rows the seam actually needs ship here."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.html-helpers :as html]))

;; ---------------------------------------------------------------------------
;; Version gate — the headline. Validated FIRST, before any emission.
;; ---------------------------------------------------------------------------

(def supported-tree-versions
  "The node-schema versions `emit-ui-tree` accepts. `#{1}` is a versioning
  scheme with exactly one implementation; bumping the integer edits this
  set DELIBERATELY, in lockstep with the walk below. The Tier-1 gate over
  the same field carries the matching set."
  #{1})

(defn- validate-tree-version!
  "Fail loud on a MISSING / NON-INTEGER / UNSUPPORTED root
  `:rf.ui/tree-version` BEFORE any emission, so a future-version or corrupt
  tree never emits plausible markup. Throws
  `:rf.error/ssr-ui-tree-version-unsupported` — the SSR-seam sibling of the
  shared `:rf.error/ui-tree-malformed` (004B §The SSR consumption
  boundary). `integer?` matches the Tier-1 gate's predicate."
  [tree]
  (let [tree-version (:rf.ui/tree-version tree)]
    (when-not (and (integer? tree-version)
                   (contains? supported-tree-versions tree-version))
      (error/throw-error!
        :rf.error/ssr-ui-tree-version-unsupported
        'rf.ssr/emit-ui-tree
        (str "re-frame.ssr/emit-ui-tree requires a root :rf.ui/tree-version in "
             (pr-str supported-tree-versions) " — got " (error/pr-form tree-version)
             ". The serialiser validates the version FIRST, before any emission, so "
             "a future-version or corrupt tree never emits plausible markup. This is "
             "an OPERATIONAL deploy-skew condition (the server is too old for the tree "
             "it was handed), distinct from the code-bug :rf.error/ui-tree-malformed. "
             "Align the deployed producer/consumer versions (roll the server forward / "
             "fix deploy skew) or fall back to client render — nothing is wrong with "
             "the view code.")
        {:recovery :no-recovery
         ;; rf2-9s68n — `tree-version` is read straight off a caller-supplied tree, so
         ;; it crosses `error/safe-form` on the ex-data side exactly as
         ;; it crosses `error/pr-form` on the message side above.
         :extra    {:got (error/safe-form tree-version)
                    :supported supported-tree-versions}}))))

;; ---------------------------------------------------------------------------
;; Conversion table — the ORIGINAL rows (rf2-0yp7w retired the substrate
;; these were once copied from and pinned against). Authority: react-dom.
;; ---------------------------------------------------------------------------

(def standard-names
  "react-dom 19.2.0 `possibleStandardNames`, reduced to non-identity
  entries and keyed by both the kebab and the hyphen-collapsed lowercase
  form. Lookup rule (`react-prop-name`): data-*/aria-* verbatim; exact key
  hit; collapsed-key hit; else verbatim (React's unrecognized-name
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

(def dom-attr-aliases
  "React prop name -> the DOM attribute name react-dom/server writes when
  the two differ — the serialiser half of the conversion table. Tracks
  react-dom 19.2.0. Everything NOT in this map serialises as the prop name verbatim
  (react-dom 19.2.0 emits e.g. `readOnly` verbatim — HTML attribute names
  are ASCII case-insensitive)."
  (merge (into {}
               (keep (fn [[attribute-key attribute-value]]
                       (when (str/includes? attribute-key "-")
                         [attribute-value attribute-key])))
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

(defn- remove-hyphens [attribute-name]
  (str/replace attribute-name "-" ""))

(defn- react-prop-name
  "Author kebab prop NAME (string, no namespace) -> React prop name.
  data-*/aria-* verbatim; recognized names via the react-dom vocabulary
  (kebab or hyphen-collapsed hit); unrecognized names verbatim (React 16+
  pass-through)."
  [attribute-name]
  (if (or (str/starts-with? attribute-name "data-")
          (str/starts-with? attribute-name "aria-"))
    attribute-name
    (or (get standard-names attribute-name)
        (get standard-names (remove-hyphens attribute-name))
        attribute-name)))

(defn- dom-attr-name
  "Author kebab attr NAME (string, no namespace) -> the serialised DOM
  attribute name. data-*/aria-* verbatim; otherwise the React prop name
  mapped through `dom-attr-aliases`, falling back to the prop name
  verbatim."
  [attribute-name]
  (if (or (str/starts-with? attribute-name "data-")
          (str/starts-with? attribute-name "aria-"))
    attribute-name
    (let [react-name (react-prop-name attribute-name)]
      (get dom-attr-aliases react-name react-name))))

(def void-tags
  "Tags that self-close and carry no children. react-dom/
  server 19.2.0 throws for children on all of these INCLUDING param and
  keygen (menuitem also rejects children but is not self-closing, so it is
  not here)."
  #{:area :base :br :col :embed :hr :img :input :link :meta :param
    :keygen :source :track :wbr})

;; The three boolean-value rosters live in `re-frame.ssr.html-helpers` —
;; ONE roster read by both SSR serialisers, so this one and the hiccup
;; emitter cannot drift apart on which names are which class (rf2-r9kf).
;; The two PIPELINES stay separate per 004B: only the rosters travel, and
;; each serialiser keeps its own attribute-name handling and escape.
(def boolean-attrs
  "HTML boolean attributes: true -> presence (attr=\"\"), false/absent ->
  omitted. Tracks react-dom 19.2.0; keyed by the hyphen-collapsed
  lowercase author name."
  html/boolean-attrs)

(def booleanish-attrs
  "true/false -> \"true\"/\"false\", never omitted. Tracks react-dom 19.2.0."
  html/booleanish-attrs)

(def overloaded-boolean-attrs
  "true -> bare presence, false -> omitted, other values stringify.
  Tracks react-dom 19.2.0."
  html/overloaded-boolean-attrs)

(def property-only-attrs
  "Names React never serialises to markup on NON-custom elements. Tracks
  react-dom 19.2.0; kept EMPTY as the named home for any future member the parity
  corpus finds."
  #{})

(defn escape-html
  "Full 5-char escaping (& < > \" ') for text and attribute values —
  trusted-HTML (`:html`) nodes are the single bypass. Matches React's
  escapeTextForBrowser (
  `'` -> `&#x27;`, NOT the `&#39;` `re-frame.ssr.html-helpers` uses for the
  hiccup tier)."
  [text]
  (-> (str text)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

;; Raw-text elements — <script>/<style> content is HTML RAW TEXT (rf2-2dh3b).
;; The serialisation rule (`html/raw-text-tags` + `html/escape-raw-text`) is
;; the ONE shared implementation in `re-frame.ssr.html-helpers`, called
;; identically by this serialiser and both hiccup emitters so every SSR path
;; emits byte-identical raw text for the same author content (rf2-xbvzh).

;; ---------------------------------------------------------------------------
;; Newline-eating elements — leading-LF compensation (rf2-z05di)
;; ---------------------------------------------------------------------------

(def newline-eating-tags
  "The HTML elements whose parser DROPS one leading LF immediately after the
  start tag — `<pre>`, `<listing>`, `<textarea>`. react-dom/server 19.2
  compensates by prefixing one extra LF so content that begins with a newline
  survives the parse round-trip."
  #{"pre" "listing" "textarea"})

(def trusted-html-newline-eating-tags
  "The newline-eating elements whose SOLE trusted-markup (`{:html s}`) child
  React ALSO leading-LF-compensates — `<pre>`/`<listing>` only. `<textarea>` is
  deliberately absent: react-dom/server 19.2 rejects a trusted-markup child on a
  textarea outright (its content is `value`/`defaultValue` or a text child), so
  a `{:html …}` child never survives to be compensated there — the seam rejects
  it through `:rf.error/ui-tree-malformed` (rf2-ib4fd). A textarea's STRING
  `:value` beginning with LF is still compensated via `newline-eating-tags`."
  #{"pre" "listing"})

(defn- sole-newline-content
  "The single content STRING React's leading-LF rule applies to, or nil. React
  compensates only when a newline-eating element's body is ONE string: a lone
  text child, OR a lone `{:html s}` trusted-markup child — React's
  `dangerouslySetInnerHTML.__html`, likewise a `typeof … === 'string'` body it
  doctors the same way (rf2-0spji). A multi-child, element, or non-string-`:html`
  body is left untouched. The `{:html s}` arm applies only under the
  trusted-html newline-eating tags (`<pre>`/`<listing>`); a textarea's
  trusted-markup child is rejected upstream and never reaches here (rf2-ib4fd).
  `content` is the element's content as a seq — one entry means a single
  (already text-coalesced) string child, a textarea's `:value`, or a sole
  trusted-markup child."
  [tag-lc content]
  (when (= 1 (count content))
    (let [sole-content (first content)]
      (cond
        (string? sole-content)
        sole-content
        (and (map? sole-content) (string? (:html sole-content))
             (contains? trusted-html-newline-eating-tags tag-lc))
        (:html sole-content)
        :else
        nil))))

(defn leading-newline-compensation
  "-> the compensating LF (`\"\\n\"`) react-dom/server 19.2 prefixes inside a
  newline-eating element (`newline-eating-tags`), or `\"\"` when none is owed.

  HTML parsing eats the FIRST LF immediately after `<pre>`/`<listing>`/
  `<textarea>` (the newline-eating elements). So a tree whose textarea value, pre
  text, or sole trusted-markup (`:html`) body begins with LF would, without
  compensation, parse to a DOM carrying one FEWER newline than authored — an S5
  hydration/correctness gap. React prefixes exactly one LF, but ONLY when the
  element's content is a SINGLE STRING body (its `typeof … === 'string'` guard,
  applied to a string child AND to `dangerouslySetInnerHTML.__html`): multiple or
  element children are left untouched, because the parser's newline-eating still
  applies but React does not doctor a multi-child body."
  [tag-lc content]
  (if (and (contains? newline-eating-tags tag-lc)
           (let [content-string (sole-newline-content tag-lc content)]
             (and (some? content-string)
                  (str/starts-with? content-string "\n"))))
    "\n"
    ""))

;; ---------------------------------------------------------------------------
;; Attribute value serialisation (the value half of the table).
;; ---------------------------------------------------------------------------

(defn- coerce-value
  "Tree attr/text values are canonical strings already (numbers went
  through JS ToString, `:style` px was applied, at tree build). Coerce
  defensively: string verbatim; number via the cross-runtime canonical
  form (`hash/canonical-number` — the same JS-ToString parity the hiccup
  emitter uses); keyword/symbol via `name` (namespace dropped, matching the
  shipped dynamic path); everything else `str`-ed."
  [value]
  (cond
    (string? value)                          value
    (number? value)                          (hash/canonical-number value)
    (or (keyword? value) (symbol? value))    (name value)
    :else                                    (str value)))

(defn- truthy-attr?
  "React's boolean-attribute presence test over TREE-SPACE values: `true`
  -> present; `false` -> absent; a non-empty string is present (`hidden
  \"until-found\"` renders bare presence on react-dom/server 19.2.0)."
  [value]
  (cond
    (boolean? value) value
    (string? value)  (not (str/blank? value))
    :else            (some? value)))

(defn- serialise-attr
  "One author-space `[k v]` -> `[final-name serialised-value]` or nil
  (attribute absent from markup). `:class` / `:style` are handled by
  the caller before this."
  [attribute-key attribute-value]
  (let [attribute-name (name attribute-key)
        collapsed-name (str/lower-case (remove-hyphens attribute-name))]
    (cond
      (str/starts-with? attribute-name "aria-")
      ;; aria-* values ALWAYS stringify — :aria-hidden false ->
      ;; aria-hidden="false", never omitted.
      [attribute-name (if (boolean? attribute-value)
                        (str attribute-value)
                        (coerce-value attribute-value))]

      (str/starts-with? attribute-name "data-")
      ;; data-* verbatim names; boolean values stringify.
      [attribute-name (if (boolean? attribute-value)
                        (str attribute-value)
                        (coerce-value attribute-value))]

      (contains? booleanish-attrs collapsed-name)
      ;; true/false -> "true"/"false", never omitted.
      [(dom-attr-name attribute-name)
       (if (boolean? attribute-value)
         (str attribute-value)
         (coerce-value attribute-value))]

      (contains? overloaded-boolean-attrs collapsed-name)
      ;; true -> bare presence, false -> omitted, other values stringify.
      (cond
        (true? attribute-value)  [(dom-attr-name attribute-name) ""]
        (false? attribute-value) nil
        :else                    [(dom-attr-name attribute-name)
                                  (coerce-value attribute-value)])

      (contains? boolean-attrs collapsed-name)
      ;; presence/absence; presence serialises as attr="".
      (when (truthy-attr? attribute-value)
        [(dom-attr-name attribute-name) ""])

      (boolean? attribute-value)
      ;; a boolean on a non-boolean-class attribute never reaches markup.
      nil

      :else
      [(dom-attr-name attribute-name) (coerce-value attribute-value)])))

(defn- style-map->css
  "A tree `:style` map (kebab keyword -> canonical CSS value string, px
  already applied at tree build) -> the CSS declaration string. Emitted in
  a PINNED TOTAL ORDER (sorted by property name) rather than map iteration
  order (004B §Emission is pure — map iteration order is trusted here
  exactly as little as in the `:class` flag-map row). nil-valued entries
  dropped."
  [style-map]
  (->> style-map
       (keep (fn [[property-name property-value]]
               (when (some? property-value)
                 (str (name property-name) ":" (coerce-value property-value)))))
       sort
       (str/join ";")))

(defn- custom-element-tag? [tag]
  (str/includes? (name tag) "-"))

(defn- element-attr-pairs
  "The author-space `:attrs` map of an element -> a seq of
  `[final-name value-string]` pairs, sorted by final name (pinned total
  order). `:class` -> `class` (already canonical); `:style` -> the CSS
  declaration string; custom-element property-classified props (named by
  `:rf.ui/property-props`) are OMITTED (applied at hydration, never markup)."
  [attrs property-props]
  (->> attrs
       (keep (fn [[attribute-key attribute-value]]
               (cond
                 (contains? property-props attribute-key) nil
                 (= attribute-key :class) (when (some? attribute-value)
                                            ["class" (coerce-value attribute-value)])
                 (= attribute-key :style) (let [css (style-map->css attribute-value)]
                                            (when (seq css) ["style" css]))
                 :else (serialise-attr attribute-key attribute-value))))
       (sort-by first)))

(defn- attrs->string
  "Render an element's `:attrs` as ` name=\"value\"` fragments (leading
  space when non-empty; empty string otherwise). Presence attributes emit
  `name=\"\"`. Values are `escape-html`-escaped."
  [attrs property-props]
  (let [rendered (map (fn [[attribute-name attribute-value]]
                         (str " " attribute-name "=\""
                              (escape-html attribute-value) "\""))
                       (element-attr-pairs attrs property-props))]
    (str/join rendered)))

;; ---------------------------------------------------------------------------
;; Node discrimination + the walk.
;; ---------------------------------------------------------------------------

(def ^:private node-discriminators
  "The structural discriminators this serialiser branches on (004B §The
  node schema owns the variant roster and the pinned order; the table is
  the authority, not a count restated here). A canonical map node carries
  EXACTLY ONE of these — `:tag` / `:view-id` / `:html` — OR none, in which
  case it is a FRAGMENT (discriminated by its `:children`).

  The roster's remaining primary, `:rf.ui/host`, is deliberately absent,
  and its absence IS this seam's host behaviour: a host node carries no
  `:tag`, `:view-id` or `:html`, so it falls to the erasing fragment arm
  and splices its `:children` — which ARE the declared SSR projection, the
  `{:fallback …}` markup or nothing at all for `:client-only`. That is
  exactly the fold 004B §The SSR consumption boundary asks of this
  function, so an arm of its own would be a second way to say it."
  [:tag :view-id :html])

(defn- malformed-node!
  "Fail loud with the SHARED node-malformation id (NOT the version-gate id).
  A malformed node PAST the version gate is a code bug — the closed variant
  set was violated — and reuses `:rf.error/ui-tree-malformed`, the id every
  tree consumer throws (004B §The node schema). `path` is the root-relative
  `get-in` position that LOCATES the offending node.

  rf2-9s68n — `extra` crosses `error/safe-form` HERE, once, for every
  arm: its slots (`:value`, `:got`) carry runtime tree values, and a cyclic
  foreign value riding out in ex-data explodes at a DOWNSTREAM logger /
  error projector / trace sink that `pr-str`s it, which is someone else's
  boundary and strictly harder to diagnose than this one. The callers own
  the MESSAGE half of the same crossing (`error/pr-form`) because the
  string is built before it arrives here. `path` is framework-built —
  `:children` keywords and integers — so it needs no crossing."
  [reason path extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    'rf.ssr/emit-ui-tree
    (str reason " (at tree path " (pr-str path) ")")
    {:extra (assoc (error/safe-form extra) :path path)}))

(declare emit-node mark-selected selected-values)

(defn- emit-children
  "Emit a children vector, threading each child's root-relative path so a
  nested malformation locates the node."
  [children parent-path]
  (str/join
    (map-indexed
      (fn [child-index child]
        (emit-node child (conj parent-path :children child-index)))
      children)))

(defn- emit-raw-text-children
  "Emit the children of a raw-text element (`<script>`/`<style>`) — the raw-text
  half of the child grammar (rf2-0spji). React's model: a raw-text element's body
  is EITHER HTML raw text (string children) OR a single trusted-markup bypass
  (`ui/html` → `dangerouslySetInnerHTML`), never both.

    - all-string content → HTML raw text: emitted verbatim but for the
      context-safe closing-sequence rewrite (`escape-raw-text`), matching
      react-dom/server's string-`children` path (rf2-2dh3b).
    - a sole `{:html s}` child → the trusted-markup bypass: `s` VERBATIM, no
      rewrite — react-dom/server pushes `dangerouslySetInnerHTML.__html` raw. This
      REUSES `emit-node`'s general `:html` row, so the body is byte-identical to a
      `:html` node anywhere else and a non-string `:html` is the SAME shared
      malformed-tree error.

  Any other shape — an element/view-boundary/fragment child, string content mixed
  with a structural child, or several structural children — is NOT raw text and
  must not be stringified (the pre-fix fast path `(str/join (:children el))`
  printed such a child's EDN literally into the script/style body). It fails loud
  through the shared `:rf.error/ui-tree-malformed` path, locating the element."
  [tag-lc children path]
  (cond
    (every? string? children)
    (html/escape-raw-text tag-lc (str/join children))

    (and (= 1 (count children))
         (map? (first children))
         (contains? (first children) :html))
    (emit-node (first children) (conj path :children 0))

    :else
    (malformed-node!
      (str "a <" tag-lc "> raw-text element takes EITHER string content OR a "
           "single trusted-markup (:html) child — never a structural, mixed, or "
           "multi-child body (which the raw-text fast path would otherwise "
           "stringify into the element): " (error/pr-form children))
      path {:value children})))

(defn- transparent-wrapper?
  "A tree node that SPLICES its children into the host's child stream rather
  than nesting them — a view boundary (`{:view-id …}`, HTML has no view
  boundaries, so it erases) or a fragment (a map carrying `:children` and NONE
  of `:tag`/`:view-id`/`:html`). Both erase; the host sees the spliced children
  directly (004B §The node schema). A `:tag` element, a `:html` leaf, and a
  string are OPAQUE — never descended."
  [node]
  (and (map? node)
       (let [discriminators (filterv #(contains? node %) node-discriminators)]
         (or (= discriminators [:view-id])
             (and (empty? discriminators) (contains? node :children))))))

(defn- effective-children
  "The EFFECTIVE child stream a host sees beneath a node, after ERASING every
  transparent wrapper (fragment / view boundary) — each splices its own
  children in-place, recursively — so a leaf nested inside wrappers surfaces
  into the stream exactly as the serialiser will emit it. Each entry is
  `[node real-path]`, the root-relative `get-in` path that LOCATES the
  (possibly spliced) leaf for a precise diagnostic. Used to validate a
  `<textarea>`'s content against its host child contract at the ACTUAL offending
  path, not merely its immediate children (rf2-ib4fd)."
  [children parent-path]
  (vec
    (mapcat
      (fn [child-index child]
        (let [child-path (conj parent-path :children child-index)]
          (if (transparent-wrapper? child)
            (effective-children (:children child) child-path)
            [[child child-path]])))
      (range)
      children)))

(defn- reject-textarea-content!
  "rf2-ib4fd — a `<textarea>`'s content is host-divergent unless it is a single
  text child OR its `:value`/`:default-value`. Validate the EFFECTIVE child
  stream (after transparent fragment / view-boundary splicing) so a nested
  trusted-markup (`:html`) leaf, a structural element child, several children,
  or a value-plus-child shape fails loud at its ACTUAL path through the SHARED
  malformed-tree path — rather than emitting a body react-dom/server 19.2
  rejects (`dangerouslySetInnerHTML` / value+children) or misrenders (`[object
  Object]`). A sole string child, and `:value` alone, stay valid.

  `textarea-value` is the textarea's already-resolved `:value`/`:default-value`, or nil;
  `raw-children` the element's source children (carried in ex-data); `path` the
  element's root-relative path (each spliced leaf carries its own real path)."
  [textarea-value raw-children path]
  (let [effective-child-entries (effective-children raw-children path)]
    (cond
      (and (some? textarea-value) (seq effective-child-entries))
      (let [[child child-path] (first effective-child-entries)]
        (malformed-node!
          (str "a <textarea> takes its content from EITHER :value / "
               ":default-value OR a single text child, never both — "
               "react-dom/server 19.2 rejects a textarea given a value AND a "
               "child: " (error/pr-form child))
          child-path {:value raw-children}))

      (> (count effective-child-entries) 1)
      (let [[_ second-child-path] (second effective-child-entries)]
        (malformed-node!
          (str "a <textarea> takes at most ONE child, but "
               (count effective-child-entries)
               " reached it after splicing — react-dom/server 19.2 rejects a "
               "textarea with more than one child: "
               (error/pr-form (mapv first effective-child-entries)))
          second-child-path {:value raw-children}))

      (= 1 (count effective-child-entries))
      (let [[child child-path] (first effective-child-entries)]
        (cond
          (and (map? child) (contains? child :html))
          (malformed-node!
            (str "a <textarea> cannot carry a trusted-markup (:html) child — "
                 "react-dom/server 19.2 rejects dangerouslySetInnerHTML on a "
                 "textarea (its content is value/defaultValue or a text child); "
                 "supply the text via :value or a string child: "
                 (error/pr-form child))
            child-path {:value raw-children})

          (and (map? child) (contains? child :tag))
          (malformed-node!
            (str "a <textarea> takes a single TEXT child, not a structural "
                 "element — react-dom/server 19.2 renders an element child as "
                 "\"[object Object]\"; supply the text via :value or a string "
                 "child: " (error/pr-form child))
            child-path {:value raw-children}))))))

(defn- emit-element
  "Emit an element node. Applies the form-control special forms
  (`:default-value`/`:default-checked` -> `value`/`checked`; `:value` on
  `:textarea` -> the text child; `:value` on `:select` -> `selected` on the
  matching option), then the attribute conversion, then children. `:events`
  and `:key` have no HTML presence and are never read here."
  [element path]
  (let [tag                 (:tag element)
        tag-name            (name tag)
        normalised-tag-name (str/lower-case tag-name)
        void?               (contains? void-tags (keyword normalised-tag-name))
        raw-text?           (contains? html/raw-text-tags normalised-tag-name)
        property-props      (if (custom-element-tag? tag)
                              (set (or (:rf.ui/property-props element) #{}))
                              #{})
        ;; form-control pre-pass (004B §Property-only and form-control
        ;; special forms): default-value/default-checked serialise as
        ;; value/checked.
        attrs (reduce (fn [normalised-attrs [source-key target-key]]
                        (if (contains? normalised-attrs source-key)
                          (-> normalised-attrs
                              (dissoc source-key)
                              (assoc target-key (get normalised-attrs source-key)))
                          normalised-attrs))
                      (or (:attrs element) {})
                      [[:default-value :value] [:default-checked :checked]])
        textarea?     (= tag :textarea)
        select?       (= tag :select)
        textarea-value (when textarea? (get attrs :value))
        select-value   (when select? (get attrs :value))
        ;; textarea/select :value never serialises as a `value` attribute.
        attrs     (cond-> attrs (or textarea? select?) (dissoc :value))
        open-tag  (str "<" tag-name (attrs->string attrs property-props))]
    ;; rf2-ib4fd — a <textarea>'s content is host-divergent unless it is a single
    ;; text child or its :value/:default-value. Validate the EFFECTIVE child
    ;; stream (after transparent fragment / view-boundary splicing), not merely
    ;; the immediate children: a nested trusted-markup leaf, a structural child,
    ;; several children, or a value-plus-child shape fails loud at its actual
    ;; path rather than emitting a body react-dom/server 19.2 rejects/misrenders.
    (when textarea?
      (reject-textarea-content! textarea-value (:children element) path))
    (cond
      void?          (str open-tag ">")
      ;; rf2-2dh3b — <script>/<style> content is HTML raw text: emit it
      ;; unescaped (only the closing-sequence escape), matching react-dom/server.
      ;; rf2-0spji — but honor a sole `{:html s}` child (the ui/html trusted
      ;; bypass) verbatim, and fail loud on any other structural child instead of
      ;; stringifying it.
      raw-text?      (str open-tag ">"
                          (emit-raw-text-children normalised-tag-name
                                                  (:children element) path)
                          "</" tag-name ">")
      ;; rf2-z05di — a textarea :value beginning with LF needs the compensating
      ;; leading LF the parser will eat back off (single-string content).
      (some? textarea-value)
      (let [coerced-textarea-value (coerce-value textarea-value)]
        (str open-tag ">"
             (leading-newline-compensation normalised-tag-name
                                           [coerced-textarea-value])
             (escape-html coerced-textarea-value) "</" tag-name ">"))
      :else
      (let [children (if (some? select-value)
                       (mark-selected (:children element)
                                      (selected-values select-value))
                       (:children element))]
        ;; rf2-z05di — <pre>/<listing>/<textarea> with a single string child
        ;; beginning with LF get the one compensating LF react-dom/server emits.
        (str open-tag ">"
             (leading-newline-compensation normalised-tag-name children)
             (emit-children children path) "</" tag-name ">")))))

(defn- node-text
  "Concatenated text content of a RAW element/fragment node — its text
  descendants in document order (option-label matching for the select
  `:value` row)."
  [node]
  (apply str (map #(if (string? %) % (node-text %)) (:children node))))

(defn- selected-values
  "The SET of option values a `<select>`'s tree `:value` selects.

  A scalar selects one option. A native `<select multiple>` carries a
  COLLECTION — its selection is the list of chosen option values, which is
  the one attribute value in the tree that is not a scalar — and selects
  every option named in it. Both answer a set, so the marking below is one
  membership test rather than two shapes of comparison."
  [select-value]
  (if (vector? select-value)
    (into #{} (map coerce-value) select-value)
    #{(coerce-value select-value)}))

(defn- mark-selected
  "The `:value`-on-`:select` row: inject `:selected true` into the option
  child(ren) whose value (their `:value` attr, else their text content) is
  in `selected`. Recurses into non-option element children (e.g.
  `:optgroup`). Operates at the raw-tree (author-space) level."
  [children selected-value-set]
  (mapv (fn [child]
          (cond
            (and (map? child) (= :option (:tag child)))
            (let [option-value (coerce-value
                                 (or (get-in child [:attrs :value])
                                     (node-text child)))]
              (if (contains? selected-value-set option-value)
                (assoc-in child [:attrs :selected] true)
                child))

            (and (map? child) (:tag child) (:children child))
            (assoc child :children
                   (mark-selected (:children child) selected-value-set))

            :else child))
        children))

(defn- emit-node
  "-> the HTML string this node contributes. Discrimination is CLOSED and
  UNAMBIGUOUS (004B §The node schema): a string is text (escaped); a map
  carries EXACTLY ONE of `:tag` / `:view-id` / `:html`, or none plus
  `:children` (a fragment). Two-plus discriminators, or none with no
  `:children`, is malformed and fails loud with the SHARED id. View
  boundaries are ERASED (children splice into the stream — HTML has no view
  boundaries); trusted-HTML writes verbatim."
  [node path]
  (cond
    (string? node)
    (escape-html node)

    (map? node)
    (let [discriminators (filterv #(contains? node %) node-discriminators)]
      (cond
        (> (count discriminators) 1)
        (malformed-node!
          (str "a tree node carries multiple node discriminators "
               (pr-str discriminators)
               " — every map node is EXACTLY ONE of :tag (element), :view-id "
               "(view boundary), :html (trusted markup), or a fragment (none of "
               "these, splicing its :children); an ambiguous map must not be "
               "interpreted by branch order: " (error/pr-form node))
          path {:value node :got discriminators})

        (= 1 (count discriminators))
        (case (first discriminators)
          :tag     (emit-element node path)
          ;; trusted markup writes verbatim; a non-string :html is malformed.
          :html    (let [trusted-html (:html node)]
                     (if (string? trusted-html)
                       trusted-html
                       (malformed-node!
                         (str "a trusted-HTML node's :html is not a string: "
                              (error/pr-form node))
                         path {:value node})))
          ;; view boundary erases — its children splice into the stream.
          :view-id (emit-children (:children node) path))

        ;; no primary discriminator: a fragment (splices its children).
        (contains? node :children)
        (emit-children (:children node) path)

        ;; no discriminator AND no :children — not a renderable node.
        :else
        (malformed-node!
          (str "a tree node carries no node discriminator — every map node is "
               "an element (:tag), a view boundary (:view-id), trusted markup "
               "(:html), or a fragment (:children); a map with none is not a "
               "renderable tree node: " (error/pr-form node))
          path {:value node :got []})))

    :else
    (malformed-node!
      (str "malformed tree node in re-frame.ssr/emit-ui-tree: " (error/pr-form node))
      path {:value node})))

;; ---------------------------------------------------------------------------
;; Public entry.
;; ---------------------------------------------------------------------------

(defn emit-ui-tree
  "Serialise an ALREADY-RENDERED version-1 structural `tree` to an HTML
  string (Spec 004B §The SSR consumption boundary). Validates the root
  `:rf.ui/tree-version` FIRST — a missing / non-integer / unsupported
  version throws `:rf.error/ssr-ui-tree-version-unsupported` with `{:got …
  :supported #{1}}` BEFORE any emission; a malformed node past the gate
  throws the shared `:rf.error/ui-tree-malformed`.

  Calls NOTHING — no view, no subscription, no frame. Pure, deterministic
  to the byte, JVM-runnable. `opts` carries a single current option,
  `:doctype?` (default off), which prefixes `<!DOCTYPE html>`; other keys are
  ignored (the `render-to-string` option set does not transfer to this seam)."
  ([tree] (emit-ui-tree tree nil))
  ([tree opts]
   (validate-tree-version! tree)
   (let [body (emit-node tree [])]
     (if (:doctype? opts)
       (str "<!DOCTYPE html>" body)
       body))))
