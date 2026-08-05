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
      (is (= [] (prop-names e))))
    (is (= 3 (:props (codec/cache-sizes)))
        "the three seeded entries and nothing else — the refusal is on the write")))

(deftest a-literal-named-after-an-inherited-property-cannot-be-served-one
  ;; rf2-2rtt6.63. Both caches are `Object.create(null)`, so a lookup can
  ;; only ever answer an own property. Keyed against `#js {}` these
  ;; literals would read `Object.prototype`'s OWN members — a function
  ;; where a ParsedTag or a PropSlot belongs — and the codec would emit
  ;; whatever that function's `.tag` / `.js-name` happened to be, which
  ;; is `undefined`. The lookup guard the shipping code used to carry
  ;; (`hasOwnProperty.call`) answered this; nothing but the cache's own
  ;; construction answers it now, so it is witnessed rather than assumed.
  (testing "a TAG named after an Object.prototype member parses as itself"
    (doseq [n ["toString" "valueOf" "hasOwnProperty" "isPrototypeOf" "constructor"]]
      (is (= n (el-type (codec/as-element [(keyword n)])))
          (str "tag " n " must render as itself"))))
  (testing "and the second render of one reads its own cached entry"
    (codec/reset-caches!)
    (codec/as-element [:toString])
    (is (= 1 (:tags (codec/cache-sizes))))
    (is (= "toString" (el-type (codec/as-element [:toString]))))
    (is (= 1 (:tags (codec/cache-sizes)))))
  (testing "a PROP named after an Object.prototype member converts as itself"
    (codec/reset-caches!)
    (let [e (codec/as-element [:div {:toString "a" :valueOf "b" :hasOwnProperty "c"}])]
      (is (= ["hasOwnProperty" "toString" "valueOf"] (prop-names e)))
      (is (= "a" (prop e "toString")))
      (is (= "b" (prop e "valueOf")))
      (is (= "c" (prop e "hasOwnProperty"))))
    (testing "and the second element reads the cached slots"
      (is (= "d" (prop (codec/as-element [:div {:toString "d"}]) "toString"))))))

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

