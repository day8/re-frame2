(ns todomvc.db
  "The shape of app-db, and the boot read that fills it.

  Demonstrates: the single source of truth (`default-db` holds the todos and the
  UI state, and nothing else; the active filter is derived from the route, never
  stored), and a recordable coeffect (`reg-cofx :todo.storage/todos`) whose
  supplier reads the saved todos in at boot, so the durable write stays
  replayable instead of reaching for localStorage inside a handler.
  See docs/guide/glossary.md (coeffect)."
  (:require [re-frame.core :as rf]))

;; The default db carries no `:showing` slot. The active filter is derived from
;; the route: the :showing sub (in subs.cljs) reads :rf.route/id and maps it to
;; :all/:active/:completed.
;;
;; The `:ui` slice holds the form/UI state. "Which row is being edited" and the
;; live input drafts are application state, so they live in app-db, are read via
;; subs, and are changed only by events. Inputs read `:drafts` for their
;; `:value` (controlled) and dispatch edit events on `:on-change`. `:editing-id`
;; names the single row in edit-in-place mode (nil = none). Two draft slots are
;; enough: one for the new-todo header input, one shared by the single editing
;; row.
(def default-ui
  {:editing-id nil
   :drafts     {:new "" :edit ""}})

(def default-db
  {:todos (sorted-map)
   :ui    default-ui})

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

;; The saved todos are a fact about the world (storage), and `:todo/initialise`
;; folds them into durable app-db. A durable write must fold a RECORDED fact, not
;; a live `localStorage` read at the write site — a live read could not be
;; reproduced under replay or epoch-restore. So `:todo.storage/todos` is a
;; RECORDABLE coeffect whose supplier reads localStorage: the supplier runs at
;; the boot dispatch, its value is recorded onto the event envelope, and replay
;; re-folds that captured snapshot rather than re-reading localStorage now.
;;
;; Registering a supplier with `reg-cofx` is the standard way to make a coeffect
;; available. See docs/guide/glossary.md (coeffect) and
;; docs/guide/glossary.md#recordable-vs-ambient-coeffects.

(defn read-todos-from-storage
  "Read the saved TodoMVC items from localStorage, normalised to a sorted-map.
   This is the supplier body for the `:todo.storage/todos` recordable coeffect.
   nil/empty localStorage (first run, or a server with no localStorage) yields an
   empty sorted-map. The sorted-map matters: allocate-next-id picks the max id
   via `(last (keys todos))`, which only holds while the map stays sorted."
  []
  (or (some-> (.-localStorage js/globalThis)
              (.getItem ls-key)
              (storage->todos))
      (sorted-map)))

(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :doc "Recordable coeffect: the saved TodoMVC items, read from localStorage as
         a sorted-map. The supplier runs at the boot dispatch; its value is
         recorded onto the event envelope and re-presented verbatim under replay
         / epoch-restore. A handler folds it into durable app-db by declaring
         `:rf.cofx/requires [:todo.storage/todos]` and reading it flat — folding
         a recorded fact, never a live read."}
  (fn [] (read-todos-from-storage)))
