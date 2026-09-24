(ns reagent2.dom.parity-cljs-test
  "Parity tests for `reagent2.dom.server/render-to-static-markup`
  against `react-dom/server.renderToStaticMarkup`.

  Per IMPL-SPEC §8.7 + §12.5 R-004. Mitigation for the risk that the
  pure-CLJS rewrite diverges from React's reference output.

  Strategy: build a representative corpus of hiccup forms, render
  each through both serialisers, assert byte-for-byte equality. Known
  differences (per §8.7 — attribute ordering, idiomatic camelCasing
  on a small set of attrs) are explicitly allow-listed inside the
  corresponding test cases via canonicalisation rather than a global
  diff filter, so the cause of any future drift is easy to trace.

  React-side path: hiccup → reagent2.impl.template/as-element →
  React element → react-dom/server.renderToStaticMarkup → string.
  CLJS-side path: hiccup → reagent2.dom.server/render-to-static-markup
  → string.

  This test runs only under :node-test (so the host has Node's
  module resolution available for `react-dom/server`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.dom.server :as server]
            [reagent2.impl.template :as template]
            ["react-dom/server" :as rds]))

(defn- via-react-dom-server [hiccup]
  (rds/renderToStaticMarkup (template/as-element hiccup)))

(defn- via-rewrite [hiccup]
  (server/render-to-static-markup hiccup))

