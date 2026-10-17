(ns re-frame.ssr-attr-name-react-parity-test
  "The EMITTED-ATTRIBUTE-NAME half of the SSR conversion table, pinned
  against react-dom ITSELF for the mask family.

  The hiccup emitter converts an author key to the React prop name the
  hydrating Reagent-tier adapter hands React, then maps that prop name
  through `html-helpers/dom-attr-aliases` to the DOM attribute react-dom
  writes. A react-dom release can move that alias (React 19.3 emits
  `maskType` as `mask-type`), and a client one version ahead on an
  attribute NAME is a hydration mismatch. The table is hand-maintained from
  upstream, so the evidence is external:
  `react_dom_probe/attr_name_mask_family.cjs` renders each mask-family
  attribute through the installed react-dom and records the name it wrote.
  `maskUnits` and `maskContentUnits` are the camelCase controls beside
  `maskType`. A move outside the mask family is not covered."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ssr.emit :as rf.ssr.emit]))

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
;; The author keys that reach React's prop name, derived from it.
;;
;; The hydrating adapter converts a keyword name through Reagent's kebab ->
;; camel rule and hands a string key to React unchanged, so three keys
;; converge on the React prop name before the emitted-name lookup: the kebab
;; keyword, the prop name as a keyword, and the prop name as a string. They
;; are derived HERE rather than listed so a new row in the fixture is
;; exercised in all three shapes without this file being edited.
;; ---------------------------------------------------------------------------

(defn- kebab
  "\"maskContentUnits\" -> \"mask-content-units\"."
  [react-prop]
  (str/lower-case (str/replace react-prop #"([a-z0-9])([A-Z])" "$1-$2")))

(defn- author-keys [react-prop]
  (distinct [(keyword (kebab react-prop)) (keyword react-prop) react-prop]))

;; ---------------------------------------------------------------------------
;; What our emitter writes.
;; ---------------------------------------------------------------------------

(defn- emitted-name
  "Render `[:svg [:mask {AUTHOR-KEY SENTINEL}]]` through `render-to-string`
  and read back the attribute name it wrote in front of the sentinel — the
  same way the probe reads it back out of React's."
  [author-key]
  (let [markup (rf.ssr.emit/render-to-string
                 [:svg [:mask {author-key (sentinel)}]] {})]
    (if-let [[_ nm] (re-find (re-pattern (str " ([^\\s=<>\"]+)=\"" (sentinel) "\""))
                             markup)]
      nm
      (throw (ex-info (str "render-to-string wrote no attribute carrying the "
                           "sentinel — the emitter dropped the attribute")
                      {:author-key author-key :markup markup})))))

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest emitted-attribute-name-agrees-with-installed-react-dom
  (testing "every mask-family attribute serialises to the name the
            INSTALLED react-dom writes, from all three author spellings. A
            comparison of the author-name half of the table alone cannot see
            an emitted-name move"
    (doseq [{:keys [react-prop emitted]} (rows)
            author-key                   (author-keys react-prop)]
      (is (= emitted (emitted-name author-key))
          (str "attribute " react-prop " written as " (pr-str author-key)
               " — react-dom " (:react-dom-version @react-evidence)
               " emits " emitted)))))
