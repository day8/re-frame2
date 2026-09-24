(ns re-frame.ssr-attr-name-react-parity-test
  "The EMITTED-ATTRIBUTE-NAME half of the SSR conversion table,
  pinned against react-dom ITSELF for the mask family.

  WHY THIS FILE EXISTS. `re-frame.ssr.ui-tree` mirrors react-dom in two
  independent halves:

    `standard-names`    author name     -> React prop name
    `dom-attr-aliases`  React prop name -> the DOM attribute name
                                           react-dom/server actually WRITES

  A react-dom release can move BOTH halves at once: React 19.3 carries
  `masktype -> maskType` in the first AND emits `maskType` as `mask-type` in
  the second. A comparison against `possibleStandardNames` alone cannot see
  the second direction, so an upgrade that re-derived only the first half
  would look complete while the emitter kept writing the old spelling, and
  every other lane in the tree would stay green over it.

  Nothing here is a taste question. The SSR output is hydrated by a client
  running the upgraded react-dom, so an emitter one version behind on an
  attribute NAME is a hydration mismatch.

  WHY THE EVIDENCE IS EXTERNAL. The two halves of the table are maintained by
  hand from the same upstream, so a test that checked one against the other
  would be two consumers of one belief agreeing with each other, and would
  pass over exactly the drift above. `react_dom_probe/attr_name_mask_family.cjs`
  renders each mask-family attribute through the INSTALLED react-dom and
  records the name react-dom wrote; this namespace reads those bytes and
  compares them with what `emit-ui-tree` writes. Both halves of the probe are
  React's: the candidate names are react-dom's own `possibleStandardNames`
  values (taking them from our roster would reproduce the blind spot
  verbatim), and the emitted name is read back out of React's markup.

  WHY THE FAMILY AND NOT THE ONE NAME. `maskUnits` and `maskContentUnits` are
  the UNAFFECTED CONTROLS. They sit in the same table, take the same code
  path, and keep their camelCase — so a witness carrying only `maskType`
  could not show that the kebab spelling is confined to it. `mask` itself rides along from
  React's own table.

  WHAT THIS DOES NOT COVER: an emitted-name change outside the mask family,
  in any react-dom release, is invisible to the tree. Closing that means probing the emitted
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
  (testing "the fixture loads, names the react-dom it was measured
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
  (testing "every mask-family attribute serialises to the name the
            INSTALLED react-dom writes, from all three author spellings. A
            comparison of the author-name half of the table alone cannot see
            an emitted-name move"
    (doseq [{:keys [react-prop emitted]} (rows)
            author-form                  (author-forms react-prop)]
      (is (= emitted (emitted-name author-form))
          (str "attribute " react-prop " written as :" author-form
               " — react-dom " (:react-dom-version @react-evidence)
               " emits " emitted)))))

(deftest the-mask-type-correction-is-narrow
  (testing "`maskType` is the only kebab-case emitted name in the family.
            Stated separately from the sweep above because the sweep would
            stay green if an edit moved a control to match a table that had
            drifted on both sides at once"
    (let [by-prop (into {} (map (juxt :react-prop :emitted)) (rows))]
      (is (= "mask-type" (get by-prop "maskType"))
          "react-dom 19.3 emits maskType as mask-type")
      (is (= "maskUnits" (get by-prop "maskUnits"))
          "unaffected control — camelCase preserved")
      (is (= "maskContentUnits" (get by-prop "maskContentUnits"))
          "unaffected control — camelCase preserved")
      (is (= "mask-type" (emitted-name "mask-type"))
          "emitter follows react-dom 19.3 for mask-type")
      (is (= "maskUnits" (emitted-name "mask-units"))
          "emitter leaves the control where react-dom leaves it"))))

(deftest the-panose1-prop-is-written-verbatim
  (testing "react-dom 19.3.0 keys its `panose-1` alias on the
            HYPHENATED name (an identity row, `[\"panose-1\", \"panose-1\"]`)
            and has no `panose1` key, so it writes the prop `panose1` verbatim.
            `dom-attr-aliases` inverts `standard-names` but skips
            `panose-1 -> panose1`, whose inversion `panose1 -> panose-1` would
            write a name react-dom does not write. Stated by hand because the
            mask-family fixture does not reach this name"
    (doseq [author-form ["panose-1" "panose1"]]
      (is (= "panose1" (emitted-name author-form))
          (str "attribute panose1 written as :" author-form)))))
