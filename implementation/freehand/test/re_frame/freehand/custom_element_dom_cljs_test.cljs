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

  So the elements below are defined with their property as a plain accessor
  and NO attribute reflection. That is deliberate: it means an attribute
  write leaves the property `undefined` rather than leaving both spellings
  populated, and the negative assertions have something to be negative
  about.

  The second half of the suite is the `on-*` name family, where the property
  grammar and the handler grammar overlap — `:on-detail` is
  `v/custom-element`'s own documented example. It drives the three browser
  seams that each ranked handler position ahead of the declaration
  separately (rf2-sv2oq), plus an UNDECLARED control on a twin tag backed by
  an identical class, which is what stops the repair from reading as 'every
  `on-*` key is a property'.

  Rides the browser lane through its `-dom-cljs-test` suffix, and matches
  the node suites' broader regex too — where there is no `customElements`
  to define into, so it says so rather than passing quietly.

  Replaces the donor `custom_element_classification_dom_cljs_test`."
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
(def on-row (:dom-on struct-011))

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

;; ---------------------------------------------------------------------------
;; The `on-*` family, mounted
;; ---------------------------------------------------------------------------
;;
;; `on-*` is the handler grammar and `:properties` is the property grammar, and
;; a web component may legitimately name a property `on-detail`. Every browser
;; seam ranked handler position ahead of the declaration — the interpreted
;; walk's own fork, `put-caller!`, `compiled-react/forward-entry!` — so a
;; declared `:on-detail` never reached a property write on any of them
;; (rf2-sv2oq).
;;
;; TWO TAGS, IDENTICAL CLASSES, one difference. `ce-detail` is declared and
;; `ce-detail-loose` is not; both are defined below with the same `onDetail`
;; accessor and no attribute reflection. So a mounted difference between them
;; cannot be attributed to the element, to the value, or to the path — only to
;; the declaration, which is the whole claim.

