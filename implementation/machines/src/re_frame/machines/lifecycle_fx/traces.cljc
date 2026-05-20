(ns re-frame.machines.lifecycle-fx.traces
  "Shared trace-emit helpers for the actor-destroy cascade.

  Per Spec 009 §`:op-type` vocabulary, `:rf.machine/destroyed` and
  `:rf.machine/system-id-released` ARE the contract surface that tools
  (Causa, story-mcp, re-frame-10x) key on for the actor-destroy signal.
  Independent emission sites = independent chances to drift in the
  argument-map keys; rf2-ur63f / round-2 audit Trace-r2-3 consolidates
  the three `:rf.machine/destroyed` sites and the two
  `:rf.machine/system-id-released` sites into a single home so a key
  rename is one-edit-touches-all.

  This namespace is the natural home (rather than `teardown.cljc`)
  because `teardown.cljc` is the unified pure app-db projection
  (rf2-lha2t) — it deliberately emits NO traces so the projection stays
  a value→value function. These helpers ARE side effects, so they live
  separately."
  (:require [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defn emit-destroyed!
  "Fire `:rf.machine/destroyed` against the canonical argument map.

  Per the rf2-vgkdt destroyed-trace-shape contract (assertion test in
  `re-frame.destroyed-trace-shape-test`), the permitted site-provided
  keys are `#{:frame :actor-id :system-id :parent-id :spawn-id
  :child-id :reason}`. Callers pass only the slots their site populates
  — `nil` values are stamped through, matching pre-consolidation
  behaviour (e.g. `destroy-single!` always stamps `:system-id`, even
  when nil; `destroy-invoke-all-children!` per-child fires omit
  `:system-id` by NOT passing the key).

  `reason` is the discriminator — `:explicit` for direct-destroy
  cascades, `:rf.machine/finished` for final-state auto-destroy."
  [{:keys [frame actor-id system-id parent-id child-id reason]
    invoke-id :spawn-id
    :or {reason :explicit}
    :as args}]
  (trace/emit! :machine :rf.machine/destroyed
               (cond-> {:frame    frame
                        :actor-id actor-id
                        :reason   reason}
                 (contains? args :system-id) (assoc :system-id system-id)
                 (contains? args :parent-id) (assoc :parent-id parent-id)
                 (contains? args :spawn-id) (assoc :spawn-id invoke-id)
                 (contains? args :child-id)  (assoc :child-id  child-id))))

(defn emit-system-id-released!
  "Per Spec 005 §Cancellation cascade D8 — fire
  `:rf.machine/system-id-released` when an actor's `:system-id` binding
  was released as part of teardown. No-op when `sid` is nil."
  [frame-id sid actor-id]
  (when sid
    (trace/emit! :machine :rf.machine/system-id-released
                 {:frame      frame-id
                  :system-id  sid
                  :machine-id actor-id})))
