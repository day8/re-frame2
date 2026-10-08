(ns re-frame.reagent-codemod-contract-donor-cljs-test
  "The DONOR half of the `[:>]` migration codemod's contract
   (`docs/design/fresco/studio/reagent-codemod-against-the-landed-escape.md`).
   Every rewrite there is argued from a claim about what Reagent 2.0.1's
   `[:>]` conversion does; these tests pin those claims, so a Reagent upgrade
   that moves one goes red under the design section it breaks. The Fresco half
   is `conversion-parity-with-the-door-on-one-prop-corpus` in
   `implementation/fresco/test/re_frame/fresco/codec_cljs_test.cljs`, over the
   same corpus.

   Every assertion reads the React element `reagent.core/as-element` returns,
   never `reagent.impl.*`, so a pin moves only when observable conversion does."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.core :as r]))

(defn- a-foreign-component
  "Stands in for a library component; nothing renders."
  [_js-props]
  nil)

(defn- emitted-props [hiccup]
  (.-props (r/as-element hiccup)))

(defn- donor-prop
  "The value Reagent emitted at `slot` for a one-prop `[:>]` crossing."
  [k v slot]
  (aget (emitted-props [:> a-foreign-component {k v}]) slot))

;; Character for character the corpus
;; `conversion-parity-with-the-door-on-one-prop-corpus` runs.
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

;; §3's conversion table, donor column.
(deftest codemod-contract-donor-the-whole-corpus
  (let [plain-fn    (fn [_])
        ref-fn      (fn [_node])
        react-props (emitted-props [:> a-foreign-component (corpus plain-fn ref-fn)])]
    (is (= {"aria-label" "close" "count" 7 "data-kind" "row" "id" "greeting"
            "label" "due date" "menuItems" [{"day-of-week" 1}] "onThing" plain-fn
            "open" true "options" {"pageSize" 10} "plainFn" plain-fn "ref" ref-fn
            "role" "dialog" "theme" "dark" "variant" "compact"}
           (dissoc (js->clj react-props) "className"))
        "functions cross by identity, scalars verbatim, named values as `(name x)`, nested
         map keys respelled but not below a vector")
    ;; `:class` and `:className` both spell `className`. Which of the two
    ;; survives is `reduce-kv` order over a sixteen-entry map, so the pin is
    ;; that exactly one does.
    (is (contains? #{"btn on" "wide"} (aget react-props "className")))))

;; W1, §4.1: a metadata key is live at a `[:>]` crossing.
(deftest codemod-contract-donor-w1-the-metadata-key
  (is (= "k7" (.-key (r/as-element ^{:key "k7"} [:> a-foreign-component {:label "x"}]))))
  (is (= "7" (.-key (r/as-element ^{:key 7} [:> a-foreign-component {:label "x"}])))
      "React stringifies keys")
  (testing "a props `:key` is converted like any prop and consumed by React (§9.3)"
    (let [element (r/as-element [:> a-foreign-component {:key :foo :label "x"}])]
      (is (= "foo" (.-key element)))
      ;; `js/Object.keys`, not `.-key`: React 19 installs a DEV warning getter
      ;; at that name, and reading it logs.
      (is (= ["label"] (vec (js/Object.keys (.-props element)))))))
  (testing "where both are present the metadata wins (§5.2's :key-conflict)"
    (is (= "from-meta"
           (.-key (r/as-element ^{:key "from-meta"} [:> a-foreign-component {:key "from-props"}]))))))

;; W2, §4.2: nested map keys are respelled kebab to camel.
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
  (testing "the recursion stops at the first non-map collection"
    (is (= [{"day-of-week" 1}] (js->clj (donor-prop :menu-items [{:day-of-week 1}] "menuItems"))))
    (is (= [{"day-of-week" 1}] (js->clj (donor-prop :menu-items #{{:day-of-week 1}} "menuItems")))))
  (testing "and never descends into a #js node, which crosses as the same object"
    (let [js-options #js {:first-name "a"}]
      (is (identical? js-options (donor-prop :opts js-options "opts")))))
  (testing "a CSS custom property is mangled (§4.2's :css-var-repair)"
    (is (= {"BrandColor" "red" "fontSize" 12}
           (js->clj (donor-prop :style {:--brand-color "red" :font-size 12} "style"))))))

;; W3, §4.3: keyword and symbol values cross as `(name x)` at every slot.
(deftest codemod-contract-donor-w3-named-prop-values
  (is (= "my-sym" (donor-prop :variant 'my-sym "variant")))
  (is (= ["dark" "dark"]
         [(donor-prop :theme :theme/dark "theme") (donor-prop :theme :other/dark "theme")])
      "two distinct namespaced values collapse onto one string (:namespaced-named-value)")
  (is (= "my-ref" (donor-prop :ref :my-ref "ref"))
      "a string ref, which React 19 removed (§5.2's :named-ref)"))

;; W4, §4.4: a non-fn IFn reaches `convert-prop-value`'s `ifn?` arm and is wrapped.
(deftest codemod-contract-donor-w4-the-non-fn-ifn
  (let [partial-fn        (r/partial (fn [a b] [a b]) 1)
        converted-handler (donor-prop :on-pick partial-fn "onPick")]
    (is (not (identical? partial-fn converted-handler)) "the donor wrapped rather than passed")
    (is (= [1 2] (converted-handler 2)) "the wrapper is return-transparent")
    (is (not (identical? converted-handler (donor-prop :on-pick partial-fn "onPick")))
        "the wrapper is minted fresh on every conversion, so there was no stable identity to lose")))

;; §5.2/§5.3: `coll?` is asked before `ifn?`, so a carrier at an event slot is inert.
(deftest codemod-contract-donor-a-carrier-at-an-event-slot-is-inert
  (is (= ["boom"] (js->clj (donor-prop :on-click [:boom] "onClick"))))
  (is (= {"Enter" ["boom"]} (js->clj (donor-prop :on-key-down {"Enter" [:boom]} "onKeyDown")))))

;; W5, §4.5: an adapt-react-class head and `[:> C …]` take one path.
(deftest codemod-contract-donor-w5-adapt-react-class-is-the-same-path
  (let [props     (corpus (fn [_]) (fn [_node]))
        via-adapt (r/as-element [(r/adapt-react-class a-foreign-component) props])
        via-arrow (r/as-element [:> a-foreign-component props])]
    (is (= [a-foreign-component a-foreign-component] [(.-type via-adapt) (.-type via-arrow)]))
    (is (= (js->clj (.-props via-arrow)) (js->clj (.-props via-adapt))))))

;; W6, §4.6: the string tag head.
(deftest codemod-contract-donor-w6-the-string-tag-head
  (testing "\"input\" and \"textarea\" take the controlled-input wrapper"
    (is (not= "input" (.-type (r/as-element [:> "input" {:value "x"}]))))
    (is (not= "textarea" (.-type (r/as-element [:> "textarea" {:value "x"}])))))
  (is (= ["div" "div#id"] (mapv #(.-type (r/as-element [:> % {}])) ["div" "div#id"]))
      "any other string reaches React whole, id/class shorthand unparsed (:string-tag-unparseable)"))

;; §5.3's :dangerous-html blocker.
(deftest codemod-contract-donor-dangerously-set-inner-html-is-deleted
  (is (= ["label"] (vec (js/Object.keys (emitted-props [:> a-foreign-component
                                                         {:dangerouslySetInnerHTML {:__html "<b>x</b>"}
                                                          :label "x"}]))))
      "dropped silently unless the value is an `UnsafeHTML`")
  (is (= "<b>x</b>"
         (.-__html (donor-prop :dangerouslySetInnerHTML (r/unsafe-html "<b>x</b>") "dangerouslySetInnerHTML")))))

;; §5.2's :amp-key refusal: the donor emitted a prop literally named "&".
(deftest codemod-contract-donor-the-ampersand-key-is-just-a-prop
  (is (= {"&" {"a" 1} "label" "x"}
         (js->clj (emitted-props [:> a-foreign-component {:& {:a 1} :label "x"}])))))

;; §3's class row and §5.4: only the literal `:class` key is coerced.
(deftest codemod-contract-donor-the-class-slot-reads-one-literal-key
  (is (= ["btn on" "dark"]
         [(donor-prop :class ["btn" nil :on] "className") (donor-prop :class :theme/dark "className")]))
  (is (array? (donor-prop :className ["a" "b"] "className"))
      "a collection under any other spelling was never coerced")
  (is (array? (donor-prop :x/class ["a" "b"] "className"))
      "a namespaced spelling still lands on className, uncoerced"))
