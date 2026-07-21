(ns re-frame.ui.custom-element-classification-dom-cljs-test
  "S4-B (rf2-vea1f) — the CLIENT half of the RULED custom-element
  classification (Spec 004 §Template grammar / 004B §Custom elements): a
  DECLARED `:properties` name is applied to a mounted custom element as a JS
  PROPERTY under its kebab -> camelCase spelling, while undeclared names ride
  as ATTRIBUTES (the all-attributes default).

  This is the `props applied at hydration` side the server emitter cannot
  render: the JVM emits attributes only (property-classified names omitted by
  `N`) and the properties apply here, on a real `react-dom/client` mount. The
  structural classification + the `N` markup-omission are proven on the JVM in
  custom_element_classification_jvm_test; the compile-time emit name
  (`helpText`) rides react_render_cljs_test. This file closes the loop on the
  actual DOM node in a real browser."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; A real custom element whose `accentColor` PROPERTY is a plain accessor with
;; NO attribute reflection. React 19's client renderer assigns a prop as a JS
;; property when a matching property exists on the element instance, else as an
;; attribute — so this gives the declared property a home, while undeclared
;; names (no matching property) fall to attributes. Defined once at ns-load and
;; guarded off the non-DOM node host (the browser job runs the assertions).
(when (and (exists? js/customElements)
           (not (js/customElements.get "ce-accent")))
  (js/customElements.define
   "ce-accent"
   (js* "(class extends HTMLElement { get accentColor(){ return this.__ac; } set accentColor(v){ this.__ac = v; } })")))

(ui/custom-element :ce-accent {:properties #{:accent-color}})

(defview accent-host []
  ;; :accent-color is DECLARED  -> property (camelCase accentColor)
  ;; :data-x / :label are NOT   -> attributes (verbatim / all-attributes)
  [:ce-accent {:accent-color "blue" :data-x "d" :label "wide & <deep>"}])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defn- accent-el [root]
  (.querySelector root "ce-accent"))

(deftest declared-property-applies-as-a-js-property-on-the-client
  (if-not (browser?)
    (is true "custom-element client property-setting needs a DOM host — the browser job runs it")
    (async done
      (-> (uit/with-root [root [accent-host]]
            (-> (uit/flush!)
                (.then
                 (fn [_]
                   (let [el (accent-el root)]
                     (is (some? el) "the custom element mounted")
                     (testing "declared :accent-color -> camelCase JS PROPERTY, applied at client render"
                       (is (= "blue" (.-accentColor el))
                           "set as a JS property under the kebab -> camelCase name")
                       (is (nil? (.getAttribute el "accent-color"))
                           "a declared property is NOT reflected as the kebab attribute")
                       (is (nil? (.getAttribute el "accentcolor"))
                           "nor as any attribute spelling — it is a PROPERTY, not markup"))
                     (testing "undeclared names ride as ATTRIBUTES (the all-attributes default)"
                       (is (= "d" (.getAttribute el "data-x")))
                       (is (= "wide & <deep>" (.getAttribute el "label")))
                       (is (nil? (.-label el))
                           "an undeclared name is NOT set as a JS property")))))) )
          (.then (fn [_] (done))
                 (fn [e]
                   (is false (str "mount rejected: " (some-> e ex-message)))
                   (done)))))))
