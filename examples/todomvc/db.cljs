(ns todomvc.db
  (:require [re-frame.core :as rf]))

(def default-db
  {:todos   (sorted-map)
   :showing :all})

(def ls-key "todos-reframe2")

(defn- normalise-todo [{:keys [id title completed]}]
  (when-let [id' (try (int id) (catch :default _ nil))]
    {:id        id'
     :title     (str title)
     :completed (boolean completed)}))

(defn- storage->todos [raw]
  (if-not (seq raw)
    (sorted-map)
    (try
      (into (sorted-map)
            (comp (map normalise-todo)
                  (remove nil?)
                  (map (fn [todo] [(:id todo) todo])))
            (js->clj (js/JSON.parse raw) :keywordize-keys true))
      (catch :default _
        (sorted-map)))))

(rf/reg-cofx :todo.storage/todos
  {:doc "Inject the saved TodoMVC items from localStorage into coeffects."}
  (fn cofx-todo-storage-todos [ctx]
    (assoc-in ctx [:coeffects :todo.storage/todos]
              (some-> (.-localStorage js/globalThis)
                      (.getItem ls-key)
                      (storage->todos)))))
