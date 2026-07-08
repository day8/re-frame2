(ns re-frame.machines.paths
  "Runtime-db path constructors for the runtime-owned machines slots.

  The machines runtime keeps four sibling maps under the reserved
  `:rf.runtime/machines` child of each frame's **runtime-db** partition
  (Conventions §Reserved runtime-db keys — machine snapshots are durable
  framework state, so they live in the runtime-db partition):

    - `:snapshots`     — actor-id → snapshot `{:state :data :tags …}`
    - `:system-ids`    — system-id → actor-id reverse index
    - `:spawned`       — parent-id → invoke-id → join-state / spawned-id
    - `:spawn-counter` — machine-id → int (hand-emitted-spawn fallback)

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

  Scope is `src/` only. Test assertions read these slots off
  `(:rf.db/runtime (rf/frame-state-value frame-id))` (rf2-t3lftq —
  API-shrink #3 retired the dedicated `rf/runtime-db-value` reader).")

#?(:clj (set! *warn-on-reflection* true))

(defn snapshot-path
  "Path (in the runtime-db value) to the `:snapshots` slot, optionally
  drilling into a specific actor's snapshot and a key (or key pair) within
  it.

    (snapshot-path)                  => [:rf.runtime/machines :snapshots]
    (snapshot-path machine-id)       => [:rf.runtime/machines :snapshots machine-id]
    (snapshot-path machine-id k)     => [:rf.runtime/machines :snapshots machine-id k]
    (snapshot-path machine-id k k2)  => [:rf.runtime/machines :snapshots machine-id k k2]"
  ([]                [:rf.runtime/machines :snapshots])
  ([machine-id]      [:rf.runtime/machines :snapshots machine-id])
  ([machine-id k]    [:rf.runtime/machines :snapshots machine-id k])
  ([machine-id k k2] [:rf.runtime/machines :snapshots machine-id k k2]))

(defn system-id-path
  "Path (in the runtime-db value) to the `:system-ids` reverse-index slot,
  optionally drilling into a specific system-id's binding.

    (system-id-path)     => [:rf.runtime/machines :system-ids]
    (system-id-path sid) => [:rf.runtime/machines :system-ids sid]"
  ([]    [:rf.runtime/machines :system-ids])
  ([sid] [:rf.runtime/machines :system-ids sid]))

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
