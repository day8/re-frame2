(ns re-frame.freehand.custom-element-cljs-test
  "FH-STRUCT-011 — one `v/custom-element` declaration, every lowering path.

  The compiled literal props map was the declaration's FIRST consumer and
  for a while its only one. A classification that reaches only there means
  *property* at a literal site and *attribute* one `v/spread` away — one
  declaration with two meanings, and no diagnostic anywhere, because both
  answers are structurally well-formed. So this suite is written by PATH:
  the interpreted walk, a `{:compiled true}` body's literal map, a
  `v/spread` in each mode, a `v/spread-safe` caller. Every row asserts the
  same classification, and the redundancy is the subject rather than a
  weakness of the table.

  What each host proves here is the STRUCTURAL half — the tree, the
  reserved `:rf.ui/property-props` fact, and the kebab -> camelCase
  property name. The server's omission is
  `custom-element-ssr-jvm-test` (the JVM render IS the server shell) and
  the mounted property write is `custom-element-dom-cljs-test`, which is
  the only place \"set as a property, not written as an attribute\" is
  observable at all.

  This file replaces the donor family's
  `custom_element_{classification,order,spread_parity}_jvm_test` — three
  suites that each drove one path against its own ad-hoc views. One fixture
  over one view roster says the same thing once, and says which path
  failed."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.custom-element-views :as views]
            [re-frame.freehand.rules :as rules]
            [re-frame.freehand.tree :as tree]))

(def struct-011 (conf/fixture :FH-STRUCT-011))

(defn- render-case
  "Render one fixture case's view. `:args` is optional — a view that needs
  no props is still called with a props map, because a boundary call takes
  exactly one."
  [{:keys [view args]}]
  (tree/render (into [(get views/by-name view)] (or (seq args) [{}]))))

;; ---------------------------------------------------------------------------
;; The declaration is real — the fixture is not reading its own expectations
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-the-fixture-is-written-against-the-live-declarations
  (testing "Per FH-STRUCT-011: the case table below is only evidence if the
            declarations it was written for are the ones the source makes.
            A fixture asserting a classification for a tag nobody declares
            any more would go green on the all-attributes default and read
            as a passing proof of the opposite law."
    (is (seq (:declarations struct-011)) "the fixture's declaration roster loaded")
    (doseq [{:keys [tag properties]} (:declarations struct-011)]
      (is (= properties (rules/custom-element-properties tag))
          (str "the live registry carries " tag "'s declared :properties")))
    (testing "and the undeclared tag really is undeclared"
      (is (= #{} (rules/custom-element-properties (:undeclared-tag struct-011)))
          "an undeclared tag answers with no properties — the all-attributes default"))))

;; ---------------------------------------------------------------------------
;; The classification, path by path
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-one-declaration-classifies-every-path-identically
  (testing "Per FH-STRUCT-011: a declared name is a PROPERTY — it keeps its
            authored kebab key in `:attrs` like everything else, its value
            passes through verbatim rather than through the attribute-value
            grammar, and it is named in the node's reserved
            `:rf.ui/property-props` set. Every other name is an attribute.
            The tree is the same whichever path the prop arrived by."
    (is (seq (:cases struct-011)) "the fixture's case table loaded")
    (doseq [{:keys [note] :as c} (:cases struct-011)]
      (is (= (:tree c) (render-case c)) note))))

(deftest fh-struct-011-the-paths-agree-with-each-other
  (testing "Per FH-STRUCT-011: the rows above pin each path against the
            contract. This pins them against EACH OTHER, which is the claim
            a reader of the ledger actually makes — that promoting a
            declaration with `{:compiled true}`, or forwarding the same map
            through `v/spread`, changes nothing about what the props MEAN.
            An element-level comparison, because the view-id differs by
            construction and is not part of the claim."
    (let [element (fn [view]
                    (first (:children (render-case {:view view}))))
          paths   [:panel-literal :panel-literal-compiled
                   :panel-spread :panel-spread-compiled]
          trees   (mapv element paths)]
      (is (apply = trees)
          (str "the four lowering paths answer one element: "
               (pr-str (zipmap paths trees))))
      (is (= #{:accent-color :help-text :scale}
             (:rf.ui/property-props (first trees)))
          "and they agree on the DECLARED set rather than on nothing at all"))))

