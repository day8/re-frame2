(ns re-frame.hicasso.examples.forms.views
  "THE MARKUP FOR THE THREE RECIPES.

  Five boundaries. Everything they reach for is `h/…` — `defview`, `sub`,
  the `::h/value` marker, `::h/revision`, the `:on-key-down` key map — or
  a plain intent vector. There is no `impl` namespace in the `:require`
  list and no `re-frame.core` either: a view neither subscribes nor
  dispatches, because a read is `h/sub` and an intent is data.

  ## Every read is as narrow as the thing it decides

  A controlled field re-renders its boundary when a subscription it read
  notifies, so a body that reads more than it needs pays for the
  difference on every keystroke. [[field-row]] reads its OWN field's text
  and its OWN field's displayable problem; the save button reads the gate
  and the write's status; the form shell reads nothing at all and so
  never re-renders. The per-keystroke arithmetic is published for the
  four-field editor and the 100-cell grid, and this application follows it
  rather than measuring it again.

  ## The submit button is ENABLED while the form is invalid

  A disabled submit button is the tempting spelling and it is the wrong
  one: it removes the control from the tab order, it announces nothing,
  and it leaves the user holding an invalid form with no way to ask what
  is wrong. So the button stays operable and the refusal happens in the
  handler — which is what makes `:attempted?` reachable at all, and
  therefore what makes every hidden problem appear at once.

  `aria-disabled` is NOT the softer spelling of that, and reaching for it
  here is the mistake this docstring exists to stop. WAI-ARIA defines
  `aria-disabled=\"true\"` as perceivable but disabled — the element \"is
  not editable or otherwise operable\" — so it makes exactly the claim
  `disabled` makes, minus the tab-order loss. Pressing Save is the ONLY
  way this form reveals what is wrong, so a button that announced itself
  inoperable would be telling a screen reader user that the one available
  instruction does not work. Validity is therefore not on the button at
  all: it is on the fields, as `aria-invalid` plus a described problem
  region that announces itself when it appears. `aria-disabled` is for a
  control the handler really does refuse — the slice application's pager
  arrow at the end of its list is one, and this button is not.

  The button IS disabled while the write is in flight, and that comes
  from the write's own status rather than from a flag this application
  keeps: `:pending?` is a projection of the mutation instance, so a
  failure cannot leave a stale `true` behind for somebody to clear.

  ## What the fields carry, and what they deliberately do not

  The buffered subject field carries `::h/revision`, because Escape and
  a refused commit both leave it MOUNTED and showing text the model may
  never have taken. The two form fields carry none: nothing in this
  application ever asks them to abandon an edit in place — a successful
  save moves their draft to a value they are not already showing, which
  React's own re-assert handles — and a revision that never has to fire
  is a prop a reader has to reason about for nothing."
  (:require [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.forms.db :as rf.hicasso.examples.forms.db]
            [re-frame.hicasso.examples.forms.events :as rf.hicasso.examples.forms.events]
            [re-frame.hicasso.examples.forms.subs :as rf.hicasso.examples.forms.subs]))

(def ^:private problem-text
  "Problem keyword → the sentence shown. The rules live in `db` as
  keywords; turning one into English is a rendering decision and belongs
  here, where the rest of the strings are."
  {:problem/assignee-blank "An assignee is required."
   :problem/notes-too-long (str "Notes must be " rf.hicasso.examples.forms.db/notes-limit
                                " characters or fewer.")})

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered subject field
;; ---------------------------------------------------------------------------

(rf.hicasso/defview subject-hint
  "*Enter commits, Escape reverts* — present only while a session is open.

  Its own boundary, and that is not tidiness. It is the ONLY body that
  reads `::subs/editing?`, so opening and closing a session re-renders
  this line and does not re-render the field. That matters more than it
  looks: the field's reads are what decide when it re-commits, and a
  re-commit is what re-asserts the model over the box. Leave this read in
  the field's own body and every session end re-commits for free — which
  would make `::h/revision` inert, and a reset that only works because
  something else happened to re-render is not a reset."
  [{:keys [ikey]}]
  (when (rf.hicasso/sub [::rf.hicasso.examples.forms.subs/editing? ikey])
    [:p.subject-hint "Enter commits, Escape reverts."]))

