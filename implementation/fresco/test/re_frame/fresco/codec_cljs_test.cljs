(ns re-frame.fresco.codec-cljs-test
  "THE HICCUP CODEC, tested at the element.

  Every assertion here reads a React element's `type`, `props` and `key`
  rather than rendered DOM. That is the right altitude for a codec and it
  is also the cheap one: the question this file answers is *what did the
  codec build*, and a mounted tree would answer it through two more
  layers that have their own opinions. What React then does with these
  elements is the arms' browser witness set, not this file's."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
            [re-frame.fresco.slot-corpus :as rf.fresco.slot-corpus]
            ["react" :as react]))

(use-fixtures :each {:before (fn [] (rf.fresco.impl.codec/reset-caches!))})

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

(deftest the-id-and-class-shorthand-is-parsed-and-folded-into-the-props
  (testing "id alone"
    (is (= "main" (prop (rf.fresco.impl.codec/as-element [:div#main]) "id"))))
  (testing "classes alone, dot-separated"
    (is (= "wide tall" (prop (rf.fresco.impl.codec/as-element [:div.wide.tall]) "className"))))
  (testing "id then classes, the one order the shorthand parses"
    (let [e (rf.fresco.impl.codec/as-element [:section#main.wide.tall])]
      (is (= "section" (el-type e)))
      (is (= "main" (prop e "id")))
      (is (= "wide tall" (prop e "className"))))))

(deftest the-caches-refuse-the-prototype-poisoning-keys
  (testing "a tag named __proto__ parses without being cached"
    (is (= "__proto__" (el-type (rf.fresco.impl.codec/as-element [:__proto__]))))
    (is (= 0 (:tags (rf.fresco.impl.codec/cache-sizes)))))
  (testing "a prop named __proto__ is neither cached nor set"
    (let [e (rf.fresco.impl.codec/as-element [:div {:__proto__ "nope"}])]
      (is (= [] (prop-names e))))
    (is (= 3 (:props (rf.fresco.impl.codec/cache-sizes)))
        "the three seeded entries and nothing else — the refusal is on the write")))

(deftest a-literal-named-after-an-inherited-property-cannot-be-served-one
  ;; Both caches are `Object.create(null)`, so a lookup can
  ;; only ever answer an own property. Keyed against `#js {}` these
  ;; literals would read `Object.prototype`'s OWN members — a function
  ;; where a ParsedTag or a PropSlot belongs — and the codec would emit
  ;; whatever that function's `.tag` / `.js-name` happened to be, which
  ;; is `undefined`. There is no `hasOwnProperty.call` lookup guard;
  ;; nothing but the cache's own construction answers this, so it is
  ;; witnessed rather than assumed.
  (testing "a TAG named after an Object.prototype member parses as itself, and
            its second render reads its own cached entry"
    (rf.fresco.impl.codec/reset-caches!)
    (rf.fresco.impl.codec/as-element [:toString])
    (is (= 1 (:tags (rf.fresco.impl.codec/cache-sizes))))
    (is (= "toString" (el-type (rf.fresco.impl.codec/as-element [:toString]))))
    (is (= 1 (:tags (rf.fresco.impl.codec/cache-sizes)))))
  (testing "a PROP named after an Object.prototype member converts as itself"
    (rf.fresco.impl.codec/reset-caches!)
    (let [e (rf.fresco.impl.codec/as-element [:div {:toString "a" :valueOf "b" :hasOwnProperty "c"}])]
      (is (= ["hasOwnProperty" "toString" "valueOf"] (prop-names e)))
      (is (= "a" (prop e "toString")))
      (is (= "b" (prop e "valueOf")))
      (is (= "c" (prop e "hasOwnProperty"))))
    (testing "and the second element reads the cached slots"
      (is (= "d" (prop (rf.fresco.impl.codec/as-element [:div {:toString "d"}]) "toString"))))))

;; ---------------------------------------------------------------------------
;; Prop names
;; ---------------------------------------------------------------------------

(deftest the-codecs-cached-slot-is-the-shared-rules-answer
  (testing "THE OTHER HALF OF THE EQUIVALENCE PIN. The rule
            lives in `.cljc` so the JVM codemod and this runtime share one
            implementation, and `slot-cljs-test` runs its corpus on both
            hosts. That pins the RULE. What it cannot see is this codec
            putting something else in front of the rule — a
            hand-written seed in `prop-cache`, a cache keyed so two
            spellings share one entry, a local copy of the rule — each
            of which would answer a slot the codemod would never predict.
            So the codec's own doors are asked the same corpus: whatever
            the rule answers, `cached-prop-name` and `canonical-slot`
            answer, for every spelling the codec accepts."
    (doseq [[k expected] rf.fresco.slot-corpus/corpus]
      (is (= expected (rf.fresco.impl.codec/cached-prop-name k) (rf.fresco.impl.codec/canonical-slot k))
          (str "codec slot for " (pr-str k))))))

(deftest the-cached-classification-is-spelling-aware-and-no-spelling-poisons-another
  ;; The prop cache's entries carry the POSITION CLASSIFICATION
  ;; beside the React name, and the entry is keyed by name — so a symbol
  ;; spelled `on-click` shares the keyword's entry while `event-prop?`
  ;; answers false for it. The flag must therefore be minted from the NAME
  ;; and applied spelling-aware at the call site; either shortcut turns
  ;; whichever spelling renders first into the answer for both, which is
  ;; exactly the order dependence the corpus above rules out for the name.
  ;; Both directions, both from a cold cache.
  (testing "a symbol minted FIRST does not cost the keyword its event lowering"
    (rf.fresco.impl.codec/reset-caches!)
    (rf.fresco.impl.codec/cached-prop-name 'on-click)
    (let [!seen (atom [])
          e     (rf.fresco.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                   (fn [] (rf.fresco.impl.codec/as-element [:button {:on-click [:go]}])))]
      (is (fn? (prop e "onClick")) "the keyword position still lowers to a handler")
      ((prop e "onClick") #js {:target #js {}})
      (is (= [[:go]] @!seen))))
  (testing "a keyword minted FIRST does not make the symbol an event position"
    (rf.fresco.impl.codec/reset-caches!)
    (rf.fresco.impl.codec/cached-prop-name :on-click)
    (let [e (rf.fresco.impl.codec/as-element [:div {'on-click [:go]}])]
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
    (is (= "a b" (prop (rf.fresco.impl.codec/as-element [:div.a {:x/class "b"}]) "className"))))
  (testing "and so does every other spelling the codec accepts"
    (doseq [k [:class :className "class" "className" 'class :x/class :class-name]]
      (is (= "a b" (prop (rf.fresco.impl.codec/as-element [:div.a {k "b"}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "the class value is coerced at the slot, so a collection joins
            whatever key carried it — coercing in the map surgery instead
            would reach only `:class` and `:className`"
    (doseq [k [:class "className" :x/class]]
      (is (= "a x y" (prop (rf.fresco.impl.codec/as-element [:div.a {k ["x" nil :y]}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "two spellings of the one slot COMPOSE rather than the last write
            silently winning — a dropped class is the failure class this
            rule exists to prevent"
    (is (= "a b c" (prop (rf.fresco.impl.codec/as-element [:div.a {:class "b" :x/class "c"}]) "className")))))

(deftest the-shorthand-id-loses-to-an-explicit-one-however-it-is-spelled
  ;; The other half of the spelling-aware rule. `#tag` is the weakest
  ;; source of an id there is, and stating "an explicit :id wins" on the
  ;; RAW KEY is what these rows forbid: `[:div#tag {"id" "explicit"}]`
  ;; then keeps BOTH, lands both on React's one `id` slot, and leaves
  ;; which one survives to the order the props map happens to iterate in.
  (testing "written on the element"
    (doseq [k [:id "id" 'id :x/id]]
      (is (= "explicit" (prop (rf.fresco.impl.codec/as-element [:div#tag {k "explicit"}]) "id"))
          (str "spelled " (pr-str k)))))
  (testing "and the shorthand still lands when nothing claims the slot"
    (is (= "tag" (prop (rf.fresco.impl.codec/as-element [:div#tag {:title "t"}]) "id"))))
  (testing "a near miss is not the id slot and keeps its own name"
    (let [e (rf.fresco.impl.codec/as-element [:div#tag {:data-id "d" :ids "many"}])]
      (is (= "tag" (prop e "id")))
      (is (= "d" (prop e "data-id")))
      (is (= "many" (prop e "ids"))))))

(deftest prop-values-are-converted-in-the-shapes-react-wants
  (testing "a style map becomes a JS object with camelCased keys"
    (let [style (prop (rf.fresco.impl.codec/as-element [:div {:style {:font-size "12px" :color :red}}]) "style")]
      (is (= "12px" (aget style "fontSize")))
      (is (= "red" (aget style "color")) "a keyword value stringifies")))
  (testing "a CSS custom property survives inside style"
    (let [style (prop (rf.fresco.impl.codec/as-element [:div {:style {:--gap "4px"}}]) "style")]
      (is (= "4px" (aget style "--gap")))))
  (testing "a function passes through BY IDENTITY, so downstream bail-outs still work"
    (let [f (fn [_])]
      (is (identical? f (prop (rf.fresco.impl.codec/as-element [:div {:on-click f}]) "onClick")))))
  (testing "a ref is an ordinary prop — callback refs are legal on native tags"
    (let [r (fn [_node])]
      (is (identical? r (prop (rf.fresco.impl.codec/as-element [:div {:ref r}]) "ref")))))
  (testing "scalars are left alone"
    (let [e (rf.fresco.impl.codec/as-element [:input {:value "x" :disabled true :tab-index 3}])]
      (is (= "x" (prop e "value")))
      (is (= true (prop e "disabled")))
      (is (= 3 (prop e "tabIndex"))))))

;; ---------------------------------------------------------------------------
;; Keys — `:key` in the props position, never metadata (HD-016)
;; ---------------------------------------------------------------------------

(deftest a-native-elements-key-is-key-in-the-props-map-and-is-not-an-attribute
  (let [e (rf.fresco.impl.codec/as-element [:li {:key 7 :class "row"}])]
    (is (= "7" (el-key e)) "React stringifies keys")
    (is (= ["className"] (prop-names e)) ":key never reaches the DOM props")))

;; ---------------------------------------------------------------------------
;; Children
;; ---------------------------------------------------------------------------

(deftest children-are-realized-once-and-flattened-one-level
  (testing "one child arrives as the single child, not an array"
    (let [e (rf.fresco.impl.codec/as-element [:p "text"])]
      (is (= "text" (children e)))))
  (testing "several children arrive in order"
    (let [e (rf.fresco.impl.codec/as-element [:p "a" "b" "c"])]
      (is (= ["a" "b" "c"] (child-vec e)))))
  (testing "a seq is expanded, and an interior nil does NOT truncate it"
    (let [e (rf.fresco.impl.codec/as-element [:ul (for [i (range 4)] (when (odd? i) [:li {:key i} i]))])
          expanded (vec (children e))]
      (is (= 4 (count expanded)) "all four slots survive the single pass")
      (is (nil? (nth expanded 0)))
      (is (= "li" (el-type (nth expanded 1))))
      (is (nil? (nth expanded 2)))
      (is (= "li" (el-type (nth expanded 3))))))
  (testing "nil and false render nothing"
    (is (nil? (rf.fresco.impl.codec/as-element nil)))
    (is (nil? (rf.fresco.impl.codec/as-element false)))
    (is (= [nil nil "x"] (child-vec (rf.fresco.impl.codec/as-element [:p nil false "x"])))))
  (testing "true is an error"
    (try
      (rf.fresco.impl.codec/as-element true)
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/fresco-true-child (:rf.error/id (ex-data e)))))))
  (testing "an existing React element is a legal child anywhere"
    (let [foreign (react/createElement "b" nil "bold")
          e       (rf.fresco.impl.codec/as-element [:p foreign])]
      (is (identical? foreign (children e))))))

;; ---------------------------------------------------------------------------
;; Fragments
;; ---------------------------------------------------------------------------

(deftest a-fragment-forwards-its-ref-to-react-by-identity
  (testing "React reads a fragment's ref off `element.props.ref` — its own
            `coerceRef` does, and its `validateFragmentProps` names `key`,
            `ref` and `children` as the whole of what a fragment accepts. So
            the handle has to be IN THE PROPS, and it has to be the author's
            own object: React detaches and reattaches on a changed ref
            identity, so a handle rewrapped per render would re-run a
            callback ref and its cleanup on every commit."
    (let [f (fn [_instance] nil)
          e (rf.fresco.impl.codec/as-element [:<> {:ref f} [:li "a"]])]
      (is (identical? (.-Fragment react) (el-type e)))
      (is (identical? f (prop e "ref")))))
  (testing "an object ref crosses the same way — the codec claims nothing
            about the slot's value-space here either"
    (let [r (react/createRef)
          e (rf.fresco.impl.codec/as-element [:<> {:ref r} [:li "a"]])]
      (is (identical? r (prop e "ref")))))
  (testing "and by the SLOT rather than the spelling, exactly as a tag's ref
            is read — `ref` is an attribute, and every rule about one is
            asked of `canonical-slot`"
    (let [f (fn [_instance] nil)]
      (doseq [k ["ref" 'ref :x/ref]]
        (is (identical? f (prop (rf.fresco.impl.codec/as-element [:<> {k f} [:li "a"]]) "ref"))
            (str "ref, spelled " (pr-str k)))))))

(deftest a-fragment-emits-the-two-slots-react-accepts-there-and-nothing-else
  (testing "no attr map, and no ref: children alone. A ref slot standing
            empty on every fragment on the page would be a prop React has to
            read and reject"
    (is (= ["children"] (prop-names (rf.fresco.impl.codec/as-element [:<> [:li "a"]])))))
  (testing "a fragment written with a key emits no `key` PROP — the key
            addresses the element, and React strips it in `createElement`"
    (let [e (rf.fresco.impl.codec/as-element [:<> {:key "k"} [:li "a"]])]
      (is (= ["children"] (prop-names e)))
      (is (= "k" (el-key e)))))
  (testing "a ref and a key together: the key on the element, the ref in the
            props, both from one attr map"
    (let [f (fn [_instance] nil)
          e (rf.fresco.impl.codec/as-element [:<> {:key "k" :ref f} [:li "a"]])]
      (is (= "k" (el-key e)))
      (is (= ["children" "ref"] (prop-names e)))
      (is (identical? f (prop e "ref")))))
  (testing "and an attribute React would refuse on a fragment is dropped
            before React sees it — the fragment forwards its ref, and is
            not open to attributes"
    (let [e (rf.fresco.impl.codec/as-element [:<> {:class "x" :on-click [:evt]} [:li "a"]])]
      (is (= ["children"] (prop-names e)))))
  (testing "a nil ref sets no slot, the same `when-some` reading `:key` gets"
    (is (= ["children"] (prop-names (rf.fresco.impl.codec/as-element [:<> {:ref nil} [:li "a"]]))))))

;; ---------------------------------------------------------------------------
;; Boundaries — the HD-016 head rules
;; ---------------------------------------------------------------------------

(defn- a-view
  "Stands in for a `defview` product: an arm mints the function component
  once, at definition, and marks it. The codec never wraps it, which is
  why HD-004's cached-component-head has nothing to do here."
  [_js-props]
  nil)

(rf.fresco.impl.codec/mark-boundary! a-view)

(deftest a-boundarys-key-is-extracted-before-the-body-sees-props
  (let [e (rf.fresco.impl.codec/as-element [a-view {:key 3 :id 7}])]
    (is (identical? a-view (el-type e)))
    (is (= "3" (el-key e)))
    (is (= {:id 7} (prop e "rfProps")) "one CLJS props map; :key is React's contract, not the body's")))

(deftest a-boundarys-children-arrive-as-a-realized-vector-of-hiccup
  (testing "trailing forms become (:children props)"
    (let [e (rf.fresco.impl.codec/as-element [a-view {:id 7} [:li "a"] [:li "b"]])]
      (is (= {:id 7 :children [[:li "a"] [:li "b"]]} (prop e "rfProps")))))
  (testing "a seq child is realized once and flattened exactly one level"
    (let [e (rf.fresco.impl.codec/as-element [a-view {} (for [i (range 2)] [:li i])])]
      (is (= [[:li 0] [:li 1]] (:children (prop e "rfProps"))))))
  (testing "a boundary with no props map still works"
    (is (= {} (prop (rf.fresco.impl.codec/as-element [a-view]) "rfProps")))))

(deftest a-plain-function-in-head-position-is-a-loud-error
  (let [helper (fn [_] [:div])]
    (try
      (rf.fresco.impl.codec/as-element [helper {}])
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/fresco-bad-head (:rf.error/id (ex-data e))))))))

(deftest an-empty-hiccup-vector-and-a-nonsense-head-are-loud-errors
  (is (= :rf.error/fresco-empty-vector
         (try (rf.fresco.impl.codec/as-element []) nil (catch :default e (:rf.error/id (ex-data e))))))
  (is (= :rf.error/fresco-bad-head
         (try (rf.fresco.impl.codec/as-element [42 {}]) nil (catch :default e (:rf.error/id (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter
;; ---------------------------------------------------------------------------

(deftest a-structural-slot-is-recognised-in-every-spelling-the-codec-accepts
  (testing "the mechanism the structural-slot filter rests on: the slot
            a key EMITS INTO, not the key it was written as. React's key and
            ref are reachable as a keyword, a string, a symbol and a
            namespaced keyword, and a rule that names `#{:key :ref}` sees
            exactly one of the four."
    (doseq [k [:key "key" 'key :x/key :re-frame.fresco/key]]
      (is (true? (rf.fresco.impl.codec/structural-slot? k)) (str "key, spelled " (pr-str k))))
    (doseq [k [:ref "ref" 'ref :x/ref]]
      (is (true? (rf.fresco.impl.codec/structural-slot? k)) (str "ref, spelled " (pr-str k))))
    (doseq [k [:id :class :on-click :data-key "aria-ref" :keyboard]]
      (is (false? (rf.fresco.impl.codec/structural-slot? k))
          (str "and nothing else is structural: " (pr-str k)))))
  (testing "the filter drops every one of them and returns the map by
            identity when there is nothing to drop"
    (is (= {:class "x"} (rf.fresco.impl.codec/without-structural
                          {:class "x" :key 1 "key" 2 :x/key 3 :ref (fn [_]) "ref" 4 :x/ref 5})))
    (let [clean {:class "x" :id "y"}]
      (is (identical? clean (rf.fresco.impl.codec/without-structural clean))))))

;; ---------------------------------------------------------------------------
;; `:ref` — React's own slot, forwarded untouched
;; ---------------------------------------------------------------------------

(deftest a-ref-crosses-by-identity-however-it-is-spelled
  (testing "the ref position is excluded from callback lowering at the SLOT
            rather than at the key, so `\"ref\"` and `:x/ref` reach React's
            ref slot by identity too — wrapping would both forbid a dispatch
            that is legitimate there and change the identity React uses to
            decide whether to re-attach the node"
    (let [f (rf.fresco.impl.intent/callback (fn [_node]))]
      (is (identical? f (prop (rf.fresco.impl.codec/as-element [:div {"ref" f}]) "ref")))
      (is (identical? f (prop (rf.fresco.impl.codec/as-element [:div {:x/ref f}]) "ref")))))
  (testing "every :ref value passes through the ordinary conversion — the
            codec claims nothing about the slot's value-space"
    (is (nil? (prop (rf.fresco.impl.codec/as-element [:div {:ref nil}]) "ref")))
    (is (= "legacy" (prop (rf.fresco.impl.codec/as-element [:div {:ref "legacy"}]) "ref")))
    (is (some? (prop (rf.fresco.impl.codec/as-element [:div {:ref {:a 1}}]) "ref")))))

;; ---------------------------------------------------------------------------
;; A presence override no tray reached
;; ---------------------------------------------------------------------------
;;
;; `impl.presence/with-phase` strips `::motion/mounting` / `::motion/unmounting`
;; off a tray's DIRECT children and merges the phase's map in their place.
;; One that reaches a prop walk was written somewhere no tray walks, and the
;; walk skips it: it is Fresco's private key, and `(name k)` would
;; otherwise have emitted it as an ordinary `mounting` attribute.

(defn- an-override-crossing
  "A foreign component, for the crossing arm below. Local because
  [[a-foreign-component]] is declared further down this file."
  [_props]
  nil)

(declare raw-props)

(deftest an-override-outside-a-trays-direct-child-is-skipped-rather-than-emitted
  (testing "a native node: the key is dropped and no attribute reaches the page"
    (let [e (rf.fresco.impl.codec/as-element [:div {:re-frame.fresco.motion/mounting {:class "in"} :id "x"}])]
      (is (= ["id"] (vec (js/Object.keys (.-props e)))))))
  (testing "both keys, and at any depth — a tray walks its direct children and
            nothing below them, so a grandchild is as unreachable as an
            element with no tray above it at all"
    (let [e     (rf.fresco.impl.codec/as-element [:section [:div {:re-frame.fresco.motion/unmounting {:class "out"}}]])
          child (aget (.-props e) "children")]
      (is (= [] (vec (js/Object.keys (.-props child)))))))
  (testing "the crossing skips it at the same position — a [:>] head that IS a
            tray's direct child has its overrides stripped and merged exactly
            as a native node does, so one arriving here is misplaced for the
            identical reason"
    (let [e (rf.fresco.impl.codec/as-element [:> an-override-crossing
                               {:re-frame.fresco.motion/mounting {:class "in"} :size 1}])]
      (is (= ["size"] (vec (js/Object.keys (raw-props e))))))))

(deftest the-override-skip-claims-the-two-private-keywords-and-nothing-else
  (testing "a bare `:mounting` is an ordinary author attribute the walk emits
            under its own name; the skip is on the EXACT keyword and not on
            the canonical slot, because these two are Fresco's own private
            keys where `ref` and `className` are React's positions"
    (is (= "x" (prop (rf.fresco.impl.codec/as-element [:div {:mounting "x"}]) "mounting")))
    (is (= "x" (prop (rf.fresco.impl.codec/as-element [:div {:unmounting "x"}]) "unmounting")))
    (is (= "x" (prop (rf.fresco.impl.codec/as-element [:div {:x/mounting "x"}]) "mounting"))
        "a namespaced keyword with the same name is not the private key either")
    (is (= "x" (aget (raw-props (rf.fresco.impl.codec/as-element [:> an-override-crossing {:mounting "x"}])) "mounting"))
        "and the same at the crossing, where a foreign ABI may genuinely
         name a prop `mounting`")))

;; ---------------------------------------------------------------------------
;; The codec's one policy call — intent lowering happens inside the walk
;; ---------------------------------------------------------------------------

(deftest a-controlled-field-lowers-its-marker-through-the-codec
  (let [!seen (atom [])
        e     (rf.fresco.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                 (fn [] (rf.fresco.impl.codec/as-element
                                         [:input {:value "milk"
                                                  :on-input [:todo.ui/edit 7 :re-frame.fresco/value]}])))]
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

(def ^:private a-host (rf.fresco.impl.codec/mint-host! "AHost" a-foreign-component))

(defn- host-prop
  "The value the codec emitted at `slot` for a one-prop host crossing.
  Reads the GATE element's props, which are the props object the foreign
  component receives — the gate forwards it verbatim."
  [k v slot]
  (prop (rf.fresco.impl.codec/as-element [a-host {k v}]) slot))

(deftest a-namespaced-keyword-keeps-its-namespace-at-a-host-prop
  (testing "`(name :theme/dark)` is \"dark\", so stringifying here would
            discard the namespace on its way to a hosted React-context
            provider and deliver a plausible string that had lost half its
            identity."
    (is (= :theme/dark (host-prop :value :theme/dark "value"))
        "the keyword crosses whole — not \"dark\", not \"theme/dark\"")))

(deftest a-named-value-bound-for-an-html-attribute-still-stringifies
  (testing "id and role: the value is an HTML attribute wherever the
            component passes it on, and a string is the only representation
            there is (the class slot's own coercion is the next deftest's)"
    (is (= "greeting" (host-prop :id :greeting "id")))
    (is (= "dialog" (host-prop :role :dialog "role"))))
  (testing "the data-* / aria-* families, by prefix"
    (is (= "row" (host-prop :data-kind :row "data-kind")))
    (is (= "close" (host-prop :aria-label :close "aria-label"))))
  (testing "the native walk drops a class keyword's namespace the same way"
    (is (= "dark" (prop (rf.fresco.impl.codec/as-element [:div {:class :theme/dark}]) "className")))))

;; ---------------------------------------------------------------------------
;; The class slot is a POSITION at the crossing too
;; ---------------------------------------------------------------------------

(deftest a-class-collection-crosses-as-a-class-string-at-a-host-and-at-a-tag
  (testing "ONE authored shape, ONE answer. The class slot has a coercion of
            its own — `class-names` — and it is taken at the crossing as
            well as at the native position. Sent through `clj->js` instead,
            `{:class [\"a\" nil :b]}` would hand the foreign component a JS
            array, which React writes to the DOM as \"a,,b\" wherever the
            component passes it on: nothing would throw and the styling
            would simply be wrong."
    (let [crossed (host-prop :class ["a" nil :b] "className")
          native  (prop (rf.fresco.impl.codec/as-element [:div {:class ["a" nil :b]}]) "className")]
      (is (string? crossed)
          "a class string, not the JS array clj->js would build")
      (is (= "a b" crossed))
      (is (= native crossed)
          "and it is the SAME answer the native walk gives — the rule for
           the named value at this slot, applied to the arm a collection
           takes")))
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
    (let [e (rf.fresco.impl.codec/as-element [a-host {:class "a" :x/class ["b" :c]}])]
      (is (= "a b c" (prop e "className"))))
    ;; the crossing rule as a worked case — the guide states it as a rule
    ;; (docs/core/fresco/09-interop.md §Crossing rules), without this example
    (is (= "btn on wide"
           (prop (rf.fresco.impl.codec/as-element [a-host {:class ["btn" nil :on] :className "wide"}])
                 "className"))))
  (testing "the slot's other conversions: a string is verbatim, a keyword
            stringifies, and a namespaced keyword drops its namespace HERE
            and only here"
    (is (= "primary" (host-prop :class "primary" "className")))
    (is (= "primary" (host-prop :class :primary "className")))
    (is (= "dark" (host-prop :class :theme/dark "className")))
    (is (nil? (host-prop :class nil "className"))))
  (testing "and the coercion is the class slot's ALONE — `id` and `role`
            hand a collection to `clj->js` at BOTH positions, so the two
            positions agree there as well"
    (is (array? (host-prop :id ["a" "b"] "id")))
    (is (array? (prop (rf.fresco.impl.codec/as-element [:div {:id ["a" "b"]}]) "id"))
        "the native walk's own answer, quoted so the claim is not asserted
         about a function nobody ran")))

(deftest a-host-infers-the-contract-from-the-spelling-exactly-as-a-native-tag
  ;; There is no declaration roster. `host-entry`'s `:else`
  ;; arm IS `intent/lower-prop`, the native walk's own classifier, so a
  ;; host prop means what the same prop means on a native tag.
  (let [!seen (atom [])
        d     (fn [ev] (swap! !seen conj ev))]
    (testing "an on*-spelled prop is an EVENT position with no declaration
              at all: an h/event there dispatches its return"
      (let [f (rf.fresco.impl.intent/with-frame d (fn [] (host-prop :on-pick (rf.fresco.impl.intent/callback (fn [x] [:row/pick x])) "onPick")))]
        (f 1)
        (is (= [[:row/pick 1]] @!seen))))
    (testing "and so are the vector and key-map spellings, exactly as at a
              native tag"
      (reset! !seen [])
      (let [f (rf.fresco.impl.intent/with-frame d (fn [] (host-prop :on-close [:dialog/cancel] "onClose")))]
        (f #js {:target #js {}})
        (is (= [[:dialog/cancel]] @!seen)))
      (reset! !seen [])
      (let [f (rf.fresco.impl.intent/with-frame d (fn [] (host-prop :on-key-down {"Enter" [:go]} "onKeyDown")))]
        (f #js {:key "Enter" :isComposing false :target #js {}})
        (is (= [[:go]] @!seen))))
    (testing "any other prop is a RENDER position: the h/event takes the
              render wrapper, which rebinds the supplying boundary's frame
              and dispatch for the call, so a row it returns dispatches
              into that frame when clicked — after the render extent has
              unwound"
      (reset! !seen [])
      (let [render-row (rf.fresco.impl.intent/with-frame d
                         (fn [] (host-prop :render-row
                                           (rf.fresco.impl.intent/callback
                                             (fn [i] (rf.fresco.impl.codec/as-element [:li {:on-click [:row/pick i]}])))
                                           "renderRow")))
            row        (render-row 9)]
        (is (nil? rf.fresco.impl.intent/*dispatch*) "precondition: no ambient dispatch here")
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
      (let [fluent    (rf.fresco.impl.codec/mint-host! "FluentList" a-foreign-component
                                        {:callbacks {:on-render-item :render}})
            render-fn (rf.fresco.impl.intent/with-frame d
                        (fn [] (prop (rf.fresco.impl.codec/as-element
                                       [fluent {:on-render-item
                                                (rf.fresco.impl.intent/callback
                                                  (fn [item] (rf.fresco.impl.codec/as-element [:li {:on-click [:row/pick item]}])))}])
                                     "onRenderItem")))
            row       (render-fn "x")]
        (is (= "li" (.-type row)) "the render output came back")
        ((aget (.-props row) "onClick") #js {:target #js {}})
        (is (= [[:row/pick "x"]] @!seen))))
    (testing "an override may also say :event where the spelling infers
              render, and the vector spelling then lowers as at a native
              event position"
      (reset! !seen [])
      (let [h (rf.fresco.impl.codec/mint-host! "PickHost" a-foreign-component {:callbacks {:pick :event}})
            f (rf.fresco.impl.intent/with-frame d (fn [] (prop (rf.fresco.impl.codec/as-element [h {:pick [:row/pick 2]}]) "pick")))]
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
      (let [h (rf.fresco.impl.codec/mint-host! "ColumnsHost" a-foreign-component {:callbacks {:render-row :render}})]
        (is (array? (prop (rf.fresco.impl.codec/as-element [h {:render-row ["a" "b"]}]) "renderRow")))))
    (testing "and a contract outside the two is a bad declaration, refused
              at mint where the author's stack is the declaration"
      (let [data (try (rf.fresco.impl.codec/mint-host! "HandlerHost" a-foreign-component
                                        {:callbacks {:on-pick :handler}})
                      nil
                      (catch :default e (ex-data e)))]
        (is (= :rf.error/fresco-bad-host-declaration (:rf.error/id data))
            ":handler is not a contract; the two are :event and :render")))))

(deftest every-other-host-prop-value-crosses-exactly-as-it-did
  (testing "collections through clj->js, whose nested keys keep the spelling
            the author wrote"
    (let [o (host-prop :options {:pageSize 10} "options")]
      (is (= 10 (aget o "pageSize"))))))

;; ---------------------------------------------------------------------------
;; What "callback refs only" actually is
;; ---------------------------------------------------------------------------

(deftest an-object-ref-crosses-by-identity-at-both-positions
  (testing "An object ref is a RECORD rather than a refusal. HD-016
            reads 'callback refs only', but the codec has no refusal
            branch at the ref slot: `:ref` crosses untouched whatever its
            value, a vector included (the last rows below). An object ref
            is not broken. React 19
            carries `ref` as an ordinary prop, so `(react/createRef)`
            attaches and detaches exactly as React documents it. It is
            untaught rather than illegal, and refusing a spelling that
            works correctly is friction rather than safety.

            This witness is what makes that a decision instead of an
            oversight: a later refusal cannot land silently, because it has
            to delete these rows first."
    (let [r (react/createRef)]
      (is (identical? r (prop (rf.fresco.impl.codec/as-element [:div {:ref r}]) "ref"))
          "a native tag")
      (is (identical? r (host-prop :ref r "ref"))
          "and a defhost crossing — HD-011's conversion parity, which is
           what makes 'enforced at one crossing and not the other' the
           outcome design C excludes")))
  (testing "and it is one rule at one slot, so an alternate spelling of the
            ref position answers the same way"
    (let [r (react/createRef)]
      (is (identical? r (prop (rf.fresco.impl.codec/as-element [:div {"ref" r}]) "ref")))
      (is (identical? r (host-prop :x/ref r "ref")))))
  (testing "and a VECTOR crosses at both as the author wrote it — nothing
            about the slot's value-space is reserved"
    (let [v [::autosize {:max-rows 8}]]
      (is (some? (prop (rf.fresco.impl.codec/as-element [:div {:ref v}]) "ref")))
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
;; [[re-frame.fresco.raw-escape-dom-cljs-test]], which needs a server renderer).

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
  (aget (raw-props (rf.fresco.impl.codec/as-element [:> a-foreign-component {k v}])) slot))

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
  (testing "THE MIS-PARSE. A `hiccup-tag?` that accepted any keyword that
            is not `:<>` would route `[:> Foo {}]` to the native path and
            ask React for an element literally named `>`"
    (let [e (rf.fresco.impl.codec/as-element [:> a-foreign-component {}])]
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
      (is (= "[:>]" (.-displayName (el-type (rf.fresco.impl.codec/as-element [computed a-foreign-component {}]))))))))

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
          door    (.-props (rf.fresco.impl.codec/as-element [a-host corpus]))
          raw     (raw-props (rf.fresco.impl.codec/as-element [:> a-foreign-component corpus]))]
      (is (= (vec (sort (js/Object.keys door))) (vec (sort (js/Object.keys raw))))
          "prop for prop, the same emitted slots")
      (doseq [k (js/Object.keys door)]
        (is (crossed-same? (aget door k) (aget raw k))
            (str "the slot " k " crossed the same way at both"))))))

(deftest the-carrier-never-leaks-and-key-rides-the-outer-element
  (testing "the foreign component's props carry NEITHER internal slot.
            Carrying the component beside the converted props and
            stripping it inside would cost a shallow copy per crossing
            per render and leave the key one bug away from React; two
            slots make the leak unrepresentable instead"
    (let [p (raw-props (rf.fresco.impl.codec/as-element [:> a-foreign-component {:label "x"}]))]
      (is (= ["label"] (vec (sort (js/Object.keys p)))))))
  (testing "and the carrier itself holds exactly the two slots, plus
            children when there are any"
    (is (= ["c" "p"] (vec (sort (js/Object.keys (raw-carrier (rf.fresco.impl.codec/as-element [:> a-foreign-component {}])))))))
    (is (= ["c" "children" "p"]
           (vec (sort (js/Object.keys (raw-carrier (rf.fresco.impl.codec/as-element [:> a-foreign-component {} [:span]]))))))))
  (testing "`:key` is React's contract on the crossing's own element, not
            an attribute and not a carrier slot — the door's rule"
    (let [e (rf.fresco.impl.codec/as-element [:> a-foreign-component {:key "k7" :label "x"}])]
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
    (is (not (contains? (set (js/Object.keys (raw-props (rf.fresco.impl.codec/as-element [:> a-foreign-component {}]))))
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
            f     (rf.fresco.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                     (fn [] (raw-prop :on-pick [:row/pick 7] "onPick")))]
        (is (fn? f))
        (f #js {:target #js {}})
        (is (= [[:row/pick 7]] @!seen))))
    (testing "and so does an event-spelled key-map"
      (let [!seen (atom [])
            f     (rf.fresco.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                     (fn [] (raw-prop :on-key-down {"Enter" [:go]} "onKeyDown")))]
        (f #js {:key "Enter" :isComposing false :target #js {}})
        (is (= [[:go]] @!seen))))
    (testing "a MARKED h/event at an on* slot takes the event wrapper and
              dispatches its return; at any other slot it takes the render
              wrapper, whose return crosses back as the render output —
              the spelling selects the contract, never a declaration"
      (let [!seen  (atom [])
            d      (fn [ev] (swap! !seen conj ev))
            marked (rf.fresco.impl.intent/callback (fn [x] [:row/pick x]))
            on     (rf.fresco.impl.intent/with-frame d (fn [] (raw-prop :on-value-change marked "onValueChange")))
            row    (rf.fresco.impl.intent/with-frame d (fn [] (raw-prop :row-formatter marked "rowFormatter")))]
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
            three arms — so an intent closure in a child
            captures the owner's frame-locked dispatch at LOWERING time
            (the two-frame proof of that is the DOM witness)"
    (let [one (raw-carrier (rf.fresco.impl.codec/as-element [:> a-foreign-component {} [:span "x"]]))]
      (is (= "span" (.-type (aget one "children")))
          "one child: an element, not hiccup"))
    (let [many (raw-carrier (rf.fresco.impl.codec/as-element [:> a-foreign-component {} [:span "a"] [:b "c"]]))
          kids (aget many "children")]
      (is (array? kids))
      (is (= ["span" "b"] (mapv #(.-type %) (vec kids))))))
  (testing "the attribute map is optional, and the indices shift by one
            from the door's: component at 1, props at 2 when it is a map,
            children from 3 or from 2"
    (let [e (rf.fresco.impl.codec/as-element [:> a-foreign-component [:span "no props"]])]
      (is (= "span" (.-type (aget (raw-carrier e) "children"))))
      (is (= [] (vec (sort (js/Object.keys (raw-props e)))))))
    (is (some? (rf.fresco.impl.codec/as-element [:> a-foreign-component]))
        "[:> Component] with neither props nor children is legal"))
  (testing "a lowered intent inside a `[:>]` child is the ordinary
            frame-locked one — the crossing is transparent to it"
    (let [!seen (atom [])
          e     (rf.fresco.impl.intent/with-frame (fn [ev] (swap! !seen conj ev))
                                   (fn [] (rf.fresco.impl.codec/as-element
                                            [:> a-foreign-component {}
                                             [:button {:on-click [:go]}]])))
          child (aget (raw-carrier e) "children")]
      ((aget (.-props child) "onClick") #js {:target #js {}})
      (is (= [[:go]] @!seen)))))

;; --- Edge 4: the legal Component value -------------------------------------
;;
;; The escape refuses exactly three values and admits everything else React
;; could mint a fiber for, so one accepts row per shape of legal type is the
;; whole roster; the refusals assert the id AND the discriminating reason,
;; because all three share one id.

(defn- accepts? [c] (identical? c (raw-component-of (rf.fresco.impl.codec/as-element [:> c {}]))))

(deftest every-category-react-mints-a-fiber-for-crosses
  (is (accepts? a-foreign-component) "a function")
  (is (accepts? (.-Suspense react)) "a `Symbol.for` exotic")
  (is (accepts? (react/memo a-foreign-component)) "a `$$typeof`-branded wrapper"))

(deftest the-three-values-react-would-accept-or-misreport-are-refused-at-the-crossing
  (testing "refused HERE, in the owner's render and on the server too,
            rather than by React — whose own refusal is minted at fiber
            creation, post-adoption and client-only"
    (testing "nil, and the bare form: the broken-import diagnosis"
      (let [d (ex-data (try (rf.fresco.impl.codec/as-element [:> nil {}]) nil (catch :default e e)))]
        (is (= :rf.error/fresco-raw-not-a-component (:rf.error/id d)))
        (is (= 3 (:argv-count d)))
        (is (re-find #"no default export" (:reason d))))
      (is (= :rf.error/fresco-raw-not-a-component (error-id #(rf.fresco.impl.codec/as-element [:>])))))
    (testing "a defview head, which React WOULD accept — it is fn?-true,
              so a bare 'is it a function' test mounts the shell raw, the
              body reads rfProps, gets undefined, and receives nil props"
      (let [d (ex-data (try (rf.fresco.impl.codec/as-element [:> a-view {}]) nil (catch :default e e)))]
        (is (= :rf.error/fresco-raw-not-a-component (:rf.error/id d)))
        (is (identical? a-view (:component d)))
        (is (re-find #"a defview product" (:reason d)))
        (is (re-find #"\[my-view" (:reason d)))))
    (testing "a defhost head — the escape is for what a declaration cannot
              express, and this one already IS a declaration"
      (let [d (ex-data (try (rf.fresco.impl.codec/as-element [:> a-host {}]) nil (catch :default e e)))]
        (is (= :rf.error/fresco-raw-not-a-component (:rf.error/id d)))
        (is (re-find #"a defhost product" (:reason d)))))))

;; ---------------------------------------------------------------------------
;; What the codec must NOT be
;; ---------------------------------------------------------------------------

(deftest the-codec-mints-a-fresh-props-object-per-element-and-memoizes-no-element
  (testing "two renders of the same hiccup are two elements with two props objects"
    (let [hiccup [:div {:class "row"} "x"]
          a      (rf.fresco.impl.codec/as-element hiccup)
          b      (rf.fresco.impl.codec/as-element hiccup)]
      (is (not (identical? a b)) "no element memoization — HD-006 stands")
      (is (not (identical? (.-props a) (.-props b))) "no props-object cache")
      (is (= (prop-names a) (prop-names b)))))
  (testing "only the two codec-work caches grow — tags and prop names (HD-004)"
    (rf.fresco.impl.codec/reset-caches!)
    (dotimes [i 20] (rf.fresco.impl.codec/as-element [:div.row {:class (str "c" i)} i]))
    (is (= {:tags 1 :props 3} (rf.fresco.impl.codec/cache-sizes))
        "one tag literal; `:class` is one of the three seeded prop names, so
         twenty renders with twenty distinct class VALUES add nothing")))

;; ---------------------------------------------------------------------------
;; The entity-key warning — development only
;; ---------------------------------------------------------------------------
;;
;; Every row below owns its own member NAMES, because the dedupe Map is
;; deliberately page-lifetime and has no reset hook: "once per site" is the
;; contract, and a fixture that reset it would be testing a different one.
;;
;; TWO hazards share that one dedupe, and which applies turns on the PARENT.
;; Under a HOST parent a seq becomes a JS array (`expand-seq`), React sees it
;; as children and runs its own missing-key check WHEN IT RECONCILES, so an
;; ABSENT key is left to React and the codec says nothing; what it warns on
;; React would coerce by CONTENT. At a BOUNDARY crossing (`check-seq-keys!`)
;; the members never reach React as children at all — they ride inside
;; `rfProps` as CLJS data — so React's check cannot run, and there the ABSENT
;; key is the codec's to report.

(defn- named-view
  "A distinct marked boundary head, stamped the way an arm's mint stamps
  one, so the warning has a name to say."
  [nm]
  (let [f (fn [_js-props] nil)]
    (unchecked-set f "displayName" nm)
    (rf.fresco.impl.codec/mark-boundary! f)))

(defn- warnings-during
  "Everything the codec said on `console.warn` while `f` ran. The channel
  matters: React's own key warning is a `console.error`, so a spy on the
  wrong one would read a Fresco row green off React's line."
  [f]
  (let [seen     (atom [])
        original (.-warn js/console)]
    (set! (.-warn js/console) (fn [& args] (swap! seen conj (apply str args))))
    (try (f) (finally (set! (.-warn js/console) original)))
    @seen))

;; A console.error spy on React deliberately does NOT appear here. React 19
;; does no key validation in `createElement` — the reconciler does it during
;; render — so at `as-element` time React is silent about EVERY shape, keyed
;; or not, host parent or boundary. A row asserting "React said nothing at the
;; crossing" would therefore pass on every tree, whatever the codec does: a
;; green that means nothing. The mechanism is pinned structurally instead, by
;; the rows that read where children land:
;; [[children-are-realized-once-and-flattened-one-level]] for a host parent's
;; children array, [[a-boundarys-children-arrive-as-a-realized-vector-of-hiccup]]
;; for a boundary's `rfProps`.

(deftest under-a-host-parent-an-unkeyed-seq-is-reacts-to-warn-about-and-the-codec-is-silent
  (let [row (named-view "w1.ns/row")]
    (is (= [] (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (for [i (range 3)] [row {:id i}])])))
        "React already warns about a missing key; the codec adds nothing")
    (is (= [] (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (list [row {:key nil :id 1}])])))
        "and `:key nil` is the same absent key")))

;; ---------------------------------------------------------------------------
;; THE BOUNDARY CROSSING — where React's own missing-key check cannot run
;;
;; `boundary-element` hands its children to `createElement` as CLJS data under
;; `rfProps`, never as child ARGUMENTS, so React never classifies them as
;; children and never key-checks them. The consequence is not cosmetic: an
;; unkeyed list of memoised boundaries reconciles BY INDEX, so deleting the
;; first row hands every row's local state — focus, scroll position, presence
;; retention — to the next row's data, in silence.
;;
;; The PRECONDITION row is the load-bearing one. A suite that only asserted
;; "a keyed crossing is silent" would pass identically on a build where the
;; warning can never fire at all, so the positive case is asserted first and
;; the negative one is only meaningful behind it.
;; ---------------------------------------------------------------------------

(deftest an-unkeyed-boundary-crossing-warns-because-react-cannot
  (testing "PRECONDITION — the unkeyed crossing DOES warn"
    (let [row  (named-view "w11.ns/row")
          host (named-view "w11.ns/host")
          out  (warnings-during
                 #(rf.fresco.impl.codec/as-element
                    [host {} (for [i (range 3)] [row {:id i}])]))
          line (first out)]
      (is (= 1 (count out)) "one line for the site")
      (is (re-find #"^\[fresco\] Missing :key on boundary children" line)
          "the console prefix the erasure scan pins")
      (is (re-find #"w11\.ns/row" line) "the member head, not the parent")
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/fresco-missing-key" line))))
  (testing "`:key nil` and a member with no props map at all are the same
            absent key"
    (let [a (named-view "w13.ns/a")
          b (named-view "w13.ns/b")
          h (named-view "w13.ns/host")]
      (is (= 1 (count (warnings-during
                        #(rf.fresco.impl.codec/as-element
                           [h {} (list [a {:key nil :id 1}])])))))
      (is (= 1 (count (warnings-during
                        #(rf.fresco.impl.codec/as-element
                           [h {} (list [b 1 2])]))))))))

(deftest a-keyed-boundary-crossing-is-silent
  (testing "the canonical keyed list says nothing — meaningful only because
            the precondition row above shows the warning can fire"
    (let [row  (named-view "w14.ns/row")
          host (named-view "w14.ns/host")]
      (is (= [] (warnings-during
                  #(rf.fresco.impl.codec/as-element
                     [host {} (for [i (range 300)] [row {:key i}])]))))))
  (testing "a non-boundary member is not this warning's business: a host-headed
            row at a crossing is React's to check when the body renders it"
    (let [host (named-view "w15.ns/host")]
      (is (= [] (warnings-during
                  #(rf.fresco.impl.codec/as-element
                     [host {} (for [i (range 3)] [:li i])])))))))

(deftest the-crossing-site-warns-exactly-once-however-many-renders-it-takes
  (let [row     (named-view "w16.ns/row")
        host    (named-view "w16.ns/host")
        hiccup  [host {} (list [row {:id 1}] [row {:id 2}])]
        first-r (warnings-during #(rf.fresco.impl.codec/as-element hiccup))
        again   (warnings-during #(rf.fresco.impl.codec/as-element hiccup))]
    (is (= 1 (count first-r))
        "two unkeyed members of one head are ONE site — a diagnostic that
         fired per member would be the nagging the dedupe exists to prevent")
    (is (= [] again) "and a second render of the same site re-fires nothing"))
  (testing "the two hazards are distinct sites on one head, so an entity key
            and an absent key each get their own line"
    (let [row  (named-view "w17.ns/row")
          host (named-view "w17.ns/host")
          out  (warnings-during
                 #(rf.fresco.impl.codec/as-element
                    [host {} (list [row {:id 1}] [row {:key {:id 2}}])]))]
      (is (= 2 (count out)))
      (is (re-find #"Missing :key" (first out)))
      (is (re-find #"Entity-valued :key" (second out))))))

(deftest a-keyed-boundary-seq-is-silent-and-so-is-every-legitimate-key-shape
  (testing "each primitive key shape — a warning that fired on valid code
            would be worse than no warning"
    (doseq [[label k] [["number" 1] ["string" "a"] ["keyword" :a]]]
      (let [row (named-view (str "w2b.ns/" label))]
        (is (= [] (warnings-during
                    #(rf.fresco.impl.codec/as-element [:ul (list [row {:key k}])])))
            (str "a " label " key is a legitimate key"))))))

(deftest a-site-is-the-member-head-and-the-hazard
  (testing "two member heads — two sites"
    (let [a   (named-view "w4.ns/a")
          b   (named-view "w4.ns/b")
          out (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (list [a {:key {:id 1}}] [b {:key {:id 2}}])]))]
      (is (= 2 (count out)) "scanning past the first offender is what finds the second")))
  (testing "one member head, two hazards — two sites"
    (let [row (named-view "w5.ns/row")
          out (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (list [row {:key {:id 1}}] [row {:key [1 2]}])]))]
      (is (= 2 (count out))))))

(deftest a-mixed-seq-names-the-first-entity-keyed-member
  (let [row (named-view "w8.ns/row")
        out (warnings-during
              #(rf.fresco.impl.codec/as-element
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
                 #(rf.fresco.impl.codec/as-element [:ul (list [row {:key {:id 1 :label "a"}}])]))
          line (first out)]
      (is (= 1 (count out)))
      (is (re-find #"^\[fresco\] Entity-valued :key" line)
          "the console prefix the erasure scan pins")
      (is (re-find #"w9\.ns/row" line) "the member head")
      (is (re-find #"carries a map at :key" line))
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/fresco-entity-key" line))
      (is (nil? (re-find #":label" line))
          "the VALUE never reaches the console — a cyclic or throwing foreign
           value would blow pr-str inside a diagnostic")))
  (testing "a vector and a set are the same hazard, and two distinct sites"
    (let [row (named-view "w10.ns/row")
          out (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (list [row {:key [1 2]}] [row {:key #{1}}])]))]
      (is (= 2 (count out)))
      (is (re-find #"carries a vector at :key" (first out)))
      (is (re-find #"carries a set at :key" (second out))))))

;; ---------------------------------------------------------------------------
;; THE CLASSIFICATION IS TOTAL
;;
;; `check-member-key!`'s classification names every non-nil `:key` that is
;; neither primitive nor a CLJS collection, rather than letting it fall out of
;; the check in silence. The foreign JS object is the shape that makes silence
;; a bug rather than a gap:
;; every one of them string-coerces to the SAME `[object Object]`, so distinct
;; rows share one key. The rows below pin the warning, then — just as
;; load-bearing — the two shapes deliberately classified SAFE, because a
;; warning that fires on a `uuid` key would teach authors to ignore it.
;; ---------------------------------------------------------------------------

(deftest a-foreign-object-key-warns-naming-the-shape-and-never-the-value
  (testing "every foreign object string-coerces to `[object Object]`, so
            distinct rows would share one key; the warning names the shape
            without ever printing the value"
    (let [row  (named-view "w25.ns/row")
          out  (warnings-during
                 #(rf.fresco.impl.codec/as-element [:ul (list [row {:key #js {:secret "s3cr3t"}}])]))
          line (first out)]
      (is (= 1 (count out)))
      (is (re-find #"w25\.ns/row" line) "the member head")
      (is (re-find #"carries a foreign object at :key" line))
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/fresco-entity-key" line))
      (is (nil? (re-find #"s3cr3t" line))
          "the VALUE never reaches the console"))))

(deftest every-other-non-primitive-key-shape-is-named-rather-than-dropped
  (testing "the shapes a classification without an `:else` would let fall through"
    (doseq [[label k pattern]
            [["boolean"  true         #"carries a boolean at :key"]
             ["function" (fn [] 1)    #"carries a function at :key"]]]
      (let [row (named-view (str "w27.ns/" label))
            out (warnings-during
                  #(rf.fresco.impl.codec/as-element [:ul (list [row {:key k}])]))]
        (is (= 1 (count out)) (str "a " label " key must not fall through"))
        (is (re-find pattern (first out)) (str "a " label " key is named"))))))

(deftest the-object-key-shapes-deliberately-classified-safe-stay-silent
  (testing "a uuid string-coerces to the identifier the author MEANS, so it is
            the canonical entity key and warning on it would be the false
            positive that teaches authors to ignore the warning"
    (let [row (named-view "w28.ns/row")
          ids [(uuid "00000000-0000-0000-0000-000000000001")
               (uuid "00000000-0000-0000-0000-000000000002")]]
      (is (= [] (warnings-during
                  #(rf.fresco.impl.codec/as-element
                     [:ul (for [id ids] [row {:key id}])]))))))
  (testing "a symbol coerces to its own NAME, exactly as the keyword that
            `plain-key?` already admits does"
    (let [row (named-view "w29.ns/row")]
      (is (= [] (warnings-during
                  #(rf.fresco.impl.codec/as-element [:ul (list [row {:key 'a}] [row {:key 'ns/b}])])))))))

(deftest the-scope-line-holds-in-both-directions
  (testing "native-tag members are React's beat — there is no boundary head to name"
    (is (= [] (warnings-during
                #(rf.fresco.impl.codec/as-element [:ul (for [i (range 3)] [:li {:key {:id i}} i])]))))))
