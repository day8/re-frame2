(ns re-frame.freehand.trusted-markup-views
  "The `(v/html s)` declarations the trusted-markup suites render — each one
  spelled TWICE, interpreted and compiled, from the same body.

  `v/html` has FOUR rendering paths, not two, and that is the whole reason
  this file is a matched pair rather than a list. Freehand's paved path is
  INTERPRETED (`{:compiled true}` is opt-in), so the browser walk and the
  structural walk each have to lower trusted markup on their own account —
  the donor's port covered only the two compiled emitters. A declaration
  that renders correctly compiled and drops its markup interpreted is the
  ordinary case failing while the optimised one works.

  So every claim is asserted over the pair, and the pair is asserted to be
  the same claim: `re-frame.freehand.trusted-markup-ssr-jvm-test` pins the
  two structural paths against the contract and against each other, and
  `re-frame.freehand.trusted-markup-dom-cljs-test` does the same for the two
  React paths in a real browser.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)
  §Trusted markup."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The verbatim bypass
;; ---------------------------------------------------------------------------

(v/defview markup-body
  "Trusted markup as the sole child of an element. `<b>` reaches the
  document as an ELEMENT, which is the whole of what the verb does."
  [{:keys [markup]}]
  [:article.body (v/html markup)])

(v/defview markup-body-compiled
  "The same declaration, promoted. One line differs, and nothing about the
  rendered output may."
  {:compiled true}
  [{:keys [markup]}]
  [:article.body (v/html markup)])

;; ---------------------------------------------------------------------------
;; The control — an ordinary string in the same position
;; ---------------------------------------------------------------------------

(v/defview escaped-body
  "The SAME string as an ordinary text child. Without this row the verbatim
  rows above would prove only that some markup came out, not that the verb
  is what stopped the escaping."
  [{:keys [markup]}]
  [:article.body markup])

(v/defview escaped-body-compiled
  "The compiled control."
  {:compiled true}
  [{:keys [markup]}]
  [:article.body markup])

;; ---------------------------------------------------------------------------
;; Ordinary props beside the markup
;; ---------------------------------------------------------------------------

(v/defview markup-with-props
  "An element carries its attributes AND its trusted markup: the content
  channel is the markup's, and the props channel is untouched by it. A
  literal class, `#id` sugar, a runtime attribute and a `data-*`
  pass-through all sit beside the bypass. A real React `:key` is a
  separate fixture — see `keyed-markup` below, which is about ORDER rather
  than about arrival."
  [{:keys [markup lang]}]
  [:section.prose#post {:lang lang :data-kind "body"} (v/html markup)])

(v/defview markup-with-props-compiled
  "The compiled twin — where the props are build-time literals and the
  markup a render-time write, so their ORDER is the thing that could
  diverge."
  {:compiled true}
  [{:keys [markup lang]}]
  [:section.prose#post {:lang lang :data-kind "body"} (v/html markup)])

;; ---------------------------------------------------------------------------
;; A real `:key` beside the markup — the ORDER fixture
;; ---------------------------------------------------------------------------

;; `#post` id sugar is a build-time literal, so `markup-with-props` can only
;; prove that ordinary props ARRIVE. It cannot see the other cross-mode fact a
;; props-plus-markup element carries: WHEN each expression runs. Both values
;; here are opaque zero-argument calls, so a suite supplies thunks that record
;; their own turn — or throw — and the authored order becomes observable
;; (rf2-rrosy, #6980 audit: the compiled React emitter wrote the trusted markup
;; before the key, so a throwing key still ran the markup expression and a
;; throwing markup expression pre-empted the key).
;;
;; `:key` is the prop chosen because it is the LAST thing the compiled React
;; emitter writes onto the props object, and trusted markup is the element's
;; content — so this pair is the two ends of the emitted element meeting.

(v/defview keyed-markup
  "A real React `:key` and trusted markup on ONE element, both from opaque
  calls. Interpreted, the authored props map — key included — is evaluated
  whole before the child position is looked at; the compiled twin must
  produce that same order."
  [{:keys [k m]}]
  [:section.order {:key (k)} (v/html (m))])

(v/defview keyed-markup-compiled
  "The compiled twin. Here the key is an `unchecked-set` write and the
  markup a `html!` write onto the same props object, so their emitted
  ORDER is the whole of the claim."
  {:compiled true}
  [{:keys [k m]}]
  [:section.order {:key (k)} (v/html (m))])

;; ---------------------------------------------------------------------------
;; A literal string — the site the compiler can settle whole
;; ---------------------------------------------------------------------------

(v/defview literal-markup
  "A literal argument. Compiled, this is the serialisable site the manifest
  records with `:static? true`; interpreted it is an ordinary call."
  [_]
  [:div.static (v/html "<em>fixed</em>")])

(v/defview literal-markup-compiled
  "The compiled twin of the literal site."
  {:compiled true}
  [_]
  [:div.static (v/html "<em>fixed</em>")])

;; ---------------------------------------------------------------------------
;; Nested — trusted markup inside ordinary structure
;; ---------------------------------------------------------------------------

(v/defview markup-nested
  "The bypass is scoped to the ONE element that owns it: its siblings and
  its ancestors escape exactly as they always did."
  [{:keys [markup]}]
  [:div.page
   [:h1.title "<not markup>"]
   [:article.body (v/html markup)]
   [:footer.foot "<also not markup>"]])

(v/defview markup-nested-compiled
  "The compiled twin."
  {:compiled true}
  [{:keys [markup]}]
  [:div.page
   [:h1.title "<not markup>"]
   [:article.body (v/html markup)]
   [:footer.foot "<also not markup>"]])

(def by-name
  "Census keyword -> declaration, so a suite cannot assert a fact about a
  view it did not actually load."
  {:markup-body              markup-body
   :markup-body-compiled     markup-body-compiled
   :escaped-body             escaped-body
   :escaped-body-compiled    escaped-body-compiled
   :markup-with-props       markup-with-props
   :markup-with-props-compiled markup-with-props-compiled
   :keyed-markup             keyed-markup
   :keyed-markup-compiled    keyed-markup-compiled
   :literal-markup           literal-markup
   :literal-markup-compiled  literal-markup-compiled
   :markup-nested            markup-nested
   :markup-nested-compiled   markup-nested-compiled})

(def modes
  "The interpreted / compiled PAIRS. Every claim below is asserted over both
  members and then between them, because `v/html` shipped with two of its
  four lowerings missing and the two that were missing were the interpreted
  ones (rf2-rrosy)."
  [[:markup-body       :markup-body-compiled]
   [:escaped-body      :escaped-body-compiled]
   [:markup-with-props :markup-with-props-compiled]
   [:keyed-markup      :keyed-markup-compiled]
   [:literal-markup    :literal-markup-compiled]
   [:markup-nested     :markup-nested-compiled]])

(defn recorder
  "Props for the `keyed-markup` pair that RECORD their turn: an atom of the
  tags in evaluation order, and the props map whose two thunks conj onto it.

  `overrides` replaces either thunk — `{:k #(throw …)}` — so the throwing
  arms and the ordinary one are the same declaration under different
  values, which is what makes the comparison a claim about the LOWERING."
  ([] (recorder {}))
  ([overrides]
   (let [log (atom [])
         note (fn [tag v] (swap! log conj tag) v)]
     [log (merge {:k #(note :key "k1")
                  :m #(note :html "<b>bold</b>")}
                 overrides)])))