(rf.hicasso/defview subject-field
  "The ticket's subject, edited in place behind a draft.

  The whole commit protocol is three props and no callback: Enter and
  blur both ask to commit, Escape asks to cancel, and each of those is a
  vector two renders of which are `=`. The handlers decide what happens;
  this body only says which gesture means which intent.

  `:value` is *the draft if there is one, otherwise the committed
  subject*, so there is no load-the-form step: the field is populated the
  moment the page paints and the first keystroke lands on top of the
  committed text. `::h/revision` is the session's end-marker — see the
  namespace docstring on why this field has one and the two below do
  not."
  [{:keys [ikey]}]
  ;; The id carries the instance key for the same reason the draft does:
  ;; two tickets edited on one page must not share an id, or the second
  ;; `<label for=…>` names the first field and a click focuses the wrong
  ;; one. An id is per-instance state too, and the page is where it shows.
  (let [id (str "ticket-" ikey "-subject")]
    [:div.subject-field
     [:label {:for id} "Subject"]
     [:input
      {:id          id
       :class       "subject"
       :type        "text"
       :value       (rf.hicasso/sub [::rf.hicasso.examples.forms.subs/subject-shown ikey])
       ::rf.hicasso/revision (rf.hicasso/sub [::rf.hicasso.examples.forms.subs/subject-revision ikey])
       :on-input    [rf.hicasso.examples.forms.db/subject-draft ikey ::rf.hicasso/value]
       :on-blur     [::rf.hicasso.examples.forms.events/commit-subject ikey]
       :on-key-down {"Enter"  [::rf.hicasso.examples.forms.events/commit-subject ikey]
                     "Escape" [::rf.hicasso.examples.forms.events/cancel-subject ikey]}}]
     [subject-hint {:ikey ikey}]]))

;; ---------------------------------------------------------------------------
;; Recipe 2 — an ordinary field, its touch mark and its gated problem
;; ---------------------------------------------------------------------------

(rf.hicasso/defview field-row
  "One label, one control, and — once the user has earned it — one
  problem.

  `:on-blur` is the touch mark: *the user has left this field* is what
  makes its problem worth showing, and it is a fact about this field
  alone. The problem region is ABSENT rather than empty while the gate is
  shut, so `aria-describedby` never points at a node that is not there."
  [{:keys [field label multiline?]}]
  (let [id      (str "ticket-" (name field))
        problem (rf.hicasso/sub [::rf.hicasso.examples.forms.subs/shown-problem field])
        busy?   (:pending? (rf.hicasso/sub [:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]))
        props   {:id                id
                 :class             (name field)
                 :value             (rf.hicasso/sub [::rf.hicasso.examples.forms.subs/field field])
                 :disabled          busy?
                 :aria-invalid      (if problem "true" "false")
                 :aria-describedby  (when problem (str id "-problem"))
                 :on-input          [::rf.hicasso.examples.forms.events/edit field ::rf.hicasso/value]
                 :on-blur           [::rf.hicasso.examples.forms.events/touch {:field field}]}]
    [:div.field-row
     [:label {:for id} label]
     (if multiline?
       [:textarea props]
       [:input (assoc props :type "text")])
     (when problem
       [:p.field-problem {:id (str id "-problem") :role "alert"}
        (problem-text problem)])]))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the write's status, read from the write
;; ---------------------------------------------------------------------------

(rf.hicasso/defview save-failure
  "The error region. Present only while the instance says the last
  attempt failed, and gone the moment a retry starts — because it is a
  projection of the write rather than a copy of it."
  [_]
  (when (:error? (rf.hicasso/sub [:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]))
    [:p.save-failure {:role "alert"} "Saving failed. Nothing was lost — try again."]))

(rf.hicasso/defview save-button
  "Submit. Disabled while the write is in flight and carrying nothing at
  all while the form is invalid — see the namespace docstring on why
  those are not two grades of the same thing.

  It does not read `::subs/can-submit?`, and that absence is the point.
  The only honest thing the button could do with validity is claim to be
  unavailable, and it is not: activating it is what reveals the errors.
  The gate still has exactly one definition — `db/can-submit?` — read by
  the submit handler and exposed at `::subs/can-submit?` for anything
  that needs the answer; `l0` pins those two agreeing."
  [_]
  (let [busy? (:pending? (rf.hicasso/sub [:rf/mutation {:instance rf.hicasso.examples.forms.events/save-instance}]))]
    [:button.save
     {:type     "submit"
      :disabled busy?}
     (if busy? "Saving…" "Save ticket")]))

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(rf.hicasso/defview details-form
  "The form shell. It reads NOTHING, so a keystroke in either field
  re-renders that field's row and stops there.

  `:on-submit` auto-prevents, so pressing Enter in a field submits the
  form and the browser does not navigate — with no `.preventDefault`
  anywhere in this file."
  [_]
  [:form.details {:on-submit [::rf.hicasso.examples.forms.events/submit]}
   [field-row {:field :assignee :label "Assignee"}]
   [field-row {:field :notes :label "Notes" :multiline? true}]
   [save-failure {}]
   [save-button {}]])

(rf.hicasso/defview screen
  "The whole page: the buffered subject above, the ordinary form below."
  [{:keys [ikey]}]
  [:main.ticket-screen
   [:h1 "Edit ticket"]
   [subject-field {:ikey ikey}]
   [details-form {}]])
