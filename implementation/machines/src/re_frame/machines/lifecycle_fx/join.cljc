(ns re-frame.machines.lifecycle-fx.join
  "`:spawn-all` join folding — the PARENT half of the child-completion
  protocol.

  Per Spec 005 §Spawn-and-join via `:spawn-all` §Child completion protocol, a
  join child completes the same way EVERY machine completes: it enters a
  `:final?` state, `:output-key` selects the result, and `:error? true` marks
  the terminal a failure. The child dispatches nothing and carries no parent
  vocabulary, so ONE child machine composes unchanged under a `:spawn` parent
  and under a `:spawn-all` parent.

  `lifecycle-fx.finalize` mints the reserved completion carrier

      [<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]

  at the child's finality, reading the exact-attempt coordinate straight out
  of the child's runtime-stamped `[:rf/runtime :join-child]` membership record. Because
  the RUNTIME both mints and consumes that carrier, the coordinate never has
  to survive an application-authored event: there is no transport fx, no
  recordable cofx fact, and no completion vocabulary on the child. The
  parent's `make-machine-handler` boundary routes the carrier here, and the
  fold:

   1. Reads the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`.
      The completion names its own `<invoke-id>`, so routing is a LOOKUP —
      no state-tree walk, no event-keyword matching, and no ambiguity when
      two parallel regions run structurally identical joins.
   2. Verifies `<child-id>` is one of the parent's spawned children. Forged /
      unknown ids are rejected with the
      `:rf.error/machine-spawn-all-bad-child-id` error trace and a no-op fx
      (the join state is NOT mutated).
   2b. Fences the completion to the EXACT join attempt.
      The carrier bears an exact-attempt COORDINATE — a
      recordable, serialisable, inspectable, replayable correlation record,
      NOT a secret / capability / signature — copied from the membership
      record the runtime stamped at spawn: parent/invoke identity, logical
      child id, exact spawned actor id, and exact attempt token. It folds
      only on FULL exact-current equality with runtime-owned join state.
      This is a fail-closed fence against ACCIDENTS (stale / cross-attempt /
      wrong-actor / duplicate completions), NOT authentication: re-frame2 is
      single-trust-domain — we trust the programmer and gate accidents.
   3. For a SUCCESS (a plain `:final?` leaf) runs the child's OPTIONAL
      per-child `:on-done` fold against the PARENT's `:data` (the same
      `(fn [{:keys [data result]}] new-data)` contract `:spawn :on-done`
      uses); a FAILURE never reaches that fold. The callback, the `:join`
      condition and the resolution events are read from the parent's
      CURRENT definition at the invoke path, so they follow hot reload as a
      single `:spawn`'s `:on-done` does; a reload that removed the node or
      the child folds with the defaults (`:join :all`, no callbacks). The
      join state itself holds only EDN facts — membership, counts,
      tombstones, the attempt token and which children declared a
      `:fixed-actor-id` — so attempt identity does NOT follow hot reload. Then adds
      `<child-id>` to `:done` or `:failed`. A NON-DECISIVE fold (the join
      does not resolve on it) publishes the child's canonical work terminal
      at fold time via
      `:rf.machine.spawn-all/child-completed`; the DECISIVE
      fold's terminal rides the resolution trace instead — one terminal
      authority per child.
   4. If `:resolved?` is already true, this is a post-resolution
      late-completion (it fires NO further parent event — the `:resolved?`
      latch already flipped). Surviving siblings are unconditionally
      destroyed at resolution, so a straggler with no live join is a
      genuinely stale completion: the record stays frozen and the
      `:rf.machine.spawn-all/late-completion` trace fires (a stale work record), with
      no `:done` / `:failed` fold.
   5. Else evaluates the join condition. The join grammar is a CLOSED
      two-member enum — `:all` (default) + `:any`. On resolution:
        - latches `:resolved?` true,
        - destroys every SURVIVOR (a child that never completed) via the
          `:explicit` `:rf.machine/destroy` fx + a
          `:rf.machine.spawn/cancelled-on-join-resolution` trace each (their
          teardown IS a cancellation). COMPLETED children need no teardown
          here: finality tears a child down synchronously at its own
          completion, so by the time the join resolves every folded child is
          already gone (Spec 005 §Final states, D4),
        - dispatches the parent join event via `:fx [[:dispatch ...]]`.
   6. Writes the new join state back into runtime-db.

  The public entry point is `intercept-spawn-done-event`; the handler-factory
  in `re-frame.machines.lifecycle-fx.registration` routes a reserved
  completion carrier through it instead of the machine's normal `:on` lookup.

  An explicitly destroyed child mints no carrier, so its keyword destroy folds
  it here directly: `cancel-child` tombstones it and decides the resolution in
  the same durable write, and `resolve-cancelled-child!` acts on that decision
  once the child is gone (Spec 005 §Join semantics)."
  (:require [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.machines.work :as rf.machines.work]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; The verified fold body is defined below the gates for reading order
;; (gates first, fold second); forward-declared so the entry point can
;; reference it.
(declare intercept-fold)

;; ---------------------------------------------------------------------------
;; Exact-attempt fence.
;;
;; A join child's completion has to be attributable to the exact ATTEMPT it
;; belongs to: after a parent re-enters its `:spawn-all` state the children are
;; respawned, and a straggler from the prior attempt is indistinguishable by
;; VALUE from a live one — most sharply for a `:fixed-actor-id` child, whose
;; address is identical across attempts. The runtime therefore stamps each
;; child's snapshot at spawn with the framework-reserved
;; `[:rf/runtime :join-child]` membership record (parent/invoke identity, logical child id, its own spawned
;; instance address, the opaque per-attempt token `spawn-all-init-fx` minted
;; into the join state, and the private work generation), and `finalize` copies
;; that coordinate onto the completion carrier it mints when the child reaches
;; `:final?`.
;;
;; This coordinate is a replayable CORRELATION RECORD, NOT authentication.
;; re-frame2 is single-trust-domain (spec/Security.md — trust the explicit
;; invoker; gate accidents, not theoretical attacks): the coordinate is
;; recordable / serializable / inspectable / replayable / caller-suppliable,
;; NEVER a secret / capability / signature. Its job is to make stale /
;; cross-attempt / wrong-actor / duplicate completions fail closed — closing
;; real correctness hazards — not to defend the app author against their own
;; process.
;;
;; Recording and strict replay need no special channel. The carrier is
;; an ORDINARY event the runtime mints from durable state, so an epoch replay
;; that re-drives the child to its `:final?` state re-mints an identical
;; carrier and the fold happens exactly once, with no host generation. A
;; PUBLIC application event authored by the child could not carry framework
;; identity through an EDN round-trip or a `:dispatch-later`, which is why the
;; runtime mints the carrier.
;;
;; The fold gate then requires the coordinate to equal runtime-owned join state
;; EXACTLY — parent/invoke identity, logical child id, exact current actor id,
;; exact attempt token — before a `:done` / `:failed` fold. Missing (a
;; hand-authored carrier that never came from a child's finality), superseded
;; (a prior attempt / wrong actor) and duplicate coordinates are classified
;; stale and perform ZERO mutation (no fold, no terminal publication, no
;; resolution) — never a silent replay-success no-op.
;; ---------------------------------------------------------------------------

(defn- completion-attempt
  "The exact-attempt coordinate the fold fences on, projected off the
  completion carrier `finalize` minted from the child's `:join-child`
  membership record: parent/invoke identity, logical child id, the child's own
  spawned instance address, and the opaque per-attempt token. Work generation
  is carried as evidence for a SUPERSEDED attempt, whose join state no
  longer exists. It is NEVER decisive for an exact-current carrier: those paths
  derive the discriminator from durable join state.

  nil for a carrier bearing no coordinate at all — a hand-authored
  `[:rf.machine.spawn/done …]` dispatch that never came from a child's
  finality. That is UNSUPPORTED rather than prohibited, and it fails closed
  (`:attempt-unverified`) exactly as any other unverifiable carrier does."
  [completion]
  (when (and (map? completion) (some? (:attempt completion)))
    (select-keys completion [:parent-id :invoke-id :child-id :spawned-id
                             :attempt :work-generation])))

(defn- join-attempt-current?
  "True iff the carrier's exact-attempt coordinate matches the CURRENT join
  attempt exactly: same parent/invoke identity, the stamped actor is the
  actor CURRENTLY mapped to the carrier's logical child, and the stamped
  attempt token equals the live join state's `:rf/attempt`. Every clause is
  verified against runtime-owned state — nothing is trusted from the carrier
  beyond equality with what the runtime already knows. `child-id` is read off
  the same completion `attempt` was projected from, so it needs no comparison."
  [attempt parent-id invoke-id child-id join-state]
  (and (map? attempt)
       (= parent-id (:parent-id attempt))
       (= invoke-id (:invoke-id attempt))
       (some? (:attempt attempt))
       (= (:rf/attempt join-state) (:attempt attempt))
       (= (get-in join-state [:children child-id]) (:spawned-id attempt))))

(defn- join-work-generation
  "Derive one child's canonical work discriminator from DURABLE join state.
  `:fixed-children` — captured when the join was seeded, never read from a
  definition that may have been edited since — supplies explicit
  fixed-vs-generated provenance; fixed uses the named attempt, while a
  known-generated child uses its allocator address generation. An
  exact-current carried coordinate never supplies or overrides this decision."
  [join-state child-id spawned-id attempt]
  (when (contains? (:children join-state) child-id)
    (if (contains? (:fixed-children join-state) child-id)
      attempt
      (rf.machines.work/actor-generation spawned-id))))

(defn- child-correlation
  "The correlation of one `:spawn-all` child's work: the join's parent and
  invoke path, the logical child id and the spawned instance address, each when
  known. A suppressed completion adds the `kind` it would have folded as."
  [parent-id invoke-id child-id spawned-id kind]
  (cond-> {}
    (some? parent-id)  (assoc :parent-id parent-id)
    (some? invoke-id)  (assoc :invoke-id (vec invoke-id))
    (some? child-id)   (assoc :child-id child-id)
    (some? spawned-id) (assoc :spawned-id spawned-id)
    (some? kind)       (assoc :kind kind)))

(defn- suppress-stale-completion!
  "Fail-closed suppression of a completion carrier that may NOT fold:
  emit one `:rf.machine.spawn-all/stale-completion` trace
  carrying a `:status :stale` / `:work-status :suppressed` work record
  with the precise `stale-reason`
  (`:rf.machine.spawn-all/attempt-unverified` — no runtime stamp;
  `:rf.machine.spawn-all/attempt-superseded` — prior attempt / wrong
  actor / mis-routed join; `:rf.machine.spawn-all/duplicate-completion` —
  exact re-completion of an already-folded child), and return the no-op
  effect map. ZERO mutation: no fold, no terminal publication, no
  resolution, no reap. The exact-current post-resolution carrier keeps its
  own `:rf.machine.spawn-all/late-completion` trace — this op covers the
  ownership / exact-attempt suppression classes on BOTH the pre- and
  post-resolution paths.

  `spawned-id` is the completion's OWN spawned-instance address: the
  CARRIER's stamped id (`(:spawned-id attempt)`) for an `:attempt-superseded`
  straggler — NOT the current join's `[:children child-id]` — so the stale
  evidence never borrows the current attempt's work identity.
  For `:attempt-unverified` there is no carried coordinate to read, so the
  caller passes the live child mapping (the slot the carrier claimed); for
  `:duplicate-completion` the carrier is exact-current, so the two coincide.

  `work-generation` is sourced the same way as `spawned-id`: the carried
  coordinate for a superseded carrier, otherwise the current child's private
  runtime record. Fixed-id stale evidence therefore never borrows a successor
  attempt's work identity."
  [frame-id parent-id invoke-id child-id spawned-id work-generation kind
   completed-at runtime-db stale-reason]
  (let [work (rf.machines.work/record
               (rf.machines.work/spawn-work-id spawned-id invoke-id work-generation)
               :stale
               {:stale-reason stale-reason
                :correlation  (child-correlation parent-id invoke-id child-id spawned-id kind)
                :completed-at completed-at})]
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/stale-completion
                 (cond-> {:actor-id  parent-id
                          :invoke-id invoke-id
                          :child-id  child-id
                          :kind      kind
                          :frame     frame-id
                          ;; work record (Spec 005 §Machine completions emit work records)
                          :rf.reply/work-kind    (:work-kind work)
                          :rf.reply/status       (:status work)
                          :rf.reply/work-id      (:work-id work)
                          :rf.reply/work-status  (:work-status work)
                          :rf.reply/stale-reason (:stale-reason work)
                          :rf.reply/correlation  (:correlation work)}
                   (contains? work :completed-at)
                   (assoc :rf.reply/completed-at (:completed-at work))))
    {:rf.db/runtime runtime-db
     :fx []}))

(defn- join-condition-met?
  "Evaluate the join condition against the current join state.
  Returns truthy iff the join has resolved on the success-side
  (`:on-all-complete` / `:on-some-complete` should fire).

  The join grammar is a CLOSED two-member enum — `:all` (default) and
  `:any` (Promise.all / Promise.any precedent). Quorum (`{:n N}`) and
  predicate (`{:fn pred}`) joins are expressed by counting completions in
  the parent's `:data` (each child spec's `:on-done`) and deciding with a
  guard on the state's `:after` entry (Spec 005 §Composition with hierarchy
  and `:after`); adding `{:n}` later is a compatible widening.

  `spec` is the parent's current `:spawn-all` map (nil after a reload removed
  it: the `:all` default). The child count is the join state's own
  membership."
  [spec join-state]
  (let [join     (:join spec :all)
        n-total  (count (:children join-state))
        n-done   (count (:done   join-state))]
    ;; Registration refuses any other `:join`.
    (case join
      :all (= n-done n-total)
      :any (>= n-done 1))))

(defn- join-unsatisfiable?
  "Decide whether `spec`'s join condition can NEVER be met by the remaining
  undecided children, given `join-state`'s current
  `:done` / `:failed` folds. The footgun this guards: an `:all` / `:any`
  join with NO `:on-any-failed` silently hangs FOREVER once enough children
  have FAILED that the success condition is unreachable — no resolution
  event ever dispatches, the parent rests on the `:spawn-all` state, and
  nothing surfaces the dead join.

  An explicitly destroyed child (`:cancelled`, the tombstone
  `cancel-child` writes) never completes either, so it counts against the
  join exactly as a failure does.

  `max-possible-done` is the largest `:done` count still achievable — the
  current `:done` plus every child not yet decided (every pending child
  optimistically succeeding). The join is unsatisfiable when even that
  ceiling cannot satisfy the condition:

    - `:all`        — any failure or cancellation makes all-done unreachable.
    - `:any`        — `max-possible-done < 1` (every child failed or was
                      cancelled).

  Returns false for a still-satisfiable (or already-resolved) join."
  [spec join-state]
  (let [join        (:join spec :all)
        n-total     (count (:children join-state))
        n-done      (count (:done      join-state))
        n-lost      (count (into (set (:failed join-state)) (:cancelled join-state)))
        n-pending   (- n-total n-done n-lost)
        max-possible-done (+ n-done n-pending)]
    ;; Registration refuses any other `:join`.
    (case join
      :all (pos? n-lost)
      :any (< max-possible-done 1))))

(defn- emit-join-unsatisfiable!
  "The one-shot `:rf.warning/spawn-all-join-unsatisfiable` advisory for the
  join at `[parent-id invoke-id]`, whose state `join-state` just became
  unsatisfiable. Advisory severity: the request is not recovered, but the
  actor is not crashed — a config footgun nudge, the dev-advisory family (the
  cofx lints), not an operation-recovery emit."
  [frame-id parent-id invoke-id spec join-state]
  (rf.trace/emit! :warning :rf.warning/spawn-all-join-unsatisfiable
                  {:actor-id  parent-id
                   :invoke-id invoke-id
                   :join      (:join spec :all)
                   :done      (:done      join-state)
                   :failed    (:failed    join-state)
                   :cancelled (:cancelled join-state)
                   :total     (count (:children join-state))
                   :frame     frame-id
                   :recovery  :join-hangs
                   :reason    (str "A :spawn-all join can no longer be "
                                   "satisfied — too many children have failed "
                                   "or been destroyed — so it will hang forever. "
                                   "Declare :on-any-failed to resolve the join on "
                                   "a failed or destroyed child, or leave the "
                                   ":spawn-all state to end it.")}))

(defn- current-spawn-all-spec
  "The `:spawn-all` declaration at `invoke-id` in the parent's CURRENT
  definition, as the fold reads it; `parent-snapshot` names the parent's
  machine when it is a spawned actor."
  [parent-id parent-snapshot invoke-id]
  (rf.machines.lifecycle-fx.resolver/spawn-all-spec-at
    (rf.machines.lifecycle-fx.resolver/spec-from-id-or-snapshot parent-id parent-snapshot)
    invoke-id))

(def ^:private cancellation-result
  "The `<result>` of a resolution whose decisive child was explicitly
  destroyed, in place of an `:output-key` error value: Managed-Effects
  §Cancellation's `:status :cancelled`, with the cancel reason the child's own
  `:rf.machine/destroyed` row carries."
  {:status :cancelled :rf.reply/cancel-reason :explicit})

(defn- compute-resolution
  "Pure. Given the post-bump `join-state'`, the join spec, and the
  arriving child's `kind` (:done | :failed | :cancelled), decide whether the
  join resolves and which kind of resolution. Returns a map:

      {:kind             kind
       :resolved?        boolean
       :fail-fired?      boolean
       :success-fired?   boolean
       :resolution-event <event-vec or nil>
       :join-event-kw    <:on-all-complete | :on-some-complete | :on-any-failed | nil>}

  - `:fail-fired?` iff the arriving child errored or was explicitly
    destroyed AND the spec declares `:on-any-failed`.
  - `:success-fired?` iff failure didn't fire AND the join condition is
    met by `join-state'`.
  - `:resolution-event` is the spec's event vector to dispatch into the
    parent, or nil when neither path fires.
  - `:join-event-kw` is the resolution kind (used by the
    cancelled-on-join-resolution trace)."
  [spec join-state' kind]
  (let [fail-fired?    (and (contains? #{:failed :cancelled} kind)
                            (vector? (:on-any-failed spec)))
        success-fired? (and (not fail-fired?)
                            (join-condition-met? spec join-state'))
        all-join?      (= :all (:join spec :all))
        resolution-event
        (cond
          fail-fired?    (:on-any-failed spec)
          success-fired? (if all-join?
                           (:on-all-complete spec)
                           (:on-some-complete spec)))
        join-event-kw
        (cond
          fail-fired?    :on-any-failed
          success-fired? (if all-join? :on-all-complete :on-some-complete))]
    {:kind             kind
     :resolved?        (boolean (or fail-fired? success-fired?))
     :fail-fired?      fail-fired?
     :success-fired?   success-fired?
     :resolution-event resolution-event
     :join-event-kw    join-event-kw}))

(defn- child-completion-tags
  "The trace tags of ONE accepted child completion's work record — the
  `:completed` / `:failed` terminal for that child's work attempt
  (`:status :ok` for a `:done` fold, `:status :error` for a `:failed` fold),
  in the same vocabulary the single-`:spawn` `:rf.machine/done` trace
  carries. Returns a tag-map fragment `{:rf.reply/work-id … :rf.reply/status
  … …}`.

  ONE AUTHORITY PER CHILD: these facts ride EXACTLY ONE trace
  per accepted fold — the `:rf.machine.spawn-all/child-completed` fold
  trace for a NON-DECISIVE fold (the join did not resolve), or ADDITIVELY
  on the resolution trace for the DECISIVE fold (the join resolved). The
  two emits sit on opposite arms of the fold's `(:resolved? resolution)`
  split (`intercept-fold`), so a child attempt can never publish its
  terminal twice; duplicate pre-resolution signals are suppressed upstream
  by the exact-attempt fence, and post-resolution arrivals stay `:stale`.

  `kind` is the arriving child's fold kind (`:done` / `:failed`)."
  [parent-id invoke-id join-state'' child-id work-generation kind completed-at]
  (let [spawned-id (get-in join-state'' [:children child-id])
        work       (rf.machines.work/record
                     (rf.machines.work/spawn-work-id spawned-id invoke-id work-generation)
                     (if (= kind :failed) :error :ok)
                     {:correlation  (child-correlation parent-id invoke-id child-id spawned-id nil)
                      :completed-at completed-at})]
    (cond-> {:rf.reply/work-kind   (:work-kind work)
             :rf.reply/status      (:status work)
             :rf.reply/work-id     (:work-id work)
             :rf.reply/work-status (:work-status work)
             :rf.reply/correlation (:correlation work)}
      (contains? work :completed-at)
      (assoc :rf.reply/completed-at (:completed-at work)))))

(defn- emit-child-fold-terminal!
  "Fire the `:rf.machine.spawn-all/child-completed` trace for a
  NON-DECISIVE accepted fold — the canonical `:completed` /
  `:failed` work terminal for a child whose first valid completion folded
  into a join that did NOT resolve on it. Without this, a non-decisive
  child's work attempt would end with NO terminal status at all: the
  resolution trace carries only the decisive child's terminal,
  so in an `:all` join every other child would be folded
  silently — stranding
  trace-derived attempt projections (Xray) on an open attempt.

  The DECISIVE fold's terminal rides the resolution trace instead
  (`emit-resolution-traces!`); the two emits sit on opposite arms of the
  fold's `(:resolved? resolution)` split, so each child attempt has
  exactly ONE terminal authority. Duplicate pre-resolution signals never
  reach here (suppressed by the exact-attempt fence) and post-resolution
  arrivals stay `:stale` — one terminal per work attempt, closed."
  [frame-id parent-id invoke-id join-state'' child-id work-generation kind
   completed-at]
  (let [spawned-id (get-in join-state'' [:children child-id])]
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/child-completed
                 (merge {:actor-id   parent-id
                         :invoke-id  invoke-id
                         :child-id   child-id
                         :spawned-id spawned-id
                         :kind       kind
                         :done       (:done   join-state'')
                         :failed     (:failed join-state'')
                         :frame      frame-id}
                        (child-completion-tags
                          parent-id invoke-id join-state''
                          child-id work-generation kind completed-at)))))

(defn- emit-resolution-traces!
  "Fire the post-resolution observability traces in order: any-failed,
  all-completed, or some-completed.

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the join
  resolution traces reach the cascade's `:trace-events` slot.

  The work record of the DECISIVE child completion that drove the
  resolution (`child-completion-tags`: `:rf.reply/work-id`,
  `:rf.reply/status`, `:rf.reply/work-status`, the causal
  `:rf.reply/completed-at`) rides ADDITIVELY on the resolution trace, so the
  join-resolving child completion classifies the same way the
  single-`:spawn` path does, beside the public resolution-trace slots
  (`:actor-id` / `:invoke-id` / `:done` / `:failed` / `:reason`). An
  explicitly destroyed decisive child adds none: its `:rf.machine/destroyed`
  row already closed its attempt as `:cancelled`, and one attempt has one
  terminal."
  [frame-id parent-id invoke-id spec join-state'' child-id work-generation
   result completed-at {:keys [kind fail-fired? success-fired?]}]
  (when fail-fired?
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/any-failed
                 (cond-> {:actor-id parent-id
                          :invoke-id invoke-id
                          :failed-id  child-id
                          :reason     result
                          :failed     (:failed    join-state'')
                          :cancelled  (:cancelled join-state'')
                          :done       (:done      join-state'')
                          :frame      frame-id}
                   (not= :cancelled kind)
                   (merge (child-completion-tags
                            parent-id invoke-id join-state''
                            child-id work-generation :failed completed-at)))))
  (when success-fired?
    (let [work-tags (child-completion-tags
                      parent-id invoke-id join-state''
                      child-id work-generation :done completed-at)]
      (if (= :all (:join spec :all))
        (rf.trace/emit! :rf.machine :rf.machine.spawn-all/all-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :frame      frame-id}
                            work-tags))
        (rf.trace/emit! :rf.machine :rf.machine.spawn-all/some-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :join       (:join spec)
                             :frame      frame-id}
                            work-tags))))))

