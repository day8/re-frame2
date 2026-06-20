(ns re-frame.machines.lifecycle-fx.resolver
  "Spawned-actor SPEC resolution from runtime-db — the leaf helper
  beneath the lazy actor-handler resolver.

  Per Spec 005 §Spawning §Liveness is derived from runtime-db: a spawned
  actor has NO per-instance event-handler registration. Spawn is a pure
  runtime-db write (install the snapshot + the spawn-registry slot); destroy
  is a pure runtime-db remove. An actor's liveness IS exactly the presence
  of its snapshot at `[:rf.runtime/machines :snapshots <actor-id>]` in
  the frame's value — so `restore-epoch!` (which reverts the WHOLE
  frame-state, both the app-db AND runtime-db partitions, via
  `replace-frame-state!` against the epoch's `:frame-state-after`) reverts
  liveness perfectly, with ZERO registrar drift. Revertibility holds
  exactly: rewinding past a spawn leaves no orphaned handler, and rewinding
  past a destroy re-materialises a working handler from the restored
  snapshot.

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

(defn spec-from-registry
  "Resolve a registered machine's SPEC from `machine-id` via the `:event`
  registrar's `:rf/machine?` / `:rf/machine` stamp (Spec 005 §Querying
  machines). Returns the spec map, or nil when `machine-id` does not name
  a registered machine (a plain event handler, or no entry at all).

  This is the canonical one-liner over the
  `(let [m (registrar/lookup :event id)] (when (:rf/machine? m) (:rf/machine m)))`
  idiom that several lifecycle-fx + querying call sites repeat — the
  registered-TYPE leg of spawned-actor / parent / singleton spec
  resolution."
  [machine-id]
  (let [m (registrar/lookup :event machine-id)]
    (when (:rf/machine? m)
      (:rf/machine m))))

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
      (keyword? t) (spec-from-registry t))))

(defn spec-from-id-or-snapshot
  "Resolve a machine SPEC for `id` (a singleton's registered handler key,
  a spawned actor's instance address, or a spawning parent's id) preferring
  the registered TYPE, falling back to the `snapshot`'s `:rf/machine-type`:

    (or (spec-from-registry id) (spec-from-snapshot snapshot)).

  The two legs are mutually exclusive for any one id — a registered
  singleton has a registrar entry but no `:rf/machine-type` on
  its snapshot, while a spawned actor carries no per-instance registrar
  entry but stamps `:rf/machine-type` on its snapshot — so the `or` order
  is behaviourally irrelevant and either leg resolves at most one spec.
  Returns nil when neither resolves (the actor was already torn down, or
  the id names a non-machine event)."
  [id snapshot]
  (or (spec-from-registry id)
      (spec-from-snapshot snapshot)))

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
