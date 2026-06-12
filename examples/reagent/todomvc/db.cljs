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
;; `todomvc.core/run`, and rides the boot dispatch token as
;; `:rf.world/inputs {:storage {:todos …}}`.
;;
;; This cofx is the RECORDABLE migration target the EP blesses (the `:app/now-ms`
;; pattern, EP-0010 §Backwards Compatibility): it reads the captured storage
;; value off `:rf.world/inputs` and returns it exactly under replay / restore /
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
  {:doc "Inject the saved TodoMVC items into coeffects as a sorted-map.

         EP-0010 recordable storage coeffect: returns the captured
         `(get-in cofx [:rf.world/inputs :storage :todos])` value when the boot
         token supplied one (replay / restore / test fixtures get the exact
         recorded value, no host re-read); falls back to a live
         `read-todos-from-storage` host read only when none was supplied. Always
         a sorted-map (never nil) so the allocate-next-id invariant holds."}
  (fn cofx-todo-storage-todos [ctx]
    (let [recorded (get-in ctx [:coeffects :rf.world/inputs :storage :todos])
          todos    (cond
                     (map? recorded) (into (sorted-map) recorded)
                     (some? recorded) (sorted-map)
                     :else (read-todos-from-storage))]
      (assoc-in ctx [:coeffects :todo.storage/todos] todos))))
