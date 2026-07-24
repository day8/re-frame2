(ns re-frame.freehand.data-attr-casing-cljs-test
  "rf2-hl7hr — a `data-*` attribute NAME carrying an ASCII uppercase letter is
  REFUSED (Spec 004B §Attribute names, ruled option (c)).

  A mixed-case `data-*` cannot mean what it says on any host surface: HTML
  `setAttribute` ASCII-lowercases the name, `.dataset` excludes any `data-*`
  carrying an uppercase letter, and SSR parsing lowercases in every namespace,
  so a foreign element can diverge between server and client. The substrate
  refuses the name rather than silently lowercasing it — that would be a
  DIFFERENT name whose `.dataset` key is the unsegmented `foobar` — and the
  diagnostic teaches the platform's own lossless mapping (write `:data-foo-bar`,
  read `element.dataset.fooBar`).

  This suite proves the refusal is ONE rule stated once and honoured
  everywhere: the shared classification both walks and the spread deny consult
  (`conv/attr-key-refusal`), the JVM STRUCTURAL walk with no React present, the
  `v/spread` / `v/spread-safe` runtime deny, and the compiled analyzer — all
  giving one verdict for the four spellings `:data-fooBar`, `:x/data-fooBar`,
  `\"data-fooBar\"` and the symbol, and leaving lowercase / correctly-hyphenated
  `data-*` and every `aria-*` untouched. The interpreted-React-emitter and
  browser-probe halves ride the FH-STRUCT-009 rows and the data-* casing probe
  (browser lane)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.tree :as tree]
            #?(:clj [re-frame.freehand.compiler :as compiler])))

