(ns re-frame.freehand.custom-element-dom-cljs-test
  "FH-STRUCT-011, the BROWSER half — a declared property is SET on the
  element, and an undeclared name is written as an attribute.

  This is the only place the law's failure mode is observable. Every
  structural row in `custom-element-cljs-test` and every markup row in
  `custom-element-ssr-jvm-test` asserts about a value; the defect
  `v/custom-element` exists to prevent is a component that RENDERS —
  correct tag, correct children, an attribute sitting there in the DOM
  inspector — and does nothing, because the property setter never ran. No
  assertion over a tree can see inert. This one can.

  So the element below is defined with `accentColor` as a plain accessor
  and NO attribute reflection. That is deliberate: it means an attribute
  write leaves the property `undefined` rather than leaving both spellings
  populated, and the negative assertions have something to be negative
  about.

  Rides the browser lane through its `-dom-cljs-test` suffix, and matches
  the node suites' broader regex too — where there is no `customElements`
  to define into, so it says so rather than passing quietly.

  Replaces the donor `re-frame.ui.custom-element-classification-dom-cljs-test`."
  (:require ["react-dom/client" :as rdc]
            ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing]]
            [goog.object :as gobj]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.rules :as rules]))

(def struct-011 (conf/fixture :FH-STRUCT-011))
(def dom-row (:dom struct-011))

;; The declaration under test. A DIFFERENT tag from the structural suite's,
;; because one tag has one property manifest across every source in a realm
;; and two suites declaring `:ce-panel` differently would be the conflict law
;; firing on the test corpus rather than on an application.
(v/custom-element :ce-accent {:properties #{:accent-color}})

(v/defview accent-host
  "`:accent-color` is DECLARED — a property. `:data-x` and `:label` are not,
  so they ride the attribute grammar. `:label` carries markup-significant
  characters on purpose: an attribute is ESCAPED and a property is not, so a
  misclassification would be visible as mangled text even if the name landed."
  [{:keys [props]}]
  [:ce-accent (v/spread props)])

(v/defview accent-host-literal
  "The same claim through a LITERAL props map, so the browser arm covers the
  compile-classified path beside the runtime one."
  [_]
  [:ce-accent {:accent-color "blue" :data-x "d" :label "wide & <deep>"}])

(defn- browser?
  "A real `customElements` registry, not merely a DOM: the whole claim is
  about a property that exists on an element INSTANCE."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))
       (exists? js/customElements)))

(when (and (exists? js/customElements)
           (not (js/customElements.get (:tag dom-row))))
  (js/customElements.define
   (:tag dom-row)
   (js* "(class extends HTMLElement { get accentColor(){ return this.__ac; } set accentColor(v){ this.__ac = v; } })")))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e (js/Promise.reject e))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- assert-classified! [el label]
  (let [{:keys [properties absent-attributes attributes absent-properties]}
        (:expect dom-row)]
    (is (some? el) (str label ": the custom element mounted"))
    (testing "a declared name is SET as a JS property under its camelCase spelling"
      (doseq [[prop expected] properties]
        (is (= expected (gobj/get el prop))
            (str label ": el." prop " carries the authored value"))))
    (testing "and is reflected as NO attribute — it is a property, not markup"
      (doseq [attr absent-attributes]
        (is (nil? (.getAttribute el attr))
            (str label ": " attr " was not written as an attribute"))))
    (testing "an undeclared name rides the attribute grammar, escaping and all"
      (doseq [[attr expected] attributes]
        (is (= expected (.getAttribute el attr))
            (str label ": " attr " is an attribute carrying its value intact"))))
    (testing "and is NOT set as a property"
      (doseq [prop absent-properties]
        (is (nil? (gobj/get el prop))
            (str label ": " prop " was not set as a JS property"))))))

(deftest fh-struct-011-the-declaration-under-test-is-live
  (testing "Per FH-STRUCT-011: the browser rows are evidence only if this
            source's declaration is the one the registry carries. A suite
            asserting a property write for a tag nobody declares would fail
            for the right reason by luck rather than by construction."
    (is (= (:declared-properties dom-row) (rules/custom-element-properties (keyword (:tag dom-row))))
        "the live registry carries the fixture's declared property set")))

(deftest fh-struct-011-a-declared-property-is-set-on-the-mounted-element
  (testing "Per FH-STRUCT-011 (browser): the declared name reaches the element
            as a JS PROPERTY under the ruled kebab -> camelCase spelling,
            while the undeclared names beside it are written as attributes.
            Both paths a value can arrive by are driven — a `v/spread` map
            the compiler never saw, and a literal map it classified at build
            time — because the whole point of the declaration is that they
            agree."
    (if-not (browser?)
      (is true "a real customElements registry is required — the browser job runs it")
      (async done
        (let [[container root] (mount!)]
          (-> (act #(.render root (fr/element [accent-host {:props (:props dom-row)}])))
              (.then (fn [_]
                       (assert-classified! (.querySelector container (:tag dom-row)) "v/spread")
                       (act #(.render root (fr/element [accent-host-literal {}])))))
              (.then (fn [_]
                       (assert-classified! (.querySelector container (:tag dom-row)) "literal")
                       (.unmount root)
                       (.remove container)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " (some-> e ex-message)))
                        (.remove container)
                        (done)))))))))
