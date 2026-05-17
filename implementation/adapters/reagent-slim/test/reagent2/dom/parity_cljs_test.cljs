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
