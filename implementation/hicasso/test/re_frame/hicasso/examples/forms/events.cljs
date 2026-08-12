(ns re-frame.hicasso.examples.forms.events
  "THE THREE RECIPES, AS EVENT HANDLERS (rf2-hic-051).

  Ordinary re-frame2: every handler here is `(fn [coeffects event-v] →
  effect-map)` and nothing in this namespace knows a view substrate
  exists. It requires `re-frame.core`, `re-frame.resources` (for the
  mutation door) and this application's own `db`, and not the Hicasso
  door — so the whole file is L0, testable with `=` and a map.

  ## Recipe 1 — the buffered draft, and why a commit is ONE turn

  [[commit-subject]] and [[cancel-subject]] both END an editing session,
  and ending a session is two facts at once: the draft is gone, and the
  field must re-baseline. `h/reg-state` mints a setter and shares the
  framework's `[::h/clear <concern> <ikey>]`, and clear is an EVENT
  rather than a value — deliberately, so that a concern whose values are
  keywords cannot have a legitimate write mistaken for a removal.

  That is right for `reg-state` and it is why this application does not
  use it here. A commit that dispatched `[::h/clear …]` for the removal
  and wrote the revision in its own `:db` would land the two in
  DIFFERENT turns, and the turn in between renders a field whose
  revision has already moved while its draft has not — a reset spent on
  the text it was supposed to discard. So both handlers write
  `h/reg-state`'s documented `[:ui <concern> <ikey>]` path directly,
  through [[re-frame.hicasso.examples.forms.db/draft-path]]. `reg-state`
  states that tier is app-space and that an ordinary handler may read and
  write it; this is the case that needs the second half.

  ## Recipe 2 — the gate is a function, and the handler calls it

  [[submit]] refuses through `db/can-submit?`, and
  `::subs/can-submit?` disables the button through the same function.
  R-A6's failure is two recomputations drifting apart, and one definition
  is what makes that impossible.

  A refused submission is not a no-op: it sets `:attempted?`, which is
  what turns every hidden problem visible at once. That asymmetry is the
  recipe — the user pressed the button, so they are owed the reason.

  ## Recipe 3 — the write's status is the write's own

  [[save]] is a `reg-mutation` and [[submit]] runs it under a stable
  INSTANCE id. The form's busy flag, its error region and its disabled
  fields all read `[:rf/mutation {:instance ::save-instance}]`; there is
  no `:saving?` boolean in `app-db` for a failure path to forget to
  clear, and no completion callback whose arity anybody has to sniff.
  Completion arrives at [[saved]] because `:reply-to` named it.

  The stale-reply fence is the runtime's and this application inherits
  it: a superseded reply never reaches `:reply-to` at all, so [[saved]]
  contains no generation check and needs none. `forms.l0-cljs-test`
  drives two submissions under one instance and asserts exactly that."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Side-effecting: registers the `:rf.mutation/*` events and the
            ;; passive `:rf/mutation` subscription this form reads.
            [re-frame.resources]
            [re-frame.hicasso.examples.forms.db :as db]))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed
  {:doc "Install the starting app-db. The frame's `:initial-events` step."}
  (fn [_ _] {:db (db/seed)}))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered subject field
;; ---------------------------------------------------------------------------

(defn- end-session
  "Remove the draft for `ikey` and move its revision, in one `db`.

  The revision is what asks the field to re-baseline. It has to move even
  where the displayed value does not, because a session can leave text in
  the DOM that the model never took — a refused keystroke, a foreign
  write from a password manager, a composition the carve-out held off —
  and where nothing the field reads has changed, nothing re-commits and
  the drift stays on screen. Ending a session is precisely the moment
  that debt comes due."
  [db ikey]
  (-> db
      (update-in (pop (db/draft-path ikey)) dissoc ikey)
      (update-in [:subject-revision ikey] (fnil inc 0))))