(deftest fh-struct-011-declaration-order-is-not-an-authoring-decision
  (testing "Per FH-STRUCT-011: `:ce-below` is declared BELOW the views that
            use it. Macros expand top to bottom, so a compiled view above
            its declaration reads the ambient build's declaration slice
            before the declaration has contributed — which is what the
            source harvest exists to fix. The interpreted and `v/spread`
            paths cannot be order-dependent and must agree anyway; a proof
            that only checked the compiled arm could not tell a working
            harvest from a broken one that happened to match."
    (let [element (fn [view] (first (:children (render-case {:view view}))))
          below   (mapv element [:below-literal :below-literal-compiled :below-spread])]
      (is (apply = below) "the three paths agree, declaration order notwithstanding")
      (is (= #{:model} (:rf.ui/property-props (first below)))
          ":model is a PROPERTY even though its declaration is written last"))))

;; ---------------------------------------------------------------------------
;; The value grammar — what makes the declaration worth having
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-an-undeclared-name-cannot-carry-a-property-value
  (testing "Per FH-STRUCT-011: a map, a vector or a host object has no
            attribute spelling, so the attribute grammar refuses it — and
            the declaration is what lifts a name OUT of that grammar. The
            refusal holds on an undeclared name of a DECLARED element as
            firmly as on an undeclared element, because what admits the
            value is the NAME being declared and never the element merely
            having a declaration somewhere."
    (is (seq (:refused struct-011)) "the fixture's refusal rows loaded")
    (doseq [{:keys [note form id]} (:refused struct-011)]
      (is (= id (conf/caught-id #(tree/render form))) note))
    (testing "and the declared name carrying the same shape of value renders"
      (is (= {:min 0 :max 10}
             (:scale (:attrs (first (:children (render-case {:view :panel-literal}))))))
          "the declared :scale keeps its map value verbatim"))))

;; ---------------------------------------------------------------------------
;; The declaration outranks HANDLER position
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-a-declared-on-name-is-a-property-on-every-path
  (testing "Per FH-STRUCT-011: `on-*` is the handler grammar and `:properties`
            is the property grammar, and they overlap — a web component may
            name a property `on-detail`, which is the example
            `v/custom-element`'s own docstring gives. The DECLARATION ranks
            first, because it is the fact an author wrote down where the
            prefix is only a guess.

            Every seam ranked the guess first: the compiled analyzer
            partitioned every `on-*` key off before consulting the
            declaration, and `node/dyn-attr-entry`, the interpreted React walk
            and both browser folds each tested handler position in an arm
            ABOVE the property arm. So all six lowering paths sent a declared
            `:on-detail` into the event grammar, where a map is an options map
            and the render raised `:rf.error/view-bad-event` — one
            declaration, unable to say anything at all about a whole valid
            property-name family (rf2-sv2oq).

            The rows are pinned against each other as well as against the
            contract, and `:model` rides beside `:on-detail` in each one
            carrying the IDENTICAL value: two props differing in nothing but
            their names is what makes a red row name the prefix as the cause."
    (let [element (fn [view] (first (:children (render-case {:view view}))))
          paths   [:on-literal :on-literal-compiled :on-spread :on-spread-compiled]
          trees   (mapv element paths)]
      (is (apply = trees)
          (str "the four lowering paths answer one element: "
               (pr-str (zipmap paths trees))))
      (let [el (first trees)]
        (is (= #{:on-detail :model} (:rf.ui/property-props el))
            "the declared `on-*` name is a property, beside its ordinary twin")
        (is (= {:payload 1} (:on-detail (:attrs el)))
            "and its map value is kept verbatim, outside the attribute-value grammar")
        (is (= (:on-detail (:attrs el)) (:model (:attrs el)))
            "the two declared props carry the same value, so only the NAME differs")
        (is (= {:on-click [:clicked]} (:events el))
            "and the undeclared `:on-click` beside them is still a native event")))))

(deftest fh-struct-011-an-undeclared-on-name-stays-a-native-event
  (testing "Per FH-STRUCT-011, the control: ranking the declaration ahead of
            handler position must NOT lift the `on-*` family out of the event
            grammar. What admits a property is the NAME being declared — never
            the prefix, and never the element merely carrying a declaration
            somewhere. `:ce-panel` is declared and does not declare
            `:on-detail`, so the same name on it is a handler site: the value
            lands in `:events`, the name is absent from
            `:rf.ui/property-props`, and a value that is not a legal options
            map is refused there exactly as it was before."
    (let [element (fn [view] (first (:children (render-case {:view view}))))
          controls (mapv element [:on-undeclared :on-undeclared-compiled])]
      (is (apply = controls) "the interpreted and compiled controls agree")
      (let [el (first controls)]
        (is (= {:on-detail [:detail]} (:events el))
            "the undeclared `:on-detail` is a native event site")
        (is (= #{:help-text} (:rf.ui/property-props el))
            "and is NOT named a property — only the element's actually-declared name is")
        (is (not (contains? (:attrs el) :on-detail))
            "so it never reaches the attribute map either")))
    (is (seq (:on-refused struct-011)) "the fixture's `on-*` refusal rows loaded")
    (doseq [{:keys [note form id]} (:on-refused struct-011)]
      (is (= id (conf/caught-id #(tree/render form))) note))))

(deftest fh-struct-011-a-forwarded-declared-on-name-folds-as-a-property
  (testing "Per FH-STRUCT-011: `v/spread-safe`'s caller fold is its own seam in
            both hosts, and it ranked handler position ahead of the
            declaration like the rest. A CALLER's declared `on-*` prop folds
            as a property under the owned props; the caller's undeclared
            handler beside it is still a native event. The owned map declares
            neither `on-*` name here, so the owned-key deny law has no handler
            family to protect and this is the ordinary fold."
    (let [el (first (:children (render-case
                               {:view :on-spread-safe
                                :args [{:caller {:on-detail {:payload 2}
                                                 :on-click [:clicked]
                                                 :data-y "y"}}]})))]
      (is (= {:payload 2} (:on-detail (:attrs el)))
          "the forwarded declared `on-*` prop is a property carrying its value verbatim")
      (is (= #{:on-detail :model} (:rf.ui/property-props el))
          "and is named in the property set beside the owned one")
      (is (= {:on-click [:clicked]} (:events el))
          "while the caller's undeclared handler is still a native event"))))

;; ---------------------------------------------------------------------------
;; The property NAME
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-a-declared-name-lowers-to-its-camelcase-property
  (testing "Per FH-STRUCT-011: the structural tree keeps the AUTHORED kebab
            key — one author-space map — and the ruled kebab -> camelCase
            spelling is the name the client SETS. It is deliberately not
            react-dom's attribute table, whose rule for an unrecognized
            kebab name is to pass it through verbatim: a property resolved
            through that table reaches React as `help-text`, React writes an
            attribute, and the setter never runs."
    (is (seq (:property-names struct-011)) "the fixture's name table loaded")
    (doseq [{:keys [note attr property]} (:property-names struct-011)]
      (is (= property (conv/custom-element-property-name attr)) note))
    (testing "the tree carries the AUTHOR spelling, never the lowered one"
      (let [attrs (:attrs (first (:children (render-case {:view :panel-literal}))))]
        (is (contains? attrs :accent-color) "the authored kebab key is what the tree records")
        (is (not (contains? attrs :accentColor))
            "the camelCase name belongs to the DOM write, not to the structural tree")))))

;; ---------------------------------------------------------------------------
;; The registry read, at the seam every runtime path goes through
;; ---------------------------------------------------------------------------

(deftest fh-struct-011-a-plain-dom-element-never-consults-the-registry
  (testing "Per FH-STRUCT-011: the runtime arm is asked per element on every
            path a value the compiler never saw arrives by, so it answers
            without a registry read for every element in an ordinary page.
            The tag test comes first — a custom element's name must contain
            `-` — and the answer for an undeclared element is `nil` rather
            than an empty set, so a caller skips the classification branch
            outright instead of consulting an empty set once per attribute."
    (is (nil? (conv/element-properties :div)) "a plain DOM element carries no properties")
    (is (nil? (conv/element-properties :section)))
    (is (nil? (conv/element-properties (:undeclared-tag struct-011)))
        "and neither does an undeclared custom element — absent, not empty")
    (is (= #{:accent-color :help-text :scale} (conv/element-properties :ce-panel))
        "a declared one answers its declared set")))
