(ns re-frame.ssr-attr-name-react-parity-test
  "rf2-4ale — the EMITTED-ATTRIBUTE-NAME half of the SSR conversion table,
  pinned against react-dom ITSELF for the mask family.

  WHY THIS FILE EXISTS. `re-frame.ssr.ui-tree` mirrors react-dom in two
  independent halves:

    `standard-names`    author name     -> React prop name
    `dom-attr-aliases`  React prop name -> the DOM attribute name
                                           react-dom/server actually WRITES

  PR #9576 upgraded this repo to react-dom 19.3.0 and re-derived the first
  half — `masktype -> maskType` landed there — while the second stayed on its
  19.2.0 mirror. React 19.3 moved BOTH: it also began emitting `maskType` as
  `mask-type`. A comparison against `possibleStandardNames` alone cannot see
  the second direction, so the upgrade looked complete while the emitter kept
  writing the 19.2 spelling, and every lane in the tree stayed green over it.

  Nothing here is a taste question. The SSR output is hydrated by a client
  running the upgraded react-dom, so an emitter one version behind on an
  attribute NAME is a hydration mismatch.

  WHY THE EVIDENCE IS EXTERNAL. The two halves of the table are maintained by
  hand from the same upstream, so a test that checked one against the other
  would be two consumers of one belief agreeing with each other — which is
  precisely how #9576 passed. `react_dom_probe/attr_name_mask_family.cjs`
  renders each mask-family attribute through the INSTALLED react-dom and
  records the name react-dom wrote; this namespace reads those bytes and
  compares them with what `emit-ui-tree` writes. Both halves of the probe are
  React's: the candidate names are react-dom's own `possibleStandardNames`
  values (taking them from our roster would reproduce the blind spot
  verbatim), and the emitted name is read back out of React's markup.

  WHY THE FAMILY AND NOT THE ONE NAME. `maskUnits` and `maskContentUnits` are
  the UNAFFECTED CONTROLS. They sit in the same table, take the same code
  path, and must not move — so a witness carrying only the row that changed
  could not show the correction was narrow. `mask` itself rides along from
  React's own table.

  WHAT THIS DOES NOT COVER, recorded rather than built for: an emitted-name
  change outside the mask family, in this react-dom bump or a later one, is
  still invisible to the tree. Closing that means probing the emitted
  direction for every name, which is a react-dom table clone and a different
  piece of work from this one."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.ui-tree :as rf.ssr.ui-tree]))

;; ---------------------------------------------------------------------------
;; The external evidence.
;; ---------------------------------------------------------------------------

(def ^:private fixture-resource
  "react_dom_probe/attr_name_mask_family.edn")

(def ^:private react-evidence
  (delay
    (if-let [url (io/resource fixture-resource)]
      (edn/read-string (slurp url))
      (throw (ex-info (str "the react-dom evidence fixture is not on the test "
                           "classpath — without it every assertion below "
                           "iterates nothing and passes vacuously")
                      {:resource fixture-resource})))))

(defn- rows [] (:rows @react-evidence))

(defn- sentinel [] (:sentinel @react-evidence))

;; ---------------------------------------------------------------------------
;; The three author spellings, derived from React's prop name.
;;
;; Spec 004B lets an author write a recognised attribute three ways, and all
;; three converge on the React prop name before the emitted-name lookup: the
;; kebab form, the React prop name verbatim, and the hyphen-collapsed form.
;; They are derived HERE rather than listed so a new row in the fixture is
;; exercised in all three shapes without this file being edited.
;; ---------------------------------------------------------------------------

(defn- kebab
  "\"maskContentUnits\" -> \"mask-content-units\"."
  [react-prop]
  (str/lower-case (str/replace react-prop #"([a-z0-9])([A-Z])" "$1-$2")))

(defn- author-forms [react-prop]
  (distinct [(kebab react-prop) react-prop (str/lower-case react-prop)]))

;; ---------------------------------------------------------------------------
;; What our emitter writes.
;; ---------------------------------------------------------------------------

(defn- emitted-name
  "Serialise `<svg><mask AUTHOR-FORM=SENTINEL></mask></svg>` through the
  structural-tree emitter and read back the attribute name it wrote in front
  of the sentinel — the same way the probe reads it back out of React's."
  [author-form]
  (let [markup (rf.ssr.ui-tree/emit-ui-tree
                {:rf.ui/tree-version 1
                 :tag                :svg
                 :attrs              {}
                 :children           [{:tag      :mask
                                       :attrs    {(keyword author-form) (sentinel)}
                                       :children []}]})]
    (if-let [[_ nm] (re-find (re-pattern (str " ([^\\s=<>\"]+)=\"" (sentinel) "\""))
                             markup)]
      nm
      (throw (ex-info (str "emit-ui-tree wrote no attribute carrying the "
                           "sentinel — the emitter dropped the attribute")
                      {:author-form author-form :markup markup})))))

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest react-evidence-fixture-is-present-and-substantial
  (testing "rf2-4ale — the fixture loads, names the react-dom it was measured
            against, and carries the row this witness exists for. A missing
            or empty fixture would make the doseq below iterate nothing and
            report a clean pass, which is the one failure mode a parity
            witness must not have"
    (is (string? (:react-dom-version @react-evidence))
        "the fixture records which react-dom produced it")
    (is (string? (sentinel))
        "the fixture records the sentinel the names were read back around")
    (is (<= 3 (count (rows)))
        "react-dom 19 carries at least mask, maskUnits and maskContentUnits")
    (is (contains? (set (map :react-prop (rows))) "maskType")
        "maskType is the row this witness exists for; without it the whole
         file passes vacuously")
    (is (every? (fn [{:keys [react-prop emitted markup]}]
                  (every? string? [react-prop emitted markup]))
                (rows))
        "every row carries React's prop name, the name React emitted, and the
         markup it was read out of")))

(deftest emitted-attribute-name-agrees-with-installed-react-dom
  (testing "rf2-4ale — every mask-family attribute serialises to the name the
            INSTALLED react-dom writes, from all three author spellings. This
            is the assertion #9576 did not have: it compared the author-name
            half of the table and shipped a 19.2 emitted name"
    (doseq [{:keys [react-prop emitted]} (rows)
            author-form                  (author-forms react-prop)]
      (is (= emitted (emitted-name author-form))
          (str "attribute " react-prop " written as :" author-form
               " — react-dom " (:react-dom-version @react-evidence)
               " emits " emitted)))))

(deftest the-mask-type-correction-is-narrow
  (testing "rf2-4ale — the correction moved `maskType` and nothing else in the
            family. Stated separately from the sweep above because the sweep
            would stay green if a later edit moved a control to match a
            table that had drifted on both sides at once"
    (let [by-prop (into {} (map (juxt :react-prop :emitted)) (rows))]
      (is (= "mask-type" (get by-prop "maskType"))
          "the row that moved in react-dom 19.3")
      (is (= "maskUnits" (get by-prop "maskUnits"))
          "unaffected control — camelCase preserved")
      (is (= "maskContentUnits" (get by-prop "maskContentUnits"))
          "unaffected control — camelCase preserved")
      (is (= "mask-type" (emitted-name "mask-type"))
          "emitter follows react-dom 19.3 for the corrected name")
      (is (= "maskUnits" (emitted-name "mask-units"))
          "emitter leaves the control where react-dom leaves it"))))
