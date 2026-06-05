(ns todomvc.db
  (:require [re-frame.core :as rf]))

;; The :showing slot is no longer in the default db — Spec 012's :route slice
;; owns it now. The :showing sub (in subs.cljs) derives :all/:active/:completed
;; from :rf.route/id.
(def default-db
  {:todos (sorted-map)})

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

;; localStorage is deliberate — see README. The Spec-014 :rf.http/managed demo lives with realworld.
(rf/reg-cofx :todo.storage/todos
  {:doc "Inject the saved TodoMVC items from localStorage into coeffects."}
  (fn cofx-todo-storage-todos [ctx]
    ;; Always yield a sorted-map. `some->` short-circuits to nil when
    ;; localStorage is empty (first run) or unavailable; without the `or`
    ;; the nil clobbers default-db's (sorted-map) downstream, breaking
    ;; allocate-next-id's `(last (keys todos))` = max-id invariant once the
    ;; map promotes to an unordered PersistentHashMap (>8 entries).
    (assoc-in ctx [:coeffects :todo.storage/todos]
              (or (some-> (.-localStorage js/globalThis)
                          (.getItem ls-key)
                          (storage->todos))
                  (sorted-map)))))
