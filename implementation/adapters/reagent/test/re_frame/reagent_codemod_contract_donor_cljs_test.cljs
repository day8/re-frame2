(ns re-frame.reagent-codemod-contract-donor-cljs-test
  "The DONOR half of the M4 contract row (rf2-d2mwk).

  The `[:>]` migration codemod — designed at
  `docs/design/hicasso/studio/reagent-codemod-against-the-landed-escape.md`
  — rewrites a consumer's Reagent sources so that a codebase whose
  `[:> …]` sites are about to be read by Hicasso keeps behaving the way
  Reagent made it behave. Every rewrite in that design is argued from a
  proof sketch, and every proof sketch is a claim about what **Reagent
  2.0.1** does. The obligation is therefore two-sided.

  The Hicasso side already landed, as
  `conversion-parity-with-the-door-on-one-prop-corpus` in
  `implementation/hicasso/test/re_frame/hicasso/codec_cljs_test.cljs`
  (it was written in the prototype's `front/codec_cljs_test.cljs`, which
  rf2-3ewp moved into the package).
  This file is the other half: **the same sixteen-prop corpus, through
  Reagent's own `[:>]` walk**, asserting the donor's answers. The pair
  straddles the equivalence the codemod claims.

  Nothing here was broken when it was written, and that is the point.
  These claims are currently verified by *reading* a dependency — the
  design cites `reagent.impl.template`'s `vec-to-elem`, `native-element`,
  `convert-props`, `convert-prop-value`, `kv-conv` and `cached-prop-name`,
  and `reagent.impl.util`'s `dash-to-prop-name`, `js-val?`, `class-names`
  and `get-react-key`, by symbol. A Reagent upgrade could move any of
  them and, before this file, nothing would have failed. Each deftest
  below names the design section whose proof it pins, so a red row says
  which argument just stopped being true.

  READ THROUGH THE PUBLIC SEAM. Every assertion goes through
  `reagent.core/as-element` and reads the React element it returns —
  what a consumer's component actually receives — rather than reaching
  into `reagent.impl.*`. A pin on an internal function would go red on a
  refactor that changed nothing observable, and stay green on a rewrite
  of the public conversion that changed everything.

  SCOPE. One corpus, the rows the proof sketches rest on. This is
  deliberately NOT a general Reagent-behaviour suite."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.core :as r]))

;; ---------------------------------------------------------------------------
;; The corpus, and how it is read
;; ---------------------------------------------------------------------------

(defn- a-foreign-component
  "Stands in for a library's component — anything React would accept as
  an element type. Nothing renders here; every question below is about
  what Reagent's conversion BUILT. Deliberately the same stand-in the
  Hicasso-side witness uses."
  [_js-props]
  nil)

(defn- emitted
  "The props object the foreign component would receive for `hiccup`.
  Reagent hands `convert-props`' result straight to `createElement`, so
  the element's own props are that object minus React's two reserved
  names."
  [hiccup]
  (.-props (r/as-element hiccup)))

(defn- donor-prop
  "The value Reagent emitted at `slot` for a ONE-prop `[:>]` crossing —
  the twin of the witness's `raw-prop`, so the two can be read against
  each other. One prop at a time, because a sixteen-entry map's
  `reduce-kv` order is ClojureScript's business and not a contract (see
  the `className` collision below)."
  [k v slot]
  (aget (emitted [:> a-foreign-component {k v}]) slot))

(defn- crossed-same?
  "Did two crossings answer the same thing? Functions by identity — which
  is the point of those rows — and everything else by value through
  `js->clj`, because two `clj->js` results are two distinct objects and
  `=` on those is identity. The witness's helper, transcribed."
  [a b]
  (if (fn? a) (identical? a b) (= (js->clj a) (js->clj b))))

;; The sixteen-prop corpus, character for character the one
;; `conversion-parity-with-the-door-on-one-prop-corpus` runs. The two
;; function slots are bound by the caller so identity rows can be
;; asserted.
(defn- corpus [f ref-fn]
  {:on-thing   f
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
   :ref        ref-fn})

