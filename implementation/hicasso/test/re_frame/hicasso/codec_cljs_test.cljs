(ns re-frame.hicasso.codec-cljs-test
  "THE HICCUP CODEC, tested at the element.

  Every assertion here reads a React element's `type`, `props` and `key`
  rather than rendered DOM. That is the right altitude for a codec and it
  is also the cheap one: the question this file answers is *what did the
  codec build*, and a mounted tree would answer it through two more
  layers that have their own opinions. What React then does with these
  elements is the arms' browser witness set, not this file's."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.hicasso.impl.slot :as rf.hicasso.impl.slot]
            [re-frame.hicasso.slot-corpus :as rf.hicasso.slot-corpus]
            ["react" :as react]))

(use-fixtures :each {:before (fn [] (rf.hicasso.impl.codec/reset-caches!))})

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
  (let [e (rf.hicasso.impl.codec/as-element [:div])]
    (is (= "div" (el-type e)))
    (is (= [] (prop-names e)))))

(deftest the-id-and-class-shorthand-is-parsed-and-folded-into-the-props
  (testing "id alone"
    (is (= "main" (prop (rf.hicasso.impl.codec/as-element [:div#main]) "id"))))
  (testing "classes alone, dot-separated"
    (is (= "wide tall" (prop (rf.hicasso.impl.codec/as-element [:div.wide.tall]) "className"))))
  (testing "id then classes, in the one spelling the donor supports"
    (let [e (rf.hicasso.impl.codec/as-element [:section#main.wide.tall])]
      (is (= "section" (el-type e)))
      (is (= "main" (prop e "id")))
      (is (= "wide tall" (prop e "className")))))
  (testing "an explicit :id wins over the shorthand"
    (is (= "explicit" (prop (rf.hicasso.impl.codec/as-element [:div#short {:id "explicit"}]) "id"))))
  (testing "the shorthand class is prepended to an explicit one"
    (is (= "short explicit" (prop (rf.hicasso.impl.codec/as-element [:div.short {:class "explicit"}]) "className"))))
  (testing "a collection class value joins, dropping nils"
    (is (= "a b" (prop (rf.hicasso.impl.codec/as-element [:div {:class ["a" nil :b]}]) "className")))))

(deftest the-tag-cache-holds-one-entry-per-distinct-literal
  (is (= 0 (:tags (rf.hicasso.impl.codec/cache-sizes))))
  (rf.hicasso.impl.codec/as-element [:div.row])
  (rf.hicasso.impl.codec/as-element [:div.row])
  (rf.hicasso.impl.codec/as-element [:div.row])
  (is (= 1 (:tags (rf.hicasso.impl.codec/cache-sizes))) "three renders of one literal, one entry")
  (rf.hicasso.impl.codec/as-element [:span])
  (is (= 2 (:tags (rf.hicasso.impl.codec/cache-sizes))))
  (testing "a cached parse produces the same element a fresh parse does"
    (is (= "row" (prop (rf.hicasso.impl.codec/as-element [:div.row]) "className")))))

(deftest the-caches-refuse-the-prototype-poisoning-keys
  (testing "a tag named __proto__ parses without being cached"
    (is (= "__proto__" (el-type (rf.hicasso.impl.codec/as-element [:__proto__]))))
    (is (= 0 (:tags (rf.hicasso.impl.codec/cache-sizes)))))
  (testing "a prop named __proto__ is neither cached nor set"
    (let [e (rf.hicasso.impl.codec/as-element [:div {:__proto__ "nope"}])]
      (is (= [] (prop-names e))))
    (is (= 3 (:props (rf.hicasso.impl.codec/cache-sizes)))
        "the three seeded entries and nothing else — the refusal is on the write")))

(deftest a-literal-named-after-an-inherited-property-cannot-be-served-one
  ;; Both caches are `Object.create(null)`, so a lookup can
  ;; only ever answer an own property. Keyed against `#js {}` these
  ;; literals would read `Object.prototype`'s OWN members — a function
  ;; where a ParsedTag or a PropSlot belongs — and the codec would emit
  ;; whatever that function's `.tag` / `.js-name` happened to be, which
  ;; is `undefined`. The lookup guard the shipping code used to carry
  ;; (`hasOwnProperty.call`) answered this; nothing but the cache's own
  ;; construction answers it now, so it is witnessed rather than assumed.
  (testing "a TAG named after an Object.prototype member parses as itself"
    (doseq [n ["toString" "valueOf" "hasOwnProperty" "isPrototypeOf" "constructor"]]
      (is (= n (el-type (rf.hicasso.impl.codec/as-element [(keyword n)])))
          (str "tag " n " must render as itself"))))
  (testing "and the second render of one reads its own cached entry"
    (rf.hicasso.impl.codec/reset-caches!)
    (rf.hicasso.impl.codec/as-element [:toString])
    (is (= 1 (:tags (rf.hicasso.impl.codec/cache-sizes))))
    (is (= "toString" (el-type (rf.hicasso.impl.codec/as-element [:toString]))))
    (is (= 1 (:tags (rf.hicasso.impl.codec/cache-sizes)))))
  (testing "a PROP named after an Object.prototype member converts as itself"
    (rf.hicasso.impl.codec/reset-caches!)
    (let [e (rf.hicasso.impl.codec/as-element [:div {:toString "a" :valueOf "b" :hasOwnProperty "c"}])]
      (is (= ["hasOwnProperty" "toString" "valueOf"] (prop-names e)))
      (is (= "a" (prop e "toString")))
      (is (= "b" (prop e "valueOf")))
      (is (= "c" (prop e "hasOwnProperty"))))
    (testing "and the second element reads the cached slots"
      (is (= "d" (prop (rf.hicasso.impl.codec/as-element [:div {:toString "d"}]) "toString"))))))

;; ---------------------------------------------------------------------------
;; Prop names
;; ---------------------------------------------------------------------------

(deftest prop-names-follow-the-donors-kebab-to-camel-rule
  (is (= "onClick" (rf.hicasso.impl.slot/prop-name :on-click)))
  (is (= "tabIndex" (rf.hicasso.impl.slot/prop-name :tab-index)))
  (is (= "className" (rf.hicasso.impl.codec/cached-prop-name :class)))
  (is (= "htmlFor" (rf.hicasso.impl.codec/cached-prop-name :for)))
  (is (= "charSet" (rf.hicasso.impl.codec/cached-prop-name :charset)))
  (testing "aria and data are HTML attribute names in React too"
    (is (= "aria-label" (rf.hicasso.impl.slot/prop-name :aria-label)))
    (is (= "data-index" (rf.hicasso.impl.slot/prop-name :data-index))))
  (testing "a CSS custom property is preserved verbatim"
    (is (= "--gap" (rf.hicasso.impl.slot/prop-name :--gap))))
  (testing "the three seeded entries are the rule, not a memo of one"
    (is (= 3 (:props (rf.hicasso.impl.codec/cache-sizes))))
    (rf.hicasso.impl.codec/cached-prop-name :on-click)
    (rf.hicasso.impl.codec/cached-prop-name :on-click)
    (is (= 4 (:props (rf.hicasso.impl.codec/cache-sizes))))))

(deftest the-codecs-cached-slot-is-the-shared-rules-answer
  (testing "THE OTHER HALF OF THE EQUIVALENCE PIN (rf2-ani6y). The rule
            lives in `.cljc` so the JVM codemod and this runtime share one
            implementation, and `slot-cljs-test` runs its corpus on both
            hosts. That pins the RULE. What it cannot see is this codec
            putting something else in front of the rule — a
            hand-written seed in `prop-cache`, a cache keyed so two
            spellings share one entry, a re-introduced local copy — each
            of which would answer a slot the codemod would never predict.
            So the codec's own doors are asked the same corpus: whatever
            the rule answers, `cached-prop-name` and `canonical-slot`
            answer, for every spelling the codec accepts."
    (doseq [[k expected] rf.hicasso.slot-corpus/corpus]
      (is (= expected (rf.hicasso.impl.codec/cached-prop-name k) (rf.hicasso.impl.codec/canonical-slot k))
          (str "codec slot for " (pr-str k)))))
  (testing "and again warm, because the first ask is the one that writes
            the cache and the second is the one that reads it"
    (doseq [[k expected] rf.hicasso.slot-corpus/corpus]
      (is (= expected (rf.hicasso.impl.codec/cached-prop-name k))
          (str "warm codec slot for " (pr-str k))))))

(deftest the-slot-is-a-pure-function-of-the-key-and-a-string-cannot-poison-it
  (testing "a string prop name is already a React name and is taken verbatim,
            so `\"on-input\"` and `:on-input` are DIFFERENT slots. They share
            a cache entry if the cache is keyed by name alone, and then the
            first string spelling rendered anywhere answers every later
            keyword — emitting every handler written the taught way into a
            slot React ignores, and making the canonical slot depend on what
            the page happened to render first."
    (is (= "on-input" (rf.hicasso.impl.codec/cached-prop-name "on-input")))
    (is (= "onInput" (rf.hicasso.impl.codec/cached-prop-name :on-input))
        "the keyword still camelCases, whatever was asked before it")
    (is (= "on-input" (rf.hicasso.impl.codec/cached-prop-name "on-input"))
        "and the string is still itself, whatever was asked before IT"))
  (testing "the other way round, from a cold cache"
    (rf.hicasso.impl.codec/reset-caches!)
    (is (= "onInput" (rf.hicasso.impl.codec/cached-prop-name :on-input)))
    (is (= "on-input" (rf.hicasso.impl.codec/cached-prop-name "on-input"))))
  (testing "the three React renames are the rule, so they hold for every
            spelling of the one attribute"
    (doseq [k [:class :className "class" :x/class 'class]]
      (is (= "className" (rf.hicasso.impl.codec/canonical-slot k)) (str "spelled " (pr-str k))))))

(deftest the-cached-classification-is-spelling-aware-and-no-spelling-poisons-another
  ;; The prop cache's entries carry the POSITION CLASSIFICATION
  ;; beside the React name, and the entry is keyed by name — so a symbol
  ;; spelled `on-click` shares the keyword's entry while `event-prop?`
  ;; answers false for it. The flag must therefore be minted from the NAME
  ;; and applied spelling-aware at the call site; either shortcut turns
  ;; whichever spelling renders first into the answer for both, which is
  ;; exactly the order dependence the string/keyword law above exists to
  ;; remove. Both directions, both from a cold cache.
  (testing "a symbol minted FIRST does not cost the keyword its event lowering"
    (rf.hicasso.impl.codec/reset-caches!)
    (rf.hicasso.impl.codec/cached-prop-name 'on-click)
    (let [!seen (atom [])
          e     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                   (fn [] (rf.hicasso.impl.codec/as-element [:button {:on-click [:go]}])))]
      (is (fn? (prop e "onClick")) "the keyword position still lowers to a handler")
      ((prop e "onClick") #js {:target #js {}})
      (is (= [[:go]] @!seen))))
  (testing "a keyword minted FIRST does not make the symbol an event position"
    (rf.hicasso.impl.codec/reset-caches!)
    (rf.hicasso.impl.codec/cached-prop-name :on-click)
    (let [e (rf.hicasso.impl.codec/as-element [:div {'on-click [:go]}])]
      (is (array? (prop e "onClick"))
          "a symbol key is never an event position (event-prop? says so), so
           the vector is a plain prop value and converts through clj->js —
           lowering it here would also have thrown, there being no ambient
           frame in this test"))))

(deftest the-shorthand-composes-with-a-class-however-it-is-spelled
  ;; THE SHORTHAND IS FOLDED ONTO THE EMITTED OBJECT, where there is no
  ;; spelling left to miss, so every spelling this codec accepts composes
  ;; with it.
  ;;
  ;; Folding it over the MAP instead is what the rows below forbid: the
  ;; merge then reads `:class` and `:className` off the map by raw key,
  ;; sees three of the spellings and silently drops the rest, so an
  ;; exotic spelling that canonicalises onto the same slot loses outright
  ;; and `[:div.a {:x/class "b"}]` pins at "a".
  (testing "a namespaced class spelling composes with the shorthand rather
            than losing to it"
    (is (= "a b" (prop (rf.hicasso.impl.codec/as-element [:div.a {:x/class "b"}]) "className"))))
  (testing "and so does every other spelling the codec accepts"
    (doseq [k [:class :className "class" "className" 'class :x/class :class-name]]
      (is (= "a b" (prop (rf.hicasso.impl.codec/as-element [:div.a {k "b"}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "the class value is coerced at the slot, so a collection joins
            whatever key carried it — the coercion used to live in the map
            surgery, where only `:class` and `:className` reached it"
    (doseq [k [:class "className" :x/class]]
      (is (= "a x y" (prop (rf.hicasso.impl.codec/as-element [:div.a {k ["x" nil :y]}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "two spellings of the one slot COMPOSE rather than the last write
            silently winning — a dropped class is the failure class the
            ruling exists to delete"
    (is (= "a b c" (prop (rf.hicasso.impl.codec/as-element [:div.a {:class "b" :x/class "c"}]) "className")))))

(deftest the-shorthand-id-loses-to-an-explicit-one-however-it-is-spelled
  ;; The other half of the spelling-aware rule. `#tag` is the weakest
  ;; source of an id there is, and stating "an explicit :id wins" on the
  ;; RAW KEY is what these rows forbid: `[:div#tag {"id" "explicit"}]`
  ;; then keeps BOTH, lands both on React's one `id` slot, and leaves
  ;; which one survives to the order the props map happens to iterate in.
  (testing "written on the element"
    (doseq [k [:id "id" 'id :x/id]]
      (is (= "explicit" (prop (rf.hicasso.impl.codec/as-element [:div#tag {k "explicit"}]) "id"))
          (str "spelled " (pr-str k)))))
  (testing "and the shorthand still lands when nothing claims the slot"
    (is (= "tag" (prop (rf.hicasso.impl.codec/as-element [:div#tag {:title "t"}]) "id"))))
  (testing "a near miss is not the id slot and keeps its own name"
    (let [e (rf.hicasso.impl.codec/as-element [:div#tag {:data-id "d" :ids "many"}])]
      (is (= "tag" (prop e "id")))
      (is (= "d" (prop e "data-id")))
      (is (= "many" (prop e "ids"))))))

(deftest the-shorthand-fold-does-not-depend-on-map-order
  ;; The audit's phrasing: the answer must hold "independent of cache/render
  ;; /map order". Both spellings of both slots in one map, written in both
  ;; orders, and again in a map big enough to be a PersistentHashMap rather
  ;; than an array map — the iteration order changes underneath and the
  ;; emitted element does not.
  (let [expected {"id" "caller" "className" "foo bar"}
        emitted  (fn [e] {"id" (prop e "id") "className" (prop e "className")})]
    (testing "the audit's own witness, both ways round"
      (is (= expected (emitted (rf.hicasso.impl.codec/as-element
                                [:div#tag.foo {"id" "caller" "className" "bar"}]))))
      (is (= expected (emitted (rf.hicasso.impl.codec/as-element
                                [:div#tag.foo {"className" "bar" "id" "caller"}])))))
    (testing "and out of a hash map, whose iteration order is neither"
      (let [caller (into {} (map (fn [i] [(keyword (str "data-" i)) i])) (range 12))]
        (is (= expected (emitted (rf.hicasso.impl.codec/as-element
                                  [:div#tag.foo (assoc caller "id" "caller" :x/class "bar")]))))))
    (testing "renders are independent of what the caches were asked first"
      (rf.hicasso.impl.codec/reset-caches!)
      (rf.hicasso.impl.codec/cached-prop-name "class")
      (rf.hicasso.impl.codec/cached-prop-name :x/id)
      (is (= expected (emitted (rf.hicasso.impl.codec/as-element
                                [:div#tag.foo {"id" "caller" "className" "bar"}])))))))

(deftest prop-values-are-converted-in-the-shapes-react-wants
  (testing "a style map becomes a JS object with camelCased keys"
    (let [style (prop (rf.hicasso.impl.codec/as-element [:div {:style {:font-size "12px" :color :red}}]) "style")]
      (is (= "12px" (aget style "fontSize")))
      (is (= "red" (aget style "color")) "a keyword value stringifies")))
  (testing "a CSS custom property survives inside style"
    (let [style (prop (rf.hicasso.impl.codec/as-element [:div {:style {:--gap "4px"}}]) "style")]
      (is (= "4px" (aget style "--gap")))))
  (testing "a function passes through BY IDENTITY, so downstream bail-outs still work"
    (let [f (fn [_])]
      (is (identical? f (prop (rf.hicasso.impl.codec/as-element [:div {:on-click f}]) "onClick")))))
  (testing "a ref is an ordinary prop — callback refs are legal on native tags"
    (let [r (fn [_node])]
      (is (identical? r (prop (rf.hicasso.impl.codec/as-element [:div {:ref r}]) "ref")))))
  (testing "scalars are left alone"
    (let [e (rf.hicasso.impl.codec/as-element [:input {:value "x" :disabled true :tab-index 3}])]
      (is (= "x" (prop e "value")))
      (is (= true (prop e "disabled")))
      (is (= 3 (prop e "tabIndex"))))))

;; ---------------------------------------------------------------------------
;; Keys — `:key` in the props position, never metadata (HD-016)
;; ---------------------------------------------------------------------------

(deftest a-native-elements-key-is-key-in-the-props-map-and-is-not-an-attribute
  (let [e (rf.hicasso.impl.codec/as-element [:li {:key 7 :class "row"}])]
    (is (= "7" (el-key e)) "React stringifies keys")
    (is (= ["className"] (prop-names e)) ":key never reaches the DOM props")))

;; ---------------------------------------------------------------------------
;; Children
;; ---------------------------------------------------------------------------

(deftest children-are-realized-once-and-flattened-one-level
  (testing "one child arrives as the single child, not an array"
    (let [e (rf.hicasso.impl.codec/as-element [:p "text"])]
      (is (= "text" (children e)))))
  (testing "several children arrive in order"
    (let [e (rf.hicasso.impl.codec/as-element [:p "a" "b" "c"])]
      (is (= ["a" "b" "c"] (child-vec e)))))
  (testing "a seq is expanded, and an interior nil does NOT truncate it"
    (let [e (rf.hicasso.impl.codec/as-element [:ul (for [i (range 4)] (when (odd? i) [:li {:key i} i]))])
          expanded (vec (children e))]
      (is (= 4 (count expanded)) "all four slots survive the single pass")
      (is (nil? (nth expanded 0)))
      (is (= "li" (el-type (nth expanded 1))))
      (is (nil? (nth expanded 2)))
      (is (= "li" (el-type (nth expanded 3))))))
  (testing "nil and false render nothing"
    (is (nil? (rf.hicasso.impl.codec/as-element nil)))
    (is (nil? (rf.hicasso.impl.codec/as-element false)))
    (is (= [nil nil "x"] (child-vec (rf.hicasso.impl.codec/as-element [:p nil false "x"])))))
  (testing "true is an error"
    (is (thrown-with-msg? js/Error #"true is an error" (rf.hicasso.impl.codec/as-element true)))
    (try
      (rf.hicasso.impl.codec/as-element true)
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-true-child (:rf.error/id (ex-data e)))))))
  (testing "an existing React element is a legal child anywhere"
    (let [foreign (react/createElement "b" nil "bold")
          e       (rf.hicasso.impl.codec/as-element [:p foreign])]
      (is (identical? foreign (children e))))))

(deftest a-map-in-the-first-slot-is-props-and-anything-else-is-a-child
  (testing "a map is props"
    (is (= "row" (prop (rf.hicasso.impl.codec/as-element [:div {:class "row"}]) "className"))))
  (testing "a string in the first slot is a child, not props"
    (is (= "hello" (children (rf.hicasso.impl.codec/as-element [:div "hello"])))))
  (testing "a vector in the first slot is a child"
    (is (= "b" (el-type (children (rf.hicasso.impl.codec/as-element [:div [:b]])))))))

;; ---------------------------------------------------------------------------
;; Fragments
;; ---------------------------------------------------------------------------

(deftest the-fragment-spelling-is-a-fragment
  (let [e (rf.hicasso.impl.codec/as-element [:<> [:li "a"] [:li "b"]])]
    (is (identical? (.-Fragment react) (el-type e)))
    (is (= 2 (count (child-vec e)))))
  (testing "a keyed fragment carries its key"
    (is (= "k" (el-key (rf.hicasso.impl.codec/as-element [:<> {:key "k"} [:li "a"]]))))))

;; ---------------------------------------------------------------------------
;; Boundaries — the HD-016 head rules
;; ---------------------------------------------------------------------------

(defn- a-view
  "Stands in for a `defview` product: an arm mints the function component
  once, at definition, and marks it. The codec never wraps it, which is
  why HD-004's cached-component-head has nothing to do here."
  [_js-props]
  nil)

(rf.hicasso.impl.codec/mark-boundary! a-view)

(deftest a-marked-view-in-head-position-is-a-boundary-child
  (let [e (rf.hicasso.impl.codec/as-element [a-view {:id 7}])]
    (is (identical? a-view (el-type e)))
    (is (= {:id 7} (prop e "rfProps")) "the body receives one CLJS props map")))

(deftest a-boundarys-key-is-extracted-before-the-body-sees-props
  (let [e (rf.hicasso.impl.codec/as-element [a-view {:key 3 :id 7}])]
    (is (= "3" (el-key e)))
    (is (= {:id 7} (prop e "rfProps")) ":key is React's contract, not the body's")))

(deftest a-boundarys-children-arrive-as-a-realized-vector-of-hiccup
  (testing "trailing forms become (:children props)"
    (let [e (rf.hicasso.impl.codec/as-element [a-view {:id 7} [:li "a"] [:li "b"]])]
      (is (= {:id 7 :children [[:li "a"] [:li "b"]]} (prop e "rfProps")))))
  (testing "a seq child is realized once and flattened exactly one level"
    (let [e (rf.hicasso.impl.codec/as-element [a-view {} (for [i (range 2)] [:li i])])]
      (is (= [[:li 0] [:li 1]] (:children (prop e "rfProps"))))))
  (testing "with no trailing forms there is no :children key at all"
    (is (= {:id 7} (prop (rf.hicasso.impl.codec/as-element [a-view {:id 7}]) "rfProps"))))
  (testing "a boundary with no props map still works"
    (is (= {} (prop (rf.hicasso.impl.codec/as-element [a-view]) "rfProps")))))

(deftest a-plain-function-in-head-position-is-a-loud-error
  (let [helper (fn [_] [:div])]
    (is (false? (rf.hicasso.impl.codec/boundary-head? helper)))
    (is (thrown-with-msg? js/Error #"never a silent" (rf.hicasso.impl.codec/as-element [helper {}])))
    (try
      (rf.hicasso.impl.codec/as-element [helper {}])
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-bad-head (:rf.error/id (ex-data e))))))))

(deftest an-empty-hiccup-vector-and-a-nonsense-head-are-loud-errors
  ;; The prose is the CATALOGUED prose (the package's refusals are minted
  ;; by `impl.error/fail!` under `check_complaint_catalogue.py`),
  ;; so each row pins the id — the stable contract — beside the wording.
  (is (thrown-with-msg? js/Error #"must have a head" (rf.hicasso.impl.codec/as-element [])))
  (is (= :rf.error/hicasso-empty-vector
         (try (rf.hicasso.impl.codec/as-element []) nil (catch :default e (:rf.error/id (ex-data e))))))
  (is (thrown-with-msg? js/Error #"Hiccup head must be a tag keyword"
                        (rf.hicasso.impl.codec/as-element [42 {}])))
  (is (= :rf.error/hicasso-bad-head
         (try (rf.hicasso.impl.codec/as-element [42 {}]) nil (catch :default e (:rf.error/id (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter
;; ---------------------------------------------------------------------------

(deftest a-structural-slot-is-recognised-in-every-spelling-the-codec-accepts
  (testing "the mechanism the whole of this bead's repair rests on: the slot
            a key EMITS INTO, not the key it was written as. React's key and
            ref are reachable as a keyword, a string, a symbol and a
            namespaced keyword, and a rule that names `#{:key :ref}` sees
            exactly one of the four."
    (doseq [k [:key "key" 'key :x/key :re-frame.hicasso/key]]
      (is (true? (rf.hicasso.impl.codec/structural-slot? k)) (str "key, spelled " (pr-str k))))
    (doseq [k [:ref "ref" 'ref :x/ref]]
      (is (true? (rf.hicasso.impl.codec/structural-slot? k)) (str "ref, spelled " (pr-str k))))
    (doseq [k [:id :class :on-click :data-key "aria-ref" :keyboard]]
      (is (false? (rf.hicasso.impl.codec/structural-slot? k))
          (str "and nothing else is structural: " (pr-str k)))))
  (testing "the filter drops every one of them and returns the map by
            identity when there is nothing to drop"
    (is (= {:class "x"} (rf.hicasso.impl.codec/without-structural
                          {:class "x" :key 1 "key" 2 :x/key 3 :ref (fn [_]) "ref" 4 :x/ref 5})))
    (let [clean {:class "x" :id "y"}]
      (is (identical? clean (rf.hicasso.impl.codec/without-structural clean))))))

;; ---------------------------------------------------------------------------
;; `:ref` — React's own slot, forwarded untouched
;; ---------------------------------------------------------------------------

(deftest a-callback-ref-is-the-surface-and-reaches-react-by-identity
  (testing "HD-003's escape hatch and HD-016's callback-refs-only rule: a
            function at :ref arrives at React as the same function object —
            rewrapping it would detach and reattach the node every render."
    (let [f (fn [_node] nil)
          e (rf.hicasso.impl.codec/as-element [:div {:ref f}])]
      (is (identical? f (prop e "ref"))))))

(deftest a-ref-crosses-by-identity-however-it-is-spelled
  (testing "the ref position is excluded from callback lowering at the SLOT
            rather than at the key, so `\"ref\"` and `:x/ref` reach React's
            ref slot by identity too — wrapping would both forbid a dispatch
            that is legitimate there and change the identity React uses to
            decide whether to re-attach the node"
    (let [f (rf.hicasso.impl.intent/callback (fn [_node]))]
      (is (identical? f (prop (rf.hicasso.impl.codec/as-element [:div {"ref" f}]) "ref")))
      (is (identical? f (prop (rf.hicasso.impl.codec/as-element [:div {:x/ref f}]) "ref")))))
  (testing "every :ref value passes through the ordinary conversion — the
            codec claims nothing about the slot's value-space"
    (is (nil? (prop (rf.hicasso.impl.codec/as-element [:div {:ref nil}]) "ref")))
    (is (= "legacy" (prop (rf.hicasso.impl.codec/as-element [:div {:ref "legacy"}]) "ref")))
    (is (some? (prop (rf.hicasso.impl.codec/as-element [:div {:ref {:a 1}}]) "ref")))
    (is (some? (prop (rf.hicasso.impl.codec/as-element [:div {:ref [::autosize {:max-rows 8}]}]) "ref"))
        "a vector converts as any collection does; nothing is reserved"))
  (testing "and :ref is not an event position, so intent lowering never sees it"
    (is (false? (rf.hicasso.impl.intent/event-prop? :ref)))))

;; ---------------------------------------------------------------------------
;; A presence override no tray reached
;; ---------------------------------------------------------------------------
;;
;; `impl.presence/with-phase` strips `::motion/mounting` / `::motion/unmounting`
;; off a tray's DIRECT children and merges the phase's map in their place.
;; One that reaches a prop walk was written somewhere no tray walks, and the
;; walk skips it: it is Hicasso's private key, and `(name k)` would
;; otherwise have emitted it as an ordinary `mounting` attribute.

(defn- an-override-crossing
  "A foreign component, for the crossing arm below. Local because
  [[a-foreign-component]] is declared further down this file."
  [_props]
  nil)

(declare raw-props)

(deftest an-override-outside-a-trays-direct-child-is-skipped-rather-than-emitted
  (testing "a native node: the key is dropped and no attribute reaches the page"
    (let [e (rf.hicasso.impl.codec/as-element [:div {:re-frame.hicasso.motion/mounting {:class "in"} :id "x"}])]
      (is (= ["id"] (vec (js/Object.keys (.-props e)))))))
  (testing "both keys, and at any depth — a tray walks its direct children and
            nothing below them, so a grandchild is as unreachable as an
            element with no tray above it at all"
    (let [e     (rf.hicasso.impl.codec/as-element [:section [:div {:re-frame.hicasso.motion/unmounting {:class "out"}}]])
          child (aget (.-props e) "children")]
      (is (= [] (vec (js/Object.keys (.-props child)))))))
  (testing "the crossing skips it at the same position — a [:>] head that IS a
            tray's direct child has its overrides stripped and merged exactly
            as a native node does, so one arriving here is misplaced for the
            identical reason"
    (let [e (rf.hicasso.impl.codec/as-element [:> an-override-crossing
                               {:re-frame.hicasso.motion/mounting {:class "in"} :size 1}])]
      (is (= ["size"] (vec (js/Object.keys (raw-props e))))))))

(deftest the-override-skip-claims-the-two-private-keywords-and-nothing-else
  (testing "a bare `:mounting` is an ordinary author attribute the walk emits
            under its own name; the skip is on the EXACT keyword and not on
            the canonical slot, because these two are Hicasso's own private
            keys where `ref` and `className` are React's positions"
    (is (= "x" (prop (rf.hicasso.impl.codec/as-element [:div {:mounting "x"}]) "mounting")))
    (is (= "x" (prop (rf.hicasso.impl.codec/as-element [:div {:unmounting "x"}]) "unmounting")))
    (doseq [spelling [:mounting "mounting" 'mounting :x/mounting :data-mounting]]
      (is (= "x" (prop (rf.hicasso.impl.codec/as-element [:div {spelling "x"}]) (name spelling)))
          (str "emitted, spelled " (pr-str spelling))))
    (is (= "x" (aget (raw-props (rf.hicasso.impl.codec/as-element [:> an-override-crossing {:mounting "x"}])) "mounting"))
        "and the same at the crossing, where a foreign ABI may genuinely
         name a prop `mounting`")))

;; ---------------------------------------------------------------------------
;; The codec's one policy call — intent lowering happens inside the walk
;; ---------------------------------------------------------------------------

(deftest event-vectors-in-attributes-lower-during-prop-conversion
  (let [!seen (atom [])
        e     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                 (fn [] (rf.hicasso.impl.codec/as-element
                                         [:button {:on-click [:todo/toggle 7] :class "btn"}
                                          "done"])))]
    (is (= "btn" (prop e "className")))
    (is (fn? (prop e "onClick")))
    (is (= [] @!seen))
    ((prop e "onClick") #js {:target #js {}})
    (is (= [[:todo/toggle 7]] @!seen))))

(deftest a-controlled-field-lowers-its-marker-through-the-codec
  (let [!seen (atom [])
        e     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                 (fn [] (rf.hicasso.impl.codec/as-element
                                         [:input {:value "milk"
                                                  :on-input [:todo.ui/edit 7 :re-frame.hicasso/value]}])))]
    (is (= "milk" (prop e "value")))
    ((prop e "onInput") #js {:target #js {:value "bread"}})
    (is (= [[:todo.ui/edit 7 "bread"]] @!seen))))

;; ---------------------------------------------------------------------------
;; The named value at a foreign crossing
;; ---------------------------------------------------------------------------

(defn- a-foreign-component
  "Stands in for a library's component — anything React would accept as
  an element type. Nothing renders here; the questions below are all
  about what the codec BUILT."
  [_js-props]
  nil)

(def ^:private a-host (rf.hicasso.impl.codec/mint-host! "AHost" a-foreign-component))

(defn- host-prop
  "The value the codec emitted at `slot` for a one-prop host crossing.
  Reads the GATE element's props, which are the props object the foreign
  component receives — the gate forwards it verbatim."
  [k v slot]
  (prop (rf.hicasso.impl.codec/as-element [a-host {k v}]) slot))

(deftest a-namespaced-keyword-keeps-its-namespace-at-a-host-prop
  (testing "the shape rf2-vrvv9 names. `(name :theme/dark)` is \"dark\", so
            the namespace used to be discarded on its way to a hosted
            React-context provider and the value arrived as a plausible
            string that had lost half its identity."
    (is (= :theme/dark (host-prop :value :theme/dark "value"))
        "the keyword crosses whole — not \"dark\", not \"theme/dark\""))
  (testing "and the collision that made the loss silent is gone. Two
            keywords from different namespaces used to arrive as ONE
            string; a crossing that answers two inputs with one output is
            not a conversion."
    (let [crossed #{(host-prop :value :theme/dark "value")
                    (host-prop :value :other/dark "value")}]
      (is (= 2 (count crossed))
          "two distinct values in, two distinct values out")
      (is (= #{:theme/dark :other/dark} crossed))))
  (testing "an unnamespaced keyword and a symbol take the same rule — the
            question is the position, never the shape of the name"
    (is (= :contained (host-prop :variant :contained "variant")))
    (is (= 'sym (host-prop :variant 'sym "variant")))))

(deftest a-named-value-bound-for-an-html-attribute-still-stringifies
  (testing "className, id and role: the value is an HTML attribute wherever
            the component passes it on, and a string is the only
            representation there is"
    (is (= "primary" (host-prop :class :primary "className")))
    (is (= "greeting" (host-prop :id :greeting "id")))
    (is (= "dialog" (host-prop :role :dialog "role"))))
  (testing "the data-* / aria-* families, by prefix"
    (is (= "row" (host-prop :data-kind :row "data-kind")))
    (is (= "close" (host-prop :aria-label :close "aria-label"))))
  (testing "the rule is written against the emitted SLOT, never the map key
            — `:class` and `:className` are one position, and a rule that
            read the spelling is a rule the other spelling walks past"
    (is (= "primary" (host-prop :className :primary "className")))
    (is (= "primary" (host-prop "class" :primary "className"))))
  (testing "the namespace drop survives ONLY here, and it is the same answer
            the native walk gives at the same name"
    (is (= "dark" (host-prop :class :theme/dark "className")))
    (is (= "dark" (prop (rf.hicasso.impl.codec/as-element [:div {:class :theme/dark}]) "className"))
        "the native crossing, unchanged")))

;; ---------------------------------------------------------------------------
;; The class slot is a POSITION at the crossing too
;; ---------------------------------------------------------------------------

(deftest a-class-collection-crosses-as-a-class-string-at-a-host-and-at-a-tag
  (testing "THE DEVIATION rf2-2rtt6.119 names. The class slot has a coercion
            of its own — `class-names` — and it was taken at the native
            position only, so ONE authored shape got TWO answers. The
            crossing sent `{:class [\"a\" nil :b]}` through `clj->js` and
            handed the foreign component a JS array, which React writes to
            the DOM as \"a,,b\" wherever the component passes it on: nothing
            threw and the styling was simply wrong."
    (let [crossed (host-prop :class ["a" nil :b] "className")
          native  (prop (rf.hicasso.impl.codec/as-element [:div {:class ["a" nil :b]}]) "className")]
      (is (string? crossed)
          "a class string, not the JS array clj->js used to build here")
      (is (= "a b" crossed))
      (is (= native crossed)
          "and it is the SAME answer the native walk gives — which is the
           rule rf2-vrvv9 already stated for the named value at this slot,
           applied to the arm a collection takes")))
  (testing "the coercion is on the SLOT, so it holds in every spelling of it
            — the discipline `canonical-slot` and the owned-literal law
            already use, and the reason a rule written against `:class`
            would be one that `:className`, `\"class\"` and `:x/class` walk
            past"
    (doseq [k [:class :className "class" "className" 'class :x/class]]
      (is (= "a b" (host-prop k ["a" nil :b] "className"))
          (str "spelled " (pr-str k)))))
  (testing "a nested collection joins one level down too, exactly as at a tag"
    (is (= "a b c" (host-prop :class ["a" ["b" nil] :c] "className"))))
  (testing "and it COMPOSES, for the reason the native position composes:
            two spellings of one element's class are two map keys and one
            React slot, so letting the last write win would drop a class
            silently"
    (let [e (rf.hicasso.impl.codec/as-element [a-host {:class "a" :x/class ["b" :c]}])]
      (is (= "a b c" (prop e "className"))))
    ;; the crossing rule as a worked case — the guide states it as a rule
    ;; (docs/core/hicasso/09-interop.md §Crossing rules) and no longer carries
    ;; the example this once quoted verbatim
    (is (= "btn on wide"
           (prop (rf.hicasso.impl.codec/as-element [a-host {:class ["btn" nil :on] :className "wide"}])
                 "className"))))
  (testing "nothing else at the slot moves. A string is verbatim, a keyword
            still stringifies (rf2-vrvv9's rule, unchanged), and a
            namespaced keyword still drops its namespace HERE and only here"
    (is (= "primary" (host-prop :class "primary" "className")))
    (is (= "primary" (host-prop :class :primary "className")))
    (is (= "dark" (host-prop :class :theme/dark "className")))
    (is (nil? (host-prop :class nil "className"))))
  (testing "and the deviation was the class slot ALONE — `id` and `role`
            hand a collection to `clj->js` at BOTH positions, so there is
            nothing to reconcile there and nothing here that changed them"
    (is (array? (host-prop :id ["a" "b"] "id")))
    (is (array? (prop (rf.hicasso.impl.codec/as-element [:div {:id ["a" "b"]}]) "id"))
        "the native walk's own answer, quoted so the claim is not asserted
         about a function nobody ran")))

(deftest a-declared-contract-still-outranks-the-class-coercion
  (testing "HD-011's whole point is that a DECLARED position means what the
            declaration says it means, so the slot coercion sits BELOW the
            declaration in the cond rather than above it. Nobody sensible
            declares a contract on `className`; the row exists so the
            precedence is pinned rather than incidental — and a vector is
            the value that tells the two orders apart, because
            `class-names` would quietly answer it \"a b\" where a `:render`
            override leaves it a value that crosses as DATA."
    (let [declared (rf.hicasso.impl.codec/mint-host! "ClassContractHost" a-foreign-component
                                     {:callbacks {:class :render}})]
      (is (array? (prop (rf.hicasso.impl.codec/as-element [declared {:class ["a" "b"]}]) "className"))
          "the declaration decided, not the slot")))
  (testing "and an undeclared host is unaffected — the same value at the
            same slot is an ordinary class collection"
    (is (= "a b" (host-prop :class ["a" "b"] "className")))))

(deftest a-host-infers-the-contract-from-the-spelling-exactly-as-a-native-tag
  ;; rf2-6c12m.2 — the declaration roster is gone. `host-entry`'s `:else`
  ;; arm IS `intent/lower-prop`, the native walk's own classifier, so a
  ;; host prop means what the same prop means on a native tag.
  (let [!seen (atom [])
        d     (fn [ev] (swap! !seen conj ev))]
    (testing "an on*-spelled prop is an EVENT position with no declaration
              at all: an h/event there dispatches its return"
      (let [f (rf.hicasso.impl.intent/with-frame d (fn [] (host-prop :on-pick (rf.hicasso.impl.intent/callback (fn [x] [:row/pick x])) "onPick")))]
        (f 1)
        (is (= [[:row/pick 1]] @!seen))))
    (testing "and so are the vector and key-map spellings, exactly as at a
              native tag"
      (reset! !seen [])
      (let [f (rf.hicasso.impl.intent/with-frame d (fn [] (host-prop :on-close [:dialog/cancel] "onClose")))]
        (f #js {:target #js {}})
        (is (= [[:dialog/cancel]] @!seen)))
      (reset! !seen [])
      (let [f (rf.hicasso.impl.intent/with-frame d (fn [] (host-prop :on-key-down {"Enter" [:go]} "onKeyDown")))]
        (f #js {:key "Enter" :isComposing false :target #js {}})
        (is (= [[:go]] @!seen))))
    (testing "any other prop is a RENDER position: the h/event takes the
              render wrapper, which rebinds the supplying boundary's frame
              and dispatch for the call, so a row it returns dispatches
              into that frame when clicked — after the render extent has
              unwound"
      (reset! !seen [])
      (let [render-row (rf.hicasso.impl.intent/with-frame d
                         (fn [] (host-prop :render-row
                                           (rf.hicasso.impl.intent/callback
                                             (fn [i] (rf.hicasso.impl.codec/as-element [:li {:on-click [:row/pick i]}])))
                                           "renderRow")))
            row        (render-row 9)]
        (is (nil? rf.hicasso.impl.intent/*dispatch*) "precondition: no ambient dispatch here")
        (is (= "li" (.-type row)))
        ((aget (.-props row) "onClick") #js {:target #js {}})
        (is (= [[:row/pick 9]] @!seen))))
    (testing "the on*-named RENDER prop is the one case the spelling gets
              wrong — Fluent's onRenderItem, Ant's onRow — and the
              `:callbacks` override is its one-line spelling: the render
              wrapper rather than the event wrapper, so the returned row
              is the render output (an event wrapper returns nil) and it
              still dispatches on click"
      (reset! !seen [])
      (let [fluent    (rf.hicasso.impl.codec/mint-host! "FluentList" a-foreign-component
                                        {:callbacks {:on-render-item :render}})
            render-fn (rf.hicasso.impl.intent/with-frame d
                        (fn [] (prop (rf.hicasso.impl.codec/as-element
                                       [fluent {:on-render-item
                                                (rf.hicasso.impl.intent/callback
                                                  (fn [item] (rf.hicasso.impl.codec/as-element [:li {:on-click [:row/pick item]}])))}])
                                     "onRenderItem")))
            row       (render-fn "x")]
        (is (= "li" (.-type row)) "the render output came back")
        ((aget (.-props row) "onClick") #js {:target #js {}})
        (is (= [[:row/pick "x"]] @!seen))))
    (testing "an override may also say :event where the spelling infers
              render, and the vector spelling then lowers as at a native
              event position"
      (reset! !seen [])
      (let [h (rf.hicasso.impl.codec/mint-host! "PickHost" a-foreign-component {:callbacks {:pick :event}})
            f (rf.hicasso.impl.intent/with-frame d (fn [] (prop (rf.hicasso.impl.codec/as-element [h {:pick [:row/pick 2]}]) "pick")))]
        (f #js {:target #js {}})
        (is (= [[:row/pick 2]] @!seen))))
    (testing "a PLAIN function crosses by identity at an event position and
              at a render position alike — there is no third contract,
              because this is what :handler named"
      (let [f (fn [_] :whatever)]
        (is (identical? f (host-prop :on-pick f "onPick")))
        (is (identical? f (host-prop :render-row f "renderRow")))))
    (testing "a vector at an inferred render position crosses as DATA, as
              at a native tag; so does one at a declared :render override"
      (is (array? (host-prop :columns ["a" "b"] "columns")))
      (let [h (rf.hicasso.impl.codec/mint-host! "ColumnsHost" a-foreign-component {:callbacks {:render-row :render}})]
        (is (array? (prop (rf.hicasso.impl.codec/as-element [h {:render-row ["a" "b"]}]) "renderRow")))))
    (testing "and a contract outside the two is a bad declaration, refused
              at mint where the author's stack is the declaration"
      (let [data (try (rf.hicasso.impl.codec/mint-host! "HandlerHost" a-foreign-component
                                        {:callbacks {:on-pick :handler}})
                      nil
                      (catch :default e (ex-data e)))]
        (is (= :rf.error/hicasso-bad-host-declaration (:rf.error/id data))
            ":handler is retired; a plain function is what it named")))))

(deftest every-other-host-prop-value-crosses-exactly-as-it-did
  (testing "functions by identity — `React.memo` and every downstream
            bail-out that compares handler identity"
    (let [f (fn [_])]
      (is (identical? f (host-prop :on-thing f "onThing")))))
  (testing "collections through clj->js, whose nested keys keep the spelling
            the author wrote"
    (let [o (host-prop :options {:pageSize 10} "options")]
      (is (= 10 (aget o "pageSize")))))
  (testing "strings, numbers, booleans and nil, verbatim"
    (is (= "due date" (host-prop :label "due date" "label")))
    (is (= 7 (host-prop :count 7 "count")))
    (is (true? (host-prop :open true "open")))
    (is (nil? (host-prop :label nil "label")))))

;; ---------------------------------------------------------------------------
;; What "callback refs only" actually is
;; ---------------------------------------------------------------------------

(deftest an-object-ref-crosses-by-identity-at-both-positions
  (testing "rf2-d03av, settled as a RECORD rather than a refusal. HD-016
            reads 'callback refs only', and HD-022 — later, and the ruling
            that is actually about `:ref`'s value space — says the whole of
            the claim is ONE refusal branch and one error id: the reserved
            vector. An object ref is neither reserved nor broken. React 19
            carries `ref` as an ordinary prop, so `(react/createRef)`
            attaches and detaches exactly as React documents it. It is
            untaught rather than illegal, and refusing a spelling that
            works correctly is friction rather than safety.

            This witness is what makes that a decision instead of an
            oversight: a later refusal cannot land silently, because it has
            to delete these rows first."
    (let [r (react/createRef)]
      (is (identical? r (prop (rf.hicasso.impl.codec/as-element [:div {:ref r}]) "ref"))
          "a native tag")
      (is (identical? r (host-prop :ref r "ref"))
          "and a defhost crossing — HD-011's conversion parity, which is
           what makes 'enforced at one crossing and not the other' the
           outcome design C ruled out")))
  (testing "and it is one rule at one slot, so an alternate spelling of the
            ref position answers the same way"
    (let [r (react/createRef)]
      (is (identical? r (prop (rf.hicasso.impl.codec/as-element [:div {"ref" r}]) "ref")))
      (is (identical? r (host-prop :x/ref r "ref")))))
  (testing "and a VECTOR crosses at both as the author wrote it — nothing
            about the slot's value-space is reserved"
    (let [v [::autosize {:max-rows 8}]]
      (is (some? (prop (rf.hicasso.impl.codec/as-element [:div {:ref v}]) "ref")))
      (is (= v (host-prop :ref v "ref"))))))

;; ---------------------------------------------------------------------------
;; The `[:>]` raw escape (HD-011)
;; ---------------------------------------------------------------------------
;;
;; The escape is `defhost` with the declaration erased, so almost every
;; row below is a PARITY row: the same fixture component, the same prop
;; corpus, one answer. What is not parity is stated as its own row — the
;; carrier, the head test, the value roster, and the fact that no `:server`
;; policy is spellable here (the DOM/SSR half of that lives in
;; [[re-frame.hicasso.raw-escape-dom-cljs-test]], which needs a server renderer).

(defn- raw-carrier
  "The gate element's own props — the two-slot carrier."
  [e]
  (.-props e))

(defn- raw-component-of [e] (aget (raw-carrier e) "c"))

(defn- raw-props
  "The props object the FOREIGN component receives: the gate hands its
  `p` slot straight through, so this is the object under test in every
  parity row."
  [e]
  (aget (raw-carrier e) "p"))

(defn- raw-prop
  "The value the escape emitted at `slot` for a one-prop crossing —
  [[host-prop]]'s twin, so the two can be read against each other."
  [k v slot]
  (aget (raw-props (rf.hicasso.impl.codec/as-element [:> a-foreign-component {k v}])) slot))

(defn- crossed-same?
  "Did the two crossings answer the same thing? Functions by identity —
  which is the point of the row — and everything else by value, through
  `js->clj`, because two `clj->js` results are two distinct objects and
  `=` on those is identity."
  [a b]
  (if (fn? a) (identical? a b) (= (js->clj a) (js->clj b))))

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(deftest the-escape-is-a-crossing-and-not-a-tag-named-angle-bracket
  (testing "THE MIS-PARSE REGRESSION. `:>` was not an error before this:
            `hiccup-tag?` accepted any keyword that is not `:<>`, so
            `[:> Foo {}]` routed to the native path and asked React for
            an element literally named `<>`"
    (let [e (rf.hicasso.impl.codec/as-element [:> a-foreign-component {}])]
      (is (not= ">" (el-type e)) "not a native tag any more")
      (is (fn? (el-type e)) "a component — the shared gate")
      (is (= "[:>]" (.-displayName (el-type e)))
          "named by the CONSTANT, never by the component: React resolves
           `displayName || name || null`, Closure renames `.name` under
           :advanced, and foreign production bundles ship without
           displayName — so any derived identity is build-dependent")
      (is (identical? a-foreign-component (raw-component-of e))
          "and the component rides in the carrier's `c` slot")))
  (testing "the head is compared with `=` and never `identical?`. A
            keyword built at runtime is a DIFFERENT object from the
            literal, so an identity test would work under :advanced —
            where the build interns literals — and silently route every
            escape back into the native path everywhere else"
    (let [computed (keyword ">")]
      (is (not (identical? :> computed))
          "precondition: the row is only meaningful because these are two
           objects")
      (is (= "[:>]" (.-displayName (el-type (rf.hicasso.impl.codec/as-element [computed a-foreign-component {}])))))))
  (testing "and the arms either side of the new one are untouched"
    (is (= (.-Fragment react) (el-type (rf.hicasso.impl.codec/as-element [:<> "x"]))))
    (is (= "div" (el-type (rf.hicasso.impl.codec/as-element [:div]))))
    (is (= "toString" (el-type (rf.hicasso.impl.codec/as-element [:toString])))
        "the redundant second fragment test that paid for this arm is
         gone; a native tag still parses as itself")))

(deftest conversion-parity-with-the-door-on-one-prop-corpus
  (testing "THE LOAD-BEARING ROW. HD-011 rules that the escape lowers
            through the SAME foreign path with the SAME default
            conversions, and one walk serves both — so this is an
            identity by construction and the row is a tripwire against a
            future fork rather than a detector of a present one"
    (let [f       (fn [_])
          ref-fn  (fn [_node])
          corpus  {:on-thing   f
                   :plain-fn   f
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
                   :ref        ref-fn}
          door    (.-props (rf.hicasso.impl.codec/as-element [a-host corpus]))
          raw     (raw-props (rf.hicasso.impl.codec/as-element [:> a-foreign-component corpus]))]
      (is (= (vec (sort (js/Object.keys door))) (vec (sort (js/Object.keys raw))))
          "prop for prop, the same emitted slots")
      (doseq [k (js/Object.keys door)]
        (is (crossed-same? (aget door k) (aget raw k))
            (str "the slot " k " crossed the same way at both")))))
  (testing "including the two conversions that are the slot's own rather
            than the walk's — the class coercion (rf2-2rtt6.119) and the
            named value bound for an HTML attribute (rf2-vrvv9)"
    (is (= "a b" (raw-prop :class ["a" nil :b] "className")))
    (is (= "dark" (raw-prop :class :theme/dark "className")))
    (is (= :theme/dark (raw-prop :value :theme/dark "value"))
        "and NOT at an ordinary slot: the keyword crosses whole"))
  (testing "the class slot composes across spellings here too, because the
            rule is on the SLOT and the walk is the door's"
    (let [e (rf.hicasso.impl.codec/as-element [:> a-foreign-component {:class "a" :x/class ["b" :c]}])]
      (is (= "a b c" (aget (raw-props e) "className"))))))

(deftest the-carrier-never-leaks-and-key-rides-the-outer-element
  (testing "the foreign component's props carry NEITHER internal slot.
            Carrying the component beside the converted props and
            stripping it inside would cost a shallow copy per crossing
            per render and leave the key one bug away from React; two
            slots make the leak unrepresentable instead"
    (let [p (raw-props (rf.hicasso.impl.codec/as-element [:> a-foreign-component {:label "x"}]))]
      (is (= ["label"] (vec (sort (js/Object.keys p)))))))
  (testing "and the carrier itself holds exactly the two slots, plus
            children when there are any"
    (is (= ["c" "p"] (vec (sort (js/Object.keys (raw-carrier (rf.hicasso.impl.codec/as-element [:> a-foreign-component {}])))))))
    (is (= ["c" "children" "p"]
           (vec (sort (js/Object.keys (raw-carrier (rf.hicasso.impl.codec/as-element [:> a-foreign-component {} [:span]]))))))))
  (testing "`:key` is React's contract on the crossing's own element, not
            an attribute and not a carrier slot — the door's rule,
            unchanged"
    (let [e (rf.hicasso.impl.codec/as-element [:> a-foreign-component {:key "k7" :label "x"}])]
      (is (= "k7" (el-key e)))
      ;; `Object.keys` rather than a read of `.key`: React 19 defines a
      ;; DEV warning getter at that name on any props object it built
      ;; from a config carrying a key, so reading it would put React's
      ;; "`key` is not a prop" line in the shared test log — an assertion
      ;; whose own mechanism is a console warning. The getter is
      ;; non-enumerable, so this sees the truth without touching it.
      (is (= ["c" "p"] (vec (sort (js/Object.keys (raw-carrier e)))))
          "the key is React's on the element and is not a carrier slot")
      (is (= ["label"] (vec (sort (js/Object.keys (raw-props e))))))))
  (testing "a childless crossing does not write `children` onto the props
            the component receives — which is what makes the parity row
            above a claim about the object rather than about our walk"
    (is (not (contains? (set (js/Object.keys (raw-props (rf.hicasso.impl.codec/as-element [:> a-foreign-component {}]))))
                        "children")))))

(deftest the-escape-infers-every-contract-exactly-as-the-door-does
  (testing "the escape's roster is EMPTY by construction, so every slot at
            this crossing is INFERRED from its spelling — `host-entry`'s
            conduct with no branch of its own. That is what makes
            `[:> X …]` → `(h/defhost x X)` behaviour-preserving, which is
            the whole theorem of the migration codemod"
    (testing "an intent vector at an event-SPELLED slot lowers to a
              dispatching handler, exactly as at a native tag"
      (let [!seen (atom [])
            f     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                     (fn [] (raw-prop :on-pick [:row/pick 7] "onPick")))]
        (is (fn? f))
        (f #js {:target #js {}})
        (is (= [[:row/pick 7]] @!seen))))
    (testing "and so does an event-spelled key-map"
      (let [!seen (atom [])
            f     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                     (fn [] (raw-prop :on-key-down {"Enter" [:go]} "onKeyDown")))]
        (f #js {:key "Enter" :isComposing false :target #js {}})
        (is (= [[:go]] @!seen))))
    (testing "a MARKED h/event at an on* slot takes the event wrapper and
              dispatches its return; at any other slot it takes the render
              wrapper, whose return crosses back as the render output —
              the spelling selects the contract, never a declaration"
      (let [!seen  (atom [])
            d      (fn [ev] (swap! !seen conj ev))
            marked (rf.hicasso.impl.intent/callback (fn [x] [:row/pick x]))
            on     (rf.hicasso.impl.intent/with-frame d (fn [] (raw-prop :on-value-change marked "onValueChange")))
            row    (rf.hicasso.impl.intent/with-frame d (fn [] (raw-prop :row-formatter marked "rowFormatter")))]
        (on 3)
        (is (= [[:row/pick 3]] @!seen) "the event wrapper dispatched")
        (is (= [:row/pick 4] (row 4)) "the render wrapper returned the value")
        (is (= [[:row/pick 3]] @!seen) "and dispatched nothing")))
    (testing "a PLAIN function crosses by identity — the fence, and the
              reason React.memo and every downstream bail-out that
              compares handler identity keep working"
      (let [f (fn [_])]
        (is (identical? f (raw-prop :on-value-change f "onValueChange")))))
    (testing "an intent vector at a NON-event slot is ordinary data"
      (is (= ["a" "b"] (js->clj (raw-prop :columns ["a" "b"] "columns")))))
    (testing "`:ref` takes HD-016's rule at this crossing exactly as at the
              door: a callback ref through by identity, any other value
              untouched"
      (let [r (fn [_node])]
        (is (identical? r (raw-prop :ref r "ref"))))
      (is (= [::autosize {}] (raw-prop :ref [::autosize {}] "ref"))))))

(deftest children-lower-eagerly-and-ride-the-outer-element
  (testing "trailing forms lower here, in the render window of the
            boundary that wrote the crossing, through `make-element`'s
            existing three arms — so an intent closure in a child
            captures the owner's frame-locked dispatch at LOWERING time
            (the two-frame proof of that is the DOM witness)"
    (let [one (raw-carrier (rf.hicasso.impl.codec/as-element [:> a-foreign-component {} [:span "x"]]))]
      (is (= "span" (.-type (aget one "children")))
          "one child: an element, not hiccup"))
    (let [many (raw-carrier (rf.hicasso.impl.codec/as-element [:> a-foreign-component {} [:span "a"] [:b "c"]]))
          kids (aget many "children")]
      (is (array? kids))
      (is (= ["span" "b"] (mapv #(.-type %) (vec kids))))))
  (testing "the attribute map is optional, and the indices shift by one
            from the door's: component at 1, props at 2 when it is a map,
            children from 3 or from 2"
    (let [e (rf.hicasso.impl.codec/as-element [:> a-foreign-component [:span "no props"]])]
      (is (= "span" (.-type (aget (raw-carrier e) "children"))))
      (is (= [] (vec (sort (js/Object.keys (raw-props e)))))))
    (is (some? (rf.hicasso.impl.codec/as-element [:> a-foreign-component]))
        "[:> Component] with neither props nor children is legal"))
  (testing "a lowered intent inside a `[:>]` child is the ordinary
            frame-locked one — the crossing is transparent to it"
    (let [!seen (atom [])
          e     (rf.hicasso.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                   (fn [] (rf.hicasso.impl.codec/as-element
                                            [:> a-foreign-component {}
                                             [:button {:on-click [:go]}]])))
          child (aget (raw-carrier e) "children")]
      ((aget (.-props child) "onClick") #js {:target #js {}})
      (is (= [[:go]] @!seen)))))

;; --- Edge 4: the legal Component value -------------------------------------
;;
;; THE PINNING ROSTER, and it is not optional. The accepted set is a
;; REPLICA of what React 19's reconciler mints a fiber for, so a React
;; upgrade that moved the boundary would have this escape false-refusing
;; a legal component — the worst failure a hatch can have. These rows
;; turn that into a red row instead of a silent ship: the accepts assert
;; that each category crosses, and the refusals assert the id AND the
;; discriminating reason rather than merely "it threw".

(defn- accepts? [c] (identical? c (raw-component-of (rf.hicasso.impl.codec/as-element [:> c {}]))))

(deftest every-category-react-mints-a-fiber-for-crosses
  (testing "functions and classes"
    (is (accepts? a-foreign-component))
    (is (accepts? (fn [_]))))
  (testing "React's built-in `Symbol.for` exotics — the reason a
            component-keyed weak cache could not have been the mechanism:
            ES2024 excludes REGISTERED symbols as WeakMap keys by design"
    (is (accepts? (.-Suspense react)))
    (is (accepts? (.-Fragment react)))
    (is (accepts? (.-StrictMode react)))
    (is (accepts? (.-Profiler react))))
  (testing "and the `$$typeof`-branded wrapper objects"
    (is (accepts? (react/memo a-foreign-component)))
    (is (accepts? (react/lazy (fn [] (js/Promise.resolve #js {:default a-foreign-component})))))
    (is (accepts? (react/forwardRef (fn [_p _r] nil))))
    (let [ctx (react/createContext "unset")]
      (is (accepts? ctx) "a context is a legal type in React 19")
      (is (accepts? (.-Provider ctx)))
      (is (accepts? (.-Consumer ctx)))))
  (testing "`Suspense` and `lazy` in particular are a PAIR — lazy requires
            a Suspense ancestor, and HD-011 names that pairing as a reason
            the escape exists, so a roster that took one and not the other
            would be unsound"
    (is (some? (rf.hicasso.impl.codec/as-element [:> (.-Suspense react) {}
                                  [:> (react/lazy (fn [] (js/Promise.resolve #js {:default a-foreign-component}))) {}]])))))

(deftest the-three-values-react-would-accept-or-misreport-are-refused-at-the-crossing
  (testing "refused HERE, in the owner's render and on the server too,
            rather than by React — whose own refusal is minted at fiber
            creation, post-adoption and client-only"
    (testing "nil, and the bare form: the broken-import diagnosis"
      (let [d (ex-data (try (rf.hicasso.impl.codec/as-element [:> nil {}]) nil (catch :default e e)))]
        (is (= :rf.error/hicasso-raw-not-a-component (:rf.error/id d)))
        (is (= 3 (:argv-count d)))
        (is (re-find #"no default export" (:reason d))))
      (is (= :rf.error/hicasso-raw-not-a-component (error-id #(rf.hicasso.impl.codec/as-element [:>])))))
    (testing "a defview head, which React WOULD accept — it is fn?-true,
              so a bare 'is it a function' test mounts the shell raw, the
              body reads rfProps, gets undefined, and receives nil props"
      (let [d (ex-data (try (rf.hicasso.impl.codec/as-element [:> a-view {}]) nil (catch :default e e)))]
        (is (= :rf.error/hicasso-raw-not-a-component (:rf.error/id d)))
        (is (identical? a-view (:component d)))
        (is (re-find #"a defview product" (:reason d)))
        (is (re-find #"\[my-view" (:reason d)))))
    (testing "a defhost head — the escape is for what a declaration cannot
              express, and this one already IS a declaration"
      (let [d (ex-data (try (rf.hicasso.impl.codec/as-element [:> a-host {}]) nil (catch :default e e)))]
        (is (= :rf.error/hicasso-raw-not-a-component (:rf.error/id d)))
        (is (re-find #"a defhost product" (:reason d)))))
    (testing "everything else is React's to judge — a keyword, a map or a
              React element crosses, and React reports it at fiber creation
              with its own 'Element type is invalid'"
      (doseq [c [:div {:a 1} (rf.hicasso.impl.codec/as-element [:div])]]
        (is (identical? c (raw-component-of (rf.hicasso.impl.codec/as-element [:> c {}])))
            (str "crosses: " (pr-str c)))))))

;; ---------------------------------------------------------------------------
;; What the codec must NOT be
;; ---------------------------------------------------------------------------

(deftest the-codec-mints-a-fresh-props-object-per-element-and-memoizes-no-element
  (testing "two renders of the same hiccup are two elements with two props objects"
    (let [hiccup [:div {:class "row"} "x"]
          a      (rf.hicasso.impl.codec/as-element hiccup)
          b      (rf.hicasso.impl.codec/as-element hiccup)]
      (is (not (identical? a b)) "no element memoization — HD-006 stands")
      (is (not (identical? (.-props a) (.-props b))) "no props-object cache")
      (is (= (prop-names a) (prop-names b)))))
  (testing "only the two codec-work caches grow — tags and prop names (HD-004)"
    (rf.hicasso.impl.codec/reset-caches!)
    (dotimes [i 20] (rf.hicasso.impl.codec/as-element [:div.row {:class (str "c" i)} i]))
    (is (= {:tags 1 :props 3} (rf.hicasso.impl.codec/cache-sizes))
        "one tag literal; `:class` is one of the three seeded prop names, so
         twenty renders with twenty distinct class VALUES add nothing")))

;; ---------------------------------------------------------------------------
;; The entity-key warning — development only
;; ---------------------------------------------------------------------------
;;
;; Every row below owns its own member NAMES, because the dedupe Map is
;; deliberately page-lifetime and has no reset hook: "once per site" is the
;; contract, and a fixture that reset it would be testing a different one.
;; An ABSENT key is React's own warning and is left to it; what the codec
;; warns on is a key React would coerce by CONTENT.

(defn- named-view
  "A distinct marked boundary head, stamped the way an arm's mint stamps
  one, so the warning has a name to say."
  [nm]
  (let [f (fn [_js-props] nil)]
    (unchecked-set f "displayName" nm)
    (rf.hicasso.impl.codec/mark-boundary! f)))

(defn- warnings-during
  "Everything the codec said on `console.warn` while `f` ran. The channel
  matters: React's own key warning is a `console.error`, so a spy on the
  wrong one would read a Hicasso row green off React's line."
  [f]
  (let [seen     (atom [])
        original (.-warn js/console)]
    (set! (.-warn js/console) (fn [& args] (swap! seen conj (apply str args))))
    (try (f) (finally (set! (.-warn js/console) original)))
    @seen))

(deftest an-unkeyed-boundary-seq-is-reacts-to-warn-about-and-the-codec-is-silent
  (let [row (named-view "w1.ns/row")]
    (is (= [] (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (for [i (range 3)] [row {:id i}])])))
        "React already warns about a missing key; the codec adds nothing")
    (is (= [] (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key nil :id 1}])])))
        "and `:key nil` is the same absent key")))

(deftest a-keyed-boundary-seq-is-silent-and-so-is-every-legitimate-key-shape
  (testing "the canonical keyed list says nothing at all"
    (let [row (named-view "w2.ns/row")]
      (is (= [] (warnings-during
                  #(rf.hicasso.impl.codec/as-element [:ul (for [i (range 300)] [row {:key i}])]))))))
  (testing "each primitive key shape individually — a warning that fired on
            valid code would be worse than no warning"
    (doseq [[label k] [["number" 1] ["string" "a"] ["keyword" :a]
                       ["namespaced-keyword" :ns/a] ["negative-number" -1]
                       ["zero" 0] ["empty-string" ""]]]
      (let [row (named-view (str "w2b.ns/" label))]
        (is (= [] (warnings-during
                    #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key k}])])))
            (str "a " label " key is a legitimate key"))))))

(deftest the-site-warns-exactly-once-however-many-renders-it-takes
  (let [row     (named-view "w3.ns/row")
        hiccup  [:ul (list [row {:key {:id 1}}] [row {:key {:id 2}}])]
        first-r (warnings-during #(rf.hicasso.impl.codec/as-element hiccup))
        again   (warnings-during #(rf.hicasso.impl.codec/as-element hiccup))]
    (is (= 1 (count first-r)) "two entity-keyed members of one head are one site")
    (is (= [] again) "a second render of the same site re-fires nothing")))

(deftest a-site-is-the-member-head-and-the-hazard
  (testing "two member heads — two sites"
    (let [a   (named-view "w4.ns/a")
          b   (named-view "w4.ns/b")
          out (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list [a {:key {:id 1}}] [b {:key {:id 2}}])]))]
      (is (= 2 (count out)) "scanning past the first offender is what finds the second")))
  (testing "one member head, two hazards — two sites"
    (let [row (named-view "w5.ns/row")
          out (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key {:id 1}}] [row {:key [1 2]}])]))]
      (is (= 2 (count out))))))

(deftest a-mixed-seq-names-the-first-entity-keyed-member
  (let [row (named-view "w8.ns/row")
        out (warnings-during
              #(rf.hicasso.impl.codec/as-element
                 [:ul (list [row {:key 0}] [row {:key 1}]
                            [row {:key {:id 2}}] [row {:key 3}])]))]
    (is (= 1 (count out)))
    (is (re-find #"first at index 2" (first out))
        "keyed members are scanned past and never named")))

(deftest an-entity-valued-key-warns-about-the-coercion-hazard
  (testing "a map at :key — React string-coerces it, so the child is keyed by
            its own CONTENT and an edit silently remounts the row"
    (let [row  (named-view "w9.ns/row")
          out  (warnings-during
                 #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key {:id 1 :label "a"}}])]))
          line (first out)]
      (is (= 1 (count out)))
      (is (re-find #"^\[hicasso\] Entity-valued :key" line)
          "the console prefix the erasure scan pins")
      (is (re-find #"w9\.ns/row" line) "the member head")
      (is (re-find #"carries a map at :key" line))
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/hicasso-entity-key" line))
      (is (nil? (re-find #":label" line))
          "the VALUE never reaches the console — a cyclic or throwing foreign
           value would blow pr-str inside a diagnostic")))
  (testing "a vector and a set are the same hazard, and two distinct sites"
    (let [row (named-view "w10.ns/row")
          out (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key [1 2]}] [row {:key #{1}}])]))]
      (is (= 2 (count out)))
      (is (re-find #"carries a vector at :key" (first out)))
      (is (re-find #"carries a set at :key" (second out))))))

;; ---------------------------------------------------------------------------
;; THE TOTALITY REPAIR
;;
;; `check-member-key!`'s classification once let every non-nil `:key` that was
;; neither primitive nor a CLJS collection fall out of the check in silence.
;; The foreign JS object is the shape that makes that a bug rather than a gap:
;; every one of them string-coerces to the SAME `[object Object]`, so distinct
;; rows share one key. The rows below pin the collision on our own lowering
;; first, then the warning, then — just as load-bearing — the two shapes
;; deliberately classified SAFE, because a warning that fires on a `uuid` key
;; would teach authors to ignore it.
;; ---------------------------------------------------------------------------

(deftest a-foreign-object-key-collapses-distinct-children-onto-one-key
  (testing "the hazard pinned on OUR lowering rather than argued from React's
            source: two DISTINCT entities mint two elements with ONE key"
    (let [row  (named-view "w24.ns/row")
          seen (atom nil)]
      (warnings-during
        #(reset! seen (child-vec
                        (rf.hicasso.impl.codec/as-element
                          [:ul (list [row {:key #js {:id 1}}]
                                     [row {:key #js {:id 2}}])]))))
      (is (= 2 (count @seen)))
      (is (= "[object Object]" (el-key (first @seen)))
          "React's `key = '' + config.key` reaches Object.prototype.toString")
      (is (= (el-key (first @seen)) (el-key (second @seen)))
          "two entities, one key — React reconciles them as the same child")))
  (testing "and it warns, naming the shape without ever printing the value"
    (let [row  (named-view "w25.ns/row")
          out  (warnings-during
                 #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key #js {:secret "s3cr3t"}}])]))
          line (first out)]
      (is (= 1 (count out)))
      (is (re-find #"w25\.ns/row" line) "the member head")
      (is (re-find #"carries a foreign object at :key" line))
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/hicasso-entity-key" line))
      (is (nil? (re-find #"s3cr3t" line))
          "the VALUE never reaches the console"))))

(deftest the-diagnostic-holds-on-a-value-that-would-blow-a-printer
  (testing "a cyclic foreign object warns rather than throwing inside the
            warning — the check names shapes, so no arm of it can reach the
            value that would recur"
    (let [row    (named-view "w26.ns/row")
          cyclic (js-obj "id" 1)]
      (unchecked-set cyclic "self" cyclic)
      (let [out (warnings-during
                  #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key cyclic}])]))]
        (is (= 1 (count out)))
        (is (re-find #"carries a foreign object at :key" (first out)))))))

(deftest every-other-non-primitive-key-shape-is-named-rather-than-dropped
  (testing "the shapes that used to fall through the missing `:else`"
    (doseq [[label k pattern]
            [["boolean"  true         #"carries a boolean at :key"]
             ["function" (fn [] 1)    #"carries a function at :key"]
             ["date"     (js/Date. 0) #"carries a foreign object at :key"]
             ["js-array" #js [1 2]    #"carries a foreign object at :key"]]]
      (let [row (named-view (str "w27.ns/" label))
            out (warnings-during
                  #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key k}])]))]
        (is (= 1 (count out)) (str "a " label " key must not fall through"))
        (is (re-find pattern (first out)) (str "a " label " key is named"))))))

(deftest the-object-key-shapes-deliberately-classified-safe-stay-silent
  (testing "a uuid string-coerces to the identifier the author MEANS, so it is
            the canonical entity key and warning on it would be the false
            positive that teaches authors to ignore the warning"
    (let [row  (named-view "w28.ns/row")
          ids  [(uuid "00000000-0000-0000-0000-000000000001")
                (uuid "00000000-0000-0000-0000-000000000002")]
          seen (atom nil)]
      (is (= [] (warnings-during
                  #(reset! seen
                           (child-vec
                             (rf.hicasso.impl.codec/as-element
                               [:ul (for [id ids] [row {:key id}])]))))))
      (is (= "00000000-0000-0000-0000-000000000001" (el-key (first @seen)))
          "the coercion is the identity itself — this is WHY it is classified safe")
      (is (not= (el-key (first @seen)) (el-key (second @seen)))
          "distinct entities keep distinct keys, which is the whole test")))
  (testing "a symbol coerces to its own NAME, exactly as the keyword that
            `plain-key?` already admits does"
    (let [row (named-view "w29.ns/row")]
      (is (= [] (warnings-during
                  #(rf.hicasso.impl.codec/as-element [:ul (list [row {:key 'a}] [row {:key 'ns/b}])])))))))

(deftest the-scope-line-holds-in-both-directions
  (testing "native-tag members are React's beat — there is no boundary head to name"
    (is (= [] (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (for [i (range 3)] [:li {:key {:id i}} i])])))))
  (testing "host-headed members are silent"
    (is (= [] (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list [a-host {:key {:id 1}}])])))))
  (testing "strings, numbers and nils in a seq are not elements"
    (is (= [] (warnings-during
                #(rf.hicasso.impl.codec/as-element [:ul (list "a" 1 nil false)])))))
  (testing "a headless vector is somebody else's refusal — the check tolerates
            it silently and leaves `vec->element`'s loud error to speak"
    (is (= [] (warnings-during
                #(try (rf.hicasso.impl.codec/as-element [:ul (list [])])
                      (catch :default _ nil)))))))

(deftest every-parent-class-reaches-the-check-through-the-one-site
  (testing "a fragment parent"
    (let [row (named-view "w15.ns/row")]
      (is (= 1 (count (warnings-during
                        #(rf.hicasso.impl.codec/as-element [:<> (list [row {:key {:id 1}}])])))))))
  (testing "a host parent — this is what the [:>] crossing will inherit"
    (let [row (named-view "w16.ns/row")]
      (is (= 1 (count (warnings-during
                        #(rf.hicasso.impl.codec/as-element [a-host {} (list [row {:key {:id 1}}])])))))))
  (testing "a nested seq is checked at its own level, one level at a time"
    (let [row (named-view "w17.ns/row")]
      (is (= 1 (count (warnings-during
                        #(rf.hicasso.impl.codec/as-element [:ul (list (list [row {:key {:id 1}}]))]))))))))

(deftest the-crossing-into-a-boundary-warns-where-nothing-else-can
  (testing "`[a-view {} (for …)]` — realize-children flattens the seq into
            direct arguments, which React marks validated and never warns
            about, so this call site is the only signal there is"
    (let [outer (named-view "w18.ns/outer")
          row   (named-view "w18.ns/row")
          out   (warnings-during
                  #(rf.hicasso.impl.codec/as-element [outer {} (for [i (range 3)] [row {:key {:id i}}])]))]
      (is (= 1 (count out)))
      (is (re-find #"w18\.ns/row" (first out)))))
  (testing "a keyed crossing seq is silent"
    (let [outer (named-view "w19.ns/outer")
          row   (named-view "w19.ns/row")]
      (is (= [] (warnings-during
                  #(rf.hicasso.impl.codec/as-element [outer {} (for [i (range 3)] [row {:key i}])]))))))
  (testing "a crossing seq of native members is silent — the presence fixture's
            own shape, and every `[presence {…} (for … [:div.toast {:key id}])]`
            the guide teaches"
    (let [tray (named-view "w20.ns/tray")]
      (is (= [] (warnings-during
                  #(rf.hicasso.impl.codec/as-element
                     [tray {:timeout-ms 300}
                      (for [i (range 3)]
                        [:div.toast {:key i} "x"])])))))))
