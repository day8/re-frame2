(ns re-frame.machines.paths
  "App-db path constructors for the runtime-owned machines slots.

  The machines runtime keeps three sibling maps under
  `[:rf/runtime :machines …]` in each frame's app-db:

    - `:snapshots`  — actor-id → snapshot `{:state :data :tags …}`
    - `:system-ids` — system-id → actor-id reverse index
    - `:spawned`    — parent-id → invoke-id → join-state / spawned-id

  These constructors are the single source of truth for the literal
  path vectors that `src/` reads and writes (`get-in` / `update-in` /
  `assoc-in`). They keep the spelling in one place so a future restructure
  (eguy4-style) touches one namespace, and they let a call-site read as
  `(snapshot-path actor-id)` rather than a runtime-path-shaped vector —
  surfacing intent (\"this is the machines snapshot slot\") at the call.

  This namespace is a leaf: it requires nothing from the rest of the
  machines artefact, so any `re-frame.machines.*` namespace may require it
  without a cycle.

  Scope is `src/` only. Test assertions deliberately keep raw
  `(get-in db [:rf/runtime :machines …])` paths — those live outside the
  machines runtime and read more honestly as literal app-db probes.")

#?(:clj (set! *warn-on-reflection* true))

(defn snapshot-path
  "Path to the `:snapshots` slot, optionally drilling into a specific
  actor's snapshot and a key (or key pair) within it.

    (snapshot-path)                  => [:rf/runtime :machines :snapshots]
    (snapshot-path machine-id)       => [:rf/runtime :machines :snapshots machine-id]
    (snapshot-path machine-id k)     => [:rf/runtime :machines :snapshots machine-id k]
    (snapshot-path machine-id k k2)  => [:rf/runtime :machines :snapshots machine-id k k2]"
  ([]                [:rf/runtime :machines :snapshots])
  ([machine-id]      [:rf/runtime :machines :snapshots machine-id])
  ([machine-id k]    [:rf/runtime :machines :snapshots machine-id k])
  ([machine-id k k2] [:rf/runtime :machines :snapshots machine-id k k2]))

(defn system-id-path
  "Path to the `:system-ids` reverse-index slot, optionally drilling into
  a specific system-id's binding.

    (system-id-path)     => [:rf/runtime :machines :system-ids]
    (system-id-path sid) => [:rf/runtime :machines :system-ids sid]"
  ([]    [:rf/runtime :machines :system-ids])
  ([sid] [:rf/runtime :machines :system-ids sid]))

(defn spawned-path
  "Path to the `:spawned` slot, optionally drilling into a parent's
  per-invoke map and a specific invoke-id's join-state slot.

    (spawned-path)                   => [:rf/runtime :machines :spawned]
    (spawned-path parent-id)         => [:rf/runtime :machines :spawned parent-id]
    (spawned-path parent-id inv-id)  => [:rf/runtime :machines :spawned parent-id inv-id]"
  ([]                    [:rf/runtime :machines :spawned])
  ([parent-id]           [:rf/runtime :machines :spawned parent-id])
  ([parent-id invoke-id] [:rf/runtime :machines :spawned parent-id invoke-id]))
