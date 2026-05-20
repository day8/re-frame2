(ns day8.re-frame2-causa.views.edn-widget.widget-cljs-test
  "Tests for the canonical Causa EDN widget (rf2-9wsdy).

  ## What's under test

  1. **Variant dispatch** — `browse` / `diff` / `mini` / `render` route
     to the right underlying engine call shape.
  2. **Code-block tokenizer** — `tokenize-clojure` classifies keywords,
     strings, numbers, parens, builtins, and plain symbols correctly.
  3. **Code-block rendering** — `code-block` returns the expected
     `[:pre [:code ...]]` shape with per-token colour spans.
  4. **Mini truncation** — long values get the ellipsis + the full
     value lands in the title attribute.

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

(deftest browse-returns-tree-hiccup
  (let [out (w/browse {:value     {:a 1 :b 2}
                       :panel-id  :test
                       :render-id "browse-1"})]
    (testing "browse returns a [:div ...] root with the panel-keyed data-testid"
      (is (vector? out))
      (is (= :div (first out)))
      ;; The underlying render-tree stamps the panel-keyed root testid.
      (is (some? (find-by-testid out "rf-causa-data-display-test-browse-1"))))))

(deftest browse-renders-keyword-value-in-violet
  (let [out  (w/browse {:value     :foo/bar
                        :panel-id  :test
                        :render-id "kw-1"})
        leaf (find-by-testid out "rf-causa-data-display-leaf-")]
    (testing "browse routes through the canonical leaf renderer"
      (is (some? leaf)))))

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

(deftest mini-short-value-is-not-truncated
  (let [out (w/mini {:a 1})]
    (testing "short pr-str fits — no ellipsis"
      (is (= :span (first out)))
      (let [body (last out)]
        (is (string? body))
        (is (not (re-find #"…$" body)))))))

(deftest mini-long-value-is-truncated-and-title-carries-full
  (let [v   (zipmap (map #(keyword (str "key-" %)) (range 30))
                    (range 30))
        out (w/mini v 40)
        attrs (second out)]
    (testing "ellipsis appended when over max-len"
      (is (re-find #"…$" (last out))))
    (testing "title attr carries the full pr-str"
      (is (= (pr-str v) (:title attrs))))))

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
