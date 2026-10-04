(ns day8.re-frame2-xray.views.edn-widget-cljs-test
  "Tests for the Xray EDN widget facade.

  The facade is a thin delegate over `views.edn-inspector`, so this
  file exercises ONLY the surfaces the facade owns end-to-end:

  1. **Code-block tokenizer** — `tokenize-clojure` + `classify-token`
     handle source-text highlighting (CLJS-source rendering, NOT
     CLJS-value rendering — values flow through `views.edn-inspector`).
  2. **Code-block rendering** — `code-block` returns the expected
     `[:pre [:code ...]]` shape with per-token colour spans.
  3. **zprint pre-format** — `format-source` survives nil / empty /
     malformed input and round-trips well-formed Clojure.
  4. **highlight-clojure-token mapping** — every token-type resolves
     to its Figma-aligned syntax token; keyword + builtin distinct.
  5. **Facade delegation** — `inspect` returns a Reagent component
     invocation of `views.edn-inspector`; the codec grades that head in
     `panels/managed_fx_template_cljs_test`."
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

(defn- find-by-testid
  [tree id]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= id (get (second n) :data-testid)))))
       first))

;; ---- code-block tokenizer ------------------------------------------------

(deftest classify-token-kinds
  (are [kind token] (= kind (w/classify-token token))
    :keyword ":foo"
    :keyword ":ns/foo"
    :string  "\"hi\""
    :string  "\"with \\\"quote\\\"\""
    :number  "42"
    :number  "-3.14"
    :comment "; hi"
    :paren   "("
    :paren   "}"
    :builtin "reg-event" ; the CURRENT event registrar (EP-0018)
    :builtin "let"
    :symbol  "my-symbol"
    :symbol  "x")
  (testing "`reg-event-db` / `-fx` / `-ctx` are not re-frame2 API (EP-0018);
            they sit in the highlighter set ONLY so a re-frame v1
            source-text snippet under inspection highlights. The
            highlighter is content-agnostic, so it paints them as
            builtins; this test pins that as intentional v1-source
            rendering, not an endorsement of the spellings as registrars."
    (are [token] (= :builtin (w/classify-token token))
      "reg-event-db"
      "reg-event-fx"
      "reg-event-ctx")))

(deftest tokenize-clojure-roundtrip
  (testing "concatenating tokenized literals reconstructs the source"
    (let [src  "(reg-event :foo (fn [{:keys [db]} [_ x]] {:db (assoc db :y x)}))"
          toks (w/tokenize-clojure src)]
      (is (= src (apply str (map second toks)))))))

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
  (let [out   (w/code-block {:source "(reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :counter/value inc)}))"})
        style (-> out second :style)]
    (testing "the code-block <pre> never exceeds its containing block"
      (is (= "100%" (:max-width style)))
      (is (= "border-box" (:box-sizing style))))
    (testing "long lines scroll within the pre rather than overflowing"
      (is (= "auto" (:overflow-x style))))))

(deftest code-block-builtin-and-keyword-render-distinct-colours
  (testing "`(let [x :foo] x)` paints `let` (builtin) and
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

(deftest format-source-passes-degenerate-input-through
  ;; nil, empty and malformed source all come back exactly as given.
  (are [in] (= in (w/format-source in))
    nil
    ""
    "(reg-event :foo "))

(deftest format-source-pretty-prints-clojure
  ;; A 97-column one-liner, past the 72-column cap, so zprint has to
  ;; break it. An identity `format-source` hands the long line back.
  (let [src       "(reg-event :counter/inc (fn [{:keys [db]} [_ amount]] {:db (update db :counter/value + amount)}))"
        formatted (w/format-source src)
        squash    #(str/replace % #"\s+" " ")]
    (is (string? formatted))
    (is (< 72 (count src)) "precondition: the input overflows the cap")
    (is (every? #(<= (count %) 72) (str/split-lines formatted))
        "every formatted line fits the 72-column cap")
    (is (= (squash src) (squash formatted))
        "only whitespace moved — the tokens are the input's, in order")))

;; ---- multi-line :doc renders as real line breaks ------------------------

;; Single-char building blocks so the escape-edge tests carry ZERO
;; hand-escaping ambiguity. `BS` is one backslash; `NL` is one newline.
(def ^:private BS (str \\))
(def ^:private NL (str \newline))

(deftest unescape-source-newlines-rewrites-only-a-bare-escaped-newline
  ;; A captured source string carrying the escaped two-char `\n` (as
  ;; `pr-str` emits for a multi-line docstring) is rewritten to a REAL
  ;; newline, so the code-block renders multi-line. The result is SOURCE
  ;; TEXT (a string token painted under `white-space: pre`), so a printed
  ;; escaped backslash `\\` (the valid source-text form of one literal
  ;; backslash) is KEPT verbatim: the fn is NOT a general string decoder.
  ;; Building blocks: `BS` = one backslash, `NL` = one newline.
  (are [expected input] (= expected (w/unescape-source-newlines input))
    ;; `line one` `\` `n` `line two` → a real newline between the lines
    (str "line one" NL "line two") (str "line one" BS "n" "line two")
    ;; no escaped newline present → unchanged
    "(def x 1)"                    "(def x 1)"
    ""                             ""
    nil                            nil
    ;; `\` `\` `\` `n`: the escaped backslash is kept, the trailing `\n`
    ;; becomes a newline
    (str BS BS NL)                 (str BS BS BS "n")
    ;; `\` `\` `n`: an escaped backslash then the letter n, so no newline
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
  (testing "the character literal `\\newline`"
    (is (str/includes?
          (rendered-text "(rf/reg-event :lines/join (fn [{:keys [db]} _] {:db (assoc db :text (str/join \\newline (:lines db)))}))")
          (str BS "newline"))))
  (testing "the character literal `\\n`, the letter n"
    (is (str/includes?
          (rendered-text "(rf/reg-event :char/n? (fn [{:keys [db]} [_ c]] {:db (assoc db :n? (= c \\n))}))")
          (str "c " BS "n)"))))
  (testing "after the character literal `\\\"`, which must not open a string"
    (let [text (rendered-text "(rf/reg-event :csv/q? (fn [{:keys [db]} [_ c]] (if (= c \\\") {:db (assoc db :sep \\n)} {:db db, :doc \"a\\nb\"})))")]
      (is (str/includes? text (str ":sep " BS "n)"))
          "a later `\\n` character literal is kept")
      (is (str/includes? text (str "\"a" NL "b\""))
          "and a later string literal still unescapes"))))

(deftest code-block-non-clojure-lang-skips-format
  (let [out  (w/code-block {:source "function f(){}" :lang :javascript})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (is (= "false" (:data-formatted attrs)))))

;; ---- highlight-clojure-token mapping -------------------------------------

(deftest highlight-clojure-token-mapping
  (are [token-type syntax-token] (= syntax-token (w/highlight-clojure-token token-type))
    :keyword :syntax-keyword
    :string  :syntax-string
    :number  :syntax-number
    :comment :text-tertiary
    :symbol  :text-primary
    :paren   :text-tertiary
    :builtin :accent
    :unknown :text-primary))

(deftest highlight-clojure-token-palette-resolution
  (doseq [tok-type [:keyword :string :number :comment
                    :symbol :paren :builtin]]
    (let [token-kw (w/highlight-clojure-token tok-type)
          resolved (get tokens token-kw)]
      (is (and (string? resolved)
               (str/starts-with? resolved "var(--rf-xray-"))))))
