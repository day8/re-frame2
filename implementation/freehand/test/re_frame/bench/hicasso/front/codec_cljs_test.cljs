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

(deftest the-shorthand-fast-lane-emits-what-the-donor-map-produced
  ;; rf2-y1jkm lane 2: an element whose only class is the tag shorthand no
  ;; longer routes through merge-shorthand's dissoc/assoc — the loop runs
  ;; over the author's map and the shorthand's className is written AFTER
  ;; it, because the donor path re-assoc'd `:class` LAST. The observable
  ;; pin is the overwrite order for an exotic spelling that canonicalises
  ;; onto the same slot: the shorthand won under the donor's map order and
  ;; must keep winning under the lane.
  (testing "a namespaced class spelling does not defeat the shorthand"
    (is (= "a" (prop (codec/as-element [:div.a {:x/class "b"}]) "className"))))
  (testing "a literal :class still takes the general lane and composes"
    (is (= "a b" (prop (codec/as-element [:div.a {:class "b"}]) "className"))))
  (testing "a caller remainder still takes the general lane and composes"
    (is (= "a b" (prop (codec/as-element [:div.a {:& {:class "b"}}]) "className")))))

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
        (is (= "base owned" (prop e "className")))))))

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
