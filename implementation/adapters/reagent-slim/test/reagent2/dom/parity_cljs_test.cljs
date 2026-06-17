(ns reagent2.dom.parity-cljs-test
  "Parity tests for `reagent2.dom.server/render-to-static-markup`
  against `react-dom/server.renderToStaticMarkup` (rf2-6hyy Stage 4-E).

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

(def ^:private boolean-attr-set
  "Boolean attributes that React 18 emits as `disabled=\"\"` but
  HTML5/React 19/the rewrite emits as bare `disabled`. Used in
  canonicalisation per §8.7 known-difference allow-list."
  #{"allowfullscreen" "async" "autofocus" "autoplay" "checked"
    "controls" "default" "defer" "disabled" "formnovalidate" "hidden"
    "loop" "multiple" "muted" "novalidate" "open" "playsinline"
    "readonly" "required" "reversed" "selected" "itemscope"})

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
    2. React 18 emits boolean attrs as `disabled=\"\"`; HTML5/the
       rewrite emits the short form `disabled`. Collapse to the
       short form.
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
                                   ;; Boolean attr with empty value → short form.
                                   (and (contains? boolean-attr-set
                                                   (clojure.string/lower-case k))
                                        (or (nil? v) (= "" v)))
                                   (str " " k)

                                   v
                                   (str " " k "=\"" v "\"")

                                   :else
                                   (str " " k))))
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
  (testing "rf2-4dlxga: text content escapes the full 5-char set
            (& < > \" '). The serializer must be byte-equal to
            re-frame.ssr.html-helpers/escape-html — which emits the
            DECIMAL apostrophe entity &#39; (React 19 emits the
            equivalent hex &#x27;, so the apostrophe byte is pinned
            against the in-repo helper, not the React reference)."
    ;; & < > and " agree byte-for-byte with react-dom/server, so a
    ;; =parity assertion catches the previous missing-quote-escape bug.
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
  (testing "rf2-ygknv finding 1: keyword DOM-attr values stringify on the
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
;; Inline-style serialisation (rf2-9nyg6)
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
  (testing "rf2-4dlxga: `WebkitBoxFlexGroup` gets px (NOT unitless) —
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
    ;; rf2-ygknv finding 2: the React-element path now preserves `--foo`
    ;; style keys verbatim (dash-to-prop-name short-circuits `--` names),
    ;; so the live path and the pure serializer AGREE — a real parity
    ;; assertion replaces the old documents-the-bug comment.
    (let [[a b] (=parity [:div {:style {:--gap "8px"}}])]
      (is (= a b) "live React path and pure serializer agree on --gap"))
    (is (= "<div style=\"--gap:8\"></div>"
           (via-rewrite [:div {:style {:--gap 8}}])))))

;; ---------------------------------------------------------------------------
;; SVG attribute-name casing parity (rf2-ygknv finding 3)
;;
;; `react-dom/server` is case-sensitive about SVG attribute names: it
;; preserves `viewBox`/`preserveAspectRatio`/`gradientUnits`/`stdDeviation`,
;; dasherizes `clipPath`→`clip-path` / `strokeWidth`→`stroke-width` /
;; `fillOpacity`→`fill-opacity` / `stopColor`→`stop-color`, and lowercases
;; plain HTML camelCase (`tabIndex`→`tabindex`). The pure serializer used
;; to blanket-lowercase, turning `viewBox` into the broken `viewbox`. The
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

(deftest parity-html-tab-index-lowercased
  (testing ":tab-index still lowercases to tabindex (HTML camelCase)"
    (let [[a b] (=parity [:div {:tab-index 3}])]
      (is (= a b)))
    (is (= "<div tabindex=\"3\"></div>"
           (via-rewrite [:div {:tab-index 3}])))))
