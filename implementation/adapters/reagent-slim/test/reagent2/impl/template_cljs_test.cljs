(ns reagent2.impl.template-cljs-test
  "Hiccup dispatch, props, children, keys and safe cache contracts."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.template :as template]
            [goog.object :as gobj]
            ["react" :as react]
            ["react-dom/server" :as rds]))

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

(deftest convert-prop-value-stringifies-html-attr-keywords-and-passes-other-values
  (doseq [[k v expected] [[:class :primary "primary"]
                          [:data-foo :bar "bar"]
                          [:aria-label :close "close"]
                          [:class "hello" "hello"]
                          [:tab-index 42 42]]]
    (is (= expected (template/convert-prop-value k v)))))

(deftype CallableProbe []
  IFn
  (-invoke [_] :called-0)
  (-invoke [_ a] [:called-1 a])
  (-invoke [_ a b] [:called-2 a b]))

(deftest convert-prop-value-fn-yields-stable-callable-js-fn-rf2-fzbj-30
  (let [calls   (atom [])
        handler (with-meta (fn [& args]
                             (swap! calls conj (vec args))
                             [:handled (vec args)])
                  {:rf/probe true})]
    (doseq [[label convert] [["1-arg" #(template/convert-prop-value handler)]
                             ["2-arg" #(template/convert-prop-value :on-click handler)]
                             ["3-arg" #(template/convert-prop-value :on-click handler true)]]]
      (let [out (convert)]
        (is (= ["function" true] [(goog/typeOf out) (identical? out (convert))]) label)))
    (is (= [:handled [:x :y]]
           (js/Reflect.apply (template/convert-prop-value :on-click handler) nil #js [:x :y])))
    (is (= [[:x :y]] @calls)))
  (let [plain (fn [_e] :plain)]
    (is (= [true true] [(identical? plain (template/convert-prop-value :on-click plain))
                         (identical? plain (template/convert-prop-value plain))]))))

(deftest convert-prop-value-non-fn-ifn-still-wrapped
  (let [probe (->CallableProbe)
        out   (template/convert-prop-value :on-select probe)]
    (is (= "function" (goog/typeOf out)))
    (is (identical? out (template/convert-prop-value :on-select probe)))
    (is (= [:called-1 :a] (.call out nil :a)))
    (is (= [:called-2 :a :b] (.call out nil :a :b)))
    (is (= :called-0 (.call (template/convert-prop-value probe) nil)))))

(defn- CallsOnSelect
  [^js props]
  (react/createElement "span" nil (.onSelect props "picked")))

(deftest metafn-callback-prop-is-callable-by-a-foreign-react-component-rf2-fzbj-30
  (let [meta-handler  (with-meta (fn [v] (str "meta-" v)) {:rf/probe true})]
    (is (= "<span>meta-picked</span>"
           (rds/renderToStaticMarkup
             (template/as-element [:> CallsOnSelect {:on-select meta-handler}])))
        "a metadata-bearing fn prop is called by the foreign component")))

(defn- seq-rows [] (list ^{:key "a"} [:span "a"] ^{:key "b"} [:span "b"]))
(defn- seq-empty [] (list))
(defn- seq-text [] (list "a" "b"))

(defn- static-markup [hiccup]
  (rds/renderToStaticMarkup (template/as-element hiccup)))

;; A list and its coerced vector compare equal; inspect the actual React output.
(defn- class-render-output
  [hiccup]
  (let [^js el   (template/as-element hiccup)
        klass    (.-type el)
        ^js inst (new klass (.-props el))]
    (try
      (.render inst)
      (finally
        (.componentWillUnmount inst)))))

(deftest component-sequence-output-renders-siblings-rf2-fzbj-30
  (is (= "<span>a</span><span>b</span>" (static-markup [seq-rows]))
      "keyed element siblings, with no wrapper element")
  (is (= "<div></div>" (static-markup [:div [seq-empty]]))
      "an empty sequence renders nothing (not an empty hiccup vector)")
  (is (= "<div>ab</div>" (static-markup [:div [seq-text]]))
      "text siblings stay text (not misread as an `<a>` tag with a child)")
  (is (= "<span>a</span><span>b</span>" (static-markup [(fn [] (fn [] (seq-rows)))]))
      "a Form-2 inner renderer returning the sequence")
  (is (= "<span>a</span><span>b</span>" (static-markup (seq-rows)))
      "a direct as-element of the sequence")
  (is (= ["a" "b"] (let [out (class-render-output [seq-rows])]
                     (when (array? out) (mapv #(.-key ^js %) out))))
      "the class render hands React an array of keyed children"))

(defn- with-warn-spy
  [calls f]
  (let [orig (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (f)
      (finally
        (set! (.-warn js/console) orig)))))

(deftest keyword-prop-on-interop-head-passes-through-silently
  (let [calls    (atom [])
        provider (.-Provider (react/createContext nil))
        el       (atom nil)]
    (with-warn-spy calls
      #(reset! el (template/as-element
                    [:> provider {:value :tenant/keyword-prop-probe} [:div "x"]])))
    (is (empty? @calls) (str "no console warning. Saw: " (pr-str @calls)))
    (is (keyword-identical? :tenant/keyword-prop-probe
                            (gobj/get (.-props ^js @el) "value"))
        "the keyword reaches React unchanged")))

(deftest as-element-primitives
  (doseq [[why x expected]
          [["nil → nil"           nil     nil]
           ["string → string"     "hello" "hello"]
           ["number → number"     42      42]
           ["bare keyword → name" :foo    "foo"]
           ["bare symbol → name"  'bar    "bar"]]]
    (testing why
      (is (= expected (template/as-element x))))))

(deftest as-element-shorthand-id-yields-to-prop
  (let [^js el (template/as-element [:div#a {:id "b"}])]
    (is (= "b" (-> el .-props .-id)))))

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
  (let [^js el (template/as-element [:div.outer [:span#inner.cls "hi"]])
        ^js child (-> el .-props .-children)]
    (is (= ["outer" "span" "cls" "inner"]
           [(-> el .-props .-className) (.-type child)
            (-> child .-props .-className) (-> child .-props .-id)]))))

(deftest as-element-fragment-with-ref
  (let [r      (react/createRef)
        ^js el (template/as-element [:<> {:ref r} [:div "a"]])]
    (is (identical? r (-> el .-props .-ref))))
  (let [f      (fn [_instance] nil)
        ^js el (template/as-element [:<> {:key "k" :ref f} [:div "a"]])]
    (is (= "k" (.-key el)))
    (is (identical? f (-> el .-props .-ref)))))

(deftest as-element-raw
  (let [Comp (fn FakeRaw [_props] nil)
        ^js el (template/as-element [:r> Comp #js {:already "shaped"}])]
    (is (= [Comp "shaped"] [(.-type el) (-> el .-props .-already)]))))

(deftest as-element-raw-key-does-not-mutate-caller-props-rf2-mdgt8t
  (let [Comp     (fn FakeRaw [_props] nil)
        js-props #js {:already "shaped"}
        ^js el   (template/as-element ^{:key "k"} [:r> Comp js-props])]
    (is (= "k" (.-key el)) "the React key is stamped on the element")
    (is (= "shaped" (-> el .-props .-already)) "props still flow through")
    (is (= 1 (.-length (js/Object.keys js-props)))
        "caller's js-props gained no extra own keys")))

(deftest as-element-interop-heads-are-keyword-sentinels-rf2-e7zxb
  (let [Comp (fn FakeComp [_props] nil)]
    (is (= Comp (.-type ^js (template/as-element [(keyword ">") Comp {:foo "bar"}])))
        "a reconstructed :> still reaches interop-element")
    (is (= (.-Fragment react)
           (.-type ^js (template/as-element [(keyword "<>") [:div "a"]])))
        "a reconstructed :<> still reaches fragment-element")
    (is (= Comp (.-type ^js (template/as-element [(keyword "r>") Comp #js {}])))
        "a reconstructed :r> still reaches raw-element")
    (is (fn? (.-type ^js (template/as-element [(keyword "f>") (fn [] [:div])])))
        "a reconstructed :f> still reaches function-element"))

  (is (= ">" (.-type ^js (template/as-element [">" "x"])))
      "the string \">\" is a custom-element tag, not the :> head")
  (is (= "<>" (.-type ^js (template/as-element ["<>" "x"])))
      "the string \"<>\" is a tag, not the :<> head")
  (is (= ">" (.-type ^js (template/as-element [(symbol ">") "x"])))
      "the symbol '> is a tag, not the :> head"))

(deftest props-slot-rule-rf2-e7zxb
  (let [^js el (template/as-element [:div nil "only-child"])]
    (is (= ["div" "only-child"] [(.-type el) (-> el .-props .-children)])))
  (let [^js el (template/as-element [:div "first" "second"])]
    (is (= 2 (.-length (-> el .-props .-children))))))

(deftest as-element-function-component-is-real-fn-component-rf2-bf4uw2
  (let [some-fn (fn [_n] [:div])
        ^js el (template/as-element [:f> some-fn 42])]

    (let [^js el2 (template/as-element [:f> some-fn 7])]
      (is (identical? (.-type el) (.-type el2))
          "wrapper cached per fn for stable reconciliation"))))

(deftest as-element-function-component-hooks-render-rf2-bf4uw2
  (let [hooked (fn hooked-view [start]
                 (let [state (react/useState start)
                       n     (aget state 0)]
                   [:span "hooked:" n]))
        markup (rds/renderToStaticMarkup
                 (template/as-element [:f> hooked 41]))]
    (is (= "<span>hooked:41</span>" markup)
        "useState ran in a valid function-component context; state seeded
         from the arg — no 'Invalid hook call' throw")))

(deftest as-element-native-tag-stringifies-keyword-and-symbol-attrs
  (doseq [[hiccup prop expected] [[[:button {:type :button}] "type" "button"]
                                 [[:input {:name 'q}] "name" "q"]]]
    (is (= expected (gobj/get (.-props ^js (template/as-element hiccup)) prop))
        (pr-str hiccup))))

(deftest as-element-interop-non-html-keyword-preserved-html-stringified
  (let [Comp   (fn FakeComp [_props] nil)
        ^js el (template/as-element [:> Comp {:role :button :kind :primary}])]
    (is (= ["button" :primary] [(-> el .-props .-role) (-> el .-props .-kind)]))))

(deftest as-element-style-css-var-alongside-normal-prop
  (let [^js el (template/as-element [:div {:style {:--accent "red" :font-size "12px"}}])
        style (.. el -props -style)]
    (is (= ["red" "12px"] [(aget style "--accent") (aget style "fontSize")]))))

(deftest as-element-seq-children-interior-nil-false
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
        "an interior false does not truncate")))

(deftest as-element-void-tag-children-warns-and-drops-rf2-mdgt8t
  (let [captured (atom nil)
        calls    (atom [])]
    (with-warn-spy calls
      #(do (reset! captured (template/as-element [:br "should-not-render"]))

           (template/as-element [:br])
           (template/as-element [:input {:type "text"}])
           (template/as-element [:img {:src "x.png"}])))
    (let [^js el @captured]
      (is (= "br" (.-type el)))
      (is (nil? (-> el .-props .-children)) "children dropped (lenient)"))
    (is (= 1 (count @calls)) "one dev warning fired for the dropped children")
    (is (re-find #"<br>" (first @calls)) "warning names the void tag")))

(deftest constructor-key-dropped-from-props-rf2-dwds9
  (let [^js el (template/as-element [:div {:constructor "leaked"}])
        props  (.-props el)]
    (is (not (.call (.. js/Object -prototype -hasOwnProperty)
                    props "constructor"))
        "no own 'constructor' slot on the props object")))

(deftest convert-prop-value-reserved-keys-dropped-rf2-dwds9
  (let [evil #js {:polluted "yes"}
        out (template/convert-prop-value
              {:__proto__ evil :constructor "y" :prototype "z"
               :legit "ok"})]
    (is (= "ok" (aget out "legit"))
        "legitimate keys flow through")
    (is (or (nil? (aget out "polluted"))
            (= js/undefined (aget out "polluted")))
        "evil prototype slot did NOT become reachable via aget")
    (doseq [k ["__proto__" "constructor" "prototype"]]
      (is (not (.call (.. js/Object -prototype -hasOwnProperty) out k))
          (str "reserved key '" k "' is not an own property")))))

;; Retain both an inherited hit and a cache entry that could shadow hasOwnProperty.
(def ^:private prototype-member-names ["toString" "hasOwnProperty"])

(deftest tag-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (doseq [n prototype-member-names]

    (let [^js first-el (template/as-element [n "x"])]
      (is (= n (.-type first-el))
          (str "head \"" n "\" renders as its own element on first sight")))

    (let [^js second-el (template/as-element [n "y"])]
      (is (= n (.-type second-el))
          (str "head \"" n "\" still renders as itself on the cached path")))))

(deftest prop-cache-inherited-name-cannot-falsely-hit-rf2-lhdp0
  (doseq [n prototype-member-names]
    (let [k (keyword n)]

      (is (= n (template/cached-prop-name k))
          (str ":" n " converts to its own name on first sight"))
      (let [^js el (template/as-element [:div {k "v"}])]
        (is (= "v" (gobj/get (.-props el) n))
            (str ":" n " reaches the props object under its own name"))))))

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
  [f]
  (let [^js el   (template/as-element [f])
        ^js inst (new (.-type el) (.-props el))]
    (try
      (.render inst)
      (.render inst)
      (finally
        (.componentWillUnmount inst)))))

(deftest expand-seq-warns-on-an-unkeyed-child-not-on-keyed-ones
  (let [calls (atom [])]
    (with-warn-spy calls
      #(template/as-element [:ul (list [:li "a"] [:li "b"] [:li "c"])]))
    (is (= 1 (count (missing-key-warnings @calls)))
        "three unkeyed children, one warning"))

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
        "the other component's own unkeyed sequence adds exactly one warning"))

  (let [calls (atom [])]
    (with-warn-spy calls
      #(template/as-element [:ul (list ^{:key "a"} [:li "a"]
                                       [:li {:key "b"} "b"])]))
    (is (empty? @calls) "keyed children raise no warning")))
