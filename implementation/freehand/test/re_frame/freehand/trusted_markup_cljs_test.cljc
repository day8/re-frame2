(ns re-frame.freehand.trusted-markup-cljs-test
  "rf2-rrosy — `(v/html s)`'s POSITION and VALUE laws on the interpreted
  structural walk, host-neutral.

  The compiled tier's refusals are build failures and are proven where build
  failures can be driven, in
  `re-frame.freehand.analyze-reject-cljs-test`. This suite is the other half:
  the same laws on the INTERPRETED walk, which is Freehand's paved path and
  where trusted markup had no lowering at all until this slice. A rule that
  refuses a shape at compile and renders it — or drops it — interpreted is one
  declaration with two answers, which is the defect class the whole
  cross-mode arrangement exists to not have.

  Every refusal here is `:rf.error/ui-tree-malformed`, and every one of them
  is raised from `re-frame.freehand.node` — the ONE canonicaliser both walks
  and both modes reach. That is deliberate: a second sentence in a second
  place is a sentence that drifts.

  It runs on BOTH hosts because the structural walk does. The browser
  emitter's own arms are `re-frame.freehand.trusted-markup-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.node :as node]
            [re-frame.freehand.tree :as tree]))

(defn- refusal
  "The diagnostic id `thunk` raises, or `::rendered` plus the value it built —
  so a row that fails says WHAT reached the tree rather than only that
  nothing was refused."
  [thunk]
  (try [::rendered (thunk)]
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
         (:rf.error/id (ex-data ex)))))

;; ---------------------------------------------------------------------------
;; The value law — a string, and the check is shared
;; ---------------------------------------------------------------------------

