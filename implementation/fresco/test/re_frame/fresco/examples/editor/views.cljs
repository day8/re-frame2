(ns re-frame.hicasso.examples.editor.views
  "THE FOUR-FIELD EDITOR — every control written the way the guide writes
  one.

  `:value` off a subscription, an intent vector at `:on-input`, and
  nothing else. No ref, no `on-change` closure, no local draft, no effect
  reconciling two copies of the text. The model is the only place the
  value lives, and the readout at the bottom of the form reads the
  committed half of it through an ordinary subscription.

  ## One field, one boundary — and that is the whole performance story

  [[text-field]] is the unit of re-render. Four controls means four
  boundaries, each reading its own address, so a keystroke in the title
  notifies the title's boundary and no other
  (`docs/core/hicasso/19-performance.md` §Trace one
  controlled keystroke). [[editor]] itself reads NOTHING: it is a layout
  body that runs at mount and, thereafter, only when a prop of its own
  changes. That absence is the load-bearing part, and
  `editor.l2-cljs-test` asserts it the only way it can be asserted — by
  handing the body an EMPTY fixture map and letting the kit refuse any
  read it makes.

  ## What was NOT needed here

  Recorded because the slice authoring report makes the same list from a
  broader application, and a facade freeze reads both. This form of four
  controls, two buttons and a readout needed `defview`, `sub`, and the three
  markers `::h/value`, `::h/checked` and `::h/revision`. It needed no
  `h/event` — every intent said what it meant as a vector — and no
  `boundary`, `portal`, `as-element`, `as-component`, `defhost`,
  `route-link` or `reg-state`. **Six names and three keywords.**

  ## The one place the public door reads awkwardly

  `::h/revision` sits in the attribute map beside `:value`, which is
  where HD-019 puts it, but it is not an attribute and never reaches the
  wire — so a reader of [[text-field]] meets three keys of which two are
  DOM and one is a runtime trigger, with nothing at the call site saying
  which is which. The `::h/` namespace is the whole of the signal. It is
  recorded rather than complained about: the alternative spellings
  (a wrapper, a second argument, a metadata carrier) all cost more than
  the ambiguity does, and the marker keywords already read
  `:re-frame.hicasso/…` in every other position."
  (:require [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.editor.events :as rf.hicasso.examples.editor.events]
            [re-frame.hicasso.examples.editor.subs :as rf.hicasso.examples.editor.subs]))

(def field-labels
  "The four accessible names, as data.

  Plain data and not a subscription, for a reason the read topology
  cares about: a label that came off a subscription would be a second
  read in every field's body, and a body's edge set is what decides when
  it re-renders. §7's answer to i18n is *ordinary data*, and at this size
  ordinary data is a map — swapping it for a locale-keyed lookup is a
  change to the parent that passes it down, not to the fields."
  {:title      "Title"
   :slug       "Slug"
   :body       "Body"
   :published? "Published"})

;; ---------------------------------------------------------------------------
;; The controls
;; ---------------------------------------------------------------------------

(rf.hicasso/defview text-field
  "One controlled `<input type=text>`: the title and the slug.

  Two reads, both its own — the field's value and the reset counter. The
  `<label>` is not decoration: an interactive element with no accessible
  name reads to a screen reader as an unlabelled control, and a witness
  that models one would be poor evidence."
  [{:keys [field label]}]
  (let [id (name field)]
    [:p.field
     [:label {:for id} label]
     [:input {:id          id
              :type        "text"
              :data-field  id
              ::rf.hicasso/revision (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/revision])
              :value       (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/field field])
              :on-input    [::rf.hicasso.examples.editor.events/edit field ::rf.hicasso/value]}]]))

(rf.hicasso/defview body-field
  "The same law on the other convergeable tag. Identical in every respect
  a reader would care about, which is the claim — `<textarea>` is not a
  second controlled-input story."
  [{:keys [label]}]
  [:p.field
   [:label {:for "body"} label]
   [:textarea {:id          "body"
               :data-field  "body"
               ::rf.hicasso/revision (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/revision])
               :value       (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/field :body])
               :on-input    [::rf.hicasso.examples.editor.events/edit :body ::rf.hicasso/value]}]])

(rf.hicasso/defview published-box
  "The owned `::h/checked` pair.

  It carries NO `::h/revision`, and the omission is not a choice: a
  revision on a value-less checkbox is REFUSED with
  `:rf.error/hicasso-revision-not-controlled`, because the trigger
  re-baselines a controlled `<input>`/`<textarea>` to a `:value` and a
  checkbox written idiomatically has none. Nor does it want one — there
  is no free draft between the click and the dispatch, so
  `[::events/discard]` moving `:published?` back always moves the value
  React compares. Both halves are measured: the refusal in
  `editor.l2-cljs-test`, the restore in `editor.flow-dom-cljs-test`."
  [{:keys [label]}]
  [:p.field
   [:label {:for "published"} label]
   [:input {:id         "published"
            :type       "checkbox"
            :data-field "published"
            :checked    (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/field :published?])
            :on-change  [::rf.hicasso.examples.editor.events/set-published ::rf.hicasso/checked]}]])

;; ---------------------------------------------------------------------------
;; The form's other two boundaries
;; ---------------------------------------------------------------------------

(rf.hicasso/defview buttons
  "Save and Discard, in their own boundary reading their own one value.

  Separate from the fields on purpose: `::subs/dirty?` moves on the
  FIRST keystroke of an editing session and then stays put, so this body
  runs once at the start of a burst of typing and not again. Putting the
  buttons in with a field would have put that read on the field's edge
  set and re-run the field on every change to it."
  [_]
  (let [dirty? (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/dirty?])]
    [:p.buttons
     [:button {:id       "save"
               :type     "button"
               :disabled (not dirty?)
               :on-click [::rf.hicasso.examples.editor.events/save]}
      "Save"]
     [:button {:id       "discard"
               :type     "button"
               :disabled (not dirty?)
               :on-click [::rf.hicasso.examples.editor.events/discard]}
      "Discard"]]))

(rf.hicasso/defview readout
  "The COMMITTED article, on the page.

  Its own boundary reading the committed addresses, so a keystroke — which
  moves the draft and not the article — notifies it not at all. That is
  the readable form of \"echoes only committed state\": the readout does
  not move while you type, and moves when you save."
  [_]
  [:dl.committed
   [:dt "title"] [:dd {:data-committed "title"} (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/committed :title])]
   [:dt "slug"] [:dd {:data-committed "slug"} (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/committed :slug])]
   [:dt "body"] [:dd {:data-committed "body"} (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/committed :body])]
   [:dt "published"] [:dd {:data-committed "published"}
                      (str (boolean (rf.hicasso/sub [::rf.hicasso.examples.editor.subs/committed :published?])))]])

;; ---------------------------------------------------------------------------
;; The form
;; ---------------------------------------------------------------------------

(rf.hicasso/defview editor
  "The whole application, and it reads nothing.

  Every value on this page comes from a child's own body, so this body
  runs at mount and then only if its own props change — which they never
  do. A parent that read anything a keystroke touches would put itself,
  and a props compare over every child, on the typing path."
  [_]
  [:main#four-field-editor
   [:h1 "Article"]
   [text-field {:key "title" :field :title :label (:title field-labels)}]
   [text-field {:key "slug" :field :slug :label (:slug field-labels)}]
   [body-field {:label (:body field-labels)}]
   [published-box {:label (:published? field-labels)}]
   [buttons {}]
   [readout {}]])
