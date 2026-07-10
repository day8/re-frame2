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

  This namespace is a LEAF over the machine-core grammar — it requires
  `registrar` + `paths` plus the pure `grammar` (state-tree descent) and
  `parallel` (the `:type :parallel` predicate) for `spawn-spec-at`. None of
  those require any `lifecycle-fx` namespace (`grammar` is require-free;
  `parallel` requires only engine-core `choice` / `result` / `timeout` /
  `transition` / `trace`), so the destroy-path consumers (`exit-cascade`,
  `finalize`, `spawn-error`) may require it without a load cycle through
  `registration`. The handler-MATERIALISING side of the resolver (which needs
  `make-machine-handler`) lives in
  `lifecycle-fx.registration/resolve-actor-handler-meta`, the late-bound
  `:machines/resolve-actor-handler-meta` hook body."
  (:require [re-frame.machines.grammar :as grammar]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.paths :as paths]
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

(defn spawn-spec-at
  "Walk `parent-spec`'s state tree to the `:spawn`-bearing node at `invoke-id`
  (the absolute prefix-path stamped at spawn time) and return that node's
  `:spawn` map, resolving flat AND region-prefixed invoke paths through
  `grammar/node-at`. For a parallel-region parent the first element of
  `invoke-id` is the region name; strip it and descend into that region's
  body. Returns nil if `parent-spec` is absent, `invoke-id` is not a
  non-empty vector, the path doesn't resolve, or the node declares no
  `:spawn`.

  The single owner for the spawn-spec-at-invoke-id lookup shared by the
  finalize `:on-done` cascade (`finalize/finalize-machine`) and the
  spawn-error `:on-error` routing (`spawn-error/parent-declares-on-error?`) —
  the two carried byte-for-byte copies before this was hoisted here
  (rf2-wm92kj), so a fix to the flat / region resolution had to land twice."
  [parent-spec invoke-id]
  (when (and parent-spec (vector? invoke-id) (seq invoke-id))
    (let [[head & tail] invoke-id
          [tree path]   (if (and (parallel/parallel? parent-spec)
                                 (contains? (:regions parent-spec) head))
                          [(get-in parent-spec [:regions head]) (vec tail)]
                          [parent-spec invoke-id])]
      (:spawn (grammar/node-at (:states tree) path)))))
