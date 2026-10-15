(ns re-frame.machines.spawn-order
  "Per-frame spawn order for reverse-creation disposal during frame
  destroy. Per Spec 005 §Cross-Spec Interactions §1 (Frame disposal with
  active machine instances): destroy must dispose live actors leaf-to-root
  in **reverse-creation order** — the most recently spawned instance
  disposes first.

  `[:rf.runtime/machines :snapshots]` snapshots live in runtime-db keyed
  by actor-id; the map iteration order is not insertion order, so an
  explicit order channel is required to satisfy the spec's
  reverse-creation invariant.

  That channel is the DURABLE vector at `[:rf.runtime/machines
  :spawn-order]` (`rf.machines.paths/spawn-order-path`; see that ns
  docstring for the slot's contract): an oldest-to-newest vector of live
  spawned actor-ids, written by `record-in-runtime-db` /
  `forget-in-runtime-db` below — each called from inside the SAME
  runtime-db swap as the snapshot install or teardown it accompanies, so
  order and snapshots cannot disagree. Because it rides the durable
  runtime-db value it survives `replace-runtime-db!`, epoch restore, and
  SSR hydration.

  There is no process-side copy of the order. A process-side copy would
  outlive any in-process runtime-state install that rewinds past a spawn
  (`restore-epoch!`, `replace-frame-state!`), naming an actor durable
  state discarded. Liveness is read off the runtime-db
  (`lifecycle-fx.destroy/actor-live?`), and the frame-destroy walk reads
  this vector."
  (:require [re-frame.machines.paths :as rf.machines.paths]))

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
  (or (get-in runtime-db (rf.machines.paths/spawn-order-path)) []))

(defn record-in-runtime-db
  "Append `actor-id` to the durable spawn-order vector in `runtime-db`,
  returning the updated runtime-db. Idempotent by value: an id already in
  the vector keeps its ORIGINAL position rather than being re-appended, so
  a re-install can never double-enter an actor and make frame destroy exit
  it twice."
  [runtime-db actor-id]
  (if (nil? actor-id)
    runtime-db
    (update-in runtime-db (rf.machines.paths/spawn-order-path)
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
                             (get-in runtime-db (rf.machines.paths/spawn-order-path)))]
      (if (empty? remaining)
        (update-in runtime-db [:rf.runtime/machines] dissoc :spawn-order)
        (assoc-in runtime-db (rf.machines.paths/spawn-order-path) remaining)))))