(rf/reg-event ::commit-subject
  {:doc "Finish the subject session for `ikey`: accept the trimmed draft,
         or refuse a blank one and leave the subject standing. Either way
         the session ends.

         It takes NO text argument. The draft is already in `app-db` —
         every keystroke wrote it there through `h/reg-state`'s setter —
         and reading it here rather than off the DOM event is what makes
         the handler safe to fire twice. Enter then blur, double Enter,
         or a blur arriving after Escape all find no session the second
         time and do nothing."}
  (fn [{:keys [db]} [_ ikey]]
    (if-some [typed (get-in db (db/draft-path ikey))]
      (let [candidate (str/trim typed)]
        (if (str/blank? candidate)
          ;; REFUSE. The subject does not move, so nothing the field reads
          ;; would change — `end-session`'s revision is the entire reason
          ;; the refusal becomes visible instead of leaving the blank text
          ;; sitting there looking accepted.
          {:db (end-session db ikey)}
          {:db (-> db
                   (assoc-in [:ticket :subject] candidate)
                   (end-session ikey))}))
      {})))

(rf/reg-event ::cancel-subject
  {:doc "Abandon the subject session for `ikey` — Escape.

         Idempotent for the same reason [[commit-subject]] is: with no
         session open there is nothing to abandon. That is the whole of
         *cancel must beat the late blur*, answered by the model rather
         than by ordering."}
  (fn [{:keys [db]} [_ ikey]]
    (if (contains? (get-in db (pop (db/draft-path ikey))) ikey)
      {:db (end-session db ikey)}
      {})))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the ordinary fields, their touch marks, and the gate
;; ---------------------------------------------------------------------------

(rf/reg-event ::edit
  {:doc "Write one form field. POSITIONAL, because it carries `::h/value`:
         markers are substituted at the intent vector's TOP LEVEL, so one
         written inside a canonical payload map arrives as the keyword
         itself — silently, and rendered as text."}
  (fn [{:keys [db]} [_ field value]]
    {:db (assoc-in db [:form :draft field] value)}))

(rf/reg-event ::touch
  {:doc "Mark `field` visited — dispatched on blur, which is what *the
         user has left this field* means. One field at a time: touching
         the assignee may not reveal the note's problem."}
  (fn [{:keys [db]} [_ {:keys [field]}]]
    {:db (update-in db [:form :touched] conj field)}))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the write, as a mutation instance
;; ---------------------------------------------------------------------------

(def save-instance
  "The form's mutation INSTANCE id — stable, so the view's status read and
  the handler's execute name the same write. Instance rather than
  mutation id because two forms saving the same mutation must not read
  each other's pending state."
  ::save)

(defn register-save!
  "Register the `::save` mutation, and answer its id.

  A FUNCTION, called at ns-load below and again from every suite's
  fixture — the same shape and the same reason as
  `re-frame.hicasso.examples.slice.routes/register!`. A reset fixture
  restores the registrar to a baseline it captured, and the resources
  artefact's own per-test reset clears the mutation kind outright, so a
  registration performed once when this file loaded is not guaranteed to
  still be there when a row runs.

  What makes that worth a named function rather than a shrug is the
  failure mode. `[:rf.mutation/execute {:mutation <unregistered> …}]`
  mints no instance, issues no request, settles nothing and reports
  NOTHING: the instance reads `:idle` afterwards, exactly as it does
  before any write. Measured in this repository's `:browser-test` build,
  where the registration was gone and every symptom pointed at the
  transport. Filed as rf2-06lp."
  []
  (rf/reg-mutation ::save
    {:params-schema [:map [:assignee :string] [:notes :string]]}
    (fn [params _ctx]
      {:request {:method :put :url "/api/tickets/7" :body params}}))
  ::save)

(register-save!)

(rf/reg-event ::submit
  {:doc "Attempt the submission.

         Refused and accepted both set `:attempted?` — the user asked, so
         every hidden problem becomes visible either way. Only the
         accepted branch executes the write."}
  (fn [{:keys [db]} _]
    (let [attempted (assoc-in db [:form :attempted?] true)]
      (if-not (db/can-submit? db)
        {:db attempted}
        {:db attempted
         :fx [[:dispatch [:rf.mutation/execute
                          {:mutation ::save
                           :params   (get-in db [:form :draft])
                           :instance save-instance
                           :reply-to [::saved]}]]]}))))

(rf/reg-event ::saved
  {:doc "The write settled. On success the draft becomes the ticket and
         the form goes back to blank; on failure nothing moves, because
         the instance already carries the failure the view renders and a
         second copy of it here could only drift.

         No generation check: the runtime does not deliver a superseded
         reply to `:reply-to` at all."}
  (fn [{:keys [db]} [_ {:keys [status]}]]
    (if (= :ok status)
      {:db (-> db
               (update :ticket merge (get-in db [:form :draft]))
               (assoc :form db/blank-form))}
      {})))