(deftest the-verb-refuses-a-non-string-at-the-call
  (testing "`(v/html x)` answers a trusted-markup value or refuses; it never
            answers something a walk has to decide about later. The check runs
            at the CALL in an interpreted body, which is the earliest place the
            value exists."
    (doseq [bad [nil 42 :markup {:html "<b>x</b>"} ["<b>x</b>"] (list "<b>x</b>")]]
      (is (= :rf.error/ui-tree-malformed (refusal #(v/html bad)))
          (str (pr-str bad) " is not trusted markup")))
    (is (node/trusted-markup? (v/html "<b>x</b>"))
        "and a string answers the trusted-markup value — the non-vacuity row")))

;; ---------------------------------------------------------------------------
;; The position law — the SOLE child of a DOM element
;; ---------------------------------------------------------------------------

(deftest trusted-markup-outside-the-sole-child-position-is-refused
  (testing "The runtime twin of `:rf.ui.compile/html-not-sole-child`. Trusted
            markup is an ELEMENT's content, so every position that is not the
            sole child of one has no element to own it — and an element cannot
            have two contents. All of these used to be unreachable, because
            the verb did not exist; now they are reachable and each answers
            one shared sentence."
    (doseq [[note form]
            [["a sibling before it"     [:div [:span "s"] (v/html "<b>x</b>")]]
             ["a sibling after it"      [:div (v/html "<b>x</b>") [:span "s"]]]
             ["a text sibling"          [:div "text" (v/html "<b>x</b>")]]
             ["two of them"             [:div (v/html "<b>x</b>") (v/html "<i>y</i>")]]
             ["inside a spliced run"    [:div (list (v/html "<b>x</b>"))]]
             ["inside a fragment"       [:<> (v/html "<b>x</b>")]]
             ["at the root of a walk"   (v/html "<b>x</b>")]]]
      (is (= :rf.error/ui-tree-malformed (refusal #(tree/render form)))
          (str note " — refused: there is no element to own the markup")))))

(deftest a-literal-html-map-child-is-refused-as-a-second-spelling
  (testing "`{:html s}` is the tree's trusted-markup NODE, and `node?` accepts
            it — the SSR serialiser and the structural test surface read
            exactly that shape. A BUILD path must not, or the map becomes a
            second, quieter spelling of the bypass: no visible call, no
            manifest site, and none of the element rules above. The door
            answers a private nominal value for exactly this reason, so the
            map is refused by name."
    (doseq [[note form] [["as a sole child"  [:div {} {:html "<b>x</b>"}]]
                         ["beside a sibling" [:div {} {:html "<b>x</b>"} [:i "y"]]]
                         ;; The text child is what puts the map in a CHILD
                         ;; position: a fragment's leading map is its props.
                         ["inside a fragment" [:<> "t" {:html "<b>x</b>"}]]]]
      (is (= :rf.error/ui-tree-malformed (refusal #(tree/render form)))
          (str note " — the node shape is not an authoring form")))))

;; ---------------------------------------------------------------------------
;; The host laws — where an element cannot carry trusted markup at all
;; ---------------------------------------------------------------------------

(deftest a-textarea-cannot-carry-trusted-markup
  (testing "React 19 rejects `dangerouslySetInnerHTML` on a `<textarea>` (its
            content is `:value` / `defaultValue`, or an ordinary text child)
            and the SSR seam refuses the same node. So the substrate refuses
            it itself, in the canonicaliser both modes reach — otherwise one
            declaration answers a host throw in the browser and divergent
            markup on the server (rf2-ib4fd)."
    (is (= :rf.error/ui-tree-malformed
           (refusal #(tree/render [:textarea (v/html "<b>x</b>")])))
        "a textarea's content channel is :value, never trusted markup")
    (is (= [:tag :attrs] (keys (dissoc (tree/render [:textarea {:value "plain"}])
                                       :rf.ui/tree-version)))
        "and the sanctioned spelling still renders — the non-vacuity row")))

(deftest a-void-element-cannot-carry-trusted-markup
  (testing "A void element has no content channel at all, so trusted markup
            lands on the same refusal any other child does. It needs no arm of
            its own, which is the point: the markup IS the element's child in
            the tree."
    (doseq [tag [:br :img :input :hr]]
      (is (= :rf.error/ui-tree-malformed
             (refusal #(tree/render [tag (v/html "<b>x</b>")])))
          (str "<" (name tag) "> cannot carry trusted markup")))))

;; ---------------------------------------------------------------------------
;; The prop spellings stay refused — one spelling, and it is the verb
;; ---------------------------------------------------------------------------

(deftest every-prop-spelling-of-the-bypass-is-still-refused
  (testing "Publishing the verb does not open a prop-shaped door beside it.
            Each refused spelling names `(v/html ...)` as the replacement, and
            that recovery text is now TRUE rather than naming a form that did
            not exist — which was the invariant the Bead turned on: a
            diagnostic must not recommend a public form that does not exist."
    (doseq [k [:dangerouslySetInnerHTML :dangerously-set-inner-html :inner-html]]
      (is (= :rf.error/ui-tree-malformed
             (refusal #(tree/render [:div {k {:__html "<b>x</b>"}} "c"])))
          (str k " is refused as a literal attribute")))))

(deftest a-runtime-spread-cannot-smuggle-the-bypass-in
  (testing "The one place raw markup could otherwise reach a host with no
            visible trust assertion anywhere in the source: a prop map
            assembled at RUNTIME. Every spelling is denied there too, in every
            build, because a key is judged by the slot it is about to be
            written into rather than by how it was spelled."
    (doseq [k [:dangerouslySetInnerHTML :dangerously-set-inner-html :inner-html]]
      (is (= :rf.error/ui-tree-malformed
             (refusal #(tree/render [:div (v/spread {k {:__html "<b>x</b>"}})])))
          (str k " is refused through a runtime v/spread"))
      (is (= :rf.error/ui-tree-malformed
             (refusal #(tree/render
                         [:div (v/spread-safe {:class "own"} {k {:__html "<b>x</b>"}})])))
          (str k " is refused through a v/spread-safe caller map")))))
