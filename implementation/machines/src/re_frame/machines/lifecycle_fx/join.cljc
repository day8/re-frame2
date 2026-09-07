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
  of the child's runtime-stamped `:rf/join-child` membership record. Because
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
   2b. Fences the completion to the EXACT join attempt (rf2-nvxehu /
      rf2-cpbjfp). The carrier bears an exact-attempt COORDINATE — a
      recordable, serialisable, inspectable, replayable correlation record,
      NOT a secret / capability / signature — copied from the membership
      record the runtime stamped at spawn: parent/invoke identity, logical
      child id, exact spawned actor id, and exact attempt token. It folds
      only on FULL exact-current equality with runtime-owned join state.
      This is a fail-closed fence against ACCIDENTS (stale / cross-attempt /
      wrong-actor / duplicate completions), NOT authentication: re-frame2 is
      single-trust-domain — we trust the programmer and gate accidents.
   3. Runs the child's OPTIONAL per-child `:on-done` fold against the
      PARENT's `:data` (the same `(fn [{:keys [data result]}] new-data)`
      contract `:spawn :on-done` uses), then adds `<child-id>` to `:done` or
      `:failed`. A NON-DECISIVE fold (the join does not resolve on it)
      publishes the child's canonical work terminal at fold time via
      `:rf.machine.spawn-all/child-completed` (rf2-ir4t5v); the DECISIVE
      fold's terminal rides the resolution trace instead — one terminal
      authority per child.
   4. If `:resolved?` is already true, this is a post-resolution
      late-completion (it fires NO further parent event — the `:resolved?`
      latch already flipped). Surviving siblings are unconditionally
      destroyed at resolution, so a straggler with no live join is a
      genuinely stale completion: the record stays frozen and the
      `:rf.machine.spawn-all/late-completion` trace fires (stale reply), with
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
  completion carrier through it instead of the machine's normal `:on` lookup."
  (:require [re-frame.machines.lifecycle-fx.resolver :as rf.machines.lifecycle-fx.resolver]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; The verified fold body is defined below the gates for reading order
;; (gates first, fold second); forward-declared so the entry point can
;; reference it.
(declare intercept-fold)

;; ---------------------------------------------------------------------------
;; Exact-attempt fence (rf2-nvxehu / rf2-cpbjfp).
;;
;; A join child's completion has to be attributable to the exact ATTEMPT it
;; belongs to: after a parent re-enters its `:spawn-all` state the children are
;; respawned, and a straggler from the prior attempt is indistinguishable by
;; VALUE from a live one — most sharply for a `:fixed-actor-id` child, whose
;; address is identical across attempts. The runtime therefore stamps each
;; child's `:data` at spawn with the framework-reserved `:rf/join-child`
;; membership record (parent/invoke identity, logical child id, its own spawned
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
;; Recording and strict replay need no special channel any more. The carrier is
;; an ORDINARY event the runtime mints from durable state, so an epoch replay
;; that re-drives the child to its `:final?` state re-mints an identical
;; carrier and the fold happens exactly once, with no host generation. That is
;; what retired the `:rf.machine/join-dispatch` transport fx and its recordable
;; `:rf.machine/join-attempt` cofx fact: they existed only because the carrier
;; used to be a PUBLIC application event the child authored, which could not
;; carry framework identity through an EDN round-trip or a `:dispatch-later`.
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
  completion carrier `finalize` minted from the child's `:rf/join-child`
  membership record: parent/invoke identity, logical child id, the child's own
  spawned instance address, and the opaque per-attempt token. Work generation
  is carried as evidence for a SUPERSEDED attempt, whose old join spec no
  longer exists. It is NEVER decisive for an exact-current carrier: those paths
  derive the discriminator from durable join/spec state.

  nil for a carrier bearing no coordinate at all — a hand-authored
  `[:rf.machine.spawn/done …]` dispatch that never came from a child's
  finality. That is UNSUPPORTED rather than prohibited, and it fails closed
  (`:attempt-unverified`) exactly as any other unverifiable carrier does."
  [completion]
  (when (and (map? completion) (some? (:attempt completion)))
    (select-keys completion [:parent-id :invoke-id :child-id :spawned-id
                             :attempt :work-generation])))

(defn- join-attempt-current?
  "True iff the carrier's `:rf/join-attempt` stamp matches the CURRENT join
  attempt exactly: same parent/invoke identity, same logical child id, the
  stamped actor is the actor CURRENTLY mapped to that child, and the
  stamped attempt token equals the live join state's `:rf/attempt`. Every
  clause is verified against runtime-owned state — nothing is trusted from
  the carrier beyond equality with what the runtime already knows."
  [attempt parent-id invoke-id child-id join-state]
  (and (map? attempt)
       (= parent-id (:parent-id attempt))
       (= invoke-id (:invoke-id attempt))
       (= child-id  (:child-id attempt))
       (some? (:attempt attempt))
       (= (:rf/attempt join-state) (:attempt attempt))
       (= (get-in join-state [:children child-id]) (:spawned-id attempt))))

(defn- join-work-generation
  "Derive one child's canonical work discriminator from DURABLE join state.
  The child spec supplies explicit fixed-vs-generated provenance; fixed uses
  the named attempt, while a known-generated child uses its allocator address
  generation. An exact-current carried coordinate never supplies or overrides
  this decision."
  [join-state child-id spawned-id attempt]
  (when-let [child-spec (some #(when (= child-id (:id %)) %)
                              (get-in join-state [:spec :children]))]
    (if (contains? child-spec :fixed-actor-id)
      attempt
      (rf.machines.reply/actor-generation spawned-id))))

(defn- suppress-stale-completion!
  "Fail-closed suppression of a completion carrier that may NOT fold
  (rf2-nvxehu): emit one `:rf.machine.spawn-all/stale-completion` trace
  carrying the `:status :stale` / `:rf.reply/work-status :suppressed`
  reply facts with the precise `stale-reason`
  (`:rf.machine.spawn-all/attempt-unverified` — no runtime stamp;
  `:rf.machine.spawn-all/attempt-superseded` — prior attempt / wrong
  actor / mis-routed join; `:rf.machine.spawn-all/duplicate-completion` —
  exact re-completion of an already-folded child), and return the no-op
  effect map. ZERO mutation: no fold, no terminal publication, no
  resolution, no reap. The exact-current post-resolution carrier keeps its
  own `:rf.machine.spawn-all/late-completion` trace — this op covers the
  ownership / exact-attempt suppression classes on BOTH the pre- and
  post-resolution paths (rf2-ixjd48).

  `spawned-id` is the completion's OWN spawned-instance address: the
  CARRIER's stamped id (`(:spawned-id attempt)`) for an `:attempt-superseded`
  straggler — NOT the current join's `[:children child-id]` — so the stale
  evidence never borrows the current attempt's work identity (rf2-ixjd48).
  For `:attempt-unverified` there is no carried coordinate to read, so the
  caller passes the live child mapping (the slot the carrier claimed); for
  `:duplicate-completion` the carrier is exact-current, so the two coincide.

  `work-generation` is sourced the same way as `spawned-id`: the carried
  coordinate for a superseded carrier, otherwise the current child's private
  runtime record. Fixed-id stale evidence therefore never borrows a successor
  attempt's work identity."
  [frame-id parent-id invoke-id child-id spawned-id work-generation kind
   completed-at runtime-db stale-reason]
  (let [stale-reply (rf.machines.reply/stale-join-child-reply
                      {:parent-id    parent-id
                       :invoke-id    invoke-id
                       :child-id     child-id
                       :spawned-id   spawned-id
                       :work-generation work-generation
                       :frame        frame-id
                       :completed-at completed-at}
                      kind stale-reason)
        summary     (rf.machines.reply/trace-reply stale-reply {:frame frame-id})]
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/stale-completion
                 (cond-> {:actor-id  parent-id
                          :invoke-id invoke-id
                          :child-id  child-id
                          :kind      kind
                          :frame     frame-id
                          ;; reply-envelope vocabulary (Managed-Effects §9)
                          :rf.reply/work-kind    (:rf.reply/work-kind summary)
                          :rf.reply/status       (:status summary)
                          :rf.reply/work-id      (:rf.reply/work-id summary)
                          :rf.reply/work-status  (:rf.reply/work-status summary)
                          :rf.reply/stale-reason (:rf.reply/stale-reason summary)
                          :rf.reply/correlation  (:correlation summary)}
                   (some? (:completed-at summary))
                   (assoc :rf.reply/completed-at (:completed-at summary))))
    {:rf.db/runtime runtime-db
     :fx []}))

