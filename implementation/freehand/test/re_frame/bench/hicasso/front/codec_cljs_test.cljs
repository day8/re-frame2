(ns re-frame.bench.hicasso.front.codec-cljs-test
  "THE HICCUP CODEC, tested at the element (rf2-2rtt6.8).

  Every assertion here reads a React element's `type`, `props` and `key`
  rather than rendered DOM. That is the right altitude for a codec and it
  is also the cheap one: the question this file answers is *what did the
  codec build*, and a mounted tree would answer it through two more
  layers that have their own opinions. What React then does with these
  elements is the arms' browser witness set, not this file's."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            ["react" :as react]))

(use-fixtures :each {:before (fn [] (codec/reset-caches!))})

;; ---------------------------------------------------------------------------
;; Reading an element
;; ---------------------------------------------------------------------------

(defn- el-type [e] (.-type e))
(defn- el-key [e] (.-key e))
(defn- prop [e k] (aget (.-props e) k))
(defn- prop-names [e] (vec (sort (js/Object.keys (.-props e)))))
(defn- children [e] (prop e "children"))
(defn- child-vec [e] (let [c (children e)] (if (array? c) (vec c) [c])))

;; ---------------------------------------------------------------------------
;; Tags
;; ---------------------------------------------------------------------------

(deftest a-bare-tag-becomes-an-element-of-that-type
  (let [e (codec/as-element [:div])]
    (is (= "div" (el-type e)))
    (is (= [] (prop-names e)))))

(deftest the-id-and-class-shorthand-is-parsed-and-folded-into-the-props
  (testing "id alone"
    (is (= "main" (prop (codec/as-element [:div#main]) "id"))))
  (testing "classes alone, dot-separated"
    (is (= "wide tall" (prop (codec/as-element [:div.wide.tall]) "className"))))
  (testing "id then classes, in the one spelling the donor supports"
    (let [e (codec/as-element [:section#main.wide.tall])]
      (is (= "section" (el-type e)))
      (is (= "main" (prop e "id")))
      (is (= "wide tall" (prop e "className")))))
  (testing "an explicit :id wins over the shorthand"
    (is (= "explicit" (prop (codec/as-element [:div#short {:id "explicit"}]) "id"))))
  (testing "the shorthand class is prepended to an explicit one"
    (is (= "short explicit" (prop (codec/as-element [:div.short {:class "explicit"}]) "className"))))
  (testing "a collection class value joins, dropping nils"
    (is (= "a b" (prop (codec/as-element [:div {:class ["a" nil :b]}]) "className")))))

(deftest the-tag-cache-holds-one-entry-per-distinct-literal
  (is (= 0 (:tags (codec/cache-sizes))))
  (codec/as-element [:div.row])
  (codec/as-element [:div.row])
  (codec/as-element [:div.row])
  (is (= 1 (:tags (codec/cache-sizes))) "three renders of one literal, one entry")
  (codec/as-element [:span])
  (is (= 2 (:tags (codec/cache-sizes))))
  (testing "a cached parse produces the same element a fresh parse does"
    (is (= "row" (prop (codec/as-element [:div.row]) "className")))))

(deftest the-caches-refuse-the-prototype-poisoning-keys
  (testing "a tag named __proto__ parses without being cached"
    (is (= "__proto__" (el-type (codec/as-element [:__proto__]))))
    (is (= 0 (:tags (codec/cache-sizes)))))
  (testing "a prop named __proto__ is neither cached nor set"
    (let [e (codec/as-element [:div {:__proto__ "nope"}])]
      (is (= [] (prop-names e))))))

;; ---------------------------------------------------------------------------
;; Prop names
;; ---------------------------------------------------------------------------

(deftest prop-names-follow-the-donors-kebab-to-camel-rule
  (is (= "onClick" (codec/prop-name :on-click)))
  (is (= "tabIndex" (codec/prop-name :tab-index)))
  (is (= "className" (codec/cached-prop-name :class)))
  (is (= "htmlFor" (codec/cached-prop-name :for)))
  (is (= "charSet" (codec/cached-prop-name :charset)))
  (testing "aria and data are HTML attribute names in React too"
    (is (= "aria-label" (codec/prop-name :aria-label)))
    (is (= "data-index" (codec/prop-name :data-index))))
  (testing "a CSS custom property is preserved verbatim"
    (is (= "--gap" (codec/prop-name :--gap))))
  (testing "the three seeded entries are the rule, not a memo of one"
    (is (= 3 (:props (codec/cache-sizes))))
    (codec/cached-prop-name :on-click)
    (codec/cached-prop-name :on-click)
    (is (= 4 (:props (codec/cache-sizes))))))

(deftest prop-values-are-converted-in-the-shapes-react-wants
  (testing "a style map becomes a JS object with camelCased keys"
    (let [style (prop (codec/as-element [:div {:style {:font-size "12px" :color :red}}]) "style")]
      (is (= "12px" (aget style "fontSize")))
      (is (= "red" (aget style "color")) "a keyword value stringifies")))
  (testing "a CSS custom property survives inside style"
    (let [style (prop (codec/as-element [:div {:style {:--gap "4px"}}]) "style")]
      (is (= "4px" (aget style "--gap")))))
  (testing "a function passes through BY IDENTITY, so downstream bail-outs still work"
    (let [f (fn [_])]
      (is (identical? f (prop (codec/as-element [:div {:on-click f}]) "onClick")))))
  (testing "a ref is an ordinary prop — callback refs are legal on native tags"
    (let [r (fn [_node])]
      (is (identical? r (prop (codec/as-element [:div {:ref r}]) "ref")))))
  (testing "scalars are left alone"
    (let [e (codec/as-element [:input {:value "x" :disabled true :tab-index 3}])]
      (is (= "x" (prop e "value")))
      (is (= true (prop e "disabled")))
      (is (= 3 (prop e "tabIndex"))))))

;; ---------------------------------------------------------------------------
;; Keys — `:key` in the props position, never metadata (HD-016)
;; ---------------------------------------------------------------------------

(deftest a-native-elements-key-is-key-in-the-props-map-and-is-not-an-attribute
  (let [e (codec/as-element [:li {:key 7 :class "row"}])]
    (is (= "7" (el-key e)) "React stringifies keys")
    (is (= ["className"] (prop-names e)) ":key never reaches the DOM props")))

;; ---------------------------------------------------------------------------
;; Children
;; ---------------------------------------------------------------------------

(deftest children-are-realized-once-and-flattened-one-level
  (testing "one child arrives as the single child, not an array"
    (let [e (codec/as-element [:p "text"])]
      (is (= "text" (children e)))))
  (testing "several children arrive in order"
    (let [e (codec/as-element [:p "a" "b" "c"])]
      (is (= ["a" "b" "c"] (child-vec e)))))
  (testing "a seq is expanded, and an interior nil does NOT truncate it"
    (let [e (codec/as-element [:ul (for [i (range 4)] (when (odd? i) [:li {:key i} i]))])
          expanded (vec (children e))]
      (is (= 4 (count expanded)) "all four slots survive the single pass")
      (is (nil? (nth expanded 0)))
      (is (= "li" (el-type (nth expanded 1))))
      (is (nil? (nth expanded 2)))
      (is (= "li" (el-type (nth expanded 3))))))
  (testing "nil and false render nothing"
    (is (nil? (codec/as-element nil)))
    (is (nil? (codec/as-element false)))
    (is (= [nil nil "x"] (child-vec (codec/as-element [:p nil false "x"])))))
  (testing "true is an error"
    (is (thrown-with-msg? js/Error #"not a renderable child" (codec/as-element true)))
    (try
      (codec/as-element true)
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-true-child (:rf.error/id (ex-data e)))))))
  (testing "an existing React element is a legal child anywhere"
    (let [foreign (react/createElement "b" nil "bold")
          e       (codec/as-element [:p foreign])]
      (is (identical? foreign (children e))))))

(deftest a-map-in-the-first-slot-is-props-and-anything-else-is-a-child
  (testing "a map is props"
    (is (= "row" (prop (codec/as-element [:div {:class "row"}]) "className"))))
  (testing "a string in the first slot is a child, not props"
    (is (= "hello" (children (codec/as-element [:div "hello"])))))
  (testing "a vector in the first slot is a child"
    (is (= "b" (el-type (children (codec/as-element [:div [:b]])))))))

;; ---------------------------------------------------------------------------
;; Fragments
;; ---------------------------------------------------------------------------

(deftest the-fragment-spelling-is-a-fragment
  (let [e (codec/as-element [:<> [:li "a"] [:li "b"]])]
    (is (identical? (.-Fragment react) (el-type e)))
    (is (= 2 (count (child-vec e)))))
  (testing "a keyed fragment carries its key"
    (is (= "k" (el-key (codec/as-element [:<> {:key "k"} [:li "a"]]))))))

;; ---------------------------------------------------------------------------
;; Boundaries — the HD-016 head rules
;; ---------------------------------------------------------------------------

(defn- a-view
  "Stands in for a `defview` product: an arm mints the function component
  once, at definition, and marks it. The codec never wraps it, which is
  why HD-004's cached-component-head has nothing to do here."
  [_js-props]
  nil)

(codec/mark-boundary! a-view)

(deftest a-marked-view-in-head-position-is-a-boundary-child
  (let [e (codec/as-element [a-view {:id 7}])]
    (is (identical? a-view (el-type e)))
    (is (= {:id 7} (prop e "rfProps")) "the body receives one CLJS props map")))

(deftest a-boundarys-key-is-extracted-before-the-body-sees-props
  (let [e (codec/as-element [a-view {:key 3 :id 7}])]
    (is (= "3" (el-key e)))
    (is (= {:id 7} (prop e "rfProps")) ":key is React's contract, not the body's")))

(deftest a-boundarys-children-arrive-as-a-realized-vector-of-hiccup
  (testing "trailing forms become (:children props)"
    (let [e (codec/as-element [a-view {:id 7} [:li "a"] [:li "b"]])]
      (is (= {:id 7 :children [[:li "a"] [:li "b"]]} (prop e "rfProps")))))
  (testing "a seq child is realized once and flattened exactly one level"
    (let [e (codec/as-element [a-view {} (for [i (range 2)] [:li i])])]
      (is (= [[:li 0] [:li 1]] (:children (prop e "rfProps"))))))
  (testing "with no trailing forms there is no :children key at all"
    (is (= {:id 7} (prop (codec/as-element [a-view {:id 7}]) "rfProps"))))
  (testing "a boundary with no props map still works"
    (is (= {} (prop (codec/as-element [a-view]) "rfProps")))))

(deftest a-plain-function-in-head-position-is-a-loud-error
  (let [helper (fn [_] [:div])]
    (is (false? (codec/boundary-head? helper)))
    (is (thrown-with-msg? js/Error #"never a silent" (codec/as-element [helper {}])))
    (try
      (codec/as-element [helper {}])
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-bad-head (:rf.error/id (ex-data e))))
        (is (= :call-it-or-make-it-a-view (:recovery (ex-data e))))))))

(deftest an-empty-hiccup-vector-and-a-nonsense-head-are-loud-errors
  (is (thrown-with-msg? js/Error #"Empty hiccup vector" (codec/as-element [])))
  (is (thrown-with-msg? js/Error #"not a valid element head" (codec/as-element [42 {}]))))

;; ---------------------------------------------------------------------------
;; The codec's one policy call — intent lowering happens inside the walk
;; ---------------------------------------------------------------------------

(deftest event-vectors-in-attributes-lower-during-prop-conversion
  (let [!seen (atom [])
        e     (intent/with-frame (fn [ev] (swap! !seen conj ev))
                                 (fn [] (codec/as-element
                                         [:button {:on-click [:todo/toggle 7] :class "btn"}
                                          "done"])))]
    (is (= "btn" (prop e "className")))
    (is (fn? (prop e "onClick")))
    (is (= [] @!seen))
    ((prop e "onClick") #js {:target #js {}})
    (is (= [[:todo/toggle 7]] @!seen))))

(deftest a-controlled-field-lowers-its-marker-through-the-codec
  (let [!seen (atom [])
        e     (intent/with-frame (fn [ev] (swap! !seen conj ev))
                                 (fn [] (codec/as-element
                                         [:input {:value "milk"
                                                  :on-input [:todo.ui/edit 7 :re-frame.hicasso/value]}])))]
    (is (= "milk" (prop e "value")))
    ((prop e "onInput") #js {:target #js {:value "bread"}})
    (is (= [[:todo.ui/edit 7 "bread"]] @!seen))))

;; ---------------------------------------------------------------------------
;; What the codec must NOT be
;; ---------------------------------------------------------------------------

(deftest the-codec-mints-a-fresh-props-object-per-element-and-memoizes-no-element
  (testing "two renders of the same hiccup are two elements with two props objects"
    (let [hiccup [:div {:class "row"} "x"]
          a      (codec/as-element hiccup)
          b      (codec/as-element hiccup)]
      (is (not (identical? a b)) "no element memoization — HD-006 stands")
      (is (not (identical? (.-props a) (.-props b))) "no props-object cache")
      (is (= (prop-names a) (prop-names b)))))
  (testing "only the two codec-work caches grow — tags and prop names (HD-004)"
    (codec/reset-caches!)
    (dotimes [i 20] (codec/as-element [:div.row {:class (str "c" i)} i]))
    (is (= {:tags 1 :props 3} (codec/cache-sizes))
        "one tag literal; `:class` is one of the three seeded prop names, so
         twenty renders with twenty distinct class VALUES add nothing")))
