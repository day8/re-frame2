(ns re-frame.reagent-codemod-contract-donor-cljs-test
  "Observable Reagent conversions required by docs/design/fresco/studio/reagent-codemod-against-the-landed-escape.md."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.core :as r]))

(defn- a-foreign-component
  [_js-props]
  nil)

(defn- emitted-props [hiccup]
  (.-props (r/as-element hiccup)))

(defn- donor-prop
  [k v slot]
  (aget (emitted-props [:> a-foreign-component {k v}]) slot))

(defn- corpus [plain-fn ref-fn]
  {:on-thing   plain-fn
   :plain-fn   plain-fn
   :menu-items [{:day-of-week 1}]
   :options    {:pageSize 10}
   :variant    :compact
   :theme      :theme/dark
   :class      ["btn" nil :on]
   :className  "wide"
   :aria-label :close
   :data-kind  :row
   :id         :greeting
   :role       :dialog
   :label      "due date"
   :count      7
   :open       true
   :ref        ref-fn})

(deftest codemod-contract-donor-the-whole-corpus
  (let [plain-fn    (fn [_])
        ref-fn      (fn [_node])
        react-props (emitted-props [:> a-foreign-component (corpus plain-fn ref-fn)])]
    (is (= [{"aria-label" "close" "count" 7 "data-kind" "row" "id" "greeting"
             "label" "due date" "menuItems" [{"day-of-week" 1}] "onThing" plain-fn
             "open" true "options" {"pageSize" 10} "plainFn" plain-fn "ref" ref-fn
             "role" "dialog" "theme" "dark" "variant" "compact"}
            true]
           [(dissoc (js->clj react-props) "className")
            (contains? #{"btn on" "wide"} (aget react-props "className"))]))))

(deftest codemod-contract-donor-w1-the-metadata-key
  (is (= ["k7" "7"]
         [(.-key (r/as-element ^{:key "k7"} [:> a-foreign-component {:label "x"}]))
          (.-key (r/as-element ^{:key 7} [:> a-foreign-component {:label "x"}]))]))
  (testing "a props `:key` is converted like any prop and consumed by React (§9.3)"
    (let [element (r/as-element [:> a-foreign-component {:key :foo :label "x"}])]
      (is (= ["foo" ["label"]]
             [(.-key element)
              (vec (js/Object.keys (.-props element)))]))))
  (testing "where both are present the metadata wins (§5.2's :key-conflict)"
    (is (= "from-meta"
           (.-key (r/as-element ^{:key "from-meta"} [:> a-foreign-component {:key "from-props"}]))))))

(deftest codemod-contract-donor-w2-nested-map-keys
  (is (= {"firstName" "a" "pageSize" 10}
         (js->clj (donor-prop :options {:page-size 10 :first-name "a"} "options"))))
  (testing "the three seeded renames apply inside a nested map too"
    (is (= ["charSet" "className" "htmlFor"]
           (vec (sort (js/Object.keys (donor-prop :cfg {:class "c" :for "f" :charset "utf-8"} "cfg")))))))
  (testing "`aria` and `data` are exempt"
    (is (= ["aria-label" "data-kind" "otherKey"]
           (vec (sort (js/Object.keys (donor-prop :cfg {:aria-label "x" :data-kind "y" :other-key "z"} "cfg")))))))
  (testing "a STRING key is verbatim"
    (is (= ["first-name"] (vec (js/Object.keys (donor-prop :cfg {"first-name" 1} "cfg"))))))
  (testing "the recursion never descends into a #js node, which crosses as the same object"
    (let [js-options #js {:first-name "a"}]
      (is (identical? js-options (donor-prop :opts js-options "opts")))))
  (testing "a CSS custom property is mangled (§4.2's :css-var-repair)"
    (is (= {"BrandColor" "red" "fontSize" 12}
           (js->clj (donor-prop :style {:--brand-color "red" :font-size 12} "style"))))))

(deftest codemod-contract-donor-w3-named-prop-values
  (is (= ["my-sym" ["dark" "dark"] "my-ref"]
         [(donor-prop :variant 'my-sym "variant")
          [(donor-prop :theme :theme/dark "theme") (donor-prop :theme :other/dark "theme")]
          (donor-prop :ref :my-ref "ref")])))

(deftest codemod-contract-donor-w4-the-non-fn-ifn
  (let [partial-fn        (r/partial (fn [a b] [a b]) 1)
        converted-handler (donor-prop :on-pick partial-fn "onPick")]
    (is (= [true [1 2] true]
           [(not (identical? partial-fn converted-handler))
            (converted-handler 2)
            (not (identical? converted-handler (donor-prop :on-pick partial-fn "onPick")))]))))

(deftest codemod-contract-donor-a-carrier-at-an-event-slot-is-inert
  (is (= [["boom"] {"Enter" ["boom"]}]
         [(js->clj (donor-prop :on-click [:boom] "onClick"))
          (js->clj (donor-prop :on-key-down {"Enter" [:boom]} "onKeyDown"))])))

(deftest codemod-contract-donor-w5-adapt-react-class-is-the-same-path
  (let [props     (corpus (fn [_]) (fn [_node]))
        via-adapt (r/as-element [(r/adapt-react-class a-foreign-component) props])
        via-arrow (r/as-element [:> a-foreign-component props])]
    (is (= [[a-foreign-component a-foreign-component] (js->clj (.-props via-arrow))]
           [[(.-type via-adapt) (.-type via-arrow)]
            (js->clj (.-props via-adapt))]))))

(deftest codemod-contract-donor-w6-the-string-tag-head
  (testing "\"input\" and \"textarea\" take the controlled-input wrapper"
    (is (= [true true]
           [(not= "input" (.-type (r/as-element [:> "input" {:value "x"}])))
            (not= "textarea" (.-type (r/as-element [:> "textarea" {:value "x"}])))])))
  (is (= ["div" "div#id"] (mapv #(.-type (r/as-element [:> % {}])) ["div" "div#id"]))))

(deftest codemod-contract-donor-dangerously-set-inner-html-is-deleted
  (let [props (emitted-props [:> a-foreign-component
                             {:dangerouslySetInnerHTML {:__html "<b>x</b>"}
                              :label "x"}])
        safe (donor-prop :dangerouslySetInnerHTML (r/unsafe-html "<b>x</b>")
                         "dangerouslySetInnerHTML")]
    (is (= [["label"] "<b>x</b>"]
           [(vec (js/Object.keys props)) (.-__html safe)]))))

(deftest codemod-contract-donor-the-ampersand-key-is-just-a-prop
  (is (= {"&" {"a" 1} "label" "x"}
         (js->clj (emitted-props [:> a-foreign-component {:& {:a 1} :label "x"}])))))

(deftest codemod-contract-donor-the-class-slot-reads-one-literal-key
  (is (= [["btn on" "dark"] true true]
         [[(donor-prop :class ["btn" nil :on] "className") (donor-prop :class :theme/dark "className")]
          (array? (donor-prop :className ["a" "b"] "className"))
          (array? (donor-prop :x/class ["a" "b"] "className"))])))
