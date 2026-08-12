(ns re-frame.hicasso.examples.forms.views
  "THE MARKUP FOR THE THREE RECIPES (rf2-hic-051).

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
  never re-renders. rf2-hic-045 published the per-keystroke arithmetic
  for the four-field editor and the 100-cell grid and this application
  follows it rather than measuring it again.

  ## The submit button is ENABLED while the form is invalid

  A disabled submit button is the tempting spelling and it is the wrong
  one: it removes the control from the tab order, it announces nothing,
  and it leaves the user holding an invalid form with no way to ask what
  is wrong. So the button stays operable, carries `aria-disabled`, and
  the refusal happens in the handler — which is what makes
  `:attempted?` reachable at all, and therefore what makes every hidden
  problem appear at once.

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
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.forms.db :as db]
            [re-frame.hicasso.examples.forms.events :as events]
            [re-frame.hicasso.examples.forms.subs :as subs]))

(def ^:private problem-text
  "Problem keyword → the sentence shown. The rules live in `db` as
  keywords; turning one into English is a rendering decision and belongs
  here, where the rest of the strings are."
  {:problem/assignee-blank "An assignee is required."
   :problem/notes-too-long (str "Notes must be " db/notes-limit
                                " characters or fewer.")})

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered subject field
;; ---------------------------------------------------------------------------

(h/defview subject-field
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
  [:div.subject-field
   [:label {:for "ticket-subject"} "Subject"]
   [:input#ticket-subject.subject
    {:type        "text"
     :value       (h/sub [::subs/subject-shown ikey])
     ::h/revision (h/sub [::subs/subject-revision ikey])
     :on-input    [db/subject-draft ikey ::h/value]
     :on-blur     [::events/commit-subject ikey]
     :on-key-down {"Enter"  [::events/commit-subject ikey]
                   "Escape" [::events/cancel-subject ikey]}}]
   (when (h/sub [::subs/editing? ikey])
     [:p.subject-hint "Enter commits, Escape reverts."])])

;; ---------------------------------------------------------------------------
;; Recipe 2 — an ordinary field, its touch mark and its gated problem
;; ---------------------------------------------------------------------------

(h/defview field-row
  "One label, one control, and — once the user has earned it — one
  problem.

  `:on-blur` is the touch mark: *the user has left this field* is what
  makes its problem worth showing, and it is a fact about this field
  alone. The problem region is ABSENT rather than empty while the gate is
  shut, so `aria-describedby` never points at a node that is not there."
  [{:keys [field label multiline?]}]
  (let [id      (str "ticket-" (name field))
        problem (h/sub [::subs/shown-problem field])
        busy?   (:pending? (h/sub [:rf/mutation {:instance events/save-instance}]))
        props   {:id                id
                 :class             (name field)
                 :value             (h/sub [::subs/field field])
                 :disabled          busy?
                 :aria-invalid      (if problem "true" "false")
                 :aria-describedby  (when problem (str id "-problem"))
                 :on-input          [::events/edit field ::h/value]
                 :on-blur           [::events/touch {:field field}]}]
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

(h/defview save-failure
  "The error region. Present only while the instance says the last
  attempt failed, and gone the moment a retry starts — because it is a
  projection of the write rather than a copy of it."
  [_]
  (when (:error? (h/sub [:rf/mutation {:instance events/save-instance}]))
    [:p.save-failure {:role "alert"} "Saving failed. Nothing was lost — try again."]))

(h/defview save-button
  "Submit. Operable while the form is invalid and disabled while the
  write is in flight — see the namespace docstring on why those are not
  the same kind of unavailable."
  [_]
  (let [busy? (:pending? (h/sub [:rf/mutation {:instance events/save-instance}]))
        can?  (h/sub [::subs/can-submit?])]
    [:button.save
     {:type          "submit"
      :disabled      busy?
      :aria-disabled (if can? "false" "true")}
     (if busy? "Saving…" "Save ticket")]))

;; ---------------------------------------------------------------------------
;; The screen
;; ---------------------------------------------------------------------------

(h/defview details-form
  "The form shell. It reads NOTHING, so a keystroke in either field
  re-renders that field's row and stops there.

  `:on-submit` auto-prevents, so pressing Enter in a field submits the
  form and the browser does not navigate — with no `.preventDefault`
  anywhere in this file."
  [_]
  [:form.details {:on-submit [::events/submit]}
   [field-row {:field :assignee :label "Assignee"}]
   [field-row {:field :notes :label "Notes" :multiline? true}]
   [save-failure {}]
   [save-button {}]])

(h/defview screen
  "The whole page: the buffered subject above, the ordinary form below."
  [{:keys [ikey]}]
  [:main.ticket-screen
   [:h1 "Edit ticket"]
   [subject-field {:ikey ikey}]
   [details-form {}]])
