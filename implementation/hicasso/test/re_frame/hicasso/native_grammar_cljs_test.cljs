(ns re-frame.hicasso.native-grammar-cljs-test
  "THE `n/$` GRAMMAR, DRIVEN PRODUCTION BY PRODUCTION (rf2-hic-030).

  The grammar is four lines in
  `docs/design/hicasso/product/lanes/ergonomics-api.md`:

      (n/$ head)
      (n/$ head child*)
      (n/$ head literal-props child*)
      (n/$ head (n/props dynamic-props) child*)

  Every row below is derived from that text rather than from the
  implementation, and every row is written to fail under a NAMED
  narrowing — the one-line change that would let a wrong implementation
  pass it. The narrowing is recorded in each row's docstring, because a
  row whose narrowing cannot be named is a row that is decorative.

  ## Why so much of this drives `prop-slots` directly

  The three props-map refusals fire at MACRO EXPANSION for a literal map
  — writing `(n/$ :div {:children x})` here would fail the BUILD, not a
  test. So the literal path is witnessed the only way a compiled lane
  can witness it: by asserting that the macro's emission is EQUAL to
  what the shared rule answers, three ways at once (macro emission,
  `prop-slots`, `props*`). If the macro carried its own copy of the
  conversion the three-way row breaks, which is the property
  `impl/slot` was extracted to guarantee one layer down.

  ## The row this file exists for

  [[a-dynamic-element-in-props-position-is-a-child]]. A React element is
  a JavaScript object, so an implementation that asked \"is this operand
  an object?\" at runtime would classify an element as props. That row is
  the sabotage the grammar's syntactic rule was designed against, and it
  is the reason the props operand is four written FORMS rather than a
  runtime test."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.native :as n]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- slots
  "The React slot names on an element's props, as a set. `children` is
  React's own and is excluded, so a row about PROPS is not perturbed by
  a row about children."
  [el]
  (disj (set (js/Object.keys (.-props el))) "children"))

(defn- slot-of
  "The value at one React slot, read off the element React actually
  built rather than off an intermediate the test constructed."
  [el s]
  (unchecked-get (.-props el) s))

(defn- kids
  "An element's children, always as a vector, so the 0/1/N shapes React
  chooses between are one shape to assert on."
  [el]
  (let [c (.. el -props -children)]
    (cond
      (undefined? c) []
      (array? c)     (vec c)
      :else          [c])))

