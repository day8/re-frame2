(ns re-frame.machines.lifecycle-fx.join
  "`:spawn-all` join-event interception.

  Per Spec 005 §Spawn-and-join via `:spawn-all` §Child completion protocol,
  the parent's handler boundary intercepts events whose inner-event-id
  matches the active state's `:on-child-done` / `:on-child-error`. The
  interception:

   1. Resolves the active `:spawn-all`-bearing state by walking the
      snapshot's `:state` path leaf→root looking for a state node whose
      `:spawn-all` declares the matching event keyword.
   2. Reads the join state at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`.
   3. Verifies `<child-id>` (event[1]) is one of the parent's spawned
      children. Forged / unknown ids are rejected with the
      `:rf.error/machine-spawn-all-bad-child-id` error trace and a
      no-op fx (the join state is NOT mutated).
   3b. Fences the carrier to the EXACT join attempt (rf2-nvxehu / rf2-cpbjfp).
      The completion carries an exact-attempt COORDINATE — a recordable,
      serialisable, inspectable, replayable correlation record, NOT a secret /
      capability / signature — that the member child's own handler boundary
      stamped: parent/invoke identity, logical child id, exact current actor
      id, and exact attempt token. It folds only on FULL exact-current equality
      with runtime-owned join state. This is a fail-closed fence against
      ACCIDENTS (stale / cross-attempt / wrong-actor / duplicate completions),
      NOT authentication: re-frame2 is single-trust-domain — we trust the
      programmer and gate accidents. The one coordinate slot the parent reads
      is the recordable `:rf.machine/join-attempt` causal-envelope fact on the
      completion's `:rf.cofx` (the framework-produced normal path — it survives
      both the event/coeffect recording + strict-replay path and delayed
      dispatch, which event-vector metadata does not, so the metadata slot is
      not read). An exact-current coordinate folds regardless of source —
      normal transport, faithful replay, tooling, or deliberate app authoring;
      a missing / stale / cross-attempt / wrong-actor / duplicate coordinate
      fails closed (`:rf.machine.spawn-all/stale-completion`) with zero
      mutation.
   4. Adds `<child-id>` to `:done` or `:failed`. A NON-DECISIVE fold (the
      join does not resolve on it) publishes the child's canonical work
      terminal at fold time via `:rf.machine.spawn-all/child-completed`
      (rf2-ir4t5v); the DECISIVE fold's terminal rides the resolution
      trace instead — one terminal authority per child.
   5. If `:resolved?` is already true, this is a post-resolution
      late-completion (it fires NO further parent event — the
      `:resolved?` latch already flipped). Surviving siblings are
       unconditionally destroyed at resolution, so a straggler with no live
      join is a genuinely stale completion: the record stays frozen and
      the `:rf.machine.spawn-all/late-completion` trace fires (stale
      reply), with no `:done` / `:failed` fold.
   6. Else evaluates the join condition. The join grammar is a CLOSED
      two-member enum — `:all` (default) + `:any`. On resolution:
        - latches `:resolved?` true,
        - tears down EVERY child: SURVIVORS (never reported completion) via
          the `:explicit` `:rf.machine/destroy` fx + a
          `:rf.machine.spawn/cancelled-on-join-resolution` trace each (their
          teardown IS a cancellation); COMPLETED / FAILED children via the
          non-cancellation `:rf.machine/join-reaped` reap form (they already
          closed their attempt as a join-child completion — reaping avoids a
          second, contradictory terminal reply, rf2-tj3l6a),
        - dispatches the parent join event via `:fx [[:dispatch ...]]`.
   7. Writes the new join state back into runtime-db.

  The interceptor's public entry point is `intercept-spawn-all-event`;
  the handler-factory in `re-frame.machines.lifecycle-fx.registration`
  routes every inbound event through it before the machine's normal `:on`
  lookup."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [re-frame.machines.path-walk :as rf.machines.path-walk]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.transition :as rf.machines.transition]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; The verified fold body is defined below the interceptor for reading
;; order (gates first, fold second); forward-declared so the interceptor
;; can reference it.
(declare intercept-fold)

;; ---------------------------------------------------------------------------
;; Exact-attempt fence (rf2-nvxehu / rf2-cpbjfp) + recordable transport
;; (rf2-t154jx).
;;
;; A `:spawn-all` child's completion carrier is the PUBLIC event shape
;; `[<parent-id> [<done-kw> <child-id> & extra]]` — it carries no actor or
;; attempt identity, so after parent re-entry / child respawn a STALE carrier
;; from a prior attempt is indistinguishable from a live one by value. The
;; runtime therefore correlates each completion to its exact attempt through
;; the existing PRIVATE lifecycle path: at spawn, each child's `:data` gets the
;; framework-reserved `:rf/join-child` membership record (parent/invoke
;; identity, logical child id, its own spawned instance address, the join's
;; completion event keywords, and the opaque per-attempt token
;; `spawn-all-init-fx` minted into the join state).
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
;; TRANSPORT (rf2-t154jx). When the child's own handler emits a matching
;; completion dispatch, `stamp-join-completion-fx` (called at the handler-return
;; boundary in `registration.cljc`) REWRITES that outbound `:dispatch` /
;; `:dispatch-later` completion into the framework-internal `:rf.machine/
;; join-dispatch` fx, which re-dispatches the UNCHANGED public completion event
;; with the exact-attempt coordinate attached as the RECORDABLE causal-envelope
;; fact `:rf.machine/join-attempt` on the dispatch's `:rf.cofx`. That channel is
;; part of the event/coeffect recording + strict-replay contract and is
;; preserved through delayed dispatch — UNLIKE event-vector metadata, which an
;; EDN round-trip (strict replay) drops and which the retired `:dispatch-n` /
;; delayed paths never carried. So the recordable `:rf.cofx` fact is the ONE
;; coordinate slot the parent reads (`carrier-attempt`); the metadata slot is
;; not a fallback (reading it was a pure narrowing — the framework-produced path
;; always populates the cofx). The public event value is untouched, and no
;; application control surface is added (a reserved-`:rf.machine/*` fx that
;; dispatches ONLY the pre-built carrier it was handed — never traversing
;; arbitrary effects).
;;
;; The interceptor's fold branch then requires the coordinate to equal
;; runtime-owned join state EXACTLY — parent/invoke identity, logical child
;; id, exact current actor id, exact attempt token — before a `:done` /
;; `:failed` fold. An exact-current coordinate folds regardless of source
;; (normal transport, faithful replay, tooling, or deliberate app authoring —
;; direct authoring is UNSUPPORTED, not prohibited). Missing, superseded (prior
;; attempt / wrong actor / old delayed straggler), and duplicate coordinates are
;; classified stale and perform ZERO mutation (no fold, no terminal publication,
;; no resolution, no reap) — never a silent replay-success no-op.
;; ---------------------------------------------------------------------------

(defn- join-completion-event?
  "True iff `event` (a `[:dispatch …]` fx's event vector) IS this join
  child's own completion carrier: exactly `[<parent-id> [<done-kw-or-
  error-kw> <child-id> & extra]]` for the membership record `rec`. Only the
  2-element outer shape is stampable — extra outer args are rebuilt by
  `route-inner-event` at the parent (metadata would be lost) and no child
  emits them for its own completion."
  [event {:keys [parent-id child-id done-kw error-kw]}]
  (and (vector? event)
       (= 2 (count event))
       (= parent-id (first event))
       (let [inner (second event)]
         (and (vector? inner)
              (or (= done-kw (first inner))
                  (= error-kw (first inner)))
              (= child-id (second inner))))))

(defn- join-attempt-of
  "The exact-attempt coordinate the interceptor fences on, projected from a
  child's `:rf/join-child` membership record `rec`: parent/invoke identity,
  logical child id, the child's own spawned instance address, and the opaque
  per-attempt token. Work generation is carried as evidence for a SUPERSEDED
  attempt, whose old join spec no longer exists. It is NEVER decisive for an
  exact-current carrier: those paths derive the discriminator from durable
  join/spec state."
  [rec]
  (select-keys rec [:parent-id :invoke-id :child-id :spawned-id :attempt
                    :work-generation]))

(defn join-dispatch-fx
  "fx handler for `:rf.machine/join-dispatch` (rf2-t154jx) — the framework-
  internal transport that carries a `:spawn-all` child's completion carrier to
  its parent WITH the exact-attempt coordinate on the RECORDABLE `:rf.cofx`
  causal-envelope fact `:rf.machine/join-attempt`. `stamp-fx-entry` rewrites a
  member child's OWN outbound `:dispatch` / `:dispatch-later` completion into
  this fx at the child's handler-return boundary; the parent's join interceptor
  reads the coordinate back off its `:rf/cofx` (`carrier-attempt`). The
  coordinate thus survives BOTH the event/coeffect recording + strict-replay
  path AND the delayed-dispatch path (event-vector metadata survives neither).

  A THIN handler over the shared core reserved-dispatch seam
  `re-frame.fx/child-dispatch!` (rf2-lud4af). Routing the completion carrier
  through the SAME seam the parent `:dispatch` / `:dispatch-later` reserved-fx
  bodies use preserves the FULL reserved-dispatch contract — inheriting from the
  emitting child's envelope its `:fx-overrides`, `:interceptor-overrides`,
  `:trace-id`, `:origin`, the per-call `:rf.cofx/mint-policy` strict/replay
  discipline, and the `:rf.machine/internal?` front-of-queue ordering (the
  emitter is always a machine child) — and a DELAYED completion (a
  `:dispatch-later` form, ANY numeric `:ms` including zero — rf2-21hsb1) rides
  the frame-owned `:dispatch-later` timer table, cancelled on frame destroy,
  rather than a raw host timeout. The transport adds ONLY its recordable
  `:rf.machine/join-attempt` `:rf.cofx` fact and stamps `:source :machine-action`;
  the public completion event VALUE is unchanged.

  CANONICAL DISPATCH OVERRIDES INTERCEPT FIRST (rf2-ulsbgr) — ONE EXPLICIT,
  NON-REDIRECTABLE POLICY (rf2-2lzk8a). #5881 lowered the authored `:dispatch` /
  `:dispatch-later` completion to this PRIVATE id BEFORE core override
  resolution, so `do-fx` looked up `:fx-overrides` under the private id and a
  `{:fx-overrides {:dispatch capture-fn}}` never intercepted the completion.
  Here we take ONE structured resolution of the CANONICAL override
  (`:dispatch-later` for a numeric `:ms`, else `:dispatch`) from the SHARED
  policy `re-frame.fx/classify-fx-override`, against the SAME effective override
  map `do-fx` used (per-frame ⋈ per-call, per-call winning), then consume THAT
  ONE disposition (rf2-5g6qq):

    - `:applied-fn` / `:applied-redirect` — a fn-value or a keyword redirect to a
      REGISTERED, NON-PROTECTED fx GENUINELY pre-empts the completion. We route
      the PRE-RESOLVED effect through the shared core seam
      `re-frame.fx/handle-one-fx` (empty overrides for a redirect target, the
      captured fn for a fn-value) so execution CANNOT re-decide applies-vs-
      fallthrough — a concurrent register / unregister can never flip the
      single disposition into a coordinate-less transport. The override captures
      / redirects the completion, queues nothing, and folds nothing — the
      framework-produced path never runs, so no coordinate is stamped.

    - `:noop` / `:fallthrough` / `:protected-rejection` — no override, a nil/false
      noop, an UNREGISTERED keyword, a MALFORMED value, or a redirect to a
      PROTECTED internal id is NOT an applied override: it falls through to the
      recordable transport below, so the join still folds EXACTLY ONCE on the
      framework-produced coordinate (the pre-fix `real-override?` gate routed
      these to `handle-one-fx`, where the fall-through re-dispatched a
      coordinate-less `:dispatch` that the parent's fence suppressed
      `:attempt-unverified`, hanging the join). The canonical
      `:rf.error/override-fallthrough` diagnostic for a real-but-non-applying
      override is surfaced through the ONE shared emit path
      (`re-frame.fx/emit-override-fallthrough!`) before the transport runs.

  EXACT-OWNER FENCE (rf2-5g6qq, modelled on rf2-8nxsh). Both the fallthrough
  diagnostic emit AND the transport fan synchronous callbacks: an always-on
  `:errors` listener can destroy owner frame A (and publish same-id successor B)
  DURING the diagnostic emit. The pre-fix seam then UNCONDITIONALLY called
  `child-dispatch!` afterward — immediate router dispatch fired the completion
  after A was gone, and a delayed completion reserved a `dispatch-later-timers`
  slot AFTER destroy had already released that table (a post-cleanup timer that
  fires dead-on-arrival). So we recheck exact-owner continuation
  (`rf.machines.data-validation/owner-continuation`, bound to A's raw owner token) AFTER the
  emit and BEFORE the router dispatch / numeric-ms timer reservation: if A is
  gone, neither lands on A nor on an unrelated same-id B. The `:applied-*`
  branch's `handle-one-fx` is itself exact-owner-fenced by core, so it needs no
  extra guard.

  RESERVED / NON-OVERRIDABLE / NON-REDIRECTABLE (rf2-2lzk8a). It re-dispatches
  ONLY the pre-built completion carrier it was handed (never traverses arbitrary
  / custom effect payloads). `:rf.machine/join-dispatch` is the one id in BOTH
  of core's separated policies (rf2-1w4af): `non-overridable-source-fx-ids`, so
  a DIRECT override of it is rejected (`:rf.error/reserved-fx-override`, the real
  body runs); AND `non-redirectable-target-fx-ids`, so a REDIRECT whose target
  is it is refused (no privilege escalation, no self-recursive
  StackOverflowError). This protects the FRAMEWORK PATH from capture or
  suppression — an override can neither swallow nor rewrite the transport — it
  does NOT make the coordinate unforgeable. Direct app emission of a
  `[:rf.machine/join-dispatch …]` fx is UNSUPPORTED but not security-prohibited
  (rf2-cpbjfp): like any hand-authored coordinate it still folds ONLY on
  exact-current equality with runtime-owned join state, and fails closed
  otherwise — the same honest stance as the cofx route.

  ctx carries `:frame`, `:envelope` (the emitting child's dispatch envelope),
  and `:event` (the originating event, threaded by `re-frame.fx/do-fx`). args:
  `{:event <[parent-id inner-event]> :rf/join-attempt <coordinate> :ms <ms?>}`. A
  NUMERIC `:ms` (from a `:dispatch-later` completion, INCLUDING `:ms 0` —
  rf2-21hsb1) arms a frame-owned delayed dispatch; a nil `:ms` (a direct
  `:dispatch` completion) dispatches immediately in the current drain."
  [{:keys [frame envelope] origin-event :event} {:keys [event ms] attempt :rf/join-attempt}]
  (let [frame-id     (rf.frame/require-frame-stamp!
                       frame :rf.machine/join-dispatch
                       {:where 'rf.machine/join-dispatch :event-id (first event)})
        ;; The CANONICAL authored effect this completion was lowered from: a
        ;; `:dispatch-later` completion carries a numeric `:ms` (zero or
        ;; positive); a direct `:dispatch` completion carries none.
        canonical-id (if (number? ms) :dispatch-later :dispatch)
        frame-record (rf.frame/frame frame-id)
        ;; The SAME effective override map `do-fx` resolved against — per-frame
        ;; ⋈ per-call, per-call winning (mirrors `router/apply-overrides`). The
        ;; per-call tier rides the emitting child's dispatch envelope.
        overrides    (merge (:fx-overrides (:config frame-record))
                            (:fx-overrides envelope))
        origin-event-id (when (vector? origin-event) (first origin-event))
        ;; ONE structured override resolution from the shared policy
        ;; (rf2-5g6qq). Consumed below WITHOUT re-consulting the registrar /
        ;; protected-target rule, so a concurrent register / unregister cannot
        ;; flip an applies-preflight into a coordinate-less transport. This is
        ;; the SAME policy `resolve-fx-with-overrides` consumes — join
        ;; duplicates no registrar / protected-target lookup.
        disposition  (rf.fx/classify-fx-override overrides canonical-id)
        ;; Exact-owner continuation predicate, captured ONCE against A's raw
        ;; owner token (rf2-5g6qq / rf2-8nxsh): true while the incarnation that
        ;; owns the in-flight event may still run framework continuation, false
        ;; once a synchronous callback (a fallthrough `:errors` listener) has
        ;; destroyed A / published same-id B.
        continue?    (rf.machines.data-validation/owner-continuation frame-id)]
    (case (:disposition disposition)
      ;; OVERRIDE GENUINELY APPLIES (rf2-ulsbgr / rf2-2lzk8a): route the
      ;; PRE-RESOLVED effect through the shared core seam. `handle-one-fx` (not
      ;; `do-fx`) keeps the single `:event/do-fx` boundary marker on the outer
      ;; walk (the nav-token wrapper precedent) and is itself exact-owner-fenced
      ;; by core. No `child-dispatch!` → no fold, no queue, no coordinate stamped.
      ;;
      ;; A fn-value override is churn-immune (no registrar dependency); pass it
      ;; as the sole override so execution resolves to the SAME fn.
      :applied-fn
      (rf.fx/handle-one-fx
        frame-id
        (if (number? ms) [canonical-id {:ms ms :event event}] [canonical-id event])
        (rf.fx/platform-for-frame-record frame-record)
        {canonical-id (:override disposition)}
        origin-event
        envelope)

      ;; A keyword redirect to a REGISTERED, NON-PROTECTED fx: invoke the
      ;; PRE-RESOLVED target directly with EMPTY overrides, so `handle-one-fx`
      ;; cannot re-resolve the canonical id to a fallthrough transport under
      ;; mid-flight churn (rf2-5g6qq). If the target was unregistered since
      ;; classification, this is an honest `:rf.error/no-such-fx` — never a
      ;; coordinate-less completion.
      :applied-redirect
      (rf.fx/handle-one-fx
        frame-id
        (if (number? ms)
          [(:target disposition) {:ms ms :event event}]
          [(:target disposition) event])
        (rf.fx/platform-for-frame-record frame-record)
        {}
        origin-event
        envelope)

      ;; :noop / :fallthrough / :protected-rejection — the normal recordable
      ;; transport. A REAL but non-applying override surfaces its canonical
      ;; `:rf.error/override-fallthrough` through the ONE shared emit path FIRST
      ;; (a `:noop` emits nothing); it does NOT strip the completion of its
      ;; coordinate. That emit fans synchronous listeners, so recheck exact-owner
      ;; continuation before the transport: if a fallthrough listener destroyed
      ;; A, neither the immediate router dispatch nor the numeric-ms timer
      ;; reservation runs (rf2-5g6qq).
      ;;
      ;; The recordable causal-envelope fact rides `:rf.cofx`; `:source
      ;; :machine-action` matches the child's original `:dispatch` stamp; the
      ;; `:rf.machine/internal?` front-of-queue ordering + override / lineage /
      ;; mint-policy inheritance are lifted from `envelope` by `child-dispatch!`.
      ;; A `:dispatch-later` completion carries its numeric `:ms` + `:source-detail`
      ;; for EVERY delay INCLUDING ZERO (rf2-21hsb1) — the canonical
      ;; `child-dispatch!` path treats every numeric `:ms` as host-clock-delayed,
      ;; so a `:dispatch-later {:ms 0}` yields through the frame-owned timer (a
      ;; render/scheduling boundary, Spec 002 §long-running work) rather than
      ;; folding synchronously. A direct `:dispatch` completion (nil `:ms`)
      ;; enqueues immediately in the current drain.
      (do
        (rf.fx/emit-override-fallthrough!
          disposition canonical-id overrides frame-id origin-event origin-event-id)
        (when (continue?)
          (rf.fx/child-dispatch!
            frame-id envelope event
            (cond-> {:source  :machine-action
                     :rf.cofx {:rf.machine/join-attempt attempt}}
              (number? ms) (assoc :ms ms :source-detail {:ms ms})))))))
  nil)

(defn- stamp-fx-entry
  "Rewrite one fx-vector entry that dispatches this child's OWN completion
  carrier into the `:rf.machine/join-dispatch` transport (rf2-t154jx), carrying
  the exact-attempt coordinate on the recordable `:rf.cofx` fact rather than
  event metadata. Handles the direct `:dispatch` form and the reserved
  `:dispatch-later` `{:ms n :event event}` form (the delayed path #5839's
  metadata stamp never covered); the retired top-level `:dispatch-n` arm is
  gone. Every non-completion entry rides through untouched. The public
  completion event VALUE is preserved verbatim."
  [fx-entry rec]
  (if (and (vector? fx-entry) (>= (count fx-entry) 2))
    (let [[fx-id arg] fx-entry]
      (cond
        (and (= :dispatch fx-id) (join-completion-event? arg rec))
        [:rf.machine/join-dispatch {:event arg :rf/join-attempt (join-attempt-of rec)}]

        (and (= :dispatch-later fx-id)
             (map? arg)
             (join-completion-event? (:event arg) rec))
        [:rf.machine/join-dispatch {:event        (:event arg)
                                    :rf/join-attempt (join-attempt-of rec)
                                    :ms           (:ms arg)}]

        :else fx-entry))
    fx-entry))

(defn stamp-join-completion-fx
  "The child-boundary half of the exact-attempt fence (rf2-nvxehu /
  rf2-t154jx). Given the effects map a `:spawn-all` child's handler is about to
  return and the child's `:rf/join-child` membership record (nil for every
  non-join-child actor — the common case, a no-op), REWRITE each outbound
  completion carrier in `:fx` (direct `:dispatch` and reserved `:dispatch-later`
  forms) targeting this child's own join into the `:rf.machine/join-dispatch`
  transport, which re-dispatches the unchanged public event with the exact-attempt
  coordinate attached on the RECORDABLE `:rf.cofx` fact `:rf.machine/join-attempt`.
  Called from the machine-handler return boundary
  (`registration.cljc/commit-or-finalize`), so it covers action fx,
  bootstrap-entry fx, and the finalize path uniformly — the existing private
  lifecycle path, no new application-facing surface."
  [effects join-child]
  (if (and join-child
           (map? effects)
           (seq (:fx effects)))
    (update effects :fx (fn [fxs] (mapv #(stamp-fx-entry % join-child) fxs)))
    effects))

(defn- carrier-attempt
  "The completion carrier's `:rf/join-attempt` exact-attempt coordinate
  (rf2-nvxehu), read from the ONE coordinate slot: the recordable `:rf.cofx`
  fact `:rf.machine/join-attempt` (rf2-t154jx), which the framework-produced
  transport (`:rf.machine/join-dispatch`) attaches and `prepare-machine-ctx`
  threads onto the machine def's `:rf/cofx`. That channel survives BOTH the
  event/coeffect recording + strict-replay path AND the delayed-dispatch path.

  Only the cofx slot is read; event-vector metadata is NOT a fallback
  (rf2-nsbwft). This is a pure NARROWING, not a secrecy boundary: the recordable
  cofx already carries the coordinate on every framework-produced path (the
  immediate `:dispatch` and delayed `:dispatch-later` completions both flow
  through `child-dispatch!`'s `:rf.cofx`), and metadata never survived
  recording/replay or delayed dispatch anyway. A coordinate hand-authored onto
  the cofx is accepted the same as any other — it still folds ONLY on
  exact-current equality (rf2-cpbjfp); the honesty is that this is a correlation
  record, not authentication.

  The coordinate is never trusted for its own sake — every clause is checked
  against runtime-owned join state downstream (`join-attempt-current?`), so a
  stale / cross-attempt / replayed coordinate that does not equal the live
  attempt fails closed regardless of how it arrived."
  [machine]
  (get-in machine [:rf/cofx :rf.machine/join-attempt]))

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

(defn- find-active-spawn-all-in-tree
  "Helper for `find-active-spawn-alls`. Given a machine-like map with
  `:states` (for a non-parallel machine, the machine itself; for a
  region of a parallel machine, the region body) and a path inside
  that tree, walk leaf→root for a `:spawn-all`-bearing state whose
  `:on-child-done` or `:on-child-error` matches inner-event-id (the
  deepest-wins rule named in `rf.machines.path-walk/walk-path-leaf-to-root`)."
  [tree path inner-event-id]
  (rf.machines.path-walk/walk-path-leaf-to-root
    tree path
    (fn [prefix n]
      (when-let [ia (:spawn-all n)]
        (cond
          (= inner-event-id (:on-child-done ia))
          {:invoke-id prefix :spec ia :kind :done}
          (= inner-event-id (:on-child-error ia))
          {:invoke-id prefix :spec ia :kind :failed})))))

(defn- find-active-spawn-alls
  "Walk the snapshot's `:state` path leaf→root looking for EVERY active
  `:spawn-all`-bearing state whose `:on-child-done` or `:on-child-error`
  matches the given inner-event-id. Returns a vector of
  `{:invoke-id <prefix-path> :spec <invoke-all-spec> :kind :done|:failed}`
  matches (empty when none).

  Per Spec 005 §Parallel regions: for parallel-region machines, iterates
  each region's active state-tree (prefixing the region name onto the
  resolved `:invoke-id`, matching the per-region scoping
  `prefix-region-invoke-id` applies on the entry-side). A flat machine has at
  most one active match.

  Returns ALL matches (not just the first) so the interceptor can
  disambiguate by join-state child-id OWNERSHIP. Two active parallel regions
  may legitimately reuse the SAME generic `:on-child-done` event id (e.g.
  `:done`, `:asset/loaded`); returning every match lets the interceptor
  route a child completion to the region whose join actually owns the
  child-id, rather than first-match-wins mis-routing it to another region's
  join."
  [machine snapshot inner-event-id]
  (cond
    (rf.machines.parallel/parallel? machine)
    (into []
          (keep (fn [[region-name region-state]]
                  (let [region-body (rf.machines.parallel/region-machine machine region-name)
                        region-path (rf.machines.transition/state-path region-state)
                        match       (find-active-spawn-all-in-tree
                                      region-body region-path inner-event-id)]
                    (when match
                      (update match :invoke-id #(vec (cons region-name %)))))))
          (:state snapshot))

    :else
    (if-let [m (find-active-spawn-all-in-tree
                 machine (rf.machines.transition/state-path (:state snapshot)) inner-event-id)]
      [m]
      [])))

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
  `child-extra` is its forwarded payload (the `:value` for a `:done`,
  the error for a `:failed`)."
  [frame-id parent-id invoke-id join-state'' child-id work-generation kind
   child-extra completed-at]
  (let [spawned-id (get-in join-state'' [:children child-id])
        reply      (rf.machines.reply/join-child-reply
                     {:parent-id    parent-id
                      :invoke-id    invoke-id
                      :child-id     child-id
                      :spawned-id   spawned-id
                      :work-generation work-generation
                      :frame        frame-id
                      :completed-at completed-at}
                     kind child-extra)
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
   child-extra completed-at]
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
                          child-id work-generation kind child-extra
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
   child-extra completed-at {:keys [fail-fired? success-fired?]}]
  (when fail-fired?
    (rf.trace/emit! :rf.machine :rf.machine.spawn-all/any-failed
                 (merge {:actor-id parent-id
                         :invoke-id invoke-id
                         :failed-id  child-id
                         :reason     child-extra
                         :failed     (:failed join-state'')
                         :done       (:done   join-state'')
                         :frame      frame-id}
                        (child-completion-reply-facts
                          frame-id parent-id invoke-id join-state''
                          child-id work-generation :failed child-extra
                          completed-at))))
  (when success-fired?
    (let [reply-facts (child-completion-reply-facts
                        frame-id parent-id invoke-id join-state''
                        child-id work-generation :done child-extra
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
  child (survivors via the `:explicit` cancellation form with one
  `:rf.machine.spawn/cancelled-on-join-resolution` trace each; completed /
  failed children via the non-cancellation `:rf.machine/join-reaped` reap
  form — rf2-tj3l6a), followed by the join-event dispatch carrying the
  decisive child's forwarded payload. Cancelling surviving siblings on the
  join decision is unconditional. Per Spec 005 §Spawn-and-join, the
  dispatched event shape is:

      [<parent-id> [<resolution-event> <decisive-child-id> & <child-extra>]]

  The `:frame` tag is REQUIRED for epoch-capture admission
  (`re-frame.epoch.capture/capture-event!` silently drops events whose
  tags lack `:frame`). The caller threads `frame-id` (resolved from
  `(:rf/frame machine)` at the interceptor's entry) so the per-survivor
  cancellation traces reach the cascade's `:trace-events` slot."
  [frame-id parent-id invoke-id spec join-state'' child-id child-extra
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
              ;; The reply-envelope facts (`:rf.reply/work-id`
              ;; keyed on the survivor's spawned instance, `:rf.reply/status
              ;; :cancelled`, `:rf.reply/cancel-reason :on-join-resolution`) ride
              ;; ADDITIVELY so the survivor cancellation joins the same
              ;; uniform work/reply row the spawn started — the spawn-all
              ;; analogue of the single-actor destroy cancellation. The
              ;; survivor's own `:rf.machine/destroy` fx ALSO closes it
              ;; through the `:rf.machine/destroyed` cancelled reply; this
              ;; trace carries the join-resolution attribution.
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
            ;; Tear down EVERY child of the resolved join — the survivors
            ;; (cancelled, traced above) AND the COMPLETED children. A
            ;; `:spawn-all` child's `:on-child-done` / `:on-child-error`
            ;; terminal state is NOT necessarily `:final?`, so a "completed"
            ;; child stays a LIVE actor after dispatching its completion.
            ;; Completed children were previously torn down ONLY when the
            ;; parent's resolution transition EXITED the `:spawn-all` state
            ;; (destroy.cljc `destroy-spawn-all-children!` in the exit
            ;; cascade) — so an INTERNAL/self resolution handler (or a parent
            ;; with no `:on` for the resolution event, which stays in the
            ;; state) leaked all N completed children's
            ;; snapshots/timers/resources (rf2-qb1j5z). Tearing down every
            ;; child HERE closes that leak regardless of whether the resolution
            ;; transition exits the state. Tearing down an already-torn-down
            ;; child (a `:final?` terminal that already auto-destroyed, or the
            ;; exit-cascade's later sweep) is a silent-idempotent no-op.
            ;;
            ;; But the teardown of a COMPLETED child is NOT a cancellation
            ;; (rf2-tj3l6a). A completed / failed child has ALREADY closed its
            ;; work attempt as a join-child completion — its `join-child-reply`
            ;; stamped the closed `:completed` / `:failed` terminal for the
            ;; canonical `[:rf.work/machine spawned-id invoke-id generation]`.
            ;; Routing it through the ordinary `:explicit` keyword destroy would
            ;; make `emit-destroyed!` attach a SECOND, contradictory
            ;; `:cancelled` terminal for the same work-id, so a work-ledger /
            ;; Xray projection could not decide whether the child completed,
            ;; failed, or was cancelled. So split by completion:
            ;;   - COMPLETED / FAILED children (`completed-ids`) → the VERIFIED
            ;;     reap form `{:rf/reap true :rf/parent-id … :rf/invoke-id …
            ;;     :rf/child-id …}`, which does the IDENTICAL teardown but
            ;;     stamps the non-cancellation `:rf.machine/join-reaped` reason
            ;;     (no second terminal). The reap carries the JOIN COORDINATES
            ;;     (parent / invoke / child), NOT a caller-chosen actor-id +
            ;;     reason: `destroy-join-reap!` re-reads the LIVE join state,
            ;;     resolves the actor-id from `:children`, and PROVES the child
            ;;     is in `:done ∪ :failed` before suppressing cancellation —
            ;;     so the cancellation-suppressing reason cannot be forged at
            ;;     the public reserved-fx boundary (rf2-3lyqzu);
            ;;   - SURVIVORS (never reported completion) → the `:explicit`
            ;;     keyword destroy, whose cancellation reply is legitimate and
            ;;     matches the `cancelled-on-join-resolution` trace above.
            (mapv (fn [[cid spawned-id]]
                    (if (contains? completed-ids cid)
                      [:rf.machine/destroy {:rf/reap      true
                                            :rf/parent-id parent-id
                                            :rf/invoke-id invoke-id
                                            :rf/child-id  cid}]
                      [:rf.machine/destroy spawned-id]))
                  children)))
        dispatch-fx
        (when resolution-event
          (let [inner (vec (concat resolution-event [child-id] child-extra))]
            [[:dispatch [parent-id inner]]]))]
    (vec (concat (or destroy-fx []) (or dispatch-fx [])))))

(defn intercept-spawn-all-event
  "Per Spec 005 §Child completion protocol. When the parent's
  handler receives an event whose inner event-id matches the active
  `:spawn-all`-bearing state's `:on-child-done` / `:on-child-error`,
  the runtime updates the join state and (on resolution) cancels surviving
  siblings + dispatches the join event. The event is NOT fed into the
  machine's normal `:on` lookup.

  Returns nil (NOT a child-event for any active `:spawn-all`) or a
  re-frame effect map with `:rf.db/runtime` (updated runtime-db — the join
  state is durable machine runtime-db state) and `:fx`
  (per-sibling destroys + the join-event dispatch). `runtime-db` is the
  frame's runtime-db partition value (the `:rf.db/runtime` coeffect)."
  [machine runtime-db _path snapshot parent-id inner-event]
  (let [inner-id (first inner-event)
        child-id (second inner-event)
        matches  (find-active-spawn-alls machine snapshot inner-id)
        ;; The completion carrier's exact-attempt coordinate, read from the ONE
        ;; coordinate slot — the recordable `:rf.cofx` fact (nil for an
        ;; unstamped / hand-crafted carrier; the metadata slot is not read).
        attempt  (carrier-attempt machine)
        ;; Select the candidate join by EXACT-ATTEMPT MATCH, BEFORE the fold
        ;; gate (rf2-wsrtlw). When more than one active `:spawn-all` matches the
        ;; event id (two parallel regions legitimately reusing the SAME
        ;; `:on-child-done` AND the SAME logical child id), routing by child-id
        ;; ownership alone mis-routes an exact-current R2 carrier to R1's join
        ;; (declaration order), where the fold gate rejects it
        ;; `:attempt-superseded` and R2 hangs. Instead: for a carrier bearing a
        ;; coordinate, select the match whose LIVE join-state IS the exact
        ;; attempt the coordinate names — same parent/invoke identity, same
        ;; `:rf/attempt` token, and child-id mapped to the coordinate's spawned
        ;; instance address. This routes the completion to the region whose join
        ;; actually spawned the child, independent of declaration order or
        ;; child-id reuse.
        exact-attempt-match?
        (fn [{invoke-id :invoke-id}]
          (and (map? attempt)
               (= parent-id (:parent-id attempt))
               (= invoke-id (:invoke-id attempt))
               (some? (:attempt attempt))
               (let [js (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))]
                 (and (map? js)
                      (= (:rf/attempt js) (:attempt attempt))
                      (= (get-in js [:children child-id]) (:spawned-id attempt))))))
        ;; child-id ownership fallback for an UNSTAMPED / malformed / unknown
        ;; carrier — it routes to a real owning join so the fold gate's
        ;; fail-closed suppression fires stable typed evidence
        ;; (`:attempt-unverified`) against it; never guesses an owner. If none
        ;; owns the child (genuinely forged child-id), fall back to the first
        ;; match so the bad-child-id error trace still fires against a real join.
        owns?    (fn [{invoke-id :invoke-id}]
                   (let [js (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))]
                     (and (map? js) (contains? (:children js) child-id))))
        match    (or (some #(when (exact-attempt-match? %) %) matches)
                     (some #(when (owns? %) %) matches)
                     (first matches))
        ;; Resolve the live frame from the runtime-stamped machine
        ;; (registration.cljc/prepare-machine-ctx assoc'd `:rf/frame` before
        ;; handing the machine to the interceptor). Threaded into
        ;; `emit-resolution-traces!` / `build-resolution-fx` AND used inline
        ;; for the late-completion + bad-child-id error traces — all of these
        ;; are dropped by epoch-capture without `:frame`.
        frame-id (:rf/frame machine)
        ;; The CAUSAL completion timestamp of the child's finishing dispatch
        ;; (the router-stamped `:rf/time-ms` off the machine def's
        ;; `:rf.cofx`, threaded by prepare-machine-ctx). Rides
        ;; the reply-envelope join-child / late-completion facts the same way
        ;; the single-`:spawn` `:rf.machine/done` reply carries it
        ;; (Managed-Effects §Causal completion metadata). nil for a pure-fn /
        ;; no-cofx caller — then omitted, not nil-filled.
        completed-at (get-in machine [:rf/cofx :rf/time-ms])]
    (when match
      (let [{:keys [spec kind] invoke-id :invoke-id} match
            ;; Per Spec 005 §Spawn-and-join: child dispatches
            ;;   [<parent-id> [<event-kw> <child-id> & extra]]
            ;; where `& extra` is the child's forwarded payload (terminal
            ;; :data slice, error reason, etc). Capture it so the
            ;; decisive child's payload can be appended onto the
            ;; resolution event AND surfaced through the
            ;; :rf.machine.spawn-all/any-failed trace's :reason key
            ;; (Spec 005 §Trace events).
            child-extra (vec (drop 2 inner-event))
            ;; Read the live join state from runtime-db (the seed was written
            ;; by :rf.machine/spawn-all-init on entry).
            join-state (get-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id))]
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

          ;; Forged / unknown child-id: the inbound `child-id` is NOT in
          ;; the seeded `:children` map. The accident class is a
          ;; hand-crafted dispatch (copy-paste from a sibling :spawn-all,
          ;; typo, cascaded event from a sibling parent) that the runtime
          ;; would otherwise silently fold into `:done` / `:failed`,
          ;; collapsing the join early. Gate it: emit a structured error
          ;; trace and short-circuit with a no-op fx (do NOT mutate the
          ;; join state). Per Spec 005 §Spawn-and-join and the machines
          ;; security-audit finding F1.
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
          ;; equal THIS join attempt: the `:rf/join-attempt` coordinate the
          ;; member child's own handler boundary stamped (`stamp-join-completion-fx`)
          ;; and carried on the recordable `:rf.cofx` transport (rf2-t154jx) —
          ;; resolved via `carrier-attempt` above as `attempt` — must equal the
          ;; current join's parent/invoke identity, logical child id, exact
          ;; current actor id, and exact attempt token. A carrier with NO
          ;; coordinate (a hand-crafted dispatch that never flowed through the
          ;; member child's boundary, OR a strict replay whose recordable fact was
          ;; stripped) is `:attempt-unverified`; a SUPERSEDED one (a prior
          ;; attempt's straggler after parent re-entry / child respawn —
          ;; including a `:fixed-actor-id` respawn where the actor id alone
          ;; cannot discriminate attempts, and an old-attempt DELAYED completion
          ;; arriving across re-entry) is `:attempt-superseded`. Both fold
          ;; NOTHING; both are zero-mutation fail-closed drops with stable typed
          ;; evidence (`:rf.reply/stale-reason` on the stale-completion trace) —
          ;; never a silent replay-success no-op.
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
          ;; `:rf.reply/work-status :suppressed` (Managed-Effects §Stale suppression):
          ;; it is SUPPRESSED from RE-RESOLVING the join — exactly the
          ;; §Stale-suppression "fires no further parent event" rule.
          ;;
          ;; Surviving siblings are unconditionally destroyed at resolution,
          ;; so a late completion is always a
          ;; genuinely stale straggler with no live join to fold into — the
          ;; record is left frozen at resolution. The public trace shape
          ;; (`:actor-id` / `:invoke-id` / `:child-id` / `:kind`) is
          ;; preserved; no `:done` / `:failed` fold, no re-resolution.
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
          ;; when an already-queued/delayed carrier retains an exact-current
          ;; coordinate. Actor
          ;; liveness is deliberately irrelevant; final-state auto-destroy has
          ;; no cancellation tombstone and its legitimate queued completion
          ;; still folds (rf2-ir4t5v).
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
                                  (:rf/attempt join-state))]
            (intercept-fold frame-id parent-id invoke-id spec join-state
                            child-id work-generation kind child-extra
                            completed-at runtime-db)))))))

(defn- intercept-fold
  "The verified fold body of `intercept-spawn-all-event` — the carrier has
  passed the live-join / child-ownership / exact-attempt fence. Read
  'compute resolution; emit traces; build fx; write back': three named
  acts plus an assoc-in."
  [frame-id parent-id invoke-id spec join-state child-id work-generation kind
   child-extra completed-at runtime-db]
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
                               child-id work-generation child-extra completed-at
                               resolution)
      (emit-child-fold-terminal! frame-id parent-id invoke-id join-state''
                                 child-id work-generation kind child-extra
                                 completed-at))
    (let [fx (build-resolution-fx frame-id parent-id invoke-id spec join-state''
                                  child-id child-extra resolution)]
      {:rf.db/runtime (assoc-in runtime-db (rf.machines.paths/spawned-path parent-id invoke-id) join-state'')
       :fx fx})))
