(ns re-frame.ui.custom-element-spread-parity-jvm-test
  "LITERAL-vs-SPREAD property-manifest parity, order-independent (rf2-vxgfnd.141,
  dimension 2 AC: 'Literal props and dynamic `ui/spread` use the same
  authoritative property manifest').

  A literal prop is classified at COMPILE time (the harvested/analyzed `elements`
  slice); a `ui/spread` prop is classified at RENDER time (the runtime ledger the
  emitted `register-custom-element!` populates). Two manifests, one truth: for a
  declared tag both must classify a name identically — property here, property
  there — so the SSR markup a literal view and a spread view emit for the same
  declared tag agree. The declaration sits BELOW both views, so the literal path
  depends on the source harvest; the parity therefore also witnesses order
  determinism.

  RED-BEFORE LEVER: revert the plain-JVM harvest seam in `analyze` and the
  LITERAL view classifies `:model` as an attribute while the SPREAD view still
  reads the runtime ledger as a property — the two manifests DISAGREE and
  `literal-and-spread-agree-on-the-declared-property` goes red. The observable is
  the emitted `:rf.ui/property-props` and the normalized markup, not a throw."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

;; Views ABOVE their declaration — the literal path relies on the harvest.
(defview literal-view [] [:ce-parity {:model "m" :data-x "d"}])
(defview spread-view  [] [:ce-parity (ui/spread {:model "m" :data-x "d"})])
(ui/custom-element :ce-parity {:properties #{:model}})

(defn- element [view] (first (:children (tree/render view {}))))
(defn- markup [view] (:attrs (first (semantic/normalize (tree/render view {})))))

(deftest literal-and-spread-agree-on-the-declared-property
  (let [literal (:rf.ui/property-props (element literal-view))
        spread  (:rf.ui/property-props (element spread-view))]
    (testing "the literal (compile-time) manifest classifies :model a property"
      (is (= #{:model} literal)))
    (testing "the spread (runtime-ledger) manifest classifies :model a property"
      (is (= #{:model} spread)))
    (testing "the two manifests agree — the parity AC"
      (is (= literal spread)))
    (testing "and both agree with the runtime ledger the register call populated"
      (is (= #{:model} (rules/custom-element-properties :ce-parity))))))

(deftest literal-and-spread-emit-identical-ssr-markup
  ;; The property is omitted from server markup (applied at hydration); the plain
  ;; attribute survives. Literal and spread must produce the SAME markup for the
  ;; same declared tag.
  (let [lit (markup literal-view)
        spr (markup spread-view)]
    (is (not (contains? lit "model")) "literal: :model omitted (property)")
    (is (not (contains? spr "model")) "spread: :model omitted (property)")
    (is (= "d" (get lit "data-x")) "literal: :data-x kept (attribute)")
    (is (= "d" (get spr "data-x")) "spread: :data-x kept (attribute)")
    (is (= (select-keys lit ["data-x" "model"])
           (select-keys spr ["data-x" "model"]))
        "literal and spread emit the same property/attribute split")))
