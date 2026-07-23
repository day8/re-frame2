(ns re-frame.freehand.structural-key-presence-cljs-test
  "`:key` is on a structural node IFF the site was explicitly keyed
  (Spec 004B) — and PRESENCE is the question, never the value.

  An explicit `nil` key is legal authored presence. React string-coerces a
  supplied key, so `{:key nil}` is the ordinary identity `\"null\"` and a
  different authored fact from no key at all — which is why
  `v/normalize-call` reports `:keyed?` beside `:key`: the stripped value
  alone reads `nil` for both. Any site that asks `(some? key)`, `(if key
  …)` or `(when key …)` collapses the two, and the collapse is invisible:
  the node still builds, the tree still prints, and the only symptom is a
  keyed child that a keyed reconciler treats as unkeyed.

  The four structural sites that can be keyed are asserted here TOGETHER —
  element, fragment, view boundary, and the reserved `v/error-boundary` —
  because a rule that held at three of them is exactly the shape the
  interpreted React walk had before it was repaired: presence threaded
  where it was noticed, `some?` where it was not.

  The boundary rows run in all FOUR mode pairings from one body written
  the same characters each time. A view boundary is the seam both front
  ends cross through `re-frame.freehand.node/mount`, so key presence
  surviving in one mode and not the other would mean adding
  `{:compiled true}` to a declaration changed whether its children were
  keyed — the cross-mode divergence two emitters over one semantic value
  exist to rule out."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.tree :as tree]))

;; ---------------------------------------------------------------------------
;; The children, and the four parents that mount them
;; ---------------------------------------------------------------------------

(v/defview kid
  "An interpreted child, mounted at three lexical sites below."
  [{:keys [label]}]
  [:span label])

(v/defview kid-compiled
  "Its promoted twin — the same body, one option map apart — so the table
  below is asserted over a compiled child as well as an interpreted one."
  {:compiled true}
  [{:keys [label]}]
  [:span label])

;; The three spellings ride ONE body, because a compiled declaration
;; settles key presence at BUILD time from the literal props map: a
;; runtime flag could not vary it, so each spelling needs its own lexical
;; site and all three belong in the same declaration to stay comparable.

(v/defview interpreted-parent-interpreted-child
  [_]
  [:<> [kid {:key nil :label "d"}] [kid {:label "d"}] [kid {:key "k" :label "d"}]])

(v/defview interpreted-parent-compiled-child
  [_]
  [:<> [kid-compiled {:key nil :label "d"}]
       [kid-compiled {:label "d"}]
       [kid-compiled {:key "k" :label "d"}]])

(v/defview compiled-parent-interpreted-child
  {:compiled true}
  [_]
  [:<> [kid {:key nil :label "d"}] [kid {:label "d"}] [kid {:key "k" :label "d"}]])

(v/defview compiled-parent-compiled-child
  {:compiled true}
  [_]
  [:<> [kid-compiled {:key nil :label "d"}]
       [kid-compiled {:label "d"}]
       [kid-compiled {:key "k" :label "d"}]])

(def ^:private kid-id (:view-id (descriptor/describe kid)))
(def ^:private kid-compiled-id (:view-id (descriptor/describe kid-compiled)))

(defn- expected-child
  "The ONE boundary node a `[kid {…}]` site denotes. `keyed?` decides
  whether `:key` is on the node at all; `key-val` is what it carries."
  [view-id keyed? key-val]
  (cond-> {:view-id  view-id
           :props    {:label "d"}
           :children [{:tag :span :children ["d"]}]}
    keyed? (assoc :key key-val)))

(defn- expected-children
  "The pinned expansion of every parent above: explicitly-nil-keyed,
  unkeyed, and ordinarily keyed, in source order."
  [child-id]
  [(expected-child child-id true nil)
   (expected-child child-id false nil)
   (expected-child child-id true "k")])

(def ^:private pairings
  [{:note "interpreted parent, interpreted child" :parent interpreted-parent-interpreted-child :child kid-id}
   {:note "interpreted parent, COMPILED child"    :parent interpreted-parent-compiled-child    :child kid-compiled-id}
   {:note "COMPILED parent, interpreted child"    :parent compiled-parent-interpreted-child    :child kid-id}
   {:note "COMPILED parent, COMPILED child"       :parent compiled-parent-compiled-child       :child kid-compiled-id}])

(deftest a-view-boundary-carries-key-iff-the-call-was-keyed
  (testing "Spec 004B: `:key` is present on the node iff the site was
            explicitly keyed. An explicit nil key is presence — React
            coerces it to the identity \"null\" — so it lands on the
            boundary as `:key nil`, distinct from the unkeyed sibling
            beside it, which carries no `:key` key at all."
    (doseq [{:keys [note parent child]} pairings]
      (let [kids (:children (tree/render [parent {}]))]
        (is (= 3 (count kids)) (str note " — three sites, three boundaries"))
        (is (contains? (first kids) :key)
            (str note " — an explicit nil key is PRESENT on the boundary"))
        (is (nil? (:key (first kids)))
            (str note " — and it carries the nil the call supplied"))
        (is (not (contains? (second kids) :key))
            (str note " — while an absent key stays absent"))
        (is (= "k" (:key (nth kids 2)))
            (str note " — and an ordinary key is untouched"))))))

(deftest key-presence-survives-the-mount-seam-in-either-mode
  (testing "`node/mount` is the ONE call either front end emits for an
            internal boundary, so the whole pinned expansion is asserted
            per pairing rather than only the `:key` slot. A rule that held
            interpreted and not compiled would mean adding
            `{:compiled true}` to a declaration changed whether its
            children were keyed."
    (doseq [{:keys [note parent child]} pairings]
      (is (= (expected-children child) (:children (tree/render [parent {}])))
          (str note " — one pinned expansion")))))

;; ---------------------------------------------------------------------------
;; The reserved boundary
;; ---------------------------------------------------------------------------

(deftest the-error-boundary-node-follows-the-same-presence-rule
  (testing "`v/error-boundary` is a declared internal boundary like any
            other — the structural host mounts it through its own
            containment path, and that path assembles the same node
            builder. A framework boundary is not a privileged one, so it
            cannot have its own answer about what `:key` means."
    (let [keyed   (tree/render [v/error-boundary {:key nil :fallback [:i "f"]} [:b "ok"]])
          unkeyed (tree/render [v/error-boundary {:fallback [:i "f"]} [:b "ok"]])
          valued  (tree/render [v/error-boundary {:key "k" :fallback [:i "f"]} [:b "ok"]])]
      (is (contains? keyed :key) "an explicit nil key is present on the boundary")
      (is (nil? (:key keyed)) "and carries nil")
      (is (not (contains? unkeyed :key)) "an absent key stays absent")
      (is (= "k" (:key valued)) "and an ordinary key is untouched"))))

;; ---------------------------------------------------------------------------
;; The other two keyable sites
;; ---------------------------------------------------------------------------

(def ^:private literal-rows
  "Every remaining structural site a `:key` can be written at, in all three
  spellings. Elements and fragments are keyed by the same law as
  boundaries, and the fragment row is the one that used to read the key's
  VALUE to decide its presence."
  [{:note "an element"  :form [:div {:key nil}]  :keyed? true  :key nil}
   {:note "an element"  :form [:div {}]          :keyed? false :key nil}
   {:note "an element"  :form [:div {:key "k"}]  :keyed? true  :key "k"}
   {:note "a fragment"  :form [:<> {:key nil} "x"] :keyed? true  :key nil}
   {:note "a fragment"  :form [:<> {} "x"]         :keyed? false :key nil}
   {:note "a fragment"  :form [:<> "x"]            :keyed? false :key nil}
   {:note "a fragment"  :form [:<> {:key "k"} "x"] :keyed? true  :key "k"}])

(deftest an-element-and-a-fragment-carry-key-iff-explicitly-keyed
  (testing "The law is one law across the node set. Asserting the literal
            sites beside the boundary sites is what makes it a law rather
            than four independent behaviours that happen to agree today."
    (doseq [{:keys [note form keyed?] k :key} literal-rows]
      (let [node (tree/render form)]
        (is (= keyed? (contains? node :key))
            (str note " " (pr-str form) " — key presence"))
        (is (= k (:key node))
            (str note " " (pr-str form) " — key value"))))))

;; ---------------------------------------------------------------------------
;; The scope fence
;; ---------------------------------------------------------------------------

(deftest normalize-call-still-strips-key-and-keeps-it-out-of-props
  (testing "Presence is reported BESIDE the props map, never inside it.
            `:key` selects sibling identity and the view body never sees
            it, and two calls that differ only in their key still compare
            equal on props — which is what lets a boundary re-render
            without its identity leaking into its inputs."
    (let [explicit (descriptor/normalize-call kid [{:key nil :label "d"}])
          absent   (descriptor/normalize-call kid [{:label "d"}])
          valued   (descriptor/normalize-call kid [{:key "k" :label "d"}])]
      (is (= {:label "d"} (:props explicit)) ":key is stripped from the body's props")
      (is (= (:props explicit) (:props absent) (:props valued))
          "and stays outside props equality")
      (is (true? (:keyed? explicit)) "an explicit nil key is reported PRESENT")
      (is (nil? (:key explicit)) "with the nil value the call supplied")
      (is (false? (:keyed? absent)) "and an absent key is reported absent")
      (is (nil? (:key absent)) "with the same nil value — which is why :keyed? exists"))))
