(ns todomvc.events
  "The only place app-db ever gets written — plus the routes and the one effect
  that touches the outside world.

  Three things to see here. `reg-event`: one pure handler per state change —
  add, toggle, save, delete, clear-completed, toggle-all — each a plain
  `(coeffects, event-vector) → effect map`. `reg-fx`: the `:todo.storage/save`
  handler that actually performs the localStorage write the event handlers only
  *describe* as data. And `reg-route`: the URL treated as an input — `/`,
  `/active`, `/completed`, plus the not-found fallback.
  See docs/core/glossary.md (event)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Pulling in re-frame.routing registers the routing subscriptions
            ;; and install hook. Skip it and the `rf/reg-route` calls below throw
            ;; :rf.error/routing-artefact-missing — a polite reminder you forgot
            ;; to bring routing along.
            [re-frame.routing]
            [todomvc.db :as db]))

;; ---- routes ---------------------------------------------------------------
;; A route is just an id paired with a path the router knows how to match.
;; TodoMVC's URLs are hash-based (#/, #/active, #/completed), but the routes are
;; written in PATH form (/, /active, /completed) — the frame's `:url-strategy`
;; (hash) handles the `#` at the edges, so `route-url`/`match-url` stay path-form
;; and these plain patterns line up. See docs/routing/concepts.md.

(rf/reg-route :todo/all       {:doc "Show all todos."} "/")
(rf/reg-route :todo/active    {:doc "Show active todos."} "/active")
(rf/reg-route :todo/completed {:doc "Show completed todos."} "/completed")

;; You must register a not-found route — it's not optional. Any URL that matches
;; nothing else lands here, and this app just sends it to "show all", so a typo'd
;; hash is a harmless shrug rather than a broken screen.
;; See docs/routing/concepts.md#not-found-is-a-route-you-register.
(rf/reg-route :rf.route/not-found {:doc "Fallback."} "/_404")

(defn- allocate-next-id [todos]
  ((fnil inc 0) (last (keys todos))))

(defn- persist-db [next-db]
  {:db next-db
   :fx [[:todo.storage/save (:todos next-db)]]})

(rf/reg-fx :todo.storage/save
  {:doc       "Write the TodoMVC items out to localStorage. This is the one spot
               that actually touches storage; the handlers just ask for it."
   :platforms #{:client}}
  (fn fx-todo-storage-save [_ todos]
    (when-let [ls (.-localStorage js/globalThis)]
      (->> todos
           vals
           (mapv #(select-keys % [:id :title :completed]))
           (clj->js)
           (js/JSON.stringify)
           (.setItem ls db/ls-key)))))

(rf/reg-event :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc db/default-db :todos todos)}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [title' (str/trim (or title ""))]
      (if (str/blank? title')
        {}
        (let [id      (allocate-next-id (:todos db))
              next-db (assoc-in db [:todos id]
                                {:id id :title title' :completed false})]
          (persist-db next-db))))))

(rf/reg-event :todo/toggle-completed
  (fn [{:keys [db]} [_ id]]
    (persist-db (update-in db [:todos id :completed] not))))

(rf/reg-event :todo/save
  (fn [{:keys [db]} [_ id title]]
    (let [title' (str/trim (or title ""))]
      (if (str/blank? title')
        (persist-db (update db :todos dissoc id))
        (persist-db (assoc-in db [:todos id :title] title'))))))

(rf/reg-event :todo/delete
  (fn [{:keys [db]} [_ id]]
    (persist-db (update db :todos dissoc id))))

(rf/reg-event :todo/clear-completed
  (fn [{:keys [db]} _]
    (persist-db
      (update db :todos
              (fn [todos]
                (into (sorted-map)
                      (remove (comp :completed val))
                      todos))))))

(rf/reg-event :todo/toggle-all
  (fn [{:keys [db]} _]
    (let [todos          (:todos db)
          mark-complete? (not (and (seq todos)
                                   (every? :completed (vals todos))))
          next-db        (update db :todos
                                 (fn [items]
                                   (reduce-kv
                                     (fn [acc id todo]
                                       (assoc acc id (assoc todo :completed mark-complete?)))
                                     (sorted-map)
                                     items)))]
      (persist-db next-db))))

;; ---- UI / form state (the `:ui` slice) ------------------------------------
;;
;; These events look after the form/UI state. The inputs are CONTROLLED: a
;; view's `:value` reads a draft sub, and its `:on-change` dispatches
;; `:todo.ui/edit-field`. The view never keeps a copy of the text itself.
;; Likewise, "which row is being edited" is real application state, so it lives
;; at `[:ui :editing-id]`, flipped on and off by `:todo.ui/start-edit` /
;; `:todo.ui/stop-edit`.
;;
;; Drafts and editing-id are pure UI froth — here one moment, gone the next — so
;; we don't persist them. That's why these handlers return a plain `{:db ...}`
;; and skip `persist-db` entirely. Only the todo facts (`:todos`) are worth
;; saving to disk.

(rf/reg-event :todo.ui/edit-field
  {:doc "The user typed into a controlled input. `which` is `:new` (the header
         input) or `:edit` (the edit-in-place input for the row in
         `:editing-id`)."}
  (fn [{:keys [db]} [_ which value]]
    {:db (assoc-in db [:ui :drafts which] value)}))

(rf/reg-event :todo.ui/start-edit
  {:doc "Drop a row into edit-in-place: remember which id is editing, and seed
         the edit draft with that todo's current title so the box opens
         pre-filled."}
  (fn [{:keys [db]} [_ id]]
    (let [title (get-in db [:todos id :title] "")]
      {:db (-> db
               (assoc-in [:ui :editing-id] id)
               (assoc-in [:ui :drafts :edit] title))})))

(rf/reg-event :todo.ui/stop-edit
  {:doc "Back out of edit-in-place without saving (Escape, or just after a
         save): forget the editing id and wipe the edit draft."}
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:ui :editing-id] nil)
             (assoc-in [:ui :drafts :edit] ""))}))

(rf/reg-event :todo.ui/commit-new
  {:doc "Commit the header input: read the `:new` draft, add the todo (the add
         handler does the trimming and blank-skipping), then clear the draft so
         it's empty for the next one."}
  (fn [{:keys [db]} _]
    (let [title (get-in db [:ui :drafts :new])]
      {:db (assoc-in db [:ui :drafts :new] "")
       :fx [[:dispatch [:todo/add title]]]})))

(rf/reg-event :todo.ui/commit-edit
  {:doc "Commit the edit-in-place input: read the `:edit` draft, save it onto
         the row being edited (a blank title deletes the todo), then leave edit
         mode."}
  (fn [{:keys [db]} _]
    (let [id    (get-in db [:ui :editing-id])
          title (get-in db [:ui :drafts :edit])]
      (cond-> {:db (-> db
                       (assoc-in [:ui :editing-id] nil)
                       (assoc-in [:ui :drafts :edit] ""))}
        id (assoc :fx [[:dispatch [:todo/save id title]]])))))
