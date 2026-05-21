(ns day8.re-frame2-causa.views.edn-widget.widget-cljs-test
  "Tests for the canonical Causa EDN widget.

  ## What's under test

  1. **Variant dispatch** — `browse` / `diff` / `mini` / `render` route
     to the right underlying engine call shape. `browse` of a
     collection routes through `data-display/render-tree`; `browse` of
     a non-collection routes through `cljs-devtools-render`; `mini`
     always routes through `cljs-devtools-render`.
  2. **Code-block tokenizer** — `tokenize-clojure` classifies keywords,
     strings, numbers, parens, builtins, and plain symbols correctly.
     This is the source-text highlighter (not CLJS-value rendering;
     cljs-devtools owns values, this tokenizer owns source strings).
  3. **Code-block rendering** — `code-block` returns the expected
     `[:pre [:code ...]]` shape with per-token colour spans.
  4. **Mini chrome** — `:title`, `:data-pr`, and `:data-testid`
     attributes are set; long pr-str gets truncated in `:data-pr`.

  Pure-data scope — no DOM mount; hiccup-shape assertions only."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.views.edn-widget.widget :as w]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (when (vector? node)
                (swap! out conj node)
                (doseq [child (rest node)]
                  (cond
                    (vector? child) (walk child)
                    (seq? child)    (doseq [c child] (walk c))))))]
      (walk tree))
    @out))

(defn- find-by-testid
  [tree id]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= id (get (second n) :data-testid)))))
       first))

;; ---- variant: browse -----------------------------------------------------

(deftest browse-of-collection-routes-through-data-display
  (let [out (w/browse {:value     {:a 1 :b 2}
                       :panel-id  :test
                       :render-id "browse-1"})]
    (testing "browse of a map returns a [:div ...] root with the panel-keyed data-testid"
      (is (vector? out))
      (is (= :div (first out)))
      ;; The underlying render-tree stamps the panel-keyed root testid.
      (is (some? (find-by-testid out "rf-causa-data-display-test-browse-1"))))))

(defn- find-testid-prefix
  "Return the first hiccup node whose :data-testid attr starts with
  `prefix`. The engine's leaf-testid is dynamic
  (`rf-causa-data-display-leaf-<path>`) so callers asserting that a
  leaf rendered cannot match exactly."
  [tree prefix]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (let [id (get (second n) :data-testid)]
                        (and (string? id)
                             (.startsWith id prefix))))))
       first))

(deftest browse-of-collection-still-routes-keyword-leaf-to-engine
  (let [out  (w/browse {:value     {:k :foo/bar}
                        :panel-id  :test
                        :render-id "kw-1"})
        leaf (find-testid-prefix out "rf-causa-data-display-leaf-")]
    (testing "map containing a keyword routes through the engine's leaf renderer"
      (is (some? leaf)))))

(deftest browse-of-non-collection-routes-through-cljs-devtools
  (let [out (w/browse {:value     :foo/bar
                       :panel-id  :test
                       :render-id "scalar-1"})]
    (testing "non-collection browse returns a [:div ...] container"
      (is (vector? out))
      (is (= :div (first out)))
      (is (some? (find-by-testid out
                                 "rf-causa-edn-widget-browse-test-scalar-1"))))))

;; ---- variant: diff -------------------------------------------------------

(deftest diff-emits-diff-tree
  (let [out (w/diff {:before    {:a 1}
                     :after     {:a 2}
                     :panel-id  :test
                     :render-id "diff-1"})]
    (testing "diff returns the diff-mode tree"
      (is (vector? out))
      (is (= :div (first out)))
      (is (some? (find-by-testid out "rf-causa-data-display-test-diff-1"))))))

;; ---- variant: mini -------------------------------------------------------

(deftest mini-returns-span-shape
  (let [out (w/mini {:a 1})]
    (testing "mini returns a [:span ...] with the canonical testid"
      (is (= :span (first out)))
      (is (= "rf-causa-edn-widget-mini"
             (-> out second :data-testid))))))

