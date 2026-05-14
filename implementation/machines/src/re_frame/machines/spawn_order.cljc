(ns re-frame.machines.spawn-order
  "Per-frame spawn-order tracking for reverse-creation disposal during
  frame destroy. Per Spec 005 §Cross-Spec Interactions §1 (Frame
  disposal with active machine instances): destroy must dispose live
  actors leaf-to-root in **reverse-creation order** — the most recently
  spawned instance disposes first.

  `:rf/machines` snapshots live in app-db keyed by actor-id; the map
  iteration order is not insertion order, so an explicit order channel
  is required to satisfy the spec's reverse-creation invariant.

  Shape: `{<frame-id> [<actor-id-1> <actor-id-2> ...]}` — a vector used
  as an append-only stack. Spawn appends; explicit destroy (single
  actor, `:invoke-all` per-child, final-state auto-destroy) removes;
  frame destroy walks the vector in reverse and clears the entry.

  Process-side (defonce atom) rather than app-db slot — the channel is
  pure runtime bookkeeping, has no observer contract, and never
  participates in revertibility (destroyed actors stay destroyed). The
  same pattern is used for the `:after` timer table in
  `re-frame.machines.timer/after-timers`.

  Per rf2-vsigt — the frame-destroy machine-cascade fix.")

#?(:clj (set! *warn-on-reflection* true))

(defonce
  ^{:doc "Runtime-owned per-frame spawn-order vectors. See ns docstring."}
  spawn-order
  (atom {}))

(defn record!
  "Append `actor-id` to `frame-id`'s spawn-order vector. Called by the
  spawn flow after a snapshot install succeeds."
  [frame-id actor-id]
  (when (and frame-id actor-id)
    (swap! spawn-order update frame-id (fnil conj []) actor-id))
  nil)

(defn forget!
  "Remove `actor-id` from `frame-id`'s spawn-order vector. Called by the
  single-actor destroy paths so the vector tracks only live actors. The
  vector entry is left in place even when emptied — a subsequent spawn
  refills it; only `clear-frame!` removes the frame-keyed slot."
  [frame-id actor-id]
  (when (and frame-id actor-id)
    (swap! spawn-order update frame-id
           (fn [v] (some->> v (filterv #(not= % actor-id))))))
  nil)

(defn frame-order
  "Return `frame-id`'s spawn-order vector (oldest → newest), or an empty
  vector when no spawns have been recorded."
  [frame-id]
  (or (get @spawn-order frame-id) []))

(defn clear-frame!
  "Drop the frame's spawn-order slot entirely. Called by the
  frame-destroy hook after the cascade walk completes."
  [frame-id]
  (when frame-id
    (swap! spawn-order dissoc frame-id))
  nil)

(defn reset-all!
  "Test-isolation helper: wipe every recorded spawn-order. Mirrors the
  shape of `re-frame.machines.timer/cancel-all-timers!` 0-arity."
  []
  (reset! spawn-order {})
  nil)
