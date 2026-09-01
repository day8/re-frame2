(ns re-frame.machines.spawn-order
  "Per-frame spawn-order tracking for reverse-creation disposal during
  frame destroy. Per Spec 005 §Cross-Spec Interactions §1 (Frame
  disposal with active machine instances): destroy must dispose live
  actors leaf-to-root in **reverse-creation order** — the most recently
  spawned instance disposes first.

  `[:rf.runtime/machines :snapshots]` snapshots live in runtime-db keyed
  by actor-id; the map iteration order is not insertion order, so an
  explicit order channel is required to satisfy the spec's
  reverse-creation invariant.

  ## Two channels, one authority

  **The DURABLE vector at `[:rf.runtime/machines :spawn-order]` is the
  source of truth** (`paths/spawn-order-path`; see that ns docstring for
  the slot's contract). It is an oldest-to-newest vector of live spawned
  actor-ids, written by `record-in-runtime-db` / `forget-in-runtime-db`
  below — each called from inside the SAME runtime-db swap as the
  snapshot install or teardown it accompanies, so order and snapshots
  cannot disagree. Because it rides the durable runtime-db value it
  survives `replace-runtime-db!`, epoch restore, and SSR hydration, which
  is precisely what the transient channel below does not.

  **The process-side `spawn-order` atom is a transient CACHE of the same
  order, never the authority** (rf2-1vlyg). It is runtime bookkeeping in
  the Spec 002 §Durable vs transient sense: it has no observer contract,
  is not serialised, and is empty after any state round trip. Destroy
  keeps consulting it as a liveness bit (`destroy/actor-live?` — it is
  set on spawn and cleared on destroy regardless of whether a runtime-db
  swap landed), but frame destroy takes its ORDER from the durable vector
  alone. Before rf2-1vlyg, frame destroy fell back to parsing the `#<n>`
  suffix of a restored actor-id when this atom was empty; that suffix is a
  per-id-prefix sequence and cannot order actors of different machine
  types, so the fallback emitted a confident wrong order. The durable
  vector replaced it.

  Shape (both channels): a vector used as an append-only stack, the
  transient one keyed by frame — `{<frame-id> [<actor-id-1> …]}`. Spawn
  appends; explicit destroy (single actor, `:spawn-all` per-child,
  final-state auto-destroy) removes; frame destroy walks in reverse and
  clears. The transient atom follows the same pattern as the `:after`
  timer table in `re-frame.machines.timer/after-timers`."
  (:require [re-frame.machines.paths :as paths]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- durable channel: [:rf.runtime/machines :spawn-order] ----------------
;;
;; PURE runtime-db → runtime-db functions. They are called from inside the
;; callers' own swap fns (`install-spawn!`'s `install-fn`,
;; `teardown/teardown-actor`), never as standalone writes, so the order
;; mutation and the snapshot mutation commit atomically together.

(defn durable-order
  "The frame's durable spawn-order vector, OLDEST → NEWEST, read out of
  `runtime-db`. Empty when no spawn has landed (the slot is allocated
  lazily) or when every spawned actor has been torn down (it is pruned
  when it empties)."
  [runtime-db]
  (or (get-in runtime-db (paths/spawn-order-path)) []))

(defn record-in-runtime-db
  "Append `actor-id` to the durable spawn-order vector in `runtime-db`,
  returning the updated runtime-db. Idempotent by value: an id already in
  the vector keeps its ORIGINAL position rather than being re-appended, so
  a re-install can never double-enter an actor and make frame destroy exit
  it twice."
  [runtime-db actor-id]
  (if (nil? actor-id)
    runtime-db
    (update-in runtime-db (paths/spawn-order-path)
               (fn [v]
                 (let [v (or v [])]
                   (if (some #(= actor-id %) v)
                     v
                     (conj v actor-id)))))))

(defn forget-in-runtime-db
  "Remove `actor-id` from the durable spawn-order vector in `runtime-db`,
  returning the updated runtime-db. Prunes the slot entirely once it
  empties — the lazy-allocation mirror `:spawned` follows, so a frame whose
  actors have all been destroyed carries no residue. Absent slot / absent
  id are clean no-ops (destroy is silent-idempotent)."
  [runtime-db actor-id]
  (if (or (nil? actor-id)
          (not (contains? (get runtime-db :rf.runtime/machines) :spawn-order)))
    runtime-db
    (let [remaining (filterv #(not= actor-id %)
                             (get-in runtime-db (paths/spawn-order-path)))]
      (if (empty? remaining)
        (update-in runtime-db [:rf.runtime/machines] dissoc :spawn-order)
        (assoc-in runtime-db (paths/spawn-order-path) remaining)))))

;; ---- transient channel: the process-side cache ---------------------------

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
  "Return `frame-id`'s TRANSIENT spawn-order vector (oldest → newest), or
  an empty vector when no spawns have been recorded in this process. This
  is the cache, not the authority — it is empty after any state round
  trip, so a caller that needs the frame's real creation order reads
  `durable-order` off the runtime-db instead."
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
