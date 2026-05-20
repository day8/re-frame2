(ns re-frame.story.ui.markdown-test
  "Pure CLJC coverage of the inline markdown → hiccup parser
  (rf2-wl7yr, audit C-2). Lives behind the JVM `clojure -M:test`
  runner — no DOM, no Reagent.

  The renderer in `re-frame.story.ui.docs/prose-section` and
  `re-frame.story.ui.workspace/prose-block` are thin projections
  over `parse` — pinning the parse output here covers both
  surfaces' contract without booting the shell."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.story.ui.markdown :as md]))

;; ---- helpers -------------------------------------------------------------

(defn- blocks
  "Strip the `:div.rf-story-md` wrapper and return the block-level
  vector. Reduces test boilerplate."
  [s]
  (vec (rest (md/parse s))))

;; ---- block shapes --------------------------------------------------------

(deftest empty-and-nil-inputs
  (testing "empty / nil / whitespace input produce an empty wrapper"
    (is (= [:div.rf-story-md] (md/parse "")))
    (is (= [:div.rf-story-md] (md/parse nil)))
    (is (= [:div.rf-story-md] (md/parse "   \n   ")))))

(deftest paragraph-with-plain-text
  (testing "plain text becomes a single <p> block"
    (let [out (blocks "Hello world.")]
      (is (= 1 (count out)))
      (is (= :p (first (first out))))
      (is (= "Hello world." (last (first out)))))))

(deftest two-paragraphs-separated-by-blank-line
  (testing "paragraphs separated by blank lines are distinct <p> blocks"
    (let [out (blocks "first.\n\nsecond.")]
      (is (= 2 (count out)))
      (is (every? #(= :p (first %)) out)))))

(deftest paragraph-joins-lines-with-space
  (testing "consecutive non-blank lines join with a single space"
    (let [out (blocks "line one\nline two")]
      (is (= 1 (count out)))
      ;; Body should contain both lines with a joining space.
      (let [body (rest (first out))]
        (is (some #{"line one"} body))
        (is (some #{"line two"} body))
        (is (some #{" "}        body))))))

(deftest hard-break-via-trailing-two-spaces
  (testing "a line ending with `  ` (CommonMark hard break) yields a
            `[:br]` element between the two lines"
    (let [out (blocks "first  \nsecond")
          body (rest (first out))]
      (is (some #(and (vector? %) (= :br (first %))) body)))))

(deftest headings-levels-1-through-6
  (testing "# / ## / ### map to :h1 / :h2 / :h3 etc."
    (is (= :h1 (first (first (blocks "# Title")))))
    (is (= :h2 (first (first (blocks "## Title")))))
    (is (= :h3 (first (first (blocks "### Title")))))
    (is (= :h4 (first (first (blocks "#### Title")))))
    (is (= :h5 (first (first (blocks "##### Title")))))
    (is (= :h6 (first (first (blocks "###### Title")))))))

(deftest heading-inline-parsing
  (testing "headings parse inline markdown — `code` / **bold** / *em*"
    (let [out (first (blocks "## A `code` heading"))]
      (is (= :h2 (first out)))
      ;; Should contain a [:code {} ...] child.
      (let [children (drop 2 out) ; drop tag + attrs
            code-children (filter (fn [c] (and (vector? c) (= :code (first c)))) children)]
        (is (= 1 (count code-children)))
        (is (= "code" (last (first code-children))))))))

(deftest bullet-list
  (testing "`- item` lines collapse into a single <ul> with one <li> per line"
    (let [out (first (blocks "- one\n- two\n- three"))]
      (is (= :ul (first out)))
      (let [items (drop 2 out)]
        (is (= 3 (count items)))
        (is (every? #(= :li (first %)) items))))))

(deftest bullet-list-with-asterisks
  (testing "`* item` syntax also produces a <ul>"
    (let [out (first (blocks "* one\n* two"))]
      (is (= :ul (first out)))
      (is (= 2 (count (drop 2 out)))))))

(deftest ordered-list
  (testing "`N. item` lines collapse into a single <ol>"
    (let [out (first (blocks "1. one\n2. two\n3. three"))]
      (is (= :ol (first out)))
      (is (= 3 (count (drop 2 out)))))))

(deftest fenced-code-block
  (testing "``` fences wrap into [:pre [:code]] preserving inner text"
    (let [out (first (blocks "```clojure\n(+ 1 2)\n```"))]
      (is (= :pre (first out)))
      (is (= {:data-lang "clojure"} (second out)))
      (let [code (nth out 2)]
        (is (= :code (first code)))
        (is (= "(+ 1 2)" (last code)))))))

(deftest fenced-code-block-no-lang
  (testing "fenced block without a lang carries an empty :data-lang"
    (let [out (first (blocks "```\nplain\n```"))]
      (is (= :pre (first out)))
      (is (= {:data-lang ""} (second out))))))

(deftest fenced-code-preserves-markdown-syntax
  (testing "markdown syntax inside a fenced block is NOT re-parsed —
            backticks / asterisks render literal"
    (let [out (first (blocks "```\n**not bold**\n- not list\n```"))
          code-text (last (nth out 2))]
      (is (= "**not bold**\n- not list" code-text)))))

(deftest blockquote
  (testing "`> ` lines collapse into a single <blockquote>"
    (let [out (first (blocks "> note one\n> note two"))]
      (is (= :blockquote (first out))))))

;; ---- inline span shapes --------------------------------------------------

(defn- p-children
  "Block index 0's children (skips the tag + attrs)."
  [s]
  (drop 2 (first (blocks s))))

(deftest inline-code
  (testing "`code` spans render as [:code {} \"code\"]"
    (let [children (p-children "see `foo` for details")
          codes    (filter (fn [c] (and (vector? c) (= :code (first c)))) children)]
      (is (= 1 (count codes)))
      (is (= "foo" (last (first codes)))))))

(deftest bold-spans
  (testing "**bold** spans render as [:strong {} ...]"
    (let [children (p-children "this is **important** stuff")
          strongs  (filter (fn [c] (and (vector? c) (= :strong (first c)))) children)]
      (is (= 1 (count strongs))))))

(deftest italic-star-spans
  (testing "*italic* spans render as [:em {} ...]"
    (let [children (p-children "this is *slanted* text")
          ems      (filter (fn [c] (and (vector? c) (= :em (first c)))) children)]
      (is (= 1 (count ems))))))

(deftest italic-underscore-spans
  (testing "_italic_ spans also render as [:em {} ...]"
    (let [children (p-children "this is _slanted_ text")
          ems      (filter (fn [c] (and (vector? c) (= :em (first c)))) children)]
      (is (= 1 (count ems))))))

(deftest link-spans
  (testing "[label](url) renders as an [:a] with safe :href + noopener
            target=_blank"
    (let [children (p-children "see [the docs](https://example.com)")
          links    (filter (fn [c] (and (vector? c) (= :a (first c)))) children)]
      (is (= 1 (count links)))
      (let [link (first links)
            attrs (second link)]
        (is (= "https://example.com" (:href attrs)))
        (is (= "_blank" (:target attrs)))
        (is (= "noopener noreferrer" (:rel attrs)))))))

(deftest link-url-safety
  (testing "javascript: links are scrubbed to # so XSS surfaces don't
            leak through markdown prose"
    (let [children (p-children "click [me](javascript:alert(1))")
          links    (filter (fn [c] (and (vector? c) (= :a (first c)))) children)
          attrs    (second (first links))]
      (is (= "#" (:href attrs))
          "the parser refuses javascript: URLs"))))

(deftest link-relative-paths-allowed
  (testing "relative paths like /foo and #anchor are accepted as-is"
    (let [c1    (p-children "[home](/index.html)")
          attrs (second (first (filter (fn [c] (and (vector? c) (= :a (first c)))) c1)))]
      (is (= "/index.html" (:href attrs))))
    (let [c2    (p-children "[top](#top)")
          attrs (second (first (filter (fn [c] (and (vector? c) (= :a (first c)))) c2)))]
      (is (= "#top" (:href attrs))))))

(deftest nested-inline-spans
  (testing "**bold with `code` inside** parses as a strong-wrap of a code child"
    (let [children (p-children "**bold with `code` inside**")
          strong   (first (filter (fn [c] (and (vector? c) (= :strong (first c)))) children))
          inner    (drop 2 strong)]
      (is (some (fn [c] (and (vector? c) (= :code (first c)))) inner)
          "the code span lives INSIDE the strong wrapper"))))

;; ---- mixed-document shapes -----------------------------------------------

(deftest mixed-paragraph-and-list
  (testing "a paragraph + a bullet list yield two separate blocks in
            the right order — pin the contract for the docs / workspace
            prose authors"
    (let [out (blocks "intro text.\n\n- one\n- two")]
      (is (= 2 (count out)))
      (is (= :p  (first (first out))))
      (is (= :ul (first (second out)))))))

(deftest heading-then-paragraph
  (testing "a heading + a paragraph below yield h1 + p in order"
    (let [out (blocks "# Title\n\nbody paragraph.")]
      (is (= 2 (count out)))
      (is (= :h1 (first (first out))))
      (is (= :p  (first (second out)))))))

(deftest realistic-prose-block
  (testing "a realistic prose authoring sample — heading, paragraph,
            list, code — parses into the expected block sequence"
    (let [src (str "# Counter variant\n"
                   "\n"
                   "A simple counter with `inc` / `dec` actions.\n"
                   "\n"
                   "Useful when you want to:\n"
                   "\n"
                   "- demonstrate event dispatch\n"
                   "- verify the **subscribe** path\n"
                   "\n"
                   "```\n"
                   "(dispatch [:counter/inc])\n"
                   "```")
          out (blocks src)]
      (is (= 5 (count out)))
      (is (= [:h1 :p :p :ul :pre] (mapv first out))))))

(deftest no-raw-html-leakage
  (testing "raw HTML / script tags survive as plain text — they're NOT
            parsed as HTML by the renderer (defends against XSS via
            markdown content)"
    (let [out (blocks "<script>alert(1)</script>")]
      ;; Should still be a paragraph; tags appear as literal text.
      (is (= :p (first (first out))))
      (let [body (rest (first out))]
        (is (some #(and (string? %) (not= -1 (.indexOf ^String % "<script>"))) body))))))
