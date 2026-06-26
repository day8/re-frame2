(ns todomvc.events
  "The only writers of app-db, plus the routes and the persistence effect.

  Demonstrates: `reg-event` (one pure handler per state change — add, toggle,
  save, delete, clear-completed, toggle-all; each `(coeffects, event-vector) →
  effect map`), `reg-fx` (the `:todo.storage/save` effect handler that performs
  the localStorage write the handlers only *describe* as data), and `reg-route`
  (the URL as an input — `/`, `/active`, `/completed`, plus the not-found
  fallback)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Routing ships in day8/re-frame2-routing.
            ;; Requiring re-frame.routing here triggers its load-time
            ;; hook + reg-sub registrations; without it, the rf/reg-route
            ;; calls below throw :rf.error/routing-artefact-missing.
            [re-frame.routing]
            [todomvc.db :as db]))

;; ---- routes (Spec 012) ----------------------------------------------------
;; TodoMVC's canonical URLs are hash-based (#/, #/active, #/completed). Spec
;; 012 routes match path-strings, so the host-adapter (core.cljs) strips the
;; leading '#' (and optional '!') from the URL hash before dispatching
;; :rf.route/handle-url-change. The result is a Spec 012 path the registered
;; routes match exactly.

(rf/reg-route :todo/all       {:doc "Show all todos."} "/")
(rf/reg-route :todo/active    {:doc "Show active todos."} "/active")
(rf/reg-route :todo/completed {:doc "Show completed todos."} "/completed")

;; Required by Spec 012 §Route-not-found. Unmatched URLs land here; we treat
;; them as "show all" so a stray hash never breaks the app.
(rf/reg-route :rf.route/not-found {:doc "Fallback."} "/_404")

(defn- allocate-next-id [todos]
  ((fnil inc 0) (last (keys todos))))

(defn- persist-db [next-db]
  {:db next-db
   :fx [[:todo.storage/save (:todos next-db)]]})

(rf/reg-fx :todo.storage/save
  {:doc       "Persist the TodoMVC items to localStorage."
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
;; These events own the form/UI state that TodoMVC used to keep in view-local
;; atoms. The inputs are CONTROLLED: a view's `:value` reads a draft sub and its
;; `:on-change` dispatches `:todo.ui/edit-field` — it never sets view-local
;; state. "Which row is being edited" is application state, so it lives at
;; `[:ui :editing-id]`, toggled by `:todo.ui/start-edit` / `:todo.ui/stop-edit`.
;;
;; Drafts and editing-id are pure UI — they are NOT persisted (no localStorage
;; write), so these handlers return a plain `{:db ...}` rather than going through
;; `persist-db`. Only the todo facts themselves (`:todos`) are durable.

(rf/reg-event :todo.ui/edit-field
  {:doc "User typed into a controlled input. `which` is `:new` (header input)
         or `:edit` (the edit-in-place input for the row in `:editing-id`)."}
  (fn [{:keys [db]} [_ which value]]
    {:db (assoc-in db [:ui :drafts which] value)}))

(rf/reg-event :todo.ui/start-edit
  {:doc "Enter edit-in-place on a row: record which id is editing and seed the
         edit draft from that todo's current title."}
  (fn [{:keys [db]} [_ id]]
    (let [title (get-in db [:todos id :title] "")]
      {:db (-> db
               (assoc-in [:ui :editing-id] id)
               (assoc-in [:ui :drafts :edit] title))})))

(rf/reg-event :todo.ui/stop-edit
  {:doc "Leave edit-in-place without saving (Escape, or after a save): clear
         the editing id and the edit draft."}
  (fn [{:keys [db]} _]
    {:db (-> db
             (assoc-in [:ui :editing-id] nil)
             (assoc-in [:ui :drafts :edit] ""))}))

(rf/reg-event :todo.ui/commit-new
  {:doc "Save the header input: read the `:new` draft, add the todo (which
         trims + ignores blanks), then clear the draft for the next entry."}
  (fn [{:keys [db]} _]
    (let [title (get-in db [:ui :drafts :new])]
      {:db (assoc-in db [:ui :drafts :new] "")
       :fx [[:dispatch [:todo/add title]]]})))

(rf/reg-event :todo.ui/commit-edit
  {:doc "Save the edit-in-place input: read the `:edit` draft, save it onto the
         editing row (a blank title deletes the todo, same as before), then
         leave edit mode."}
  (fn [{:keys [db]} _]
    (let [id    (get-in db [:ui :editing-id])
          title (get-in db [:ui :drafts :edit])]
      (cond-> {:db (-> db
                       (assoc-in [:ui :editing-id] nil)
                       (assoc-in [:ui :drafts :edit] ""))}
        id (assoc :fx [[:dispatch [:todo/save id title]]])))))
