(ns re-frame.machines.lifecycle-fx.teardown
  "Unified app-db teardown projection for spawned-actor destruction.

  Per Spec 005 §Cancellation cascade — when a spawned actor is destroyed
  (final-state auto-destroy, exit-cascade declarative-`:spawn` destroy,
  iterated `:spawn-all` children-destroy, or keyword/imperative
  destroy) the runtime applies a four-step app-db projection:

    1. dissoc snapshot at `[:rf.runtime/machines :snapshots actor-id]`
    2. release `[:rf.runtime/machines :system-ids <sid>]` reverse-index
       entry (if bound)
    3. clear `[:rf.runtime/machines :spawned parent-id invoke-id]` slot
       (declarative form)
    4. prune the per-parent `[:rf.runtime/machines :spawned parent-id]`
       map and the `[:rf.runtime/machines :spawned]` slot if they just
       emptied (lazy-allocation invariant: spawn ALLOCATES the maps
       lazily, so destroy mirrors that by pruning when emptied — see
       Spec 005 §Spawning §Lazy allocation).

  The `[:rf.runtime/machines :snapshots]` and
  `[:rf.runtime/machines :system-ids]` maps are NOT pruned when
  emptied: per the `machine/destroy-machine-clears-system-id-index`
  conformance fixture the runtime keeps those slots present-but-empty
  so callers observing the machines container see a stable shape.

  The unification lives here so contract changes (e.g. an extra slot to
  prune) touch one spot — the three destroy paths (explicit, exit-
  cascade declarative, final-state auto-destroy) all route through this
  projection.

  This namespace is PURE — it does not unregister handlers, abort
  in-flight HTTP, or emit traces. Those are caller side effects whose
  ordering relative to db mutation is contract (Spec 005 §Cancellation
  cascade D6-D8: emit `:rf.machine/destroyed` BEFORE db mutation so
  in-flight trace consumers see the destroy signal while the handler
  still resolves; unregister the handler LAST)."
  (:require [re-frame.machines.paths :as paths]))

#?(:clj (set! *warn-on-reflection* true))

(defn find-system-id-for-actor
  "Walk the `[:rf.runtime/machines :system-ids]` reverse index of `db`
  looking for the entry whose value is `actor-id`. Returns the bound
  `:system-id` keyword or nil. Cheap O(n) over the reverse index; n is
  typically <10."
  [db actor-id]
  (when actor-id
    (some (fn [[sid mid]]
            (when (= mid actor-id) sid))
          (get-in db (paths/system-id-path)))))

(defn teardown-actor
  "Apply the unified app-db teardown projection to `db`. Returns a tuple
  `[new-db released-sid]` where `released-sid` is the `:system-id`
  keyword that was released (or nil — callers emit
  `:rf.machine/system-id-released` against it when non-nil).

  Args map:
    :actor-id   — the spawned actor's event-handler key (nil ⇒ no
                  snapshot or system-id-binding mutation, only
                  spawn-slot prune)
    :parent-id  — the spawning actor's id (declarative form only)
    :invoke-id  — the absolute invocation prefix-path the runtime stamped
                  on the child at spawn time (declarative form only)

  When `:parent-id` and `:invoke-id` are both supplied, the
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot is
  cleared and the parent map / `:spawned` root are pruned under the
  lazy-allocation invariant (matching how spawn ALLOCATES the maps
  lazily — see Spec 005 §Spawning §Lazy allocation). The
  `[:rf.runtime/machines :snapshots]` and
  `[:rf.runtime/machines :system-ids]` maps are NOT pruned when
  emptied (see ns docstring).

  PURE: no trace emission, no handler unregistration, no HTTP abort —
  those are caller side effects whose ordering relative to db mutation
  is contract."
  [db {:keys [actor-id parent-id invoke-id]}]
  (let [released-sid (find-system-id-for-actor db actor-id)
        track?       (and parent-id invoke-id)
        ;; (1)+(2)+(3): the three primary slot mutations.
        new-db       (cond-> db
                       actor-id     (update-in (paths/snapshot-path)
                                               dissoc actor-id)
                       released-sid (update-in (paths/system-id-path)
                                               dissoc released-sid)
                       track?       (update-in (paths/spawned-path parent-id)
                                                dissoc invoke-id))
        ;; (4a): prune the per-parent `:spawned` map if empty.
        new-db       (cond-> new-db
                       (and track?
                            (empty? (get-in new-db (paths/spawned-path parent-id))))
                       (update-in (paths/spawned-path) dissoc parent-id))
        ;; (4b): prune the `[:rf.runtime/machines :spawned]` slot if
        ;; empty (lazy-allocation mirror — :snapshots and :system-ids
        ;; stay present per fixture contract).
        new-db       (cond-> new-db
                       (and (contains? (get-in new-db [:rf.runtime/machines])
                                       :spawned)
                            (empty? (get-in new-db (paths/spawned-path))))
                       (update-in [:rf.runtime/machines] dissoc :spawned))]
    [new-db released-sid]))