(defn- live-attempt-member?
  "True iff `spawned-id` is still a LIVE actor whose `:join-child`
  membership authenticates against THIS join attempt — parent, invoke path,
  logical child id, its own address, and the attempt token (the same test
  `destroy/authenticated-join-child` applies before a teardown).

  `:done ∪ :failed` record carriers already FOLDED, not actors
  already FINISHED. A sibling that reached `:final?` while its carrier is still
  queued behind the decisive one has already published its own terminal and
  torn itself down; it is no survivor, and cancelling it would give one attempt
  contradictory terminals."
  [runtime-db parent-id invoke-id join-state child-id spawned-id]
  (let [member (get-in runtime-db (rf.machines.paths/snapshot-path spawned-id :rf/runtime
                                                                   :join-child))]
    (and (map? member)
         (= parent-id  (:parent-id member))
         (= invoke-id  (:invoke-id member))
         (= child-id   (:child-id member))
         (= spawned-id (:spawned-id member))
         (some? (:attempt member))
         (= (:rf/attempt join-state) (:attempt member)))))

(defn- build-resolution-fx
  "Build the fx vector to fire on resolution: a `:rf.machine/destroy` per
  SURVIVOR (a child that never completed), each carrying one
  `:rf.machine.spawn/cancelled-on-join-resolution` trace, followed by the
  join-event dispatch carrying the decisive child's result. Cancelling
  surviving siblings on the join decision is unconditional. Per Spec 005
  §Spawn-and-join, the dispatched event shape is:

      [<parent-id> [<resolution-event> <decisive-child-id> <result>]]

  where `<result>` is the decisive child's `:output-key` value (its error
  payload on `:on-any-failed`) — ONE value, sourced from the child's finality
  rather than from whatever the child chose to forward — or
  `cancellation-result` when the decisive child was explicitly destroyed.

  COMPLETED children are NOT torn down here. Completion IS finality (Spec 005
  §Final states, D4): a child that folded into this join reached a `:final?`
  state and auto-destroyed synchronously at that moment with `:reason
  :rf.machine/finished`, publishing its own closed work terminal on the way
  out. By the time the join resolves, every child in `:done` / `:failed` is
  already gone, so the live-member test below excludes it: there is nothing
  left to reap and no second, contradictory `:cancelled` terminal to suppress.

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose tags
  lack `:frame`). The caller threads `frame-id` (resolved from `(:rf/frame
  machine)` at the entry point) so the per-survivor cancellation traces reach
  the cascade's `:trace-events` slot.

  A SURVIVOR is also a LIVE member of this attempt (`live-attempt-member?`,
  read off `runtime-db`): a sibling that already FINISHED while its carrier is
  still queued is neither cancelled nor destroyed here — its queued carrier
  lands as a `late-completion`."
  [runtime-db frame-id parent-id invoke-id join-state'' child-id result
   {:keys [resolved? resolution-event join-event-kw]}]
  (let [destroy-fx
        (when resolved?
          (let [survivors     (->> (:children join-state'')
                                   (filter (fn [[cid spawned-id]]
                                             (live-attempt-member?
                                               runtime-db parent-id invoke-id
                                               join-state'' cid spawned-id))))]
            (doseq [[cid spawned-id] survivors]
              ;; A join-survivor cancellation closes the survivor's actor
              ;; work attempt as DATA (Managed-Effects §Cancellation): a
              ;; `:status :cancelled` work record keyed on the survivor's
              ;; spawned instance, with `:cancel-reason :on-join-resolution`,
              ;; so the cancellation joins the work row the spawn started —
              ;; the spawn-all analogue of the single-actor destroy
              ;; cancellation. The survivor's own `:rf.machine/destroy` fx
              ;; ALSO closes it on its `:rf.machine/destroyed` trace; this
              ;; trace carries the join-resolution attribution.
              (let [work (rf.machines.work/record
                           (rf.machines.work/spawn-work-id
                             spawned-id invoke-id
                             (join-work-generation join-state'' cid spawned-id
                                                   (:rf/attempt join-state'')))
                           :cancelled
                           {:cancel-reason :on-join-resolution
                            :correlation   (rf.machines.work/actor-correlation
                                             spawned-id parent-id invoke-id)})]
                (rf.trace/emit! :rf.machine :rf.machine.spawn/cancelled-on-join-resolution
                             {:actor-id parent-id
                              :invoke-id invoke-id
                              :child-id   cid
                              :spawned-id spawned-id
                              :join-event join-event-kw
                              :frame      frame-id
                              ;; work record (Spec 005 §Machine completions emit work records)
                              :rf.reply/work-kind     (:work-kind work)
                              :rf.reply/status        (:status work)
                              :rf.reply/work-id       (:work-id work)
                              :rf.reply/work-status   (:work-status work)
                              :rf.reply/cancelled?    true
                              :rf.reply/cancel-reason (:cancel-reason work)
                              :rf.reply/correlation   (:correlation work)})))
            ;; Only the SURVIVORS need an fx. Tearing down an already-torn-down
            ;; child would be a silent-idempotent no-op, but emitting the
            ;; destroy at all would be a lie about what the join did: the
            ;; completed children closed themselves at their own finality.
            (mapv (fn [[_cid spawned-id]] [:rf.machine/destroy spawned-id])
                  survivors)))
        dispatch-fx
        (when resolution-event
          (let [inner (conj (vec resolution-event) child-id result)]
            [[:dispatch [parent-id inner]]]))]
    (vec (concat (or destroy-fx []) (or dispatch-fx [])))))