(defn- join-condition-met?
  "Evaluate the join condition against the current join state.
  Returns truthy iff the join has resolved on the success-side
  (`:on-all-complete` / `:on-some-complete` should fire).

  The join grammar is a CLOSED two-member enum — `:all` (default) and
  `:any` (Promise.all / Promise.any precedent). Quorum (`{:n N}`) and
  predicate (`{:fn pred}`) joins are expressed with the data-only `:after`
  + `:done-guard` idiom (Spec 005 §Composition with hierarchy and
  `:after`); re-adding `{:n}` later is a compatible widening."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))]
    (cond
      (= :all join)
      (= n-done n-total)

      (= :any join)
      (>= n-done 1)

      :else false)))

(defn- join-unsatisfiable?
  "Decide whether `spec`'s join condition can NEVER be met by the remaining
  undecided children, given `join-state`'s current
  `:done` / `:failed` folds. The footgun this guards: an `:all` / `:any`
  join with NO `:on-any-failed` silently hangs FOREVER once enough children
  have FAILED that the success condition is unreachable — no resolution
  event ever dispatches, the parent rests on the `:spawn-all` state, and
  nothing surfaces the dead join.

  `max-possible-done` is the largest `:done` count still achievable — the
  current `:done` plus every child not yet decided (every pending child
  optimistically succeeding). The join is unsatisfiable when even that
  ceiling cannot satisfy the condition:

    - `:all`        — any failure makes all-done unreachable.
    - `:any`        — `max-possible-done < 1` (every child failed).

  Returns false for a still-satisfiable (or already-resolved) join."
  [spec join-state]
  (let [join     (:join spec :all)
        children (:children spec)
        n-total  (count children)
        n-done   (count (:done   join-state))
        n-failed (count (:failed join-state))
        n-decided (+ n-done n-failed)
        n-pending (- n-total n-decided)
        max-possible-done (+ n-done n-pending)]
    (cond
      (= :all join)               (pos? n-failed)
      (= :any join)               (< max-possible-done 1)
      :else                       false)))

