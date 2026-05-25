(ns day8.re-frame2-xray.views.edn-widget.widget-cljs-test
  "Tests for the Xray EDN widget facade — post-rf2-oqa60 phase 1.

  After the data-display rebuild the facade is a thin delegate over
  `views.data-display`; tests for the prior cljs-devtools routing /
  copy-affordance behaviour are deleted with the cljs-devtools cut-over.

  This file now exercises ONLY the surfaces the facade still owns
  end-to-end:

  1. **Code-block tokenizer** — `tokenize-clojure` + `classify-token`
     handle source-text highlighting (CLJS-source rendering, NOT
     CLJS-value rendering — values flow through the new widget).
  2. **Code-block rendering** — `code-block` returns the expected
     `[:pre [:code ...]]` shape with per-token colour spans.
  3. **zprint pre-format** — `format-source` survives nil / empty /
     malformed input and round-trips well-formed Clojure.
  4. **highlight-clojure-token mapping** — every token-type resolves
     to its Figma-aligned syntax token; keyword + builtin distinct.
  5. **Variant dispatcher** — `render` routes by `:variant`.
  6. **Facade delegation** — `browse` / `inspect` / `mini` return
     hiccup whose root data-attribute identifies the new widget."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-xray.views.edn-widget.widget :as w]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]))

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

;; ---- facade delegation --------------------------------------------------
;;
;; Post-rf2-oqa60 the facade returns a Reagent component form 2:
;; `[dd/data-display value opts]` for browse / inspect, and a hiccup
;; vector for mini. The dispatcher returns hiccup. The test surface
;; that mattered (sentinel chrome, type colours, click-to-toggle)
;; lives in `views/data_display_cljs_test.cljs`.

(deftest browse-returns-data-display-mount-form
  (let [out (w/browse {:value     {:a 1 :b 2}
                       :panel-id  :test
                       :render-id "b1"})]
    (testing "browse returns a Reagent component invocation"
      (is (vector? out))
      ;; First element is the data-display fn (a Var).
      (is (fn? (first out)) "first element is a function ref"))))

(deftest inspect-returns-data-display-mount-form
  (let [out (w/inspect :hello "node-key")]
    (is (vector? out))
    (is (fn? (first out)))))

(deftest mini-returns-span-shape
  (let [out (w/mini :hello)]
    (is (= :span (first out)))
    (is (= "rf-xray-data-display-mini" (get (second out) :data-testid)))))

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
    (is (some? (find-by-testid out "rf-xray-edn-widget-code-empty")))))

(deftest code-block-renders-pre-code-shape
  (let [out (w/code-block {:source "(def x 1)"})]
    (testing "outer is [:pre ...]"
      (is (= :pre (first out)))
      (is (= "clojure" (get (second out) :data-lang)))
      (is (= "rf-xray-edn-widget-code"
             (get (second out) :data-testid))))
    (testing "contains [:code ...] child"
      (let [code-node (some #(when (and (vector? %) (= :code (first %))) %)
                            (walk-hiccup out))]
        (is (some? code-node))))))

(deftest code-block-pre-clamps-and-scrolls-within-container
  (let [out   (w/code-block {:source "(reg-event-db :counter/inc (fn [db _] (update db :counter/value inc)))"})
        style (-> out second :style)]
    (testing "the code-block <pre> never exceeds its containing block"
      (is (= "100%" (:max-width style)))
      (is (= "border-box" (:box-sizing style))))
    (testing "long lines scroll within the pre rather than overflowing"
      (is (= "auto" (:overflow-x style))))))

(deftest code-block-keyword-token-uses-syntax-keyword
  (testing "rf2-93jp0 — keyword tokens render with the Figma
            `.syntax-keyword` red family"
    (let [out      (w/code-block {:source ":foo"})
          spans    (walk-hiccup out)
          kw?      (fn [n]
                     (let [c (some-> n second :style :color)]
                       (= c (:syntax-keyword tokens))))
          kw-span  (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (kw? %)) %)
                         spans)]
      (is (some? kw-span)))))

