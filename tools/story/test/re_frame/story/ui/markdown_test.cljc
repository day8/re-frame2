(ns re-frame.story.ui.markdown-test
  "Pure CLJC coverage of the markdown → hiccup parser. The docs prose
  section and `:prose`-layout workspaces are thin projections over `parse`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [re-frame.story.ui.markdown :as rf.story.ui.markdown]))

(defn- blocks
  "The block-level vector inside the `:div.rf-story-md` wrapper."
  [s]
  (vec (rest (rf.story.ui.markdown/parse s))))

;; ---- block shapes --------------------------------------------------------

(deftest empty-and-nil-inputs
  (testing "nil / whitespace-only input produce an empty wrapper"
    (is (= [:div.rf-story-md] (rf.story.ui.markdown/parse nil)))
    (is (= [:div.rf-story-md] (rf.story.ui.markdown/parse "   \n   ")))))

(deftest paragraph-joins-lines-with-space
  (testing "consecutive non-blank lines form one <p>, joined with a single space"
    (is (= [[:p {} "line one" " " "line two"]] (blocks "line one\nline two")))))

(deftest hard-break-via-trailing-two-spaces
  (testing "a line ending with `  ` (CommonMark hard break) yields a `[:br]`"
    (is (= [[:p {} "first" [:br {}] "second"]] (blocks "first  \nsecond")))))

(deftest headings-levels-1-through-6
  (is (= :h1 (first (first (blocks "# Title")))))
  (is (= :h6 (first (first (blocks "###### Title"))))))

(deftest heading-inline-parsing
  (testing "headings parse inline markdown"
    (is (= [[:h2 {} "A " [:code {} "code"] " heading"]] (blocks "## A `code` heading")))))

(deftest bullet-list
  (testing "`- item` and `* item` lines collapse into one <ul>, one <li> per line"
    (is (= [[:ul {} [:li {} "one"] [:li {} "two"] [:li {} "three"]]]
           (blocks "- one\n- two\n- three")))
    (is (= [[:ul {} [:li {} "one"] [:li {} "two"]]] (blocks "* one\n* two")))))

(deftest ordered-list
  (testing "`N. item` lines collapse into a single <ol>"
    (is (= [[:ol {} [:li {} "one"] [:li {} "two"] [:li {} "three"]]]
           (blocks "1. one\n2. two\n3. three")))))

(deftest fenced-code-block
  (testing "``` fences wrap into [:pre [:code]] preserving inner text"
    (is (= [[:pre {:data-lang "clojure"} [:code {} "(+ 1 2)"]]]
           (blocks "```clojure\n(+ 1 2)\n```")))))

(deftest fenced-code-preserves-markdown-syntax
  (testing "markdown syntax inside a fenced block is NOT re-parsed"
    (let [out (first (blocks "```\n**not bold**\n- not list\n```"))]
      (is (= "**not bold**\n- not list" (last (nth out 2)))))))

(deftest blockquote
  (testing "`> ` lines collapse into a single <blockquote>"
    (is (= [[:blockquote {} "note one" " " "note two"]] (blocks "> note one\n> note two")))))

;; ---- inline span shapes --------------------------------------------------

(defn- p-children
  "Block index 0's children (skips the tag + attrs)."
  [s]
  (drop 2 (first (blocks s))))

(deftest emphasis-spans
  (are [src] (= 1 (count (filter (fn [c] (and (vector? c) (= :em (first c))))
                                 (p-children src))))
    "this is *slanted* text"
    "this is _slanted_ text"))

(deftest link-spans
  (testing "[label](url) renders as an [:a] with safe :href + noopener target=_blank"
    (is (= ["see " [:a {:href "https://example.com" :target "_blank" :rel "noopener noreferrer"}
                    "the docs"]]
           (p-children "see [the docs](https://example.com)")))))

(deftest link-href-sanitisation
  (are [src href] (= href (:href (second (first (filter (fn [c] (and (vector? c) (= :a (first c))))
                                                        (p-children src))))))
    ;; javascript: links are scrubbed to # so XSS cannot ride in on prose
    "click [me](javascript:alert(1))" "#"
    "[home](/index.html)"             "/index.html"))

(deftest nested-inline-spans
  (testing "**bold with `code` inside** parses as a strong-wrap of a code child"
    (is (= [[:strong {} "bold with " [:code {} "code"] " inside"]]
           (p-children "**bold with `code` inside**")))))

;; ---- mixed-document shapes -----------------------------------------------

(deftest realistic-prose-block
  (testing "heading, paragraph, list, code — parse into the expected block sequence"
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
                   "```")]
      (is (= [:h1 :p :p :ul :pre] (mapv first (blocks src)))))))

(deftest no-raw-html-leakage
  (testing "raw HTML / script tags survive as plain text — never parsed as HTML"
    (is (= [[:p {} "<script>alert(1)</script>"]] (blocks "<script>alert(1)</script>")))))