(deftest mini-short-value-data-pr-not-truncated
  (let [out   (w/mini {:a 1})
        attrs (second out)]
    (testing "short pr-str fits — no ellipsis in :data-pr"
      (is (= (pr-str {:a 1}) (:title attrs)))
      (is (not (re-find #"…$" (:data-pr attrs)))))))

(deftest mini-long-value-data-pr-truncated-but-title-full
  (let [v   (zipmap (map #(keyword (str "key-" %)) (range 30))
                    (range 30))
        out (w/mini v 40)
        attrs (second out)]
    (testing "ellipsis appended to :data-pr when over max-len"
      (is (re-find #"…$" (:data-pr attrs))))
    (testing ":title attr carries the full pr-str"
      (is (= (pr-str v) (:title attrs))))))

(deftest mini-renders-cljs-devtools-markup-inside
  (let [out   (w/mini :hello)
        spans (walk-hiccup out)]
    (testing "mini embeds cljs-devtools-rendered hiccup as children"
      ;; cljs-devtools renders a keyword as a coloured span; we should
      ;; see at least one nested :span under the outer :span.
      (is (some #(and (vector? %) (= :span (first %))) (rest spans))))))

;; ---- code-block tokenizer ------------------------------------------------

(deftest classify-token-keyword
  (is (= :keyword  (w/classify-token ":foo")))
  (is (= :keyword  (w/classify-token ":ns/foo"))))

(deftest classify-token-string
  (is (= :string   (w/classify-token "\"hi\"")))
  (is (= :string   (w/classify-token "\"with \\\"quote\\\"\""))))

(deftest classify-token-number
  (is (= :number   (w/classify-token "42")))
  (is (= :number   (w/classify-token "-3.14"))))

(deftest classify-token-comment
  (is (= :comment  (w/classify-token "; hi"))))

(deftest classify-token-paren
  (is (= :paren    (w/classify-token "(")))
  (is (= :paren    (w/classify-token "}"))))

(deftest classify-token-builtin
  (is (= :builtin  (w/classify-token "reg-event-db")))
  (is (= :builtin  (w/classify-token "let"))))

(deftest classify-token-symbol
  (is (= :symbol   (w/classify-token "my-symbol")))
  (is (= :symbol   (w/classify-token "x"))))

(deftest tokenize-clojure-roundtrip
  (testing "concatenating tokenized literals reconstructs the source"
    (let [src  "(reg-event-db :foo (fn [db [_ x]] (assoc db :y x)))"
          toks (w/tokenize-clojure src)]
      (is (= src (apply str (map second toks)))))))

(deftest tokenize-clojure-keyword-classification
  (let [toks (w/tokenize-clojure "(:foo bar)")
        kws  (filter #(= :keyword (first %)) toks)]
    (is (= 1 (count kws)))
    (is (= ":foo" (second (first kws))))))

(deftest tokenize-clojure-string-classification
  (let [toks (w/tokenize-clojure "(def s \"hello\")")
        strs (filter #(= :string (first %)) toks)]
    (is (= 1 (count strs)))
    (is (= "\"hello\"" (second (first strs))))))

(deftest tokenize-clojure-builtin-classification
  (let [toks (w/tokenize-clojure "(reg-event-db :foo)")
        blt  (filter #(= :builtin (first %)) toks)]
    (is (= 1 (count blt)))
    (is (= "reg-event-db" (second (first blt))))))

;; ---- code-block render ---------------------------------------------------

(deftest code-block-empty-source-renders-placeholder
  (let [out (w/code-block {:source nil})]
    (is (some? (find-by-testid out "rf-causa-edn-widget-code-empty")))))

(deftest code-block-renders-pre-code-shape
  (let [out (w/code-block {:source "(def x 1)"})]
    (testing "outer is [:pre ...]"
      (is (= :pre (first out)))
      (is (= "clojure" (get (second out) :data-lang)))
      (is (= "rf-causa-edn-widget-code"
             (get (second out) :data-testid))))
    (testing "contains [:code ...] child"
      (let [code-node (some #(when (and (vector? %) (= :code (first %))) %)
                            (walk-hiccup out))]
        (is (some? code-node))))))

(deftest code-block-keyword-token-uses-accent-violet
  (let [out      (w/code-block {:source ":foo"})
        spans    (walk-hiccup out)
        violet?  (fn [n]
                   (let [c (some-> n second :style :color)]
                     (= c (:accent-violet tokens))))
        kw-span  (some #(when (and (vector? %)
                                   (= :span (first %))
                                   (violet? %)) %)
                       spans)]
    (is (some? kw-span)
        "keyword tokens render with accent-violet colour")))

(deftest code-block-string-token-uses-green
  (let [out     (w/code-block {:source "\"hi\""})
        spans   (walk-hiccup out)
        green?  (fn [n]
                  (let [c (some-> n second :style :color)]
                    (= c (:green tokens))))
        str-span (some #(when (and (vector? %)
                                   (= :span (first %))
                                   (green? %)) %)
                       spans)]
    (is (some? str-span))))

;; ---- zprint pre-format ---------------------------------------------------

(deftest format-source-nil-input-returns-input
  (testing "nil source survives — format-source never throws"
    (is (nil? (w/format-source nil)))))

(deftest format-source-empty-input-returns-input
  (testing "empty / blank source survives unchanged"
    (is (= "" (w/format-source "")))))

(deftest format-source-pretty-prints-clojure
  (let [src       "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"
        formatted (w/format-source src)]
    (testing "zprint reformats the input (canonical line-breaks)"
      ;; A well-formed registration on one line gets canonicalised —
      ;; either the same string back (zprint thinks it's already ideal)
      ;; or one with newlines introduced. The output MUST still be a
      ;; valid string.
      (is (string? formatted)))
    (testing "the round-trip text contains the same form contents"
      ;; zprint never drops form contents — every token in the input
      ;; appears in the output (possibly across more lines).
      (is (re-find #"reg-event-db" formatted))
      (is (re-find #":counter/inc" formatted))
      (is (re-find #"update" formatted)))))

(deftest format-source-malformed-input-falls-through
  (testing "zprint parse failure returns the original input unchanged"
    ;; Unmatched paren — zprint's parser refuses; format-source's
    ;; try/catch falls through.
    (let [bad "(reg-event-db :foo "]
      (is (= bad (w/format-source bad))))))

(deftest code-block-pre-formats-via-zprint
  (let [out  (w/code-block {:source "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (testing "code-block emits the :pre root with a :data-formatted attr"
      (is (some? attrs))
      (is (contains? attrs :data-formatted)))))

(deftest code-block-non-clojure-lang-skips-format
  (let [out  (w/code-block {:source "function f(){}" :lang :javascript})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (testing "non-clojure lang skips the zprint pre-format stage"
      (is (= "false" (:data-formatted attrs))
          ":data-formatted = false when zprint did not run"))))

;; ---- highlight-clojure-token mapping -------------------------------------

(deftest highlight-clojure-token-mapping
  (is (= :accent-violet (w/highlight-clojure-token :keyword)))
  (is (= :green         (w/highlight-clojure-token :string)))
  (is (= :cyan          (w/highlight-clojure-token :number)))
  (is (= :text-tertiary (w/highlight-clojure-token :comment)))
  (is (= :text-primary  (w/highlight-clojure-token :symbol)))
  (is (= :accent-violet (w/highlight-clojure-token :builtin)))
  ;; Unknown token-type falls through to text-primary.
  (is (= :text-primary  (w/highlight-clojure-token :unknown))))

;; ---- render dispatcher ---------------------------------------------------

(deftest render-defaults-to-browse
  (let [out (w/render {:value     {:a 1}
                       :panel-id  :test
                       :render-id "dispatch-1"})]
    (is (= :div (first out)))))

(deftest render-routes-to-diff-when-variant-diff
  (let [out (w/render {:variant   :diff
                       :before    {:a 1}
                       :after     {:a 2}
                       :panel-id  :test
                       :render-id "dispatch-2"})]
    (is (= :div (first out)))))

(deftest render-routes-to-mini-when-variant-mini
  (let [out (w/render {:variant :mini
                       :value   {:a 1}})]
    (is (= :span (first out)))))