(v/custom-element :ce-detail {:properties #{:on-detail}})

(v/defview detail-literal
  "The interpreted walk's own props fork — the seam that sent a declared
  `on-*` name to `handler-proxy` instead of to the property write."
  [_]
  [:ce-detail {:on-detail {:payload 1} :data-x "d"}])

(v/defview detail-literal-compiled
  "The BUILD-time classification: a literal declared property is resolved to
  its camelCase name at compile time and never consults the registry at all."
  {:compiled true}
  [_]
  [:ce-detail {:on-detail {:payload 1} :data-x "d"}])

(v/defview detail-spread-compiled
  "`compiled-react/forward-entry!` — a map the compiler never saw, folded
  onto a compiled element. Its own seam, with no structural counterpart."
  {:compiled true}
  [{:keys [props]}]
  [:ce-detail (v/spread props)])

(v/defview detail-safe
  "`react/put-caller!` — the ONE browser fold both front ends reach, and the
  third seam that tested handler position before the declaration."
  [{:keys [caller]}]
  [:ce-detail (v/spread-safe {:data-x "d"} caller)])

(v/defview detail-loose
  "THE CONTROL, on the undeclared twin tag: an undeclared `on-*` name is a
  handler site, and a declarative event vector at one installs no listener
  and writes no prop outside a committed frame. Had the repair lifted the
  `on-*` FAMILY into the property lane, the vector itself would be sitting on
  `el.onDetail`."
  [_]
  [:ce-detail-loose {:on-detail [:detail] :data-x "d"}])

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

;; The declared tag and its undeclared twin, defined from two class expressions
;; with the SAME body — `customElements.define` refuses a constructor it has
;; already registered, so one class cannot back both names. Identical accessor,
;; no attribute reflection: the only difference between the two tags is which
;; of them `v/custom-element` mentions.
(when (exists? js/customElements)
  (doseq [tag [(:declared-tag on-row) (:undeclared-tag on-row)]]
    (when-not (js/customElements.get tag)
      (js/customElements.define
       tag
       (js* "(class extends HTMLElement { get onDetail(){ return this.__od; } set onDetail(v){ this.__od = v; } })")))))

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

(defn- assert-on-declared! [el label expected-value]
  (let [{:keys [property absent-attributes attributes]} (:expect on-row)]
    (is (some? el) (str label ": the custom element mounted"))
    (is (= expected-value (gobj/get el property))
        (str label ": el." property " carries the authored value VERBATIM — the "
             "declaration outranks handler position, so the map reached the "
             "property setter instead of the event grammar"))
    (doseq [attr absent-attributes]
      (is (nil? (.getAttribute el attr))
          (str label ": " attr " was not written as an attribute")))
    (doseq [[attr expected] attributes]
      (is (= expected (.getAttribute el attr))
          (str label ": " attr " is an attribute carrying its value intact")))))

(defn- assert-on-undeclared! [el label]
  (let [{:keys [absent-properties absent-attributes attributes]} (:expect-undeclared on-row)]
    (is (some? el) (str label ": the custom element mounted"))
    (doseq [prop absent-properties]
      (is (nil? (gobj/get el prop))
          (str label ": el." prop " was NOT set — an undeclared `on-*` name is a "
               "handler site, so a declarative event vector installs no listener "
               "and writes no prop. The vector itself sitting here is the "
               "over-reach this row exists to catch")))
    (doseq [attr absent-attributes]
      (is (nil? (.getAttribute el attr))
          (str label ": " attr " was not written as an attribute either")))
    (doseq [[attr expected] attributes]
      (is (= expected (.getAttribute el attr))
          (str label ": " attr " is unaffected")))))

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

(deftest fh-struct-011-the-on-family-declaration-is-live
  (testing "Per FH-STRUCT-011: the `on-*` rows are evidence only if the
            declared tag is declared and its twin is NOT. If both were
            declared the control could not fail, and if neither were the
            positive rows would fail for the wrong reason."
    (is (= (:declared-properties on-row)
           (rules/custom-element-properties (keyword (:declared-tag on-row))))
        "the declared tag carries the fixture's `on-*` property set")
    (is (= #{} (rules/custom-element-properties (keyword (:undeclared-tag on-row))))
        "and the twin tag declares nothing — the all-attributes default")))

(deftest fh-struct-011-a-declared-on-name-is-set-as-a-property-on-the-element
  (testing "Per FH-STRUCT-011 (browser): a DECLARED `on-*` name reaches the
            mounted element as the `onDetail` JS property carrying its value
            verbatim, and is written as no attribute under any spelling. The
            three browser seams that each ranked handler position ahead of the
            declaration are driven separately, because each is its own write —
            the interpreted walk's props fork, the compiled tier's build-time
            literal plus `forward-entry!`'s forwarded map, and
            `put-caller!`'s caller fold — and a repair that reached only one of
            them would leave a declaration meaning property here and event one
            `v/spread` away.

            Then the CONTROL, on the twin tag defined from the same class and
            declared nowhere: nothing is set. That row is the half that keeps
            the repair from becoming 'every `on-*` key is a property'."
    (if-not (browser?)
      (is true "a real customElements registry is required — the browser job runs it")
      (async done
        (let [declared-sel   (:declared-tag on-row)
              undeclared-sel (:undeclared-tag on-row)
              value          (:value (:expect on-row))
              caller-value   (:caller-value (:expect on-row))
              ;; A FRESH root per row, and not one root re-rendered. Three of
              ;; these rows mount the same tag with the same value, so React
              ;; would reconcile onto the element the previous row already
              ;; wrote — and each row would then pass on its predecessor's
              ;; property write rather than on its own. A new element per row
              ;; makes every row prove its own seam.
              step (fn [view label sel expect!]
                     (fn [_]
                       (let [[container root] (mount!)]
                         (-> (act #(.render root (fr/element view)))
                             (.then (fn [_]
                                      (expect! (.querySelector container sel) label)
                                      (.unmount root)
                                      (.remove container)
                                      nil))))))]
          (-> (js/Promise.resolve nil)
              (.then (step [detail-literal {}] "interpreted literal" declared-sel
                           #(assert-on-declared! %1 %2 value)))
              (.then (step [detail-literal-compiled {}] "compiled literal" declared-sel
                           #(assert-on-declared! %1 %2 value)))
              (.then (step [detail-spread-compiled {:props (:props on-row)}]
                           "compiled v/spread" declared-sel
                           #(assert-on-declared! %1 %2 value)))
              (.then (step [detail-safe {:caller (:caller-props on-row)}]
                           "v/spread-safe caller" declared-sel
                           #(assert-on-declared! %1 %2 caller-value)))
              (.then (step [detail-loose {}] "undeclared control" undeclared-sel
                           assert-on-undeclared!))
              ;; The rejection handler sits UPSTREAM of the single trailing
              ;; `done` (rf2-qpns): `done` runs the whole remainder of the run
              ;; synchronously, so a `.catch` after it claims a foreign throw
              ;; as this row's and fires `done` a second time.
              (.catch (fn [e]
                        (is false (str "mount rejected: " (some-> e ex-message)))
                        nil))
              (.then (fn [_] (done)))))))))