(defn- compute-resolution
  "Pure. Given the post-bump `join-state'`, the join spec, and the
  arriving child's `kind` (:done | :failed), decide whether the join
  resolves and which kind of resolution. Returns a map:

      {:resolved?        boolean
       :fail-fired?      boolean
       :success-fired?   boolean
       :resolution-event <event-vec or nil>
       :join-event-kw    <:on-all-complete | :on-some-complete | :on-any-failed | nil>}

  - `:fail-fired?` iff the arriving child errored AND the spec declares
    `:on-any-failed`.
  - `:success-fired?` iff failure didn't fire AND the join condition is
    met by `join-state'`.
  - `:resolution-event` is the spec's event vector to dispatch into the
    parent, or nil when neither path fires.
  - `:join-event-kw` is the resolution kind (used by the
    cancelled-on-join-resolution trace)."
  [spec join-state' kind]
  (let [fail-fired?    (and (= kind :failed)
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
    {:resolved?        (boolean (or fail-fired? success-fired?))
     :fail-fired?      fail-fired?
     :success-fired?   success-fired?
     :resolution-event resolution-event
     :join-event-kw    join-event-kw}))

(defn- child-completion-reply-facts
  "Build the reply-envelope facts for ONE accepted child completion —
  the canonical `:completed` / `:failed` terminal for that child's work
  attempt, lowered through the shared `join-child-reply` (`:status :ok`
  for a `:done` fold, `:status :error` for a `:failed` fold) — the same
  uniform vocabulary the single-`:spawn` `:rf.machine/done` reply carries.
  Returns a tag-map fragment `{:rf.reply/work-id … :rf.reply/status … …}`.

  ONE AUTHORITY PER CHILD (rf2-ir4t5v): these facts ride EXACTLY ONE trace
  per accepted fold — the `:rf.machine.spawn-all/child-completed` fold
  trace for a NON-DECISIVE fold (the join did not resolve), or ADDITIVELY
  on the resolution trace for the DECISIVE fold (the join resolved). The
  two emits sit on opposite arms of the fold's `(:resolved? resolution)`
  split (`intercept-fold`), so a child attempt can never publish its
  terminal twice; duplicate pre-resolution signals are suppressed upstream
  by the exact-attempt fence, and post-resolution arrivals stay `:stale`.

  `kind` is the arriving child's fold kind (`:done` / `:failed`);
  `result` is the child's `:output-key` value at its finality (carried under
  `:value` for a `:done` fold, wrapped as the error for a `:failed` fold)."
  [frame-id parent-id invoke-id join-state'' child-id work-generation kind
   result completed-at]
  (let [spawned-id (get-in join-state'' [:children child-id])
        reply      (rf.machines.reply/join-child-reply
                     {:parent-id    parent-id
                      :invoke-id    invoke-id
                      :child-id     child-id
                      :spawned-id   spawned-id
                      :work-generation work-generation
                      :frame        frame-id
                      :completed-at completed-at}
                     kind result)
        summary    (rf.machines.reply/trace-reply reply {:frame frame-id})]
    (cond-> {:rf.reply/work-kind            (:rf.reply/work-kind summary)
             :rf.reply/status      (:status summary)
             :rf.reply/work-id     (:rf.reply/work-id summary)
             :rf.reply/work-status (:rf.reply/work-status summary)
             :rf.reply/correlation (:correlation summary)}
      (some? (:completed-at summary))
      (assoc :rf.reply/completed-at (:completed-at summary)))))

(defn- emit-child-fold-terminal!
  "Fire the `:rf.machine.spawn-all/child-completed` trace for a
  NON-DECISIVE accepted fold (rf2-ir4t5v) — the canonical `:completed` /
  `:failed` work terminal for a child whose first valid completion folded
  into a join that did NOT resolve on it. Without this, a non-decisive
  child's work attempt ended with NO terminal status at all: the join
  machinery published terminals only through the final resolution trace,
  so in an `:all` join every child but the decisive one was folded
  silently, then reaped without cancellation — stranding
  work-ledger/Xray projections on an open attempt.

  The DECISIVE fold's terminal rides the resolution trace instead
  (`emit-resolution-traces!`); the two emits sit on opposite arms of the
  fold's `(:resolved? resolution)` split, so each child attempt has
  exactly ONE terminal authority. Duplicate pre-resolution signals never
  reach here (suppressed by the exact-attempt fence) and post-resolution
  arrivals stay `:stale` — one terminal per work attempt, closed."
  [frame-id parent-id invoke-id join-state'' child-id work-generation kind
   result completed-at]
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
                        (child-completion-reply-facts
                          frame-id parent-id invoke-id join-state''
                          child-id work-generation kind result
                          completed-at)))))

