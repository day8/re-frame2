(ns re-frame.machines.lifecycle-fx.resolver
  "Spawned-actor SPEC resolution from app-db (rf2-a2sn1) — the leaf helper
  beneath the lazy actor-handler resolver.

  Per Spec 005 §Spawning §Liveness is derived from app-db: a spawned
  actor has NO per-instance event-handler registration. Spawn is a pure
  app-db write (install the snapshot + the spawn-registry slot); destroy
  is a pure app-db remove. An actor's liveness IS exactly the presence
  of its snapshot at `[:rf/runtime :machines :snapshots <actor-id>]` in
  the frame's value — so `restore-epoch` (which reverts app-db only)
  reverts liveness perfectly, with ZERO registrar drift. This closes the
  Goal-2 revertibility leak: rewinding past a spawn no longer leaves an
  orphaned handler, and rewinding past a destroy re-materialises a
  working handler from the restored snapshot.

  The actor's TYPE rides the snapshot under the reserved root key
  `:rf/machine-type` (per Spec 005 §Reserved snapshot-internal keys):

    - a `:machine-id` spawn stores the registered TYPE keyword — the type
      is registered like a singleton (`reg-machine`) and outlives every
      instance, so the resolver reads the live spec back from the
      registrar;
    - an inline `:definition` spawn stores the spec map directly on the
      snapshot — there is no registered type, so the snapshot is the only
      source of truth (and it is fully revertible).

  This namespace is a LEAF — it requires only `registrar` + `paths`, so
  the destroy-path consumers (`exit-cascade`, `finalize`) may require it
  without a load cycle through `registration`. The handler-MATERIALISING
  side of the resolver (which needs `make-machine-handler`) lives in
  `lifecycle-fx.registration/resolve-actor-handler-meta`, the late-bound
  `:machines/resolve-actor-handler-meta` hook body."
  (:require [re-frame.machines.paths :as paths]
            [re-frame.registrar :as registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn spec-from-snapshot
  "Resolve the machine SPEC for a spawned actor from its `snapshot`'s
  `:rf/machine-type` reserved slot (per Spec 005 §Reserved
  snapshot-internal keys), or nil.

    - keyword type → read the registered TYPE's spec back from the
      registrar's `:rf/machine` metadata (the type is registered like a
      singleton and outlives instances);
    - map type     → an inline-`:definition` spawn carried its spec on
      the snapshot; return it verbatim.

  Returns nil when the snapshot carries no `:rf/machine-type` (a
  singleton snapshot, or a pre-resolver snapshot) or when a keyword type
  no longer names a registered machine (the type was cleared — a genuine
  missing reference)."
  [snapshot]
  (let [t (:rf/machine-type snapshot)]
    (cond
      (map? t)     t
      (keyword? t) (let [m (registrar/lookup :event t)]
                     (when (:rf/machine? m)
                       (:rf/machine m))))))

(defn resolvable?
  "True iff the actor identified by `actor-id` in `db` resolves to a live
  machine spec via its snapshot's `:rf/machine-type` — i.e. its liveness
  can be re-materialised purely from `db`. Used by the epoch restore
  precondition check (`:rf.epoch/restore-missing-handler`): a spawned
  actor whose TYPE is still registered (or whose snapshot carries an
  inline `:definition`) is a VALID restore target even though no
  per-instance handler is registered. Returns false when the actor has
  no snapshot, or its snapshot carries no resolvable `:rf/machine-type`."
  [db actor-id]
  (boolean
    (some-> (get-in db (paths/snapshot-path actor-id))
            (spec-from-snapshot))))
