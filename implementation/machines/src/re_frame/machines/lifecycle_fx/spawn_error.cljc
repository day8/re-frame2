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
  `rf.machines.transition/pick-spawn-error-transition` (the `:on-error` transition is
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
  (`resolver` + `paths` + `transition`, plus core's `frame` / `fx`), so both
  `finalize` and `registration` may require it without a load cycle. That is
  also why it owns `dispatch-carrier!`, the queueing seam BOTH completion
  carriers share. The `:spawn`-at-invoke-id lookup lives in
  `rf.machines.lifecycle-fx.resolver/spawn-spec-at`; `transition` is retained
  only for the reserved `spawn-error-event-id`."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn- resolve-parent-spec
  "Resolve the spawning parent's machine spec from `parent-id` against `db`.
  A singleton parent has a registrar entry; a NESTED spawn whose parent is
  itself a spawned actor (no per-instance registration) is resolved from its
  own snapshot's `:rf/machine-type`. Mirrors the resolution
  `finalize-machine` does for `:on-done`."
  [db parent-id]
  (when parent-id
    (rf.machines.lifecycle-fx.resolver/spec-from-id-or-snapshot
      parent-id
      (get-in db (rf.machines.paths/snapshot-path parent-id)))))

(defn parent-instance-live?
  "True iff `parent-id` names a parent with a LIVE INSTANCE in `db` — the ONE
  gate both failure producers consult before delivering into a parent.

  rf2-xjee — A DEFINITION-BEARING REGISTRAR ENTRY IS NOT LIVENESS. A destroyed
  singleton parent keeps its `reg-machine` DEFINITION (the registration is the
  load-time PROGRAM; the snapshot was the INSTANCE — Spec 005 §Liveness is
  derived from runtime-db, D4/D7), so the parent's spec, its `:spawn` map and
  its `:on-error` all still RESOLVE after the parent is gone. Resolving them is
  what the definition is FOR; answering \"is there anyone home?\" with it is
  not. Read that way, framework-owned failure delivery would dispatch the
  reserved `[:rf.machine.spawn/error …]` carrier at the dead address, D5 lazy
  re-creation would synthesise a fresh initial snapshot, and the runtime would
  RESURRECT an actor the app destroyed in order to hand it a dead child's
  failure — which Spec 005 §Async completions §Stale suppression forbids (the
  `:on-done` / `:on-error` routing MUST NOT run for a late completion whose
  spawn correlation no longer names a live actor) and §Destroy is
  silent-idempotent forbids reading as liveness in the first place.

  The two signals, exactly as `destroy/actor-live?` reads them:

    - a SNAPSHOT at `[:rf.runtime/machines :snapshots <parent-id>]` — the
      canonical instance signal for singleton and nested-spawn parents alike;
    - a NON-MACHINE registrar entry squatting at the parent's address, which
      still counts as somebody home, exactly as before.

  This fences FRAMEWORK-OWNED delivery only. D5 is untouched: an ordinary
  AUTHORED event dispatched to an address that still carries a definition
  finds no snapshot and is answered by a fresh instance, exactly as before its
  first start."
  [db parent-id]
  (boolean
    (when parent-id
      (or (some? (get-in db (rf.machines.paths/snapshot-path parent-id)))
          (let [reg (rf.registrar/lookup :event parent-id)]
            (and (some? reg) (not (:rf/machine? reg))))))))

(defn parent-declares-on-error?
  "True iff the child identified by `parent-id` / `invoke-id` was spawned by a
  parent whose `:spawn` map at `invoke-id` declares `:on-error`. `db` is the
  frame's runtime-db (used to resolve a nested-spawn parent's spec). Returns false
  when the actor is a singleton (no `parent-id`), the parent spec doesn't
  resolve, or the `:spawn` map carries no `:on-error`."
  [db parent-id invoke-id]
  (boolean
    (when (and parent-id invoke-id)
      (some-> (resolve-parent-spec db parent-id)
              (rf.machines.lifecycle-fx.resolver/spawn-spec-at invoke-id)
              :on-error
              some?))))

(defn dispatch-carrier!
  "Queue a completion carrier `event` into the spawning parent on `frame-id`.
  This is the ONE seam both carriers use: `[:rf.machine.spawn/done …]` from
  `finalize` and `[:rf.machine.spawn/error …]` from `dispatch-spawn-error!`.

  rf2-ix8fd — a carrier is minted while the child's handler is processing the
  event that FINISHED it, so it is a child of THAT event (Spec 002 §Run
  propagation). It queues through the reserved-dispatch seam
  `rf.fx/child-dispatch!` with the in-flight envelope core exposes to a handler
  body (`rf.frame/current-event-envelope`). The finishing event's per-call
  `:fx-overrides` / `:interceptor-overrides`, its `:trace-id` / `:origin`, and
  its per-call mint policy therefore reach the parent's continuation. A
  completion caused by a FRESH event, such as an `:after` wake-up, inherits
  nothing, because that event carried nothing.

  `:source :machine-spawn` is re-stamped, never inherited. `:rf.machine/internal?`
  is dropped so the carrier keeps its FIFO place, as it always has. Outside a
  router pipeline (pure-fn / conformance callers) the envelope is nil and
  `child-dispatch!` falls back to `{:frame frame-id}`; it no-ops when the
  `:router/dispatch!` hook is absent."
  [frame-id event]
  (rf.fx/child-dispatch! frame-id
                         (some-> (rf.frame/current-event-envelope frame-id)
                                 (dissoc :rf.machine/internal?))
                         event
                         {:source :machine-spawn})
  nil)

(defn dispatch-spawn-error!
  "Dispatch the reserved parent-failure event
  `[<parent-id> [:rf.machine.spawn/error <invoke-id> <error>]]` into the
  spawning parent so its macrostep fires the declarative `:spawn :on-error`
  transition. `error` is the failure payload that rides on the
  parent transition's `:event` (the child's `:output-key` slot for the
  error-leaf trigger, or the exception envelope for the action-exception
  trigger). Queued through `dispatch-carrier!`, so like `dispatch-spawn-done!`
  it inherits the finishing event's run propagation and keeps
  `:source :machine-spawn`. That makes it the declarative twin of the
  `[:fx [[:dispatch …]]]` escape hatch, which inherits the same way."
  [frame-id parent-id invoke-id error]
  (dispatch-carrier! frame-id
                     [parent-id [rf.machines.transition/spawn-error-event-id invoke-id error]]))