;; ---------------------------------------------------------------------------
;; There is deliberately NO roster of boolean attribute names here.
;;
;; A roster here would be a second copy of `reagent2.dom.server`'s
;; presence roster, kept so that the diff could recognise `disabled=""`
;; (React's spelling) as equivalent to the bare `disabled` this serializer
;; emits — and it would drift invisibly, because the corpus below
;; exercises exactly one boolean name (`:disabled`).
;;
;; The roster was answering a question the canonicalisation never needed
;; to ask. `name` and `name=""` are the SAME markup: an HTML attribute
;; written without a value has the empty string as its value, so both
;; spellings parse to an identical DOM node and hydrate identically.
;; Collapsing them is therefore a lossless canonicalisation of equivalent
;; bytes rather than an allow-list of names, and it needs no roster, so
;; there is no copy to drift.
;;
;; What it does NOT do is hide a missing or wrong roster entry, because
;; those change whether the attribute is PRESENT, not how it is spelled:
;; a name the serializer fails to classify emits no attribute at all
;; (`<input>` vs React's `<input disabled="">`) and still reds. The
;; roster itself is anchored externally, against react-dom's own
;; `possibleStandardNames` at run time, by
;; `reagent2.dom.boolean-attr-react-parity-cljs-test`; that is the gate
;; for WHICH names are boolean, and this file is the gate for the
;; corpus's byte-level shape.
;; ---------------------------------------------------------------------------

(defn- strip-react-19-resource-hints
  "React 19's `renderToStaticMarkup` auto-emits `<link rel=\"preload\">`
  resource hints in front of certain media tags (`<img>`, `<script>`,
  certain `<link>` and `<style>` cases) — see React 19's
  Float / resource-loading docs. The pure-CLJS rewrite does not emit
  these hints; they are React's runtime concern, not part of the
  hiccup-to-HTML contract this parity suite asserts. Strip the
  auto-emitted hints from React's output before the diff so the
  comparison remains a static-markup parity check rather than a
  resource-loading-strategy check."
  [s]
  (clojure.string/replace
   s
   #"<link[^>]*\srel=\"preload\"[^>]*>"
   ""))

(defn- normalise-attr-order
  "Canonicalise both serialisers' output for the parity diff. Per
  §8.7's known-difference allow-list:

    1. React 18's renderToStaticMarkup emits XHTML-style self-closing
       `<input/>` (closing slash); HTML5/React-19/the rewrite emits
       `<input>`. Strip the closing slash on void tags.
    2. React emits a present-but-empty attribute as `disabled=\"\"`;
       HTML5/the rewrite emits the short form `disabled`. Both
       spellings parse to the same DOM node, so BOTH sides collapse
       to the short form — no roster of names is consulted, and none
       is maintained here (see the no-roster note above).
    3. React's attribute insertion order may differ from hiccup map
       order. Sort attributes within each tag.
    4. React 19 auto-emits `<link rel=\"preload\">` resource hints in
       front of `<img>` (and other media tags); these are React's
       resource-loading concern and are stripped before the diff —
       see `strip-react-19-resource-hints`.

  This canonicalisation operates ONLY on the open-tag region; nested
  HTML structure / element order / text content / escape sequences
  remain part of the diff."
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

(deftest parity-plain-text
  (testing "plain text content"
    (let [[a b] (=parity [:div "hello"])]
      (is (= a b)))))

(deftest parity-escaped-text
  (testing "text with HTML special chars"
    (let [[a b] (=parity [:div "1 < 2 && 3 > 0"])]
      (is (= a b)))))

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
;; Attributes
;; ---------------------------------------------------------------------------

(deftest parity-class-attr
  (testing ":class attribute (hiccup) ↔ class= (React)"
    (let [[a b] (=parity [:div {:class "foo"}])]
      (is (= a b)))))

(deftest parity-id-attr
  (testing ":id attribute"
    (let [[a b] (=parity [:div {:id "main"}])]
      (is (= a b)))))

(deftest parity-data-attr
  (testing "data-* attribute"
    (let [[a b] (=parity [:div {:data-id "7"}])]
      (is (= a b)))))

(deftest parity-aria-attr
  (testing "aria-* attribute"
    (let [[a b] (=parity [:div {:aria-label "close"}])]
      (is (= a b)))))

(deftest parity-multiple-attrs
  (testing "multiple attributes (after canonicalisation)"
    (let [[a b] (=parity [:div {:class "c" :id "i" :title "t"}])]
      (is (= a b)))))

(deftest parity-native-keyword-attr-values
  (testing "keyword DOM-attr values stringify on the
            LIVE React path the same way the server serializer does —
            [:button {:type :button}] / [:a {:target :_blank}]"
    (let [[a b] (=parity [:button {:type :button}])]
      (is (= a b)))
    (let [[a b] (=parity [:a {:target :_blank :rel :noopener}])]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Boolean attributes
;; ---------------------------------------------------------------------------

(deftest parity-boolean-attr-true
  (testing "true boolean attr emits short form"
    (let [[a b] (=parity [:input {:disabled true}])]
      (is (= a b)))))

(deftest parity-boolean-attr-false
  (testing "false boolean attr is omitted"
    (let [[a b] (=parity [:input {:disabled false}])]
      (is (= a b)))))

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
;; Void tags
;; ---------------------------------------------------------------------------

(deftest parity-br-void
  (testing "<br> void tag"
    (let [[a b] (=parity [:br])]
      (is (= a b)))))

(deftest parity-img-void
  (testing "<img> void tag with attrs"
    (let [[a b] (=parity [:img {:src "/x.png" :alt "x"}])]
      (is (= a b)))))

(deftest parity-input-void
  (testing "<input> void tag"
    (let [[a b] (=parity [:input {:type "text" :name "q"}])]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Fragments
;; ---------------------------------------------------------------------------

(deftest parity-empty-fragment
  (testing "empty :<> fragment"
    (let [[a b] (=parity [:<>])]
      (is (= a b)))))

(deftest parity-fragment-with-children
  (testing ":<> with children"
    (let [[a b] (=parity [:<> [:a] [:b]])]
      (is (= a b)))))

(deftest parity-nested-fragments
  (testing "nested :<> fragments"
    (let [[a b] (=parity [:<> [:p "a"] [:<> [:p "b"] [:p "c"]]])]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Sequence children
;; ---------------------------------------------------------------------------

(deftest parity-seq-children
  (testing "(map ...) children with keys"
    (let [[a b] (=parity
                 [:ul (map-indexed (fn [i x] ^{:key i} [:li x])
                                   ["a" "b" "c"])])]
      (is (= a b)))))

(deftest parity-mixed-children
  (testing "string + number + vector children"
    (let [[a b] (=parity [:div "x" 42 [:span "y"]])]
      (is (= a b)))))

(deftest parity-nil-children-dropped
  (testing "nil children render as empty"
    (let [[a b] (=parity [:div "a" nil "b"])]
      (is (= a b)))))

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
;; Compound: nested hiccup with attrs + classes
;; ---------------------------------------------------------------------------

(deftest parity-compound-tree
  (testing "realistic nested tree"
    (let [[a b] (=parity
                 [:div {:class "card"}
                  [:h2 {:class "title"} "Hello"]
                  [:p {:class "body"} "World"]
                  [:ul (map-indexed
                        (fn [i x] ^{:key i} [:li x])
                        ["one" "two" "three"])]])]
      (is (= a b)))))

(deftest parity-tag-shorthand
  (testing ":div#bar.foo shorthand"
    (let [[a b] (=parity [:div#bar.foo "x"])]
      (is (= a b)))))

;; ---------------------------------------------------------------------------
;; Inline-style serialisation
;;
;; `react-dom/server.renderToStaticMarkup` appends `px` to numeric values
;; of non-unitless CSS properties, keeps unitless properties bare, and
;; omits entries whose value is nil/boolean/empty. The pure-CLJS rewrite
;; must match. The =parity assertions below pin against react-dom/server;
;; the explicit-string assertions document the target output independently
;; of the React reference so a future React change is easy to spot.
;; ---------------------------------------------------------------------------

(deftest parity-style-numeric-px
  (testing "numeric px-property values render with px"
    (let [[a b] (=parity [:div {:style {:width 10 :height 20}}])]
      (is (= a b)))
    (is (= "<div style=\"width:10px;height:20px\"></div>"
           (via-rewrite [:div {:style {:width 10 :height 20}}])))))

(deftest parity-style-unitless-bare
  (testing "unitless properties render bare (no px)"
    (let [[a b] (=parity [:div {:style {:flex-grow 1 :z-index 5 :opacity 0.5}}])]
      (is (= a b)))
    (is (= "<div style=\"flex-grow:1;z-index:5;opacity:0.5\"></div>"
           (via-rewrite [:div {:style {:flex-grow 1 :z-index 5 :opacity 0.5}}])))))

(deftest parity-style-zero-no-px
  (testing "numeric zero never gets px (matches React)"
    (let [[a b] (=parity [:div {:style {:width 0}}])]
      (is (= a b)))
    (is (= "<div style=\"width:0\"></div>"
           (via-rewrite [:div {:style {:width 0}}])))))

(deftest parity-style-nil-omitted
  (testing "nil-valued style entry is omitted entirely"
    (let [[a b] (=parity [:div {:style {:color nil :width 10}}])]
      (is (= a b)))
    (is (= "<div style=\"width:10px\"></div>"
           (via-rewrite [:div {:style {:color nil :width 10}}])))))

(deftest parity-style-string-value-untouched
  (testing "string values pass through (px in the string is preserved)"
    (let [[a b] (=parity [:div {:style {:width "10em" :color "red"}}])]
      (is (= a b)))
    (is (= "<div style=\"width:10em;color:red\"></div>"
           (via-rewrite [:div {:style {:width "10em" :color "red"}}])))))

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

(deftest parity-style-keyword-value-rf2-fdm4rm
  (testing "keyword-valued style properties stringify on the
            LIVE React path so react-dom/server and the pure serializer
            AGREE — {:cursor :pointer} → cursor:pointer on both. This pin
            FAILS if the live template path passes a raw keyword into React
            (React would string-coerce the CLJS keyword :pointer
            to \":pointer\", emitting invalid `cursor::pointer`)."
    (let [[a b] (=parity [:div {:style {:cursor :pointer}}])]
      (is (= a b)
          "live React path and pure serializer agree on :cursor :pointer"))
    (is (= "<div style=\"cursor:pointer\"></div>"
           (via-rewrite [:div {:style {:cursor :pointer}}])))
    ;; Mixed keyword values across several properties, one style map.
    (let [[a b] (=parity [:div {:style {:display :flex :text-align :center}}])]
      (is (= a b)))
    (is (= "<div style=\"display:flex;text-align:center\"></div>"
           (via-rewrite [:div {:style {:display :flex :text-align :center}}])))))

;; ---------------------------------------------------------------------------
;; SVG attribute-name casing parity
;;
;; `react-dom/server` is case-sensitive about SVG attribute names: it
;; preserves `viewBox`/`preserveAspectRatio`/`gradientUnits`/`stdDeviation`,
;; dasherizes `clipPath`→`clip-path` / `strokeWidth`→`stroke-width` /
;; `fillOpacity`→`fill-opacity` / `stopColor`→`stop-color`, and lowercases
;; plain HTML camelCase (`tabIndex`→`tabindex`). A blanket-lowercasing
;; serializer would turn `viewBox` into the broken `viewbox`. The
;; =parity assertions pin the serializer against the live React reference;
;; the explicit-string assertions document the target independently.
;; ---------------------------------------------------------------------------

(deftest parity-svg-viewbox
  (testing ":viewBox preserved (case-sensitive SVG attribute)"
    (let [[a b] (=parity [:svg {:viewBox "0 0 10 10"}])]
      (is (= a b)))
    (is (= "<svg viewBox=\"0 0 10 10\"></svg>"
           (via-rewrite [:svg {:viewBox "0 0 10 10"}])))))

(deftest parity-svg-preserve-aspect-ratio
  (testing ":preserveAspectRatio preserved verbatim"
    (let [[a b] (=parity [:svg {:preserveAspectRatio "xMidYMid"}])]
      (is (= a b)))
    (is (= "<svg preserveAspectRatio=\"xMidYMid\"></svg>"
           (via-rewrite [:svg {:preserveAspectRatio "xMidYMid"}])))))

(deftest parity-svg-clip-path-dasherized
  (testing ":clipPath dasherizes to clip-path (React's output)"
    (let [[a b] (=parity [:rect {:clipPath "url(#c)"}])]
      (is (= a b)))
    (is (= "<rect clip-path=\"url(#c)\"></rect>"
           (via-rewrite [:rect {:clipPath "url(#c)"}])))))

(deftest parity-svg-stroke-width-dasherized
  (testing ":strokeWidth dasherizes to stroke-width"
    (let [[a b] (=parity [:path {:strokeWidth 2 :d "M0 0"}])]
      (is (= a b)))
    (is (= "<path stroke-width=\"2\" d=\"M0 0\"></path>"
           (via-rewrite [:path {:strokeWidth 2 :d "M0 0"}])))))

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

(deftest parity-svg-fill-stop-color-dasherized
  (testing ":fillOpacity / :stopColor dasherize like React"
    (let [[a b] (=parity [:circle {:fillOpacity 0.5}])]
      (is (= a b)))
    (let [[a b] (=parity [:stop {:stopColor "red"}])]
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

(deftest parity-html-tab-index-lowercased
  (testing ":tab-index lowercases to tabindex (HTML camelCase)"
    (let [[a b] (=parity [:div {:tab-index 3}])]
      (is (= a b)))
    (is (= "<div tabindex=\"3\"></div>"
           (via-rewrite [:div {:tab-index 3}])))))

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
