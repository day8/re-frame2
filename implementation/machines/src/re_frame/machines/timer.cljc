(ns re-frame.machines.timer
  "Wall-clock `:after` timer scheduling. Per Spec 005 §Delayed `:after`
  transitions.

  On entry to an `:after`-bearing state node, the pure transition engine
  (`re-frame.machines.transition`) emits one `:rf.machine/after-schedule`
  fx per `:after` entry. The fx handler here resolves the delay (pos-int?
  literal / subscription vector / fn-form), schedules a real timer via
  `interop/schedule-after!` (the Spec 005 §Clock abstraction primitive —
  `set-timeout!`'s spec-named machines surface), and (for sub-vec delays)
  installs a watcher
  that triggers cancel-and-reschedule on sub-value change. On expiry the
  timer dispatches the synthetic

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]

  back into the parent machine via the late-bound `:router/dispatch!`
  hook. Per Spec 005 §Hierarchy interaction the scheduling node's
  declaring path (`<decl-path>`) travels with EVERY timer alongside its
  per-path epoch — the event is always this 4-element shape.
  Pick-after-transition (in transition) resolves the delay-key
  against the active state path's `:after` table; epoch-mismatch surfaces
  as `:rf.machine.timer/stale-after`, epoch-match drives the transition
  through the standard cascade.

  A frame-scoped timer table tracks live handles so cancellation (state
  exit) and subscription-driven re-resolution can clear them. The table is
  partitioned per frame (`{<frame-id> {<inner-key> <entry>}}`), so
  concurrent frames in the same process — the common test-fixture and
  SSR-load shape — observe disjoint timer state. The
  epoch mechanism backstops correctness; explicit cancellation via
  `:rf.machine/after-cancel` is an optimisation that promptly releases
  the host-clock handle.

  No-op under `:platform :server` (per Spec 005 §SSR mode); the pure side
  already emitted `:rf.machine.timer/skipped-on-server` in place of
  /scheduled."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.transition :as transition]
            [re-frame.managed-timer :as managed-timer]
            [re-frame.subs :as subs]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

(defonce after-timers
  ;; Per Spec 005 §Delayed `:after` transitions: runtime-owned timer-handle
  ;; table for in-flight :after timers, partitioned per frame.
  ;;
  ;; Outer shape: {<frame-id> {<inner-key> <entry>}}.
  ;; Inner key:   {:parent <parent-id> :spawn <invoke-id-vec>
  ;;               :delay <delay-key>} — multiple delays per :after map
  ;;              have their own slot, and parallel-region machines
  ;;              partition further on the region-prefixed invoke-id.
  ;;              The key is a map rather than a positional tuple — the map
  ;;              form lets readers ignore slot order.
  ;; Entry value:
  ;;   {:handle <opaque host-clock handle>
  ;;    :reaction <subscription reaction or nil>
  ;;    :sub-watcher-key <key passed to add-watch>
  ;;    :resolved-ms <int>
  ;;    :epoch <int>
  ;;    :state <state-keyword>
  ;;    :delay-source <:literal | :sub | :fn>}
  ;;
  ;; Frame-scoping the outer key keeps the timer table out of process-global
  ;; state. Two consequences:
  ;;   (1) Two frames running concurrently see strictly disjoint inner
  ;;       tables — no cross-frame contamination of timer handles, no
  ;;       chance that one frame's `reset-timers!` clobbers another's
  ;;       in-flight handles.
  ;;   (2) `after-cancel-fx` (state-exit cleanup) scans only the active
  ;;       frame's inner map rather than every frame's combined entries —
  ;;       O(timers-this-frame) instead of O(timers-all-frames).
  ;;
  ;; Cancellation (state exit + sub re-resolution) clears the entry and
  ;; releases the handle / detaches the watcher. Frame-teardown clears the
  ;; entire inner map via the `:machines/on-frame-destroyed!` late-bind
  ;; hook invoked from `frame/destroy-frame!`.
  (atom {}))

(defn- after-timer-key
  "Inner-table key — frame-id is the OUTER key into `after-timers` and
  is intentionally absent from this map.

  The key is a `{:parent ... :spawn ... :delay ...}` map rather than a
  positional tuple — the map form lets readers ignore slot order, and the
  scan in `after-cancel-fx` reads `(:parent k)` / `(:spawn k)` rather than
  `(nth k 0)` / `(nth k 1)`. The key is opaque to callers (used only as a
  `get-in` index into `after-timers`'s inner map)."
  [parent-id invoke-id delay-key]
  {:parent parent-id
   :spawn (vec invoke-id)
   :delay  delay-key})

;; `:after` delay-source classification (`{:literal :sub :fn}`) lives once
;; in `re-frame.machines.transition/classify-delay-source` — the leaf engine
;; that owns the `:after` grammar and already tags the pure-side
;; `:rf.machine.timer/scheduled` trace from it. The fx side reuses the same
;; classifier so the two can never disagree on a delay-key's source.

(defn- resolve-delay-ms
  "Resolve an :after map key to a positive-integer ms delay. For pos-int?
  literal: returns the value. For subscription vector: subscribes via the
  late-bound subscribe-once hook and uses the resolved value. For fn:
  invokes (f snapshot) once.

  Returns [resolved-ms reaction-or-nil]. The reaction is non-nil only for
  subscription-vector delays; the caller installs an add-watch on it to
  trigger re-resolution."
  [frame-id delay-key snapshot]
  (cond
    (number? delay-key)
    [delay-key nil]

    (fn? delay-key)
    ;; A throwing fn-form `:after` emits `:rf.error/machine-after-fn-threw`
    ;; on the exception path; the fn falls through to no-clock-configured
    ;; for recovery, but the exception is observable rather than silently
    ;; swallowed.
    ;;
    ;; The `:after` delay-fn receives the unified context-map
    ;; `{:snapshot ...}` and returns a positive-int ms delay.
    (let [v (try (delay-key {:snapshot snapshot})
                 (catch #?(:clj Throwable :cljs :default) e
                   (trace/emit-error! :rf.error/machine-after-fn-threw
                                      {:exception e
                                       :frame     frame-id
                                       :recovery  :no-clock-configured})
                   nil))]
      [v nil])

    (vector? delay-key)
    ;; subscribe to keep the reaction live; caller will add-watch for
    ;; change-detection then unsubscribe on cancellation.
    (let [reaction (subs/subscribe delay-key {:frame frame-id})
          v        (when reaction
                     (try @reaction
                          (catch #?(:clj Throwable :cljs :default) e
                            ;; Canonical subscription identity: `:rf.sub/id`
                            ;; (+ `:rf.sub/query-v` for the full vector).
                            (trace/emit-error! :rf.error/machine-after-sub-threw
                                               {:exception      e
                                                :rf.sub/id      (first delay-key)
                                                :rf.sub/query-v (vec delay-key)
                                                :frame          frame-id
                                                :recovery       :no-clock-configured})
                            nil)))]
      [v reaction])

    :else
    [nil nil]))

(declare schedule-after-timer!)

(defn- release-entry-resources!
  "Best-effort release of the host-clock handle, sub-reaction watcher, and
  subscription registration belonging to a single timer-table entry. Pure
  side-effect; the caller owns the swap that removes the entry from the
  outer atom. Tolerates partial-state entries (the watcher / reaction
  slots are nil for literal- and fn-form delays).

  rf2-i4aj9c — the host-clock cancel and `remove-watch` release THIS entry's
  own captured handle / reaction (A's own host work), so they always run. The
  `subs/unsubscribe frame-id delay-key` is the ONE shared release: the
  subscription cache is ref-counted by `(frame-id, query-v)`, so once the
  cancellation trace has destroyed A and re-armed the SAME query in same-id
  B (bumping the shared count), A's decrement would dispose B's fresh
  reaction. The optional `owner-gone?` predicate (threaded from the destroy
  tail's exact-incarnation gate) skips ONLY that shared decrement once A is
  lost — B's reaction / dependency refs stay intact. `owner-gone?` is MONOTONIC.
  The 3-arity is the historical unconditional release (fixture-reset,
  frame-destroy — no event owner)."
  ([frame-id entry delay-key] (release-entry-resources! frame-id entry delay-key (constantly false)))
  ([frame-id entry delay-key owner-gone?]
   ;; Shared best-effort host-clock cancel — swallows throws and no-ops a nil
   ;; handle, tolerating the partial-state entries (literal- / fn-form delays
   ;; whose watcher / reaction slots are nil).
   (managed-timer/cancel! (:handle entry))
   (when (and (:reaction entry) (:sub-watcher-key entry))
     (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
          (catch #?(:clj Throwable :cljs :default) _ nil))
     (when (and (vector? delay-key) frame-id (not (owner-gone?)))
       (try (subs/unsubscribe frame-id delay-key)
            (catch #?(:clj Throwable :cljs :default) _ nil))))))

(defonce ^:private after-attempt-counter
  ;; Monotonic per-arm attempt-token source (mirrors core's
  ;; `dispatch-later-counter`, rf2-j538f7.2). Every `schedule-after-timer!`
  ;; arm stamps its slot with a unique token, so a trailing cancellation of an
  ;; OLD attempt can never claim a re-armed SUCCESSOR occupying the same reused
  ;; `{:parent :spawn :delay}` key (rf2-j538f7.7), and a late-returning arm can
  ;; detect that a cleanup already claimed its slot. The durable per-decl-path
  ;; EPOCH remains the transition stale-gate (a dynamic-delay re-arm keeps the
  ;; same epoch on purpose); this token is transient host-work OWNERSHIP only —
  ;; never snapshot / replay / SSR-egress state.
  (atom 0))

(defn- next-attempt-token []
  (swap! after-attempt-counter inc))

(defn- claim-entry!
  "Atomically remove the `[frame-id k]` slot IFF it still holds the attempt
  identified by `token`, pruning an emptied inner frame-map. Returns the exact
  entry removed — so the caller releases precisely what it claimed — or nil when
  the slot no longer holds this attempt: a concurrent supersede / cleanup
  already took it, or a fresh re-arm installed a SUCCESSOR under the same key.
  The token check is what stops an old cancellation from deleting a successor it
  never released (rf2-j538f7.7). The host side effect (release / host-clock
  cancel) rides the returned value OUTSIDE the retriable swap, never inside it
  (`swap-vals!` yields the pre-swap snapshot from the winning CAS attempt)."
  [frame-id k token]
  (let [[old _new]
        (swap-vals! after-timers
                    (fn [m]
                      (if (= token (:token (get-in m [frame-id k])))
                        (let [inner (dissoc (get m frame-id) k)]
                          (if (empty? inner)
                            (dissoc m frame-id)
                            (assoc m frame-id inner)))
                        m)))
        claimed (get-in old [frame-id k])]
    (when (= token (:token claimed))
      claimed)))

(defn- emit-cancelled!
  "Emit the unified `:rf.machine.timer/cancelled` trace for one
  cancellation site. Every cancellation path — state exit, machine
  destroy, subscription re-resolution, in-place supersede, frame
  destroy — flows through this single emit so consumers can pair
  scheduled→fired→cancelled by `(actor-id, state, epoch)` and branch on
  `:reason` from the closed set `:on-exit / :on-destroy / :on-resolution
  / :on-supersede / :on-frame-destroy / :on-restore` (the epoch-restore
  host-timer cleanup, `cancel-frame-timers-on-restore!`).

  Payload shape mirrors `:rf.machine.timer/scheduled` for arm-fire-
  cancel pairing — same `:actor-id` / `:state` / `:delay` / `:epoch`
  / `:frame` slots — plus the `:reason` discriminator. The dynamic-delay
  subscription identity rides under the canonical `:rf.sub/id` (the
  sub-id) plus `:rf.sub/query-v` (the full subscription vector) when the
  cancelled timer was a sub-vec delay (`:delay-source :sub`) — the same
  spelling the rest of the framework uses for a subscription trace
  identity. `:delay` reads the entry's `:resolved-ms` so the cancelled trace
  reports the wall-clock window the timer actually held, not the
  unresolved delay-key."
  [frame-id k entry reason]
  (let [delay-key    (:delay k)
        delay-source (:delay-source entry)
        sub-vec      (when (vector? delay-key) delay-key)
        ;; The timer-table `:spawn` is the region-PREFIXED invoke-id for a
        ;; region `:after` (`prefix-region-invoke-id`). The fired / stale
        ;; replies strip the region head (`pick-after-transition`'s
        ;; `carried-decl-path`), so strip it here too — using the `:region`
        ;; name recorded at schedule time — before building the reply's
        ;; `:rf.reply/work-id`. Without the strip the cancelled row's
        ;; `[:rf.work/timer [<actor> <region> <state...>] epoch]` work-id
        ;; would split from the fired / stale `[:rf.work/timer
        ;; [<actor> <state...>] epoch]` rows of the SAME logical `:after`
        ;; across the work/reply ledger (correlation only — the runtime
        ;; stale-gate keys on the region-scoped epoch slot and is unaffected).
        ;; A flat / root timer has no `:region`, so its decl-path is unchanged.
        region       (:region entry)
        decl-path    (let [sp (:spawn k)]
                       (if (and region (seq sp) (= region (first sp)))
                         (vec (rest sp))
                         sp))
        ;; Close the timer work attempt the reply-envelope way: a cancelled
        ;; `:after` timer is `:status :cancelled` DATA, not the absence of a
        ;; reply (Managed-Effects §Cancellation). The canonical `:rf.reply/work-id`
        ;; matches the fired / stale reply's so the cancelled completion
        ;; joins the same uniform work/reply row the timer's scheduling
        ;; started; `:rf.reply/cancel-reason` carries the closed `timer-cancel-reasons`
        ;; discriminator. The reply facts ride ADDITIVELY — the public trace
        ;; shape (`:actor-id` / `:state` / `:delay` / `:epoch` / `:reason` /
        ;; sub identity) is preserved.
        cancel-reply (m-reply/cancelled-timer-reply
                       {:actor-id  (:parent k)
                        :state     (:state entry)
                        :delay     (:resolved-ms entry)
                        :decl-path decl-path
                        :epoch     (:epoch entry)
                        :frame     frame-id
                        :reason    reason})
        summary      (m-reply/trace-reply cancel-reply {:frame frame-id})]
    (trace/emit! :rf.machine :rf.machine.timer/cancelled
                 (cond-> {;; the timer's owning actor INSTANCE;
                          ;; `:machine-id` is reserved for the registered TYPE.
                          :actor-id   (:parent k)
                          :state      (:state entry)
                          :delay      (:resolved-ms entry)
                          :epoch      (:epoch entry)
                          :reason     reason
                          :frame      frame-id
                          ;; reply-envelope vocabulary (Managed-Effects §9)
                          :rf.reply/work-kind            (:rf.reply/work-kind summary)
                          :rf.reply/status      (:status summary)
                          :rf.reply/work-id     (:rf.reply/work-id summary)
                          :rf.reply/work-status (:rf.reply/work-status summary)
                          :rf.reply/cancelled?  (:cancelled? summary)
                          :rf.reply/cancel-reason (:rf.reply/cancel-reason summary)
                          :rf.reply/correlation (:correlation summary)}
                   (some? delay-source) (assoc :delay-source delay-source)
                   (some? sub-vec)      (assoc :rf.sub/id      (first sub-vec)
                                               :rf.sub/query-v (vec sub-vec))))))

(defn- successor-published?-fn
  "Build the same-id-successor detection predicate for `frame-id`, captured at
  the cancellation entry BEFORE the callback-bearing `:rf.machine.timer/cancelled`
  trace (rf2-rbxdxa). Returns a 0-arity predicate true once a `destroy-frame!` +
  same-id reconstruction — a cancellation listener re-arming the SAME
  subscription-vector query in successor B — has replaced the captured
  incarnation. When the frame is absent at capture (nil token) the predicate is
  `(constantly false)`, so an ordinary cancellation with no successor releases its
  shared `(frame,query-v)` subscription ref fully.

  Precise BY CONSTRUCTION: `frame-incarnation-token` mints a DISTINCT token per
  construction, so the predicate flips only on a genuine A→B incarnation swap,
  never on a mere frame-close — no ordinary state-exit / supersede / resolution /
  frame-destroy / restore cancellation leaks its subscription. It reads no bound
  event owner, so it fences the non-destroy reasons uniformly (INCLUDING the
  eventless frame-destroy / restore paths) without the wrongful-skip a
  closing-frame `owner-continuation` gate would inflict on a frame torn down under
  an unrelated event owner. The explicit-`owner-gone?` 4-arity keeps the stronger
  destroy-tail gate its callers already captured (the actor — not the frame — is
  destroyed there, so the frame incarnation is stable and the token check would be
  too weak)."
  [frame-id]
  (let [captured (frame/frame-incarnation-token frame-id)]
    (if (some? captured)
      (fn [] (not (frame/frame-incarnation-live? frame-id captured)))
      (constantly false))))

(defn- claim-emit-release!
  "The ONE durable cancel step: claim the `[frame-id k]` slot by EXACTLY `token`,
  and only if that claim wins, emit the single owed `:rf.machine.timer/cancelled`
  trace and release the claimed entry's resources. Shared by the current-occupant
  cancel (`cancel-after-timer-entry!`) and the incarnation-exact batch cancel
  (`cancel-snapshotted-entry!`).

  Because the claim is scoped to the exact attempt `token` (an atomic
  token-guarded CAS, `claim-entry!`), a same-id successor B that re-armed key `k`
  under a FRESH globally-unique token is never claimed — the CAS fails and B's
  entry + host handle survive byte-identical (rf2-ijlhj / rf2-j538f7.7).
  `owner-gone?` fences the shared `(frame,query-v)` subscription decrement inside
  `release-entry-resources!` (rf2-i4aj9c) so A's release cannot dispose a
  reaction same-id B re-armed for the SAME query."
  [frame-id k reason token owner-gone?]
  (when-let [claimed (claim-entry! frame-id k token)]
    (emit-cancelled! frame-id k claimed reason)
    (release-entry-resources! frame-id claimed (:delay k) owner-gone?)))

(defn- cancel-after-timer-entry!
  "Cancel the CURRENT occupant of a single :after timer-table slot `[frame-id k]`,
  emitting one `:rf.machine.timer/cancelled` trace stamped with
  `reason` (closed set: `:on-exit / :on-destroy / :on-resolution /
  :on-supersede / :on-frame-destroy / :on-restore`). Idempotent —
  a second call against the same `[frame-id k]` is a no-op (the entry
  is gone so no trace fires).

  Reads the current occupant's `:token` and atomically CLAIMS the slot only while
  that token is still current (`claim-entry!`), so if a concurrent re-arm
  published a SUCCESSOR under the same key between the read and the claim, this
  cancellation leaves the successor untouched and emits nothing (rf2-j538f7.7). A
  cancellation that claims an ARMING sentinel (`:handle nil`, host clock not yet
  armed) still emits the owed trace and releases the held subscription; the
  arming thread's publish phase then finds its token gone and cancels the
  returned host handle.

  Used by the reschedule single-cancels — `schedule-after-timer!`'s leading
  `:on-supersede` and `on-sub-changed!`'s `:on-resolution` — which DELIBERATELY
  cancel whatever attempt currently holds the key before installing / rescheduling
  over it. BATCH cancellations (`after-cancel-fx`, actor / frame destroy, restore)
  must NOT re-read the current occupant — a same-id successor B could have re-armed
  the key on a prior cancellation's callback stack, and re-reading would claim B —
  so they use `cancel-snapshotted-entry!` instead, binding the claim to the
  incarnation's OWN attempt token captured at batch entry (rf2-ijlhj).

  rf2-i4aj9c / rf2-rbxdxa — `emit-cancelled!` is CALLBACK-BEARING; `owner-gone?`
  (defaulting to a same-id-successor predicate captured BEFORE the trace) is
  threaded into `release-entry-resources!` so the shared `subs/unsubscribe`
  decrement is skipped once A is lost, while an ordinary cancellation with no
  successor still releases fully."
  ([frame-id k reason] (cancel-after-timer-entry! frame-id k reason (successor-published?-fn frame-id)))
  ([frame-id k reason owner-gone?]
   (when-let [entry (get-in @after-timers [frame-id k])]
     (claim-emit-release! frame-id k reason (:token entry) owner-gone?))))

(defn- cancel-snapshotted-entry!
  "Cancel a batch-SNAPSHOTTED `[k entry]` pair, binding the claim to the EXACT
  attempt token the batch OBSERVED — not the slot's current occupant (rf2-ijlhj).

  A batch (`after-cancel-fx`, `cancel-actor-timers!`, `cancel-all-timers!`,
  `cancel-frame-timers-on-restore!`) snapshots the incarnation A's entries, then
  cancels them one by one. Each cancellation's `:rf.machine.timer/cancelled` trace
  is CALLBACK-BEARING: a listener can destroy A and publish same-id B, re-arming a
  reused `{:parent :spawn :delay}` key under a FRESH token, on that trace's own
  stack (deterministic) — or a JVM thread can do the same between snapshot and
  claim. Re-reading the current occupant to source the claim token (the old
  `cancel-after-timer-entry!` shape) would then claim/remove B. Sourcing the token
  from the SNAPSHOT `entry` instead makes the claim incarnation-exact: B's
  fresh-token entry fails the atomic CAS and survives untouched. `owner-gone?` is
  the batch's ONE captured incarnation predicate, threaded into the release."
  [frame-id k entry reason owner-gone?]
  (claim-emit-release! frame-id k reason (:token entry) owner-gone?))

(defn- on-sub-changed!
  "Watch callback invoked when a subscription-vector delay's value
  changes. Per Spec 005 §Dynamic delay re-resolution: cancel the prior
  in-flight timer, emit `:rf.machine.timer/cancelled` (the unified
  cancellation event with `:reason :on-resolution`), and reschedule a
  fresh timer at the new resolution time. Epoch is
  unchanged (the snapshot's :state hasn't moved); we read it back from
  the live snapshot at reschedule-time so a concurrent state change is
  caught by the epoch invariant when the new timer fires."
  [frame-id parent-id invoke-id delay-key state old-v new-v]
  (when-not (= old-v new-v)
    (let [k (after-timer-key parent-id invoke-id delay-key)
          ;; rf2-ijlhj — capture the owning incarnation BEFORE the callback-bearing
          ;; `:on-resolution` cancel. `cancel-after-timer-entry!` fires the
          ;; `:rf.machine.timer/cancelled` trace, whose listener can destroy A and
          ;; publish same-id B on this stack; the bare-ID runtime read + reschedule
          ;; below would otherwise resolve `still-here?` against B's snapshot and
          ;; install A-derived timer work into B. This ONE predicate fences both the
          ;; cancel's shared-subscription release and the reschedule continuation.
          owner-gone? (successor-published?-fn frame-id)]
      (cancel-after-timer-entry! frame-id k :on-resolution owner-gone?)
      ;; Machine snapshots are durable runtime-db state. The post-cancel read +
      ;; reschedule run ONLY while the captured incarnation still owns the frame;
      ;; once a cancellation listener has replaced A with B, nothing A-derived is
      ;; read from or installed into the successor.
      (when-let [rt (and (not (owner-gone?))
                         (frame/frame-runtime-db-value frame-id))]
        (let [snap (get-in rt (paths/snapshot-path parent-id))
              ;; Per Spec 005 §Per-region :after scoping: for parallel-region
              ;; machines the snapshot's :state is a map
              ;; of region-name → that region's state, and the invoke-id
              ;; is `[<region-name> <state...>]` (prefix-region-invoke-id).
              ;; Resolve the active path inside the bearing region; the
              ;; epoch lives at the per-region epoch slot.
              parallel-snap? (and snap (map? (:state snap)))
              ;; Per Spec 005 §Hierarchy interaction: the epoch slot holds
              ;; a per-decl-path map `{<path> <int>}`, so the per-region /
              ;; flat base path is suffixed with the scheduling node's
              ;; decl-path to read the node's own epoch.
              [in-region-invoke-id active epoch-slot]
              (if parallel-snap?
                (let [rn (first invoke-id)
                      iid-tail (vec (rest invoke-id))]
                  [iid-tail
                   (when-let [rs (get (:state snap) rn)] (transition/state-path rs))
                   [:data :rf/after-epoch-by-region rn iid-tail]])
                [invoke-id (when snap (transition/state-path (:state snap)))
                 [:data :rf/after-epoch (vec invoke-id)]])
              still-here? (and active
                                (= (vec in-region-invoke-id)
                                   (vec (take (count in-region-invoke-id) active))))]
          (when still-here?
            (let [epoch (or (get-in snap epoch-slot) 0)]
              (schedule-after-timer! frame-id parent-id invoke-id state
                                      delay-key epoch false snap
                                      {:emit-scheduled-trace? true}))))))))

(defn- schedule-after-timer!
  "Internal helper: resolve the delay, install the host-clock timer, and
  (for sub-vec delays) install the change-watcher. The
  `:rf.machine.timer/scheduled` (or `/skipped-on-server`) trace is
  emitted by the pure-code side (apply-transition-once) at machine-
  transition time; this fn emits a fresh `/scheduled` (paired with the
  unified `/cancelled :reason :on-resolution`) only when called from a
  subscription-change watcher.

  Idempotent against the timer-table key — cancels any prior entry
  before installing the new one. The leading cancel emits a
  `:rf.machine.timer/cancelled` trace with `:reason :on-supersede` —
  the only path that hits this branch with a live prior entry is
  `cancel-and-reschedule` (initial schedule against an empty slot is a
  no-op cancel).

  rf2-ijlhj — the leading `:on-supersede` cancel is CALLBACK-BEARING when it
  supersedes a LIVE prior entry (the reschedule path): its
  `:rf.machine.timer/cancelled` listener can destroy owning incarnation A and
  publish same-id B on this stack. The incarnation captured at entry
  (`owner-gone?`) is rechecked AFTER that cancel and BEFORE any delay
  resolution / slot reservation / host arm, so a superseding reschedule installs
  NO A-derived host work (or subscription ref-count) into successor B. An INITIAL
  schedule supersedes an empty slot (no trace, no callback), so the recheck is a
  no-op pass-through and the arm proceeds normally."
  [frame-id parent-id invoke-id state delay-key epoch server? snapshot
   {:keys [emit-scheduled-trace?]}]
  (let [delay-source (transition/classify-delay-source delay-key)
        k            (after-timer-key parent-id invoke-id delay-key)
        ;; rf2-ijlhj — the owning incarnation, captured BEFORE the callback-bearing
        ;; `:on-supersede` cancel and rechecked before the durable arm below.
        owner-gone?  (successor-published?-fn frame-id)
        ;; A region `:after`'s `invoke-id` is region-PREFIXED
        ;; (`prefix-region-invoke-id` prepends the region name). Record that
        ;; region name in the entry so `emit-cancelled!` can strip it before
        ;; building the reply's `:rf.reply/work-id` — matching the fired / stale
        ;; rows (whose `carried-decl-path` is region-stripped by
        ;; `pick-after-transition`) so all four rows of ONE logical `:after`
        ;; share one work-id in the work/reply ledger. Parallel machines carry
        ;; a region-name → state MAP as `:state` (mirrors `on-sub-changed!`);
        ;; a flat / compound machine's `:state` is a keyword / vector path (no
        ;; region head), and the parallel-ROOT timer's `invoke-id` is empty
        ;; (`[]`, no head) — both yield nil, so nothing is stripped.
        region       (when (and snapshot
                                 (map? (:state snapshot))
                                 (seq invoke-id))
                       (first invoke-id))]
    (cancel-after-timer-entry! frame-id k :on-supersede owner-gone?)
    ;; rf2-ijlhj — a superseding cancellation's listener may have replaced A with
    ;; same-id B on the stack above; gate every durable step (delay resolution,
    ;; subscription hold, slot reservation, host arm, publish) on the captured
    ;; incarnation so no A-derived timer work lands on B. Initial schedules keep
    ;; the live owner (empty-slot supersede fires no callback), so this passes.
    (when-not (owner-gone?)
     (cond
      server?
      ;; Pure-side already emitted :skipped-on-server; no-op here.
      nil

      :else
      (let [[resolved-ms reaction] (resolve-delay-ms frame-id delay-key snapshot)]
        (cond
          (or (not (number? resolved-ms))
              (not (pos? resolved-ms)))
          ;; Bad delay resolution — emit advisory and skip.
          ;;
          ;; `resolve-delay-ms` for a subscription-vector delay calls
          ;; `subs/subscribe` (bumping the sub-cache ref-count) BEFORE we
          ;; know whether the resolved value is usable. The bad-delay branch
          ;; short-circuits and stores nothing in `after-timers`, so no
          ;; future `cancel-after-timer-entry!` will ever run
          ;; `release-entry-resources!` to drop the ref. Pair the subscribe
          ;; with an `unsubscribe` here so every exit path balances the
          ;; ref-count. Per Spec 006 §Reference counting and disposal.
          (do
            (when (and reaction (vector? delay-key))
              (try (subs/unsubscribe frame-id delay-key)
                   (catch #?(:clj Throwable :cljs :default) _ nil)))
            (trace/emit! :warning :rf.warning/no-clock-configured
                         ;; the timer's owning actor is a LIVE INSTANCE;
                         ;; address it by `:actor-id`, not `:machine-id`
                         ;; (reserved for the registered TYPE).
                         {:actor-id     parent-id
                          :state        state
                          :delay-key    delay-key
                          :delay-source delay-source
                          :frame        frame-id
                          :recovery     :skipped}))

          :else
          ;; TWO-PHASE, TOKEN-OWNED arm (rf2-j538f7.7; mirrors core
          ;; `:dispatch-later`, rf2-j538f7.2). The host clock is armed BETWEEN
          ;; reserving the slot and publishing the handle, so a concurrent
          ;; cleanup (frame / actor / state-exit / restore / supersede) — or a
          ;; host scheduler that fires the callback synchronously before
          ;; `arm!` returns — can atomically CLAIM this attempt and leave the
          ;; publish phase to find its token gone and cancel the orphan handle.
          (let [token     (next-attempt-token)
                watch-key (when (= :sub delay-source)
                            [::after-watch frame-id parent-id invoke-id delay-key])]
            (when emit-scheduled-trace?
              (trace/emit! :rf.machine :rf.machine.timer/scheduled
                           (cond-> {;; the timer's owning actor INSTANCE;
                                    ;; `:machine-id` is reserved for the
                                    ;; registered TYPE.
                                    :actor-id     parent-id
                                    :state        state
                                    :delay        resolved-ms
                                    :delay-source delay-source
                                    :epoch        epoch
                                    :frame        frame-id}
                             ;; canonical subscription identity:
                             ;; `:rf.sub/id` + `:rf.sub/query-v`.
                             (= :sub delay-source)
                             (assoc :rf.sub/id      (first delay-key)
                                    :rf.sub/query-v (vec delay-key)))))
            ;; PHASE 1 — RESERVE the slot with an arming sentinel (`:handle
            ;; nil`) carrying this attempt's `:token`, BEFORE arming any host
            ;; clock. The reaction + watch-key ride the sentinel so a claiming
            ;; cleanup releases the held subscription ref-count even though the
            ;; physical add-watch is deferred to publication.
            (swap! after-timers assoc-in [frame-id k]
                   {:handle          nil
                    :reaction        reaction
                    :sub-watcher-key watch-key
                    :resolved-ms     resolved-ms
                    :epoch           epoch
                    :state           state
                    :region          region
                    :delay-source    delay-source
                    :token           token})
            (let [handle
                  ;; Shared positive-delay-guarded arm — the host-clock arm
                  ;; step both timer artefacts share. `resolved-ms` is already
                  ;; known-positive here (the non-positive case was handled by
                  ;; the prior `cond` branch), so the guard is a no-op
                  ;; pass-through.
                  (managed-timer/arm!
                    (fn []
                      ;; ATOMICALLY claim THIS fire's slot — dispatch AUTHORITY
                      ;; + reap in a single swap (mirrors core `:dispatch-later`,
                      ;; rf2-j538f7.2). If a cleanup / supersede claimed the slot
                      ;; first — even a synchronous host callback firing before
                      ;; `arm!` returns, or a JVM cleanup racing the arm — this
                      ;; fire has LOST authority: suppress the dead-on-arrival
                      ;; dispatch and touch nothing (the claimer already released
                      ;; our resources). On the live path the claim wins:
                      (when-let [claimed (claim-entry! frame-id k token)]
                        (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
                          ;; Stamp `:source :after-timer` so the Epoch panel's
                          ;; DISPATCH step labels it "from :after timer" and
                          ;; Xray's L2 timeline can prefix the row + per-source
                          ;; filter pills can discriminate timer-fired cascades
                          ;; (closed-enum per Spec-Schemas
                          ;; §`:rf/dispatch-envelope`). Per Spec 005 §Hierarchy
                          ;; interaction: carry the scheduling node's decl-path
                          ;; (`invoke-id`) so the pure side routes the firing to
                          ;; the correct per-path epoch — colliding delay-keys
                          ;; across hierarchy levels resolve unambiguously.
                          (dispatch! [parent-id [:rf.machine.timer/after-elapsed
                                                 delay-key epoch (vec invoke-id)]]
                                     {:frame  frame-id
                                      :source :after-timer}))
                        ;; A one-shot `:after` timer fires exactly once per
                        ;; arming (XState §delayed transitions). Release THIS
                        ;; fire's sub-reaction watcher + held subscription
                        ;; ref-count (the host handle just fired, so its cancel
                        ;; is a harmless no-op). This is what keeps a
                        ;; GUARD-SUPPRESSED fire (no state exit → no `:on-exit`
                        ;; cancel) from stranding the entry, and stops a spent
                        ;; one-shot from re-arming on a later sub-vec change. The
                        ;; release is silent — a fired timer was not cancelled,
                        ;; so no `:cancelled` trace is emitted.
                        (release-entry-resources! frame-id claimed (:delay k))))
                    resolved-ms)
                  ;; PHASE 2 — PUBLISH the host handle IFF this attempt still
                  ;; owns the slot. `swap-vals!` is a pure replace-if-token-
                  ;; present; the cancel/add-watch side effects below ride the
                  ;; CAS-derived old snapshot, never inside the swap.
                  [old _new]
                  (swap-vals! after-timers
                              (fn [m]
                                (if (= token (:token (get-in m [frame-id k])))
                                  (assoc-in m [frame-id k :handle] handle)
                                  m)))
                  owned? (= token (:token (get-in old [frame-id k])))]
              (if owned?
                (do
                  ;; We own the now-armed slot — attach the dynamic-delay
                  ;; change-watcher (a sub-value change cancels + reschedules).
                  ;; Attached ONLY here so a publication that LOST to cleanup
                  ;; never strands an orphan watcher on a released reaction.
                  (when (and reaction watch-key)
                    ;; Surface `add-watch` exceptions rather than silently
                    ;; dropping them; the re-resolution watcher won't fire if
                    ;; `add-watch` failed, so the author needs a signal the
                    ;; dynamic-delay subscription is not actually wired up.
                    (try
                      ;; ACTIVATE, then watch — the order is the whole fix for
                      ;; rf2-wmpte, and it mirrors
                      ;; `re-frame.substrate.observation/build-node-handle!`
                      ;; (rf2-8cnxg / rf2-jt8vz).
                      ;;
                      ;; `resolve-delay-ms`'s "subscribe to keep the reaction
                      ;; live" is FALSE on the ratom family, and silently so. A
                      ;; `reagent.ratom/Reaction` learns its sources ONLY through
                      ;; `deref-capture`; the plain `deref` `resolve-delay-ms`
                      ;; takes runs outside `*ratom-context*` with no `auto-run`,
                      ;; so it runs the body raw and leaves `watching` nil. The
                      ;; node is then in no source's watcher set: `_handle-change`
                      ;; is never called, `_queued-run` short-circuits on
                      ;; `(some? watching)`, and the `add-watch` below RECORDS a
                      ;; callback that can never fire. The first arming resolved
                      ;; correctly and the dynamic delay then never re-resolved
                      ;; for the rest of the arming's life — no trace, no error.
                      ;;
                      ;; A Reagent COMPONENT never hits this because its render
                      ;; IS the capture context; a timer is not a component, so
                      ;; nothing but this call supplies one. It runs BEFORE
                      ;; `add-watch` so activation's own first recompute cannot
                      ;; fan a priming change at this watcher. Total and
                      ;; idempotent: the React-hook spine (UIx / Helix /
                      ;; re-frame.ui / Freehand) is push-based from birth and
                      ;; publishes no hook, so the routed chain-bottom returns
                      ;; nil; plain-atom / JVM derived values have no capture
                      ;; step. A throw here lands on the same
                      ;; `:recovery :static-delay` signal as a failed
                      ;; `add-watch` — both mean the dynamic delay is not wired.
                      (interop/activate-derived-value! reaction)
                      (add-watch reaction watch-key
                                 (fn [_ _ old-v new-v]
                                   (on-sub-changed! frame-id parent-id invoke-id
                                                    delay-key state old-v new-v)))
                      (catch #?(:clj Throwable :cljs :default) e
                        ;; Owning actor INSTANCE under `:actor-id`; canonical
                        ;; subscription identity (`:rf.sub/id` + `:rf.sub/query-v`).
                        (trace/emit-error! :rf.error/machine-after-watch-failed
                                           {:exception      e
                                            :actor-id       parent-id
                                            :rf.sub/id      (first delay-key)
                                            :rf.sub/query-v (vec delay-key)
                                            :frame          frame-id
                                            :recovery       :static-delay}))))
                  handle)
                ;; A cleanup / supersede / synchronous fire claimed the sentinel
                ;; while we armed — do NOT republish; cancel the orphan handle
                ;; (idempotent even if it already fired). The claimer already
                ;; released the reaction and emitted the single owed `:cancelled`
                ;; trace (or, for a self-fire, dispatched + released silently).
                (do (managed-timer/cancel! handle)
                    nil))))))))))

(defn after-schedule-fx
  "fx handler for `:rf.machine/after-schedule`. Per Spec 005 §Delayed
  `:after` transitions, on entry to an :after-bearing state node the
  runtime emits one of these per :after entry. The handler
  resolves the delay (literal pos-int? / subscription vector / fn),
  schedules a real wall-clock timer via `interop/schedule-after!` (Spec
  005 §Clock abstraction), and (for
  subscription delays) installs an add-watch that triggers
  cancel-and-reschedule on sub-value change.

  The synthetic event dispatched on expiry is

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]]

  (the scheduling node's declaring path travels with the timer per Spec
  005 §Hierarchy interaction) which routes through pick-after-transition's
  per-node epoch check and (on match) through the standard transition
  cascade.

  No-op under `:platform :server` (per Spec 005 §SSR mode)."
  [{frame-id :frame} args]
  (let [;; The cascade envelope frame is the fx-context `:frame`; a nil
        ;; stamp is an invariant failure (`:rf.error/no-frame-context`),
        ;; never a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/after-schedule
                     {:where 'rf.machine/after-schedule
                      :event-id (:rf/parent-id args)})
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        state      (:state args)
        delay-key  (:delay-key args)
        epoch      (:epoch args)
        server?    (boolean (:server? args))
        ;; Machine snapshots are durable runtime-db state.
        snapshot   (get-in (frame/frame-runtime-db-value frame-id)
                           (paths/snapshot-path parent-id))]
    ;; Initial state-entry scheduling — the :scheduled trace was already
    ;; emitted synchronously by apply-transition-once (the pure side). For
    ;; sub-vec delays, the fx layer's resolution may yield a different
    ;; :delay value than the pure-side reported as :delay-key; if so, the
    ;; sub-changed watcher emits a follow-up /scheduled with the resolved
    ;; ms once the subscription's first-read completes — but for the
    ;; common case where the sub's value is stable across the schedule
    ;; window the pure-side trace stands.
    (schedule-after-timer! frame-id parent-id invoke-id state
                            delay-key epoch server? snapshot
                            {:emit-scheduled-trace? false})
    nil))

(defn after-cancel-fx
  "fx handler for `:rf.machine/after-cancel`. Emitted on exit from an
  :after-bearing state node to release the host-clock timer handles and
  any subscription watchers attached to the prior visit's timers. The
  epoch-mismatch invariant backstops correctness if a timer fires before
  this fx runs; this handler is the fast-path that prevents zombie
  watchers and releases timer slots promptly.

  Each cancellation emits one `:rf.machine.timer/cancelled` trace with
  `:reason :on-exit` so the scheduled→fired→cancelled pairing in the Xray
  Handler section's AFTER TIMERS sub-section can attribute the cancel to
  the state-exit cause.

  The scan is bounded by the active frame's inner table — siblings'
  timers in other frames are not walked. The inner key is
  `{:parent ... :spawn ... :delay ...}`, so the only cross-key axis we
  iterate is `:delay` (one entry per :after map entry on the bearing
  state node — typically 1-3 entries)."
  [{frame-id :frame} args]
  (let [;; The cascade envelope frame is the fx-context `:frame`; a nil
        ;; stamp is an invariant failure (`:rf.error/no-frame-context`),
        ;; never a synthesised `:rf/default`.
        frame-id  (frame/require-frame-stamp!
                    frame-id :rf.machine/after-cancel
                    {:where 'rf.machine/after-cancel
                     :event-id (:rf/parent-id args)})
        parent-id (:rf/parent-id args)
        invoke-id (vec (:rf/invoke-id args))
        ;; rf2-ijlhj — bind the whole batch to ONE captured incarnation. Every
        ;; `:rf.machine.timer/cancelled` trace is callback-bearing; a listener can
        ;; destroy A and re-arm a same-key successor B on the FIRST cancellation's
        ;; own stack. Two protections, both keyed off the entries SNAPSHOTTED here:
        ;;   (1) cancel by the snapshotted attempt token (`cancel-snapshotted-entry!`),
        ;;       never re-reading the current occupant — B's fresh-token re-arm fails
        ;;       the atomic claim, so a within-key A→B swap can't remove B;
        ;;   (2) short-circuit the loop the instant ownership is lost, so the
        ;;       remaining keys (which B may have re-armed) are never even visited.
        owner-gone? (successor-published?-fn frame-id)
        snap        (into []
                          (filter (fn [[k _]] (and (= parent-id (:parent k))
                                                   (= invoke-id (:spawn k)))))
                          (get @after-timers frame-id))]
    (loop [pairs snap]
      (when (and (seq pairs) (not (owner-gone?)))
        (let [[k entry] (first pairs)]
          (cancel-snapshotted-entry! frame-id k entry :on-exit owner-gone?))
        (recur (rest pairs))))
    nil))

(defn cancel-actor-timers!
  "Cancel every in-flight `:after` timer owned by `parent-id` under
  `frame-id`. Emits one `:rf.machine.timer/cancelled` trace per entry
  with `:reason :on-destroy`. Called from the machine-destroy paths so
  trace consumers see the cancellation cause distinct from state-exit /
  frame-destroy cancellations.

  Pure side-effect — no return value. No-op when the actor has no
  in-flight timers. The transition engine's epoch invariant backstops
  any timer that fires between destroy and host-clock teardown; this
  helper releases the host-clock handle eagerly and emits the trace
  so the Xray Handler section can attribute the cancel to the actor's
  destroy event.

  rf2-4ipqe4 / rf2-ijlhj — each `:rf.machine.timer/cancelled` emit is
  CALLBACK-BEARING: a listener can synchronously destroy the finishing actor's
  owning frame incarnation A and publish a same-id successor B ON THE FIRST
  CANCELLATION's own stack, re-arming a reused key under a fresh token. The loop
  SNAPSHOTS A's `[k entry]` pairs up front and cancels each by its snapshotted
  attempt token (`cancel-snapshotted-entry!`), so a re-read can never claim B's
  fresh-token entry; the destroy tail that follows this call (classification /
  spawn-order / system-id release) resolves bare frame/actor ids to the CURRENT
  incarnation B. The optional `owner-gone?` predicate (the finalize cascade's
  exact-incarnation gate) is rechecked BEFORE each cancellation, so once the first
  cancellation loses A the loop short-circuits and never touches B's timer, and it
  is threaded into the release so A's `subs/unsubscribe` cannot decrement a
  reaction B re-armed for the same query. `owner-gone?` is MONOTONIC (once A→B it
  stays gone). The 2-arity (the imperative `destroy` tail, which carries no event
  owner) passes `(constantly false)` — its historical behaviour, now still safe
  because the snapshot-token claim alone fences B's entry."
  ([frame-id parent-id]
   (cancel-actor-timers! frame-id parent-id (constantly false)))
  ([frame-id parent-id owner-gone?]
   (when (and frame-id parent-id)
     (loop [pairs (into []
                        (filter (fn [[k _]] (= parent-id (:parent k))))
                        (vec (get @after-timers frame-id)))]
       (when (and (seq pairs) (not (owner-gone?)))
         (let [[k entry] (first pairs)]
           (cancel-snapshotted-entry! frame-id k entry :on-destroy owner-gone?))
         (recur (subvec pairs 1)))))))

(defn cancel-all-timers!
  "Cancel every in-flight :after timer the runtime is currently tracking
  and reset the timer table.

  0-arity: every frame's timers (fixture teardown — `reset-timers!`).
  Silent — fixture-reset is test-isolation cleanup, not a runtime
  cancellation event; emitting traces here would pollute the trace
  stream observed by the next test.

  1-arity: just the given frame's timers (`frame/destroy-frame!` hook).
  Each cancelled timer emits one `:rf.machine.timer/cancelled` trace with
  `:reason :on-frame-destroy` so the Handler section's AFTER TIMERS
  sub-section can pair scheduled → cancelled on frame teardown.

  The timer table is partitioned per frame; the 1-arity variant releases
  the destroyed frame's host-clock handles and subscription watchers
  without touching sibling frames' state.

  rf2-ijlhj — the 1-arity is a BATCH: each `:rf.machine.timer/cancelled` trace is
  callback-bearing, so a listener could re-arm a same-key successor B on the first
  cancellation's stack. It snapshots the `[k entry]` pairs, cancels each by its
  snapshotted attempt token (`cancel-snapshotted-entry!`, never re-reading — a
  re-read would claim B), and short-circuits once the captured incarnation is
  lost. The 0-arity fixture-reset stays a SILENT bulk release (no traces, no
  successor semantics — test-isolation cleanup)."
  ([]
   (doseq [[frame-id inner] @after-timers
           [k entry] inner]
     (release-entry-resources! frame-id entry (:delay k)))
   (reset! after-timers {}))
  ([frame-id]
   (let [owner-gone? (successor-published?-fn frame-id)]
     (loop [pairs (vec (get @after-timers frame-id))]
       (when (and (seq pairs) (not (owner-gone?)))
         (let [[k entry] (first pairs)]
           (cancel-snapshotted-entry! frame-id k entry :on-frame-destroy owner-gone?))
         (recur (subvec pairs 1)))))))

(defn cancel-frame-timers-on-restore!
  "Cancel every in-flight `:after` timer the given `frame-id` currently holds,
  emitting one `:rf.machine.timer/cancelled` trace per entry with
  `:reason :on-restore`.

  Epoch restore installs the captured durable frame-state (the machine
  snapshots in runtime-db) WHOLESALE, so timer LIVENESS reverts atomically —
  a pre-restore timer that fires later carries a stale per-path epoch and is
  silently dropped via `:rf.machine.timer/stale-after` (Spec 005 §Hierarchy
  interaction). But the host-clock HANDLE itself is NOT frame-state: it stays
  attached to the pre-restore timeline and would still fire (and dispatch a
  doomed-stale synthetic event) unless released. This is the restore
  counterpart of the `:on-frame-destroy` cleanup `cancel-all-timers!` does on
  teardown — it releases the orphaned host handles eagerly so the restored
  frame carries no leaked wall-clock timers from the unwound epochs
  (Managed-Effects §restore: \"epoch restore MUST NOT revive host work\").

  Published as the `:machines/on-frame-restored!` late-bind hook, consulted by
  the epoch restore boundary (`perform-restore!`) AFTER a successful install (the
  frame stays LIVE across a restore — it is a wholesale runtime-db swap, not a
  destroy). No-op when the frame holds no in-flight timers.

  rf2-ijlhj — a BATCH like `cancel-all-timers!`: it snapshots the `[k entry]`
  pairs, cancels each by its snapshotted attempt token (`cancel-snapshotted-entry!`,
  never re-reading), and short-circuits once a callback-published same-id
  successor B has replaced the incarnation captured at entry — so no `:on-restore`
  cancel lands on B."
  [frame-id]
  (let [owner-gone? (successor-published?-fn frame-id)]
    (loop [pairs (vec (get @after-timers frame-id))]
      (when (and (seq pairs) (not (owner-gone?)))
        (let [[k entry] (first pairs)]
          (cancel-snapshotted-entry! frame-id k entry :on-restore owner-gone?))
        (recur (subvec pairs 1)))))
  nil)