(defn- emit-resolution-traces!
  "Fire the post-resolution observability traces in order: any-failed,
  all-completed, or some-completed.

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the join
  resolution traces reach the cascade's `:trace-events` slot.

  The DECISIVE child completion that drove the resolution lowers through
  the shared `join-child-reply`; its reply-envelope facts
  (`:rf.reply/work-id`, `:rf.reply/status`, `:rf.reply/work-status`, the causal
  `:completed-at`) ride ADDITIVELY on the resolution trace, so the
  join-resolving child completion classifies the same way the
  single-`:spawn` path does. The public resolution-trace shape
  (`:actor-id` / `:invoke-id` / `:done` / `:failed` / `:reason`) is
  preserved."
  [frame-id parent-id invoke-id spec join-state'' child-id work-generation
   result completed-at {:keys [fail-fired? success-fired?]}]
  (when fail-fired?
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/any-failed
                 (merge {:actor-id parent-id
                         :invoke-id invoke-id
                         :failed-id  child-id
                         :reason     result
                         :failed     (:failed join-state'')
                         :done       (:done   join-state'')
                         :frame      frame-id}
                        (child-completion-reply-facts
                          frame-id parent-id invoke-id join-state''
                          child-id work-generation :failed result
                          completed-at))))
  (when success-fired?
    (let [reply-facts (child-completion-reply-facts
                        frame-id parent-id invoke-id join-state''
                        child-id work-generation :done result
                        completed-at)]
      (if (= :all (:join spec :all))
        (rf.trace/emit! :rf.machine :rf.machine.spawn-all/all-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :frame      frame-id}
                            reply-facts))
        (rf.trace/emit! :rf.machine :rf.machine.spawn-all/some-completed
                     (merge {:actor-id parent-id
                             :invoke-id invoke-id
                             :done       (:done join-state'')
                             :join       (:join spec)
                             :frame      frame-id}
                            reply-facts))))))

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
  rather than from whatever the child chose to forward.

  COMPLETED children are NOT torn down here. Completion IS finality (Spec 005
  §Final states, D4): a child that folded into this join reached a `:final?`
  state and auto-destroyed synchronously at that moment with `:reason
  :rf.machine/finished`, publishing its own closed work terminal on the way
  out. By the time the join resolves, every child in `:done` / `:failed` is
  already gone, so there is nothing left to reap and no second, contradictory
  `:cancelled` terminal to suppress — which is what retired the verified-reap
  destroy form and its `:rf.machine/join-reaped` reason.

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose tags
  lack `:frame`). The caller threads `frame-id` (resolved from `(:rf/frame
  machine)` at the entry point) so the per-survivor cancellation traces reach
  the cascade's `:trace-events` slot."
  [frame-id parent-id invoke-id join-state'' child-id result
   {:keys [resolved? resolution-event join-event-kw]}]
  (let [destroy-fx
        (when resolved?
          (let [children      (:children join-state'')
                completed-ids (into #{} (concat (:done   join-state'')
                                                (:failed join-state'')))
                survivors     (->> children
                                   (remove (fn [[cid _]]
                                             (contains? completed-ids cid))))]
            (doseq [[cid spawned-id] survivors]
              ;; A join-survivor cancellation closes the survivor's actor
              ;; work attempt the reply-envelope way: a `:status :cancelled`
              ;; reply (cancellation as DATA, Managed-Effects §Cancellation).
              ;; The reply-envelope facts (`:rf.reply/work-id` keyed on the
              ;; survivor's spawned instance, `:rf.reply/status :cancelled`,
              ;; `:rf.reply/cancel-reason :on-join-resolution`) ride ADDITIVELY
              ;; so the survivor cancellation joins the same uniform work/reply
              ;; row the spawn started — the spawn-all analogue of the
              ;; single-actor destroy cancellation. The survivor's own
              ;; `:rf.machine/destroy` fx ALSO closes it through the
              ;; `:rf.machine/destroyed` cancelled reply; this trace carries the
              ;; join-resolution attribution.
              (let [survivor-summary
                    (rf.machines.reply/trace-reply
                      (rf.machines.reply/cancelled-actor-reply
                        {:actor-id          spawned-id
                         :parent-id         parent-id
                         :work-bearing-path invoke-id
                         :work-generation   (join-work-generation
                                              join-state'' cid spawned-id
                                              (:rf/attempt join-state''))
                         :frame             frame-id
                         :reason            :on-join-resolution})
                      {:frame frame-id})]
                (rf.trace/emit! :rf.machine :rf.machine.spawn/cancelled-on-join-resolution
                             {:actor-id parent-id
                              :invoke-id invoke-id
                              :child-id   cid
                              :spawned-id spawned-id
                              :join-event join-event-kw
                              :frame      frame-id
                              ;; reply-envelope vocabulary (Managed-Effects §9)
                              :rf.reply/work-kind            (:rf.reply/work-kind survivor-summary)
                              :rf.reply/status      (:status survivor-summary)
                              :rf.reply/work-id     (:rf.reply/work-id survivor-summary)
                              :rf.reply/work-status (:rf.reply/work-status survivor-summary)
                              :rf.reply/cancelled?  (:cancelled? survivor-summary)
                              :rf.reply/cancel-reason (:rf.reply/cancel-reason survivor-summary)
                              :rf.reply/correlation (:correlation survivor-summary)})))
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

(defn- child-spec-at
  "The join spec's declared child entry for `child-id`, or nil. The join spec
  is the one the seeding `spawn-all-init-fx` froze into the join state, so this
  reads the attempt's OWN spec even if the parent's registration has since been
  replaced."
  [join-state child-id]
  (some #(when (= child-id (:id %)) %)
        (get-in join-state [:spec :children])))

(defn- apply-child-on-done
  "Run a `:spawn-all` child's OPTIONAL per-child `:on-done` fold against the
  PARENT's `:data`, returning the updated runtime-db.

  Same contract as `:spawn :on-done` — `(fn [{:keys [data result]}] new-data)`
  — run at THIS child's finality, before the join fold, so a fan-out can land
  each child's result under its own key without an app-db staging slot and
  without the child knowing the parent exists:

      :spawn-all {:children [{:id :s1 :machine-id :work/processor
                              :on-done (fn [{:keys [data result]}]
                                         (assoc-in data [:results :s1] result))}
                             …]}

  The fold itself is the SHARED `resolver/apply-on-done` — the same single
  application site the `:spawn` form uses at the parent's handler boundary —
  so a throwing fold is contained identically: the parent's `:data` is left
  untouched and the join still folds, because a bad presentation callback must
  not be able to hang a join."
  [runtime-db parent-id child-spec result frame-id]
  (if (:on-done child-spec)
    (let [parent-path (rf.machines.paths/snapshot-path parent-id)
          parent-snap (get-in runtime-db parent-path)]
      (if parent-snap
        (assoc-in runtime-db (conj parent-path :data)
                  (rf.machines.lifecycle-fx.resolver/apply-on-done
                    (:on-done child-spec) (:data parent-snap) result
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
          ;; reply-envelope join-child / late-completion facts the same way the
          ;; single-`:spawn` `:rf.machine/done` reply carries it
          ;; (Managed-Effects §Causal completion metadata). nil for a pure-fn /
          ;; no-cofx caller — then omitted, not nil-filled.
          completed-at (:completed-at completion)
          ;; Read the live join state from runtime-db (the seed was written by
          ;; `:rf.machine/spawn-all-init` on entry). The completion names its own
          ;; `invoke-id`, so this is a direct lookup — no state-tree walk, and no
          ;; mis-routing when two parallel regions run structurally identical
          ;; joins over the same logical child ids.
          join-state   (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))]
      (cond
        ;; No LIVE child-bearing join state at the slot — fall through to
        ;; no-op. Both no-`:children` cases land here: a pure-call snapshot
        ;; where the slot was never seeded (the runtime tracks join state
        ;; via the fx handlers, not the pure machine-transition), AND a
        ;; childless `spawn-all-reject-sentinel` from an atomically-rejected
        ;; `:spawn-all` (rf2-qb1j5z) — physically present but child-less, so
        ;; a stray / forged completion against it is a harmless no-op.
        (or (not (map? join-state))
            (not (contains? join-state :children)))
        {:rf.db/runtime runtime-db :fx []}

        ;; Validate OWNERSHIP + EXACT-ATTEMPT COORDINATE *before* the
        ;; resolved-vs-unresolved classification (rf2-ixjd48). Pre-fix the
        ;; `:resolved?` branch ran FIRST and attributed ANY matching event
        ;; shape to the CURRENT attempt: it built a `:late-completion` record
        ;; from the current join's `[:children child-id]`, so an old-attempt
        ;; straggler, an unstamped carrier, or an unknown child all forged
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
        ;; §Spawn-and-join and the machines security-audit finding F1.
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

        ;; Exact-attempt fence (rf2-nvxehu). The carrier's coordinate must
        ;; equal THIS join attempt: the coordinate `finalize` copied off the
        ;; child's `:rf/join-child` membership record must equal the current
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
        ;; work identity (rf2-ixjd48).
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
        ;; classify it stale (no further PARENT event ever fires). The
        ;; reply-envelope facts mark the completion `:status :stale` /
        ;; `:rf.reply/work-status :suppressed` (Managed-Effects §Stale
        ;; suppression): it is SUPPRESSED from RE-RESOLVING the join — exactly
        ;; the §Stale-suppression "fires no further parent event" rule.
        ;;
        ;; Surviving siblings are unconditionally destroyed at resolution, so a
        ;; late completion is always a genuinely stale straggler with no live
        ;; join to fold into — the record is left frozen at resolution. The
        ;; public trace shape (`:actor-id` / `:invoke-id` / `:child-id` /
        ;; `:kind`) is preserved; no `:done` / `:failed` fold, no re-resolution.
        (:resolved? join-state)
        (let [spawned-id  (get-in join-state [:children child-id])
              work-generation (join-work-generation
                                join-state child-id spawned-id
                                (:rf/attempt join-state))
              stale-reply (rf.machines.reply/stale-join-child-reply
                            {:parent-id    parent-id
                             :invoke-id    invoke-id
                             :child-id     child-id
                             :spawned-id   spawned-id
                             :work-generation work-generation
                             :frame        frame-id
                             :completed-at completed-at}
                            kind)
              summary    (rf.machines.reply/trace-reply stale-reply {:frame frame-id})]
          (rf.trace/emit! :rf.machine :rf.machine.spawn-all/late-completion
                       (cond-> {:actor-id parent-id
                                :invoke-id invoke-id
                                :child-id   child-id
                                :kind       kind
                                :frame      frame-id
                                ;; reply-envelope vocabulary (Managed-Effects §9)
                                :rf.reply/work-kind             (:rf.reply/work-kind summary)
                                :rf.reply/status       (:status summary)
                                :rf.reply/work-id      (:rf.reply/work-id summary)
                                :rf.reply/work-status  (:rf.reply/work-status summary)
                                :rf.reply/stale-reason (:rf.reply/stale-reason summary)
                                :rf.reply/correlation  (:correlation summary)}
                         (some? (:completed-at summary))
                         (assoc :rf.reply/completed-at (:completed-at summary))))
          ;; NO record mutation and NO resolution fx — the join stays
          ;; latched `:resolved?` and fires no further event (the stale
          ;; reply records the suppression).
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
              runtime-db      (apply-child-on-done
                                runtime-db parent-id
                                (child-spec-at join-state child-id)
                                result frame-id)]
          (intercept-fold frame-id parent-id invoke-id (:spec join-state) join-state
                          child-id work-generation kind result
                          completed-at runtime-db))))))

(defn- intercept-fold
  "The verified fold body of `intercept-spawn-done-event` — the completion
  has passed the live-join / child-ownership / exact-attempt fence and its
  per-child `:on-done` has already folded the parent's `:data`. Read
  'compute resolution; emit traces; build fx; write back': three named
  acts plus an assoc-in."
  [frame-id parent-id invoke-id spec join-state child-id work-generation kind
   result completed-at runtime-db]
  (let [join-state'  (case kind
                       :done   (update join-state :done   (fnil conj #{}) child-id)
                       :failed (update join-state :failed (fnil conj #{}) child-id))
        resolution   (compute-resolution spec join-state' kind)
        join-state'' (assoc join-state' :resolved? (:resolved? resolution))]
    ;; Surface a join that just became UNSATISFIABLE.
    ;; When a child FAILS and the spec has no `:on-any-failed`, the
    ;; failure folds into `:failed` without resolving; once enough
    ;; children have failed that the success condition is unreachable
    ;; the join hangs forever, silently. Emit a one-shot advisory on
    ;; the fold that FIRST makes the join unsatisfiable (it was
    ;; satisfiable before this fold, and this fold did not resolve) so
    ;; the operator sees the dead join + the likely fix (declare
    ;; `:on-any-failed`). Advisory severity: the request is not
    ;; recovered, but the actor is not crashed — this is a config
    ;; footgun nudge, the dev-advisory family (`:on-spawn-return-
    ;; ignored`, the cofx lints), not an operation-recovery emit.
    (when (and (not (:resolved? resolution))
               (join-unsatisfiable? spec join-state')
               (not (join-unsatisfiable? spec join-state)))
      (rf.trace/emit! :warning :rf.warning/spawn-all-join-unsatisfiable
                   {:actor-id  parent-id
                    :invoke-id invoke-id
                    :join      (:join spec :all)
                    :done      (:done   join-state')
                    :failed    (:failed join-state')
                    :total     (count (:children spec))
                    :frame     frame-id
                    :recovery  :join-hangs
                    :reason    (str "A :spawn-all join can no longer be "
                                    "satisfied — too many children have failed "
                                    "and no :on-any-failed transition is declared, "
                                    "so the join will hang forever. Declare "
                                    ":on-any-failed to handle child failures.")}))
    ;; ONE terminal authority per child (rf2-ir4t5v): a DECISIVE fold's
    ;; terminal rides the resolution trace; a NON-DECISIVE fold publishes
    ;; its canonical terminal HERE, at first valid fold — never both.
    (if (:resolved? resolution)
      (emit-resolution-traces! frame-id parent-id invoke-id spec join-state''
                               child-id work-generation result completed-at
                               resolution)
      (emit-child-fold-terminal! frame-id parent-id invoke-id join-state''
                                 child-id work-generation kind result
                                 completed-at))
    (let [fx (build-resolution-fx frame-id parent-id invoke-id join-state''
                                  child-id result resolution)]
      {:rf.db/runtime (assoc-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id) join-state'')
       :fx fx})))
