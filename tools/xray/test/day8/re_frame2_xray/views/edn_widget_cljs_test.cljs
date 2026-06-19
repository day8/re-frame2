(ns day8.re-frame2-xray.views.edn-widget-cljs-test
  "Tests for the Xray EDN widget facade — post-rf2-oqa60 phase 1.

  After the edn-inspector rebuild the facade is a thin delegate over
  `views.edn-inspector`; tests for the prior cljs-devtools routing /
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
  5. **Facade delegation** — `inspect` returns a Reagent component
     invocation of the new widget."
  (:require [cljs.test :refer-macros [deftest is testing]]
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

;; ---- facade delegation --------------------------------------------------
;;
;; Post-rf2-oqa60 `inspect` returns a Reagent component form 2:
;; `[ei/edn-inspector value opts]`. The test surface that mattered
;; (sentinel chrome, type colours, click-to-toggle) lives in
;; `views/edn_inspector_cljs_test.cljs`.

(deftest inspect-returns-edn-inspector-mount-form
  (let [out (w/inspect :hello "node-key")]
    (is (vector? out))
    (is (fn? (first out)))))

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
  (testing "the CURRENT event registrar `reg-event` (EP-0018) is a builtin"
    (is (= :builtin  (w/classify-token "reg-event"))))
  (is (= :builtin  (w/classify-token "let"))))

(deftest classify-token-retired-registrar-spellings
  (testing "EP-0018 retired `reg-event-db` / `-fx` / `-ctx`; they stay in the
            highlighter set ONLY so a v1 / pre-migration source-text snippet
            under inspection still highlights — they are NOT current API. The
            highlighter is content-agnostic, so it still paints them as
            builtins; this test pins that as the intentional migration-rendering
            behaviour, not an endorsement of the spellings as current registrars."
    (is (= :builtin  (w/classify-token "reg-event-db")))
    (is (= :builtin  (w/classify-token "reg-event-fx")))
    (is (= :builtin  (w/classify-token "reg-event-ctx")))))

(deftest classify-token-symbol
  (is (= :symbol   (w/classify-token "my-symbol")))
  (is (= :symbol   (w/classify-token "x"))))

(deftest tokenize-clojure-roundtrip
  (testing "concatenating tokenized literals reconstructs the source"
    (let [src  "(reg-event :foo (fn [{:keys [db]} [_ x]] {:db (assoc db :y x)}))"
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
  (let [toks (w/tokenize-clojure "(reg-event :foo)")
        blt  (filter #(= :builtin (first %)) toks)]
    (is (= 1 (count blt)))
    (is (= "reg-event" (second (first blt))))))

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
  (let [src       "(reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n inc)}))"
        formatted (w/format-source src)]
    (is (string? formatted))
    (is (re-find #"reg-event" formatted))
    (is (re-find #":counter/inc" formatted))
    (is (re-find #"update" formatted))))

(deftest format-source-malformed-input-falls-through
  (let [bad "(reg-event :foo "]
    (is (= bad (w/format-source bad)))))

;; ---- rf2-iosnp — multi-line :doc renders as real line breaks ------------

;; Single-char building blocks so the escape-edge tests carry ZERO
;; hand-escaping ambiguity. `BS` is one backslash; `NL` is one newline.
(def ^:private BS (str \\))
(def ^:private NL (str \newline))

(deftest unescape-source-newlines-converts-escaped-newline
  (testing "rf2-iosnp — a captured source string carrying the escaped
            two-char `\\n` (as `pr-str` emits for a multi-line docstring)
            is rewritten to a REAL newline so the code-block renders
            multi-line."
    ;; printed input  = `line one` `\` `n` `line two`
    ;; expected output = `line one` + real newline + `line two`
    (is (= (str "line one" NL "line two")
           (w/unescape-source-newlines (str "line one" BS "n" "line two"))))))

(deftest unescape-source-newlines-noop-without-escape
  (testing "no escaped-newline present → string returned unchanged"
    (is (= "(def x 1)" (w/unescape-source-newlines "(def x 1)")))
    (is (= "" (w/unescape-source-newlines "")))
    (is (nil? (w/unescape-source-newlines nil)))))

(deftest unescape-source-newlines-keeps-escaped-backslash
  (testing "rf2-iosnp — the result is still SOURCE TEXT (it is re-fed to
            the tokenizer + painted under `white-space: pre`), so a
            printed escaped backslash `\\\\` (the valid source-text form
            of one literal backslash) is KEPT verbatim — only the `\\n`
            newline escape relaxes to a real line break. The fn is NOT a
            general string decoder. Building blocks: `BS` = one
            backslash, `NL` = one newline."
    ;; printed input  = `\` `\` `\` `n`  (escaped-backslash `\\` then `\n`)
    ;; expected output = `\` `\`         (the `\\` source form) + newline
    (is (= (str BS BS NL)
           (w/unescape-source-newlines (str BS BS BS "n")))
        "escaped-backslash kept as `\\\\`; the trailing \\n restored to a newline")
    ;; printed input  = `\` `\` `n`  (escaped-backslash `\\` then letter n)
    ;; expected output = unchanged   (no newline escape present)
    (is (= (str BS BS "n")
           (w/unescape-source-newlines (str BS BS "n")))
        "no spurious newline when the backslash is escaped")))

(deftest code-block-renders-multiline-doc-as-line-breaks
  (testing "rf2-iosnp — a source string whose `:doc` literal carries the
            escaped `\\n` (the `pr-str` capture shape) renders across
            real lines: the `:pre` block's text contains an actual
            newline and no literal backslash-n."
    (let [src  "(reg-event :foo {:doc \"line one\\nline two\"} (fn [{:keys [db]} _] {:db db}))"
          out  (w/code-block {:source src})
          pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                     (walk-hiccup out))
          text (str/join "" (filter string? (flatten pre)))]
      (is (some? pre) "a :pre block renders")
      (is (str/includes? text "\n")
          "the rendered text carries a REAL newline")
      (is (not (str/includes? text "\\n"))
          "no literal backslash-n survives in the rendered text"))))

(deftest code-block-pre-formats-via-zprint
  (let [out  (w/code-block {:source "(reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n inc)}))"})
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
