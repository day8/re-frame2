(ns re-frame.freehand.caller-carrier-cljs-test
  "`(v/spread-safe owned caller)`'s internal transport, and the fact that an
  author cannot write it.

  The guarded caller map rides to the element inside the owned attribute map,
  under a reserved key, because the fold that puts it UNDER the owned props
  belongs to the canonicaliser rather than to either front end. That leaves one
  key with two readings — transport to the walks, an ordinary namespaced
  attribute to Freehand's own attribute law — and both readings were reachable
  by writing the key:

    [:div {:rf.ui/caller \"authored\"}]        threw a raw ClassCastException,
                                             folding a string as a map;
    [:div {:rf.ui/caller {:data-x \"1\"}}]      rendered a PERFECTLY WELL-FORMED
                                             element carrying `data-x` — caller
                                             attributes forged onto an element
                                             that never asked for them.

  The second is why the rows below assert the RESULT rather than that something
  went wrong: a test that only demanded a throw, or a non-empty render, passed
  against the defect.

  Three walks read one roster, so all three are asked here — the interpreted
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
  "Every representation of the reserved carrier NAME. The walks classify an
  attribute key by the prop name they are about to emit — the namespace is
  dropped on the way to the DOM — so a qualified keyword, a plain keyword, a
  string and a symbol are one name written four ways, and a guard that read the
  raw key would leave three of them outside it."
  [:rf.ui/caller :caller :x/caller "caller" 'caller])

(def ^:private forged-values
  "What an authored carrier could carry. The MAP is the dangerous one — it is
  the shape the fold expects, so it produced an element rather than an error."
  ["authored" {:data-forged "yes"} nil])

;; ---------------------------------------------------------------------------
;; The interpreted structural walk
;; ---------------------------------------------------------------------------

(deftest an-authored-carrier-key-is-refused-by-the-structural-walk
  (testing "Every spelling of the reserved name, carrying every shape an
            author could give it, is refused with the grammar's own typed
            diagnostic — including the map, which used to be folded."
    (doseq [k spellings
            raw forged-values]
      (is (= :rf.error/ui-tree-malformed
             (conf/caught-id #(tree/render [:div {k raw}])))
          (str (pr-str k) " carrying " (pr-str raw) " is refused, not folded"))))

  (testing "and the diagnostic names the verb that owns the transport"
    (is (str/includes? (conf/caught-message
                         #(tree/render [:div {:rf.ui/caller {:data-forged "yes"}}]))
                       "(v/spread-safe owned caller)")))

  (testing "the forged attributes are the point: this exact element, with this
            exact attribute map, is what the defect rendered from an authored
            carrier — and it is reachable ONLY through the verb"
    (is (= {:data-forged "yes"}
           (:attrs (tree/render [:div (v/spread-safe {} {:data-forged "yes"})])))
        "non-vacuous: the guarded carrier still delivers exactly these attrs"))

  (testing "not over-broad — an ordinary qualified attribute whose name is not
            the reserved one keeps its authored spelling"
    (is (= {:x/title "t"} (:attrs (tree/render [:div {:x/title "t"}]))))
    (is (= {"rf.ui/caller" "s"} (:attrs (tree/render [:div {"rf.ui/caller" "s"}])))
        "a STRING key has no namespace to drop, so its emitted name is the whole
         string and it is a different attribute from the reserved one")))

(deftest a-guarded-caller-still-folds-under-the-owned-props
  (testing "The transport itself is untouched by the refusal: owned wins,
            `:class` composes sugar-then-owned-then-caller, and the caller's
            own attributes arrive."
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

(deftest a-forwarded-runtime-map-cannot-smuggle-the-carrier
  (testing "`v/spread` reaches the element through the same seam, so a
            runtime-assembled map carrying the reserved key is refused where a
            literal one is — at the verb, before anything renders."
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(v/spread {} {:rf.ui/caller {:data-forged "yes"}}))))
    (is (str/includes? (conf/caught-message
                         #(v/spread {} {:rf.ui/caller {:data-forged "yes"}}))
                       "A forwarded attribute map carries :rf.ui/caller")))

  (testing "and a CALLER map handed to `v/spread-safe` cannot carry it either —
            the guard canonicalizes the key to its name first, so the qualified
            spelling is refused as the plain one is"
    (is (= :rf.error/ui-tree-malformed
           (conf/caught-id #(v/spread-safe {} {:rf.ui/caller {:data-forged "yes"}})))))

  (testing "non-vacuous: an ordinary forwarded key passes both verbs"
    (is (= {:title "t"} (v/spread {} {:title "t"})))
    (is (= {:data-x "1"}
           (:attrs (tree/render [:div (v/spread-safe {} {:data-x "1"})]))))))

;; ---------------------------------------------------------------------------
;; The compiled analyzer
;; ---------------------------------------------------------------------------

(deftest the-compiled-analyzer-refuses-the-carrier-spelling-too
  (testing "One roster, three readers: the analyzer answers a literal carrier
            key at build time with its own rejected-spelling error, so a
            declaration cannot compile into the shape the interpreted walks
            refuse."
    (doseq [k [:rf.ui/caller :caller :x/caller]]
      (is (= :rf.ui.compile/rejected-prop-spelling (reject-id [:div {k "authored"}]))
          (str (pr-str k) " projects onto the reserved carrier slot"))))

  (testing "not over-broad — a neighbouring qualified prop still compiles"
    (is (nil? (reject-id '[:div {:x/title "ok"}])))))

;; ---------------------------------------------------------------------------
;; The interpreted React walk
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest an-authored-carrier-key-is-refused-by-the-react-walk
     (testing "The sibling emitter reads the carrier through the same splitter,
               so an authored key is refused there with the same typed
               diagnostic rather than reaching React as a props object entry."
       (doseq [k spellings
               raw forged-values]
         (is (= :rf.error/ui-tree-malformed
                (conf/caught-id #(fr/element [:div {k raw}])))
             (str (pr-str k) " carrying " (pr-str raw) " is refused by the React walk"))))))

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
         (is (false? (gobj/containsKey props "caller"))
             "no internal caller prop reaches React")
         (is (false? (gobj/containsKey props "rf.ui/caller"))
             "and neither does the reserved key under its own name")))))
