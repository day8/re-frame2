(ns re-frame.realworld-markdown-cljs-test
  "Contract tests for the shared RealWorld article-body markdown renderer
   (`realworld-shared.markdown` — rf2-e2t7v4), which replaced the
   hand-rolled markdown subset (rf2-ke9gtx) with a real CommonMark library
   (`io.github.nextjournal/markdown`, hiccup-emitting mode).

   The renderer source is example code (`examples/real-apps/realworld_shared/...`), but the
   regression contract lives HERE in the adapter test tree per the
   test-free-examples policy (rf2-8cevm) — the same posture the prior
   markdown work used. Runs under the always-on `:node-test` gate (markdown-it
   is pure JS — no DOM — so it tokenizes fine in plain Node).

   Two contracts:

   1. SAFE BY CONSTRUCTION (XSS). The renderer emits HICCUP DATA — never an
      HTML string, never `:dangerouslySetInnerHTML` — so React escapes all
      text. Adversarial input must NOT yield executable markup:
        - a `<script>` / raw-HTML injection is inert (escaped text, no
          `:script` element, no dangerously-set-inner-html anywhere);
        - an `<img onerror=...>` injection is inert (no live `:img` element
          synthesised from raw HTML);
        - a `[text](javascript:...)` link drops the unsafe `:href` (the link
          text survives) and likewise for `data:` image `:src`.

   2. CommonMark FIDELITY. Shapes the hand-rolled subset could not do now
      render correctly: a GFM table, a nested list, and an image."
  (:require [clojure.walk :as walk]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest testing is]]
            [realworld-shared.markdown :as md]))

;; ---------------------------------------------------------------------------
;; Hiccup tree-walk helpers (the renderer returns pure hiccup data)
;; ---------------------------------------------------------------------------

(defn- elements
  "All hiccup element vectors in `tree` (depth-first), i.e. vectors whose
   head is a keyword tag."
  [tree]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [node]
        (when (and (vector? node) (keyword? (first node)))
          (swap! acc conj node))
        node)
      tree)
    @acc))

(defn- tags
  "The set of element tag keywords present in `tree`."
  [tree]
  (into #{} (map first) (elements tree)))

(defn- attrs-of
  "All attribute maps (the second element of an element vector, when a map)
   present in `tree`."
  [tree]
  (into []
        (keep (fn [node]
                (when (and (vector? node)
                           (keyword? (first node))
                           (map? (second node)))
                  (second node))))
        (elements tree)))

(defn- strings-of
  "All string leaves in `tree` concatenated — the visible text."
  [tree]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [node]
        (when (string? node) (swap! acc conj node))
        node)
      tree)
    (str/join " " @acc)))

(defn- href-tag-keyword?
  "The language fence encodes the language INTO the tag keyword
   (`:code.language-js`). Guard against a false `:script`/etc. positive by
   only matching exact tags."
  [tag wanted]
  (= tag wanted))

;; ===========================================================================
;; 1. SAFE BY CONSTRUCTION
;; ===========================================================================

(deftest safe-url-predicate-allowlist
  ;; The defensive sanitization primitive, tested in isolation (markdown-it
  ;; also rejects these schemes upstream, so this pins OUR layer directly).
  (testing "allowlisted absolute schemes + relative navigation are safe"
    (is (md/safe-url? "https://example.com"))
    (is (md/safe-url? "http://example.com"))
    (is (md/safe-url? "mailto:a@b.c"))
    (is (md/safe-url? "/relative/path"))
    (is (md/safe-url? "#fragment"))
    (is (md/safe-url? "./sibling"))
    (is (md/safe-url? "../parent")))
  (testing "script-bearing schemes are rejected (incl. casing / whitespace tricks)"
    (is (not (md/safe-url? "javascript:alert(1)")))
    (is (not (md/safe-url? "JavaScript:alert(1)")))
    (is (not (md/safe-url? "  javascript:alert(1)")))
    (is (not (md/safe-url? "vbscript:msgbox(1)")))
    (is (not (md/safe-url? "data:text/html,<script>alert(1)</script>")))
    (is (not (md/safe-url? "file:///etc/passwd")))
    (is (not (md/safe-url? "")))
    (is (not (md/safe-url? nil)))))

