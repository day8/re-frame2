(ns reagent2.dom.parity-cljs-test
  "Byte parity of `reagent2.dom.server/render-to-static-markup` with
  react-dom/server's `renderToStaticMarkup` over the same hiccup (rendered via
  `reagent2.impl.template/as-element`). Known, equivalent differences are
  canonicalised by `normalise-attr-order` rather than filtered globally.
  Node-only: it needs Node's module resolution for `react-dom/server`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom.server :as server]
            [reagent2.impl.template :as template]
            ["react-dom/server" :as rds]))

(defn- via-react-dom-server [hiccup]
  (rds/renderToStaticMarkup (template/as-element hiccup)))

(defn- via-rewrite [hiccup]
  (server/render-to-static-markup hiccup))

;; No roster of boolean attribute names lives here: `name` and `name=""` are
;; the same markup, so the canonicalisation collapses both spellings without
;; consulting names. A missing roster entry still reds, because it changes
;; whether the attribute is PRESENT. The roster itself is anchored against
;; react-dom by `reagent2.dom.boolean-attr-react-parity-cljs-test`.

(defn- strip-react-19-resource-hints
  "React 19 prefixes `<link rel=\"preload\">` hints to some media tags. They are
  React's resource-loading concern, not hiccup-to-HTML output, so they go
  before the diff."
  [s]
  (clojure.string/replace
   s
   #"<link[^>]*\srel=\"preload\"[^>]*>"
   ""))

(defn- normalise-attr-order
  "Canonicalise both outputs inside open tags only: drop React 18's void-tag
  slash, collapse an empty attribute value to the bare name (same DOM node),
  sort attributes, and strip React 19's resource hints. Structure, order, text
  and escapes stay part of the diff."
  [s]
  (-> s
      strip-react-19-resource-hints
      (clojure.string/replace
       ;; Match `<tag ...>` (or `<tag .../>`) — captures tag, attr string, slash.
       #"<([a-zA-Z][a-zA-Z0-9]*)((?:\s+[^>]*?)?)(/?)>"
       (fn [[_ tag attrs _slash]]
         (let [parts (->> (re-seq #"\s+([^=\s>]+)(?:=\"([^\"]*)\")?" attrs)
                          (map (fn [[_ k v]]
                                 (cond
                                   ;; Empty value → the bare short form,
                                   ;; whatever the attribute is named. The
                                   ;; two spellings denote the same DOM
                                   ;; node (the no-roster note, above),
                                   ;; so this is a canonicalisation
                                   ;; rather than a name-scoped
                                   ;; allow-list.
                                   (or (nil? v) (= "" v))
                                   (str " " k)

                                   :else
                                   (str " " k "=\"" v "\""))))
                          sort
                          (apply str))]
           ;; Always drop the trailing slash; HTML5 doesn't use it.
           (str "<" tag parts ">"))))))

(defn- =parity
  "Assert that both serialisers produce byte-identical output (after
  attribute-order canonicalisation per §8.7's known-difference
  allow-list)."
  [hiccup]
  (let [a (normalise-attr-order (via-react-dom-server hiccup))
        b (normalise-attr-order (via-rewrite hiccup))]
    [a b]))

;; ---------------------------------------------------------------------------
;; Plain text + escaping
;; ---------------------------------------------------------------------------

(deftest parity-escaped-text-quotes-apostrophe
  (testing "text content escapes the full 5-char set
            (& < > \" '). The serializer must be byte-equal to
            re-frame.ssr.html-helpers/escape-html — which emits the
            DECIMAL apostrophe entity &#39; (React 19 emits the
            equivalent hex &#x27;, so the apostrophe byte is pinned
            against the in-repo helper, not the React reference)."
    ;; & < > and " agree byte-for-byte with react-dom/server, so a
    ;; =parity assertion catches a missing quote escape.
    (let [[a b] (=parity [:div "a < b > c & d \"e\""])]
      (is (= a b)))
    ;; Apostrophe diverges from React's hex form, so pin the full
    ;; 5-char output against the in-repo escape-html target directly.
    (is (= "<div>say &quot;hi&quot; &amp; &#39;bye&#39;</div>"
           (via-rewrite [:div "say \"hi\" & 'bye'"])))))

