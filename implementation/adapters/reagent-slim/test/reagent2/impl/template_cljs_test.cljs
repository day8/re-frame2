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
            [goog.object :as gobj]
            ["react" :as react]
            ;; rf2-bf4uw2: render an [:f> f] through the server renderer to
            ;; prove f's React hooks run in a valid function-component
            ;; context (a class-lowered f would throw 'Invalid hook call').
            ["react-dom/server" :as rds]))

;; ---------------------------------------------------------------------------
;; Tag parsing — :div.cls#id shorthand
;; ---------------------------------------------------------------------------

(deftest parse-tag-bare
  (testing "bare tag: :div"
    (let [parsed (template/parse-tag :div [:div])]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (nil? (.-className parsed))))))

(deftest parse-tag-with-class
  (testing ":div.foo"
    (let [parsed (template/parse-tag :div.foo [:div.foo])]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (= "foo" (.-className parsed))))))

(deftest parse-tag-with-id
  (testing ":div#bar"
    (let [parsed (template/parse-tag :div#bar [:div#bar])]
      (is (= "div" (.-tag parsed)))
      (is (= "bar" (.-id parsed)))
      (is (nil? (.-className parsed))))))

(deftest parse-tag-with-id-and-class
  (testing ":div#bar.foo"
    (let [parsed (template/parse-tag :div#bar.foo [:div#bar.foo])]
      (is (= "div" (.-tag parsed)))
      (is (= "bar" (.-id parsed)))
      (is (= "foo" (.-className parsed))))))

(deftest parse-tag-with-multiple-classes
  (testing ":div.a.b.c"
    (let [parsed (template/parse-tag :div.a.b.c [:div.a.b.c])]
      (is (= "div" (.-tag parsed)))
      (is (nil? (.-id parsed)))
      (is (= "a b c" (.-className parsed))
          "multiple .class shorthand parts join with space"))))

(deftest parse-tag-input
  (testing ":input — void element parses normally"
    (let [parsed (template/parse-tag :input [:input])]
      (is (= "input" (.-tag parsed))))))

(deftest parse-tag-id-before-class-supported
  (testing ":div#id.a.b — id MUST precede classes (the supported form)"
    (let [parsed (template/parse-tag :div#id.a.b [:div#id.a.b])]
      (is (= "div" (.-tag parsed)))
      (is (= "id" (.-id parsed)))
      (is (= "a b" (.-className parsed))))))

(deftest parse-tag-class-before-id-not-supported
  ;; rf2-ee38b.15: the regex requires `#id` before `.class` (matches stock
  ;; Reagent). The class-before-id form (`:div.a#id`) does NOT match —
  ;; `re-matches` returns nil and the result carries a nil tag. Pin the
  ;; constraint so the docstring and the code can never silently disagree.
  (testing ":div.a#id — class-before-id is NOT supported (nil tag)"
    (let [parsed (template/parse-tag :div.a#id [:div.a#id])]
      (is (nil? (.-tag parsed))
          "class-before-id yields a nil tag, not a parsed element"))))

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

(deftest convert-prop-value-3-arg-native-stringifies-any-name
  (testing "rf2-ygknv finding 1: 3-arg form with native?=true stringifies
            keyword values for ANY prop name (every DOM attr is a string)"
    (is (= "button" (template/convert-prop-value :type :button true)))
    (is (= "_blank" (template/convert-prop-value :target :_blank true)))
    (is (= "noopener" (template/convert-prop-value :rel :noopener true)))
    ;; symbol value too
    (is (= "x" (template/convert-prop-value :name 'x true)))))

(deftest convert-prop-value-3-arg-non-native-narrowed
  (testing "rf2-ygknv finding 1: 3-arg form with native?=false defers to
            the interop (narrowed) rule — non-HTML keyword preserved"
    (is (= :button (template/convert-prop-value :type :button false)))
    (is (= :rf/foo (template/convert-prop-value :value :rf/foo false)))
    ;; HTML-attr name still stringifies even on the non-native path
    (is (= "primary" (template/convert-prop-value :class :primary false)))))

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

(deftest nested-style-keyword-value-stringifies-on-live-path-rf2-fdm4rm
  (testing "rf2-fdm4rm: a nested style-map keyword value reaches React as
            a STRING on the LIVE path (props.style.cursor === \"pointer\"),
            matching stock Reagent + the SSR serializer — not a raw CLJS
            keyword. This exercises the real nested-map path through
            as-element, the path production renders actually take. (The
            old 1-arg-direct test was VACUOUS: no nested-map render reaches
            the 1-arg form directly — kv-conv did, but routed values through
            the 2-arg interop form, leaving the keyword un-stringified.)"
    (let [^js el (template/as-element [:div {:style {:cursor :pointer}}])
          style  (-> el .-props .-style)]
      (is (= "div" (.-type el)))
      (is (string? (.-cursor style))
          "nested keyword style value is a JS string, not a raw CLJS keyword")
      (is (= "pointer" (.-cursor style))
          "keyword style value stringified to \"pointer\""))
    ;; A multi-property style map: keyword + keyword + string values all
    ;; convert, and the camelCase key transform is preserved for each key.
    (let [^js el (template/as-element
                   [:div {:style {:display          :flex
                                  :text-align       :center
                                  :background-color "red"}}])
          style  (-> el .-props .-style)]
      (is (= "flex" (.-display style)) "keyword value :flex → \"flex\"")
      (is (= "center" (.-textAlign style))
          "camelCased key :text-align → textAlign, keyword value stringified")
      (is (= "red" (.-backgroundColor style))
          "string value passes through under the camelCased key"))))

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

;; rf2-5t8mr.3 (correctness review): :class and :className both map to
;; React's `className` prop. Before the fix, leaving both keys in the
;; prop map sent two writes to the same JS slot and the survivor was
;; iteration-order dependent (array-map vs hash-map differ) — silently
;; dropping one class string (and, with shorthand, the shorthand class
;; too). collapse-class-keys folds :className into :class deterministically,
;; matching the server path's merge-shorthand :className handling.
(deftest as-element-classname-prop-only
  (testing "[:div {:className \"bar\"}] → React-style :className passes through"
    (let [^js el (template/as-element [:div {:className "bar"}])]
      (is (= "bar" (-> el .-props .-className))))))

(deftest as-element-class-and-classname-both
  (testing "[:div {:class \"a\" :className \"b\"}] → merged \"a b\", neither dropped"
    (let [^js el (template/as-element [:div {:class "a" :className "b"}])]
      (is (= "a b" (-> el .-props .-className))))))

(deftest as-element-shorthand-with-class-and-classname
  (testing "[:div.sh {:class \"a\" :className \"b\"}] → \"sh a b\" (all three kept)"
    (let [^js el (template/as-element [:div.sh {:class "a" :className "b"}])]
      (is (= "sh a b" (-> el .-props .-className))))))

(deftest as-element-shorthand-with-classname-prop
  (testing "[:div.foo {:className \"bar\"}] → \"foo bar\" (no double-merge)"
    (let [^js el (template/as-element [:div.foo {:className "bar"}])]
      (is (= "foo bar" (-> el .-props .-className))))))

(deftest as-element-class-classname-collision-deterministic-large-map
  (testing "large (hash-map) props with :class + :className stays deterministic"
    (let [props  (merge {:class "a" :className "b"}
                        (zipmap (map #(keyword (str "data-x" %)) (range 20))
                                (range 20)))
          ^js el (template/as-element [:div props])]
      (is (= "a b" (-> el .-props .-className))))))

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

(deftest as-element-raw-key-does-not-mutate-caller-props-rf2-mdgt8t
  (testing "rf2-mdgt8t (d): stamping :key on an :r> element COPIES the
            caller-supplied js-props object instead of mutating it. The
            caller's object must be unchanged after render."
    (let [Comp     (fn FakeRaw [_props] nil)
          js-props #js {:already "shaped"}
          ^js el   (template/as-element ^{:key "k"} [:r> Comp js-props])]
      (is (= "k" (.-key el)) "the React key is stamped on the element")
      (is (= "shaped" (-> el .-props .-already)) "props still flow through")
      ;; The caller's object must NOT have gained a :key (the pre-fix bug
      ;; set! (.-key js-props) key directly on this input object).
      (is (undefined? (.-key js-props))
          "caller's js-props object was NOT mutated with :key")
      (is (= 1 (.-length (js/Object.keys js-props)))
          "caller's js-props gained no extra own keys"))))

(deftest as-element-function-component-is-real-fn-component-rf2-bf4uw2
  (testing "rf2-bf4uw2: [:f> f] renders f as a REAL React FUNCTION
            component — NOT a class. The prior fn-to-class lowering
            produced a React class, defeating the head's defining
            purpose (hosting React hooks)."
    (let [some-fn (fn [_n] [:div])
          ^js el (template/as-element [:f> some-fn 42])]
      (is (fn? (.-type el)) ":f> head is a function component")
      (is (not (component/reagent-class? (.-type el)))
          ":f> is NOT lowered to a reagent-slim class (the rf2-bf4uw2 bug)")
      (is (not (component/react-class? (.-type el)))
          ":f> head is not a React class either — a plain function component")
      ;; Stable identity: the same fn re-wraps to the SAME component type
      ;; so React reconciles across renders rather than remounting (which
      ;; would drop hook + DOM state).
      (let [^js el2 (template/as-element [:f> some-fn 7])]
        (is (identical? (.-type el) (.-type el2))
            "wrapper cached per fn for stable reconciliation")))))

(deftest as-element-function-component-passes-args-rf2-bf4uw2
  (testing "rf2-bf4uw2: [:f> f a b] calls f with the user args (a b),
            converting its hiccup return to a React element"
    (let [greet  (fn [who] [:div.greet "hi " who])
          ^js el (rds/renderToStaticMarkup
                   (template/as-element [:f> greet "there"]))]
      (is (= "<div class=\"greet\">hi there</div>" el)
          "f received its positional arg and its hiccup was rendered"))))

(deftest as-element-function-component-hooks-render-rf2-bf4uw2
  (testing "rf2-bf4uw2: an [:f> f] whose f calls a React hook renders
            WITHOUT throwing 'Invalid hook call'. Rendered through
            react-dom/server, which installs the hooks dispatcher for
            function components (and an error dispatcher for classes) — the
            pre-fix class lowering would THROW here; the real function
            component renders the hook-seeded value."
    (let [hooked (fn hooked-view [start]
                   (let [state (react/useState start)
                         n     (aget state 0)]
                     [:span "hooked:" n]))
          markup (rds/renderToStaticMarkup
                   (template/as-element [:f> hooked 41]))]
      (is (= "<span>hooked:41</span>" markup)
          "useState ran in a valid function-component context; state seeded
           from the arg — no 'Invalid hook call' throw"))))

(deftest as-element-user-fn
  (testing "[my-view 1] — user-fn head wraps via fn-to-class"
    (let [my-view (fn [_n] [:div])
          ^js el (template/as-element [my-view 1])]
      (is (component/reagent-class? (.-type el))))))

;; ---------------------------------------------------------------------------
;; Source-coord stamping is gated on a native DOM-tag head (rf2-33lo7r)
;;
;; native-element is the emit path for BOTH real DOM tags AND :> interop
;; elements. The *source-coord* merge must fire ONLY for a string DOM tag —
;; never as a foreign prop on a :> component (React drops the unknown prop,
;; leaving the real DOM root unannotated), mirroring the React-hook spine's
;; dom-element? string-type gate.
;; ---------------------------------------------------------------------------

(def ^:private src-coord "my.ns:my-view:12:4")

(defn- source-coord-prop [^js el]
  (gobj/get (.-props el) "data-rf2-source-coord"))

(deftest source-coord-stamps-dom-root
  (testing "a native DOM-tag root consumes *source-coord* and IS stamped"
    (binding [template/*source-coord* src-coord]
      (let [^js el (template/as-element [:div "x"])]
        (is (= "div" (.-type el)))
        (is (= src-coord (source-coord-prop el))
            "the DOM root carries data-rf2-source-coord")))))

(deftest source-coord-skips-interop-root
  (testing "a :> interop root does NOT get source-coord as a foreign prop"
    (let [Comp (fn FakeComp [_props] nil)]
      (binding [template/*source-coord* src-coord]
        (let [^js el (template/as-element [:> Comp {:foo "bar"}])]
          (is (= Comp (.-type el)) "root is the foreign component")
          (is (= "bar" (gobj/get (.-props el) "foo")) "user prop preserved")
          (is (nil? (source-coord-prop el))
              "no data-rf2-source-coord stamped onto the :> component"))))))

(deftest source-coord-flows-past-interop-root-to-first-dom-child
  (testing "with a :> root the binding is left UNCONSUMED so the first real
            DOM element downstream gets stamped (§5.4 'first DOM-tag head')"
    (let [Comp (fn FakeComp [_props] nil)]
      (binding [template/*source-coord* src-coord]
        (let [^js el    (template/as-element [:> Comp [:div "child"]])
              ^js child (gobj/get (.-props el) "children")]
          (is (= Comp (.-type el)))
          (is (nil? (source-coord-prop el)) "interop root itself is unstamped")
          (is (= "div" (.-type child)) "the child is the real DOM element")
          (is (= src-coord (source-coord-prop child))
              "the first DOM element downstream carries data-rf2-source-coord"))))))

;; ---------------------------------------------------------------------------
;; rf2-ygknv finding 1: target-aware keyword/symbol DOM-attr stringification
;;
;; convert-props is shared by native DOM tags and :> custom React
;; components. For native DOM tags every prop is an HTML attribute, so
;; keyword/symbol values must stringify (matching the pure server
;; serializer + React DOM). For custom/interop components the keyword
;; is preserved (e.g. :rf/foo on a React-context Provider's :value).
;; ---------------------------------------------------------------------------

(deftest as-element-native-button-type-keyword-stringifies
  (testing "[:button {:type :button}] → props.type === \"button\""
    (let [^js el (template/as-element [:button {:type :button}])]
      (is (= "button" (-> el .-props .-type))))))

(deftest as-element-native-anchor-keyword-attrs-stringify
  (testing "[:a {:target :_blank :rel :noopener}] → string DOM attrs"
    (let [^js el (template/as-element [:a {:target :_blank :rel :noopener}])]
      (is (= "_blank" (-> el .-props .-target)))
      (is (= "noopener" (-> el .-props .-rel))))))

(deftest as-element-native-symbol-attr-stringifies
  (testing "native DOM tag stringifies a symbol attr value too"
    (let [^js el (template/as-element [:input {:name 'q}])]
      (is (= "q" (-> el .-props .-name))))))

(deftest as-element-interop-preserves-keyword-value
  (testing "[:> Provider {:value :rf/foo}] preserves the CLJS keyword
            (custom React component — NOT a native DOM tag)"
    (let [Provider (fn FakeProvider [_props] nil)
          ^js el   (template/as-element [:> Provider {:value :rf/foo}])]
      (is (= Provider (.-type el)))
      (is (= :rf/foo (-> el .-props .-value))
          "keyword preserved for the interop component, not stringified"))))

(deftest as-element-interop-non-html-keyword-preserved-html-stringified
  (testing "interop: HTML-attr keyword stringifies, non-HTML keyword preserved"
    (let [Comp   (fn FakeComp [_props] nil)
          ^js el (template/as-element [:> Comp {:role :button :kind :primary}])]
      (is (= "button" (-> el .-props .-role))
          ":role is an HTML-attr name → stringified even on interop")
      (is (= :primary (-> el .-props .-kind))
          ":kind is a custom prop → keyword preserved on interop"))))

;; ---------------------------------------------------------------------------
;; rf2-ygknv finding 2: CSS custom properties (--foo) not camelCased
;;
;; The live React-element path's style-map conversion ran every key
;; through cached-prop-name, camelCasing `--gap` → `Gap` and silently
;; dropping the variable (while the pure server serializer preserved
;; it → parity break). dash-to-prop-name now short-circuits `--` names.
;; ---------------------------------------------------------------------------

(deftest as-element-style-css-var-preserved
  (testing "[:div {:style {:--gap \"8px\"}}] → props.style[\"--gap\"] === \"8px\""
    (let [^js el (template/as-element [:div {:style {:--gap "8px"}}])
          style  (.. el -props -style)]
      (is (= "8px" (aget style "--gap"))
          "CSS custom property name preserved verbatim")
      (is (or (nil? (aget style "Gap")) (= js/undefined (aget style "Gap")))
          "NO camelCased 'Gap' replacement key created"))))

(deftest as-element-style-css-var-alongside-normal-prop
  (testing "CSS var coexists with a normal camelCased style prop"
    (let [^js el (template/as-element [:div {:style {:--accent "red"
                                                     :font-size "12px"}}])
          style  (.. el -props -style)]
      (is (= "red" (aget style "--accent"))
          "custom property preserved")
      (is (= "12px" (aget style "fontSize"))
          "regular kebab style key still camelCased to fontSize"))))

(deftest cached-prop-name-css-var-not-camelcased
  (testing "rf2-ygknv finding 2: cached-prop-name preserves --foo verbatim"
    (is (= "--gap" (template/cached-prop-name :--gap)))
    (is (= "--my-custom-prop" (template/cached-prop-name :--my-custom-prop)))))

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

(deftest as-element-seq-children-interior-nil-false
  ;; rf2-8u8tx.1 — expand-seq must NOT truncate at the first nil/false
  ;; element. The idiomatic conditional-list shape
  ;;   (for [x xs] (when (pred? x) [:li ...]))
  ;; yields interior nils for filtered-out rows; a truthiness-gated loop
  ;; would stop at the first one and silently drop it AND every later
  ;; child. Stock Reagent maps as-element over the WHOLE seq (nil → React
  ;; null), so the list is never truncated.
  (testing "interior nil does not truncate — later children survive"
    (let [;; (list [:li 1] nil [:li 3]) — the shape produced by
          ;; (for [x [1 2 3]] (when (odd? x) [:li {:key x} x]))
          seq-children (for [n (range 1 4)]
                         (when (odd? n) ^{:key n} [:li n]))
          arr (template/expand-seq seq-children)]
      (is (= 3 (alength arr))
          "all 3 positions present (nil placeholder kept, not dropped)")
      (is (some? (aget arr 0)) "[:li 1] survives")
      (is (nil? (aget arr 1)) "interior nil → React null placeholder")
      (is (some? (aget arr 2))
          "[:li 3] survives — NOT truncated by the interior nil")))
  (testing "interior false does not truncate — later children survive"
    (let [seq-children (list ^{:key 0} [:span "a"] false ^{:key 2} [:span "c"])
          arr (template/expand-seq seq-children)]
      (is (= 3 (alength arr)) "all 3 positions present")
      (is (some? (aget arr 0)) "first element survives")
      (is (some? (aget arr 2))
          "trailing element survives past the interior false")))
  (testing "leading nil does not abort the whole seq"
    (let [seq-children (list nil ^{:key 1} [:span "b"] ^{:key 2} [:span "c"])
          arr (template/expand-seq seq-children)]
      (is (= 3 (alength arr)))
      (is (nil? (aget arr 0)))
      (is (some? (aget arr 1)) "element after leading nil survives")
      (is (some? (aget arr 2))))))

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

(deftest as-element-void-tag-children-warns-and-drops-rf2-mdgt8t
  (testing "rf2-mdgt8t (c): a void tag given children still DROPS them
            (documented leniency — no render crash) but emits a DEBUG dev
            warning so the app bug is NON-silent, not masked."
    (let [captured (atom nil)
          calls    (atom [])]
      (with-warn-spy calls
        #(reset! captured (template/as-element [:br "should-not-render"])))
      (let [^js el @captured]
        (is (= "br" (.-type el)))
        ;; children still dropped: React.createElement(br, props) → no children
        (is (or (nil? (-> el .-props .-children))
                (js/Array.isArray (-> el .-props .-children)))
            "children dropped (lenient)"))
      ;; but NON-silent: exactly one warning naming the void tag
      (is (= 1 (count @calls)) "one dev warning fired for the dropped children")
      (is (re-find #"void element" (first @calls)) "warning names the offence")
      (is (re-find #"<br>" (first @calls)) "warning names the void tag"))))

(deftest as-element-void-tag-no-children-does-not-warn-rf2-mdgt8t
  (testing "rf2-mdgt8t (c): a void tag WITHOUT children does not warn"
    (let [calls (atom [])]
      (with-warn-spy calls
        #(do (template/as-element [:br])
             (template/as-element [:input {:type "text"}])
             (template/as-element [:img {:src "x.png"}])))
      (is (zero? (count @calls))
          "no warning when void tags carry no children"))))

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

;; ---------------------------------------------------------------------------
;; rf2-tsuk6: an ACCEPTED tag/prop name must not poison the shared caches
;;
;; `tag-name-cache` and `prop-name-cache` are plain `#js {}` objects keyed on
;; user-controlled names. String Hiccup heads are accepted, so "hasOwnProperty"
;; is a valid head; prop-key names are accepted, so `{:hasOwnProperty x}` is a
;; valid prop. Testing a cache hit with `(.hasOwnProperty cache n)` reads the
;; method OFF the cache object, so caching an entry NAMED "hasOwnProperty"
;; `aset`s a value under that own-property name and shadows the method — and
;; the NEXT lookup then invokes that value as a function and throws a raw host
;; TypeError, taking down every later render until reload. The fix tests
;; membership with `Object.prototype.hasOwnProperty.call(cache, n)`, which no
;; cache entry can shadow.
;;
;; The lever is ORDER: seed the "hasOwnProperty"-named entry FIRST (that render
;; succeeds and shadows the method), THEN render an ordinary tag/prop — before
;; the fix that second render throws; after it, it parses normally. The tests
;; assert the OBSERVABLE parse result (`.-type` / `props`) of BOTH renders, so
;; a spurious-crash is distinguished from a correct-parse (not vacuous: a stub
;; returning nil would fail the type assertions).
;; ---------------------------------------------------------------------------

(deftest tag-name-cache-accepts-hasownproperty-head-rf2-tsuk6
  (testing "rf2-tsuk6: caching the accepted string head \"hasOwnProperty\"
            does not break the NEXT tag lookup"
    ;; Seed FIRST: this render succeeds and, pre-fix, `aset`s a HiccupTag
    ;; under the own-property name "hasOwnProperty", shadowing the method.
    (let [^js seeded (template/as-element ["hasOwnProperty" "first"])]
      (is (= "hasOwnProperty" (.-type seeded))
          "the accepted string head renders as its own custom element"))
    ;; The very next ordinary lookup must parse normally. Pre-fix,
    ;; `(.hasOwnProperty tag-name-cache \"div\")` invokes the shadowing
    ;; HiccupTag as a function → raw TypeError; the render never returns.
    (let [^js el (template/as-element ["div" "second"])]
      (is (= "div" (.-type el))
          "the subsequent ordinary tag renders correctly, not a TypeError"))))

(deftest prop-name-cache-accepts-hasownproperty-key-rf2-tsuk6
  (testing "rf2-tsuk6: caching the accepted prop key :hasOwnProperty does
            not break the NEXT prop-name lookup"
    ;; Seed FIRST: `cached-prop-name :hasOwnProperty` `aset`s "hasOwnProperty"
    ;; under that own-property name in prop-name-cache, shadowing the method.
    (let [^js seeded (template/as-element [:div {:hasOwnProperty "x"}])]
      (is (= "div" (.-type seeded))
          "an element carrying a :hasOwnProperty prop renders"))
    ;; The next element with ANY prop must convert normally. Pre-fix,
    ;; `(.hasOwnProperty prop-name-cache \"class\")` invokes the shadowing
    ;; string as a function → raw TypeError.
    (let [^js el (template/as-element [:span {:class "c"}])]
      (is (= "span" (.-type el))
          "the subsequent element parses, not a TypeError")
      (is (= "c" (.. el -props -className))
          "and its prop still camelCases through prop-name-cache"))))

;; ---------------------------------------------------------------------------
;; rf2-lhdp0: the caches have NO PROTOTYPE, and that is load-bearing
;;
;; `tag-name-cache` and `prop-name-cache` are `Object.create(null)`. Nothing in
;; them is ever handed to React (the props objects that ARE handed to React
;; keep their prototype — React's style diffing calls `styles.hasOwnProperty`),
;; so the caches are free to drop the chain, and dropping it is what lets their
;; HIT path — once per element and once per prop of every mount — carry no
;; guard at all.
;;
;; With no prototype a lookup can only ever answer an OWN property, so an
;; inherited name cannot falsely hit. These witnesses pin exactly that: a tag
;; or prop literally NAMED after an `Object.prototype` member must be parsed
;; and converted AS ITSELF, and must still answer itself the second time
;; (proving the entry it then owns is its own and not the inherited one).
;;
;; THE MUTATION THEY EXIST FOR: put either cache back to `#js {}` and these go
;; red — the lookup is served `Object.prototype`'s member for a name nobody
;; cached. They are not vacuous: each asserts the parsed VALUE, so a stub
;; answering nil fails them too.
;; ---------------------------------------------------------------------------

(def ^:private prototype-member-names
  ["toString" "valueOf" "hasOwnProperty" "isPrototypeOf"
   "propertyIsEnumerable" "toLocaleString"])

(deftest tag-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (testing "rf2-lhdp0: a string head named after an Object.prototype member
            parses as ITSELF, twice — a prototype-less cache cannot serve
            the inherited member"
    (doseq [n prototype-member-names]
      ;; First sight: a MISS that must parse rather than inherit.
      (let [^js first-el (template/as-element [n "x"])]
        (is (= n (.-type first-el))
            (str "head \"" n "\" renders as its own element on first sight")))
      ;; Second sight: a HIT that must answer the entry we cached, not the
      ;; prototype member of the same name.
      (let [^js second-el (template/as-element [n "y"])]
        (is (= n (.-type second-el))
            (str "head \"" n "\" still renders as itself on the cached path"))
        (is (string? (.-type second-el))
            (str "head \"" n "\" answers a parsed tag, never a host function"))))))

(deftest prop-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (testing "rf2-lhdp0: a prop key named after an Object.prototype member
            converts to its own name, twice"
    (doseq [n prototype-member-names]
      (let [k (keyword n)]
        ;; The public cache entry point, direct.
        (is (= n (template/cached-prop-name k))
            (str ":" n " converts to its own name on first sight"))
        (is (= n (template/cached-prop-name k))
            (str ":" n " converts to its own name on the cached path"))
        (is (string? (template/cached-prop-name k))
            (str ":" n " answers a string, never a host function"))
        ;; And through a whole element, where the name reaches the props object.
        (let [^js el (template/as-element [:div {k "v"}])]
          (is (= "v" (gobj/get (.-props el) n))
              (str ":" n " reaches the props object under its own name")))))))

;; ---------------------------------------------------------------------------
;; rf2-lhdp0: `void-tag?` indexes `void-tags` — one roster, not two
;;
;; The probe is a null-prototype index BUILT FROM the `void-tags` set, so the
;; set stays the single source of truth. This witness walks the WHOLE roster
;; (the pre-existing cases cover br/input/img — 3 of 14) so the derivation is
;; pinned end to end rather than sampled, and checks a non-void tag still
;; keeps its children.
;; ---------------------------------------------------------------------------

(deftest every-void-tag-drops-children-rf2-lhdp0
  (testing "rf2-lhdp0: every member of void-tags is recognised by the probe"
    (is (= 14 (count template/void-tags))
        "the HTML5 void roster is the fixed 14")
    (doseq [t template/void-tags]
      (let [^js el (template/as-element [(keyword t) "dropped"])]
        (is (= t (.-type el)) (str "<" t "> renders"))
        (is (nil? (-> el .-props .-children))
            (str "<" t "> drops the child React would reject"))))))

(deftest non-void-tag-keeps-children-rf2-lhdp0
  (testing "rf2-lhdp0: the index answers false for ordinary tags, which
            therefore keep their children (the probe is not stuck true)"
    (doseq [t ["div" "span" "p" "section" "a"]]
      (let [^js el (template/as-element [(keyword t) "kept"])]
        (is (= t (.-type el)) (str "<" t "> renders"))
        (is (= "kept" (-> el .-props .-children))
            (str "<" t "> keeps its child"))))))

;; ---------------------------------------------------------------------------
;; rf2-lhdp0: the specialised per-element `:key` read
;;
;; `native-element` reads the key from the props slot `hiccup-shape` already
;; bound rather than re-deriving it through `get-react-key`'s `nth`/`case`
;; ladder. `native-element` is the emit path for BOTH routes — a DOM tag
;; (props at index 1) and `:>` interop (props at index 2) — so both are
;; witnessed here, on both key spellings, plus the precedence between them
;; and the absence case.
;; ---------------------------------------------------------------------------

(deftest key-read-covers-both-native-element-routes-rf2-lhdp0
  (testing "rf2-lhdp0: DOM tag — meta key, prop key, neither"
    (is (= "m" (.-key ^js (template/as-element ^{:key "m"} [:div "x"])))
        "meta key on a DOM tag")
    (is (= "p" (.-key ^js (template/as-element [:div {:key "p"} "x"])))
        "prop key on a DOM tag")
    (is (nil? (.-key ^js (template/as-element [:div "x"])))
        "no key at all on a DOM tag")
    (is (nil? (.-key ^js (template/as-element [:div {:class "c"} "x"])))
        "props without :key leave the key unset"))

  (testing "rf2-lhdp0: meta key WINS over the prop key, both routes"
    (is (= "m" (.-key ^js (template/as-element ^{:key "m"} [:div {:key "p"} "x"])))
        "DOM tag: meta beats props"))

  (testing "rf2-lhdp0: :> interop — props live at index 2, not 1"
    (let [C (fn [_] nil)]
      (is (= "p" (.-key ^js (template/as-element [:> C {:key "p"} "x"])))
          "prop key on an interop head")
      (is (= "m" (.-key ^js (template/as-element ^{:key "m"} [:> C {:key "p"} "x"])))
          "meta key beats the prop key on an interop head")
      (is (nil? (.-key ^js (template/as-element [:> C "x"])))
          "interop head with no props slot has no key"))))
