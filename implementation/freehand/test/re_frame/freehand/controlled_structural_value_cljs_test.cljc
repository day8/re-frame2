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
   (defn- compiled-tree
     "The structural tree a COMPILED declaration answers — the emitted
     lowering, evaluated and called, rather than a claim about the form it
     emitted."
     [body]
     (let [lowering (compiler/compile-structural-view
                      {:form            (list 'v/defview 'subject '[_] body)
                       :menv            nil
                       :ns-sym          're-frame.freehand.controlled-structural-value-cljs-test
                       :vname           'subject
                       :view-id         ::subject
                       :params          '[_]
                       :body            [body]
                       :children-policy :optional})]
       ((eval (:body lowering)) {}))))

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