(deftest the-slot-is-a-pure-function-of-the-key-and-a-string-cannot-poison-it
  (testing "a string prop name is already a React name and is taken verbatim,
            so `\"on-input\"` and `:on-input` are DIFFERENT slots. They share
            a cache entry if the cache is keyed by name alone, and then the
            first string spelling rendered anywhere answers every later
            keyword — emitting every handler written the taught way into a
            slot React ignores, and making the canonical slot depend on what
            the page happened to render first."
    (is (= "on-input" (codec/cached-prop-name "on-input")))
    (is (= "onInput" (codec/cached-prop-name :on-input))
        "the keyword still camelCases, whatever was asked before it")
    (is (= "on-input" (codec/cached-prop-name "on-input"))
        "and the string is still itself, whatever was asked before IT"))
  (testing "the other way round, from a cold cache"
    (codec/reset-caches!)
    (is (= "onInput" (codec/cached-prop-name :on-input)))
    (is (= "on-input" (codec/cached-prop-name "on-input"))))
  (testing "the three React renames are the rule, so they hold for every
            spelling of the one attribute"
    (doseq [k [:class :className "class" :x/class 'class]]
      (is (= "className" (codec/canonical-slot k)) (str "spelled " (pr-str k))))))

(deftest the-cached-classification-is-spelling-aware-and-no-spelling-poisons-another
  ;; rf2-y1jkm: the prop cache's entries carry the POSITION CLASSIFICATION
  ;; beside the React name, and the entry is keyed by name — so a symbol
  ;; spelled `on-click` shares the keyword's entry while `event-prop?`
  ;; answers false for it. The flag must therefore be minted from the NAME
  ;; and applied spelling-aware at the call site; either shortcut turns
  ;; whichever spelling renders first into the answer for both, which is
  ;; exactly the order dependence the string/keyword law above exists to
  ;; remove. Both directions, both from a cold cache.
  (testing "a symbol minted FIRST does not cost the keyword its event lowering"
    (codec/reset-caches!)
    (codec/cached-prop-name 'on-click)
    (let [!seen (atom [])
          e     (intent/with-frame (fn [ev] (swap! !seen conj ev))
                                   (fn [] (codec/as-element [:button {:on-click [:go]}])))]
      (is (fn? (prop e "onClick")) "the keyword position still lowers to a handler")
      ((prop e "onClick") #js {:target #js {}})
      (is (= [[:go]] @!seen))))
  (testing "a keyword minted FIRST does not make the symbol an event position"
    (codec/reset-caches!)
    (codec/cached-prop-name :on-click)
    (let [e (codec/as-element [:div {'on-click [:go]}])]
      (is (array? (prop e "onClick"))
          "a symbol key is never an event position (event-prop? says so), so
           the vector is a plain prop value and converts through clj->js —
           lowering it here would also have thrown, there being no ambient
           frame in this test"))))

(deftest the-shorthand-composes-with-a-class-however-it-is-spelled
  ;; THIS TEST WAS THE OTHER WAY ROUND (rf2-2rtt6.36, merged-PR audit
  ;; #7332). Under rf2-y1jkm's lane 2 the shorthand's className was written
  ;; over whatever the loop had emitted, and an exotic spelling that
  ;; canonicalises onto the same slot lost outright — `[:div.a {:x/class
  ;; "b"}]` was pinned at "a". It was pinned because it MATCHED the general
  ;; path, and the general path was itself wrong: the shorthand merge read
  ;; `:class` and `:className` off the map by raw key, so it saw three of
  ;; the spellings this codec accepts and silently dropped the rest.
  ;;
  ;; The shorthand is folded onto the EMITTED object now, where there is
  ;; no spelling left to miss, so every one of them composes. The
  ;; assertion below is the flipped one and it is the whole repair in a
  ;; line.
  (testing "a namespaced class spelling composes with the shorthand rather
            than losing to it"
    (is (= "a b" (prop (codec/as-element [:div.a {:x/class "b"}]) "className"))))
  (testing "and so does every other spelling the codec accepts"
    (doseq [k [:class :className "class" "className" 'class :x/class :class-name]]
      (is (= "a b" (prop (codec/as-element [:div.a {k "b"}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "a caller remainder composes too, in every spelling"
    (doseq [k [:class :className "class" "className" 'class :x/class]]
      (is (= "a b" (prop (codec/as-element [:div.a {:& {k "b"}}]) "className"))
          (str "forwarded as " (pr-str k)))))
  (testing "the class value is coerced at the slot, so a collection joins
            whatever key carried it — the coercion used to live in the map
            surgery, where only `:class` and `:className` reached it"
    (doseq [k [:class "className" :x/class]]
      (is (= "a x y" (prop (codec/as-element [:div.a {k ["x" nil :y]}]) "className"))
          (str "spelled " (pr-str k)))))
  (testing "two spellings of the one slot COMPOSE rather than the last write
            silently winning — a dropped class is the failure class the
            ruling exists to delete"
    (is (= "a b c" (prop (codec/as-element [:div.a {:class "b" :x/class "c"}]) "className")))))

(deftest the-shorthand-id-loses-to-an-explicit-one-however-it-is-spelled
  ;; The other half of merged-PR audit #7332. `#tag` is the weakest source
  ;; of an id there is, and the rule "an explicit :id wins" was stated on
  ;; the raw key — so `[:div#tag {"id" "explicit"}]` kept BOTH, landed both
  ;; on React's one `id` slot, and left which one survived to the order the
  ;; props map happened to iterate in.
  (testing "written on the element"
    (doseq [k [:id "id" 'id :x/id]]
      (is (= "explicit" (prop (codec/as-element [:div#tag {k "explicit"}]) "id"))
          (str "spelled " (pr-str k)))))
  (testing "forwarded through the one merge — the door where the author of
            the element never sees the key at all"
    (doseq [k [:id "id" 'id :x/id]]
      (is (= "caller" (prop (codec/as-element [:div#tag {:& {k "caller"}}]) "id"))
          (str "forwarded as " (pr-str k)))))
  (testing "and the shorthand still lands when nothing claims the slot"
    (is (= "tag" (prop (codec/as-element [:div#tag {:& {:title "t"}}]) "id"))))
  (testing "a near miss is not the id slot and keeps its own name"
    (let [e (codec/as-element [:div#tag {:data-id "d" :ids "many"}])]
      (is (= "tag" (prop e "id")))
      (is (= "d" (prop e "data-id")))
      (is (= "many" (prop e "ids"))))))

(deftest the-shorthand-fold-does-not-depend-on-map-order
  ;; The audit's phrasing: the answer must hold "independent of cache/render
  ;; /map order". Both spellings of both slots in one remainder, written in
  ;; both orders, and again in a map big enough to be a PersistentHashMap
  ;; rather than an array map — the iteration order changes underneath and
  ;; the emitted element does not.
  (let [expected {"id" "caller" "className" "foo bar"}
        emitted  (fn [e] {"id" (prop e "id") "className" (prop e "className")})]
    (testing "the audit's own witness, both ways round"
      (is (= expected (emitted (codec/as-element
                                [:div#tag.foo {:& {"id" "caller" "className" "bar"}}]))))
      (is (= expected (emitted (codec/as-element
                                [:div#tag.foo {:& {"className" "bar" "id" "caller"}}])))))
    (testing "and out of a hash map, whose iteration order is neither"
      (let [caller (into {} (map (fn [i] [(keyword (str "data-" i)) i])) (range 12))]
        (is (= expected (emitted (codec/as-element
                                  [:div#tag.foo {:& (assoc caller "id" "caller" :x/class "bar")}]))))))
    (testing "renders are independent of what the caches were asked first"
      (codec/reset-caches!)
      (codec/cached-prop-name "class")
      (codec/cached-prop-name :x/id)
      (is (= expected (emitted (codec/as-element
                                [:div#tag.foo {:& {"id" "caller" "className" "bar"}}])))))))

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
;; `:&` — one merge, and the owned-literal law unconditional
;; (HD-023, rf2-2rtt6.36)
;; ---------------------------------------------------------------------------

(deftest a-caller-remainder-merges-under-the-literals-that-are-written
  (testing "the law, in its plainest form: keys the caller supplies and the
            element does not, land; keys the element writes are not
            reachable from `:&` at all."
    (let [e (codec/as-element [:div {:& {:title "from caller" :id "caller"}
                                     :id "owned"}])]
      (is (= "from caller" (prop e "title")) "an unclaimed key lands")
      (is (= "owned" (prop e "id")) "a written literal always wins")
      (is (not (contains? (set (prop-names e)) "&"))
          ":& is a reserved key, never an attribute")))
  (testing "the whole point of the direction: a caller override is spelled by
            NOT writing the literal, because the dangerous default is the
            other way round"
    (is (= "caller" (prop (codec/as-element [:div {:& {:id "caller"}}]) "id")))))

(deftest a-hostile-remainder-cannot-forfeit-the-controlled-input-door
  (testing "the predecessor's silent failure, deleted. There, a dynamic map
            merged onto a controlled input WITHOUT the door-preserving spread
            form forfeits caret and IME protection, and no diagnostic is
            raised anywhere — the choice of syntax is the choice of
            correctness. Here there is one merge and the law is
            unconditional, so a remainder carrying the whole controlled
            contract reaches none of it."
    (let [!seen (atom [])
          caller {:value      "HOSTILE"
                  :on-input   [:hostile/edit]
                  :key        "hostile"
                  :ref        (fn [_] (swap! !seen conj :ref-fired))
                  :class      "from-caller"
                  :aria-label "kept"}
          e (intent/with-frame (fn [ev] (swap! !seen conj ev))
                               (fn [] (codec/as-element
                                       [:input {:& caller
                                                :value    "owned"
                                                :on-input [:todo.ui/edit 7 :re-frame.hicasso/value]}])))]
      (is (= "owned" (prop e "value")) "the controlled value survives")
      (is (nil? (el-key e)) ":key is never taken from a remainder")
      (is (nil? (prop e "ref")) ":ref is never taken from a remainder")
      (is (= "from-caller" (prop e "className")) "an unclaimed key still lands")
      (is (= "kept" (prop e "aria-label")))
      ((prop e "onInput") #js {:target #js {:value "typed"}})
      (is (= [[:todo.ui/edit 7 "typed"]] @!seen)
          "and the owned handler is the one that fired — the caller's intent
           never reached the element"))))

(deftest an-owned-class-composes-through-the-tag-shorthand
  (testing "the one place a caller's value and an owned value both survive,
            and it needs no exception to the law: the element's own classes
            are written on the TAG, which is not a literal attribute key, so
            the shorthand merge composes them with whatever the remainder
            brought."
    (let [e (codec/as-element [:input.form-control {:& {:class "form-control-lg"}}])]
      (is (= "form-control form-control-lg" (prop e "className"))))
    (testing "and a literal :class still wins outright, because it is a literal"
      (let [e (codec/as-element [:input.base {:& {:class "from-caller"} :class "owned"}])]
        (is (= "base owned" (prop e "className")))))
    (testing "composition does not reopen the deny: what composes is what
              SURVIVED the merge, and an alias at a slot an owned literal
              claims never gets that far — in either spelling"
      (doseq [k ["className" :x/class 'class]]
        (is (= "base owned"
               (prop (codec/as-element [:input.base {:& {k "from-caller"} :class "owned"}])
                     "className"))
            (str "forwarded as " (pr-str k))))
      (doseq [k ["id" :x/id 'id]]
        (is (= "owned"
               (prop (codec/as-element [:div#tag {:& {k "hostile"} :id "owned"}]) "id"))
            (str "forwarded as " (pr-str k)))))))

(deftest the-same-merge-and-the-same-law-hold-at-a-crossing
  (testing "the case the predecessor needs a THIRD rule for. Its spread forms
            are element forms, so forwarding a remainder through one onto a
            declared foreign head rewrites `:className` into the `:class`
            slot and the component never sees the prop it reads — hence
            'neither spread form is legal there' and an ordinary `merge`
            instead. `:&` is not a spread: it is a key in the props map,
            merged before any conversion, and the conversion that follows is
            the position's own."
    (let [e (codec/as-element [a-view {:& {:className "react-name" :selected "caller"}
                                       :selected "owned"}])]
      (is (= {:className "react-name" :selected "owned"} (prop e "rfProps"))
          ":className crosses under the name it was written as, and the
           owned literal still wins")))
  (testing "a remainder's :key cannot become the crossing's key either"
    (let [e (codec/as-element [a-view {:& {:key "hostile"} :id 7}])]
      (is (nil? (el-key e)))
      (is (= {:id 7} (prop e "rfProps"))))))

(deftest the-merge-costs-one-lookup-when-it-is-absent
  (testing "the overwhelming case. No `:&` means the map comes back by
            identity — the feature allocates nothing on an element that does
            not use it."
    (let [props {:id "x" :class "y"}]
      (is (identical? props (codec/merge-caller props)))))
  (testing "an explicit nil remainder is a no-op that still removes the key"
    (is (= {:id "x"} (codec/merge-caller {:& nil :id "x"}))))
  (testing "a non-map remainder is a loud error rather than a silent drop"
    (is (thrown-with-msg? js/Error #"carries a caller's attribute map"
                          (codec/as-element [:div {:& [:not :a :map]}])))
    (try
      (codec/as-element [:div {:& "nope"}])
      (is false "should have thrown")
      (catch :default e
        (is (= :rf.error/hicasso-merge-not-a-map (:rf.error/id (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; The canonical structural-slot filter (rf2-2rtt6.36)
;; ---------------------------------------------------------------------------

(deftest a-structural-slot-is-recognised-in-every-spelling-the-codec-accepts
  (testing "the mechanism the whole of this bead's repair rests on: the slot
            a key EMITS INTO, not the key it was written as. React's key and
            ref are reachable as a keyword, a string, a symbol and a
            namespaced keyword, and a rule that names `#{:key :ref}` sees
            exactly one of the four."
    (doseq [k [:key "key" 'key :x/key :re-frame.hicasso/key]]
      (is (true? (codec/structural-slot? k)) (str "key, spelled " (pr-str k))))
    (doseq [k [:ref "ref" 'ref :x/ref]]
      (is (true? (codec/structural-slot? k)) (str "ref, spelled " (pr-str k))))
    (doseq [k [:id :class :on-click :data-key "aria-ref" :keyboard]]
      (is (false? (codec/structural-slot? k))
          (str "and nothing else is structural: " (pr-str k)))))
  (testing "the filter drops every one of them and returns the map by
            identity when there is nothing to drop"
    (is (= {:class "x"} (codec/without-structural
                          {:class "x" :key 1 "key" 2 :x/key 3 :ref (fn [_]) "ref" 4 :x/ref 5})))
    (let [clean {:class "x" :id "y"}]
      (is (identical? clean (codec/without-structural clean))))))

(deftest an-alternate-spelling-in-a-remainder-reaches-neither-structural-slot
  (testing "the hole this repair closes. A remainder is about ATTRIBUTES;
            `key` and `ref` are about node identity. Denying `:key` and
            `:ref` by map-key identity denies one spelling of each and lets
            three through — and with no literal at that slot to overwrite it
            afterwards, the caller's value is simply what React gets."
    (doseq [spelling ["key" 'key :x/key]]
      (let [e (codec/as-element [:li {:& {spelling "hostile"} :class "row"}])]
        (is (nil? (el-key e)) (str "a remainder's " (pr-str spelling) " is not the element's key"))
        (is (= ["className"] (prop-names e))
            "and it does not land as an attribute either")))
    (doseq [spelling ["ref" 'ref :x/ref]]
      (let [!fired (atom false)
            e      (codec/as-element [:div {:& {spelling (fn [_] (reset! !fired true))}}])]
        (is (nil? (prop e "ref")) (str "a remainder's " (pr-str spelling) " is not a ref"))
        (is (= [] (prop-names e)))
        (is (false? @!fired)))))
  (testing "and the element's OWN literal is untouched by the filter — the
            law is about what a remainder may reach, not about what an
            author may write on their own node"
    (let [f (fn [_node])
          e (codec/as-element [:li {:key 7 :ref f :& {:title "caller"}}])]
      (is (= "7" (el-key e)))
      (is (identical? f (prop e "ref")))
      (is (= "caller" (prop e "title"))))))

(deftest an-alias-cannot-defeat-an-owned-literal-at-the-slot-they-share
  (testing "the second half of the same hole. `:onInput` and `:on-input` are
            two map keys and ONE React slot, so a raw-key merge leaves both
            in the map and lets whichever the map iterates last decide —
            which is a law that holds by luck of map ordering rather than by
            construction. The deny is on the slot, so the alias never
            reaches the map at all."
    (is (= {:on-input [:owned/edit]}
           (codec/merge-caller {:& {:onInput [:hostile/edit]} :on-input [:owned/edit]})))
    (is (= {:on-input [:owned/edit]}
           (codec/merge-caller {:& {"onInput" [:hostile/edit]} :on-input [:owned/edit]}))
        "including the string spelling of the same React name"))
  (testing "at a crossing the map is handed over UNRENAMED, so the alias is
            visible in the props a structural test reads — and the law is
            the same one rule at both positions (HD-023(d))"
    (let [e (codec/as-element [a-view {:& {:onInput [:hostile/edit] :title "kept"}
                                       :on-input [:owned/edit]}])]
      (is (= {:on-input [:owned/edit] :title "kept"} (prop e "rfProps")))))
  (testing "and the owned handler is the one React calls"
    (let [!seen (atom [])
          e (intent/with-frame (fn [ev] (swap! !seen conj ev))
                               (fn [] (codec/as-element
                                       [:input {:& {:onInput [:hostile/edit]
                                                    :onFocus  [:hostile/focus]}
                                                :on-input [:todo.ui/edit 7]}])))]
      ((prop e "onInput") #js {:target #js {}})
      (is (= [[:todo.ui/edit 7]] @!seen))
      (is (fn? (prop e "onFocus"))
          "an alias the element does NOT write still lands — the deny is the
           slot the literal claimed, never the shape of the spelling")))
  (testing "the same for the controlled pair spelled with a namespace"
    (let [e (codec/as-element [:input {:& {:x/value "HOSTILE"} :value "owned"}])]
      (is (= "owned" (prop e "value"))))))

;; ---------------------------------------------------------------------------
;; The reserved `:ref` value-space (HD-022, rf2-2rtt6.38)
;; ---------------------------------------------------------------------------

(deftest a-callback-ref-is-the-v0-surface-and-reaches-react-by-identity
  (testing "HD-003's escape hatch and HD-016's callback-refs-only rule are
            unchanged by the reservation: a function at :ref is the v0
            spelling, and it arrives at React as the same function object —
            rewrapping it would detach and reattach the node every render."
    (let [f (fn [_node] nil)
          e (codec/as-element [:div {:ref f}])]
      (is (identical? f (prop e "ref"))))))

(deftest a-vector-at-ref-is-reserved-and-refused-loudly
  (testing "the whole of rf2-2rtt6.38. `{:ref [registered-id config]}` is the
            spelling the later data form wants, so v0 claims the value-space
            now and refuses it with a diagnostic naming the reservation —
            rather than handing React an opaque array, which it would ignore
            in silence and which would look like a ref that never fires."
    (is (thrown-with-msg? js/Error #"RESERVED"
                          (codec/as-element [:div {:ref [::autosize {:max-rows 8}]}])))
    (try
      (codec/as-element [:textarea.composer {:ref [::autosize {:max-rows 8}]}])
      (is false "should have thrown")
      (catch :default e
        (let [d (ex-data e)]
          (is (= :rf.error/hicasso-ref-vector-reserved (:rf.error/id d)))
          (is (= :use-a-callback-ref-or-an-effect (:recovery d)))
          (is (= [::autosize {:max-rows 8}] (:ref d))
              "the refusal carries the value it refused, so a later
               migration can find its own call sites"))))))

(deftest the-reservation-holds-at-the-ref-slot-however-the-ref-is-spelled
  (testing "the hole. This codec deliberately accepts string, symbol and
            namespaced prop spellings and emits them all under one React
            name, so a reservation that reads `(:ref props)` is a
            reservation `\"ref\"` and `:x/ref` walk straight past — on their
            way to React with the opaque array the reservation exists to
            stop, which is the silent ref-that-never-fires it was written to
            replace."
    (doseq [spelling ["ref" 'ref :x/ref :re-frame.hicasso/ref]]
      (try
        (codec/as-element [:textarea.composer {spelling [::autosize {:max-rows 8}]}])
        (is false (str "should have thrown for " (pr-str spelling)))
        (catch :default e
          (let [d (ex-data e)]
            (is (= :rf.error/hicasso-ref-vector-reserved (:rf.error/id d))
                (str "refused, spelled " (pr-str spelling)))
            (is (= spelling (:position d))
                "and the diagnostic names the spelling that was written")
            (is (= [::autosize {:max-rows 8}] (:ref d))))))))
  (testing "and a callback ref under an alternate spelling still reaches
            React's ref slot BY IDENTITY — the reservation is the VECTOR
            arm, not a claim on the spelling, and the ref position is
            excluded from callback lowering at the slot rather than at the
            key. Wrapping it would both forbid a dispatch that is legitimate
            there and change the identity React uses to decide whether to
            re-attach the node."
    (let [f (intent/callback (fn [_node]))]
      (is (identical? f (prop (codec/as-element [:div {"ref" f}]) "ref")))
      (is (identical? f (prop (codec/as-element [:div {:x/ref f}]) "ref")))))
  (testing "a vector anywhere else is still an ordinary prop value"
    (is (= "a b" (prop (codec/as-element [:div {:class ["a" "b"]}]) "className")))
    (is (some? (prop (codec/as-element [:div {:data-refs [1 2]}]) "data-refs")))))

(deftest the-reservation-costs-one-branch-and-claims-nothing-else
  (testing "no other :ref value moves. A string ref, a nil ref and a map at
            :ref all pass through the ordinary conversion — the reservation
            is the VECTOR arm and nothing wider, because a wider claim would
            be designing the later surface rather than reserving room for it."
    (is (nil? (prop (codec/as-element [:div {:ref nil}]) "ref")))
    (is (= "legacy" (prop (codec/as-element [:div {:ref "legacy"}]) "ref")))
    (is (some? (prop (codec/as-element [:div {:ref {:a 1}}]) "ref"))))
  (testing "and :ref is not an event position, so intent lowering never sees it"
    (is (false? (intent/event-prop? :ref)))))


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
;; The named value at a foreign crossing (rf2-vrvv9)
;; ---------------------------------------------------------------------------

(defn- a-foreign-component
  "Stands in for a library's component — anything React would accept as
  an element type. Nothing renders here; the questions below are all
  about what the codec BUILT."
  [_js-props]
  nil)

(def ^:private a-host (codec/mint-host! "AHost" a-foreign-component))

(defn- host-prop
  "The value the codec emitted at `slot` for a one-prop host crossing.
  Reads the GATE element's props, which are the props object the foreign
  component receives — the gate forwards it verbatim."
  [k v slot]
  (prop (codec/as-element [a-host {k v}]) slot))

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
    (is (= "dark" (prop (codec/as-element [:div {:class :theme/dark}]) "className"))
        "the native crossing, unchanged")))

;; ---------------------------------------------------------------------------
;; The class slot is a POSITION at the crossing too (rf2-2rtt6.119)
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
          native  (prop (codec/as-element [:div {:class ["a" nil :b]}]) "className")]
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
    (let [e (codec/as-element [a-host {:class "a" :x/class ["b" :c]}])]
      (is (= "a b c" (prop e "className"))))
    ;; the guide's own example, asserted verbatim so the page cannot drift
    ;; from the door (draft-guide/05-interop.md §Defaults)
    (is (= "btn on wide"
           (prop (codec/as-element [a-host {:class ["btn" nil :on] :className "wide"}])
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
    (is (array? (prop (codec/as-element [:div {:id ["a" "b"]}]) "id"))
        "the native walk's own answer, quoted so the claim is not asserted
         about a function nobody ran")))

(deftest a-declared-contract-still-outranks-the-class-coercion
  (testing "HD-011's whole point is that a DECLARED position means what the
            declaration says it means, so the slot coercion sits BELOW the
            declaration in the cond rather than above it. Nobody sensible
            declares a contract on `className`; the row exists so the
            precedence is pinned rather than incidental — and a vector is
            the value that tells the two orders apart, because
            `class-names` would quietly answer it \"a b\" where the
            declaration refuses it."
    (let [declared (codec/mint-host! "ClassContractHost" a-foreign-component
                                     {:callbacks {:class :handler}})]
      (try
        (codec/as-element [declared {:class ["a" "b"]}])
        (is false "should have thrown")
        (catch :default e
          (is (= :rf.error/hicasso-intent-at-a-non-event-contract
                 (:rf.error/id (ex-data e)))
              "the declaration decided, not the slot")))))
  (testing "and an undeclared host is unaffected — the same value at the
            same slot is an ordinary class collection"
    (is (= "a b" (host-prop :class ["a" "b"] "className")))))

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
;; What "callback refs only" actually is (rf2-d03av)
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
      (is (identical? r (prop (codec/as-element [:div {:ref r}]) "ref"))
          "a native tag")
      (is (identical? r (host-prop :ref r "ref"))
          "and a defhost crossing — HD-011's conversion parity, which is
           what makes 'enforced at one crossing and not the other' the
           outcome design C ruled out")))
  (testing "and it is one rule at one slot, so an alternate spelling of the
            ref position answers the same way"
    (let [r (react/createRef)]
      (is (identical? r (prop (codec/as-element [:div {"ref" r}]) "ref")))
      (is (identical? r (host-prop :x/ref r "ref")))))
  (testing "the reserved VECTOR is still refused at both, which is the rule
            that IS enforced — so the two questions stay separable"
    (let [reserved [::autosize {:max-rows 8}]]
      (is (thrown-with-msg? js/Error #"RESERVED"
                            (codec/as-element [:div {:ref reserved}])))
      (is (thrown-with-msg? js/Error #"RESERVED"
                            (codec/as-element [a-host {:ref reserved}]))))))

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

;; ---------------------------------------------------------------------------
;; The minted key warnings — development only (rf2-2rtt6.104)
;; ---------------------------------------------------------------------------
;;
;; Every row below owns its own view and member NAMES, because the dedupe
;; Set is deliberately page-lifetime and has no reset hook: "once per site"
;; is the contract, and a fixture that reset it would be testing a
;; different one. Distinct names are the cheaper discipline and they also
;; make each row's site visible in its own source.

(defn- named-view
  "A distinct marked boundary head, stamped the way an arm's mint stamps
  one, so the warning has a name to say."
  [nm]
  (let [f (fn [_js-props] nil)]
    (unchecked-set f "displayName" nm)
    (codec/mark-boundary! f)))

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

(defn- lowering-as
  "Lower `hiccup` with `owner` recorded as the enclosing view, the way the
  arm's `run-once` records it — and clear it afterwards, the way `run-once`
  clears it in its `finally`."
  [owner hiccup]
  (codec/set-lowering-owner! owner)
  (try (codec/as-element hiccup)
       (finally (codec/set-lowering-owner! nil))))

(deftest an-unkeyed-boundary-seq-warns-once-naming-the-view-the-child-and-the-index
  (let [row  (named-view "w1.ns/row")
        out  (warnings-during
               #(lowering-as "w1.ns/list" [:ul (for [i (range 3)] [row {:id i}])]))
        line (first out)]
    (is (= 1 (count out)) "one line, not one per member")
    (is (re-find #"w1\.ns/list" line) "the enclosing view — the fact React cannot name")
    (is (re-find #"w1\.ns/row" line) "the member head")
    (is (re-find #"first at index 0" line))
    (is (re-find #":key in its props map" line) "the fix spelling, not just the complaint")
    (is (re-find #":rf\.warning/hicasso-missing-key" line))))

(deftest a-keyed-boundary-seq-is-silent-and-so-is-every-legitimate-key-shape
  (testing "the canonical keyed list says nothing at all"
    (let [row (named-view "w2.ns/row")]
      (is (= [] (warnings-during
                  #(lowering-as "w2.ns/list"
                                [:ul (for [i (range 300)] [row {:key i}])]))))))
  (testing "each primitive key shape individually — a warning that fired on
            valid code would be worse than no warning"
    (doseq [[label k] [["number" 1] ["string" "a"] ["keyword" :a]
                       ["namespaced-keyword" :ns/a] ["negative-number" -1]
                       ["zero" 0] ["empty-string" ""]]]
      (let [row (named-view (str "w2b.ns/" label))]
        (is (= [] (warnings-during
                    #(lowering-as "w2b.ns/list" [:ul (list [row {:key k}])])))
            (str "a " label " key is a legitimate key"))))))

(deftest the-site-warns-exactly-once-however-many-renders-it-takes
  (let [row     (named-view "w3.ns/row")
        hiccup  [:ul (list [row {}] [row {}])]
        first-r (warnings-during #(lowering-as "w3.ns/list" hiccup))
        again   (warnings-during #(lowering-as "w3.ns/list" hiccup))]
    (is (= 1 (count first-r)) "two unkeyed members of one head are one site")
    (is (= [] again) "a second render of the same site re-fires nothing")))

(deftest a-site-is-the-pair-of-the-enclosing-view-and-the-member-head
  (testing "one view, two member heads — two sites"
    (let [a   (named-view "w4.ns/a")
          b   (named-view "w4.ns/b")
          out (warnings-during
                #(lowering-as "w4.ns/list" [:ul (list [a {}] [b {}])]))]
      (is (= 2 (count out)) "scanning past the first offender is what finds the second")))
  (testing "one member head, two views — two sites"
    (let [row (named-view "w5.ns/row")
          out (warnings-during
                (fn []
                  (lowering-as "w5.ns/one" [:ul (list [row {}])])
                  (lowering-as "w5.ns/two" [:ul (list [row {}])])))]
      (is (= 2 (count out))))))

(deftest a-key-that-computed-nil-warns-because-the-emitted-element-is-keyless
  (testing "`:key nil` and an absent `:key` mint the same keyless element, so
            the check and the emission gate cannot disagree"
    (let [row (named-view "w6.ns/row")
          out (warnings-during
                #(lowering-as "w6.ns/list" [:ul (list [row {:key nil :id 1}])]))]
      (is (= 1 (count out)))
      (is (re-find #"absent or nil" (first out))
          "the author who DID write :key is pointed at the value")))
  (testing "the conditional-key idiom that yields no key at all"
    (let [row (named-view "w7.ns/row")
          out (warnings-during
                #(lowering-as "w7.ns/list"
                              [:ul (list [row (when false {:key 1})])]))]
      (is (= 1 (count out))))))

(deftest a-mixed-seq-names-the-first-unkeyed-member
  (let [row (named-view "w8.ns/row")
        out (warnings-during
              #(lowering-as "w8.ns/list"
                            [:ul (list [row {:key 0}] [row {:key 1}]
                                       [row {}] [row {:key 3}])]))]
    (is (= 1 (count out)))
    (is (re-find #"first at index 2" (first out))
        "keyed members are scanned past and never named")))

(deftest an-entity-valued-key-warns-about-the-coercion-hazard
  (testing "a map at :key — React string-coerces it, so the child is keyed by
            its own CONTENT and an edit silently remounts the row"
    (let [row  (named-view "w9.ns/row")
          out  (warnings-during
                 #(lowering-as "w9.ns/list"
                               [:ul (list [row {:key {:id 1 :label "a"}}])]))
          line (first out)]
      (is (= 1 (count out)))
      (is (re-find #"w9\.ns/list" line))
      (is (re-find #"carries a map at :key" line))
      (is (re-find #"first at index 0" line))
      (is (re-find #":rf\.warning/hicasso-entity-key" line))
      (is (nil? (re-find #":label" line))
          "the VALUE never reaches the console — a cyclic or throwing foreign
           value would blow pr-str inside a diagnostic")))
  (testing "a vector and a set are the same hazard, and two distinct sites"
    (let [row (named-view "w10.ns/row")
          out (warnings-during
                #(lowering-as "w10.ns/list"
                              [:ul (list [row {:key [1 2]}] [row {:key #{1}}])]))]
      (is (= 2 (count out)))
      (is (re-find #"carries a vector at :key" (first out)))
      (is (re-find #"carries a set at :key" (second out))))))

(deftest the-scope-line-holds-in-both-directions
  (testing "native-tag members are React's beat — there is no boundary head to name"
    (is (= [] (warnings-during
                #(lowering-as "w11.ns/list" [:ul (for [i (range 3)] [:li i])])))))
  (testing "host-headed members are silent in v1"
    (is (= [] (warnings-during
                #(lowering-as "w12.ns/list" [:ul (list [a-host {}])])))))
  (testing "strings, numbers and nils in a seq are not elements"
    (is (= [] (warnings-during
                #(lowering-as "w13.ns/list" [:ul (list "a" 1 nil false)])))))
  (testing "a headless vector is somebody else's refusal — the check tolerates
            it silently and leaves `vec->element`'s loud error to speak"
    (is (= [] (warnings-during
                #(try (lowering-as "w14.ns/list" [:ul (list [])])
                      (catch :default _ nil)))))))

(deftest every-parent-class-reaches-the-check-through-the-one-site
  (testing "a fragment parent"
    (let [row (named-view "w15.ns/row")]
      (is (= 1 (count (warnings-during
                        #(lowering-as "w15.ns/list" [:<> (list [row {}])])))))))
  (testing "a host parent — this is what the [:>] crossing will inherit"
    (let [row (named-view "w16.ns/row")]
      (is (= 1 (count (warnings-during
                        #(lowering-as "w16.ns/list" [a-host {} (list [row {}])])))))))
  (testing "a nested seq is checked at its own level, one level at a time"
    (let [row (named-view "w17.ns/row")]
      (is (= 1 (count (warnings-during
                        #(lowering-as "w17.ns/list"
                                      [:ul (list (list [row {}]))]))))))))

(deftest the-crossing-into-a-boundary-warns-where-nothing-else-can
  (testing "`[a-view {} (for …)]` — realize-children flattens the seq into
            direct arguments, which React marks validated and never warns
            about, so this call site is the only signal there is"
    (let [outer (named-view "w18.ns/outer")
          row   (named-view "w18.ns/row")
          out   (warnings-during
                  #(lowering-as "w18.ns/page"
                                [outer {} (for [_ (range 3)] [row {}])]))]
      (is (= 1 (count out)))
      (is (re-find #"w18\.ns/page" (first out))
          "the owner slot is the body that WROTE the crossing seq")
      (is (re-find #"w18\.ns/row" (first out)))))
  (testing "a keyed crossing seq is silent"
    (let [outer (named-view "w19.ns/outer")
          row   (named-view "w19.ns/row")]
      (is (= [] (warnings-during
                  #(lowering-as "w19.ns/page"
                                [outer {} (for [i (range 3)] [row {:key i}])]))))))
  (testing "a crossing seq of native members is silent — the presence fixture's
            own shape, and every `[presence {…} (for … [:div.toast {:key id}])]`
            the guide teaches"
    (let [tray (named-view "w20.ns/tray")]
      (is (= [] (warnings-during
                  #(lowering-as "w20.ns/page"
                                [tray {:timeout-ms 300}
                                 (for [i (range 3)]
                                   [:div.toast {:key i} "x"])])))))))

(deftest the-owner-clause-is-dropped-rather-than-guessed-when-no-body-is-lowering
  (let [row  (named-view "w21.ns/row")
        out  (warnings-during
               (fn []
                 (codec/set-lowering-owner! nil)
                 (codec/as-element [:ul (list [row {}])])))
        line (first out)]
    (is (= 1 (count out)))
    (is (re-find #"^\[hicasso\] Unkeyed boundary children: " line)
        "no owner clause at all, rather than a stale or invented one")
    (is (re-find #"w21\.ns/row" line) "the member head still names itself")))

(deftest an-unbalanced-owner-pair-is-pinned-on-the-console
  (testing "setting a non-nil owner over a non-nil one says the pair is unbalanced"
    (let [out (warnings-during
                (fn []
                  (codec/set-lowering-owner! "w22.ns/first")
                  (codec/set-lowering-owner! "w22.ns/second")
                  (codec/set-lowering-owner! nil)))]
      (is (= 1 (count out)))
      (is (re-find #"w22\.ns/first" (first out)))
      (is (re-find #"unbalanced" (first out)))))
  (testing "the set/clear/set the arm actually performs is silent"
    (is (= [] (warnings-during
                (fn []
                  (codec/set-lowering-owner! "w23.ns/a")
                  (codec/set-lowering-owner! nil)
                  (codec/set-lowering-owner! "w23.ns/b")
                  (codec/set-lowering-owner! nil)))))))
