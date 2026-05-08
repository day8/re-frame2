(ns todomvc.events
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [todomvc.db :as db]))

(defn- filter-from-hash [hash]
  (case hash
    "#/active"    :active
    "#!/active"   :active
    "#/completed" :completed
    "#!/completed" :completed
    :all))

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
  (fn [{:todo.storage/keys [todos]} [_ current-hash]]
    {:db (assoc db/default-db
                :todos todos
                :showing (filter-from-hash current-hash))}))

(rf/reg-event-db :todo/url-changed
  (fn [db [_ current-hash]]
    (assoc db :showing (filter-from-hash current-hash))))

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
