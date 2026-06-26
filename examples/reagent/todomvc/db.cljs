(ns todomvc.db
  "The shape of app-db, and the boot read that fills it.

  Demonstrates: the single source of truth (`default-db` — todos and *nothing
  else*; the active filter is derived from the route, never stored), and a
  recordable coeffect (`reg-cofx :todo.storage/todos`) whose registered supplier
  reads the saved todos *in* at boot, so the durable write stays replayable
  instead of reaching for localStorage inside a handler."
  (:require [re-frame.core :as rf]))

;; The default db carries no `:showing` slot — Spec 012's :route slice owns
;; that fact. The :showing sub (in subs.cljs) derives :all/:active/:completed
;; from :rf.route/id.
;;
;; The `:ui` slice holds the form/UI state TodoMVC used to keep in view-local
;; atoms. It is APPLICATION state — "which row is being edited" and the live
;; input drafts — so it lives in app-db, is projected via subs, and is mutated
;; only by events. Inputs read `:drafts` for their `:value` (controlled) and
;; dispatch edit events on `:on-change`; `:editing-id` names the single row in
;; edit-in-place mode (nil = none). Two draft slots are enough: one for the
;; new-todo header input, one shared by the single editing row.
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

;; localStorage is deliberate — see README; the Spec-014 :rf.http/managed demo
;; lives with realworld.
;;
;; EP-0017 (recordable coeffects): the saved todos are a STORAGE world fact, and
;; `:todo/initialise` folds them into durable app-db. A durable write must fold a
;; RECORDED fact, not an ambient `localStorage` read at the write site (which
;; replay / epoch-restore could not reproduce). So `:todo.storage/todos` is a
;; registered RECORDABLE coeffect whose supplier reads localStorage: the supplier
;; runs at the boot dispatch, its value is recorded onto the causal token, and
;; replay re-folds that captured snapshot rather than re-reading whatever
;; localStorage holds now.
;;
;; Registering the supplier is the canonical coeffect shape — the Glossary's
;; "make a coeffect available by registering a supplier with reg-cofx". (The
;; value-stamped-at-the-dispatch-site PROVIDED form — `:provided? true`, no
;; supplier — is reserved for boundary facts the app can't read itself, e.g. an
;; SSR server that stamps the session the client can't see.)

(defn read-todos-from-storage
  "Read the saved TodoMVC items from localStorage, normalised to a sorted-map —
   the supplier body for the `:todo.storage/todos` recordable coeffect. nil/empty
   localStorage (first run, or a server with no localStorage) yields an empty
   sorted-map, so it never clobbers `default-db`'s `(sorted-map)` — which would
   break allocate-next-id's `(last (keys todos))` max-id invariant once the map
   promotes to an unordered PersistentHashMap past 8 entries."
  []
  (or (some-> (.-localStorage js/globalThis)
              (.getItem ls-key)
              (storage->todos))
      (sorted-map)))

(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :doc "Recordable coeffect (EP-0017): the saved TodoMVC items, read from
         localStorage as a sorted-map. The registered supplier runs at the boot
         dispatch; its value is recorded onto the causal token and re-presented
         verbatim under replay / epoch-restore. A handler folds it into durable
         app-db by declaring `:rf.cofx/requires [:todo.storage/todos]` and
         reading it flat — folding a recorded fact, never an ambient read."}
  (fn [] (read-todos-from-storage)))
