(ns re-frame.freehand.controlled-structural-value-cljs-test
  "An explicitly present `value` / `checked` holding `nil` is a CONTROLLED
  slot with nothing in it, and the STRUCTURAL tree has to say so.

  The generic nil-attribute law is right and stays: an author who writes
  nothing means absent. The controlled slots are the exception the
  controlled-input contract already states, because on a supported native
  control absence is the HOST's own signal that the node is uncontrolled —
  the opposite claim from the one the author made and the door already
  acted on.

  The React emitter was repaired first, and the structural walk went on
  dropping the entry. That left the two hosts describing one declaration
  two ways, and it put a server render that omits the attribute against a
  client render that sets it — a hydration seam from one authored word.
  The repair is at the ONE canonicaliser both structural modes reach, so
  the interpreted walk and a compiled view's emitted body answer the same
  tree by construction rather than by agreement.

  Runs on BOTH hosts: the structural walk is one implementation and the
  tree is one value, so a claim proved on the JVM alone would be a claim
  about the JVM. The compiled rows are JVM-only for the ordinary reason —
  the lowering is produced by a macro expansion and called.

  The browser half of the claim is
  `re-frame.freehand.controlled-nil-value-cljs-test` (the React props) and
  `re-frame.freehand.controlled-input-dom-cljs-test` (a live node).

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)
  §Attr value normalization."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand.tree :as tree]
            #?(:clj [re-frame.freehand.compiler :as compiler])))

(defn- interpreted-tree
  "The structural tree the INTERPRETED walk answers, with the root's
  version stripped so a row reads as the node it is about."
  [body]
  (dissoc (tree/render body) :rf.ui/tree-version))

#?(:clj
   (def ^:private params
     "The bindings the dynamic rows read, so a computed attribute value is a
     real runtime value in the compiled mode rather than a literal in
     disguise."
     '[{:keys [vs flag]}]))

#?(:clj
   (defn- compiled-tree
     "The structural tree a COMPILED declaration answers — the emitted
     lowering, evaluated and called, rather than a claim about the form it
     emitted."
     ([body] (compiled-tree body {}))
     ([body props]
      (let [lowering (compiler/compile-structural-view
                       {:form            (list 'v/defview 'subject params body)
                        :menv            nil
                        :ns-sym          're-frame.freehand.controlled-structural-value-cljs-test
                        :vname           'subject
                        :view-id         ::subject
                        :params          params
                        :body            [body]
                        :children-policy :optional})]
        ((eval (:body lowering)) props)))))

;; ---------------------------------------------------------------------------
;; The controlled slots, on the supported native controls
;; ---------------------------------------------------------------------------

(def cleared-rows
  "Every spelling of a controlled slot an explicit `nil` must leave PRESENT
  and empty in the tree. The alternate spellings are here because the rule
  is about the emitted SLOT: `:x/value` is written into the host's own
  `value`, so it is the same prop and takes the same answer — while the
  KEY stays in author space, as every other qualified attribute's does."
  [{:note "the paved path"
    :body '[:input {:value nil}]
    :tree {:tag :input :attrs {:value ""}}}

   {:note "an aliased :value is that prop, and keeps its authored name"
    :body '[:input {:x/value nil}]
    :tree {:tag :input :attrs {:x/value ""}}}

   {:note "checked is the second slot, and its empty value is FALSE"
    :body '[:input {:type "checkbox" :checked nil}]
    :tree {:tag :input :attrs {:type "checkbox" :checked false}}}

   {:note "and its aliased spelling"
    :body '[:input {:type "checkbox" :x/checked nil}]
    :tree {:tag :input :attrs {:type "checkbox" :x/checked false}}}

   {:note "a textarea is a supported control"
    :body '[:textarea {:value nil}]
    :tree {:tag :textarea :attrs {:value ""}}}

   {:note "and so is a select"
    :body '[:select {:value nil}]
    :tree {:tag :select :attrs {:value ""}}}

   {:note "the whole declaration, handler and all — the shape the door acts on"
    :body '[:input {:value nil :on-change [:field/edited]}]
    :tree {:tag :input :attrs {:value ""} :events {:on-change [:field/edited]}}}])

