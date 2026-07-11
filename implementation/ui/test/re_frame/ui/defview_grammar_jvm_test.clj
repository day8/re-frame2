(ns re-frame.ui.defview-grammar-jvm-test
  "defview declaration grammar (arities, options map, Q2 header rules,
  the RULED custom-element grammar) + the removed-forms export-surface
  check. Macro-level, so JVM-hosted — the macro code is host-shared, so
  these pins hold for the CLJS expansion path too."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.header :as header]))

(defn- expand-error
  "Macroexpand a defview/custom-element form; nil when it expands, the
  :rf.ui.compile/error id when it throws."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.ui.compile/error (ex-data ex)))
    (catch Exception ex
      ;; macroexpansion wraps in CompilerException in some paths
      (let [c (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo c)
          (:rf.ui.compile/error (ex-data c)))))))

;; ---------------------------------------------------------------------------
;; Arities + options
;; ---------------------------------------------------------------------------

(deftest declaration-arities
  (is (nil? (expand-error '(re-frame.ui/defview v1 [] [:div "x"])))
      "zero-arg view")
  (is (nil? (expand-error '(re-frame.ui/defview v2 "doc" [] [:div "x"])))
      "docstring")
  (is (nil? (expand-error '(re-frame.ui/defview v3 "doc" {:display-name "V"}
                             [{:keys [a]}] [:div a])))
      "docstring + options")
  (is (nil? (expand-error '(re-frame.ui/defview v4 [p] [:div (str p)])))
      "bare symbol argv ≡ {:as p}")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [:div "no argv"]))))
  (is (= :rf.ui.compile/positional-args
         (expand-error '(re-frame.ui/defview v [a b] [:div a])))
      "no positional args — one props map")
  (is (= :rf.ui.compile/multi-form-body
         (expand-error '(re-frame.ui/defview v [] [:div "a"] [:div "b"])))
      "the body is exactly ONE template form")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v ["not-a-binding"] [:div "x"])))
      "the one argument is a map-destructuring form or a symbol"))

(deftest options-map-is-closed
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:memo false} [] [:div])))
      ":memo false was considered and rejected")
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:on-mount [:x/y]} [] [:div])))
      ":on-mount cannot ride mechanical React lifecycle")
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:catch true} [] [:div])))
      "error handling is the explicit ui/error-boundary component")
  (is (= :rf.ui.compile/bad-view-id
         (expand-error '(re-frame.ui/defview v {:id :unqualified} [] [:div])))
      ":id override must be a qualified keyword"))

;; ---------------------------------------------------------------------------
;; Q2 — header rules
;; ---------------------------------------------------------------------------

(deftest header-rules
  (is (= :rf.ui.compile/key-prop-declared
         (expand-error '(re-frame.ui/defview v [{:keys [key]}] [:div key])))
      ":key is reserved — it feeds React's key slot")
  (is (= :rf.ui.compile/key-prop-declared
         (expand-error '(re-frame.ui/defview v {:props [:map [:key :string]]}
                          [] [:div])))
      "…including via the :props schema")
  (is (= :rf.ui.compile/ref-prop-declared-s1
         (expand-error '(re-frame.ui/defview v [{r :ref}] [:div])))
      ":ref forwarding declaration lands S3")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [{:strs [a]}] [:div a])))
      ":strs/:syms are outside the props ABI — slots are keywords")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [{:keys [a] :or {b 1}}] [:div a])))
      ":or keys must match bound slot symbols"))

(deftest q3-slot-encoding-table
  ;; the encode function E — the props-ABI freeze's normative core
  (is (= "product"    (header/slot-name :product)))
  (is (= "cart/item"  (header/slot-name :cart/item)) "namespace preserved")
  (is (= "on-select"  (header/slot-name :on-select)) "no camelization — view props are not DOM props")
  (is (= "a-b?"       (header/slot-name :a-b?)) "punctuation verbatim (quoted JS access)")
  (is (= "class"      (header/slot-name :class)) "no reserved-JS-word mangling")
  (is (= "children"   (header/slot-name :children)) "children is the children slot"))

(deftest q2-declared-slots-and-closure
  (let [hdr (header/parse-header '[{:keys [a b] :cart/keys [item] :as all}])]
    (is (= [:a :b :cart/item] (:slots hdr)))
    (is (= :as (:mode hdr)))
    (is (true? (:children? hdr)) ":as reaches every slot — children included"))
  (is (= [:a :b :c]
         (header/declared-slots (header/parse-header '[{:keys [a b]}])
                                (header/props-schema-keys [:map [:b :int] [:c :int]])))
      "header order first, then schema-only keys")
  (is (nil? (header/props-schema-keys 'SomeSchemaVar))
      "non-literal schemas cannot be introspected — no closed-map enforcement"))

;; ---------------------------------------------------------------------------
;; custom-element (RULED grammar, closed)
;; ---------------------------------------------------------------------------

(deftest custom-element-grammar
  (is (nil? (expand-error '(re-frame.ui/custom-element :x-el {:properties #{:a-b}}))))
  (is (nil? (expand-error '(re-frame.ui/custom-element :x-el {}))))
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :div {:properties #{}})))
      "custom elements are tags containing '-'")
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :x-el {:events #{:changed}})))
      "the options map is CLOSED: {:properties #{...}} is the entire v1 grammar")
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :x-el {:properties [:a]})))
      ":properties is a literal set"))

;; ---------------------------------------------------------------------------
;; Removed forms — the export surface IS the absence check
;; ---------------------------------------------------------------------------

(deftest export-surface-is-exactly-the-blessed-set
  (is (= '#{defview custom-element sub lease raw html raw-fn spread
            ;; S1c (rf2-vxgfnd.3) — root identity + the mount surface
            mount create-root render! hydrate-root unmount! frame-root}
         (set (keys (ns-publics 're-frame.ui))))
      "no reg-view family, no Form-1/2/3 helpers, no h macro, no view
       lookup, no ratom/cursor/reaction — Spec 004 §Removed forms"))

;; ---------------------------------------------------------------------------
;; CLJS emission wiring (order-free pin: the emitter is .cljc, so the
;; exact CLJS expansion is data on this host)
;; ---------------------------------------------------------------------------

(deftest cljs-emission-wires-memo-registration-and-debug-gate
  (let [forms (compiler/defview* '(defview probe [] [:div "x"])
                                 {:ns {:name 'app.probe}} ; cljs env marker
                                 'probe
                                 '([] [:div "x"]))
        text  (pr-str forms)]
    (is (str/includes? text "re-frame.ui.runtime/memo-view")
        "every internal view is memoized — no opt-out")
    (is (str/includes? text "re-frame.ui.runtime/register-view!")
        "defview emits the registrar :view registration")
    (is (str/includes? text "js/goog.DEBUG")
        "the registration/manifest emission is dev-gated (I-12)")
    (let [def-sym (some #(when (and (seq? %) (= 'def (first %))
                                    (= 'probe (second %)))
                           (second %))
                        forms)]
      (is (true? (:rf.ui/view (meta def-sym)))
          "the public var carries the Q5 discrimination meta")
      (is (= :app.probe/probe (:rf.ui/view-id (meta def-sym)))))))

;; ---------------------------------------------------------------------------
;; Build digest
;; ---------------------------------------------------------------------------

(deftest build-digest-has-a-home
  (defview bd-probe [] [:div "bd"])
  (is (str/starts-with? (compiler/current-build-digest) "bd1-"))
  (is (= (compiler/current-build-digest) (compiler/current-build-digest))
      "stable within a build"))
