(ns re-frame.machines.lifecycle-fx.teardown
  "Unified runtime-db teardown projection for spawned-actor destruction.

  Per Spec 005 §Cancellation cascade — when a spawned actor is destroyed
  (final-state auto-destroy, exit-cascade declarative-`:spawn` destroy,
  iterated `:spawn-all` children-destroy, or keyword/imperative
  destroy) the runtime applies a four-step runtime-db projection:

    1. dissoc snapshot at `[:rf.runtime/machines :snapshots actor-id]`
    1b. drop the actor from the durable spawn-order vector at
       `[:rf.runtime/machines :spawn-order]` — the frame's recorded
       total creation order, pruned when it empties. This
       is the teardown half of the append `install-spawn!` makes; both
       ride the same swap as the snapshot mutation, so the order can
       never name a dead actor or omit a live one.
    2. clear `[:rf.runtime/machines :spawned parent-id invoke-id]` slot
       (declarative form)
    3. clear the PARENT snapshot's own mirror entry at
       `[:rf.runtime/machines :snapshots parent-id :rf/runtime :spawned
       invoke-id]` (declarative form; `paths/spawned-mirror-path`) so the
       parent-side mirror
       (mechanism 1 — XState-context parity) matches the runtime registry
       (step 2) EXACTLY, per Spec 005 §Reserved snapshot-internal keys.
       Without this a callback reading `:spawned` AFTER the
       child completed would get a DEAD id — the stale-id footgun.
    4. prune the per-parent `[:rf.runtime/machines :spawned parent-id]`
       map, the `[:rf.runtime/machines :spawned]` slot, and the emptied
       parent-side `:spawned` mirror (and then `:rf/runtime`) if they just emptied
       (lazy-allocation invariant: spawn ALLOCATES the maps lazily, so
       destroy mirrors that by pruning when emptied — see Spec 005
       §Spawning §Lazy allocation).

  The `[:rf.runtime/machines :snapshots]` map is NOT pruned when
  emptied: the runtime keeps that slot present-but-empty so callers
  observing the machines container see a stable shape.

  The unification lives here so contract changes (e.g. an extra slot to
  prune) touch one spot — the three destroy paths (explicit, exit-
  cascade declarative, final-state auto-destroy) all route through this
  projection.

  This namespace is PURE — it does not unregister handlers, abort
  in-flight HTTP, or emit traces. Those are caller side effects whose
  ordering relative to db mutation is contract (Spec 005 §Cancellation
  cascade D6-D8). The `spawn-order` fns it reaches for step 1b are
  likewise pure runtime-db → runtime-db."
  (:require [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]))

#?(:clj (set! *warn-on-reflection* true))

(defn teardown-actor
  "Apply the unified runtime-db teardown projection to `db`. Returns the
  new runtime-db.

  Args map:
    :actor-id   — the spawned actor's event-handler key (nil ⇒ no
                  snapshot mutation, only spawn-slot prune)
    :parent-id  — the spawning actor's id (declarative form only)
    :invoke-id  — the absolute invocation prefix-path the runtime stamped
                  on the child at spawn time (declarative form only)

  When `:parent-id` and `:invoke-id` are both supplied, the
  `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]` slot is
  cleared AND the parent snapshot's own mirror entry at
  `[:rf.runtime/machines :snapshots <parent-id> :rf/runtime :spawned
  <invoke-id>]` (`paths/spawned-mirror-path`) is cleared, so the parent-side
  mirror matches the
  runtime registry EXACTLY (Spec 005 §Reserved snapshot-internal keys). Both the parent map /
  `:spawned` root AND the emptied parent-side `:spawned` mirror are
  pruned under the lazy-allocation invariant (matching how spawn
  ALLOCATES the maps lazily — see Spec 005 §Spawning §Lazy allocation).
  The `[:rf.runtime/machines :snapshots]` map is NOT pruned when
  emptied (see ns docstring).

  PURE: no trace emission, no handler unregistration, no HTTP abort —
  those are caller side effects whose ordering relative to db mutation
  is contract."
  [db {:keys [actor-id parent-id invoke-id]}]
  (let [track?       (and parent-id invoke-id)
        ;; The parent's own mirror entry is cleared (step 3) ONLY when the
        ;; parent snapshot is still present. A parent already torn down (its
        ;; snapshot dissoc'd) has nothing to mirror, and touching it would
        ;; materialise a phantom snapshot on a dead parent — so guard the
        ;; parent-side clear on presence.
        clear-mirror? (and track?
                           (contains? (get-in db (rf.machines.paths/snapshot-path))
                                      parent-id))
        ;; (1)+(2)+(3): the primary slot mutations. (3) clears the PARENT
        ;; snapshot's own `[:rf/runtime :spawned <invoke-id>]` entry so the
        ;; parent-side mirror (mechanism 1) matches the runtime registry
        ;; (step 2) EXACTLY, pruning the emptied `:spawned` map and then an
        ;; emptied `:rf/runtime` (lazy allocation) — see ns docstring.
        new-db       (cond-> db
                       actor-id     (update-in (rf.machines.paths/snapshot-path)
                                               dissoc actor-id)
                       ;; (1b) drop the actor from the DURABLE
                       ;; spawn-order vector in the same swap that dissocs its
                       ;; snapshot, so the recorded creation order tracks the
                       ;; LIVE actors exactly. Every destroy path routes through
                       ;; this projection (explicit / tracked / exit-cascade /
                       ;; `:final?` auto-destroy), so one removal site covers
                       ;; them all: a destroyed actor can neither be exited a
                       ;; second time by a later frame destroy nor accumulate as
                       ;; an unbounded stale entry. The slot is pruned when it
                       ;; empties (`forget-in-runtime-db`), mirroring `:spawned`.
                       actor-id     (rf.machines.spawn-order/forget-in-runtime-db actor-id)
                       track?       (update-in (rf.machines.paths/spawned-path parent-id)
                                                dissoc invoke-id)
                       clear-mirror?
                       (update-in (rf.machines.paths/snapshot-path parent-id)
                                  rf.machines.paths/prune-runtime-in :spawned invoke-id))
        ;; (4a): prune the per-parent `:spawned` map if empty.
        new-db       (cond-> new-db
                       (and track?
                            (empty? (get-in new-db (rf.machines.paths/spawned-path parent-id))))
                       (update-in (rf.machines.paths/spawned-path) dissoc parent-id))
        ;; (4b): prune the `[:rf.runtime/machines :spawned]` slot if
        ;; empty (lazy-allocation mirror — :snapshots stays present per
        ;; the stable-shape contract).
        new-db       (cond-> new-db
                       (and (contains? (get-in new-db [:rf.runtime/machines])
                                       :spawned)
                            (empty? (get-in new-db (rf.machines.paths/spawned-path))))
                       (update-in [:rf.runtime/machines] dissoc :spawned))]
    new-db))
