(ns re-frame.notebook-markdown-cljs-test
  "Contract tests for the Notebook example's hand-rolled markdown renderer
  (`notebook.core` — rf2-kpvpmj). The preview pane renders USER-TYPED
  markdown through a pure `string -> hiccup` parser
  (`markdown->hiccup` / `inline-md->hiccup` / `split-by-regex` /
  `render-block`) whose link handling leans on a SECURITY-relevant
  `safe-href` scheme allowlist — and, before this test, had ZERO backing
  coverage.

  Why HERE and not under examples/: the example tree is test-free
  (rf2-8cevm), and `notebook.core` is a Reagent-coupled `.cljs`-only entry
  namespace. These pure parser fns therefore run under the consolidated
  `:node-test` CLJS build, which has `../examples/core` on its source paths
  and is the only runtime where this example's ns actually loads — the same
  classpath posture as `re-frame.seven-guis-cells-parser-cljs-test`.

  Note the DISTINCT sibling: `realworld-shared.markdown/safe-url?`
  (re-frame.realworld-markdown-cljs-test) is a SEPARATE, CommonMark-library
  implementation for the RealWorld app — it is already thoroughly tested and
  is NOT the code under test here. `notebook.core`'s hand-rolled `safe-href`
  + parser are their own surface.

  Three contracts:

  1. safe-href ALLOWLIST (the XSS primitive, tested in isolation). Two things
     pass and return the href verbatim: an allowlisted absolute scheme
     (http/https/mailto) and a scheme-LESS link (relative / `#fragment`,
     which can't carry code). Everything else — `javascript:`, `data:`,
     `vbscript:`, `file:` — returns nil (the caller degrades it to inert
     text). Casing + leading/trailing whitespace are normalised.

  2. markdown->hiccup XSS BY CONSTRUCTION. The renderer emits HICCUP DATA
     (React escapes it) and routes every link destination through
     `safe-href`, so an unsafe link yields NO live `:a`/`:href` — it degrades
     to `[:span.nb-unsafe-link]` with the text preserved — while a safe link
     yields a real `[:a {:href .. :rel .. :target ..}]`.

  3. markdown->hiccup STRUCTURE. Headings, bold, italic, inline code, and
     ordered/unordered lists render to the expected tags.

  Plus a regression guard for rf2-9tllom — the control-char-broken scheme
  vectors (`java<TAB>script:` and friends) that a browser collapses back to
  a live `javascript:` before resolving the link must now be REJECTED, both
  by `safe-href` in isolation and end-to-end through `markdown->hiccup`
  (no live `:a`/`:href`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [notebook.core :as nb]))

;; `safe-href` is private to notebook.core; reach it by var (the same posture
;; `re-frame.source-coord-parity-cljs-test` uses to exercise an internal
;; helper by var). Testing it in isolation pins the primitive directly, on top
;; of the end-to-end markdown->hiccup assertions below.
(def ^:private safe-href #'notebook.core/safe-href)

;; ---------------------------------------------------------------------------
;; Hiccup tree-walk helpers (markdown->hiccup returns pure hiccup data)
;; ---------------------------------------------------------------------------

(defn- elements
  "All hiccup element vectors in `tree` (depth-first) — vectors whose head is
  a keyword tag."
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
  "Every attribute map (an element vector's 2nd item, when a map) in `tree`."
  [tree]
  (into []
        (keep (fn [node]
                (when (and (vector? node)
                           (keyword? (first node))
                           (map? (second node)))
                  (second node))))
        (elements tree)))

(defn- attr-values
  "All values of attribute `k` across every element attribute map in `tree`."
  [tree k]
  (into [] (keep #(get % k)) (attrs-of tree)))

(defn- strings-of
  "All string leaves in `tree`, joined — the visible text."
  [tree]
  (let [acc (atom [])]
    (walk/postwalk
      (fn [node]
        (when (string? node) (swap! acc conj node))
        node)
      tree)
    (str/join " " @acc)))

;; ===========================================================================
;; 1. safe-href ALLOWLIST — the XSS primitive in isolation
;; ===========================================================================

(deftest safe-href-passes-allowlisted-and-scheme-less-links
  (testing "an allowlisted absolute scheme is returned verbatim (clickable)"
    (doseq [href ["http://example.com"
                  "https://example.com/a/b?q=1#x"
                  "HTTPS://EXAMPLE.COM"
                  "mailto:a@b.c"
                  "MailTo:a@b.c"]]
      (is (= href (safe-href href))
          (str href " is an allowlisted scheme and must pass through"))))
  (testing "a scheme-LESS link (relative path / fragment) is safe — it can't
            carry code, so it is returned verbatim"
    (doseq [href ["/relative/path"
                  "relative/path"
                  "./sibling"
                  "../parent"
                  "#fragment"
                  "page.html"
                  "a/b:c"]]
      (is (= href (safe-href href))
          (str href " has no leading scheme and must pass through")))))

(deftest safe-href-rejects-script-and-data-schemes
  (testing "script/data/other non-allowlisted schemes return nil (degraded to
            inert text by the caller)"
    (doseq [href ["javascript:alert(1)"
                  "JavaScript:alert(1)"
                  "JAVASCRIPT:alert(document.cookie)"
                  "vbscript:msgbox(1)"
                  "data:text/html,<script>alert(1)</script>"
                  "DATA:text/html,x"
                  "file:///etc/passwd"]]
      (is (nil? (safe-href href))
          (str href " is not allowlisted and must be rejected (nil)"))))
  (testing "leading/trailing WHITESPACE (incl. a leading tab) does not sneak a
            script scheme past the allowlist — safe-href strips first"
    (doseq [href ["  javascript:alert(1)"
                  "javascript:alert(1)   "
                  "\tjavascript:alert(1)"
                  "  vbscript:x  "]]
      (is (nil? (safe-href href))
          (str (pr-str href) " must be rejected after stripping"))))
  (testing "nil href is rejected (nil in, nil out)"
    (is (nil? (safe-href nil)))))

;; ===========================================================================
;; 1b. rf2-9tllom REGRESSION — control-char-obfuscated schemes are REJECTED
;; ===========================================================================

(deftest safe-href-rejects-control-char-obfuscated-schemes
  ;; rf2-9tllom regression guard. A control char (TAB / LF / CR / a leading
  ;; C0 control such as SOH) spliced into a scheme token breaks the anchored
  ;; scheme regex, so the OLD safe-href misclassified the value as scheme-LESS
  ;; and returned it verbatim -> a LIVE `:a {:href ...}`. Browsers strip these
  ;; control chars before resolving the scheme, so `java<TAB>script:alert(1)`
  ;; fires as `javascript:` on click — a classic allowlist-evasion XSS. The
  ;; fix strips every ASCII control + space char BEFORE scheme detection, then
  ;; default-denies the de-obfuscated (non-allowlisted) scheme. Every vector
  ;; below must now be REJECTED (nil).
  (let [tab (str (char 9))  lf  (str (char 10)) cr  (str (char 13))
        soh (str (char 1))  vt  (str (char 11)) ff  (str (char 12))
        nul (str (char 0))  del (str (char 127))]
    (testing "a control char breaking the scheme token no longer smuggles a
              script/data scheme past the allowlist"
      (doseq [href [(str "java" tab "script:alert(1)")   ;; embedded TAB
                    (str "java" lf "script:alert(1)")    ;; embedded LF
                    (str "java" cr "script:alert(1)")    ;; embedded CR
                    (str "java" vt "script:alert(1)")    ;; embedded VT (0x0B)
                    (str "java" ff "script:alert(1)")    ;; embedded FF (0x0C)
                    (str "java" nul "script:alert(1)")   ;; embedded NUL (0x00)
                    "java script:alert(1)"               ;; embedded SPACE
                    (str soh "javascript:alert(1)")      ;; leading SOH (0x01)
                    (str tab "javascript:alert(1)")      ;; leading TAB
                    (str "javascript" tab ":alert(1)")   ;; control before ':'
                    (str "data" tab ":text/html,x")      ;; obfuscated data:
                    (str "vb" cr "script:msgbox(1)")     ;; obfuscated vbscript:
                    (str "javascript:alert(1)" del)]]    ;; trailing DEL (0x7F)
        (is (nil? (safe-href href))
            (str "rf2-9tllom: control-char-obfuscated scheme "
                 (pr-str href) " must be rejected (nil)"))))))

;; ===========================================================================
;; 2. markdown->hiccup — XSS by construction
;; ===========================================================================

(deftest unsafe-link-yields-no-live-anchor-text-preserved
  (testing "a javascript: link renders as inert [:span.nb-unsafe-link], never
            a live :a, and never a :href carrying the scheme; text survives"
    (let [tree (nb/markdown->hiccup "click [here](javascript:alert(1))")]
      (is (not (contains? (tags tree) :a))
          "no live :a element is synthesised for an unsafe link")
      (is (contains? (tags tree) :span.nb-unsafe-link)
          "the unsafe link degrades to the inert span")
      (is (empty? (attr-values tree :href))
          "no :href attribute is emitted at all for an unsafe link")
      (is (str/includes? (strings-of tree) "here")
          "the link text is preserved as inert text")))
  (testing "data: / vbscript: links likewise emit no live :a or :href"
    (doseq [scheme ["data:text/html,<script>alert(1)</script>"
                    "vbscript:msgbox(1)"]]
      (let [tree (nb/markdown->hiccup (str "[x](" scheme ")"))]
        (is (not (contains? (tags tree) :a))
            (str "no :a for unsafe scheme " (pr-str scheme)))
        (is (empty? (filter #(or (str/includes? (str %) "javascript:")
                                 (str/includes? (str %) "data:")
                                 (str/includes? (str %) "vbscript:"))
                            (attr-values tree :href)))
            (str "no live :href for unsafe scheme " (pr-str scheme)))))))

(deftest control-char-obfuscated-link-degrades-to-inert-span
  ;; rf2-9tllom, end-to-end through the full markdown->hiccup pipeline: a
  ;; markdown link whose scheme is broken by a control char must degrade to
  ;; the inert [:span.nb-unsafe-link] — no live :a, no :href — exactly like a
  ;; plain `javascript:` link, so the browser never gets a chance to collapse
  ;; the obfuscated scheme back into an executable one.
  (let [tab (str (char 9)) soh (str (char 1))]
    (doseq [scheme [(str "java" tab "script:alert(1)")   ;; embedded TAB
                    (str soh "javascript:alert(1)")]]    ;; leading SOH
      (let [tree (nb/markdown->hiccup (str "click [here](" scheme ")"))]
        (is (not (contains? (tags tree) :a))
            (str "no live :a for control-char scheme " (pr-str scheme)))
        (is (contains? (tags tree) :span.nb-unsafe-link)
            (str "control-char scheme degrades to the inert span: "
                 (pr-str scheme)))
        (is (empty? (attr-values tree :href))
            (str "no :href emitted for control-char scheme " (pr-str scheme)))
        (is (str/includes? (strings-of tree) "here")
            "the link text is preserved as inert text")))))

(deftest safe-link-yields-a-hardened-anchor
  (testing "a safe http(s)/mailto/relative link becomes a real :a with the
            href preserved and hardened rel + target attributes"
    (doseq [href ["https://example.com"
                  "http://example.com/x"
                  "mailto:a@b.c"
                  "/relative/path"
                  "#anchor"]]
      (let [tree    (nb/markdown->hiccup (str "see [link](" href ")"))
            anchors (filter #(= :a (first %)) (elements tree))]
        (is (= 1 (count anchors))
            (str "exactly one :a for safe href " href))
        (let [[_ attrs text] (first anchors)]
          (is (= href (:href attrs)) (str "href preserved: " href))
          (is (= "noopener noreferrer" (:rel attrs))
              "rel is hardened against tab-nabbing/referrer leak")
          (is (= "_blank" (:target attrs)) "opens in a new tab")
          (is (= "link" text) "anchor text preserved"))))))

;; ===========================================================================
;; 3. markdown->hiccup — structural rendering
;; ===========================================================================

(deftest headings-render-to-their-level
  (testing "#/##/### map to :h1/:h2/:h3 and inline runs still render inside"
    (doseq [[src tag] [["# Big **bold**"   :h1]
                       ["## Medium"        :h2]
                       ["### Small *em*"   :h3]]]
      (let [tree  (nb/markdown->hiccup src)
            block (first tree)]
        (is (= tag (first block)) (str src " -> " tag)))))
  (testing "a heading with a bold run keeps the :strong child"
    (let [tree (nb/markdown->hiccup "# Big **bold**")]
      (is (contains? (tags tree) :strong)
          "the **bold** run is parsed inside the heading"))))

(deftest inline-runs-render-bold-italic-code
  (testing "a paragraph with bold / italic / inline-code emits :strong / :em /
            :code and preserves the surrounding text"
    (let [tree (nb/markdown->hiccup "plain **b** and *i* and `c` end")
          ts   (tags tree)]
      (is (contains? ts :p)      "paragraph wrapper")
      (is (contains? ts :strong) "**b** -> :strong")
      (is (contains? ts :em)     "*i* -> :em")
      (is (contains? ts :code)   "`c` -> :code")
      (is (str/includes? (strings-of tree) "plain")
          "surrounding text is preserved"))))

(deftest lists-render-ul-and-ol
  (testing "a '- ' block renders a :ul with one :li per line"
    (let [tree (nb/markdown->hiccup "- alpha\n- beta\n- gamma")
          uls  (filter #(= :ul (first %)) (elements tree))
          lis  (filter #(= :li (first %)) (elements tree))]
      (is (= 1 (count uls)) "one :ul")
      (is (= 3 (count lis)) "three :li")
      (is (str/includes? (strings-of tree) "beta") "item text preserved")))
  (testing "a '1. ' block renders an :ol with one :li per numbered line"
    (let [tree (nb/markdown->hiccup "1. one\n2. two")
          ols  (filter #(= :ol (first %)) (elements tree))
          lis  (filter #(= :li (first %)) (elements tree))]
      (is (= 1 (count ols)) "one :ol")
      (is (= 2 (count lis)) "two :li")
      (is (str/includes? (strings-of tree) "two") "item text preserved"))))

(deftest blank-source-renders-nothing
  (testing "a blank / nil source yields an empty block vector (no throw)"
    (is (= [] (nb/markdown->hiccup nil)))
    (is (= [] (nb/markdown->hiccup "")))
    (is (= [] (nb/markdown->hiccup "   \n\n   ")))))

(deftest multi-block-splits-on-blank-lines
  (testing "two paragraphs separated by a blank line render as two blocks"
    (let [tree (nb/markdown->hiccup "first para\n\nsecond para")]
      (is (= 2 (count tree)) "two top-level blocks")
      (is (every? #(= :p (first %)) tree) "each block is a paragraph"))))