(deftest code-block-string-token-uses-syntax-string
  (testing "rf2-93jp0 — string tokens render on the dedicated
            `:syntax-string` token"
    (let [out      (w/code-block {:source "\"hi\""})
          spans    (walk-hiccup out)
          str?     (fn [n]
                     (let [c (some-> n second :style :color)]
                       (= c (:syntax-string tokens))))
          str-span (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (str? %)) %)
                         spans)]
      (is (some? str-span)))))

(deftest code-block-builtin-and-keyword-render-distinct-colours
  (testing "rf2-93jp0 — `(let [x :foo] x)` paints `let` (builtin) and
            `:foo` (keyword) on DIFFERENT colours"
    (let [out          (w/code-block {:source "(let [x :foo] x)"})
          spans        (walk-hiccup out)
          coloured     (keep (fn [n]
                               (when (vector? n)
                                 (let [[tag attrs & body] n
                                       c (some-> attrs :style :color)
                                       lit (first body)]
                                   (when (and (= :span tag)
                                              (string? lit))
                                     [lit c]))))
                             spans)
          builtin-colour (some (fn [[lit c]] (when (= lit "let") c)) coloured)
          keyword-colour (some (fn [[lit c]] (when (= lit ":foo") c)) coloured)]
      (is (some? builtin-colour))
      (is (some? keyword-colour))
      (is (not= builtin-colour keyword-colour))
      (is (= keyword-colour (:syntax-keyword tokens)))
      (is (= builtin-colour (:accent tokens))))))

;; ---- zprint pre-format ---------------------------------------------------

(deftest format-source-nil-input-returns-input
  (is (nil? (w/format-source nil))))

(deftest format-source-empty-input-returns-input
  (is (= "" (w/format-source ""))))

(deftest format-source-pretty-prints-clojure
  (let [src       "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"
        formatted (w/format-source src)]
    (is (string? formatted))
    (is (re-find #"reg-event-db" formatted))
    (is (re-find #":counter/inc" formatted))
    (is (re-find #"update" formatted))))

(deftest format-source-malformed-input-falls-through
  (let [bad "(reg-event-db :foo "]
    (is (= bad (w/format-source bad)))))

(deftest code-block-pre-formats-via-zprint
  (let [out  (w/code-block {:source "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (is (some? attrs))
    (is (contains? attrs :data-formatted))))

(deftest code-block-non-clojure-lang-skips-format
  (let [out  (w/code-block {:source "function f(){}" :lang :javascript})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (is (= "false" (:data-formatted attrs)))))

;; ---- highlight-clojure-token mapping -------------------------------------

(deftest highlight-clojure-token-mapping
  (is (= :syntax-keyword (w/highlight-clojure-token :keyword)))
  (is (= :syntax-string  (w/highlight-clojure-token :string)))
  (is (= :syntax-number  (w/highlight-clojure-token :number)))
  (is (= :text-tertiary  (w/highlight-clojure-token :comment)))
  (is (= :text-primary   (w/highlight-clojure-token :symbol)))
  (is (= :text-tertiary  (w/highlight-clojure-token :paren)))
  (is (= :accent         (w/highlight-clojure-token :builtin)))
  (is (= :text-primary   (w/highlight-clojure-token :unknown)))
  (is (not= (w/highlight-clojure-token :keyword)
            (w/highlight-clojure-token :builtin))))

(deftest highlight-clojure-token-palette-resolution
  (doseq [tok-type [:keyword :string :number :comment
                    :symbol :paren :builtin]]
    (let [token-kw (w/highlight-clojure-token tok-type)
          resolved (get tokens token-kw)]
      (is (string? resolved))
      (is (and (string? resolved)
               (str/starts-with? resolved "var(--rf-xray-"))))))

;; ---- render dispatcher ---------------------------------------------------

(deftest render-defaults-to-browse
  (let [out (w/render {:value     {:a 1}
                       :panel-id  :test
                       :render-id "dispatch-1"})]
    ;; browse delegates to dd/data-display; the dispatcher returns
    ;; the same shape as `browse` — a [function opts] reagent form.
    (is (vector? out))))

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
