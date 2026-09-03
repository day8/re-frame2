(ns re-frame.machines.paths
  "Runtime-db path constructors for the runtime-owned machines slots.

  The machines runtime keeps five sibling slots under the reserved
  `:rf.runtime/machines` child of each frame's **runtime-db** partition
  (Conventions §Reserved runtime-db keys — machine snapshots are durable
  framework state, so they live in the runtime-db partition):

    - `:snapshots`     — actor-id → snapshot `{:state :data :tags …}`
    - `:system-ids`    — system-id → actor-id reverse index
    - `:spawned`       — parent-id → invoke-id → join-state / spawned-id
    - `:spawn-counter` — machine-id → int (hand-emitted-spawn fallback)
    - `:spawn-order`   — vector of actor-id, **oldest → newest**

  `:spawn-order` is the durable TOTAL creation order over the frame's live
  spawned actors, and it is the ONLY authority for the reverse-creation
  disposal contract (Spec 005 §Cross-Spec Interactions §1). It exists
  because no other durable fact carries a frame-global sequence:
  `:spawn-counter` (and the parent-snapshot-resident `:rf/spawn-counter`
  the declarative path bumps) are **per-id-prefix** allocators, so the
  `#<n>` suffix of an actor-id sequences that prefix ALONE and cannot
  order two actors of different machine types — and an id supplied
  through `:fixed-actor-id` carries no suffix at all. Deriving disposal
  order from the id spelling is therefore reconstructing information the
  durable state does not contain; this slot records it instead (rf2-1vlyg).

  It is written by exactly two places, each inside the SAME runtime-db
  swap as the snapshot mutation it accompanies, so the order and the
  snapshots can never disagree: the spawn install
  (`lifecycle-fx.spawn/install-spawn!`) appends, and the unified teardown
  projection (`lifecycle-fx.teardown/teardown-actor`) removes. Because it
  rides the durable runtime-db value it survives every supported round
  trip — `replace-runtime-db!`, epoch restore, and the SSR machine-runtime
  projection (which re-projects `:snapshots` and carries its siblings
  verbatim) — without a restore callback. Allocated lazily and pruned when
  it empties, mirroring `:spawned`.

  The pure constructors/removers for the slot live in
  `re-frame.machines.spawn-order` alongside the transient process-side
  cache of the same order.

  These constructors are the single source of truth for the literal
  path vectors that `src/` reads and writes (`get-in` / `update-in` /
  `assoc-in`) AGAINST THE RUNTIME-DB VALUE. They keep the spelling in one
  place so a future restructure touches one namespace, and they let a
  call-site read as `(snapshot-path actor-id)` rather than a
  runtime-path-shaped vector — surfacing intent (\"this is the machines
  snapshot slot\") at the call.

  This namespace is a leaf: it requires nothing from the rest of the
  machines artefact, so any `re-frame.machines.*` namespace may require it
  without a cycle.

  Scope is `src/` only. Test assertions read these slots from the
  `:rf.db/runtime` partition of `rf/frame-state-value`.")

#?(:clj (set! *warn-on-reflection* true))

(defn snapshot-path
  "Path (in the runtime-db value) to the `:snapshots` slot, optionally
  drilling into a specific actor's snapshot and a key (or key pair) within
  it.

    (snapshot-path)                  => [:rf.runtime/machines :snapshots]
    (snapshot-path actor-id)                 => [:rf.runtime/machines :snapshots actor-id]
    (snapshot-path actor-id snapshot-key)    => [:rf.runtime/machines :snapshots actor-id snapshot-key]
    (snapshot-path actor-id snapshot-key nested-key)
      => [:rf.runtime/machines :snapshots actor-id snapshot-key nested-key]"
  ([]                                      [:rf.runtime/machines :snapshots])
  ([actor-id]                              [:rf.runtime/machines :snapshots actor-id])
  ([actor-id snapshot-key]                 [:rf.runtime/machines :snapshots actor-id snapshot-key])
  ([actor-id snapshot-key nested-key]      [:rf.runtime/machines :snapshots actor-id snapshot-key nested-key]))

(defn system-id-path
  "Path (in the runtime-db value) to the `:system-ids` reverse-index slot,
  optionally drilling into a specific system-id's binding.

    (system-id-path)           => [:rf.runtime/machines :system-ids]
    (system-id-path system-id) => [:rf.runtime/machines :system-ids system-id]"
  ([]          [:rf.runtime/machines :system-ids])
  ([system-id] [:rf.runtime/machines :system-ids system-id]))

(defn spawn-order-path
  "Path (in the runtime-db value) to the `:spawn-order` slot — the durable
  oldest-to-newest vector of live spawned actor-ids that carries the
  frame's TOTAL creation order (see ns docstring).

    (spawn-order-path) => [:rf.runtime/machines :spawn-order]"
  []
  [:rf.runtime/machines :spawn-order])

(defn spawned-path
  "Path (in the runtime-db value) to the `:spawned` slot, optionally
  drilling into a parent's per-invoke map and a specific invoke-id's
  join-state slot.

    (spawned-path)                   => [:rf.runtime/machines :spawned]
    (spawned-path parent-id)         => [:rf.runtime/machines :spawned parent-id]
    (spawned-path parent-id invoke-id) => [:rf.runtime/machines :spawned parent-id invoke-id]"
  ([]                    [:rf.runtime/machines :spawned])
  ([parent-id]           [:rf.runtime/machines :spawned parent-id])
  ([parent-id invoke-id] [:rf.runtime/machines :spawned parent-id invoke-id]))