;; ---------------------------------------------------------------------------
;; The corpus, whole
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-the-whole-corpus
  (testing "THE ROW THE OTHER FIFTEEN DECOMPOSE. Reagent routes
            `[:> C props]` through `vec-to-elem`'s `:>` case into
            `native-element` with a `HiccupTag` whose id, className and
            custom flag are all nil, and thence into `convert-props` →
            `convert-prop-value` → `kv-conv`. This is what comes out the
            far end. (§3's conversion table, donor column.)"
    (let [f      (fn [_])
          ref-fn (fn [_node])
          p      (emitted [:> a-foreign-component (corpus f ref-fn)])]
      (testing "the emitted slot roster — SIXTEEN props, FIFTEEN slots"
        (is (= ["aria-label" "className" "count" "data-kind" "id" "label"
                "menuItems" "onThing" "open" "options" "plainFn" "ref"
                "role" "theme" "variant"]
               (vec (sort (js/Object.keys p))))
            "`cached-prop-name` spells BOTH `:class` and `:className` as
             `className`, so one `gobj/set` overwrites the other and the
             corpus loses a slot on the way across"))
      (testing "which of the two survives is `reduce-kv` order over a
                sixteen-entry map — ClojureScript's business, not
                Reagent's — so the pin is that exactly one does"
        (is (contains? #{"btn on" "wide"} (aget p "className"))))
      (testing "functions cross by identity: `js-val?` is
                `(not (identical? \"object\" (goog/typeOf x)))` and
                `goog/typeOf` answers \"function\" for a function, so the
                first `cond` arm returns it untouched"
        (is (identical? f (aget p "onThing")))
        (is (identical? f (aget p "plainFn")))
        (is (identical? ref-fn (aget p "ref"))))
      (testing "scalars verbatim, by the same arm"
        (is (= "due date" (aget p "label")))
        (is (= 7 (aget p "count")))
        (is (true? (aget p "open"))))
      (testing "named values answer `(name x)` at every slot, HTML
                attribute or not"
        (is (= "compact" (aget p "variant")))
        (is (= "dark" (aget p "theme")))
        (is (= "close" (aget p "aria-label")))
        (is (= "row" (aget p "data-kind")))
        (is (= "greeting" (aget p "id")))
        (is (= "dialog" (aget p "role"))))
      (testing "a nested map literal is walked and its keys respelled; a
                map reached through a VECTOR is not"
        (is (= {"pageSize" 10} (js->clj (aget p "options"))))
        (is (= [{"day-of-week" 1}] (js->clj (aget p "menuItems")))
            "`:day-of-week` kept the author's spelling — the `coll?` arm
             is `clj->js`, which does not camelCase")))))

;; ---------------------------------------------------------------------------
;; W1 — the metadata key (§4.1)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w1-the-metadata-key
  (testing "W1's proof sketch: `native-element` sets `.-key` on the
            converted props object from `(-> (meta argv) get-react-key)`
            AFTER `convert-props` has run. So a metadata key is LIVE at a
            `[:>]` crossing — which is why losing it at the migration is
            the highest-value rewrite in the design."
    (is (= "k7" (.-key (r/as-element ^{:key "k7"} [:> a-foreign-component {:label "x"}]))))
    (is (= "7" (.-key (r/as-element ^{:key 7} [:> a-foreign-component {:label "x"}])))
        "React stringifies keys"))
  (testing "a props `:key` is converted like any other prop — `kv-conv`
            spells the slot `key` and `convert-prop-value` answers
            `(name :foo)` — and React then consumes it off the config.
            That is §9.3's whole argument: `:foo` reached React as
            \"foo\" under the donor."
    (let [e (r/as-element [:> a-foreign-component {:key :foo :label "x"}])]
      (is (= "foo" (.-key e)))
      ;; `js/Object.keys` rather than a read of `.-key`: React 19 installs
      ;; a non-enumerable DEV warning getter at that name on any props
      ;; object it built from a config carrying a key, so reading it would
      ;; put React's "`key` is not a prop" line in the shared test log.
      (is (= ["label"] (vec (sort (js/Object.keys (.-props e)))))
          "and React keeps it off the props, so the component never sees it")))
  (testing "and where both are present the METADATA wins, because its
            `set!` runs last — the premise under the design's
            `:key-conflict` refusal (§5.2), which exists precisely
            because W1 would otherwise have to overwrite a value the
            author wrote"
    (is (= "from-meta"
           (.-key (r/as-element ^{:key "from-meta"} [:> a-foreign-component {:key "from-props"}]))))))

;; ---------------------------------------------------------------------------
;; W2 — nested map keys (§4.2)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w2-nested-map-keys
  (testing "W2's proof sketch: `convert-prop-value`'s `map?` arm recurses
            through `kv-conv`, respelling every key with
            `cached-prop-name` — which is `dash-to-prop-name`'s
            kebab→camel rule. W2 exists to write that spelling into the
            source, so the rule is the rewrite."
    (let [o (donor-prop :options {:page-size 10 :first-name "a"} "options")]
      (is (= ["firstName" "pageSize"] (vec (sort (js/Object.keys o)))))
      (is (= 10 (aget o "pageSize")))))
  (testing "the three seeded renames in `prop-name-cache`, and they apply
            INSIDE a nested map as well as at the top — one key function,
            one cache"
    (let [o (donor-prop :cfg {:class "c" :for "f" :charset "utf-8"} "cfg")]
      (is (= ["charSet" "className" "htmlFor"] (vec (sort (js/Object.keys o)))))))
  (testing "`aria` and `data` are exempt — `dash-to-prop-name` returns the
            name whole when the first dash-segment is in
            `dont-camel-case`"
    (let [o (donor-prop :cfg {:aria-label "x" :data-kind "y" :other-key "z"} "cfg")]
      (is (= ["aria-label" "data-kind" "otherKey"] (vec (sort (js/Object.keys o)))))))
  (testing "a STRING key is verbatim: `cached-prop-name` hands back any
            non-`named?` key untouched"
    (let [o (donor-prop :cfg {"first-name" 1} "cfg")]
      (is (= ["first-name"] (vec (js/Object.keys o))))))
  (testing "WHERE THE RECURSION STOPS, which is the half of W2 that is a
            fence rather than a rule. Reagent recurses through the `map?`
            arm only; a map inside a vector is reached by the `coll?` arm,
            which is `clj->js` and does not camelCase. W2 walks map values
            through maps and stops at the first non-map collection because
            that is exactly where the donor stopped."
    (let [arr (donor-prop :menu-items [{:day-of-week 1}] "menuItems")]
      (is (array? arr))
      (is (= ["day-of-week"] (vec (js/Object.keys (aget arr 0))))
          "no camelCasing below a vector"))
    (let [arr (donor-prop :menu-items #{{:day-of-week 1}} "menuItems")]
      (is (array? arr))
      (is (= ["day-of-week"] (vec (js/Object.keys (aget arr 0))))
          "nor below a set")))
  (testing "and it never descends into a `#js {…}` node: that value is
            not `map?`, not `coll?` and not `ifn?`, so it falls to
            `clj->js`, whose `:else` arm returns a foreign value UNCHANGED
            — the same object, not a copy"
    (let [o   #js {:first-name "a"}
          out (donor-prop :opts o "opts")]
      (is (identical? o out))
      (is (= ["first-name"] (vec (js/Object.keys out))))))
  (testing "THE ONE CELL WHERE W2 REFUSES TO PRESERVE (§4.2). A CSS custom
            property is mangled by `dash-to-prop-name` into a style key
            nothing reads, which is why the design reports
            `:css-var-repair` and lets the site start working instead."
    (let [st (donor-prop :style {:--brand-color "red" :font-size 12} "style")]
      (is (= ["BrandColor" "fontSize"] (vec (sort (js/Object.keys st)))))
      (is (= "red" (aget st "BrandColor"))
          "`--brand-color` splits to (\"\" \"\" \"brand\" \"color\"), and the
           empty leading segments capitalize to nothing"))))

;; ---------------------------------------------------------------------------
;; W3 — named prop values (§4.3)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w3-named-prop-values
  (testing "W3's proof sketch: `convert-prop-value` answers `(name x)` for
            any keyword or symbol, at every prop of every element, and
            `[:>]` reaches it through `native-element` like any other. W3
            writes that `name` into the source as a string, and a string
            is a fixpoint on both walks."
    (is (= "compact" (donor-prop :variant :compact "variant")))
    (is (= "my-sym" (donor-prop :variant 'my-sym "variant"))
        "symbols take the same arm — `named?` is keyword-or-symbol"))
  (testing "the HTML-attribute slots W3 SKIPS, and the reason it may: the
            donor already answers `name` there, so the two runtimes agree
            and the rewrite would only grow the diff"
    (is (= "wide" (donor-prop :className :wide "className")))
    (is (= "greeting" (donor-prop :id :greeting "id")))
    (is (= "dialog" (donor-prop :role :dialog "role")))
    (is (= "close" (donor-prop :aria-label :close "aria-label")))
    (is (= "row" (donor-prop :data-kind :row "data-kind"))))
  (testing "THE REPORT OBLIGATION (§4.3). `(name :theme/dark)` is \"dark\",
            so the donor collapsed two distinct keywords onto one string.
            Preserving that is exactly correct AND bakes in the collision
            `rf2-vrvv9` was filed to remove — which is why the design
            fires the rewrite and reports `:namespaced-named-value`."
    (is (= "dark" (donor-prop :theme :theme/dark "theme")))
    (is (= (donor-prop :theme :theme/dark "theme")
           (donor-prop :theme :other/dark "theme"))
        "two distinct values in, ONE value out — the collision, asserted"))
  (testing "the `ref` slot, where W3 reports rather than writes: the donor
            produced a STRING ref, and React 19 removed string refs — so
            preserving Reagent here would restore a crash (`:named-ref`,
            §5.2)"
    (is (= "my-ref" (donor-prop :ref :my-ref "ref")))))

;; ---------------------------------------------------------------------------
;; W4 — the non-fn IFn literal (§4.4)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w4-the-non-fn-ifn
  (testing "W4's proof sketch: `convert-prop-value` ends with an `ifn?`
            arm returning `(fn [& args] (apply x args))`. `r/partial`
            builds a `PartialFn` deftype — an object, so `js-val?` is
            false — and it reaches that arm. Hicasso has no such arm, so
            the object crosses opaque and a working handler stops firing;
            W4 spells the donor's own wrapper at the call site."
    (let [f   (fn [a b] [a b])
          p   (r/partial f 1)
          out (donor-prop :on-pick p "onPick")]
      (is (fn? out) "a real function came out")
      (is (not (identical? p out))
          "and it is NOT the PartialFn — the donor wrapped rather than passed")
      (is (= [1 2] (out 2))
          "RETURN-TRANSPARENT, which is Law 2: whatever `f` returned
           before, the wrapper returns now")
      (is (not (identical? out (donor-prop :on-pick p "onPick")))
          "§4.4's non-obvious half: the wrapper is minted FRESH on every
           conversion, so the prop's identity already changed on every
           render and no downstream memo bail-out was getting a stable
           value. W4 cannot make memoisation worse because there was
           none.")))
  (testing "the contrast that makes the row mean something: a PLAIN
            function is not wrapped, because `js-val?` catches it first"
    (let [f (fn [_])]
      (is (identical? f (donor-prop :on-pick f "onPick"))))))

;; ---------------------------------------------------------------------------
;; The arm ORDER — `coll?` before `ifn?` (§4.6's first guard, §5.3)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-a-carrier-at-an-event-slot-is-inert
  (testing "THE PREMISE UNDER THE DESIGN'S TWO LOUDEST REFUSALS —
            `:event-carrier-goes-live` (§5.2) and
            `:intent-needs-a-declaration` (§5.3). A vector IS `ifn?` in
            ClojureScript, so what decides this row is that
            `convert-prop-value` asks `coll?` FIRST. The donor emitted an
            inert array and the handler never fired."
    (is (ifn? [:boom])
        "precondition: the row is only meaningful because the `ifn?` arm
         WOULD have matched")
    (let [v (donor-prop :on-click [:boom] "onClick")]
      (is (array? v))
      (is (not (fn? v)) "an array, not a handler — nothing fires")
      (is (= ["boom"] (js->clj v)))))
  (testing "and a key-map at an event slot takes the `map?` arm, which is
            equally inert"
    (let [m (donor-prop :on-key-down {"Enter" [:boom]} "onKeyDown")]
      (is (not (fn? m)))
      (is (= {"Enter" ["boom"]} (js->clj m)))))
  (testing "which is what makes the migration an IMPROVEMENT and a
            hazard at once: Hicasso refuses these loudly at render, so a
            silently dead handler becomes a page that throws — possibly
            in a branch the migration's smoke test never reaches"
    (is (= ["boom"] (js->clj (donor-prop :on-click [:boom] "onClick"))))))

;; ---------------------------------------------------------------------------
;; W5 — the inline adapt-react-class head (§4.5)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w5-adapt-react-class-is-the-same-path
  (testing "W5's proof sketch: `vec-to-elem` sends a `NativeWrapper` head
            to `(native-element tag v 1)` and `[:> C …]` to
            `(native-element … v 2)`. Same function, same `convert-props`,
            same nil id/className/custom — two spellings of one path with
            the props slot at a different index. That is the whole licence
            for respelling one as the other."
    (let [f         (fn [_])
          ref-fn    (fn [_node])
          props     (corpus f ref-fn)
          adapted   (r/adapt-react-class a-foreign-component)
          via-adapt (r/as-element [adapted props])
          via-arrow (r/as-element [:> a-foreign-component props])]
      (is (identical? a-foreign-component (.-type via-adapt))
          "the wrapper unwraps to the component itself")
      (is (identical? (.-type via-arrow) (.-type via-adapt))
          "same element type")
      (is (= (vec (sort (js/Object.keys (.-props via-adapt))))
             (vec (sort (js/Object.keys (.-props via-arrow)))))
          "prop for prop, the same emitted slots")
      (doseq [k (js/Object.keys (.-props via-arrow))]
        (is (crossed-same? (aget (.-props via-adapt) k) (aget (.-props via-arrow) k))
            (str "the slot " k " converted the same way at both spellings"))))))

;; ---------------------------------------------------------------------------
;; W6 — the string tag head (§4.6)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-w6-the-string-tag-head
  (testing "W6's proof sketch: under Reagent `[:> \"input\" …]` took the
            CONTROLLED-INPUT wrapper, because `native-element` asks
            `input-component?` on this exact path and it matches \"input\"
            and \"textarea\". Rewriting the site to `[:input …]` lands it
            on Hicasso's own controlled door, which is the taught form."
    (is (not= "input" (.-type (r/as-element [:> "input" {:value "x"}])))
        "not a bare host element — the wrapper class stands in front of it")
    (is (not= "textarea" (.-type (r/as-element [:> "textarea" {:value "x"}])))))
  (testing "and any other string head is handed to React verbatim"
    (is (= "div" (.-type (r/as-element [:> "div" {}])))))
  (testing "W6's SECOND guard (§5.2, `:string-tag-unparseable`): the `:>`
            path never parses the id/class shorthand, so
            `[:> \"div#id\" …]` was already asking React for a nonexistent
            element under the donor. Rewriting it to `[:div#id …]` would
            make the site start working DIFFERENTLY, so the design reports
            instead."
    (is (= "div#id" (.-type (r/as-element [:> "div#id" {}])))
        "the string reached `createElement` whole, shorthand unparsed")))

;; ---------------------------------------------------------------------------
;; Two more §3 rows that carry a §5.2/§5.3 refusal each
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-dangerously-set-inner-html-is-deleted
  (testing "THE `:dangerous-html` BLOCKER (§5.3). `convert-props` ends by
            DELETING `dangerouslySetInnerHTML` unless the value is an
            `UnsafeHTML` instance. Hicasso passes the prop through, so the
            migration RESURRECTS a prop the donor had been silently
            dropping — which is a decision for a person, not a rewrite."
    (let [p (emitted [:> a-foreign-component
                      {:dangerouslySetInnerHTML {:__html "<b>x</b>"} :label "x"}])]
      (is (= ["label"] (vec (sort (js/Object.keys p))))
          "the slot is gone entirely, with no diagnostic")))
  (testing "the one spelling the donor honoured"
    (let [p (emitted [:> a-foreign-component
                      {:dangerouslySetInnerHTML (r/unsafe-html "<b>x</b>")}])]
      (is (= "<b>x</b>" (.-__html (aget p "dangerouslySetInnerHTML")))))))

(deftest codemod-contract-donor-the-ampersand-key-is-just-a-prop
  (testing "THE `:amp-key` REFUSAL (§5.2). `dash-to-prop-name` leaves `:&`
            alone, so the donor emitted a prop LITERALLY NAMED \"&\";
            Hicasso reads the same key as its one attribute merge. Two
            unrelated meanings for one literal, and the author's intent is
            unrecoverable from the text — so the design refuses the site."
    (let [p (emitted [:> a-foreign-component {:& {:a 1} :label "x"}])]
      (is (= ["&" "label"] (vec (sort (js/Object.keys p)))))
      (is (= {"a" 1} (js->clj (aget p "&")))))))

;; ---------------------------------------------------------------------------
;; The class slot (§3's class row, §5.4's first bullet)
;; ---------------------------------------------------------------------------

(deftest codemod-contract-donor-the-class-slot-reads-one-literal-key
  (testing "`convert-props` reads `(:class props)` — the LITERAL key —
            and only then coerces through `class-names`. That is the
            donor's whole class rule."
    (is (= "btn on" (donor-prop :class ["btn" nil :on] "className"))
        "nils dropped, named values `name`d, the rest joined by a space")
    (is (= "dark" (donor-prop :class :theme/dark "className"))))
  (testing "so a collection under ANY OTHER SPELLING was never coerced —
            it reached React as a `clj->js` array and the DOM wrote it as
            \"a,b\". §5.4's first bullet: a divergence the codemod
            deliberately does not repair, because Hicasso is simply better
            here."
    (is (array? (donor-prop :className ["a" "b"] "className")))
    (is (array? (donor-prop :x/class ["a" "b"] "className"))
        "`cached-prop-name` keys its cache on `(name k)`, so a namespaced
         spelling still LANDS on className — it just never got coerced")))
