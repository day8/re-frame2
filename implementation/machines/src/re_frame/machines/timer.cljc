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
  slots are nil for literal- and fn-form delays)."
  [frame-id entry delay-key]
  ;; Shared best-effort host-clock cancel — swallows throws and no-ops a nil
  ;; handle, tolerating the partial-state entries (literal- / fn-form delays
  ;; whose watcher / reaction slots are nil).
  (managed-timer/cancel! (:handle entry))
  (when (and (:reaction entry) (:sub-watcher-key entry))
    (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
         (catch #?(:clj Throwable :cljs :default) _ nil))
    (when (and (vector? delay-key) frame-id)
      (try (subs/unsubscribe frame-id delay-key)
           (catch #?(:clj Throwable :cljs :default) _ nil)))))

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
                        :decl-path (:spawn k)
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

(defn- cancel-after-timer-entry!
  "Cancel and clear a single :after timer-table entry under `frame-id`,
  emitting one `:rf.machine.timer/cancelled` trace stamped with
  `reason` (closed set: `:on-exit / :on-destroy / :on-resolution /
  :on-supersede / :on-frame-destroy / :on-restore`). Idempotent —
  a second call against the same `[frame-id k]` is a no-op (the entry
  is gone so no trace fires)."
  [frame-id k reason]
  (when-let [entry (get-in @after-timers [frame-id k])]
    (emit-cancelled! frame-id k entry reason)
    (release-entry-resources! frame-id entry (:delay k))
    ;; Drop the inner-table entry; drop the outer-table entry if this was
    ;; the frame's last live timer so a frame that briefly held timers
    ;; doesn't leave a stale empty map behind.
    (swap! after-timers
           (fn [m]
             (let [inner (dissoc (get m frame-id) k)]
               (if (empty? inner)
                 (dissoc m frame-id)
                 (assoc m frame-id inner)))))))

(defn- reap-fired-entry!
  "Reap a one-shot `:after` entry whose host-clock timer has just FIRED —
  release its sub-reaction watcher + subscription registration and drop the
  inner-table slot. Distinct from `cancel-after-timer-entry!`: a fired timer
  was NOT cancelled (the host clock ran to completion), so this emits NO
  `:rf.machine.timer/cancelled` trace — the fired/stale-after trace already
  records the synthetic event's fate. This is the host-clock counterpart of
  the cancellation paths: it exists so the COMMON case where a fire is
  guard-suppressed (the synthetic event is discarded, the state does NOT
  exit, so no `:on-exit` cancel ever runs) does not strand the entry — a leak
  of the add-watch + held subscription ref-count, and — worse — a live
  watcher that would re-arm a fresh timer for a one-shot that already
  fired-and-was-discarded the next time the sub-vec delay's value changes.
  Per XState v5 semantics a delayed transition's timer fires once per arming;
  a guard-rejected fire does NOT re-schedule.

  EPOCH-GUARDED. The reap runs only when the slot still holds THIS fire's
  entry — same `:epoch` as the one the timer was armed at. A real-transition
  fire exits the state, runs `:on-exit` cancel, and re-entry installs a FRESH
  entry under the same key at a strictly-greater per-decl-path epoch; the
  guard refuses to clobber that successor (and refuses to double-reap an
  already-cancelled slot). Returns nil.

  `vec`-snapshots nothing — a single `get-in` + a compare-and-dissoc swap that
  re-reads the epoch inside the swap fn so a concurrent re-arm between the
  read and the swap is not clobbered on the JVM target."
  [frame-id k fired-epoch]
  (when-let [entry (get-in @after-timers [frame-id k])]
    (when (= fired-epoch (:epoch entry))
      (release-entry-resources! frame-id entry (:delay k))
      (swap! after-timers
             (fn [m]
               ;; Re-read the entry inside the swap: only dissoc when the slot
               ;; STILL carries this fire's epoch, so a concurrent re-arm that
               ;; landed a fresh entry between the outer read and this swap is
               ;; preserved rather than dropped.
               (if (= fired-epoch (:epoch (get-in m [frame-id k])))
                 (let [inner (dissoc (get m frame-id) k)]
                   (if (empty? inner)
                     (dissoc m frame-id)
                     (assoc m frame-id inner)))
                 m)))))
  nil)

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
    (let [k (after-timer-key parent-id invoke-id delay-key)]
      (cancel-after-timer-entry! frame-id k :on-resolution)
      ;; Machine snapshots are durable runtime-db state.
      (when-let [rt (frame/frame-runtime-db-value frame-id)]
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
  no-op cancel)."
  [frame-id parent-id invoke-id state delay-key epoch server? snapshot
   {:keys [emit-scheduled-trace?]}]
  (let [delay-source (transition/classify-delay-source delay-key)
        k            (after-timer-key parent-id invoke-id delay-key)]
    (cancel-after-timer-entry! frame-id k :on-supersede)
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
          (let [_ (when emit-scheduled-trace?
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
                handle
                ;; Shared positive-delay-guarded arm — the host-clock arm
                ;; step both timer artefacts share. `resolved-ms`
                ;; is already known-positive here (the non-positive case was
                ;; handled by the prior `cond` branch with the
                ;; `:no-clock-configured` advisory), so the guard is a no-op
                ;; pass-through; the machines-specific entry bookkeeping +
                ;; sub-change watcher install below stay here.
                (managed-timer/arm!
                  (fn []
                    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
                      ;; Stamp `:source :after-timer` on the timer-fired
                      ;; dispatch so the Epoch panel's DISPATCH step labels
                      ;; it "from :after timer", and Xray's L2 timeline can
                      ;; prefix the row + per-source filter pills can
                      ;; discriminate timer-fired cascades. `:after-timer`
                      ;; is the single functional-origin discriminator
                      ;; (closed-enum per Spec-Schemas
                      ;; §`:rf/dispatch-envelope`).
                      ;; Per Spec 005 §Hierarchy interaction: carry the
                      ;; scheduling node's decl-path (`invoke-id`) so the
                      ;; pure side routes the firing to the correct
                      ;; per-path epoch — colliding delay-keys across
                      ;; hierarchy levels (a parent and child both with
                      ;; `:after {30000 ...}`) resolve unambiguously.
                      (dispatch! [parent-id [:rf.machine.timer/after-elapsed
                                              delay-key epoch (vec invoke-id)]]
                                 {:frame              frame-id
                                  :source             :after-timer}))
                    ;; A one-shot `:after` timer fires exactly once per arming
                    ;; (XState v5 §delayed transitions). Reap THIS fire's entry
                    ;; — release the sub-reaction watcher + held subscription
                    ;; ref-count and drop the slot — epoch-guarded so a
                    ;; real-transition exit's re-armed successor (fresh entry,
                    ;; strictly-greater epoch) is not clobbered. Without this,
                    ;; a GUARD-SUPPRESSED fire (state does not exit, so no
                    ;; `:on-exit` cancel runs) would strand the entry AND let a
                    ;; later sub-vec value change re-arm a spent one-shot via
                    ;; `on-sub-changed!`. The reap is silent — a fired timer
                    ;; was not cancelled, so no `:cancelled` trace is emitted.
                    ;; Runs AFTER the dispatch returns: in dispatch-sync the
                    ;; transition (and its `:on-exit` cancel, if any) has
                    ;; already executed, so the epoch guard sees the settled
                    ;; slot; in async dispatch the guard still distinguishes
                    ;; this entry from any future re-arm by epoch.
                    (reap-fired-entry! frame-id k epoch))
                  resolved-ms)
                watch-key (when (= :sub delay-source)
                            [::after-watch frame-id parent-id invoke-id delay-key])]
            (when (and reaction watch-key)
              ;; Surface `add-watch` exceptions rather than silently
              ;; dropping them; the sub-changed re-resolution watcher won't
              ;; fire if `add-watch` failed, so the author needs a signal
              ;; that the dynamic-delay subscription is not actually wired
              ;; up.
              (try
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
            (swap! after-timers assoc-in [frame-id k]
                   {:handle          handle
                    :reaction        reaction
                    :sub-watcher-key watch-key
                    :resolved-ms     resolved-ms
                    :epoch           epoch
                    :state           state
                    :delay-source    delay-source})
            handle))))))

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
        invoke-id (vec (:rf/invoke-id args))]
    (doseq [[k _entry] (get @after-timers frame-id)
            :when (and (= parent-id (:parent k))
                       (= invoke-id (:spawn k)))]
      (cancel-after-timer-entry! frame-id k :on-exit))
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
  destroy event."
  [frame-id parent-id]
  (when (and frame-id parent-id)
    (doseq [[k _entry] (vec (get @after-timers frame-id))
            :when (= parent-id (:parent k))]
      (cancel-after-timer-entry! frame-id k :on-destroy))))

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
  without touching sibling frames' state."
  ([]
   (doseq [[frame-id inner] @after-timers
           [k entry] inner]
     (release-entry-resources! frame-id entry (:delay k)))
   (reset! after-timers {}))
  ([frame-id]
   (doseq [[k _entry] (vec (get @after-timers frame-id))]
     ;; Use the single-entry helper so each cancellation emits the
     ;; unified `:rf.machine.timer/cancelled` trace with the right
     ;; `:reason`. `vec`-snapshot the iteration so the swap inside
     ;; `cancel-after-timer-entry!` cannot trip a concurrent-
     ;; modification surprise on the JVM target.
     (cancel-after-timer-entry! frame-id k :on-frame-destroy))))

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
  the epoch restore boundary (`perform-restore!`) AFTER a successful install.
  No-op when the frame holds no in-flight timers. `vec`-snapshots the iteration
  so the swap inside `cancel-after-timer-entry!` cannot trip a concurrent-
  modification surprise on the JVM target."
  [frame-id]
  (doseq [[k _entry] (vec (get @after-timers frame-id))]
    (cancel-after-timer-entry! frame-id k :on-restore))
  nil)