;; ---------------------------------------------------------------------------
;; Structural shapes: attributes, void tags, fragments, children, shorthand
;; ---------------------------------------------------------------------------

(deftest parity-structural-shapes
  (doseq [[why hiccup]
          [["multiple attributes (after canonicalisation)"
            [:div {:class "c" :id "i" :title "t"}]]
           ["<img> void tag with attrs"
            [:img {:src "/x.png" :alt "x"}]]
           ["<input> void tag"
            [:input {:type "text" :name "q"}]]
           ["empty :<> fragment"
            [:<>]]
           ["nested :<> fragments"
            [:<> [:p "a"] [:<> [:p "b"] [:p "c"]]]]
           ["string + number + vector children"
            [:div "x" 42 [:span "y"]]]
           ["nil children render as empty"
            [:div "a" nil "b"]]
           [":div#bar.foo shorthand"
            [:div#bar.foo "x"]]
           ["a realistic nested tree with keyed seq children"
            [:div {:class "card"}
             [:h2 {:class "title"} "Hello"]
             [:p {:class "body"} "World"]
             [:ul (map-indexed
                   (fn [i x] ^{:key i} [:li x])
                   ["one" "two" "three"])]]]]]
    (testing why
      (let [[a b] (=parity hiccup)]
        (is (= a b))))))

;; ---------------------------------------------------------------------------
;; Boolean attributes
;; ---------------------------------------------------------------------------

(def ^:private presence-value-corpus
  "Non-boolean `:disabled` values, each labelled and paired with the bytes this
  serializer must produce. The corpus spans the JS PRIMITIVE TYPES that can be
  falsey rather than a list of remembered values, because that is the axis a
  divergence lives on: string, number and bigint each contribute a falsey member and
  a truthy neighbour, and every falsey member here is logically TRUE in
  ClojureScript — which is precisely why a `(when v ...)` written in CLJS
  diverges from react-dom.

  A bigint is the row worth stating plainly: `cljs.core/number?` compiles to
  `typeof x === \"number\"`, so a partial falsey roster written in CLJS terms
  cannot see `0n` at all. None of these values is a boolean, so the
  class probe in `reagent2.dom.boolean-attr-react-parity-cljs-test` cannot see
  any of them either."
  [["the empty string"      ""              "<button>x</button>"]
   ["the number zero"       0               "<button>x</button>"]
   ["a bigint zero"         (js/BigInt 0)   "<button>x</button>"]
   ["a non-empty string"    "yes"           "<button disabled>x</button>"]
   ;; falsey as a NUMBER, truthy as a STRING — the pair that catches a coercion
   ;; applied to the wrong type.
   ["the string \"0\""      "0"             "<button disabled>x</button>"]
   ["a non-zero number"     1               "<button disabled>x</button>"]
   ["a non-zero bigint"     (js/BigInt 1)   "<button disabled>x</button>"]])

(deftest parity-presence-attr-collapses-on-js-truthiness
  (testing "a presence attribute collapses on JS TRUTHINESS, against
            the live react-dom reference"
    (doseq [[label v _] presence-value-corpus]
      (let [[a b] (=parity [:button {:disabled v} "x"])]
        (is (= a b)
            (str "react-dom and this serializer must agree about " label)))))
  (testing "…and the reference bytes, stated independently of react-dom so the
            intent survives a react-dom bump"
    (doseq [[label v expected] presence-value-corpus]
      (is (= expected (via-rewrite [:button {:disabled v} "x"]))
          (str "the emitted bytes for " label))))
  (testing "NaN is JS-falsey too. It is asserted on this serializer's bytes
            ALONE and deliberately kept out of the `=parity` loop above: React
            logs a 'Received NaN for the disabled attribute' warning for it, and
            a suite should not manufacture one to make a point the raw bytes
            already make."
    (is (= "<button>x</button>" (via-rewrite [:button {:disabled js/NaN} "x"])))))

(deftest empty-value-on-an-ordinary-attribute-keeps-its-quotes
  (testing "the canonicalisation above collapses `k=\"\"` to bare `k`
            on BOTH sides, so it cannot be the thing asserting that an ordinary
            attribute keeps the quoted empty value. That is pinned here,
            OUTSIDE `=parity`, on the serializer's raw bytes — react-dom emits
            `title=\"\"` and so must this."
    (is (= "<div title=\"\"></div>" (via-rewrite [:div {:title ""}])))
    (is (= "<div id=\"\"></div>" (via-rewrite [:div {:id ""}])))))

;; ---------------------------------------------------------------------------
;; dangerouslySetInnerHTML
;; ---------------------------------------------------------------------------

(deftest parity-dangerously-set-inner-html
  (testing ":dangerouslySetInnerHTML emits raw"
    (let [[a b] (=parity
                 [:div {:dangerouslySetInnerHTML {:__html "<b>raw</b>"}}])]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Element-context text rules
;;
;; react-dom emits a `<script>` / `<style>` string body verbatim (raw text,
;; which the HTML parser never entity-decodes) with only an embedded
;; closing-tag sequence rewritten, and prefixes one compensating LF to a
;; `<pre>` whose sole string body starts with LF. Without a raw-text
;; element or a leading-LF body in this corpus, a serializer that
;; entity-escaped either would pass unnoticed.
;; ---------------------------------------------------------------------------

(deftest parity-raw-text-elements-rf2-3x7nj-6-2
  (testing "script/style string bodies match react-dom byte for byte"
    (doseq [hiccup [[:style "body { font-family: 'Open Sans' } td > p { margin: 0 }"]
                    [:script "if (a < b && c) go()"]
                    [:script "x = '</script><b>'"]
                    [:style "a{}</STYLE><b>"]]]
      (let [[a b] (=parity hiccup)]
        (is (= a b) (str "reagent-slim diverges from react-dom for "
                         (pr-str hiccup)))))))

(deftest parity-leading-newline-rf2-3x7nj-6-2
  (testing "a <pre> sole string body starting with LF gets one compensating
            LF; the controls (no LF, multi-child body) get none"
    (doseq [hiccup [[:pre "\n  indented"]
                    [:pre "x"]
                    [:pre "\n" [:b "x"]]]]
      (let [[a b] (=parity hiccup)]
        (is (= a b) (str "reagent-slim diverges from react-dom for "
                         (pr-str hiccup)))))))

;; ---------------------------------------------------------------------------
;; Inline styles. Each row asserts parity with react-dom AND the bytes, so the
;; intended output survives a react-dom bump.
;; ---------------------------------------------------------------------------

(deftest parity-style-serialisation
  (testing "px on numeric non-unitless values, bare unitless values, no px on
            zero, nil entries omitted, strings untouched, keyword values
            stringified on the live React path too"
    (doseq [[style expected]
            [[{:width 10 :height 20} "width:10px;height:20px"]
             [{:flex-grow 1 :z-index 5 :opacity 0.5} "flex-grow:1;z-index:5;opacity:0.5"]
             [{:width 0} "width:0"]
             [{:color nil :width 10} "width:10px"]
             [{:width "10em" :color "red"} "width:10em;color:red"]
             [{:cursor :pointer} "cursor:pointer"]
             [{:display :flex :text-align :center} "display:flex;text-align:center"]]]
      (let [[a b] (=parity [:div {:style style}])]
        (is (= a b) (pr-str style)))
      (is (= (str "<div style=\"" expected "\"></div>")
             (via-rewrite [:div {:style style}]))))))

(deftest parity-style-webkit-box-flex-group
  (testing "`WebkitBoxFlexGroup` gets px (NOT unitless) —
            React 19.2.0's unitlessNumber Set spells the entry with a
            capital K (`WebKitBoxFlexGroup`), but the camelCase prop
            token is lowercase-k `WebkitBoxFlexGroup`, so React's own
            lookup misses and it emits px. Byte-parity requires the
            serializer to mirror the typo and emit px too."
    (let [[a b] (=parity [:div {:style {:WebkitBoxFlexGroup 2}}])]
      (is (= a b)))
    (is (= "<div style=\"-webkit-box-flex-group:2px\"></div>"
           (via-rewrite [:div {:style {:WebkitBoxFlexGroup 2}}])))
    ;; Sibling vendor entries whose camelCase token DOES match the Set
    ;; stay unitless (regression guard that the typo is scoped to one key).
    (let [[a b] (=parity [:div {:style {:WebkitBoxFlex 3}}])]
      (is (= a b)))
    (is (= "<div style=\"-webkit-box-flex:3\"></div>"
           (via-rewrite [:div {:style {:WebkitBoxFlex 3}}])))))

(deftest style-custom-property-verbatim
  (testing "CSS custom property (--foo) passes through verbatim, no px"
    ;; The React-element path preserves `--foo` style keys verbatim
    ;; (dash-to-prop-name short-circuits `--` names), so the live path
    ;; and the pure serializer AGREE, and a real parity assertion pins it.
    (let [[a b] (=parity [:div {:style {:--gap "8px"}}])]
      (is (= a b) "live React path and pure serializer agree on --gap"))
    (is (= "<div style=\"--gap:8\"></div>"
           (via-rewrite [:div {:style {:--gap 8}}])))))

;; ---------------------------------------------------------------------------
;; Attribute-name casing. react-dom keeps SVG's viewBox / preserveAspectRatio,
;; dasherizes clipPath / strokeWidth, and lowercases plain HTML camelCase. A
;; blanket-lowercasing serializer would write the broken `viewbox`.
;; ---------------------------------------------------------------------------

(deftest parity-attribute-name-casing
  (doseq [[hiccup expected]
          [[[:svg {:preserveAspectRatio "xMidYMid"}] "<svg preserveAspectRatio=\"xMidYMid\"></svg>"]
           [[:rect {:clipPath "url(#c)"}] "<rect clip-path=\"url(#c)\"></rect>"]
           [[:path {:strokeWidth 2 :d "M0 0"}] "<path stroke-width=\"2\" d=\"M0 0\"></path>"]
           [[:div {:tab-index 3}] "<div tabindex=\"3\"></div>"]]]
    (let [[a b] (=parity hiccup)]
      (is (= a b) (pr-str hiccup)))
    (is (= expected (via-rewrite hiccup)))))

(deftest parity-svg-tree-with-children
  (testing "realistic SVG tree: viewBox root + clipPath/strokeWidth children"
    (let [[a b] (=parity
                 [:svg {:viewBox "0 0 100 100"
                        :preserveAspectRatio "xMidYMid meet"}
                  [:defs [:clipPath {:id "clip"}
                          [:circle {:cx 50 :cy 50 :r 40}]]]
                  [:path {:d "M10 10 L90 90"
                          :strokeWidth 3
                          :stroke "black"
                          :clipPath "url(#clip)"}]])]
      (is (= a b)))))

(deftest parity-svg-mask-family-rf2-4ale
  (testing "react-dom 19.3 emits `maskType` as `mask-type`. Without a
            `maskType` row in `react-attribute-name-overrides` the name would
            fall through to the lowercase rule and this serializer would write
            `masktype`. The other mask-family names are the controls: they must
            keep their camelCase through the same code path, which is what
            keeps the override narrow rather than a lowercase-rule change.

            The reference here is the INSTALLED react-dom rather than a
            hand-written expectation, because the defect class is this table
            drifting from react-dom — an expectation written by the same hand
            that wrote the table would drift with it"
    (doseq [hiccup [[:mask {:mask-type "alpha"}]
                    [:mask {:maskType "alpha"}]
                    [:mask {:mask-units "userSpaceOnUse"}]
                    [:mask {:mask-content-units "userSpaceOnUse"}]]]
      (let [[a b] (=parity hiccup)]
        (is (= a b)
            (str "reagent-slim SSR diverges from react-dom for "
                 (pr-str hiccup)))))
    (is (= "<mask mask-type=\"alpha\"></mask>"
           (via-rewrite [:mask {:mask-type "alpha"}])))
    (is (= "<mask maskUnits=\"userSpaceOnUse\"></mask>"
           (via-rewrite [:mask {:mask-units "userSpaceOnUse"}])))
    (is (= "<mask maskContentUnits=\"userSpaceOnUse\"></mask>"
           (via-rewrite [:mask {:mask-content-units "userSpaceOnUse"}])))))

(deftest parity-xml-namespaced-and-transform-origin-names-rf2-u0xpc
  (testing "react-dom 19.3.0 writes the XML-namespaced names with
            their colon (`xlinkHref` → `xlink:href` and `xmlLang` → `xml:lang`
            are dedicated `pushAttribute` cases, `xmlnsXlink` → `xmlns:xlink`
            an `aliases` row) and dasherizes `transformOrigin` (an `aliases`
            row). Without their `react-attribute-name-overrides` rows each would
            fall through to the lowercase rule and this serializer would write
            `xlinkhref` / `xmllang` / `transformorigin`: attributes no browser
            knows, so a `<use>` sprite reference would not resolve. The reference
            is the INSTALLED react-dom; the whole candidate space is swept by
            `attribute-names-agree-with-installed-react-dom` in
            `reagent2.dom.boolean-attr-react-parity-cljs-test`"
    (doseq [hiccup [[:svg [:use {:xlink-href "#icon"}]]
                    [:svg [:use {:xlinkHref "#icon"}]]
                    [:svg [:text {:xml-lang "en"} "x"]]
                    [:svg [:g {:transform-origin "center"}]]
                    [:svg {:xmlns-xlink "http://www.w3.org/1999/xlink"}]
                    [:svg [:a {:xlink-actuate "onLoad" :xlink-arcrole "r"
                               :xlink-role "r" :xlink-show "new"
                               :xlink-title "t" :xlink-type "simple"}]]
                    [:svg [:g {:xml-base "/b/" :xml-space "preserve"}]]]]
      (let [[a b] (=parity hiccup)]
        (is (= a b)
            (str "reagent-slim SSR diverges from react-dom for "
                 (pr-str hiccup))))))
  (testing "…and the bytes of the three named rows, stated independently of
            react-dom so the intent survives a react-dom bump"
    (is (= "<svg><use xlink:href=\"#icon\"></use></svg>"
           (via-rewrite [:svg [:use {:xlink-href "#icon"}]])))
    (is (= "<svg><text xml:lang=\"en\">x</text></svg>"
           (via-rewrite [:svg [:text {:xml-lang "en"} "x"]])))
    (is (= "<svg><g transform-origin=\"center\"></g></svg>"
           (via-rewrite [:svg [:g {:transform-origin "center"}]])))))

;; ---------------------------------------------------------------------------
;; javascript: URLs
;;
;; react-dom 19 neutralises a `javascript:` URL in its URL-bearing props:
;; `href`, `src`, `action`, `formAction` and `xlinkHref` on any non-custom
;; element, and `data` on an `<object>`. It swaps the value for a URL that
;; throws (`sanitizeURL`, tested by `isJavaScriptProtocol`). A serializer
;; writing them unchanged would send out live a `javascript:` URL that
;; react-dom blocks. The reference below is the INSTALLED
;; react-dom. `javascript-url-blocking-agrees-with-installed-react-dom`, in
;; `reagent2.dom.boolean-attr-react-parity-cljs-test`, sweeps the same rule
;; over react-dom's whole candidate-name space.
;; ---------------------------------------------------------------------------

(def ^:private blocked-javascript-url
  "What react-dom 19.3.0 writes in place of a blocked `javascript:` URL."
  "javascript:throw new Error('React has blocked a javascript: URL as a security precaution.')")

(def ^:private javascript-url-values
  "Each is a spelling of the scheme that react-dom's `isJavaScriptProtocol`
  matches: it ignores case, skips leading C0 controls and spaces, and allows a
  tab, LF or CR between the letters."
  [["plain"                  "javascript:alert(1)"]
   ["mixed case"             "JaVaScRiPt:alert(1)"]
   ["leading spaces"         "  javascript:alert(1)"]
   ["leading C0 controls"    "\u0001\u001Fjavascript:alert(1)"]
   ["tab, LF and CR inside"  "java\tscr\nipt\r:alert(1)"]
   ["a trailing space"       "javascript:alert(1) "]])

(def ^:private near-miss-url-values
  "Values react-dom leaves alone. They are the controls: an ordinary URL, and
  two near-misses that a looser copy of the rule would wrongly block (JS `\\s`
  matches a no-break space, and a space before the colon ends the scheme)."
  [["an ordinary https URL"   "https://example.com/?q=javascript:x"]
   ["a space before the colon" "javascript :alert(1)"]
   ["a leading no-break space" " javascript:alert(1)"]])

(def ^:private url-attribute-forms
  "One hiccup form per attribute react-dom sanitises, each a fn of the value."
  [["<a href>"              (fn [v] [:a {:href v}])]
   ["<img src>"             (fn [v] [:img {:src v}])]
   ["<form action>"         (fn [v] [:form {:action v}])]
   ["<button formAction>"   (fn [v] [:button {:form-action v}])]
   ["<svg><use xlink:href>" (fn [v] [:svg [:use {:xlink-href v}]])]
   ["<object data>"         (fn [v] [:object {:data v}])]])

(defn- =url-parity
  "`=parity`, plus one canonicalisation. react-dom escapes `'` as `&#x27;`
  inside an attribute value. This serializer leaves it literal, because its
  attribute escape covers `&` and `\"` only. Both spellings parse to the same
  attribute value, and the blocked URL carries two apostrophes."
  [hiccup]
  (mapv #(clojure.string/replace % "&#x27;" "'") (=parity hiccup)))

(deftest parity-javascript-urls-are-blocked-rf2-w1hd8
  (testing "every spelling of a javascript: URL is blocked in each URL-bearing
            attribute, exactly as the installed react-dom blocks it"
    (doseq [[attribute form] url-attribute-forms
            [label value]    javascript-url-values]
      (let [[a b] (=url-parity (form value))]
        (is (clojure.string/includes? a blocked-javascript-url)
            (str "control: react-dom blocks " label " in " attribute
                 " — got " (pr-str a)))
        (is (= a b)
            (str "reagent-slim diverges from react-dom for " label " in "
                 attribute)))))
  (testing "a value react-dom leaves alone is left alone, in each attribute"
    (doseq [[attribute form] url-attribute-forms
            [label value]    near-miss-url-values]
      (let [[a b] (=url-parity (form value))]
        (is (not (clojure.string/includes? a "React has blocked"))
            (str "control: react-dom leaves " label " alone in " attribute))
        (is (= a b)
            (str "reagent-slim diverges from react-dom for " label " in "
                 attribute))))))

(deftest parity-javascript-urls-where-react-dom-does-not-block-rf2-w1hd8
  (testing "`data` is a URL only on an <object>, and a custom element's props
            are written verbatim; react-dom blocks neither, so neither may this"
    (doseq [hiccup [[:div {:data "javascript:alert(1)"}]
                    [:my-widget {:href "javascript:alert(1)"}]]]
      (let [[a b] (=url-parity hiccup)]
        (is (not (clojure.string/includes? a "React has blocked"))
            (str "control: react-dom leaves " (pr-str hiccup) " alone"))
        (is (= a b)
            (str "reagent-slim diverges from react-dom for " (pr-str hiccup)))))))

(deftest javascript-url-bytes-rf2-w1hd8
  (testing "the bytes, stated independently of react-dom so the intent
            survives a react-dom bump"
    (is (= (str "<a href=\"" blocked-javascript-url "\"></a>")
           (via-rewrite [:a {:href "JaVaScRiPt:alert(1)"}])))
    (is (= (str "<svg><use xlink:href=\"" blocked-javascript-url "\"></use></svg>")
           (via-rewrite [:svg [:use {:xlink-href " javascript:alert(1)"}]])))
    (is (= "<a href=\"https://example.com/\"></a>"
           (via-rewrite [:a {:href "https://example.com/"}]))
        "an ordinary https URL is untouched")))
