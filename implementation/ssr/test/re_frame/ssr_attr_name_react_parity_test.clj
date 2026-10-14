(ns re-frame.ssr-attr-name-react-parity-test
  "The EMITTED-ATTRIBUTE-NAME half of the SSR conversion table, pinned
  against react-dom ITSELF for the mask family.

  `re-frame.ssr.ui-tree` maps author name -> React prop name
  (`standard-names`) and React prop name -> the DOM attribute react-dom
  writes (`dom-attr-aliases`). A react-dom release can move both halves at
  once (React 19.3 emits `maskType` as `mask-type`), and a client one
  version ahead on an attribute NAME is a hydration mismatch. Both halves are
  hand-maintained from one upstream, so the evidence is external:
  `react_dom_probe/attr_name_mask_family.cjs` renders each mask-family
  attribute through the installed react-dom and records the name it wrote.
  `maskUnits` and `maskContentUnits` are the camelCase controls beside
  `maskType`. A move outside the mask family is not covered."
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
