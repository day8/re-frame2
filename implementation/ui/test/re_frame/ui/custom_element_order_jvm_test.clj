(ns re-frame.ui.custom-element-order-jvm-test
  "Clean-build ORDER DETERMINISM for custom-element property lowering on the
  plain-JVM / SSR path (rf2-vxgfnd.141, dimension 2).

  Property-vs-attribute classification is baked at MACROEXPANSION by reading the
  ambient build's `elements` slice, and macros expand top-to-bottom. Before the
  source harvest, a view expanded BEFORE its tag's `(ui/custom-element …)`
  declaration read an EMPTY slice and lowered a declared property to an HTML
  ATTRIBUTE; the same forms reversed lowered it to a JS PROPERTY. That is the
  order dependence this file pins away: on the plain-JVM path there is no
  `:compile-prepare` hook, so `analyze` harvests this namespace's own literal
  declarations before classifying.

  RED-BEFORE LEVER (this file's own): revert the `harvest/ensure-namespace-
  harvested!` call in `analyze/analyze-element-props` and `below-view` /
  `spread-below-view` classify `:model` as an attribute — `declaration-above-and-
  below-classify-identically` and the markup assertions go red, and NO other
  proof does. The observable asserted is the emitted classification itself
  (`:rf.ui/property-props`, and property omission from SSR markup), never that
  something merely threw."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

;; --- declaration ABOVE its view -------------------------------------------
(ui/custom-element :ce-order-above {:properties #{:model}})
(defview above-view [] [:ce-order-above {:model "m" :data-x "d"}])

;; --- declaration BELOW its view (the defect case) -------------------------
(defview below-view [] [:ce-order-below {:model "m" :data-x "d"}])
(ui/custom-element :ce-order-below {:properties #{:model}})

;; --- a fully-qualified declaration BELOW its view -------------------------
(defview below-fqn-view [] [:ce-order-fqn {:model "m"}])
(re-frame.ui/custom-element :ce-order-fqn {:properties #{:model}})

;; --- an UNDECLARED element stays all-attributes ---------------------------
(defview undeclared-view [] [:ce-order-undeclared {:model "m" :data-x "d"}])

(defn- element [view] (first (:children (tree/render view {}))))
(defn- markup-attrs [view] (:attrs (first (semantic/normalize (tree/render view {})))))

(deftest declaration-above-and-below-classify-identically
  (testing "declaration ABOVE the view classifies :model as a property"
    (is (= #{:model} (:rf.ui/property-props (element above-view)))))
  (testing "declaration BELOW the view classifies :model IDENTICALLY (harvest)"
    (is (= #{:model} (:rf.ui/property-props (element below-view)))))
  (testing "a fully-qualified declaration below the view is found too"
    (is (= #{:model} (:rf.ui/property-props (element below-fqn-view)))))
  (testing "the two orders produce the same classification — the AC"
    (is (= (:rf.ui/property-props (element above-view))
           (:rf.ui/property-props (element below-view)))))
  (testing "the undeclared name is an attribute regardless of harvest"
    (is (nil? (:rf.ui/property-props (element undeclared-view))))))

(deftest declared-property-is-omitted-from-ssr-markup-in-either-order
  ;; The server cannot set a property, so a property-classified name is OMITTED
  ;; from markup (applied at hydration). Whether the declaration sits above or
  ;; below the view, the observable markup must be identical.
  (doseq [[label view] [["above" above-view] ["below" below-view]]]
    (let [attrs (markup-attrs view)]
      (testing (str "declaration " label ": :model omitted, :data-x kept")
        (is (not (contains? attrs "model")) "kebab property name absent from markup")
        (is (not (contains? attrs "modelValue")))
        (is (= "d" (get attrs "data-x")) "the plain attribute survives")))))

(deftest undeclared-element-keeps-every-name-as-markup
  (let [attrs (markup-attrs undeclared-view)]
    (is (= "m" (get attrs "model")) "undeclared -> :model is a plain attribute")
    (is (= "d" (get attrs "data-x")))))
