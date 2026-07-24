(ns re-frame.freehand.caller-carrier-cljs-test
  "`(v/spread-safe owned caller)`'s internal transport, and the fact that an
  author cannot forge it.

  The guarded caller map rides to the element inside the owned attribute map,
  under a reserved key, because the fold that puts it UNDER the owned props
  belongs to the canonicaliser rather than to either front end. That leaves one
  key with two readings — transport to the walks, an ordinary namespaced
  attribute to Freehand's own attribute law. The forgery answer is a VALUE
  mark, not a reserved name: the transport is a `CarriedCaller`, and an
  authored value never is one, so a `:rf.ui/caller` an author writes is left in
  the attribute map and treated as the ordinary attribute it is —

    [:div {:rf.ui/caller \"ok\"}]      emits an ordinary `caller` attribute;
    [:div {:rf.ui/caller {:x 1}}]     is refused by the ordinary
                                      attribute-value grammar, because a map
                                      is not an attribute value — exactly as
                                      a map under any other name is;

  and NEVER folded as caller attributes onto an element that did not ask for
  them. Reserving the NAME as well (as an early fix did) burned the emitted
  `caller` slot across every spelling and silently narrowed the pass-through
  attribute law; the mark alone is sufficient, so the name is ordinary
  (rf2-drpa3.132).

  Three walks read one rule, so all three are asked here — the interpreted
  structural walk, the interpreted React walk, and the compiled analyzer — plus
  the two runtime forwarding verbs, because `v/spread` could hand the same key
  to the same seam from the other side."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.analyze-reject-cljs-test :refer [reject-id]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.tree :as tree]
            #?(:cljs [goog.object :as gobj])
            #?(:cljs [re-frame.freehand.react :as fr])))

(def ^:private spellings
  "Every representation of the carrier NAME. The walks classify an attribute
  key by the prop name they are about to emit — the namespace is dropped on the
  way to the DOM — so a qualified keyword, a plain keyword, a string and a
  symbol are one name written four ways. NONE of them is reserved: the mark, not
  the name, is what makes transport transport."
  [:rf.ui/caller :caller :x/caller "caller" 'caller])

;; ---------------------------------------------------------------------------
;; The interpreted structural walk — the carrier NAME is an ordinary attribute
;; ---------------------------------------------------------------------------

(deftest an-authored-carrier-key-is-an-ordinary-attribute-in-the-structural-walk
  (testing "A scalar value under any spelling of the carrier name is the
            ordinary attribute the pass-through law says it is — carried in
            author space, not folded as transport (rf2-drpa3.132)."
    (is (= {:rf.ui/caller "ok"} (:attrs (tree/render [:div {:rf.ui/caller "ok"}]))))
    (is (= {:caller "ok"}       (:attrs (tree/render [:div {:caller "ok"}]))))
    (is (= {:x/caller "ok"}     (:attrs (tree/render [:div {:x/caller "ok"}]))))
    (is (= {"caller" "ok"}      (:attrs (tree/render [:div {"caller" "ok"}])))))

  (testing "a nil value is the ordinary DROPPED attribute, not a carrier"
    (is (nil? (:attrs (tree/render [:div {:rf.ui/caller nil}]))))
    (is (nil? (:attrs (tree/render [:div {:caller nil}])))))

  (testing "a MAP value is refused by the ordinary attribute-value grammar —
            because a map is not an attribute value, exactly as under any other
            name, and NOT by a reserved-name or transport-fold path"
    (doseq [k spellings]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [:div {k {:data-forged "yes"}}])))
          (str (pr-str k) " carrying a map is refused as a map-valued attribute")))
    (is (str/includes? (conf/caught-message
                         #(tree/render [:div {:rf.ui/caller {:data-forged "yes"}}]))
                       "no attribute spelling")
        "the diagnostic is the ordinary attribute-value one, not the transport verb"))

  (testing "and none of this forges anything: an authored map is never folded
            as caller attributes onto the element"
    (is (nil? (:attrs (tree/render [:div {:caller nil}])))
        "no attrs at all — certainly no forged data-* the caller would carry")))

(deftest a-guarded-caller-still-folds-under-the-owned-props
  (testing "The transport itself is untouched: owned wins, `:class` composes
            sugar-then-owned-then-caller, and the caller's own attributes
            arrive — reached ONLY through the verb, via the value mark."
    (is (= {:aria-label "L"
            :data-x     "1"
            :class      "field owned-class caller-class"
            :value      "owned"}
           (:attrs (tree/render
                     [:input.field (v/spread-safe {:value "owned" :class "owned-class"}
                                                  {:aria-label "L" :class "caller-class"
                                                   :data-x "1"})]))))))

;; ---------------------------------------------------------------------------
;; The runtime forwarding verbs
;; ---------------------------------------------------------------------------

(deftest a-forwarded-runtime-map-carries-the-carrier-name-as-an-ordinary-attribute
  (testing "The carrier name is not reserved, so `v/spread` forwarding it is
            not refused at the verb — it is the ordinary attribute it names.
            A scalar rides through; a map value is refused by the ordinary
            attribute-value grammar at the ELEMENT, as any map under any name
            is (rf2-drpa3.132)."
    (is (= {:rf.ui/caller "s"} (v/spread {} {:rf.ui/caller "s"}))
        "v/spread forwards the ordinary attribute untouched")
    (is (= {:rf.ui/caller "s"}
           (:attrs (tree/render [:div (v/spread {} {:rf.ui/caller "s"})])))
        "and it reaches the element as an ordinary attribute — never folded as transport")
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id
             #(tree/render [:div (v/spread {} {:rf.ui/caller {:data-forged "yes"}})])))
        "a MAP value is refused at the element, as any map-valued attribute is"))

  (testing "non-vacuous: the guarded v/spread-safe transport still delivers
            exactly its caller attrs through the value mark"
    (is (= {:title "t"} (v/spread {} {:title "t"})))
    (is (= {:data-x "1"}
           (:attrs (tree/render [:div (v/spread-safe {} {:data-x "1"})]))))))

;; ---------------------------------------------------------------------------
;; The compiled analyzer
;; ---------------------------------------------------------------------------

(deftest the-compiled-analyzer-treats-the-carrier-name-as-an-ordinary-prop
  (testing "One rule, three readers: the analyzer no longer refuses a literal
            carrier-name key — a scalar under it compiles as the ordinary
            `caller` prop, so a declaration is not narrowed out of the
            pass-through attribute language (rf2-drpa3.132)."
    (doseq [k [:rf.ui/caller :caller :x/caller]]
      (is (nil? (reject-id [:div {k "ok"}]))
          (str (pr-str k) " is an ordinary attribute, not a rejected spelling"))))

  (testing "the ordinary attribute-value rule still holds — a literal MAP under
            it is refused as a collection value, as under any other prop"
    (is (= :rf.ui.compile/collection-attr-value
           (reject-id '[:div {:caller {:data-forged "yes"}} "x"])))))

;; ---------------------------------------------------------------------------
;; The interpreted React walk
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest an-authored-carrier-key-is-an-ordinary-prop-in-the-react-walk
     (testing "The sibling emitter reads the carrier through the same value
               mark, so an authored scalar reaches React as the ordinary
               `caller` prop and a map value is refused by the ordinary
               attribute-value grammar — neither is folded as transport."
       (doseq [k [:rf.ui/caller :caller :x/caller "caller"]]
         (let [props (.-props (fr/element [:div {k "ok"}]))]
           (is (= "ok" (gobj/get props "caller"))
               (str (pr-str k) " emits the ordinary caller prop"))))
       (doseq [k spellings]
         (is (= :rf.error/ui-tree-malformed
                (conf/caught-id #(fr/element [:div {k {:data-forged "yes"}}])))
             (str (pr-str k) " carrying a map is refused as a map-valued attribute"))))))

#?(:cljs
   (deftest a-guarded-caller-reaches-the-react-props-object
     (testing "The interpreted React path is the one this transport was invisible
               on: the carrier reached the plain-attribute path and refused the
               whole element. What is asserted is the props object React is
               handed — owned value, caller `aria-*`/`data-*`, classes composed
               owned-first — and that no transport entry rides along."
       (let [props (.-props (fr/element
                              [:input.field
                               (v/spread-safe {:value "owned" :class "owned-class"}
                                              {:aria-label "L" :class "caller-class"
                                               :data-x "1"})]))]
         (is (= "owned" (gobj/get props "value")))
         (is (= "field owned-class caller-class" (gobj/get props "className")))
         (is (= "L" (gobj/get props "aria-label")))
         (is (= "1" (gobj/get props "data-x")))
         (is (false? (gobj/containsKey props "rf.ui/caller"))
             "no internal carrier prop reaches React under its own name")))))
