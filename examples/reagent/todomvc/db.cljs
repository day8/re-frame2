(ns todomvc.db
  (:require [re-frame.core :as rf]))

;; The default db carries no `:showing` slot — Spec 012's :route slice owns
;; that fact. The :showing sub (in subs.cljs) derives :all/:active/:completed
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
;;
;; EP-0017 (recordable coeffects): the saved todos are a STORAGE
;; world fact, and `:todo/initialise` folds them into durable app-db. A durable
;; write must be a function of prior frame-state plus the causal token — not of
;; an ambient `localStorage` read at the write site (which replay/restore could
;; not reproduce). So the host-boundary boot read happens once, in
;; `todomvc.core/run`, and rides the boot dispatch token as the flat RECORDABLE
;; coeffect `:rf.cofx {:todo.storage/todos …}`.
;;
;; `:todo.storage/todos` is therefore registered RECORDABLE + PROVIDED
;; (EP-0017 §2, the `:rf/time-ms` shape): it carries NO generator — its value is
;; STAMPED onto the boot dispatch token by its owner (`todomvc.core/run`,
;; reading the host once at the boundary), recorded with the token, and
;; re-presented verbatim under replay / epoch-restore. This closes the
;; determinism hole the ambient grade left open: replay re-folds the captured
;; snapshot, never a live re-read of whatever localStorage holds now.
;;
;; The boundary read (`read-todos-from-storage`) always coerces to a
;; `(sorted-map)`: a nil (empty / absent localStorage, first run) would
;; otherwise clobber default-db's `(sorted-map)` and break allocate-next-id's
;; `(last (keys todos))` = max-id invariant once the map promotes to an
;; unordered PersistentHashMap (>8 entries).
(defn read-todos-from-storage
  "Host-boundary read of the saved TodoMVC items from localStorage, normalised
   to a sorted-map. Called from `todomvc.core/run` so the value is STAMPED onto
   the boot dispatch token's `:rf.cofx` as a recordable causal fact rather than
   being read ambiently in a durable handler. nil/empty localStorage (first
   run, or node with no localStorage) yields an empty sorted-map."
  []
  (or (some-> (.-localStorage js/globalThis)
              (.getItem ls-key)
              (storage->todos))
      (sorted-map)))

(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :provided?   true
   :doc "Recordable, PROVIDED coeffect (EP-0017 §2): the saved TodoMVC items,
         read from localStorage as a sorted-map. It has NO generator — its
         value is stamped onto the boot dispatch token by `todomvc.core/run`
         (the host-boundary read happens ONCE there), recorded with the token,
         and re-presented verbatim under replay / epoch-restore. A handler that
         folds it into durable app-db declares
         `:rf.cofx/requires [:todo.storage/todos]` and reads it flat; absent
         from the token it is `:rf.error/missing-required-cofx` (the boot
         dispatch always supplies it). Tests / replay supply the value directly
         on the dispatch token — `{:rf.cofx {:todo.storage/todos (sorted-map …)}}`
         — never re-register a supplier."})
