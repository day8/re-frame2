(ns re-frame.hicasso.examples.todo.db
  "THE SHAPE, AND THE ONE PIECE OF WIDGET STATE (rf2-hic-086).

  The todos, and nothing derived. The active filter is NOT here — it is
  read off the URL by [[re-frame.hicasso.examples.todo.subs/showing]], so
  the address bar and the highlighted tab cannot disagree. Neither is
  \"how many are left\": a count of a map this file already holds is a
  subscription, not a fact.

  ## Why `h/reg-state` is declared HERE

  [[draft]] is the per-row edit text — `h/reg-state`'s own named example
  (*\"a row's draft text\"*), keyed by the to-do's id, which is a domain
  id and therefore a good React key and therefore a good instance key.

  It is declared in the namespace that owns the SHAPE rather than in
  `subs` or in `events`, and that is a deliberate answer to a small
  problem the door creates: `reg-state` mints a subscription **and** an
  event under ONE keyword, so the keyword belongs to neither of the two
  namespaces an ordinary re-frame2 application splits along. Put it in
  `subs` and every write reads `[::subs/draft id text]`; put it in
  `events` and every read reads `(h/sub [::events/draft id])`. Half the
  call sites are wrong either way. `db` names a PLACE rather than an
  action, so `[::db/draft id]` reads correctly as both.

  A `nil` draft means *this row is not being edited* — one representation
  of unset, which is why cancelling an edit is `[::h/clear ::draft id]`
  (removal, back to the default) rather than a write of `nil`."
  (:require [re-frame.hicasso :as h]))

;; ---------------------------------------------------------------------------
;; The shape
;; ---------------------------------------------------------------------------

(def empty-db
  "No to-dos, an empty new-to-do box, and nothing else.

  `:todos` is a sorted map so that iteration order is insertion order
  without a second `:order` vector to keep in step with it. `:next-id`
  is the id allocator: ids are DATA the list is keyed by, so they may
  never be an index — a list keyed by its position reuses the wrong row
  the moment the order changes, and nothing on screen says so."
  {:todos (sorted-map) :next-id 1 :new-todo ""})

(defn seed
  "`titles` as fresh, undone to-dos. The one place the row shape is
  written out; every assertion about it reads it from here."
  [titles]
  (reduce (fn [db title]
            (let [id (:next-id db)]
              (-> db
                  (assoc-in [:todos id] {:id id :title title :done? false})
                  (assoc :next-id (inc id)))))
          empty-db
          titles))

;; ---------------------------------------------------------------------------
;; The one piece of widget state
;; ---------------------------------------------------------------------------

(def draft
  "The edit-in-place text for ONE row, keyed by the to-do's id.

      (h/sub      [::draft id])          ;; nil unless this row is editing
      (dispatch   [::draft id \"milk\"])   ;; type, or open the editor
      (dispatch   [::h/clear ::draft id]) ;; cancel — back to the default

  Absent means *not editing*, so opening the editor and seeding it with
  the current title are the same write, and the whole of `:on-double-
  click` is `[::draft id title]`.

  A hand-rolled `[:ui :edit-draft]` path is the bug this deletes: every
  row on the page would share one draft, they would all open together,
  and nothing would complain."
  (h/reg-state ::draft {:default nil}))