(defn- refusal
  "Run `f` and answer the refusal's ex-data — or a marker saying what
  happened instead, so a failing row reports WHICH of the two ways it
  failed rather than throwing out of the assertion."
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; 1. Heads
;; ---------------------------------------------------------------------------

(deftest heads-are-classified-syntactically
  (testing "an unqualified keyword names an intrinsic element — it lowers
            to the NAME, so an implementation that passed the keyword
            through by identity (React would receive a Keyword object)
            fails here"
    (is (= "div" (.-type (n/$ :div)))))

  (testing "a string head is verbatim, custom elements and SVG included —
            an implementation that camelCased or otherwise touched a
            string head fails on the hyphen"
    (is (= "my-web-component" (.-type (n/$ "my-web-component"))))
    (is (= "feGaussianBlur" (.-type (n/$ "feGaussianBlur")))))

  (testing "any other head expression is evaluated and used as the type —
            an implementation that only accepted keywords and strings
            fails here, and so does one that stringified the component"
    (let [component (fn Widget [_props] nil)]
      (is (identical? component (.-type (n/$ component))))))

  (testing "there is NO selector shorthand in the v0 native grammar: a
            dotted keyword is the element name it spells, not a div with
            a class. The row is here because the DOCUMENT says the
            production is absent, so an implementation that quietly
            added Hiccup's selector parsing must fail something"
    (is (= "div.card" (.-type (n/$ :div.card))))
    (is (= #{} (slots (n/$ :div.card))))))

;; ---------------------------------------------------------------------------
;; 2. The props operand — the four written forms, and everything else
;; ---------------------------------------------------------------------------

(deftest the-headless-production-carries-neither-props-nor-children
  (testing "`(n/$ head)` — narrowing caught: an implementation that
            always emitted an empty children array would show one child"
    (let [el (n/$ :div)]
      (is (= #{} (slots el)))
      (is (= [] (kids el))))))

(deftest trailing-forms-are-children-and-order-is-preserved
  (testing "`(n/$ head child*)` — narrowing caught: 'the second operand
            is the props operand', which would take \"a\" out of the
            children and put it in props"
    (let [el (n/$ :div "a" "b" "c")]
      (is (= #{} (slots el)))
      (is (= ["a" "b" "c"] (kids el)))))

  (testing "six children reach the variadic emission arm in order —
            narrowing caught: an arity split that dropped or reordered
            the tail"
    (is (= ["a" "b" "c" "d" "e" "f"] (kids (n/$ :div "a" "b" "c" "d" "e" "f"))))))

(deftest a-literal-nil-is-the-props-operand-and-not-a-child
  (testing "narrowing caught: 'only a map or n/props is props', which
            would leave nil in child position and give this element two
            children rather than one"
    (let [el (n/$ :div nil "only")]
      (is (= ["only"] (kids el))))))

(deftest a-js-object-literal-is-the-props-operand
  (testing "`#js {}` is one of the four written props forms — narrowing
            caught: an implementation recognising only ClojureScript
            maps, which would make this object a child"
    (let [el (n/$ :div #js {"className" "c"} "kid")]
      (is (= #{"className"} (slots el)))
      (is (= "c" (slot-of el "className")))
      (is (= ["kid"] (kids el))))))

(deftest a-dynamic-element-in-props-position-is-a-child
  (testing "THE misclassification sabotage. A React element is a
            JavaScript object, so an implementation that inspected the
            runtime value — `(if (object? x) props child)` — would put
            this element in the props slot. The grammar is syntactic
            precisely so that cannot happen"
    (let [inner (n/$ :span nil "inner")
          el    (n/$ :div inner)]
      (is (= #{} (slots el)))
      (is (= 1 (count (kids el))))
      (is (identical? inner (first (kids el))))))

  (testing "and the same element AFTER a literal props operand is still
            a child — narrowing caught: 'the first object-valued operand
            wins the props slot'"
    (let [inner (n/$ :span nil "inner")
          el    (n/$ :div {:class "c"} inner)]
      (is (= #{"className"} (slots el)))
      (is (identical? inner (first (kids el)))))))

;; ---------------------------------------------------------------------------
;; 3. The slot rule, on the literal path
;; ---------------------------------------------------------------------------

(deftest literal-props-lower-through-the-canonical-slot-rule
  (let [el (n/$ :div {:class       "card"
                      :for         "field"
                      :on-click    identity
                      :data-row-id 7
                      :aria-label  "row"
                      :viewBox     "0 0 1 1"
                      "tabIndex"   3})]

    (testing "every spelling in the rule's vocabulary lands on its React
              slot. Narrowings caught, one per key: `(name k)` misses
              className and onClick; camelCasing everything breaks
              data-row-id and aria-label; `str/capitalize` (which
              lower-cases the tail) turns viewBox into Viewbox; treating
              a string key like a keyword would rewrite tabIndex"
      (is (= #{"className" "htmlFor" "onClick" "data-row-id" "aria-label"
               "viewBox" "tabIndex"}
             (slots el))))

    (testing "and the values are the ones written"
      (is (= "card" (slot-of el "className")))
      (is (= 7 (slot-of el "data-row-id")))
      (is (= 3 (slot-of el "tabIndex"))))))

(deftest prop-values-pass-by-identity
  (testing "no deep conversion, no keyword-value conversion, no
            style-map conversion. Narrowing caught: a `clj->js` anywhere
            on the props path — which is the single most likely wrong
            implementation of this tier, because it is what every
            Reagent-shaped instinct reaches for"
    (let [style  #js {:transform "translateX(4px)"}
          data   {:deep {:nested true}}
          el     (n/$ :div {:style style :data-x data :aria-sort :ascending})]
      (is (identical? style (slot-of el "style")))
      (is (identical? data (slot-of el "data-x")))
      (is (= :ascending (slot-of el "aria-sort")))
      (is (keyword? (slot-of el "aria-sort"))))))

(deftest key-and-ref-use-reacts-ordinary-slots
  (testing "narrowing caught: an implementation that normalised :key
            into the props object rather than handing it to
            createElement, which is where React reads it"
    (is (= "k1" (.-key (n/$ :li {:key "k1"} "x"))))
    (is (= #{} (slots (n/$ :li {:key "k1"} "x"))))))

(deftest an-empty-literal-props-map-is-the-same-element-as-nil-props
  (testing "narrowing caught: emitting an empty object for `{}` and
            nothing for `nil`, which would make two spellings of one
            element observably different"
    (is (= (slots (n/$ :div {} "x")) (slots (n/$ :div nil "x"))))
    (is (= (kids (n/$ :div {} "x")) (kids (n/$ :div nil "x"))))))

;; ---------------------------------------------------------------------------
;; 4. The dynamic path, and its parity with the literal one
;; ---------------------------------------------------------------------------

(def ^:private corpus
  "The parity corpus. One map, written once, driven through BOTH lowering
  paths and through the shared rule itself."
  {:class       "card"
   :for         "field"
   :data-row-id 7
   :aria-label  "row"
   :viewBox     "0 0 1 1"
   "tabIndex"   3})

(deftest the-macro-and-the-runtime-share-one-rule-rather-than-two-copies
  (let [from-rule    (into {} (n/prop-slots corpus 'test))
        from-runtime (let [o (n/props corpus)]
                       (into {} (map (fn [k] [k (unchecked-get o k)]))
                             (js/Object.keys o)))
        from-macro   (let [el (n/$ :div (n/props corpus))]
                       (into {} (map (fn [k] [k (slot-of el k)])) (slots el)))]

    (testing "the shared rule, the runtime conversion and the emitted
              element all answer the same slot map. Narrowing caught: a
              second conversion algorithm on EITHER path — the exact
              defect `impl/slot` exists to delete, reproduced one layer
              up. Any divergence in a single spelling reds this"
      (is (= from-rule from-runtime))
      (is (= from-rule from-macro)))

    (testing "and the corpus really did exercise the interesting
              spellings, so the equality above cannot hold vacuously"
      (is (= #{"className" "htmlFor" "data-row-id" "aria-label" "viewBox" "tabIndex"}
             (set (keys from-rule)))))))

(deftest a-literal-map-and-a-marked-dynamic-map-lower-identically
  (testing "the same authored map, one written literally and one reached
            through `n/props`, produces the same element. Narrowing
            caught: a macro that lowered literals with its own inline
            walk"
    (let [literal (n/$ :div {:class "card" :for "field" :data-row-id 7})
          dynamic (n/$ :div (n/props {:class "card" :for "field" :data-row-id 7}))]
      (is (= (slots literal) (slots dynamic)))
      (is (= (slot-of literal "className") (slot-of dynamic "className")))
      (is (= (slot-of literal "htmlFor") (slot-of dynamic "htmlFor")))
      (is (= (slot-of literal "data-row-id") (slot-of dynamic "data-row-id"))))))

(deftest a-javascript-object-passes-by-identity-through-n-props
  (testing "a raw JavaScript object already carries React's own names,
            so it is not renamed and not copied. Narrowing caught: a
            `js->clj`/`clj->js` round trip, or running the slot rule
            over an object's keys — either of which would answer a NEW
            object here"
    (let [o #js {"className" "c" "data-x" 1}]
      (is (identical? o (n/props o)))))

  (testing "and nil passes too, so `(n/props nil)` is `(n/$ head nil …)`"
    (is (nil? (n/props nil)))))

;; ---------------------------------------------------------------------------
;; 5. The refusals — every closed set driven at a member AND a non-member
;; ---------------------------------------------------------------------------

(deftest children-in-a-props-map-refuses
  (testing "there is one child channel. Member of the closed set"
    (is (= :rf.error/hicasso-native-children-in-props
           (:rf.error/id (refusal #(n/props {:children "x"}))))))

  (testing "and a string spelling normalises to the same slot, so it
            refuses too — narrowing caught: a check written against the
            KEYWORD `:children` rather than against the React slot"
    (is (= :rf.error/hicasso-native-children-in-props
           (:rf.error/id (refusal #(n/props {"children" "x"}))))))

  (testing "NON-MEMBER: a neighbouring key is an ordinary slot. A row
            that only drove the member could not see an implementation
            that refused every key beginning with `child`"
    (is (= #{"child"} (set (map first (n/prop-slots {:child "x"} 'test)))))))

(deftest a-normalised-slot-collision-refuses-rather-than-picking-a-winner
  (testing "two spellings of one React slot in one map. Member"
    (let [data (refusal #(n/props {:class "a" "className" "b"}))]
      (is (= :rf.error/hicasso-native-slot-collision (:rf.error/id data)))
      (is (= "className" (:slot data)))))

  (testing "a NAMESPACED key normalises by its name, so it collides with
            the unqualified spelling — this is the namespaced-key
            ambiguity, and the shared rule is what decides it"
    (is (= :rf.error/hicasso-native-slot-collision
           (:rf.error/id (refusal #(n/props {:x/class "a" :class "b"}))))))

  (testing "NON-MEMBER 1: a namespaced key ALONE is not a collision — it
            lowers to its slot. Narrowing caught: 'refuse every
            namespaced key', which would pass the two rows above while
            refusing a legal map"
    (is (= [["className" "a"]] (n/prop-slots {:x/class "a"} 'test))))

  (testing "NON-MEMBER 2: two distinct slots do not collide. Narrowing
            caught: a `seen` set that was never keyed by slot — an
            implementation refusing any map with two keys passes both
            member rows"
    (is (= 2 (count (n/prop-slots {:class "a" :id "b"} 'test))))))

(deftest an-event-vector-at-a-native-callback-slot-refuses
  (testing "past the fence nothing lowers an intent. Member"
    (let [data (refusal #(n/props {:on-click [:watchlist/select 1]}))]
      (is (= :rf.error/hicasso-native-intent-in-prop (:rf.error/id data)))
      (is (= "onClick" (:slot data)))))

  (testing "NON-MEMBER 1: an ordinary function at the same slot is the
            native spelling and passes. Narrowing caught: 'refuse every
            on* prop'"
    (is (= 1 (count (n/prop-slots {:on-click identity} 'test)))))

  (testing "NON-MEMBER 2: a vector at a NON-callback slot is ordinary
            data passing by identity. Narrowing caught: 'refuse every
            vector-valued prop', which would break a ClojureScript
            component head taking a collection"
    (is (= [["data-items" [1 2 3]]] (n/prop-slots {:data-items [1 2 3]} 'test))))

  (testing "NON-MEMBER 3: a vector at a callback slot whose head is not
            a keyword is not an intent. Narrowing caught: 'refuse every
            vector at an on* slot'"
    (is (= 1 (count (n/prop-slots {:on-click [1 2]} 'test))))))

(deftest a-clojurescript-map-in-child-position-refuses
  (testing "the unmarked dynamic props operand, which the grammar makes
            a CHILD. Member — and the recovery is what names `n/props`"
    (let [cell {:class "px"}
          data (refusal #(n/$ :td cell "5"))]
      (is (= :rf.error/hicasso-native-map-as-child (:rf.error/id data)))
      (is (= :mark-the-props-operand-with-n-props (:recovery data)))
      (is (= cell (:child data)))))

  (testing "NON-MEMBER: the same map MARKED is props and refuses
            nothing. Narrowing caught: refusing every map operand
            outright, which would pass the member row while making the
            grammar's fourth production unreachable"
    (let [cell {:class "px"}]
      (is (= #{"className"} (slots (n/$ :td (n/props cell) "5")))))))

(deftest a-hiccup-vector-in-child-position-refuses
  (testing "brackets have no meaning past the fence. Member"
    (let [row  [:span "not markup here"]
          data (refusal #(n/$ :div row))]
      (is (= :rf.error/hicasso-native-hiccup-child (:rf.error/id data)))
      (is (= :nest-n-dollar-or-convert-with-h-as-element (:recovery data)))))

  (testing "NON-MEMBER 1: a ClojureScript SEQ of elements is ES6-iterable
            and is therefore already a React child. Narrowing caught:
            'refuse every ClojureScript collection in child position'"
    (let [els (map #(n/$ :li {:key %} %) [1 2])
          el  (n/$ :ul els)]
      (is (= [els] (kids el)))))

  (testing "NON-MEMBER 2: a JavaScript array child passes untouched —
            read off `props.children` directly, because a single array
            child and two children are the same shape to React and
            [[kids]] cannot tell them apart"
    (let [arr #js ["a" "b"]]
      (is (identical? arr (.. (n/$ :div arr) -props -children))))))

;; ---------------------------------------------------------------------------
;; 6. The refusal shape (rf2-hic-007), asserted once as a whole map
;; ---------------------------------------------------------------------------

(deftest a-native-refusal-carries-the-stable-shape
  (testing "the id, the fn that refused, a non-blank reason and a
            concrete recovery — asserted as a map rather than as
            `(thrown? …)`, because a refusal's subject is its identity
            and a neighbouring complaint would satisfy a throw"
    (let [data (refusal #(n/props {:children "x"}))]
      (is (= :rf.error/hicasso-native-children-in-props (:rf.error/id data)))
      (is (= 're-frame.hicasso.native/props (:where data)))
      (is (string? (:reason data)))
      (is (seq (:reason data)))
      (is (= :pass-children-after-the-props-operand (:recovery data)))
      (is (= :children (:prop data)))))

  (testing "and a refusal raised from the element path names THAT door,
            so a reader can tell which of the two conversion sites
            refused"
    (let [row [:span "x"]]
      (is (= 're-frame.hicasso.native/$
             (:where (refusal #(n/$ :div {:a 1} row))))))))
