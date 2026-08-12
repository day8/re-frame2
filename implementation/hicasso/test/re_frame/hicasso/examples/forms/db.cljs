(ns re-frame.hicasso.examples.forms.db
  "THE SHAPE, AND THE THREE PURE FUNCTIONS THE RECIPES SHARE (rf2-hic-051).

  One support ticket, one buffered field in front of its subject, and one
  ordinary form under it. Nothing derived is stored: *which problems does
  this draft have* and *may it be submitted* are functions of the map
  below, defined here once and called from both a subscription and an
  event handler.

  ## The shape

      {:ticket    {:id :subject :assignee :notes}
       :subject-revision {id n}   ;; the buffered field's reset trigger
       :form      {:draft {:assignee :notes}
                   :touched #{:assignee :notes}
                   :attempted? boolean}}

  and, at `h/reg-state`'s own address rather than in this map, the
  buffered field's live draft — see [[subject-draft]].

  ## Why [[can-submit?]] is a function and not a stored flag

  R-A6 asks for a submit gate that *cannot go stale*: the view disables
  the button from it and the submit handler refuses from it, and the two
  may never disagree. The failure mode the requirement names is two
  independent recomputations drifting apart, so the fix is one
  DEFINITION rather than one cached value. `::subs/can-submit?` calls
  this function and `::events/submit` calls this function, so a change to
  the rule reaches both or neither; there is no third site to forget.

  A materialised flow (`:output-path` into `app-db`) is the other honest
  answer and costs a registration and a boot event. It buys a value a
  tool can see, which matters when the rule is expensive or when
  something else must react to it changing. Neither is true here, so this
  application does not pay for it — and the reason is written down
  because *which of the two* is the question a reader arrives with."
  (:require [clojure.string :as str]
            [re-frame.hicasso :as h]))

;; ---------------------------------------------------------------------------
;; The shape
;; ---------------------------------------------------------------------------

(def blank-form
  "A form nobody has touched: the draft is empty, no field has been
  visited, and no submission has been attempted.

  `:touched` and `:attempted?` are the two halves of R-A5's display gate
  and they are kept apart deliberately. Touching one field must not
  reveal another field's problem; attempting a submission must reveal
  every one of them at once."
  {:draft      {:assignee "" :notes ""}
   :touched    #{}
   :attempted? false})

(defn seed
  "The starting `app-db` — one ticket, its subject already committed, and
  a blank form. A literal-shaped function so a suite seeds a frame with
  `[[:rf/set-db (db/seed)]]` and gets the page the browser shows."
  []
  {:ticket           {:id 7 :subject "Login page hangs on submit"}
   :subject-revision {}
   :form             blank-form})

;; ---------------------------------------------------------------------------
;; The buffered field's draft — h/reg-state, keyed by the ticket
;; ---------------------------------------------------------------------------

(def subject-draft
  "The buffered draft for ONE ticket's subject, keyed by the ticket's id.

      (h/sub    [::subject-draft id])          ;; nil unless a session is open
      (dispatch [::subject-draft id \"text\"])   ;; type — the first write opens it

  Absent means *no editing session*, which is what makes the commit
  handler idempotent: a blur arriving after Escape has ended the session
  finds nothing to commit and does nothing (R-A7's cancel-beats-late-blur,
  solved by the model rather than by ordering).

  It is keyed by the ticket id and not by a hand-picked path for
  `h/reg-state`'s own stated reason: a fixed `[:ui :subject-draft]` would
  be ONE draft shared by every ticket on the page, opening them all
  together with nothing on screen to say so."
  (h/reg-state ::subject-draft {:default nil}))

(defn draft-path
  "`h/reg-state`'s documented `app-db` layout for [[subject-draft]] — the
  `[:ui <concern> <ikey>]` triple, as a function of the instance key.

  Written out here rather than at three call sites because the `:ui` root
  is app-space and has no name on the door. An ordinary handler may read
  and write it, which is `reg-state`'s own claim about the tier, and this
  application needs the WRITE half: see [[re-frame.hicasso.examples.forms.events/end-session]]
  for the one turn a commit has to be."
  [ikey]
  [:ui subject-draft ikey])

;; ---------------------------------------------------------------------------
;; The three pure functions
;; ---------------------------------------------------------------------------

(defn subject-shown
  "What the subject field DISPLAYS for ticket `ikey`: the live draft when
  a session is open, the committed subject otherwise.

  The fallback is what makes the first keystroke work without a
  load-the-form step — there is no draft yet, so the field is already
  populated, and the first character lands on top of the committed text
  rather than into an empty box."
  [db ikey]
  (or (get-in db (draft-path ikey))
      (get-in db [:ticket :subject])))

(def notes-limit
  "How long a note may be. A rule the client can check, so the form
  checks it and the server never sees a submission that breaks it."
  140)

(defn problems
  "The problems `draft` has, as a map of field → problem keyword.

  Problem KEYWORDS rather than sentences: a message is a rendering
  decision and belongs where the strings live, and a keyword is what a
  test can assert on without pinning prose."
  [{:keys [assignee notes]}]
  (cond-> {}
    (str/blank? assignee)          (assoc :assignee :problem/assignee-blank)
    (> (count notes) notes-limit)  (assoc :notes :problem/notes-too-long)))

(defn shown-problem
  "The problem to DISPLAY for `field`, or nil — R-A5's gate.

  A problem exists from the first paint of a blank form; showing it then
  is the defect. It becomes visible when the user has left that field, or
  when they have asked to submit — never before, and never for a field
  its neighbour was touched."
  [{:keys [draft touched attempted?]} field]
  (when (or attempted? (contains? touched field))
    (get (problems draft) field)))

(defn can-submit?
  "May this form be submitted? The ONE definition — see the namespace
  docstring on why it is a function rather than a stored flag."
  [db]
  (empty? (problems (get-in db [:form :draft]))))
