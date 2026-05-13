(ns re-frame.machines.timer
  "Wall-clock `:after` timer scheduling. Per Spec 005 §Delayed `:after`
  transitions and rf2-3y3y.

  On entry to an `:after`-bearing state node, the pure transition engine
  (`re-frame.machines.transition`) emits one `:rf.machine/after-schedule`
  fx per `:after` entry. The fx handler here resolves the delay (pos-int?
  literal / subscription vector / fn-form), schedules a real timer via
  `interop/set-timeout!`, and (for sub-vec delays) installs a watcher
  that triggers cancel-and-reschedule on sub-value change. On expiry the
  timer dispatches the synthetic

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch>]]

  back into the parent machine via the late-bound `:router/dispatch!`
  hook. Pick-after-transition (in transition) resolves the delay-key
  against the active state path's `:after` table; epoch-mismatch surfaces
  as `:rf.machine.timer/stale-after`, epoch-match drives the transition
  through the standard cascade.

  A per-frame timer table tracks live handles so cancellation (state
  exit) and subscription-driven re-resolution can clear them. The epoch
  mechanism backstops correctness; explicit cancellation via
  `:rf.machine/after-cancel` is an optimisation that promptly releases
  the host-clock handle.

  No-op under `:platform :server` (per Spec 005 §SSR mode); the pure side
  already emitted `:rf.machine.timer/skipped-on-server` in place of
  /scheduled."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.machines.transition :as transition]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.trace :as trace]))

(defonce after-timers
  ;; Per Spec 005 §Delayed `:after` transitions and rf2-3y3y. Runtime-owned
  ;; timer-handle table for in-flight :after timers. Keyed by
  ;; [frame-id parent-id invoke-id delay-key] so multiple delays per
  ;; :after map have their own slot. Value:
  ;;   {:handle <opaque host-clock handle>
  ;;    :reaction <subscription reaction or nil>
  ;;    :sub-watcher-key <key passed to add-watch>
  ;;    :resolved-ms <int>
  ;;    :epoch <int>
  ;;    :state <state-keyword>
  ;;    :delay-source <:literal | :sub | :fn>}
  ;; Cancellation (state exit + sub re-resolution) clears the entry and
  ;; releases the handle / detaches the watcher.
  (atom {}))

(defn- after-timer-key [frame-id parent-id invoke-id delay-key]
  [frame-id parent-id (vec invoke-id) delay-key])

(defn- classify-delay-source [delay-key]
  (cond
    (number? delay-key)  :literal
    (vector? delay-key)  :sub
    (fn? delay-key)      :fn
    :else                :literal))

