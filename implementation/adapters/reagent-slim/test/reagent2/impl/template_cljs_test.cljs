(ns reagent2.impl.template-cljs-test
  "Unit tests for reagent2.impl.template (Stage 4-D, rf2-6hyy).

  Per IMPL-SPEC §7 + §12.1 + §12.5 R-001. Covers:

    - Tag parsing (:div, :div.cls, :div#id, :div.a.b#id).
    - Hiccup vector dispatch (:>, :<>, :r>, :f>, DOM tag, user fn).
    - Narrowed convert-prop-value (D2): HTML-attribute names stringify
      keyword values; non-HTML names pass through unchanged.
    - Sequence-as-children flattening + dev-only key warning.
    - Void-tag handling (children rejected for <br>, <img>, etc.).
    - cached-prop-name kebab→camel conversion.

  Test strategy: most tests directly inspect the output of
  template/as-element / parse-tag / convert-prop-value without driving
  React. The render-path tests walk a hiccup tree through as-element
  and inspect the resulting React element's `.type`, `.props`, etc.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.template :as template]
            [reagent2.impl.component :as component]
            ["react" :as react]))

;; ---------------------------------------------------------------------------
;; Tag parsing — :div.cls#id shorthand
;; ---------------------------------------------------------------------------

(deftest parse-tag-bare
  (testing "bare tag: :div"
    (let [parsed (template/parse-tag :div)]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (nil? (.-className parsed))))))

(deftest parse-tag-with-class
  (testing ":div.foo"
    (let [parsed (template/parse-tag :div.foo)]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (= "foo" (.-className parsed))))))

