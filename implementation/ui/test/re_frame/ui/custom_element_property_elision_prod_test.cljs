(ns re-frame.ui.custom-element-property-elision-prod-test
  "rf2-gvcnu — G-7's orthogonal gap: custom-element PROPERTY APPLICATION in the
  ADVANCED production bundle.

  This is NOT one of the host-render `cond` wrapper shapes rf2-55zsd enumerated
  (those select the frame/sub/handle/event ViewCell carriage) — it is orthogonal
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

  Mutation teeth (each reddens the fixture — the reads are `(unchecked-get el
  \"accentPayload\")` and `(unchecked-get el \"accentKind\")`, the same literal
  strings the emitter emits):
    - the emitter emitting a NON-string (renameable) property key ⇒ `:advanced`
      renames it ⇒ React never finds the string key `in` the element instance,
      falls back to `setAttribute` under the mangled name ⇒ the read is undefined;
    - routing a declared property VALUE through `attr-val` ⇒ `attr-val` stringifies
      a keyword/symbol via `name`, so the `accentKind` property carries the STRING
      `\"spilled-ink\"` instead of the keyword `:spilled-ink` ⇒ `keyword?`/`=` RED.
      (An OBJECT value is a NO-OP under attr-val — the object stays identical and
      the `accentPayload` identity check STAYS GREEN — which is exactly why the
      keyword oracle, not the object, supplies these teeth. Reproduced against main:
      the attr-val mutation was false-green until `accentKind` was added.)
    - dropping `:accent-payload` from the `:properties` declaration ⇒ it lowers
      to the VERBATIM kebab attribute name `accent-payload`, which the element
      does not define ⇒ React `setAttribute`s it (`\"[object Object]\"`) ⇒ RED.
      (Verified by mutation: flipping `:properties #{:accent-payload :accent-kind}`
      to drop a name, rebuilding this advanced bundle, reddens that property's
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

;; A real custom element whose declared PROPERTIES are plain accessors with NO
;; attribute reflection: each getter returns exactly what the setter stored.
;; React 19 assigns a prop as a JS property when a matching property EXISTS on the
;; instance — `key in domElement ? (domElement[key] = value) : setAttribute(...)`
;; (react-dom 19.2.0) — so a DECLARED property lands HERE while an undeclared name
;; (no matching property) falls to an attribute.
;;
;; Two accessors, each proving a distinct half of "properties pass through RAW":
;;   accentPayload — carries an OBJECT; identity pins the property KEY (`:advanced`
;;                   rename teeth) and non-reflection, but is a NO-OP under an
;;                   `attr-val` mutation (attr-val leaves objects unchanged), so it
;;                   cannot by itself detect value-coercion;
;;   accentKind    — carries a KEYWORD; `attr-val` DOES stringify keywords (via
;;                   `name`), so this accessor is the oracle that reddens when the
;;                   declared-property VALUE is routed through attr-val.
;;
;; Both names are HYPHENATED at the author boundary (`:accent-payload`,
;; `:accent-kind`) precisely so the classification is OBSERVABLE — the camelCase
;; property spelling differs from the verbatim kebab attribute spelling; a
;; single-word name would be indistinguishable.
;;
;; The property names are declared through `Object.defineProperty` with STRING
;; keys, NOT unquoted class accessors: under `:advanced` Closure would rename an
;; unquoted accessor, so `"accentPayload" in el` (the exact string the emitter puts
;; in the props object) would then MISS and React would fall to the attribute. A
;; real web component's property name is a stable string; this models that
;; faithfully, so the fixture proves the emitter's key matches a genuinely-named
;; DOM property rather than an artefact of the same build's renaming. Defined once
;; at ns-load and guarded off the non-DOM host.
(when (and (exists? js/customElements)
           (not (js/customElements.get "ce-prop-probe")))
  (let [ctor  (js* "(class extends HTMLElement {})")
        proto (unchecked-get ctor "prototype")]
    ;; STRING-keyed descriptors (js-obj) so `:advanced` cannot rename the accessor
    ;; names or the descriptor keys — the properties are genuinely "accentPayload"
    ;; and "accentKind".
    (js/Object.defineProperty
     proto "accentPayload"
     (js-obj "configurable" true
             "get" (fn [] (this-as this (unchecked-get this "__ap")))
             "set" (fn [v] (this-as this (unchecked-set this "__ap" v)))))
    (js/Object.defineProperty
     proto "accentKind"
     (js-obj "configurable" true
             "get" (fn [] (this-as this (unchecked-get this "__ak")))
             "set" (fn [v] (this-as this (unchecked-set this "__ak" v)))))
    (js/customElements.define "ce-prop-probe" ctor)))

(ui/custom-element :ce-prop-probe {:properties #{:accent-payload :accent-kind}})

;; Both values are referenced as VARS (so the emit exercises the DYNAMIC
;; `:property` branch — `(= kind :property) value`, the "properties pass through"
;; line — not the compile-time literal branch, which would fold a literal keyword
;; to a string before it ever reaches the property).
;;
;; probe-payload is a non-string, non-scalar OBJECT: identity pins the property KEY
;;   and non-reflection, but an object is UNCHANGED by attr-val, so it cannot see a
;;   value-coercion mutation.
;; probe-accent-kind is a KEYWORD — the value class attr-val actually rewrites
;;   (`(name :spilled-ink)` -> "spilled-ink"). Routing the declared property through
;;   attr-val would deliver that STRING; raw passthrough delivers the exact keyword.
;;   This is the oracle for acceptance-criterion 1.
(def ^:private probe-payload    #js {:probe "custom-element-property-passthrough"})
(def ^:private probe-accent-kind :spilled-ink)

(defview prop-probe-host []
  ;; :accent-payload / :accent-kind are DECLARED -> camelCase JS PROPERTIES
  ;;   (accentPayload / accentKind), values pass through RAW;
  ;; :label is NOT declared                       -> attribute (the all-attributes default).
  [:ce-prop-probe {:accent-payload probe-payload
                   :accent-kind    probe-accent-kind
                   :label          "ride-as-attr"}])

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
                            "EXACT object reference — the emitted string key matched `in el`"))
                   (is (nil? (.getAttribute el "accent-payload"))
                       "a declared property is NOT reflected as the kebab attribute")
                   (is (nil? (.getAttribute el "accentpayload"))
                       "nor as any attribute spelling — it is a PROPERTY, not markup"))
                 (testing "declared :accent-kind carries a KEYWORD RAW — attr-val coercion is not applied"
                   ;; The RAW-passthrough oracle: attr-val stringifies keywords, so
                   ;; if the declared-property branch routed the value through
                   ;; attr-val the property would hold the STRING "spilled-ink".
                   ;; Raw passthrough delivers the exact keyword object.
                   (let [got (unchecked-get el "accentKind")]
                     (is (keyword? got)
                         (str "the keyword-valued property is a RAW keyword, NOT the string "
                              "attr-val would produce via `name`"))
                     (is (= probe-accent-kind got)
                         "the EXACT keyword reached the property — no attr-val name-coercion"))
                   (is (nil? (.getAttribute el "accent-kind"))
                       "the keyword property is NOT reflected as the kebab attribute"))
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
