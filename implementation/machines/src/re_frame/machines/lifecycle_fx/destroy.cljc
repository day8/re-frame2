(ns re-frame.machines.lifecycle-fx.destroy
  "Destroy live-handler wiring: `:rf.machine/destroy` fx handler and its
  helpers.

  `apply-transition-once` emits `[:rf.machine/destroy actor-id]` into
  the fx vector whenever exit cascades cross an `:invoke`-bearing state.
  Per Spec 005 §Spawning, destroy unregisters the spawned actor's event
  handler, clears its snapshot at `[:rf/machines <id>]` in the spawning
  frame's app-db, and (if the actor was system-id-bound) clears the
  `[:rf/system-ids]` reverse index entry.

  Per rf2-t07u (Option A revised), `args` can be either:
    - a keyword `actor-id` — the legacy / imperative form (action emits
      `[:rf.machine/destroy actor-id]` directly with the recorded id), OR
    - a map `{:rf/parent-id ... :rf/invoke-id ...}` — the declarative-
      `:invoke` exit-cascade form, where the runtime resolves the actor
      id from `[:rf/spawned <parent-id> <invoke-id>]` in the frame's
      app-db.

  Per rf2-6vmw, the map form may also carry `:rf/invoke-all true` —
  the declarative-`:invoke-all` exit-cascade form. The slot at
  `[:rf/spawned <parent-id> <invoke-id>]` holds a join-state map whose
  `:children` sub-map has every spawned child id. The handler iterates
  `:children` and tears each one down, then clears the slot."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

(defn- find-system-id-for
  "Walk the `:rf/system-ids` reverse index of `db` looking for the entry
  whose value is `actor-id`. Returns the bound `:system-id` keyword or
  nil."
  [db actor-id]
  (some (fn [[sid mid]]
          (when (= mid actor-id) sid))
        (get db :rf/system-ids)))

(defn- destroy-single-actor!
  "Destroy a single spawned actor: dissoc the snapshot at
  `[:rf/machines <id>]`, clear any `:system-id` binding pointing at it,
  unregister the live event handler. Used by `destroy-machine-fx` for
  the keyword-form legacy/imperative destroy AND iterated for each
  child in an `:invoke-all` teardown.

  Per rf2-wvkn (Spec 005 §Cancellation cascade), also fires the
  `:http/abort-on-actor-destroy` late-bind hook so any in-flight
  `:rf.http/managed` requests the actor had issued are aborted."
  [frame-id actor-id]
  (when actor-id
    (finalize/abort-actor-in-flight-http! actor-id)
    (when-let [container (frame/get-frame-db frame-id)]
      (let [old-db       (adapter/read-container container)
            released-sid (find-system-id-for old-db actor-id)
            new-db       (cond-> old-db
                           true         (update :rf/machines dissoc actor-id)
                           released-sid (update :rf/system-ids dissoc released-sid))]
        (adapter/replace-container! container new-db)
        (when released-sid
          (trace/emit! :machine :rf.machine/system-id-released
                       {:frame      frame-id
                        :system-id  released-sid
                        :machine-id actor-id}))
        (registrar/unregister! :event actor-id)
        released-sid))))

(defn- destroy-invoke-all-children!
  "Per rf2-6vmw — the declarative-`:invoke-all` exit-cascade form.
  Resolves the children map from `[:rf/spawned parent-id invoke-id]`,
  tears each child down via `destroy-single-actor!`, then prunes the
  spawn registry slot under the lazy-allocation invariant."
  [frame-id parent-id invoke-id]
  (let [join-state (when-let [container (frame/get-frame-db frame-id)]
                     (get-in (adapter/read-container container)
                             [:rf/spawned parent-id invoke-id]))
        children   (when (map? join-state) (:children join-state))]
    (doseq [[child-id spawned-id] children]
      (trace/emit! :machine :rf.machine/destroyed
                   {:frame      frame-id
                    :actor-id   spawned-id
                    :parent-id  parent-id
                    :invoke-id  invoke-id
                    :child-id   child-id
                    :reason     :explicit})        ;; rf2-gn80 D6 — discriminator
      (destroy-single-actor! frame-id spawned-id))
    ;; Clear the slot + lazy-allocation prune.
    (when-let [container (frame/get-frame-db frame-id)]
      (let [old-db (adapter/read-container container)
            new-db (cond-> old-db
                     true (update-in [:rf/spawned parent-id]
                                     dissoc invoke-id)
                     (empty? (get-in old-db [:rf/spawned parent-id]))
                     (update :rf/spawned dissoc parent-id))
            new-db (cond-> new-db
                     (empty? (get new-db :rf/spawned))
                     (dissoc :rf/spawned))]
        (adapter/replace-container! container new-db)))
    nil))

(defn- destroy-single!
  "Per rf2-t07u — the keyword (legacy/imperative) form and the single-
  `:invoke` (tracked map) form of `:rf.machine/destroy`. Resolves the
  actor-id (keyword direct OR via the `[:rf/spawned ...]` slot), tears
  down its snapshot + system-id binding + handler, prunes the spawn
  registry under the lazy-allocation invariant."
  [frame-id args]
  (let [tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        actor-id  (if tracked?
                    (when-let [container (frame/get-frame-db frame-id)]
                      (get-in (adapter/read-container container)
                              [:rf/spawned parent-id invoke-id]))
                    args)
        released-sid
        (when (and actor-id (frame/get-frame-db frame-id))
          (find-system-id-for
            (adapter/read-container (frame/get-frame-db frame-id))
            actor-id))]
    (trace/emit! :machine :rf.machine/destroyed
                 {:frame      frame-id
                  :actor-id   actor-id
                  :system-id  released-sid
                  :parent-id  parent-id
                  :invoke-id  invoke-id
                  :reason     :explicit})              ;; rf2-gn80 D6 — discriminator
    ;; Tracked-form destroy with no resolved actor-id is a benign no-op:
    ;; the spawn slot was already cleared (e.g. by an earlier explicit
    ;; destroy) or the spawn was suppressed (SSR / platform gating).
    (when actor-id
      (finalize/abort-actor-in-flight-http! actor-id)
      (when-let [container (frame/get-frame-db frame-id)]
        (let [old-db (adapter/read-container container)
              new-db (cond-> old-db
                       true          (update :rf/machines dissoc actor-id)
                       released-sid  (update :rf/system-ids dissoc released-sid)
                       tracked?      (update-in [:rf/spawned parent-id]
                                                dissoc invoke-id))
              ;; Tidy up the per-parent map if it just emptied — same
              ;; lazy-allocation invariant as :rf/system-ids.
              new-db (cond-> new-db
                       (and tracked?
                            (empty? (get-in new-db [:rf/spawned parent-id])))
                       (update :rf/spawned dissoc parent-id))
              new-db (cond-> new-db
                       (and tracked? (empty? (get new-db :rf/spawned)))
                       (dissoc :rf/spawned))]
          (adapter/replace-container! container new-db)))
      (when released-sid
        (trace/emit! :machine :rf.machine/system-id-released
                     {:frame      frame-id
                      :system-id  released-sid
                      :machine-id actor-id}))
      ;; Unregister the live handler. Last so any in-flight trace emit
      ;; against the actor still resolves before the slot disappears.
      (registrar/unregister! :event actor-id))
    nil))

(defn destroy-machine-fx
  "fx handler for `:rf.machine/destroy`. Dispatches to the keyword-form
  / single-`:invoke` teardown (`destroy-single!`) or the
  `:invoke-all` children-iteration teardown
  (`destroy-invoke-all-children!`) per the `args` shape. See the ns
  docstring for the form semantics."
  [{:keys [frame]} args]
  (let [frame-id    (or frame :rf/default)
        invoke-all? (and (map? args) (true? (:rf/invoke-all args)))]
    (if invoke-all?
      (destroy-invoke-all-children! frame-id
                                    (:rf/parent-id args)
                                    (:rf/invoke-id args))
      (destroy-single! frame-id args))))
