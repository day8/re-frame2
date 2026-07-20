(ns re-frame.ui.custom-element-classification-jvm-test
  "S4-B (rf2-vea1f) — the RULED custom-element property-vs-attribute
  classification, proven on the JVM structural surface (Spec 004
  §Template grammar / 004B §Custom elements).

  A DECLARED `:properties` name is a PROPERTY: it stays in the single
  `:attrs` author map, is named by the `:rf.ui/property-props` reserved
  marker, and lowers to the kebab -> camelCase JS property name. Every
  other name is an ATTRIBUTE. An UNDECLARED element is all-attributes.
  The JVM emitter carries ATTRIBUTES ONLY into markup — property-classified
  names are OMITTED by semantic normalization `N` (the server cannot set
  properties) and applied at hydration on the client (the client half is
  proven in custom_element_classification_dom_cljs_test).

  The closed `{:properties #{…}}` grammar, the macro shape, and the
  cross-source declaration law are proven in compiler_build_jvm_test /
  custom_element_conflict_jvm_test; this file proves the CLASSIFICATION
  behaviour end-to-end through the real `defview` -> tree -> N path,
  including the adversarial cases the S4 contract calls out."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

;; Declared custom elements — distinct tags per source (the cross-source
;; conflict law forbids two SOURCES declaring one tag with different sets).
(ui/custom-element :ce-fancy   {:properties #{:accent-color :help-text}})

;; A declared name that is ALSO a standard HTML attribute spelling: the
;; DECLARATION is the sole classifier, so `:tab-index` is a PROPERTY here,
;; not the `tabindex` attribute it would be on a plain element.
(ui/custom-element :ce-attrish {:properties #{:tab-index}})

(defview declared-props-view []
  [:ce-fancy {:accent-color "blue" :help-text "h" :data-x "d" :label "lab"}])

(defview attr-override-view []
  [:ce-attrish {:tab-index "3" :role "note"}])

;; `:ce-plain` is never declared — the all-attributes default.
(defview undeclared-element-view []
  [:ce-plain {:accent-color "x" :data-y "y"}])

(defn- strip-view-evidence
  "Recursively drop the DEV host-root view-evidence annotation
  (:data-rf2-source-coord / :data-rf-view) the compiler stamps on each view's
  compiler-owned root element (rf2-hac8p). This suite asserts the RULED
  property-vs-attribute classification, not the host-root annotation (own
  coverage: the emit-annotation tests, the parity corpus, test:elision)."
  [node]
  (if (map? node)
    (let [node (if-let [a (:attrs node)]
                 (let [a (dissoc a :data-rf2-source-coord :data-rf-view)]
                   (if (seq a) (assoc node :attrs a) (dissoc node :attrs)))
                 node)]
      (cond-> node
        (:children node) (update :children #(mapv strip-view-evidence %))))
    node))

(defn- element [view] (strip-view-evidence (first (:children (tree/render view {})))))

(deftest declared-name-is-a-property-undeclared-is-an-attribute
  (let [el (element declared-props-view)]
    (is (= :ce-fancy (:tag el)))
    (testing "every author-space key lives in the single :attrs map"
      (is (= {:accent-color "blue" :help-text "h" :data-x "d" :label "lab"}
             (:attrs el))))
    (testing "ONLY the declared names are classified as properties (the marker)"
      (is (= #{:accent-color :help-text} (:rf.ui/property-props el))))
    (testing "undeclared names are attributes — never in the property marker"
      (is (not (contains? (:rf.ui/property-props el) :data-x)))
      (is (not (contains? (:rf.ui/property-props el) :label))))))

(deftest kebab-property-name-lowers-to-camelcase
  ;; The JVM structural tree keeps the AUTHOR kebab key (the marker names
  ;; :accent-color); the RULED kebab -> camelCase JS property NAME is the
  ;; lowered spelling both emitters use (the client sets `el.accentColor`).
  (is (= "accentColor" (rules/custom-element-property-name "accent-color")))
  (is (= "helpText" (rules/custom-element-property-name "help-text")))
  (is (= "tabIndex" (rules/custom-element-property-name "tab-index"))
      "a standard-attribute spelling still camelizes when declared a property"))

(deftest declaration-overrides-default-attribute-classification
  ;; Adversarial: a DECLARED name that is a standard HTML attribute spelling
  ;; is forced to a PROPERTY — the declaration is the sole classifier, never
  ;; an attribute-name heuristic.
  (let [el (element attr-override-view)]
    (is (= #{:tab-index} (:rf.ui/property-props el))
        ":tab-index is a PROPERTY here because it is DECLARED — not the tabindex attribute")
    (is (not (contains? (:rf.ui/property-props el) :role))
        "the undeclared :role stays an attribute alongside the property")))

(deftest undeclared-element-is-all-attributes
  (let [el (element undeclared-element-view)]
    (is (= :ce-plain (:tag el)))
    (is (= {:accent-color "x" :data-y "y"} (:attrs el)))
    (is (nil? (:rf.ui/property-props el))
        "no declaration -> no property classification (all-attributes default)")))

(deftest jvm-markup-is-attributes-only-properties-apply-at-hydration
  ;; `N` (the semantic/serialiser half) OMITS property-classified names from
  ;; markup — the server cannot set properties, so they apply on the client at
  ;; hydration. The plain attributes survive.
  (testing "ce-fancy: declared properties omitted, undeclared attributes kept"
    (let [[node] (semantic/normalize (tree/render declared-props-view {}))]
      (is (= :ce-fancy (:tag node)))
      (is (not (contains? node :rf.ui/property-props))
          "the marker itself never reaches the semantic output")
      (let [attrs (:attrs node)]
        (is (= "d" (get attrs "data-x")) "the plain attribute survives as markup")
        (is (not (contains? attrs "accent-color")) "kebab property name absent from markup")
        (is (not (contains? attrs "accentColor"))  "camelCase property name absent from markup")
        (is (not (contains? attrs "help-text")))
        (is (not (contains? attrs "helpText"))))))
  (testing "ce-attrish: the DECLARED tab-index property is omitted from markup"
    (let [[node] (semantic/normalize (tree/render attr-override-view {}))
          attrs  (:attrs node)]
      (is (not (contains? attrs "tabindex")) "declared -> property -> omitted, not a tabindex attribute")
      (is (not (contains? attrs "tabIndex")))
      (is (contains? attrs "role") "the undeclared :role remains an attribute")))
  (testing "undeclared element: everything is markup (no property omission)"
    (let [[node] (semantic/normalize (tree/render undeclared-element-view {}))
          attrs  (:attrs node)]
      (is (= "x" (get attrs "accent-color")) "undeclared element -> the name is a plain attribute")
      (is (= "y" (get attrs "data-y"))))))
