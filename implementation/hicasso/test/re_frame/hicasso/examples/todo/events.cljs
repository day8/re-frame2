(ns re-frame.hicasso.examples.todo.events
  "EVERY WRITE THIS APPLICATION MAKES (rf2-hic-086).

  Eight handlers, all pure, none of them knowing that a view substrate
  exists. That is the point of the file: a Todo-class application's model
  is ordinary re-frame2, and Hicasso is a way of READING it.

  ## The two places the substrate does leak in here, and why

  1. **`::h/clear`.** Cancelling an edit removes the `h/reg-state` entry,
     and the door's spelling for that is a FRAMEWORK-NAMED event,
     `[::h/clear ::concern ikey]`. A handler that has to end an edit as
     part of doing something else — `::commit-edit` below — therefore
     dispatches it, and this namespace `:require`s the view door for the
     sole purpose of spelling one keyword. Nothing else here needs it.

  2. **The literal `:ui`.** `::commit-edit` has to ask *is this row
     being edited?*, and `h/reg-state` answers that question with a
     subscription and a setter — neither of which is available to an
     event handler. What IS available is the app-db layout the sugar
     documents, `[:ui ::concern ikey]`, which `impl.state`'s docstring
     explicitly says ordinary handlers may read and write. So the read is
     legitimate; what the door does not give is a NAME for the `:ui`
     root, so the line below writes the keyword out by hand:

         (get-in db [:ui db/draft id])

     Both are recorded in the authoring report. Neither is a reach past
     the door — `re-frame.hicasso` is the door — but both are places
     where the sugar stops one step short of the call site that needed
     it."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.todo.db :as db]))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed
  {:doc "Start from `titles`, or from nothing. The frame's `:initial-events`
         run to fixed point before the first render, so a mounted page opens
         on a seeded model rather than on a nil the views have to defend
         against."}
  (fn [_ [_ titles]]
    {:db (db/seed (or titles []))}))

;; ---------------------------------------------------------------------------
;; The new-to-do box
;; ---------------------------------------------------------------------------

(rf/reg-event ::typed
  {:doc "A keystroke in the new-to-do box. The box is CONTROLLED — the text
         lives here and nowhere else — so this is the whole of it."}
  (fn [{:keys [db]} [_ text]]
    {:db (assoc db :new-todo text)}))

(rf/reg-event ::add
  {:doc "Commit the new-to-do box: trim, ignore a blank, otherwise append and
         empty the box.

         A blank is IGNORED rather than refused. Submitting an empty form is
         not an error a user needs telling about, and the `{}` return —
         no `:db` key at all — is how a re-frame2 handler says *nothing
         happened*."}
  (fn [{:keys [db]} _]
    (let [title (str/trim (:new-todo db))]
      (if (str/blank? title)
        {}
        (let [id (:next-id db)]
          {:db (-> db
                   (assoc-in [:todos id] {:id id :title title :done? false})
                   (assoc :next-id (inc id))
                   (assoc :new-todo ""))})))))

;; ---------------------------------------------------------------------------
;; The to-dos themselves
;; ---------------------------------------------------------------------------

(rf/reg-event ::toggle
  {:doc "Set ONE row's done flag. The value arrives from `::h/checked` rather
         than being computed by negation, so the model takes what the checkbox
         actually is and the two cannot drift."}
  (fn [{:keys [db]} [_ id done?]]
    {:db (assoc-in db [:todos id :done?] (boolean done?))}))

(rf/reg-event ::toggle-all
  {:doc "Set EVERY row's done flag, again from `::h/checked`."}
  (fn [{:keys [db]} [_ done?]]
    {:db (update db :todos
                 (fn [todos]
                   (reduce-kv (fn [acc id todo]
                                (assoc acc id (assoc todo :done? (boolean done?))))
                              (sorted-map)
                              todos)))}))

(rf/reg-event ::destroy
  (fn [{:keys [db]} [_ id]]
    {:db (update db :todos dissoc id)}))

(rf/reg-event ::clear-completed
  (fn [{:keys [db]} _]
    {:db (update db :todos
                 (fn [todos]
                   (into (sorted-map) (remove (comp :done? val)) todos)))}))

;; ---------------------------------------------------------------------------
;; Edit in place
;; ---------------------------------------------------------------------------

(rf/reg-event ::commit-edit
  {:doc "Finish editing row `id`: write the trimmed draft onto the row (a
         blank one destroys it, which is TodoMVC's own rule), and close the
         editor.

         It takes NO text argument. The draft is already in `app-db` —
         every keystroke wrote it there through `h/reg-state`'s setter —
         and reading it here rather than off the DOM event is what makes
         this handler safe to fire twice. A blur that lands after Escape
         has already cleared the draft finds nothing to commit and does
         nothing, which is the whole of the cancel-must-beat-late-blur
         problem, solved by the model rather than by ordering."}
  (fn [{:keys [db]} [_ id]]
    ;; `[:ui <concern> <ikey>]` is `h/reg-state`'s documented app-db layout
    ;; and an ordinary handler may read it. The `:ui` root has no name on
    ;; the door, so it is written out — see the namespace docstring.
    (if-some [text (get-in db [:ui db/draft id])]
      (let [title (str/trim text)]
        {:db (if (str/blank? title)
               (update db :todos dissoc id)
               (assoc-in db [:todos id :title] title))
         :fx [[:dispatch [::h/clear db/draft id]]]})
      {})))