(defn- resolve-delay-ms
  "Resolve an :after map key to a positive-integer ms delay. For pos-int?
  literal: returns the value. For subscription vector: subscribes via the
  late-bound subscribe-value hook and uses the resolved value. For fn:
  invokes (f snapshot) once.

  Returns [resolved-ms reaction-or-nil]. The reaction is non-nil only for
  subscription-vector delays; the caller installs an add-watch on it to
  trigger re-resolution."
  [frame-id delay-key snapshot]
  (cond
    (number? delay-key)
    [delay-key nil]

    (fn? delay-key)
    (let [v (try (delay-key snapshot)
                 (catch #?(:clj Throwable :cljs :default) _ nil))]
      [v nil])

    (vector? delay-key)
    ;; subscribe to keep the reaction live; caller will add-watch for
    ;; change-detection then unsubscribe on cancellation.
    (let [reaction (subs/subscribe frame-id delay-key)
          v        (when reaction
                     (try @reaction
                          (catch #?(:clj Throwable :cljs :default) _ nil)))]
      [v reaction])

    :else
    [nil nil]))

(declare schedule-after-timer!)

(defn- cancel-after-timer-entry!
  "Cancel and clear a single :after timer-table entry. Idempotent."
  [k]
  (when-let [entry (get @after-timers k)]
    (when-let [h (:handle entry)]
      (try (interop/clear-timeout! h)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (when (and (:reaction entry) (:sub-watcher-key entry))
      (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
           (catch #?(:clj Throwable :cljs :default) _ nil))
      (let [[frame-id _ _ delay-key] k]
        (when (and (vector? delay-key) frame-id)
          (try (subs/unsubscribe frame-id delay-key)
               (catch #?(:clj Throwable :cljs :default) _ nil)))))
    (swap! after-timers dissoc k)))

(defn- on-sub-changed!
  "Watch callback invoked when a subscription-vector delay's value
  changes. Per Spec 005 §Dynamic delay re-resolution: cancel the prior
  in-flight timer, emit :rf.machine.timer/cancelled-on-resolution, and
  reschedule a fresh timer at the new resolution time. Epoch is
  unchanged (the snapshot's :state hasn't moved); we read it back from
  the live snapshot at reschedule-time so a concurrent state change is
  caught by the epoch invariant when the new timer fires."
  [frame-id parent-id invoke-id delay-key state old-v new-v]
  (when-not (= old-v new-v)
    (let [k (after-timer-key frame-id parent-id invoke-id delay-key)
          prior-entry (get @after-timers k)
          prior-ms    (:resolved-ms prior-entry)]
      (trace/emit! :machine :rf.machine.timer/cancelled-on-resolution
                   {:machine-id parent-id
                    :state      state
                    :delay      prior-ms
                    :reason     :sub-changed
                    :sub-id     (first delay-key)})
      (cancel-after-timer-entry! k)
      (when-let [container (frame/get-frame-db frame-id)]
        (let [db   (adapter/read-container container)
              snap (get-in db [:rf/machines parent-id])
              ;; Per Spec 005 §Per-region :after scoping (rf2-l67o): for
              ;; parallel-region machines the snapshot's :state is a map
              ;; of region-name → that region's state, and the invoke-id
              ;; is `[<region-name> <state...>]` (prefix-region-invoke-id).
              ;; Resolve the active path inside the bearing region; the
              ;; epoch lives at the per-region epoch slot.
              parallel-snap? (and snap (map? (:state snap)))
              [in-region-invoke-id active epoch-slot]
              (if parallel-snap?
                (let [rn (first invoke-id)
                      iid-tail (vec (rest invoke-id))]
                  [iid-tail
                   (when-let [rs (get (:state snap) rn)] (transition/state-path rs))
                   [:data :rf/after-epoch-by-region rn]])
                [invoke-id (when snap (transition/state-path (:state snap)))
                 [:data :rf/after-epoch]])
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
  :rf.machine.timer/scheduled (or /skipped-on-server) trace is emitted by
  the pure-code side (apply-transition-once) at machine-transition time;
  this fn emits a fresh /scheduled (paired with :cancelled-on-resolution)
  only when called from a subscription-change watcher.

  Idempotent against the timer-table key — cancels any prior entry
  before installing the new one."
  [frame-id parent-id invoke-id state delay-key epoch server? snapshot
   {:keys [emit-scheduled-trace?]}]
  (let [delay-source (classify-delay-source delay-key)
        k            (after-timer-key frame-id parent-id invoke-id delay-key)]
    (cancel-after-timer-entry! k)
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
          (trace/emit! :machine :rf.warning/no-clock-configured
                       {:machine-id   parent-id
                        :state        state
                        :delay-key    delay-key
                        :delay-source delay-source
                        :recovery     :skipped})

          :else
          (let [_ (when emit-scheduled-trace?
                    (trace/emit! :machine :rf.machine.timer/scheduled
                                 (cond-> {:machine-id   parent-id
                                          :state        state
                                          :delay        resolved-ms
                                          :delay-source delay-source
                                          :epoch        epoch}
                                   (= :sub delay-source)
                                   (assoc :sub-id (first delay-key)))))
                handle
                (interop/set-timeout!
                  (fn []
                    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
                      (dispatch! [parent-id [:rf.machine.timer/after-elapsed
                                              delay-key epoch]]
                                 {:frame frame-id})))
                  resolved-ms)
                watch-key (when (= :sub delay-source)
                            [::after-watch frame-id parent-id invoke-id delay-key])]
            (when (and reaction watch-key)
              (try
                (add-watch reaction watch-key
                           (fn [_ _ old-v new-v]
                             (on-sub-changed! frame-id parent-id invoke-id
                                              delay-key state old-v new-v)))
                (catch #?(:clj Throwable :cljs :default) _ nil)))
            (swap! after-timers assoc k
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
  `:after` transitions and rf2-3y3y, on entry to an :after-bearing state
  node the runtime emits one of these per :after entry. The handler
  resolves the delay (literal pos-int? / subscription vector / fn),
  schedules a real wall-clock timer via `interop/set-timeout!`, and (for
  subscription delays) installs an add-watch that triggers
  cancel-and-reschedule on sub-value change.

  The synthetic event dispatched on expiry is

      [<parent-id> [:rf.machine.timer/after-elapsed <delay-key> <epoch>]]

  which routes through pick-after-transition's epoch check and (on match)
  through the standard transition cascade.

  No-op under `:platform :server` (per Spec 005 §SSR mode)."
  [{:keys [frame]} args]
  (let [frame-id   (or frame :rf/default)
        parent-id  (:rf/parent-id args)
        invoke-id  (:rf/invoke-id args)
        state      (:state args)
        delay-key  (:delay-key args)
        epoch      (:epoch args)
        server?    (boolean (:server? args))
        snapshot   (when-let [container (frame/get-frame-db frame-id)]
                     (get-in (adapter/read-container container)
                             [:rf/machines parent-id]))]
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
  "fx handler for `:rf.machine/after-cancel`. Per rf2-3y3y, emitted on
  exit from an :after-bearing state node to release the host-clock timer
  handles and any subscription watchers attached to the prior visit's
  timers. The epoch-mismatch invariant backstops correctness if a timer
  fires before this fx runs; this handler is the fast-path that prevents
  zombie watchers and releases timer slots promptly."
  [{:keys [frame]} args]
  (let [frame-id  (or frame :rf/default)
        parent-id (:rf/parent-id args)
        invoke-id (vec (:rf/invoke-id args))]
    (doseq [[k _entry] @after-timers
            :when (and (= frame-id  (nth k 0))
                       (= parent-id (nth k 1))
                       (= invoke-id (nth k 2)))]
      (cancel-after-timer-entry! k))
    nil))

(defn cancel-all-timers!
  "Cancel every in-flight :after timer the runtime is currently tracking
  and reset the timer table. Used by `reset-timers!` in fixture
  teardown."
  []
  (doseq [[_ entry] @after-timers]
    (when-let [h (:handle entry)]
      (try (interop/clear-timeout! h)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (when (and (:reaction entry) (:sub-watcher-key entry))
      (try (remove-watch (:reaction entry) (:sub-watcher-key entry))
           (catch #?(:clj Throwable :cljs :default) _ nil))))
  (reset! after-timers {}))
