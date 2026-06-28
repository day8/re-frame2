(ns todomvc.db
  "The shape of app-db, and the boot read that fills it.

  Two ideas live here. First, the single source of truth: `default-db` holds
  the todos and the UI state and nothing else — notably not the active filter,
  which we derive from the route instead of storing twice. Second, a recordable
  coeffect (`reg-cofx :todo.storage/todos`) that reads the saved todos in at
  boot. Routing the storage read through a coeffect keeps the durable write
  replayable, which a stray localStorage call inside a handler would quietly
  break. See docs/core/glossary.md (coeffect)."
  (:require [re-frame.core :as rf]))

;; Notice there's no `:showing` slot here. The active filter isn't stored — it's
;; derived: the :showing sub (in subs.cljs) reads :rf.route/id and maps it to
;; :all/:active/:completed. One fact, one home.
;;
;; The `:ui` slice is where the form/UI state lives. "Which row is being edited"
;; and the live input drafts are genuinely application state, so they get the
;; full treatment: stored in app-db, read via subs, changed only by events.
;; Inputs read `:drafts` for their `:value` (that's what makes them controlled)
;; and dispatch edit events on `:on-change`. `:editing-id` names the one row in
;; edit-in-place mode (nil = nobody's editing). Two draft slots cover it: one
;; for the new-todo header input, one shared by the single editing row.
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

;; The saved todos are a fact about the world out there (storage), and
;; `:todo/initialise` folds them into durable app-db. Here's the rule: a durable
;; write has to fold a RECORDED fact, never a live `localStorage` read taken at
;; the write site. Why? Because a live read can't be reproduced later under
;; replay or epoch-restore — the world may have moved on. So `:todo.storage/todos`
;; is a RECORDABLE coeffect: its supplier reads localStorage at the boot
;; dispatch, the value gets stamped onto the event envelope, and replay re-folds
;; that captured snapshot instead of reading localStorage all over again.
;;
;; `reg-cofx` is how you make a coeffect available. See
;; docs/core/glossary.md (coeffect) and
;; docs/core/glossary.md#recordable-vs-ambient-coeffects.

(defn read-todos-from-storage
  "Read the saved TodoMVC items from localStorage into a sorted-map. This is the
   supplier body behind the `:todo.storage/todos` recordable coeffect.

   nil or empty localStorage (a first run, or a server that has none at all)
   gives back an empty sorted-map. And the sorted-map isn't incidental:
   allocate-next-id grabs the highest id with `(last (keys todos))`, which only
   tells the truth while the map stays sorted."
  []
  (or (some-> (.-localStorage js/globalThis)
              (.getItem ls-key)
              (storage->todos))
      (sorted-map)))

(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :doc "Recordable coeffect: the saved TodoMVC items, read from localStorage as
         a sorted-map. The supplier runs at the boot dispatch; its value is
         recorded onto the event envelope and handed back verbatim under replay
         or epoch-restore. To use it, a handler declares
         `:rf.cofx/requires [:todo.storage/todos]` and reads it straight off the
         coeffects map — folding a recorded fact, never a live read."}
  (fn [] (read-todos-from-storage)))