(deftest an-explicit-nil-control-value-is-present-and-empty-in-the-tree
  (testing "Presence is the whole question. An author who wrote `:value
            nil` declared a controlled field with nothing in it; a tree
            reporting an element with no value attribute reports the
            opposite, and hands a hydrating client a prop its server render
            never wrote."
    (doseq [{:keys [note body tree]} cleared-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted")))))

#?(:clj
   (deftest the-compiled-tree-answers-the-same-nodes
     (testing "A literal nil cannot be settled at build time — whether it
               is dropped or kept is the canonicaliser's rule — so it rides
               the same slot the values only the render knows ride, and the
               compiled tree is the interpreted tree by construction."
       (doseq [{:keys [note body tree]} cleared-rows]
         (is (= tree (compiled-tree body)) (str note " — compiled"))))))

;; ---------------------------------------------------------------------------
;; The generic law is untouched
;; ---------------------------------------------------------------------------

(def dropped-rows
  "The declarations the exception must NOT have reached. A nil attribute is
  an absent attribute, because absent is what an author means by writing
  nothing — a conditional value is the ordinary way to write one."
  [{:note "an ordinary nil attribute on a control is still omitted"
    :body '[:input {:title nil :value "x"}]
    :tree {:tag :input :attrs {:value "x"}}}

   {:note ":default-value seeds an UNCONTROLLED input and is not a controlled slot"
    :body '[:input {:default-value nil}]
    :tree {:tag :input}}

   {:note "a :div has no value the host restores, so nothing is excepted for it"
    :body '[:div {:value nil}]
    :tree {:tag :div}}

   {:note "and a custom element's value is its own adapter's protocol"
    :body '[:my-field {:value nil}]
    :tree {:tag :my-field}}

   {:note "an ABSENT key is still absent — the exception is about presence"
    :body '[:input {:type "text"}]
    :tree {:tag :input :attrs {:type "text"}}}

   {:note "a non-nil control value takes the ordinary conversion, unchanged"
    :body '[:input {:type "checkbox" :checked false}]
    :tree {:tag :input :attrs {:type "checkbox" :checked false}}}])

(deftest the-generic-nil-attribute-law-is-untouched
  (testing "Only the controlled slots on a supported native control are
            excepted, and the exception is scoped exactly as the door is."
    (doseq [{:keys [note body tree]} dropped-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted")))))

#?(:clj
   (deftest the-generic-law-is-untouched-in-the-compiled-tree-too
     (testing "The build-time fold and the render-time fold are one rule,
               so an emitter that stopped dropping nils generally would
               show here."
       (doseq [{:keys [note body tree]} dropped-rows]
         (is (= tree (compiled-tree body)) (str note " — compiled"))))))

;; ---------------------------------------------------------------------------
;; The one control whose value is COLLECTION-shaped
;; ---------------------------------------------------------------------------

(def multiple-select-rows
  "A native `<select multiple>` is the single element in the DOM whose
  `value` is not a scalar: what is selected is a LIST of option values.
  The tree records ORDINARY DATA for it — a Clojure vector whose members
  took the same conversion every scalar attribute value takes — and
  turning it into the array React's contract requires is the React
  emitter's own last step."
  [{:note "an authored vector of option values is retained, in order"
    :body '[:select {:multiple true :value ["a" "b"]}]
    :tree {:tag :select :attrs {:multiple true :value ["a" "b"]}}}

   {:note "and its members take the ordinary attribute-value conversion"
    :body '[:select {:multiple true :value [:a 1]}]
    :tree {:tag :select :attrs {:multiple true :value ["a" "1"]}}}

   {:note "an EMPTY selection is an empty vector, not the empty string"
    :body '[:select {:multiple true :value nil}]
    :tree {:tag :select :attrs {:multiple true :value []}}}

   {:note "the aliased value slot takes the same answer, keeping its authored name"
    :body '[:select {:multiple true :x/value nil}]
    :tree {:tag :select :attrs {:multiple true :x/value []}}}

   {:note "and the flag is read through the same slot projection, so an alias flags it"
    :body '[:select {:x/multiple true :value nil}]
    :tree {:tag :select :attrs {:x/multiple true :value []}}}

   {:note "an explicitly FALSE multiple is a single select — present-false, and the scalar empty"
    :body '[:select {:multiple false :value nil}]
    :tree {:tag :select :attrs {:multiple false :value ""}}}

   {:note "an empty authored vector is already the empty selection"
    :body '[:select {:multiple true :value []}]
    :tree {:tag :select :attrs {:multiple true :value []}}}])

(deftest a-multiple-selects-value-is-collection-shaped-in-the-tree
  (testing "The empty value is the fact `multiple` decides, and it is
            decided once for the whole element — from either attribute
            source, because a compiled element's flag may be a literal the
            compiler folded or a value only the render knows."
    (doseq [{:keys [note body tree]} multiple-select-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted")))))

#?(:clj
   (deftest the-compiled-tree-agrees-about-the-collection-shape
     (testing "One canonicaliser, so a compiled declaration answers the tree
               its interpreted twin answers — for EVERY row, literal
               selections included. The literal rows used to be excluded
               here: the analyzer refused a literal collection attribute
               value before either emitter ran, so
               `[:select {:multiple true :value [\"a\" \"b\"]}]` failed to
               compile while its interpreted twin rendered it, and a
               COMPUTED selection of the same shape compiled fine
               (rf2-b6poy). The guard admits the select value slot now, and
               the literal rides the same runtime path the computed one
               always did."
       (doseq [{:keys [note body tree]} multiple-select-rows]
         (is (= tree (compiled-tree body)) (str note " — compiled"))))))

#?(:clj
   (deftest the-compiled-tree-settles-the-collection-shape-at-render
     (testing "The two facts a compiler cannot always see — the selection
               itself, and whether the select is MULTIPLE — are both
               ordinary runtime values, and the canonicaliser reads them from
               the same slot whichever mode put them there."
       (is (= {:tag :select :attrs {:multiple true :value ["a" "b"]}}
              (compiled-tree '[:select {:multiple true :value vs}] {:vs ["a" "b"]}))
           "a computed selection reaches the tree as ordinary data")
       (is (= {:tag :select :attrs {:multiple true :value []}}
              (compiled-tree '[:select {:multiple flag :value nil}] {:flag true}))
           "and a computed `multiple` still decides what EMPTY means")
       (is (= {:tag :select :attrs {:multiple false :value ""}}
              (compiled-tree '[:select {:multiple flag :value nil}] {:flag false}))
           "including when it decides the element is a single select"))))

(def refused-collection-rows
  "The collection values that stay REFUSED. Widening one host element's
  `value` slot is not a general collection-attribute grammar, and a set is
  refused even there: the tree is ONE value on two hosts, and a set's read
  order is not something the JVM and ClojureScript agree on."
  ['[:div {:value ["a"]}]
   '[:input {:value ["a"]}]
   '[:select {:multiple true :value #{"a"}}]
   '[:select {:multiple true :value {:a 1}}]
   '[:select {:multiple true :title ["a"]}]
   '[:select {:multiple true :value ["a" ["b"]]}]])

(deftest an-ordinary-collection-attribute-value-is-still-refused
  (testing "The exception is ONE slot on ONE tag. Everywhere else a
            collection has no attribute spelling, and inside the exception
            the members take the same closed scalar grammar."
    (doseq [body refused-collection-rows]
      (let [ex (try (interpreted-tree body)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
        (is (some? ex) (str (pr-str body) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic, not a new one")))))))

#?(:clj
   (defn- compile-refusal
     "The `{:rf.ui.compile/error id, :msg …}` a compiled declaration of
     `body` is refused with, or nil when it compiles."
     [body]
     (try
       (compiled-tree body)
       nil
       (catch clojure.lang.ExceptionInfo ex
         (when-let [id (:rf.ui.compile/error (ex-data ex))]
           {:id id :msg (ex-message ex)})))))

#?(:clj
   (deftest the-compiled-tier-refuses-exactly-what-the-walk-refuses
     (testing "The widening is ONE slot on ONE tag, and it is the same slot
               in both modes. A set stays refused in the compiled tier for
               the reason the walk refuses it — the tree is one value on two
               hosts and a set has no order they agree on — and every
               ordinary attribute keeps the general refusal."
       (doseq [body ['[:select {:multiple true :value #{"a"}}]
                     '[:select {:multiple true :value {:a 1}}]
                     '[:select {:multiple true :title ["a"]}]
                     '[:input {:value ["a"]}]
                     '[:div {:value ["a"]}]]]
         (is (= :rf.ui.compile/collection-attr-value (:id (compile-refusal body)))
             (str (pr-str body) " — refused compiled, as it is interpreted"))))
     (testing "and the refusal at the widened slot says why a set is not a
               selection, rather than claiming collections are only for
               :class/:style"
       (let [msg (:msg (compile-refusal '[:select {:multiple true :value #{"a"}}]))]
         (is (re-find #"SEQUENTIAL" msg))
         (is (re-find #"order is not a value the two hosts agree on" msg))))
     (testing "the literal selection itself compiles — the corner this
               widening was about"
       (is (nil? (compile-refusal '[:select {:multiple true :value ["a" "b"]}]))))))

(deftest a-single-selects-scalar-value-is-untouched
  (testing "The control that makes the widening mean something. A `<select>`
            with no `multiple` keeps exactly the scalar behaviour it had —
            the empty string for an explicit nil, and an ordinary
            conversion for a value."
    (is (= {:tag :select :attrs {:value ""}}
           (interpreted-tree '[:select {:value nil}])))
    (is (= {:tag :select :attrs {:value "a"}}
           (interpreted-tree '[:select {:value "a"}])))
    (is (= {:tag :select :attrs {:multiple true :value "a"}}
           (interpreted-tree '[:select {:multiple true :value "a"}]))
        "a scalar on a multiple select is still an ordinary scalar here — the
         shape mismatch is React's own diagnostic, at the boundary that has
         the contract")))
