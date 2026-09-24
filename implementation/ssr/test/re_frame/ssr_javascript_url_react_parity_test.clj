(ns re-frame.ssr-javascript-url-react-parity-test
  "The SSR hiccup emitters block a `javascript:` URL exactly where
  the hydrating react-dom client does, pinned against react-dom ITSELF.

  WHY THIS FILE EXISTS. `render-to-string` and the streaming shell walk paint
  markup that a Reagent-tier client hydrates, and that client paints through
  react-dom. react-dom's `setProp` swaps a `javascript:` URL in `href`, `src`,
  `action`, `formAction` and `xlinkHref`, and in `data` on an `<object>`, for a
  URL that throws. React does not patch an attribute at hydration (Spec 011
  §Hydration-mismatch detection), so an emitter that wrote the value unchanged
  would leave the unblocked URL live on the hydrated page while the client's
  own render blocked it.

  WHY THE EVIDENCE IS EXTERNAL. The emitter carries a copy of react-dom's rule
  (`isJavaScriptProtocol` and the URL it substitutes), and a test written by
  the same hand as that copy would share any mistake in it.
  `react_dom_probe/javascript_url.cjs` renders every row through the INSTALLED
  react-dom and records the attribute value React wrote. It also checks that the
  server and client builds apply one rule, so that value is the one the client
  paints. The fixture carries every spelling of the scheme the rule matches, the
  near-misses it leaves alone, one of the eight hyphenated tags react-dom does
  not treat as custom elements (`font-face`, where it blocks), and the two
  places react-dom does not block (`data` on a `<div>`, `href` on a custom
  element)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; The external evidence.
;; ---------------------------------------------------------------------------

(def ^:private fixture-resource "react_dom_probe/javascript_url.edn")

(def ^:private react-evidence
  (delay
    (if-let [url (io/resource fixture-resource)]
      (edn/read-string (slurp url))
      (throw (ex-info (str "the react-dom evidence fixture is not on the test "
                           "classpath — without it every assertion below "
                           "iterates nothing and passes vacuously")
                      {:resource fixture-resource})))))

(defn- rows [] (:rows @react-evidence))

;; ---------------------------------------------------------------------------
;; What our emitters paint.
;; ---------------------------------------------------------------------------

(defn- kebab
  "\"formAction\" -> \"form-action\"."
  [react-prop]
  (str/lower-case (str/replace react-prop #"([a-z0-9])([A-Z])" "$1-$2")))

(defn- author-keys
  "The three ways an author can name React prop `react-prop` in hiccup: the
  kebab keyword, the camelCase keyword, and the string, which the adapters
  hand React unchanged."
  [react-prop]
  (distinct [(keyword (kebab react-prop)) (keyword react-prop) react-prop]))

(defn- hiccup [{:keys [element parent value]} author-key]
  (let [el [(keyword element) {author-key value}]]
    (if parent [(keyword parent) el] el)))

(defn- decode
  "The value an HTML parser reads out of an escaped attribute value."
  [s]
  (-> s
      (str/replace "&quot;" "\"")
      (str/replace "&#x27;" "'")
      (str/replace "&#39;" "'")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&amp;" "&")))

(defn- painted
  "The value `markup` gives `attribute` on its `<element>`, or nil."
  [markup element attribute]
  (when-let [[_ v] (re-find (re-pattern (str "<" element "[^>]*? "
                                             (java.util.regex.Pattern/quote attribute)
                                             "=\"([^\"]*)\""))
                            markup)]
    (decode v)))

(def ^:private emitters
  {"render-to-string"      rf.ssr.emit/render-to-string
   "the streaming shell walk" (fn [tree] (:shell-html (rf.ssr.streaming/render-shell tree)))})

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest react-evidence-fixture-is-present-and-substantial
  (testing "the fixture loads, names the react-dom it was measured against, and
            both blocks and leaves values alone. A fixture that did only one
            would pass against an emitter with the rule backwards"
    (is (string? (:react-dom-version @react-evidence)))
    (is (= 81 (count (rows))) "9 targets x 9 values")
    (is (= 42 (count (filter :blocked? (rows))))
        "six spellings blocked on each of the seven blocking targets")
    (is (every? (fn [row] (= (:blocked-url @react-evidence) (:painted row)))
                (filter :blocked? (rows))))
    (is (= #{"https" "space-before-colon" "leading-no-break-space"}
           (set (map :label (remove :blocked? (filter #(= "a" (:element %)) (rows))))))
        "the controls on <a href> are exactly the three near-misses")))

(deftest hiccup-emitters-paint-what-react-dom-paints
  (testing "for every row, from every author spelling of the prop,
            both hiccup body emitters paint the attribute value the installed
            react-dom paints: the blocked URL where it blocks, the value
            unchanged where it does not"
    (doseq [[emitter-name emit] emitters
            {:keys [element prop attribute label] :as row} (rows)
            author-key (author-keys prop)]
      (let [markup (emit (hiccup row author-key))]
        (is (= (:painted row) (painted markup element attribute))
            (str emitter-name " diverges from react-dom "
                 (:react-dom-version @react-evidence) " for " label " in <"
                 element " " (pr-str author-key) ">: wrote " (pr-str markup)))))))
