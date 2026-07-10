(ns re-frame.machines.lifecycle-fx.spawn-error
  "Spawn `:on-error` routing — the child-failure → parent-transition wiring
  (XState v5 `invoke onError`).

  Per Spec 005 §Final states §`:on-error`, when a `:spawn`-spawned child
  FAILS, the runtime routes the failure to the spawning parent's
  `:spawn :on-error` TRANSITION (control flow — a declarative parent state
  change), symmetric with the `:spawn :on-done` teardown hook. Two triggers
  reach this namespace:

    1. the child reaches a designated ERROR `:final?` leaf (`:error? true`) —
       fired from `lifecycle-fx.finalize/finalize-machine`;
    2. an uncaught child action exception
       (`:rf.error/machine-action-exception`) — fired from
       `lifecycle-fx.registration`'s action-failure projection.

  Both route through `dispatch-spawn-error!`, which dispatches the reserved
  event `[<parent-id> [:rf.machine.spawn/error <invoke-id> <error>]]` into the
  parent. The parent's macrostep resolves it natively via
  `transition/pick-spawn-error-transition` (the `:on-error` transition is
  resolved at the `:spawn`-bearing state's level — a keyword target is a
  sibling), so the parent transition fires with full engine semantics (entry /
  exit cascade, `:always` / `:raise` drain, traces, and the parent's own
  finalize). Dispatched (not raised) because the parent is a SEPARATE actor —
  symmetric with how the spawn fx dispatches `:start` into the newborn child.

  This is ADDITIVE: the existing trace emission (observability — the
  `:rf.error/machine-action-exception` / `:rf.machine/done` traces) is
  unchanged, and the explicit dispatch-back-to-parent
  (`[:fx [[:dispatch [parent-id [:failed err]]]]]`) escape hatch keeps working.
  `:on-error` is the declarative invoke-site control-flow form; the escape
  hatch is the lower-level form.

  This namespace is a LEAF over the spawn-resolution helpers it needs
  (`resolver` + `paths` + `transition` + `late-bind`), so both `finalize` and
  `registration` may require it without a load cycle. The `:spawn`-at-invoke-id
  lookup lives in `resolver/spawn-spec-at` (the shared owner — rf2-wm92kj);
  `transition` is retained only for the reserved `spawn-error-event-id`."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.machines.lifecycle-fx.resolver :as resolver]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.transition :as transition]))

#?(:clj (set! *warn-on-reflection* true))

(defn- resolve-parent-spec
  "Resolve the spawning parent's machine spec from `parent-id` against `db`.
  A singleton parent has a registrar entry; a NESTED spawn whose parent is
  itself a spawned actor (no per-instance registration) is resolved from its
  own snapshot's `:rf/machine-type`. Mirrors the resolution
  `finalize-machine` does for `:on-done`."
  [db parent-id]
  (when parent-id
    (resolver/spec-from-id-or-snapshot
      parent-id
      (get-in db (paths/snapshot-path parent-id)))))

(defn parent-declares-on-error?
  "True iff the child identified by `parent-id` / `invoke-id` was spawned by a
  parent whose `:spawn` map at `invoke-id` declares `:on-error`. `db` is the
  frame's app-db (used to resolve a nested-spawn parent's spec). Returns false
  when the actor is a singleton (no `parent-id`), the parent spec doesn't
  resolve, or the `:spawn` map carries no `:on-error`."
  [db parent-id invoke-id]
  (boolean
    (when (and parent-id invoke-id)
      (some-> (resolve-parent-spec db parent-id)
              (resolver/spawn-spec-at invoke-id)
              :on-error
              some?))))

(defn dispatch-spawn-error!
  "Dispatch the reserved parent-failure event
  `[<parent-id> [:rf.machine.spawn/error <invoke-id> <error>]]` into the
  spawning parent so its macrostep fires the declarative `:spawn :on-error`
  transition. `error` is the failure payload that rides on the
  parent transition's `:event` (the child's `:output-key` slot for the
  error-leaf trigger, or the exception envelope for the action-exception
  trigger). No-op when the `:router/dispatch!` hook is absent (pure-fn /
  conformance callers). `:source :machine-spawn` labels the dispatch so the
  Epoch panel attributes it to the spawn lifecycle."
  [frame-id parent-id invoke-id error]
  (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
    (dispatch! [parent-id [transition/spawn-error-event-id invoke-id error]]
               {:frame frame-id :source :machine-spawn}))
  nil)