(defn cancel-child
  "Pure. `runtime-db` with logical child `child-id`'s attempt closed in the
  join at `[parent-id invoke-id]`: the id joins the `:cancelled` tombstone
  set, so a queued completion for it folds nothing.

  `counts-as-failure?` is true for a keyword destroy — the app's own explicit
  destroy of the child — which counts as that child FAILING (Spec 005 §Join
  semantics). When it also resolves the join, through `:on-any-failed` on a
  join not yet resolved, `:resolved?` latches in this same write, so nothing
  folds between the cancellation and the resolution `resolve-cancelled-child!`
  then carries out. The runtime's own cancellations never count: the exit
  cascade passes false, and the cancel-on-decision cascade's destroys meet a
  join already resolved."
  [runtime-db parent-id invoke-id child-id counts-as-failure?]
  (let [path        (rf.machines.paths/spawned-path parent-id invoke-id)
        join-state  (get-in runtime-db path)
        join-state' (update join-state :cancelled (fnil conj #{}) child-id)
        resolves?   (and counts-as-failure?
                         (not (:resolved? join-state))
                         (:fail-fired?
                           (compute-resolution
                             (current-spawn-all-spec
                               parent-id
                               (get-in runtime-db (rf.machines.paths/snapshot-path parent-id))
                               invoke-id)
                             join-state' :cancelled)))]
    (assoc-in runtime-db path
              (cond-> join-state' resolves? (assoc :resolved? true)))))

(defn resolve-cancelled-child!
  "Carry out what `cancel-child` decided for the destroyed `child-id`, once
  the child is gone: `before` is the join state its write replaced and
  `after` the one it wrote; `runtime-db` is read after the child's teardown.

  If the write latched `:resolved?`, the destroy resolved the join exactly as
  a child error does: the `:rf.machine.spawn-all/any-failed` trace fires and
  the returned fx destroy every survivor and dispatch `:on-any-failed`, its
  `<result>` the `cancellation-result`. Otherwise, with no `:on-any-failed` to
  answer it, the one-shot unsatisfiable-join advisory fires when this
  cancellation FIRST strands the join. Returns the fx vector for the caller to
  run, or nil."
  [runtime-db frame-id parent-id invoke-id child-id before after]
  (let [spec (current-spawn-all-spec
               parent-id
               (get-in runtime-db (rf.machines.paths/snapshot-path parent-id))
               invoke-id)]
    (cond
      (and (:resolved? after) (not (:resolved? before)))
      (let [resolution (compute-resolution spec after :cancelled)]
        (emit-resolution-traces! frame-id parent-id invoke-id spec after child-id
                                 nil cancellation-result nil resolution)
        (build-resolution-fx runtime-db frame-id parent-id invoke-id after child-id
                             cancellation-result resolution))

      (and (not (:resolved? after))
           (join-unsatisfiable? spec after)
           (not (join-unsatisfiable? spec before)))
      (do (emit-join-unsatisfiable! frame-id parent-id invoke-id spec after)
          nil))))

(defn- child-spec-at
  "The child entry for `child-id` in `spawn-all-spec` — the parent's CURRENT
  `:spawn-all` declaration at the invoke path — or nil (no such node or child,
  e.g. after a reload removed it: no callback)."
  [spawn-all-spec child-id]
  (some #(when (= child-id (:id %)) %)
        (:children spawn-all-spec)))

(defn- apply-child-on-done
  "Run a `:spawn-all` child's OPTIONAL per-child `:on-done` fold against the
  PARENT's `:data`, returning the updated runtime-db.

  Same contract as `:spawn :on-done` — the parent's callback context plus
  `:result`, returning the parent's new `:data` — run at THIS child's
  successful finality, before the join fold, so a fan-out
  can land each child's result under its own key without an app-db staging
  slot and without the child knowing the parent exists:

      :spawn-all {:children [{:id :s1 :machine-id :work/processor
                              :on-done (fn [{:keys [data result]}]
                                         (assoc-in data [:results :s1] result))}
                             …]}

  The fold itself is the SHARED `resolver/apply-on-done` — the same single
  application site the `:spawn` form uses at the parent's handler boundary —
  so a throwing fold is contained identically: the parent's `:data` is left
  untouched and the join still folds, because a bad presentation callback must
  not be able to hang a join.

  The caller runs it for a `:done` completion ONLY. A failed
  child reaches the parent through `:on-any-failed` and the join's `:failed`
  set, never through this success fold (Spec 005 §Child completion protocol)."
  [machine runtime-db parent-id invoke-id child-spec completion frame-id]
  (if (:on-done child-spec)
    (let [parent-path (rf.machines.paths/snapshot-path parent-id)
          parent-snap (get-in runtime-db parent-path)]
      (if parent-snap
        (assoc-in runtime-db (conj parent-path :data)
                  (rf.machines.lifecycle-fx.resolver/apply-on-done
                    (:on-done child-spec)
                    (rf.machines.transition/callback-ctx
                      machine parent-snap
                      [rf.machines.transition/spawn-done-event-id invoke-id completion])
                    (:result completion)
                    {:actor-id parent-id
                     :state    (:state parent-snap)
                     :frame    frame-id
                     :child-id (:id child-spec)}))
        runtime-db))
    runtime-db))

(defn intercept-spawn-done-event
  "Per Spec 005 §Child completion protocol. Route the reserved completion
  carrier `[<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]`
  that `lifecycle-fx.finalize` minted for a `:spawn-all` JOIN child: fold it
  into the parent's join state and, on resolution, cancel surviving siblings +
  dispatch the join event. The event is NOT fed into the parent machine's
  normal `:on` lookup — the join's resolution event is what drives the parent's
  macrostep.

  Returns nil when `completion` is not a join child's (an ordinary `:spawn`
  child's completion, which the caller routes to the `:spawn :on-done` fold and
  the parent's ordinary macrostep instead), or a re-frame effect map with
  `:rf.db/runtime` (updated runtime-db — the join state is durable machine
  runtime-db state) and `:fx` (per-survivor destroys + the join-event
  dispatch). `runtime-db` is the frame's runtime-db partition value (the
  `:rf.db/runtime` coeffect)."
  [machine runtime-db parent-id invoke-id completion]
  (when (and (map? completion) (some? (:child-id completion)))
    (let [child-id     (:child-id completion)
          ;; `:error? true` on the child's reached final leaf makes this a
          ;; `:failed` fold; every other finality is a `:done` fold. A join
          ;; child's failure control flow is `:on-any-failed` — a `:spawn-all`
          ;; child spec may not declare `:on-error` at all (registration rejects
          ;; it), so there is no per-child error transition to route to.
          kind         (if (:error? completion) :failed :done)
          result       (:result completion)
          attempt      (completion-attempt completion)
          ;; Resolve the live frame from the runtime-stamped machine
          ;; (registration.cljc/prepare-machine-ctx assoc'd `:rf/frame` before
          ;; handing the machine to the router). Threaded into
          ;; `emit-resolution-traces!` / `build-resolution-fx` AND used inline
          ;; for the late-completion + bad-child-id error traces — all of these
          ;; are dropped by epoch-capture without `:frame`.
          frame-id     (:rf/frame machine)
          ;; The CAUSAL completion timestamp of the child's finality, carried on
          ;; the completion the child's own finalize minted (the router-stamped
          ;; `:rf/time-ms` off that macrostep's `:rf.cofx`). Rides the
          ;; join-child / late-completion work records the same way the
          ;; single-`:spawn` `:rf.machine/done` record carries it
          ;; (Managed-Effects §Causal completion metadata). nil for a pure-fn /
          ;; no-cofx caller — then omitted, not nil-filled.
          completed-at (:completed-at completion)
          ;; Read the live join state from runtime-db (the seed was written by
          ;; `:rf.machine/spawn-all-init` on entry). The completion names its own
          ;; `invoke-id`, so this is a direct lookup — no state-tree walk, and no
          ;; mis-routing when two parallel regions run structurally identical
          ;; joins over the same logical child ids.
          join-state   (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))
          ;; The join's callbacks and configuration come from the parent's
          ;; CURRENT definition at the invoke path (nil after a reload removed
          ;; the node: the defaults), as a single `:spawn`'s `:on-done` does.
          spawn-all-spec (rf.machines.lifecycle-fx.resolver/spawn-all-spec-at machine invoke-id)]
      (cond
        ;; No LIVE child-bearing join state at the slot — fall through to
        ;; no-op. Both no-`:children` cases land here: a pure-call snapshot
        ;; where the slot was never seeded (the runtime tracks join state
        ;; via the fx handlers, not the pure machine-transition), AND a
        ;; childless `spawn-all-reject-sentinel` from an atomically-rejected
        ;; `:spawn-all` — physically present but child-less, so
        ;; a stray / forged completion against it is a harmless no-op.
        (or (not (map? join-state))
            (not (contains? join-state :children)))
        {:rf.db/runtime runtime-db :fx []}

        ;; Validate OWNERSHIP + EXACT-ATTEMPT COORDINATE *before* the
        ;; resolved-vs-unresolved classification. Run FIRST, the
        ;; `:resolved?` branch would attribute ANY matching event
        ;; shape to the CURRENT attempt: it builds its `:late-completion` record
        ;; from the current join's `[:children child-id]`, so an old-attempt
        ;; straggler, an unstamped carrier, or an unknown child would all forge
        ;; evidence carrying the current attempt's spawned/work identity.
        ;; Ordering the ownership + exact-attempt gates ahead of `:resolved?`
        ;; means ONLY an exact-current carrier may enter the post-resolution
        ;; late-completion path; every other carrier is classified the SAME
        ;; way it is on the pre-resolution path — resolved or not — with ZERO
        ;; db mutation.

        ;; Forged / unknown child-id: the carried `child-id` is NOT in the
        ;; seeded `:children` map. The accident class is a hand-crafted
        ;; dispatch (copy-paste from a sibling `:spawn-all`, typo, a cascaded
        ;; event from a sibling parent) that the runtime would otherwise
        ;; silently fold into `:done` / `:failed`, collapsing the join early.
        ;; Gate it: emit a structured error trace and short-circuit with a
        ;; no-op fx (do NOT mutate the join state). Per Spec 005
        ;; §Spawn-and-join.
        (not (contains? (:children join-state) child-id))
        (do (rf.trace/emit-error! :rf.error/machine-spawn-all-bad-child-id
                               {:actor-id parent-id
                                :invoke-id invoke-id
                                :child-id   child-id
                                :children   (set (keys (:children join-state)))
                                :kind       kind
                                :frame      frame-id
                                :recovery   :event-dropped})
            {:rf.db/runtime runtime-db :fx []})

        ;; Exact-attempt fence. The carrier's coordinate must
        ;; equal THIS join attempt: the coordinate `finalize` copied off the
        ;; child's `:join-child` membership record must equal the current
        ;; join's parent/invoke identity, logical child id, exact current actor
        ;; id, and exact attempt token. A carrier with NO coordinate (a
        ;; hand-authored `[:rf.machine.spawn/done …]` dispatch that never came
        ;; from a child's finality) is `:attempt-unverified`; a SUPERSEDED one
        ;; (a prior attempt's straggler after parent re-entry / child respawn —
        ;; including a `:fixed-actor-id` respawn where the actor id alone cannot
        ;; discriminate attempts) is `:attempt-superseded`. Both fold NOTHING;
        ;; both are zero-mutation fail-closed drops with stable typed evidence
        ;; (`:rf.reply/stale-reason` on the stale-completion trace) — never a
        ;; silent replay-success no-op.
        ;;
        ;; The coordinate-less carrier has nothing to source a work identity
        ;; from, so its evidence carries the live child mapping (the slot it
        ;; CLAIMED); the superseded carrier carries the CARRIER's OWN stamped
        ;; `:spawned-id`, so the evidence never borrows the current attempt's
        ;; work identity.
        (nil? attempt)
        (suppress-stale-completion!
          frame-id parent-id invoke-id child-id
          (get-in join-state [:children child-id])
          (join-work-generation join-state child-id
                                (get-in join-state [:children child-id])
                                (:rf/attempt join-state))
          kind completed-at runtime-db
          :rf.machine.spawn-all/attempt-unverified)

        (not (join-attempt-current? attempt parent-id invoke-id child-id join-state))
        (suppress-stale-completion!
          frame-id parent-id invoke-id child-id
          (:spawned-id attempt)
          (:work-generation attempt)
          kind completed-at runtime-db
          :rf.machine.spawn-all/attempt-superseded)

        ;; The carrier is now proven EXACT-CURRENT for this attempt.

        ;; Already resolved: post-resolution LATE completion of the carrier's
        ;; OWN current attempt (a genuine survivor draining after the
        ;; `:resolved?` latch already flipped). Trace once for observability +
        ;; classify it stale (no further PARENT event ever fires). Its work
        ;; record is `:status :stale` / `:work-status :suppressed`
        ;; (Managed-Effects §Stale suppression): it is SUPPRESSED from
        ;; RE-RESOLVING the join — exactly the §Stale-suppression "fires no
        ;; further parent event" rule.
        ;;
        ;; Surviving siblings are unconditionally destroyed at resolution, so a
        ;; late completion is always a genuinely stale straggler with no live
        ;; join to fold into — the record is left frozen at resolution. The
        ;; trace carries the public `:actor-id` / `:invoke-id` / `:child-id` /
        ;; `:kind` slots; no `:done` / `:failed` fold, no re-resolution.
        (:resolved? join-state)
        (let [spawned-id  (get-in join-state [:children child-id])
              work-generation (join-work-generation
                                join-state child-id spawned-id
                                (:rf/attempt join-state))
              work (rf.machines.work/record
                     (rf.machines.work/spawn-work-id spawned-id invoke-id work-generation)
                     :stale
                     {:stale-reason :rf.machine.spawn-all/join-resolved
                      :correlation  (child-correlation parent-id invoke-id child-id spawned-id kind)
                      :completed-at completed-at})]
          (rf.trace/emit! :rf.machine :rf.machine.spawn-all/late-completion
                       (cond-> {:actor-id parent-id
                                :invoke-id invoke-id
                                :child-id   child-id
                                :kind       kind
                                :frame      frame-id
                                ;; work record (Spec 005 §Machine completions emit work records)
                                :rf.reply/work-kind    (:work-kind work)
                                :rf.reply/status       (:status work)
                                :rf.reply/work-id      (:work-id work)
                                :rf.reply/work-status  (:work-status work)
                                :rf.reply/stale-reason (:stale-reason work)
                                :rf.reply/correlation  (:correlation work)}
                         (contains? work :completed-at)
                         (assoc :rf.reply/completed-at (:completed-at work))))
          ;; NO record mutation and NO resolution fx — the join stays
          ;; latched `:resolved?` and fires no further event (the stale
          ;; work record records the suppression).
          {:rf.db/runtime runtime-db
           :fx []})

        ;; CLOSED exact attempt in a still-live join: either the child was
        ;; already folded, or a membership-verified explicit teardown published
        ;; cancellation and durably tombstoned it before callbacks. Both are
        ;; duplicate terminal claims and must suppress without folding, even
        ;; when a replayed carrier retains an exact-current coordinate.
        (or (contains? (:done   join-state) child-id)
            (contains? (:failed join-state) child-id)
            (contains? (:cancelled join-state) child-id))
        (suppress-stale-completion!
          frame-id parent-id invoke-id child-id
          (get-in join-state [:children child-id])
          (join-work-generation join-state child-id
                                (get-in join-state [:children child-id])
                                (:rf/attempt join-state))
          kind completed-at runtime-db
          :rf.machine.spawn-all/duplicate-completion)

        :else
        (let [spawned-id      (get-in join-state [:children child-id])
              work-generation (join-work-generation
                                join-state child-id spawned-id
                                (:rf/attempt join-state))
              ;; Per-child `:on-done` folds the PARENT's `:data` at THIS
              ;; child's finality, BEFORE the join fold — so an
              ;; `:on-all-complete` handler reads a `:data` every child has
              ;; already contributed to.
              ;;
              ;; For a `:done` completion ONLY. The fold is a
              ;; success route: it is handed `{:data :result}` and nothing
              ;; that says the child failed, so a failure folded here lands an
              ;; error map in a success slot (and, under a typed
              ;; `[:schemas :data]`, the rollback swallows the carrier and
              ;; hangs the join). A failed child reaches the parent through
              ;; `:on-any-failed` and the `:failed` set below.
              runtime-db      (if (= kind :done)
                                (apply-child-on-done
                                  machine runtime-db parent-id invoke-id
                                  (child-spec-at spawn-all-spec child-id)
                                  completion frame-id)
                                runtime-db)]
          (intercept-fold frame-id parent-id invoke-id spawn-all-spec join-state
                          child-id work-generation kind result
                          completed-at runtime-db))))))

(defn- intercept-fold
  "The verified fold body of `intercept-spawn-done-event` — the completion
  has passed the live-join / child-ownership / exact-attempt fence and, for a
  `:done` completion, its per-child `:on-done` has already folded the parent's
  `:data` (a `:failed` one folds nothing there). Read
  'compute resolution; emit traces; build fx; write back': three named
  acts plus an assoc-in."
  [frame-id parent-id invoke-id spec join-state child-id work-generation kind
   result completed-at runtime-db]
  (let [join-state'  (case kind
                       :done   (update join-state :done   (fnil conj #{}) child-id)
                       :failed (update join-state :failed (fnil conj #{}) child-id))
        resolution   (compute-resolution spec join-state' kind)
        join-state'' (assoc join-state' :resolved? (:resolved? resolution))]
    ;; Surface a join that just became UNSATISFIABLE: once enough children
    ;; have failed that the success condition is unreachable and no
    ;; `:on-any-failed` resolves it, the join hangs forever, silently. The
    ;; advisory fires on the fold that FIRST makes the join unsatisfiable (it
    ;; was satisfiable before this fold, and this fold did not resolve).
    (when (and (not (:resolved? resolution))
               (join-unsatisfiable? spec join-state')
               (not (join-unsatisfiable? spec join-state)))
      (emit-join-unsatisfiable! frame-id parent-id invoke-id spec join-state'))
    ;; ONE terminal authority per child: a DECISIVE fold's
    ;; terminal rides the resolution trace; a NON-DECISIVE fold publishes
    ;; its canonical terminal HERE, at first valid fold — never both.
    (if (:resolved? resolution)
      (emit-resolution-traces! frame-id parent-id invoke-id spec join-state''
                               child-id work-generation result completed-at
                               resolution)
      (emit-child-fold-terminal! frame-id parent-id invoke-id join-state''
                                 child-id work-generation kind completed-at))
    (let [fx (build-resolution-fx runtime-db frame-id parent-id invoke-id join-state''
                                  child-id result resolution)]
      {:rf.db/runtime (assoc-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id) join-state'')
       :fx fx})))
