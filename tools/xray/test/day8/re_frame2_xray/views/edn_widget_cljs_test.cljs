(ns day8.re-frame2-xray.views.edn-widget-cljs-test
  "The facade's `code-block` source-text pipeline: zprint pre-format, the
  Clojure-mode tokenizer, per-string-token newline unescaping, and the
  token colours. The `inspect` head is graded by
  `views/edn_widget_two_heads_dom_cljs_test`."
  (:require [cljs.test :refer-macros [are deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-xray.views.edn-widget :as w]
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

;; ---- code-block tokenizer ------------------------------------------------

(deftest classify-token-kinds
  ;; The tokenizer hands `classify-token` only symbol-shaped literals, so
  ;; builtin-set membership is the one split it makes for them.
  (are [kind token] (= kind (w/classify-token token))
    :builtin "reg-event"
    :symbol  "my-symbol"))

(deftest tokenize-clojure-roundtrip
  (testing "concatenating tokenized literals reconstructs the source"
    (let [src  "(reg-event :foo (fn [{:keys [db]} [_ x]] {:db (assoc db :y x)}))"
          toks (w/tokenize-clojure src)]
      (is (= src (apply str (map second toks)))))))

;; ---- code-block render ---------------------------------------------------

(deftest code-block-empty-source-renders-placeholder
  (is (= "rf-xray-edn-widget-code-empty"
         (:data-testid (second (w/code-block {:source nil}))))))

(deftest code-block-pre-clamps-and-scrolls-within-container
  ;; Without these a long line widens every flex ancestor past the panel
  ;; edge instead of scrolling inside the block.
  (is (= {:max-width "100%" :box-sizing "border-box" :overflow-x "auto"}
         (-> (w/code-block {:source "(def x 1)"})
             second
             :style
             (select-keys [:max-width :box-sizing :overflow-x])))))

(deftest code-block-builtin-and-keyword-render-distinct-colours
  (let [colour-of (into {}
                        (keep (fn [[tag attrs lit]]
                                (when (and (= :span tag) (string? lit))
                                  [lit (-> attrs :style :color)])))
                        (walk-hiccup (w/code-block {:source "(let [x :foo] x)"})))]
    (is (= {"let" (:accent tokens) ":foo" (:syntax-keyword tokens)}
           (select-keys colour-of ["let" ":foo"])))))

;; ---- zprint pre-format ---------------------------------------------------

(deftest format-source-passes-degenerate-input-through
  ;; Source zprint cannot parse comes back exactly as given.
  (is (= "(reg-event :foo " (w/format-source "(reg-event :foo "))))

(deftest format-source-pretty-prints-clojure
  ;; A 97-column one-liner, past the 72-column cap, so zprint has to
  ;; break it. An identity `format-source` hands the long line back.
  (let [src       "(reg-event :counter/inc (fn [{:keys [db]} [_ amount]] {:db (update db :counter/value + amount)}))"
        formatted (w/format-source src)
        squash    #(str/replace % #"\s+" " ")]
    (is (every? #(<= (count %) 72) (str/split-lines formatted))
        "every formatted line fits the 72-column cap")
    (is (= (squash src) (squash formatted))
        "only whitespace moved — the tokens are the input's, in order")))

;; ---- multi-line :doc renders as real line breaks ------------------------

;; `BS` is one backslash and `NL` one newline, so the escape rows carry no
;; hand-escaping ambiguity.
(def ^:private BS (str \\))
(def ^:private NL (str \newline))

(deftest unescape-source-newlines-rewrites-only-a-bare-escaped-newline
  ;; The result is source text painted under `white-space: pre`, so a
  ;; printed escaped backslash stays verbatim: this is not a string decoder.
  (are [expected input] (= expected (w/unescape-source-newlines input))
    (str "line one" NL "line two") (str "line one" BS "n" "line two")
    "(def x 1)"                    "(def x 1)"
    ;; an escaped backslash, then an escaped newline
    (str BS BS NL)                 (str BS BS BS "n")
    ;; an escaped backslash, then the letter n
    (str BS BS "n")                (str BS BS "n")))

;; ---- backslash-n OUTSIDE a string literal is code -----------------------
;;
;; `pr-str` prints the same two characters outside string literals: a
;; regex literal's pattern source verbatim, and the character literals
;; `\n` (the letter n) and `\newline`. Each source below is the exact
;; JVM `pr-str` of the whole form, the shape the capture macro stores.

(defn- rendered-text
  "The text a `code-block` paints for `src`."
  [src]
  (let [pre (some #(when (and (vector? %) (= :pre (first %))) %)
                  (walk-hiccup (w/code-block {:source src})))]
    (str/join "" (filter string? (flatten pre)))))

(deftest code-block-keeps-a-regex-literal-verbatim
  (let [text (rendered-text "(rf/reg-event :lines/split (fn [{:keys [db]} [_ s]] {:db (assoc db :lines (str/split s #\"\\n\"))}))")]
    (is (str/includes? text (str "#\"" BS "n\""))
        "the regex's `\\n` is the regex escape, not a line break")))

(deftest code-block-keeps-character-literals-verbatim
  (testing "after the character literal `\\\"`, which must not open a string"
    (let [text (rendered-text "(rf/reg-event :csv/q? (fn [{:keys [db]} [_ c]] (if (= c \\\") {:db (assoc db :sep \\n)} {:db db, :doc \"a\\nb\"})))")]
      (is (str/includes? text (str ":sep " BS "n)"))
          "a later `\\n` character literal is kept")
      (is (str/includes? text (str "\"a" NL "b\""))
          "and a later string literal still unescapes"))))
