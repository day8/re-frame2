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
;;
;; EP-0010 (causal world inputs, rf2-lk86xl): the saved todos are a STORAGE
;; world fact, and `:todo/initialise` folds them into durable app-db. A durable
;; write must be a function of prior frame-state plus the causal token — not of
;; an ambient `localStorage` read at the write site (which replay/restore could
;; not reproduce). So the host-boundary boot read happens once, in
;; `todomvc.core/run`, and rides the boot dispatch token as the flat recordable
;; coeffect `:rf.cofx {:todo.storage/todos …}`.
;;
;; This cofx is the RECORDABLE migration target the EP blesses (the `:app/now-ms`
;; pattern, EP-0010 §Backwards Compatibility): it reads the captured storage
;; value off `:rf.cofx` and returns it exactly under replay / restore /
;; test fixtures. It falls back to a live host read ONLY when no storage was
;; supplied on the token — i.e. when building a fresh live token at the boundary
;; where reading the host IS correct. Either way the value is always coerced to a
;; `(sorted-map)`: a nil (empty / absent localStorage, first run) would otherwise
;; clobber default-db's `(sorted-map)` and break allocate-next-id's
;; `(last (keys todos))` = max-id invariant once the map promotes to an unordered
;; PersistentHashMap (>8 entries).
(defn read-todos-from-storage
  "Host-boundary read of the saved TodoMVC items from localStorage, normalised
   to a sorted-map. Called from `todomvc.core/run` so the value rides the boot
   dispatch token as a causal world input rather than being read ambiently in a
   durable handler. nil/empty localStorage (first run, or node with no
   localStorage) yields an empty sorted-map."
  []
  (or (some-> (.-localStorage js/globalThis)
              (.getItem ls-key)
              (storage->todos))
      (sorted-map)))

(rf/reg-cofx :todo.storage/todos
  {:recordable? true
   :provided?   true
   :doc "PROVIDED recordable coeffect (EP-0017 §2): the saved TodoMVC items,
         stamped onto the boot dispatch token by `todomvc.core/run`
         (`{:rf.cofx {:todo.storage/todos (read-todos-from-storage)}}`). The
         host read happens ONCE, at the boundary, and rides the token as a
         recordable fact — replay / restore / test fixtures supply the exact
         recorded value, no host re-read. A handler that folds it into durable
         app-db declares `:rf.cofx/requires [:todo.storage/todos]` and reads it
         flat. The boundary read (`read-todos-from-storage`) always yields a
         sorted-map (never nil) so the allocate-next-id invariant holds; the
         value is delivered verbatim from the token. There is no generator —
         this is a provided fact, so absence from the token is
         `:rf.error/missing-required-cofx`."})
