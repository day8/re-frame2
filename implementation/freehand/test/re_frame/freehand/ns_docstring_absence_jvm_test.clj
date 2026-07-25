(ns re-frame.freehand.ns-docstring-absence-jvm-test
  "A DECLARATION DOES NOT CARRY ITS NAMESPACE'S DOCSTRING INTO EMITTED CODE
  (rf2-9y9uv).

  ## The defect this pins shut

  `v/defview` stamps every declaration with a `:source` map, and that map's
  `:ns` is a QUOTED SYMBOL. A quoted symbol is a compiled constant that
  carries its own METADATA: `cljs.analyzer/analyze-wrap-meta` elides only
  reader and analyzer keys, so anything else on a quoted symbol is emitted
  beside it as a real `cljs.core/with-meta` call over a real map.

  shadow-cljs hangs the whole namespace DOCSTRING off the ns-name symbol —
  `shadow.build.ns-form/parse` merges `{:doc <docstring>}` into it and that
  symbol becomes `cljs.analyzer/*cljs-ns*`, which macroexpansion binds
  `*ns*` to. So `(ns-name *ns*)` inside a macro answers a symbol dragging
  the consuming namespace's entire prose behind it, and a namespace
  declaring N views shipped its docstring N TIMES into `:advanced`.

  It was measured, not suspected: the B5 release entry (three declared
  views) shipped its 1,643-byte namespace docstring three times, and
  `re-frame.freehand`'s own 1,960-byte docstring twice, for 9,136 bytes of
  a 739,938-byte production bundle. Per-VIEW docstrings never shipped —
  they live in var metadata, which ClojureScript drops — so the cost fell
  entirely on the one docstring an author is most likely to write well.

  `re-frame.core-reg-macros` states the same caller contract for every
  `reg-*` macro (rf2-xnym); Freehand simply was not honouring it.

  ## Why the test builds its namespace the way it does

  The subject is created with `(create-ns (with-meta 'fresh.ns {:doc …}))`
  — which is exactly the shape shadow-cljs hands the macro, reproduced
  without a ClojureScript build. A namespace symbol carrying no metadata
  would make every assertion below pass for the wrong reason, so each
  deftest first proves its own subject really is contaminated.

  JVM-only because macroexpansion is a JVM act: this is the compiler's
  OUTPUT under inspection, before any host runs it. The bytes half of the
  claim — that the docstring is absent from a real `:advanced` artefact —
  belongs to a build, not to a test, and is the measurement recorded above."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]))

(def ^:private sentinel
  "Distinctive enough that finding it anywhere in an expansion is proof,
  and long enough that shipping it would be a real cost."
  (str "SENTINEL namespace prose that no declaration has any business "
       "carrying into a production bundle."))

(defn- contaminated-ns
  "A namespace whose NAME SYMBOL carries a `:doc`, the way shadow-cljs
  leaves it. `create-ns` keeps the symbol it was handed, so the name must
  be one this JVM has not seen — an existing namespace would be returned
  unchanged and the subject would be clean."
  []
  (create-ns (with-meta (gensym "re-frame.freehand.ns-docstring-probe-")
               {:doc sentinel})))

(defn- metadatas
  "Every metadata map hanging off any node of `form`, the emitted form
  included. `clojure.walk` visits the collections; symbols and keywords
  are visited as themselves, which is where the contamination rides."
  [form]
  (let [found (atom [])]
    (walk/postwalk (fn [x]
                     (when-let [m (meta x)] (swap! found conj m))
                     x)
                   form)
    @found))

(defn- carries-sentinel?
  [form]
  (boolean (some #(some (fn [v] (and (string? v) (= sentinel v))) (vals %))
                 (metadatas form))))

;; ---------------------------------------------------------------------------
;; The positive control: the finder can SEE the contamination
;; ---------------------------------------------------------------------------

(deftest the-contamination-finder-is-not-vacuous
  (testing "Everything below asserts an ABSENCE, so the finder is proven to
            report a PRESENCE first. Both halves matter: the namespace this
            test builds really does carry the docstring on its name symbol
            (which is the defect's whole mechanism), and a form quoting that
            symbol really does test positive."
    (let [ns-obj (contaminated-ns)
          ns-sym (ns-name ns-obj)]
      (is (= sentinel (:doc (meta ns-sym)))
          "the subject namespace's NAME SYMBOL carries the docstring — this is
           the shadow-cljs shape the defect rode in on, and without it every
           assertion below would pass for the wrong reason")
      (is (carries-sentinel? {:source {:ns (list 'quote ns-sym)}})
          "and a form that quotes that symbol tests POSITIVE — the finder works")
      (is (not (carries-sentinel? {:source {:ns (list 'quote (symbol (str ns-sym)))}}))
          "while the metadata-free spelling tests negative — the finder is not
           merely matching on the symbol's name"))))

;; ---------------------------------------------------------------------------
;; The law
;; ---------------------------------------------------------------------------

(deftest defview-emits-no-namespace-docstring
  (testing "The declaration's `:source` map is where the ns symbol reaches
            emitted code, and it must reach it BARE. This is asserted over
            the whole expansion rather than over the `:source` entry alone:
            the claim is that the declaration ships none of its namespace's
            prose, not that one particular key was cleaned."
    (let [ns-sym   (ns-name (contaminated-ns))
          expanded (v/expand-defview {:line 42 :column 1}
                                     "app/probe.cljs"
                                     ns-sym
                                     'probe
                                     '("The view's OWN docstring, which never shipped."
                                       [_]
                                       [:div.probe "hello"]))]
      (is (not (carries-sentinel? expanded))
          (str "a v/defview expansion carries its namespace's docstring into "
               "emitted code. A quoted symbol carries its metadata into the "
               "bundle, so this is one ns docstring shipped PER DECLARED VIEW "
               "(rf2-9y9uv). Pass the metadata-free ns symbol — "
               "`(symbol (str ns-sym))` — as re-frame.core-reg-macros does "
               "for every reg-* macro (rf2-xnym).")))))

(deftest defview-still-emits-the-source-namespace
  (testing "The removal is of the METADATA, never of the coordinate. A
            declaration that stopped naming its namespace would satisfy the
            law above by deleting the thing it exists to protect."
    (let [ns-sym   (ns-name (contaminated-ns))
          expanded (v/expand-defview {:line 42 :column 1}
                                     "app/probe.cljs"
                                     ns-sym
                                     'probe
                                     '([_] [:div.probe]))
          quoted   (->> (tree-seq coll? seq expanded)
                        (filter #(and (seq? %) (= 'quote (first %))))
                        (map second)
                        (filter symbol?)
                        set)]
      (is (contains? quoted (symbol (str ns-sym)))
          "the `:source` map still names the declaring namespace")
      (is (every? #(nil? (meta %)) quoted)
          "and every symbol the expansion quotes is metadata-free"))))

(deftest custom-element-emits-no-namespace-docstring
  (testing "`v/custom-element` quotes the declaring namespace into its
            emitted `register-custom-element!` call for hot-reload eviction,
            which is the same emission shape and therefore the same defect.
            One declaration form fixed and its sibling left open would be a
            fix that only held while nobody used the other one."
    (let [ns-obj   (contaminated-ns)
          expanded (binding [*ns* ns-obj]
                     (compiler/custom-element* nil nil
                                               (keyword (str (gensym "probe-el-")))
                                               {:properties #{:help-text}}))]
      (is (not (carries-sentinel? expanded))
          (str "a v/custom-element expansion carries its namespace's docstring "
               "into emitted code — see the defview law above (rf2-9y9uv)."))
      (is (some #(and (symbol? %) (= (str (ns-name ns-obj)) (str %)))
                (tree-seq coll? seq expanded))
          "and it still names the declaring namespace — the eviction key"))))
