(ns re-frame.freehand.spread-precedence-jvm-test
  "`(v/spread base overrides)` — `overrides` wins EVERY collision, in both
  modes, however the colliding key is spelled.

  The rule is one sentence and the substrate states it as later-arg-wins,
  but a collision is only a collision if something SEES it. Two attribute
  keys own a slot in the element rather than a place in the attribute map
  — `:class`, which composes with the `.class` sugar, and `:style`, which
  carries the CSS grammar — and every spelling of those names reaches the
  same slot. So `{:x/class \"base\"}` and `{:class \"override\"}` are the
  same key twice; a raw `merge` sees two distinct map keys, keeps both,
  and leaves the winner to whatever the downstream fold visits last.

  The two front ends fold in different orders. The interpreted walk
  splits the exact spelling into its dedicated field and applies the
  aliased one after it; the compiled emitter reduces the merged map in
  insertion order. One declaration, two answers — interpreted `\"base\"`,
  compiled `\"override\"` — and the divergence is invisible in an ordinary
  same-spelling row, which is why the promotion parity suites did not
  catch it. Reversing the two spellings happens to agree, so half the
  table would have passed too.

  The repair is that the projection happens BEFORE the merge, inside the
  one seam both modes call: `overrides` wins on the canonical slot, which
  is the only place the rule is written down.

  Every row is asserted in BOTH modes from one declaration — interpreted
  through [[re-frame.freehand.tree/render]], compiled through the emitted
  lowering itself, evaluated and called. The companion suite for aliased
  keys on the DIRECT attribute path is
  `re-frame.freehand.structural-slot-alias-jvm-test`.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Props forwarding."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.tree :as tree]))

(def ^:private params
  "The bindings a compiled row reads its two maps through, so `base` and
  `overrides` are real RUNTIME values in both modes rather than literals
  the compiler could have settled."
  '[{:keys [base overrides]}])

(defn- compiled-tree
  "The structural tree the COMPILED front end answers for `body` — the
  emitted lowering, evaluated and called on `props`, rather than a claim
  about the form it emitted."
  [body props]
  (let [lowering (compiler/compile-structural-view
                   {:form            (list 'v/defview 'subject params body)
                    :menv            nil
                    :ns-sym          're-frame.freehand.spread-precedence-jvm-test
                    :vname           'subject
                    :view-id         ::subject
                    :params          params
                    :body            [body]
                    :children-policy :optional})]
    ((eval (:body lowering)) props)))

(defn- interpreted-tree
  "The structural tree the INTERPRETED walk answers for the same element,
  with the root's version stripped so the two modes' values compare."
  [form]
  (dissoc (tree/render form) :rf.ui/tree-version))

;; ---------------------------------------------------------------------------
;; Precedence across spellings
;; ---------------------------------------------------------------------------

(def ^:private one-arg-body
  '[:div (v/spread base)])

(def ^:private two-arg-body
  '[:div (v/spread base overrides)])

(def ^:private sugared-two-arg-body
  '[:div.sugar (v/spread base overrides)])

(def collision-rows
  "One collision per row, spelled every way the grammar accepts, and the
  ONE tree both modes must answer.

  The first two rows are the reported defect. The rest are the controls
  that make it mean something: the reverse spelling direction and the
  same-spelling case already agreed before the repair, so a fix that
  merely reversed the winner would show up here rather than pass."
  [{:note      "an aliased base loses to an exact override — the reported divergence"
    :body      two-arg-body
    :props     {:base {:x/class "base"} :overrides {:class "override"}}
    :tree      {:tag :div :attrs {:class "override"}}}

   {:note      "and the same for :style, whose slot carries the CSS grammar"
    :body      two-arg-body
    :props     {:base {:x/style {:color "red"}} :overrides {:style {:color "blue"}}}
    :tree      {:tag :div :attrs {:style {:color "blue"}}}}

   {:note      "the reverse direction — an exact base loses to an aliased override"
    :body      two-arg-body
    :props     {:base {:class "base"} :overrides {:x/class "override"}}
    :tree      {:tag :div :attrs {:class "override"}}}

   {:note      "a string spelling is the same key too"
    :body      two-arg-body
    :props     {:base {"class" "base"} :overrides {:class "override"}}
    :tree      {:tag :div :attrs {:class "override"}}}

   {:note      "as is a symbol spelling"
    :body      two-arg-body
    :props     {:base {'class "base"} :overrides {:class "override"}}
    :tree      {:tag :div :attrs {:class "override"}}}

   {:note      "the same-spelling collision was always correct and stays correct"
    :body      two-arg-body
    :props     {:base {:class "base"} :overrides {:class "override"}}
    :tree      {:tag :div :attrs {:class "override"}}}

   {:note      "an override that collides with NOTHING still lands beside the base"
    :body      two-arg-body
    :props     {:base {:x/class "base"} :overrides {:x/title "t"}}
    :tree      {:tag :div :attrs {:class "base" :x/title "t"}}}

   {:note      "an ORDINARY namespaced key is not a slot, so it keeps its authored name"
    :body      two-arg-body
    :props     {:base {:x/title "base"} :overrides {:x/title "override"}}
    :tree      {:tag :div :attrs {:x/title "override"}}}])

(deftest overrides-win-a-collision-on-the-canonical-slot-in-both-modes
  (testing "A collision is judged on the SLOT the key projects onto, not
            on the spelling the author used, because that is the thing the
            element has one of. Judging it on the raw map key leaves the
            winner to the downstream fold, and the two front ends fold in
            different orders — so the same declaration answered the base
            in one mode and the override in the other."
    (is (<= 6 (count collision-rows)) "the table is not vacuously small")
    (doseq [{:keys [note body props tree]} collision-rows]
      (let [interpreted (interpreted-tree
                          (if (= body one-arg-body)
                            [:div (v/spread (:base props))]
                            [:div (v/spread (:base props) (:overrides props))]))]
        (is (= tree interpreted) (str note " — interpreted"))
        (is (= tree (compiled-tree body props)) (str note " — compiled"))))))

(deftest a-winning-class-still-composes-with-the-tag-shorthand
  (testing "The obligation the canonical projection carries. `:class`
            owns its slot BECAUSE it composes with `.class` sugar, so a
            projection that assigned into the slot instead would silently
            drop `:div.sugar`'s classes — a worse defect than the one
            being fixed, and an invisible one, because the element still
            renders."
    (let [props {:base {:x/class "base"} :overrides {:class "override"}}]
      (doseq [[mode tree] [["interpreted" (interpreted-tree
                                            [:div.sugar (v/spread (:base props)
                                                                  (:overrides props))])]
                           ["compiled"    (compiled-tree sugared-two-arg-body props)]]]
        (is (= {:tag :div :attrs {:class "sugar override"}} tree)
            (str mode " — the shorthand survives and the override won"))))))

(deftest an-explicit-nil-override-clears-the-slot-however-it-is-spelled
  (testing "`overrides` winning every collision includes winning with
            nil: the same-spelling case already dropped the attribute,
            so an aliased base that SURVIVED an explicit nil override
            would be the divergence back again, one value along."
    (doseq [[note base overrides tree]
            [["same spelling"      {:class "base"}   {:class nil} {:tag :div}]
             ["aliased base"       {:x/class "base"} {:class nil} {:tag :div}]
             ["aliased override"   {:class "base"}   {:x/class nil} {:tag :div}]
             ["a nil BASE loses to a real override"
              {:x/class nil} {:class "override"} {:tag :div :attrs {:class "override"}}]]]
      (is (= tree (interpreted-tree [:div (v/spread base overrides)]))
          (str note " — interpreted"))
      (is (= tree (compiled-tree two-arg-body {:base base :overrides overrides}))
          (str note " — compiled")))))

(deftest the-one-argument-form-is-unchanged
  (testing "There is no collision to judge with one map, so the single-
            argument form must answer exactly what it always did — the
            base folded onto the element through the ordinary rule table,
            aliases routed to their slots by that fold."
    (let [props {:base {:x/class "base" :x/title "t"}}
          tree  {:tag :div :attrs {:class "base" :x/title "t"}}]
      (is (= tree (interpreted-tree [:div (v/spread (:base props))])) "interpreted")
      (is (= tree (compiled-tree one-arg-body props)) "compiled"))
    (let [tree {:tag :div}]
      (is (= tree (interpreted-tree [:div (v/spread nil)])) "a nil base is an empty map, interpreted"))))

;; ---------------------------------------------------------------------------
;; Evaluation order and count
;; ---------------------------------------------------------------------------

(deftest each-map-expression-is-evaluated-once-in-authored-order
  (testing "The projection rebuilds two maps, so the obvious way to get
            it wrong is to evaluate an argument twice — a forwarded map
            built by a function call would then run its effects twice,
            and an author cannot see that from the call site. Base then
            overrides, once each, in both modes."
    (doseq [[mode run] [["interpreted"
                         (fn [b o] (interpreted-tree [:div (v/spread (b) (o))]))]
                        ["compiled"
                         (fn [b o] (compiled-tree '[:div (v/spread (base) (overrides))]
                                                  {:base b :overrides o}))]]]
      (let [log (atom [])
            b   (fn [] (swap! log conj :base) {:x/class "base"})
            o   (fn [] (swap! log conj :overrides) {:class "override"})
            t   (run b o)]
        (is (= [:base :overrides] @log)
            (str mode " — each expression ran once, base first"))
        (is (= {:tag :div :attrs {:class "override"}} t)
            (str mode " — and the override still won"))))))