(deftest no-dangerously-set-inner-html
  (testing "no emitted attribute map carries :dangerouslySetInnerHTML for ANY input"
    (doseq [src ["# heading\n\nplain **text**"
                 "<script>alert(1)</script>"
                 "<div onclick=\"x\">raw block</div>"
                 "[x](javascript:alert(1))"
                 "![y](data:text/html;base64,PHNjcmlwdD4=)"]]
      (let [tree (md/render src)]
        (is (not (some #(contains? % :dangerouslySetInnerHTML) (attrs-of tree)))
            (str "no :dangerouslySetInnerHTML for input: " (pr-str src)))))))

(deftest script-injection-is-inert
  (testing "a <script> injection never becomes a live :script element"
    (let [tree (md/render "Hello\n\n<script>window.__pwned = true</script>\n\nbye")]
      ;; No script element was synthesised from the raw HTML.
      (is (not (contains? (tags tree) :script))
          "raw <script> must not become a :script hiccup element")
      ;; The raw source survives only as inert (React-escaped) text so the
      ;; user can SEE what was in the document.
      (is (str/includes? (strings-of tree) "window.__pwned")
          "the raw script source is preserved as inert escaped text")))

  (testing "raw inline HTML with an attribute is inert (no live element)"
    (let [tree (md/render "before <b onclick=\"boom()\">x</b> after")]
      ;; The raw <b ...> is NOT promoted to a live :b element with an
      ;; onclick — it is rendered as inert escaped text.
      (is (not (some #(contains? % :onclick) (attrs-of tree)))
          "no live onclick attribute is synthesised from raw HTML")
      (is (str/includes? (strings-of tree) "onclick")
          "the raw HTML (incl. the onclick attr) renders as inert text"))))

(deftest img-onerror-injection-is-inert
  (testing "a raw <img onerror=...> injection never becomes a live :img with onerror"
    (let [tree (md/render "<img src=x onerror=alert(1)>")]
      (is (not (some #(contains? % :onerror) (attrs-of tree)))
          "no live onerror handler is synthesised from raw HTML")
      ;; If a defensive renderer ever DID emit an :img, its src must not be
      ;; the bare `x` from raw HTML — but the contract is simply: no live
      ;; onerror. The raw markup survives as inert escaped text.
      (is (str/includes? (strings-of tree) "onerror")
          "the raw <img onerror=...> renders as inert escaped text"))))

(defn- attr-values
  "All values of attribute `k` across every element attribute map in `tree`."
  [tree k]
  (into [] (keep #(get % k)) (attrs-of tree)))

(deftest unsafe-link-href-never-reaches-a-live-attribute
  ;; Two layers neutralise a dangerous link scheme: markdown-it's own
  ;; `validateLink` rejects `javascript:`/`vbscript:`/`data:` (the link
  ;; degrades to literal text, no anchor), and our `sanitize-hiccup` drops
  ;; any unsafe `:href` that survives. Either way the contract is the same:
  ;; the unsafe URL never appears as a live `:href`, and the link text is
  ;; preserved (as anchor text or as inert literal text).
  (testing "a javascript: link never yields a live :href; text survives"
    (let [tree (md/render "click [here](javascript:alert(document.cookie))")]
      (is (not (some #(str/includes? (str %) "javascript:") (attr-values tree :href)))
          "no :href carries the javascript: scheme")
      (is (str/includes? (strings-of tree) "here")
          "the link text survives (as anchor text or inert literal text)")))

  (testing "vbscript: and data: links never yield a live :href"
    (doseq [scheme ["vbscript:msgbox(1)" "data:text/html,<script>alert(1)</script>"]]
      (let [tree (md/render (str "[x](" scheme ")"))]
        (is (empty? (filter #(or (str/includes? (str %) "vbscript:")
                                 (str/includes? (str %) "data:"))
                            (attr-values tree :href)))
            (str "no live :href for unsafe scheme: " scheme)))))

  (testing "a safe http(s) / relative / mailto href IS preserved on an anchor"
    (doseq [[href expect] [["https://example.com" "https://example.com"]
                           ["/relative/path"      "/relative/path"]
                           ["mailto:a@b.c"        "mailto:a@b.c"]
                           ["#anchor"             "#anchor"]]]
      (let [tree    (md/render (str "[x](" href ")"))
            anchors (filter #(href-tag-keyword? (first %) :a) (elements tree))]
        (is (some (fn [a] (= expect (:href (second a)))) anchors)
            (str "safe href preserved: " href))))))

(deftest unsafe-image-src-never-reaches-a-live-attribute
  (testing "a javascript:/data: image src never yields a live :src"
    (doseq [src ["javascript:alert(1)" "data:text/html,<script>alert(1)</script>"]]
      (let [tree (md/render (str "![alt](" src ")"))]
        (is (empty? (filter #(or (str/includes? (str %) "javascript:")
                                 (str/includes? (str %) "data:"))
                            (attr-values tree :src)))
            (str "no live :src for unsafe scheme: " src)))))

  (testing "a safe image src IS preserved on an :img"
    (let [tree (md/render "![cat](https://example.com/cat.png)")
          imgs (filter #(href-tag-keyword? (first %) :img) (elements tree))]
      (is (some (fn [i] (= "https://example.com/cat.png" (:src (second i)))) imgs)
          "safe image src preserved"))))

;; ===========================================================================
;; 2. CommonMark FIDELITY (shapes the hand-rolled subset could not render)
;; ===========================================================================

(deftest renders-gfm-table
  (testing "a GFM table renders as a real table structure"
    (let [tree (md/render (str "| A | B |\n"
                               "| --- | --- |\n"
                               "| 1 | 2 |\n"))
          ts   (tags tree)
          text (strings-of tree)]
      (is (contains? ts :table) "a :table element is emitted")
      ;; header + data cells
      (is (or (contains? ts :th) (contains? ts :td)) "table cells are emitted")
      (is (and (str/includes? text "A") (str/includes? text "1"))
          "header and body cell text are present"))))

(deftest renders-nested-list
  (testing "a nested unordered list renders nested :ul structure"
    (let [tree (md/render (str "- top\n"
                               "  - nested-a\n"
                               "  - nested-b\n"))
          ;; count :ul elements anywhere in the tree — a nested list yields >1
          uls  (filter #(= :ul (first %)) (elements tree))
          text (strings-of tree)]
      (is (>= (count uls) 2)
          "a nested list produces at least two :ul elements (outer + inner)")
      (is (and (str/includes? text "top")
               (str/includes? text "nested-a")
               (str/includes? text "nested-b"))
          "every list item's text is present"))))

(deftest renders-image
  (testing "an image renders as a real :img with src + alt"
    (let [tree (md/render "![a kitten](https://example.com/kitten.png)")
          imgs (filter #(= :img (first %)) (elements tree))]
      (is (seq imgs) "an :img element is emitted")
      (is (some (fn [i] (= "https://example.com/kitten.png" (:src (second i)))) imgs)
          "the image :src is preserved")
      (is (some (fn [i] (= "a kitten" (:alt (second i)))) imgs)
          "the image :alt is preserved"))))

(deftest renders-baseline-shapes
  (testing "headings, emphasis, code, and blockquote still render"
    (let [tree (md/render (str "# Title\n\n"
                               "A **bold** and *italic* and `code` span.\n\n"
                               "> a quote\n"))
          ts   (tags tree)]
      (is (contains? ts :h1) "ATX heading")
      (is (contains? ts :strong) "bold")
      (is (contains? ts :em) "italic")
      (is (contains? ts :code) "inline code")
      (is (contains? ts :blockquote) "blockquote")))

  (testing "a blank / nil source renders an empty fragment without throwing"
    (is (= [:<>] (md/render nil)))
    (is (= [:<>] (md/render "")))
    (is (= [:<>] (md/render "   \n  ")))))
