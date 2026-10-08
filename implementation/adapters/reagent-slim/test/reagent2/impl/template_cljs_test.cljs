(ns reagent2.impl.template-cljs-test
  "Unit tests for reagent2.impl.template: tag parsing, prop-name and prop-value
  conversion, hiccup dispatch to React elements, sequence children and void
  tags."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.template :as template]
            [reagent2.impl.component :as component]
            [goog.object :as gobj]
            ["react" :as react]
            ["react-dom/server" :as rds]))

;; ---------------------------------------------------------------------------
;; Tag parsing — :div.cls#id shorthand
;; ---------------------------------------------------------------------------

(deftest parse-tag-splits-tag-id-and-class-shorthand
  (doseq [[head tag id class-name label]
          [[:div        "div" nil  nil   "bare tag"]
           [:div.foo    "div" nil  "foo" "one class"]
           [:div#bar    "div" "bar" nil  "an id"]
           [:div#id.a.b "div" "id" "a b" "id before classes (the supported form); classes join with a space"]]]
    (let [parsed (template/parse-tag head [head])]
      (is (= [tag id class-name]
             [(.-tag parsed) (.-id parsed) (.-className parsed)])
          (str (pr-str head) " — " label)))))

(deftest parse-tag-class-before-id-not-supported
  (testing ":div.a#id — class-before-id is NOT supported (nil tag)"
    (let [parsed (template/parse-tag :div.a#id [:div.a#id])]
      (is (nil? (.-tag parsed))
          "class-before-id yields a nil tag, not a parsed element"))))

;; ---------------------------------------------------------------------------
;; cached-prop-name — kebab→camel + special cases
;; ---------------------------------------------------------------------------

(deftest cached-prop-name-maps-each-prop-key
  (doseq [[why k expected]
          [[":class → \"className\""                                :class          "className"]
           [":for → \"htmlFor\""                                    :for            "htmlFor"]
           [":tab-index → \"tabIndex\" (kebab→camel)"               :tab-index      "tabIndex"]
           [":data-foo → \"data-foo\" (data-* not camelCased)"      :data-foo       "data-foo"]
           [":aria-label → \"aria-label\" (aria-* not camelCased)"  :aria-label     "aria-label"]
           ["non-keyword value passes through unchanged"            "alreadyString" "alreadyString"]]]
    (testing why
      (is (= expected (template/cached-prop-name k))))))

;; ---------------------------------------------------------------------------
;; Narrowed convert-prop-value — D2 (R-001)
;;
;; Per IMPL-SPEC §7.2: keyword values stringify only for HTML attribute
;; names (:class, :id, :role, :data-*, :aria-*). Other names pass
;; through with a one-shot dev warning.
;; ---------------------------------------------------------------------------

(deftest convert-prop-value-stringifies-html-attr-keywords-and-passes-other-values
  (doseq [[why k v expected]
          [[":class with keyword value → string (HTML-attr name)"         :class      :primary     "primary"]
           [":id with keyword value → string (HTML-attr name)"            :id         :main-header "main-header"]
           [":role with keyword value → string (HTML-attr name)"          :role       :button      "button"]
           [":data-foo with keyword value → string (data-* HTML attr)"    :data-foo   :bar         "bar"]
           [":aria-label with keyword value → string (aria-* HTML attr)"  :aria-label :close       "close"]
           ["string value passes through unchanged"                       :class      "hello"      "hello"]
           ["number value passes through unchanged"                       :tab-index  42           42]]]
    (testing why
      (is (= expected (template/convert-prop-value k v))))))

;; Reaches `convert-prop-value`'s `ifn?` arm: object-backed, IFn but not Fn,
;; and none of the earlier arms' shapes. Each -invoke records its arguments.
(deftype CallableProbe [calls]
  IFn
  (-invoke [_]     (swap! calls conj [])       :called-0)
  (-invoke [_ a]   (swap! calls conj [a])      [:called-1 a])
  (-invoke [_ a b] (swap! calls conj [a b])    [:called-2 a b]))

(deftest convert-prop-value-fn-yields-stable-callable-js-fn-rf2-fzbj-30
  (testing "a metadata-bearing fn (a MetaFn, which JavaScript cannot call)
            converts to a real JS function, the same one each time, so a host
            can invoke it and React's identity checks still hold. The witness
            is a NATIVE call: CLJS invocation and `.call` both work on an
            unconverted MetaFn"
    (let [calls   (atom [])
          handler (with-meta (fn [& args]
                               (swap! calls conj (vec args))
                               [:handled (vec args)])
                    {:rf/probe true})]
      (doseq [[label convert] [["1-arg" #(template/convert-prop-value handler)]
                               ["2-arg" #(template/convert-prop-value :on-click handler)]
                               ["3-arg" #(template/convert-prop-value :on-click handler true)]]]
        (let [out (convert)]
          (is (= "function" (goog/typeOf out)) label)
          (is (identical? out (convert)) (str label ": the same function again"))))
      (is (= [:handled [:x :y]]
             (js/Reflect.apply (template/convert-prop-value :on-click handler) nil #js [:x :y])))
      (is (= [[:x :y]] @calls) "the handler itself ran, exactly once")))
  (testing "a plain JS fn passes through unchanged"
    (let [plain (fn [_e] :plain)]
      (is (identical? plain (template/convert-prop-value :on-click plain)))
      (is (identical? plain (template/convert-prop-value plain))))))

(deftest convert-prop-value-non-fn-ifn-still-wrapped
  (testing "an object-backed IFn that is not a Fn reaches the `ifn?` arm and
            comes back as a JavaScript-invokable function, once per input"
    (let [calls (atom [])
          probe (->CallableProbe calls)
          out   (template/convert-prop-value :on-select probe)]
      (is (= "function" (goog/typeOf out)))
      (is (identical? out (template/convert-prop-value :on-select probe)))
      (is (= [:called-1 :a] (.call out nil :a)))
      (is (= [:called-2 :a :b] (.call out nil :a :b)))
      (is (= [[:a] [:a :b]] @calls) "the fixture's own -invoke ran for each call")
      (is (= :called-0 (.call (template/convert-prop-value probe) nil))
          "the 1-arg form takes the same arm"))))

(defn- CallsOnSelect
  "A foreign React function component that calls its callback prop the
  way third-party JavaScript does — natively, as `props.onSelect(...)`."
  [^js props]
  (react/createElement "span" nil (.onSelect props "picked")))

(deftest metafn-callback-prop-is-callable-by-a-foreign-react-component-rf2-fzbj-30
  (testing "a real React render of a foreign
            component that invokes its callback prop natively. A MetaFn
            object reaching `props.onSelect` unchanged would make the render
            throw `props.onSelect is not a function`."
    (let [meta-handler  (with-meta (fn [v] (str "meta-" v)) {:rf/probe true})
          plain-handler (fn [v] (str "plain-" v))]
      (is (= "<span>plain-picked</span>"
             (rds/renderToStaticMarkup
               (template/as-element [:> CallsOnSelect {:on-select plain-handler}])))
          "control: a plain fn prop is called by the foreign component")
      (is (= "<span>meta-picked</span>"
             (rds/renderToStaticMarkup
               (template/as-element [:> CallsOnSelect {:on-select meta-handler}])))
          "a metadata-bearing fn prop is called by the foreign component too"))))

;; ---------------------------------------------------------------------------
;; A component whose render returns a SEQUENCE renders sibling children. Driven
;; through the real generated class, because a list and the vector coerced
;; from it compare equal, yet `as-element` reads the vector as ONE hiccup form.
;; ---------------------------------------------------------------------------

(defn- seq-rows [] (list ^{:key "a"} [:span "a"] ^{:key "b"} [:span "b"]))
(defn- seq-empty [] (list))
(defn- seq-text [] (list "a" "b"))

(defn- static-markup [hiccup]
  (rds/renderToStaticMarkup (template/as-element hiccup)))

(defn- class-render-output
  "Instantiate the class `as-element` generates for the component vector
  `hiccup` and run its React `render` method, returning what React would
  receive. Unmounts the instance so its render Reaction is disposed."
  [hiccup]
  (let [^js el   (template/as-element hiccup)
        klass    (.-type el)
        ^js inst (new klass (.-props el))]
    (try
      (.render inst)
      (finally
        (.componentWillUnmount inst)))))

(deftest component-sequence-output-renders-siblings-rf2-fzbj-30
  (testing "a component returning a list renders sibling children
            through the runtime classification path — keyed elements, an
            empty sequence and text siblings alike"
    (is (= "<span>a</span><span>b</span>" (static-markup [seq-rows]))
        "keyed element siblings, with no wrapper element")
    (is (= "<div></div>" (static-markup [:div [seq-empty]]))
        "an empty sequence renders nothing (not an empty hiccup vector)")
    (is (= "<div>ab</div>" (static-markup [:div [seq-text]]))
        "text siblings stay text (not misread as an `<a>` tag with a child)")
    (is (= ["a" "b"] (let [out (class-render-output [seq-rows])]
                       (when (array? out) (mapv #(.-key ^js %) out))))
        "the class render hands React an array of keyed children")))

(deftest sequence-output-agrees-across-form-shapes-rf2-fzbj-30
  (testing "controls: a Form-2 inner renderer and a direct as-element of
            the same sequence render the same siblings, and a hiccup
            vector still means ONE element"
    (let [form-2        (fn [] (fn [] (seq-rows)))
          vector-form-1 (fn [] [:span "one"])]
      (is (= "<span>a</span><span>b</span>" (static-markup [form-2]))
          "Form-2 inner renderer")
      (is (= "<span>a</span><span>b</span>" (static-markup (seq-rows)))
          "direct as-element of the sequence")
      (is (= "<span>one</span>" (static-markup [vector-form-1]))
          "a component returning a hiccup vector still renders one element"))))

;; ---------------------------------------------------------------------------
;; A keyword value on a non-HTML-attribute prop passes through and warns once
;; per [k name-of-v]. The cache is a private defonce, so the pairs here are
;; unique to this file.
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

(deftest warn-once-keyword-prop-fires-once-per-non-html-pair
  (let [calls (atom [])]
    (with-warn-spy calls
      #(doseq [[k v] [[:rf2-warn-test-k1 :rf2-v1]
                      [:rf2-warn-test-k1 :rf2-v1]
                      [:rf2-warn-test-k1 :rf2-v1b]
                      [:class :rf2-warn-test-html]
                      [:data-foo :rf2-warn-test-data]
                      [:aria-label :rf2-warn-test-aria]]]
         (template/convert-prop-value k v)))
    (is (= 2 (count @calls))
        "a repeated pair is silent, a fresh value warns again, HTML attrs never warn")
    (is (every? #(re-find % (first @calls)) [#"rf2-warn-test-k1" #"rf2-v1"])
        "the warning names the prop and the value")
    (is (re-find #"rf2-v1b" (second @calls)))))

;; ---------------------------------------------------------------------------
;; as-element — primitive cases
;; ---------------------------------------------------------------------------

(deftest as-element-primitives
  (doseq [[why x expected]
          [["nil → nil"           nil     nil]
           ["string → string"     "hello" "hello"]
           ["number → number"     42      42]
           ["bare keyword → name" :foo    "foo"]
           ["bare symbol → name"  'bar    "bar"]]]
    (testing why
      (is (= expected (template/as-element x))))))

;; ---------------------------------------------------------------------------
;; as-element — DOM tags
;; ---------------------------------------------------------------------------


(deftest as-element-shorthand-id-yields-to-prop
  (testing "[:div#a {:id \"b\"}] → user :id wins over shorthand"
    (let [^js el (template/as-element [:div#a {:id "b"}])]
      (is (= "b" (-> el .-props .-id))))))

;; :class and :className both map to React's `className` prop. Leaving
;; both keys in the prop map would send two writes to the same JS slot,
;; and the survivor would be iteration-order dependent (array-map vs
;; hash-map differ) — silently dropping one class string (and, with
;; shorthand, the shorthand class too). collapse-class-keys folds
;; :className into :class deterministically,
;; matching the server path's merge-tag-shorthand :className handling.
(deftest as-element-class-props-collapse-into-one-className
  (doseq [[why hiccup expected]
          [["[:div {:class \"foo\"}] → element with className"
            [:div {:class "foo"}] "foo"]
           ["[:div.foo {:class \"bar\"}] → \"foo bar\" (shorthand prepends per stock)"
            [:div.foo {:class "bar"}] "foo bar"]
           ["[:div {:className \"bar\"}] → React-style :className passes through"
            [:div {:className "bar"}] "bar"]
           ["[:div {:class \"a\" :className \"b\"}] → merged \"a b\", neither dropped"
            [:div {:class "a" :className "b"}] "a b"]
           ["[:div.sh {:class \"a\" :className \"b\"}] → \"sh a b\" (all three kept)"
            [:div.sh {:class "a" :className "b"}] "sh a b"]
           ["[:div.foo {:className \"bar\"}] → \"foo bar\" (no double-merge)"
            [:div.foo {:className "bar"}] "foo bar"]
           ["large (hash-map) props with :class + :className stays deterministic"
            [:div (merge {:class "a" :className "b"}
                         (zipmap (map #(keyword (str "data-x" %)) (range 20))
                                 (range 20)))]
            "a b"]]]
    (testing why
      (let [^js el (template/as-element hiccup)]
        (is (= expected (-> el .-props .-className)))))))

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

(deftest as-element-fragment-with-ref
  (testing "React accepts `key`, `ref` and `children` on a Fragment, and this
            path converts the WHOLE props map — so a fragment ref crosses here
            with no arm of its own. Measured rather than read off the
            conversion rules, because the identity is the part that matters:
            React detaches and reattaches on a changed ref identity, so a
            wrapper allocated per render would re-run the author's callback,
            and its cleanup, on every commit."
    (let [f      (fn [_instance] nil)
          ^js el (template/as-element [:<> {:ref f} [:div "a"]])]
      (is (= (.-Fragment react) (.-type el)))
      (is (identical? f (-> el .-props .-ref))
          "the author's own function, not a wrapper")))
  (testing "an object ref crosses untouched too — it takes the `:else` arm
            rather than any of the converting ones"
    (let [r      (react/createRef)
          ^js el (template/as-element [:<> {:ref r} [:div "a"]])]
      (is (identical? r (-> el .-props .-ref)))))
  (testing "and a key and a ref from one map land in their two different
            places: the key on the element, the ref in the props"
    (let [f      (fn [_instance] nil)
          ^js el (template/as-element [:<> {:key "k" :ref f} [:div "a"]])]
      (is (= "k" (.-key el)))
      (is (identical? f (-> el .-props .-ref))))))

(deftest as-element-raw
  (testing "[:r> Comp js-props] → raw createElement, no prop conversion"
    (let [Comp (fn FakeRaw [_props] nil)
          js-props #js {:already "shaped"}
          ^js el (template/as-element [:r> Comp js-props])]
      (is (= Comp (.-type el)))
      (is (= "shaped" (-> el .-props .-already))))))

(deftest as-element-raw-key-does-not-mutate-caller-props-rf2-mdgt8t
  (testing "stamping :key on an :r> element COPIES the
            caller-supplied js-props object instead of mutating it. The
            caller's object must be unchanged after render."
    (let [Comp     (fn FakeRaw [_props] nil)
          js-props #js {:already "shaped"}
          ^js el   (template/as-element ^{:key "k"} [:r> Comp js-props])]
      (is (= "k" (.-key el)) "the React key is stamped on the element")
      (is (= "shaped" (-> el .-props .-already)) "props still flow through")
      (is (= 1 (.-length (js/Object.keys js-props)))
          "caller's js-props gained no extra own keys"))))

(deftest as-element-interop-heads-are-keyword-sentinels-rf2-e7zxb
  (testing "the four interop heads are dispatched by a `case`,
            which lowers to a switch on the head's `.-fqn`. A head
            RECONSTRUCTED at runtime is not `identical?` to the literal, so
            this is the pin that the dispatch is by VALUE, as a
            `(= tag :>)` ladder would be."
    (let [Comp (fn FakeComp [_props] nil)]
      (is (= Comp (.-type ^js (template/as-element [(keyword ">") Comp {:foo "bar"}])))
          "a reconstructed :> still reaches interop-element")
      (is (= (.-Fragment react)
             (.-type ^js (template/as-element [(keyword "<>") [:div "a"]])))
          "a reconstructed :<> still reaches fragment-element")
      (is (= Comp (.-type ^js (template/as-element [(keyword "r>") Comp #js {}])))
          "a reconstructed :r> still reaches raw-element")
      (is (fn? (.-type ^js (template/as-element [(keyword "f>") (fn [] [:div])])))
          "a reconstructed :f> still reaches function-element")))

  (testing "the STRING and SYMBOL look-alikes are DOM-tag heads,
            not sentinels — the `case` must refuse a non-keyword, as a
            `(= tag :>)` ladder would."
    (is (= ">" (.-type ^js (template/as-element [">" "x"])))
        "the string \">\" is a custom-element tag, not the :> head")
    (is (= "<>" (.-type ^js (template/as-element ["<>" "x"])))
        "the string \"<>\" is a tag, not the :<> head")
    (is (= ">" (.-type ^js (template/as-element [(symbol ">") "x"])))
        "the symbol '> is a tag, not the :> head")))

(deftest props-slot-rule-rf2-e7zxb
  (testing "`props-slot?` is the one named rule four call sites
            share — nil or a map occupies the props slot, anything else is
            the first child."
    (is (= [true true true false false false false]
           (map template/props-slot? [nil {} {:id "x"} "x" [:span] 0 :kw]))))

  (testing "an explicit nil props slot is a props slot, so children start
            one later — the arm a past-the-end index also takes"
    (let [^js el (template/as-element [:div nil "only-child"])]
      (is (= "div" (.-type el)))
      (is (= "only-child" (-> el .-props .-children))))
    (let [^js el (template/as-element [:div "first" "second"])]
      (is (= 2 (.-length (-> el .-props .-children)))
          "a non-map slot is the first CHILD, not props"))))

(deftest as-element-function-component-is-real-fn-component-rf2-bf4uw2
  (testing "[:f> f] renders f as a REAL React FUNCTION
            component — NOT a class. A fn-to-class lowering would
            produce a React class, defeating the head's defining
            purpose (hosting React hooks)."
    (let [some-fn (fn [_n] [:div])
          ^js el (template/as-element [:f> some-fn 42])]
      ;; Stable identity: the same fn re-wraps to the SAME component type
      ;; so React reconciles across renders rather than remounting (which
      ;; would drop hook + DOM state).
      (let [^js el2 (template/as-element [:f> some-fn 7])]
        (is (identical? (.-type el) (.-type el2))
            "wrapper cached per fn for stable reconciliation")))))

(deftest as-element-function-component-hooks-render-rf2-bf4uw2
  (testing "an [:f> f] whose f calls a React hook renders
            WITHOUT throwing 'Invalid hook call'. Rendered through
            react-dom/server, which installs the hooks dispatcher for
            function components (and an error dispatcher for classes) — a
            class lowering would THROW here; the real function
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
;; Source-coord stamping is gated on a native DOM-tag head
;;
;; converted-props-element is the emit path for BOTH real DOM tags AND :>
;; interop elements. The *source-coord* merge must fire ONLY for a string DOM
;; tag —
;; never as a foreign prop on a :> component (React drops the unknown prop,
;; leaving the real DOM root unannotated), mirroring the React-hook spine's
;; dom-element? string-type gate.
;; ---------------------------------------------------------------------------

(def ^:private src-coord "my.ns:my-view:12:4")

(defn- source-coord-prop [^js el]
  (gobj/get (.-props el) "data-rf2-source-coord"))

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
;; Target-aware keyword/symbol DOM-attr stringification
;;
;; convert-props is shared by native DOM tags and :> custom React
;; components. For native DOM tags every prop is an HTML attribute, so
;; keyword/symbol values must stringify (matching the pure server
;; serializer + React DOM). For custom/interop components the keyword
;; is preserved (e.g. :rf/foo on a React-context Provider's :value).
;; ---------------------------------------------------------------------------

(deftest as-element-native-tag-stringifies-keyword-and-symbol-attrs
  (doseq [[hiccup prop expected]
          [[[:button {:type :button}] "type" "button"]
           [[:a {:target :_blank :rel :noopener}] "target" "_blank"]
           [[:a {:target :_blank :rel :noopener}] "rel" "noopener"]
           [[:input {:name 'q}] "name" "q"]]]
    (is (= expected (gobj/get (.-props ^js (template/as-element hiccup)) prop))
        (pr-str hiccup))))

(deftest as-element-interop-non-html-keyword-preserved-html-stringified
  (testing "interop: HTML-attr keyword stringifies, non-HTML keyword preserved"
    (let [Comp   (fn FakeComp [_props] nil)
          ^js el (template/as-element [:> Comp {:role :button :kind :primary}])]
      (is (= "button" (-> el .-props .-role))
          ":role is an HTML-attr name → stringified even on interop")
      (is (= :primary (-> el .-props .-kind))
          ":kind is a custom prop → keyword preserved on interop"))))

;; ---------------------------------------------------------------------------
;; CSS custom properties (--foo) not camelCased
;;
;; The live React-element path's style-map conversion runs every key
;; through cached-prop-name. CamelCasing `--gap` → `Gap` would silently
;; drop the variable (while the pure server serializer preserves it →
;; parity break), so dash-to-prop-name short-circuits `--` names.
;; ---------------------------------------------------------------------------

(deftest as-element-style-css-var-alongside-normal-prop
  (testing "CSS var coexists with a normal camelCased style prop"
    (let [^js el (template/as-element [:div {:style {:--accent "red"
                                                     :font-size "12px"}}])
          style  (.. el -props -style)]
      (is (= "red" (aget style "--accent"))
          "custom property preserved")
      (is (= "12px" (aget style "fontSize"))
          "regular kebab style key still camelCased to fontSize"))))

;; ---------------------------------------------------------------------------
;; Sequence-as-children
;; ---------------------------------------------------------------------------

(deftest as-element-seq-children-interior-nil-false
  (testing "expand-seq keeps every position: a nil or false child (the
            `(for ... (when ...))` shape) never truncates the children after it"
    (is (= [true false true]
           (mapv some? (template/expand-seq (for [n (range 1 4)]
                                              (when (odd? n) ^{:key n} [:li n])))))
        "an interior nil becomes a React null")
    (is (= [false true true]
           (mapv some? (template/expand-seq
                         (list nil ^{:key 1} [:span "b"] ^{:key 2} [:span "c"]))))
        "a leading nil does not abort the seq")
    (let [arr (template/expand-seq (list ^{:key 0} [:span "a"] false ^{:key 2} [:span "c"]))]
      (is (= [3 true true] [(alength arr) (some? (aget arr 0)) (some? (aget arr 2))])
          "an interior false does not truncate"))))

;; ---------------------------------------------------------------------------
;; Void tags — children rejected per HTML5
;; ---------------------------------------------------------------------------


(deftest as-element-void-tag-children-warns-and-drops-rf2-mdgt8t
  (testing "a void tag given children still DROPS them
            (documented leniency — no render crash) but emits a DEBUG dev
            warning so the app bug is NON-silent, not masked."
    (let [captured (atom nil)
          calls    (atom [])]
      (with-warn-spy calls
        #(reset! captured (template/as-element [:br "should-not-render"])))
      (let [^js el @captured]
        (is (= "br" (.-type el)))
        (is (nil? (-> el .-props .-children)) "children dropped (lenient)"))
      (is (= 1 (count @calls)) "one dev warning fired for the dropped children")
      (is (re-find #"<br>" (first @calls)) "warning names the void tag"))))

(deftest as-element-void-tag-no-children-does-not-warn-rf2-mdgt8t
  (testing "a void tag WITHOUT children does not warn"
    (let [calls (atom [])]
      (with-warn-spy calls
        #(do (template/as-element [:br])
             (template/as-element [:input {:type "text"}])
             (template/as-element [:img {:src "x.png"}])))
      (is (zero? (count @calls))
          "no warning when void tags carry no children"))))

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
        (is (nil? template/*source-coord*)
            "the binding is consumed by the first DOM root")))))

;; ---------------------------------------------------------------------------
;; Prototype-pollution defence: user keys `:__proto__`, `:constructor` and
;; `:prototype` are dropped before any `aset`, so they never become own
;; properties or reach a prototype chain.
;; ---------------------------------------------------------------------------

(deftest constructor-key-dropped-from-props-rf2-dwds9
  (testing "{:constructor \"x\"} prop is dropped (does not
            override the prototype's constructor or leak as own property)"
    (let [^js el (template/as-element [:div {:constructor "leaked"}])
          props  (.-props el)]
      (is (not (.call (.. js/Object -prototype -hasOwnProperty)
                      props "constructor"))
          "no own 'constructor' slot on the props object"))))

(deftest convert-prop-value-reserved-keys-dropped-rf2-dwds9
  (testing "convert-prop-value at the map? branch drops
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
;; `tag-name-cache` and `prop-name-cache` are `Object.create(null)`, so their
;; unguarded hit path can only answer an OWN entry. A tag or prop named after
;; an `Object.prototype` member must parse as itself, first time and cached.
;; ---------------------------------------------------------------------------

(def ^:private prototype-member-names
  ["toString" "valueOf" "hasOwnProperty" "isPrototypeOf"
   "propertyIsEnumerable" "toLocaleString"])

(deftest tag-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (testing "a string head named after an Object.prototype member
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
            (str "head \"" n "\" still renders as itself on the cached path"))))))

(deftest prop-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (testing "a prop key named after an Object.prototype member
            converts to its own name, twice"
    (doseq [n prototype-member-names]
      (let [k (keyword n)]
        ;; The public cache entry point, direct.
        (is (= n (template/cached-prop-name k))
            (str ":" n " converts to its own name on first sight"))
        (is (= n (template/cached-prop-name k))
            (str ":" n " converts to its own name on the cached path"))
        ;; And through a whole element, where the name reaches the props object.
        (let [^js el (template/as-element [:div {k "v"}])]
          (is (= "v" (gobj/get (.-props el) n))
              (str ":" n " reaches the props object under its own name")))))))

;; ---------------------------------------------------------------------------
;; The React key: metadata wins, then the props map's `:key`, read off the
;; props slot of each head (index 1 for a DOM tag or component, 2 for `:>`
;; and `:f>`). `:r>` is covered by the raw-key test above.
;; ---------------------------------------------------------------------------

(deftest key-read-covers-both-converted-props-routes-rf2-lhdp0
  (let [C (fn [_] nil)
        f (fn [_] [:span])]
    (doseq [[hiccup expected]
            [[^{:key "m"} [:div "x"] "m"]
             [[:div {:key "p"} "x"] "p"]
             [[:div "x"] nil]
             [[:div {:class "c"} "x"] nil]
             [^{:key "m"} [:div {:key "p"} "x"] "m"]
             [[:> C {:key "p"} "x"] "p"]
             [^{:key "m"} [:> C {:key "p"} "x"] "m"]
             [[:> C "x"] nil]
             [[:<> {:key "p"} "x"] "p"]
             [^{:key "m"} [:<> "x"] "m"]
             [^{:key "m"} [:<> {:key "p"} "x"] "m"]
             [[:<> "x"] nil]
             [^{:key "m"} [:f> f "x"] "m"]
             [[:f> f {:key "p"}] "p"]
             [^{:key "m"} [f "x"] "m"]
             [[f {:key "p"}] "p"]]]
      (is (= expected (.-key ^js (template/as-element hiccup)))
          (str (pr-str hiccup) " " (pr-str (meta hiccup)))))))

(defn- missing-key-warnings [calls]
  (filter #(re-find #"unique :key" %) calls))

(defn- render-component-twice!
  "Render one instance of the component `f` twice, as a re-render does,
  then unmount it."
  [f]
  (let [^js el   (template/as-element [f])
        ^js inst (new (.-type el) (.-props el))]
    (try
      (.render inst)
      (.render inst)
      (finally
        (.componentWillUnmount inst)))))

(deftest expand-seq-warns-on-an-unkeyed-child-not-on-keyed-ones
  (testing "a sequence holding an unkeyed child vector warns once"
    (let [calls (atom [])]
      (with-warn-spy calls #(template/as-element [:ul (list [:li "a"])]))
      (is (= 1 (count @calls)) "one unkeyed child, one warning")
      (is (re-find #"unique :key" (str (first @calls)))
          "the warning names the missing :key")))

  (testing "several unkeyed children in one sequence warn once"
    (let [calls (atom [])]
      (with-warn-spy calls
        #(template/as-element [:ul (list [:li "a"] [:li "b"] [:li "c"])]))
      (is (= 1 (count (missing-key-warnings @calls)))
          "three unkeyed children, one warning")))

  (testing "a surrounding component warns once across its sequences and its
            re-renders, and a different component still warns for itself"
    (let [calls (atom [])
          lists (fn [] [:div
                        [:ul (list [:li "a"] [:li "b"])]
                        [:ol (list [:li "c"] [:li "d"])]])
          other (fn [] [:ul (list [:li "e"] [:li "f"])])]
      (with-warn-spy calls #(render-component-twice! lists))
      (is (= 1 (count (missing-key-warnings @calls)))
          "two unkeyed sequences of two children, rendered twice: one warning")
      (with-warn-spy calls #(render-component-twice! other))
      (is (= 2 (count (missing-key-warnings @calls)))
          "the other component's own unkeyed sequence adds exactly one warning")))

  (testing "a sequence of keyed children — meta and prop spellings — is silent"
    (let [calls (atom [])]
      (with-warn-spy calls
        #(template/as-element [:ul (list ^{:key "a"} [:li "a"]
                                         [:li {:key "b"} "b"])]))
      (is (empty? @calls) "keyed children raise no warning"))))
