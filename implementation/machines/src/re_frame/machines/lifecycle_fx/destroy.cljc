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
  `:children` and tears each one down, then clears the slot.

  Per rf2-lha2t the actor-teardown app-db dance lives in
  `re-frame.machines.lifecycle-fx.teardown` — one helper, three (now
  unified) call-sites."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.lifecycle-fx.finalize :as finalize]
            [re-frame.machines.lifecycle-fx.teardown :as teardown]
            [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

(defn- emit-system-id-released!
  "Per Spec 005 §Cancellation cascade D8 — fire the
  `:rf.machine/system-id-released` trace when an actor's `:system-id`
  binding was released as part of teardown. No-op when sid is nil."
  [frame-id sid actor-id]
  (when sid
    (trace/emit! :machine :rf.machine/system-id-released
                 {:frame      frame-id
                  :system-id  sid
                  :machine-id actor-id})))

(defn- destroy-single-actor!
  "Destroy a single spawned actor against the frame's container: apply
  the unified teardown projection (per
  `re-frame.machines.lifecycle-fx.teardown`), abort in-flight
  `:rf.http/managed` requests (rf2-wvkn), emit the
  `:system-id-released` trace, and unregister the live event handler.

  Used by `destroy-machine-fx` for the keyword-form legacy/imperative
  destroy AND iterated for each child in an `:invoke-all` teardown."
  [frame-id actor-id]
  (when actor-id
    (finalize/abort-actor-in-flight-http! actor-id)
    ;; `teardown-actor` returns [new-db released-sid]; `swap-frame-db!`
    ;; expects a fn returning the new-db only. Capture sid via a side
    ;; channel so we keep a single read + single write.
    (let [sid (volatile! nil)]
      (when (frame/swap-frame-db! frame-id
                                  (fn [db]
                                    (let [[new-db released-sid]
                                          (teardown/teardown-actor db {:actor-id actor-id})]
                                      (vreset! sid released-sid)
                                      new-db)))
        (emit-system-id-released! frame-id @sid actor-id)
        (registrar/unregister! :event actor-id)
        @sid))))

(defn- destroy-invoke-all-children!
  "Per rf2-6vmw — the declarative-`:invoke-all` exit-cascade form.
  Resolves the children map from `[:rf/spawned parent-id invoke-id]`,
  tears each child down via `destroy-single-actor!`, then clears the
  join-state slot via the unified teardown projection (slot-prune only:
  nil actor-id)."
  [frame-id parent-id invoke-id]
  (let [join-state (get-in (frame/frame-app-db-value frame-id)
                           [:rf/spawned parent-id invoke-id])
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
    ;; Clear the join-state slot via the unified projection (slot-only).
    (frame/swap-frame-db! frame-id
                          (fn [db]
                            (first (teardown/teardown-actor
                                     db {:parent-id parent-id
                                         :invoke-id invoke-id}))))
    nil))

(defn- destroy-single!
  "Per rf2-t07u — the keyword (legacy/imperative) form and the single-
  `:invoke` (tracked map) form of `:rf.machine/destroy`. Resolves the
  actor-id (keyword direct OR via the `[:rf/spawned ...]` slot), emits
  the `:rf.machine/destroyed` trace, then applies the unified teardown
  projection."
  [frame-id args]
  (let [tracked?  (map? args)
        parent-id (when tracked? (:rf/parent-id args))
        invoke-id (when tracked? (:rf/invoke-id args))
        old-db    (frame/frame-app-db-value frame-id)
        actor-id  (if tracked?
                    (when old-db (get-in old-db [:rf/spawned parent-id invoke-id]))
                    args)
        released-sid (teardown/find-system-id-for-actor old-db actor-id)]
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
      (frame/swap-frame-db! frame-id
                            (fn [db]
                              (first (teardown/teardown-actor
                                       db {:actor-id  actor-id
                                           :parent-id parent-id
                                           :invoke-id invoke-id}))))
      (emit-system-id-released! frame-id released-sid actor-id)
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
