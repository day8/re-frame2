(ns re-frame.ui.custom-element-property-elision-prod-test
  "rf2-gvcnu — G-7's orthogonal gap: custom-element PROPERTY APPLICATION in the
  ADVANCED production bundle.

  This is NOT one of the host-render `cond` wrapper shapes rf2-55zsd enumerated
  (those select the frame/sub/lease/event ViewCell carriage) — it is orthogonal
  DOM emission: the `attr-pair` `:property` branch in
  `re-frame.ui.compiler.emit_cljs` (\"properties pass through\") lowers a DECLARED
  `:properties` name to its camelCase JS property spelling as a STRING key and
  passes the value through RAW (no `attr-val` coercion). React 19's client
  renderer then assigns a prop as a JS PROPERTY when a matching property exists on
  the custom element instance, else as an attribute.

  The existing advanced custom-element test
  (`custom_element_reload_elision_prod_test`) proves only registration
  bookkeeping / reload-ledger elision / the conflict law — it mounts nothing. The
  mounted DOM property proof (`custom_element_classification_dom_cljs_test`) runs
  in DEV (`goog.DEBUG=true`, non-advanced), where the property KEY has not faced
  Closure `:advanced` renaming. So neither pins the emitter's own invariant:

    Keys remain STRING expressions, so `:advanced` cannot rename the custom
    property spelling (`emit_cljs`, `ordered-literal-object`).

  This namespace runs ONLY in `:browser-test-prod-elision` (`shadow-cljs release`
  ⇒ `:optimizations :advanced`, `goog.DEBUG=false`) and mounts a real
  `react-dom/client` root, so the compiled `accentPayload` key is finally observed
  in the mode it is written for.

  Mutation teeth (each reddens the fixture — the read is `(unchecked-get el
  \"accentPayload\")`, the same literal string the emitter emits):
    - the emitter emitting a NON-string (renameable) property key ⇒ `:advanced`
      renames it ⇒ React never finds `\"accentPayload\"` `in` the element instance,
      falls back to `setAttribute` under the mangled name ⇒ the read is undefined;
    - routing a declared property through `attr-val` ⇒ the object is stringified
      onto an attribute ⇒ the property is never set, `identical?` fails;
    - dropping `:accent-payload` from the `:properties` declaration ⇒ it lowers
      to the VERBATIM kebab attribute name `accent-payload`, which the element
      does not define ⇒ React `setAttribute`s it (`\"[object Object]\"`) ⇒ RED.
      (Verified by mutation: flipping `:properties #{:accent-payload}` to `#{}`,
      rebuilding this advanced bundle, reddens the identity + non-reflection
      assertions.)

  Its DEV positive control is `custom_element_classification_dom_cljs_test`; its
  compile-time emit-name control is `custom-element-property-classification` in
  `react_render_cljs_test` (`helpText`). This file closes the loop on the actual
  DOM node, in the ADVANCED bundle."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; A real custom element whose `accentPayload` PROPERTY is a plain accessor with
;; NO attribute reflection: the getter returns exactly what the setter stored.
;; React 19 assigns a prop as a JS property when a matching property EXISTS on the
;; instance — `key in domElement ? (domElement[key] = value) : setAttribute(...)`
;; (react-dom 19.2.0) — so a DECLARED property lands HERE while an undeclared name
;; (no matching property) falls to an attribute.
;;
;; `accentPayload` is HYPHENATED at the author boundary (`:accent-payload`)
;; precisely so the classification is OBSERVABLE — the camelCase property spelling
;; differs from the verbatim kebab attribute spelling; a single-word name would be
;; indistinguishable.
;;
;; The property name is declared through `Object.defineProperty` with a STRING key,
;; NOT an unquoted class accessor: under `:advanced` Closure would rename an
;; unquoted `accentPayload` accessor, so `"accentPayload" in el` (the exact string
;; the emitter puts in the props object) would then MISS and React would fall to
;; the attribute. A real web component's property name is a stable string; this
;; models that faithfully, so the fixture proves the emitter's key matches a
;; genuinely-named DOM property rather than an artefact of the same build's
;; renaming. Defined once at ns-load and guarded off the non-DOM host.
(when (and (exists? js/customElements)
           (not (js/customElements.get "ce-prop-probe")))
  (let [ctor (js* "(class extends HTMLElement {})")]
    ;; STRING-keyed descriptor (js-obj) so `:advanced` cannot rename the accessor
    ;; name or the descriptor keys — the property is genuinely "accentPayload".
    (js/Object.defineProperty
     (unchecked-get ctor "prototype") "accentPayload"
     (js-obj "configurable" true
             "get" (fn [] (this-as this (unchecked-get this "__ap")))
             "set" (fn [v] (this-as this (unchecked-set this "__ap" v)))))
    (js/customElements.define "ce-prop-probe" ctor)))

(ui/custom-element :ce-prop-probe {:properties #{:accent-payload}})

;; A non-string, non-scalar value referenced as a VAR (so the emit exercises the
;; DYNAMIC `:property` branch — `(= kind :property) value`, the "properties pass
;; through" line — not the literal branch). An object also cannot survive an
;; attribute round-trip intact, so identity-equality is a crisp raw-passthrough
;; proof: neither renamed by `:advanced` nor stringified through `attr-val`.
(def ^:private probe-payload #js {:probe "custom-element-property-passthrough"})

(defview prop-probe-host []
  ;; :accent-payload is DECLARED -> camelCase JS PROPERTY (accentPayload),
  ;;   value passes through RAW;
  ;; :label is NOT declared        -> attribute (the all-attributes default).
  [:ce-prop-probe {:accent-payload probe-payload :label "ride-as-attr"}])

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(deftest declared-property-passes-through-raw-as-a-js-property-in-advanced-production
  (if-not (browser?)
    (is true "custom-element client property-setting needs a DOM host — the browser job runs it")
    (let [container (js/document.createElement "div")
          _         (.appendChild js/document.body container)
          root      (ui/create-root container {:root-id ::prop-root})
          mounted?  (volatile! true)]
      (async done
        (-> (js/Promise.resolve)
            (.then
             (fn []
               (ReactDOM/flushSync #(ui/render! root [prop-probe-host]))
               (let [el (.querySelector container "ce-prop-probe")]
                 (is (some? el) "the custom element mounted")
                 (testing "declared :accent-payload -> camelCase JS PROPERTY, value RAW, in the :advanced bundle"
                   ;; String-keyed reads: the custom property name is not externed,
                   ;; so a dotted `(.-accentPayload el)` would itself be renamed by
                   ;; `:advanced` and miss. `unchecked-get` reads by the literal
                   ;; string the emitter also emitted — the exact interop contract.
                   (is (identical? probe-payload (unchecked-get el "accentPayload"))
                       (str "the declared property landed as the camelCase JS property carrying the "
                            "EXACT object reference — the emitted string key matched `in el`, the "
                            "value was NOT coerced through attr-val"))
                   (is (nil? (.getAttribute el "accent-payload"))
                       "a declared property is NOT reflected as the kebab attribute")
                   (is (nil? (.getAttribute el "accentpayload"))
                       "nor as any attribute spelling — it is a PROPERTY, not markup"))
                 (testing "undeclared names ride as ATTRIBUTES (the all-attributes default)"
                   (is (= "ride-as-attr" (.getAttribute el "label")))
                   (is (nil? (unchecked-get el "label"))
                       "an undeclared name is NOT set as a JS property")))))
            (.catch (fn [e] (is false (str "custom-element property fixture rejected: "
                                           (some-> e ex-message)))))
            (.finally
             (fn []
               (when @mounted?
                 (ReactDOM/flushSync #(ui/unmount! root))
                 (vreset! mounted? false))
               (.remove container)
               (done))))))))
