(ns re-frame.freehand.crossing-lowering-jvm-test
  "How a crossing is LOWERED — the compile-time half of the cross-mode
  contract, asserted against the emitted form as data.

  Two claims live here, and neither can be made from a render:

  1. **Exactly one emitted boundary.** A statically named internal-view
     head lowers to one call, whichever mode the child was declared in.
     The count is the claim, not the fact that it works: a lowering that
     emitted a boundary *and* an interpreter entry, or that re-entered a
     walk per child, would render correctly and be exactly the hidden
     fallback D010 rules out. A site inside a keyed list still emits one
     boundary and mounts once per row.
  2. **A Freehand descriptor classifies as an INTERNAL view.** The
     analyzer's head rule reads the marker `v/defview` stamps. Read the
     wrong key and every internal head silently becomes a foreign
     component with an empty manifest — a defect that renders fine on the
     JVM and destroys every static claim the compiled tier makes.

  Expansion is driven directly rather than through `defview` forms in
  source, so the emitted form can be walked before anything runs.

  The ClojureScript arm of claim 2 is not here and does not need to be:
  `re-frame.freehand.crossing-views` declares compiled parents that mount
  interpreted children ACROSS namespaces, and it is compiled by the
  ClojureScript build. Were those heads to classify foreign there, the
  grammar would refuse them and the build would fail — and
  `re-frame.freehand.crossings-cljs-test` reads the resulting manifests
  in the ClojureScript runtime, which only exist because the CLJS-host
  analyzer classified those heads as internal views."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiled-views :as compiled]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.tree-views :as views]))

(v/defview an-interpreted-child
  "A statically named interpreted child — the head a compiled parent
  crosses at."
  [{:keys [label]}]
  [:span label])

(v/defview a-compiled-child
  "Its promoted counterpart, so the classification and the marking are
  asserted over both modes rather than one."
  {:compiled true}
  [{:keys [label]}]
  [:span label])

(defn- lower
  "Run the compiled front end over `body`, as `v/defview {:compiled true}`
  does. Returns the lowering map."
  ([body] (lower '[{:keys [items label]}] body))
  ([params body]
   (compiler/compile-structural-view
     {:form            (list 'v/defview 'subject params body)
      :menv            nil
      ;; The consuming namespace is named LITERALLY: `*ns*` at test-run time
      ;; is the runner's, and head resolution happens against the namespace a
      ;; declaration lives in.
      :ns-sym          're-frame.freehand.crossing-lowering-jvm-test
      :vname           'subject
      :view-id         ::subject
      :params          params
      :body            [body]
      :children-policy :optional})))

(defn- boundary-calls
  "Every emitted internal-boundary call in `form`, as data.

  `re-frame.freehand.node/mount` is the ONE call a compiled body emits for
  an internal boundary, so counting it counts boundaries. Walking the form
  rather than grepping a bundle is deliberate — at this point nothing has
  been inlined, minified or renamed."
  [form]
  (let [hits (atom [])]
    (walk/postwalk
      (fn [x]
        (when (and (seq? x) (= 're-frame.freehand.node/mount (first x)))
          (swap! hits conj x))
        x)
      form)
    @hits))

(defn- hiccup-shaped
  "Every vector in `form` that looks like authored markup — a vector whose
  first element is a keyword. A compiled body passes VALUES across a
  boundary, never forms, so one of these inside an emitted mount argument
  would be a form the child was expected to walk.

  Map entries are excluded: they satisfy `vector?` and a keyword-keyed
  entry would look exactly like a two-element Hiccup vector, so without
  the exclusion every emitted props map reads as markup."
  [form]
  (let [hits (atom [])]
    (walk/postwalk
      (fn [x]
        (when (and (vector? x) (not (map-entry? x)) (keyword? (first x)))
          (swap! hits conj x))
        x)
      form)
    @hits))

;; ---------------------------------------------------------------------------
;; Exactly one emitted boundary
;; ---------------------------------------------------------------------------

(deftest the-boundary-oracle-discriminates
  (testing "Before trusting a count, prove the instrument counts. The same
            walk that will report ONE boundary must report none for a body
            that has none, and two for a body with two."
    (is (= 0 (count (boundary-calls (:body (lower '[:p "no crossing here"])))))
        "known-absent: a body with no child boundary emits none")
    (is (= 2 (count (boundary-calls
                      (:body (lower '[:div
                                      [an-interpreted-child {:label "a"}]
                                      [a-compiled-child {:label "b"}]])))))
        "known-present: two lexical crossings emit two boundaries")))

(deftest a-statically-named-interpreted-child-emits-exactly-one-boundary
  (testing "The acceptance count. One lexical crossing, one emitted call,
            naming the child descriptor — and nothing beside it."
    (let [form  (:body (lower '[an-interpreted-child {:label label}]))
          calls (boundary-calls form)]
      (is (= 1 (count calls)) "exactly one emitted interpreted-child boundary")
      (is (= 'an-interpreted-child (second (first calls)))
          "the call names the child descriptor, not a walker or a registry lookup")
      (is (= 1 (count (boundary-calls (:body (lower '[a-compiled-child {:label label}])))))
          "and a compiled child is the same one call — the seam does not ask"))))

(deftest a-crossing-inside-a-keyed-list-is-still-one-emitted-boundary
  (testing "One SITE, many mounts. A lowering that emitted per row would
            put the count in the wrong place — and would be the first
            step toward a per-child interpreter entry."
    (let [form (:body (lower '[:ul (for [i items]
                                     [an-interpreted-child {:key i :label i}])]))]
      (is (= 1 (count (boundary-calls form)))
          "the keyed list site emits one boundary, evaluated once per row"))))

(deftest the-crossing-carries-values-not-forms
  (testing "What crosses is already structural. The child receives built
            child values and a props map, never markup to walk — which is
            what makes the crossing symmetric, and what leaves no argument
            position an interpreter could be needed for."
    (let [form (:body (lower '[an-interpreted-child {:label label}
                               [:i "child markup"]]))
          call (first (boundary-calls form))]
      (is (= 1 (count (boundary-calls form))))
      (is (= ['[:i "child markup"]]
             (hiccup-shaped '(node/mount c [{} [:i "child markup"]])))
          "COUNTERFACTUAL: the oracle finds a markup form when one is there")
      (is (empty? (hiccup-shaped (nth call 2)))
          "no authored markup vector survives into the emitted mount arguments")
      (is (seq (filter #(and (seq? %) (= 're-frame.freehand.node/element (first %)))
                       (tree-seq coll? seq (nth call 2))))
          "the trailing child crossed as a built element value"))))

;; ---------------------------------------------------------------------------
;; A Freehand descriptor is an INTERNAL view
;; ---------------------------------------------------------------------------

(def ^:private clj-env
  (env/make-env {:host :clj :ns-sym 're-frame.freehand.crossing-lowering-jvm-test}))

(deftest a-freehand-descriptor-classifies-as-an-internal-view
  (testing "The head rule reads the marker `v/defview` stamps, in both
            modes and through a namespace alias. Reading the wrong key
            makes every internal head a foreign component with an empty
            manifest — which renders fine and destroys every static claim
            the compiled tier makes."
    (doseq [[note head expected-id expected-lowering]
            [["a same-namespace interpreted view"
              'an-interpreted-child ::an-interpreted-child :interpreted]
             ["a same-namespace compiled view"
              'a-compiled-child ::a-compiled-child :compiled]
             ["an aliased view from another namespace"
              'views/row :re-frame.freehand.tree-views/row :interpreted]
             ["an aliased compiled view from another namespace"
              'compiled/row :re-frame.freehand.compiled-views/row :compiled]
             ["the framework's own markup boundary"
              'v/markup :re-frame.freehand/markup :interpreted]]]
      (let [info (env/classify-head clj-env head)]
        (is (= :view (:kind info)) (str note " — internal, never foreign"))
        (is (= expected-id (:view-id info)) (str note " — its view id"))
        (is (= expected-lowering (:lowering info))
            (str note " — and the mode a crossing into it enters"))))))

(deftest the-classification-still-discriminates
  (testing "An internal-view rule that answered `:view` for everything
            would satisfy the rows above. A plain var is still a foreign
            boundary, and it carries no lowering — a foreign component has
            no Freehand mode to report."
    (let [info (env/classify-head clj-env 'clojure.string/upper-case)]
      (is (= :foreign (:kind info)) "an ordinary var is a foreign boundary")
      (is (nil? (:lowering info)) "and reports no lowering"))
    (is (= #{:interpreted :compiled :unknown} env/lowerings)
        "the lowering roster is closed and named")
    (is (= :unknown (env/view-lowering {:rf.ui/view true}))
        "a view whose declaration the compiler has not seen is honestly unknown")))

(deftest the-analyzed-node-is-an-internal-boundary
  (testing "Classification is only half of it — the analyzed node has to
            BE a view boundary, because that is what the grammar admits
            and what the emitter lowers to one mount. A `:foreign` node
            would be refused outright, which is the loud version of this
            defect; the quiet version is a node that classifies internal
            and lowers as something else."
    (let [ast (ana/analyze clj-env '[an-interpreted-child {:label "x"}])]
      (is (= :view (:op ast)) "the analyzed child is an internal-view boundary")
      (is (= ::an-interpreted-child (:view-id ast))))))

;; ---------------------------------------------------------------------------
;; The manifest, at build time
;; ---------------------------------------------------------------------------

(deftest the-lowering-carries-a-manifest-marking-every-crossing
  (testing "The manifest is produced by the same analysis that produced
            the body, from the same site index — not recomputed by a
            second walk that could disagree with the emitted code."
      (let [{:keys [manifest body]}
            (lower '[:div
                     [an-interpreted-child {:label "a"}]
                     [a-compiled-child {:label "b"}]])]
        (is (= ::subject (:view-id manifest)))
        (is (= :re-frame.freehand/v1 (:grammar manifest)))
        (is (= [{:view-id ::an-interpreted-child :lowering :interpreted :path [0]}
                {:view-id ::a-compiled-child     :lowering :compiled    :path [1]}]
               (mapv #(dissoc % :source-coord) (:crossings manifest)))
            "both crossings, in source order, each marked and separately addressed")
        (is (= (count (:crossings manifest)) (count (boundary-calls body)))
            "one manifest crossing per emitted boundary — the two halves agree"))))

(deftest a-self-recursive-head-is-not-a-crossing-into-another-mode
  (testing "A view mounting itself mounts the declaration being compiled,
            so the mode is settled without resolving a var that need not
            exist yet."
    (let [{:keys [manifest]} (lower '[{:keys [items]}]
                                    '[:ul (for [i items] [subject {:key i}])])]
      (is (= [{:view-id ::subject :lowering :compiled :path [0 :for]}]
             (mapv #(dissoc % :source-coord) (:crossings manifest)))))))