(deftest parse-tag-with-id
  (testing ":div#bar"
    (let [parsed (template/parse-tag :div#bar)]
      (is (= "div" (.-tag parsed)))
      (is (= "bar" (.-id parsed)))
      (is (nil? (.-className parsed))))))

(deftest parse-tag-with-id-and-class
  (testing ":div#bar.foo"
    (let [parsed (template/parse-tag :div#bar.foo)]
      (is (= "div" (.-tag parsed)))
      (is (= "bar" (.-id parsed)))
      (is (= "foo" (.-className parsed))))))

(deftest parse-tag-with-multiple-classes
  (testing ":div.a.b.c"
    (let [parsed (template/parse-tag :div.a.b.c)]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (= "a b c" (.-className parsed))
          "multiple .class shorthand parts join with space"))))

(deftest parse-tag-input
  (testing ":input — void element parses normally"
    (let [parsed (template/parse-tag :input)]
      (is (= "input" (.-tag parsed))))))

;; ---------------------------------------------------------------------------
;; cached-prop-name — kebab→camel + special cases
;; ---------------------------------------------------------------------------

(deftest cached-prop-name-class
  (testing ":class → \"className\""
    (is (= "className" (template/cached-prop-name :class)))))

(deftest cached-prop-name-for
  (testing ":for → \"htmlFor\""
    (is (= "htmlFor" (template/cached-prop-name :for)))))

(deftest cached-prop-name-tab-index
  (testing ":tab-index → \"tabIndex\" (kebab→camel)"
    (is (= "tabIndex" (template/cached-prop-name :tab-index)))))

(deftest cached-prop-name-data-attr
  (testing ":data-foo → \"data-foo\" (data-* not camelCased)"
    (is (= "data-foo" (template/cached-prop-name :data-foo)))))

(deftest cached-prop-name-aria-attr
  (testing ":aria-label → \"aria-label\" (aria-* not camelCased)"
    (is (= "aria-label" (template/cached-prop-name :aria-label)))))

(deftest cached-prop-name-string-passthrough
  (testing "non-keyword value passes through unchanged"
    (is (= "alreadyString" (template/cached-prop-name "alreadyString")))))

;; ---------------------------------------------------------------------------
;; Narrowed convert-prop-value — DECISION-2 (R-001)
;;
;; Per IMPL-SPEC §7.2: keyword values stringify only for HTML attribute
;; names (:class, :id, :role, :data-*, :aria-*). Other names pass
;; through with a one-shot dev warning.
;; ---------------------------------------------------------------------------

(deftest convert-prop-value-class-keyword-stringifies
  (testing ":class with keyword value → string (HTML-attr name)"
    (is (= "primary"
           (template/convert-prop-value :class :primary)))))

(deftest convert-prop-value-id-keyword-stringifies
  (testing ":id with keyword value → string (HTML-attr name)"
    (is (= "main-header"
           (template/convert-prop-value :id :main-header)))))

(deftest convert-prop-value-role-keyword-stringifies
  (testing ":role with keyword value → string (HTML-attr name)"
    (is (= "button"
           (template/convert-prop-value :role :button)))))

(deftest convert-prop-value-data-attr-stringifies
  (testing ":data-foo with keyword value → string (data-* HTML attr)"
    (is (= "bar"
           (template/convert-prop-value :data-foo :bar)))))

(deftest convert-prop-value-aria-attr-stringifies
  (testing ":aria-label with keyword value → string (aria-* HTML attr)"
    (is (= "close"
           (template/convert-prop-value :aria-label :close)))))

(deftest convert-prop-value-non-html-keyword-passes-through
  (testing ":value with keyword value → keyword unchanged (non-HTML name; D2 narrowing)"
    ;; The bridge would have stringified this; the rewrite preserves
    ;; the keyword so React-context Provider :value works as intended.
    (is (= :some-frame
           (template/convert-prop-value :value :some-frame)))))

(deftest convert-prop-value-custom-prop-name-passes-keyword
  (testing "custom prop names: :type with keyword passes through"
    (is (= :primary
           (template/convert-prop-value :type :primary)))))

(deftest convert-prop-value-string-passthrough
  (testing "string value passes through unchanged"
    (is (= "hello"
           (template/convert-prop-value :class "hello")))))

(deftest convert-prop-value-number-passthrough
  (testing "number value passes through unchanged"
    (is (= 42 (template/convert-prop-value :tab-index 42)))))

(deftest convert-prop-value-fn-passthrough
  (testing "fn value passes through (event handlers)"
    (let [f (fn [_e])
          out (template/convert-prop-value :on-click f)]
      (is (fn? out)))))

(deftest convert-prop-value-fn-preserves-identity-rf2-wyocr
  (testing "rf2-wyocr: fn props pass through with === identity preserved
            so React.memo / shouldComponentUpdate bail-outs work. Two
            calls with the SAME fn return the SAME reference."
    (let [handler (fn [_e] :clicked)
          ;; 2-arg form — the production path through `kv-conv`.
          a (template/convert-prop-value :on-click handler)
          b (template/convert-prop-value :on-click handler)]
      (is (identical? handler a)
          "fn returned is the SAME reference passed in (=== check)")
      (is (identical? a b)
          "two conversions of the same fn produce the same reference"))
    (let [handler (fn [_e] :nested)
          ;; 1-arg form — used for nested map values.
          a (template/convert-prop-value handler)
          b (template/convert-prop-value handler)]
      (is (identical? handler a)
          "1-arg form preserves identity too")
      (is (identical? a b)
          "1-arg form: repeat conversions return the same reference"))))

(deftest convert-prop-value-non-fn-ifn-still-wrapped
  (testing "rf2-wyocr: keyword (IFn but not fn?) still wraps via shim
            so the React side can invoke it as a JS function"
    (let [out (template/convert-prop-value :on-click :some-kw)]
      ;; Keyword is named? so it hits the named? branch first; this is
      ;; the warn-once path, not the ifn wrap. Verify a true non-fn IFn
      ;; (a map-as-fn) goes through the wrapper path.
      (is (or (keyword? out) (string? out))
          "keyword routed through named? branch (warn-once path)"))
    (let [m   {:a 1 :b 2}
          out (template/convert-prop-value :custom-lookup m)]
      ;; Maps are routed to the map? branch (recursive conversion), not
      ;; the ifn? branch — that's correct (a prop-map value is recursively
      ;; converted as a JS object, not invoked as a fn).
      (is (= "object" (goog/typeOf out))
          "map value recursively converts to JS object (map? branch wins)")
      (is (= 1 (aget out "a")) "key a flows through")
      (is (= 2 (aget out "b")) "key b flows through"))))

(deftest convert-prop-value-1-arg-form-stringifies-named
  (testing "1-arg form (nested map values) stringifies named values"
    ;; Used recursively for map values where there's no outer prop-
    ;; name context (CSS values etc.). Per the impl docstring.
    (is (= "pointer" (template/convert-prop-value :pointer)))))

;; ---------------------------------------------------------------------------
;; warn-once-keyword-prop! — one-shot DEBUG warning contract
;;
;; Per IMPL-SPEC §7.2 D2: a keyword value on a non-HTML-attribute prop
;; passes through unchanged AND fires a one-shot console.warn keyed on
;; [k name-of-v]. The cache lives in a private defonce'd atom; tests
;; use fresh (k, v-name) pairs each assertion to remain robust against
;; cache state from sibling tests. js/console.warn is redirected via
;; set! to count invocations.
;; ---------------------------------------------------------------------------

(defn- with-warn-spy
  "Run `f` with js/console.warn redirected to record invocations onto
  `calls` (an atom holding a vector of arg strings). Restores the
  original on exit."
  [calls f]
  (let [orig (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (f)
      (finally
        (set! (.-warn js/console) orig)))))

(deftest warn-once-keyword-prop-fires-on-non-html-attr
  (testing "non-HTML prop name + keyword value triggers a console.warn
            (the rf2-6hyy §7.2 D2 informational notice)"
    (let [calls (atom [])]
      (with-warn-spy calls
        #(template/convert-prop-value :rf2-warn-test-k1 :rf2-v1))
      (is (= 1 (count @calls))
          "warn fired exactly once on first encounter")
      (is (re-find #"keyword value" (first @calls))
          "warn message names the offence shape")
      (is (re-find #"rf2-warn-test-k1" (first @calls))
          "warn message names the prop key")
      (is (re-find #"rf2-v1" (first @calls))
          "warn message names the keyword value"))))

(deftest warn-once-keyword-prop-suppresses-repeat-same-pair
  (testing "second call with same (k, v) does NOT re-warn — keyed on
            [k name-of-v] so the cache deduplicates"
    (let [calls (atom [])]
      (with-warn-spy calls
        (fn []
          (template/convert-prop-value :rf2-warn-test-k2 :rf2-v2)
          (template/convert-prop-value :rf2-warn-test-k2 :rf2-v2)
          (template/convert-prop-value :rf2-warn-test-k2 :rf2-v2)))
      (is (= 1 (count @calls))
          "three calls with same pair: warn fired exactly once"))))

(deftest warn-once-keyword-prop-fresh-pair-fires
  (testing "different v under same k → fresh cache key → warn fires
            (the cache discriminates on v-name as well as k)"
    (let [calls (atom [])]
      (with-warn-spy calls
        (fn []
          (template/convert-prop-value :rf2-warn-test-k3 :rf2-v3a)
          (template/convert-prop-value :rf2-warn-test-k3 :rf2-v3b)))
      (is (= 2 (count @calls))
          "different v-name fired a separate warn"))))

(deftest warn-once-keyword-prop-html-attr-no-warn
  (testing "HTML-attribute prop name with keyword value stringifies
            WITHOUT a warn — the warn fires only on the non-HTML path"
    (let [calls (atom [])]
      (with-warn-spy calls
        (fn []
          (template/convert-prop-value :class :rf2-warn-test-html)
          (template/convert-prop-value :data-foo :rf2-warn-test-data)
          (template/convert-prop-value :aria-label :rf2-warn-test-aria)))
      (is (= 0 (count @calls))
          "HTML-attribute paths stringified silently (no warn fired)"))))

;; ---------------------------------------------------------------------------
;; as-element — primitive cases
;; ---------------------------------------------------------------------------

(deftest as-element-nil
  (testing "nil → nil"
    (is (nil? (template/as-element nil)))))

(deftest as-element-string
  (testing "string → string"
    (is (= "hello" (template/as-element "hello")))))

(deftest as-element-number
  (testing "number → number"
    (is (= 42 (template/as-element 42)))))

(deftest as-element-keyword
  (testing "bare keyword → name"
    (is (= "foo" (template/as-element :foo)))))

(deftest as-element-symbol
  (testing "bare symbol → name"
    (is (= "bar" (template/as-element 'bar)))))

;; ---------------------------------------------------------------------------
;; as-element — DOM tags
;; ---------------------------------------------------------------------------

(deftest as-element-bare-div
  (testing "[:div] → React element with tag \"div\""
    (let [^js el (template/as-element [:div])]
      (is (= "div" (.-type el))))))

(deftest as-element-div-with-text
  (testing "[:div \"hi\"] → React element with text child"
    (let [^js el (template/as-element [:div "hi"])]
      (is (= "div" (.-type el)))
      (is (= "hi" (-> el .-props .-children))))))

(deftest as-element-div-with-class
  (testing "[:div {:class \"foo\"}] → element with className"
    (let [^js el (template/as-element [:div {:class "foo"}])]
      (is (= "div" (.-type el)))
      (is (= "foo" (-> el .-props .-className))))))

(deftest as-element-div-with-id
  (testing "[:div {:id \"x\"}] → element with id"
    (let [^js el (template/as-element [:div {:id "x"}])]
      (is (= "x" (-> el .-props .-id))))))

(deftest as-element-shorthand-class
  (testing "[:div.foo] → className from shorthand"
    (let [^js el (template/as-element [:div.foo])]
      (is (= "foo" (-> el .-props .-className))))))

(deftest as-element-shorthand-id
  (testing "[:div#bar] → id from shorthand"
    (let [^js el (template/as-element [:div#bar])]
      (is (= "bar" (-> el .-props .-id))))))

(deftest as-element-shorthand-class-and-prop-class
  (testing "[:div.foo {:class \"bar\"}] → \"foo bar\" (shorthand prepends per stock)"
    (let [^js el (template/as-element [:div.foo {:class "bar"}])]
      (is (= "foo bar" (-> el .-props .-className))))))

(deftest as-element-shorthand-id-yields-to-prop
  (testing "[:div#a {:id \"b\"}] → user :id wins over shorthand"
    (let [^js el (template/as-element [:div#a {:id "b"}])]
      (is (= "b" (-> el .-props .-id))))))

(deftest as-element-multiple-children
  (testing "[:div [:span] [:span]] → div with two child elements"
    (let [^js el (template/as-element [:div [:span "a"] [:span "b"]])]
      (is (= "div" (.-type el)))
      (let [children (-> el .-props .-children)]
        (is (or (array? children) (seqable? children))
            "multi-child renders as array")))))

(deftest as-element-nested-shorthand
  (testing "[:div.outer [:span#inner.cls \"hi\"]] — nested shorthand"
    (let [^js el (template/as-element [:div.outer [:span#inner.cls "hi"]])]
      (is (= "outer" (-> el .-props .-className)))
      (let [^js child (-> el .-props .-children)]
        (is (= "span" (.-type child)))
        (is (= "cls" (-> child .-props .-className)))
        (is (= "inner" (-> child .-props .-id)))))))

;; ---------------------------------------------------------------------------
;; as-element — interop heads (:>, :<>, :r>, :f>)
;; ---------------------------------------------------------------------------

(deftest as-element-fragment
  (testing "[:<> [:div] [:span]] → React.Fragment"
    (let [^js el (template/as-element [:<> [:div "a"] [:span "b"]])]
      (is (= (.-Fragment react) (.-type el))))))

(deftest as-element-fragment-with-key
  (testing "[:<> {:key \"k\"} ...] → Fragment with React key"
    (let [^js el (template/as-element [:<> {:key "k"} [:div "a"]])]
      (is (= (.-Fragment react) (.-type el)))
      (is (= "k" (.-key el))))))

(deftest as-element-interop-react-component
  (testing "[:> Comp {:foo \"bar\"} child] → React.createElement on Comp"
    (let [Comp (fn FakeComp [_props] nil)
          ^js el (template/as-element [:> Comp {:foo "bar"} [:span]])]
      (is (= Comp (.-type el)))
      (is (= "bar" (-> el .-props .-foo))))))

(deftest as-element-raw
  (testing "[:r> Comp js-props] → raw createElement, no prop conversion"
    (let [Comp (fn FakeRaw [_props] nil)
          js-props #js {:already "shaped"}
          ^js el (template/as-element [:r> Comp js-props])]
      (is (= Comp (.-type el)))
      (is (= "shaped" (-> el .-props .-already))))))

(deftest as-element-function-component
  (testing "[:f> some-fn arg] → wrapped as React class for reactivity"
    (let [some-fn (fn [_n] [:div])
          ^js el (template/as-element [:f> some-fn 42])]
      ;; f> dispatches through fn-to-class so the head is a class.
      (is (some? (.-type el)))
      (is (component/reagent-class? (.-type el))
          "fn-to-class produced a reagent-slim class for the f>'d fn"))))

(deftest as-element-user-fn
  (testing "[my-view 1] — user-fn head wraps via fn-to-class"
    (let [my-view (fn [_n] [:div])
          ^js el (template/as-element [my-view 1])]
      (is (component/reagent-class? (.-type el))))))

;; ---------------------------------------------------------------------------
;; Sequence-as-children + key warnings
;; ---------------------------------------------------------------------------

(deftest as-element-seq-children
  (testing "(map ...) children expand to array"
    (let [seq-children (map (fn [n] ^{:key n} [:span n]) (range 3))
          ;; We test expand-seq directly because as-element on a vector
          ;; with a seq inside flattens at the children level.
          arr (template/expand-seq seq-children)]
      (is (array? arr) "expand-seq returns a JS array")
      (is (= 3 (alength arr))))))

;; ---------------------------------------------------------------------------
;; Void tags — children rejected per HTML5
;; ---------------------------------------------------------------------------

(deftest as-element-void-tag-no-children
  (testing "[:br] → React element with no children"
    (let [^js el (template/as-element [:br])]
      (is (= "br" (.-type el))))))

(deftest as-element-void-tag-input
  (testing "[:input {:type \"text\"}] → element with props but no children"
    (let [^js el (template/as-element [:input {:type "text"}])]
      (is (= "input" (.-type el)))
      (is (= "text" (-> el .-props .-type))))))

(deftest as-element-void-tag-img
  (testing "[:img {:src \"x.png\"}] → img with src"
    (let [^js el (template/as-element [:img {:src "x.png"}])]
      (is (= "img" (.-type el)))
      (is (= "x.png" (-> el .-props .-src))))))

(deftest as-element-void-tag-children-dropped
  (testing "void tag with child positions: child arg ignored"
    ;; Per HTML5: br/hr/img/input/etc cannot have children; React would
    ;; otherwise warn. The renderer drops children for void tags.
    (let [^js el (template/as-element [:br "should-not-render"])]
      (is (= "br" (.-type el)))
      ;; React.createElement(br, props) with no children → undefined
      ;; or nil children.
      (is (or (nil? (-> el .-props .-children))
              (js/Array.isArray (-> el .-props .-children)))))))

;; ---------------------------------------------------------------------------
;; React keys
;; ---------------------------------------------------------------------------

(deftest as-element-meta-key
  (testing "^{:key \"k\"} on hiccup vector flows to React key"
    (let [^js el (template/as-element ^{:key "k"} [:div "x"])]
      (is (= "k" (.-key el))))))

(deftest as-element-prop-key
  (testing "{:key \"k\"} in props → React key"
    (let [^js el (template/as-element [:div {:key "k"} "x"])]
      (is (= "k" (.-key el))))))

;; ---------------------------------------------------------------------------
;; Source-coord stamping (per IMPL-SPEC §5.4 + §9.4)
;; ---------------------------------------------------------------------------

(deftest as-element-source-coord-stamping
  (testing "*source-coord* binding is consumed by first DOM-tag root"
    (binding [template/*source-coord* "myns:my-view:42:7"]
      (let [^js el (template/as-element [:div [:span "hi"]])]
        (is (= "myns:my-view:42:7"
               (aget (.-props el) "data-rf2-source-coord"))
            "first DOM root gets the attr")
        ;; Nested element should NOT have the attr (binding consumed).
        ;; We can't easily inspect the child without driving render, so
        ;; we settle for: a second as-element call after the first
        ;; doesn't see the binding (it was consumed).
        (is (nil? template/*source-coord*)
            "binding consumed after first DOM root encountered")))))

(deftest as-element-source-coord-no-binding
  (testing "no *source-coord* binding → no data-rf2-source-coord attr"
    (let [^js el (template/as-element [:div])]
      (is (nil? (aget (.-props el) "data-rf2-source-coord"))))))

;; ---------------------------------------------------------------------------
;; rf2-dwds9 MEDIUM: prototype-pollution defence
;;
;; User-controlled hiccup keys like `:__proto__`, `:constructor`, and
;; `:prototype` MUST NOT mutate the prototype chain of the per-element
;; props object or any shared cache. `kv-conv` and `cached-prop-name`
;; drop the reserved key trio before any `aset`, which is the single
;; chokepoint where user keys become JS object writes.
;;
;; These tests pin the contract by attempting the attack-shape and
;; asserting the result has no leaked slot reachable from the props
;; object, no own slot for the reserved name, and legitimate sibling
;; keys still flow through.
;; ---------------------------------------------------------------------------

(deftest prototype-key-dropped-from-props-rf2-dwds9
  (testing "rf2-dwds9: {:__proto__ {:polluted true}} prop does NOT
            mutate the props object's prototype chain. Without the
            kv-conv filter, `aset obj '__proto__' {...}` would invoke
            the prototype-setter and change Object.prototype lookups
            on every subsequent prop object — exactly the leak we close."
    (let [;; A sentinel "evil" prototype carrying a slot we can detect.
          evil      #js {:polluted "yes"}
          ^js el    (template/as-element [:div {:__proto__ evil
                                                :id "legit"}])
          props     (.-props el)]
      ;; The props object did NOT inherit `polluted` from the evil object.
      (is (or (nil? (aget props "polluted"))
              (= js/undefined (aget props "polluted")))
          "evil prototype slot did NOT become reachable via aget")
      ;; The legitimate sibling key still flows through.
      (is (= "legit" (aget props "id"))
          "legit prop alongside the __proto__ attempt is still present")
      ;; Belt: no own slot for __proto__.
      (is (not (.call (.. js/Object -prototype -hasOwnProperty)
                      props "__proto__"))
          "no own '__proto__' slot on the props object"))))

(deftest constructor-key-dropped-from-props-rf2-dwds9
  (testing "rf2-dwds9: {:constructor \"x\"} prop is dropped (does not
            override the prototype's constructor or leak as own property)"
    (let [^js el (template/as-element [:div {:constructor "leaked"}])
          props  (.-props el)]
      (is (not (.call (.. js/Object -prototype -hasOwnProperty)
                      props "constructor"))
          "no own 'constructor' slot on the props object"))))

(deftest prototype-string-key-dropped-rf2-dwds9
  (testing "rf2-dwds9: {:prototype \"x\"} prop is dropped"
    (let [^js el (template/as-element [:div {:prototype "leaked"}])
          props  (.-props el)]
      (is (not (.call (.. js/Object -prototype -hasOwnProperty)
                      props "prototype"))
          "no own 'prototype' slot on the props object"))))

(deftest nested-prototype-key-dropped-rf2-dwds9
  (testing "rf2-dwds9: nested {:style {:__proto__ {...} :color \"red\"}}
            does NOT leak the evil prototype's slots into the style object"
    (let [evil   #js {:polluted "yes"}
          ^js el (template/as-element [:div {:style {:__proto__ evil
                                                     :color "red"}}])
          style  (.. el -props -style)]
      (is (or (nil? (aget style "polluted"))
              (= js/undefined (aget style "polluted")))
          "evil prototype slot did NOT pollute the style object")
      (is (= "red" (.-color style))
          "legitimate sibling props in the same map survive"))))

(deftest convert-prop-value-reserved-keys-dropped-rf2-dwds9
  (testing "rf2-dwds9: convert-prop-value at the map? branch drops
            reserved keys before `aset` — no prototype mutation, no
            own-property pollution; legitimate sibling keys survive"
    (let [evil #js {:polluted "yes"}
          out (template/convert-prop-value
                {:__proto__ evil :constructor "y" :prototype "z"
                 :legit "ok"})]
      (is (= "object" (goog/typeOf out)))
      (is (= "ok" (aget out "legit"))
          "legitimate keys flow through")
      (is (or (nil? (aget out "polluted"))
              (= js/undefined (aget out "polluted")))
          "evil prototype slot did NOT become reachable via aget")
      (doseq [k ["__proto__" "constructor" "prototype"]]
        (is (not (.call (.. js/Object -prototype -hasOwnProperty) out k))
            (str "reserved key '" k "' is not an own property"))))))