(def ^:private four-spellings
  "The one authored name, spelled four ways — all reduce to the emitted name
  `data-fooBar`, so the line-501 emitted-name law gives one verdict for all."
  [:data-fooBar :x/data-fooBar "data-fooBar" 'data-fooBar])

(def ^:private legitimate-names
  "Names the refusal must NOT touch: lowercase and correctly-hyphenated
  `data-*`, every `aria-*` (mixed-case or not), the bare `data`, ordinary and
  qualified props, and a non-nameable key (which must not even throw)."
  [:data-priority :data-foo-bar :data :aria-hidden :aria-labelledBy
   :title :x/title :tab-index 42 [:vec]])

;; ---------------------------------------------------------------------------
;; The predicate + the shared classification
;; ---------------------------------------------------------------------------

(deftest the-casing-refusal-predicate-fires-only-on-mixed-case-data-star
  (testing "conv/data-attr-casing-refusal refuses a lowercase `data-` prefix
            whose remainder carries an ASCII uppercase letter, and nothing
            else — and its sentence teaches the one-keystroke repair."
    (is (some? (conv/data-attr-casing-refusal "data-fooBar")))
    (is (nil?  (conv/data-attr-casing-refusal "data-foo-bar")) "hyphenated is fine")
    (is (nil?  (conv/data-attr-casing-refusal "data-priority")) "lowercase is fine")
    (is (nil?  (conv/data-attr-casing-refusal "data")) "the bare prefix has no remainder")
    (is (nil?  (conv/data-attr-casing-refusal "aria-hidden")) "aria-* is not data-*")
    (let [msg (conv/data-attr-casing-refusal "data-fooBar")]
      (is (str/includes? msg "data-foo-bar")
          "the diagnostic names the lossless hyphenated spelling")
      (is (str/includes? msg "dataset.fooBar")
          "and the .dataset key the platform reads it back as"))))

(deftest the-shared-classification-gives-one-verdict-for-four-spellings
  (testing "conv/attr-key-refusal — the classification both walks and the
            spread deny consult — refuses all four spellings of one authored
            name with the SAME sentence, per the line-501 emitted-name law."
    (let [refusals (map conv/attr-key-refusal four-spellings)]
      (is (every? some? refusals) "every spelling is refused")
      (is (= 1 (count (distinct refusals)))
          "and with one identical sentence — one authored name, one verdict"))))

(deftest legitimate-names-are-untouched
  (testing "The refusal is narrow: lowercase / hyphenated data-*, aria-*, the
            bare data, ordinary and qualified props, and a non-nameable key all
            pass with no refusal and no throw."
    (doseq [k legitimate-names]
      (is (nil? (conv/attr-key-refusal k))
          (str (pr-str k) " is not refused as a data-* casing error")))))

;; ---------------------------------------------------------------------------
;; The JVM structural walk — enforced with no React present (acceptance 2)
;; ---------------------------------------------------------------------------

(defn- structural-error
  "The `:rf.error/id` the structural walk raises building `hiccup`, or ::ok."
  [hiccup]
  (try (tree/render hiccup) ::ok
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest the-structural-walk-refuses-mixed-case-data-star
  (testing "The refusal is a pure string predicate enforced on the STRUCTURAL
            walk — the host-neutral tree builder that touches no React. A
            mixed-case data-* raises; a lowercase-hyphenated one builds."
    (is (= :rf.error/ui-tree-malformed (structural-error [:div {:data-fooBar "x"}]))
        "mixed-case data-* is refused with no React present")
    (is (= ::ok (structural-error [:div {:data-foo-bar "x"}]))
        "and the lossless hyphenated spelling builds")
    (is (= ::ok (structural-error [:div {:data-priority "high"}]))
        "as does an ordinary lowercase data-*")))

;; ---------------------------------------------------------------------------
;; The v/spread and v/spread-safe runtime deny
;; ---------------------------------------------------------------------------

(defn- forward-error
  [thunk]
  (try (thunk) ::ok
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest the-spread-paths-refuse-mixed-case-data-star
  (testing "A runtime-computed map forwarded through v/spread or v/spread-safe
            is judged by exactly the rule a literal attribute map is, so a
            mixed-case data-* is refused there too; a lowercase one folds."
    (is (= :rf.error/ui-tree-malformed
           (forward-error #(node/spread-attrs {:data-fooBar "x"} nil :div nil)))
        "v/spread refuses a mixed-case data-*")
    (is (= ::ok (forward-error #(node/spread-attrs {:data-foo-bar "x"} nil :div nil)))
        "and forwards a lowercase-hyphenated one")
    (is (= :rf.error/ui-tree-malformed
           (forward-error #(node/safe-caller-attrs {:data-fooBar "x"} #{})))
        "v/spread-safe refuses a mixed-case data-* in the guarded caller map")))

;; ---------------------------------------------------------------------------
;; The compiled analyzer — refuses at COMPILE time, in the same words (JVM)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- compile-error
     "The compile diagnostic id a `{:compiled true}` body carrying `attrs` is
      refused with, or ::accepted."
     [attrs]
     (let [body [:div attrs "x"]]
       (try
         (compiler/compile-structural-view
           {:form            (list 'v/defview 'subject [] body)
            :menv            nil
            :ns-sym          're-frame.freehand.data-attr-casing-cljs-test
            :vname           'subject
            :view-id         ::subject
            :params          []
            :body            [body]
            :children-policy :optional})
         ::accepted
         (catch clojure.lang.ExceptionInfo ex
           {:id (:rf.ui.compile/error (ex-data ex)) :msg (ex-message ex)})))))

#?(:clj
   (deftest the-compiled-analyzer-refuses-mixed-case-data-star
     (testing "The compiled analyzer refuses a mixed-case data-* at COMPILE
               time, in the same teaching sentence the interpreted walk uses at
               render, and admits the lowercase spellings."
       (let [d (compile-error {:data-fooBar "x"})]
         (is (map? d) "a compiled mixed-case data-* is refused, not lowered")
         (is (= :rf.ui.compile/rejected-prop-spelling (:id d))
             "under the analyzer's rejected-prop-spelling id")
         (is (str/includes? (:msg d) "data-foo-bar")
             "and the compile diagnostic teaches the same lossless repair"))
       (is (= ::accepted (compile-error {:data-foo-bar "x"}))
           "a lowercase-hyphenated data-* compiles")
       (is (= ::accepted (compile-error {:data-priority "high"}))
           "as does an ordinary lowercase data-*"))))
