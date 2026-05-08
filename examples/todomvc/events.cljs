(ns todomvc.events
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [todomvc.db :as db]))

;; ---- routes (Spec 012) ----------------------------------------------------
;; TodoMVC's canonical URLs are hash-based (#/, #/active, #/completed). Spec
;; 012 routes match path-strings, so the host-adapter (core.cljs) strips the
;; leading '#' (and optional '!') from the URL hash before dispatching
;; :rf.route/handle-url-change. The result is a Spec 012 path the registered
;; routes match exactly.

(rf/reg-route :todo/all       {:doc "Show all todos."       :path "/"})
(rf/reg-route :todo/active    {:doc "Show active todos."    :path "/active"})
(rf/reg-route :todo/completed {:doc "Show completed todos." :path "/completed"})

;; Required by Spec 012 §Route-not-found. Unmatched URLs land here; we treat
;; them as "show all" so a stray hash never breaks the app.
(rf/reg-route :rf.route/not-found {:doc "Fallback." :path "/_404"})

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

(rf/reg-event-fx :todo/initialise
  [(rf/inject-cofx :todo.storage/todos)]
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc db/default-db :todos todos)}))

(rf/reg-event-fx :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [title' (str/trim (or title ""))]
      (if (str/blank? title')
        {}
        (let [id      (allocate-next-id (:todos db))
              next-db (assoc-in db [:todos id]
                                {:id id :title title' :completed false})]
          (persist-db next-db))))))

(rf/reg-event-fx :todo/toggle-completed
  (fn [{:keys [db]} [_ id]]
    (persist-db (update-in db [:todos id :completed] not))))

(rf/reg-event-fx :todo/save
  (fn [{:keys [db]} [_ id title]]
    (let [title' (str/trim (or title ""))]
      (if (str/blank? title')
        (persist-db (update db :todos dissoc id))
        (persist-db (assoc-in db [:todos id :title] title'))))))

(rf/reg-event-fx :todo/delete
  (fn [{:keys [db]} [_ id]]
    (persist-db (update db :todos dissoc id))))

(rf/reg-event-fx :todo/clear-completed
  (fn [{:keys [db]} _]
    (persist-db
      (update db :todos
              (fn [todos]
                (into (sorted-map)
                      (remove (comp :completed val))
                      todos))))))

(rf/reg-event-fx :todo/toggle-all
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
