(ns re-frame.machines.lifecycle-fx.spawn-error
  "Spawn `:on-error` routing — the child-failure → parent-transition wiring
  (XState v5 `invoke onError`).

  Per Spec 005 §Final states §`:on-error`, when a `:spawn`-spawned child
  FAILS, the runtime routes the failure to the spawning parent's
  `:spawn :on-error` TRANSITION (control flow — a declarative parent state
  change), symmetric with the `:spawn :on-done` teardown hook. The failure
  event goes to the parent whether or not it declares `:on-error`:
  the parent's engine falls back to an explicit
  `:on {:rf.machine.spawn/error …}`, else ignores it. A failure therefore
  never reaches a success route. Two triggers reach this namespace:

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

  This routing sits alongside the trace emission (observability — the
  `:rf.error/machine-action-exception` / `:rf.machine/done` traces) and the
  explicit dispatch-back-to-parent
  (`[:fx [[:dispatch [parent-id [:failed err]]]]]`) escape hatch; it replaces
  neither. `:on-error` is the declarative invoke-site control-flow form; the
  escape hatch is the lower-level form.

  This namespace is a LEAF over the helpers it needs (`paths` + `transition`,
  plus core's `frame` / `fx` / `registrar`), so both `finalize` and
  `registration` may require it without a load cycle. That is also why it
  owns `dispatch-carrier!`, the queueing seam BOTH completion carriers share.
  It resolves no parent spec: whether the parent declares `:on-error` is the
  parent engine's question, answered when the carrier arrives. `transition`
  is required only for the reserved event ids."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.registrar :as rf.registrar]))

#?(:clj (set! *warn-on-reflection* true))

(defn parent-instance-live?
  "True iff `parent-id` names a parent with a LIVE INSTANCE in `db` — the ONE
  gate both failure producers consult before delivering into a parent.

  A DEFINITION-BEARING REGISTRAR ENTRY IS NOT LIVENESS. A destroyed
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
      counts as somebody home.

  This fences FRAMEWORK-OWNED delivery only. D5 applies to authored events:
  an ordinary AUTHORED event dispatched to an address that still carries a definition
  finds no snapshot and is answered by a fresh instance, exactly as before its
  first start."
  [db parent-id]
  (boolean
    (when parent-id
      (or (some? (get-in db (rf.machines.paths/snapshot-path parent-id)))
          (let [reg (rf.registrar/lookup :event parent-id)]
            (and (some? reg) (not (:rf/machine? reg))))))))

(defn dispatch-carrier!
  "Queue a completion carrier `event` into the spawning parent on `frame-id`.
  This is the ONE seam both carriers use: `[:rf.machine.spawn/done …]` from
  `finalize` and `[:rf.machine.spawn/error …]` from `dispatch-spawn-error!`.

  A carrier is minted while the child's handler is processing the
  event that FINISHED it, so it is a child of THAT event (Spec 002 §Run
  propagation). It queues through the reserved-dispatch seam
  `rf.fx/child-dispatch!` with the in-flight envelope core exposes to a handler
  body (`rf.frame/current-event-envelope`). The finishing event's per-call
  `:fx-overrides` / `:interceptor-overrides`, its `:trace-id` / `:origin`, and
  its per-call mint policy therefore reach the parent's continuation. A
  completion caused by a FRESH event, such as an `:after` wake-up, inherits
  nothing, because that event carried nothing.

  `:source :machine-spawn` is re-stamped, never inherited. `:rf.machine/internal?`
  is dropped so the carrier keeps its FIFO place. Outside a
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
  transition, or, with none declared, an explicit
  `:on {:rf.machine.spawn/error …}`. `error` is the failure payload that rides
  on the parent transition's `:event` (the child's `:output-key` slot for the
  error-leaf trigger, or the exception envelope for the action-exception
  trigger). Queued through `dispatch-carrier!`, so like `dispatch-spawn-done!`
  it inherits the finishing event's run propagation and keeps
  `:source :machine-spawn`. That makes it the declarative twin of the
  `[:fx [[:dispatch …]]]` escape hatch, which inherits the same way.

  `attempt` is the failing child's `:rf/invoke-attempt`. When
  present it rides as a FOURTH element, after the public `(nth ev 2)` error
  payload, so the parent's boundary can drop a carrier from a superseded or
  exited spawn attempt (`transition/spawn-carrier-stale-reason`)."
  ([frame-id parent-id invoke-id error]
   (dispatch-spawn-error! frame-id parent-id invoke-id error nil))
  ([frame-id parent-id invoke-id error attempt]
   (dispatch-carrier! frame-id
                      [parent-id (cond-> [rf.machines.transition/spawn-error-event-id invoke-id error]
                                   (some? attempt) (conj attempt))])))
